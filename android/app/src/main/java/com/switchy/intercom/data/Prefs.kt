package com.switchy.intercom.data

import android.content.Context

/**
 * เก็บค่าตั้งของผู้ใช้ (จำไว้ใช้ครั้งต่อไป — เปิดแอปแล้วกดเชื่อมต่อได้เลย)
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("switchy", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v.trim()).apply()

    var wsPort: Int
        get() = sp.getInt(KEY_WS_PORT, 8090)
        set(v) = sp.edit().putInt(KEY_WS_PORT, v).apply()

    var audioPort: Int
        get() = sp.getInt(KEY_AUDIO_PORT, 50500)
        set(v) = sp.edit().putInt(KEY_AUDIO_PORT, v).apply()

    var name: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAME, v.trim()).apply()

    var camera: String
        get() = sp.getString(KEY_CAMERA, "cam1") ?: "cam1"
        set(v) = sp.edit().putString(KEY_CAMERA, v.trim().lowercase()).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    /** รหัสกล้องที่เคยเห็นจากเซิร์ฟเวอร์ (คั่นด้วย ,) */
    var knownCameras: String
        get() = sp.getString(KEY_CAMERAS, "cam1,cam2,cam3") ?: "cam1,cam2,cam3"
        set(v) = sp.edit().putString(KEY_CAMERAS, v).apply()

    var gateEnabled: Boolean
        get() = sp.getBoolean(KEY_GATE, true)
        set(v) = sp.edit().putBoolean(KEY_GATE, v).apply()

    var gateThresholdDb: Float
        get() = sp.getFloat(KEY_GATE_DB, -45f)
        set(v) = sp.edit().putFloat(KEY_GATE_DB, v).apply()

    var muteWhileTalking: Boolean
        get() = sp.getBoolean(KEY_MUTE_TALK, true)
        set(v) = sp.edit().putBoolean(KEY_MUTE_TALK, v).apply()

    var preferOpus: Boolean
        get() = sp.getBoolean(KEY_OPUS, false)
        set(v) = sp.edit().putBoolean(KEY_OPUS, v).apply()

    // ---- โหมดต่อ vMix ตรง (ไม่ต้องมีเซิร์ฟเวอร์) ----
    var directVmix: Boolean
        get() = sp.getBoolean(KEY_DIRECT_VMIX, false)
        set(v) = sp.edit().putBoolean(KEY_DIRECT_VMIX, v).apply()

    var vmixHost: String
        get() = sp.getString(KEY_VMIX_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_VMIX_HOST, v.trim()).apply()

    var vmixPort: Int
        get() = sp.getInt(KEY_VMIX_PORT, 8099)
        set(v) = sp.edit().putInt(KEY_VMIX_PORT, v).apply()

    /** ตั้งค่าเสร็จแล้วอย่างน้อยหนึ่งครั้ง — เปิดแอปครั้งต่อไปเข้าจอ Tally ได้เลย */
    var configured: Boolean
        get() = sp.getBoolean(KEY_CONFIGURED, false)
        set(v) = sp.edit().putBoolean(KEY_CONFIGURED, v).apply()

    fun cameraList(): List<String> =
        knownCameras.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("cam1", "cam2", "cam3") }

    fun hasServer(): Boolean = host.isNotEmpty()

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_WS_PORT = "wsPort"
        private const val KEY_AUDIO_PORT = "audioPort"
        private const val KEY_NAME = "name"
        private const val KEY_CAMERA = "camera"
        private const val KEY_TOKEN = "token"
        private const val KEY_CAMERAS = "cameras"
        private const val KEY_GATE = "gate"
        private const val KEY_GATE_DB = "gateDb"
        private const val KEY_MUTE_TALK = "muteWhileTalking"
        private const val KEY_OPUS = "preferOpus"
        private const val KEY_DIRECT_VMIX = "directVmix"
        private const val KEY_VMIX_HOST = "vmixHost"
        private const val KEY_VMIX_PORT = "vmixPort"
        private const val KEY_CONFIGURED = "configured"
    }
}
