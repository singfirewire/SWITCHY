package com.switchy.intercom.audio

/**
 * เบ็คเอนด์เสียงแบบ native (Oboe) — ทางเลือกของ AudioRecord/AudioTrack
 *
 * ใช้เมื่อบิลด์มี libswitchy_audio.so (บิลด์ด้วย -PwithNativeAudio=true) เท่านั้น
 * ถ้าไม่มี ระบบจะไม่เรียกคลาสนี้เลย (AudioEngine จะใช้ AudioRecord/AudioTrack ตามปกติ)
 *
 * ทำไม Oboe เร็วกว่า: Oboe ใช้ AAudio (Android 8.1+) ซึ่งเป็นเส้นทางเสียงความหน่วงต่ำของระบบ
 * และจัดการ callback จาก audio thread เอง (ไม่ต้องมี thread อ่าน/เขียนของเรา)
 */
class NativeAudioBackend(
    private val params: AudioParams,
    private val log: (String) -> Unit,
    /** เรียกเมื่อได้เฟรมเสียงจากไมค์ (ทำงานบน audio thread ของ Oboe — ต้องเร็ว ห้ามทำ I/O หนัก) */
    private val onMicFrame: (ByteArray, Int) -> Unit,
    /** ให้ engine เติมเฟรมที่จะเล่นลง dst คืนจำนวนไบต์ */
    private val fillPlayback: (ByteArray, Int) -> Int
) : NativeAudio.OboeCallback {

    private var handle: Long = 0L

    val available: Boolean get() = NativeAudio.available

    var version: String = "-"
        private set

    val isRunning: Boolean get() = handle > 0L

    fun start(): Boolean {
        if (!NativeAudio.available) {
            log("บิลด์นี้ไม่มี native lib — ใช้ AudioRecord/AudioTrack")
            return false
        }
        version = runCatching { NativeAudio.oboeVersion() }.getOrDefault("?")
        val h = try {
            NativeAudio.oboeStart(params.sampleRate, params.frameMs, this, true)
        } catch (t: Throwable) {
            // สำคัญ: ถ้า native เปิดสตรีมได้แล้วมี exception ค้างอยู่ การจับตรงนี้จะบอกได้ว่าอะไรผิด
            log("Oboe โยน exception ตอนเปิดสตรีม: ${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.w(TAG, "oboeStart ล้มเหลว", t)
            0L
        }
        if (h <= 0L) {
            log("Oboe คืนค่า handle=0 (เปิดสตรีมไม่สำเร็จ)")
            return false
        }
        handle = h
        log("Oboe พร้อม (เวอร์ชัน $version) · ${params.sampleRate} Hz / ${params.frameMs} ms")
        return true
    }

    fun setPTT(on: Boolean) {
        if (handle > 0L) runCatching { NativeAudio.oboeSetPTT(handle, on) }
    }

    fun stop() {
        if (handle > 0L) runCatching { NativeAudio.oboeStop(handle) }
        handle = 0L
    }

    // ---- NativeAudio.OboeCallback (ถูกเรียกจาก audio thread ของ Oboe) ----

    override fun onCapture(pcm: ByteArray, len: Int) {
        if (!isRunning) return
        onMicFrame(pcm, len)
    }

    override fun getPlaybackFrame(dst: ByteArray, len: Int): Int {
        if (!isRunning) return 0
        return fillPlayback(dst, len)
    }

    companion object {
        private const val TAG = "SwitchyOboeBackend"
    }
}
