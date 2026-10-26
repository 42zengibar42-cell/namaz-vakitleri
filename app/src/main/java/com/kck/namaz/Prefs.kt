package com.kck.namaz

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId

data class City(val name: String, val lat: Double, val lng: Double, val tz: String)

object Cities {
    val list = listOf(
        City("Frankfurt am Main", 50.1109, 8.6821, "Europe/Berlin"),
        City("Offenbach", 50.0956, 8.7761, "Europe/Berlin"),
        City("Hanau", 50.1264, 8.9283, "Europe/Berlin"),
        City("Wiesbaden", 50.0782, 8.2398, "Europe/Berlin"),
        City("Mainz", 49.9929, 8.2473, "Europe/Berlin"),
        City("Darmstadt", 49.8728, 8.6512, "Europe/Berlin"),
        City("Rüsselsheim", 49.9949, 8.4114, "Europe/Berlin"),
        City("Gießen", 50.5841, 8.6784, "Europe/Berlin"),
        City("Fulda", 50.5558, 9.6808, "Europe/Berlin"),
        City("Kassel", 51.3127, 9.4797, "Europe/Berlin"),
        City("Mannheim", 49.4875, 8.4660, "Europe/Berlin"),
        City("Köln", 50.9375, 6.9603, "Europe/Berlin"),
        City("Düsseldorf", 51.2277, 6.7735, "Europe/Berlin"),
        City("Dortmund", 51.5136, 7.4653, "Europe/Berlin"),
        City("Berlin", 52.5200, 13.4050, "Europe/Berlin"),
        City("Hamburg", 53.5511, 9.9937, "Europe/Berlin"),
        City("München", 48.1351, 11.5820, "Europe/Berlin"),
        City("Stuttgart", 48.7758, 9.1829, "Europe/Berlin"),
        City("Nürnberg", 49.4521, 11.0767, "Europe/Berlin"),
        City("İstanbul", 41.0082, 28.9784, "Europe/Istanbul"),
        City("Ankara", 39.9334, 32.8597, "Europe/Istanbul"),
        City("İzmir", 38.4237, 27.1428, "Europe/Istanbul"),
        City("Bursa", 40.1885, 29.0610, "Europe/Istanbul"),
        City("Konya", 37.8746, 32.4932, "Europe/Istanbul"),
        City("Kayseri", 38.7205, 35.4826, "Europe/Istanbul"),
        City("Trabzon", 41.0027, 39.7168, "Europe/Istanbul"),
    )
}

class Prefs(ctx: Context) {
    private val app: Context = ctx.applicationContext ?: ctx
    private val p: SharedPreferences = ctx.getSharedPreferences("namaz", Context.MODE_PRIVATE)

    /** Diyanet ilçe numarası (0 = seçilmedi). Varsayılan: Frankfurt (Hessen). */
    var diyanetId: Int
        get() = p.getInt("diyanetId", if ((p.getString("city", null) ?: "Frankfurt am Main") == "Frankfurt am Main") 11010 else 0)
        set(v) = p.edit().putInt("diyanetId", v).apply()

    /** Bu gün için Diyanet verisi var mı? */
    fun fromDiyanet(date: LocalDate): Boolean = diyanetId > 0 && DiyanetTimes.has(app, diyanetId, date)

    var lang: String
        get() = p.getString("lang", "tr") ?: "tr"
        set(v) = p.edit().putString("lang", v).apply()

    var city: City
        get() = City(
            p.getString("city", "Frankfurt am Main") ?: "Frankfurt am Main",
            p.getFloat("lat", 50.1109f).toDouble(),
            p.getFloat("lng", 8.6821f).toDouble(),
            p.getString("tz", "Europe/Berlin") ?: "Europe/Berlin"
        )
        set(c) = p.edit().putString("city", c.name).putFloat("lat", c.lat.toFloat())
            .putFloat("lng", c.lng.toFloat()).putString("tz", c.tz).apply()

    val zone: ZoneId get() = try { ZoneId.of(city.tz) } catch (e: Exception) { ZoneId.systemDefault() }

    fun notify(i: Int): Boolean = p.getBoolean("n$i", i != 1) // güneş varsayılan kapalı
    fun setNotify(i: Int, v: Boolean) = p.edit().putBoolean("n$i", v).apply()

    fun offset(i: Int): Int = p.getInt("o$i", 0)
    fun setOffset(i: Int, v: Int) = p.edit().putInt("o$i", v).apply()
    fun offsets() = IntArray(PrayerCalc.COUNT) { offset(it) }

    var before: Int
        get() = p.getInt("before", 0)
        set(v) = p.edit().putInt("before", v).apply()

    var adhan: Boolean
        get() = p.getBoolean("adhan", true)
        set(v) = p.edit().putBoolean("adhan", v).apply()

    /** Vakte özel ezan dosyası; null = uygulamadaki ezan. */
    fun adhanUri(i: Int): String? {
        val v = p.getString("adhanUri$i", null) ?: p.getString("adhanUri", null) // eski sürümden kalan ortak seçim
        return if (v.isNullOrEmpty()) null else v
    }
    fun adhanName(i: Int): String? = if (adhanUri(i) == null) null
        else p.getString("adhanName$i", null) ?: p.getString("adhanName", null)
    fun setAdhanFile(i: Int, uri: String?, name: String?) =
        p.edit().putString("adhanUri$i", uri ?: "").putString("adhanName$i", name).apply()

    var religiousNotify: Boolean
        get() = p.getBoolean("relNotify", true)
        set(v) = p.edit().putBoolean("relNotify", v).apply()

    fun adhanOn(i: Int): Boolean = p.getBoolean("adhanOn$i", true)
    fun setAdhanOn(i: Int, v: Boolean) = p.edit().putBoolean("adhanOn$i", v).apply()

    var bayramOffset: Int
        get() = p.getInt("oBayram", 0)
        set(v) = p.edit().putInt("oBayram", v).apply()

    fun bayram(date: LocalDate): Long {
        val c = city
        return PrayerCalc.bayram(date, c.lat, c.lng, zone, bayramOffset)
    }

    fun times(date: LocalDate): LongArray {
        val id = diyanetId
        if (id > 0) {
            val d = DiyanetTimes.get(app, id, date)
            if (d != null) {
                val o = offsets()
                return LongArray(PrayerCalc.COUNT) { i ->
                    date.atStartOfDay().plusMinutes((d[i] + o[i]).toLong()).atZone(zone).toInstant().toEpochMilli()
                }
            }
        }
        val c = city
        return PrayerCalc.times(date, c.lat, c.lng, zone, offsets())
    }

    /** Kıble açısı: Diyanet'in verdiği açı varsa o, yoksa koordinattan hesap. */
    fun qibla(): Double {
        val id = diyanetId
        if (id > 0) DiyanetTimes.qibla(app, id)?.let { return it }
        val c = city
        return PrayerCalc.qibla(c.lat, c.lng)
    }
}

/** Basit iki dilli metinler. */
class L(ctx: Context) {
    val tr = Prefs(ctx).lang != "de"

    val names: Array<String> get() = if (tr)
        arrayOf("İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı")
    else arrayOf("Fadschr", "Sonnenaufgang", "Mittag (Dhuhr)", "Nachmittag (Asr)", "Abend (Maghrib)", "Nacht (Ischa)")

    val shortNames: Array<String> get() = if (tr)
        arrayOf("İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı")
    else arrayOf("Fadschr", "Sonne", "Dhuhr", "Asr", "Maghrib", "Ischa")

    fun t(tr: String, de: String) = if (this.tr) tr else de

    val hijriMonths: Array<String> get() = if (tr)
        arrayOf("Muharrem", "Safer", "Rebiülevvel", "Rebiülahir", "Cemaziyelevvel", "Cemaziyelahir",
            "Recep", "Şaban", "Ramazan", "Şevval", "Zilkade", "Zilhicce")
    else arrayOf("Muharram", "Safar", "Rabi al-Awwal", "Rabi ath-Thani", "Dschumada al-Ula", "Dschumada ath-Thaniya",
            "Radschab", "Schaban", "Ramadan", "Schawwal", "Dhu l-Qa'da", "Dhu l-Hiddscha")

    val locale: java.util.Locale get() = if (tr) java.util.Locale("tr", "TR") else java.util.Locale.GERMANY
}
