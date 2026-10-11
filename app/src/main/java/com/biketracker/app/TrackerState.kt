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
    var running = false
    var paused = false
    var mode = "Sepeda"
    var targetKm = 10
    var distanceMeters = 0f
    var speedKmh = 0f
    var maxSpeedKmh = 0f
    var elapsedBeforeRun = 0L
    var runStartedAt = 0L
    var climbM = 0f
    var gpsStatus = "Mencari GPS…"
    val route = mutableListOf<DoubleArray>()

    private var refAlt = Double.NaN
    private var lastLocation: Location? = null

    fun speedLimit(): Float = when (mode) {
        "Sepeda" -> 80f
        "Lari" -> 30f
        else -> 20f
    }

    fun start(target: Int) {
        targetKm = target
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
        gpsStatus = "Aktivitas dijeda"
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
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
    }

    fun currentElapsed(): Long =
        elapsedBeforeRun + if (running && !paused) System.currentTimeMillis() - runStartedAt else 0L

    fun averageSpeed(): Float {
        val h = currentElapsed() / 3600000f
        return if (h > 0f) (distanceMeters / 1000f) / h else 0f
    }

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

        val previous = lastLocation
        if (previous != null && location.time > previous.time) {
            val seconds = (location.time - previous.time) / 1000f
            val delta = previous.distanceTo(location)
            if (seconds > 0f && delta in 0.5f..100f && delta / seconds * 3.6f <= speedLimit()) {
                distanceMeters += delta
            }
        }
        lastLocation = location

        val measured = if (location.hasSpeed()) location.speed * 3.6f else 0f
        speedKmh = if (measured in 0f..speedLimit()) measured else 0f
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

        val last = route.lastOrNull()
        var moved = true
        if (last != null) {
            val res = FloatArray(1)
            Location.distanceBetween(last[0], last[1], location.latitude, location.longitude, res)
            moved = res[0] >= 3f
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
            .putInt("target", targetKm)
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
        targetKm = p.getInt("target", 10)
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
