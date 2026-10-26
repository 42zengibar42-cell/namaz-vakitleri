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
        val place = if (prefs.diyanetId > 0) DiyanetDb.byId(this, prefs.diyanetId) else null
        loc.addView(tv("📍 " + (place?.pretty(l) ?: prefs.city.name), 17f, C.TEXT, true), lp())
        if (place != null) loc.addView(tv(place.sub(l), 13f, C.MUTED), lp())
        loc.addView(tv(sourceText(), 13f, if (sourceOk()) C.GOLD else 0xFFE5A04B.toInt()), lp().apply { topMargin = dp(6) })
        loc.addView(button(l.t("Diyanet ilçesi ara ve seç", "Ort suchen (Diyanet)")) { chooseCity() }, lp().apply { topMargin = dp(10) })
        if (prefs.diyanetId > 0) loc.addView(ghostButton("⟳ " + l.t("Diyanet'ten şimdi güncelle", "Jetzt von Diyanet laden")) {
            download(prefs.diyanetId)
        }, lp().apply { topMargin = dp(8) })
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

        val rel = section(root, l.t("DİNİ GÜNLER", "RELIGIÖSE TAGE"))
        switchRow(rel, l.t("Kandil ve bayram bildirimleri", "Hinweise zu heiligen Nächten und Festen"), prefs.religiousNotify) {
            prefs.religiousNotify = it; Scheduler.scheduleReligious(this)
        }
        rel.addView(tv(l.t("Bir gün önce saat 19:00'da ve o gün saat 09:00'da hatırlatır.",
            "Erinnert am Vortag um 19:00 und am Tag selbst um 09:00."), 12f, C.MUTED), lp().apply { topMargin = dp(4) })

        // Sessiz mod
        val sil = section(root, l.t("NAMAZDA SESSİZ MOD", "STUMM BEIM GEBET"))
        switchRow(sil, l.t("Vakit girince telefonu sessize al", "Handy zur Gebetszeit stumm schalten"), Silent.enabled(this)) {
            Silent.setEnabled(this, it); Silent.schedule(this); rebuild()
        }
        if (Silent.enabled(this)) {
            val mr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val vib = !Silent.full(this)
            mr.addView((if (vib) button(l.t("📳 Titreşim", "📳 Vibration")) {} else ghostButton(l.t("📳 Titreşim", "📳 Vibration")) {
                Silent.setFull(this, false); rebuild() }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            mr.addView((if (!vib) button(l.t("🔕 Tam sessiz", "🔕 Lautlos")) {} else ghostButton(l.t("🔕 Tam sessiz", "🔕 Lautlos")) {
                Silent.setFull(this, true)
                if (!Silent.hasPolicyAccess(this)) {
                    Toast.makeText(this, l.t("Listeden 'Namaz Vakitleri'ni bulup izin verin", "Bitte 'Namaz Vakitleri' erlauben"), Toast.LENGTH_LONG).show()
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (e: Exception) { }
                }
                rebuild() }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            sil.addView(mr, lp().apply { topMargin = dp(8) })
            if (Silent.full(this) && !Silent.hasPolicyAccess(this)) {
                sil.addView(tv(l.t("⚠ Tam sessiz için 'Rahatsız Etmeyin' izni gerekli, izin verilene kadar titreşime alınır.",
                    "⚠ Für 'Lautlos' wird die 'Nicht stören'-Berechtigung benötigt, bis dahin Vibration."), 12f, 0xFFE5A04B.toInt()), lp().apply { topMargin = dp(6) })
                sil.addView(ghostButton(l.t("İzin ver", "Erlauben")) {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (e: Exception) { }
                }, lp().apply { topMargin = dp(6) })
            }
            val mins = intArrayOf(10, 15, 20, 25, 30, 40, 45, 60)
            sil.addView(ghostButton("⏱ " + l.t("Süre: ${Silent.minutes(this)} dk", "Dauer: ${Silent.minutes(this)} Min.")) {
                AlertDialog.Builder(this).setItems(mins.map { l.t("$it dakika", "$it Minuten") }.toTypedArray()) { _, w ->
                    Silent.setMinutes(this, mins[w]); rebuild() }.show()
            }, lp().apply { topMargin = dp(8) })
            sil.addView(ghostButton("🕌 " + l.t("Cuma öğle: ${Silent.fridayMinutes(this)} dk", "Freitagsgebet: ${Silent.fridayMinutes(this)} Min.")) {
                AlertDialog.Builder(this).setItems(mins.map { l.t("$it dakika", "$it Minuten") }.toTypedArray()) { _, w ->
                    Silent.setFridayMinutes(this, mins[w]); rebuild() }.show()
            }, lp().apply { topMargin = dp(8) })
            for (i in intArrayOf(0, 2, 3, 4, 5)) switchRow(sil, l.names[i], Silent.prayer(this, i)) {
                Silent.setPrayer(this, i, it); Silent.schedule(this)
            }
            sil.addView(tv(l.t("Süre bitince ses kendiliğinden eski haline döner. Bildirimdeki 'Sesi şimdi aç' ile erken açabilirsiniz. Ezan sesi sessizde de çalar.",
                "Danach wird der Ton automatisch wieder eingeschaltet. Der Adhan ertönt trotzdem."), 12f, C.MUTED), lp().apply { topMargin = dp(6) })
        }

        // Ezan
        val ez = section(root, l.t("EZAN SESİ", "ADHAN-TON"))
        switchRow(ez, l.t("Vakit girince ezan çal", "Adhan zur Gebetszeit abspielen"), prefs.adhan) { prefs.adhan = it; rebuild() }
        if (prefs.adhan) {
            ez.addView(tv(l.t("Her vakit için ayrı ezan seçebilirsiniz.", "Für jede Gebetszeit kann ein eigener Adhan gewählt werden."),
                12f, C.MUTED), lp().apply { topMargin = dp(4) })
            for (i in intArrayOf(0, 2, 3, 4, 5)) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundBg(C.BG, dp(12).toFloat())
                    setPadding(dp(12), dp(8), dp(12), dp(10))
                }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(tv(l.names[i], 17f, C.TEXT, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(SwitchCompat(this).apply {
                    isChecked = prefs.adhanOn(i)
                    setOnCheckedChangeListener { _, v -> prefs.setAdhanOn(i, v); rebuild() }
                })
                box.addView(top, lp())
                if (prefs.adhanOn(i)) {
                    box.addView(tv("🎵 " + (prefs.adhanName(i) ?: l.t("Uygulamadaki ezan", "Eingebauter Adhan")), 14f, C.MUTED).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, lp().apply { topMargin = dp(2) })
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val small = { v: android.widget.TextView -> v.textSize = 13f; v.setPadding(dp(4), dp(9), dp(4), dp(9)); v }
                    r.addView(small(ghostButton("▶ " + l.t("Dinle", "Hören")) {
                        startForegroundService(Intent(this, AdhanService::class.java).putExtra("idx", -1).putExtra("snd", i))
                    }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
                    r.addView(small(ghostButton("■") {
                        startService(Intent(this, AdhanService::class.java).setAction("stop"))
                    }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                    r.addView(small(button("📁 " + l.t("Değiştir", "Ändern")) {
                        val it = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                        try { startActivityForResult(it, pickAudio + 100 + i) } catch (e: Exception) { }
                    }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
                    box.addView(r, lp().apply { topMargin = dp(8) })
                    if (prefs.adhanUri(i) != null) {
                        box.addView(tv("↺ " + l.t("Uygulamadaki ezana dön", "Eingebauten Adhan nutzen"), 13f, C.GOLD).apply {
                            setPadding(0, dp(8), 0, 0)
                            setOnClickListener { prefs.setAdhanFile(i, null, null); rebuild() }
                        }, lp())
                    }
                } else {
                    box.addView(tv(l.t("Ezan yok, sadece bildirim", "Kein Adhan, nur Hinweis"), 13f, C.MUTED), lp())
                }
                ez.addView(box, lp().apply { topMargin = dp(10) })
            }
        }
        ez.addView(tv(l.t(
            "Güneş vaktinde ezan okunmaz, sadece bildirim gelir. Ezan kapalıysa normal bildirim sesi çalar. Telefonun 'Alarm' sesi kısıksa ezan duyulmaz.",
            "Bei Sonnenaufgang gibt es keinen Adhan, nur einen Hinweis. Ist der Adhan aus, ertönt der normale Hinweiston. Die Lautstärke folgt der Wecker-Lautstärke."
        ), 12f, C.MUTED), lp().apply { topMargin = dp(10) })

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
        run {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(tv(l.t("Bayram namazı", "Festgebet"), 16f), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = tv(fmtOff(prefs.bayramOffset), 16f, C.GOLD, true).apply { gravity = Gravity.CENTER; minWidth = dp(64) }
            val minus = tv(" − ", 22f, C.GOLD, true).apply {
                background = roundBg(C.HI, dp(8).toFloat()); setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { prefs.bayramOffset = (prefs.bayramOffset - 1).coerceAtLeast(-60); v.text = fmtOff(prefs.bayramOffset); Scheduler.scheduleReligious(this@SettingsActivity) }
            }
            val plus = tv(" + ", 22f, C.GOLD, true).apply {
                background = roundBg(C.HI, dp(8).toFloat()); setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { prefs.bayramOffset = (prefs.bayramOffset + 1).coerceAtMost(60); v.text = fmtOff(prefs.bayramOffset); Scheduler.scheduleReligious(this@SettingsActivity) }
            }
            r.addView(minus); r.addView(v); r.addView(plus)
            adj.addView(r, lp())
            adj.addView(tv(l.t("Almanya'da camiler bayram namazını bazen hesaplanan saatten daha geç kıldırır. Caminizin ilan ettiği saate göre ayarlayın.",
                "Moscheen in Deutschland beten das Festgebet oft später. Bitte an die Zeit Ihrer Moschee anpassen."), 12f, C.MUTED), lp().apply { topMargin = dp(4) })
        }

        root.addView(tv(l.t(
            "Vakitler seçtiğiniz ilçenin Diyanet takviminden alınır ve telefonda saklanır, internet sadece güncellemede gerekir. Diyanet'e ulaşılamazsa vakitler Diyanet yöntemiyle telefonda hesaplanır (yazın 1-4 dk fark olabilir).",
            "Die Zeiten kommen aus dem Diyanet-Kalender Ihres Ortes und werden gespeichert. Ist Diyanet nicht erreichbar, rechnet das Handy selbst (im Sommer 1-4 Min. Abweichung möglich)."
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

    private fun sourceOk(): Boolean = prefs.fromDiyanet(java.time.LocalDate.now(prefs.zone))

    private fun sourceText(): String {
        val id = prefs.diyanetId
        if (id <= 0) return "⚠ " + l.t("Diyanet ilçesi seçilmedi · vakitler hesaplanıyor", "Kein Diyanet-Ort gewählt · Zeiten werden berechnet")
        if (!sourceOk()) return "⚠ " + l.t("Diyanet verisi henüz yok · şimdilik hesaplanıyor", "Noch keine Diyanet-Daten · vorläufig berechnet")
        val df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val last = DiyanetTimes.lastDate(this, id)?.format(df) ?: "-"
        val at = java.time.Instant.ofEpochMilli(DiyanetTimes.fetchedAt(this, id)).atZone(prefs.zone).toLocalDate().format(df)
        return "✅ " + l.t("Diyanet takvimi · $last tarihine kadar · güncelleme: $at", "Diyanet-Kalender · bis $last · aktualisiert: $at")
    }

    /** Diyanet'ten indir, sonucu göster. */
    private fun download(id: Int, onFail: (() -> Unit)? = null) {
        val dlg = AlertDialog.Builder(this).setMessage(l.t("Diyanet'ten vakitler indiriliyor…", "Zeiten werden von Diyanet geladen…"))
            .setCancelable(false).show()
        Thread {
            val r = DiyanetTimes.fetch(this, id)
            Scheduler.scheduleNext(this)
            runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) { }
                if (r.error == null) {
                    Toast.makeText(this, l.t("✅ ${r.days} günlük Diyanet takvimi indirildi", "✅ Diyanet-Kalender für ${r.days} Tage geladen"), Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setMessage(l.t(
                        "Diyanet'e şu an ulaşılamadı. İnternet bağlantınızı kontrol edin. O zamana kadar vakitler telefonda hesaplanıyor.",
                        "Diyanet ist gerade nicht erreichbar. Bis dahin werden die Zeiten berechnet."
                    ) + "\n\n(" + r.error + ")").setPositiveButton("OK", null).show()
                    onFail?.invoke()
                }
                rebuild()
            }
        }.start()
    }

    private fun selectPlace(id: Int) {
        Thread {
            val pl = DiyanetDb.byId(this, id) ?: return@Thread
            DiyanetSelect.apply(this, pl)
            runOnUiThread { rebuild(); download(id) }
        }.start()
    }

    private fun setCity(c: City) {
        prefs.city = c
        Scheduler.scheduleNext(this)
        rebuild()
    }

    private fun chooseCity() {
        startActivityForResult(Intent(this, CityPickerActivity::class.java), 50)
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
            var names = ArrayList<String>()
            var cc: String? = null
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(this, Locale.GERMANY).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                if (a != null) {
                    cc = a.countryCode
                    listOfNotNull(a.locality, a.subAdminArea, a.subLocality).forEach { if (it.isNotBlank()) names.add(it) }
                }
            } catch (e: Exception) { }
            // Diyanet listesinde aynı isimli ilçeyi bul; yoksa en yakın bilinen şehre göre ara
            val all = DiyanetDb.load(this)
            var found: DiyanetDb.Place? = null
            for (n in names) {
                val k = CityDb.norm(n.split('(', ',')[0])
                found = all.firstOrNull { it.key == k && (cc == null || it.cc == cc) } ?: continue
                break
            }
            if (found == null) {
                // Yakındaki şehirlerden Diyanet listesinde olanı seç (en yakın)
                val near = CityDb.load(this).filter { cc == null || it.cc == cc }
                    .sortedBy { (it.lat - loc.latitude) * (it.lat - loc.latitude) + (it.lng - loc.longitude) * (it.lng - loc.longitude) }
                    .take(60)
                for (r in near) { found = all.firstOrNull { it.key == r.key && it.cc == r.cc }; if (found != null) break }
            }
            val f = found
            runOnUiThread {
                if (f != null) {
                    Toast.makeText(this, "📍 " + f.pretty(l), Toast.LENGTH_SHORT).show()
                    selectPlace(f.id)
                } else {
                    startActivityForResult(Intent(this, CityPickerActivity::class.java).putExtra("q", names.firstOrNull() ?: ""), 50)
                }
            }
        }.start()
    }

    @Deprecated("")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 50) {
            val id = data?.getIntExtra("id", 0) ?: 0
            if (resultCode == RESULT_OK && id > 0) selectPlace(id)
            return
        }
        val idx = requestCode - pickAudio - 100
        if (idx !in 0 until PrayerCalc.COUNT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        var name = "ezan.mp3"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (e: Exception) { }
        prefs.setAdhanFile(idx, uri.toString(), name)
        prefs.setAdhanOn(idx, true)
        rebuild()
        val others = intArrayOf(0, 2, 3, 4, 5).filter { it != idx }
        AlertDialog.Builder(this)
            .setMessage(l.t("\"$name\" ${l.names[idx]} için seçildi. Diğer vakitlerde de kullanılsın mı?",
                "\"$name\" für ${l.names[idx]} gewählt. Auch für die anderen Zeiten nutzen?"))
            .setPositiveButton(l.t("Tüm vakitlere", "Für alle")) { _, _ ->
                others.forEach { prefs.setAdhanFile(it, uri.toString(), name) }; rebuild()
            }
            .setNegativeButton(l.t("Sadece ${l.names[idx]}", "Nur ${l.names[idx]}"), null)
            .show()
    }
}
