// Package audioengine = UDP relay สำหรับเสียง Intercom (low-latency)
//
// แนวคิด: เสียงไม่วิ่งผ่าน WebSocket (หัวข้อ TCP ทำให้เกิด head-of-line blocking)
// ไคลเอนต์ส่ง UDP ไปที่พอร์ตเดียว (Listen.AudioUDP) เซิร์ฟเวอร์เรียนรู้ปลายทางจาก
// แพ็กเก็ตแรกที่ตรงกับ clientNum ที่ลงทะเบียนผ่าน WS แล้ว forward ต่อให้คนอื่น
package audioengine

import (
	"encoding/binary"
	"errors"
)

const (
	Magic0  byte = 'S'
	Magic1  byte = 'W'
	Version byte = 1

	HeaderSize = 16

	FlagTalk    uint8 = 1 << 0 // แพ็กเก็ตนี้เป็นเสียงที่ผู้พูดกำลังส่งจริง (กด PTT อยู่)
	FlagOpus    uint8 = 1 << 1 // payload เป็น Opus
	FlagLast    uint8 = 1 << 2 // เฟรมสุดท้ายก่อนปล่อยคีย์
	FlagControl uint8 = 1 << 3 // payload เป็นข้อความควบคุมสั้น ๆ (JSON) ไม่ใช่เสียง
)

var (
	ErrShortPacket = errors.New("แพ็กเก็ตสั้นเกินไป")
	ErrBadMagic    = errors.New("magic ไม่ถูกต้อง")
	ErrBadVersion  = errors.New("เวอร์ชันโปรโตคอลไม่รองรับ")
)

// Header = 16 ไบต์
//
//	0..1  magic 'S','W'
//	2     version
//	3     flags
//	4..5  clientNum (uint16 LE)  — เลขที่เซิร์ฟเวอร์แจกใน welcome
//	6..7  seq (uint16 LE)
//	8..11 timestampMs (uint32 LE) — เวลาที่เก็บเสียง (ใช้วัด jitter / เรียงลำดับ)
//	12..13 payloadLen (uint16 LE)
//	14..15 reserved (uint16, ต้องเป็น 0)
type Header struct {
	Flags   uint8
	Client  uint16
	Seq     uint16
	TSms    uint32
	Payload uint16
}

func (h Header) Encode(dst []byte) {
	dst[0], dst[1] = Magic0, Magic1
	dst[2] = Version
	dst[3] = h.Flags
	binary.LittleEndian.PutUint16(dst[4:], h.Client)
	binary.LittleEndian.PutUint16(dst[6:], h.Seq)
	binary.LittleEndian.PutUint32(dst[8:], h.TSms)
	binary.LittleEndian.PutUint16(dst[12:], h.Payload)
	binary.LittleEndian.PutUint16(dst[14:], 0)
}

func DecodeHeader(b []byte) (Header, error) {
	if len(b) < HeaderSize {
		return Header{}, ErrShortPacket
	}
	if b[0] != Magic0 || b[1] != Magic1 {
		return Header{}, ErrBadMagic
	}
	if b[2] != Version {
		return Header{}, ErrBadVersion
	}
	return Header{
		Flags:   b[3],
		Client:  binary.LittleEndian.Uint16(b[4:]),
		Seq:     binary.LittleEndian.Uint16(b[6:]),
		TSms:    binary.LittleEndian.Uint32(b[8:]),
		Payload: binary.LittleEndian.Uint16(b[12:]),
	}, nil
}

// EncodePacket สร้างแพ็กเก็ต UDP พร้อมส่ง
func EncodePacket(h Header, payload []byte) []byte {
	out := make([]byte, HeaderSize+len(payload))
	h.Payload = uint16(len(payload))
	h.Encode(out)
	copy(out[HeaderSize:], payload)
	return out
}
