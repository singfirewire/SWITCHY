// Package wsserver = WebSocket gateway สำหรับ Tally + ควบคุม Intercom (PTT / เลือกกล้อง)
//
// ข้อความทั้งหมดเป็น JSON บรรทัดเดียว (ข้อความเดียวต่อ 1 frame)
// ดูสเปกเต็มที่ docs/PROTOCOL.md
package wsserver

import "encoding/json"

import "github.com/switchy/server/internal/messages"

const (
	ProtoVersion = 1
)

// ---- ข้อความจากไคลเอนต์ -> เซิร์ฟเวอร์ ----

type ClientMsg struct {
	T string `json:"t"`

	// hello
	Proto      int    `json:"proto,omitempty"`
	ClientKey  string `json:"clientKey,omitempty"`
	Name       string `json:"name,omitempty"`
	Camera     string `json:"camera,omitempty"`
	Token      string `json:"token,omitempty"`
	AudioPort  int    `json:"audioPort,omitempty"`
	AudioCodec string `json:"codec,omitempty"` // opus | pcm
	FrameMS    int    `json:"frameMs,omitempty"`

	// ptt
	On bool `json:"on,omitempty"`

	// reply (ตอบกลับข้อความด้วยปุ่มสำเร็จรูป — พิมพ์เองไม่ได้)
	Code  string `json:"code,omitempty"`  // ack | disagree | help
	MsgID int64  `json:"msgId,omitempty"` // ตอบข้อความไหน

	// ping
	TS int64 `json:"ts,omitempty"`
}

// ---- ข้อความจากเซิร์ฟเวอร์ -> ไคลเอนต์ ----

type ServerMsg struct {
	T string `json:"t"`

	// welcome
	Proto     int    `json:"proto,omitempty"`
	ClientID  int    `json:"clientId,omitempty"`
	ClientNum uint16 `json:"clientNum,omitempty"`
	Server    string `json:"server,omitempty"`
	Ver       string `json:"ver,omitempty"`       // เวอร์ชันเซิร์ฟเวอร์ (ไว้ตรวจว่า APK ตรงรุ่น)
	AudioAddr string `json:"audioAddr,omitempty"` // "udp://192.168.1.10:50500"
	FrameMS   int    `json:"frameMs,omitempty"`
	SampleRat int    `json:"sampleRate,omitempty"`
	FullDuplx bool   `json:"fullDuplex,omitempty"`
	Camera    string `json:"camera,omitempty"`
	Error     string `json:"error,omitempty"`

	// tally / peers / welcome
	Tally *TallyPayload   `json:"tally,omitempty"`
	Peers []PeerPayload   `json:"peers,omitempty"`
	Audio *AudioGameState `json:"audio,omitempty"`

	// ข้อความจากศูนย์ควบคุม + การตอบกลับของเครื่องลูก
	Message  *messages.Message  `json:"message,omitempty"`
	Reply    *messages.Reply    `json:"reply,omitempty"`
	Messages []messages.Message `json:"messages,omitempty"`
	Replies  []messages.Reply   `json:"replies,omitempty"`

	// pong
	TS int64 `json:"ts,omitempty"`
}

type TallyPayload struct {
	States map[string]string `json:"states"`
	Titles map[string]string `json:"titles,omitempty"` // {"1":"Camera 1"} -> key = เลข input
	Seq    uint64            `json:"seq"`
	Source string            `json:"source"`
	VMixUp bool              `json:"vmixUp"`
	AtUnix int64             `json:"at"`
}

type PeerPayload struct {
	ID        int    `json:"id"`
	Num       uint16 `json:"num"`
	Name      string `json:"name"`
	Camera    string `json:"camera"`
	PTT       bool   `json:"ptt"`
	Codec     string `json:"codec"`
	IP        string `json:"ip"`
	RTTms     int    `json:"rttMs,omitempty"`
	AudioPort int    `json:"audioPort,omitempty"`
}

// AudioGameState บอกไคลเอนต์ว่าใครกำลังพูดอยู่ (ไว้โชว์บนจอ)
type AudioGameState struct {
	Talkers []uint16 `json:"talkers"` // clientNum ที่กำลังกด PTT
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		return []byte(`{"t":"error","error":"encode failed"}`)
	}
	return b
}
