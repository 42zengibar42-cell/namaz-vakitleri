package com.kck.namaz

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.*

/**
 * Diyanet yöntemiyle namaz vakti hesabı.
 * İmsak 18°, Yatsı 17°, İkindi (Şafii/asr-ı evvel), temkinler:
 * Güneş -7, Öğle +5, İkindi +4, Akşam +7, Yatsı -7 dk.
 * 45° kuzeyinde yaz aylarında Diyanet'in kısaltılmış imsak/yatsı kuralına
 * yaklaşan bir sınır uygulanır (Almanya tablolarıyla karşılaştırıldı).
 */
object PrayerCalc {
    const val COUNT = 6 // imsak, güneş, öğle, ikindi, akşam, yatsı

    private fun dsin(x: Double) = sin(Math.toRadians(x))
    private fun dcos(x: Double) = cos(Math.toRadians(x))
    private fun dtan(x: Double) = tan(Math.toRadians(x))
    private fun fix(a: Double, b: Double): Double { var r = a - b * floor(a / b); if (r < 0) r += b; return r }

    private fun julian(y0: Int, m0: Int, d: Int): Double {
        var y = y0; var m = m0
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0); val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** returns (declination, equation of time) */
    private fun sun(jd: Double): Pair<Double, Double> {
        val dd = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * dd, 360.0)
        val q = fix(280.459 + 0.98564736 * dd, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * dd
        val ra = Math.toDegrees(atan2(dcos(e) * dsin(l), dcos(l))) / 15.0
        val eqt = q / 15.0 - fix(ra, 24.0)
        val decl = Math.toDegrees(asin(dsin(e) * dsin(l)))
        return Pair(decl, eqt)
    }

    private fun hourAngle(angle: Double, decl: Double, lat: Double): Double {
        val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
        if (x < -1 || x > 1) return Double.NaN
        return Math.toDegrees(acos(x)) / 15.0
    }

    /** Yerel saat olarak (ondalık saat) 6 vakit. */
    fun hours(date: LocalDate, lat: Double, lng: Double, tz: Double): DoubleArray {
        val jd = julian(date.year, date.monthValue, date.dayOfMonth) - lng / (15.0 * 24.0)
        fun at(h: Double) = sun(jd + h / 24.0)
        fun noon(h: Double) = fix(12 - at(h).second, 24.0)
        fun t(angle: Double, h: Double, ccw: Boolean): Double {
            val (decl, _) = at(h)
            val ha = hourAngle(angle, decl, lat)
            return if (ccw) noon(h) - ha else noon(h) + ha
        }
        fun asr(h: Double): Double {
            val (decl, _) = at(h)
            val a = -Math.toDegrees(atan(1.0 / (1.0 + dtan(abs(lat - decl)))))
            return noon(h) + hourAngle(a, decl, lat)
        }

        val tt = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        repeat(2) {
            val n = doubleArrayOf(
                t(18.0, tt[0], true), t(0.833, tt[1], true), noon(tt[2]),
                asr(tt[3]), t(0.833, tt[4], false), t(17.0, tt[5], false)
            )
            for (i in 0 until 6) if (!n[i].isNaN()) tt[i] = n[i]
        }
        var fajr = t(18.0, tt[0], true)
        val sunrise = tt[1]; val dhuhr = tt[2]; val asrT = tt[3]; val sunset = tt[4]
        var isha = t(17.0, tt[5], false)

        var ishaCapped = false
        if (lat >= 45.0) {
            val decl = at(12.0).first
            val fc = (172.58 - 3.340 * decl) / 60.0
            val ic = (162.81 - 3.335 * decl) / 60.0
            if (fajr.isNaN() || sunrise - fajr > fc) fajr = sunrise - fc
            if (isha.isNaN() || isha - sunset > ic) { isha = sunset + ic; ishaCapped = true }
        }
        if (fajr.isNaN()) fajr = sunrise - 1.5
        if (isha.isNaN()) { isha = sunset + 1.5; ishaCapped = true }

        val out = doubleArrayOf(
            fajr, sunrise - 7 / 60.0, dhuhr + 5 / 60.0, asrT + 4 / 60.0,
            sunset + 7 / 60.0, if (ishaCapped) isha else isha - 7 / 60.0
        )
        val shift = tz - lng / 15.0
        for (i in out.indices) out[i] = out[i] + shift
        return out
    }

    /** Verilen gün için 6 vakit (epoch milisaniye). offsets: dakika düzeltmeleri. */
    fun times(date: LocalDate, lat: Double, lng: Double, zone: ZoneId, offsets: IntArray? = null): LongArray {
        val noon = ZonedDateTime.of(date.atTime(12, 0), zone)
        val tz = noon.offset.totalSeconds / 3600.0
        val h = hours(date, lat, lng, tz)
        return LongArray(COUNT) { i ->
            val mins = Math.round(h[i] * 60).toInt() + (offsets?.getOrNull(i) ?: 0)
            val ldt: LocalDateTime = date.atStartOfDay().plusMinutes(mins.toLong())
            ldt.atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** Kıble açısı (gerçek kuzeye göre, derece). */
    fun qibla(lat: Double, lng: Double): Double {
        val la = Math.toRadians(lat); val lo = Math.toRadians(lng)
        val kl = Math.toRadians(21.4225); val kL = Math.toRadians(39.8262)
        val b = atan2(sin(kL - lo), cos(la) * tan(kl) - sin(la) * cos(kL - lo))
        return (Math.toDegrees(b) + 360) % 360
    }
}
