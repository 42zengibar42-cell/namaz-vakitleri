package com.kck.namaz

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ImsakiyeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); val l = L(this)
        val today = LocalDate.now(prefs.zone)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG)
            setPadding(dp(8), dp(18), dp(8), dp(8))
        }
        val ram = ReligiousDays.ramadanFor(today)
        if (ram == null) {
            root.addView(tv(l.t("Ramazan bilgisi bulunamadı.", "Keine Ramadan-Daten."), 16f))
            setContentView(root); return
        }
        val (start, end) = ram
        root.addView(tv(l.t("Ramazan İmsakiyesi ${start.year}", "Ramadan-Kalender ${start.year}"), 22f, C.TEXT, true).apply {
            gravity = Gravity.CENTER
        }, lp())
        root.addView(tv(prefs.city.name, 13f, C.MUTED).apply { gravity = Gravity.CENTER }, lp())

        fun row(cells: Array<String>, header: Boolean, hl: Boolean, special: Boolean): LinearLayout {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(9), dp(2), dp(9))
                if (hl) background = roundBg(C.HI, dp(8).toFloat())
            }
            val w = floatArrayOf(0.6f, 1.5f, 1f, 1f)
            cells.forEachIndexed { i, s ->
                val c = when { header -> C.GOLD; hl -> C.GOLD; special && i == 1 -> C.GOLD; else -> C.TEXT }
                val t = tv(s, if (header) 12f else 14f, c, header || hl || i >= 2)
                t.gravity = Gravity.CENTER
                r.addView(t, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, w[i]))
            }
            return r
        }

        root.addView(row(arrayOf(l.t("Gün", "Tag"), l.t("Tarih", "Datum"), l.t("İmsak\n(Sahur)", "Fadschr\n(Sahur)"),
            l.t("Akşam\n(İftar)", "Maghrib\n(Iftar)")), true, false, false), lp().apply { topMargin = dp(10) })

        val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val f = SimpleDateFormat("HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone(prefs.zone) }
        val df = DateTimeFormatter.ofPattern("d MMM EE", l.locale)
        val kadir = ReligiousDays.all.firstOrNull { it.kind == Kind.KADIR && !it.date.isBefore(start) && !it.date.isAfter(end) }?.date
        var d = start; var n = 1; var todayRow = -1
        while (!d.isAfter(end)) {
            val t = prefs.times(d)
            var label = d.format(df)
            if (d == kadir) label += " 🌙"
            val isToday = d == today
            if (isToday) todayRow = n - 1
            val r = row(arrayOf("$n", label, f.format(Date(t[0])), f.format(Date(t[4]))), false, isToday, d == kadir)
            if (!isToday && n % 2 == 0) r.setBackgroundColor(0x14FFFFFF)
            table.addView(r, lp())
            d = d.plusDays(1); n++
        }
        val sv = ScrollView(this).apply { addView(table) }
        root.addView(sv, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tv(l.t("🌙 Kadir Gecesi · İmsak vaktinde sahur biter, akşam vaktinde iftar açılır.",
            "🌙 Kadir-Nacht · Zu Fadschr endet der Sahur, zu Maghrib ist Iftar."), 12f, C.MUTED).apply {
            gravity = Gravity.CENTER
        }, lp().apply { topMargin = dp(6) })
        setContentView(root)
        sv.post { if (todayRow > 2) sv.scrollTo(0, table.getChildAt(todayRow - 2).top) }
    }
}
