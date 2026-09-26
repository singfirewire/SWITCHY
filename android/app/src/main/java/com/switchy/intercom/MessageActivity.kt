package com.switchy.intercom

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.switchy.intercom.databinding.ActivityMessagesBinding
import com.switchy.intercom.net.Bus
import com.switchy.intercom.net.ChatMsg
import com.switchy.intercom.net.ChatReply
import com.switchy.intercom.net.ReplyCode
import com.switchy.intercom.service.IntercomService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * จอข้อความจากศูนย์ควบคุม
 *
 * ดีไซน์ตามที่กำหนด: **อ่านได้อย่างเดียว** — เครื่องลูกพิมพ์ตอบกลับไม่ได้
 * แต่มีปุ่มตอบกลับสำเร็จรูป 3 ปุ่ม (รับทราบ / ไม่เห็นด้วย / ขอความช่วยเหลือ)
 */
class MessageActivity : Activity(), Bus.Listener {

    private lateinit var b: ActivityMessagesBinding
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnAck.setOnClickListener { sendReply(ReplyCode.ACK) }
        b.btnDisagree.setOnClickListener { sendReply(ReplyCode.DISAGREE) }
        b.btnHelp.setOnClickListener { sendReply(ReplyCode.HELP) }

        if (Bus.mode == Bus.Mode.VMIX_DIRECT) {
            b.tvHint.text = getString(R.string.hint_messages_no_server)
            setReplyEnabled(false)
        }
        render(Bus.messages, Bus.replies)
        Bus.markAllRead()
    }

    override fun onStart() {
        super.onStart()
        Bus.register(this)
    }

    override fun onStop() {
        Bus.unregister(this)
        super.onStop()
    }

    // ------------------------------------------------------------ การตอบกลับ

    private fun sendReply(code: ReplyCode) {
        val latest = Bus.latestMessage()
        if (latest == null) {
            Toast.makeText(this, getString(R.string.no_message_to_reply), Toast.LENGTH_SHORT).show()
            return
        }
        val svc = IntercomService.instance
        if (svc == null || Bus.mode == Bus.Mode.VMIX_DIRECT) {
            Toast.makeText(this, getString(R.string.hint_messages_no_server), Toast.LENGTH_LONG).show()
            return
        }
        svc.reply(code, latest.id)
        b.tvReplyState.text = getString(R.string.reply_sent, code.label, timeFmt.format(Date()))
        Toast.makeText(this, getString(R.string.reply_sent, code.label, timeFmt.format(Date())), Toast.LENGTH_SHORT).show()
    }

    private fun setReplyEnabled(enabled: Boolean) {
        for (v in listOf<View>(b.btnAck, b.btnDisagree, b.btnHelp)) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.4f
        }
    }

    // ------------------------------------------------------------ วาดรายการ

    override fun onMessages(messages: List<ChatMsg>, replies: List<ChatReply>) {
        render(messages, replies)
    }

    private fun render(messages: List<ChatMsg>, replies: List<ChatReply>) {
        val box = b.listMessages
        box.removeAllViews()

        if (messages.isEmpty()) {
            box.addView(hintView(getString(R.string.no_messages_yet)))
            return
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // แสดงจากใหม่ -> เก่า (ข้อความล่าสุดอยู่บนสุด เพื่อให้อ่านง่ายบนมือถือ)
        for (m in messages.sortedByDescending { it.id }) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_panel)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val head = TextView(this).apply {
                text = "${timeFmt.format(Date(m.atMillis))}  ·  ${m.from}" +
                    if (m.isAlert) "  ·  ⚠ ด่วน" else ""
                setTextColor(getColor(if (m.isAlert) R.color.warn else R.color.dim))
                textSize = 11f
            }
            card.addView(head)

            val body = TextView(this).apply {
                text = m.text
                setTextColor(getColor(R.color.txt))
                textSize = 16f
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(body)

            // คำตอบของทีมที่มีต่อข้อความนี้
            val myReplies = replies.filter { it.msgId == m.id || it.msgId == 0L }
                .filter { it.msgId == m.id }
            if (myReplies.isNotEmpty()) {
                val line = TextView(this).apply {
                    text = "ตอบกลับ: " + myReplies.joinToString("  ·  ") { "${it.name}: ${it.label}" }
                    setTextColor(getColor(R.color.ok))
                    textSize = 12f
                    setPadding(0, dp(8), 0, 0)
                    typeface = Typeface.DEFAULT
                }
                card.addView(line)
            }

            box.addView(card)
        }
    }

    private fun hintView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.dim))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, 40, 0, 40)
    }

    override fun onStatus(status: Bus.Status, detail: String) {
        if (status == Bus.Status.ERROR && detail.isNotEmpty()) {
            b.tvReplyState.text = detail
        }
    }
}
