package com.biketracker.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Membaca berkas GPX (dari Strava, Garmin Connect, aplikasi lain, atau ekspor Delaas sendiri) menjadi satu aktivitas riwayat.
 * Sengaja memakai regex, bukan XmlPullParser, supaya bisa diuji di unit test biasa dan tetap cepat untuk berkas besar.
 */
object GpxImport {

    /** Titik jalur: [lat, lon, tinggi (NaN bila tidak ada), waktu ms (0 bila tidak ada)]. */
    class Result(
        val points: List<DoubleArray>,
        val date: String,
        val km: Double,
        val elapsedMs: Long,
        val movingMs: Long,
        val avgKmh: Double,
        val maxKmh: Double,
        val climbM: Double,
        val samples: List<Float>,
        val sampleSec: Int
    )

    /** Batas kecepatan masuk akal; lonjakan GPS di atas ini tidak dihitung sebagai kecepatan maksimum. */
    private const val MAX_PLAUSIBLE_KMH = 180.0

    private val trkpt = Regex("<trkpt\\b([^>]*?)(?:/>|>(.*?)</trkpt>)", RegexOption.DOT_MATCHES_ALL)
    private val latAttr = Regex("\\blat\\s*=\\s*[\"']([^\"']+)[\"']")
    private val lonAttr = Regex("\\blon\\s*=\\s*[\"']([^\"']+)[\"']")
    private val eleTag = Regex("<ele>\\s*([^<]+?)\\s*</ele>")
    private val timeTag = Regex("<time>\\s*([^<]+?)\\s*</time>")

    /** Waktu ISO 8601 (mis. 2026-10-16T04:00:00Z, dengan pecahan detik, atau dengan selisih +07:00) ke milidetik, atau null. */
    internal fun parseTime(raw: String): Long? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        t = t.replace(Regex("\\.\\d+"), "")
        t = if (t.endsWith("Z")) t.dropLast(1) + "+0000"
        else t.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "\$1\$2")
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(t)?.time
        } catch (e: Exception) {
            null
        }
    }

    internal fun parsePoints(xml: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (m in trkpt.findAll(xml)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val la = latAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lo = lonAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (la < -90.0 || la > 90.0 || lo < -180.0 || lo > 180.0) continue
            val ele = eleTag.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN
            val tm = timeTag.find(body)?.groupValues?.get(1)?.let { parseTime(it) } ?: 0L
            out.add(doubleArrayOf(la, lo, ele, tm.toDouble()))
        }
        return out
    }

    /** Jarak haversine dua titik dalam meter. */
    internal fun meters(a: DoubleArray, b: DoubleArray): Double {
        val p1 = Math.toRadians(a[0])
        val p2 = Math.toRadians(b[0])
        val s1 = Math.sin((p2 - p1) / 2.0)
        val s2 = Math.sin(Math.toRadians(b[1] - a[1]) / 2.0)
        val h = s1 * s1 + Math.cos(p1) * Math.cos(p2) * s2 * s2
        return 2.0 * 6371000.0 * Math.asin(Math.sqrt(h))
    }

    /** Urai GPX. Null kalau tidak ada minimal dua titik jalur. */
    fun parse(xml: String): Result? {
        val pts = parsePoints(xml)
        if (pts.size < 2) return null

        var dist = 0.0
        var moving = 0L
        var maxV = 0.0
        var climb = 0.0
        var refEle = pts[0][2]
        val speeds = ArrayList<Float>()

        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val d = meters(a, b)
            dist += d
            val dt = (b[3] - a[3]).toLong()
            if (a[3] > 0.0 && b[3] > 0.0 && dt > 0L) {
                val v = d / (dt / 1000.0) * 3.6
                if (v <= MAX_PLAUSIBLE_KMH) {
                    if (v > maxV) maxV = v
                    speeds.add(v.toFloat())
                }
                // Selisih waktu panjang (jeda atau sinyal hilang) tidak dihitung sebagai waktu bergerak.
                if (v >= 1.0 && dt <= 120_000L) moving += dt
            }
            val e = b[2]
            if (!e.isNaN()) {
                if (refEle.isNaN()) refEle = e
                // Ambang 3 m supaya derau GPS tidak dihitung sebagai tanjakan.
                if (e - refEle >= 3.0) {
                    climb += e - refEle
                    refEle = e
                } else if (refEle - e >= 3.0) {
                    refEle = e
                }
            }
        }

        val first = pts.first()[3]
        val last = pts.last()[3]
        val elapsed = if (first > 0.0 && last > first) (last - first).toLong() else 0L
        val km = dist / 1000.0
        val avg = when {
            moving > 0L -> km / (moving / 3_600_000.0)
            elapsed > 0L -> km / (elapsed / 3_600_000.0)
            else -> 0.0
        }

        // Contoh kecepatan untuk grafik: paling banyak 150 titik.
        var samples: List<Float> = emptyList()
        var sampleSec = 0
        if (speeds.isNotEmpty() && elapsed > 0L) {
            val size = Math.ceil(speeds.size / 150.0).toInt().coerceAtLeast(1)
            val merged = ArrayList<Float>()
            var i = 0
            while (i < speeds.size) {
                val end = minOf(i + size, speeds.size)
                merged.add(speeds.subList(i, end).average().toFloat())
                i = end
            }
            samples = merged
            sampleSec = ((elapsed / 1000.0) / merged.size).roundToInt().coerceAtLeast(1)
        }

        val stamp = if (first > 0.0) Date(first.toLong()) else Date()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(stamp)
        return Result(pts, date, km, elapsed, moving, avg, maxV, climb, samples, sampleSec)
    }

    /** Rute ringkas untuk riwayat: paling banyak ±800 titik, sama seperti saat merekam. */
    internal fun encodeRoute(points: List<DoubleArray>): String {
        if (points.isEmpty()) return ""
        val step = maxOf(1, Math.ceil(points.size / 800.0).toInt())
        val sb = StringBuilder()
        var i = 0
        while (i < points.size) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(String.format(Locale.US, "%.5f,%.5f", points[i][0], points[i][1]))
            i += step
        }
        if ((points.size - 1) % step != 0) {
            val l = points[points.size - 1]
            sb.append(';').append(String.format(Locale.US, "%.5f,%.5f", l[0], l[1]))
        }
        return sb.toString()
    }

    /** Satu baris riwayat dengan format yang sama seperti aktivitas rekaman (lihat Rec). */
    fun recordLine(r: Result, mode: String, target: String): String = listOf(
        r.date,
        mode,
        String.format(Locale.US, "%.3f", r.km),
        r.elapsedMs.toString(),
        String.format(Locale.US, "%.1f", r.avgKmh),
        String.format(Locale.US, "%.1f", r.maxKmh),
        target,
        encodeRoute(r.points),
        "0",
        r.climbM.roundToInt().toString(),
        r.movingMs.toString(),
        r.sampleSec.toString(),
        r.samples.joinToString(",") { String.format(Locale.US, "%.1f", it) }
    ).joinToString("|")
}
