package wsserver

import (
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/switchy/server/internal/messages"
)

// Client = หนึ่งเครื่องที่เชื่อมต่อ (แอป Android / dashboard / สคริปต์)
type Client struct {
	id   int
	num  uint16
	srv  *Server
	conn *websocket.Conn
	ip   string
	send chan []byte

	deadline time.Duration

	ready atomic.Bool
	ptt   atomic.Bool
	// closed = true เมื่อไคลเอนต์ออกแล้ว — ใช้กันการส่งข้อความไปหาคนที่จากไปแล้ว
	closed atomic.Bool
	// done ถูกปิดเมื่อไคลเอนต์ออก (writePump เฝ้าอยู่) — ห้ามปิด channel send โดยตรง
	// เพราะจะชนกับ Broadcast ที่กำลังส่งข้อความ (panic: send on closed channel)
	done chan struct{}

	mu        sync.RWMutex
	name      string
	camera    string
	codec     string
	audioPort int
	lastPong  time.Time
	closeOnce sync.Once
}

func (c *Client) Ready() bool    { return c.ready.Load() }
func (c *Client) PTT() bool      { return c.ptt.Load() }
func (c *Client) SetPTT(v bool)  { c.ptt.Store(v) }
func (c *Client) Name() string   { c.mu.RLock(); defer c.mu.RUnlock(); return c.name }
func (c *Client) Camera() string { c.mu.RLock(); defer c.mu.RUnlock(); return c.camera }
func (c *Client) Codec() string  { c.mu.RLock(); defer c.mu.RUnlock(); return c.codec }
func (c *Client) AudioPort() int { c.mu.RLock(); defer c.mu.RUnlock(); return c.audioPort }

// readPump อ่านข้อความจากไคลเอนต์จนกว่าจะปิด
func (c *Client) readPump() {
	defer func() {
		c.srv.removeClient(c)
		c.close()
	}()
	c.conn.SetReadLimit(16 << 10)
	_ = c.conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	c.conn.SetPongHandler(func(string) error {
		c.mu.Lock()
		c.lastPong = time.Now()
		c.mu.Unlock()
		return c.conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	})

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		c.handle(data)
	}
}

// writePump ส่งข้อความ + ping ตามรอบ
func (c *Client) writePump() {
	ticker := time.NewTicker(20 * time.Second)
	defer func() {
		ticker.Stop()
		c.close()
	}()
	for {
		select {
		case <-c.done:
			_ = c.conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
			_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
			return
		case data := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(websocket.TextMessage, data); err != nil {
				return
			}
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// trySend คิวข้อความไว้ส่ง (ข้ามถ้าคิวเต็มหรือไคลเอนต์ออกไปแล้ว)
func (c *Client) trySend(data []byte) {
	if c.closed.Load() {
		return
	}
	select {
	case <-c.done: // ออกไปแล้ว — ไม่แตะ channel ต่อ
	case c.send <- data:
	default: // คิวเต็ม = ไคลเอนต์ช้า ทิ้งข้อความ tally เก่าได้
	}
}

func (c *Client) sendMsg(m ServerMsg) { c.trySend(mustJSON(m)) }

func (c *Client) close() {
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		close(c.done)
		_ = c.conn.Close()
	})
}

// handle ประมวลผลข้อความ JSON หนึ่งข้อความ
func (c *Client) handle(data []byte) {
	var msg ClientMsg
	if err := unmarshal(data, &msg); err != nil {
		c.sendMsg(ServerMsg{T: "error", Error: "JSON ไม่ถูกต้อง: " + err.Error()})
		return
	}

	switch msg.T {
	case "hello":
		c.handleHello(msg)
	case "ptt":
		c.handlePTT(msg.On)
	case "reply":
		c.handleReply(msg.Code, msg.MsgID)
	case "cam":
		c.handleCamera(msg.Camera)
	case "ping":
		c.sendMsg(ServerMsg{T: "pong", TS: msg.TS})
	case "bye":
		c.close()
	default:
		c.sendMsg(ServerMsg{T: "error", Error: "ไม่รู้จักคำสั่ง: " + msg.T})
	}
}

func (c *Client) handleHello(msg ClientMsg) {
	cfg := c.srv.cfg
	if cfg.AuthToken != "" && msg.Token != cfg.AuthToken {
		c.sendMsg(ServerMsg{T: "error", Error: "token ไม่ถูกต้อง"})
		c.close()
		return
	}
	if msg.Proto != 0 && msg.Proto != ProtoVersion {
		c.sendMsg(ServerMsg{T: "error", Error: "โปรโตคอลไม่ตรงกัน (เซิร์ฟเวอร์=" + itoa(ProtoVersion) + ")"})
		c.close()
		return
	}

	name := strings.TrimSpace(msg.Name)
	if name == "" {
		name = "client-" + itoa(int(c.num))
	}
	camera := strings.ToLower(strings.TrimSpace(msg.Camera))
	if camera == "" {
		camera = cfg.CameraCodes()[0]
	}
	if _, ok := cfg.Camera(camera); !ok {
		c.sendMsg(ServerMsg{T: "error", Error: "ไม่รู้จักรหัสกล้อง: " + camera})
		return
	}
	codec := strings.ToLower(strings.TrimSpace(msg.AudioCodec))
	if codec != "opus" {
		codec = "pcm"
	}

	c.mu.Lock()
	c.name = name
	c.camera = camera
	c.codec = codec
	c.audioPort = msg.AudioPort
	c.lastPong = time.Now()
	c.mu.Unlock()

	c.srv.reg.Register(c.num, name, codec, c.ip)
	c.ready.Store(true)

	// ให้เครื่องที่เพิ่งเข้าห้องเห็นข้อความล่าสุดทันที
	var recentMsgs []messages.Message
	var recentReplies []messages.Reply
	if c.srv.msgs != nil {
		recentMsgs, recentReplies = c.srv.msgs.Snapshot(0, 0)
	}
	if len(recentMsgs) > 20 {
		recentMsgs = recentMsgs[len(recentMsgs)-20:]
	}
	if len(recentReplies) > 20 {
		recentReplies = recentReplies[len(recentReplies)-20:]
	}

	// บอกค่าที่ต้องใช้จริงกลับไป (clientNum, พอร์ตเสียง, frame)
	c.sendMsg(ServerMsg{
		T:         "welcome",
		Proto:     ProtoVersion,
		ClientID:  c.id,
		ClientNum: c.num,
		Server:    cfg.ServerName,
		Ver:       c.srv.version,
		AudioAddr: cfg.AudioAdvertise,
		FrameMS:   cfg.Audio.FrameMS,
		SampleRat: cfg.Audio.SampleRate,
		FullDuplx: cfg.Audio.FullDuplex,
		Camera:    camera,
		Tally:     snapshotPayload(c.srv.hub.Snapshot()),
		Peers:     c.srv.Peers(),
		Messages:  recentMsgs,
		Replies:   recentReplies,
	})
	c.srv.log.Info("ไคลเอนต์ลงทะเบียนแล้ว",
		"num", c.num, "name", name, "camera", camera, "codec", codec, "audioPort", msg.AudioPort)
	c.srv.BroadcastPeers()
}

func (c *Client) handlePTT(on bool) {
	if !c.ready.Load() {
		c.sendMsg(ServerMsg{T: "error", Error: "ต้องส่ง hello ก่อน"})
		return
	}
	if c.srv.cfg.Audio.FullDuplex {
		// โหมด full-duplex ไม่ต้องใช้ PTT (ไมค์เปิดตลอด)
		return
	}
	c.SetPTT(on)
	talkers := c.srv.talkersList()
	c.srv.Broadcast(ServerMsg{T: "audio", Audio: &AudioGameState{Talkers: talkers}})
	c.srv.BroadcastPeers()
	c.srv.syncRoIP(on)
	c.srv.log.Debug("PTT", "num", c.num, "on", on, "talkers", len(talkers))
}

// handleReply บันทึกการตอบกลับด้วยปุ่มสำเร็จรูปของเครื่องลูก แล้วกระจายให้ทุกเครื่องเห็น
func (c *Client) handleReply(code string, msgID int64) {
	if !c.ready.Load() {
		c.sendMsg(ServerMsg{T: "error", Error: "ต้องส่ง hello ก่อน"})
		return
	}
	if c.srv.msgs == nil {
		return
	}
	r, ok := c.srv.msgs.AddReply(msgID, c.num, c.Name(), code)
	if !ok {
		c.sendMsg(ServerMsg{T: "error", Error: "รหัสตอบกลับไม่ถูกต้อง (ใช้ ack / disagree / help)"})
		return
	}
	c.srv.Broadcast(ServerMsg{T: "reply", Reply: &r})
	c.srv.log.Info("ตอบกลับข้อความ", "num", c.num, "name", c.Name(), "code", r.Code, "label", r.Label)
}

func (c *Client) handleCamera(code string) {
	code = strings.ToLower(strings.TrimSpace(code))
	if code == "" {
		return
	}
	if _, ok := c.srv.cfg.Camera(code); !ok {
		c.sendMsg(ServerMsg{T: "error", Error: "ไม่รู้จักรหัสกล้อง: " + code})
		return
	}
	c.mu.Lock()
	c.camera = code
	c.mu.Unlock()
	c.srv.BroadcastPeers()
	c.sendMsg(ServerMsg{T: "cam", Camera: code})
}
