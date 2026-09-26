package wsserver

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/switchy/server/internal/audioengine"
	"github.com/switchy/server/internal/config"
	"github.com/switchy/server/internal/messages"
	"github.com/switchy/server/internal/roip"
	"github.com/switchy/server/internal/tally"
)

// Deps = ของที่ server ต้องใช้ (ฉีดเข้ามาเพื่อให้ทดสอบง่าย)
type Deps struct {
	Cfg      config.Config
	Hub      *tally.Hub
	Registry *audioengine.Registry
	Relay    *audioengine.Relay
	RoIP     *roip.Gateway
	Messages *messages.Bus
	Log      *slog.Logger
	Version  string
}

// Server = WebSocket gateway
type Server struct {
	cfg     config.Config
	hub     *tally.Hub
	reg     *audioengine.Registry
	relay   *audioengine.Relay
	roip    *roip.Gateway
	msgs    *messages.Bus
	log     *slog.Logger
	version string

	up websocket.Upgrader

	mu      sync.RWMutex
	clients map[int]*Client
	nextID  int
	nextNum uint16
	talkers []uint16
}

func New(d Deps) *Server {
	return &Server{
		cfg:     d.Cfg,
		hub:     d.Hub,
		reg:     d.Registry,
		relay:   d.Relay,
		roip:    d.RoIP,
		msgs:    d.Messages,
		log:     d.Log.With("mod", "wsserver"),
		version: d.Version,
		clients: map[int]*Client{},
		up: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			// ใช้งานใน LAN ปิด -> ยอมรับ origin ใดก็ได้ (แอป Android ไม่ส่ง Origin)
			CheckOrigin: func(*http.Request) bool { return true },
		},
	}
}

// Run ผูก hub เข้ากับ WebSocket: ทุกครั้งที่ tally เปลี่ยน -> broadcast
func (s *Server) Run(ctx context.Context) {
	_, ch := s.hub.Subscribe()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case snap, ok := <-ch:
				if !ok {
					return
				}
				s.Broadcast(ServerMsg{T: "tally", Tally: snapshotPayload(snap)})
			}
		}
	}()

	// แจ้งเมื่อมีคนเริ่ม/หยุดพูด (มาจากฝั่ง UDP)
	s.relay.OnTalkerChange(func(talkers []uint16) {
		s.mu.Lock()
		s.talkers = talkers
		s.mu.Unlock()
		s.Broadcast(ServerMsg{T: "audio", Audio: &AudioGameState{Talkers: talkers}})
		s.syncRoIP(len(talkers) > 0)
	})
}

func snapshotPayload(s tally.Snapshot) *TallyPayload {
	states := make(map[string]string, len(s.States))
	for k, v := range s.States {
		states[k] = string(v)
	}
	titles := make(map[string]string, len(s.Titles))
	for num, name := range s.Titles {
		titles[itoa(num)] = name
	}
	return &TallyPayload{
		States: states,
		Titles: titles,
		Seq:    s.Seq,
		Source: s.Source,
		VMixUp: s.VMixUp,
		AtUnix: s.At.Unix(),
	}
}

// Handler = HTTP entrypoint ทั้งหมด
func (s *Server) Handler(ui http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/healthz", s.handleHealth)
	mux.HandleFunc("/api/state", s.handleState)
	mux.HandleFunc("/api/peers", s.handlePeers)
	mux.HandleFunc("/api/tally", s.handlePushTally)
	mux.HandleFunc("/api/message", s.handlePushMessage)
	mux.HandleFunc("/api/messages", s.handleMessages)
	if ui != nil {
		mux.Handle("/", ui)
	}
	return logRequests(s.log, mux)
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := s.up.Upgrade(w, r, nil)
	if err != nil {
		s.log.Warn("upgrade ไม่สำเร็จ", "err", err.Error())
		return
	}
	c := s.newClient(conn, clientIP(r))
	go c.writePump()
	c.readPump()
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	snap := s.hub.Snapshot()
	st := s.relay.Stats()
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"server":   s.cfg.ServerName,
		"proto":    ProtoVersion,
		"vmixUp":   snap.VMixUp,
		"source":   snap.Source,
		"clients":  s.ClientCount(),
		"tallySeq": snap.Seq,
		"audio": map[string]any{
			"rxPackets": st.RxPackets,
			"txPackets": st.TxPackets,
			"badPacket": st.BadPackets,
			"denied":    st.DeniedCount,
			"listen":    s.cfg.Listen.AudioUDP,
		},
	})
}

func (s *Server) handleState(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"tally": snapshotPayload(s.hub.Snapshot()),
		"peers": s.Peers(),
	})
}

func (s *Server) handlePeers(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"peers": s.Peers()})
}

// PushMessage ส่งข้อความจากศูนย์ควบคุมลงไปยังเครื่องลูกทุกเครื่อง
//
//	POST /api/message  {"text":"เตรียมกล้อง 2","from":"โปรดิวเซอร์","to":"all","kind":"info"}
func (s *Server) PushMessage(from, to, text, kind string) (messages.Message, bool) {
	if s.msgs == nil || strings.TrimSpace(text) == "" {
		return messages.Message{}, false
	}
	m := s.msgs.Push(from, to, text, kind)
	s.Broadcast(ServerMsg{T: "msg", Message: &m})
	s.log.Info("ส่งข้อความ", "from", m.From, "to", m.To, "kind", m.Kind, "text", m.Text)
	return m, true
}

func (s *Server) handlePushMessage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "ใช้ POST เท่านั้น"})
		return
	}
	var body struct {
		Text string `json:"text"`
		From string `json:"from"`
		To   string `json:"to"`
		Kind string `json:"kind"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "JSON ไม่ถูกต้อง: " + err.Error()})
		return
	}
	m, ok := s.PushMessage(body.From, body.To, body.Text, body.Kind)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "ต้องมี text"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "message": m})
}

// handleMessages คืนข้อความ + การตอบกลับ (ให้ dashboard/ศูนย์ควบคุมดึงไปแสดง)
//
//	GET /api/messages?since=0&replies=0
func (s *Server) handleMessages(w http.ResponseWriter, r *http.Request) {
	if s.msgs == nil {
		writeJSON(w, http.StatusOK, map[string]any{"messages": []any{}, "replies": []any{}})
		return
	}
	since := int64(0)
	if v := r.URL.Query().Get("since"); v != "" {
		fmt.Sscanf(v, "%d", &since)
	}
	replySince := int64(0)
	if v := r.URL.Query().Get("replies"); v != "" {
		fmt.Sscanf(v, "%d", &replySince)
	}
	msgs, reps := s.msgs.Snapshot(since, replySince)
	if msgs == nil {
		msgs = []messages.Message{}
	}
	if reps == nil {
		reps = []messages.Reply{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": msgs, "replies": reps})
}

// handlePushTally = ทางเข้าสำหรับ switcher/สคริปต์อื่น (OBS, Companion, Stream Deck)
//
//	POST /api/tally  {"states":{"cam1":"program"}}   หรือ  {"camera":"cam1","state":"preview"}
func (s *Server) handlePushTally(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "ใช้ POST เท่านั้น"})
		return
	}
	var body struct {
		States map[string]string `json:"states"`
		Camera string            `json:"camera"`
		State  string            `json:"state"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "JSON ไม่ถูกต้อง: " + err.Error()})
		return
	}
	states := map[string]tally.State{}
	for k, v := range body.States {
		states[strings.ToLower(strings.TrimSpace(k))] = normalizeState(v)
	}
	if body.Camera != "" {
		states[strings.ToLower(strings.TrimSpace(body.Camera))] = normalizeState(body.State)
	}
	if len(states) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "ต้องมี states หรือ camera/state"})
		return
	}
	rejected := s.hub.PushAll(states)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"rejected": rejected,
		"tally":    snapshotPayload(s.hub.Snapshot()),
	})
}

func normalizeState(v string) tally.State {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "program", "live", "pgm", "on", "true", "1":
		return tally.StateProgram
	case "preview", "pvw", "2":
		return tally.StatePreview
	case "safe", "off", "idle", "false", "0":
		return tally.StateSafe
	default:
		return tally.StateUnknown
	}
}

// ---- การจัดการไคลเอนต์ ----

func (s *Server) newClient(conn *websocket.Conn, ip string) *Client {
	s.mu.Lock()
	s.nextID++
	s.nextNum++
	c := &Client{
		id:       s.nextID,
		num:      s.nextNum,
		srv:      s,
		conn:     conn,
		ip:       ip,
		send:     make(chan []byte, 32),
		done:     make(chan struct{}),
		codec:    "pcm",
		deadline: 30 * time.Second,
	}
	s.clients[c.id] = c
	s.mu.Unlock()

	s.log.Info("ไคลเอนต์เชื่อมต่อ", "id", c.id, "num", c.num, "ip", ip)
	return c
}

func (s *Server) removeClient(c *Client) {
	s.mu.Lock()
	delete(s.clients, c.id)
	s.mu.Unlock()
	s.reg.Unregister(c.num)
	s.log.Info("ไคลเอนต์ออก", "id", c.id, "num", c.num, "name", c.Name())
	s.syncRoIP(len(s.talkersList()) > 0)
	s.Broadcast(ServerMsg{T: "peers", Peers: s.Peers()})
}

func (s *Server) ClientCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.clients)
}

func (s *Server) clientsList() []*Client {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]*Client, 0, len(s.clients))
	for _, c := range s.clients {
		out = append(out, c)
	}
	return out
}

func (s *Server) talkersList() []uint16 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []uint16{}
	for _, c := range s.clients {
		if c.PTT() {
			out = append(out, c.num)
		}
	}
	return out
}

// Broadcast ส่งข้อความให้ไคลเอนต์ที่พร้อมใช้งานทุกตัว
func (s *Server) Broadcast(msg ServerMsg) {
	data := mustJSON(msg)
	for _, c := range s.clientsList() {
		if c.Ready() {
			c.trySend(data)
		}
	}
}

// BroadcastPeers แจ้งรายชื่อเพื่อนในห้อง (หลังมีคนเปลี่ยนกล้อง/กด PTT)
func (s *Server) BroadcastPeers() {
	s.Broadcast(ServerMsg{T: "peers", Peers: s.Peers()})
}

func (s *Server) Peers() []PeerPayload {
	out := []PeerPayload{}
	for _, c := range s.clientsList() {
		if !c.Ready() {
			continue
		}
		out = append(out, PeerPayload{
			ID:        c.id,
			Num:       c.num,
			Name:      c.Name(),
			Camera:    c.Camera(),
			PTT:       c.PTT(),
			Codec:     c.Codec(),
			IP:        c.ip,
			AudioPort: c.AudioPort(),
		})
	}
	return out
}

// syncRoIP คีย์/ปล่อยวิทยุตามสถานะการพูดของทั้งห้อง
func (s *Server) syncRoIP(talking bool) {
	if s.roip == nil || !s.roip.Enabled() {
		return
	}
	if talking {
		s.roip.KeyDown()
	} else {
		s.roip.KeyUp()
	}
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	if host == "::1" {
		return "127.0.0.1"
	}
	return host
}

func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/ws") {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Debug("http", "method", r.Method, "path", r.URL.Path, "ms", time.Since(start).Milliseconds())
	})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [12]byte
	i := len(buf)
	neg := n < 0
	if neg {
		n = -n
	}
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
