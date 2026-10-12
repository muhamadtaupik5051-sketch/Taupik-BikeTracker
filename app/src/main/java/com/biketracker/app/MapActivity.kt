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
        listOf("⌂" to "Beranda", "⌖" to "Peta", "▤" to "Riwayat", "⚙" to "Atur")
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
            text = if (viewRoute != null) "Rute Aktivitas" else "Peta Rute"
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
        page1.addView(tabRow, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(16); topMargin = dp(4); bottomMargin = dp(8)
        })

        statusText = TextView(this).apply {
            text = "Menyiapkan peta GPS..."
            textSize = 12f
            setTextColor(t.muted)
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        page1.addView(statusText)

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
        controls.addView(mapButton("+") {
            if (pageReady) mapView.evaluateJavascript("if(map)map.zoomIn()", null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = gap })
        controls.addView(mapButton("−") {
            if (pageReady) mapView.evaluateJavascript("if(map)map.zoomOut()", null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = gap })
        controls.addView(mapButton("◎") {
            if (pageReady) mapView.evaluateJavascript("locate()", null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        mapArea.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(12)
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
        // Dua halaman yang bisa digeser: Peta dan Survei Lokasi
        val sw = resources.displayMetrics.widthPixels
        val pageTabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val pageTabViews = ArrayList<TextView>()
        fun stylePageTab(v: TextView, sel: Boolean) {
            v.setTextColor(if (sel) accentColor else t.muted)
            v.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            v.paint.isUnderlineText = sel
        }
        lateinit var pager: PagerScroll
        listOf("Peta", "Survei Lokasi").forEachIndexed { i, name ->
            val v = TextView(this).apply {
                text = name; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setOnClickListener { pager.goTo(i, true) }
            }
            stylePageTab(v, i == 0)
            pageTabViews.add(v)
            pageTabs.addView(v)
        }
        pager = PagerScroll(this, sw) { p -> pageTabViews.forEachIndexed { i, v -> stylePageTab(v, i == p) } }
        pager.pages = 2
        val strip = LinearLayout(this)
        strip.addView(page1, LinearLayout.LayoutParams(sw, -1))
        strip.addView(buildSurveyPage(), LinearLayout.LayoutParams(sw, -1))
        pager.addView(strip)
        goMapPage = { pager.goTo(0, true) }
        root.addView(pageTabs)
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
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
