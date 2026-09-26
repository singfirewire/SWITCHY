package com.switchy.intercom.audio

/**
 * พารามิเตอร์เสียงของทั้งระบบ — ต้องตรงกับฝั่งเซิร์ฟเวอร์ (server/config.json → audio)
 *
 * ค่าเริ่มต้น 48 kHz / 20 ms / mono PCM16
 *   - 48 kHz = อัตราที่ AAudio/AudioTrack ทำงานได้ตรงที่สุด (ไม่ต้องรีแซมเปิล)
 *   - 20 ms = สมดุลระหว่าง latency กับ overhead ต่อแพ็กเก็ต
 *   - 10 ms ก็รองรับ (จะได้ latency ต่ำลง แต่จำนวนแพ็กเก็ตเพิ่มเป็น 2 เท่า)
 */
data class AudioParams(
    val sampleRate: Int = 48_000,
    val frameMs: Int = 20,
    val channels: Int = 1
) {
    val samplesPerFrame: Int get() = sampleRate * frameMs / 1000
    val bytesPerFrame: Int get() = samplesPerFrame * 2 * channels
    val frameDurationUs: Long get() = frameMs * 1000L

    companion object {
        fun fromServer(frameMs: Int, sampleRate: Int): AudioParams {
            val ms = if (frameMs == 10 || frameMs == 20) frameMs else 20
            val rate = if (sampleRate in 8_000..48_000) sampleRate else 48_000
            return AudioParams(sampleRate = rate, frameMs = ms)
        }
    }
}
