package com.biketracker.app

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Menyimpan jalur lengkap (lat, lon, tinggi, waktu) tiap aktivitas di berkas terpisah.
 * Riwayat sendiri hanya menyimpan rute yang dipadatkan (±300 titik), sedangkan GPX diekspor dari jalur lengkap ini.
 */
object TrackStore {
    private fun dir(c: Context) = File(c.applicationContext.filesDir, "tracks")

    /** Kunci berkas dari tanggal dan durasi (tanggal di riwayat hanya sampai menit, durasi membedakan dua aktivitas semenit yang sama). */
    fun key(date: String, elapsedMs: Long): String =
        (date + "_" + elapsedMs).replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun save(c: Context, key: String, pts: List<DoubleArray>) {
        if (pts.size < 2) return
        try {
            val d = dir(c)
            if (!d.exists()) d.mkdirs()
            File(d, "$key.trk").bufferedWriter().use { w ->
                for (p in pts) {
                    w.write(String.format(Locale.US, "%.6f,%.6f,", p[0], p[1]))
                    if (p.size >= 4) {
                        if (!p[2].isNaN()) w.write(String.format(Locale.US, "%.1f", p[2]))
                        w.write(",")
                        w.write(p[3].toLong().toString())
                    }
                    w.write("\n")
                }
            }
        } catch (e: Exception) {
            // Gagal menyimpan jalur lengkap tidak boleh menggagalkan penyimpanan aktivitas.
        }
    }

    /** Jalur lengkap, atau daftar kosong kalau tidak ada (aktivitas lama sebelum fitur ini). */
    fun load(c: Context, key: String): List<DoubleArray> {
        val f = File(dir(c), "$key.trk")
        if (!f.exists()) return emptyList()
        return try {
            val out = ArrayList<DoubleArray>()
            f.forEachLine { line ->
                val q = line.split(",")
                val la = q.getOrNull(0)?.toDoubleOrNull()
                val lo = q.getOrNull(1)?.toDoubleOrNull()
                if (la != null && lo != null) {
                    val ele = q.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN
                    val tm = q.getOrNull(3)?.toLongOrNull()
                    out.add(if (tm != null) doubleArrayOf(la, lo, ele, tm.toDouble()) else doubleArrayOf(la, lo))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun delete(c: Context, key: String) {
        try {
            File(dir(c), "$key.trk").delete()
        } catch (e: Exception) {
        }
    }

    fun deleteAll(c: Context) {
        try {
            dir(c).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
        }
    }
}
