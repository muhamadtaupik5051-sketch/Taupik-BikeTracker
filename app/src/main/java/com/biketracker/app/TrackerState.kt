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
