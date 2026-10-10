
package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color

class MapActivity : Activity() {

    private lateinit var mapView: WebView
    private lateinit var statusText: TextView
    private lateinit var locationManager: LocationManager

    private var mapReady = false
    private var firstLocation = true

    private val routePoints = mutableListOf<Location>()

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy > 30f) {
                statusText.text = "GPS mencari sinyal yang lebih akurat..."
                return
            }

            val previous = routePoints.lastOrNull()

            if (previous != null) {
                val distance = previous.distanceTo(location)

                if (distance < 3f) return
                if (distance > 100f) return
            }

            routePoints.add(location)

            statusText.text =
                "GPS aktif • Titik rute: ${routePoints.size}"

            if (mapReady) {
                val lat = location.latitude
                val lon = location.longitude

                mapView.evaluateJavascript(
                    "addRoutePoint($lat, $lon, $firstLocation)",
                    null
                )
            }

            firstLocation = false
        }

        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {}

        override fun onProviderEnabled(provider: String) {
            statusText.text = "GPS aktif • Menunggu lokasi..."
        }

        override fun onProviderDisabled(provider: String) {
            statusText.text = "GPS mati • Aktifkan lokasi HP"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 32))
        }

        statusText = TextView(this).apply {
            text = "Menyiapkan peta GPS..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(16, 14, 16, 14)
        }

        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
        }

        val backButton = Button(this).apply {
            text = "KEMBALI"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        root.addView(statusText)

        root.addView(
            mapView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(backButton)
        setContentView(root)

        mapView.loadDataWithBaseURL(
            "https://www.openstreetmap.org/",
            mapHtml(),
            "text/html",
            "UTF-8",
            null
        )

        mapView.postDelayed({
            mapReady = true

            routePoints.forEachIndexed { index, location ->
                mapView.evaluateJavascript(
                    "addRoutePoint(${location.latitude}, " +
                        "${location.longitude}, ${index == 0})",
                    null
                )
            }
        }, 2500)

        startGps()
    }

    private fun startGps() {
        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                200
            )
            statusText.text = "Izinkan akses lokasi untuk menampilkan rute"
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                0f,
                gpsListener
            )
            statusText.text = "GPS aktif • Menunggu sinyal..."
        } catch (_: Exception) {
            statusText.text = "GPS tidak tersedia. Periksa lokasi HP."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 200 &&
            (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
             checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
        ) {
            startGps()
        }
    }

    private fun mapHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport"
                    content="width=device-width, initial-scale=1.0">
              <link rel="stylesheet"
                href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
              <script
                src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js">
              </script>
              <style>
                html, body, #map {
                  height: 100%;
                  width: 100%;
                  margin: 0;
                  padding: 0;
                }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                const map = L.map('map').setView([-2.5, 118], 5);

                L.tileLayer(
                  'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                  {
                    maxZoom: 19,
                    attribution: '&copy; OpenStreetMap contributors'
                  }
                ).addTo(map);

                const route = L.polyline([], {
                  color: '#26be7d',
                  weight: 5
                }).addTo(map);

                let points = [];
                let marker = null;

                function addRoutePoint(lat, lon, first) {
                  const point = [lat, lon];
                  points.push(point);
                  route.setLatLngs(points);

                  if (marker) {
                    marker.setLatLng(point);
                  } else {
                    marker = L.marker(point).addTo(map);
                  }

                  if (first) {
                    map.setView(point, 17);
                  } else {
                    map.panTo(point);
                  }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(gpsListener)
        } catch (_: Exception) {
        }

        mapView.stopLoading()
        mapView.destroy()

        super.onDestroy()
    }
}
