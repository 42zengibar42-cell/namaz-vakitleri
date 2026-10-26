package com.kck.namaz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var l: L
    private val handler = Handler(Looper.getMainLooper())
    private var dayShift = 0L

    private lateinit var countLabel: TextView
    private lateinit var countText: TextView
    private lateinit var countTime: TextView
    private lateinit var dayLabel: TextView
    private lateinit var hijriLabel: TextView
    private lateinit var dayInfo: TextView
    private val rowViews = ArrayList<LinearLayout>()
    private val timeViews = ArrayList<TextView>()
    private val bellViews = ArrayList<TextView>()
    private val nameViews = ArrayList<TextView>()

    private val tick = object : Runnable {
        override fun run() { updateCountdown(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this); l = L(this)
        build()
        Scheduler.channels(this)
        Scheduler.scheduleNext(this)
        handler.removeCallbacks(tick); handler.post(tick)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).apply {
        timeZone = TimeZone.getTimeZone(prefs.zone)
    }.format(Date(ms))

    private fun build() {
        rowViews.clear(); timeViews.clear(); bellViews.clear(); nameViews.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // Şehir
        val city = tv("📍 " + prefs.city.name, 22f, C.TEXT, true).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(city)
        hijriLabel = tv("", 14f, C.MUTED)
        root.addView(hijriLabel, lp().apply { topMargin = dp(2) })
        dayInfo = tv("", 15f, C.GOLD, true).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, DaysActivity::class.java)) }
        }
        root.addView(dayInfo, lp().apply { topMargin = dp(4) })

        // Geri sayım kartı
        val cc = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        countLabel = tv("", 16f, C.MUTED).apply { gravity = Gravity.CENTER }
        countText = tv("--:--:--", 46f, C.GOLD, true).apply { gravity = Gravity.CENTER }
        countTime = tv("", 15f, C.TEXT).apply { gravity = Gravity.CENTER }
        cc.addView(countLabel, lp()); cc.addView(countText, lp()); cc.addView(countTime, lp())
        root.addView(cc, lp().apply { topMargin = dp(16) })

        // Gün seçici
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = tv("‹", 34f, C.GOLD, true).apply { setPadding(dp(14), 0, dp(14), 0); setOnClickListener { dayShift--; fillDay() } }
        val next = tv("›", 34f, C.GOLD, true).apply { setPadding(dp(14), 0, dp(14), 0); setOnClickListener { dayShift++; fillDay() } }
        dayLabel = tv("", 16f, C.TEXT, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { dayShift = 0; fillDay() }
        }
        nav.addView(prev); nav.addView(dayLabel, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); nav.addView(next)
        root.addView(nav, lp().apply { topMargin = dp(14) })

        // Vakit satırları
        val list = card().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        for (i in 0 until PrayerCalc.COUNT) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(13), dp(8), dp(13))
            }
            val name = tv(l.names[i], 18f, C.TEXT)
            val time = tv("--:--", 22f, C.TEXT, true)
            val bell = tv("", 20f, C.GOLD).apply {
                setPadding(dp(14), 0, dp(6), 0)
                setOnClickListener {
                    prefs.setNotify(i, !prefs.notify(i)); updateBell(i)
                    Scheduler.scheduleNext(this@MainActivity)
                }
            }
            row.addView(name, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(time); row.addView(bell)
            list.addView(row, lp())
            rowViews.add(row); timeViews.add(time); bellViews.add(bell); nameViews.add(name)
            updateBell(i)
        }
        root.addView(list, lp().apply { topMargin = dp(6) })

        // Butonlar
        val b1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b1.addView(button("🧭 " + l.t("Kıble", "Qibla")) { startActivity(Intent(this, QiblaActivity::class.java)) },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        b1.addView(button("📅 " + l.t("Aylık", "Monat")) { startActivity(Intent(this, MonthActivity::class.java)) },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        b1.addView(button("⚙ " + l.t("Ayarlar", "Einstellungen")) { startActivity(Intent(this, SettingsActivity::class.java)) },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(b1, lp().apply { topMargin = dp(16) })
        val b2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b2.addView(ghostButton("🕌 " + l.t("Dini Günler", "Religiöse Tage")) { startActivity(Intent(this, DaysActivity::class.java)) },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        val ram = ReligiousDays.ramadanFor(today())
        if (ram != null && java.time.temporal.ChronoUnit.DAYS.between(today(), ram.first) <= 30) {
            b2.addView(ghostButton("🌙 " + l.t("İmsakiye", "Ramadan-Kalender")) { startActivity(Intent(this, ImsakiyeActivity::class.java)) },
                lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        }
        root.addView(b2, lp().apply { topMargin = dp(10) })

        root.addView(tv(l.t("Hesaplama: Diyanet yöntemi", "Berechnung: Diyanet-Methode"), 12f, C.MUTED).apply {
            gravity = Gravity.CENTER
        }, lp().apply { topMargin = dp(14) })

        val sv = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true }
        sv.addView(root)
        setContentView(sv)
        fillDay()
    }

    private fun updateBell(i: Int) {
        bellViews[i].text = if (prefs.notify(i)) "🔔" else "🔕"
        bellViews[i].alpha = if (prefs.notify(i)) 1f else 0.5f
    }

    private fun today(): LocalDate = LocalDate.now(prefs.zone)

    private fun fillDay() {
        val date = today().plusDays(dayShift)
        val times = prefs.times(date)
        for (i in 0 until PrayerCalc.COUNT) timeViews[i].text = hm(times[i])
        val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", l.locale)
        val ds = date.format(fmt)
        dayLabel.text = when (dayShift) {
            0L -> l.t("Bugün", "Heute") + " · " + ds
            1L -> l.t("Yarın", "Morgen") + " · " + ds
            -1L -> l.t("Dün", "Gestern") + " · " + ds
            else -> ds
        }
        hijriLabel.text = Hijri.text(l, date)
        dayInfo.text = dayInfoText(date)
        dayInfo.visibility = if (dayInfo.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        updateCountdown()
    }

    private fun updateCountdown() {
        if (!::countText.isInitialized) return
        val now = System.currentTimeMillis()
        val t0 = prefs.times(today())
        var nextIdx = -1; var nextTime = 0L
        for (i in 0 until PrayerCalc.COUNT) if (t0[i] > now) { nextIdx = i; nextTime = t0[i]; break }
        if (nextIdx == -1) { nextIdx = 0; nextTime = prefs.times(today().plusDays(1))[0] }
        var cur = -1
        for (i in 0 until PrayerCalc.COUNT) if (t0[i] <= now) cur = i

        val left = (nextTime - now) / 1000
        countText.text = String.format(Locale.US, "%02d:%02d:%02d", left / 3600, (left / 60) % 60, left % 60)
        val n = l.names[nextIdx]
        val imsakDay = if (t0[0] > now) today() else today().plusDays(1)
        countLabel.text = when {
            nextIdx == 0 && ReligiousDays.inRamadan(imsakDay) -> l.t("🌙 Sahur'un bitmesine (imsak) kalan", "🌙 Verbleibend bis Sahur-Ende")
            nextIdx == 4 && ReligiousDays.inRamadan(today()) -> l.t("🌙 İftara kalan", "🌙 Verbleibend bis Iftar")
            else -> l.t("$n vaktine kalan", "Verbleibend bis $n")
        }
        countTime.text = "$n  ${hm(nextTime)}"

        for (i in 0 until PrayerCalc.COUNT) {
            val active = dayShift == 0L && i == cur
            rowViews[i].background = if (active) roundBg(C.HI, dp(12).toFloat()) else null
            val c = if (active) C.GOLD else C.TEXT
            nameViews[i].setTextColor(c); timeViews[i].setTextColor(c)
        }
        // Gün değiştiyse listeyi yenile
        if (dayShift == 0L && timeViews[0].text != hm(t0[0])) fillDay()
    }

    private fun dayInfoText(date: LocalDate): String {
        val parts = ArrayList<String>()
        val ram = ReligiousDays.ramadanFor(date)
        if (ram != null && !date.isBefore(ram.first)) {
            val n = java.time.temporal.ChronoUnit.DAYS.between(ram.first, date) + 1
            parts.add("🌙 " + l.t("Ramazan'ın $n. günü", "$n. Tag des Ramadan"))
        }
        for (d in ReligiousDays.onDate(date)) {
            if (d.kind == Kind.RAMAZAN) continue
            parts.add(ReligiousDays.emoji(d.kind) + " " + ReligiousDays.name(l, d.kind) +
                if (d.kind.night) l.t(" (bu akşam)", " (heute Abend)") else "")
        }
        if (parts.isEmpty()) {
            ReligiousDays.onDate(date.plusDays(1)).firstOrNull()?.let {
                parts.add(l.t("Yarın: ", "Morgen: ") + ReligiousDays.name(l, it.kind))
            }
        }
        return parts.joinToString("  ·  ")
    }
}
