package com.biketracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Emits two short camera-flash pulses every 60 seconds while enabled. */
class FlashBlinkService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var cameraId: String? = null
    private var torchOn = false
    private fun intervalMs(): Long {
        val sec = getSharedPreferences("bike_history", MODE_PRIVATE).getInt(PREF_INTERVAL, DEFAULT_INTERVAL_S)
        return sec.coerceIn(5, 3600) * 1000L
    }
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    // One cycle: ON 150 ms, OFF 180 ms, ON 150 ms, then remain OFF until next minute.
    private val secondPulse = Runnable {
        setTorch(true)
        handler.postDelayed(turnOff, PULSE_DURATION_MS)
    }
    private val turnOff = Runnable { setTorch(false) }
    private val cycle = object : Runnable {
        override fun run() {
            val enabled = getSharedPreferences("bike_history", MODE_PRIVATE)
                .getBoolean("flash_blink_enabled", false)
            if (!enabled) {
                stopSelf()
                return
            }
            // Ensure a clean start even if a previous device/torch callback was delayed.
            handler.removeCallbacks(secondPulse)
            handler.removeCallbacks(turnOff)
            setTorch(true)
            handler.postDelayed({
                setTorch(false)
                handler.postDelayed(secondPulse, GAP_BETWEEN_PULSES_MS)
            }, PULSE_DURATION_MS)
            handler.postDelayed(this, intervalMs())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_track)
                .setContentTitle("Kedip flash aktif")
                .setContentText("2× kedip cepat setiap ${intervalMs() / 1000} detik")
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_track)
                .setContentTitle("Kedip flash aktif")
                .setContentText("2× kedip cepat setiap ${intervalMs() / 1000} detik")
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { cameraId = null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || cameraId == null) {
            getSharedPreferences("bike_history", MODE_PRIVATE).edit().putBoolean("flash_blink_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacksAndMessages(null)
        // First double-blink after one interval; then repeat (interval is set in Settings).
        handler.postDelayed(cycle, intervalMs())
        return START_NOT_STICKY
    }

    private fun setTorch(enabled: Boolean) {
        val id = cameraId ?: return
        try {
            cameraManager.setTorchMode(id, enabled)
            torchOn = enabled
        } catch (_: Exception) {
            // Another camera app or device restriction may temporarily prevent torch use.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Kedip flash", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (torchOn) setTorch(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.biketracker.app.action.FLASH_BLINK_START"
        private const val CHANNEL_ID = "flash_blink_channel"
        private const val NOTIFICATION_ID = 6902
        const val PREF_INTERVAL = "flash_interval_s"
        const val DEFAULT_INTERVAL_S = 60
        private const val PULSE_DURATION_MS = 150L
        private const val GAP_BETWEEN_PULSES_MS = 180L
    }
}
