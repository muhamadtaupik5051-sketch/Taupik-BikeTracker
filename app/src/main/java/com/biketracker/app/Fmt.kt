package com.biketracker.app

import java.util.Locale

/**
 * Format angka untuk tampilan gaya Indonesia (koma desimal, titik ribuan).
 * Data yang disimpan ke riwayat tetap memakai Locale.US supaya aman dibaca ulang.
 */
object Fmt {
    private val id: Locale = Locale.forLanguageTag("id-ID")

    fun dec(v: Float, digits: Int = 1): String = String.format(id, "%." + digits + "f", v)

    fun dec(v: Double, digits: Int = 1): String = String.format(id, "%." + digits + "f", v)

    fun int(n: Int): String = String.format(id, "%,d", n)

    fun time(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Pace dalam detik per km -> "5:42". Nilai kosong atau tidak masuk akal tampil "--:--". */
    fun pace(sec: Int?): String {
        if (sec == null || sec <= 0 || sec > 5999) return "--:--"
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    /** Durasi ringkas untuk statistik mingguan, mis. "3 j 05 m". */
    fun hm(ms: Long): String {
        val mins = (ms / 60000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d j %02d m", mins / 60, mins % 60)
    }
}
