package com.kck.namaz

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.Normalizer
import java.util.Locale

/** Uygulama içindeki şehir veritabanı (GeoNames: Almanya/Türkiye 1000+ nüfus, dünya 15000+). */
object CityDb {
    class Row(val name: String, val region: String, val cc: String, val lat: Double, val lng: Double, val tz: String, val key: String)

    @Volatile private var rows: List<Row>? = null
    private var countries: Map<String, String> = emptyMap()

    fun norm(s: String): String {
        val t = s.replace('İ', 'i').replace('I', 'i').replace('ı', 'i').replace("ß", "ss")
            .lowercase(Locale.ROOT)
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace("ae", "a").replace("oe", "o").replace("ue", "u")
            .replace('-', ' ').replace(Regex("\\s+"), " ").trim()
    }

    fun country(cc: String) = countries[cc] ?: cc

    fun load(ctx: Context): List<Row> {
        rows?.let { return it }
        synchronized(this) {
            rows?.let { return it }
            val list = ArrayList<Row>(50000)
            ctx.assets.open("cities.tsv").bufferedReader(Charsets.UTF_8).use { r ->
                val tzs = r.readLine().split('\t')
                countries = r.readLine().split('\t').associate { val p = it.split('=', limit = 2); p[0] to p.getOrElse(1) { p[0] } }
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split('\t')
                    if (p.size < 6) continue
                    list.add(Row(p[0], p[1], p[2], p[3].toDouble(), p[4].toDouble(), tzs[p[5].toInt()], norm(p[0])))
                }
            }
            rows = list
            return list
        }
    }

    /** Almanya ve Türkiye önce, sonra nüfusa göre (dosya zaten nüfusa göre sıralı). */
    fun search(ctx: Context, q: String, limit: Int = 80): List<Row> {
        val all = load(ctx)
        val n = norm(q)
        if (n.isEmpty()) return all.filter { it.cc == "DE" && it.region == "Hessen" }.take(40) +
            all.filter { it.cc == "TR" }.take(30)
        fun rank(r: Row): Int {
            val home = if (r.cc == "DE" || r.cc == "TR") 0 else 1
            val m = when {
                r.key == n -> 0
                r.key.startsWith(n) -> 1
                r.key.split(' ').any { it.startsWith(n) } -> 2
                else -> 3
            }
            return m * 2 + home
        }
        val hits = ArrayList<Row>()
        for (r in all) if (r.key.contains(n)) hits.add(r)
        return hits.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }.take(limit)
    }
}

/** Diyanet ilçesini seçince koordinat/saat dilimi bulur ve kaydeder. */
object DiyanetSelect {
    fun coordsFor(ctx: Context, pl: DiyanetDb.Place): City? {
        val all = CityDb.load(ctx)
        val inCc = all.filter { it.cc == pl.cc }
        val st = CityDb.norm(pl.state)
        val base = CityDb.norm(pl.name.split('/', '(', ',')[0])
        fun pick(rows: List<CityDb.Row>): CityDb.Row? =
            rows.firstOrNull { st.isNotEmpty() && (CityDb.norm(it.region) == st || (it.region.isEmpty() && it.key == st)) } ?: rows.firstOrNull()
        val exact = inCc.filter { it.key == base }
        val r = pick(exact)
            ?: pick(inCc.filter { it.key.startsWith(base) || base.startsWith(it.key + " ") })
            ?: (if (base == "merkez" || base.isEmpty()) inCc.firstOrNull { it.key == st } else null)
            ?: inCc.firstOrNull { st.isNotEmpty() && it.key == st }
            ?: inCc.firstOrNull { st.isNotEmpty() && CityDb.norm(it.region) == st }
            ?: inCc.firstOrNull()
            ?: return null
        val name = if (r.key == base) r.name else pl.pretty(L(ctx))
        return City(name, r.lat, r.lng, r.tz)
    }

    /** Kaydeder; koordinat bulunamazsa eskisini tutar. Arka planda çağırın. */
    fun apply(ctx: Context, pl: DiyanetDb.Place) {
        val prefs = Prefs(ctx)
        val c = coordsFor(ctx, pl)
        prefs.city = c ?: prefs.city.copy(name = pl.pretty(L(ctx)))
        prefs.diyanetId = pl.id
    }
}

/** Diyanet yerlerinin koordinatları (şehir veritabanından eşleştirilir) ve en yakın yer araması. */
object DiyanetNear {
    class P(val place: DiyanetDb.Place, val lat: Double, val lng: Double)
    @Volatile private var list: List<P>? = null

    fun load(ctx: Context): List<P> {
        list?.let { return it }
        synchronized(this) {
            list?.let { return it }
            val byKey = HashMap<String, ArrayList<CityDb.Row>>()
            for (r in CityDb.load(ctx)) byKey.getOrPut(r.cc + "|" + r.key) { ArrayList() }.add(r)
            val out = ArrayList<P>()
            for (p in DiyanetDb.load(ctx)) {
                val base = CityDb.norm(p.name.split('/', '(', ',')[0])
                val rows = byKey[p.cc + "|" + base] ?: continue
                val st = CityDb.norm(p.state)
                val r = rows.firstOrNull { st.isNotEmpty() && CityDb.norm(it.region) == st } ?: rows.first()
                out.add(P(p, r.lat, r.lng))
            }
            list = out
            return out
        }
    }

    fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    fun nearest(ctx: Context, lat: Double, lng: Double, n: Int = 3): List<Pair<DiyanetDb.Place, Double>> =
        load(ctx).map { Pair(it.place, km(lat, lng, it.lat, it.lng)) }.sortedBy { it.second }.take(n)
}

class CityPickerActivity : AppCompatActivity() {
    private lateinit var l: L
    private lateinit var input: EditText
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private var searchSeq = 0

    /** Liste satırı: Diyanet yeri, Diyanet'te olmayan yer (şehir veritabanı / internet), başlık ya da internet araması. */
    private sealed class Row {
        class Dy(val p: DiyanetDb.Place) : Row()
        class Other(val name: String, val sub: String, val lat: Double, val lng: Double, val tz: String?) : Row()
        class Header(val text: String) : Row()
        object Online : Row()
    }
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        l = L(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG)
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }
        root.addView(tv(l.t("Yer seçimi", "Ort wählen"), 22f, C.TEXT, true))
        root.addView(tv(l.t("İlçe, şehir veya köy yazın. Diyanet listesindeki yerler camilerle aynı vakitleri verir.",
            "Ort, Stadt oder Dorf eingeben. Orte der Diyanet-Liste haben dieselben Zeiten wie die Moscheen."), 13f, C.MUTED), lp())
        input = EditText(this).apply {
            hint = l.t("Ara: ör. Dietzenbach, Kadıköy…", "Suchen: z. B. Dietzenbach, Kadıköy…")
            setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 17f
            background = roundBg(C.CARD, dp(12).toFloat(), C.HI)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> onlineSearch(); true }
            setText(intent.getStringExtra("q") ?: "")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { search() }
            })
        }
        root.addView(input, lp().apply { topMargin = dp(12) })
        status = tv("", 13f, C.MUTED)
        root.addView(status, lp().apply { topMargin = dp(6); bottomMargin = dp(4) })
        listView = ListView(this).apply {
            divider = null
            setOnItemClickListener { _, _, pos, _ -> click(rows.getOrNull(pos)) }
        }
        root.addView(listView, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        status.text = l.t("Yükleniyor…", "Wird geladen…")
        Thread { DiyanetDb.load(this); CityDb.load(this); runOnUiThread { search() } }.start()
    }

    private fun show(list: List<Row>) {
        rows = list
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun isEnabled(p: Int) = rows[p] !is Row.Header
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val box = LinearLayout(this@CityPickerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                when (val r = rows[p]) {
                    is Row.Dy -> { box.addView(tv("🕌 " + r.p.pretty(l), 17f, C.TEXT)); box.addView(tv(r.p.sub(l), 13f, C.MUTED)) }
                    is Row.Other -> { box.addView(tv("📍 " + r.name, 17f, C.TEXT)); box.addView(tv(r.sub, 13f, C.MUTED)) }
                    is Row.Header -> { box.setPadding(dp(4), dp(14), dp(4), dp(4)); box.addView(tv(r.text, 13f, C.GOLD, true)) }
                    Row.Online -> {
                        box.background = roundBg(C.HI, dp(12).toFloat())
                        box.addView(tv("🔍 " + l.t("İnternette ara: \"${input.text.toString().trim()}\"", "Im Internet suchen: \"${input.text.toString().trim()}\""), 16f, C.GOLD, true))
                        box.addView(tv(l.t("Listede olmayan köy veya mahalleler için", "Für Dörfer und Ortsteile, die fehlen"), 12f, C.MUTED))
                    }
                }
                return box
            }
        }
    }

    private fun search() {
        val q = input.text.toString()
        val seq = ++searchSeq
        Thread {
            val dy = DiyanetDb.search(this, q, 60)
            val out = ArrayList<Row>()
            dy.forEach { out.add(Row.Dy(it)) }
            if (q.trim().length >= 2) {
                val dyKeys = dy.map { it.cc + "|" + it.key }.toHashSet()
                val others = CityDb.search(this, q, 30).filter { (it.cc + "|" + it.key) !in dyKeys }.take(12)
                if (others.isNotEmpty()) {
                    out.add(Row.Header(l.t("DİYANET LİSTESİNDE OLMAYAN YERLER", "ORTE AUSSERHALB DER DIYANET-LISTE")))
                    others.forEach { r ->
                        val sub = listOf(r.region, CityDb.country(r.cc)).filter { it.isNotEmpty() }.joinToString(", ")
                        out.add(Row.Other(r.name, sub, r.lat, r.lng, r.tz))
                    }
                }
                out.add(Row.Online)
            }
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                status.text = when {
                    q.isBlank() -> l.t("Hessen'deki Diyanet yerleri · başka yer için yazarak arayın", "Diyanet-Orte in Hessen · zum Suchen tippen")
                    dy.isEmpty() -> l.t("Diyanet listesinde yok · aşağıdan yeri seçin, en yakın Diyanet yerini gösteririm",
                        "Nicht in der Diyanet-Liste · Ort unten wählen")
                    else -> l.t("${dy.size} Diyanet yeri", "${dy.size} Diyanet-Orte")
                }
                show(out)
            }
        }.start()
    }

    private fun onlineSearch() {
        val q = input.text.toString().trim()
        if (q.length < 2) return
        if (!Geocoder.isPresent()) {
            status.text = l.t("Bu telefonda internet araması yok.", "Keine Online-Suche verfügbar.")
            return
        }
        status.text = l.t("İnternette aranıyor…", "Online-Suche…")
        val seq = ++searchSeq
        Thread {
            val found = ArrayList<Row>()
            var error = false
            try {
                @Suppress("DEPRECATION")
                val res = Geocoder(this, l.locale).getFromLocationName(q, 10) ?: emptyList()
                for (a in res) {
                    val name = a.locality ?: a.subLocality ?: a.subAdminArea ?: a.featureName ?: q
                    val sub = listOfNotNull(a.subAdminArea?.takeIf { it != name }, a.adminArea, a.countryName).distinct().joinToString(", ")
                    found.add(Row.Other(name, sub, a.latitude, a.longitude, null))
                }
            } catch (e: Exception) { error = true }
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                status.text = when {
                    error -> l.t("İnternet araması yapılamadı. Bağlantıyı kontrol edin.", "Online-Suche fehlgeschlagen.")
                    found.isEmpty() -> l.t("Bulunamadı. Farklı yazmayı deneyin.", "Nichts gefunden.")
                    else -> l.t("İnternet sonuçları · bir yer seçin", "Online-Ergebnisse · Ort wählen")
                }
                if (found.isNotEmpty()) show(listOf(Row.Header(l.t("İNTERNET SONUÇLARI", "ONLINE-ERGEBNISSE"))) + found)
            }
        }.start()
    }

    private fun tzNear(lat: Double, lng: Double): String =
        CityDb.load(this).minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) }?.tz
            ?: java.time.ZoneId.systemDefault().id

    private fun click(r: Row?) {
        when (r) {
            is Row.Dy -> { setResult(Activity.RESULT_OK, Intent().putExtra("id", r.p.id)); finish() }
            Row.Online -> onlineSearch()
            is Row.Other -> chooseFor(r)
            else -> {}
        }
    }

    /** Diyanet'te olmayan yer: en yakın Diyanet yerleri ya da telefonda hesaplama. */
    private fun chooseFor(r: Row.Other) {
        status.text = l.t("En yakın Diyanet yerleri aranıyor…", "Suche nächste Diyanet-Orte…")
        Thread {
            val near = DiyanetNear.nearest(this, r.lat, r.lng, 3)
            val tz = r.tz ?: tzNear(r.lat, r.lng)
            runOnUiThread {
                status.text = ""
                val labels = ArrayList<String>()
                near.forEachIndexed { i, (p, km) ->
                    labels.add("🕌 " + p.pretty(l) + " · " + Math.round(km) + " km" +
                        (if (i == 0) l.t("  (önerilen, camilerle aynı)", "  (empfohlen, wie Moscheen)") else ""))
                }
                labels.add("📍 " + l.t("${r.name} için telefonda hesapla", "Für ${r.name} selbst berechnen") +
                    l.t("\n   tam konuma göre · camiden 1–3 dk farklı olabilir", "\n   genauer Ort · bis 1–3 Min. Abweichung zur Moschee"))
                AlertDialog.Builder(this)
                    .setTitle(l.t("\"${r.name}\" Diyanet listesinde yok", "\"${r.name}\" nicht in der Diyanet-Liste"))
                    .setItems(labels.toTypedArray()) { _, w ->
                        if (w < near.size) setResult(Activity.RESULT_OK, Intent().putExtra("id", near[w].first.id))
                        else setResult(Activity.RESULT_OK, Intent().putExtra("calc", true).putExtra("name", r.name)
                            .putExtra("lat", r.lat).putExtra("lng", r.lng).putExtra("tz", tz))
                        finish()
                    }.show()
            }
        }.start()
    }
}
