package roip

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"sync/atomic"

	"github.com/switchy/server/internal/audioengine"
	"github.com/switchy/server/internal/config"
)

// RadioClientNum = clientNum สมมติของ "วิทยุ" (ให้แอปแยกได้ว่าเสียงนี้มาจาก WT)
const RadioClientNum uint16 = 0xFFF0

var ErrNoAudioBridge = errors.New("roip: ยังไม่ได้ตั้งค่า audioUdp (บริดจ์ USB Soundcard)")

// Gateway = สะพานเชื่อมระหว่างห้อง Intercom บน IP กับวิทยุจริง
//
//	[แอป Android] --UDP--> [SWITCHY relay] --UDP--> [helper/USB soundcard] --analog--> [วิทยุ]
//	                                      <--UDP--                        <--analog--
//
// PTT: คีย์ผ่านขา RTS/DTR ของ USB-Serial (CH340/FTDI)
type Gateway struct {
	cfg   config.RoIPConfig
	log   *slog.Logger
	relay *audioengine.Relay
	ctrl  *Controller
	conn  *net.UDPConn

	enabled    bool
	opusWarned atomic.Bool
	keyed      atomic.Bool
	rxPackets  atomic.Uint64
	txPackets  atomic.Uint64

	ctx    context.Context
	cancel context.CancelFunc
}

func NewGateway(cfg config.RoIPConfig, relay *audioengine.Relay, log *slog.Logger) *Gateway {
	return &Gateway{
		cfg:     cfg,
		log:     log.With("mod", "roip"),
		relay:   relay,
		enabled: cfg.Enabled,
	}
}

func (g *Gateway) Enabled() bool { return g.enabled }

// Name = implement audioengine.Sink
func (g *Gateway) Name() string { return "roip(" + g.cfg.SerialPort + ")" }

// Start เปิดพอร์ต Serial + บริดจ์เสียง และลงทะเบียนเป็น sink ของ relay
func (g *Gateway) Start(ctx context.Context) error {
	if !g.enabled {
		g.log.Info("RoIP ปิดอยู่ (ตั้งค่า roip.enabled = true เพื่อเปิด)")
		return nil
	}
	g.ctx, g.cancel = context.WithCancel(ctx)

	keyer, err := OpenSerial(g.cfg)
	if err != nil {
		// ไม่ให้ทั้งเซิร์ฟเวอร์ล้มเพราะวิทยุไม่พร้อม: log แล้วรันต่อแบบไม่มี PTT
		g.log.Error("เปิด PTT ไม่ได้ - จะรัน RoIP แบบไม่มีคีย์วิทยุ", "err", err.Error())
		keyer = &noopKeyer{}
	}
	g.ctrl = NewController(keyer, g.cfg, g.log)
	g.ctrl.OnChange(func(on bool) { g.keyed.Store(on) })

	if g.cfg.AudioUDP != "" {
		ua, err := net.ResolveUDPAddr("udp", g.cfg.AudioUDP)
		if err != nil {
			return fmt.Errorf("roip audioUdp ไม่ถูกต้อง: %w", err)
		}
		conn, err := net.DialUDP("udp", nil, ua)
		if err != nil {
			return fmt.Errorf("เชื่อมบริดจ์เสียง %s ไม่ได้: %w", g.cfg.AudioUDP, err)
		}
		g.conn = conn
		go g.readLoop()
		g.log.Info("บริดจ์เสียงวิทยุพร้อม", "helper", g.cfg.AudioUDP)
	}

	if g.relay != nil {
		g.relay.AddSink(g)
	}
	g.log.Info("RoIP gateway ทำงาน",
		"serial", g.cfg.SerialPort, "pttMode", g.cfg.PTTMode,
		"voxThreshold", g.cfg.VOXThreshold, "tailMs", g.cfg.TalkTailMS)
	return nil
}

// WriteAudio = implement audioengine.Sink : ได้สำเนาเสียงจาก IP ทุกแพ็กเก็ต
func (g *Gateway) WriteAudio(from uint16, payload []byte, opus bool) error {
	if opus {
		if g.opusWarned.CompareAndSwap(false, true) {
			g.log.Warn("ได้รับแพ็กเก็ต Opus: RoIP ต้องใช้ codec=pcm (เพราะต้องถอดรหัสก่อนส่งออกวิทยุ)")
		}
		return nil
	}
	// ทำ VOX / อัปเดตเสียงล่าสุดไว้คีย์ PTT
	if g.ctrl != nil {
		g.ctrl.FeedAudio(g.ctx, audioengine.RMSdBFS(payload))
	}
	if g.conn == nil {
		return ErrNoAudioBridge
	}
	if _, err := g.conn.Write(payload); err != nil {
		return err
	}
	g.txPackets.Add(1)
	return nil
}

// readLoop รับเสียงจากวิทยุ (helper ส่งกลับมาที่ socket เดิม) แล้วยิงเข้าห้อง Intercom
func (g *Gateway) readLoop() {
	buf := make([]byte, 2048)
	for {
		n, err := g.conn.Read(buf)
		if err != nil {
			if g.ctx != nil && g.ctx.Err() != nil {
				return
			}
			g.log.Warn("อ่านเสียงจากวิทยุไม่ได้", "err", err.Error())
			return
		}
		if n == 0 {
			continue
		}
		g.rxPackets.Add(1)
		if g.relay != nil {
			g.relay.InjectAudio(RadioClientNum, false, buf[:n])
		}
	}
}

// KeyDown / KeyUp = สั่งคีย์วิทยุ (เรียกจาก WS เมื่อมีคนกด PTT)
func (g *Gateway) KeyDown() {
	if g.ctrl == nil {
		return
	}
	if err := g.ctrl.KeyManual(g.ctx, true); err != nil {
		g.log.Warn("คีย์วิทยุไม่ได้", "err", err.Error())
	}
}

func (g *Gateway) KeyUp() {
	if g.ctrl == nil {
		return
	}
	if err := g.ctrl.KeyManual(g.ctx, false); err != nil {
		g.log.Warn("ปล่อยคีย์วิทยุไม่ได้", "err", err.Error())
	}
}

func (g *Gateway) SetVOX(on bool) {
	if g.ctrl != nil {
		g.ctrl.SetVOX(on)
	}
}

func (g *Gateway) Status() map[string]any {
	if !g.enabled {
		return map[string]any{"enabled": false}
	}
	return map[string]any{
		"enabled":   true,
		"serial":    g.cfg.SerialPort,
		"pttMode":   g.cfg.PTTMode,
		"keyed":     g.keyed.Load(),
		"audioUdp":  g.cfg.AudioUDP,
		"rxPackets": g.rxPackets.Load(),
		"txPackets": g.txPackets.Load(),
	}
}

func (g *Gateway) Close() {
	if g.cancel != nil {
		g.cancel()
	}
	if g.ctrl != nil {
		g.ctrl.ReleaseAll(context.Background())
		_ = g.ctrl.Close()
	}
	if g.conn != nil {
		_ = g.conn.Close()
	}
}
