package com.switchy.intercom.audio

/**
 * Jitter Buffer ต่อหนึ่งผู้พูด (หนึ่ง clientNum)
 *
 * Wi-Fi ทำให้แพ็กเก็ตมาถี่/ห่างไม่เท่ากัน — ถ้าเล่นทันทีที่มาถึง เสียงจะกระตุก
 * วิธีคือ "สะสม" ไว้ targetDepth เฟรมก่อนเริ่มเล่น แล้วจึงปล่อยออกตามจังหวะคงที่
 * เฟรมที่หายไปจะกลายเป็นความเงียบ (silence) ที่ตำแหน่งเดิม ไม่ทำให้เสียงเลื่อน
 */
class JitterBuffer(
    private val targetDepth: Int = 3,
    private val maxDepth: Int = 16,
    /** ยอมรับเฟรมที่เก่ากว่าปัจจุบันไม่เกินกี่เฟรม (ต่ำกว่านี้ = ทิ้ง) */
    private val lateWindow: Int = 6
) {
    class Frame(
        val seq: Int,
        val tsMs: Long,
        val opus: Boolean,
        val last: Boolean,
        val data: ByteArray
    )

    private val queue = ArrayDeque<Frame>(maxDepth)

    var received: Long = 0L; private set
    var duplicates: Long = 0L; private set
    var late: Long = 0L; private set
    var missing: Long = 0L; private set
    var overflow: Long = 0L; private set
    var underruns: Long = 0L; private set

    @Volatile var started: Boolean = false; private set

    private var lastSeq: Int = -1

    /** คืน true ถ้ารับเข้า buffer (false = ซ้ำ/เก่าเกินไป) ต้องเรียกจาก thread เดียวกับ pop */
    @Synchronized
    fun push(frame: Frame): Boolean {
        if (lastSeq >= 0) {
            val diff = AudioPacket.seqDiff(frame.seq, lastSeq)
            if (diff <= 0) {
                duplicates++
                return false
            }
            if (diff > lateWindow) {
                // มาช้าเกินกว่าที่จะแทรกได้ (คิวเดินหน้าไปแล้ว)
                late++
                return false
            }
            if (diff > 1) missing += (diff - 1).toLong()
        }
        queue.addLast(frame)
        lastSeq = frame.seq
        received++

        if (queue.size > maxDepth) {
            queue.removeFirst()
            overflow++
        }
        return true
    }

    /** ดึงเฟรมถัดไป (null = ยัง prebuffer ไม่ครบ หรือขาดข้อมูล) */
    @Synchronized
    fun pop(): Frame? {
        if (!started) {
            if (queue.size < targetDepth) return null
            started = true
        }
        val f = queue.removeFirstOrNull()
        if (f == null) {
            underruns++
            return null
        }
        return f
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
        lastSeq = -1
        started = false
    }

    /** สรุปไว้โชว์บนจอ */
    fun summary(): String =
        "rx=$received dup=$duplicates late=$late missing=$missing under=$underruns"
}
