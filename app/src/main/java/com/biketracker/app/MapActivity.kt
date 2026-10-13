package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.Toast
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Peta GPS (OpenStreetMap / Satelit + Leaflet). Tiga mode:
 * 1. Riwayat   : menampilkan rute satu perjalanan (extra "route" = lat,lon,lat,lon,... + ringkasan)
 * 2. Live      : saat tracking berjalan, rute diambil dari TrackerState
 * 3. Biasa     : menampilkan posisi saat ini
 */
class MapActivity : Activity() {

    private lateinit var t: AppTheme
    private lateinit var mapView: WebView
    private lateinit var statusText: TextView
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var accentColor = 0
    private var accentHex = "#26be7d"
    private var mode = "Sepeda"
    private var pageReady = false
    private var viewRoute: List<DoubleArray>? = null
    private var sent = 0
    private var firstFix = true
    private var ownGps = false
    private var satellite = false

    private val tabs = ArrayList<TextView>()
    private var distV: TextView? = null
    private var timeV: TextView? = null
    private var labelC: TextView? = null
    private var valueC: TextView? = null
    private var labelD: TextView? = null
    private var valueD: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            liveUpdate()
            handler.postDelayed(this, 1000)
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLoc = location
            updateSurvey(location)
            if (!mapFree()) return
            if (location.accuracy > 30f) {
                statusText.text = "GPS mencari sinyal yang lebih akurat..."
                return
            }
            statusText.text = "Posisi saat ini • GPS ±${location.accuracy.toInt()} m"
            if (pageReady) {
                mapView.evaluateJavascript(
                    String.format(Locale.US, "setPosition(%.6f,%.6f,%b)", location.latitude, location.longitude, firstFix),
                    null
                )
                firstFix = false
            }
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            if (mapFree()) statusText.text = "GPS aktif • Menunggu lokasi..."
            svStatus?.text = "GPS aktif • Menunggu lokasi..."
        }

        override fun onProviderDisabled(provider: String) {
            if (mapFree()) statusText.text = "GPS mati • Aktifkan lokasi HP"
            svStatus?.text = "GPS mati • Aktifkan lokasi HP"
        }
    }

    /** Dipanggil dari JavaScript (thread lain), jadi selalu dipindah ke main thread. */
    inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            handler.post {
                pageReady = true
                onMapReady()
            }
        }

        @JavascriptInterface
        fun onPlan(dist: Double, count: Int, snapped: Boolean, err: Boolean, json: String) {
            handler.post { onPlanChanged(dist, count, snapped, err, json) }
        }

        @JavascriptInterface
        fun onLongPress(lat: Double, lon: Double) {
            handler.post { longPressDialog(lat, lon) }
        }

        @JavascriptInterface
        fun onMsg(m: String) {
            handler.post { Toast.makeText(this@MapActivity, m, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun onFail() {
            handler.post {
                statusText.text = "Peta butuh internet dan belum bisa dimuat. Rute tetap direkam."
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun mapButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(t.text)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.surface) }
        elevation = dp(4).toFloat()
        setOnClickListener { action() }
    }

    private fun styleTabs() {
        tabs.forEachIndexed { i, v ->
            val sel = (i == 1) == satellite
            v.setTextColor(if (sel) onColor(accentColor) else t.muted)
            v.background = if (sel) round(accentColor, 15) else null
        }
    }

    private fun applyLayer() {
        styleTabs()
        if (pageReady) {
            mapView.evaluateJavascript("setLayer('${if (satellite) "sat" else "osm"}')", null)
        }
    }

    private fun statBox(label: String): Triple<View, TextView, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = round(t.bg, 14)
        }
        val l = TextView(this).apply { text = label; textSize = 11f; setTextColor(t.muted) }
        val v = TextView(this).apply {
            text = "--"; textSize = 17f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(l)
        box.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        return Triple(box, l, v)
    }

    private fun legendChip(color: Int, label: String): View {
        val chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = round(t.bg, 16)
        }
        chip.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }, LinearLayout.LayoutParams(dp(10), dp(10)))
        chip.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        return chip
    }

    private fun goTab(tab: Int) {
        val i = android.content.Intent(this, MainActivity::class.java)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.putExtra("tab", tab)
        startActivity(i)
        finish()
    }

    private fun bottomNav(): View {
        val bar = LinearLayout(this).apply {
            setBackgroundColor(t.surface)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf("⌂" to "Beranda", "⌖" to "Routes", "▤" to "Riwayat", "⚙" to "Atur")
            .forEachIndexed { i, (ic, label) ->
                val sel = i == 1
                val c = if (sel) t.accent else t.muted
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener { if (i != 1) goTab(i) }
                }
                cell.addView(TextView(this).apply {
                    text = ic; textSize = 20f; setTextColor(c); typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
                cell.addView(TextView(this).apply {
                    text = label; textSize = 10f; setTextColor(c)
                    typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    gravity = Gravity.CENTER
                })
                bar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
        return bar
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        t = Themes.current(this)
        Themes.styleWindow(this, t)

        val flat = intent?.getDoubleArrayExtra("route")
        if (flat != null && flat.size >= 2) {
            val list = ArrayList<DoubleArray>()
            var i = 0
            while (i + 1 < flat.size) {
                list.add(doubleArrayOf(flat[i], flat[i + 1]))
                i += 2
            }
            viewRoute = list
        }
        mode = intent?.getStringExtra("mode") ?: TrackerState.mode
        accentColor = Themes.modeAccent(mode)
        accentHex = String.format("#%06X", 0xFFFFFF and accentColor)
        val showPanel = viewRoute != null || TrackerState.running
        val page1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
        }

        // Judul + tombol kembali
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), 0)
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(t.text)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = if (viewRoute != null) "Rute Aktivitas" else "Routes"
            textSize = 18f
            setTextColor(t.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(header)

        // Tab Peta | Satelit
        val tabRow = LinearLayout(this).apply {
            background = round(t.surface, 18)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        listOf("Peta", "Satelit").forEachIndexed { i, name ->
            val v = TextView(this).apply {
                text = name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { satellite = i == 1; applyLayer() }
            }
            tabs.add(v)
            tabRow.addView(v, LinearLayout.LayoutParams(-2, dp(30)))
        }
        styleTabs()

        statusText = TextView(this).apply {
            text = "Menyiapkan peta GPS..."
            textSize = 11f
            setTextColor(t.muted)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = round((t.surface and 0x00FFFFFF) or 0xDD000000.toInt(), 12)
        }

        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            MapHtml.applyCache(this)
            addJavascriptInterface(Bridge(), "Bridge")
            webViewClient = WebViewClient()
            // Geser di dalam peta menggeser peta; geser dari tepi kiri/kanan pindah ke halaman Survei.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val edge = 28f * resources.displayMetrics.density
                    parent?.requestDisallowInterceptTouchEvent(e.x > edge && e.x < v.width - edge)
                }
                false
            }
        }

        // Peta + tombol zoom dan lokasi di sisi kanan
        val mapArea = FrameLayout(this)
        mapArea.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gap = dp(8)
        controls.addView(mapButton("✎") { togglePlan() },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = gap * 2 })
        controls.addView(mapButton("+") {
            if (pageReady) mapView.evaluateJavascript("if(map)map.zoomIn()", null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = gap })
        controls.addView(mapButton("−") {
            if (pageReady) mapView.evaluateJavascript("if(map)map.zoomOut()", null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = gap })
        controls.addView(mapButton("◎") {
            if (pageReady) mapView.evaluateJavascript("locate()", null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        mapArea.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(10); topMargin = dp(10)
        })
        mapArea.addView(tabRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10); topMargin = dp(10)
        })
        mapArea.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10); topMargin = dp(52)
        })
        mapArea.addView(buildPlanBar(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(8)
        })
        page1.addView(mapArea, LinearLayout.LayoutParams(-1, 0, 1f))

        // Ringkasan di bawah peta (hanya untuk rute riwayat dan tracking aktif)
        if (showPanel) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(16))
                background = GradientDrawable().apply {
                    setColor(t.surface)
                    val r = dp(20).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
            }
            val legend = LinearLayout(this)
            legend.addView(legendChip(0xFF27D282.toInt(), "Start"))
            legend.addView(legendChip(0xFFFF3B3B.toInt(), "Stop"),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            legend.addView(legendChip(accentColor, "Garis rute"),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            panel.addView(legend)

            panel.addView(TextView(this).apply {
                text = "Ringkasan Aktivitas"
                textSize = 14f
                setTextColor(t.text)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

            val dist = statBox("Jarak")
            val time = statBox("Durasi")
            val c = statBox("Rata-rata")
            val d = statBox("Maksimum")
            distV = dist.third; timeV = time.third
            labelC = c.second; valueC = c.third
            labelD = d.second; valueD = d.third
            val row1 = LinearLayout(this)
            row1.addView(dist.first, LinearLayout.LayoutParams(0, -2, 1f))
            row1.addView(time.first, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            val row2 = LinearLayout(this)
            row2.addView(c.first, LinearLayout.LayoutParams(0, -2, 1f))
            row2.addView(d.first, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            panel.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            panel.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            page1.addView(panel)

            if (viewRoute != null) {
                updateStats(
                    intent.getFloatExtra("km", 0f),
                    intent.getLongExtra("elapsed", 0L),
                    intent.getFloatExtra("avg", 0f),
                    intent.getFloatExtra("max", 0f)
                )
            }
        }

        // Navigasi bawah, sama seperti di layar utama, supaya tidak hilang saat melihat peta.
        root.addView(page1, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNav())
        setContentView(root)

        mapView.loadDataWithBaseURL(
            "https://www.openstreetmap.org/",
            mapHtml(),
            "text/html",
            "UTF-8",
            null
        )

        // Jika peta belum siap setelah 20 detik, beri tahu pengguna.
        handler.postDelayed({
            if (!pageReady) {
                statusText.text = "Peta belum termuat. Periksa koneksi internet."
            }
        }, 20000)
    }

    /** Isi kartu ringkasan. Dua kartu terakhir menyesuaikan aktivitas. */
    private fun updateStats(km: Float, elapsedMs: Long, avgKmh: Float, maxKmh: Float) {
        distV?.text = Fmt.dec(km, 2) + " km"
        timeV?.text = Fmt.time(elapsedMs)
        when (mode) {
            "Lari" -> {
                val pace = if (avgKmh >= 1.5f) (3600f / avgKmh).roundToInt() else null
                labelC?.text = "Pace rata-rata"
                valueC?.text = Fmt.pace(pace) + " /km"
                labelD?.text = "Maksimum"
                valueD?.text = Fmt.dec(maxKmh, 1) + " km/jam"
            }
            "Jalan" -> {
                labelC?.text = "Langkah"
                valueC?.text = Fmt.int((km * 1000f * TrackerState.STEPS_PER_METER).roundToInt())
                labelD?.text = "Rata-rata"
                valueD?.text = Fmt.dec(avgKmh, 1) + " km/jam"
            }
            else -> {
                labelC?.text = "Rata-rata"
                valueC?.text = Fmt.dec(avgKmh, 1) + " km/jam"
                labelD?.text = "Maksimum"
                valueD?.text = Fmt.dec(maxKmh, 1) + " km/jam"
            }
        }
    }

    /** Dipanggil sekali ketika Leaflet sudah siap. */
    private fun onMapReady() {
        applyLayer()
        val vr = viewRoute
        when {
            vr != null -> {
                statusText.text = String.format(Locale.US, "Rute perjalanan • %d titik", vr.size)
                mapView.evaluateJavascript("showRoute(${toJson(vr, 0)})", null)
            }
            TrackerState.running -> {
                sent = 0
                liveUpdate()
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 1000)
            }
            else -> {}
        }
        startOwnGps()
        for (p in loadPoints()) {
            mapView.evaluateJavascript(markJs(p), null)
        }
        // Rute rencana yang dipilih sebagai panduan (garis putus-putus oranye)
        val act = prefsSurvey().getString("active_plan", "") ?: ""
        val pl = loadPlans().firstOrNull { it[0] == act }
        if (pl != null && viewRoute == null) loadPlanOnMap(pl, false)
        pendingPlan?.let { loadPlanOnMap(it, true); planOn = true; applyPlanUi(); pendingPlan = null }
        if (viewRoute == null && !TrackerState.running && !planOn) {
            planOn = true
            applyPlanUi()
        }
    }

    private fun liveUpdate() {
        if (!pageReady || !TrackerState.running) return
        val r = TrackerState.route
        if (r.size < sent) sent = 0
        if (r.size > sent) {
            mapView.evaluateJavascript("addPoints(${toJson(r, sent)})", null)
            sent = r.size
        }
        val km = TrackerState.distanceMeters / 1000f
        updateStats(km, TrackerState.currentElapsed(), TrackerState.averageSpeed(), TrackerState.maxSpeedKmh)
        statusText.text = if (TrackerState.paused) {
            String.format(Locale.US, "Aktivitas dijeda • %d titik", r.size)
        } else {
            String.format(Locale.US, "Merekam rute • %d titik", r.size)
        }
    }

    private fun toJson(list: List<DoubleArray>, from: Int): String {
        val sb = StringBuilder("[")
        for (i in from until list.size) {
            if (i > from) sb.append(',')
            sb.append(String.format(Locale.US, "[%.6f,%.6f]", list[i][0], list[i][1]))
        }
        return sb.append(']').toString()
    }

    private fun startOwnGps() {
        if (ownGps) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                200
            )
            if (mapFree()) statusText.text = "Izinkan akses lokasi presisi untuk menampilkan posisi"
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, gpsListener)
            ownGps = true
            if (mapFree()) statusText.text = "GPS aktif • Menunggu sinyal..."
        } catch (e: Exception) {
            if (mapFree()) statusText.text = "GPS tidak tersedia. Periksa lokasi HP."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startOwnGps()
            } else {
                statusText.text = "Izin lokasi presisi ditolak. Aktifkan di Pengaturan HP."
            }
        }
    }

    /** Hentikan GPS dan pembaruan saat layar peta tidak terlihat, supaya baterai hemat. */
    override fun onPause() {
        handler.removeCallbacks(ticker)
        try {
            locationManager.removeUpdates(gpsListener)
        } catch (e: Exception) {
        }
        ownGps = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        startOwnGps()
        if (pageReady && viewRoute == null && TrackerState.running) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
    }

    // ---------- Rencana Rute manual ----------
    private var planOn = false
    private var planLoop = false
    private var loopBtn: TextView? = null
    private var planRoad = true
    private var planDist = 0.0
    private var planCount = 0
    private var planSnapped = false
    private var planWps: List<DoubleArray> = emptyList()
    private var planInfo: TextView? = null
    private var planRoadBtn: TextView? = null
    private var planBar: LinearLayout? = null
    private var pendingPlan: List<String>? = null
    private var plansBox: LinearLayout? = null

    private fun planProfile() = if (mode == "Sepeda") "bike" else "foot"

    /** Rencana tersimpan: id|nama|road(0/1)|profil|jarakM|lat,lon;lat,lon */
    private fun loadPlans(): MutableList<List<String>> =
        (prefsSurvey().getString("plans", "") ?: "").lines().filter { it.isNotBlank() }
            .map { it.split("|") }.filter { it.size >= 6 }.toMutableList()

    private fun savePlans(list: List<List<String>>) {
        prefsSurvey().edit().putString("plans", list.joinToString("\n") { it.joinToString("|") }).apply()
    }

    private fun parseWps(s: String): List<DoubleArray> = s.split(";").mapNotNull { q ->
        val p = q.split(",")
        val la = p.getOrNull(0)?.toDoubleOrNull()
        val lo = p.getOrNull(1)?.toDoubleOrNull()
        if (la != null && lo != null) doubleArrayOf(la, lo) else null
    }

    private fun loadPlanOnMap(p: List<String>, fit: Boolean) {
        if (!pageReady) return
        val wps = parseWps(p[5])
        planLoop = p.getOrNull(6) == "1"
        loopBtn?.text = if (planLoop) "⟲ Pulang-pergi" else "→ Sekali jalan"
        mapView.evaluateJavascript("loadPlan(${toJson(wps, 0)},${p[2] == "1"},'${p[3]}',$fit,$planLoop)", null)
    }

    private fun onPlanChanged(dist: Double, count: Int, snapped: Boolean, err: Boolean, json: String) {
        planDist = dist
        planCount = count
        planSnapped = snapped
        val list = ArrayList<DoubleArray>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val q = arr.getJSONArray(i)
                list.add(doubleArrayOf(q.getDouble(0), q.getDouble(1)))
            }
        } catch (e: Exception) {
        }
        planWps = list
        val mode = when {
            count < 2 -> "ketuk peta untuk menambah titik"
            !planRoad -> "garis lurus antar titik"
            err -> "jalan tidak ditemukan, memakai garis lurus"
            snapped -> "mengikuti jalan"
            else -> "menghitung jalan…"
        }
        val kmh = if (planProfile() == "bike") 16.0 else 5.0
        val mnt = Math.round(dist / 1000.0 / kmh * 60.0).toInt()
        val dur = if (mnt >= 60) (mnt / 60).toString() + " j " + (mnt % 60) + " mnt" else "$mnt mnt"
        planInfo?.text = Fmt.dec(dist / 1000.0, 2) + " km • " + dur + " • " + count + " titik • " + mode
    }

    /** Daftar rute tersimpan: buka, jadikan garis panduan, buka di Google Maps, atau hapus. */
    private fun savedPlansDialog() {
        val plans = loadPlans()
        if (plans.isEmpty()) {
            Toast.makeText(this, "Belum ada rute tersimpan.", Toast.LENGTH_SHORT).show()
            return
        }
        val active = prefsSurvey().getString("active_plan", "") ?: ""
        val names = Array(plans.size) { i ->
            val p = plans[i]
            (if (p[0] == active) "★ " else "") + p[1] + " · " +
                Fmt.dec((p[4].toDoubleOrNull() ?: 0.0) / 1000.0, 2) + " km"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rute saya")
            .setItems(names) { _, i ->
                val p = plans[i]
                val isActive = p[0] == active
                val acts = arrayOf(
                    "Buka di peta",
                    if (isActive) "Matikan sebagai panduan" else "Jadikan garis panduan (★)",
                    "Buka di Google Maps",
                    "Hapus rute"
                )
                android.app.AlertDialog.Builder(this)
                    .setTitle(p[1])
                    .setItems(acts) { _, k ->
                        when (k) {
                            0 -> {
                                planRoad = p[2] == "1"
                                if (!planOn) { planOn = true; applyPlanUi() }
                                loadPlanOnMap(p, true)
                            }
                            1 -> {
                                prefsSurvey().edit().putString("active_plan", if (isActive) "" else p[0]).apply()
                                Toast.makeText(this,
                                    if (isActive) "Panduan dimatikan" else "Dipakai sebagai garis panduan",
                                    Toast.LENGTH_SHORT).show()
                            }
                            2 -> openDirections(parseWps(p[5]), p[3])
                            else -> {
                                val all = loadPlans()
                                all.removeAll { it[0] == p[0] }
                                savePlans(all)
                                if (isActive) prefsSurvey().edit().putString("active_plan", "").apply()
                                Toast.makeText(this, "Rute dihapus", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun longPressDialog(lat: Double, lon: Double) {
        val la = String.format(Locale.US, "%.6f", lat)
        val lo = String.format(Locale.US, "%.6f", lon)
        val items = arrayOf("🟢 Mulai di sini (A)", "🔴 Jadikan titik akhir (B)", "＋ Tambah ke rute")
        android.app.AlertDialog.Builder(this)
            .setTitle("$la, $lo")
            .setItems(items) { _, i ->
                if (!pageReady) return@setItems
                val fn = when (i) { 0 -> "startHere"; 1 -> "endHere"; else -> "addWp" }
                mapView.evaluateJavascript("$fn($la,$lo)", null)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun waypointListDialog() {
        if (planWps.isEmpty()) {
            Toast.makeText(this, "Belum ada titik. Tekan lama di peta.", Toast.LENGTH_SHORT).show()
            return
        }
        val n = planWps.size
        val names = Array(n) { i ->
            val tag = if (n > 1 && i == 0) "A" else if (n > 1 && i == n - 1) "B" else (i + 1).toString()
            tag + "   " + String.format(Locale.US, "%.5f, %.5f", planWps[i][0], planWps[i][1])
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Titik rute")
            .setItems(names) { _, i ->
                android.app.AlertDialog.Builder(this)
                    .setTitle(names[i])
                    .setItems(arrayOf("Lihat di peta", "Naik", "Turun", "Hapus titik")) { _, k ->
                        if (!pageReady) return@setItems
                        when (k) {
                            0 -> mapView.evaluateJavascript(String.format(Locale.US, "focusMark(%.6f,%.6f)", planWps[i][0], planWps[i][1]), null)
                            1 -> mapView.evaluateJavascript("moveWp($i,-1)", null)
                            2 -> mapView.evaluateJavascript("moveWp($i,1)", null)
                            else -> mapView.evaluateJavascript("removeWp($i)", null)
                        }
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun searchDialog() {
        val et = android.widget.EditText(this).apply {
            hint = "Nama tempat atau jalan"
            setSingleLine()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cari lokasi")
            .setView(et)
            .setPositiveButton("Cari") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty() && pageReady) mapView.evaluateJavascript("searchPlace(" + org.json.JSONObject.quote(q) + ")", null)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyPlanUi() {
        planBar?.visibility = if (planOn) View.VISIBLE else View.GONE
        if (planOn && pageReady) {
            if (mapFree()) statusText.text = "Ketuk peta untuk menambah titik, tekan lama untuk menu titik"
        }
        if (pageReady) mapView.evaluateJavascript("setPlan($planOn)", null)
        planRoadBtn?.text = if (planRoad) "Ikuti jalan: ON" else "Ikuti jalan: OFF"
    }

    private fun togglePlan() {
        if (!pageReady) {
            Toast.makeText(this, "Peta belum siap.", Toast.LENGTH_SHORT).show()
            return
        }
        planOn = !planOn
        applyPlanUi()
    }

    private fun spill(label: String, fill: Int, action: () -> Unit) = pill(label, fill, action).apply {
        textSize = 11.5f
        background = round(fill, 16)
    }

    private fun buildPlanBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = round((t.surface and 0x00FFFFFF) or 0xEE000000.toInt(), 16)
            visibility = View.GONE
        }
        bar.addView(TextView(this).apply {
            text = "Rencana rute"; textSize = 12f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        })
        val info = TextView(this).apply {
            text = "0,00 km • 0 titik • ketuk peta untuk menambah titik"; textSize = 11f; setTextColor(t.muted)
        }
        planInfo = info
        bar.addView(info, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })

        val row1 = LinearLayout(this)
        row1.addView(spill("↶ Urungkan", t.bg) {
            if (pageReady) mapView.evaluateJavascript("undoWp()", null)
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        row1.addView(spill("Hapus semua", t.bg) {
            if (pageReady) mapView.evaluateJavascript("clearPlan()", null)
        }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(6) })
        val rb = spill("Ikuti jalan: ON", t.bg) {
            planRoad = !planRoad
            planRoadBtn?.text = if (planRoad) "Ikuti jalan: ON" else "Ikuti jalan: OFF"
            if (pageReady) mapView.evaluateJavascript("setRoad($planRoad,'${planProfile()}')", null)
        }
        planRoadBtn = rb
        row1.addView(rb, LinearLayout.LayoutParams(0, dp(32), 1.2f).apply { leftMargin = dp(6) })
        bar.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val row3 = LinearLayout(this)
        val lb = spill("→ Sekali jalan", t.bg) {
            planLoop = !planLoop
            loopBtn?.text = if (planLoop) "⟲ Pulang-pergi" else "→ Sekali jalan"
            if (pageReady) mapView.evaluateJavascript("setLoop($planLoop)", null)
        }
        loopBtn = lb
        row3.addView(lb, LinearLayout.LayoutParams(0, dp(32), 1.6f))
        row3.addView(spill("☷ Titik", t.bg) { waypointListDialog() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(6) })
        row3.addView(spill("⌕ Cari", t.bg) { searchDialog() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(6) })
        bar.addView(row3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val row2 = LinearLayout(this)
        row2.addView(spill("Simpan rute", accentColor) { savePlanDialog() }, LinearLayout.LayoutParams(0, dp(34), 1f))
        row2.addView(spill("☰ Rute saya", t.bg) { savedPlansDialog() },
            LinearLayout.LayoutParams(0, dp(34), 1.1f).apply { leftMargin = dp(6) })
        row2.addView(spill("Maps ↗", t.bg) {
            if (planWps.size < 2) Toast.makeText(this, "Tambahkan minimal 2 titik.", Toast.LENGTH_SHORT).show()
            else openDirections(planWps, planProfile())
        }, LinearLayout.LayoutParams(0, dp(34), 0.8f).apply { leftMargin = dp(6) })
        bar.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        planBar = bar
        return bar
    }

    /** Link arah Google Maps (maks. 10 titik, dipilih merata). */
    private fun directionsUrl(w: List<DoubleArray>, prof: String): String {
        val pts = if (w.size <= 10) w else List(10) { i -> w[(i * (w.size - 1)) / 9] }
        val sb = StringBuilder("https://www.google.com/maps/dir/")
        for (p in pts) sb.append(String.format(Locale.US, "%.6f,%.6f/", p[0], p[1]))
        sb.append("?travelmode=").append(if (prof == "bike") "bicycling" else "walking")
        return sb.toString()
    }

    private fun openDirections(w: List<DoubleArray>, prof: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directionsUrl(w, prof))))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka peta.", Toast.LENGTH_LONG).show()
        }
    }

    private fun savePlanDialog() {
        if (planWps.size < 2) {
            Toast.makeText(this, "Tambahkan minimal 2 titik.", Toast.LENGTH_SHORT).show()
            return
        }
        val et = android.widget.EditText(this).apply {
            setText("Rute " + (loadPlans().size + 1))
            setSingleLine()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Simpan rencana rute")
            .setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                val name = et.text.toString().replace("|", " ").replace("\n", " ").trim().ifEmpty { "Rute" }
                val wps = planWps.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it[0], it[1]) }
                val list = loadPlans()
                list.add(0, listOf(
                    System.currentTimeMillis().toString(), name, if (planRoad) "1" else "0",
                    planProfile(), planDist.toInt().toString(), wps, if (planLoop) "1" else "0"
                ))
                savePlans(list.take(20))
                refreshPlans()
                Toast.makeText(this, "Rencana \"$name\" disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun buildPlansSection(col: LinearLayout) {
        col.addView(TextView(this).apply {
            text = "Rencana rute"; textSize = 14f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        col.addView(pill("✎  Buat rencana rute di peta", accentColor) {
            goMapPage()
            planOn = true
            applyPlanUi()
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        plansBox = box
        col.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        refreshPlans()
    }

    private fun refreshPlans() {
        val box = plansBox ?: return
        box.removeAllViews()
        val plans = loadPlans()
        if (plans.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Belum ada rencana. Atur titik yang akan dilewati di peta lalu simpan."
                textSize = 12f; setTextColor(t.muted)
            })
            return
        }
        val active = prefsSurvey().getString("active_plan", "") ?: ""
        for (p in plans) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(10))
                background = round(t.surface, 16)
                setOnClickListener {
                    goMapPage()
                    planRoad = p[2] == "1"
                    planOn = true
                    loadPlanOnMap(p, true)
                    applyPlanUi()
                }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = p[1]; textSize = 14f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = Fmt.dec((p[4].toDoubleOrNull() ?: 0.0) / 1000.0, 2) + " km • " +
                    parseWps(p[5]).size + " titik" + if (p[2] == "1") " • ikut jalan" else ""
                textSize = 11f; setTextColor(t.muted)
            })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = if (p[0] == active) "★" else "☆"; textSize = 18f
                setTextColor(if (p[0] == active) accentColor else t.muted); gravity = Gravity.CENTER
                setOnClickListener {
                    prefsSurvey().edit().putString("active_plan", if (p[0] == active) "" else p[0]).apply()
                    refreshPlans()
                    Toast.makeText(this@MapActivity,
                        if (p[0] == active) "Panduan dimatikan" else "Dipakai sebagai garis panduan di peta",
                        Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = "⤴"; textSize = 18f; setTextColor(accentColor); gravity = Gravity.CENTER
                setOnClickListener { openDirections(parseWps(p[5]), p[3]) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 16f; setTextColor(t.muted); gravity = Gravity.CENTER
                setOnClickListener {
                    val all = loadPlans()
                    all.removeAll { it[0] == p[0] }
                    savePlans(all)
                    if (p[0] == active) prefsSurvey().edit().putString("active_plan", "").apply()
                    refreshPlans()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    // ---------- Survei Lokasi ----------
    private var lastLoc: Location? = null
    private var goMapPage: () -> Unit = {}
    private var svStatus: TextView? = null
    private var svCoord: TextView? = null
    private var svAlt: TextView? = null
    private var svAcc: TextView? = null
    private var svSpeed: TextView? = null
    private var svHead: TextView? = null
    private var svList: LinearLayout? = null

    private fun mapFree() = viewRoute == null && !TrackerState.running

    private fun prefsSurvey() = getSharedPreferences("survey", MODE_PRIVATE)

    /** Titik survei tersimpan: waktu|lat|lon|alt|nama (terbaru di atas). */
    private fun loadPoints(): MutableList<List<String>> =
        (prefsSurvey().getString("points", "") ?: "").lines().filter { it.isNotBlank() }
            .map { it.split("|") }.filter { it.size >= 5 }.toMutableList()

    private fun savePoints(list: List<List<String>>) {
        prefsSurvey().edit().putString("points", list.joinToString("\n") { it.joinToString("|") }).apply()
    }

    private fun jsStr(s: String) = "'" + s.replace("\\", "").replace("'", "") + "'"

    private fun markJs(p: List<String>) = "addMark(${p[1]},${p[2]},${jsStr(p[4])})"

    private fun compass(b: Float): String {
        val n = listOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")
        return n[(((b % 360f) + 360f) % 360f / 45f + 0.5f).toInt() % 8]
    }

    private fun coordText(l: Location) = String.format(Locale.US, "%.6f, %.6f", l.latitude, l.longitude)

    private fun updateSurvey(l: Location) {
        svCoord?.text = coordText(l)
        svAlt?.text = if (l.hasAltitude()) Fmt.dec(l.altitude, 0) + " m" else "--"
        svAcc?.text = "±" + l.accuracy.toInt() + " m"
        svSpeed?.text = if (l.hasSpeed()) Fmt.dec(l.speed * 3.6f, 1) + " km/jam" else "--"
        svHead?.text = if (l.hasBearing() && l.hasSpeed() && l.speed > 0.5f)
            Fmt.dec(l.bearing, 0) + "° " + compass(l.bearing) else "--"
        svStatus?.text = if (l.accuracy <= 15f) "Sinyal GPS baik" else if (l.accuracy <= 30f) "Sinyal GPS sedang" else "Sinyal GPS lemah, tunggu sebentar"
    }

    private fun pill(label: String, fill: Int, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(onColor(fill))
        background = round(fill, 22)
        setOnClickListener { action() }
    }

    private fun infoBox(label: String): Pair<View, TextView> {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = round(t.surface, 16)
        }
        b.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(t.muted) })
        val v = TextView(this).apply {
            text = "--"; textSize = 17f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        }
        b.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        return Pair(b, v)
    }

    private fun twoRow(l: View, r: View, top: Int = 8): LinearLayout {
        val row = LinearLayout(this)
        row.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(r, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        return row
    }

    private fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(i, "Bagikan lokasi"))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi untuk berbagi.", Toast.LENGTH_LONG).show()
        }
    }

    private fun mapsLink(lat: String, lon: String) = "https://maps.google.com/?q=$lat,$lon"

    private fun buildSurveyPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Survei Lokasi"; textSize = 20f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        })
        val st = TextView(this).apply {
            text = "Menunggu sinyal GPS..."; textSize = 12f; setTextColor(t.muted)
        }
        svStatus = st
        col.addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })

        val cc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = round(t.surface, 18)
        }
        cc.addView(TextView(this).apply { text = "Koordinat"; textSize = 11f; setTextColor(t.muted) })
        val coord = TextView(this).apply {
            text = "--"; textSize = 20f; setTextColor(accentColor)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        svCoord = coord
        cc.addView(coord, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        col.addView(cc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val alt = infoBox("Ketinggian"); val acc = infoBox("Akurasi GPS")
        val spd = infoBox("Kecepatan"); val hd = infoBox("Arah")
        svAlt = alt.second; svAcc = acc.second; svSpeed = spd.second; svHead = hd.second
        col.addView(twoRow(alt.first, acc.first))
        col.addView(twoRow(spd.first, hd.first))

        col.addView(pill("⚑   TANDAI TITIK INI", accentColor) {
            val l = lastLoc
            if (l == null) {
                Toast.makeText(this, "Belum ada sinyal GPS.", Toast.LENGTH_SHORT).show()
            } else {
                val list = loadPoints()
                val name = "Titik " + (list.size + 1)
                val p = listOf(
                    System.currentTimeMillis().toString(),
                    String.format(Locale.US, "%.6f", l.latitude),
                    String.format(Locale.US, "%.6f", l.longitude),
                    if (l.hasAltitude()) l.altitude.roundToInt().toString() else "0",
                    name
                )
                list.add(0, p)
                savePoints(list.take(30))
                if (pageReady) mapView.evaluateJavascript(markJs(p), null)
                refreshPoints()
                Toast.makeText(this, name + " disimpan", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })

        val share = pill("⤴  Bagikan", t.surface) {
            val l = lastLoc
            if (l == null) Toast.makeText(this, "Belum ada sinyal GPS.", Toast.LENGTH_SHORT).show()
            else shareText("Lokasi saya: " + mapsLink(String.format(Locale.US, "%.6f", l.latitude), String.format(Locale.US, "%.6f", l.longitude)))
        }
        val copy = pill("⧉  Salin koordinat", t.surface) {
            val l = lastLoc
            if (l == null) Toast.makeText(this, "Belum ada sinyal GPS.", Toast.LENGTH_SHORT).show()
            else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("koordinat", coordText(l)))
                Toast.makeText(this, "Koordinat disalin", Toast.LENGTH_SHORT).show()
            }
        }
        val br = LinearLayout(this)
        br.addView(share, LinearLayout.LayoutParams(0, dp(44), 1f))
        br.addView(copy, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        col.addView(br, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        col.addView(pill("⌖  Buka di aplikasi peta", t.surface) {
            val l = lastLoc
            if (l == null) Toast.makeText(this, "Belum ada sinyal GPS.", Toast.LENGTH_SHORT).show()
            else try {
                val q = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Lokasi)", l.latitude, l.longitude, l.latitude, l.longitude)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q)))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak ada aplikasi peta.", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        col.addView(TextView(this).apply {
            text = "Titik tersimpan"; textSize = 14f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        svList = list
        col.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        refreshPoints()
        buildPlansSection(col)
        return ScrollView(this).apply { addView(col) }
    }

    private fun refreshPoints() {
        val box = svList ?: return
        box.removeAllViews()
        val pts = loadPoints()
        if (pts.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Belum ada titik. Tekan Tandai titik ini untuk menyimpan lokasi survei."
                textSize = 12f; setTextColor(t.muted)
            })
            return
        }
        for ((idx, p) in pts.withIndex()) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(10))
                background = round(t.surface, 16)
                setOnClickListener {
                    goMapPage()
                    if (pageReady) mapView.evaluateJavascript("focusMark(${p[1]},${p[2]})", null)
                }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = p[4]; textSize = 14f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
            })
            val time = java.text.SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("id-ID"))
                .format(java.util.Date(p[0].toLongOrNull() ?: 0L))
            info.addView(TextView(this).apply {
                text = p[1] + ", " + p[2] + " • " + p[3] + " m • " + time
                textSize = 11f; setTextColor(t.muted)
            })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "⤴"; textSize = 18f; setTextColor(accentColor); gravity = Gravity.CENTER
                setOnClickListener { shareText(p[4] + ": " + mapsLink(p[1], p[2])) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 16f; setTextColor(t.muted); gravity = Gravity.CENTER
                setOnClickListener {
                    val all = loadPoints()
                    if (idx < all.size) all.removeAt(idx)
                    savePoints(all)
                    if (pageReady) {
                        mapView.evaluateJavascript("clearMarks()", null)
                        for (q in all) mapView.evaluateJavascript(markJs(q), null)
                    }
                    refreshPoints()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun mapHtml(): String = MapHtml.build(accentHex)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            locationManager.removeUpdates(gpsListener)
        } catch (e: Exception) {
        }

        mapView.stopLoading()
        mapView.destroy()

        super.onDestroy()
    }
}
