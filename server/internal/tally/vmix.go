package tally

import (
	"bufio"
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/switchy/server/internal/config"
)

// VMixClient อ่าน Tally จาก vMix TCP API (port 8099) แล้วดันเข้า Hub
//
// vMix protocol:
//
//	ส่ง  "SUBSCRIBE TALLY\r\n"  -> ได้บรรทัด "TALLY OK 0120" ทุกครั้งที่สถานะเปลี่ยน
//	ตัวอักษรตัวที่ n (เริ่ม 1) = สถานะของ input n : 0=off 1=program 2=preview
type VMixClient struct {
	cfg config.VMixConfig
	hub *Hub
	log *slog.Logger
}

func NewVMixClient(cfg config.VMixConfig, hub *Hub, log *slog.Logger) *VMixClient {
	return &VMixClient{cfg: cfg, hub: hub, log: log.With("mod", "vmix")}
}

func (c *VMixClient) Run(ctx context.Context) {
	if !c.cfg.Enabled {
		c.log.Info("vMix ถูกปิดในคอนฟิก (ใช้ HTTP push API แทน)")
		return
	}
	go c.titleLoop(ctx)

	backoff := time.Duration(c.cfg.ReconnectMS) * time.Millisecond
	for {
		if err := c.tallyLoop(ctx); err != nil && ctx.Err() == nil {
			c.log.Warn("ขาดการเชื่อมต่อ vMix tally", "err", err.Error())
		}
		c.hub.SetVMixUp(false)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 15*time.Second {
			backoff *= 2
		}
	}
}

func (c *VMixClient) addr() string {
	return net.JoinHostPort(c.cfg.Host, strconv.Itoa(c.cfg.TallyPort))
}

func (c *VMixClient) tallyLoop(ctx context.Context) error {
	d := net.Dialer{Timeout: 4 * time.Second, KeepAlive: 10 * time.Second}
	conn, err := d.DialContext(ctx, "tcp", c.addr())
	if err != nil {
		return fmt.Errorf("เชื่อม %s: %w", c.addr(), err)
	}
	defer conn.Close()

	// ปิดการเชื่อมต่อทันทีเมื่อ context ถูกยกเลิก
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-stop:
		}
	}()

	if _, err := io.WriteString(conn, "SUBSCRIBE TALLY\r\n"); err != nil {
		return err
	}
	c.hub.SetVMixUp(true)
	c.log.Info("เชื่อมต่อ vMix tally แล้ว", "addr", c.addr())

	r := bufio.NewReaderSize(conn, 4096)
	for {
		line, err := r.ReadString('\n')
		if trimmed := strings.TrimSpace(line); trimmed != "" {
			c.handleLine(trimmed)
		}
		if err != nil {
			if err == io.EOF {
				return fmt.Errorf("vMix ปิดการเชื่อมต่อ")
			}
			return err
		}
	}
}

func (c *VMixClient) handleLine(line string) {
	upper := strings.ToUpper(line)
	switch {
	case strings.HasPrefix(upper, "TALLY OK"):
		payload := strings.TrimSpace(line[len("TALLY OK"):])
		c.hub.SetVMixTally(parseTallyPayload(payload))
	case strings.HasPrefix(upper, "TALLY ERR"):
		c.log.Warn("vMix ตอบ error", "line", line)
	case strings.HasPrefix(upper, "VERSION"):
		c.log.Info("vMix", "line", line)
	}
}

// parseTallyPayload แปลง "0120" -> [safe program preview safe]
func parseTallyPayload(s string) []State {
	if s == "" {
		return nil
	}
	out := make([]State, 0, len(s))
	for i := 0; i < len(s); i++ {
		ch := s[i]
		if ch == ' ' || ch == ',' {
			continue
		}
		out = append(out, FromVMixDigit(ch))
	}
	return out
}

// ---- ชื่อ input (ใช้ HTTP API เพื่อให้ dashboard/แอปแสดงชื่อกล้องจริง) ----

func (c *VMixClient) titleLoop(ctx context.Context) {
	interval := time.Duration(c.cfg.PollTitlesMS) * time.Millisecond
	client := &http.Client{Timeout: 4 * time.Second}
	url := fmt.Sprintf("http://%s:%d/api", c.cfg.Host, c.cfg.HTTPPort)

	tick := time.NewTicker(interval)
	defer tick.Stop()
	for {
		if titles, err := fetchTitles(ctx, client, url); err == nil && len(titles) > 0 {
			c.hub.SetTitles(titles)
		} else if err != nil && ctx.Err() == nil {
			c.log.Debug("ดึงชื่อ input จาก vMix HTTP API ไม่ได้", "err", err.Error())
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

type vmixXMLDoc struct {
	Inputs []struct {
		Number     int    `xml:"number,attr"`
		ShortTitle string `xml:"shortTitle,attr"`
		Title      string `xml:"title,attr"`
	} `xml:"inputs>input"`
}

func fetchTitles(ctx context.Context, client *http.Client, url string) (map[int]string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("vMix HTTP API ตอบ %s", resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	var doc vmixXMLDoc
	if err := xml.Unmarshal(body, &doc); err != nil {
		return nil, fmt.Errorf("parse XML: %w", err)
	}
	titles := make(map[int]string, len(doc.Inputs))
	for _, in := range doc.Inputs {
		name := in.ShortTitle
		if name == "" {
			name = in.Title
		}
		if in.Number > 0 && name != "" {
			titles[in.Number] = name
		}
	}
	return titles, nil
}
