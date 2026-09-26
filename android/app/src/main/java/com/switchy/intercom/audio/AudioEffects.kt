package com.switchy.intercom.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * เปิดใช้ DSP ของระบบให้กับ AudioRecord session
 *
 * ใช้ AudioSource = VOICE_COMMUNICATION ซึ่งโดยทั่วไป Android จะเปิด AEC ให้อัตโนมัติ
 * ตัวนี้จะ "ยืนยัน" ว่าเปิดได้จริง และเก็บสถานะไว้โชว์บนจอ (ผู้ใช้จะได้รู้ว่ากันเสียงสะท้อนอยู่ไหม)
 *
 * หมายเหตุ: อุปกรณ์บางรุ่นไม่รองรับ effect เหล่านี้ — ระบบจะรายงานตามจริง ไม่ปด
 */
object AudioEffects {

    private const val TAG = "SwitchyAudioFx"

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile
    var status: String = "ยังไม่เปิด"
        private set

    /** แนบ effect กับ session ของ AudioRecord แล้วคืนข้อความสรุป */
    fun attach(record: AudioRecord): String {
        release()
        val session = try {
            record.audioSessionId
        } catch (t: Throwable) {
            return "อ่าน session ไม่ได้: ${t.message}"
        }

        val sb = StringBuilder()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session)
                aec?.enabled = true
                sb.append(if (aec?.enabled == true) "AEC ✓" else "AEC ✗")
            } else {
                sb.append("AEC ✗ (เครื่องไม่รองรับ)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AEC ไม่สำเร็จ", t); sb.append("AEC ✗")
        }
        sb.append("  ")
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session)
                ns?.enabled = true
                sb.append(if (ns?.enabled == true) "NS ✓" else "NS ✗")
            } else {
                sb.append("NS ✗ (เครื่องไม่รองรับ)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NS ไม่สำเร็จ", t); sb.append("NS ✗")
        }
        sb.append("  ")
        try {
            if (AutomaticGainControl.isAvailable()) {
                // ปิด AGC ไว้เป็นค่าเริ่มต้น: AGC ทำให้เสียงคนไกลดังเท่าคนใกล้ ซึ่งไม่เหมาะกับ intercom
                agc = AutomaticGainControl.create(session)
                agc?.enabled = false
                sb.append("AGC ปิดไว้")
            } else {
                sb.append("AGC ✗ (เครื่องไม่รองรับ)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AGC ไม่สำเร็จ", t); sb.append("AGC ✗")
        }

        status = sb.toString()
        return status
    }

    fun release() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null; ns = null; agc = null
        status = "ยังไม่เปิด"
    }
}
