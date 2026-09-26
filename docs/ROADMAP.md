# SWITCHY — แผนงาน (Roadmap)

## เฟส 1 — โครงหลักที่ใช้งานได้จริง ✅ (อยู่ในโปรเจกต์นี้)

- [x] Mini PC Server (Go): vMix Tally TCP 8099 + WebSocket gateway + HTTP push API + dashboard
- [x] UDP audio relay: PTT gate, ตรวจ IP กัน spoof, จำกัดจำนวนผู้พูดพร้อมกัน, สถิติใน `/healthz`
- [x] แอป Android: หน้าตั้งค่า (IP/กล้อง), จอ Tally เต็มสี, ปุ่ม PTT, Noise Gate, AEC ของระบบ
- [x] Foreground Service + WifiLock/WakeLock (ไม่ตัดเสียงเมื่อดับจอ)
- [x] RoIP PTT ผ่าน USB-Serial (RTS/DTR) + VOX + tail
- [x] เครื่องมือทดสอบ: `fake-vmix.py`, `switchy-sim` (มือถือจำลอง), dashboard

## เฟส 2 — คุณภาพเสียง/ความหน่วงระดับมืออาชีพ

- [x] **Native audio (Oboe + libopus)** — บิลด์ผ่านแล้วทั้ง arm64-v8a และ armeabi-v7a
      - Oboe 1.9.3 (AAudio) เปิด 2 สตรีม: ไมค์ (`InputPreset::VoiceCommunication` → AEC/NS ของระบบ)
        และลำโพง (`Usage::VoiceCommunication`) พร้อม reopen อัตโนมัติเมื่ออุปกรณ์เปลี่ยน
      - Opus 1.6.1 ที่ 32 kbps (VBR + FEC 5% + DTX) → bandwidth ต่อคนลดจาก ~768 kbps เหลือ ~32 kbps
      - เลือกใช้ Opus อัตโนมัติเมื่อมี lib / ถอยไป PCM ได้ทันทีถ้าโหลด lib ไม่ได้
      - เปิดใช้: `./gradlew assembleDebug -PwithNativeAudio=true` (NDK r30 + CMake 4.1.2)
      - ยังเหลือ: ทดสอบเสียงจริงบนมือถือ (ต้องติดตั้ง APK ลงเครื่อง — ติดข้อจำกัด "ติดตั้งผ่าน USB" ของ HyperOS)
- [ ] **RoIP เสียงเต็มรูปแบบ** — USB Soundcard ⇄ IP (helper แยก, ดู `docs/ROIP.md`)
      - กด PTT จากแอป → คีย์วิทยุ → เสียงจากวิทยุเข้ามาในห้อง Intercom เสมือนผู้พูดอีกคน
- [ ] **Half-duplex/double-talk suppression** — เมื่อมีคนพูด เปิด ducking เบา ๆ ให้ได้ยินเสียงตัวเอง
- [ ] **ปรับระดับอัตโนมัติ (AGC เฉพาะทาง)** — ให้เสียงทุกคนดังใกล้เคียงกันโดยไม่ปั๊มเสียงรบกวน

## เฟส 3 — ระบบProduction

- [ ] **เข้ารหัสเสียง (SRTP/DTLS-SRTP)** สำหรับงานที่ต้องผ่านเครือข่ายที่ไว้ใจไม่ได้
- [ ] **Tally จาก OBS** — OBS ไม่มี TCP 8099 → ใช้ `POST /api/tally` จาก obs-websocket/สคริปต์ได้แล้ววันนี้
- [ ] **หลายห้อง (rooms)** — แยกช่องสื่อสาร: กล้อง / แสง / เวที / ประสานงาน
      (ออกแบบ: เพิ่ม `room` ใน `hello` แล้วแยก registry+relay ตามห้อง)
- [ ] **คิว/ลำดับผู้พูด + ปุ่ม "ขอพูด"** สำหรับทีมที่ต้องการความเป็นระเบียบ
- [ ] **บันทึกเสียงย้อนหลัง 30 นาที** เพื่อตรวจสอบคำสั่งที่หลุด
- [ ] **Android TV / แท็บเล็ตจอใหญ่** — จอ Tally ตั้งPageside ไม่ต้องกดปุ่ม
- [ ] **Widget/Always-on Tally** — โชว์สี Tally บนหน้าจอล็อก (Android 14+)

## สิ่งที่ "จงใจ" ไม่ทำ

| ไม่ทำ | เพราะ |
|---|---|
| Mesh ระหว่างมือถือ (ไม่ผ่านเซิร์ฟเวอร์) | N เครื่องจะส่ง N×(N-1) สาย Wi-Fi ตายก่อน; เซิร์ฟเวอร์กลางคุมคุณภาพเสียงได้ |
| ส่งเสียงผ่าน WebSocket | TCP head-of-line blocking ทำให้ทั้งห้องเงียบเวลามีแพ็กเก็ตหาย |
| ใช้ RTP/SIP สำเร็จรูป | ซับซ้อนเกินงาน และต้องการ UDP relay + Tally รวมในที่เดียวอยู่แล้ว |
