package com.switchy.intercom.audio

/**
 * แพ็กเก็ตเสียง UDP ของ SWITCHY — ต้องตรงกับ server/internal/audioengine/packet.go
 *
 *  0..1  magic 'S','W'
 *  2     version
 *  3     flags   (TALK / OPUS / LAST / CONTROL)
 *  4..5  clientNum (uint16 LE) — เลขที่เซิร์ฟเวอร์แจกตอน welcome
 *  6..7  seq (uint16 LE)
 *  8..11 timestamp ms (uint32 LE) — เวลาที่เก็บเสียง (ใช้เรียงลำดับ/jitter)
 *  12..13 payload len (uint16 LE)
 *  14..15 reserved
 */
object AudioPacket {

    const val HEADER_SIZE = 16

    const val FLAG_TALK = 1        // เฟรมนี้เป็นเสียงที่ผู้พูดกด PTT อยู่
    const val FLAG_OPUS = 2        // payload เป็น Opus
    const val FLAG_LAST = 4        // เฟรมสุดท้ายก่อนปล่อยคีย์
    const val FLAG_CONTROL = 8     // payload เป็นข้อความควบคุม (ไม่ใช่เสียง)

    private const val MAGIC0 = 'S'.code.toByte()
    private const val MAGIC1 = 'W'.code.toByte()
    private const val VERSION = 1

    class Header(
        val flags: Int,
        val client: Int,
        val seq: Int,
        val tsMs: Long,
        val payloadLen: Int
    )

    fun encode(
        flags: Int,
        client: Int,
        seq: Int,
        tsMs: Long,
        payload: ByteArray,
        payloadLen: Int = payload.size
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + payloadLen)
        out[0] = MAGIC0
        out[1] = MAGIC1
        out[2] = VERSION.toByte()
        out[3] = flags.toByte()
        putShort(out, 4, client)
        putShort(out, 6, seq and 0xFFFF)
        putInt(out, 8, (tsMs and 0xFFFFFFFFL).toInt())
        putShort(out, 12, payloadLen)
        putShort(out, 14, 0)
        System.arraycopy(payload, 0, out, HEADER_SIZE, payloadLen)
        return out
    }

    /** คืน null ถ้าแพ็กเก็ตไม่ใช่ของ SWITCHY หรือเสียหาย */
    fun decode(buf: ByteArray, len: Int): Header? {
        if (len < HEADER_SIZE) return null
        if (buf[0] != MAGIC0 || buf[1] != MAGIC1) return null
        if (buf[2].toInt() != VERSION) return null
        val payloadLen = getShort(buf, 12)
        if (HEADER_SIZE + payloadLen > len) return null
        return Header(
            flags = buf[3].toInt() and 0xFF,
            client = getShort(buf, 4),
            seq = getShort(buf, 6),
            tsMs = getInt(buf, 8).toLong() and 0xFFFFFFFFL,
            payloadLen = payloadLen
        )
    }

    // ---- helpers สำหรับ PCM16 little-endian ----

    fun getShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun putShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * ต่างกันของ seq แบบคิด wrap 16-bit (คืนค่าเป็น +/-)
     * diff > 0 = ใหม่กว่า, diff < 0 = เก่ากว่า/ซ้ำ
     */
    fun seqDiff(a: Int, b: Int): Int = (((a - b + 0x8000) and 0xFFFF) - 0x8000)
}
