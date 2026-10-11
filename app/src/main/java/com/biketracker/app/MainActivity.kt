
package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
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

    private val bg = Color.rgb(5, 15, 34)
    private val surface = Color.rgb(12, 30, 58)
    private val surface2 = Color.rgb(17, 42, 77)
    private val cyan = Color.rgb(0, 184, 255)
    private val green = Color.rgb(39, 210, 130)
    private val red = Color.rgb(244, 76, 88)
    private val white = Color.rgb(245, 249, 255)
    private val muted = Color.rgb(151, 174, 202)

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
        window.statusBarColor = bg
        window.navigationBarColor = bg
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        showHome()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun column(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(24))
            setBackgroundColor(bg)
        }

    private fun showScreen(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        }
        setContentView(scroll)
    }

    private fun gap(root: LinearLayout, height: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun panel(color: Int = surface, radius: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun makeButton(
        label: String,
        color: Int,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setTextColor(white)
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = panel(color, 15)
            stateListAnimator = null
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun addHeader(root: LinearLayout, subtitle: String) {
        root.addView(makeText("R I D E T R A C K", 12f, cyan, true))
        gap(root, 5)
        root.addView(makeText("RideTrack Pro", 29f, white, true))
        gap(root, 4)
        root.addView(makeText(subtitle, 14f, muted))
    }

    private fun statCard(
        label: String,
        initial: String,
        accent: Int = white
    ): Pair<View, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = panel(surface, 17)
        }

        box.addView(makeText(label.uppercase(Locale.getDefault()), 10f, muted, true))
        gap(box, 7)

        val value = makeText(initial, 18f, accent, true)
        box.addView(value)

        return Pair(box, value)
    }

    private fun addStatRow(
        root: LinearLayout,
        left: Pair<View, TextView>,
        right: Pair<View, TextView>
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(left.first, LinearLayout.LayoutParams(0, dp(88), 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(right.first, LinearLayout.LayoutParams(0, dp(88), 1f))
        root.addView(row)
    }

    private fun showHome() {
        val root = column()

        addHeader(root, "GPS CYCLING & ACTIVITY TRACKER")
        gap(root, 24)

        val welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = panel(surface2, 22)
        }

        welcome.addView(makeText("SIAP UNTUK BERGERAK?", 11f, cyan, true))
        gap(welcome, 7)
        welcome.addView(makeText("Setiap perjalanan\npunya cerita.", 24f, white, true))
        gap(welcome, 8)
        welcome.addView(makeText("Pantau performa dan rekam perjalananmu.", 13f, muted))
        root.addView(welcome)

        gap(root, 22)
        root.addView(makeText("PILIH AKTIVITAS", 12f, muted, true))
        gap(root, 10)

        for (activityMode in listOf("Sepeda", "Lari", "Jalan")) {
            root.addView(makeButton(
                (if (mode == activityMode) "✓  " else "     ") + activityMode,
                if (mode == activityMode) cyan else surface
            ) {
                if (!running) {
                    mode = activityMode
                    showHome()
                }
            })
        }

        gap(root, 14)
        root.addView(makeText("MULAI PERJALANAN", 12f, muted, true))
        gap(root, 8)

        root.addView(makeButton("▶   MULAI AKTIVITAS", green) {
            startTracking()
        })

        root.addView(makeButton("⌖   BUKA PETA GPS", surface2) {
            openMap()
        })

        root.addView(makeButton("▤   RIWAYAT PERJALANAN", surface) {
            showHistory()
        })

        gap(root, 12)
        root.addView(makeText("TRACK YOUR RIDE • REACH YOUR GOAL", 11f, muted, true).apply {
            gravity = Gravity.CENTER
        })

        showScreen(root)
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
        val root = column()

        root.addView(makeText("●  AKTIVITAS BERLANGSUNG", 12f, green, true))
        gap(root, 5)
        root.addView(makeText(mode, 27f, white, true))
        gap(root, 18)

        val speedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
            background = panel(surface2, 24)
        }

        speedPanel.addView(makeText("KECEPATAN SAAT INI", 12f, muted, true))
        gap(speedPanel, 10)

        speedText = makeText("0.0", 66f, white, true).apply {
            gravity = Gravity.CENTER
        }
        speedPanel.addView(speedText)
        gap(speedPanel, 1)
        speedPanel.addView(makeText("KM/JAM", 12f, cyan, true))
        gap(speedPanel, 12)
        speedPanel.addView(makeText("LIVE GPS TRACKING", 10f, muted, true))

        root.addView(speedPanel)

        gap(root, 16)

        val distance = statCard("Jarak tempuh", "0.00 km", cyan)
        val time = statCard("Durasi", "00:00:00")
        distanceText = distance.second
        timeText = time.second
        addStatRow(root, distance, time)

        gap(root, 10)

        val average = statCard("Rata-rata", "0.0 km/jam", green)
        val maximum = statCard("Kecepatan maks.", "0.0 km/jam", cyan)
        averageSpeedText = average.second
        maxSpeedText = maximum.second
        addStatRow(root, average, maximum)

        gap(root, 16)
        gpsText = makeText("GPS aktif • Menunggu lokasi...", 12f, green, true)
        root.addView(gpsText)

        gap(root, 18)
        root.addView(makeButton(
            if (paused) "▶   LANJUTKAN PERJALANAN" else "Ⅱ   JEDA PERJALANAN",
            cyan
        ) {
            if (paused) resumeTracking() else pauseTracking()
        })

        root.addView(makeButton("■   SELESAI & SIMPAN", red) {
            finishTracking()
        })

        root.addView(makeButton("⌖   LIHAT PETA GPS", surface) {
            openMap()
        })

        showScreen(root)
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
        val root = column()

        addHeader(root, "RINGKASAN AKTIVITAS")
        gap(root, 22)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = panel(surface2, 22)
        }

        panel.addView(makeText("✓  PERJALANAN TERSIMPAN", 12f, green, true))
        gap(panel, 14)
        panel.addView(makeText(mode, 24f, white, true))
        gap(panel, 8)
        panel.addView(makeText(
            String.format(Locale.US, "%.2f km", distanceMeters / 1000f),
            36f, cyan, true
        ))
        panel.addView(makeText("Jarak perjalanan", 13f, muted))
        gap(panel, 20)
        panel.addView(makeText("Durasi: ${formatTime(elapsed)}", 15f, white))
        gap(panel, 8)
        panel.addView(makeText(
            String.format(Locale.US, "Rata-rata: %.1f km/jam", averageSpeedKmh),
            15f, white
        ))
        gap(panel, 8)
        panel.addView(makeText(
            String.format(Locale.US, "Kecepatan maksimum: %.1f km/jam", maxSpeedKmh),
            15f, white
        ))

        root.addView(panel)
        gap(root, 20)

        root.addView(makeButton("⌂   KEMBALI KE BERANDA", green) {
            showHome()
        })
        root.addView(makeButton("▤   LIHAT RIWAYAT", surface) {
            showHistory()
        })

        showScreen(root)
    }

    private fun showHistory() {
        val root = column()

        addHeader(root, "RIWAYAT PERJALANAN")
        gap(root, 18)

        val records = (prefs.getString("records", "") ?: "")
            .lines()
            .filter { it.isNotBlank() }

        if (records.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(30), dp(20), dp(30))
                background = panel()
            }
            empty.addView(makeText("Belum ada perjalanan", 18f, white, true))
            gap(empty, 8)
            empty.addView(makeText("Perjalanan yang diselesaikan akan muncul di sini.", 13f, muted))
            root.addView(empty)
        } else {
            root.addView(makeText(
                "${records.size} perjalanan tersimpan",
                13f, cyan, true
            ))
            gap(root, 12)

            for (record in records) {
                val fields = record.split("|")
                if (fields.size >= 6) {
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(15), dp(16), dp(15))
                        background = panel(surface, 17)
                    }

                    item.addView(makeText(fields[1], 18f, cyan, true))
                    gap(item, 4)
                    item.addView(makeText(fields[0], 12f, muted))
                    gap(item, 10)
                    item.addView(makeText("Jarak: ${fields[2]} km", 15f, white, true))

                    val elapsed = fields[3].toLongOrNull() ?: 0L
                    gap(item, 5)
                    item.addView(makeText("Durasi: ${formatTime(elapsed)}", 13f, muted))
                    gap(item, 4)
                    item.addView(makeText("Rata-rata: ${fields[4]} km/jam", 13f, white))
                    gap(item, 4)
                    item.addView(makeText("Maksimum: ${fields[5]} km/jam", 13f, white))

                    root.addView(item, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(12)
                    })
                }
            }

            root.addView(makeButton("HAPUS SEMUA RIWAYAT", red) {
                prefs.edit().remove("records").apply()
                showHistory()
            })
        }

        gap(root, 12)
        root.addView(makeButton("⌂   KEMBALI KE BERANDA", green) {
            showHome()
        })

        showScreen(root)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        super.onDestroy()
    }
}
