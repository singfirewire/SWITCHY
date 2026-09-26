package com.switchy.intercom.ui

import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.switchy.intercom.R

/**
 * ปุ่มเลือกรหัสกล้อง (chip) — สร้างจากรายการที่เซิร์ฟเวอร์บอก
 * เพื่อไม่ต้อง hardcode cam1/cam2/cam3 ไว้ในแอป
 */
object CameraChips {

    fun render(
        activity: Activity,
        row: LinearLayout,
        codes: List<String>,
        selected: String,
        onPick: (String) -> Unit
    ) {
        row.removeAllViews()
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        for (code in codes) {
            val chip = Button(activity)
            chip.text = code.uppercase()
            chip.isAllCaps = false
            chip.setTextColor(activity.getColor(R.color.txt))
            chip.background = activity.getDrawable(R.drawable.bg_chip)
            chip.minHeight = dp(44)
            chip.minWidth = dp(72)
            chip.setPadding(dp(14), dp(6), dp(14), dp(6))
            chip.isSelected = code == selected
            chip.tag = code
            chip.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            chip.setOnClickListener {
                for (i in 0 until row.childCount) {
                    row.getChildAt(i).isSelected = false
                }
                chip.isSelected = true
                onPick(code)
            }
            row.addView(chip)
        }
    }
}
