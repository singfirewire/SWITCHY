# SWITCHY — โปรโตคอล (Protocol Spec v1)

ทุกอย่างในระบบนี้เป็น **v1** — เลข `proto` ถูกส่งไป-กลับใน `hello`/`welcome` เพื่อกัน APK กับเซิร์ฟเวอร์คนละรุ่น

---

## 1. WebSocket — `ws://<server>:8090/ws`

ข้อความ = JSON 1 ก้อนต่อ 1 frame (ไม่ต้องมี newline)

### 1.1 ไคลเอนต์ → เซิร์ฟเวอร์

| `t` | ฟิลด์ | ความหมาย |
|---|---|---|
| `hello` | `proto`, `name`, `camera`, `codec` (`pcm`/`opus`), `audioPort`, `token` | ลงทะเบียนเข้าใช้งาน (ต้องส่งเป็นข้อความแรก) |
| `ptt` | `on` (bool) | กด/ปล่อยปุ่มพูด — ใช้บอกสถานะให้คนอื่นเห็น (เสียงจริงใช้ flag ใน UDP) |
| `cam` | `camera` | เปลี่ยนกล้องที่รับผิดชอบกลางคัน |
| `ping` | `ts` | วัดว่าสายยังดี (ได้ `pong` กลับ) |
| `reply` | `code` (`ack`/`disagree`/`help`), `msgId` | ตอบกลับข้อความด้วยปุ่มสำเร็จรูป — **พิมพ์เองไม่ได้** |
| `bye` | – | ขอออกอย่างสุภาพ |

```json
{"t":"hello","proto":1,"name":"กล้อง 1","camera":"cam1","codec":"pcm","audioPort":0}
{"t":"ptt","on":true}
{"t":"cam","camera":"cam2"}
```

### 1.2 เซิร์ฟเวอร์ → ไคลเอนต์

| `t` | ฟิลด์ | ความหมาย |
|---|---|---|
| `welcome` | `proto`, `clientId`, `clientNum`, `server`, `ver`, `audioAddr`, `frameMs`, `sampleRate`, `fullDuplex`, `camera`, `tally`, `peers` | ค่าที่ต้องใช้จริงทั้งหมด (clientNum ใช้ในแพ็กเก็ต UDP) |
| `tally` | `tally{states,seq,source,vmixUp,at}` | สถานะ Tally เปลี่ยน |
| `peers` | `peers[]` | รายชื่อคนในห้อง |
| `audio` | `audio{talkers[]}` | ใครกำลังพูด (clientNum) |
| `cam` | `camera` | ยืนยันว่าเปลี่ยนกล้องแล้ว |
| `error` | `error` | ข้อความผิดพลาด (เช่น token ไม่ตรง, ไม่รู้จักรหัสกล้อง) |
| `pong` | `ts` | ตอบ ping |
| `msg` | `message{id,from,to,text,kind,at}` | ข้อความใหม่จากศูนย์ควบคุม (ขึ้นแบนเนอร์บนจอมือถือ) |
| `reply` | `reply{id,msgId,clientNum,name,code,label}` | มีเครื่องไหนกดตอบกลับ (กระจายให้ทุกเครื่องเห็น) |

> `welcome` ยังแนบ `messages[]` และ `replies[]` (20 รายการล่าสุด) เพื่อให้เครื่องที่เพิ่งเข้าห้องเห็นข้อความปัจจุบันทันที

```json
{"t":"welcome","proto":1,"clientId":3,"clientNum":3,"server":"SWITCHY Gateway","ver":"0.1.0",
 "audioAddr":"192.168.1.50:50500","frameMs":20,"sampleRate":48000,"fullDuplex":false,
 "camera":"cam1",
 "tally":{"states":{"cam1":"program","cam2":"preview","cam3":"safe"},"seq":42,"source":"vmix","vmixUp":true},
 "peers":[{"id":3,"num":3,"name":"กล้อง 1","camera":"cam1","ptt":false,"codec":"pcm","ip":"192.168.1.61"}]}
```

### 1.3 คำศัพท์สถานะ Tally

| ค่า | สีที่แอปแสดง | ความหมาย |
|---|---|---|
| `program` | แดง | กำลังออกอากาศ (LIVE) |
| `preview` | เขียว | รอสลับขึ้น (NEXT) |
| `safe` | เทา | ไม่ได้ใช้งาน |
| `unknown` | ดำ | ยังไม่ได้รับข้อมูลจาก switcher |

---

## 2. UDP เสียง — `<server>:50500`

ไคลเอนต์ทุกเครื่องส่งไปที่พอร์ตเดียวนี้ เซิร์ฟเวอร์เรียนรู้ `ip:port` จากแพ็กเก็ตแรก
แล้วกระจายต่อให้ **ทุกเครื่องยกเว้นผู้ส่ง** (ไม่ต้องรู้ IP กันเอง)

### 2.1 ส่วนหัว 16 ไบต์ (little-endian)

| offset | ขนาด | ชื่อ | หมายเหตุ |
|---|---|---|---|
| 0 | 1 | magic0 | `'S'` (0x53) |
| 1 | 1 | magic1 | `'W'` (0x57) |
| 2 | 1 | version | `1` |
| 3 | 1 | flags | bit0 = TALK, bit1 = OPUS, bit2 = LAST, bit3 = CONTROL |
| 4 | 2 | clientNum | เลขที่เซิร์ฟเวอร์แจกใน `welcome` |
| 6 | 2 | seq | นับขึ้นทุกเฟรมที่ส่ง (wrap 16-bit) |
| 8 | 4 | timestampMs | เวลาที่เก็บเสียง (uint32, wrap ได้) |
| 12 | 2 | payloadLen | ความยาว payload |
| 14 | 2 | reserved | 0 |

### 2.2 payload

| โหมด | รูปแบบ | ขนาด/เฟรม (48 kHz, 20 ms) |
|---|---|---|
| `pcm` | PCM 16-bit signed mono, little-endian | 1,920 ไบต์ (~768 kbps) |
| `opus` | Opus packet (1 เฟรม, 20 ms, mono) | ~60–90 ไบต์ (~32 kbps) |

### 2.3 กฎที่เซิร์ฟเวอร์บังคับ

1. `clientNum` ต้องเคยลงทะเบียนผ่าน WebSocket และ **IP ต้นทางต้องตรง** กับตอนลงทะเบียน (ปิดได้ด้วย `audio.disableIpCheck`)
2. แพ็กเก็ตที่ไม่มี flag `TALK` จะ **ไม่ถูกส่งต่อ** (กันเสียงห้องหลุด) ยกเว้นตั้ง `fullDuplex = true`
3. ผู้พูดพร้อมกันเกิน `maxTalkers` → ส่วนเกินถูกทิ้ง (นับในสถิติ ไม่ทำให้ระบบล่ม)
4. เฟรมสุดท้ายของชุดพูดใส่ `TALK|LAST` เพื่อให้ปลายทางรู้ว่าจบช่วง

---

## 3. HTTP API (ใช้กับ dashboard/สคริปต์/switcher อื่น)

### `GET /api/state`
```json
{"tally":{"states":{"cam1":"program"},"seq":42,"source":"vmix","vmixUp":true},
 "peers":[{"num":3,"name":"กล้อง 1","camera":"cam1","ptt":false,"codec":"pcm","ip":"192.168.1.61"}]}
```

### `POST /api/tally` — ยิงสถานะจากที่อื่น (OBS, Companion, Stream Deck, สคริปต์)
```bash
# ตั้งทีละกล้อง
curl -X POST http://192.168.1.50:8090/api/tally \
     -H "Content-Type: application/json" \
     -d '{"camera":"cam1","state":"program"}'

# ตั้งหลายกล้องพร้อมกัน
curl -X POST http://192.168.1.50:8090/api/tally \
     -H "Content-Type: application/json" \
     -d '{"states":{"cam1":"program","cam2":"preview","cam3":"safe"}}'
```
ค่าที่รับ: `program|live|pgm|on|1`, `preview|pvw|2`, `safe|off|idle|false|0`
รหัสกล้องที่ไม่รู้จักจะถูกปฏิเสธ แล้วคืนใน `rejected[]`

> หมายเหตุ: เมื่อ vMix ส่ง Tally มา (source = `vmix`) ค่าที่ push ทาง API จะถูกล้าง
> เพราะ vMix ถือเป็นแหล่งข้อมูลหลัก

### `POST /api/message` — แม่ข่าย/ศูนย์ควบคุมส่งข้อความลงเครื่องลูก (ทางเดียว)
```bash
curl -X POST http://192.168.1.50:8090/api/message \
     -H "Content-Type: application/json" \
     -d '{"text":"เตรียมกล้อง 2 อีก 5 นาที","from":"โปรดิวเซอร์","kind":"info"}'
```
| ฟิลด์ | ความหมาย |
|---|---|
| `text` | ข้อความ (ว่างไม่ได้) |
| `from` | ผู้ส่งที่จะโชว์บนมือถือ (ว่าง = "ศูนย์ควบคุม") |
| `to` | `all` หรือเลข clientNum (ว่าง = all) |
| `kind` | `info` (ปกติ) หรือ `alert` (ขึ้น ⚠ บนมือถือ) |

### `GET /api/messages?since=0&replies=0` — ดูข้อความ + การตอบกลับ
```json
{"messages":[{"id":1,"at":"2026-09-26T09:31:17+07:00","from":"โปรดิวเซอร์","to":"all",
              "text":"เตรียมกล้อง 2 อีก 5 นาที","kind":"info"}],
 "replies":[{"id":1,"msgId":1,"at":"2026-09-26T09:33:02+07:00","clientNum":1,
             "name":"มือถือ","code":"ack","label":"รับทราบ"}]}
```
`since` / `replies` = รับเฉพาะที่ id ใหม่กว่า (ใช้ทำ polling แบบประหยัด)

> หน้า dashboard (`http://<ip>:8090/`) มีช่องพิมพ์ข้อความ + ตารางดูการตอบกลับในตัว ไม่ต้องใช้ curl

### `GET /healthz`
{"ok":true,"server":"SWITCHY Gateway","proto":1,"vmixUp":true,"source":"vmix","clients":3,
 "audio":{"rxPackets":1387,"txPackets":343,"badPacket":0,"denied":1,"listen":":50500"}}
```

---

## 4. vMix TCP API (ฝั่งที่เซิร์ฟเวอร์ใช้คุยกับ vMix)

| ทิศทาง | ข้อความ |
|---|---|
| ส่ง | `SUBSCRIBE TALLY\r\n` |
| รับ | `TALLY OK <ตัวเลขเรียงตาม input>` เช่น `TALLY OK 0210` |

ตำแหน่งตัวอักษรที่ n (เริ่มที่ 1) = สถานะของ input n

| ตัวอักษร | ความหมาย |
|---|---|
| `0` | ปิด (safe) |
| `1` | ขึ้นจอหลัก (program) |
| `2` | รอสลับ (preview) |

ชื่อ input ดึงจาก HTTP API ของ vMix (`http://<vmix>:8088/api` → `<inputs><input number="1" shortTitle="…">`)
โดย poll ทุก 5 วินาที (ตั้งได้ที่ `vmix.pollTitlesMs`)

---

## 5. ตัวอย่างทดสอบด้วยมือ (ไม่ต้องมีแอป)

```bash
# ดูสถานะปัจจุบัน
curl http://192.168.1.50:8090/api/state

# ยิง Tally ให้ cam2 เป็น program
curl -X POST http://192.168.1.50:8090/api/tally -H "Content-Type: application/json" \
     -d '{"camera":"cam2","state":"program"}'
```
