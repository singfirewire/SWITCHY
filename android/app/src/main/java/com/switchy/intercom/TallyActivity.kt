package com.switchy.intercom

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.switchy.intercom.audio.AudioEngine
import com.switchy.intercom.data.Prefs
import com.switchy.intercom.databinding.ActivityTallyBinding
import com.switchy.intercom.net.Bus
import com.switchy.intercom.net.ChatMsg
import com.switchy.intercom.net.ChatReply
import com.switchy.intercom.net.Peer
import com.switchy.intercom.net.TallyState
import com.switchy.intercom.service.IntercomService
import com.switchy.intercom.ui.CameraChips
import kotlin.math.roundToInt

/**
 * หน้าจอ Tally + Intercom
 *
 * - พื้นจอเปลี่ยนสีตามสถานะกล้องที่รับผิดชอบ (แดง = PROGRAM, เขียว = PREVIEW, เทา = SAFE)
 * - ปุ่ม PTT ขนาดใหญ่ (กดค้างเพื่อพูด) ใช้พื้นที่นิ้วโป้ง แตะน้อยที่สุด
 * - โชว์ระดับไมค์/สถานะเสียง และรายชื่อคนในห้อง
 */
class TallyActivity : Activity(), Bus.Listener {

    private lateinit var b: ActivityTallyBinding
    private lateinit var prefs: Prefs
    private var camera: String = "cam1"
    private var pttOn = false
    private var lastPromptAt = 0L
    private var lastState: TallyState = TallyState.UNKNOWN

    private companion object {
        /** เว้นช่วงก่อนเด้งถามซ้ำ (กันเด้งรัวตอนระบบลองเชื่อมซ้ำเอง) */
        private const val PROMPT_COOLDOWN_MS = 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTallyBinding.inflate(layoutInflater)
        setContentView(b.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        camera = prefs.camera
        b.cbGate.isChecked = prefs.gateEnabled
        b.cbMute.isChecked = prefs.muteWhileTalking

        b.cbGate.setOnCheckedChangeListener { _, checked ->
            IntercomService.instance?.setGateEnabled(checked)
        }
        b.cbMute.setOnCheckedChangeListener { _, checked ->
            IntercomService.instance?.setMuteWhileTalking(checked)
        }

        b.btnPtt.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    setPTT(true)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    setPTT(false)
                    true
                }

                else -> false
            }
        }

        b.btnExit.setOnClickListener {
            IntercomService.stop(this)
            Bus.reset()
            finish()
        }

        b.btnMessages.setOnClickListener { openMessages() }
        b.tvBanner.setOnClickListener { openMessages() }

        if (Bus.mode == Bus.Mode.VMIX_DIRECT) applyDirectMode()
        renderMessages(Bus.messages, Bus.replies)

        CameraChips.render(this, b.rowCameras, Bus.cameras, camera) { code ->
            camera = code
            prefs.camera = code
            IntercomService.instance?.setCamera(code)
            applyTally(Bus.states)
        }

        applyTally(Bus.states)
        renderPeers(Bus.peers)
        updateStatus(Bus.status, Bus.statusDetail)
        val stats = Bus.lastStats
        if (stats != null) renderAudio(stats)
    }

    override fun onStart() {
        super.onStart()
        Bus.register(this)
    }

    override fun onStop() {
        // กันไมค์ค้าง: ถ้าออกจากจอตอนกดพูดอยู่ ให้ปล่อยก่อนเสมอ
        if (pttOn) setPTT(false)
        Bus.unregister(this)
        super.onStop()
    }

    @Deprecated("ใช้ onBackPressed ของ Activity (minSdk 26)")
    override fun onBackPressed() {
        Toast.makeText(this, "Intercom ยังทำงานอยู่ — แตะการแจ้งเตือนเพื่อกลับมา", Toast.LENGTH_LONG).show()
        super.onBackPressed()
    }

    // ------------------------------------------------------------ ข้อความ / โหมด

    private fun openMessages() {
        Bus.markAllRead()
        startActivity(Intent(this, MessageActivity::class.java))
    }

    /** โหมดต่อ vMix ตรง: ไม่มี Intercom → ปิดปุ่ม PTT พร้อมบอกเหตุผลบนจอ */
    private fun applyDirectMode() {
        b.btnPtt.isEnabled = false
        b.btnPtt.alpha = 0.4f
        b.btnPtt.text = getString(R.string.hint_intercom_off)
        b.pbMic.progress = 0
        b.tvMicDb.text = "—"
        b.tvConn.text = "โหมดต่อ vMix ตรง (Tally เท่านั้น)"
    }

    override fun onMessages(messages: List<ChatMsg>, replies: List<ChatReply>) {
        renderMessages(messages, replies)
    }

    private fun renderMessages(messages: List<ChatMsg>, replies: List<ChatReply>) {
        val unread = Bus.unreadCount()
        b.btnMessages.text = if (unread > 0) "${getString(R.string.btn_messages)} ($unread)" else getString(R.string.btn_messages)

        val latest = messages.lastOrNull()
        if (latest == null) {
            b.tvBanner.visibility = View.GONE
            return
        }
        b.tvBanner.visibility = View.VISIBLE
        b.tvBanner.text = buildString {
            append(if (latest.isAlert) "⚠ " else "📣 ")
            append(latest.from).append(": ").append(latest.text)
            if (unread > 1) append("   (+${unread - 1} ข้อความใหม่)")
            append("   — แตะเพื่ออ่าน/ตอบกลับ")
        }
    }

    // ------------------------------------------------------------ PTT

    @SuppressLint("ClickableViewAccessibility")
    private fun setPTT(on: Boolean) {
        pttOn = on
        IntercomService.instance?.setPTT(on)
        b.btnPtt.text = if (on) getString(R.string.btn_ptt_active) else getString(R.string.btn_ptt)
        // ขณะพูด พื้นจอเปลี่ยนเป็นสีน้ำเงินเข้ม เพื่อให้รู้ทันทีว่ากำลังเปิดไมค์
        if (on) {
            b.root.setBackgroundColor(getColor(R.color.ptt))
        } else {
            applyTally(Bus.states)
        }
    }

    // ------------------------------------------------------------ Bus.Listener

    override fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean, seq: Long) {
        applyTally(states)
        b.tvVmix.text = "Tally: $source" + if (vmixUp) " · vMix ต่ออยู่" else " · vMix ขาดการเชื่อมต่อ"
    }

    override fun onPeers(peers: List<Peer>) {
        renderPeers(peers)
    }

    override fun onTalkers(nums: List<Int>) {
        if (nums.isEmpty()) return
        val names = nums.map { num -> Bus.peers.firstOrNull { it.num == num }?.name ?: "เครื่อง $num" }
        b.tvPeers.text = "🎙 กำลังพูด: " + names.joinToString(", ")
    }

    override fun onStatus(status: Bus.Status, detail: String) {
        updateStatus(status, detail)
        when (status) {
            Bus.Status.CONNECTED -> lastPromptAt = 0L
            Bus.Status.ERROR -> promptForSettings(detail)
            else -> Unit
        }
    }

    /**
     * ต่อไม่ติด → ถามว่าจะไปกรอก IP เองไหม
     * ถามครั้งแรกทันที แต่ครั้งต่อไปต้องห่างกัน 60 วิ (กันเด้งรัวตอนระบบลองเชื่อมซ้ำ)
     */
    private fun promptForSettings(detail: String) {
        if (isFinishing) return
        val now = System.currentTimeMillis()
        if (now - lastPromptAt < PROMPT_COOLDOWN_MS) return
        lastPromptAt = now
        val target = if (Bus.mode == Bus.Mode.VMIX_DIRECT) "vMix" else "เซิร์ฟเวอร์"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_conn_failed_title))
            .setMessage(getString(R.string.dlg_conn_failed_msg, target, detail))
            .setPositiveButton(R.string.dlg_open_settings) { _, _ ->
                startActivity(MainActivity.intentManual(this))
                finish()
            }
            .setNegativeButton(R.string.dlg_retry) { _, _ ->
                lastPromptAt = 0L
                IntercomService.instance?.connect()
            }
            .setNeutralButton(R.string.dlg_close, null)
            .show()
    }

    override fun onAudio(stats: AudioEngine.Stats) {
        renderAudio(stats)
    }

    // ------------------------------------------------------------ วาดจอ

    private fun applyTally(states: Map<String, String>) {
        val state = TallyState.from(states[camera])
        lastState = state
        if (pttOn) return

        val color = when (state) {
            TallyState.PROGRAM -> R.color.tally_program
            TallyState.PREVIEW -> R.color.tally_preview
            TallyState.SAFE -> R.color.tally_safe
            TallyState.UNKNOWN -> R.color.tally_unknown
        }
        b.root.setBackgroundColor(getColor(color))
        b.tvCameraCode.text = camera.uppercase()
        b.tvCameraName.text = camera.replaceFirstChar { it.uppercase() }
        b.tvState.text = when (state) {
            TallyState.PROGRAM -> getString(R.string.state_program)
            TallyState.PREVIEW -> getString(R.string.state_preview)
            TallyState.SAFE -> getString(R.string.state_safe)
            TallyState.UNKNOWN -> getString(R.string.state_unknown)
        }
    }

    private fun renderPeers(peers: List<Peer>) {
        if (peers.isEmpty()) {
            b.tvPeers.text = getString(R.string.label_peers) + ": — ยังไม่มีเครื่องอื่น"
            return
        }
        b.tvPeers.text = getString(R.string.label_peers) + ": " +
            peers.joinToString("  ·  ") { "${it.name}[${it.camera}]" + if (it.ptt) "🎙" else "" }
    }

    private fun renderAudio(stats: AudioEngine.Stats) {
        if (Bus.mode == Bus.Mode.VMIX_DIRECT) {
            b.tvVmix.text = "โหมดต่อ vMix ตรง · ไม่มีเสียง Intercom"
            return
        }
        val pct = (((stats.micLevelDb + 60f) / 60f).coerceIn(0f, 1f) * 100f).roundToInt()
        b.pbMic.progress = pct
        b.tvMicDb.text = if (stats.micLevelDb <= -119f) "—" else "${stats.micLevelDb.roundToInt()} dB"
        b.tvVmix.text = buildString {
            append(stats.backend).append(" · ").append(stats.codecName)
            if (stats.txPackets > 0) append(" · tx ").append(stats.txPackets)
            if (stats.rxPackets > 0) append(" · rx ").append(stats.rxPackets)
            if (stats.sources > 0) append(" · ผู้พูด ").append(stats.sources)
        }
        if (!stats.speakerRunning || !stats.micRunning) {
            b.tvConn.text = "ไมค์/ลำโพงยังไม่ทำงาน"
        }
    }

    private fun updateStatus(status: Bus.Status, detail: String) {
        b.tvConn.text = when (status) {
            Bus.Status.CONNECTED -> "● เชื่อมต่อแล้ว"
            Bus.Status.CONNECTING -> "○ กำลังเชื่อมต่อ…"
            Bus.Status.ERROR -> "⚠ ${detail.ifEmpty { "ผิดพลาด" }}"
            Bus.Status.DISCONNECTED -> "○ ไม่ได้เชื่อมต่อ"
        }
    }
}
