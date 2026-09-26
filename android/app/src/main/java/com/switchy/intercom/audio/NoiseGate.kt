package com.switchy.intercom.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Noise Gate — ตัดเสียงรบกวนรอบข้างออกจากไมค์ (แอร์, พัดลม, เสียงห้อง)
 *
 * ทำงานในโดเมน PCM16 ที่อ่านมาจาก AudioRecord: คำนวณ RMS → dBFS
 * ถ้าต่ำกว่า threshold จะลดเกนลงแบบนุ่มนวล (attack/release) เพื่อไม่ให้เกิดเสียง "คลิก"
 */
class NoiseGate(
    private val frameMs: Int,
    thresholdDb: Float = -45f
) {
    @Volatile var thresholdDb: Float = thresholdDb
    @Volatile var enabled: Boolean = true

    /** เวลาที่เกนวิ่งจาก 0 -> 1 (วินาที) — สั้นเพื่อไม่ให้คำแรกขาด */
    @Volatile var attackMs: Float = 3f

    /** เวลาที่เกนวิ่งจาก 1 -> 0 (วินาที) — ยาวเพื่อไม่ให้ท้ายคำหาย */
    @Volatile var releaseMs: Float = 160f

    /** ระดับเสียงของเฟรมล่าสุด (dBFS) ใช้โชว์มิเตอร์ */
    @Volatile var lastLevelDb: Float = -120f
        private set

    private var gain = 0f

    fun process(buf: ByteArray, len: Int) {
        val db = rmsDb(buf, len)
        lastLevelDb = db

        val target = if (!enabled || db >= thresholdDb) 1f else 0f
        val step = if (target > gain) {
            frameMs / attackMs.coerceAtLeast(1f) / 1000f
        } else {
            frameMs / releaseMs.coerceAtLeast(1f) / 1000f
        }
        when {
            target > gain -> gain = (gain + step).coerceAtMost(target)
            target < gain -> gain = (gain - step).coerceAtLeast(target)
        }

        if (gain >= 0.999f) return // เปิดเต็มที่ ไม่ต้องแตะข้อมูล

        var i = 0
        while (i + 1 < len) {
            val s = AudioPacket.getShort(buf, i)
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            AudioPacket.putShort(buf, i, v)
            i += 2
        }
    }

    fun reset() {
        gain = 0f
        lastLevelDb = -120f
    }

    companion object {
        /** RMS ของ PCM16 LE เป็น dBFS (0 = เต็มสเกล, -120 = เงียบสนิท) */
        fun rmsDb(buf: ByteArray, len: Int): Float {
            if (len < 2) return -120f
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < len) {
                val s = AudioPacket.getShort(buf, i)
                sum += (s.toDouble() * s.toDouble())
                n++
                i += 2
            }
            if (n == 0) return -120f
            val rms = sqrt(sum / n)
            if (rms < 1.0) return -120f
            return (20 * log10(rms / 32768.0)).toFloat()
        }

        /** Peak ของ PCM16 (ใช้โชว์ระดับเสียงที่ได้รับ) */
        fun peak(buf: ByteArray, len: Int): Int {
            var p = 0
            var i = 0
            while (i + 1 < len) {
                val s = abs(AudioPacket.getShort(buf, i))
                if (s > p) p = s
                i += 2
            }
            return p
        }
    }
}
