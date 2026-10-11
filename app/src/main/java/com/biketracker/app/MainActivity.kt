package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var locationManager: LocationManager

    private var mode = "Sepeda"
    private var currentTab = 0
    private var filter = "Semua"
    private var digitalMode = false

    private var lastLocation: Location? = null
    private var distanceMeters = 0f
    private var speedKmh = 0f
    private var maxSpeedKmh = 0f
    private var elapsedBeforeRun = 0L
    private var runStartedAt = 0L
    private var running = false
    private var paused = false
    private var climbM = 0f
    private var refAlt = Double.NaN
    private var targetKm = 10
    private var gpsStatus = "Mencari GPS…"
    private val route = mutableListOf<DoubleArray>()

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

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.accuracy > 25f) {
                setGps("Mencari GPS…")
                return
            }
            setGps("GPS ±${location.accuracy.toInt()} m")
            if (!running || paused) return

            val previous = lastLocation
            if (previous != null && location.time > previous.time) {
                val seconds = (location.time - previous.time) / 1000f
                val delta = previous.distanceTo(location)
                if (seconds > 0f && delta in 0.5f..100f && delta / seconds * 3.6f <= speedLimit()) {
                    distanceMeters += delta
                }
            }
            lastLocation = location

            val measured = if (location.hasSpeed()) location.speed * 3.6f else 0f
            speedKmh = if (measured in 0f..speedLimit()) measured else 0f
            if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

            val last = route.lastOrNull()
            var moved = true
            if (last != null) {
                val res = FloatArray(1)
                Location.distanceBetween(last[0], last[1], location.latitude, location.longitude, res)
                moved = res[0] >= 3f
            }
            if (moved && route.size < 5000) route.add(doubleArrayOf(location.latitude, location.longitude))

            if (location.hasAltitude()) {
                val alt = location.altitude
                if (refAlt.isNaN()) refAlt = alt
                else if (alt - refAlt >= 3.0) { climbM += (alt - refAlt).toFloat(); refAlt = alt }
                else if (refAlt - alt >= 3.0) refAlt = alt
            }
            refreshTracking()
        }

        override fun onProviderEnabled(provider: String) { setGps("Menunggu lokasi…") }

        override fun onProviderDisabled(provider: String) {
            setGps("GPS mati")
            speedKmh = 0f
            refreshTracking()
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
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

    private fun speedLimit() = when (mode) { "Sepeda" -> 80f; "Lari" -> 30f; else -> 20f }
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
            val d = (try { fmt.parse(r.date) } catch (_: Exception) { null }) ?: continue
            if (d.time < start) continue
            c2.time = d
            out[(c2.get(Calendar.DAY_OF_WEEK) + 5) % 7] += r.km
            week.add(r)
        }
        return Pair(out, week)
    }

    private fun tripCard(rec: Rec, detailed: Boolean, onLong: (() -> Unit)? = null): LinearLayout {
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

        val last = rawRecords().mapNotNull { parse(it) }.take(2)
        if (last.isNotEmpty()) {
            r.add(tv("Terakhir", 11f, t.muted, true), 16)
            for (rec in last) r.add(tripCard(rec, false), 8)
        }
        show(0, r)
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        distanceMeters = 0f; speedKmh = 0f; maxSpeedKmh = 0f
        elapsedBeforeRun = 0L
        runStartedAt = System.currentTimeMillis()
        lastLocation = null
        climbM = 0f; refAlt = Double.NaN
        route.clear()
        targetKm = Themes.target(this)
        gpsStatus = "Mencari GPS…"
        running = true; paused = false

        showTracking()
        requestGpsUpdates()
        handler.removeCallbacks(timer)
        handler.post(timer)
    }

    private fun requestGpsUpdates() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            setGps("Menunggu lokasi…")
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, gpsListener)
        } catch (_: Exception) {
            setGps("GPS tidak tersedia")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startTracking()
            } else {
                Toast.makeText(this, "Izin lokasi diperlukan untuk melacak perjalanan.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setGps(msg: String) {
        gpsStatus = msg
        if (::gpsText.isInitialized) gpsText.text = msg
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

    private fun currentElapsed(): Long =
        elapsedBeforeRun + if (running && !paused) System.currentTimeMillis() - runStartedAt else 0L

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun averageSpeed(): Float {
        val h = currentElapsed() / 3600000f
        return if (h > 0f) (distanceMeters / 1000f) / h else 0f
    }

    private fun calories(): Int {
        val h = currentElapsed() / 3600000f
        if (h <= 0f) return 0
        val avg = averageSpeed()
        val met = when (mode) {
            "Sepeda" -> when {
                avg < 16f -> 4f
                avg < 19f -> 6f
                avg < 22f -> 8f
                avg < 26f -> 10f
                else -> 12f
            }
            "Lari" -> maxOf(6f, avg)
            else -> if (avg < 5.5f) 3.5f else 4.3f
        }
        return (met * Themes.weight(this) * h).roundToInt()
    }

    private fun refreshTracking() {
        if (!::gauge.isInitialized || !::targetBar.isInitialized) return
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
        } catch (_: Exception) {
            Toast.makeText(this, "Halaman peta belum siap.", Toast.LENGTH_LONG).show()
        }
    }

    private fun pauseTracking() {
        if (!running || paused) return
        elapsedBeforeRun += System.currentTimeMillis() - runStartedAt
        paused = true
        speedKmh = 0f
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        setGps("Aktivitas dijeda")
        showTracking()
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
    }

    private fun stopGpsUpdates() {
        try { locationManager.removeUpdates(gpsListener) } catch (_: Exception) {}
    }

    private fun finishTracking() {
        if (!running) return
        val elapsed = currentElapsed()
        elapsedBeforeRun = elapsed
        running = false
        paused = false
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        speedKmh = 0f
        saveActivity(elapsed)
        showSummary(elapsed)
    }

    private fun encodeRoute(): String {
        if (route.isEmpty()) return ""
        val step = maxOf(1, Math.ceil(route.size / 60.0).toInt())
        val sb = StringBuilder()
        var i = 0
        while (i < route.size) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(String.format(Locale.US, "%.5f,%.5f", route[i][0], route[i][1]))
            i += step
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
                r.add(tripCard(rec, true) {
                    confirm("Hapus perjalanan ini?") {
                        val list = rawRecords()
                        if (idx < list.size) list.removeAt(idx)
                        writeRecords(list)
                        showHistory()
                    }
                }, 8)
            }
            r.add(tv("Tekan lama untuk menghapus satu perjalanan", 11f, t.muted).apply {
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

        r.add(tv("RideTrack Pro V2 • Tanpa iklan • Data tersimpan di HP", 11f, t.muted).apply {
            gravity = Gravity.CENTER
        }, 20)
        show(3, r)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            running -> moveTaskToBack(true)
            currentTab > 0 -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        stopGpsUpdates()
        super.onDestroy()
    }
}
