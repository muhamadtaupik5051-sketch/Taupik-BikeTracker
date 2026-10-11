package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Satu baris riwayat. Format: tanggal|mode|km|durasiMs|rata2|maks|targetKm|rute|kkal|tanjakan */
internal class Rec(val f: List<String>) {
    val date get() = f[0]
    val mode get() = f[1]
    val km get() = f[2].toFloatOrNull() ?: 0f
    val elapsed get() = f[3].toLongOrNull() ?: 0L
    val avg get() = f[4]
    val target get() = f.getOrNull(6)?.toFloatOrNull() ?: 0f
    val reached get() = target > 0f && km >= target
    val maxSpeed get() = f.getOrNull(5) ?: "0.0"
    val kcal get() = f.getOrNull(8) ?: "0"
    val climb get() = f.getOrNull(9) ?: "0"
    val route: List<DoubleArray> by lazy {
        (f.getOrNull(7) ?: "").split(";").mapNotNull { s ->
            val p = s.split(",")
            val la = p.getOrNull(0)?.toDoubleOrNull()
            val lo = p.getOrNull(1)?.toDoubleOrNull()
            if (la != null && lo != null) doubleArrayOf(la, lo) else null
        }
    }
}

class MainActivity : Activity() {

    private lateinit var t: AppTheme

    private var mode: String
        get() = TrackerState.mode
        set(v) { TrackerState.mode = v }
    private var currentTab = 0
    private var filter = "Semua"
    private var digitalMode = false
    private var inDetail = false
    private var pendingResume = false
    private var splashDone = false

    // Status pelacakan disimpan di TrackerState supaya tetap hidup bersama TrackingService.
    private val running get() = TrackerState.running
    private val paused get() = TrackerState.paused
    private val distanceMeters get() = TrackerState.distanceMeters
    private val speedKmh get() = TrackerState.speedKmh
    private val maxSpeedKmh get() = TrackerState.maxSpeedKmh
    private val climbM get() = TrackerState.climbM
    private val targetKm get() = TrackerState.targetKm
    private val gpsStatus get() = TrackerState.gpsStatus
    private val route get() = TrackerState.route

    private lateinit var gauge: GaugeView
    private lateinit var routeView: RouteView
    private lateinit var targetBar: SegBar
    private lateinit var targetValue: TextView
    private lateinit var targetPct: TextView
    private lateinit var targetInfo: TextView
    private lateinit var gpsText: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var avgText: TextView
    private lateinit var maxText: TextView
    private lateinit var kcalText: TextView
    private lateinit var climbText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bike_history", MODE_PRIVATE) }

    private val timer = object : Runnable {
        override fun run() {
            if (running && !paused) {
                refreshTracking()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        when {
            // Service masih berjalan (mis. aplikasi dibuka lagi dari notifikasi)
            TrackerState.running -> {
                splashDone = true
                showTracking()
                if (!paused) handler.post(timer)
            }
            savedInstanceState == null -> showSplash()
            else -> {
                splashDone = true
                showHome()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (running && !paused && currentTab == -1) {
            handler.removeCallbacks(timer)
            handler.post(timer)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(timer)
        super.onPause()
    }

    private fun showSplash() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(t.bg)
        }
        root.addView(tv("🚴", 64f, t.text).apply { gravity = Gravity.CENTER })
        root.add(tv("R I D E T R A C K   P R O", 13f, t.accent, true).apply { gravity = Gravity.CENTER }, 14)
        root.add(tv("MUHAMAD TAUPIK", 24f, t.text, true).apply { gravity = Gravity.CENTER }, 6)
        setContentView(root)

        val goHome = Runnable {
            if (!splashDone && !isFinishing) {
                splashDone = true
                showHome()
                checkSavedSession()
            }
        }
        root.setOnClickListener { handler.removeCallbacksAndMessages(null); goHome.run() }
        handler.postDelayed(goHome, 1500)
    }

    /** Jika aplikasi sempat dimatikan sistem saat merekam, tawarkan untuk melanjutkan. */
    private fun checkSavedSession() {
        if (TrackerState.running || !TrackerState.hasSaved(this)) return
        AlertDialog.Builder(this)
            .setTitle("Aktivitas belum selesai")
            .setMessage("Ada perjalanan yang belum disimpan karena aplikasi sempat tertutup. Lanjutkan?")
            .setPositiveButton("Lanjutkan") { _, _ ->
                if (TrackerState.restore(this)) showTracking() else TrackerState.clearSaved(this)
            }
            .setNegativeButton("Buang") { _, _ -> TrackerState.clearSaved(this) }
            .setCancelable(false)
            .show()
    }

    private fun applyTheme() {
        t = Themes.current(this)
        Themes.styleWindow(this, t)
    }

    private fun scaleFor(m: String) = when (m) { "Sepeda" -> 60f; "Lari" -> 30f; else -> 12f }

    // ---------- UI helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun mono(v: TextView): TextView {
        v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        return v
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

    private fun btn(label: String, fill: Int, radius: Int = 16, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onColor(fill))
        background = box(fill, radius, fill == t.surface)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun show(tab: Int, body: LinearLayout) {
        currentTab = tab
        inDetail = false
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
        r.add(tv(big, 24f, t.text, true), 2)
    }

    private fun icon(m: String) = when (m) { "Sepeda" -> "🚴"; "Lari" -> "🏃"; else -> "🚶" }

    private fun statCard(label: String, initial: String, accent: Int = t.text): Pair<View, TextView> {
        val c = card(t.surface, 10, 14)
        c.addView(tv(label, 11f, t.muted))
        val value = tv(initial, 17f, accent, true)
        c.add(value, 3)
        return Pair(c, value)
    }

    private fun statRow(r: LinearLayout, l: Pair<View, TextView>, rt: Pair<View, TextView>, top: Int = 8) {
        val row = LinearLayout(this)
        row.addView(l.first, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rt.first, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        r.add(row, top)
    }

    private fun circleBtn(label: String, action: () -> Unit) = tv(label, 18f, t.text, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.surface2) }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
    }

    private fun stepper(label: String, sub: String, value: String, minus: () -> Unit, plus: () -> Unit): LinearLayout {
        val row = card(t.surface, 12, 16).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(tv(label, 13f, t.text, true))
        left.add(tv(sub, 11f, t.muted), 2)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(circleBtn("−", minus))
        row.addView(mono(tv(value, 16f, t.accent, true)).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
        })
        row.addView(circleBtn("+", plus))
        return row
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Hapus") { _, _ -> ok() }
            .setNegativeButton("Batal", null).show()
    }

    // ---------- Data ----------
    private fun rawRecords(): MutableList<String> =
        (prefs.getString("records", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()

    private fun parse(line: String): Rec? {
        val f = line.split("|")
        return if (f.size >= 6) Rec(f) else null
    }

    private fun writeRecords(list: List<String>) {
        prefs.edit().putString("records", list.joinToString("\n")).apply()
    }

    private fun weekData(): Pair<FloatArray, List<Rec>> {
        val out = FloatArray(7)
        val week = mutableListOf<Rec>()
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val c2 = Calendar.getInstance()
        for (line in rawRecords()) {
            val r = parse(line) ?: continue
            val d = (try { fmt.parse(r.date) } catch (e: Exception) { null }) ?: continue
            if (d.time < start) continue
            c2.time = d
            out[(c2.get(Calendar.DAY_OF_WEEK) + 5) % 7] += r.km
            week.add(r)
        }
        return Pair(out, week)
    }

    private fun tripCard(rec: Rec, detailed: Boolean, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null): LinearLayout {
        val c = card(t.surface, 10, 16).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        c.addView(RouteView(this, t, true).apply { points = rec.route }, LinearLayout.LayoutParams(dp(64), dp(48)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(tv("${icon(rec.mode)}  ${rec.mode}", 13f, t.text, true))
        mid.add(tv(rec.date, 11f, t.muted), 2)
        if (detailed) mid.add(tv("${formatTime(rec.elapsed)} • ${rec.avg} km/jam", 11f, t.muted), 2)
        c.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(tv(String.format(Locale.US, "%.2f km", rec.km), 16f, t.accent, true))
        if (detailed && rec.target > 0f) {
            right.add(tv(if (rec.reached) "✓ Target tercapai" else "Target belum", 11f,
                if (rec.reached) t.good else t.muted), 2)
        }
        c.addView(right)
        if (onClick != null) c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener { onLong(); true }
        return c
    }

    // ---------- Screens ----------
    private fun showHome() {
        val r = column()
        title(r, "R I D E T R A C K   P R O", "Siap gowes hari ini?")

        val (week, recs) = weekData()
        val wc = card(t.surface, 14, 18)
        val top = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(tv("Minggu ini", 11f, t.muted))
        left.addView(mono(tv(String.format(Locale.US, "%.1f km", week.sum()), 24f, t.accent, true)))
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val avgs = recs.mapNotNull { it.avg.toFloatOrNull() }
        val avgTxt = if (avgs.isEmpty()) "0.0" else String.format(Locale.US, "%.1f", avgs.average())
        top.addView(tv("${recs.size} perjalanan\nrata-rata $avgTxt km/jam", 11f, t.muted).apply {
            gravity = Gravity.END
        })
        wc.addView(top)

        val maxKm = maxOf(week.maxOrNull() ?: 0f, 1f)
        val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        val dim = (t.accent and 0x00FFFFFF) or 0x66000000
        val dayNames = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val bars = LinearLayout(this)
        for (i in 0..6) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            val h = if (week[i] > 0f) maxOf(dp(4), (week[i] / maxKm * dp(44)).toInt()) else dp(3)
            val barColor = if (i == today) t.accent else if (week[i] > 0f) dim else t.surface2
            col.addView(View(this).apply { background = box(barColor, 4) }, LinearLayout.LayoutParams(-1, h))
            col.addView(tv(dayNames[i], 11f, if (i == today) t.accent else t.muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            bars.addView(col, LinearLayout.LayoutParams(0, dp(68), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        wc.add(bars, 10)
        r.add(wc, 14)

        val chips = LinearLayout(this)
        listOf("Sepeda", "Lari", "Jalan").forEachIndexed { i, m ->
            val sel = m == mode
            val chip = tv(icon(m) + "  " + m, 13f, if (sel) onColor(t.accent) else t.text, true).apply {
                gravity = Gravity.CENTER
                background = box(if (sel) t.accent else t.surface, 14, !sel)
                setOnClickListener { if (!running) { mode = m; showHome() } }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        r.add(chips, 12)

        r.add(stepper("◎ Target hari ini", "Tampil di layar tracking",
            "${Themes.target(this)} km",
            { Themes.stepTarget(this, -1); showHome() },
            { Themes.stepTarget(this, 1); showHome() }), 12)

        r.add(btn("▶   MULAI AKTIVITAS", t.good, 30) { startTracking() }, 12, 0f, dp(58))

        val last = rawRecords().mapIndexedNotNull { i, line -> parse(line)?.let { i to it } }.take(2)
        if (last.isNotEmpty()) {
            r.add(tv("Terakhir", 11f, t.muted, true), 16)
            for ((idx, rec) in last) r.add(tripCard(rec, false, { showDetail(idx, rec) }), 8)
        }
        show(0, r)
    }

    private fun hasFine() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 100)
    }

    private fun showLocationDenied() {
        AlertDialog.Builder(this)
            .setTitle("Izin lokasi presisi diperlukan")
            .setMessage("Izinkan akses lokasi dengan pilihan \"Presisi/Tepat\" agar jarak dan kecepatan dihitung dari GPS.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Buka Pengaturan > Aplikasi > izin lokasi.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun sendService(action: String) {
        val i = Intent(this, TrackingService::class.java).setAction(action)
        try {
            val foreground = action == TrackingService.ACTION_START || action == TrackingService.ACTION_RESUME
            if (foreground && Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Pelacakan latar belakang gagal dimulai.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startTracking() {
        if (!hasFine()) {
            pendingResume = false
            askPermissions()
            return
        }
        TrackerState.start(Themes.target(this))
        TrackerState.persist(this)
        sendService(TrackingService.ACTION_START)
        showTracking()
        handler.removeCallbacks(timer)
        handler.post(timer)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasFine()) {
                if (pendingResume) {
                    pendingResume = false
                    resumeTracking()
                } else {
                    startTracking()
                }
            } else {
                pendingResume = false
                showLocationDenied()
            }
        }
    }

    private fun showTracking() {
        val r = column()

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("⌖", 22f, t.muted).apply {
            setPadding(dp(2), dp(4), dp(10), dp(4)); setOnClickListener { openMap() }
        })
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for ((i, name) in listOf("Analog", "Digital").withIndex()) {
            val sel = (i == 1) == digitalMode
            tabs.addView(tv(name, 15f, if (sel) t.accent else t.muted, sel).apply {
                setPadding(dp(14), dp(4), dp(14), dp(4))
                if (sel) paint.isUnderlineText = true
                setOnClickListener { digitalMode = i == 1; showTracking() }
            })
        }
        top.addView(tabs, LinearLayout.LayoutParams(0, -2, 1f))
        gpsText = tv(gpsStatus, 11f, t.good, true).apply { gravity = Gravity.END }
        top.addView(gpsText)
        r.addView(top)

        routeView = RouteView(this, t).apply { setOnClickListener { openMap() } }
        r.add(routeView, 10, 0f, dp(90))

        gauge = GaugeView(this, t).apply { scaleMax = scaleFor(mode); digital = digitalMode }
        r.add(gauge, 6)

        val tc = card(t.surface, 12, 16)
        val head = LinearLayout(this)
        val what = when (mode) { "Sepeda" -> "bersepeda"; "Lari" -> "lari"; else -> "jalan" }
        head.addView(tv("◎ Target $what", 12f, t.muted), LinearLayout.LayoutParams(0, -2, 1f))
        targetPct = tv("0%", 12f, t.good, true)
        head.addView(targetPct)
        tc.addView(head)
        val vrow = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        targetValue = mono(tv("0.00", 22f, t.accent, true))
        vrow.addView(targetValue)
        vrow.addView(tv("  / $targetKm km", 13f, t.muted))
        tc.add(vrow, 4)
        targetBar = SegBar(this, t)
        tc.add(targetBar, 8, 0f, dp(10))
        targetInfo = tv("", 11f, t.muted)
        tc.add(targetInfo, 6)
        r.add(tc, 4)

        val maxC = statCard("Kecepatan maks.", "0.0 km/jam", t.accent)
        val avgC = statCard("Rata-rata", "0.0 km/jam", t.good)
        val disC = statCard("Jarak", "0.00 km")
        val timC = statCard("Durasi", "00:00:00")
        val kalC = statCard("Kalori", "0 kkal")
        val clmC = statCard("Tanjakan", "0 m")
        maxText = maxC.second; avgText = avgC.second; distanceText = disC.second
        timeText = timC.second; kcalText = kalC.second; climbText = clmC.second
        statRow(r, maxC, avgC, 10)
        statRow(r, disC, timC)
        statRow(r, kalC, clmC)

        val row = LinearLayout(this)
        row.addView(btn("SELESAI", t.bad, 28) { finishTracking() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(btn(if (paused) "LANJUT" else "JEDA", t.accent, 28) {
            if (paused) resumeTracking() else pauseTracking()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(10) })
        r.add(row, 14)

        show(-1, r)
        refreshTracking()
    }

    private fun currentElapsed(): Long = TrackerState.currentElapsed()

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun averageSpeed(): Float = TrackerState.averageSpeed()

    private fun calories(): Int = TrackerState.calories(Themes.weight(this))

    private fun refreshTracking() {
        if (!::gauge.isInitialized || !::targetBar.isInitialized) return
        if (::gpsText.isInitialized) gpsText.text = gpsStatus
        val km = distanceMeters / 1000f
        gauge.speed = speedKmh
        gauge.maxSpeed = maxSpeedKmh
        gauge.invalidate()
        routeView.points = route.toList()
        routeView.invalidate()

        distanceText.text = String.format(Locale.US, "%.2f km", km)
        timeText.text = formatTime(currentElapsed())
        avgText.text = String.format(Locale.US, "%.1f km/jam", averageSpeed())
        maxText.text = String.format(Locale.US, "%.1f km/jam", maxSpeedKmh)
        kcalText.text = "${calories()} kkal"
        climbText.text = "${climbM.roundToInt()} m"

        val frac = (km / targetKm).coerceIn(0f, 1f)
        targetBar.fraction = frac
        targetBar.invalidate()
        targetValue.text = String.format(Locale.US, "%.2f", km)
        targetPct.text = "${(frac * 100).toInt()}%"
        if (km >= targetKm) {
            targetInfo.text = "✓ Target tercapai, kerja bagus!"
        } else {
            val remain = targetKm - km
            val avg = averageSpeed()
            val eta = if (avg > 1f) (remain / avg * 60f).roundToInt() else -1
            targetInfo.text = String.format(Locale.US, "Sisa %.2f km", remain) +
                if (eta >= 0) " • sekitar $eta menit lagi" else ""
        }
    }

    private fun openMap() {
        try {
            startActivity(Intent(this, MapActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Halaman peta belum siap.", Toast.LENGTH_LONG).show()
        }
    }

    private fun pauseTracking() {
        if (!running || paused) return
        TrackerState.pause()
        TrackerState.persist(this)
        handler.removeCallbacks(timer)
        sendService(TrackingService.ACTION_PAUSE)
        showTracking()
    }

    private fun resumeTracking() {
        if (!running || !paused) return
        if (!hasFine()) {
            pendingResume = true
            askPermissions()
            return
        }
        TrackerState.resume()
        sendService(TrackingService.ACTION_RESUME)
        showTracking()
        handler.removeCallbacks(timer)
        handler.post(timer)
    }

    private fun finishTracking() {
        if (!running) return
        val km = distanceMeters / 1000f
        val b = AlertDialog.Builder(this).setTitle("Selesaikan aktivitas?")
        if (km >= 0.01f) {
            b.setMessage(String.format(Locale.US, "Jarak %.2f km • durasi %s", km, formatTime(currentElapsed())))
            b.setPositiveButton("Simpan") { _, _ -> completeTracking(true) }
        } else {
            b.setMessage("Belum ada jarak yang terekam, jadi aktivitas ini belum bisa disimpan.")
        }
        b.setNegativeButton("Buang") { _, _ -> completeTracking(false) }
        b.setNeutralButton("Lanjut", null)
        b.show()
    }

    private fun completeTracking(save: Boolean) {
        if (!running) return
        val elapsed = currentElapsed()
        handler.removeCallbacks(timer)
        TrackerState.finish(elapsed)
        sendService(TrackingService.ACTION_STOP)
        TrackerState.clearSaved(this)
        if (save) {
            saveActivity(elapsed)
            showSummary(elapsed)
        } else {
            Toast.makeText(this, "Aktivitas dibuang.", Toast.LENGTH_SHORT).show()
            showHome()
        }
    }

    private fun encodeRoute(): String {
        if (route.isEmpty()) return ""
        val step = maxOf(1, Math.ceil(route.size / 300.0).toInt())
        val sb = StringBuilder()
        var i = 0
        while (i < route.size) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(String.format(Locale.US, "%.5f,%.5f", route[i][0], route[i][1]))
            i += step
        }
        if ((route.size - 1) % step != 0) {
            val last = route[route.size - 1]
            sb.append(';').append(String.format(Locale.US, "%.5f,%.5f", last[0], last[1]))
        }
        return sb.toString()
    }

    private fun saveActivity(elapsed: Long) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val record = listOf(
            date, mode,
            String.format(Locale.US, "%.3f", distanceMeters / 1000f),
            elapsed.toString(),
            String.format(Locale.US, "%.1f", averageSpeed()),
            String.format(Locale.US, "%.1f", maxSpeedKmh),
            targetKm.toString(),
            encodeRoute(),
            calories().toString(),
            climbM.roundToInt().toString()
        ).joinToString("|")
        val list = rawRecords()
        list.add(0, record)
        writeRecords(list)
    }

    private fun showSummary(elapsed: Long) {
        val r = column()
        title(r, "RINGKASAN AKTIVITAS", "Kerja bagus! 🎉")

        val km = distanceMeters / 1000f
        val hero = card(t.accent, 18, 24)
        val on = onColor(t.accent)
        hero.addView(tv("✓  PERJALANAN TERSIMPAN", 11f, on, true))
        hero.add(tv("${icon(mode)}  $mode", 16f, on, true), 8)
        hero.add(mono(tv(String.format(Locale.US, "%.2f km", km), 34f, on, true)), 2)
        r.add(hero, 14)

        r.add(RouteView(this, t).apply { points = route.toList(); hint = "Rute tidak terekam" }, 10, 0f, dp(130))

        val reached = km >= targetKm
        val tc = card(t.surface, 12, 16)
        tc.addView(tv(if (reached) "✓ Target $targetKm km tercapai" else
            String.format(Locale.US, "Target %d km • tercapai %.0f%%", targetKm, km / targetKm * 100f),
            14f, if (reached) t.good else t.text, true))
        r.add(tc, 10)

        statRow(r, statCard("Durasi", formatTime(elapsed)),
            statCard("Rata-rata", String.format(Locale.US, "%.1f km/jam", averageSpeed()), t.good), 10)
        statRow(r, statCard("Kecepatan maks.", String.format(Locale.US, "%.1f km/jam", maxSpeedKmh), t.accent),
            statCard("Kalori", "${calories()} kkal"))
        statRow(r, statCard("Tanjakan", "${climbM.roundToInt()} m"),
            statCard("Jarak", String.format(Locale.US, "%.2f km", km)))

        r.add(btn("⌂   KEMBALI KE BERANDA", t.good, 28) { showHome() }, 16, 0f, dp(54))
        r.add(btn("▤   LIHAT RIWAYAT", t.surface) { showHistory() }, 10, 0f, dp(50))
        show(0, r)
    }

    private fun showHistory() {
        val r = column()
        title(r, "RIWAYAT", "Perjalananmu")

        val all = rawRecords().mapIndexedNotNull { i, line -> parse(line)?.let { i to it } }
        val sc = card(t.surface, 12, 16).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(label: String, value: String, color: Int) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(label, 11f, t.muted))
            col.add(tv(value, 17f, color, true), 2)
            sc.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        cell("Total jarak", String.format(Locale.US, "%.1f km", all.sumOf { it.second.km.toDouble() }), t.accent)
        cell("Perjalanan", all.size.toString(), t.text)
        cell("Target tercapai", all.count { it.second.reached }.toString(), t.text)
        r.add(sc, 12)

        val chips = LinearLayout(this)
        listOf("Semua", "Sepeda", "Lari", "Jalan").forEachIndexed { i, name ->
            val sel = name == filter
            chips.addView(tv(name, 12f, if (sel) onColor(t.accent) else t.muted, sel).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = box(if (sel) t.accent else t.surface, 14, !sel)
                setOnClickListener { filter = name; showHistory() }
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(6) })
        }
        r.add(chips, 12)

        val shown = all.filter { filter == "Semua" || it.second.mode == filter }
        if (shown.isEmpty()) {
            val e = card(t.surface, 24, 20).apply { gravity = Gravity.CENTER }
            e.addView(tv("Belum ada perjalanan", 17f, t.text, true).apply { gravity = Gravity.CENTER })
            e.add(tv("Perjalanan yang diselesaikan akan muncul di sini.", 12f, t.muted).apply {
                gravity = Gravity.CENTER
            }, 6)
            r.add(e, 12)
        } else {
            for ((idx, rec) in shown) {
                r.add(tripCard(rec, true, { showDetail(idx, rec) }) {
                    confirm("Hapus perjalanan ini?") {
                        val list = rawRecords()
                        if (idx < list.size) list.removeAt(idx)
                        writeRecords(list)
                        showHistory()
                    }
                }, 8)
            }
            r.add(tv("Ketuk untuk detail • tekan lama untuk menghapus", 11f, t.muted).apply {
                gravity = Gravity.CENTER
            }, 10)
            r.add(btn("Hapus semua riwayat", t.bad) {
                confirm("Hapus semua riwayat?") {
                    prefs.edit().remove("records").apply(); showHistory()
                }
            }, 10, 0f, dp(48))
        }
        show(2, r)
    }

    private fun showDetail(idx: Int, rec: Rec) {
        val r = column()
        title(r, "DETAIL AKTIVITAS", rec.date)

        val hero = card(t.accent, 18, 24)
        val on = onColor(t.accent)
        hero.addView(tv("${icon(rec.mode)}  ${rec.mode}", 16f, on, true))
        hero.add(mono(tv(String.format(Locale.US, "%.2f km", rec.km), 34f, on, true)), 2)
        r.add(hero, 14)

        r.add(RouteView(this, t).apply { points = rec.route; hint = "Rute tidak terekam" }, 10, 0f, dp(150))

        if (rec.target > 0f) {
            val tc = card(t.surface, 12, 16)
            tc.addView(tv(
                if (rec.reached) "✓ Target ${rec.target.toInt()} km tercapai"
                else "Target ${rec.target.toInt()} km belum tercapai",
                14f, if (rec.reached) t.good else t.text, true))
            r.add(tc, 10)
        }

        statRow(r, statCard("Durasi", formatTime(rec.elapsed)),
            statCard("Rata-rata", "${rec.avg} km/jam", t.good), 10)
        statRow(r, statCard("Kecepatan maks.", "${rec.maxSpeed} km/jam", t.accent),
            statCard("Kalori", "${rec.kcal} kkal"))
        statRow(r, statCard("Tanjakan", "${rec.climb} m"),
            statCard("Titik rute", rec.route.size.toString()))

        if (rec.route.isNotEmpty()) {
            r.add(btn("⌖   LIHAT DI PETA", t.good, 28) {
                val flat = DoubleArray(rec.route.size * 2)
                rec.route.forEachIndexed { i, p -> flat[i * 2] = p[0]; flat[i * 2 + 1] = p[1] }
                try {
                    startActivity(Intent(this, MapActivity::class.java).putExtra("route", flat))
                } catch (e: Exception) {
                    Toast.makeText(this, "Halaman peta belum siap.", Toast.LENGTH_LONG).show()
                }
            }, 16, 0f, dp(54))
        }
        r.add(btn("▤   KEMBALI KE RIWAYAT", t.surface) { showHistory() }, 10, 0f, dp(50))
        r.add(btn("Hapus perjalanan ini", t.bad) {
            confirm("Hapus perjalanan ini?") {
                val list = rawRecords()
                if (idx < list.size) list.removeAt(idx)
                writeRecords(list)
                showHistory()
            }
        }, 10, 0f, dp(48))

        show(2, r)
        inDetail = true
    }

    private fun showSettings() {
        val r = column()
        title(r, "PENGATURAN", "Tampilan")
        r.add(tv("TEMA WARNA", 11f, t.muted, true), 14)

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

        r.add(tv("LAINNYA", 11f, t.muted, true), 20)
        r.add(stepper("Berat badan", "Untuk perkiraan kalori", "${Themes.weight(this)} kg",
            { Themes.stepWeight(this, -1); showSettings() },
            { Themes.stepWeight(this, 1); showSettings() }), 8)
        r.add(stepper("Target harian", "Jarak target saat tracking", "${Themes.target(this)} km",
            { Themes.stepTarget(this, -1); showSettings() },
            { Themes.stepTarget(this, 1); showSettings() }), 8)
        val on = Themes.keepOn(this)
        r.add(btn("Layar tetap menyala: " + (if (on) "AKTIF" else "MATI"), t.surface) {
            Themes.setKeepOn(this, !on); applyTheme(); showSettings()
        }, 8, 0f, dp(52))

        r.add(tv("RideTrack Pro V3 • Tanpa iklan • Data tersimpan di HP", 11f, t.muted).apply {
            gravity = Gravity.CENTER
        }, 20)
        show(3, r)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            running -> moveTaskToBack(true)
            inDetail -> showHistory()
            currentTab > 0 -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
