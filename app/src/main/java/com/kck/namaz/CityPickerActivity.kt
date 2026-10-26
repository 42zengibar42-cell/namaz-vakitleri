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

class CityPickerActivity : AppCompatActivity() {
    private lateinit var l: L
    private lateinit var input: EditText
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private var items: List<DiyanetDb.Place> = emptyList()
    private var searchSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        l = L(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG)
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }
        root.addView(tv(l.t("Diyanet ilçe seçimi", "Ort wählen (Diyanet)"), 22f, C.TEXT, true))
        root.addView(tv(l.t("Vakitler seçtiğiniz yerin Diyanet takviminden alınır.",
            "Die Zeiten kommen aus dem Diyanet-Kalender dieses Ortes."), 13f, C.MUTED), lp())
        input = EditText(this).apply {
            hint = l.t("Ara: ör. Dietzenbach, Kadıköy, Hessen…", "Suchen: z. B. Dietzenbach, Kadıköy…")
            setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 17f
            background = roundBg(C.CARD, dp(12).toFloat(), C.HI)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
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
            setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { pick(it) } }
        }
        root.addView(listView, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        status.text = l.t("Yükleniyor…", "Wird geladen…")
        Thread { DiyanetDb.load(this); CityDb.load(this); runOnUiThread { search() } }.start()
    }

    private fun show(list: List<DiyanetDb.Place>) {
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
                }
                box.addView(tv(it.pretty(l), 17f, C.TEXT))
                box.addView(tv(it.sub(l), 13f, C.MUTED))
                return box
            }
        }
    }

    private fun search() {
        val q = input.text.toString()
        val seq = ++searchSeq
        Thread {
            val res = DiyanetDb.search(this, q)
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                status.text = when {
                    q.isBlank() -> l.t("Hessen · başka yer için yazarak arayın (ilçe, il, eyalet veya ülke)",
                        "Hessen · zum Suchen tippen (Ort, Bundesland oder Land)")
                    res.isEmpty() -> l.t("Diyanet listesinde bulunamadı. En yakın büyük yeri arayın.",
                        "Nicht in der Diyanet-Liste. Bitte den nächsten größeren Ort suchen.")
                    else -> l.t("${res.size} sonuç", "${res.size} Treffer")
                }
                show(res)
            }
        }.start()
    }

    private fun pick(p: DiyanetDb.Place) {
        setResult(Activity.RESULT_OK, Intent().putExtra("id", p.id))
        finish()
    }
}
