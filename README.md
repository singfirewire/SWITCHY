# SWITCHY — Intercom + Tally Light สำหรับทีมถ่ายทอดสด

ระบบสื่อสารเสียง **real-time ผ่าน Wi-Fi (LAN เดียวกัน)** พร้อม **ไฟ Tally (Program / Preview / Safe)**
จาก Video Switcher (vMix) มาโชว์บนมือถือ Android ทุกเครื่องในกอง

```
        ┌─────────────┐   TCP 8099 (Tally)     ┌──────────────────────┐
        │  vMix / OBS │ ─────────────────────► │  Mini PC (SWITCHY)   │
        └─────────────┘                        │  Go Gateway          │
                                               │  · Tally → WebSocket │
   ┌─────────────┐   WebSocket (ควบคุม)        │  · Audio UDP relay   │
   │ มือถือ 1    │ ◄──────────────────────────►│  · RoIP (วิทยุ)      │
   │ (Android)   │   UDP (เสียง Intercom)      └──────────────────────┘
   ├─────────────┤ ◄──────────────────────────►        ▲
   │ มือถือ 2 …  │                                      │
   └─────────────┘                                      │ USB Serial (PTT)
                                                 ┌──────┴───────┐
                                                 │ Walkie-Talkie│
                                                 └──────────────┘
```

## สถานะปัจจุบัน (เฟส 1 — ใช้งานได้จริงใน LAN)

| ส่วน | สถานะ | หลักฐานที่วัดได้จริง |
|---|---|---|
| Mini PC Server (Go) | ✅ บิลด์+รันได้ | `go build`+`go vet` ผ่าน, รันจริงที่ `:8090` + UDP `:50500` |
| vMix Tally (TCP 8099) | ✅ ทดสอบกับ vMix จำลอง | รับ `TALLY OK 0120…` → เปลี่ยน safe→preview→program ถูกต้อง, seq เพิ่มทุกครั้ง |
| WebSocket gateway | ✅ | 3 เครื่องเชื่อมต่อพร้อมกัน, welcome/tally/peers/talkers ครบ |
| Audio relay (UDP) | ✅ | 3 เครื่อง: rx 397–399 แพ็กเก็ต/เครื่อง, `rxWhileOtherSilent = 0` |
| PTT gate | ✅ | ไม่มีแพ็กเก็ตที่ไม่กด PTT ถูกส่งต่อเลย (0) |
| RoIP PTT (USB-Serial) | ✅ โค้ดพร้อม | คีย์วิทยุด้วยขา RTS/DTR (CH340/FTDI) — ต้องมีฮาร์ดแวร์จริง |
| แอป Android (Kotlin) | ✅ บิลด์ APK ได้ | 2 แบบ: ปกติ 2.6 MB · native 6.7 MB (minSdk 26 / targetSdk 36) |
| RoIP เสียงวิทยุ ↔ IP | ⏳ เฟส 2 | ต้องมี USB Soundcard + helper (ดู docs/ROIP.md) |
| **Opus + Oboe (native)** | ✅ **บิลด์+ลิงก์ผ่านแล้ว** | Oboe 1.9.3 + libopus 1.6.1 ต่อกับ NDK r30 / CMake 4.1.2 — `.so` ทั้ง arm64-v8a + armeabi-v7a, JNI ครบ 8 ฟังก์ชัน, เปิด `-O2` แล้ว — บนเครื่องจริง lib โหลดได้ + สตรีมเปิดได้ แต่ยังถอยไปใช้ AudioRecord/AudioTrack (ดูข้อ 6 "Oboe คืน handle=0") |
| **โหมดต่อ vMix ตรง** | ✅ ทดสอบบนเครื่องจริง | ติ๊กในแอป + ใส่ IP vMix → รับ Tally ผ่าน TCP 8099 เองไม่ต้องมีเซิร์ฟเวอร์ (ยืนยัน: จอไล่สี SAFE→PROGRAM→PREVIEW ตาม vMix) |
| **ข้อความทางเดียว + ปุ่มตอบกลับ** | ✅ ทดสอบบนเครื่องจริง | แม่ข่ายส่ง → มือถือขึ้นแบนเนอร์ + จอข้อความ (อ่านเท่านั้น) → กดปุ่ม "รับทราบ" → เซิร์ฟเวอร์บันทึกการตอบกลับ |

| รุ่น APK | ขนาด | มีอะไรต่าง |
|---|---|---|
| `assembleDebug` | 2.6 MB | PCM 48 kHz ผ่าน AudioRecord/AudioTrack (LINEAR — ใช้งานได้ทุกเครื่อง) |
| `assembleDebug -PwithNativeAudio=true` | 6.7 MB | + Oboe + Opus (เลือกใช้ Opus อัตโนมัติเมื่อมี lib) |

```bash
# บิลด์แบบ native (ต้องมี NDK + CMake — เครื่องนี้ติดตั้งแล้ว)
cd android
export JAVA_HOME="C:/Users/Bon6have6Mouse/tools/jdk21"
./gradlew --no-daemon assembleDebug -PwithNativeAudio=true
```

ค่าเริ่มต้นใช้ **PCM 16-bit 48 kHz 20 ms ผ่าน UDP** (latency ต่ำสุดใน LAN, ไม่ต้องใช้ NDK)
และออกแบบให้สลับเป็น **Opus** ได้ทันทีเมื่อบิลด์ native (ประหยัด bandwidth ~10 เท่า)

---

## 1. โครงสร้างโปรเจกต์

```
SWITCHY/
├── server/                        # Mini PC Central Server (Go — ไม่มี dependency หนัก)
│   ├── cmd/switchy-server/        #   ตัวเซิร์ฟเวอร์หลัก
│   ├── cmd/switchy-sim/           #   มือถือจำลอง (ใช้ทดสอบโดยไม่ต้องมีโทรศัพท์)
│   ├── internal/tally/            #   อ่าน vMix TCP 8099 + hub กระจายสถานะ
│   ├── internal/wsserver/         #   WebSocket gateway (/ws) + HTTP API
│   ├── internal/audioengine/      #   โปรโตคอลแพ็กเก็ตเสียง + UDP relay
│   ├── internal/roip/             #   PTT ผ่าน USB-Serial (CH340/FTDI)
│   ├── internal/webui/            #   dashboard ทดสอบ (ฝังในไบนารี)
│   └── scripts/fake-vmix.py       #   vMix จำลองสำหรับเทสต์
├── android/                       # แอป Android (Kotlin, ไม่ใช้ NDK ในเฟสนี้)
│   └── app/src/main/java/com/switchy/intercom/
│       ├── MainActivity.kt        #   หน้าตั้งค่า IP / เลือกกล้อง
│       ├── TallyActivity.kt       #   จอ Tally เต็มสี + ปุ่ม PTT
│       ├── service/               #   Foreground Service (ไม่ให้ระบบตัดเสียง/Wi-Fi)
│       ├── audio/                 #   AudioEngine, JitterBuffer, NoiseGate, Opus-ready
│       ├── net/                   #   WebSocket client + Bus (สื่อสารกับ UI)
│       └── data/Prefs.kt          #   จำค่าตั้ง
├── docs/                          # ARCHITECTURE / PROTOCOL / ROADMAP / ROIP
└── tools/                         # ไฟล์ .bat กดใช้ทีเดียว (รันเซิร์ฟเวอร์, บิลด์ APK, ทดสอบ)
```

---

## 2. เริ่มใช้งาน (3 ขั้น)

### ขั้น 1 — ติดตั้ง/บิลด์เซิร์ฟเวอร์ (Mini PC)

```bash
cd server
go build -o dist/switchy-server.exe ./cmd/switchy-server
go build -o dist/switchy-sim.exe    ./cmd/switchy-sim
```

รัน (ครั้งแรกจะสร้าง `config.json` ให้อัตโนมัติ):

```bash
./dist/switchy-server.exe -config config.json
```

เซิร์ฟเวอร์จะพิมพ์ค่าที่ต้องใช้ตั้งในมือถือให้ทันที:

```
   ตั้งค่าในแอป Android:
     IP เซิร์ฟเวอร์ = 192.168.1.50    พอร์ต WS = 8090    พอร์ตเสียง = 50500
```

แก้ `config.json` ให้ชี้ไปที่เครื่อง vMix:

```json
{
  "vmix": { "enabled": true, "host": "192.168.1.40", "tallyPort": 8099, "httpPort": 8088 },
  "cameras": [
    { "code": "cam1", "name": "กล้อง 1", "vmixInput": 1 },
    { "code": "cam2", "name": "กล้อง 2", "vmixInput": 2 },
    { "code": "cam3", "name": "กล้อง 3", "vmixInput": 3 }
  ]
}
```

> `vmixInput` = เลข input ใน vMix (ดูได้จากหน้าจอ vMix) — แอปจะโชว์สีตาม input นั้น
> ถ้าไม่ใช้ vMix ก็ได้: ปิด `vmix.enabled` แล้วยิงสถานะเข้าที่ `POST /api/tally`

### ขั้น 2 — บิลด์/ติดตั้งแอป Android

```bash
cd android
./gradlew assembleDebug          # หรือกด tools\build-apk.bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### ขั้น 3 — ตั้งค่าในมือถือ

1. เปิดแอป → ใส่ **IP ของ Mini PC** (พอร์ต 8090 ตามค่าเริ่มต้น)
2. กด **ตรวจเซิร์ฟเวอร์** → แอปจะดึงรายการกล้องจริงจากเซิร์ฟเวอร์มาให้เลือก
3. เลือกกล้องที่ตัวเองรับผิดชอบ → กด **เชื่อมต่อและเข้าใช้งาน**
4. จอจะเปลี่ยนเป็นสีตาม Tally และมี **ปุ่ม PTT** ให้กดค้างเพื่อพูด

---

## 3. ทดสอบโดยไม่ต้องมี vMix

```bash
# เทอร์มินัล 1: vMix จำลอง (สลับ program/preview ทุก 3 วินาที)
python server/scripts/fake-vmix.py --inputs 8 --cycle 3

# เทอร์มินัล 2: เซิร์ฟเวอร์
server/dist/switchy-server.exe -config server/run/config.json

# เทอร์มินัล 3: มือถือจำลอง 3 เครื่อง (ทดสอบ Tally + เสียง + PTT gate)
server/dist/switchy-sim.exe -ws 192.168.1.50:8090 -clients 3 -seconds 20
```

ผลที่ต้องได้ (ยืนยันจากการรันจริง — 3 เครื่อง 16 วินาที):

```
sim-1    sent=799   rxFromOther=399   rxWhileOtherTalk=199   rxWhileOtherSilent(gate)=0
sim-2    sent=797   rxFromOther=399   rxWhileOtherTalk=399   rxWhileOtherSilent(gate)=0
sim-3    sent=797   rxFromOther=397   rxWhileOtherTalk=199   rxWhileOtherSilent(gate)=0
healthz: {"audio":{"rxPackets":2392,"txPackets":1195,"badPacket":0,"denied":0},"clients":0,
          "source":"vmix","tallySeq":17,"vmixUp":true,"ok":true}
✅ ผลทดสอบ: tally + audio relay + PTT gate ทำงานถูกต้อง
```

- `rxFromOther > 0` = เสียงวิ่งระหว่างเครื่องได้จริง
- `rxWhileOtherSilent = 0` = แพ็กเก็ตที่ไม่กด PTT ถูกกันไว้ (ไม่มีเสียงหลุดออกอากาศ)

Dashboard ทดสอบ (เปิดจากเบราว์เซอร์เครื่องไหนก็ได้ในวง):
`http://<ip-ของ-mini-pc>:8090/`

---

## 4. API ย่อ (ดูเต็มที่ docs/PROTOCOL.md)

| ปลายทาง | ใช้ทำอะไร |
|---|---|
| `ws://<server>:8090/ws` | ควบคุม + รับ Tally (JSON) |
| `udp://<server>:50500` | ส่ง/รับเสียง Intercom (แพ็กเก็ต 16-byte header + payload) |
| `GET /api/state` | ดูสถานะปัจจุบัน (ใช้ในหน้าตั้งค่า) |
| `POST /api/tally` | ยิงสถานะ Tally จาก switcher/สคริปต์อื่น `{"camera":"cam1","state":"program"}` |
| `GET /healthz` | สถิติสำหรับ monitor |

---

## 5. ข้อควรรู้จากการใช้งานจริง (จากการทดสอบ)
1. **IP ที่แอปต้องใช้คือ IP ของ Mini PC** ในวง Wi-Fi เดียวกัน — เซิร์ฟเวอร์จะพิมพ์ให้ตอนสตาร์ท
   (ถ้าเครื่องมี WSL/Hyper-V จะข้ามอะแดปเตอร์เสมือนให้แล้ว)
2. **มือถือควรใช้หูฟัง** — กันเสียงจากลำโพงเข้าไมค์ (แอปเปิด AEC ของระบบให้แล้ว แต่หูฟังชัดที่สุด)
3. **ห้ามล็อกจอทิ้งไว้โดยไม่ให้สิทธิ์แจ้งเตือน** — Android จะตัดไมค์; แอปใช้ Foreground Service + WifiLock ผู้ใช้จะเห็นแถบแจ้งเตือนค้างไว้
4. **เซิร์ฟเวอร์ตรวจ IP ของแพ็กเก็ตเสียง** ให้ตรงกับที่ลงทะเบียนผ่าน WebSocket (กันคนนอกยิงเสียงเข้าห้อง)
   ถ้ามือถือสลับ Wi-Fi บ่อยจน IP เปลี่ยน แล้วอยากให้ยอมรับอัตโนมัติ ตั้ง `audio.disableIpCheck = true`
5. vMix ส่ง Tally ผ่าน TCP 8099 เท่านั้น (ไม่ใช่ HTTP) — เซิร์ฟเวอร์ยังใช้ HTTP 8088 เพื่อดึง "ชื่อ input" มาโชว์
6. **เปิดแอปครั้งแรก** จะเจอหน้าตั้งค่า (ใส่ IP) — หลังจากกดเชื่อมต่อสำเร็จแล้ว **ครั้งต่อไปเปิดแอปจะเข้าจอ Tally ทันที**
   ถ้าต่อไม่ติด จอ Tally จะขึ้นกล่อง "เชื่อมต่อไม่ได้" พร้อมปุ่ม **ไปกรอก IP / ลองใหม่ / ปิด** (ถามซ้ำไม่ถี่กว่า 60 วินาที)
7. **โหมดต่อ vMix ตรง** ไม่ต้องมี Mini PC และไม่ต้องให้สิทธิ์ไมค์ (ใช้แค่ Tally) — เลือกได้ในหน้าตั้งค่า

---

## 6. บทเรียนจากการบิลด์/ทดสอบจริง (กันเสียเวลารอบหน้า)

| อาการ | สาเหตุ | ทางแก้ที่ใช้ |
|---|---|---|
| `panic: send on closed channel` ตอนเครื่องออกจากห้อง | `Broadcast` ส่งข้อความชนกับตอนปิด channel ของไคลเอนต์ | เลิกปิด channel ตรง ๆ ใช้ `done chan struct{}` + `closed atomic.Bool` (แก้ทั้ง `wsserver` และ `tally.Hub`) |
| Kotlin: `Unresolved reference 'setAudioAttributes'` | `AudioRecord.Builder.setAudioAttributes()` เป็น SystemApi ไม่อยู่ใน public SDK | ตัดออก แล้วใช้ `AudioSource = VOICE_COMMUNICATION` ซึ่งให้ AEC/NS อยู่แล้ว (ฝั่ง `AudioTrack` ใช้ `setAudioAttributes` ได้ปกติ) |
| XML: `Element type "TextView" must be followed by …` | ใช้ `\"` ใน XML attribute (XML ไม่รองรับ backslash escape) | เปลี่ยนเป็น `&quot;` |
| เซิร์ฟเวอร์ปฏิเสธ UDP ทุกแพ็กเก็ต (`denied` พุ่ง) | WS มาจาก `127.0.0.1` แต่ UDP ออกด้วย IP ของ LAN | ให้ socket UDP ผูกตระกูลเดียวกับปลายทาง (`udp4`) และทดสอบด้วย IP จริงของ LAN |
| **Oboe คืนค่า `handle=0`** ทั้งที่ log ขึ้น "ไมค์พร้อม/ลำโพงพร้อม" | มี Java exception ค้างจาก callback ตอนสตรีมเริ่ม ทำให้ค่าที่คืนจาก JNI ถูกทิ้ง | ดัก exception ที่ฝั่ง Kotlin แล้ว log (`Oboe โยน exception …`) ยังต้องตามต่อ — ตอนนี้ระบบถอยไป AudioRecord/AudioTrack ให้อัตโนมัติ (ไม่ล่ม) |
| **ติดตั้ง APK ไม่ได้: `INSTALL_FAILED_USER_RESTRICTED`** (POCO/HyperOS 3) | HyperOS ปิดเส้นทาง `adb install` ไว้ (หาเมนู "ติดตั้งผ่าน USB" ไม่เจอบน OS3) | ใช้ `pm install-create` → `pm install-write -S <bytes>` → `pm install-commit` (สคริปต์อยู่ใน `tools/6-install-apk.bat` ช่วงท้าย) |
| ทดสอบมือถือทั้งที่คนละวง Wi-Fi กับ PC | UDP/TCP ไปไม่ถึงกัน | `adb reverse tcp:8090 tcp:8090` (+ `tcp:8099` สำหรับโหมด vMix ตรง) แล้วตั้ง IP ในแอปเป็น `127.0.0.1` |
| ไดอะล็อกขออนุญาตไมค์โผล่ทุกครั้งที่ทดสอบ | สิทธิ์ถูกให้แบบ "เฉพาะครั้งนี้" (ONE_TIME) | `adb shell pm revoke …RECORD_AUDIO` แล้ว `pm grant` ใหม่ (จะไม่ขึ้น ONE_TIME อีก) |
| บิลด์ Android ครั้งแรกช้า (~3 นาที) | ดาวน์โหลด AGP/Kotlin/androidx/OkHttp/Oboe/Opus | ครั้งต่อไปเร็ว (~1–1.5 นาที) เพราะแคชไว้แล้ว |

คำสั่งบิลด์ที่ใช้ได้บนเครื่องนี้:

```bash
cd android
export JAVA_HOME="$HOME/tools/jdk21"     # MSYS/Git-bash
./gradlew --no-daemon assembleDebug
```
