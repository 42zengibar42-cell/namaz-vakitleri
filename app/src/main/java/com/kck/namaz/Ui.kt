package com.kck.namaz

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

object C {
    const val BG = 0xFF0B2A22.toInt()
    const val CARD = 0xFF12392E.toInt()
    const val HI = 0xFF1E5A47.toInt()
    const val GOLD = 0xFFE8BE5C.toInt()
    const val TEXT = 0xFFF3EFE4.toInt()
    const val MUTED = 0xFF9DB8AE.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.tv(s: String, sp: Float = 16f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun roundBg(color: Int, radius: Float, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke != 0) setStroke(2, stroke)
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = roundBg(C.CARD, dp(16).toFloat())
    setPadding(dp(16), dp(14), dp(16), dp(14))
}

fun Context.button(s: String, onClick: () -> Unit): TextView = tv(s, 15f, C.BG, true).apply {
    gravity = Gravity.CENTER
    background = roundBg(C.GOLD, dp(12).toFloat())
    setPadding(dp(10), dp(12), dp(10), dp(12))
    setOnClickListener { onClick() }
}

fun Context.ghostButton(s: String, onClick: () -> Unit): TextView = tv(s, 15f, C.GOLD, true).apply {
    gravity = Gravity.CENTER
    background = roundBg(C.CARD, dp(12).toFloat(), C.GOLD)
    setPadding(dp(10), dp(12), dp(10), dp(12))
    setOnClickListener { onClick() }
}

fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun View.margins(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = (layoutParams as? LinearLayout.LayoutParams) ?: lp()
    p.setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
    layoutParams = p
    return this
}

object Hijri {
    /** (gün, ay 0-11, yıl) */
    fun of(date: LocalDate): Triple<Int, Int, Int>? = try {
        val cal = android.icu.util.Calendar.getInstance(android.icu.util.ULocale("@calendar=islamic-umalqura"))
        cal.clear()
        val g = android.icu.util.GregorianCalendar(date.year, date.monthValue - 1, date.dayOfMonth, 12, 0)
        cal.timeInMillis = g.timeInMillis
        Triple(
            cal.get(android.icu.util.Calendar.DAY_OF_MONTH),
            cal.get(android.icu.util.Calendar.MONTH),
            cal.get(android.icu.util.Calendar.YEAR)
        )
    } catch (e: Throwable) { null }

    fun text(l: L, date: LocalDate): String {
        val h = of(date) ?: return ""
        return "${h.first} ${l.hijriMonths.getOrElse(h.second) { "" }} ${h.third}"
    }
}
