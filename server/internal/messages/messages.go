// Package messages = ระบบข้อความทางเดียว + ปุ่มตอบกลับสำเร็จรูป
//
// แนวคิด (ตามที่ผู้ใช้ออกแบบ): "แม่ข่าย/ผู้รับ" พิมพ์และส่งข้อความลงมา
// เครื่องลูก (มือถือ) อ่านได้อย่างเดียว พิมพ์ตอบกลับไม่ได้ แต่กดปุ่มสำเร็จรูปตอบได้:
//
//	รับทราบ (ack) · ไม่เห็นด้วย (disagree) · ขอความช่วยเหลือ (help)
package messages

import (
	"sort"
	"strings"
	"sync"
	"time"
)

// รหัสตอบกลับที่อนุญาต (เครื่องลูกพิมพ์เองไม่ได้)
const (
	CodeAck      = "ack"
	CodeDisagree = "disagree"
	CodeHelp     = "help"
)

func Label(code string) string {
	switch strings.ToLower(strings.TrimSpace(code)) {
	case CodeAck:
		return "รับทราบ"
	case CodeDisagree:
		return "ไม่เห็นด้วย"
	case CodeHelp:
		return "ขอความช่วยเหลือ"
	default:
		return ""
	}
}

func ValidCode(code string) bool { return Label(code) != "" }

const keepLast = 200

type Message struct {
	ID   int64     `json:"id"`
	At   time.Time `json:"at"`
	From string    `json:"from"` // เช่น "โปรดิวเซอร์"
	To   string    `json:"to"`   // "all" หรือเลข clientNum
	Text string    `json:"text"`
	Kind string    `json:"kind"` // info | alert
}

type Reply struct {
	ID        int64     `json:"id"`
	MsgID     int64     `json:"msgId"`
	At        time.Time `json:"at"`
	ClientNum uint16    `json:"clientNum"`
	Name      string    `json:"name"`
	Code      string    `json:"code"`
	Label     string    `json:"label"`
}

// Bus เก็บข้อความ/การตอบกลับล่าสุดไว้ให้ดูย้อนหลัง (ไม่ลงฐานข้อมูล — พอสำหรับงานหน้างาน)
type Bus struct {
	mu      sync.RWMutex
	msgs    []Message
	replies []Reply
	nextMsg int64
	nextRep int64
}

func NewBus() *Bus { return &Bus{} }

// Push เพิ่มข้อความจากแม่ข่าย แล้วคืนข้อความที่บันทึกแล้ว
func (b *Bus) Push(from, to, text, kind string) Message {
	b.mu.Lock()
	b.nextMsg++
	m := Message{
		ID:   b.nextMsg,
		At:   time.Now(),
		From: strings.TrimSpace(from),
		To:   strings.TrimSpace(to),
		Text: strings.TrimSpace(text),
		Kind: kind,
	}
	if m.From == "" {
		m.From = "ศูนย์ควบคุม"
	}
	if m.To == "" {
		m.To = "all"
	}
	if m.Kind != "alert" {
		m.Kind = "info"
	}
	b.msgs = append(b.msgs, m)
	if len(b.msgs) > keepLast {
		b.msgs = b.msgs[len(b.msgs)-keepLast:]
	}
	b.mu.Unlock()
	return m
}

// AddReply บันทึกการกดปุ่มตอบกลับของเครื่องลูก
func (b *Bus) AddReply(msgID int64, clientNum uint16, name, code string) (Reply, bool) {
	label := Label(code)
	if label == "" {
		return Reply{}, false
	}
	b.mu.Lock()
	b.nextRep++
	r := Reply{
		ID:        b.nextRep,
		MsgID:     msgID,
		At:        time.Now(),
		ClientNum: clientNum,
		Name:      name,
		Code:      strings.ToLower(code),
		Label:     label,
	}
	b.replies = append(b.replies, r)
	if len(b.replies) > keepLast {
		b.replies = b.replies[len(b.replies)-keepLast:]
	}
	b.mu.Unlock()
	return r, true
}

// Snapshot คืนข้อความ+การตอบกลับที่ใหม่กว่า since (0 = ทั้งหมด)
func (b *Bus) Snapshot(since int64, replySince int64) ([]Message, []Reply) {
	b.mu.RLock()
	defer b.mu.RUnlock()

	msgs := make([]Message, 0, len(b.msgs))
	for _, m := range b.msgs {
		if m.ID > since {
			msgs = append(msgs, m)
		}
	}
	reps := make([]Reply, 0, len(b.replies))
	for _, r := range b.replies {
		if r.ID > replySince {
			reps = append(reps, r)
		}
	}
	sort.Slice(msgs, func(i, j int) bool { return msgs[i].ID < msgs[j].ID })
	sort.Slice(reps, func(i, j int) bool { return reps[i].ID < reps[j].ID })
	return msgs, reps
}

// Latest คืนข้อความล่าสุด (ไว้โชว์บนจอ Tally)
func (b *Bus) Latest() (Message, bool) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	if len(b.msgs) == 0 {
		return Message{}, false
	}
	return b.msgs[len(b.msgs)-1], true
}
