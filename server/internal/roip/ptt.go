// Package roip = Radio-over-IP gateway (เฟส 2)
//
// หน้าที่: เป็นสะพานระหว่าง "ห้อง Intercom บน IP" กับ "วิทยุสื่อสารจริง"
//   - PTT: สั่งคีย์วิทยุผ่านขา RTS/DTR ของ USB-Serial (CH340 / FTDI)
//   - Audio: รับ/ส่งเสียงผ่าน USB Soundcard (เอกสารวิธีต่อใน docs/ROIP.md)
//
// เฟสนี้ implement ส่วน PTT (คีย์/ปล่อย) และ VOX ให้ใช้งานได้จริง
// ส่วนเสียงวิทยุ <> IP ใช้ helper แยก (ดู docs/ROIP.md) เพราะต้องใช้ PortAudio/CGO
package roip

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/switchy/server/internal/config"
	"go.bug.st/serial"
)

// PTTKeyer = อุปกรณ์ที่สั่งคีย์/ปล่อย PTT ได้
type PTTKeyer interface {
	Key(ctx context.Context, on bool) error
	Close() error
	Name() string
}

// serialKeyer คีย์วิทยุด้วยขา RTS หรือ DTR ของ USB-Serial (active-low ตามฮาร์ดแวร์ส่วนใหญ่)
type serialKeyer struct {
	mu        sync.Mutex
	port      serial.Port
	mode      string // rts | dtr
	invert    bool
	keyed     bool
	path      string
	lastError error
}

// OpenSerial เปิดพอร์ต USB-Serial สำหรับสั่ง PTT
func OpenSerial(cfg config.RoIPConfig) (PTTKeyer, error) {
	if cfg.PTTMode == "none" {
		return &noopKeyer{}, nil
	}
	mode := &serial.Mode{
		BaudRate: cfg.Baud,
		DataBits: 8,
		Parity:   serial.NoParity,
		StopBits: serial.OneStopBit,
	}
	port, err := serial.Open(cfg.SerialPort, mode)
	if err != nil {
		return nil, fmt.Errorf("เปิดพอร์ต %s ไม่ได้: %w", cfg.SerialPort, err)
	}
	if err := port.SetDTR(false); err != nil {
		port.Close()
		return nil, fmt.Errorf("ตั้ง DTR ไม่ได้: %w", err)
	}
	if err := port.SetRTS(false); err != nil {
		port.Close()
		return nil, fmt.Errorf("ตั้ง RTS ไม่ได้: %w", err)
	}
	k := &serialKeyer{port: port, mode: cfg.PTTMode, invert: cfg.InvertPTT, path: cfg.SerialPort}
	if err := k.apply(false); err != nil {
		port.Close()
		return nil, err
	}
	return k, nil
}

func (k *serialKeyer) Name() string { return "usb-serial:" + k.path }

func (k *serialKeyer) Key(_ context.Context, on bool) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.keyed == on {
		return nil
	}
	if err := k.apply(on); err != nil {
		k.lastError = err
		return err
	}
	k.keyed = on
	return nil
}

func (k *serialKeyer) apply(on bool) error {
	level := on
	if k.invert {
		level = !on
	}
	switch k.mode {
	case "dtr":
		return k.port.SetDTR(level)
	default:
		return k.port.SetRTS(level)
	}
}

func (k *serialKeyer) Close() error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.keyed {
		_ = k.apply(false)
		k.keyed = false
	}
	if k.port == nil {
		return nil
	}
	return k.port.Close()
}

// noopKeyer ใช้ตอนโหมด PTT = none (เช่นทดสอบกับวิทยุที่มี VOX ในตัว)
type noopKeyer struct{}

func (n *noopKeyer) Key(context.Context, bool) error { return nil }
func (n *noopKeyer) Close() error                    { return nil }
func (n *noopKeyer) Name() string                    { return "noop-ptt" }

// Controller รวม PTT + VOX + กันเสียงค้าง (tail) ให้เป็นตัวเดียว
//
// การทำงาน: เมื่อมีเสียงดังเกิน threshold ติดต่อกันตาม hangFrames -> คีย์ PTT
// เมื่อเงียบต่อเนื่องเกิน TalkTailMS -> ปล่อย PTT
type Controller struct {
	keyer      PTTKeyer
	log        *slog.Logger
	threshold  float64 // dBFS
	tail       time.Duration
	minHold    time.Duration
	mu         sync.Mutex
	keyed      bool
	lastVoice  time.Time
	startedAt  time.Time
	voxEnabled bool
	onChange   func(bool)
}

func NewController(keyer PTTKeyer, cfg config.RoIPConfig, log *slog.Logger) *Controller {
	return &Controller{
		keyer:      keyer,
		log:        log.With("mod", "roip-ptt"),
		threshold:  cfg.VOXThreshold,
		tail:       time.Duration(cfg.TalkTailMS) * time.Millisecond,
		minHold:    120 * time.Millisecond,
		voxEnabled: true,
	}
}

func (c *Controller) OnChange(fn func(bool)) { c.onChange = fn }

// KeyManual สั่งคีย์/ปล่อย PTT ตามที่ผู้ใช้กด (ปุ่ม PTT จากแอป)
func (c *Controller) KeyManual(ctx context.Context, on bool) error {
	return c.setKeyed(ctx, on)
}

// FeedAudio รับระดับเสียง (dBFS) ของเฟรมล่าสุดเพื่อทำ VOX
func (c *Controller) FeedAudio(ctx context.Context, dBFS float64) {
	if !c.voxEnabled {
		return
	}
	c.mu.Lock()
	loud := dBFS > c.threshold
	if loud {
		c.lastVoice = time.Now()
	}
	already := c.keyed
	c.mu.Unlock()

	if loud {
		if !already {
			if err := c.setKeyed(ctx, true); err != nil {
				c.log.Warn("คีย์ PTT ไม่ได้", "err", err.Error())
			}
		}
		return
	}
	// เงียบ: รอ tail ให้ครบก่อนปล่อย (กันท้ายคำขาด)
	if already && time.Since(c.startedAt) > c.minHold {
		c.mu.Lock()
		silentFor := time.Since(c.lastVoice)
		c.mu.Unlock()
		if silentFor > c.tail {
			if err := c.setKeyed(ctx, false); err != nil {
				c.log.Warn("ปล่อย PTT ไม่ได้", "err", err.Error())
			}
		}
	}
}

// ReleaseAll ปล่อย PTT แน่นอน (เรียกตอนปิดบริการ / ผู้ใช้ยกเลิก)
func (c *Controller) ReleaseAll(ctx context.Context) {
	_ = c.setKeyed(ctx, false)
}

func (c *Controller) setKeyed(ctx context.Context, on bool) error {
	c.mu.Lock()
	if c.keyed == on {
		c.mu.Unlock()
		return nil
	}
	c.keyed = on
	c.startedAt = time.Now()
	cb := c.onChange
	c.mu.Unlock()

	if err := c.keyer.Key(ctx, on); err != nil {
		return err
	}
	c.log.Info("PTT", "state", map[bool]string{true: "KEY (ส่ง)", false: "RELEASE (รับ)"}[on], "device", c.keyer.Name())
	if cb != nil {
		cb(on)
	}
	return nil
}

func (c *Controller) State() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.keyed
}

func (c *Controller) SetVOX(enabled bool) {
	c.mu.Lock()
	c.voxEnabled = enabled
	c.mu.Unlock()
}

func (c *Controller) Close() error {
	if c.keyer == nil {
		return nil
	}
	return c.keyer.Close()
}
