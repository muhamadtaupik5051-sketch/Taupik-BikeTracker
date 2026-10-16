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

    /** Jeda otomatis saat berhenti (lampu merah, istirahat); dinyalakan/dimatikan di Pengaturan. */
    @Volatile var autoPause = true
    @Volatile var autoPaused = false
    private var slowSinceMs = 0L
    var mode = "Sepeda"
    /** Target dalam satuan mode: km untuk Sepeda/Lari, langkah untuk Jalan. */
    var target = 10
    var distanceMeters = 0f
    var speedKmh = 0f
    var maxSpeedKmh = 0f
    var elapsedBeforeRun = 0L
    var runStartedAt = 0L
    /** Fix GPS terakhir yang akurat (dipakai halaman Survei). */
    var lastFix: Location? = null
    var climbM = 0f
    var gpsStatus = "Mencari GPS…"
    /** Garis panduan dari rute terpilih (lat, lon) dan status keluar rute. */
    var guide: List<DoubleArray> = emptyList()
    var offRoute = false
    var offDistM = 0f
    /** 1 = baru keluar rute, 2 = kembali ke rute. Dibaca lalu direset oleh TrackingService. */
    var routeEvent = 0

    /** Muat rute panduan (★) dari penyimpanan "survey". Format rencana: id|nama|road|profil|jarak|titik|loop|garis. */
    fun loadGuide(c: Context) {
        offRoute = false
        offDistM = 0f
        routeEvent = 0
        guide = emptyList()
        try {
            val sp = c.applicationContext.getSharedPreferences("survey", Context.MODE_PRIVATE)
            val act = sp.getString("active_plan", "") ?: ""
            if (act.isEmpty()) return
            val plan = (sp.getString("plans", "") ?: "").lines().filter { it.isNotBlank() }
                .map { it.split("|") }.firstOrNull { it.size >= 6 && it[0] == act } ?: return
            val geo = parsePoints(plan.getOrNull(7) ?: "")
            if (geo.size >= 2) {
                guide = geo
            } else if (plan.getOrNull(2) == "1") {
                // Rute lama tanpa bentuk jalan: garis lurus antar titik tidak cocok, jadi tidak dipakai untuk peringatan.
                guide = emptyList()
            } else {
                val wps = parsePoints(plan[5]).toMutableList()
                if (plan.getOrNull(6) == "1" && wps.size >= 2) wps.add(wps[0])
                guide = wps
            }
        } catch (e: Exception) {
            guide = emptyList()
        }
    }

    private fun parsePoints(s: String): List<DoubleArray> = s.split(";").mapNotNull { q ->
        val p = q.split(",")
        val la = p.getOrNull(0)?.toDoubleOrNull()
        val lo = p.getOrNull(1)?.toDoubleOrNull()
        if (la != null && lo != null) doubleArrayOf(la, lo) else null
    }

    /** Jarak terdekat (meter) dari titik ke garis panduan. */
    private fun distToGuide(lat: Double, lon: Double): Float {
        val k = Math.cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        for (i in 0 until guide.size - 1) {
            val ax = (guide[i][1] - lon) * k * 111320.0
            val ay = (guide[i][0] - lat) * 110540.0
            val bx = (guide[i + 1][1] - lon) * k * 111320.0
            val by = (guide[i + 1][0] - lat) * 110540.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val u = if (len2 <= 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val t = if (u < 0.0) 0.0 else if (u > 1.0) 1.0 else u
            val px = ax + t * dx
            val py = ay + t * dy
            val d = Math.sqrt(px * px + py * py)
            if (d < best) best = d
        }
        return best.toFloat()
    }

    private fun checkRoute(location: Location) {
        if (guide.size < 2) return
        val d = distToGuide(location.latitude, location.longitude)
        offDistM = d
        val limit = maxOf(50f, location.accuracy * 2f)
        if (!offRoute && d > limit) {
            offRoute = true
            routeEvent = 1
        } else if (offRoute && d < 35f) {
            offRoute = false
            routeEvent = 2
        }
    }
    /** Titik rute: [lat, lon, tinggi (NaN bila tidak ada), waktu epoch ms]. Kode lama cukup membaca indeks 0 dan 1. */
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

    /** Mengosongkan angka tampilan saat belum merekam (setelah aktivitas selesai atau dibuang). */
    fun clearDisplay() {
        if (running) return
        distanceMeters = 0f
        speedKmh = 0f
        maxSpeedKmh = 0f
        elapsedBeforeRun = 0L
        climbM = 0f
        movingMs = 0L
        smoothKmh = 0f
        splits.clear()
        speedSamples.clear()
        route.clear()
        splitStartElapsed = 0L
        lastSampleAt = 0L
        sampleEveryMs = 5000L
        lastLocation = null
        refAlt = Double.NaN
    }

    fun start(targetValue: Int) {
        bumpGeneration()
        resetFilter()
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
        offRoute = false
        routeEvent = 0
        running = true
        paused = false
        autoPaused = false
        slowSinceMs = 0L
    }

    fun pause() {
        if (!running || paused) return
        if (!autoPaused) elapsedBeforeRun += System.currentTimeMillis() - runStartedAt
        autoPaused = false
        slowSinceMs = 0L
        paused = true
        speedKmh = 0f
        smoothKmh = 0f
        gpsStatus = "Aktivitas dijeda"
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        smoothKmh = 0f
        resetFilter()
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        gpsStatus = "Menunggu lokasi…"
    }

    /** Menghentikan pelacakan; angka terakhir tetap tersimpan di sini untuk layar ringkasan. */
    fun finish(elapsed: Long) {
        bumpGeneration()
        elapsedBeforeRun = elapsed
        running = false
        paused = false
        autoPaused = false
        slowSinceMs = 0L
        speedKmh = 0f
        smoothKmh = 0f
    }

    fun currentElapsed(): Long =
        elapsedBeforeRun + if (running && !paused && !autoPaused) System.currentTimeMillis() - runStartedAt else 0L

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

    // ---------- Penghalusan posisi GPS (filter Kalman sederhana) ----------
    private var kLat = 0.0
    private var kLng = 0.0
    private var kVar = -1f
    private var kTime = 0L

    /** Filter dimulai dari awal lagi supaya titik pertama tidak tertarik posisi lama (aktivitas baru atau setelah jeda). */
    private fun resetFilter() {
        kVar = -1f
        kTime = 0L
    }

    /** Label kualitas sinyal berdasarkan akurasi dalam meter. */
    fun gpsLabel(acc: Float): String {
        val q = if (acc <= 8f) "bagus" else if (acc <= 15f) "cukup" else "lemah"
        return "GPS $q ±${acc.toInt()} m"
    }

    /** Kurangi goyangan titik GPS: titik yang akurasinya buruk diberi bobot kecil. */
    private fun smooth(raw: Location): Location {
        val acc = maxOf(raw.accuracy, 1f)
        if (kVar < 0f) {
            kLat = raw.latitude; kLng = raw.longitude; kVar = acc * acc; kTime = raw.time
            return raw
        }
        val dt = (raw.time - kTime) / 1000f
        if (dt > 0f) {
            val q = maxOf(3f, if (raw.hasSpeed()) raw.speed else 0f)
            kVar += dt * q * q
        }
        val k = kVar / (kVar + acc * acc)
        kLat += k * (raw.latitude - kLat)
        kLng += k * (raw.longitude - kLng)
        kVar = (1f - k) * kVar
        kTime = raw.time
        return Location(raw).apply { latitude = kLat; longitude = kLng }
    }

    /**
     * Jeda/lanjut otomatis berdasarkan kecepatan GPS. Mengembalikan true bila sedang jeda otomatis
     * (titik ini tidak dihitung). GPS tetap menyala supaya gerakan berikutnya terdeteksi.
     */
    private fun autoPauseStep(loc: Location): Boolean {
        if (!autoPause) {
            if (autoPaused) leaveAutoPause()
            slowSinceMs = 0L
            return false
        }
        if (!loc.hasSpeed()) return autoPaused
        val walk = mode == "Jalan"
        val stopBelow = if (walk) 0.4f else 0.8f
        val goAbove = if (walk) 0.8f else 1.5f
        val holdMs = if (walk) 10_000L else 8_000L
        val v = loc.speed
        val now = android.os.SystemClock.elapsedRealtime()
        if (!autoPaused) {
            if (v < stopBelow) {
                if (slowSinceMs == 0L) slowSinceMs = now
                if (now - slowSinceMs >= holdMs) {
                    elapsedBeforeRun += System.currentTimeMillis() - runStartedAt
                    autoPaused = true
                    speedKmh = 0f
                    smoothKmh = 0f
                    lastLocation = null
                    gpsStatus = "Jeda otomatis"
                }
            } else {
                slowSinceMs = 0L
            }
        } else if (v >= goAbove) {
            leaveAutoPause()
        }
        if (autoPaused) {
            speedKmh = 0f
            smoothKmh = 0f
            gpsStatus = "Jeda otomatis"
        }
        return autoPaused
    }

    private fun leaveAutoPause() {
        autoPaused = false
        slowSinceMs = 0L
        smoothKmh = 0f
        resetFilter()
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
    }

    fun onLocation(raw: Location) {
        // Abaikan titik lama (basi) yang kadang dikirim sistem.
        if (raw.elapsedRealtimeNanos > 0L) {
            val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - raw.elapsedRealtimeNanos) / 1_000_000L
            if (ageMs > 10_000L) return
        }
        var location: Location = raw
        // Akurasi nol/tidak valid bukanlah fix yang dapat dipercaya. Abaikan juga fix
        // yang terlalu kasar agar tidak mengotori jarak, kecepatan, grafik, dan rute.
        if (location.accuracy <= 0f || location.accuracy > 25f) {
            gpsStatus = "Mencari GPS…"
            return
        }
        gpsStatus = gpsLabel(location.accuracy)
        lastFix = location
        if (!running || paused) return
        if (autoPauseStep(raw)) return
        checkRoute(location)
        location = smooth(raw)

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
            // Rute panjang: jarak antar titik dilebarkan perlahan supaya perekaman tidak berhenti.
            val base = if (route.size > 8000) 15f else if (route.size > 4000) 8f else 3f
            moved = !still && res[0] >= maxOf(base, location.accuracy)
        }
        if (moved && route.size < 12000) {
            route.add(doubleArrayOf(
                location.latitude, location.longitude,
                if (location.hasAltitude()) location.altitude else Double.NaN,
                location.time.toDouble()
            ))
        }

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

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var generation = 0

    /** Setiap sesi baru atau selesai menaikkan angka ini, supaya simpanan latar belakang yang basi dibuang. */
    private fun bumpGeneration() {
        synchronized(lock) { generation++ }
    }

    /**
     * Simpan sesi ke penyimpanan. Data dicuplik di main thread, tetapi teks rute yang panjang
     * disusun dan ditulis di thread latar belakang supaya layar tidak tersendat.
     */
    fun persist(c: Context) {
        if (!running) return
        val ctx = c.applicationContext
        val g = synchronized(lock) { generation }
        val pts = ArrayList(route)
        val modeNow = mode
        val targetNow = target
        val splitsText = splits.joinToString(",")
        val splitStart = splitStartElapsed
        val moving = movingMs
        val every = sampleEveryMs
        val samplesText = speedSamples.joinToString(",") { String.format(Locale.US, "%.1f", it) }
        val dist = distanceMeters
        val maxKmh = maxSpeedKmh
        val elapsed = currentElapsed()
        val climb = climbM
        val pausedNow = paused
        io.execute {
            val sb = StringBuilder()
            for ((i, p) in pts.withIndex()) {
                if (i > 0) sb.append(';')
                sb.append(String.format(Locale.US, "%.6f,%.6f", p[0], p[1]))
                if (p.size >= 4) {
                    sb.append(',')
                    if (!p[2].isNaN()) sb.append(String.format(Locale.US, "%.1f", p[2]))
                    sb.append(',').append(p[3].toLong())
                }
            }
            val routeText = sb.toString()
            synchronized(lock) {
                if (g != generation) return@execute
                sp(ctx).edit()
                    .putBoolean("active", true)
                    .putBoolean("paused", pausedNow)
                    .putString("mode", modeNow)
                    .putInt("target", targetNow)
                    .putString("splits", splitsText)
                    .putLong("splitStart", splitStart)
                    .putLong("moving", moving)
                    .putLong("sampleEvery", every)
                    .putString("samples", samplesText)
                    .putFloat("dist", dist)
                    .putFloat("max", maxKmh)
                    .putLong("elapsed", elapsed)
                    .putFloat("climb", climb)
                    .putString("route", routeText)
                    .apply()
            }
        }
    }

    fun hasSaved(c: Context): Boolean = sp(c).getBoolean("active", false)

    /** Apakah sesi tersimpan terakhir dalam keadaan dijeda (dipakai saat service dihidupkan ulang sistem). */
    fun savedPaused(c: Context): Boolean = sp(c).getBoolean("paused", false)

    fun clearSaved(c: Context) {
        sp(c).edit().clear().apply()
    }

    /** Memulihkan sesi terakhir dalam keadaan dijeda. */
    fun restore(c: Context): Boolean {
        val p = sp(c)
        if (!p.getBoolean("active", false)) return false
        bumpGeneration()
        resetFilter()
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
            if (la != null && lo != null) {
                val ele = q.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN
                val tm = q.getOrNull(3)?.toLongOrNull()
                route.add(if (tm != null) doubleArrayOf(la, lo, ele, tm.toDouble()) else doubleArrayOf(la, lo))
            }
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
        autoPaused = false
        slowSinceMs = 0L
        autoPause = c.getSharedPreferences("bike_history", Context.MODE_PRIVATE).getBoolean("auto_pause", true)
        return true
    }
}
