package com.kck.namaz

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class DaysActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); val l = L(this)
        val today = LocalDate.now(prefs.zone)
        val fmt = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", l.locale)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        root.addView(tv(l.t("Dini Günler", "Religiöse Tage"), 24f, C.TEXT, true))
        root.addView(tv(l.t("Diyanet takvimine göre", "Nach dem Diyanet-Kalender"), 13f, C.MUTED), lp())

        ReligiousDays.ramadanFor(today)?.let { (start, _) ->
            root.addView(button("🌙 " + l.t("Ramazan İmsakiyesi ${start.year}", "Ramadan-Kalender ${start.year}")) {
                startActivity(Intent(this, ImsakiyeActivity::class.java))
            }, lp().apply { topMargin = dp(14) })
        }

        val list = ReligiousDays.all.filter { !it.lastDay.isBefore(today.minusMonths(2)) }
        val nextIdx = list.indexOfFirst { !it.lastDay.isBefore(today) }
        var year = -1
        var scrollTarget: View? = null
        list.forEachIndexed { i, d ->
            if (d.date.year != year) {
                year = d.date.year
                root.addView(tv("$year", 15f, C.GOLD, true), lp().apply { topMargin = dp(20); bottomMargin = dp(2) })
            }
            val past = d.lastDay.isBefore(today)
            val isNow = !today.isBefore(d.date) && !today.isAfter(d.lastDay)
            val box = card().apply {
                if (i == nextIdx) background = roundBg(C.HI, dp(16).toFloat(), C.GOLD)
                alpha = if (past) 0.45f else 1f
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(tv(ReligiousDays.emoji(d.kind) + "  " + ReligiousDays.name(l, d.kind), 17f, C.TEXT, true),
                lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val left = ChronoUnit.DAYS.between(today, d.date)
            val badge = when {
                isNow -> l.t("Bugün", "Heute")
                left == 1L -> l.t("Yarın", "Morgen")
                left > 1 -> l.t("$left gün", "in $left Tagen")
                else -> ""
            }
            if (badge.isNotEmpty()) top.addView(tv(badge, 13f, C.GOLD, true))
            box.addView(top, lp())
            var dateText = d.date.format(fmt)
            if (d.kind.days > 1) dateText += " – " + d.lastDay.format(DateTimeFormatter.ofPattern("d MMMM", l.locale)) +
                l.t(" (${d.kind.days} gün)", " (${d.kind.days} Tage)")
            box.addView(tv(dateText, 14f, C.TEXT), lp().apply { topMargin = dp(4) })
            var sub = ReligiousDays.hijriText(l, d)
            if (d.kind.night) sub += l.t(" · o günün akşamı", " · am Abend")
            if (d.kind == Kind.RAMAZAN) sub += l.t(" · ilk sahur bir önceki gece", " · erster Sahur in der Nacht davor")
            if (!d.official) sub += l.t(" · tahmini", " · voraussichtlich")
            box.addView(tv(sub, 12f, C.MUTED), lp().apply { topMargin = dp(2) })
            if (ReligiousDays.isBayram(d.kind)) {
                val hmF = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).apply { timeZone = java.util.TimeZone.getTimeZone(prefs.zone) }
                box.addView(tv("🕌 " + l.t("Bayram namazı: ", "Festgebet: ") + hmF.format(java.util.Date(prefs.bayram(d.date))) +
                    "  (${prefs.city.name})", 15f, C.GOLD, true), lp().apply { topMargin = dp(6) })
            }
            root.addView(box, lp().apply { topMargin = dp(8) })
            if (i == maxOf(0, nextIdx - 1)) scrollTarget = box
        }

        root.addView(tv(l.t(
            "2026–2027 tarihleri Diyanet İşleri Başkanlığı'nın takviminden alınmıştır. Sonraki yıllar hesaplanmış tahmindir, 1 gün fark olabilir.",
            "Die Daten 2026–2027 stammen aus dem Diyanet-Kalender. Spätere Jahre sind berechnet und können um 1 Tag abweichen."
        ), 12f, C.MUTED), lp().apply { topMargin = dp(18) })

        val sv = ScrollView(this).apply { setBackgroundColor(C.BG) }
        sv.addView(root)
        setContentView(sv)
        sv.post { scrollTarget?.let { sv.scrollTo(0, it.top - dp(40)) } }
    }
}
