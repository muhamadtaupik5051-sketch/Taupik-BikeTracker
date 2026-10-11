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

/** Satu baris riwayat. Format: tanggal|mode|km|durasiMs|rata2|maks|target|rute|kkal|tanjakan
 *  target: km (Sepeda/Lari) atau langkah (Jalan; data lama Jalan memakai km). */
internal class Rec(val f: List<String>) {
    val date get() = f[0]
    val mode get() = f[1]
    val km get() = f[2].toFloatOrNull() ?: 0f
    val elapsed get() = f[3].toLongOrNull() ?: 0L
    val avg get() = f[4]
    val target get() = f.getOrNull(6)?.toFloatOrNull() ?: 0f
    val maxSpeed get() = f.getOrNull(5) ?: "0.0"
    val avgF get() = avg.toFloatOrNull() ?: 0f
    val maxF get() = maxSpeed.toFloatOrNull() ?: 0f
    val steps get() = (km * 1000f * TrackerState.STEPS_PER_METER).roundToInt()
    val paceSec: Int? get() = if (km > 0.05f && elapsed > 0L) ((elapsed / 1000f) / km).roundToInt() else null
    private val stepTarget get() = mode == "Jalan" && target >= 100f
    val reached get() = target > 0f && (if (stepTarget) steps >= target else km >= target)
    val targetText get() = if (stepTarget) Fmt.int(target.toInt()) + " langkah" else target.toInt().toString() + " km"
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
    private var homePage = 0
    private var trackPage = 0
    private val modes = listOf("Sepeda", "Lari", "Jalan")

    // Status pelacakan disimpan di TrackerState supaya tetap hidup bersama TrackingService.
    private val running get() = TrackerState.running
    private val paused get() = TrackerState.paused
    private val distanceMeters get() = TrackerState.distanceMeters
    private val speedKmh get() = TrackerState.speedKmh
    private val maxSpeedKmh get() = TrackerState.maxSpeedKmh
    private val climbM get() = TrackerState.climbM
    private val gpsStatus get() = TrackerState.gpsStatus
    private val route get() = TrackerState.route

    // View layar tracking. Sebagian hanya ada di mode tertentu, jadi dibuat nullable.
    private var gauge: GaugeView? = null
    private var ring: RingView? = null
    private var targetBar: SegBar? = null
    private var targetValue: TextView? = null
    private var targetPct: TextView? = null
    private var targetInfo: TextView? = null
    private var gpsText: TextView? = null
    private var distanceText: TextView? = null
    private var timeText: TextView? = null
    private var avgText: TextView? = null
    private var maxText: TextView? = null
    private var kcalText: TextView? = null
    private var climbText: TextView? = null
    private var speedText: TextView? = null
    private var cadenceText: TextView? = null
    private var paceNowText: TextView? = null
    private var paceAvgText: TextView? = null
    private var paceBestText: TextView? = null
    private var ringInfo: TextView? = null
    private var splitsBox: LinearLayout? = null
    private var trackMap: MapPanel? = null
    private var chartMap: MapPanel? = null
    private var chartDistText: TextView? = null
    private var chartTimeText: TextView? = null
    private var chartKcalText: TextView? = null
    private var routeDistText: TextView? = null
    private var routeTimeText: TextView? = null
    private var routeGauge: GaugeView? = null
    private var routeAvgText: TextView? = null
    private var routeMaxText: TextView? = null
    private var routeKcalText: TextView? = null
    private var chart: ChartView? = null
    private var chartAvgText: TextView? = null
    private var chartMaxText: TextView? = null

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

    /** Dipanggil dari navigasi bawah di layar peta: buka tab yang dipilih. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent?.getIntExtra("tab", -1) ?: -1) {
            0 -> showHome()
            2 -> showHistory()
            3 -> showSettings()
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
        trackMap?.onResume(); chartMap?.onResume()
        if (running && !paused && currentTab == -1) {
            handler.removeCallbacks(timer)
            handler.post(timer)
        }
    }

    override fun onPause() {
        trackMap?.onPause(); chartMap?.onPause()
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
                if (TrackerState.restore(this)) { trackPage = 0; showTracking() } else TrackerState.clearSaved(this)
            }
            .setNegativeButton("Buang") { _, _ -> TrackerState.clearSaved(this) }
            .setCancelable(false)
            .show()
    }

    private fun applyTheme() {
        t = Themes.current(this)
        Themes.styleWindow(this, t)
    }

    private fun acc(m: String = mode) = Themes.modeAccent(m)

    private fun dim(c: Int) = (c and 0x00FFFFFF) or 0x66000000

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
        trackMap?.release(); trackMap = null; chartMap?.release(); chartMap = null
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

    private fun stepper(
        label: String, sub: String, value: String, minus: () -> Unit, plus: () -> Unit,
        color: Int = t.accent
    ): LinearLayout {
        val row = card(t.surface, 12, 16).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(tv(label, 13f, t.text, true))
        left.add(tv(sub, 11f, t.muted), 2)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(circleBtn("−", minus))
        row.addView(mono(tv(value, 16f, color, true)).apply {
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

    private fun weekData(m: String): Pair<FloatArray, List<Rec>> {
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
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val c2 = Calendar.getInstance()
        for (line in rawRecords()) {
            val r = parse(line) ?: continue
            if (r.mode != m) continue
            val d = (try { fmt.parse(r.date) } catch (e: Exception) { null }) ?: continue
            if (d.time < start) continue
            c2.time = d
            out[(c2.get(Calendar.DAY_OF_WEEK) + 5) % 7] += if (m == "Jalan") r.steps.toFloat() else r.km
            week.add(r)
        }
        return Pair(out, week)
    }

    /** Angka pendamping di kartu perjalanan, sesuai fungsi tiap aktivitas. */
    private fun secondary(rec: Rec): String = when (rec.mode) {
        "Lari" -> Fmt.pace(rec.paceSec) + " /km"
        "Jalan" -> Fmt.int(rec.steps) + " langkah"
        else -> Fmt.dec(rec.avgF, 1) + " km/jam"
    }

    private fun tripCard(rec: Rec, detailed: Boolean, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null): LinearLayout {
        val a = acc(rec.mode)
        val c = card(t.surface, 10, 16).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        c.addView(RouteView(this, t, true).apply { points = rec.route; accent = a }, LinearLayout.LayoutParams(dp(64), dp(48)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(tv("${icon(rec.mode)}  ${rec.mode}", 13f, t.text, true))
        mid.add(tv(rec.date, 11f, t.muted), 2)
        if (detailed) mid.add(tv("${formatTime(rec.elapsed)} • ${secondary(rec)}", 11f, t.muted), 2)
        c.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(tv(Fmt.dec(rec.km, 2) + " km", 16f, a, true))
        if (detailed) {
            if (rec.target > 0f) {
                right.add(tv(if (rec.reached) "✓ Target tercapai" else "Target belum", 11f,
                    if (rec.reached) t.good else t.muted), 2)
            }
        } else {
            right.add(tv(secondary(rec), 11f, t.muted), 2)
        }
        c.addView(right)
        if (onClick != null) c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener { onLong(); true }
        return c
    }

    // ---------- Screens ----------
    private fun greeting(m: String) = when (m) {
        "Sepeda" -> "Siap gowes hari ini?"
        "Lari" -> "Siap lari hari ini?"
        else -> "Siap jalan hari ini?"
    }

    private fun startLabel(m: String) = when (m) { "Sepeda" -> "BERSEPEDA"; "Lari" -> "LARI"; else -> "JALAN" }

    private fun targetSub(m: String) = when (m) {
        "Sepeda" -> "Jarak per perjalanan"
        "Lari" -> "Jarak per lari"
        else -> "Langkah per hari"
    }

    /** Beranda: tiga halaman (Sepeda, Lari, Jalan) yang digeser ke samping. */
    private fun showHome() {
        trackMap?.release(); trackMap = null; chartMap?.release(); chartMap = null
        currentTab = 0
        inDetail = false
        val w = resources.displayMetrics.widthPixels
        homePage = modes.indexOf(mode).coerceAtLeast(0)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(4))
            setBackgroundColor(t.bg)
        }
        val small = tv("R I D E T R A C K   P R O", 11f, acc(), true)
        val big = tv(greeting(mode), 24f, t.text, true)
        header.addView(small)
        header.add(big, 2)

        val tabRow = LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = box(t.surface, 16, true)
        }
        fun styleTab(v: TextView, selected: Boolean, m: String) {
            val a = acc(m)
            v.setTextColor(if (selected) onColor(a) else t.muted)
            v.background = if (selected) box(a, 12) else null
        }
        modes.forEachIndexed { i, m ->
            val chip = tv(icon(m) + "  " + m, 13f, t.muted, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { if (!running && mode != m) { mode = m; showHome() } }
            }
            styleTab(chip, i == homePage, m)
            tabRow.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) leftMargin = dp(4) })
        }
        header.add(tabRow, 12)

        val body = homePageView(modes[homePage])

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
        }
        outer.addView(header)
        outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(navBar(0))
        setContentView(outer)
        outer.requestApplyInsets()
    }

    /** Isi satu halaman beranda untuk satu aktivitas. */
    private fun homePageView(m: String): ScrollView {
        val a = acc(m)
        val r = column()
        val (week, recs) = weekData(m)

        val todayStr0 = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayRecs = recs.filter { it.date.startsWith(todayStr0) }
        val tgt = Themes.target(this, m).toFloat().coerceAtLeast(1f)
        val todayKm = todayRecs.sumOf { it.km.toDouble() }.toFloat()
        val todaySt = todayRecs.sumOf { it.steps }
        val todayMs = todayRecs.sumOf { it.elapsed }
        val hc = card(t.surface, 14, 14)
        val hrow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val ring0 = RingView(this, t, a).apply {
            label = "HARI INI"
            if (m == "Jalan") { value = Fmt.int(todaySt); sub = "langkah"; fraction = todaySt / tgt }
            else { value = Fmt.dec(todayKm, 1); sub = "km"; fraction = todayKm / tgt }
        }
        hrow.addView(ring0, LinearLayout.LayoutParams(dp(112), dp(112)))
        val facts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fact(k: String, v: String) {
            facts.addView(tv(k, 11f, t.muted))
            facts.addView(mono(tv(v, 17f, t.text, true)), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        }
        fact("Target", Themes.targetLabel(m, Themes.target(this, m)))
        fact("Durasi hari ini", Fmt.hm(todayMs))
        fact("Aktivitas hari ini", todayRecs.size.toString() + "x")
        hrow.addView(facts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(18) })
        hc.addView(hrow)
        r.add(hc, 6)

        val wc = card(t.surface, 14, 18)
        val top = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(tv("Minggu ini", 11f, t.muted))
        val total = week.sum()
        val totalText = if (m == "Jalan") Fmt.int(total.roundToInt()) + " langkah" else Fmt.dec(total, 1) + " km"
        left.addView(mono(tv(totalText, 24f, a, true)))
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val countText = when (m) {
            "Sepeda" -> "${recs.size} perjalanan"
            "Lari" -> "${recs.size} kali lari"
            else -> "${week.count { it > 0f }} hari berjalan"
        }
        top.addView(tv(countText, 11f, t.muted).apply { gravity = Gravity.END })
        wc.addView(top)

        val maxV = maxOf(week.maxOrNull() ?: 0f, 1f)
        val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        val dayNames = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val bars = LinearLayout(this)
        for (i in 0..6) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            val h = if (week[i] > 0f) maxOf(dp(4), (week[i] / maxV * dp(44)).toInt()) else dp(3)
            val barColor = if (i == today) a else if (week[i] > 0f) dim(a) else t.surface2
            col.addView(View(this).apply { background = box(barColor, 4) }, LinearLayout.LayoutParams(-1, h))
            col.addView(tv(dayNames[i], 11f, if (i == today) a else t.muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            bars.addView(col, LinearLayout.LayoutParams(0, dp(68), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        wc.add(bars, 10)
        r.add(wc, 10)

        val totalKm = recs.sumOf { it.km.toDouble() }.toFloat()
        val totalMs = recs.sumOf { it.elapsed }
        val hours = totalMs / 3600000f
        val metrics = when (m) {
            "Sepeda" -> Pair(
                statCard("Kecepatan rata-rata",
                    if (hours > 0f) Fmt.dec(totalKm / hours, 1) + " km/jam" else "0 km/jam"),
                statCard("Tanjakan minggu ini",
                    recs.sumOf { (it.climb.toFloatOrNull() ?: 0f).toDouble() }.roundToInt().toString() + " m")
            )
            "Lari" -> Pair(
                statCard("Pace rata-rata",
                    if (totalKm > 0.05f) Fmt.pace(((totalMs / 1000f) / totalKm).roundToInt()) + " /km" else "--:-- /km"),
                statCard("Terjauh minggu ini", Fmt.dec(recs.maxOfOrNull { it.km } ?: 0f, 1) + " km")
            )
            else -> {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val todaySteps = recs.filter { it.date.startsWith(todayStr) }.sumOf { it.steps }
                Pair(
                    statCard("Langkah hari ini", Fmt.int(todaySteps), a),
                    statCard("Durasi minggu ini", Fmt.hm(totalMs))
                )
            }
        }
        statRow(r, metrics.first, metrics.second, 10)

        r.add(stepper("◎ Target hari ini", targetSub(m), Themes.targetLabel(m, Themes.target(this, m)),
            { Themes.stepTarget(this, m, -1); showHome() },
            { Themes.stepTarget(this, m, 1); showHome() }, a), 10)

        if (running) {
            r.add(btn("●   KEMBALI KE AKTIVITAS", a, 30) { showTracking() }, 10, 0f, dp(58))
        } else {
            r.add(btn("▶   MULAI " + startLabel(m), a, 30) { startTracking(m) }, 10, 0f, dp(58))
        }

        val last = rawRecords().mapIndexedNotNull { i, line -> parse(line)?.let { i to it } }
            .filter { it.second.mode == m }.take(2)
        if (last.isNotEmpty()) {
            r.add(tv("Terakhir · $m", 11f, t.muted, true), 16)
            for ((idx, rec) in last) r.add(tripCard(rec, false, { showDetail(idx, rec) }), 8)
        }
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(t.bg)
            addView(r)
        }
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

    private fun startTracking(m: String = mode) {
        if (running) return
        mode = m
        trackPage = 0
        if (!hasFine()) {
            pendingResume = false
            askPermissions()
            return
        }
        TrackerState.start(Themes.target(this, mode))
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

    private fun resetTrackViews() {
        gauge = null; ring = null; targetBar = null; targetValue = null; targetPct = null
        targetInfo = null; gpsText = null; distanceText = null; timeText = null; avgText = null
        maxText = null; kcalText = null; climbText = null; speedText = null; cadenceText = null
        paceNowText = null; paceAvgText = null; paceBestText = null; ringInfo = null; splitsBox = null
        trackMap?.release(); trackMap = null; chartMap?.release(); chartMap = null
        chartDistText = null; chartTimeText = null; chartKcalText = null; routeDistText = null; routeTimeText = null
        routeGauge = null; routeAvgText = null; routeMaxText = null; routeKcalText = null
        chart = null; chartAvgText = null; chartMaxText = null
    }

    private fun trackScroll(col: LinearLayout) = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(t.bg)
        addView(col)
    }

    /** Halaman "Rute": peta sungguhan (Peta/Satelit) dengan garis rute langsung + tombol ke peta lengkap. */
    private fun buildRoutePage(a: Int): LinearLayout {
        val col = column()
        val g = GaugeView(this, t).apply {
            scaleMax = scaleFor(mode)
            accent = a
            scale = 0.5f
            digital = true
        }
        routeGauge = g
        col.add(g, 0)

        val avgC = statCard("Rata-rata", "0,0 km/jam", t.text)
        val maxC = statCard("Maksimum", "0,0 km/jam", t.text)
        routeAvgText = avgC.second
        routeMaxText = maxC.second
        statRow(col, avgC, maxC, 4)

        val disC = statCard("Jarak", "0,00 km")
        val kalC = statCard("Kalori", "0 kkal")
        val timC = statCard("Timer", "00:00:00")
        routeDistText = disC.second
        routeKcalText = kalC.second
        routeTimeText = timC.second
        val row = LinearLayout(this)
        row.addView(disC.first, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(kalC.first, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        row.addView(timC.first, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        col.add(row, 8)

        val mp = MapPanel(this, t, a)
        trackMap = mp
        col.addView(mp, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        return col
    }

    /** Halaman "Grafik": kecepatan (atau pace untuk Lari) sepanjang aktivitas. */
    private fun buildChartPage(m: String, a: Int): LinearLayout {
        val col = column()

        // Peta mini dengan pin Start dan Stop
        val mp = MapPanel(this, t, a, true)
        chartMap = mp
        col.addView(mp, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(4) })

        // Kartu ringkasan aktivitas
        val sc = card(t.surface, 14, 18)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(icon(m), 22f, a).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(tv(m, 16f, t.text, true))
        ht.addView(tv(SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("id-ID")).format(Date()), 12f, t.muted))
        head.addView(ht)
        sc.addView(head)

        val avgLabel = if (m == "Lari") "Pace rata-rata" else "Rata-rata"
        val maxLabel = when (m) { "Lari" -> "Split terbaik"; "Jalan" -> "Irama"; else -> "Maksimum" }
        val disC = statCard("Jarak", "0,00 km")
        val timC = statCard("Durasi", "00:00:00")
        val avgC = statCard(avgLabel, "--", t.text)
        val maxC = statCard(maxLabel, "--", t.text)
        val kalC = statCard("Kalori", "0 kkal", Themes.modeAccent("Lari"))
        chartDistText = disC.second
        chartTimeText = timC.second
        chartAvgText = avgC.second
        chartMaxText = maxC.second
        chartKcalText = kalC.second
        statRow(sc, disC, timC, 12)
        statRow(sc, avgC, maxC, 8)
        statRow(sc, kalC, Pair<View, TextView>(View(this), TextView(this)), 8)
        col.add(sc, 8)

        // Grafik kecepatan
        val cc = card(t.surface, 14, 18)
        cc.addView(tv(if (m == "Lari") "Grafik Pace (menit/km)" else "Grafik Kecepatan", 14f, t.text, true))
        val ch = ChartView(this, t, a)
        if (m == "Lari") {
            ch.yTitle = "min/km"
            ch.label = { v -> if (v < 1.5f) "" else Fmt.pace((3600f / v).roundToInt()) }
        }
        chart = ch
        cc.add(ch, 6, 0f, dp(220))
        col.add(cc, 8)
        return col
    }

    /**
     * Layar tracking, tiga halaman yang digeser ke samping: Utama (berbeda tiap aktivitas),
     * Rute, dan Grafik. Tombol SELESAI/JEDA tetap di bawah.
     */
    private fun showTracking() {
        resetTrackViews()
        currentTab = -1
        inDetail = false
        val m = mode
        val a = acc(m)
        val w = resources.displayMetrics.widthPixels

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), 0)
            setBackgroundColor(t.bg)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv(icon(m) + "  " + m, 14f, a, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = box((a and 0x00FFFFFF) or 0x26000000, 22)
        }, LinearLayout.LayoutParams(-2, dp(44)))
        val gps = tv(gpsStatus, 11f, t.good, true).apply { gravity = Gravity.CENTER }
        gpsText = gps
        top.addView(gps, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv("⌖", 20f, t.text).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.surface) }
            setOnClickListener { openMap() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(top)

        val tabViews = ArrayList<TextView>()
        lateinit var pager: PagerScroll
        fun styleTab(v: TextView, selected: Boolean) {
            v.setTextColor(if (selected) a else t.muted)
            v.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            v.paint.isUnderlineText = selected
        }
        val tabRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf("Utama", "Rute", "Grafik").forEachIndexed { i, name ->
            val v = tv(name, 14f, t.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(6))
                setOnClickListener { pager.goTo(i, true) }
            }
            styleTab(v, i == trackPage)
            tabViews.add(v)
            tabRow.addView(v)
        }
        header.add(tabRow, 4)

        pager = PagerScroll(this, w) { p ->
            trackPage = p
            tabViews.forEachIndexed { i, v -> styleTab(v, i == p) }
        }
        val main = column()
        when (m) {
            "Lari" -> buildRunBody(main, a)
            "Jalan" -> buildWalkBody(main, a)
            else -> buildBikeBody(main, a)
        }
        val strip = LinearLayout(this)
        strip.addView(trackScroll(main), LinearLayout.LayoutParams(w, -1))
        strip.addView(trackScroll(buildRoutePage(a)), LinearLayout.LayoutParams(w, -1))
        strip.addView(trackScroll(buildChartPage(m, a)), LinearLayout.LayoutParams(w, -1))
        pager.addView(strip)

        val row = LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), dp(16))
            setBackgroundColor(t.bg)
        }
        row.addView(btn("SELESAI", t.bad, 28) { finishTracking() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(btn(if (paused) "LANJUT" else "JEDA", a, 28) {
            if (paused) resumeTracking() else pauseTracking()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(10) })

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
        }
        outer.addView(header)
        outer.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(row)
        outer.addView(navBar(-1))
        setContentView(outer)
        outer.requestApplyInsets()
        pager.post { pager.goTo(trackPage, false) }
        refreshTracking()
    }

    /** Sepeda: speedometer besar (ketuk untuk analog/digital), target jarak, enam statistik. */
    private fun buildBikeBody(r: LinearLayout, a: Int) {
        val g = GaugeView(this, t).apply {
            scaleMax = scaleFor("Sepeda")
            digital = digitalMode
            accent = a
            setOnClickListener { digitalMode = !digitalMode; digital = digitalMode; invalidate() }
        }
        gauge = g
        r.add(g, 6)

        val tc = card(t.surface, 12, 16)
        val head = LinearLayout(this)
        head.addView(tv("◎ Target bersepeda", 12f, t.muted), LinearLayout.LayoutParams(0, -2, 1f))
        val pct = tv("0%", 12f, t.good, true)
        targetPct = pct
        head.addView(pct)
        tc.addView(head)
        val vrow = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        val value = mono(tv("0,00", 22f, a, true))
        targetValue = value
        vrow.addView(value)
        vrow.addView(tv("  / ${TrackerState.target} km", 13f, t.muted))
        tc.add(vrow, 4)
        val bar = SegBar(this, t).apply { color = a }
        targetBar = bar
        tc.add(bar, 8, 0f, dp(10))
        val info = tv("", 11f, t.muted)
        targetInfo = info
        tc.add(info, 6)
        r.add(tc, 4)

        val maxC = statCard("Kecepatan maks.", "0,0 km/jam", a)
        val avgC = statCard("Rata-rata", "0,0 km/jam", t.good)
        val disC = statCard("Jarak", "0,00 km")
        val timC = statCard("Durasi", "00:00:00")
        val clmC = statCard("Tanjakan", "0 m")
        val kalC = statCard("Kalori", "0 kkal")
        maxText = maxC.second; avgText = avgC.second; distanceText = disC.second
        timeText = timC.second; climbText = clmC.second; kcalText = kalC.second
        statRow(r, maxC, avgC, 10)
        statRow(r, disC, timC)
        statRow(r, clmC, kalC)
    }

    /** Lari: pace sebagai angka utama, split per km, target jarak. */
    private fun buildRunBody(r: LinearLayout, a: Int) {
        val hero = card(t.surface, 16, 22).apply { gravity = Gravity.CENTER_HORIZONTAL }
        hero.addView(tv("PACE SAAT INI", 11f, t.muted, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        })
        val pn = mono(tv("--:--", 64f, a, true)).apply { gravity = Gravity.CENTER }
        paceNowText = pn
        val prow = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
        prow.addView(pn)
        prow.addView(tv(" /km", 16f, t.muted, true).apply { setPadding(0, 0, 0, dp(10)) })
        hero.addView(prow)
        val sub = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val pa = tv("", 13f, t.muted)
        val pb = tv("", 13f, t.muted)
        paceAvgText = pa
        paceBestText = pb
        sub.addView(pa)
        sub.addView(pb, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(18) })
        hero.add(sub, 4)
        r.add(hero, 10)

        val disC = statCard("Jarak", "0,00 km")
        val timC = statCard("Durasi", "00:00:00")
        disC.second.textSize = 26f
        timC.second.textSize = 26f
        distanceText = disC.second
        timeText = timC.second
        statRow(r, disC, timC, 8)

        val sc = card(t.surface, 12, 16)
        val sh = LinearLayout(this)
        sh.addView(tv("Split per km", 11f, t.muted), LinearLayout.LayoutParams(0, -2, 1f))
        sh.addView(tv("pace /km", 11f, t.muted))
        sc.addView(sh)
        val sb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        splitsBox = sb
        sc.add(sb, 2)
        r.add(sc, 8)

        val tc = card(t.surface, 12, 16)
        val head = LinearLayout(this)
        val label = tv("", 12f, t.muted)
        targetInfo = label
        head.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        val pct = tv("0%", 12f, t.good, true)
        targetPct = pct
        head.addView(pct)
        tc.addView(head)
        val bar = SegBar(this, t).apply { color = a }
        targetBar = bar
        tc.add(bar, 8, 0f, dp(10))
        r.add(tc, 8)

        val spC = statCard("Kecepatan", "0,0 km/jam")
        val kalC = statCard("Kalori", "0 kkal")
        speedText = spC.second
        kcalText = kalC.second
        statRow(r, spC, kalC, 8)
    }

    /** Jalan: cincin langkah menuju target harian (langkah diperkirakan dari jarak GPS). */
    private fun buildWalkBody(r: LinearLayout, a: Int) {
        val rc = card(t.surface, 16, 22).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val rv = RingView(this, t, a)
        ring = rv
        rc.addView(rv, LinearLayout.LayoutParams(dp(230), dp(230)))
        val ri = tv("", 13f, t.muted).apply { gravity = Gravity.CENTER }
        ringInfo = ri
        rc.add(ri, 8)
        r.add(rc, 10)

        val disC = statCard("Jarak", "0,00 km")
        val timC = statCard("Durasi", "00:00:00")
        val avgC = statCard("Rata-rata", "0,0 km/jam")
        val cadC = statCard("Irama", "0 langkah/mnt")
        val kalC = statCard("Kalori", "0 kkal")
        val clmC = statCard("Tanjakan", "0 m")
        distanceText = disC.second; timeText = timC.second; avgText = avgC.second
        cadenceText = cadC.second; kcalText = kalC.second; climbText = clmC.second
        statRow(r, disC, timC, 8)
        statRow(r, avgC, cadC)
        statRow(r, kalC, clmC)
    }

    private fun splitRow(
        container: LinearLayout, label: String, frac: Float, barColor: Int,
        right: String, highlight: Boolean, a: Int
    ) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv(label, 12f, if (highlight) a else t.muted, highlight), LinearLayout.LayoutParams(dp(46), -2))
        val bar = LinearLayout(this).apply { background = box(t.surface2, 6) }
        val f = frac.coerceIn(0f, 1f)
        bar.addView(View(this).apply { background = box(barColor, 6) }, LinearLayout.LayoutParams(0, -1, f))
        bar.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f - f))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(12), 1f))
        row.addView(mono(tv(right, 14f, if (highlight) a else t.text, true)).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(dp(64), -2).apply { leftMargin = dp(10) })
        container.add(row, 6)
    }

    /** Tiga km terakhir yang selesai + km yang sedang berjalan. Pace lebih cepat = batang lebih panjang. */
    private fun fillSplits(container: LinearLayout, a: Int) {
        container.removeAllViews()
        val sp = TrackerState.splits
        val fast = sp.minOrNull()
        val slow = sp.maxOrNull()
        for (i in maxOf(0, sp.size - 3) until sp.size) {
            val s = sp[i]
            val f = if (fast == null || slow == null || fast == slow) 0.9f
            else 0.5f + 0.5f * (slow - s).toFloat() / (slow - fast).toFloat()
            val best = sp.size > 1 && s == fast
            splitRow(container, "Km ${i + 1}", f, if (best) a else dim(a), Fmt.pace(s), best, a)
        }
        val rem = (distanceMeters % 1000f) / 1000f
        splitRow(container, "Km ${sp.size + 1}", rem, dim(a), Fmt.dec(rem, 2) + " km", false, a)
    }

    private fun currentElapsed(): Long = TrackerState.currentElapsed()

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun averageSpeed(): Float = TrackerState.averageSpeed()

    private fun calories(): Int = TrackerState.calories(Themes.weight(this))

    private fun refreshTracking() {
        if (distanceText == null) return
        gpsText?.text = gpsStatus
        val km = distanceMeters / 1000f
        val target = TrackerState.target
        val frac = (TrackerState.progressValue() / target).coerceIn(0f, 1f)
        val pct = "${(frac * 100).toInt()}%"

        distanceText?.text = Fmt.dec(km, 2) + " km"
        timeText?.text = Fmt.time(currentElapsed())
        kcalText?.text = "${calories()} kkal"
        climbText?.text = "${climbM.roundToInt()} m"
        targetBar?.let { it.fraction = frac; it.invalidate() }
        targetPct?.text = pct

        val rl = route.toList()
        trackMap?.sync(rl)
        chartMap?.sync(rl)
        routeDistText?.text = Fmt.dec(km, 2) + " km"
        routeTimeText?.text = Fmt.time(currentElapsed())
        routeKcalText?.text = "${calories()} kkal"
        chartDistText?.text = Fmt.dec(km, 2) + " km"
        chartTimeText?.text = Fmt.time(currentElapsed())
        chartKcalText?.text = "${calories()} kkal"
        routeAvgText?.text = Fmt.dec(averageSpeed(), 1) + " km/jam"
        routeMaxText?.text = Fmt.dec(maxSpeedKmh, 1) + " km/jam"
        routeGauge?.let { it.speed = speedKmh; it.maxSpeed = maxSpeedKmh; it.invalidate() }
        chart?.let {
            it.values = TrackerState.speedSamples.toList()
            it.minutes = TrackerState.chartMinutes()
            it.invalidate()
        }

        when (mode) {
            "Lari" -> {
                paceNowText?.text = Fmt.pace(TrackerState.paceNowSec())
                paceAvgText?.text = "Rata-rata " + Fmt.pace(TrackerState.avgPaceSec())
                paceBestText?.text = "Split terbaik " + Fmt.pace(TrackerState.bestSplitSec())
                speedText?.text = Fmt.dec(speedKmh, 1) + " km/jam"
                targetInfo?.text = "Target lari · " + Fmt.dec(km, 2) + " / " + target + " km"
                splitsBox?.let { fillSplits(it, acc("Lari")) }
                chartAvgText?.text = Fmt.pace(TrackerState.avgPaceSec()) + " /km"
                chartMaxText?.text = Fmt.pace(TrackerState.bestSplitSec()) + " /km"
            }
            "Jalan" -> {
                val steps = TrackerState.steps()
                ring?.let {
                    it.fraction = frac
                    it.value = Fmt.int(steps)
                    it.sub = "dari " + Fmt.int(target) + " · " + pct
                    it.invalidate()
                }
                avgText?.text = Fmt.dec(averageSpeed(), 1) + " km/jam"
                val mins = currentElapsed() / 60000f
                val cadence = if (mins >= 0.5f) (steps / mins).roundToInt() else 0
                cadenceText?.text = "$cadence langkah/mnt"
                chartAvgText?.text = Fmt.dec(averageSpeed(), 1) + " km/jam"
                chartMaxText?.text = "$cadence langkah/mnt"
                ringInfo?.text = if (steps >= target) {
                    "✓ Target tercapai, kerja bagus!"
                } else {
                    val remain = target - steps
                    "Sisa " + Fmt.int(remain) + " langkah" +
                        (if (cadence >= 30) " • sekitar " + (remain / cadence.toFloat()).roundToInt() + " menit lagi" else "")
                }
            }
            else -> {
                gauge?.let {
                    it.speed = speedKmh
                    it.maxSpeed = maxSpeedKmh
                    it.invalidate()
                }
                avgText?.text = Fmt.dec(averageSpeed(), 1) + " km/jam"
                maxText?.text = Fmt.dec(maxSpeedKmh, 1) + " km/jam"
                chartAvgText?.text = Fmt.dec(averageSpeed(), 1) + " km/jam"
                chartMaxText?.text = Fmt.dec(maxSpeedKmh, 1) + " km/jam"
                targetValue?.text = Fmt.dec(km, 2)
                targetInfo?.text = if (km >= target) {
                    "✓ Target tercapai, kerja bagus!"
                } else {
                    val remain = target - km
                    val avg = averageSpeed()
                    val eta = if (avg > 1f) (remain / avg * 60f).roundToInt() else -1
                    "Sisa " + Fmt.dec(remain, 2) + " km" + (if (eta >= 0) " • sekitar $eta menit lagi" else "")
                }
            }
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
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val record = listOf(
            date, mode,
            String.format(Locale.US, "%.3f", distanceMeters / 1000f),
            elapsed.toString(),
            String.format(Locale.US, "%.1f", averageSpeed()),
            String.format(Locale.US, "%.1f", maxSpeedKmh),
            TrackerState.target.toString(),
            encodeRoute(),
            calories().toString(),
            climbM.roundToInt().toString()
        ).joinToString("|")
        val list = rawRecords()
        list.add(0, record)
        writeRecords(list)
    }

    private fun showSummary(elapsed: Long) {
        val m = mode
        val a = acc(m)
        val r = column()
        title(r, "RINGKASAN AKTIVITAS", "Kerja bagus! 🎉")

        val km = distanceMeters / 1000f
        val hero = card(a, 18, 24)
        val on = onColor(a)
        hero.addView(tv("✓  PERJALANAN TERSIMPAN", 11f, on, true))
        hero.add(tv("${icon(m)}  $m", 16f, on, true), 8)
        val big = if (m == "Jalan") Fmt.int(TrackerState.steps()) + " langkah" else Fmt.dec(km, 2) + " km"
        hero.add(mono(tv(big, 34f, on, true)), 2)
        if (m == "Jalan") hero.add(tv(Fmt.dec(km, 2) + " km", 14f, on), 2)
        r.add(hero, 14)

        r.add(RouteView(this, t).apply {
            points = route.toList(); hint = "Rute tidak terekam"; accent = a
        }, 10, 0f, dp(130))

        val target = TrackerState.target
        val prog = TrackerState.progressValue()
        val reached = prog >= target
        val label = Themes.targetLabel(m, target) + if (m == "Jalan") " langkah" else ""
        val tc = card(t.surface, 12, 16)
        tc.addView(tv(
            if (reached) "✓ Target $label tercapai"
            else "Target $label • tercapai ${(prog / target * 100f).roundToInt()}%",
            14f, if (reached) t.good else t.text, true))
        r.add(tc, 10)

        val avgCard = when (m) {
            "Lari" -> statCard("Pace rata-rata", Fmt.pace(TrackerState.avgPaceSec()) + " /km", t.good)
            "Jalan" -> statCard("Langkah", Fmt.int(TrackerState.steps()), t.good)
            else -> statCard("Rata-rata", Fmt.dec(averageSpeed(), 1) + " km/jam", t.good)
        }
        statRow(r, statCard("Durasi", formatTime(elapsed)), avgCard, 10)
        val peakCard = when (m) {
            "Lari" -> statCard("Split terbaik", Fmt.pace(TrackerState.bestSplitSec()) + " /km", a)
            "Jalan" -> statCard("Rata-rata", Fmt.dec(averageSpeed(), 1) + " km/jam", a)
            else -> statCard("Kecepatan maks.", Fmt.dec(maxSpeedKmh, 1) + " km/jam", a)
        }
        statRow(r, peakCard, statCard("Kalori", "${calories()} kkal"))
        statRow(r, statCard("Tanjakan", "${climbM.roundToInt()} m"),
            statCard("Jarak", Fmt.dec(km, 2) + " km"))

        r.add(btn("⌂   KEMBALI KE BERANDA", a, 28) { showHome() }, 16, 0f, dp(54))
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
        cell("Total jarak", Fmt.dec(all.sumOf { it.second.km.toDouble() }, 1) + " km", t.accent)
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
        val a = acc(rec.mode)
        val r = column()
        title(r, "DETAIL AKTIVITAS", rec.date)

        val hero = card(a, 18, 24)
        val on = onColor(a)
        hero.addView(tv("${icon(rec.mode)}  ${rec.mode}", 16f, on, true))
        hero.add(mono(tv(Fmt.dec(rec.km, 2) + " km", 34f, on, true)), 2)
        r.add(hero, 14)

        r.add(RouteView(this, t).apply {
            points = rec.route; hint = "Rute tidak terekam"; accent = a
        }, 10, 0f, dp(150))

        if (rec.target > 0f) {
            val tc = card(t.surface, 12, 16)
            tc.addView(tv(
                if (rec.reached) "✓ Target ${rec.targetText} tercapai"
                else "Target ${rec.targetText} belum tercapai",
                14f, if (rec.reached) t.good else t.text, true))
            r.add(tc, 10)
        }

        val second = when (rec.mode) {
            "Lari" -> statCard("Pace rata-rata", Fmt.pace(rec.paceSec) + " /km", t.good)
            else -> statCard("Rata-rata", Fmt.dec(rec.avgF, 1) + " km/jam", t.good)
        }
        statRow(r, statCard("Durasi", formatTime(rec.elapsed)), second, 10)
        statRow(r, statCard("Kecepatan maks.", Fmt.dec(rec.maxF, 1) + " km/jam", a),
            statCard("Kalori", "${rec.kcal} kkal"))
        statRow(r, statCard("Tanjakan", "${rec.climb} m"),
            if (rec.mode == "Jalan") statCard("Langkah", Fmt.int(rec.steps))
            else statCard("Titik rute", rec.route.size.toString()))

        if (rec.route.isNotEmpty()) {
            r.add(btn("⌖   LIHAT DI PETA", a, 28) {
                val flat = DoubleArray(rec.route.size * 2)
                rec.route.forEachIndexed { i, p -> flat[i * 2] = p[0]; flat[i * 2 + 1] = p[1] }
                try {
                    startActivity(
                        Intent(this, MapActivity::class.java)
                            .putExtra("route", flat)
                            .putExtra("mode", rec.mode)
                            .putExtra("km", rec.km)
                            .putExtra("elapsed", rec.elapsed)
                            .putExtra("avg", rec.avgF)
                            .putExtra("max", rec.maxF)
                    )
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
        for (m in modes) {
            r.add(stepper("${icon(m)} Target $m", targetSub(m), Themes.targetLabel(m, Themes.target(this, m)),
                { Themes.stepTarget(this, m, -1); showSettings() },
                { Themes.stepTarget(this, m, 1); showSettings() }, acc(m)), 8)
        }
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
        trackMap?.release(); trackMap = null; chartMap?.release(); chartMap = null
        super.onDestroy()
    }
}
