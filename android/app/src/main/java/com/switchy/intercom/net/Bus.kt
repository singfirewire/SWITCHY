package com.switchy.intercom.net

import android.os.Handler
import android.os.Looper
import com.switchy.intercom.audio.AudioEngine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * สื่อกลางระหว่าง Service กับหน้าจอ (Activity)
 *
 * ทุก callback ถูก post ไปที่ main thread แล้ว → Activity แก้ view ได้ตรง ๆ ไม่ต้องกังวลเรื่อง thread
 * เก็บ "ค่าล่าสุด" ไว้ด้วย เพื่อให้จอที่เพิ่งเปิดสามารถวาดสถานะปัจจุบันได้ทันที
 */
object Bus {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    /** โหมดการทำงาน: ผ่านเซิร์ฟเวอร์ SWITCHY (มี Intercom) หรือต่อ vMix ตรง (Tally อย่างเดียว) */
    enum class Mode { SERVER, VMIX_DIRECT }

    interface Listener {
        fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean, seq: Long) {}
        fun onPeers(peers: List<Peer>) {}
        fun onTalkers(nums: List<Int>) {}
        fun onStatus(status: Status, detail: String) {}
        fun onAudio(stats: AudioEngine.Stats) {}
        fun onMessages(messages: List<ChatMsg>, replies: List<ChatReply>) {}
        fun onLog(line: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var status: Status = Status.DISCONNECTED
        private set

    @Volatile var statusDetail: String = ""
        private set

    @Volatile var states: Map<String, String> = emptyMap()
        private set

    @Volatile var source: String = "?"
        private set

    @Volatile var vmixUp: Boolean = false
        private set

    @Volatile var seq: Long = 0
        private set

    @Volatile var peers: List<Peer> = emptyList()
        private set

    @Volatile var talkers: List<Int> = emptyList()
        private set

    @Volatile var serverInfo: String = ""
        private set

    @Volatile var cameras: List<String> = listOf("cam1", "cam2", "cam3")
        private set

    @Volatile var lastStats: AudioEngine.Stats? = null
        private set

    @Volatile var mode: Mode = Mode.SERVER
        private set

    @Volatile var messages: List<ChatMsg> = emptyList()
        private set

    @Volatile var replies: List<ChatReply> = emptyList()
        private set

    fun setMode(newMode: Mode) {
        mode = newMode
    }

    /** ตั้งรายการข้อความทั้งหมด (ตอน welcome) */
    fun setMessages(list: List<ChatMsg>, replyList: List<ChatReply>) {
        messages = list
        replies = replyList
        each { it.onMessages(messages, replies) }
    }

    fun addMessage(m: ChatMsg) {
        val list = (messages + m).distinctBy { it.id }.sortedBy { it.id }
        messages = list
        each { it.onMessages(messages, replies) }
    }

    fun addReply(r: ChatReply) {
        val list = (replies + r).distinctBy { it.id }.sortedBy { it.id }
        replies = list
        each { it.onMessages(messages, replies) }
    }

    fun latestMessage(): ChatMsg? = messages.lastOrNull()

    /** จำนวนข้อความที่ยังไม่ได้เปิดอ่าน (คำนวณจาก id ที่อ่านล่าสุดของผู้ใช้) */
    @Volatile var readUpToId: Long = 0

    fun unreadCount(): Int = messages.count { it.id > readUpToId }

    fun markAllRead() {
        readUpToId = messages.lastOrNull()?.id ?: 0
        each { it.onMessages(messages, replies) }
    }

    private val logLines = ArrayDeque<String>()

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    /** สแนปช็อตข้อความ log ล่าสุด (ไว้ให้จอที่เพิ่งเปิดแสดง) */
    fun recentLogs(): List<String> = synchronized(logLines) { logLines.toList() }

    private fun each(action: (Listener) -> Unit) {
        if (listeners.isEmpty()) return
        main.post {
            for (l in listeners) {
                runCatching { action(l) }
            }
        }
    }

    fun setStatus(newStatus: Status, detail: String = "") {
        status = newStatus
        statusDetail = detail
        each { it.onStatus(newStatus, detail) }
    }

    fun setTally(states: Map<String, String>, source: String, vmixUp: Boolean, seq: Long) {
        this.states = states
        this.source = source
        this.vmixUp = vmixUp
        this.seq = seq
        if (cameras.size <= 1 && states.isNotEmpty()) cameras = states.keys.toList()
        each { it.onTally(states, source, vmixUp, seq) }
    }

    fun setPeers(list: List<Peer>) {
        peers = list
        each { it.onPeers(list) }
    }

    fun setTalkers(nums: List<Int>) {
        talkers = nums
        each { it.onTalkers(nums) }
    }

    fun setServerInfo(info: String) {
        serverInfo = info
        val keys = parseCameraCodes(info)
        if (keys.isNotEmpty()) cameras = keys
    }

    fun setCameras(codes: List<String>) {
        if (codes.isNotEmpty()) cameras = codes
    }

    fun setAudio(stats: AudioEngine.Stats) {
        lastStats = stats
        each { it.onAudio(stats) }
    }

    fun log(line: String) {
        val stamped = "${nowStamp()}  $line"
        android.util.Log.i("SwitchyBus", line)   // ให้ดูได้จาก logcat ด้วย (debug ระยะไกลผ่าน adb)
        synchronized(logLines) {
            logLines.addLast(stamped)
            while (logLines.size > 120) logLines.removeFirst()
        }
        each { it.onLog(stamped) }
    }

    fun reset() {
        states = emptyMap()
        peers = emptyList()
        talkers = emptyList()
        messages = emptyList()
        replies = emptyList()
        seq = 0
        source = "?"
        vmixUp = false
        setStatus(Status.DISCONNECTED, "")
    }

    private fun nowStamp(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    /** ดึงรหัสกล้องจากข้อความ info ที่เก็บไว้ (บรรทัด "cameras: cam1=กล้อง 1 ...") */
    private fun parseCameraCodes(info: String): List<String> {
        val line = info.lineSequence().firstOrNull { it.startsWith("cameras:") } ?: return emptyList()
        val part = line.removePrefix("cameras:").trim()
        return part.split(Regex("\\s{2,}"))
            .mapNotNull { it.substringBefore('=').trim().ifEmpty { null } }
    }
}
