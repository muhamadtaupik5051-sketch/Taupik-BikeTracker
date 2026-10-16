package com.biketracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Service foreground: GPS tetap aktif walau layar mati atau aplikasi di latar belakang. */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.biketracker.app.START"
        const val ACTION_PAUSE = "com.biketracker.app.PAUSE"
        const val ACTION_RESUME = "com.biketracker.app.RESUME"
        const val ACTION_STOP = "com.biketracker.app.STOP"
        /** Dari tombol Jeda/Lanjut di notifikasi. */
        const val ACTION_TOGGLE = "com.biketracker.app.TOGGLE"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var tick = 0
    private var channelReady = false

    // Suara tiap kilometer
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var spokenKm = 0

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            TrackerState.onLocation(location)
            speakSplits()
            val ev = TrackerState.routeEvent
            if (ev != 0) {
                TrackerState.routeEvent = 0
                val ap = getSharedPreferences("bike_history", MODE_PRIVATE)
                val all = ap.getBoolean("alert_route", true)
                if (all && ap.getBoolean("alert_sound", true)) beep(ev == 1)
                if (all && ap.getBoolean("alert_vibe", true)) buzz(if (ev == 1) longArrayOf(0, 500, 200, 500, 200, 500) else longArrayOf(0, 200))
                notifyUpdate()
            }
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

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                TrackerState.loadGuide(this)
                spokenKm = TrackerState.splits.size
                goForeground()
                startListening()
                startTicker()
            }
            ACTION_PAUSE -> {
                stopListening()
                handler.removeCallbacks(ticker)
                notifyUpdate()
            }
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> shutdown()
            else -> recover()
        }
        // Kalau sistem mematikan proses saat merekam, service dihidupkan ulang dan sesi dipulihkan (lihat recover()).
        return START_STICKY
    }

    /** Tombol Jeda/Lanjut di notifikasi. */
    private fun toggle() {
        if (!TrackerState.running) {
            shutdown()
            return
        }
        if (TrackerState.paused) {
            TrackerState.resume()
            TrackerState.loadGuide(this)
            startListening()
            startTicker()
        } else {
            TrackerState.pause()
            stopListening()
            handler.removeCallbacks(ticker)
        }
        TrackerState.persist(this)
        notifyUpdate()
    }

    /**
     * Dipanggil saat service dihidupkan ulang tanpa perintah (sistem sempat mematikan proses).
     * Sesi terakhir dipulihkan dari simpanan; kalau tidak ada sesi, service berhenti.
     */
    private fun recover() {
        if (TrackerState.running) {
            try { goForeground() } catch (e: Exception) { stopSelf(); return }
            if (!TrackerState.paused) {
                spokenKm = TrackerState.splits.size
                startListening()
                startTicker()
            }
            return
        }
        if (!TrackerState.hasSaved(this)) {
            shutdown()
            return
        }
        val wasPaused = TrackerState.savedPaused(this)
        if (!TrackerState.restore(this)) {
            shutdown()
            return
        }
        try {
            goForeground()
        } catch (e: Exception) {
            // Sistem tidak mengizinkan service latar belakang dimulai; sesi tetap tersimpan dan ditawarkan saat aplikasi dibuka.
            stopSelf()
            return
        }
        if (wasPaused) {
            notifyUpdate()
        } else {
            TrackerState.resume()
            TrackerState.loadGuide(this)
            spokenKm = TrackerState.splits.size
            startListening()
            startTicker()
            notifyUpdate()
        }
    }

    // ---------- Suara tiap kilometer ----------

    private fun spokenTime(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val sb = StringBuilder()
        if (h > 0) sb.append(h).append(" jam ")
        if (h > 0 || m > 0) sb.append(m).append(" menit ")
        sb.append(sec).append(" detik")
        return sb.toString()
    }

    private fun speakSplits() {
        val n = TrackerState.splits.size
        if (n < spokenKm) spokenKm = n
        if (n <= spokenKm) return
        spokenKm = n
        if (!getSharedPreferences("bike_history", MODE_PRIVATE).getBoolean("alert_voice", true)) return
        val last = TrackerState.splits.last()
        val sb = StringBuilder()
        sb.append(n).append(" kilometer. Waktu ").append(spokenTime(TrackerState.currentElapsed() / 1000L)).append(". ")
        if (TrackerState.mode == "Lari") {
            sb.append("Pace ").append(last / 60).append(" menit ").append(last % 60).append(" detik per kilometer.")
        } else {
            sb.append("Kecepatan rata-rata ").append(Fmt.dec(TrackerState.averageSpeed(), 1)).append(" kilometer per jam.")
        }
        speak(sb.toString())
    }

    private fun speak(text: String) {
        try {
            if (tts == null) {
                pendingSpeech = text
                tts = TextToSpeech(applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts?.setLanguage(Locale.forLanguageTag("id-ID"))
                        } catch (e: Exception) {
                        }
                        ttsReady = true
                        pendingSpeech?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "km") }
                        pendingSpeech = null
                    }
                }
                return
            }
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "km") else pendingSpeech = text
        } catch (e: Exception) {
        }
    }

    private fun releaseSpeech() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    // ---------- Peringatan ----------

    private fun beep(long: Boolean) {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, if (long) 900 else 250)
            handler.postDelayed({ try { tg.release() } catch (e: Exception) {} }, 1500)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz(pattern: LongArray) {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
        }
    }

    private var wake: android.os.PowerManager.WakeLock? = null

    /** Jaga CPU tetap hidup selama merekam supaya GPS tidak terputus saat layar mati. */
    private fun holdWake(on: Boolean) {
        try {
            if (on) {
                if (wake == null) {
                    val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                    wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ridetrack:gps")
                }
                if (wake?.isHeld != true) wake?.acquire(12 * 60 * 60 * 1000L)
            } else if (wake?.isHeld == true) {
                wake?.release()
            }
        } catch (e: Exception) {
        }
    }

    private fun startListening() {
        if (listening) return
        holdWake(true)
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
        holdWake(false)
    }

    @Suppress("DEPRECATION")
    private fun shutdown() {
        stopListening()
        handler.removeCallbacks(ticker)
        releaseSpeech()
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
        if (Build.VERSION.SDK_INT >= 26 && !channelReady) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pelacakan aktivitas", NotificationManager.IMPORTANCE_LOW)
            )
            channelReady = true
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, flags)

        // Tombol di notifikasi: Jeda/Lanjut langsung ke service, Selesai membuka aplikasi dengan dialog konfirmasi.
        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val finishIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .putExtra("finish", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )

        val km = TrackerState.distanceMeters / 1000f
        val time = fmt(TrackerState.currentElapsed())
        val main = when (TrackerState.mode) {
            "Lari" -> Fmt.dec(km, 2) + " km • " + Fmt.pace(TrackerState.paceNowSec()) + " /km • " + time
            "Jalan" -> Fmt.int(TrackerState.steps()) + " langkah • " + Fmt.dec(km, 2) + " km • " + time
            else -> Fmt.dec(km, 2) + " km • " + time
        }
        val body = (if (TrackerState.offRoute && getSharedPreferences("bike_history", MODE_PRIVATE).getBoolean("alert_route", true)) "⚠ Keluar rute • " else "") + main + (if (TrackerState.paused) " • dijeda" else "")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val paused = TrackerState.paused
        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            if (paused) "Lanjut" else "Jeda",
            toggleIntent
        ).build()
        val finishAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Selesai",
            finishIntent
        ).build()
        return b.setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("RideTrack Pro • ${TrackerState.mode}")
            .setContentText(body)
            .setContentIntent(pi)
            .addAction(toggleAction)
            .addAction(finishAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        stopListening()
        handler.removeCallbacks(ticker)
        releaseSpeech()
        super.onDestroy()
    }
}
