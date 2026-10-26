package com.kck.namaz

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/** Diyanet'in ilçe listesi (namazvakitleri.diyanet.gov.tr ilçe numaraları). */
object DiyanetDb {
    class Place(val id: Int, val name: String, val state: String, val country: String, val cc: String, val key: String) {
        fun pretty(l: L): String = Names.pretty(name, cc)
        fun sub(l: L): String = listOf(if (state.isNotEmpty()) Names.pretty(state, cc) else "", Names.country(l, country))
            .filter { it.isNotEmpty() }.joinToString(", ")
    }

    @Volatile private var places: List<Place>? = null

    fun load(ctx: Context): List<Place> {
        places?.let { return it }
        synchronized(this) {
            places?.let { return it }
            val out = ArrayList<Place>(8400)
            ctx.assets.open("diyanet.tsv").bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (line in seq) {
                    val p = line.split('\t')
                    if (p.size < 5) continue
                    val id = p[0].toIntOrNull() ?: continue
                    out.add(Place(id, p[1], p[2], p[3], p[4], CityDb.norm(p[1])))
                }
            }
            places = out
            return out
        }
    }

    fun byId(ctx: Context, id: Int): Place? = load(ctx).firstOrNull { it.id == id }

    fun search(ctx: Context, q: String, limit: Int = 100): List<Place> {
        val all = load(ctx)
        val n = CityDb.norm(q)
        if (n.isEmpty()) {
            val hessen = all.filter { it.cc == "DE" && it.state == "HESSEN" }
            val first = listOf("FRANKFURT", "OFFENBACH", "WIESBADEN", "DARMSTADT", "HANAU", "KASSEL")
            return first.mapNotNull { f -> hessen.firstOrNull { it.name == f } } + hessen.filter { it.name !in first }
        }
        val words = n.split(' ')
        fun rank(p: Place): Int {
            val home = when (p.cc) { "DE" -> 0; "TR" -> 0; else -> 1 }
            val m = when {
                p.key == n -> 0
                p.key.startsWith(n) -> 1
                else -> 2
            }
            return m * 2 + home
        }
        val hits = all.filter { p ->
            val hay = p.key + " " + CityDb.norm(p.state) + " " + CityDb.norm(p.country)
            words.all { w -> hay.contains(w) } && (p.key.contains(words[0]) || CityDb.norm(p.state).contains(words[0]))
        }
        return hits.sortedWith(compareBy({ rank(it) }, { it.key })).take(limit)
    }
}

object Names {
    private val trLocale = Locale("tr", "TR")

    /** "FRANKFURT" -> "Frankfurt", "İSTANBUL" -> "İstanbul". */
    fun pretty(s: String, cc: String): String {
        val loc = if (cc == "TR" || cc == "CY" || cc == "AZ") trLocale else Locale.GERMANY
        val sb = StringBuilder()
        var up = true
        for (ch in s.trim().lowercase(loc)) {
            sb.append(if (up && ch.isLetter()) ch.toString().uppercase(loc) else ch.toString())
            up = !ch.isLetter() && ch != '\''
        }
        return sb.toString()
    }

    private val de = mapOf(
        "TURKIYE" to "Türkei", "ALMANYA" to "Deutschland", "AVUSTURYA" to "Österreich", "HOLLANDA" to "Niederlande",
        "BELCIKA" to "Belgien", "ISVICRE" to "Schweiz", "FRANSA" to "Frankreich", "INGILTERE" to "Großbritannien",
        "ABD" to "USA", "DANIMARKA" to "Dänemark", "ISVEC" to "Schweden", "NORVEC" to "Norwegen", "ITALYA" to "Italien",
        "ISPANYA" to "Spanien", "SAUDI ARABISTAN" to "Saudi-Arabien", "YUNANISTAN" to "Griechenland",
        "BULGARISTAN" to "Bulgarien", "LUKSEMBURG" to "Luxemburg", "KUZEY KIBRIS" to "Nordzypern"
    )
    private val tr = mapOf(
        "TURKIYE" to "Türkiye", "BELCIKA" to "Belçika", "ISVICRE" to "İsviçre", "INGILTERE" to "İngiltere",
        "DANIMARKA" to "Danimarka", "ISVEC" to "İsveç", "NORVEC" to "Norveç", "ITALYA" to "İtalya", "ISPANYA" to "İspanya",
        "SAUDI ARABISTAN" to "Suudi Arabistan", "KUZEY KIBRIS" to "Kuzey Kıbrıs", "LUKSEMBURG" to "Lüksemburg",
        "BULGARISTAN" to "Bulgaristan", "YUNANISTAN" to "Yunanistan"
    )

    fun country(l: L, c: String): String =
        (if (l.tr) tr[c] else de[c]) ?: pretty(c, "TR")
}

/** Diyanet sitesinden indirilen yıllık vakit tablosu. */
object DiyanetTimes {
    private val cache = HashMap<Int, Map<LocalDate, IntArray>>()

    private fun file(ctx: Context, id: Int) = File(ctx.filesDir, "diyanet_$id.txt")

    /** Gün için dakika cinsinden 6 vakit (gece yarısından itibaren) ya da null. */
    @Synchronized
    fun get(ctx: Context, id: Int, date: LocalDate): IntArray? {
        val m = cache.getOrPut(id) { read(ctx, id) }
        return m[date]
    }

    fun has(ctx: Context, id: Int, date: LocalDate) = get(ctx, id, date) != null

    @Synchronized
    fun lastDate(ctx: Context, id: Int): LocalDate? = cache.getOrPut(id) { read(ctx, id) }.keys.maxOrNull()

    private fun read(ctx: Context, id: Int): Map<LocalDate, IntArray> {
        val f = file(ctx, id)
        if (!f.exists()) return emptyMap()
        val out = HashMap<LocalDate, IntArray>()
        try {
            f.forEachLine { line ->
                val p = line.trim().split(' ')
                if (p.size >= 7) {
                    val d = LocalDate.parse(p[0])
                    out[d] = IntArray(6) { i -> p[i + 1].toInt() }
                }
            }
        } catch (e: Exception) { }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, id: Int, data: Map<LocalDate, IntArray>) {
        val old = cache.getOrPut(id) { read(ctx, id) }
        val merged = HashMap(old)
        merged.putAll(data)
        // Çok eski günleri sakla ama şişmesin
        val cutoff = LocalDate.now().minusDays(60)
        val keep = merged.filterKeys { !it.isBefore(cutoff) }
        val sb = StringBuilder()
        for (d in keep.keys.sorted()) {
            sb.append(d.toString())
            for (v in keep[d]!!) sb.append(' ').append(v)
            sb.append('\n')
        }
        file(ctx, id).writeText(sb.toString())
        cache[id] = keep
    }

    class Result(val days: Int, val qibla: Double?, val error: String?)

    private val months = mapOf(
        "ocak" to 1, "subat" to 2, "mart" to 3, "nisan" to 4, "mayis" to 5, "haziran" to 6,
        "temmuz" to 7, "agustos" to 8, "eylul" to 9, "ekim" to 10, "kasim" to 11, "aralik" to 12
    )
    private val rowRe = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRe = Regex("<[^>]+>")
    private val timeRe = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    private val dotDate = Regex("\\b(\\d{1,2})[./](\\d{1,2})[./](\\d{4})\\b")
    private val longDate = Regex("\\b(\\d{1,2})\\s+([A-Za-zÇĞİÖŞÜçğıöşü]+)\\s+(\\d{4})\\b")

    private fun text(html: String) = tagRe.replace(html, " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")

    private fun parseDate(s: String): Pair<LocalDate, Int>? {
        dotDate.find(s)?.let { m ->
            return try { Pair(LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()), m.range.last) } catch (e: Exception) { null }
        }
        for (m in longDate.findAll(s)) {
            val mon = months[CityDb.norm(m.groupValues[2])] ?: continue
            return try { Pair(LocalDate.of(m.groupValues[3].toInt(), mon, m.groupValues[1].toInt()), m.range.last) } catch (e: Exception) { null }
        }
        return null
    }

    fun parse(html: String): Map<LocalDate, IntArray> {
        val out = HashMap<LocalDate, IntArray>()
        fun take(seg: String) {
            val (d, end) = parseDate(seg) ?: return
            val ts = timeRe.findAll(seg.substring(end + 1)).take(6).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList()
            if (ts.size == 6 && ts.all { it in 0 until 24 * 60 } && ts[0] < ts[2] && ts[2] < ts[3] && ts[3] < ts[4]) out[d] = ts.toIntArray()
        }
        for (m in rowRe.findAll(html)) take(text(m.groupValues[1]))
        if (out.isEmpty()) {
            // Tablo yapısı değişmişse: düz metinde "tarih + 6 saat" dizilerini ara
            val t = text(html)
            for (m in dotDate.findAll(t)) take(t.substring(m.range.first, minOf(t.length, m.range.first + 220)))
            for (m in longDate.findAll(t)) take(t.substring(m.range.first, minOf(t.length, m.range.first + 260)))
        }
        return out
    }

    private fun parseQibla(html: String): Double? {
        val t = text(html)
        val m = Regex("K[ıi]ble\\s*A[çc][ıi]s[ıi][^0-9]{0,40}?(\\d{1,3}(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE).find(t) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..360.0 }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 25000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
        c.setRequestProperty("Referer", "https://namazvakitleri.diyanet.gov.tr/tr-TR")
        try {
            val code = c.responseCode
            if (code != 200) throw Exception("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { c.disconnect() }
    }

    /** İnternetten indir (arka planda çağırın). */
    fun fetch(ctx: Context, id: Int): Result {
        return try {
            val html = get("https://namazvakitleri.diyanet.gov.tr/tr-TR/$id")
            val data = parse(html)
            if (data.isEmpty()) return Result(0, null, "parse")
            save(ctx, id, data)
            val q = parseQibla(html)
            val p = ctx.getSharedPreferences("namaz", Context.MODE_PRIVATE).edit()
                .putLong("dyFetched_$id", System.currentTimeMillis())
            if (q != null) p.putFloat("dyQibla_$id", q.toFloat())
            p.apply()
            Result(data.size, q, null)
        } catch (e: Exception) {
            Result(0, null, e.message ?: "net")
        }
    }

    fun fetchedAt(ctx: Context, id: Int): Long =
        ctx.getSharedPreferences("namaz", Context.MODE_PRIVATE).getLong("dyFetched_$id", 0L)

    fun qibla(ctx: Context, id: Int): Double? =
        ctx.getSharedPreferences("namaz", Context.MODE_PRIVATE).getFloat("dyQibla_$id", -1f).takeIf { it >= 0 }?.toDouble()

    /** Eskiyse ya da önümüzdeki 20 gün eksikse yenilenmeli mi? */
    fun needsRefresh(ctx: Context, id: Int, today: LocalDate): Boolean {
        val age = System.currentTimeMillis() - fetchedAt(ctx, id)
        val last = lastDate(ctx, id)
        if (last == null || last.isBefore(today.plusDays(20))) return age > 3 * 3600_000L
        return age > 7L * 24 * 3600_000L
    }

    @Volatile private var running = false

    /** Gerekirse arka planda yeniler; bitince onDone ana iş parçacığında değil, arka planda çağrılır. */
    fun refreshAsync(ctx: Context, id: Int, force: Boolean = false, onDone: ((Result) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (running) return
        if (!force && !needsRefresh(app, id, LocalDate.now())) return
        running = true
        Thread {
            val r = try { fetch(app, id) } finally { running = false }
            if (r.error == null) Scheduler.scheduleNext(app)
            onDone?.invoke(r)
        }.start()
    }
}
