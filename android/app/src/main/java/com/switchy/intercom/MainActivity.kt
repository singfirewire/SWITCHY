package com.switchy.intercom

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.switchy.intercom.data.Prefs
import com.switchy.intercom.databinding.ActivityMainBinding
import com.switchy.intercom.net.Bus
import com.switchy.intercom.net.ControlClient
import com.switchy.intercom.service.IntercomService
import com.switchy.intercom.ui.CameraChips
import org.json.JSONObject

/**
 * หน้าจอตั้งค่า: ใส่ IP ของ Mini PC, เลือกกล้องที่รับผิดชอบ, แล้วเข้าโหมด Tally/Intercom
 *
 * ปุ่ม "ตรวจเซิร์ฟเวอร์" จะยิง HTTP ไปที่ /api/state เพื่อดึงรายการกล้องจริง
 * (ผู้ใช้ไม่ต้องพิมพ์รหัสกล้องเอง และรู้ทันทีว่า vMix ต่ออยู่หรือไม่)
 */
class MainActivity : Activity(), Bus.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var httpOnly: ControlClient
    private var selectedCamera: String = "cam1"
    private var pendingConnect: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)

        // เปิดแอปแล้วเข้าจอหลัก (Tally) ทันที ถ้าเคยตั้งค่าไว้แล้ว
        // — ถ้าต่อไม่ติด จอ Tally จะเด้งกลับมาที่หน้านี้ให้กรอก IP เอง (ดู TallyActivity)
        if (!intent.getBooleanExtra(EXTRA_MANUAL, false) && prefs.configured && !autoOpened) {
            autoOpened = true
            IntercomService.start(this)
            startActivity(Intent(this, TallyActivity::class.java))
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        httpOnly = ControlClient(object : ControlClient.Listener {})

        b.etHost.setText(prefs.host)
        b.etPort.setText(prefs.wsPort.toString())
        b.etName.setText(prefs.name)
        b.etToken.setText(prefs.token)
        b.etVmixHost.setText(prefs.vmixHost)
        b.etVmixPort.setText(prefs.vmixPort.toString())
        b.cbDirect.isChecked = prefs.directVmix
        applyModeUi(prefs.directVmix)
        b.cbDirect.setOnCheckedChangeListener { _, checked ->
            prefs.directVmix = checked
            applyModeUi(checked)
        }
        selectedCamera = prefs.camera
        CameraChips.render(this, b.rowCameras, prefs.cameraList(), selectedCamera) { code ->
            selectedCamera = code
            prefs.camera = code
        }

        b.btnCheck.setOnClickListener { checkServer() }
        b.btnConnect.setOnClickListener { connectToServer() }

        val logs = Bus.recentLogs()
        if (logs.isNotEmpty()) b.tvLog.text = logs.takeLast(8).joinToString("\n")

        requestNeededPermissions(false)
    }

    override fun onStart() {
        super.onStart()
        Bus.register(this)
    }

    override fun onStop() {
        Bus.unregister(this)
        super.onStop()
    }

    // ------------------------------------------------------------ การทำงานหลัก

    /**
     * โหมดต่อ vMix ตรง: ปิดช่องของเซิร์ฟเวอร์ไว้ (ไม่ใช้) แล้วบอกเหตุผลบนจอ
     * — ผู้ใช้ยังสลับกลับได้ตลอดด้วยการเอาติ๊กออก
     */
    private fun applyModeUi(direct: Boolean) {
        val serverFields = listOf<android.view.View>(b.etHost, b.etPort, b.etToken, b.btnCheck)
        for (v in serverFields) {
            v.isEnabled = !direct
            v.alpha = if (direct) 0.4f else 1f
        }
        b.tvStatus.text = if (direct) {
            getString(R.string.hint_intercom_off)
        } else {
            getString(R.string.not_connected)
        }
        b.tvCameraHint.text = if (direct) {
            "โหมดตรง: รหัสกล้อง cam1..cam4 = input 1..4 ของ vMix"
        } else {
            "แตะ \"ตรวจเซิร์ฟเวอร์\" เพื่อดึงรายการกล้องจาก Mini PC (หรือใช้ cam1/cam2/cam3 ที่ตั้งไว้)"
        }
    }

    private fun checkServer() {
        val host = b.etHost.text.toString().trim()
        val port = b.etPort.text.toString().trim().toIntOrNull() ?: prefs.wsPort
        if (host.isEmpty()) {
            toast("ใส่ IP เซิร์ฟเวอร์ก่อนครับ")
            b.etHost.requestFocus()
            return
        }
        b.btnCheck.isEnabled = false
        b.tvStatus.text = "กำลังตรวจ $host:$port …"
        httpOnly.fetchState(host, port) { ok, body ->
            runOnUiThread {
                b.btnCheck.isEnabled = true
                if (!ok) {
                    b.tvStatus.text = "ตรวจไม่สำเร็จ: $body"
                    b.tvServerInfo.visibility = View.GONE
                    return@runOnUiThread
                }
                showServerInfo(host, port, body)
            }
        }
    }

    private fun showServerInfo(host: String, port: Int, body: String) {
        try {
            val json = JSONObject(body)
            val tally = json.optJSONObject("tally")
            val statesObj = tally?.optJSONObject("states")
            val codes = ArrayList<String>()
            statesObj?.keys()?.forEach { codes.add(it) }
            val states = codes.joinToString("  ") { "$it=${statesObj?.optString(it)}" }
            val source = tally?.optString("source", "?") ?: "?"
            val vmixUp = tally?.optBoolean("vmixUp", false) ?: false
            val peers = json.optJSONArray("peers")?.length() ?: 0

            b.tvServerInfo.visibility = View.VISIBLE
            b.tvServerInfo.text = buildString {
                append("เซิร์ฟเวอร์ตอบแล้ว\n")
                append("กล้อง: ").append(if (codes.isEmpty()) "-" else codes.joinToString(", ")).append('\n')
                append("สถานะตอนนี้: ").append(states.ifEmpty { "-" }).append('\n')
                append("Tally จาก: ").append(source).append(if (vmixUp) "  (vMix ต่ออยู่)" else "  (vMix ยังไม่ต่อ)").append('\n')
                append("เครื่องในห้อง: ").append(peers)
            }
            b.tvStatus.text = "พร้อมเชื่อมต่อ"

            if (codes.isNotEmpty()) {
                prefs.knownCameras = codes.joinToString(",")
                Bus.setCameras(codes)
                if (selectedCamera !in codes) {
                    selectedCamera = codes.first()
                    prefs.camera = selectedCamera
                }
                CameraChips.render(this, b.rowCameras, codes, selectedCamera) { code ->
                    selectedCamera = code
                    prefs.camera = code
                }
                b.tvCameraHint.text = "ดึงจากเซิร์ฟเวอร์แล้ว: " + codes.joinToString(" · ")
            }
        } catch (t: Throwable) {
            b.tvStatus.text = "อ่านข้อมูลเซิร์ฟเวอร์ไม่ได้: ${t.message}"
        }
    }

    private fun connectToServer() {
        val direct = b.cbDirect.isChecked
        val host = b.etHost.text.toString().trim()
        val port = b.etPort.text.toString().trim().toIntOrNull() ?: prefs.wsPort
        val vmixHost = b.etVmixHost.text.toString().trim()
        val vmixPort = b.etVmixPort.text.toString().trim().toIntOrNull() ?: 8099

        if (direct) {
            if (vmixHost.isEmpty()) {
                toast("ใส่ IP ของ vMix ก่อนครับ (โหมดต่อตรง)")
                b.etVmixHost.requestFocus()
                return
            }
        } else if (host.isEmpty()) {
            toast("ใส่ IP เซิร์ฟเวอร์ก่อนครับ")
            b.etHost.requestFocus()
            return
        }

        prefs.directVmix = direct
        prefs.vmixHost = vmixHost
        prefs.vmixPort = vmixPort
        prefs.host = host
        prefs.wsPort = port
        prefs.name = b.etName.text.toString()
        prefs.token = b.etToken.text.toString()
        prefs.camera = selectedCamera
        // จำว่าเคยตั้งค่าแล้ว → เปิดแอปครั้งต่อไปเข้าจอ Tally ทันที ไม่ต้องผ่านหน้านี้
        prefs.configured = true

        // โหมดต่อ vMix ตรงไม่ใช้ไมค์ → ไม่ต้องขอสิทธิ์ RECORD_AUDIO
        if (!direct) {
            val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!micOk) {
                pendingConnect = true
                requestNeededPermissions(true)
                return
            }
        }

        IntercomService.start(this)
        startActivity(Intent(this, TallyActivity::class.java))
    }

    // ------------------------------------------------------------ สิทธิ์

    private fun requestNeededPermissions(fromConnect: Boolean) {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            if (fromConnect) pendingConnect = true
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            toast(getString(R.string.perm_mic_needed))
            pendingConnect = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.perm_notif_needed))
        }
        if (pendingConnect && micGranted) {
            pendingConnect = false
            connectToServer()
        }
    }

    // ------------------------------------------------------------ Bus.Listener

    override fun onStatus(status: Bus.Status, detail: String) {
        b.tvStatus.text = when (status) {
            Bus.Status.CONNECTED -> "เชื่อมต่อแล้ว · $detail"
            Bus.Status.CONNECTING -> "กำลังเชื่อมต่อ · $detail"
            Bus.Status.ERROR -> "ผิดพลาด · $detail"
            Bus.Status.DISCONNECTED -> getString(R.string.not_connected)
        }
    }

    override fun onLog(line: String) {
        val current = b.tvLog.text?.toString().orEmpty()
        val merged = if (current.isEmpty()) line else "$current\n$line"
        b.tvLog.text = merged.lines().takeLast(12).joinToString("\n")
    }

    override fun onTally(states: Map<String, String>, source: String, vmixUp: Boolean, seq: Long) {
        if (states.isEmpty()) return
        val codes = states.keys.toList()
        val known = prefs.cameraList()
        if (codes != known) {
            prefs.knownCameras = codes.joinToString(",")
            CameraChips.render(this, b.rowCameras, codes, selectedCamera) { code ->
                selectedCamera = code
                prefs.camera = code
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        runCatching { httpOnly.shutdown() }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMS = 1001

        /** เปิดหน้านี้ในโหมด "ผู้ใช้สั่งเอง" — ห้ามเด้งไปจอ Tally อัตโนมัติ */
        private const val EXTRA_MANUAL = "manual"

        /** เด้งอัตโนมัติได้ครั้งเดียวต่อการเปิดแอปหนึ่งครั้ง (กันวนกลับไปกลับมา) */
        @Volatile
        private var autoOpened = false

        /** เปิดหน้าตั้งค่าจากจออื่น (ผู้ใช้กดเอง) */
        fun intentManual(from: android.content.Context): Intent =
            Intent(from, MainActivity::class.java).putExtra(EXTRA_MANUAL, true)
    }
}
