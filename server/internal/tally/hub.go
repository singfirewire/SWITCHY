package tally

import (
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/switchy/server/internal/config"
)

// Snapshot = ภาพสถานะ ณ ขณะหนึ่ง ส่งออกทาง WebSocket / HTTP
type Snapshot struct {
	States map[string]State `json:"states"` // {"cam1":"program", ...}
	Inputs map[int]State    `json:"inputs"` // {1:"program", ...} (raw จาก vMix)
	Titles map[int]string   `json:"titles"` // {1:"Camera 1"} ชื่อ input จาก vMix HTTP API
	Seq    uint64           `json:"seq"`
	Source string           `json:"source"` // vmix | api | none
	At     time.Time        `json:"at"`
	VMixUp bool             `json:"vmixUp"`
}

type sub struct {
	ch     chan Snapshot
	done   chan struct{}
	closed atomic.Bool
}

// Hub เก็บสถานะปัจจุบันและกระจายให้ผู้ติดตามทั้งหมด
type Hub struct {
	mu      sync.RWMutex
	cameras []config.Camera
	inputs  map[int]State
	manual  map[string]State // ค่าที่ push ผ่าน HTTP API (ชนะเมื่อ priority สูงกว่า)
	titles  map[int]string
	seq     uint64
	source  string
	at      time.Time
	vmixUp  bool
	subs    map[int]*sub
	nextID  int
}

func NewHub(cameras []config.Camera) *Hub {
	return &Hub{
		cameras: append([]config.Camera(nil), cameras...),
		inputs:  map[int]State{},
		manual:  map[string]State{},
		titles:  map[int]string{},
		source:  "none",
		subs:    map[int]*sub{},
	}
}

func (h *Hub) SetCameras(cameras []config.Camera) {
	h.mu.Lock()
	h.cameras = append([]config.Camera(nil), cameras...)
	h.mu.Unlock()
	h.publish(nil)
}

func (h *Hub) Subscribe() (int, <-chan Snapshot) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.nextID++
	id := h.nextID
	s := &sub{ch: make(chan Snapshot, 8), done: make(chan struct{})}
	h.subs[id] = s
	// ส่งภาพปัจจุบันให้ทันที
	select {
	case s.ch <- h.snapshotLocked():
	default:
	}
	return id, s.ch
}

func (h *Hub) Unsubscribe(id int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s, ok := h.subs[id]; ok {
		delete(h.subs, id)
		// ไม่ปิด channel ตรง ๆ (จะชนกับ publish ที่กำลังส่งอยู่ = panic) ใช้ done แทน
		if s.closed.CompareAndSwap(false, true) {
			close(s.done)
		}
	}
}

func (h *Hub) Snapshot() Snapshot {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.snapshotLocked()
}

func (h *Hub) snapshotLocked() Snapshot {
	states := make(map[string]State, len(h.cameras))
	for _, cam := range h.cameras {
		st := StateUnknown
		if cam.VMixInput > 0 {
			if s, ok := h.inputs[cam.VMixInput]; ok {
				st = s
			} else if len(h.inputs) > 0 {
				st = StateSafe
			}
		}
		if m, ok := h.manual[cam.Code]; ok {
			st = Merge(st, m)
		}
		if st == StateUnknown && h.vmixUp {
			st = StateSafe
		}
		states[cam.Code] = st
	}
	inputs := make(map[int]State, len(h.inputs))
	for k, v := range h.inputs {
		inputs[k] = v
	}
	titles := make(map[int]string, len(h.titles))
	for k, v := range h.titles {
		titles[k] = v
	}
	return Snapshot{
		States: states,
		Inputs: inputs,
		Titles: titles,
		Seq:    h.seq,
		Source: h.source,
		At:     h.at,
		VMixUp: h.vmixUp,
	}
}

// SetVMixTally รับสถานะดิบจาก vMix (index 0 = input 1)
func (h *Hub) SetVMixTally(states []State) {
	h.mu.Lock()
	for i, s := range states {
		h.inputs[i+1] = s
	}
	// vMix มีอำนาจเหนือค่าที่ push มือ
	h.manual = map[string]State{}
	h.source = "vmix"
	h.vmixUp = true
	h.at = time.Now()
	h.mu.Unlock()
	h.publish(nil)
}

// SetVMixUp บอกว่าคอนเนคชันไป vMix ยังอยู่หรือไม่
func (h *Hub) SetVMixUp(up bool) {
	h.mu.Lock()
	if h.vmixUp == up {
		h.mu.Unlock()
		return
	}
	h.vmixUp = up
	if !up {
		h.source = "none"
		h.inputs = map[int]State{}
	}
	h.mu.Unlock()
	h.publish(nil)
}

func (h *Hub) SetTitles(titles map[int]string) {
	h.mu.Lock()
	changed := false
	for k, v := range titles {
		if h.titles[k] != v {
			h.titles[k] = v
			changed = true
		}
	}
	h.mu.Unlock()
	if changed {
		h.publish(nil)
	}
}

// PushOne ตั้งสถานะกล้องหนึ่งตัวจากภายนอก (HTTP API)
func (h *Hub) PushOne(code string, st State) bool {
	h.mu.Lock()
	known := false
	for _, cam := range h.cameras {
		if cam.Code == code {
			known = true
			break
		}
	}
	if !known {
		h.mu.Unlock()
		return false
	}
	h.manual[code] = st
	h.source = "api"
	h.at = time.Now()
	h.mu.Unlock()
	h.publish(nil)
	return true
}

// PushAll ตั้งสถานะหลายกล้องพร้อมกัน
func (h *Hub) PushAll(states map[string]State) []string {
	h.mu.Lock()
	var rejected []string
	for code, st := range states {
		known := false
		for _, cam := range h.cameras {
			if cam.Code == code {
				known = true
				break
			}
		}
		if !known {
			rejected = append(rejected, code)
			continue
		}
		h.manual[code] = st
	}
	sort.Strings(rejected)
	h.source = "api"
	h.at = time.Now()
	h.mu.Unlock()
	h.publish(nil)
	return rejected
}

// publish แจ้งเตือนผู้ติดตาม (ต้องเรียกหลังปลด lock เท่านั้น) แล้วออก seq ใหม่ถ้ามีการเปลี่ยน
func (h *Hub) publish(_ []string) {
	h.mu.Lock()
	h.seq++
	snap := h.snapshotLocked()
	subs := make([]*sub, 0, len(h.subs))
	for _, s := range h.subs {
		subs = append(subs, s)
	}
	h.mu.Unlock()

	for _, s := range subs {
		if s.closed.Load() {
			continue
		}
		select {
		case <-s.done:
		case s.ch <- snap:
		default: // ผู้ช้าถูกข้ามได้ ไม่ให้บล็อกทั้งระบบ
		}
	}
}

// Cameras คืนรายการกล้อง (copy)
func (h *Hub) Cameras() []config.Camera {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return append([]config.Camera(nil), h.cameras...)
}
