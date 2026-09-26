package com.switchy.intercom.audio

/**
 * จุดเชื่อมต่อกับ native layer (libopus + Oboe) ที่อยู่ใน app/src/main/cpp
 *
 * บิลด์ปกติจะ "ไม่" มี .so เหล่านี้ (ไม่ต้องติดตั้ง NDK) → [available] = false
 * แล้วระบบจะใช้ PCM ผ่าน AudioRecord/AudioTrack (AAudio path ของ Android) แทน
 *
 * เปิดใช้ native:  cd android && ./gradlew assembleDebug -PwithNativeAudio=true
 */
object NativeAudio {

    private const val TAG = "SwitchyNative"

    /** เหตุผลที่โหลด lib ไม่ได้ (โชว์บนจอ/log ได้) */
    @Volatile
    var failureReason: String? = null
        private set

    /** true = โหลด libswitchy_audio.so ได้ (คือบิลด์แบบ -PwithNativeAudio=true) */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("switchy_audio")
            android.util.Log.i(TAG, "โหลด libswitchy_audio.so สำเร็จ")
            true
        } catch (t: Throwable) {
            failureReason = t.message ?: t.javaClass.simpleName
            android.util.Log.w(TAG, "โหลด libswitchy_audio.so ไม่ได้: $failureReason")
            false
        }
    }

    val unavailableReason: String
        get() = failureReason ?: "บิลด์นี้ไม่มี native libopus (ใช้ PCM แทนได้ปกติ)"

    // ---- Opus ----
    external fun opusCreate(sampleRate: Int, channels: Int, bitrate: Int, frameMs: Int): Long
    external fun opusEncode(handle: Long, pcm: ByteArray, len: Int): ByteArray?
    external fun opusDecode(handle: Long, data: ByteArray, len: Int): ByteArray?
    external fun opusDestroy(handle: Long)

    // ---- Oboe (low-latency engine) ----
    external fun oboeVersion(): String
    external fun oboeStart(
        sampleRate: Int,
        frameMs: Int,
        callback: OboeCallback,
        pttMode: Boolean
    ): Long

    external fun oboeStop(handle: Long)
    external fun oboeSetPTT(handle: Long, on: Boolean)

    /**
     * callback จาก native ขึ้นมาฝั่ง Kotlin: native จะเรียก onCapture เมื่อได้เฟรมจากไมค์
     * และดึงเฟรมที่จะเล่นด้วย getPlaybackFrame
     */
    interface OboeCallback {
        fun onCapture(pcm: ByteArray, len: Int)
        fun getPlaybackFrame(dst: ByteArray, len: Int): Int
    }
}
