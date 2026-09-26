// Package config โหลด/ตรวจสอบค่าคอนฟิกของ SWITCHY Central Server
package config

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
)

// Camera = การจับคู่ "รหัสกล้อง" ที่แอปมือถือใช้ กับ input number ของ vMix
type Camera struct {
	Code      string `json:"code"`      // cam1
	Name      string `json:"name"`      // กล้อง 1 (ชื่อที่แสดงบนมือถือ)
	VMixInput int    `json:"vmixInput"` // 0 = ไม่ผูกกับ vMix (ใช้ค่าที่ push ผ่าน HTTP API)
	Accent    string `json:"accent,omitempty"`
}

type ListenConfig struct {
	HTTP     string `json:"http"`     // ":8090"  -> /ws, /api/*, dashboard
	AudioUDP string `json:"audioUdp"` // ":50500" -> relay เสียงระหว่างแอป
}

type VMixConfig struct {
	Enabled      bool   `json:"enabled"`
	Host         string `json:"host"`
	TallyPort    int    `json:"tallyPort"` // 8099 (TCP API)
	HTTPPort     int    `json:"httpPort"`  // 8088 (HTTP API - ใช้ดึงชื่อ input)
	PollTitlesMS int    `json:"pollTitlesMs"`
	ReconnectMS  int    `json:"reconnectMs"`
}

type AudioConfig struct {
	FrameMS        int  `json:"frameMs"`        // 10 หรือ 20
	SampleRate     int  `json:"sampleRate"`     // 48000 (หรือ 16000)
	OpusBitrate    int  `json:"opusBitrate"`    // bps ต่อคน
	FullDuplex     bool `json:"fullDuplex"`     // true = ส่งตลอดเวลา (ไม่ต้องกด PTT)
	MaxTalkers     int  `json:"maxTalkers"`     // จำนวนคนพูดพร้อมกันสูงสุดที่ relay
	JitterFrames   int  `json:"jitterFrames"`   // ความลึก jitter buffer ฝั่งไคลเอนต์ (เฟรม)
	DisableIPCheck bool `json:"disableIpCheck"` // true = ยอมรับ IP ใหม่ของไคลเอนต์ (ผ่อนความปลอดภัย)
}

type RoIPConfig struct {
	Enabled      bool    `json:"enabled"`
	SerialPort   string  `json:"serialPort"` // COM3 (CH340/FTDI)
	Baud         int     `json:"baud"`
	PTTMode      string  `json:"pttMode"`   // rts | dtr | none
	InvertPTT    bool    `json:"invertPtt"` // true = active-low
	AudioUDP     string  `json:"audioUdp"`  // ปลายทางบริดจ์ USB soundcard (helper exe) เช่น 127.0.0.1:50600
	VOXThreshold float64 `json:"voxThreshold"`
	TalkTailMS   int     `json:"talkTailMs"` // หน่วงปล่อย PTT หลังหยุดพูด (กันคำขาด)
}

type Config struct {
	ServerName  string       `json:"serverName"`
	AuthToken   string       `json:"authToken"`   // ว่าง = ไม่ต้องยืนยันตัวตน
	AdvertiseIP string       `json:"advertiseIp"` // IP ที่จะบอกไคลเอนต์ (ว่าง = หาเองจาก LAN)
	Listen      ListenConfig `json:"listen"`
	VMix        VMixConfig   `json:"vmix"`
	Audio       AudioConfig  `json:"audio"`
	RoIP        RoIPConfig   `json:"roip"`
	Cameras     []Camera     `json:"cameras"`

	// derived (ไม่ serialize)
	CameraByCode   map[string]Camera `json:"-"`
	AudioAdvertise string            `json:"-"` // "192.168.1.10:50500" สำหรับส่งให้ไคลเอนต์
	Path           string            `json:"-"`
}

func Default() Config {
	return Config{
		ServerName: "SWITCHY Gateway",
		Listen:     ListenConfig{HTTP: ":8090", AudioUDP: ":50500"},
		VMix: VMixConfig{
			Enabled:      true,
			Host:         "127.0.0.1",
			TallyPort:    8099,
			HTTPPort:     8088,
			PollTitlesMS: 5000,
			ReconnectMS:  3000,
		},
		Audio: AudioConfig{
			FrameMS:      20,
			SampleRate:   48000,
			OpusBitrate:  32000,
			FullDuplex:   false,
			MaxTalkers:   4,
			JitterFrames: 3,
		},
		RoIP: RoIPConfig{
			Baud:         9600,
			PTTMode:      "rts",
			VOXThreshold: 0.02,
			TalkTailMS:   250,
		},
		Cameras: []Camera{
			{Code: "cam1", Name: "กล้อง 1", VMixInput: 1},
			{Code: "cam2", Name: "กล้อง 2", VMixInput: 2},
			{Code: "cam3", Name: "กล้อง 3", VMixInput: 3},
		},
	}
}

// Load อ่านไฟล์ JSON (ถ้าไม่มี -> สร้างไฟล์ตัวอย่างให้ แล้วใช้ค่า default)
func Load(path string) (Config, error) {
	cfg := Default()
	cfg.Path = path

	if path != "" {
		if raw, err := os.ReadFile(path); err == nil {
			dec := json.NewDecoder(strings.NewReader(string(raw)))
			dec.DisallowUnknownFields()
			if err := dec.Decode(&cfg); err != nil {
				return cfg, fmt.Errorf("อ่าน %s ไม่ได้: %w", path, err)
			}
		} else if !os.IsNotExist(err) {
			return cfg, err
		} else {
			if err := cfg.WriteSample(path); err != nil {
				return cfg, fmt.Errorf("สร้างไฟล์คอนฟิกตัวอย่างไม่ได้: %w", err)
			}
		}
	}
	return cfg, cfg.Finalize()
}

// Finalize ตรวจค่าและสร้าง index
func (c *Config) Finalize() error {
	if c.Listen.HTTP == "" {
		c.Listen.HTTP = ":8090"
	}
	if c.Listen.AudioUDP == "" {
		c.Listen.AudioUDP = ":50500"
	}
	if c.Audio.FrameMS != 10 && c.Audio.FrameMS != 20 {
		c.Audio.FrameMS = 20
	}
	if c.Audio.SampleRate == 0 {
		c.Audio.SampleRate = 48000
	}
	if c.Audio.JitterFrames <= 0 {
		c.Audio.JitterFrames = 3
	}
	if c.Audio.MaxTalkers <= 0 {
		c.Audio.MaxTalkers = 4
	}
	if c.VMix.TallyPort == 0 {
		c.VMix.TallyPort = 8099
	}
	if c.VMix.HTTPPort == 0 {
		c.VMix.HTTPPort = 8088
	}
	if c.VMix.ReconnectMS <= 0 {
		c.VMix.ReconnectMS = 3000
	}
	if c.VMix.PollTitlesMS <= 0 {
		c.VMix.PollTitlesMS = 5000
	}
	if c.RoIP.Baud == 0 {
		c.RoIP.Baud = 9600
	}
	switch c.RoIP.PTTMode {
	case "rts", "dtr", "none":
	default:
		c.RoIP.PTTMode = "rts"
	}
	if c.RoIP.TalkTailMS <= 0 {
		c.RoIP.TalkTailMS = 250
	}
	if c.ServerName == "" {
		c.ServerName = "SWITCHY Gateway"
	}
	if len(c.Cameras) == 0 {
		c.Cameras = Default().Cameras
	}

	c.CameraByCode = make(map[string]Camera, len(c.Cameras))
	for i := range c.Cameras {
		cam := c.Cameras[i]
		cam.Code = strings.ToLower(strings.TrimSpace(cam.Code))
		if cam.Code == "" {
			return fmt.Errorf("cameras[%d]: code ว่างไม่ได้", i)
		}
		if _, dup := c.CameraByCode[cam.Code]; dup {
			return fmt.Errorf("cameras: code ซ้ำ %q", cam.Code)
		}
		c.Cameras[i] = cam
		c.CameraByCode[cam.Code] = cam
	}

	// ที่อยู่เสียงที่จะบอกไคลเอนต์ (ต้องเป็น IP จริง ไม่ใช่ :50500 เปล่า ๆ)
	host := strings.TrimSpace(c.AdvertiseIP)
	if host == "" {
		host = FirstLANIP()
	}
	_, port, err := net.SplitHostPort(c.Listen.AudioUDP)
	if err != nil {
		return fmt.Errorf("listen.audioUdp ไม่ถูกต้อง (%q): %w", c.Listen.AudioUDP, err)
	}
	c.AudioAdvertise = net.JoinHostPort(host, port)
	return nil
}

// LANIPs คืน IPv4 ของเครื่องนี้ที่ขึ้นสถานะ up (ตัด loopback, จัดลำดับ LAN จริงก่อน)
//
// อะแดปเตอร์เสมือน (WSL / Hyper-V / VirtualBox / VMware) ถูกจัดไว้ท้ายสุด
// เพื่อให้ค่าเริ่มต้นที่บอกมือถือเป็น IP ของ Wi-Fi/LAN จริง
func LANIPs() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return []string{"127.0.0.1"}
	}
	var physical, virtual []string
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		isVirtual := isVirtualAdapter(ifc.Name)
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || ipnet.IP.To4() == nil {
				continue
			}
			ip := ipnet.IP.String()
			switch {
			case isVirtual:
				virtual = append(virtual, ip)
			default:
				physical = append(physical, ip)
			}
		}
	}
	out := append(physical, virtual...)
	if len(out) == 0 {
		out = append(out, "127.0.0.1")
	}
	return out
}

func isVirtualAdapter(name string) bool {
	n := strings.ToLower(name)
	for _, marker := range []string{
		"vethernet", "wsl", "hyper-v", "vmware", "virtualbox", "vbox",
		"loopback", "bluetooth", "tap", "tun", "tailscale", "zerotier",
		"docker", "npcap", "radmin", "hamachi",
	} {
		if strings.Contains(n, marker) {
			return true
		}
	}
	return false
}

// FirstLANIP IP แรกที่ใช้บอกมือถือ (LAN หลักของ Mini PC)
func FirstLANIP() string { return LANIPs()[0] }

func (c Config) Camera(code string) (Camera, bool) {
	cam, ok := c.CameraByCode[strings.ToLower(strings.TrimSpace(code))]
	return cam, ok
}

func (c Config) CameraCodes() []string {
	out := make([]string, 0, len(c.Cameras))
	for _, cam := range c.Cameras {
		out = append(out, cam.Code)
	}
	return out
}

// InputByCamera แปลงรหัสกล้อง -> vMix input number (0 = ไม่ผูก)
func (c Config) InputByCamera(code string) int {
	if cam, ok := c.Camera(code); ok {
		return cam.VMixInput
	}
	return 0
}

func (c Config) WriteSample(path string) error {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	raw, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(raw, '\n'), 0o644)
}
