package com.switchy.intercom.net

/** ข้อมูลที่เซิร์ฟเวอร์ส่งกลับมาตอนลงทะเบียนสำเร็จ (ข้อความ "welcome") */
class Welcome(
    val proto: Int,
    val clientId: Int,
    val clientNum: Int,
    val server: String,
    val serverVersion: String,
    val audioHost: String,
    val audioPort: Int,
    val frameMs: Int,
    val sampleRate: Int,
    val fullDuplex: Boolean,
    val camera: String,
    val states: Map<String, String>,
    val source: String,
    val vmixUp: Boolean
) {
    override fun toString(): String =
        "$server v$serverVersion  ·  ${audioHost}:$audioPort  ·  ${sampleRate}Hz/${frameMs}ms" +
            if (fullDuplex) "  ·  full-duplex" else ""
}

/** เครื่องอื่นที่อยู่ในห้องเดียวกัน */
class Peer(
    val num: Int,
    val name: String,
    val camera: String,
    val ptt: Boolean,
    val codec: String,
    val ip: String
) {
    override fun toString(): String =
        "$name ($camera)${if (ptt) " · กำลังพูด" else ""}  [$ip]"
}

/** สถานะ Tally ของกล้องหนึ่งตัว */
enum class TallyState(val wire: String) {
    PROGRAM("program"),
    PREVIEW("preview"),
    SAFE("safe"),
    UNKNOWN("unknown");

    companion object {
        fun from(wire: String?): TallyState = when (wire?.lowercase()) {
            "program", "live", "pgm" -> PROGRAM
            "preview", "pvw" -> PREVIEW
            "safe", "off", "idle" -> SAFE
            else -> UNKNOWN
        }
    }
}

/** ข้อความจากศูนย์ควบคุม (เครื่องลูกอ่านได้อย่างเดียว) */
class ChatMsg(
    val id: Long,
    val from: String,
    val to: String,
    val text: String,
    val kind: String,
    val atMillis: Long
) {
    val isAlert: Boolean get() = kind == "alert"
}

/** การตอบกลับด้วยปุ่มสำเร็จรูปของเครื่องลูก */
class ChatReply(
    val id: Long,
    val msgId: Long,
    val clientNum: Int,
    val name: String,
    val code: String,
    val label: String,
    val atMillis: Long
)

/** รหัสปุ่มตอบกลับที่อนุญาต (ต้องตรงกับฝั่งเซิร์ฟเวอร์) */
enum class ReplyCode(val wire: String, val label: String) {
    ACK("ack", "รับทราบ"),
    DISAGREE("disagree", "ไม่เห็นด้วย"),
    HELP("help", "ขอความช่วยเหลือ");

    companion object {
        fun from(wire: String?): ReplyCode? = entries.firstOrNull { it.wire == wire }
    }
}
