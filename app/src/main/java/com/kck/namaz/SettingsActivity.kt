package com.kck.namaz

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.time.ZoneId
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var l: L
    private val pickAudio = 11
    private val locReq = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun section(root: LinearLayout, title: String): LinearLayout {
        root.addView(tv(title, 14f, C.GOLD, true), lp().apply { topMargin = dp(18); bottomMargin = dp(6) })
        val c = card()
        root.addView(c, lp())
        return c
    }

    private fun switchRow(parent: LinearLayout, text: String, checked: Boolean, on: (Boolean) -> Unit) {
        val s = SwitchCompat(this).apply {
            this.text = text; textSize = 16f; setTextColor(C.TEXT); isChecked = checked
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> on(v) }
        }
        parent.addView(s, lp())
    }

    private fun build() {
        prefs = Prefs(this); l = L(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        root.addView(tv(l.t("Ayarlar", "Einstellungen"), 24f, C.TEXT, true))

        // Dil
        val lang = section(root, l.t("DİL", "SPRACHE"))
        val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val trB = (if (l.tr) button("Türkçe") {} else ghostButton("Türkçe") { prefs.lang = "tr"; rebuild() })
        val deB = (if (!l.tr) button("Deutsch") {} else ghostButton("Deutsch") { prefs.lang = "de"; rebuild() })
        lr.addView(trB, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        lr.addView(deB, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        lang.addView(lr, lp())

        // Konum
        val loc = section(root, l.t("KONUM", "STANDORT"))
        loc.addView(tv("📍 " + prefs.city.name, 17f, C.TEXT, true), lp())
        loc.addView(tv(String.format(Locale.US, "%.4f, %.4f", prefs.city.lat, prefs.city.lng), 12f, C.MUTED), lp())
        loc.addView(button(l.t("Şehir seç", "Stadt wählen")) { chooseCity() }, lp().apply { topMargin = dp(10) })
        loc.addView(ghostButton(l.t("Konumumu kullan (GPS)", "Meinen Standort nutzen (GPS)")) { useLocation() }, lp().apply { topMargin = dp(8) })

        // Bildirimler
        val nt = section(root, l.t("BİLDİRİMLER", "BENACHRICHTIGUNGEN"))
        for (i in 0 until PrayerCalc.COUNT) switchRow(nt, l.names[i], prefs.notify(i)) {
            prefs.setNotify(i, it); Scheduler.scheduleNext(this)
        }
        val beforeTxt = { v: Int -> if (v == 0) l.t("Vakit girince", "Zur Gebetszeit") else l.t("$v dk önce de hatırlat", "Auch $v Min. vorher erinnern") }
        nt.addView(ghostButton("⏰ " + beforeTxt(prefs.before)) {
            val opts = intArrayOf(0, 5, 10, 15, 20, 30, 45)
            AlertDialog.Builder(this).setTitle(l.t("Hatırlatma", "Erinnerung"))
                .setItems(opts.map { beforeTxt(it) }.toTypedArray()) { _, w ->
                    prefs.before = opts[w]; Scheduler.scheduleNext(this); rebuild()
                }.show()
        }, lp().apply { topMargin = dp(10) })
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                nt.addView(button(l.t("Tam zamanlı alarm iznini aç", "Exakte Alarme erlauben")) {
                    try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
                }, lp().apply { topMargin = dp(8) })
            }
        }
        nt.addView(ghostButton(l.t("Pil tasarrufundan çıkar (önerilir)", "Akku-Optimierung ausschalten (empfohlen)")) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
            }
        }, lp().apply { topMargin = dp(8) })

        // Ezan
        val ez = section(root, l.t("EZAN SESİ", "ADHAN-TON"))
        switchRow(ez, l.t("Vakit girince ezan çal", "Adhan zur Gebetszeit abspielen"), prefs.adhan) { prefs.adhan = it }
        ez.addView(tv(l.t("Ses: ", "Ton: ") + (prefs.adhanName ?: l.t("Uygulamadaki ezan", "Eingebauter Adhan")), 14f, C.MUTED), lp().apply { topMargin = dp(4) })
        ez.addView(ghostButton(l.t("Başka ezan dosyası seç (mp3)", "Andere Adhan-Datei wählen (mp3)")) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
            try { startActivityForResult(i, pickAudio) } catch (e: Exception) { }
        }, lp().apply { topMargin = dp(10) })
        if (prefs.adhanUri != null) {
            ez.addView(ghostButton(l.t("Uygulamadaki ezana dön", "Eingebauten Adhan nutzen")) {
                prefs.adhanUri = null; prefs.adhanName = null; rebuild()
            }, lp().apply { topMargin = dp(8) })
        }
        run {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(ghostButton("▶ " + l.t("Dene", "Testen")) {
                startForegroundService(Intent(this, AdhanService::class.java).putExtra("idx", -1))
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            r.addView(ghostButton("■ " + l.t("Durdur", "Stopp")) {
                startService(Intent(this, AdhanService::class.java).setAction("stop"))
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            ez.addView(r, lp().apply { topMargin = dp(8) })
        }
        ez.addView(tv(l.t(
            "Güneş vaktinde ezan okunmaz, sadece bildirim gelir. Ezan kapalıysa normal bildirim sesi çalar. Telefonun 'Alarm' sesi kısıksa ezan duyulmaz.",
            "Bei Sonnenaufgang gibt es keinen Adhan, nur einen Hinweis. Ist der Adhan aus, ertönt der normale Hinweiston. Die Lautstärke folgt der Wecker-Lautstärke."
        ), 12f, C.MUTED), lp().apply { topMargin = dp(8) })

        // Dakika düzeltme
        val adj = section(root, l.t("DAKİKA DÜZELTME", "MINUTEN-KORREKTUR"))
        adj.addView(tv(l.t("Camiinizin takvimiyle birebir aynı olsun diye her vakti dakika dakika kaydırabilirsiniz.",
            "Jede Zeit lässt sich minutengenau an den Kalender Ihrer Moschee anpassen."), 12f, C.MUTED), lp().apply { bottomMargin = dp(6) })
        for (i in 0 until PrayerCalc.COUNT) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(tv(l.names[i], 16f), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = tv(fmtOff(prefs.offset(i)), 16f, C.GOLD, true).apply { gravity = Gravity.CENTER; minWidth = dp(64) }
            val minus = tv(" − ", 22f, C.GOLD, true).apply {
                background = roundBg(C.HI, dp(8).toFloat()); setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { val n = (prefs.offset(i) - 1).coerceAtLeast(-30); prefs.setOffset(i, n); v.text = fmtOff(n); Scheduler.scheduleNext(this@SettingsActivity) }
            }
            val plus = tv(" + ", 22f, C.GOLD, true).apply {
                background = roundBg(C.HI, dp(8).toFloat()); setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { val n = (prefs.offset(i) + 1).coerceAtMost(30); prefs.setOffset(i, n); v.text = fmtOff(n); Scheduler.scheduleNext(this@SettingsActivity) }
            }
            r.addView(minus); r.addView(v); r.addView(plus)
            adj.addView(r, lp())
        }

        root.addView(tv(l.t(
            "Vakitler Diyanet yöntemiyle telefonda hesaplanır, internet gerekmez. Almanya'da yaz aylarında Diyanet takviminden 1-3 dk fark olabilir.",
            "Die Zeiten werden nach der Diyanet-Methode offline berechnet. In Deutschland kann es im Sommer 1-3 Min. Abweichung geben."
        ), 12f, C.MUTED), lp().apply { topMargin = dp(18) })

        val sv = ScrollView(this).apply { setBackgroundColor(C.BG) }
        sv.addView(root)
        setContentView(sv)
    }

    private fun fmtOff(v: Int) = when {
        v > 0 -> "+$v dk"
        v < 0 -> "$v dk"
        else -> "0"
    }.let { if (l.tr) it else it.replace("dk", "Min") }

    private fun rebuild() {
        val y = (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? ScrollView)?.scrollY ?: 0
        build()
        (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? ScrollView)?.post {
            (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? ScrollView)?.scrollTo(0, y)
        }
    }

    private fun setCity(c: City) {
        prefs.city = c
        Scheduler.scheduleNext(this)
        rebuild()
    }

    private fun chooseCity() {
        val names = Cities.list.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle(l.t("Şehir seç", "Stadt wählen"))
            .setItems(names) { _, w -> setCity(Cities.list[w]) }.show()
    }

    private fun useLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), locReq)
            return
        }
        fetchLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locReq && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) fetchLocation()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (e: Exception) { false }
        }
        if (providers.isEmpty()) {
            Toast.makeText(this, l.t("Konum kapalı. Lütfen telefonda konumu açın.", "Standort ist aus. Bitte einschalten."), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, l.t("Konum alınıyor…", "Standort wird ermittelt…"), Toast.LENGTH_SHORT).show()
        var done = false
        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (done) return
                done = true
                try { lm.removeUpdates(this) } catch (e: Exception) { }
                applyLocation(loc)
            }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        for (p in providers) try { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) } catch (e: Exception) { }
        handler.postDelayed({
            if (done) return@postDelayed
            done = true
            try { lm.removeUpdates(listener) } catch (e: Exception) { }
            val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }
            if (last != null) applyLocation(last)
            else Toast.makeText(this, l.t("Konum bulunamadı. Şehir listesinden seçin.", "Kein Standort gefunden. Bitte Stadt wählen."), Toast.LENGTH_LONG).show()
        }, 15000)
    }

    private fun applyLocation(loc: Location) {
        Thread {
            var name = l.t("Konumum", "Mein Standort")
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(this, l.locale).getFromLocation(loc.latitude, loc.longitude, 1)
                val n = a?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                if (!n.isNullOrBlank()) name = n
            } catch (e: Exception) { }
            val c = City(name, loc.latitude, loc.longitude, ZoneId.systemDefault().id)
            runOnUiThread {
                setCity(c)
                Toast.makeText(this, "📍 $name", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    @Deprecated("")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickAudio || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        var name = "ezan.mp3"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (e: Exception) { }
        prefs.adhanUri = uri.toString(); prefs.adhanName = name; prefs.adhan = true
        rebuild()
    }
}
