// SWITCHY Central Server (Mini PC) — vMix Tally WebSocket Gateway + Intercom Audio Relay
//
// วิธีรัน:
//
//	go run ./cmd/switchy-server -config config.json
//
// เปิด dashboard ทดสอบที่ http://<ip-ของ-mini-pc>:8090
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/switchy/server/internal/audioengine"
	"github.com/switchy/server/internal/config"
	"github.com/switchy/server/internal/messages"
	"github.com/switchy/server/internal/roip"
	"github.com/switchy/server/internal/tally"
	"github.com/switchy/server/internal/webui"
	"github.com/switchy/server/internal/wsserver"
)

const version = "0.1.0"

func main() {
	var (
		cfgPath   = flag.String("config", "config.json", "ไฟล์คอนฟิก JSON (สร้างตัวอย่างให้ถ้าไม่มี)")
		httpAddr  = flag.String("http", "", "override ที่อยู่ HTTP/WS เช่น :8090")
		audioAddr = flag.String("audio", "", "override พอร์ต UDP เสียง เช่น :50500")
		vmixHost  = flag.String("vmix", "", "override IP/host ของเครื่อง vMix")
		noVMix    = flag.Bool("no-vmix", false, "ไม่เชื่อม vMix (ใช้ HTTP push API ทดสอบแทน)")
		verbose   = flag.Bool("v", false, "log ละเอียด (debug)")
		printCfg  = flag.Bool("print-config", false, "แสดงคอนฟิกที่โหลดได้แล้วจบการทำงาน")
	)
	flag.Parse()

	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level}))

	cfg, err := config.Load(*cfgPath)
	if err != nil {
		log.Error("โหลดคอนฟิกไม่สำเร็จ", "err", err.Error())
		os.Exit(1)
	}
	if *httpAddr != "" {
		cfg.Listen.HTTP = *httpAddr
	}
	if *audioAddr != "" {
		cfg.Listen.AudioUDP = *audioAddr
	}
	if *vmixHost != "" {
		cfg.VMix.Host = *vmixHost
	}
	if *noVMix {
		cfg.VMix.Enabled = false
	}

	if *printCfg {
		raw, _ := json.MarshalIndent(cfg, "", "  ")
		fmt.Println(string(raw))
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// --- ประกอบระบบ ---
	hub := tally.NewHub(cfg.Cameras)
	registry := audioengine.NewRegistry(log, 30*time.Second, !cfg.Audio.DisableIPCheck)
	relay := audioengine.NewRelay(registry, log, cfg.Audio.FrameMS, cfg.Audio.FullDuplex, cfg.Audio.MaxTalkers)
	roipGW := roip.NewGateway(cfg.RoIP, relay, log)
	msgBus := messages.NewBus()

	wsSrv := wsserver.New(wsserver.Deps{
		Cfg:      cfg,
		Hub:      hub,
		Registry: registry,
		Relay:    relay,
		RoIP:     roipGW,
		Messages: msgBus,
		Log:      log,
		Version:  version,
	})
	wsSrv.Run(ctx)

	if err := roipGW.Start(ctx); err != nil {
		log.Error("เริ่ม RoIP gateway ไม่ได้", "err", err.Error())
	}
	defer roipGW.Close()

	// vMix tally client
	vmix := tally.NewVMixClient(cfg.VMix, hub, log)
	go vmix.Run(ctx)

	// UDP audio relay
	go func() {
		if err := relay.Listen(ctx, cfg.Listen.AudioUDP); err != nil {
			log.Error("UDP relay ล้ม", "err", err.Error())
			stop()
		}
	}()

	httpSrv := &http.Server{
		Addr:              cfg.Listen.HTTP,
		Handler:           wsSrv.Handler(webui.Handler()),
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() {
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Error("HTTP server ล้ม", "err", err.Error())
			stop()
		}
	}()

	printBanner(cfg, log)

	<-ctx.Done()
	log.Info("กำลังปิดระบบ…")
	shCtx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(shCtx)
}

func printBanner(cfg config.Config, log *slog.Logger) {
	httpPort := portOf(cfg.Listen.HTTP)
	audioPort := portOf(cfg.Listen.AudioUDP)

	log.Info("SWITCHY Gateway พร้อมใช้งาน",
		"version", version, "config", cfg.Path,
		"http", cfg.Listen.HTTP, "audioUdp", cfg.Listen.AudioUDP,
		"vmix", fmt.Sprintf("%s:%d (tally) / :%d (api)", cfg.VMix.Host, cfg.VMix.TallyPort, cfg.VMix.HTTPPort),
		"vmixEnabled", cfg.VMix.Enabled)

	fmt.Println()
	fmt.Println("  ╭──────────────────────────────────────────────────────────────╮")
	fmt.Println("  │  SWITCHY Central Server v" + version + "                              │")
	fmt.Println("  ╰──────────────────────────────────────────────────────────────╯")
	for _, ip := range config.LANIPs() {
		fmt.Printf("   Dashboard   http://%s:%s/\n", ip, httpPort)
		fmt.Printf("   WebSocket   ws://%s:%s/ws\n", ip, httpPort)
		fmt.Printf("   Audio UDP   %s\n", cfg.AudioAdvertise)
		fmt.Println()
	}
	fmt.Println("   ตั้งค่าในแอป Android:")
	fmt.Printf("     IP เซิร์ฟเวอร์ = %s    พอร์ต WS = %s    พอร์ตเสียง = %s\n", config.FirstLANIP(), httpPort, audioPort)
	fmt.Println("   กล้องที่ตั้งไว้:", cameraList(cfg))
	fmt.Println("   สถานะ Tally จาก:", tallySource(cfg))
	fmt.Println()
	log.Info("กด Ctrl+C เพื่อปิด")
}

func tallySource(cfg config.Config) string {
	if cfg.VMix.Enabled {
		return fmt.Sprintf("vMix %s:%d (ต่อผ่าน TCP)", cfg.VMix.Host, cfg.VMix.TallyPort)
	}
	return "HTTP push API (POST /api/tally) — โหมดทดสอบ"
}

func cameraList(cfg config.Config) string {
	parts := make([]string, 0, len(cfg.Cameras))
	for _, c := range cfg.Cameras {
		if c.VMixInput > 0 {
			parts = append(parts, fmt.Sprintf("%s=%s(in %d)", c.Code, c.Name, c.VMixInput))
		} else {
			parts = append(parts, fmt.Sprintf("%s=%s", c.Code, c.Name))
		}
	}
	return strings.Join(parts, "  ")
}

func portOf(addr string) string {
	if _, p, err := net.SplitHostPort(addr); err == nil {
		return p
	}
	return "8090"
}
