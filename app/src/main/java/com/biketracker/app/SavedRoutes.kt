package com.biketracker.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale

/** Satu rute tersimpan beserta keterangan tambahan (aktivitas, tanjakan, favorit, berapa kali diikuti). */
internal class SavedRoute(
    val id: String,
    val name: String,
    val road: Boolean,
    val profile: String,
    val distM: Double,
    val wps: List<DoubleArray>,
    val loop: Boolean,
    val geo: List<DoubleArray>,
    val mode: String,
    /** Tanjakan dalam meter; -1 kalau tidak diketahui. */
    val climb: Int,
    val fav: Boolean,
    val count: Int,
    val last: Long,
    val alts: List<Int>
) {
    val km: Double get() = distM / 1000.0

    /** Titik untuk digambar: jalur penuh kalau ada, kalau tidak titik rencana. */
    val points: List<DoubleArray> get() = if (geo.size >= 2) geo else wps

    /** Perkiraan waktu tempuh dalam menit menurut jenis aktivitas. */
    fun etaMin(): Int = Math.round(km / SavedRoutes.speedKmh(mode) * 60.0).toInt()
}

/**
 * Rute tersimpan memakai penyimpanan rencana yang sama dengan menu Peta ("survey" > "plans"),
 * jadi rute dari peta, impor GPX, dan riwayat sama-sama bisa dipakai sebagai garis panduan.
 * Format baris: id|nama|road|profil|jarakM|titik rencana|loop|jalur.
 * Keterangan tambahan disimpan terpisah di "route_meta": id -> aktivitas|tanjakan|favorit|diikuti|terakhir|tinggi.
 */
internal object SavedRoutes {
    private const val LIMIT = 20

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("survey", Context.MODE_PRIVATE)
    private fun mp(c: Context) = c.applicationContext.getSharedPreferences("route_meta", Context.MODE_PRIVATE)

    fun speedKmh(mode: String): Double = when (mode) {
        "Sepeda" -> 16.0
        "Motor" -> 40.0
        "Lari" -> 9.0
        else -> 5.0
    }

    fun parsePts(s: String): List<DoubleArray> = s.split(";").mapNotNull { q ->
        val p = q.split(",")
        val la = p.getOrNull(0)?.toDoubleOrNull()
        val lo = p.getOrNull(1)?.toDoubleOrNull()
        if (la != null && lo != null) doubleArrayOf(la, lo) else null
    }

    private fun fmtPts(p: List<DoubleArray>): String =
        p.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it[0], it[1]) }

    private fun clean(name: String): String =
        name.replace("|", " ").replace("\n", " ").trim().ifEmpty { "Rute" }

    /** Ambil paling banyak [max] titik secara merata (titik awal dan akhir selalu ikut). */
    fun <T> thin(p: List<T>, max: Int): List<T> {
        if (p.size <= max || max < 2) return p
        val out = ArrayList<T>(max)
        for (i in 0 until max) {
            out.add(p[(i.toLong() * (p.size - 1) / (max - 1)).toInt()])
        }
        return out
    }

    private fun lines(c: Context): MutableList<List<String>> =
        (sp(c).getString("plans", "") ?: "").lines().filter { it.isNotBlank() }
            .map { it.split("|") }.filter { it.size >= 6 }.toMutableList()

    private fun write(c: Context, list: List<List<String>>) {
        sp(c).edit().putString("plans", list.joinToString("\n") { it.joinToString("|") }).apply()
    }

    private fun putMeta(
        c: Context, id: String, mode: String, climb: Int, fav: Boolean,
        count: Int, last: Long, alts: List<Int>
    ) {
        val v = listOf(
            mode, climb.toString(), if (fav) "1" else "0", count.toString(),
            last.toString(), alts.joinToString(",")
        ).joinToString("|")
        mp(c).edit().putString(id, v).apply()
    }

    fun all(c: Context): List<SavedRoute> = lines(c).map { p ->
        val m = (mp(c).getString(p[0], "") ?: "").split("|")
        val mode = m.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: (if (p[3] == "bike") "Sepeda" else "Jalan")
        SavedRoute(
            p[0], p[1], p[2] == "1", p[3], p[4].toDoubleOrNull() ?: 0.0,
            parsePts(p[5]), p.getOrNull(6) == "1", parsePts(p.getOrNull(7) ?: ""),
            mode, m.getOrNull(1)?.toIntOrNull() ?: -1, m.getOrNull(2) == "1",
            m.getOrNull(3)?.toIntOrNull() ?: 0, m.getOrNull(4)?.toLongOrNull() ?: 0L,
            (m.getOrNull(5) ?: "").split(",").mapNotNull { it.toIntOrNull() }
        )
    }

    fun find(c: Context, id: String): SavedRoute? = all(c).firstOrNull { it.id == id }

    fun activeId(c: Context): String = sp(c).getString("active_plan", "") ?: ""

    fun toggleFav(c: Context, id: String) {
        val r = find(c, id) ?: return
        putMeta(c, id, r.mode, r.climb, !r.fav, r.count, r.last, r.alts)
    }

    /** Jadikan garis panduan dan catat bahwa rute ini diikuti. */
    fun activate(c: Context, id: String) {
        sp(c).edit().putString("active_plan", id).apply()
        val r = find(c, id) ?: return
        putMeta(c, id, r.mode, r.climb, r.fav, r.count + 1, System.currentTimeMillis(), r.alts)
    }

    fun clearActive(c: Context) {
        sp(c).edit().putString("active_plan", "").apply()
        if (TrackerState.running) TrackerState.loadGuide(c)
    }

    fun rename(c: Context, id: String, name: String) {
        val list = lines(c)
        val i = list.indexOfFirst { it[0] == id }
        if (i < 0) return
        val row = list[i].toMutableList()
        row[1] = clean(name)
        list[i] = row
        write(c, list)
    }

    fun delete(c: Context, id: String) {
        val list = lines(c)
        list.removeAll { it[0] == id }
        write(c, list)
        mp(c).edit().remove(id).apply()
        if (activeId(c) == id) {
            sp(c).edit().putString("active_plan", "").apply()
            if (TrackerState.running) TrackerState.loadGuide(c)
        }
    }

    /** Catat jenis aktivitas untuk rute yang baru dibuat dari peta. */
    fun setMode(c: Context, id: String, mode: String) {
        val r = find(c, id) ?: return
        putMeta(c, id, mode, r.climb, r.fav, r.count, r.last, r.alts)
    }

    /**
     * Simpan jalur (lat, lon, tinggi, waktu) sebagai rute baru. Mengembalikan id rute, atau null kalau titiknya kurang.
     * [climb] dalam meter, -1 kalau tidak diketahui.
     */
    fun addFromPoints(c: Context, name: String, mode: String, pts: List<DoubleArray>, distKm: Double, climb: Int): String? {
        if (pts.size < 2) return null
        val id = System.currentTimeMillis().toString()
        val profile = if (mode == "Sepeda" || mode == "Motor") "bike" else "foot"
        val flat = pts.map { doubleArrayOf(it[0], it[1]) }
        val list = lines(c)
        list.add(0, listOf(
            id, clean(name), "0", profile, (distKm * 1000.0).toInt().toString(),
            fmtPts(thin(flat, 80)), "0", fmtPts(thin(flat, 300))
        ))
        write(c, list.take(LIMIT))
        val ele = pts.filter { it.size >= 3 && !it[2].isNaN() }.map { Math.round(it[2]).toInt() }
        val alts: List<Int> = if (ele.size >= 2) thin(ele, 40) else emptyList()
        putMeta(c, id, mode, climb, false, 0, 0L, alts)
        return id
    }
}

/** Grafik kecil profil tinggi (area) untuk layar detail rute. */
internal class ProfileView(ctx: Context, private val t: AppTheme) : View(ctx) {
    var values: List<Int> = emptyList()
    var accent: Int = t.accent

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = Path()
    private val ln = Path()

    override fun onDraw(c: Canvas) {
        if (values.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        var lo = values[0]
        var hi = values[0]
        for (v in values) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = Math.max(hi - lo, 10).toFloat()
        val top = 16f * d
        val bottom = 4f * d
        val ph = h - top - bottom
        area.reset()
        ln.reset()
        for (i in values.indices) {
            val x = w * i / (values.size - 1).toFloat()
            val y = top + ph * (1f - (values[i] - lo) / span)
            if (i == 0) {
                ln.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                ln.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(w, h)
        area.close()
        fill.color = accent
        fill.alpha = 60
        c.drawPath(area, fill)
        line.color = accent
        line.strokeWidth = 2f * d
        c.drawPath(ln, line)
        txt.color = t.muted
        txt.textSize = 10f * d
        c.drawText("$hi m", 2f * d, 11f * d, txt)
    }
}
