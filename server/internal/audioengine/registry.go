package audioengine

import (
	"log/slog"
	"math"
	"sync"
	"sync/atomic"
	"time"
)

// Endpoint = ปลายทาง UDP ของไคลเอนต์หนึ่งเครื่อง
type Endpoint struct {
	ClientNum uint16
	Name      string
	Codec     string
	Addr      string // "ip:port"
	IP        string

	lastSeen atomic.Int64 // unix nano
	talking  atomic.Bool
	pkts     atomic.Uint64
	lost     atomic.Uint64
	denied   atomic.Uint64
	lastSeq  uint16
	haveSeq  bool
}

func (e *Endpoint) Touch()              { e.lastSeen.Store(time.Now().UnixNano()) }
func (e *Endpoint) LastSeen() time.Time { return time.Unix(0, e.lastSeen.Load()) }
func (e *Endpoint) Talking() bool       { return e.talking.Load() }
func (e *Endpoint) SetTalking(v bool)   { e.talking.Store(v) }

// Registry เก็บรายชื่อไคลเอนต์ที่ได้รับอนุญาต (ลงทะเบียนผ่าน WebSocket แล้ว)
type Registry struct {
	mu       sync.RWMutex
	byNum    map[uint16]*Endpoint
	log      *slog.Logger
	timeout  time.Duration
	strictIP bool
}

func NewRegistry(log *slog.Logger, timeout time.Duration, strictIP bool) *Registry {
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	return &Registry{
		byNum:    map[uint16]*Endpoint{},
		log:      log.With("mod", "audio-registry"),
		timeout:  timeout,
		strictIP: strictIP,
	}
}

// Register เพิ่ม/อัปเดตไคลเอนต์ (เรียกจาก WS ตอน hello)
func (r *Registry) Register(num uint16, name, codec, ip string) *Endpoint {
	r.mu.Lock()
	defer r.mu.Unlock()
	ep, ok := r.byNum[num]
	if !ok {
		ep = &Endpoint{ClientNum: num}
		r.byNum[num] = ep
	}
	ep.Name = name
	ep.Codec = codec
	ep.IP = ip
	ep.Touch()
	return ep
}

func (r *Registry) Unregister(num uint16) {
	r.mu.Lock()
	delete(r.byNum, num)
	r.mu.Unlock()
}

func (r *Registry) Get(num uint16) (*Endpoint, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	ep, ok := r.byNum[num]
	return ep, ok
}

// LearnAddr บันทึก ip:port จริงที่ไคลเอนต์ส่งมาจาก
//
// ค่าเริ่มต้น (strictIP = true): ต้องมาจาก IP เดียวกับที่เข้า WS ไม่งั้นทิ้ง
// กันคนอื่นในวงยิงเสียงปลอมเข้ามาโดยอ้าง clientNum ของคนอื่น
// ถ้าปิดการตรวจ (audio.disableIpCheck = true) จะยอมเรียนรู้ IP ใหม่ให้
// (มีประโยชน์ตอนมือถือสลับ Wi-Fi / hotspot แล้ว IP เปลี่ยน)
func (r *Registry) LearnAddr(num uint16, addr, ip string) (*Endpoint, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ep, ok := r.byNum[num]
	if !ok {
		return nil, false
	}
	if ep.IP != "" && ip != ep.IP {
		n := ep.denied.Add(1)
		if r.strictIP {
			if n == 1 || n%200 == 0 {
				r.log.Warn("ปฏิเสธ UDP: IP ไม่ตรงกับที่ลงทะเบียนผ่าน WS",
					"client", num, "ws_ip", ep.IP, "udp_ip", ip, "count", n)
			}
			return nil, false
		}
		if n == 1 {
			r.log.Warn("ไคลเอนต์เปลี่ยน IP (ยอมรับเพราะปิดการตรวจ IP)",
				"client", num, "from", ep.IP, "to", ip)
		}
		ep.IP = ip
	}
	if ep.Addr != addr {
		r.log.Info("เรียนรู้ปลายทางเสียง", "client", num, "addr", addr)
		ep.Addr = addr
	}
	ep.Touch()
	return ep, true
}

// Others คืนปลายทางของทุกคนยกเว้นผู้ส่ง และที่ยัง active
func (r *Registry) Others(exclude uint16) []*Endpoint {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*Endpoint, 0, len(r.byNum))
	now := time.Now()
	for num, ep := range r.byNum {
		if num == exclude {
			continue
		}
		if ep.Addr == "" {
			continue
		}
		if now.Sub(ep.LastSeen()) > r.timeout {
			continue
		}
		out = append(out, ep)
	}
	return out
}

func (r *Registry) List() []*Endpoint {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*Endpoint, 0, len(r.byNum))
	for _, ep := range r.byNum {
		out = append(out, ep)
	}
	return out
}

func (r *Registry) Talkers() []uint16 {
	out := []uint16{}
	for _, ep := range r.List() {
		if ep.Talking() && ep.Addr != "" {
			out = append(out, ep.ClientNum)
		}
	}
	return out
}

func (r *Registry) Stats(num uint16) (pkts, lost uint64, jitterMs float64) {
	ep, ok := r.Get(num)
	if !ok {
		return 0, 0, 0
	}
	return ep.pkts.Load(), ep.lost.Load(), 0
}

// --- ตัวช่วยวัดระดับเสียง (RMS) ใช้ทำ VOX / โชว์มิเตอร์ ---

// RMSdBFS คำนวณระดับเสียงจาก PCM 16-bit little-endian คืนค่าเป็น dBFS (ติดลบ, 0 = เต็มสเกล)
func RMSdBFS(pcm []byte) float64 {
	if len(pcm) < 2 {
		return -120
	}
	var sum float64
	n := 0
	for i := 0; i+1 < len(pcm); i += 2 {
		s := float64(int16(uint16(pcm[i]) | uint16(pcm[i+1])<<8))
		sum += s * s
		n++
	}
	if n == 0 {
		return -120
	}
	rms := math.Sqrt(sum / float64(n))
	if rms < 1 {
		return -120
	}
	return 20 * math.Log10(rms/32768.0)
}
