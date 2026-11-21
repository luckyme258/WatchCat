package com.luckyme.watchcat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 视觉层：整套界面只用系统控件 + 代码生成的 drawable，不引 AndroidX（规范 W7）。
 *
 * 配色口径：纯黑底 + 低对比卡片 + 一个薄荷绿主色。
 * 只有「可写」「已授权」这类正向状态才用主色；警告用琥珀，破坏性动作用红。
 * 不做渐变、不做阴影 —— 4GB 机器上少一层绘制，滚动才不会掉帧。
 */
object Ui {

    const val BG = 0xFF000000.toInt()
    const val CARD = 0xFF131316.toInt()
    const val CARD_ALT = 0xFF1C1C21.toInt()
    const val STROKE = 0xFF26262C.toInt()
    const val TEXT = 0xFFF3F3F5.toInt()
    const val MUTED = 0xFF8A8A93.toInt()
    const val FAINT = 0xFF5A5A63.toInt()
    const val ACCENT = 0xFF3DDC97.toInt()
    const val WARN = 0xFFF5A623.toInt()
    const val DANGER = 0xFFEF4D4D.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** 圆角填充；需要描边时同时给 stroke + strokeW */
    fun fill(color: Int, radiusPx: Int, stroke: Int? = null, strokeW: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx.toFloat()
        if (stroke != null && strokeW > 0) d.setStroke(strokeW, stroke)
        return d
    }

    fun alpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0, 255) shl 24))

    fun tv(c: Context, text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(c)
        v.text = text
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        v.setTextColor(color)
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD)
        v.includeFontPadding = false
        return v
    }

    fun col(c: Context, spaceDp: Int = 0): LinearLayout {
        val v = LinearLayout(c)
        v.orientation = LinearLayout.VERTICAL
        if (spaceDp > 0) v.setPadding(0, 0, 0, 0)
        return v
    }

    fun row(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout {
        val v = LinearLayout(c)
        v.orientation = LinearLayout.HORIZONTAL
        v.gravity = gravity
        return v
    }

    /** 卡片：圆角 16dp + 1dp 描边，内边距 14dp */
    fun card(c: Context, padDp: Int = 14): LinearLayout {
        val v = col(c)
        v.background = fill(CARD, dp(c, 16), STROKE, dp(c, 1))
        v.setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp))
        return v
    }

    fun spacer(c: Context, hDp: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp))
        return v
    }

    fun divider(c: Context, insetDp: Int = 0): View {
        val v = View(c)
        v.setBackgroundColor(STROKE)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1))
        lp.leftMargin = dp(c, insetDp)
        lp.rightMargin = dp(c, insetDp)
        v.layoutParams = lp
        return v
    }

    /** 小标题：左边一条 3dp 主色竖条，右边字数少但字重够 */
    fun sectionTitle(c: Context, text: String, note: String? = null): LinearLayout {
        val box = col(c)
        box.setPadding(0, dp(c, 18), 0, dp(c, 8))
        val head = row(c)
        val bar = View(c)
        bar.setBackgroundColor(ACCENT)
        val blp = LinearLayout.LayoutParams(dp(c, 3), dp(c, 16))
        blp.rightMargin = dp(c, 8)
        bar.layoutParams = blp
        head.addView(bar)
        head.addView(tv(c, text, 14f, TEXT, bold = true))
        box.addView(head)
        if (note != null) {
            val n = tv(c, note, 11.5f, MUTED)
            val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            nlp.leftMargin = dp(c, 11)
            nlp.topMargin = dp(c, 4)
            n.layoutParams = nlp
            box.addView(n)
        }
        return box
    }

    /** 圆角小标签，用于 uid / 系统应用 / 权限数量 */
    fun pill(c: Context, text: String, color: Int = MUTED, solid: Boolean = false): TextView {
        val v = tv(c, text, 11f, if (solid) BG else color)
        v.background = fill(if (solid) color else alpha(color, 0x22), dp(c, 8), color, dp(c, 1))
        v.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3))
        v.gravity = Gravity.CENTER
        return v
    }

    fun button(
        c: Context,
        label: String,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val base = when {
            danger -> DANGER
            primary -> ACCENT
            else -> TEXT
        }
        val v = tv(c, label, 12.5f, if (primary || danger) base else TEXT, bold = primary)
        v.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            fill(if (primary) alpha(ACCENT, 0x22) else CARD_ALT, dp(c, 10), base, dp(c, 1)),
            null,
        )
        v.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
        v.gravity = Gravity.CENTER
        v.isClickable = true
        v.setOnClickListener { onClick() }
        return v
    }

    /** 五态选择用小胶囊：选中 = 主色底 20% + 主色描边，未选中 = 灰描边 */
    fun chip(c: Context, label: String, selected: Boolean, tone: Int = ACCENT, onClick: () -> Unit): TextView {
        val v = tv(c, label, 11.5f, if (selected) tone else MUTED, bold = selected)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5))
        v.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            fill(if (selected) alpha(tone, 0x26) else CARD_ALT, dp(c, 9),
                if (selected) tone else STROKE, dp(c, 1)),
            null,
        )
        v.isClickable = true
        v.setOnClickListener { onClick() }
        return v
    }

    fun switchView(c: Context, checked: Boolean, on: (Boolean) -> Unit): Switch {
        val s = Switch(c)
        s.isChecked = checked // 先设值再挂监听，避免初始化触发一次「假操作」
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(alpha(ACCENT, 0x55), 0xFF3A3A42.toInt()),
        )
        val thumb = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ACCENT, 0xFF6E6E78.toInt()),
        )
        s.trackTintList = track
        s.thumbTintList = thumb
        s.setOnCheckedChangeListener { _, value -> on(value) }
        return s
    }

    /** 让某一行在点击时给个反馈（列表行用） */
    fun clickableBackground(c: Context, radiusDp: Int = 14, color: Int = CARD): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            fill(color, dp(c, radiusDp)),
            null,
        )
}
