
package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val navy = Color.rgb(7, 28, 46)
    private val card = Color.rgb(20, 48, 64)
    private val green = Color.rgb(0, 220, 130)
    private val white = Color.WHITE
    private val muted = Color.rgb(180, 198, 207)

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var mode = "Sepeda"
    private var lastLocation: Location? = null
    private var distanceMeters = 0f
    private var speedKmh = 0f
    private var elapsedBeforeRun = 0L
    private var runStartedAt = 0L
    private var running = false
    private var paused = false
    private var permissionPending = false

    private lateinit var speedText: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var gpsText: TextView
    private lateinit var actionArea: LinearLayout

    private val timer = object : Runnable {
        override fun run() {
            if (running && !paused) {
                timeText.text = formatTime(currentElapsed())
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy > 30f) {
                gpsText.text = "GPS AKTIF • Sinyal kurang akurat"
                return
            }

            gpsText.text = if (location.hasAccuracy()) {
                "GPS TERKUNCI • Akurasi ±${location.accuracy.toInt()} m"
            } else {
                "GPS AKTIF • Lokasi diterima"
            }

            val previous = lastLocation
            if (previous != null) {
                val delta = previous.distanceTo(location)
                if (delta in 0.5f..100f) {
                    distanceMeters += delta
                }
            }

            lastLocation = location
            speedKmh = if (location.hasSpeed()) {
                (location.speed * 3.6f).coerceAtLeast(0f)
            } else {
                0f
            }

            speedText.text = String.format(Locale.US, "%.1f", speedKmh)
            distanceText.text = String.format(
                Locale.US, "%.2f km", distanceMeters / 1000f
            )
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
            provider: String?, status: Int, extras: Bundle?
        ) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = navy
        window.navigationBarColor = navy

        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        showHome()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(24))
        setBackgroundColor(navy)
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(5), dp(4), dp(5))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        color: Int,
        onClick: () -> Unit
    ) {
        val button = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(white)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(15), dp(12), dp(15))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(7), 0, dp(7))
        parent.addView(button, params)
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun showHome() {
        val layout = column()
        layout.gravity = Gravity.CENTER_HORIZONTAL

        layout.addView(text("Taupik_BikeTracker", 27f, white, true))
        layout.addView(text("GPS OUTDOOR TRACKER", 14f, green, true))
        addGap(layout, 22)

        addButton(layout, "🚴  MULAI BERSEPEDA", card) {
            openDashboard("Sepeda")
        }
        addButton(layout, "🏃  MULAI BERLARI", card) {
            openDashboard("Lari")
        }
        addButton(layout, "🚶  MULAI BERJALAN", card) {
            openDashboard("Jalan")
        }

        addGap(layout, 16)
        addButton(layout, "📋 RIWAYAT PERJALANAN", green) {
            showHistory()
        }

        layout.addView(
            text(
                "GPS digunakan untuk mengukur kecepatan dan jarak.",
                12f, muted
            )
        )
        setContentView(layout)
    }

    private fun openDashboard(selectedMode: String) {
        mode = selectedMode
        distanceMeters = 0f
        speedKmh = 0f
        elapsedBeforeRun = 0L
        lastLocation = null
        running = false
        paused = false

        val root = column()
        root.addView(text("Taupik_BikeTracker", 23f, white, true))
        root.addView(text("MODE ${mode.uppercase()}", 14f, green, true))
        addGap(root, 10)

        val speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), dp(18))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(24).toFloat()
            }
        }

        speedCard.addView(text("KECEPATAN SAAT INI", 14f, muted))
        speedText = text("0.0", 76f, green, true)
        speedCard.addView(speedText)
        speedCard.addView(text("km/jam", 19f, white))

        root.addView(
            speedCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(225)
            )
        )

        addGap(root, 12)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val distanceCard = makeStatCard("JARAK", "0.00 km")
        distanceText = distanceCard.second

        val timeCard = makeStatCard("WAKTU", "00:00:00")
        timeText = timeCard.second

        stats.addView(
            distanceCard.first,
            LinearLayout.LayoutParams(0, dp(100), 1f)
        )
        stats.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        stats.addView(
            timeCard.first,
            LinearLayout.LayoutParams(0, dp(100), 1f)
        )
        root.addView(stats)

        addGap(root, 8)
        gpsText = text("GPS belum dimulai", 13f, muted)
        root.addView(gpsText)

        actionArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(actionArea)
        renderActions()

        addButton(root, "KEMBALI KE BERANDA", card) {
            if (running) {
                Toast.makeText(
                    this,
                    "Selesaikan aktivitas terlebih dahulu",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showHome()
            }
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(navy)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun makeStatCard(
        label: String,
        initial: String
    ): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(16).toFloat()
            }
        }

        box.addView(text(label, 12f, muted, true))
        val value = text(initial, 21f, white, true)
        box.addView(value)
        return Pair(box, value)
    }

    private fun renderActions() {
        actionArea.removeAllViews()

        if (!running) {
            addButton(actionArea, "▶  MULAI", green) {
                startOrRequestPermission()
            }
        } else if (paused) {
            addButton(actionArea, "▶  LANJUTKAN", green) {
                resumeTracking()
            }
            addButton(
                actionArea, "■  BERHENTI & SIMPAN",
                Color.rgb(190, 65, 65)
            ) {
                finishTracking()
            }
        } else {
            addButton(
                actionArea, "Ⅱ  JEDA",
                Color.rgb(200, 135, 25)
            ) {
                pauseTracking()
            }
            addButton(
                actionArea, "■  BERHENTI & SIMPAN",
                Color.rgb(190, 65, 65)
            ) {
                finishTracking()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startOrRequestPermission() {
        if (!hasLocationPermission()) {
            permissionPending = true
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                101
            )
            return
        }

        startTracking()
    }

    @Deprecated("Android runtime permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == 101 && permissionPending) {
            permissionPending = false

            if (hasLocationPermission()) {
                startTracking()
            } else {
                gpsText.text = "Izin lokasi ditolak"
                Toast.makeText(
                    this,
                    "Izinkan akses lokasi agar GPS berfungsi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun registerGps() {
        if (!hasLocationPermission()) return

        try {
            if (locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                )
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    gpsListener,
                    Looper.getMainLooper()
                )
                gpsText.text = "Mencari sinyal GPS..."
            } else {
                gpsText.text = "GPS mati • Aktifkan lokasi HP"
            }

            if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val last = locationManager.getLastKnownLocation(
                    LocationManager.GPS_PROVIDER
                )
                if (last != null) {
                    gpsListener.onLocationChanged(last)
                }
            }
        } catch (e: Exception) {
            gpsText.text = "GPS tidak tersedia • Periksa pengaturan lokasi"
        }
    }

    private fun startTracking() {
        running = true
        paused = false
        lastLocation = null
        runStartedAt = SystemClock.elapsedRealtime()

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        registerGps()
        handler.removeCallbacks(timer)
        handler.post(timer)
        renderActions()
    }

    private fun pauseTracking() {
        if (!running || paused) return

        elapsedBeforeRun = currentElapsed()
        paused = true
        speedKmh = 0f
        speedText.text = "0.0"

        handler.removeCallbacks(timer)
        locationManager.removeUpdates(gpsListener)
        gpsText.text = "Aktivitas dijeda"

        renderActions()
    }

    private fun resumeTracking() {
        if (!running || !paused) return

        paused = false
        runStartedAt = SystemClock.elapsedRealtime()
        lastLocation = null

        registerGps()
        handler.removeCallbacks(timer)
        handler.post(timer)
        renderActions()
    }

    private fun currentElapsed(): Long {
        return if (running && !paused) {
            elapsedBeforeRun +
                SystemClock.elapsedRealtime() - runStartedAt
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

    private fun finishTracking() {
        if (!running) return

        val elapsed = currentElapsed()

        running = false
        paused = false

        handler.removeCallbacks(timer)
        locationManager.removeUpdates(gpsListener)

        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        saveActivity(elapsed)

        Toast.makeText(
            this,
            "Aktivitas selesai dan disimpan",
            Toast.LENGTH_LONG
        ).show()

        showSummary(elapsed)
    }

    private fun saveActivity(elapsed: Long) {
        val prefs = getSharedPreferences("bike_history", MODE_PRIVATE)
        val oldHistory = prefs.getString("items", "") ?: ""

        val date = java.text.SimpleDateFormat(
            "dd-MM-yyyy HH:mm",
            Locale.getDefault()
        ).format(java.util.Date())

        val record = listOf(
            date,
            mode,
            String.format(Locale.US, "%.2f", distanceMeters / 1000f),
            formatTime(elapsed),
            String.format(Locale.US, "%.1f", speedKmh)
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

        root.addView(text("AKTIVITAS SELESAI", 24f, green, true))
        root.addView(text("Ringkasan $mode", 17f, white))
        addGap(root, 24)

        root.addView(text("Jarak", 14f, muted))
        root.addView(
            text(
                String.format(Locale.US, "%.2f km", distanceMeters / 1000f),
                32f, white, true
            )
        )

        addGap(root, 12)
        root.addView(text("Waktu", 14f, muted))
        root.addView(text(formatTime(elapsed), 25f, white, true))

        addGap(root, 12)
        root.addView(text("Kecepatan terakhir", 14f, muted))
        root.addView(
            text(
                String.format(Locale.US, "%.1f km/jam", speedKmh),
                23f, green, true
            )
        )

        addGap(root, 24)

        addButton(root, "KEMBALI KE BERANDA", green) {
            showHome()
        }

        addButton(root, "LIHAT RIWAYAT", card) {
            showHistory()
        }

        setContentView(root)
    }

    private fun showHistory() {
        val root = column()

        root.addView(text("RIWAYAT AKTIVITAS", 24f, white, true))
        root.addView(text("Tersimpan di HP kamu", 13f, green))
        addGap(root, 16)

        val prefs = getSharedPreferences("bike_history", MODE_PRIVATE)
        val history = prefs.getString("items", "") ?: ""

        if (history.isBlank()) {
            root.addView(
                text(
                    "Belum ada aktivitas tersimpan.\nMulai olahraga untuk membuat riwayat.",
                    15f, muted
                )
            )
        } else {
            history.lines().filter { it.isNotBlank() }.forEach { record ->
                val parts = record.split("|")

                if (parts.size >= 5) {
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(card)
                            cornerRadius = dp(14).toFloat()
                        }
                    }

                    item.addView(text(parts[1], 18f, green, true))
                    item.addView(text(parts[0], 12f, muted))
                    item.addView(
                        text("Jarak: ${parts[2]} km", 15f, white)
                    )
                    item.addView(
                        text("Waktu: ${parts[3]}", 15f, white)
                    )
                    item.addView(
                        text("Kecepatan terakhir: ${parts[4]} km/jam", 13f, muted)
                    )

                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, dp(12))
                    root.addView(item, params)
                }
            }
        }

        addGap(root, 12)

        addButton(root, "HAPUS SEMUA RIWAYAT", Color.rgb(150, 45, 45)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Hapus riwayat?")
                .setMessage("Semua riwayat aktivitas yang tersimpan akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    prefs.edit().remove("items").apply()
                    showHistory()
                }
                .show()
        }

        addButton(root, "KEMBALI KE BERANDA", green) {
            showHome()
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(navy)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        // Timer tetap dikelola selama aktivitas berlangsung.
        // Versi ini belum menjamin pelacakan GPS di latar belakang.
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        locationManager.removeUpdates(gpsListener)
        super.onDestroy()
    }
}
