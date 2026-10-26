package com.kck.namaz

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

enum class Kind(val night: Boolean, val days: Int) {
    UC_AYLAR(false, 1), REGAIB(true, 1), MIRAC(true, 1), BERAT(true, 1),
    RAMAZAN(false, 1), KADIR(true, 1), AREFE_R(false, 1), R_BAYRAM(false, 3),
    AREFE_K(false, 1), K_BAYRAM(false, 4), HICRI(false, 1), ASURE(false, 1), MEVLID(true, 1)
}

/** date: Diyanet'in verdiği miladi gün (kandiller o günün akşamı idrak edilir). */
data class RDay(val date: LocalDate, val kind: Kind, val hDay: Int, val hMonth: Int, val hYear: Int, val official: Boolean) {
    val lastDay: LocalDate get() = date.plusDays((kind.days - 1).toLong())
}

object ReligiousDays {
    /** Diyanet İşleri Başkanlığı dini günler takvimi (2026 ve 2027). */
    private val official: List<RDay> = listOf(
        r("2025-12-21", Kind.UC_AYLAR, 1, 7, 1447),
        r("2025-12-25", Kind.REGAIB, 5, 7, 1447),
        r("2026-01-15", Kind.MIRAC, 26, 7, 1447),
        r("2026-02-02", Kind.BERAT, 14, 8, 1447),
        r("2026-02-19", Kind.RAMAZAN, 1, 9, 1447),
        r("2026-03-16", Kind.KADIR, 26, 9, 1447),
        r("2026-03-19", Kind.AREFE_R, 29, 9, 1447),
        r("2026-03-20", Kind.R_BAYRAM, 1, 10, 1447),
        r("2026-05-26", Kind.AREFE_K, 9, 12, 1447),
        r("2026-05-27", Kind.K_BAYRAM, 10, 12, 1447),
        r("2026-06-16", Kind.HICRI, 1, 1, 1448),
        r("2026-06-25", Kind.ASURE, 10, 1, 1448),
        r("2026-08-24", Kind.MEVLID, 11, 3, 1448),
        r("2026-12-10", Kind.UC_AYLAR, 1, 7, 1448),
        r("2026-12-10", Kind.REGAIB, 1, 7, 1448),
        r("2027-01-04", Kind.MIRAC, 26, 7, 1448),
        r("2027-01-22", Kind.BERAT, 14, 8, 1448),
        r("2027-02-08", Kind.RAMAZAN, 1, 9, 1448),
        r("2027-03-05", Kind.KADIR, 26, 9, 1448),
        r("2027-03-08", Kind.AREFE_R, 29, 9, 1448),
        r("2027-03-09", Kind.R_BAYRAM, 1, 10, 1448),
        r("2027-05-15", Kind.AREFE_K, 9, 12, 1448),
        r("2027-05-16", Kind.K_BAYRAM, 10, 12, 1448),
        r("2027-06-06", Kind.HICRI, 1, 1, 1449),
        r("2027-06-15", Kind.ASURE, 10, 1, 1449),
        r("2027-08-13", Kind.MEVLID, 11, 3, 1449),
        r("2027-11-29", Kind.UC_AYLAR, 1, 7, 1449),
        r("2027-12-02", Kind.REGAIB, 4, 7, 1449),
        r("2027-12-24", Kind.MIRAC, 26, 7, 1449),
    )
    private val officialEnd: LocalDate = LocalDate.of(2027, 12, 31)

    private fun r(d: String, k: Kind, hd: Int, hm: Int, hy: Int) = RDay(LocalDate.parse(d), k, hd, hm, hy, true)

    private fun h(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.from(HijrahDate.of(y, m, d)) } catch (e: Exception) { null }

    /** Diyanet listesinin bittiği yerden sonrası için Ümmü'l-Kurâ takviminden tahmin. */
    private fun computed(): List<RDay> {
        val out = ArrayList<RDay>()
        for (y in 1449..1455) {
            fun add(m: Int, d: Int, k: Kind, yy: Int = y) { h(yy, m, d)?.let { out.add(RDay(it, k, d, m, yy, false)) } }
            add(1, 1, Kind.HICRI); add(1, 10, Kind.ASURE); add(3, 11, Kind.MEVLID)
            h(y, 7, 1)?.let { first ->
                out.add(RDay(first, Kind.UC_AYLAR, 1, 7, y, false))
                var t = first
                while (t.dayOfWeek != DayOfWeek.THURSDAY) t = t.plusDays(1)
                out.add(RDay(t, Kind.REGAIB, 1 + (t.toEpochDay() - first.toEpochDay()).toInt(), 7, y, false))
            }
            add(7, 26, Kind.MIRAC); add(8, 14, Kind.BERAT); add(9, 1, Kind.RAMAZAN); add(9, 26, Kind.KADIR)
            h(y, 10, 1)?.let { b ->
                val ar = b.minusDays(1)
                val hd = try { HijrahDate.from(ar).get(ChronoField.DAY_OF_MONTH) } catch (e: Exception) { 29 }
                out.add(RDay(ar, Kind.AREFE_R, hd, 9, y, false))
                out.add(RDay(b, Kind.R_BAYRAM, 1, 10, y, false))
            }
            add(12, 9, Kind.AREFE_K); add(12, 10, Kind.K_BAYRAM)
        }
        return out.filter { it.date.isAfter(officialEnd) }
    }

    val all: List<RDay> by lazy { (official + computed()).sortedWith(compareBy({ it.date }, { if (it.kind.night) 0 else 1 }, { it.kind.ordinal })) }

    fun onDate(d: LocalDate): List<RDay> = all.filter { !d.isBefore(it.date) && !d.isAfter(it.lastDay) }

    /** Bugünü kapsayan ya da sıradaki Ramazan: (ilk oruç, arefe = son oruç). */
    fun ramadanFor(today: LocalDate): Pair<LocalDate, LocalDate>? {
        val starts = all.filter { it.kind == Kind.RAMAZAN }
        for (s in starts) {
            val end = all.firstOrNull { it.kind == Kind.AREFE_R && it.date.isAfter(s.date) } ?: continue
            if (!today.isAfter(end.date)) return Pair(s.date, end.date)
        }
        return null
    }

    fun inRamadan(today: LocalDate): Boolean {
        val r = ramadanFor(today) ?: return false
        return !today.isBefore(r.first)
    }

    fun name(l: L, k: Kind): String = when (k) {
        Kind.UC_AYLAR -> l.t("Üç Ayların Başlangıcı", "Beginn der drei heiligen Monate")
        Kind.REGAIB -> l.t("Regaib Kandili", "Regaib-Nacht")
        Kind.MIRAC -> l.t("Miraç Kandili", "Miradsch-Nacht")
        Kind.BERAT -> l.t("Berat Kandili", "Berat-Nacht")
        Kind.RAMAZAN -> l.t("Ramazan Başlangıcı", "Beginn des Ramadan")
        Kind.KADIR -> l.t("Kadir Gecesi", "Nacht der Bestimmung (Kadir)")
        Kind.AREFE_R -> l.t("Ramazan Bayramı Arefesi", "Vortag des Ramadanfests")
        Kind.R_BAYRAM -> l.t("Ramazan Bayramı", "Ramadanfest")
        Kind.AREFE_K -> l.t("Kurban Bayramı Arefesi", "Arafat-Tag (Vortag Opferfest)")
        Kind.K_BAYRAM -> l.t("Kurban Bayramı", "Opferfest")
        Kind.HICRI -> l.t("Hicri Yılbaşı", "Islamisches Neujahr")
        Kind.ASURE -> l.t("Aşure Günü", "Aschura-Tag")
        Kind.MEVLID -> l.t("Mevlid Kandili", "Mevlid-Nacht")
    }

    fun emoji(k: Kind): String = when (k) {
        Kind.R_BAYRAM, Kind.K_BAYRAM -> "🎉"
        Kind.RAMAZAN, Kind.KADIR -> "🌙"
        Kind.AREFE_R, Kind.AREFE_K -> "🕊"
        Kind.HICRI -> "📆"
        Kind.ASURE -> "🥣"
        Kind.UC_AYLAR -> "✨"
        else -> "🕯"
    }

    fun hijriText(l: L, d: RDay): String = "${d.hDay} ${l.hijriMonths.getOrElse(d.hMonth - 1) { "" }} ${d.hYear}"

    /** Bildirim metni. dayBefore=true ise bir gün önceki hatırlatma. */
    fun message(l: L, d: RDay, dayBefore: Boolean): Pair<String, String> {
        val n = name(l, d.kind)
        return if (dayBefore) when {
            d.kind == Kind.RAMAZAN -> Pair(l.t("Yarın Ramazan başlıyor", "Morgen beginnt der Ramadan"),
                l.t("Bu gece ilk sahur. Hayırlı Ramazanlar!", "Heute Nacht ist der erste Sahur. Gesegneten Ramadan!"))
            d.kind.night -> Pair(l.t("Yarın akşam $n", "Morgen Abend: $n"),
                l.t("$n yarın akşam idrak edilecek.", "Die $n ist morgen Abend."))
            d.kind == Kind.R_BAYRAM || d.kind == Kind.K_BAYRAM -> Pair(l.t("Yarın $n", "Morgen: $n"),
                l.t("Bayram namazı vaktini unutmayın.", "Denken Sie an das Festgebet."))
            else -> Pair(l.t("Yarın: $n", "Morgen: $n"), hijriText(l, d))
        } else when {
            d.kind == Kind.RAMAZAN -> Pair(l.t("Hayırlı Ramazanlar 🌙", "Gesegneten Ramadan 🌙"),
                l.t("Ramazan'ın ilk günü. Allah kabul etsin.", "Erster Tag des Ramadan."))
            d.kind.night -> Pair(l.t("Bu gece $n 🕯", "Heute Nacht: $n 🕯"),
                l.t("Kandiliniz mübarek olsun.", "Gesegnete Nacht!"))
            d.kind == Kind.R_BAYRAM || d.kind == Kind.K_BAYRAM -> Pair(l.t("$n mübarek olsun 🎉", "Gesegnetes $n 🎉"),
                l.t("Bayramınız kutlu olsun.", "Frohes Fest!"))
            else -> Pair(l.t("Bugün: $n", "Heute: $n"), hijriText(l, d))
        }
    }
}
