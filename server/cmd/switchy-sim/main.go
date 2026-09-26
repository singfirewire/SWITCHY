// switchy-sim = มือถือจำลองสำหรับทดสอบ SWITCHY โดยไม่ต้องมีโทรศัพท์
//
// จำลอง N เครื่อง: ต่อ WebSocket, ส่ง hello, รับ tally, ส่งเสียง UDP แบบกด PTT สลับกัน
// แล้วรายงานผลว่าการกระจายเสียง + การกันเสียง (gate) ทำงานจริงหรือไม่
//
// ตัวอย่าง:
//
//	go run ./cmd/switchy-sim -ws 127.0.0.1:8090 -clients 2 -seconds 14
package main

import (
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type clientMsg struct {
	T         string `json:"t"`
	Proto     int    `json:"proto,omitempty"`
	Name      string `json:"name,omitempty"`
	Camera    string `json:"camera,omitempty"`
	AudioPort int    `json:"audioPort,omitempty"`
	Codec     string `json:"codec,omitempty"`
	On        bool   `json:"on,omitempty"`
	TS        int64  `json:"ts,omitempty"`
}

type serverMsg struct {
	T         string `json:"t"`
	Proto     int    `json:"proto,omitempty"`
	ClientNum uint16 `json:"clientNum,omitempty"`
	Server    string `json:"server,omitempty"`
	Ver       string `json:"ver,omitempty"`
	AudioAddr string `json:"audioAddr,omitempty"`
	FrameMS   int    `json:"frameMs,omitempty"`
	SampleRat int    `json:"sampleRate,omitempty"`
	Camera    string `json:"camera,omitempty"`
	Error     string `json:"error,omitempty"`
	Tally     *struct {
		States map[string]string `json:"states"`
		Seq    uint64            `json:"seq"`
		Source string            `json:"source"`
		VMixUp bool              `json:"vmixUp"`
	} `json:"tally,omitempty"`
	Peers []struct {
		Num    uint16 `json:"num"`
		Name   string `json:"name"`
		Camera string `json:"camera"`
		PTT    bool   `json:"ptt"`
	} `json:"peers,omitempty"`
	Audio *struct {
		Talkers []uint16 `json:"talkers"`
	} `json:"audio,omitempty"`
	TS int64 `json:"ts,omitempty"`
}

type sim struct {
	name     string
	cameras  []string
	idx      int // ลำดับเครื่อง (0-based) ใช้ก่อนได้ clientNum
	num      uint16
	conn     *websocket.Conn
	udp      *net.UDPConn
	audioDst *net.UDPAddr
	frameMS  int
	rate     int

	seq      uint16
	sent     atomic.Uint64
	rxOther  atomic.Uint64 // ได้รับเสียงขณะ "คนอื่น" พูด  -> ควรมากกว่า 0
	rxGated  atomic.Uint64 // ได้รับเสียงขณะ "คนอื่น" เงียบ -> ควรเป็น 0
	rxDuring atomic.Uint64
	lastSeq  uint16
	lost     atomic.Uint64

	tallyMu sync.Mutex
	tally   map[string]string
}

func (s *sim) pttOn(elapsed time.Duration) bool {
	slot := int(elapsed.Seconds()) % 4
	return slot == 2*(s.idx%2)
}

func otherTalking(elapsed time.Duration, myIdx int) bool {
	slot := int(elapsed.Seconds()) % 4
	other := 1 - myIdx
	return slot == 2*other
}

func (s *sim) run(wsURL string, start time.Time, dur time.Duration) error {
	u, err := url.Parse(wsURL)
	if err != nil {
		return err
	}
	conn, _, err := websocket.DefaultDialer.Dial(u.String(), http.Header{})
	if err != nil {
		return fmt.Errorf("%s: ต่อ WS ไม่ได้: %w", s.name, err)
	}
	s.conn = conn

	hello := clientMsg{T: "hello", Proto: 1, Name: s.name, Camera: s.cameras[s.idx%len(s.cameras)], Codec: "pcm"}
	if err := conn.WriteJSON(hello); err != nil {
		return err
	}

	welcome := make(chan serverMsg, 1)
	go func() {
		for {
			var m serverMsg
			if err := conn.ReadJSON(&m); err != nil {
				return
			}
			switch m.T {
			case "welcome":
				select {
				case welcome <- m:
				default:
				}
			case "tally":
				s.printTallyChange(m)
			case "error":
				log.Printf("[%s] server error: %s", s.name, m.Error)
			}
		}
	}()

	var wm serverMsg
	select {
	case wm = <-welcome:
	case <-time.After(4 * time.Second):
		return fmt.Errorf("%s: ไม่ได้ welcome จากเซิร์ฟเวอร์", s.name)
	}
	s.num = wm.ClientNum
	s.frameMS = wm.FrameMS
	s.rate = wm.SampleRat
	if s.frameMS == 0 {
		s.frameMS = 20
	}
	if s.rate == 0 {
		s.rate = 48000
	}
	fmt.Printf("[%s] welcome: num=%d server=%q audio=%s frame=%dms %dHz codec=pcm\n",
		s.name, s.num, wm.Server, wm.AudioAddr, s.frameMS, s.rate)

	// เตรียม UDP
	if err := s.openUDP(wm.AudioAddr, u.Host); err != nil {
		return err
	}
	go s.rxLoop()

	// ส่งเสียงตามคิว PTT
	ticker := time.NewTicker(time.Duration(s.frameMS) * time.Millisecond)
	defer ticker.Stop()
	done := time.After(dur)

	samples := s.rate * s.frameMS / 1000
	payload := make([]byte, samples*2)
	phase := 0.0
	step := 2 * math.Pi * float64(300+int(s.num)*170) / float64(s.rate)

	lastPTT := false
	for {
		select {
		case <-done:
			return nil
		case <-ticker.C:
			elapsed := time.Since(start)
			on := s.pttOn(elapsed)
			if on != lastPTT {
				lastPTT = on
				_ = s.conn.WriteJSON(clientMsg{T: "ptt", On: on})
				fmt.Printf("[%s] PTT %v (t=%.1fs)\n", s.name, on, elapsed.Seconds())
			}
			for i := 0; i < samples; i++ {
				v := int16(math.Sin(phase) * 8000)
				binary.LittleEndian.PutUint16(payload[i*2:], uint16(v))
				phase += step
			}
			flags := byte(0)
			if on {
				flags |= 1 // FlagTalk
			}
			pkt := encodePacket(flags, s.num, s.seq, uint32(elapsed.Milliseconds()), payload)
			s.seq++
			if _, err := s.udp.WriteToUDP(pkt, s.audioDst); err == nil {
				s.sent.Add(1)
			}
		}
	}
}

func (s *sim) openUDP(audioAddr, wsHost string) error {
	host, port, err := net.SplitHostPort(audioAddr)
	if err != nil {
		return fmt.Errorf("audioAddr ไม่ถูกต้อง: %q", audioAddr)
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		// เซิร์ฟเวอร์บอกแค่พอร์ต -> ใช้ host เดียวกับ WS แต่คงพอร์ตเสียงไว้
		if wh, _, e := net.SplitHostPort(wsHost); e == nil && wh != "" && wh != "localhost" {
			host = wh
		} else {
			host = "127.0.0.1"
		}
	}
	dst, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, port))
	if err != nil {
		return err
	}
	// ผูก socket ให้เป็นตระกูลเดียวกับปลายทาง ไม่งั้น Windows อาจเลือก source IP ผิด
	// (ทำให้เซิร์ฟเวอร์เห็น IP ไม่ตรงกับที่ลงทะเบียนผ่าน WebSocket แล้วปฏิเสธแพ็กเก็ต)
	network := "udp"
	laddr := &net.UDPAddr{IP: net.IPv6unspecified, Port: 0}
	if dst.IP.To4() != nil {
		network = "udp4"
		laddr = &net.UDPAddr{IP: net.IPv4zero, Port: 0}
	}
	conn, err := net.ListenUDP(network, laddr)
	if err != nil {
		return err
	}
	s.udp = conn
	s.audioDst = dst
	fmt.Printf("[%s] UDP local=%s -> server=%s\n", s.name, conn.LocalAddr(), dst)
	return nil
}

func (s *sim) rxLoop() {
	buf := make([]byte, 2048)
	myIdx := s.idx % 2
	for {
		n, _, err := s.udp.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 16 {
			continue
		}
		flags := buf[3]
		if flags&1 == 0 {
			// เซิร์ฟเวอร์ไม่ควรส่งต่อแพ็กเก็ตที่ไม่ใช่เสียงพูด
			s.rxGated.Add(1)
			continue
		}
		from := binary.LittleEndian.Uint16(buf[4:])
		if from == s.num {
			continue
		}
		s.rxOther.Add(1)
		if otherTalking(time.Since(testStart), myIdx) {
			s.rxDuring.Add(1)
		}
		// นับช่องว่างของ seq — ปกติต้องมี เพราะแพ็กเก็ตที่ไม่ได้กด PTT ถูกเซิร์ฟเวอร์กันไว้
		seq := binary.LittleEndian.Uint16(buf[6:])
		if s.lastSeq != 0 && seq != s.lastSeq+1 && seq > s.lastSeq {
			s.lost.Add(uint64(seq - s.lastSeq - 1))
		}
		s.lastSeq = seq
	}
}

// testStart = เวลาเริ่มทดสอบ (ใช้จัดคิว PTT ให้ทุกเครื่องตรงกัน)
var testStart time.Time

func (s *sim) printTallyChange(m serverMsg) {
	if m.Tally == nil {
		return
	}
	txt := ""
	for _, cam := range []string{"cam1", "cam2", "cam3"} {
		if st, ok := m.Tally.States[cam]; ok {
			txt += fmt.Sprintf("%s=%s ", cam, st)
		}
	}
	fmt.Printf("[%s] TALLY %s(source=%s seq=%d vmix=%v)\n", s.name, txt, m.Tally.Source, m.Tally.Seq, m.Tally.VMixUp)
}

// ---- UDP packet (ต้องตรงกับ internal/audioengine/packet.go) ----

func encodePacket(flags byte, client uint16, seq uint16, ts uint32, payload []byte) []byte {
	out := make([]byte, 16+len(payload))
	out[0], out[1], out[2] = 'S', 'W', 1
	out[3] = flags
	binary.LittleEndian.PutUint16(out[4:], client)
	binary.LittleEndian.PutUint16(out[6:], seq)
	binary.LittleEndian.PutUint32(out[8:], ts)
	binary.LittleEndian.PutUint16(out[12:], uint16(len(payload)))
	copy(out[16:], payload)
	return out
}

func main() {
	wsHost := flag.String("ws", "127.0.0.1:8090", "host:port ของเซิร์ฟเวอร์ SWITCHY")
	clients := flag.Int("clients", 2, "จำนวนเครื่องที่จำลอง")
	seconds := flag.Int("seconds", 14, "ระยะเวลาทดสอบ (วินาที)")
	prefix := flag.String("prefix", "sim", "คำนำหน้าชื่อเครื่อง")
	flag.Parse()

	wsURL := "ws://" + *wsHost + "/ws"
	cameras := []string{"cam1", "cam2", "cam3"}

	fmt.Printf("=== SWITCHY simulator: %d clients, %ds -> %s ===\n", *clients, *seconds, wsURL)
	testStart = time.Now()
	start := testStart
	var wg sync.WaitGroup
	sims := make([]*sim, 0, *clients)

	for i := 0; i < *clients; i++ {
		s := &sim{
			name:    fmt.Sprintf("%s-%d", *prefix, i+1),
			cameras: cameras,
			idx:     i,
		}
		sims = append(sims, s)
		wg.Add(1)
		go func(s *sim) {
			defer wg.Done()
			if err := s.run(wsURL, start, time.Duration(*seconds)*time.Second); err != nil {
				log.Printf("[%s] %v", s.name, err)
			}
			_ = s.conn.WriteJSON(clientMsg{T: "bye"})
			_ = s.conn.Close()
			_ = s.udp.Close()
		}(s)
	}
	wg.Wait()

	fmt.Println("\n=== สรุปผล ===")
	ok := true
	for _, s := range sims {
		fmt.Printf("%-8s sent=%-5d rxFromOther=%-5d rxWhileOtherTalk=%-5d rxWhileOtherSilent(gate)=%-4d seqGap=%d (ปกติ: แพ็กเก็ตที่ไม่กด PTT ถูกกันไว้)\n",
			s.name, s.sent.Load(), s.rxOther.Load(), s.rxDuring.Load(), s.rxGated.Load(), s.lost.Load())
		if s.rxOther.Load() == 0 {
			fmt.Printf("  ❌ %s ไม่ได้รับเสียงจากเครื่องอื่นเลย (relay ไม่ทำงาน?)\n", s.name)
			ok = false
		}
		if s.rxGated.Load() > 0 {
			fmt.Printf("  ❌ %s ได้รับแพ็กเก็ตที่ไม่ใช่ PTT (%d) — gate ทำงานผิด\n", s.name, s.rxGated.Load())
			ok = false
		}
	}

	// ตรวจ /healthz
	resp, err := http.Get("http://" + *wsHost + "/healthz")
	if err == nil {
		var body map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&body)
		resp.Body.Close()
		raw, _ := json.Marshal(body)
		fmt.Println("healthz:", string(raw))
	}

	if ok {
		fmt.Println("✅ ผลทดสอบ: tally + audio relay + PTT gate ทำงานถูกต้อง")
		os.Exit(0)
	}
	fmt.Println("⚠️  ผลทดสอบ: มีบางอย่างไม่ถูกต้อง (ดูบรรทัด ❌ ด้านบน)")
	os.Exit(1)
}
