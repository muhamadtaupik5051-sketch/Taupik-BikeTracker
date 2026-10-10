
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val green = Color.rgb(38, 190, 125)
    private val white = Color.WHITE
    private val muted = Color.rgb(170, 180, 190)
    private val dark = Color.rgb(16, 24, 32)
    private val card = Color.rgb(31, 43, 54)

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
    private lateinit var actionArea: LinearLayout

    private val handler = Handler(Looper.getMainLooper())

    private val timer = object : Runnable {
        override fun run() {
            if (running) {
                timeText.text = formatTime(currentElapsed())
                updateAverageSpeed()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val gpsListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy > 20f) {
                gpsText.text = "GPS aktif • Mencari sinyal akurat"
                return
            }

            if (!running) return

            val previous = lastLocation

            if (previous != null && location.time > previous.time) {
                val delta = previous.distanceTo(location)
                val seconds = (location.time - previous.time) / 1000.0

                val speedLimit = when (mode) {
                    "Sepeda" -> 80.0
                    "Lari" -> 30.0
                    else -> 20.0
                }

                if (
                    seconds > 0 &&
                    delta in 0.5f..100f &&
                    delta.toDouble() / seconds * 3.6 <= speedLimit
                ) {
                    distanceMeters += delta
                }
            }

            lastLocation = location

            val gpsSpeed = if (location.hasSpeed()) {
                location.speed * 3.6f
            } else {
                0f
            }

            val limit = when (mode) {
                "Sepeda" -> 80f
                "Lari" -> 30f
                else -> 20f
            }

            speedKmh = if (gpsSpeed in 0f..limit) gpsSpeed else 0f

            if (speedKmh > maxSpeedKmh) {
                maxSpeedKmh = speedKmh
            }

            updateDashboardStats()

            gpsText.text = if (location.hasAccuracy()) {
                "GPS TERKUNCI • Akurasi ±${location.accuracy.toInt()} m"
            } else {
                "GPS aktif • Lokasi diterima"
            }
        }

        override fun onProviderEnabled(provider: String) {
            gpsText.text = "GPS aktif • Mencari satelit..."
        }

        override fun onProviderDisabled(provider: String) {
            gpsText.text = "GPS mati • Aktifkan lokasi HP"
            speedKmh = 0f
            speedText.text = "0.0"
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
        window.statusBarColor = dark
        window.navigationBarColor = dark
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        showHome()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun column(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(dark)
        }
    }

    private fun showScreen(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(dark)
            addView(root)
        }
        setContentView(scroll)
    }

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

    private fun addGap(root: LinearLayout, height: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
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
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun addButton(
        root: LinearLayout,
        label: String,
        color: Int,
        action: () -> Unit
    ) {
        root.addView(makeButton(label, color, action))
    }

    private fun makeStatCard(
        label: String,
        initial: String
    ): Pair<View, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
            }
        }

        box.addView(
            makeText(label, 12f, muted, true),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val value = makeText(initial, 19f, white, true).apply {
            gravity = Gravity.CENTER
        }

        box.addView(value)
        return Pair(box, value)
    }

    private fun showHome() {
        val root = column()

        addGap(root, 28)
        root.addView(makeText("Taupik_BikeTracker", 27f, white, true))
        root.addView(makeText("Catat aktivitas olahraga dengan GPS", 14f, muted))

        addGap(root, 32)
        root.addView(makeText("PILIH AKTIVITAS", 16f, green, true))
        addGap(root, 12)

        addButton(root, "SEPEDA", green) {
            openDashboard("Sepeda")
        }

        addButton(root, "LARI", card) {
            openDashboard("Lari")
        }

        addButton(root, "JALAN", card) {
            openDashboard("Jalan")
        }

        addGap(root, 20)

        addButton(root, "RIWAYAT AKTIVITAS", card) {
            showHistory()
        }

        showScreen(root)
    }

    private fun openDashboard(selectedMode: String) {
        mode = selectedMode
        distanceMeters = 0f
        speedKmh = 0f
        maxSpeedKmh = 0f
        averageSpeedKmh = 0f
        elapsedBeforeRun = 0L
        runStartedAt = 0L
        lastLocation = null
        running = false
        paused = false

        val root = column()

        root.addView(makeText(mode.uppercase(Locale.getDefault()), 16f, green, true))
        root.addView(makeText("KECEPATAN", 14f, muted))
        addGap(root, 4)

        speedText = makeText("0.0", 60f, white, true)
        root.addView(speedText)
        root.addView(makeText("km/jam", 16f, muted))

        addGap(root, 24)

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val distanceCard = makeStatCard("JARAK", "0.00 km")
        distanceText = distanceCard.second

        val timeCard = makeStatCard("WAKTU", "00:00:00")
        timeText = timeCard.second

        firstRow.addView(
            distanceCard.first,
            LinearLayout.LayoutParams(0, dp(105), 1f)
        )
        firstRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        firstRow.addView(
            timeCard.first,
            LinearLayout.LayoutParams(0, dp(105), 1f)
        )

        root.addView(firstRow)
        addGap(root, 10)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val averageCard = makeStatCard("RATA-RATA", "0.0 km/jam")
        averageSpeedText = averageCard.second

        val maxCard = makeStatCard("MAKSIMUM", "0.0 km/jam")
        maxSpeedText = maxCard.second

        secondRow.addView(
            averageCard.first,
            LinearLayout.LayoutParams(0, dp(105), 1f)
        )
        secondRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        secondRow.addView(
            maxCard.first,
            LinearLayout.LayoutParams(0, dp(105), 1f)
        )

        root.addView(secondRow)
        addGap(root, 18)

        gpsText = makeText("GPS belum dimulai", 13f, muted)
        root.addView(gpsText)

        addGap(root, 20)

        actionArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(actionArea)

        addButton(root, "KEMBALI KE BERANDA", card) {
            stopGps()
            handler.removeCallbacks(timer)
            running = false
            paused = false
            showHome()
        }

        renderActions()
        showScreen(root)
    }

    private fun renderActions() {
        actionArea.removeAllViews()

        if (running) {
            addButton(actionArea, "JEDA", green) {
                pauseTracking()
            }
            addButton(actionArea, "SELESAI", Color.rgb(190, 65, 65)) {
                finishTracking()
            }
        } else if (paused) {
            addButton(actionArea, "LANJUTKAN", green) {
                resumeTracking()
            }
            addButton(actionArea, "SELESAI", Color.rgb(190, 65, 65)) {
                finishTracking()
            }
        } else {
            addButton(actionArea, "MULAI", green) {
                startTracking()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
            return
        }

        running = true
        paused = false
        runStartedAt = System.currentTimeMillis()

        registerGps()
        handler.removeCallbacks(timer)
        handler.post(timer)
        renderActions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            if (hasLocationPermission()) {
                startTracking()
            } else {
                gpsText.text = "Izin lokasi diperlukan untuk memakai GPS"
            }
        }
    }

    private fun registerGps() {
        if (!hasLocationPermission()) return

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                gpsListener
            )
            gpsText.text = "GPS aktif • Mencari satelit..."
        } catch (e: SecurityException) {
            gpsText.text = "Izin GPS belum tersedia"
        } catch (e: IllegalArgumentException) {
            gpsText.text = "GPS tidak tersedia di perangkat ini"
        }
    }

    private fun stopGps() {
        try {
            locationManager.removeUpdates(gpsListener)
        } catch (e: SecurityException) {
        }
    }

    private fun pauseTracking() {
        if (!running) return

        elapsedBeforeRun = currentElapsed()
        running = false
        paused = true

        handler.removeCallbacks(timer)
        stopGps()
        speedKmh = 0f
        speedText.text = "0.0"
        renderActions()
    }

    private fun resumeTracking() {
        if (!hasLocationPermission()) {
            startTracking()
            return
        }

        running = true
        paused = false
        runStartedAt = System.currentTimeMillis()

        registerGps()
        handler.removeCallbacks(timer)
        handler.post(timer)
        renderActions()
    }

    private fun currentElapsed(): Long {
        return if (running) {
            elapsedBeforeRun + System.currentTimeMillis() - runStartedAt
        } else {
            elapsedBeforeRun
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun updateAverageSpeed() {
        val hours = currentElapsed() / 3600000.0

        averageSpeedKmh = if (hours > 0.0) {
            (distanceMeters / 1000.0 / hours).toFloat()
        } else {
            0f
        }

        updateDashboardStats()
    }

    private fun updateDashboardStats() {
        distanceText.text = String.format(
            Locale.US,
            "%.2f km",
            distanceMeters / 1000f
        )

        averageSpeedText.text = String.format(
            Locale.US,
            "%.1f km/jam",
            averageSpeedKmh
        )

        maxSpeedText.text = String.format(
            Locale.US,
            "%.1f km/jam",
            maxSpeedKmh
        )

        speedText.text = String.format(Locale.US, "%.1f", speedKmh)
        timeText.text = formatTime(currentElapsed())
    }

    private fun finishTracking() {
        val elapsed = currentElapsed()

        running = false
        paused = false
        elapsedBeforeRun = elapsed

        handler.removeCallbacks(timer)
        stopGps()

        speedKmh = 0f
        updateAverageSpeed()
        saveActivity(elapsed)
        showSummary(elapsed)
    }

    private fun saveActivity(elapsed: Long) {
        val prefs = getSharedPreferences("bike_history", MODE_PRIVATE)
        val oldHistory = prefs.getString("items", "") ?: ""

        val date = SimpleDateFormat(
            "dd/MM/yyyy HH:mm",
            Locale.getDefault()
        ).format(Date())

        val record = listOf(
            date,
            mode,
            String.format(Locale.US, "%.2f", distanceMeters / 1000f),
            formatTime(elapsed),
            String.format(Locale.US, "%.1f", speedKmh),
            String.format(Locale.US, "%.1f", averageSpeedKmh),
            String.format(Locale.US, "%.1f", maxSpeedKmh)
        ).joinToString("|")

        val updatedHistory = if (oldHistory.isBlank()) {
            record
        } else {
            "$record\n$oldHistory"
        }

        prefs.edit().putString("items", updatedHistory).apply()
    }

    private fun showSummary(elapsed: Long) {
        val root = column()

        root.addView(makeText("AKTIVITAS SELESAI", 24f, green, true))
        root.addView(makeText("Ringkasan $mode", 17f, white))

        addGap(root, 24)
        root.addView(makeText("Jarak", 14f, muted))
        root.addView(
            makeText(
                String.format(Locale.US, "%.2f km", distanceMeters / 1000f),
                32f,
                white,
                true
            )
        )

        addGap(root, 12)
        root.addView(makeText("Waktu", 14f, muted))
        root.addView(makeText(formatTime(elapsed), 25f, white, true))

        addGap(root, 12)
        root.addView(makeText("Kecepatan rata-rata", 14f, muted))
        root.addView(
            makeText(
                String.format(Locale.US, "%.1f km/jam", averageSpeedKmh),
                23f,
                white,
                true
            )
        )

        addGap(root, 12)
        root.addView(makeText("Kecepatan maksimum", 14f, muted))
        root.addView(
            makeText(
                String.format(Locale.US, "%.1f km/jam", maxSpeedKmh),
                23f,
                green,
                true
            )
        )

        addGap(root, 24)

        addButton(root, "KEMBALI KE BERANDA", green) {
            showHome()
        }

        addButton(root, "LIHAT RIWAYAT", card) {
            showHistory()
        }

        showScreen(root)
    }

    private fun showHistory() {
        val root = column()

        root.addView(makeText("RIWAYAT AKTIVITAS", 24f, white, true))
        root.addView(makeText("Tersimpan di HP kamu", 13f, green))
        addGap(root, 16)

        val prefs = getSharedPreferences("bike_history", MODE_PRIVATE)
        val history = prefs.getString("items", "") ?: ""

        if (history.isBlank()) {
            root.addView(
                makeText(
                    "Belum ada aktivitas tersimpan.\nMulai olahraga untuk membuat riwayat.",
                    15f,
                    muted
                )
            )
        } else {
            history.lines().filter { it.isNotBlank() }.forEach { record ->
                val parts = record.split("|")

                if (parts.size >= 5) {
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = GradientDrawable().apply {
                            setColor(card)
                            cornerRadius = dp(12).toFloat()
                        }
                    }

                    item.addView(makeText(parts[0], 13f, muted))
                    item.addView(makeText(parts[1], 18f, green, true))
                    item.addView(
                        makeText(
                            "Jarak: ${parts[2]} km",
                            15f,
                            white
                        )
                    )
                    item.addView(
                        makeText(
                            "Waktu: ${parts[3]}",
                            15f,
                            white
                        )
                    )

                    val average = parts.getOrNull(5) ?: "0.0"
                    val maximum = parts.getOrNull(6) ?: parts[4]

                    item.addView(
                        makeText(
                            "Rata-rata: $average km/jam",
                            14f,
                            white
                        )
                    )
                    item.addView(
                        makeText(
                            "Maksimum: $maximum km/jam",
                            14f,
                            white
                        )
                    )

                    root.addView(item)
                    addGap(root, 10)
                }
            }
        }

        addGap(root, 16)

        addButton(root, "KEMBALI KE BERANDA", green) {
            showHome()
        }

        showScreen(root)
    }

    override fun onPause() {
        super.onPause()

        if (running) {
            elapsedBeforeRun = currentElapsed()
            running = false
            paused = true
            handler.removeCallbacks(timer)
            stopGps()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        stopGps()
        super.onDestroy()
    }
}
