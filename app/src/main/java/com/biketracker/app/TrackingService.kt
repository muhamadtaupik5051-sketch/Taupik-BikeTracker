package com.biketracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Locale

/** Service foreground: GPS tetap aktif walau layar mati atau aplikasi di latar belakang. */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.biketracker.app.START"
        const val ACTION_PAUSE = "com.biketracker.app.PAUSE"
        const val ACTION_RESUME = "com.biketracker.app.RESUME"
        const val ACTION_STOP = "com.biketracker.app.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var tick = 0

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            TrackerState.onLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            TrackerState.gpsStatus = "Menunggu lokasi…"
        }

        override fun onProviderDisabled(provider: String) {
            TrackerState.gpsStatus = "GPS mati"
            TrackerState.speedKmh = 0f
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick++
            if (tick % 5 == 0) notifyUpdate()
            if (tick % 15 == 0) TrackerState.persist(this@TrackingService)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                goForeground()
                startListening()
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 1000)
            }
            ACTION_PAUSE -> {
                stopListening()
                handler.removeCallbacks(ticker)
                notifyUpdate()
            }
            ACTION_STOP -> shutdown()
            else -> if (!TrackerState.running) shutdown()
        }
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (listening) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
            listening = true
            TrackerState.gpsStatus = "Menunggu lokasi…"
        } catch (e: SecurityException) {
            TrackerState.gpsStatus = "Izin lokasi ditolak"
        } catch (e: Exception) {
            TrackerState.gpsStatus = "GPS tidak tersedia"
        }
    }

    private fun stopListening() {
        try {
            lm.removeUpdates(listener)
        } catch (e: Exception) {
        }
        listening = false
    }

    @Suppress("DEPRECATION")
    private fun shutdown() {
        stopListening()
        handler.removeCallbacks(ticker)
        stopForeground(true)
        stopSelf()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyUpdate() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pelacakan aktivitas", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val km = TrackerState.distanceMeters / 1000f
        val body = String.format(Locale.US, "%.2f km • %s", km, fmt(TrackerState.currentElapsed())) +
            if (TrackerState.paused) " • dijeda" else ""
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RideTrack Pro • ${TrackerState.mode}")
            .setContentText(body)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        stopListening()
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
