package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Peta GPS (OpenStreetMap + Leaflet). Tiga mode:
 * 1. Riwayat   : menampilkan rute satu perjalanan (extra "route" = lat,lon,lat,lon,...)
 * 2. Live      : saat tracking berjalan, rute diambil dari TrackerState
 * 3. Biasa     : menampilkan posisi saat ini
 */
class MapActivity : Activity() {

    private lateinit var mapView: WebView
    private lateinit var statusText: TextView
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var accentHex = "#26be7d"
    private var pageReady = false
    private var viewRoute: List<DoubleArray>? = null
    private var sent = 0
    private var firstFix = true
    private var ownGps = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val t = Themes.current(this)
        Themes.styleWindow(this, t)
        accentHex = String.format("#%06X", 0xFFFFFF and t.accent)

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
        }

        statusText = TextView(this).apply {
            text = "Menyiapkan peta GPS..."
            textSize = 14f
            setTextColor(t.text)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(40, 28, 40, 28)
        }

        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Bridge")
            webViewClient = WebViewClient()
        }

        val backButton = Button(this).apply {
            text = "KEMBALI"
            isAllCaps = false
            setTextColor(onColor(t.accent))
            background = GradientDrawable().apply {
                setColor(t.accent); cornerRadius = 48f
            }
            stateListAnimator = null
            setOnClickListener { finish() }
        }

        root.addView(statusText)
        root.addView(
            mapView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            backButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(40, 20, 40, 28) }
        )
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

    /** Dipanggil sekali ketika Leaflet sudah siap. */
    private fun onMapReady() {
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
        statusText.text = if (TrackerState.paused) {
            String.format(Locale.US, "Aktivitas dijeda • %.2f km", km)
        } else {
            String.format(Locale.US, "Merekam rute • %d titik • %.2f km", r.size, km)
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

    private fun mapHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                var map = null, route = null, marker = null;
                var ACCENT = '$accentHex';

                function dotStyle(color) {
                  return { radius: 8, color: '#ffffff', weight: 3, fillColor: color, fillOpacity: 1 };
                }

                function initMap() {
                  map = L.map('map').setView([-2.5, 118], 5);
                  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '&copy; OpenStreetMap contributors'
                  }).addTo(map);
                  route = L.polyline([], { color: ACCENT, weight: 5 }).addTo(map);
                  Bridge.onReady();
                }

                function loadLeaflet(bases, i) {
                  if (i >= bases.length) { Bridge.onFail(); return; }
                  var css = document.createElement('link');
                  css.rel = 'stylesheet';
                  css.href = bases[i] + 'leaflet.css';
                  document.head.appendChild(css);
                  var s = document.createElement('script');
                  s.src = bases[i] + 'leaflet.js';
                  s.onload = initMap;
                  s.onerror = function () { loadLeaflet(bases, i + 1); };
                  document.head.appendChild(s);
                }

                loadLeaflet([
                  'https://unpkg.com/leaflet@1.9.4/dist/',
                  'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/'
                ], 0);

                // Rute yang sedang direkam: tambahkan titik baru dan ikuti posisi terakhir.
                function addPoints(arr) {
                  if (!map || arr.length === 0) return;
                  for (var i = 0; i < arr.length; i++) { route.addLatLng(arr[i]); }
                  var last = arr[arr.length - 1];
                  if (!marker) {
                    marker = L.circleMarker(last, dotStyle(ACCENT)).addTo(map);
                    map.setView(last, 17);
                  } else {
                    marker.setLatLng(last);
                    map.panTo(last);
                  }
                }

                // Rute perjalanan dari riwayat: tampilkan utuh.
                function showRoute(arr) {
                  if (!map || arr.length === 0) return;
                  route.setLatLngs(arr);
                  L.circleMarker(arr[0], dotStyle('#27D282')).addTo(map);
                  L.circleMarker(arr[arr.length - 1], dotStyle('#FF3B3B')).addTo(map);
                  if (arr.length > 1) { map.fitBounds(route.getBounds(), { padding: [30, 30] }); }
                  else { map.setView(arr[0], 17); }
                }

                // Posisi saat ini (tanpa tracking).
                function setPosition(lat, lon, first) {
                  if (!map) return;
                  var p = [lat, lon];
                  if (!marker) { marker = L.circleMarker(p, dotStyle(ACCENT)).addTo(map); }
                  else { marker.setLatLng(p); }
                  if (first) { map.setView(p, 17); } else { map.panTo(p); }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

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
