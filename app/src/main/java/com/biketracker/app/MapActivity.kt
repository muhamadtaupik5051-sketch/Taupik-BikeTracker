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
import android.view.Gravity
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
            statusText.text = "GPS aktif • Menunggu lokasi..."
        }

        override fun onProviderDisabled(provider: String) {
            statusText.text = "GPS mati • Aktifkan lokasi HP"
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
        root.addView(tabRow, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(16); topMargin = dp(4); bottomMargin = dp(8)
        })

        statusText = TextView(this).apply {
            text = "Menyiapkan peta GPS..."
            textSize = 12f
            setTextColor(t.muted)
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(statusText)

        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            MapHtml.applyCache(this)
            addJavascriptInterface(Bridge(), "Bridge")
            webViewClient = WebViewClient()
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
        root.addView(mapArea, LinearLayout.LayoutParams(-1, 0, 1f))

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
            root.addView(panel)

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
            else -> startOwnGps()
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
            statusText.text = "Izinkan akses lokasi presisi untuk menampilkan posisi"
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, gpsListener)
            ownGps = true
            statusText.text = "GPS aktif • Menunggu sinyal..."
        } catch (e: Exception) {
            statusText.text = "GPS tidak tersedia. Periksa lokasi HP."
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
                if (pageReady) startOwnGps()
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
        if (pageReady) {
            when {
                viewRoute != null -> {}
                TrackerState.running -> {
                    handler.removeCallbacks(ticker)
                    handler.postDelayed(ticker, 1000)
                }
                else -> startOwnGps()
            }
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
