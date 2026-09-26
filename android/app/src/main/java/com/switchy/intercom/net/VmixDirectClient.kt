package com.switchy.intercom.net

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ไคลเอนต์ Tally ที่ต่อ **vMix ตรง ๆ** ผ่าน TCP 8099 — ไม่ต้องมีเซิร์ฟเวอร์ SWITCHY
 *
 * ใช้เมื่อผู้ใช้ต้องการแค่ไฟ Tally (ไม่มี Intercom เพราะการสื่อสารเสียงต้องมีเซิร์ฟเวอร์กลาง)
 *
 * vMix protocol: ส่ง "SUBSCRIBE TALLY\r\n" แล้วได้บรรทัด "TALLY OK 0120…"
 * ตำแหน่งตัวอักษรที่ n (เริ่ม 1) = สถานะของ input n : 0=off 1=program 2=preview
 */
class VmixDirectClient(
    private val listener: Listener,
    /** รหัสกล้องตามลำดับ input: index 0 = input 1 */
    private val cameras: List<String>
) {

    interface Listener {
        fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean)
        fun onConnected(info: String)
        fun onDisconnected(reason: String)
        fun onLog(line: String)
    }

    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    fun start(host: String, port: Int) {
        stop()
        running = true
        thread = thread(name = "switchy-vmix-direct") { loop(host, port) }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop(host: String, port: Int) {
        var backoff = 2000L
        while (running) {
            try {
                connectOnce(host, port)
                backoff = 2000L
            } catch (t: Throwable) {
                if (!running) return
                Log.w(TAG, "vMix ตรง: หลุด", t)
                listener.onDisconnected(t.message ?: t.javaClass.simpleName)
            }
            if (!running) return
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return
            }
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    private fun connectOnce(host: String, port: Int) {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 4000)
        s.soTimeout = 0

        val out: OutputStream = s.getOutputStream()
        out.write("SUBSCRIBE TALLY\r\n".toByteArray())
        out.flush()
        listener.onConnected("vMix $host:$port")
        listener.onLog("ต่อ vMix ตรงแล้ว ($host:$port)")

        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        while (running) {
            val line = reader.readLine() ?: throw IllegalStateException("vMix ปิดการเชื่อมต่อ")
            val trimmed = line.trim()
            when {
                trimmed.startsWith("TALLY OK", ignoreCase = true) -> {
                    val digits = trimmed.substring(8).trim()
                    listener.onTally(parse(digits), "vmix-direct", true)
                }

                trimmed.startsWith("TALLY ERR", ignoreCase = true) -> listener.onLog("vMix: $trimmed")
                trimmed.startsWith("VERSION", ignoreCase = true) -> listener.onLog("vMix: $trimmed")
            }
        }
    }

    /** "0120" -> {cam1: safe, cam2: program, cam3: preview, cam4: safe} */
    private fun parse(digits: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((i, ch) in digits.withIndex()) {
            val code = cameras.getOrNull(i) ?: "in${i + 1}"
            out[code] = when (ch) {
                '1' -> "program"
                '2' -> "preview"
                else -> "safe"
            }
        }
        return out
    }

    companion object {
        private const val TAG = "SwitchyVmixDirect"
    }
}
