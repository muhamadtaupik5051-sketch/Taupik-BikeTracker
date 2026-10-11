package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var t: AppTheme
    private lateinit var locationManager: LocationManager

    private var mode = "Sepeda"
    private var lastLocation: Location? = null
    private var distanceMeters = 0f
    private var speedKmh = 0f
    private var maxSpeedKmh = 0f
    private var averageSpeedKmh = 0f
    private var elapsedBeforeRun = 0L
    private var runStartedAt = 0L
    private var running = false
    private var paused = false

    private lateinit var speedText: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var gpsText: TextView
    private lateinit var averageSpeedText: TextView
    private lateinit var maxSpeedText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy {
        getSharedPreferences("bike_history", MODE_PRIVATE)
    }

    private val timer = object : Runnable {
        override fun run() {
            if (running && !paused) {
                timeText.text = formatTime(currentElapsed())
                updateAverageSpeed()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.accuracy > 25f) {
                gpsText.text = "GPS • Mencari sinyal akurat..."
                return
            }

            gpsText.text =
                "GPS TERKUNCI • Akurasi ±${location.accuracy.toInt()} m"

            if (!running || paused) return

            val previous = lastLocation

            if (previous != null && location.time > previous.time) {
                val seconds = (location.time - previous.time) / 1000f
                val delta = previous.distanceTo(location)

                val limit = when (mode) {
                    "Sepeda" -> 80f
                    "Lari" -> 30f
                    else -> 20f
                }

                if (seconds > 0f && delta in 0.5f..100f &&
                    delta / seconds * 3.6f <= limit
                ) {
                    distanceMeters += delta
                }
            }

            lastLocation = location

            val measuredSpeed =
                if (location.hasSpeed()) location.speed * 3.6f else 0f

            val limit = when (mode) {
                "Sepeda" -> 80f
                "Lari" -> 30f
                else -> 20f
            }

            speedKmh = if (measuredSpeed in 0f..limit) measuredSpeed else 0f

            if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

            updateDashboardStats()
        }

        override fun onProviderEnabled(provider: String) {
            gpsText.text = "GPS aktif • Menunggu lokasi..."
        }

        override fun onProviderDisabled(provider: String) {
            gpsText.text = "GPS mati • Aktifkan lokasi HP"
            speedKmh = 0f
            if (::speedText.isInitialized) speedText.text = "0.0"
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        applyTheme()
        showHome()
    }

    private fun applyTheme() {
        t = Themes.current(this)
        Themes.styleWindow(this, t)
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun box(color: Int, r: Int = 18, line: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (line) setStroke(dp(1), t.surface2)
    }

    private fun card(color: Int = t.surface, pad: Int = 16, r: Int = 20) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = box(color, r, true)
    }

    private fun LinearLayout.add(v: View, top: Int = 0, weight: Float = 0f, height: Int = -2) {
        addView(v, LinearLayout.LayoutParams(if (weight > 0f) 0 else -1, height, weight).apply {
            topMargin = dp(top)
        })
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(20))
        setBackgroundColor(t.bg)
    }

    private fun btn(label: String, fill: Int, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onColor(fill))
        background = box(fill, 16, fill == t.surface)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun show(tab: Int, body: LinearLayout) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tab >= 0) outer.addView(navBar(tab))
        setContentView(outer)
        outer.requestApplyInsets()
    }

    private fun navBar(sel: Int): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(t.surface, 0)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf("⌂" to "Beranda", "⌖" to "Peta", "▤" to "Riwayat", "⚙" to "Atur")
            .forEachIndexed { i, (ic, label) ->
                val c = if (i == sel) t.accent else t.muted
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener {
                        when (i) { 0 -> showHome(); 1 -> openMap(); 2 -> showHistory(); else -> showSettings() }
                    }
                }
                cell.addView(tv(ic, 20f, c, true).apply { gravity = Gravity.CENTER })
                cell.addView(tv(label, 10f, c, i == sel).apply { gravity = Gravity.CENTER })
                bar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
        return bar
    }

    private fun title(r: LinearLayout, small: String, big: String) {
        r.add(tv(small, 11f, t.accent, true))
        r.add(tv(big, 28f, t.text, true), 2)
    }

    private fun icon(m: String) = when (m) { "Sepeda" -> "🚴"; "Lari" -> "🏃"; else -> "🚶" }

    private fun records(): List<List<String>> =
        (prefs.getString("records", "") ?: "").lines().filter { it.isNotBlank() }
            .map { it.split("|") }.filter { it.size >= 6 }

    private fun statCard(label: String, initial: String, accent: Int = t.text): Pair<View, TextView> {
        val box = card(t.surface, 14, 18)
        box.addView(tv(label.uppercase(Locale.getDefault()), 10f, t.muted, true))
        val value = tv(initial, 19f, accent, true)
        box.add(value, 6)
        return Pair(box, value)
    }

    private fun statRow(r: LinearLayout, l: Pair<View, TextView>, rt: Pair<View, TextView>, top: Int = 10) {
        val row = LinearLayout(this)
        row.addView(l.first, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rt.first, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        r.add(row, top)
    }

    // ---------- Screens ----------
    private fun showHome() {
        val r = column()
        val recs = records()
        val totalKm = recs.sumOf { (it[2].toDoubleOrNull() ?: 0.0) }

        title(r, "R I D E T R A C K   P R O", "Siap gowes hari ini?")

        val hero = card(t.accent, 20, 24)
        val on = onColor(t.accent)
        hero.addView(tv("TOTAL JARAK TEMPUH", 11f, on, true))
        hero.add(tv(String.format(Locale.US, "%.1f km", totalKm), 38f, on, true), 4)
        hero.add(tv("${recs.size} perjalanan tersimpan", 13f, on), 2)
        r.add(hero, 18)

        r.add(tv("PILIH AKTIVITAS", 11f, t.muted, true), 20)
        val chips = LinearLayout(this)
        listOf("Sepeda", "Lari", "Jalan").forEachIndexed { i, m ->
            val sel = m == mode
            val chip = tv(icon(m) + "  " + m, 14f, if (sel) onColor(t.accent) else t.text, true).apply {
                gravity = Gravity.CENTER
                background = box(if (sel) t.accent else t.surface, 14, !sel)
                setOnClickListener { if (!running) { mode = m; showHome() } }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        r.add(chips, 8)

        r.add(btn("▶   MULAI AKTIVITAS", t.good) { startTracking() }, 18, 0f, dp(62))

        val row = LinearLayout(this)
        row.addView(btn("⌖  Peta GPS", t.surface) { openMap() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(btn("▤  Riwayat", t.surface) { showHistory() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(10) })
        r.add(row, 10)

        if (recs.isNotEmpty()) {
            r.add(tv("TERAKHIR", 11f, t.muted, true), 22)
            for (f in recs.take(2)) {
                val c = card(t.surface, 14, 16)
                c.addView(tv("${icon(f[1])}  ${f[1]}  •  ${f[2]} km", 15f, t.text, true))
                c.add(tv(f[0], 12f, t.muted), 3)
                r.add(c, 8)
            }
        }
        show(0, r)
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
            return
        }

        distanceMeters = 0f
        speedKmh = 0f
        maxSpeedKmh = 0f
        averageSpeedKmh = 0f
        elapsedBeforeRun = 0L
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        running = true
        paused = false

        showTracking()
        requestGpsUpdates()
        handler.removeCallbacks(timer)
        handler.post(timer)
    }

    private fun requestGpsUpdates() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) return

            gpsText.text = "GPS aktif • Menunggu lokasi..."
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                gpsListener
            )
        } catch (_: Exception) {
            gpsText.text = "GPS tidak tersedia. Periksa pengaturan lokasi."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() &&
                grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            ) {
                startTracking()
            } else {
                Toast.makeText(
                    this,
                    "Izin lokasi diperlukan untuk melacak perjalanan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showTracking() {
        val r = column()

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("${icon(mode)}  $mode", 14f, onColor(t.accent), true).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7)); background = box(t.accent, 20)
        })
        gpsText = tv("GPS aktif • Menunggu lokasi...", 11f, t.good, true).apply { gravity = Gravity.END }
        top.addView(gpsText, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        r.addView(top)

        val sp = card(t.surface2, 20, 28).apply { gravity = Gravity.CENTER_HORIZONTAL }
        sp.addView(tv("KECEPATAN SAAT INI", 11f, t.muted, true).apply { gravity = Gravity.CENTER })
        speedText = tv("0.0", 76f, t.text, true).apply { gravity = Gravity.CENTER }
        sp.add(speedText, 6)
        sp.addView(tv("KM/JAM", 13f, t.accent, true).apply { gravity = Gravity.CENTER })
        r.add(sp, 16)

        val distance = statCard("Jarak", "0.00 km", t.accent)
        val time = statCard("Durasi", "00:00:00")
        distanceText = distance.second; timeText = time.second
        statRow(r, distance, time, 14)

        val average = statCard("Rata-rata", "0.0 km/jam", t.good)
        val maximum = statCard("Maksimum", "0.0 km/jam", t.accent)
        averageSpeedText = average.second; maxSpeedText = maximum.second
        statRow(r, average, maximum)

        val row = LinearLayout(this)
        row.addView(btn(if (paused) "▶  Lanjut" else "Ⅱ  Jeda", t.accent) {
            if (paused) resumeTracking() else pauseTracking()
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        row.addView(btn("■  Selesai", t.bad) { finishTracking() },
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(10) })
        r.add(row, 18)
        r.add(btn("⌖  Lihat Peta GPS", t.surface) { openMap() }, 10, 0f, dp(50))
        show(-1, r)
    }

    private fun openMap() {
        try {
            startActivity(Intent(this, MapActivity::class.java))
        } catch (_: Exception) {
            Toast.makeText(this, "Halaman peta belum siap.", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentElapsed(): Long {
        return elapsedBeforeRun +
            if (running && !paused) {
                System.currentTimeMillis() - runStartedAt
            } else {
                0L
            }
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            seconds / 3600,
            (seconds % 3600) / 60,
            seconds % 60
        )
    }

    private fun updateAverageSpeed() {
        val hours = currentElapsed() / 3600000f
        averageSpeedKmh = if (hours > 0f) {
            (distanceMeters / 1000f) / hours
        } else {
            0f
        }

        if (::averageSpeedText.isInitialized) {
            averageSpeedText.text =
                String.format(Locale.US, "%.1f km/jam", averageSpeedKmh)
        }
    }

    private fun updateDashboardStats() {
        if (::speedText.isInitialized) {
            speedText.text = String.format(Locale.US, "%.1f", speedKmh)
        }
        if (::distanceText.isInitialized) {
            distanceText.text = String.format(
                Locale.US, "%.2f km", distanceMeters / 1000f
            )
        }
        if (::timeText.isInitialized) {
            timeText.text = formatTime(currentElapsed())
        }
        if (::maxSpeedText.isInitialized) {
            maxSpeedText.text =
                String.format(Locale.US, "%.1f km/jam", maxSpeedKmh)
        }
        updateAverageSpeed()
    }

    private fun pauseTracking() {
        if (!running || paused) return

        elapsedBeforeRun += System.currentTimeMillis() - runStartedAt
        paused = true
        speedKmh = 0f
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        showTracking()
        updateDashboardStats()
        gpsText.text = "Aktivitas dijeda"
    }

    private fun resumeTracking() {
        if (!running || !paused) return

        paused = false
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        showTracking()
        requestGpsUpdates()
        handler.removeCallbacks(timer)
        handler.post(timer)
        updateDashboardStats()
    }

    private fun stopGpsUpdates() {
        try {
            locationManager.removeUpdates(gpsListener)
        } catch (_: Exception) {
        }
    }

    private fun finishTracking() {
        if (!running) return

        val elapsed = currentElapsed()
        running = false
        paused = false
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        speedKmh = 0f
        updateAverageSpeed()
        saveActivity(elapsed)
        showSummary(elapsed)
    }

    private fun saveActivity(elapsed: Long) {
        val date = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.getDefault()
        ).format(Date())

        val record = listOf(
            date,
            mode,
            String.format(Locale.US, "%.3f", distanceMeters / 1000f),
            elapsed.toString(),
            String.format(Locale.US, "%.1f", averageSpeedKmh),
            String.format(Locale.US, "%.1f", maxSpeedKmh)
        ).joinToString("|")

        val oldHistory = prefs.getString("records", "") ?: ""
        val updated = if (oldHistory.isEmpty()) record else "$record\n$oldHistory"
        prefs.edit().putString("records", updated).apply()
    }

    private fun showSummary(elapsed: Long) {
        val r = column()
        title(r, "RINGKASAN AKTIVITAS", "Kerja bagus! 🎉")

        val hero = card(t.accent, 20, 24)
        val on = onColor(t.accent)
        hero.addView(tv("✓  PERJALANAN TERSIMPAN", 11f, on, true))
        hero.add(tv("${icon(mode)}  $mode", 18f, on, true), 10)
        hero.add(tv(String.format(Locale.US, "%.2f km", distanceMeters / 1000f), 42f, on, true), 2)
        r.add(hero, 18)

        statRow(r, statCard("Durasi", formatTime(elapsed)),
            statCard("Rata-rata", String.format(Locale.US, "%.1f km/jam", averageSpeedKmh), t.good), 14)
        statRow(r, statCard("Kecepatan maks.", String.format(Locale.US, "%.1f km/jam", maxSpeedKmh), t.accent),
            statCard("Jarak", String.format(Locale.US, "%.2f km", distanceMeters / 1000f)))

        r.add(btn("⌂   KEMBALI KE BERANDA", t.good) { showHome() }, 20, 0f, dp(54))
        r.add(btn("▤   LIHAT RIWAYAT", t.surface) { showHistory() }, 10, 0f, dp(50))
        show(0, r)
    }

    private fun showHistory() {
        val r = column()
        title(r, "RIWAYAT", "Perjalananmu")
        val recs = records()

        if (recs.isEmpty()) {
            val e = card(t.surface, 24, 20).apply { gravity = Gravity.CENTER }
            e.addView(tv("Belum ada perjalanan", 18f, t.text, true).apply { gravity = Gravity.CENTER })
            e.add(tv("Perjalanan yang diselesaikan akan muncul di sini.", 13f, t.muted).apply { gravity = Gravity.CENTER }, 6)
            r.add(e, 18)
        } else {
            r.add(tv("${recs.size} perjalanan tersimpan", 13f, t.accent, true), 8)
            for (f in recs) {
                val c = card(t.surface, 16, 18)
                val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                left.addView(tv("${icon(f[1])}  ${f[1]}", 16f, t.text, true))
                left.add(tv(f[0], 12f, t.muted), 2)
                head.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(tv("${f[2]} km", 20f, t.accent, true))
                c.addView(head)
                val el = f[3].toLongOrNull() ?: 0L
                c.add(tv("⏱ ${formatTime(el)}    ⌀ ${f[4]} km/jam    ▲ ${f[5]} km/jam", 12f, t.muted), 10)
                r.add(c, 10)
            }
            r.add(btn("Hapus semua riwayat", t.bad) {
                prefs.edit().remove("records").apply(); showHistory()
            }, 16, 0f, dp(50))
        }
        show(2, r)
    }

    private fun showSettings() {
        val r = column()
        title(r, "PENGATURAN", "Tampilan")
        r.add(tv("TEMA WARNA", 11f, t.muted, true), 16)

        Themes.all.forEachIndexed { i, th ->
            val sel = i == Themes.index(this)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(th.bg); cornerRadius = dp(16).toFloat()
                    setStroke(dp(if (sel) 3 else 1), if (sel) th.accent else th.surface2)
                }
                setOnClickListener { Themes.save(this@MainActivity, i); applyTheme(); showSettings() }
            }
            row.addView(tv(th.name, 15f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            for (c in listOf(th.accent, th.good, th.surface, th.bad)) {
                row.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), 0x33888888)
                    }
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(6) })
            }
            if (sel) row.addView(tv("  ✓", 16f, th.accent, true))
            r.add(row, 10)
        }

        r.add(tv("LAINNYA", 11f, t.muted, true), 22)
        val on = Themes.keepOn(this)
        r.add(btn("Layar tetap menyala saat tracking: " + (if (on) "AKTIF" else "MATI"), t.surface) {
            Themes.setKeepOn(this, !on); applyTheme(); showSettings()
        }, 8, 0f, dp(54))

        r.add(tv("RideTrack Pro V2 • Tanpa iklan • Data tersimpan di HP", 11f, t.muted).apply {
            gravity = Gravity.CENTER
        }, 22)
        show(3, r)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        super.onDestroy()
    }
}
