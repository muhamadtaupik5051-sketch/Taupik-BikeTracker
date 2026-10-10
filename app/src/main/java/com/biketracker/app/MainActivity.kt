package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
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
                val seconds =
                    (location.time - previous.time) / 1000.0

                val limit = when (mode) {
                    "Sepeda" -> 80.0
                    "Lari" -> 30.0
                    else -> 20.0
                }

                if (
                    seconds > 0 &&
                    delta >= 0.5f &&
                    delta <= 100f &&
                    delta.toDouble() / seconds * 3.6 <= limit
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

            val speedLimit = when (mode) {
                "Sepeda" -> 80f
                "Lari" -> 30f
                else -> 20f
            }

            speedKmh = if (gpsSpeed in 0f..speedLimit) {
                gpsSpeed
            } else {
                0f
            }

            if (speedKmh > maxSpeedKmh) {
                maxSpeedKmh = speedKmh
            }

            gpsText.text =
                "GPS TERKUNCI • Akurasi ±${location.accuracy.toInt()} m"

            updateDashboardStats()
        }

        override fun onProviderEnabled(provider: String) {
            gpsText.text = "GPS aktif • Menunggu lokasi..."
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

        locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

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

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun addGap(root: LinearLayout, height: Int) {
        root.addView(
            View(this),
            LinearLayout.LayoutParams(1, dp(height))
        )
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

        box.addView(
            makeText(label, 12f, muted, true)
        )

        val value = makeText(initial, 19f, white, true).apply {
            gravity = Gravity.CENTER
        }

        box.addView(value)

        return Pair(box, value)
    }
