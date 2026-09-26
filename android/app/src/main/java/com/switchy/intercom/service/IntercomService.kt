package com.switchy.intercom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.switchy.intercom.MainActivity
import com.switchy.intercom.R
import com.switchy.intercom.audio.AudioCodecs
import com.switchy.intercom.audio.AudioEngine
import com.switchy.intercom.audio.AudioParams
import com.switchy.intercom.audio.NativeAudio
import com.switchy.intercom.data.Prefs
import com.switchy.intercom.net.Bus
import com.switchy.intercom.net.ChatMsg
import com.switchy.intercom.net.ChatReply
import com.switchy.intercom.net.ControlClient
import com.switchy.intercom.net.Peer
import com.switchy.intercom.net.ReplyCode
import com.switchy.intercom.net.VmixDirectClient
import com.switchy.intercom.net.Welcome

/**
 * บริการเบื้องหลัง (Foreground Service) — หัวใจที่ทำให้ระบบไม่ตายเมื่อดับจอ
 *
 * ทำสามอย่าง:
 *   1) ถือ WebSocket (Tally + ควบคุม PTT)
 *   2) ถือ Audio Engine (ไมค์/ลำโพง/UDP)
 *   3) ขอ WifiLock + WakeLock + AudioMode = MODE_IN_COMMUNICATION
 *      (ถ้าไม่มีสามข้อหลัง Android จะตัด Wi-Fi/เสียงเมื่อล็อกจอ)
 */
class IntercomService : Service(), ControlClient.Listener {

    inner class LocalBinder : Binder() {
        fun service(): IntercomService = this@IntercomService
    }

    private val binder = LocalBinder()
    private lateinit var prefs: Prefs
    private lateinit var control: ControlClient
    private lateinit var engine: AudioEngine
    private lateinit var audioManager: AudioManager

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    private var vmixDirect: VmixDirectClient? = null

    @Volatile private var connectedHost: String = ""
    @Volatile private var connectedPort: Int = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        engine = AudioEngine(AudioParams.fromServer(20, 48_000), log = { line -> Bus.log(line) }) { stats ->
            Bus.setAudio(stats)
        }
        control = ControlClient(this)
        createChannel()
        startForegroundCompat(getString(R.string.notif_text, "…"))
        Bus.log("บริการ Intercom เริ่มทำงาน")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PTT_ON -> setPTT(true)
            ACTION_PTT_OFF -> setPTT(false)

            else -> connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
        instance = null
        Bus.log("บริการ Intercom หยุดทำงาน")
    }

    // ------------------------------------------------------------------ เชื่อมต่อ

    fun connect() {
        if (prefs.directVmix) {
            connectDirectVmix()
            return
        }
        val host = prefs.host
        if (host.isEmpty()) {
            Bus.setStatus(Bus.Status.ERROR, "ยังไม่ได้ตั้งค่า IP เซิร์ฟเวอร์")
            return
        }
        Bus.setMode(Bus.Mode.SERVER)
        connectedHost = host
        connectedPort = prefs.wsPort
        Bus.setStatus(Bus.Status.CONNECTING, "$host:${prefs.wsPort}")
        Bus.log("เริ่มเชื่อมต่อ $host:${prefs.wsPort}")
        acquireLocks()
        control.connect(
            host = host,
            port = prefs.wsPort,
            name = prefs.name.ifEmpty { "มือถือ" },
            camera = prefs.camera,
            token = prefs.token,
            codecName = if (prefs.preferOpus || NativeAudio.available) "opus" else "pcm",
            audioPort = 0
        )
    }

    /**
     * โหมดต่อ vMix ตรง — ใช้เฉพาะไฟ Tally (ไม่ต้องมีเซิร์ฟเวอร์ SWITCHY)
     * Intercom ปิดในโหมดนี้ เพราะเสียงต้องมีเซิร์ฟเวอร์กลางคอยกระจาย
     */
    private fun connectDirectVmix() {
        val host = prefs.vmixHost
        if (host.isEmpty()) {
            Bus.setStatus(Bus.Status.ERROR, "ยังไม่ได้ตั้งค่า IP ของ vMix")
            return
        }
        Bus.setMode(Bus.Mode.VMIX_DIRECT)
        Bus.setStatus(Bus.Status.CONNECTING, "vMix $host:${prefs.vmixPort} (โหมดตรง)")
        Bus.log("โหมดต่อ vMix ตรง: $host:${prefs.vmixPort} · Intercom จะไม่ทำงาน")
        acquireLocks()

        val client = VmixDirectClient(object : VmixDirectClient.Listener {
            override fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean) {
                Bus.setTally(states, source, vmixUp, 0)
            }

            override fun onConnected(info: String) {
                Bus.setStatus(Bus.Status.CONNECTED, info)
                updateNotification("โหมด vMix ตรง · cam ${prefs.camera}")
            }

            override fun onDisconnected(reason: String) {
                Bus.setStatus(Bus.Status.ERROR, "vMix: $reason")
                Bus.setTally(emptyMap(), "vmix-direct", false, 0)
            }

            override fun onLog(line: String) {
                Bus.log(line)
            }
        }, prefs.cameraList())
        vmixDirect = client
        client.start(host, prefs.vmixPort)
        updateNotification("โหมด vMix ตรง · ${host}")
    }

    /** ตอบกลับข้อความด้วยปุ่มสำเร็จรูป (โหมดเซิร์ฟเวอร์เท่านั้น) */
    fun reply(code: ReplyCode, msgId: Long) {
        control.sendReply(code, msgId)
        Bus.log("ตอบกลับ: ${code.label}")
    }

    fun disconnect() {
        runCatching { vmixDirect?.stop() }
        vmixDirect = null
        control.disconnect()
        engine.stop()
        releaseLocks()
        Bus.setStatus(Bus.Status.DISCONNECTED, "")
        Bus.log("ออกจากห้องแล้ว")
    }

    private fun shutdown() {
        runCatching { vmixDirect?.stop() }
        vmixDirect = null
        runCatching { control.disconnect() }
        runCatching { engine.stop() }
        runCatching { control.shutdown() }
        releaseLocks()
        Bus.reset()
        Bus.setMode(Bus.Mode.SERVER)
    }

    // ------------------------------------------------------------------ PTT / คำสั่ง

    fun setPTT(on: Boolean) {
        engine.setPTT(on)
        control.setPTT(on)
        updateNotification(if (on) getString(R.string.notif_ptt_on) else getString(R.string.notif_ptt_off))
    }

    fun setCamera(code: String) {
        control.setCamera(code)
        prefs.camera = code
    }

    fun setGateEnabled(enabled: Boolean) {
        engine.gate.enabled = enabled
        prefs.gateEnabled = enabled
    }

    fun setGateThresholdDb(db: Float) {
        engine.gate.thresholdDb = db
        prefs.gateThresholdDb = db
    }

    fun setMuteWhileTalking(mute: Boolean) {
        engine.muteWhileTalking = mute
        prefs.muteWhileTalking = mute
    }

    fun engineRef(): AudioEngine = engine

    fun isAudioRunning(): Boolean = engine.isRunning

    // ------------------------------------------------------------------ ControlClient.Listener

    override fun onWelcome(welcome: Welcome) {
        Bus.setStatus(Bus.Status.CONNECTED, welcome.toString())
        prefs.audioPort = if (welcome.audioPort > 0) welcome.audioPort else prefs.audioPort
        if (welcome.camera.isNotEmpty()) prefs.camera = welcome.camera

        // ตั้งค่า frame/sample rate ตามที่เซิร์ฟเวอร์กำหนด (ต้องตรงกันทั้งระบบ)
        val params = AudioParams.fromServer(welcome.frameMs, welcome.sampleRate)
        engine.reconfigure(params)

        // เลือกตัวเข้ารหัส: ถ้าบิลด์นี้มี libopus (native build) ให้ใช้ Opus อัตโนมัติ
        val (codec, note) = AudioCodecs.pick(prefs.preferOpus || NativeAudio.available, params)
        engine.codec = codec
        engine.codecNote = note
        engine.gate.enabled = prefs.gateEnabled
        engine.gate.thresholdDb = prefs.gateThresholdDb
        engine.muteWhileTalking = prefs.muteWhileTalking
        engine.fullDuplex = welcome.fullDuplex
        engine.jitterFrames = 3

        // ที่อยู่เสียง: ใช้ที่เซิร์ฟเวอร์บอก ถ้าว่างให้ใช้ IP เดียวกับ WebSocket
        val audioHost = welcome.audioHost.ifEmpty { connectedHost }
        val audioPort = if (welcome.audioPort > 0) welcome.audioPort else prefs.audioPort
        val ok = engine.start(audioHost, audioPort, welcome.clientNum)
        if (ok) {
            Bus.log("เสียงเดินแล้ว: ${engine.destDescription} · ${note} · ${engine.effectsStatus}")
        } else {
            Bus.setStatus(Bus.Status.ERROR, "เปิดไมค์/ลำโพงไม่ได้")
        }
        updateNotification(getString(R.string.notif_text, "${welcome.server} · cam ${prefs.camera}"))
    }

    override fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean, seq: Long) {
        Bus.setTally(states, source, vmixUp, seq)
    }

    override fun onPeers(peers: List<Peer>) {
        Bus.setPeers(peers)
    }

    override fun onTalkers(nums: List<Int>) {
        Bus.setTalkers(nums)
    }

    override fun onServerError(message: String) {
        Bus.setStatus(Bus.Status.ERROR, message)
        Bus.log("เซิร์ฟเวอร์แจ้ง: $message")
    }

    override fun onClosed(reason: String) {
        Bus.setStatus(Bus.Status.ERROR, reason)
        Bus.log(reason)
        runCatching { engine.stop() }
    }

    override fun onLog(line: String) {
        Bus.log(line)
    }

    override fun onMessage(message: ChatMsg) {
        Bus.addMessage(message)
        Bus.log("ข้อความจาก ${message.from}: ${message.text}")
        updateNotification(if (message.isAlert) "⚠ ${message.text}" else "${message.from}: ${message.text}")
    }

    override fun onReply(reply: ChatReply) {
        Bus.addReply(reply)
    }

    override fun onMessageHistory(messages: List<ChatMsg>, replies: List<ChatReply>) {
        Bus.setMessages(messages, replies)
    }

    // ------------------------------------------------------------------ ล็อก/โหมดเสียง

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null && wifiLock == null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "switchy:wifi")
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
                Bus.log("ล็อก Wi-Fi ไว้ไม่ให้หลับ")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WifiLock ไม่สำเร็จ", t)
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchy:cpu")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WakeLock ไม่สำเร็จ", t)
        }
        try {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (t: Throwable) {
            Log.w(TAG, "ตั้งโหมดเสียงไม่สำเร็จ", t)
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { audioManager.mode = previousAudioMode }
    }

    // ------------------------------------------------------------------ แจ้งเตือน

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "แจ้งเตือนการทำงานของ Intercom และ Tally"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, IntercomService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_disconnect), stop)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
    }

    companion object {
        private const val TAG = "SwitchyService"
        private const val CHANNEL_ID = "switchy_intercom"
        private const val NOTIF_ID = 4201

        const val ACTION_CONNECT = "com.switchy.intercom.action.CONNECT"
        const val ACTION_DISCONNECT = "com.switchy.intercom.action.DISCONNECT"
        const val ACTION_PTT_ON = "com.switchy.intercom.action.PTT_ON"
        const val ACTION_PTT_OFF = "com.switchy.intercom.action.PTT_OFF"

        @Volatile
        var instance: IntercomService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, IntercomService::class.java).setAction(ACTION_CONNECT)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IntercomService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
        }
    }
}
