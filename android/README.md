# SWITCHY — แอป Android (Intercom + Tally Light)

Kotlin ล้วน ไม่ต้องใช้ NDK ในเฟสนี้ (Opus/Oboe เป็นเฟส 2 — เปิดได้ด้วย flag เดียว)

## บิลด์/ติดตั้ง

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

หรือกด `tools\5-build-apk.bat` / `tools\6-install-apk.bat`

| ค่าที่ตั้งไว้ | ค่า |
|---|---|
| package | `com.switchy.intercom` (บิลด์ debug เป็น `.debug`) |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Gradle / AGP / Kotlin | 8.14.3 / 8.13.0 / 2.1.0 |
| JDK ที่ใช้บิลด์ | 17+ (ทดสอบด้วย Temurin 21) |
| dependency | `androidx.core:core-ktx`, `okhttp3` (เท่านั้น) |

## โครงไฟล์

```
app/src/main/java/com/switchy/intercom/
├── MainActivity.kt            หน้าตั้งค่า: IP, พอร์ต, ชื่อ, เลือกกล้อง (ดึงจากเซิร์ฟเวอร์ได้)
├── TallyActivity.kt           จอ Tally เต็มสี + ปุ่ม PTT + มิเตอร์ไมค์ + รายชื่อคนในห้อง
├── service/IntercomService.kt Foreground Service: WebSocket + Audio Engine + WifiLock/WakeLock
├── audio/
│   ├── AudioEngine.kt         วงจรเสียงทั้งหมด (ไมค์ → gate → codec → UDP, UDP → jitter → มิกซ์ → ลำโพง)
│   ├── AudioPacket.kt         แพ็กเก็ต UDP 16-byte header (ตรงกับฝั่ง Go)
│   ├── AudioParams.kt         48 kHz / 20 ms / mono
│   ├── JitterBuffer.kt        กันเสียงกระตุกจาก Wi-Fi (ต่อผู้พูดหนึ่งคน)
│   ├── NoiseGate.kt           ตัดเสียงรบกวนด้วย RMS→dBFS + attack/release
│   ├── AudioEffects.kt        เปิด AEC / NoiseSuppressor / (ปิด AGC) ของระบบ
│   ├── AudioCodec.kt          สลับ PCM ⇄ Opus ได้โดยไม่ต้องแก้ engine
│   ├── NativeAudio.kt         สะพาน JNI (libopus + Oboe)
│   └── UdpAudioTransport.kt   UDP socket (ส่ง/รับ)
├── net/
│   ├── ControlClient.kt       WebSocket client (OkHttp) + HTTP /api/state
│   ├── Protocol.kt            Welcome / Peer / TallyState
│   └── Bus.kt                 สื่อสาร Service ⇄ Activity (callback บน main thread)
├── data/Prefs.kt              จำค่าตั้งทั้งหมด
└── ui/CameraChips.kt          ปุ่มเลือกกล้องที่สร้างจากข้อมูลจริงของเซิร์ฟเวอร์
```

## สิทธิ์ที่ขอ และเหตุผล

| สิทธิ์ | ใช้ทำอะไร |
|---|---|
| `RECORD_AUDIO` | ไมค์ Intercom |
| `INTERNET`, `ACCESS_NETWORK_STATE` | WebSocket + UDP |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | ทำงานบน Wi-Fi |
| `WAKE_LOCK` | ไม่ให้ CPU/Wi-Fi หลับเมื่อดับจอ |
| `FOREGROUND_SERVICE(+MICROPHONE,+DATA_SYNC)` | บริการเสียงต้องรันเบื้องหลังตามนโยบาย Android 14+ |
| `POST_NOTIFICATIONS` | แถบแจ้งเตือนค้างไว้ (Android จะไม่ตัดไมค์ถ้ามี notification ของ foreground service) |
| `MODIFY_AUDIO_SETTINGS` | ตั้งโหมดเสียงเป็น MODE_IN_COMMUNICATION |

## บิลด์แบบ native (Opus + Oboe) — เฟส 2

```bash
cd android
export JAVA_HOME="C:/Users/Bon6have6Mouse/tools/jdk21"
./gradlew --no-daemon assembleDebug -PwithNativeAudio=true
```

ต้องมี (ติดตั้งแล้วบนเครื่องนี้):

```bash
sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"
```

CMake จะดึง **Oboe 1.9.3** และ **libopus v1.6.1** จาก GitHub เอง (ครั้งแรกใช้เวลา ~2–3 นาที)
ถ้าต้องการบิลด์แบบไม่ต่อเน็ต: ก็อป repo ไปวางที่ `app/src/main/cpp/third_party/oboe` และ `.../opus`

| เรื่องที่ต้องระวัง | ทำไม |
|---|---|
| Oboe ไม่มี `Direction::FullDuplex` | ต้องเปิด 2 สตรีม (ไมค์ + ลำโพง) แยกกัน — โค้ดเดิมที่ใช้ `FullDuplex` จะคอมไพล์ไม่ผ่าน |
| `OboeVersionString()` ไม่มี | ใช้ `oboe::getVersionText()` |
| บิลด์ debug ของ AGP ไม่ใส่ออปติไมซ์ | libopus จะช้าและเสียงกระตุก — CMakeLists เติม `add_compile_options($<$<CONFIG:Debug>:-O2>)` ไว้แล้ว (ต้องวาง **ก่อน** FetchContent) |
| CMake 4 เข้มเรื่องเวอร์ชันขั้นต่ำ | ต้องมี `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` (ตั้งไว้ใน `app/build.gradle.kts` แล้ว) |

หลังบิลด์ ควรเห็นใน APK:

```
lib/arm64-v8a/libswitchy_audio.so     ~683 KB   (Oboe + Opus + JNI ครบ 8 ฟังก์ชัน)
lib/armeabi-v7a/libswitchy_audio.so   ~480 KB
lib/arm64-v8a/libc++_shared.so        1.4 MB
```

ตรวจสอบด้วย: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libswitchy`

## หมายเหตุการออกแบบ

1. **ใช้ `android.app.Activity` ไม่ใช่ AppCompat** — ลด dependency ทำให้บิลด์เร็วและ APK เล็ก
   ธีมใช้ `Theme.Material.NoActionBar` ของระบบ
2. **ไม่เก็บ state ใน Activity** — สถานะทั้งหมดอยู่ใน Service แล้วกระจายผ่าน `Bus`
   (callback ถูก post ไป main thread ให้แล้ว Activity จึงแก้ view ได้ตรง ๆ)
3. **ปุ่ม PTT เป็น touch listener** ที่กดค้าง (ไม่ใช่ click) และจะปล่อยอัตโนมัติเมื่อออกจากจอ
   (`onStop`) เพื่อกันไมค์ค้างเปิด
4. **มุมมองสี**: PROGRAM = แดง, PREVIEW = เขียว, SAFE = เทา, UNKNOWN = ดำ
   ระหว่างกดพูดพื้นจอจะเปลี่ยนเป็นน้ำเงิน เพื่อให้รู้ทันทีว่ากำลังเปิดไมค์
5. **Opus พร้อมใช้แต่ไม่บังคับ** — ถ้า native ไม่มี ระบบจะรายงานใน log ว่าใช้ PCM
   (ไม่ปิดกั้นการใช้งาน)
