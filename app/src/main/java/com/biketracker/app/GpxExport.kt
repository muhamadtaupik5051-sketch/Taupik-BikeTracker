package com.biketracker.app

import java.util.Locale

/** Membuat berkas GPX dari jalur rekaman (tanpa stempel waktu per titik). */
object GpxExport {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun build(name: String, route: List<DoubleArray>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RideTrack Pro\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(esc(name)).append("</name>\n    <trkseg>\n")
        for (p in route) {
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", p[0], p[1]))
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    fun fileName(mode: String, date: String): String {
        val safe = (mode + "_" + date).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "RideTrack_$safe.gpx"
    }
}
