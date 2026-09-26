package com.switchy.intercom.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ช่องทางควบคุม (WebSocket) ไปยัง SWITCHY Gateway
 *
 * - รับสถานะ Tally + รายชื่อคนในห้อง + ใครกำลังพูด
 * - ส่งคำสั่ง PTT / เปลี่ยนกล้อง / ping
 * - เสียง "ไม่" วิ่งผ่านที่นี่ (วิ่ง UDP) เพื่อไม่ให้ TCP head-of-line blocking ทำให้เสียงสะดุด
 *
 * ข้อความทั้งหมดเป็น JSON บรรทัดเดียว ดูสเปกที่ docs/PROTOCOL.md
 */
class ControlClient(private val listener: Listener) {

    /**
     * ผู้รับเหตุการณ์จากเซิร์ฟเวอร์ — มี default ว่างไว้ เพื่อให้สร้าง client แบบใช้แค่ HTTP ได้ง่าย
     */
    interface Listener {
        fun onWelcome(welcome: Welcome) {}
        fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean, seq: Long) {}
        fun onPeers(peers: List<Peer>) {}
        fun onTalkers(nums: List<Int>) {}
        fun onServerError(message: String) {}
        fun onClosed(reason: String) {}
        fun onLog(line: String) {}
        /** ข้อความใหม่จากศูนย์ควบคุม */
        fun onMessage(message: ChatMsg) {}
        /** มีเครื่องอื่นกดตอบกลับ */
        fun onReply(reply: ChatReply) {}
        /** รายการข้อความทั้งหมด (ตอนเข้าห้องครั้งแรก) */
        fun onMessageHistory(messages: List<ChatMsg>, replies: List<ChatReply>) {}
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket ต้องไม่หมดเวลาเอง
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var lastCamera = "cam1"

    val isConnected: Boolean get() = connected

    fun connect(
        host: String,
        port: Int,
        name: String,
        camera: String,
        token: String,
        codecName: String,
        audioPort: Int
    ) {
        disconnect()
        lastCamera = camera
        val url = "ws://$host:$port/ws"
        listener.onLog("กำลังเชื่อมต่อ $url …")

        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                listener.onLog("WebSocket เปิดแล้ว")
                val hello = JSONObject()
                    .put("t", "hello")
                    .put("proto", 1)
                    .put("name", name)
                    .put("camera", camera)
                    .put("codec", codecName)
                    .put("audioPort", audioPort)
                if (token.isNotEmpty()) hello.put("token", token)
                ws.send(hello.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    handle(text)
                } catch (t: Throwable) {
                    Log.w(TAG, "อ่านข้อความไม่ได้: $text", t)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener.onClosed("ขาดการเชื่อมต่อ: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                listener.onClosed("เซิร์ฟเวอร์ปิดการเชื่อมต่อ ($code) $reason")
            }
        })
    }

    private fun handle(text: String) {
        val json = JSONObject(text)
        when (json.optString("t")) {
            "welcome" -> {
                val tally = json.optJSONObject("tally")
                val states = parseStates(tally)
                val audioAddr = json.optString("audioAddr", "")
                val (audioHost, audioPort) = splitHostPort(audioAddr)
                val welcome = Welcome(
                    proto = json.optInt("proto", 1),
                    clientId = json.optInt("clientId", 0),
                    clientNum = json.optInt("clientNum", 0),
                    server = json.optString("server", "SWITCHY"),
                    serverVersion = json.optString("ver", "?"),
                    audioHost = audioHost,
                    audioPort = audioPort,
                    frameMs = json.optInt("frameMs", 20),
                    sampleRate = json.optInt("sampleRate", 48000),
                    fullDuplex = json.optBoolean("fullDuplex", false),
                    camera = json.optString("camera", lastCamera).ifEmpty { lastCamera },
                    states = states,
                    source = tally?.optString("source") ?: "?",
                    vmixUp = tally?.optBoolean("vmixUp") ?: false
                )
                listener.onLog("ลงทะเบียนแล้ว: clientNum=${welcome.clientNum} · ${welcome}")
                listener.onWelcome(welcome)
                if (states.isNotEmpty()) {
                    listener.onTally(states, welcome.source, welcome.vmixUp, tally?.optLong("seq") ?: 0L)
                }
                listener.onPeers(parsePeers(json.optJSONArray("peers")))
                val history = parseMsgs(json.optJSONArray("messages"))
                val historyReplies = parseReplies(json.optJSONArray("replies"))
                if (history.isNotEmpty() || historyReplies.isNotEmpty()) {
                    listener.onMessageHistory(history, historyReplies)
                }
            }

            "msg" -> {
                val o = json.optJSONObject("message") ?: return
                listener.onMessage(parseMsg(o))
            }

            "reply" -> {
                val o = json.optJSONObject("reply") ?: return
                listener.onReply(parseReply(o))
            }

            "tally" -> {
                val tally = json.optJSONObject("tally") ?: return
                listener.onTally(
                    parseStates(tally),
                    tally.optString("source", "?"),
                    tally.optBoolean("vmixUp", false),
                    tally.optLong("seq", 0L)
                )
            }

            "peers" -> listener.onPeers(parsePeers(json.optJSONArray("peers")))

            "audio" -> {
                val arr = json.optJSONObject("audio")?.optJSONArray("talkers") ?: return
                val nums = ArrayList<Int>(arr.length())
                for (i in 0 until arr.length()) nums.add(arr.optInt(i))
                listener.onTalkers(nums)
            }

            "error" -> listener.onServerError(json.optString("error", "ไม่ทราบสาเหตุ"))

            "pong" -> { /* ใช้ยืนยันว่าสายยังดี */ }
        }
    }

    private fun parseStates(tally: JSONObject?): Map<String, String> {
        val obj = tally?.optJSONObject("states") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k)
        }
        return out
    }

    private fun parsePeers(arr: org.json.JSONArray?): List<Peer> {
        if (arr == null) return emptyList()
        val out = ArrayList<Peer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Peer(
                    num = o.optInt("num"),
                    name = o.optString("name", "?"),
                    camera = o.optString("camera", "-"),
                    ptt = o.optBoolean("ptt", false),
                    codec = o.optString("codec", "-"),
                    ip = o.optString("ip", "-") + if (o.optInt("audioPort", 0) > 0) ":${o.optInt("audioPort")}" else ""
                )
            )
        }
        return out
    }

    // ---- ข้อความจากศูนย์ควบคุม ----

    private fun parseMsg(o: JSONObject): ChatMsg = ChatMsg(
        id = o.optLong("id"),
        from = o.optString("from", "ศูนย์ควบคุม"),
        to = o.optString("to", "all"),
        text = o.optString("text", ""),
        kind = o.optString("kind", "info"),
        atMillis = parseTime(o.optString("at", ""))
    )

    private fun parseReply(o: JSONObject): ChatReply = ChatReply(
        id = o.optLong("id"),
        msgId = o.optLong("msgId"),
        clientNum = o.optInt("clientNum"),
        name = o.optString("name", "?"),
        code = o.optString("code", ""),
        label = o.optString("label", ""),
        atMillis = parseTime(o.optString("at", ""))
    )

    private fun parseMsgs(arr: org.json.JSONArray?): List<ChatMsg> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(parseMsg(it)) }
        return out
    }

    private fun parseReplies(arr: org.json.JSONArray?): List<ChatReply> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChatReply>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(parseReply(it)) }
        return out
    }

    /** เวลาจากเซิร์ฟเวอร์มาเป็น RFC3339 (เช่น 2026-09-26T09:20:00.123+07:00) */
    private fun parseTime(s: String): Long = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    private fun splitHostPort(addr: String): Pair<String, Int> {
        if (addr.isBlank()) return "" to 0
        val idx = addr.lastIndexOf(':')
        if (idx <= 0) return addr to 0
        val host = addr.substring(0, idx)
        val port = addr.substring(idx + 1).toIntOrNull() ?: 0
        return host to port
    }

    fun setPTT(on: Boolean) {
        send(JSONObject().put("t", "ptt").put("on", on))
    }

    fun setCamera(code: String) {
        lastCamera = code
        send(JSONObject().put("t", "cam").put("camera", code))
    }

    /**
     * ตอบกลับข้อความด้วยปุ่มสำเร็จรูป — เครื่องลูกพิมพ์เองไม่ได้โดยดีไซน์
     * ส่งได้เฉพาะรหัส ack / disagree / help เท่านั้น
     */
    fun sendReply(code: ReplyCode, msgId: Long) {
        send(
            JSONObject()
                .put("t", "reply")
                .put("code", code.wire)
                .put("msgId", msgId)
        )
    }

    fun ping() {
        send(JSONObject().put("t", "ping").put("ts", System.currentTimeMillis()))
    }

    private fun send(json: JSONObject) {
        val ws = socket
        if (ws == null || !connected) return
        runCatching { ws.send(json.toString()) }
    }

    fun disconnect() {
        connected = false
        runCatching { socket?.close(1000, "bye") }
        socket = null
    }

    /**
     * ดึงรายการกล้อง/สถานะจากเซิร์ฟเวอร์ผ่าน HTTP (ใช้ในหน้าตั้งค่า ก่อนเชื่อมต่อจริง)
     * คืน JSON ดิบของ /api/state ให้ผู้เรียกแสดงผล
     */
    fun fetchState(host: String, port: Int, callback: (Boolean, String) -> Unit) {
        Thread {
            val url = "http://$host:$port/api/state"
            try {
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    callback(resp.isSuccessful, body.ifEmpty { "ไม่ได้รับข้อมูล" })
                }
            } catch (t: Throwable) {
                callback(false, t.message ?: t.javaClass.simpleName)
            }
        }.start()
    }

    /** ปิด client ถาวร (ตอน service ถูกทำลาย) */
    fun shutdown() {
        disconnect()
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    companion object {
        private const val TAG = "SwitchyWs"
    }
}
