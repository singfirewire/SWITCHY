# SWITCHY — สถาปัตยกรรมระบบ

## 1. ภาพรวมการไหลของข้อมูล

```
                            ┌─────────────────── Mini PC (Windows/Linux) ───────────────────┐
                            │                                                               │
 ┌──────────┐  TCP 8099     │  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐ │
 │  vMix    │ ─────────────►│  │ tally client │──►│  tally.Hub   │──►│  wsserver        │ │
 │ (Switcher)│  "TALLY OK…" │  │ (reconnect + │   │ (state+seq)  │   │  /ws broadcast   │ │
 │          │  HTTP 8088    │  │  ชื่อ input) │   └──────────────┘   │  /api/state      │ │
 │          │ ◄─────────────│  └──────────────┘                      │  /api/tally (รับ) │ │
 └──────────┘  (ชื่อ input)  │                                        └────────┬─────────┘ │
                            │                                                  │ JSON      │
                            │  ┌───────────────────────────────────────────────┴─────────┐ │
                            │  │ audioengine.Relay  (UDP :50500)                          ││
                            │  │  · ตรวจ clientNum + IP ที่ลงทะเบียนผ่าน WS (กัน spoof)   ││
                            │  │  · forward เฉพาะแพ็กเก็ตที่กด PTT (หรือ full-duplex)     ││
                            │  │  · ส่งสำเนาให้ RoIP sink (วิทยุจริง) ถ้าเปิดใช้          ││
                            │  └───────────────┬──────────────────────────┬───────────────┘ │
                            │                  │                          │                 │
                            │        ┌─────────┴────────┐      ┌──────────┴──────────┐      │
                            │        │ roip.Controller  │      │ roip.Gateway        │      │
                            │        │ PTT via RTS/DTR  │      │ UDP ⇄ USB Soundcard │      │
                            │        └─────────┬────────┘      └──────────┬──────────┘      │
                            └──────────────────┼──────────────────────────┼─────────────────┘
                                               │ USB-Serial (CH340/FTDI)  │ USB Audio
                                          ┌────┴─────┐              ┌─────┴──────┐
                                          │ วิทยุ WT │◄─── analog ──│ Soundcard  │
                                          └──────────┘              └────────────┘

 ┌──────────────────────── มือถือ Android ───────────────────────┐
 │  MainActivity (ตั้งค่า IP/กล้อง)                               │
 │  TallyActivity (จอสีตาม Tally + ปุ่ม PTT)                      │
 │        ▲  Bus (main-thread callbacks)                          │
 │  IntercomService (Foreground: WifiLock + WakeLock + AudioMode)  │
 │        ├── ControlClient  ── WebSocket ──► wsserver             │
 │        └── AudioEngine                                          │
 │              ├── AudioRecord(VOICE_COMMUNICATION) → NoiseGate → codec → UDP
 │              └── UDP → JitterBuffer/ผู้พูด → มิกซ์ → AudioTrack (LOW_LATENCY)
 └────────────────────────────────────────────────────────────────┘
```

## 2. ทำไมแยกเสียงออกจาก WebSocket

| ประเด็น | WebSocket (TCP) | UDP (ที่เลือกใช้) |
|---|---|---|
| ลักษณะข้อมูล | Tally/คำสั่ง (ต้องไม่หาย) | เสียง 20 ms/เฟรม (หายบ้างได้) |
| อาการเมื่อแพ็กเก็ตหาย | ค้างรอ retransmit → ทั้งห้องเงียบเป็นวินาที | เสียงขาดเฟรมเดียว (~20 ms) แล้วไปต่อ |
| Latency | สูงและแกว่ง | ต่ำและคงที่ |

จึงใช้ **TCP/WebSocket สำหรับควบคุม** และ **UDP สำหรับเสียง** — เป็นสูตรเดียวกับ VoIP ทั่วไป

## 3. กลไกหลักฝั่งเสียง

| กลไก | ที่อยู่ | ทำอะไร |
|---|---|---|
| PTT gate | `audioengine/relay.go` | แพ็กเก็ตที่ไม่มี flag TALK ถูกทิ้งที่เซิร์ฟเวอร์ → ไม่มีเสียงห้องหลุดออกอากาศ |
| Anti-spoof IP | `audioengine/registry.go` | clientNum + IP ต้องตรงกับที่ลงทะเบียนผ่าน WebSocket |
| Jitter buffer | `android/audio/JitterBuffer.kt` | เก็บ 3 เฟรมก่อนเล่น, รับมือ seq ขาด/ซ้ำ/มาไม่ตรงจังหวะ |
| Mixer | `android/audio/AudioEngine.kt` | หลายคนพูดพร้อมกัน = บวก PCM แล้ว clip (ไม่ต้องมี DSP หนัก) |
| Noise gate | `android/audio/NoiseGate.kt` | ตัดเสียงแอร์/พัดลม ด้วย RMS→dBFS + attack/release |
| AEC/NS | `android/audio/AudioEffects.kt` | เปิด AcousticEchoCanceler/NoiseSuppressor ของระบบ (รายงานตามจริงว่าเครื่องรองรับไหม) |
| Mute while talking | `AudioEngine.muteWhileTalking` | ปิดลำโพงขณะกดพูด → ตัดวงจรเสียงสะท้อนแบบง่ายแต่ได้ผล |
| Foreground Service | `service/IntercomService.kt` | WifiLock(WIFI_MODE_FULL_HIGH_PERF) + WakeLock + MODE_IN_COMMUNICATION |

## 4. ตัวเลข latency ที่ควรได้ (LAN Wi-Fi)

| ขั้น | เวลา |
|---|---|
| อัด 1 เฟรม (20 ms) | 20 ms |
| Jitter buffer (3 เฟรม) | 60 ms |
| ซับเน็ต Wi-Fi (AP เดียวกัน) | 2–8 ms |
| **รวมโดยประมาณ** | **~80–110 ms** |

ปรับได้: ลด `frameMs` เป็น 10 และ `jitterFrames` เป็น 2 → ~50–60 ms (แลกกับความเสถียรบน Wi-Fi ร่วม)

## 5. ความทนทานต่อความผิดพลาด

- เซิร์ฟเวอร์: vMix หลุด → reconnect แบบ exponential backoff; ระหว่างนั้นยังใช้ `POST /api/tally` ได้
- ไคลเอนต์: WebSocket หลุด → Android จะแสดง "ขาดการเชื่อมต่อ" และหยุดไมค์ทันที (ไม่ค้างส่งเสียง)
- UDP relay: ปลายทางที่เงียบเกิน 30 วินาที ถูกถอดออกจากรายการ (กันส่งไปที่ที่ไม่มีคนแล้ว)
- แพ็กเก็ตเสีย: header ตรวจ magic/version/length, นับใน `/healthz` (`badPacket`)

## 6. ความปลอดภัย (ระดับ LAN ที่เชื่อถือได้)

| ประเด็น | มาตรการ |
|---|---|
| คนนอกยิงเสียงเข้าห้อง | ต้องลงทะเบียนผ่าน WS ก่อน + IP ต้องตรง |
| เข้าห้องผิดทีม | ตั้ง `authToken` ใน config แล้วแอปต้องใส่รหัสตรงกัน |
| ดักฟัง | ใน LAN ปัจจุบันยังไม่เข้ารหัสเสียง — ถ้าต้องการจริงจัง เฟส 3 จะเพิ่ม SRTP/DTLS (ดู ROADMAP) |
| เปิดพอร์ตเกินจำเป็น | ใช้แค่ HTTP 8090 + UDP 50500 (+ 9xxx สำหรับ vMix ที่เป็นฝั่งขาออก) |
