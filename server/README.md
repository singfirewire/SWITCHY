# SWITCHY — Mini PC Central Server (Go)

ไบนารีเดียว ไม่ต้องติดตั้งอะไรเพิ่มบน Mini PC (ยกเว้นจะใช้ RoIP ที่ต้องมี USB-Serial)

## บิลด์/รัน

```bash
go build -o dist/switchy-server.exe ./cmd/switchy-server
go build -o dist/switchy-sim.exe    ./cmd/switchy-sim
./dist/switchy-server.exe -config config.json
```

| flag | ความหมาย |
|---|---|
| `-config` | ไฟล์คอนฟิก JSON (ถ้าไม่มี จะสร้างตัวอย่างให้) |
| `-http` | override ที่อยู่ HTTP/WS เช่น `:8090` |
| `-audio` | override พอร์ต UDP เสียง เช่น `:50500` |
| `-vmix` | override IP ของเครื่อง vMix |
| `-no-vmix` | ไม่เชื่อม vMix (ใช้ `POST /api/tally` ทดสอบแทน) |
| `-v` | log ละเอียด (debug) |
| `-print-config` | พิมพ์ค่าที่โหลดได้แล้วจบการทำงาน |

## คอนฟิกที่สำคัญ (`config.json`)

```json
{
  "serverName": "SWITCHY Gateway",
  "authToken": "",
  "advertiseIp": "",
  "listen": { "http": ":8090", "audioUdp": ":50500" },
  "vmix": { "enabled": true, "host": "127.0.0.1", "tallyPort": 8099, "httpPort": 8088,
            "pollTitlesMs": 5000, "reconnectMs": 3000 },
  "audio": { "frameMs": 20, "sampleRate": 48000, "opusBitrate": 32000,
             "fullDuplex": false, "maxTalkers": 4, "jitterFrames": 3, "disableIpCheck": false },
  "cameras": [ { "code": "cam1", "name": "กล้อง 1", "vmixInput": 1 } ]
}
```

| ฟิลด์ | ผลกระทบ |
|---|---|
| `authToken` | ถ้าตั้ง แอปต้องใส่รหัสตรงกันในช่อง "รหัสผ่านห้อง" |
| `advertiseIp` | IP ที่จะบอกไคลเอนต์ (ว่าง = หา LAN IP ให้เอง โดยข้ามอะแดปเตอร์เสมือน WSL/Hyper-V) |
| `audio.fullDuplex` | true = ไมค์เปิดตลอด ไม่ต้องกด PTT (ใช้กับงานที่ต้องการห้องเปิด) |
| `audio.maxTalkers` | จำนวนคนพูดพร้อมกันสูงสุดที่ relay ต่อให้ |
| `audio.disableIpCheck` | true = ยอมรับ IP ใหม่ของไคลเอนต์ (มือถือสลับ Wi-Fi บ่อย) — ผ่อนความปลอดภัยลง |
| `cameras[].vmixInput` | เลข input ใน vMix (0 = ไม่ผูก ใช้ค่าจาก API push) |

## API (ย่อ — เต็มที่ ../docs/PROTOCOL.md)

```
GET  /healthz            สถิติ+สถานะ (ใช้ monitor)
GET  /api/state          สถานะ Tally + รายชื่อเครื่องในห้อง
POST /api/tally          ยิงสถานะ Tally จากที่อื่น
WS   /ws                 คุม + รับ Tally
UDP  :50500              เสียง Intercom
GET  /                   dashboard ทดสอบ (ฝังในไบนารี)
```

## โมดูลภายใน

| แพ็กเกจ | หน้าที่ |
|---|---|
| `internal/config` | โหลด/ตรวจคอนฟิก + หา LAN IP ที่เหมาะกับมือถือ |
| `internal/tally` | ต่อ vMix TCP 8099 (reconnect + backoff), ดึงชื่อ input ทาง HTTP 8088, `Hub` เก็บสถานะ + seq |
| `internal/wsserver` | WebSocket gateway, ลงทะเบียนไคลเอนต์, กระจาย Tally/peers/talkers, HTTP API |
| `internal/audioengine` | แพ็กเก็ตเสียง + `Registry` (ตรวจ clientNum/IP) + `Relay` (forward + sink สำหรับ RoIP) |
| `internal/roip` | PTT ผ่าน USB-Serial + VOX/tail + Gateway บริดจ์เสียงไปวิทยุ |
| `internal/webui` | dashboard (embed เข้าไบนารี ไม่ต้องมีไฟล์แนบ) |
| `cmd/switchy-sim` | มือถือจำลอง (WS + UDP) ใช้ทดสอบโดยไม่ต้องมีโทรศัพท์ |

## ทดสอบเร็วสุด (ไม่ต้องมี vMix)

```bash
python scripts/fake-vmix.py --inputs 8 --cycle 3      # เทอร์มินัล 1
./dist/switchy-server.exe -config run/config.json     # เทอร์มินัล 2
./dist/switchy-sim.exe -ws 127.0.0.1:8090 -clients 2 -seconds 14   # เทอร์มินัล 3
```
