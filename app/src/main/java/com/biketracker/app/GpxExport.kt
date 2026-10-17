package com.biketracker.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Membuat berkas GPX dari jalur rekaman. Titik yang punya tinggi dan waktu (indeks 2 dan 3)
 * ditulis lengkap dengan <ele> dan <time>, supaya bisa diimpor ke Strava, Garmin Connect, dan sejenisnya.
 */
object GpxExport {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    fun build(name: String, route: List<DoubleArray>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Delaas\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        val firstTime = route.firstOrNull { it.size >= 4 && it[3] > 0.0 }?.get(3)?.toLong()
        if (firstTime != null) {
            sb.append("  <metadata><time>").append(iso(firstTime)).append("</time></metadata>\n")
        }
        sb.append("  <trk>\n    <name>").append(esc(name)).append("</name>\n    <trkseg>\n")
        for (p in route) {
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\"", p[0], p[1]))
            val hasEle = p.size >= 3 && !p[2].isNaN()
            val hasTime = p.size >= 4 && p[3] > 0.0
            if (!hasEle && !hasTime) {
                sb.append("/>\n")
                continue
            }
            sb.append(">")
            if (hasEle) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p[2]))
            if (hasTime) sb.append("<time>").append(iso(p[3].toLong())).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    fun fileName(mode: String, date: String): String {
        val safe = (mode + "_" + date).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "Delaas_$safe.gpx"
    }
}
