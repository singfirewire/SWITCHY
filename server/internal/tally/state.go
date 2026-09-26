// Package tally รวมสถานะ Tally (Program/Preview/Safe) และ hub สำหรับกระจายให้ไคลเอนต์
package tally

// State = สถานะของกล้องหนึ่งตัวที่แสดงเป็นสีบนมือถือ
type State string

const (
	StateProgram State = "program" // แดง = ขึ้นจอหลัก (LIVE)
	StatePreview State = "preview" // เขียว/ฟ้า = รอสลับขึ้น
	StateSafe    State = "safe"    // เทา/ดำ = ไม่ได้ใช้งาน
	StateUnknown State = "unknown" // ยังไม่ได้รับข้อมูลจาก switcher
)

// FromVMixDigit แปลงตัวอักษรที่ vMix ส่งมาใน TALLY: '0' off, '1' program, '2' preview
func FromVMixDigit(b byte) State {
	switch b {
	case '1':
		return StateProgram
	case '2':
		return StatePreview
	case '0':
		return StateSafe
	default:
		return StateUnknown
	}
}

// Priority ใช้เลือกสีเมื่อกล้องตัวเดียวถูกอ้างถึงหลาย source (program ชนะ preview ชนะ safe)
func (s State) Priority() int {
	switch s {
	case StateProgram:
		return 3
	case StatePreview:
		return 2
	case StateSafe:
		return 1
	default:
		return 0
	}
}

// Merge คืนค่าที่ priority สูงกว่า (ใช้ตอนรวม vMix + ค่าที่ push ผ่าน HTTP)
func Merge(a, b State) State {
	if b.Priority() > a.Priority() {
		return b
	}
	return a
}
