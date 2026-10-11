
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
import android.widget.Toast
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val green = Color.rgb(38, 190, 125)
    private val white = Color.WHITE
    private val muted = Color.rgb(170, 180, 190)
    private val dark = Color.rgb(16, 24, 32)
    private val card = Color.rgb(31, 43, 54)
    private val red = Color.rgb(220, 75, 75)
    private val blue = Color.rgb(55, 130, 220)

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
                gpsText.text = "GPS aktif • Mencari sinyal akurat"
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

            speedKmh = if (measuredSpeed in 0f..limit) {
                measuredSpeed
            } else {
                0f
            }

            if (speedKmh > maxSpeedKmh) {
                maxSpeedKmh = speedKmh
            }

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
        window.statusBarColor = dark
        window.navigationBarColor = dark
        locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager
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
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(dark)
        }

    private fun showScreen(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(dark)
            addView(root)
        }
        setContentView(scroll)
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

        box.addView(makeText(label, 12f, muted, true))
        val value = makeText(initial, 19f, white, true).apply {
            gravity = Gravity.CENTER
        }
        box.addView(value)
        return Pair(box, value)
    }

    private fun showHome() {
        val root = column()

        root.addView(makeText("TAUPIK", 15f, green, true))
        root.addView(makeText("BikeTracker", 30f, white, true))
        root.addView(
            makeText("Lacak perjalanan dan aktivitas GPS Anda", 14f, muted)
        )

        addGap(root, 22)
        root.addView(makeText("JENIS AKTIVITAS", 13f, muted, true))
        addGap(root, 8)

        for (activityMode in listOf("Sepeda", "Lari", "Jalan")) {
            root.addView(makeButton(
                if (mode == activityMode) "✓ $activityMode" else activityMode,
                if (mode == activityMode) green else card
            ) {
                if (!running) {
                    mode = activityMode
                    showHome()
                }
            })
        }

        addGap(root, 12)
        root.addView(makeText("Kecepatan saat ini", 14f, muted))
        root.addView(makeText("0.0 km/jam", 30f, white, true))

        addGap(root, 12)
        root.addView(makeButton("MULAI AKTIVITAS", green) {
            startTracking()
        })

        root.addView(makeButton("PETA GPS", blue) {
            try {
                startActivity(Intent(this, MapActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Halaman peta belum siap.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        root.addView(makeButton("RIWAYAT AKTIVITAS", card) {
            showHistory()
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

        root.addView(makeText("AKTIVITAS BERLANGSUNG", 13f, green, true))
        root.addView(makeText(mode, 28f, white, true))
        addGap(root, 16)

        root.addView(makeText("KECEPATAN", 13f, muted, true))
        speedText = makeText("0.0", 48f, white, true)
        root.addView(speedText)
        root.addView(makeText("km/jam", 14f, muted))

        addGap(root, 18)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val distanceCard = makeStatCard("Jarak", "0.00 km")
        val timeCard = makeStatCard("Waktu", "00:00:00")
        distanceText = distanceCard.second
        timeText = timeCard.second

        row1.addView(distanceCard.first, LinearLayout.LayoutParams(0, dp(90), 1f))
        row1.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row1.addView(timeCard.first, LinearLayout.LayoutParams(0, dp(90), 1f))
        root.addView(row1)

        addGap(root, 10)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val avgCard = makeStatCard("Rata-rata", "0.0 km/jam")
        val maxCard = makeStatCard("Maksimum", "0.0 km/jam")
        averageSpeedText = avgCard.second
        maxSpeedText = maxCard.second

        row2.addView(avgCard.first, LinearLayout.LayoutParams(0, dp(90), 1f))
        row2.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row2.addView(maxCard.first, LinearLayout.LayoutParams(0, dp(90), 1f))
        root.addView(row2)

        addGap(root, 14)
        gpsText = makeText("GPS aktif • Menunggu lokasi...", 13f, green)
        root.addView(gpsText)

        addGap(root, 22)
        root.addView(makeButton(
            if (paused) "LANJUTKAN" else "JEDA",
            blue
        ) {
            if (paused) resumeTracking() else pauseTracking()
        })

        root.addView(makeButton("SELESAI & SIMPAN", red) {
            finishTracking()
        })

        root.addView(makeButton("PETA GPS", card) {
            try {
                startActivity(Intent(this, MapActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, "Peta belum siap.", Toast.LENGTH_SHORT).show()
            }
        })

        showScreen(root)
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
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            remainingSeconds
        )
    }

    private fun updateAverageSpeed() {
        val elapsedHours = currentElapsed() / 3600000f
        averageSpeedKmh = if (elapsedHours > 0f) {
            (distanceMeters / 1000f) / elapsedHours
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
                Locale.US,
                "%.2f km",
                distanceMeters / 1000f
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
        val updated = if (oldHistory.isEmpty()) {
            record
        } else {
            "$record\n$oldHistory"
        }

        prefs.edit().putString("records", updated).apply()
    }

    private fun showSummary(elapsed: Long) {
        val root = column()

        root.addView(makeText("AKTIVITAS SELESAI", 14f, green, true))
        root.addView(makeText("Ringkasan perjalanan", 27f, white, true))
        addGap(root, 20)

        root.addView(makeText("Jenis aktivitas: $mode", 17f, white))
        addGap(root, 10)
        root.addView(makeText(
            "Jarak: %.2f km".format(Locale.US, distanceMeters / 1000f),
            17f, white
        ))
        addGap(root, 10)
        root.addView(makeText("Waktu: ${formatTime(elapsed)}", 17f, white))
        addGap(root, 10)
        root.addView(makeText(
            "Kecepatan rata-rata: %.1f km/jam".format(
                Locale.US, averageSpeedKmh
            ), 17f, white
        ))
        addGap(root, 10)
        root.addView(makeText(
            "Kecepatan maksimum: %.1f km/jam".format(
                Locale.US, maxSpeedKmh
            ), 17f, white
        ))

        addGap(root, 24)
        root.addView(makeButton("KEMBALI KE BERANDA", green) {
            showHome()
        })
        root.addView(makeButton("LIHAT RIWAYAT", card) {
            showHistory()
        })

        showScreen(root)
    }

    private fun showHistory() {
        val root = column()

        root.addView(makeText("RIWAYAT", 14f, green, true))
        root.addView(makeText("Aktivitas tersimpan", 27f, white, true))
        addGap(root, 16)

        val records = (prefs.getString("records", "") ?: "")
            .lines()
            .filter { it.isNotBlank() }

        if (records.isEmpty()) {
            root.addView(makeText("Belum ada aktivitas tersimpan.", 15f, muted))
        } else {
            for (record in records) {
                val fields = record.split("|")
                if (fields.size >= 6) {
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        background = GradientDrawable().apply {
                            setColor(card)
                            cornerRadius = dp(12).toFloat()
                        }
                    }

                    item.addView(makeText(fields[1], 18f, green, true))
                    item.addView(makeText(fields[0], 12f, muted))
                    item.addView(makeText(
                        "Jarak: ${fields[2]} km",
                        14f, white
                    ))
                    val elapsed = fields[3].toLongOrNull() ?: 0L
                    item.addView(makeText(
                        "Waktu: ${formatTime(elapsed)}",
                        14f, white
                    ))
                    item.addView(makeText(
                        "Rata-rata: ${fields[4]} km/jam",
                        14f, white
                    ))
                    item.addView(makeText(
                        "Maksimum: ${fields[5]} km/jam",
                        14f, white
                    ))

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

        addGap(root, 12)
        root.addView(makeButton("KEMBALI KE BERANDA", green) {
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
