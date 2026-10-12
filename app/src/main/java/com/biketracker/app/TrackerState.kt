package com.biketracker.app

import android.content.Context
import android.location.Location
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Status pelacakan yang dipakai bersama oleh MainActivity, MapActivity, dan TrackingService.
 * Semua akses terjadi di main thread (callback GPS juga dikirim ke main looper).
 */
object TrackerState {
    /** Perkiraan langkah per meter untuk mode Jalan (tanpa sensor langkah). */
    const val STEPS_PER_METER = 1.3f

    var running = false
    var paused = false
    var mode = "Sepeda"
    /** Target dalam satuan mode: km untuk Sepeda/Lari, langkah untuk Jalan. */
    var target = 10
    var distanceMeters = 0f
    var speedKmh = 0f
    var maxSpeedKmh = 0f
    var elapsedBeforeRun = 0L
    var runStartedAt = 0L
    var climbM = 0f
    var gpsStatus = "Mencari GPS…"
    val route = mutableListOf<DoubleArray>()
    /** Waktu (detik) tiap kilometer penuh yang sudah diselesaikan. */
    val splits = mutableListOf<Int>()

    /** Contoh kecepatan (km/jam) tiap beberapa detik, untuk grafik di layar tracking. */
    val speedSamples = mutableListOf<Float>()

    var movingMs = 0L
    private var sampleEveryMs = 5000L
    private var lastSampleAt = 0L
    private var splitStartElapsed = 0L
    private var smoothKmh = 0f
    private var refAlt = Double.NaN
    private var lastLocation: Location? = null

    fun speedLimit(): Float = when (mode) {
        "Sepeda" -> 80f
        "Lari" -> 30f
        else -> 20f
    }

    fun steps(): Int = (distanceMeters * STEPS_PER_METER).roundToInt()

    /** Kemajuan menuju target, dalam satuan target (km atau langkah). */
    fun progressValue(): Float = if (mode == "Jalan") steps().toFloat() else distanceMeters / 1000f

    fun paceNowSec(): Int? = if (smoothKmh >= 1.5f) (3600f / smoothKmh).roundToInt() else null

    fun avgPaceSec(): Int? {
        val a = averageSpeed()
        return if (a >= 1.5f) (3600f / a).roundToInt() else null
    }

    fun bestSplitSec(): Int? = splits.minOrNull()

    /** Panjang sumbu waktu grafik, dalam menit. */
    fun chartMinutes(): Float = speedSamples.size * sampleEveryMs / 60000f

    fun start(targetValue: Int) {
        target = targetValue
        smoothKmh = 0f
        splits.clear()
        splitStartElapsed = 0L
        movingMs = 0L
        speedSamples.clear()
        sampleEveryMs = 5000L
        lastSampleAt = 0L
        distanceMeters = 0f
        speedKmh = 0f
        maxSpeedKmh = 0f
        elapsedBeforeRun = 0L
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        climbM = 0f
        refAlt = Double.NaN
        route.clear()
        gpsStatus = "Mencari GPS…"
        running = true
        paused = false
    }

    fun pause() {
        if (!running || paused) return
        elapsedBeforeRun += System.currentTimeMillis() - runStartedAt
        paused = true
        speedKmh = 0f
        smoothKmh = 0f
        gpsStatus = "Aktivitas dijeda"
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        smoothKmh = 0f
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        gpsStatus = "Menunggu lokasi…"
    }

    /** Menghentikan pelacakan; angka terakhir tetap tersimpan di sini untuk layar ringkasan. */
    fun finish(elapsed: Long) {
        elapsedBeforeRun = elapsed
        running = false
        paused = false
        speedKmh = 0f
        smoothKmh = 0f
    }

    fun currentElapsed(): Long =
        elapsedBeforeRun + if (running && !paused) System.currentTimeMillis() - runStartedAt else 0L

    /** Rata-rata kecepatan memakai waktu bergerak (berhenti di lampu merah tidak dihitung). */
    fun averageSpeed(): Float {
        val ms = if (movingMs >= 15000L) minOf(movingMs, currentElapsed().coerceAtLeast(1L)) else currentElapsed()
        val h = ms / 3600000f
        return if (h > 0f) (distanceMeters / 1000f) / h else 0f
    }

    /** Detik per interval contoh grafik (untuk disimpan bersama riwayat). */
    fun sampleSeconds(): Int = (sampleEveryMs / 1000L).toInt()

    fun calories(weightKg: Int): Int {
        val h = currentElapsed() / 3600000f
        if (h <= 0f) return 0
        val avg = averageSpeed()
        val met = when (mode) {
            "Sepeda" -> when {
                avg < 16f -> 4f
                avg < 19f -> 6f
                avg < 22f -> 8f
                avg < 26f -> 10f
                else -> 12f
            }
            "Lari" -> maxOf(6f, avg)
            else -> if (avg < 5.5f) 3.5f else 4.3f
        }
        return (met * weightKg * h).roundToInt()
    }

    fun onLocation(location: Location) {
        if (location.accuracy > 25f) {
            gpsStatus = "Mencari GPS…"
            return
        }
        gpsStatus = "GPS ±${location.accuracy.toInt()} m"
        if (!running || paused) return

        // Diam di tempat: GPS tetap bergoyang beberapa meter, jangan dihitung sebagai jarak.
        val still = location.hasSpeed() && location.speed < 0.5f

        val previous = lastLocation
        if (previous != null && location.time > previous.time && !still) {
            val seconds = (location.time - previous.time) / 1000f
            val delta = previous.distanceTo(location)
            val minStep = maxOf(1.5f, location.accuracy * 0.5f)
            if (seconds > 0f && delta >= minStep && delta / seconds * 3.6f <= speedLimit()) {
                distanceMeters += delta
                movingMs += (minOf(seconds, 5f) * 1000f).toLong()
            }
        }
        lastLocation = location

        val kmDone = (distanceMeters / 1000f).toInt()
        while (splits.size < kmDone) {
            val e = currentElapsed()
            splits.add(((e - splitStartElapsed) / 1000L).toInt())
            splitStartElapsed = e
        }

        val measured = if (location.hasSpeed() && !still) location.speed * 3.6f else 0f
        speedKmh = if (measured in 0f..speedLimit()) measured else 0f
        smoothKmh = if (smoothKmh <= 0f) speedKmh else smoothKmh * 0.6f + speedKmh * 0.4f
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

        val nowMs = currentElapsed()
        if (nowMs - lastSampleAt >= sampleEveryMs) {
            speedSamples.add(smoothKmh)
            lastSampleAt = nowMs
            if (speedSamples.size > 720) {
                // Gabungkan dua titik jadi satu supaya grafik tetap ringan pada aktivitas panjang.
                val merged = ArrayList<Float>()
                var i = 0
                while (i + 1 < speedSamples.size) {
                    merged.add((speedSamples[i] + speedSamples[i + 1]) / 2f)
                    i += 2
                }
                speedSamples.clear()
                speedSamples.addAll(merged)
                sampleEveryMs *= 2L
            }
        }

        val last = route.lastOrNull()
        var moved = true
        if (last != null) {
            val res = FloatArray(1)
            Location.distanceBetween(last[0], last[1], location.latitude, location.longitude, res)
            moved = !still && res[0] >= maxOf(3f, location.accuracy)
        }
        if (moved && route.size < 5000) route.add(doubleArrayOf(location.latitude, location.longitude))

        if (location.hasAltitude()) {
            val alt = location.altitude
            if (refAlt.isNaN()) {
                refAlt = alt
            } else if (alt - refAlt >= 3.0) {
                climbM += (alt - refAlt).toFloat()
                refAlt = alt
            } else if (refAlt - alt >= 3.0) {
                refAlt = alt
            }
        }
    }

    // ---------- Simpanan sesi (jaga-jaga kalau aplikasi dimatikan sistem) ----------

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun persist(c: Context) {
        if (!running) return
        val sb = StringBuilder()
        for ((i, p) in route.withIndex()) {
            if (i > 0) sb.append(';')
            sb.append(String.format(Locale.US, "%.6f,%.6f", p[0], p[1]))
        }
        sp(c).edit()
            .putBoolean("active", true)
            .putString("mode", mode)
            .putInt("target", target)
            .putString("splits", splits.joinToString(","))
            .putLong("splitStart", splitStartElapsed)
            .putLong("moving", movingMs)
            .putLong("sampleEvery", sampleEveryMs)
            .putString("samples", speedSamples.joinToString(",") { String.format(Locale.US, "%.1f", it) })
            .putFloat("dist", distanceMeters)
            .putFloat("max", maxSpeedKmh)
            .putLong("elapsed", currentElapsed())
            .putFloat("climb", climbM)
            .putString("route", sb.toString())
            .apply()
    }

    fun hasSaved(c: Context): Boolean = sp(c).getBoolean("active", false)

    fun clearSaved(c: Context) {
        sp(c).edit().clear().apply()
    }

    /** Memulihkan sesi terakhir dalam keadaan dijeda. */
    fun restore(c: Context): Boolean {
        val p = sp(c)
        if (!p.getBoolean("active", false)) return false
        mode = p.getString("mode", "Sepeda") ?: "Sepeda"
        target = p.getInt("target", 10)
        distanceMeters = p.getFloat("dist", 0f)
        maxSpeedKmh = p.getFloat("max", 0f)
        elapsedBeforeRun = p.getLong("elapsed", 0L)
        climbM = p.getFloat("climb", 0f)
        route.clear()
        for (s in (p.getString("route", "") ?: "").split(";")) {
            val q = s.split(",")
            val la = q.getOrNull(0)?.toDoubleOrNull()
            val lo = q.getOrNull(1)?.toDoubleOrNull()
            if (la != null && lo != null) route.add(doubleArrayOf(la, lo))
        }
        splits.clear()
        for (x in (p.getString("splits", "") ?: "").split(",")) {
            x.toIntOrNull()?.let { splits.add(it) }
        }
        splitStartElapsed = p.getLong("splitStart", elapsedBeforeRun)
        movingMs = p.getLong("moving", 0L)
        sampleEveryMs = p.getLong("sampleEvery", 5000L).coerceAtLeast(5000L)
        speedSamples.clear()
        for (x in (p.getString("samples", "") ?: "").split(",")) {
            x.toFloatOrNull()?.let { speedSamples.add(it) }
        }
        lastSampleAt = elapsedBeforeRun
        smoothKmh = 0f
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        refAlt = Double.NaN
        speedKmh = 0f
        gpsStatus = "Aktivitas dijeda"
        running = true
        paused = true
        return true
    }
}
