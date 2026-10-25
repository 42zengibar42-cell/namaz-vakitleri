package com.kck.namaz

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MonthActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var l: L
    private var ym: YearMonth = YearMonth.now()
    private lateinit var title: TextView
    private lateinit var table: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); l = L(this)
        ym = YearMonth.from(LocalDate.now(prefs.zone))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG)
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(tv("‹", 34f, C.GOLD, true).apply { setPadding(dp(14), 0, dp(14), 0); setOnClickListener { ym = ym.minusMonths(1); fill() } })
        title = tv("", 19f, C.TEXT, true).apply { gravity = Gravity.CENTER }
        nav.addView(title, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(tv("›", 34f, C.GOLD, true).apply { setPadding(dp(14), 0, dp(14), 0); setOnClickListener { ym = ym.plusMonths(1); fill() } })
        root.addView(nav)
        root.addView(tv(prefs.city.name, 13f, C.MUTED).apply { gravity = Gravity.CENTER }, lp())

        root.addView(row(arrayOf(l.t("Gün", "Tag")) + l.shortNames, header = true, today = false), lp().apply { topMargin = dp(8) })
        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(table) }
        root.addView(scroll, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        fill()
    }

    private fun row(cells: Array<String>, header: Boolean, today: Boolean): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(9), dp(2), dp(9))
            if (today) background = roundBg(C.HI, dp(8).toFloat())
        }
        cells.forEachIndexed { i, s ->
            val t = tv(s, if (header) 11f else 13f, if (header) C.GOLD else if (today) C.GOLD else C.TEXT, header || today || i == 0)
            t.gravity = Gravity.CENTER
            t.maxLines = 2
            r.addView(t, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i == 0) 1.25f else 1f))
        }
        return r
    }

    private fun fill() {
        title.text = ym.month.getDisplayName(TextStyle.FULL_STANDALONE, l.locale)
            .replaceFirstChar { it.titlecase(l.locale) } + " " + ym.year
        table.removeAllViews()
        val f = SimpleDateFormat("HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone(prefs.zone) }
        val today = LocalDate.now(prefs.zone)
        val dayFmt = DateTimeFormatter.ofPattern("d EE", l.locale)
        var todayIndex = -1
        for (d in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(d)
            val t = prefs.times(date)
            val cells = arrayOf(date.format(dayFmt)) + Array(PrayerCalc.COUNT) { f.format(Date(t[it])) }
            val isToday = date == today
            if (isToday) todayIndex = d - 1
            val r = row(cells, header = false, today = isToday)
            if (!isToday && d % 2 == 0) r.setBackgroundColor(0x14FFFFFF)
            table.addView(r, lp())
        }
        scroll.post {
            if (todayIndex > 2) scroll.scrollTo(0, table.getChildAt(todayIndex - 2).top) else scroll.scrollTo(0, 0)
        }
    }
}
