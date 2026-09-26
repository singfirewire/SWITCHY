package audioengine

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"sync"
	"time"
)

// Sink = ปลายทางพิเศษที่รับสำเนาเสียงทุกแพ็กเก็ต (เช่นบริดจ์ RoIP ไปวิทยุจริง)
type Sink interface {
	Name() string
	WriteAudio(from uint16, pcm []byte, opus bool) error
}

// Relay = UDP relay สำหรับเสียง Intercom
type Relay struct {
	reg      *Registry
	log      *slog.Logger
	frameMS  int
	fullDup  bool
	maxTalk  int
	conn     *net.UDPConn
	mu       sync.RWMutex
	sinks    []Sink
	stats    RelayStats
	onTalker func(talkers []uint16)
}

type RelayStats struct {
	RxPackets   uint64
	TxPackets   uint64
	RxBytes     uint64
	BadPackets  uint64
	DeniedCount uint64
	StartedAt   time.Time
}

func NewRelay(reg *Registry, log *slog.Logger, frameMS int, fullDuplex bool, maxTalkers int) *Relay {
	return &Relay{
		reg:     reg,
		log:     log.With("mod", "audio-relay"),
		frameMS: frameMS,
		fullDup: fullDuplex,
		maxTalk: maxTalkers,
	}
}

// OnTalkerChange ตั้ง callback เพื่อแจ้ง WS ว่าใครกำลังพูด (ไว้โชว์บนจอ)
func (r *Relay) OnTalkerChange(fn func([]uint16)) { r.onTalker = fn }

func (r *Relay) AddSink(s Sink) {
	r.mu.Lock()
	r.sinks = append(r.sinks, s)
	r.mu.Unlock()
	r.log.Info("เพิ่มปลายทางเสียงพิเศษ", "name", s.Name())
}

// Listen เปิดพอร์ต UDP แล้ววนรับแพ็กเก็ตจนกว่า ctx จะถูกยกเลิก
func (r *Relay) Listen(ctx context.Context, addr string) error {
	ua, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp", ua)
	if err != nil {
		return fmt.Errorf("เปิด UDP %s ไม่ได้: %w", addr, err)
	}
	// buffer รับใหญ่พอสำหรับ burst หลายสายพร้อมกัน
	_ = conn.SetReadBuffer(1 << 20)
	_ = conn.SetWriteBuffer(1 << 20)
	r.conn = conn
	r.stats.StartedAt = time.Now()
	r.log.Info("UDP audio relay พร้อม", "addr", conn.LocalAddr().String(), "frameMs", r.frameMS)

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	buf := make([]byte, 2048)
	for {
		n, raddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			r.log.Warn("อ่าน UDP ผิดพลาด", "err", err.Error())
			continue
		}
		r.handlePacket(buf[:n], raddr)
	}
}

// LocalAddr คืนที่อยู่ที่ผูกอยู่จริง (ใช้บอกไคลเอนต์)
func (r *Relay) LocalAddr() *net.UDPAddr {
	if r.conn == nil {
		return nil
	}
	ua, _ := r.conn.LocalAddr().(*net.UDPAddr)
	return ua
}

func (r *Relay) Stats() RelayStats {
	return r.stats
}

func (r *Relay) close() {
	if r.conn != nil {
		_ = r.conn.Close()
	}
}

func (r *Relay) handlePacket(b []byte, from *net.UDPAddr) {
	h, err := DecodeHeader(b)
	if err != nil {
		r.stats.BadPackets++
		return
	}
	payloadLen := int(h.Payload)
	if HeaderSize+payloadLen > len(b) {
		r.stats.BadPackets++
		return
	}
	payload := b[HeaderSize : HeaderSize+payloadLen]

	ip := from.IP.String()
	ep, ok := r.reg.LearnAddr(h.Client, from.String(), ip)
	if !ok {
		r.stats.DeniedCount++
		// ไม่ได้ลงทะเบียนผ่าน WS -> ทิ้ง (กันคนนอกยิงเสียงเข้าห้อง)
		return
	}
	r.stats.RxPackets++
	r.stats.RxBytes += uint64(len(b))

	talking := h.Flags&FlagTalk != 0
	if ep.Talking() != talking {
		ep.SetTalking(talking)
		if r.onTalker != nil {
			r.onTalker(r.reg.Talkers())
		}
	}

	// เสียงจะถูก relay เฉพาะตอนผู้พูดกด PTT (หรือโหมด full-duplex)
	if !talking && !r.fullDup {
		return
	}
	if talking && !r.fullDup && len(r.reg.Talkers()) > r.maxTalk {
		ep.lost.Add(1)
		return
	}

	r.forward(h, payload, ep, from)
}

func (r *Relay) forward(h Header, payload []byte, ep *Endpoint, from *net.UDPAddr) {
	out := make([]byte, HeaderSize+len(payload))
	out[3] = h.Flags
	copy(out[4:], []byte{byte(h.Client), byte(h.Client >> 8)})
	copy(out[6:], []byte{byte(h.Seq), byte(h.Seq >> 8)})
	copy(out[8:], []byte{byte(h.TSms), byte(h.TSms >> 8), byte(h.TSms >> 16), byte(h.TSms >> 24)})
	copy(out[12:], []byte{byte(len(payload)), byte(len(payload) >> 8)})
	copy(out[HeaderSize:], payload)

	r.mu.RLock()
	targets := r.reg.Others(ep.ClientNum)
	sinks := append([]Sink(nil), r.sinks...)
	r.mu.RUnlock()

	for _, dst := range targets {
		ua, err := net.ResolveUDPAddr("udp", dst.Addr)
		if err != nil {
			continue
		}
		if _, err := r.conn.WriteToUDP(out, ua); err == nil {
			r.stats.TxPackets++
		}
	}
	// ส่งสำเนาให้บริดจ์พิเศษ (RoIP)
	isOpus := h.Flags&FlagOpus != 0
	for _, s := range sinks {
		if err := s.WriteAudio(ep.ClientNum, payload, isOpus); err != nil {
			r.log.Debug("ส่งเข้า sink ไม่ได้", "sink", s.Name(), "err", err.Error())
		}
	}
	if len(targets) == 0 && len(sinks) == 0 {
		ep.lost.Add(1)
	}
}

// InjectAudio ยิงเสียงจากภายนอก (วิทยุ/RoIP) เข้าห้องเสมือนเป็นไคลเอนต์หนึ่งเครื่อง
func (r *Relay) InjectAudio(clientNum uint16, opus bool, payload []byte) int {
	if r.conn == nil || len(payload) == 0 {
		return 0
	}
	flags := FlagTalk
	if opus {
		flags |= FlagOpus
	}
	h := Header{Flags: flags, Client: clientNum, TSms: uint32(time.Since(r.stats.StartedAt).Milliseconds())}
	pkt := EncodePacket(h, payload)
	n := 0
	for _, dst := range r.reg.Others(clientNum) {
		ua, err := net.ResolveUDPAddr("udp", dst.Addr)
		if err != nil {
			continue
		}
		if _, err := r.conn.WriteToUDP(pkt, ua); err == nil {
			n++
			r.stats.TxPackets++
		}
	}
	return n
}

// SendTo ยิงแพ็กเก็ตตรงไปยังไคลเอนต์รายเครื่อง (ใช้ทดสอบ/แจ้งเตือน)
func (r *Relay) SendTo(clientNum uint16, pkt []byte) error {
	ep, ok := r.reg.Get(clientNum)
	if !ok || ep.Addr == "" {
		return fmt.Errorf("ไม่รู้ปลายทางของ client %d", clientNum)
	}
	ua, err := net.ResolveUDPAddr("udp", ep.Addr)
	if err != nil {
		return err
	}
	_, err = r.conn.WriteToUDP(pkt, ua)
	return err
}

func itoaPort(addr string) int {
	_, p, err := net.SplitHostPort(addr)
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(p)
	return n
}
