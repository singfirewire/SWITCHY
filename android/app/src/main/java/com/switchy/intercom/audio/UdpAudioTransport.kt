package com.switchy.intercom.audio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * ช่องทาง UDP สำหรับเสียง Intercom
 *
 * - ใช้ socket เดียวทั้งส่งและรับ (ผูก ephemeral port)
 * - เซิร์ฟเวอร์จะเรียนรู้ ip:port จากแพ็กเก็ตแรกที่ส่งไป
 * - ตั้ง buffer ใหญ่พอสำหรับ burst หลายสายพร้อมกัน
 */
class UdpAudioTransport(private val log: (String) -> Unit) {

    private var socket: DatagramSocket? = null
    private var dest: InetSocketAddress? = null
    private var rxThread: Thread? = null

    @Volatile private var running = false

    var localPort: Int = 0
        private set

    var destDescription: String = "-"
        private set

    fun open(host: String, port: Int): Boolean {
        close()
        return try {
            val ds = DatagramSocket()
            ds.soTimeout = 400
            runCatching { ds.receiveBufferSize = 1 shl 20 }
            runCatching { ds.sendBufferSize = 1 shl 20 }
            val address = InetSocketAddress(InetAddress.getByName(host), port)
            socket = ds
            dest = address
            localPort = ds.localPort
            destDescription = "$host:$port"
            Log.i(TAG, "UDP พร้อม local=${ds.localPort} dest=$destDescription")
            true
        } catch (t: Throwable) {
            log("เปิด UDP ไม่ได้: ${t.message}")
            false
        }
    }

    fun send(packet: ByteArray): Boolean {
        val s = socket ?: return false
        val d = dest ?: return false
        return try {
            s.send(DatagramPacket(packet, packet.size, d))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "ส่ง UDP ไม่ได้", t)
            false
        }
    }

    /** เริ่ม thread รับแพ็กเก็ต (เรียกครั้งเดียวหลัง open) */
    fun startReceive(onPacket: (ByteArray, Int) -> Unit) {
        if (rxThread != null) return
        running = true
        rxThread = thread(name = "switchy-udp-rx") {
            val buf = ByteArray(2048)
            while (running) {
                val s = socket ?: break
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    if (pkt.length > 0) onPacket(pkt.data, pkt.length)
                } catch (_: java.net.SocketTimeoutException) {
                    // ปกติ: หมดเวลาแล้ววนใหม่เพื่อเช็ค running
                } catch (t: Throwable) {
                    if (running) Log.w(TAG, "รับ UDP ผิดพลาด", t)
                    break
                }
            }
        }
    }

    fun close() {
        running = false
        rxThread?.interrupt()
        rxThread = null
        runCatching { socket?.close() }
        socket = null
        dest = null
        localPort = 0
    }

    companion object {
        private const val TAG = "SwitchyUdp"
    }
}
