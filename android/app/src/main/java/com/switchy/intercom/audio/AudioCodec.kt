package com.switchy.intercom.audio

/**
 * ตัวเข้ารหัสเสียง — เลือกใช้ได้ระหว่าง PCM ดิบ กับ Opus
 *
 * ทำเป็น interface เพื่อสลับได้โดยไม่ต้องแก้ AudioEngine:
 *   - [PcmCodec]  ใช้ได้ทุกเครื่อง (ค่าเริ่มต้น, latency ต่ำสุดบน LAN)
 *   - [OpusCodec] ใช้เมื่อบิลด์ด้วย native libopus (ประหยัด bandwidth ~10 เท่า)
 */
interface AudioCodec {
    val name: String
    val isOpus: Boolean

    /** คืน payload พร้อมส่ง (ความยาวจริงอาจสั้นกว่าข้อมูลเข้า) */
    fun encode(pcm: ByteArray, len: Int): ByteArray

    /** คืน PCM16 ของ payload ที่รับมา */
    fun decode(data: ByteArray, len: Int): ByteArray

    fun close() {}
}

/** PCM16 ดิบ — ไม่บีบอัด, ใช้ bandwidth 768 kbps ที่ 48 kHz แต่ latency ต่ำสุด */
class PcmCodec : AudioCodec {
    override val name = "pcm"
    override val isOpus = false

    override fun encode(pcm: ByteArray, len: Int): ByteArray = pcm.copyOf(len)

    override fun decode(data: ByteArray, len: Int): ByteArray =
        if (data.size == len) data else data.copyOf(len)
}

/**
 * Opus ผ่าน JNI (libopus) — ต้องบิลด์ด้วย ./gradlew assembleDebug -PwithNativeAudio=true
 * ถ้าไม่มี native lib ในเครื่อง จะสร้างไม่ได้ (create() คืน null) แล้วระบบจะ fallback เป็น PCM
 */
class OpusCodec private constructor(
    private val handle: Long
) : AudioCodec {

    override val name = "opus"
    override val isOpus = true

    override fun encode(pcm: ByteArray, len: Int): ByteArray =
        NativeAudio.opusEncode(handle, pcm, len) ?: pcm.copyOf(len)

    override fun decode(data: ByteArray, len: Int): ByteArray =
        NativeAudio.opusDecode(handle, data, len) ?: ByteArray(0)

    override fun close() {
        NativeAudio.opusDestroy(handle)
    }

    companion object {
        fun create(params: AudioParams, bitrate: Int): OpusCodec? {
            if (!NativeAudio.available) return null
            val h = NativeAudio.opusCreate(params.sampleRate, params.channels, bitrate, params.frameMs)
            return if (h <= 0L) null else OpusCodec(h)
        }
    }
}

object AudioCodecs {
    /**
     * เลือกตัวเข้ารหัสที่ใช้ได้จริงบนเครื่องนี้
     * preferOpus = true แต่ native ไม่พร้อม -> คืน PcmCodec พร้อมเหตุผลไปโชว์บนจอ
     */
    fun pick(preferOpus: Boolean, params: AudioParams, opusBitrate: Int = 32_000): Pair<AudioCodec, String> {
        if (!preferOpus) return PcmCodec() to "pcm (เลือกเอง)"
        val opus = OpusCodec.create(params, opusBitrate)
            ?: return PcmCodec() to "pcm (ยังไม่มี native libopus ในบิลด์นี้)"
        return opus to "opus ${opusBitrate / 1000} kbps"
    }
}
