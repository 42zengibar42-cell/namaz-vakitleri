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

class CityPickerActivity : AppCompatActivity() {
    private lateinit var l: L
    private lateinit var input: EditText
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private var items: List<Item> = emptyList()
    private var searchSeq = 0

    data class Item(val title: String, val sub: String, val city: City?, val online: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        l = L(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG)
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }
        root.addView(tv(l.t("Şehir / ilçe seç", "Stadt / Ort wählen"), 22f, C.TEXT, true))
        input = EditText(this).apply {
            hint = l.t("Ara: ör. Dietzenbach, Kadıköy…", "Suchen: z. B. Dietzenbach, Kadıköy…")
            setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 17f
            background = roundBg(C.CARD, dp(12).toFloat(), C.HI)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> onlineSearch(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { localSearch() }
            })
        }
        root.addView(input, lp().apply { topMargin = dp(12) })
        status = tv("", 13f, C.MUTED)
        root.addView(status, lp().apply { topMargin = dp(6); bottomMargin = dp(4) })
        listView = ListView(this).apply {
            divider = null; isVerticalScrollBarEnabled = true
            setOnItemClickListener { _, _, pos, _ -> pick(items.getOrNull(pos)) }
        }
        root.addView(listView, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        status.text = l.t("Yükleniyor…", "Wird geladen…")
        Thread { CityDb.load(this); runOnUiThread { localSearch() } }.start()
    }

    private fun show(list: List<Item>) {
        items = list
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val it = items[p]
                val box = LinearLayout(this@CityPickerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    background = if (it.city == null) roundBg(C.HI, dp(12).toFloat()) else null
                }
                box.addView(tv(it.title, 17f, if (it.city == null) C.GOLD else C.TEXT, it.city == null || p < 0))
                if (it.sub.isNotEmpty()) box.addView(tv(it.sub, 13f, C.MUTED))
                return box
            }
        }
    }

    private fun localSearch() {
        val q = input.text.toString()
        val seq = ++searchSeq
        Thread {
            val res = CityDb.search(this, q)
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                val list = ArrayList<Item>()
                for (r in res) {
                    val sub = listOf(r.region, CityDb.country(r.cc)).filter { it.isNotEmpty() }.joinToString(", ")
                    list.add(Item(r.name, sub, City(r.name, r.lat, r.lng, r.tz)))
                }
                if (q.trim().length >= 2) list.add(Item("🔍 " + l.t("İnternette ara: \"${q.trim()}\"", "Im Internet suchen: \"${q.trim()}\""),
                    l.t("Listede yoksa köy, mahalle veya adres de arayabilirsiniz", "Auch Dörfer, Stadtteile oder Adressen"), null))
                status.text = when {
                    q.isBlank() -> l.t("Hessen ve Türkiye'nin büyük şehirleri · yazarak arayın", "Große Städte in Hessen und der Türkei · zum Suchen tippen")
                    res.isEmpty() -> l.t("Listede bulunamadı", "Nicht in der Liste")
                    else -> l.t("${res.size} sonuç", "${res.size} Treffer")
                }
                show(list)
            }
        }.start()
    }

    private fun onlineSearch() {
        val q = input.text.toString().trim()
        if (q.length < 2) return
        if (!Geocoder.isPresent()) {
            status.text = l.t("Bu telefonda internet araması yok. GPS ile konumu kullanın.", "Keine Online-Suche verfügbar. Bitte GPS nutzen.")
            return
        }
        status.text = l.t("İnternette aranıyor…", "Online-Suche…")
        val seq = ++searchSeq
        Thread {
            val found = ArrayList<Item>()
            var error = false
            try {
                @Suppress("DEPRECATION")
                val res = Geocoder(this, l.locale).getFromLocationName(q, 10) ?: emptyList()
                for (a in res) {
                    val name = a.locality ?: a.subLocality ?: a.subAdminArea ?: a.featureName ?: q
                    val sub = listOfNotNull(a.subAdminArea?.takeIf { it != name }, a.adminArea, a.countryName).distinct().joinToString(", ")
                    val tz = tzFor(a.countryCode, a.latitude, a.longitude)
                    found.add(Item(name, sub, City(name, a.latitude, a.longitude, tz), online = true))
                }
            } catch (e: Exception) { error = true }
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                status.text = when {
                    error -> l.t("İnternet araması yapılamadı. İnternet bağlantısını kontrol edin.", "Online-Suche fehlgeschlagen. Internet prüfen.")
                    found.isEmpty() -> l.t("Bulunamadı. Farklı yazmayı deneyin.", "Nichts gefunden.")
                    else -> l.t("İnternet sonuçları", "Online-Ergebnisse")
                }
                if (found.isNotEmpty()) show(found)
            }
        }.start()
    }

    /** Ülke koduna göre saat dilimi; birden fazla dilimli ülkelerde en yakın şehrinkini kullan. */
    private fun tzFor(cc: String?, lat: Double, lng: Double): String {
        val all = CityDb.load(this)
        val pool = if (cc != null) all.filter { it.cc == cc.uppercase() } else all
        val src = if (pool.isEmpty()) all else pool
        return src.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) }?.tz
            ?: java.time.ZoneId.systemDefault().id
    }

    private fun pick(it: Item?) {
        if (it == null) return
        if (it.city == null) { onlineSearch(); return }
        val c = it.city
        setResult(Activity.RESULT_OK, Intent()
            .putExtra("name", c.name).putExtra("lat", c.lat).putExtra("lng", c.lng).putExtra("tz", c.tz))
        finish()
    }
}
