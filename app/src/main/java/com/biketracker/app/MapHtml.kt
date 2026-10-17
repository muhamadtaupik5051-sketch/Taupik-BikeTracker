package com.biketracker.app

/** Halaman HTML Leaflet yang dipakai MapActivity dan panel peta di layar tracking. */
object MapHtml {
    /** Alamat palsu yang dilayani dari folder assets/leaflet (diisi saat build oleh workflow). */
    private const val LOCAL_HOST = "appassets.local"

    /**
     * WebViewClient yang menyajikan Leaflet dari assets lebih dulu supaya peta tidak bergantung CDN.
     * Kalau berkas tidak ada di assets, hasilnya null dan halaman otomatis mencoba CDN.
     */
    fun client(ctx: android.content.Context): android.webkit.WebViewClient {
        val app = ctx.applicationContext
        return object : android.webkit.WebViewClient() {
            /** Peta tidak boleh pindah halaman: tautan luar (mis. atribusi Leaflet) dibuka di peramban. */
            private fun openOutside(url: String?): Boolean {
                val s = url ?: return true
                if (s.startsWith("https://") || s.startsWith("http://")) {
                    try {
                        app.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(s))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                }
                return true
            }

            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean = openOutside(request?.url?.toString())

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean = openOutside(url)

            /** Proses WebView mati (mis. memori habis): jangan biarkan aplikasi ikut tertutup. */
            override fun onRenderProcessGone(
                view: android.webkit.WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                try {
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    view?.destroy()
                    android.widget.Toast.makeText(app, app.getString(R.string.map_crashed), android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val u = request?.url ?: return null
                if (u.host != LOCAL_HOST) return null
                val path = (u.path ?: "").removePrefix("/")
                if (!path.startsWith("leaflet/") || path.contains("..")) return null
                return try {
                    val text = path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".svg")
                    val mime = when {
                        path.endsWith(".js") -> "application/javascript"
                        path.endsWith(".css") -> "text/css"
                        path.endsWith(".png") -> "image/png"
                        path.endsWith(".svg") -> "image/svg+xml"
                        else -> "application/octet-stream"
                    }
                    android.webkit.WebResourceResponse(mime, if (text) "UTF-8" else null, app.assets.open(path))
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Tanpa internet, peta memakai cache bagian yang pernah dibuka (lewat jalur yang sudah dilihat).
     * Tidak mengunduh peta massal, sesuai aturan penggunaan tile OpenStreetMap.
     */
    @Suppress("DEPRECATION")
    fun applyCache(web: android.webkit.WebView) {
        var online = true
        try {
            val svc = web.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            val cm = svc as android.net.ConnectivityManager
            val info = cm.activeNetworkInfo
            online = info != null && info.isConnected
        } catch (e: Exception) {
            online = true
        }
        web.settings.cacheMode = if (online) android.webkit.WebSettings.LOAD_DEFAULT
        else android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
    }

    /** Peta hanya butuh JavaScript dan jaringan: matikan akses berkas dan konten lokal. */
    fun harden(web: android.webkit.WebView) {
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
    }

    fun build(accentHex: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; }
                .pin { width: 26px; height: 26px; border-radius: 50% 50% 50% 0; transform: rotate(-45deg);
                       border: 3px solid #ffffff; box-shadow: 0 2px 6px rgba(0,0,0,0.45); }
                .pin.g { background: #27D282; }
                .pin.r { background: #FF3B3B; }
                .wp { width: 26px; height: 26px; border-radius: 50%; background: #FFB020; color: #111;
                      font: 700 13px sans-serif; display: flex; align-items: center; justify-content: center;
                      border: 3px solid #ffffff; box-shadow: 0 2px 6px rgba(0,0,0,0.45); box-sizing: border-box; }
                body.nowp .wpi { display: none; }
                .rb { position: absolute; right: 10px; width: 34px; height: 34px; border-radius: 50%;
                      background: rgba(20,28,36,0.92); color: #fff; z-index: 1000; display: none;
                      align-items: center; justify-content: center; font: 700 14px sans-serif;
                      box-shadow: 0 1px 4px rgba(0,0,0,0.4); -webkit-tap-highlight-color: transparent; }
                .rb.on { background: #1E90FF; }
                .leaflet-control-attribution { font-size: 8px !important; line-height: 1.3; padding: 0 4px !important;
                      background: rgba(255,255,255,0.55) !important; color: #333 !important; }
                .leaflet-control-attribution a { color: #333 !important; }
                .lb { position: absolute; left: 10px; top: 176px; z-index: 1000; border-radius: 14px;
                      background: rgba(20,28,36,0.92); color: #fff; font: 600 12px sans-serif;
                      box-shadow: 0 1px 4px rgba(0,0,0,0.4); -webkit-tap-highlight-color: transparent; }
                .lb .t { min-width: 34px; height: 34px; padding: 0 12px; box-sizing: border-box; display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 16px; }
                .lb .t span { font: 700 12px sans-serif; }
                .lb .m { display: none; padding: 2px 6px 6px; }
                .lb.open .m { display: block; }
                .lb .h { padding: 6px 8px 2px; font-size: 10px; opacity: 0.65; }
                .lb .o { padding: 8px 10px; border-radius: 9px; white-space: nowrap; }
                .lb .o.on { background: #1E90FF; }
                .gps { position: relative; width: 28px; height: 28px; }
                .gps b { position: absolute; left: 4px; top: 4px; width: 20px; height: 20px; border-radius: 50%;
                         background: #1565FF; border: 3px solid #ffffff; box-sizing: border-box;
                         box-shadow: 0 1px 6px rgba(0,0,0,0.55); }
                .gps i { position: absolute; left: 0; top: 0; width: 28px; height: 28px; border-radius: 50%;
                         background: rgba(0,229,255,0.45); animation: gpsPulse 1.8s ease-out infinite; }
                @keyframes gpsPulse { 0% { transform: scale(0.6); opacity: 0.9; } 100% { transform: scale(2.4); opacity: 0; } }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <div id="cmp" class="rb" style="top:56px"><svg id="cmpA" width="22" height="22" viewBox="0 0 22 22"><path d="M11 2 L16 12 L11 10 L6 12 Z" fill="#FF3B3B"/><path d="M11 20 L16 12 L11 10 L6 12 Z" fill="#E6EDF3"/></svg></div>
              <div id="hd" class="rb" style="top:96px">&#10148;</div>
              <div id="elv" class="lb" style="left:52px;display:none;pointer-events:none;padding:5px 10px 3px;font-size:11px">
                <div id="elv_t"></div>
                <svg id="elv_g" width="110" height="26"></svg>
                <div id="elv_s" style="margin-top:3px;font-size:10px;opacity:0.9"></div>
                <svg id="elv_b" width="110" height="6" style="display:block;margin-top:2px"></svg>
              </div>
              <div id="lb" class="lb">
                <div class="t" onclick="lbToggle()">&#128506;<span>Gaya</span></div>
                <div class="m">
                  <div class="h">GAYA PETA</div>
                  <div class="o" id="st_osm" onclick="setStyle('osm')">Standar</div>
                  <div class="o" id="st_topo" onclick="setStyle('topo')">Medan (kontur)</div>
                  <div class="o" id="st_cyclo" onclick="setStyle('cyclo')">Sepeda</div>
                  <div class="h">LAPISAN</div>
                  <div class="o" id="ov_trails" onclick="toggleTrails()">Jalur sepeda</div>
                </div>
              </div>
              <script>
                var map = null, route = null, routeCase = null, marker = null, startPin = null, endPin = null;
                var osm = null, sat = null, topo = null, cyclo = null, trails = null, mapStyle = 'osm', satOn = false, trailsOn = false, follow = true, recording = false, pinned = false, refollowTimer = null;
                var ACCENT = '$accentHex';
                var ROUTE_COLOR = '#00E5FF';
                var rotOk = false, headingMode = false, compactMap = false;

                function setWpHidden(b) { document.body.className = b ? 'nowp' : ''; }
                function cmpEl() { return document.getElementById('cmp'); }
                function hdEl() { return document.getElementById('hd'); }
                function setCmpTop(px) { cmpEl().style.top = px + 'px'; hdEl().style.top = (px + 40) + 'px'; }
                function updateCmp() {
                  if (!map || !rotOk || !map.getBearing) return;
                  document.getElementById('cmpA').style.transform = 'rotate(' + (-map.getBearing()) + 'deg)';
                }
                function initRotateUi() {
                  if (!rotOk || compactMap) return;
                  cmpEl().style.display = 'flex';
                  hdEl().style.display = 'flex';
                  cmpEl().onclick = function () {
                    headingMode = false; hdEl().className = 'rb';
                    if (map.setBearing) map.setBearing(0);
                    updateCmp();
                  };
                  hdEl().onclick = function () {
                    headingMode = !headingMode;
                    hdEl().className = headingMode ? 'rb on' : 'rb';
                    if (headingMode) { follow = true; if (lastHeading !== null) map.setBearing(lastHeading); }
                  };
                  setInterval(updateCmp, 300);
                }
                var lastHeading = null;
                function bearingOf(a, b) {
                  var r = Math.PI / 180, y = Math.sin((b.lng - a.lng) * r) * Math.cos(b.lat * r);
                  var x = Math.cos(a.lat * r) * Math.sin(b.lat * r) - Math.sin(a.lat * r) * Math.cos(b.lat * r) * Math.cos((b.lng - a.lng) * r);
                  return (Math.atan2(y, x) / r + 360) % 360;
                }
                function noteMove(prev, cur) {
                  if (!prev || !map) return;
                  if (map.distance(prev, cur) < 4) return;
                  var b = bearingOf(prev, cur);
                  if (lastHeading !== null) {
                    var d = ((b - lastHeading + 540) % 360) - 180;
                    b = (lastHeading + d * 0.5 + 360) % 360;
                  }
                  lastHeading = b;
                  if (headingMode && rotOk && map.setBearing) map.setBearing(b);
                }

                function gpsMarker(p) {
                  return L.marker(p, {
                    icon: L.divIcon({ className: '', html: '<div class="gps"><i></i><b></b></div>', iconSize: [28, 28], iconAnchor: [14, 14] }),
                    zIndexOffset: 1000, interactive: false
                  });
                }

                function dotStyle(color) {
                  return { radius: 8, color: '#ffffff', weight: 3, fillColor: color, fillOpacity: 1 };
                }

                function pin(cls) {
                  return L.divIcon({
                    className: '',
                    html: '<div class="pin ' + cls + '"></div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 39]
                  });
                }

                function initMap() {
                  var opts = { zoomControl: false };
                  if (rotOk) { opts.rotate = true; opts.touchRotate = true; opts.bearing = 0; opts.rotateControl = false; }
                  try { map = L.map('map', opts); }
                  catch (e) { rotOk = false; map = L.map('map', { zoomControl: false }); }
                  map.setView([-2.5, 118], 5);
                  map.attributionControl.setPrefix(false);
                  osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '&copy; OpenStreetMap'
                  }).addTo(map);
                  sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
                    maxZoom: 19,
                    attribution: '&copy; Esri'
                  });
                  topo = L.tileLayer('https://tile.opentopomap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19, maxNativeZoom: 17,
                    attribution: '&copy; OSM, SRTM, OpenTopoMap'
                  });
                  cyclo = L.tileLayer('https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png', {
                    maxZoom: 19, subdomains: 'abc',
                    attribution: '&copy; OSM, CyclOSM'
                  });
                  trails = L.tileLayer('https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png', {
                    maxZoom: 19, maxNativeZoom: 18, opacity: 0.85, zIndex: 5,
                    attribution: 'Waymarked Trails'
                  });
                  try {
                    var ms = localStorage.getItem('rt_style');
                    if (ms === 'topo' || ms === 'cyclo') { mapStyle = ms; }
                    if (localStorage.getItem('rt_trails') === '1') { trailsOn = true; }
                  } catch (e) { }
                  applyBase();
                  map.on('dragstart', function () { follow = false; if (refollowTimer) { clearTimeout(refollowTimer); refollowTimer = null; } });
                  // Saat merekam, peta otomatis kembali mengikuti posisi 8 detik setelah disentuh.
                  map.on('dragend', function () {
                    if (!recording || pinned) return;
                    if (refollowTimer) clearTimeout(refollowTimer);
                    refollowTimer = setTimeout(function () {
                      refollowTimer = null;
                      follow = true;
                      if (marker) { map.panTo(marker.getLatLng(), { animate: true, duration: 0.8 }); }
                    }, 8000);
                  });
                  routeCase = L.polyline([], { color: '#03222B', weight: 11, opacity: 0.6, lineJoin: 'round', lineCap: 'round' }).addTo(map);
                  route = L.polyline([], { color: ROUTE_COLOR, weight: 6, opacity: 1, lineJoin: 'round', lineCap: 'round' }).addTo(map);
                  planInit();
                  initRotateUi();
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
                  s.onload = function () { loadRotate(0); };
                  s.onerror = function () { loadLeaflet(bases, i + 1); };
                  document.head.appendChild(s);
                }

                // Plugin putar peta (dua jari). Kalau gagal dimuat, peta tetap jalan tanpa putar.
                var ROT = [
                  'https://appassets.local/leaflet/leaflet-rotate-src.js',
                  'https://unpkg.com/leaflet-rotate@0.2.8/dist/leaflet-rotate-src.js',
                  'https://cdn.jsdelivr.net/npm/leaflet-rotate@0.2.8/dist/leaflet-rotate-src.js'
                ];
                function loadRotate(i) {
                  if (i >= ROT.length) { rotOk = false; initMap(); return; }
                  var s = document.createElement('script');
                  s.src = ROT[i];
                  s.onload = function () { rotOk = true; initMap(); };
                  s.onerror = function () { loadRotate(i + 1); };
                  document.head.appendChild(s);
                }

                loadLeaflet([
                  'https://appassets.local/leaflet/',
                  'https://unpkg.com/leaflet@1.9.4/dist/',
                  'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/'
                ], 0);

                // Ganti tampilan peta: 'osm' (Peta) atau 'sat' (Satelit). Gaya Peta bisa
                // Standar / Medan / Sepeda lewat tombol di kiri (setStyle).
                function applyBase() {
                  if (!map) return;
                  var want = satOn ? sat : (mapStyle === 'topo' ? topo : (mapStyle === 'cyclo' ? cyclo : osm));
                  [osm, topo, cyclo, sat].forEach(function (l) { if (l && l !== want && map.hasLayer(l)) map.removeLayer(l); });
                  if (!map.hasLayer(want)) { want.addTo(map); }
                  if (trailsOn) { if (!map.hasLayer(trails)) { trails.addTo(map); } }
                  else if (map.hasLayer(trails)) { map.removeLayer(trails); }
                  ['osm', 'topo', 'cyclo'].forEach(function (k) {
                    var e = document.getElementById('st_' + k);
                    if (e) { e.className = 'o' + ((!satOn && mapStyle === k) ? ' on' : ''); }
                  });
                  var ot = document.getElementById('ov_trails');
                  if (ot) { ot.className = 'o' + (trailsOn ? ' on' : ''); }
                }
                function setLayer(n) { satOn = (n === 'sat'); applyBase(); }
                function setStyle(n) {
                  mapStyle = n; satOn = false; applyBase();
                  try { localStorage.setItem('rt_style', n); } catch (e) { }
                }
                function toggleTrails() {
                  trailsOn = !trailsOn; applyBase();
                  try { localStorage.setItem('rt_trails', trailsOn ? '1' : '0'); } catch (e) { }
                }
                function lbToggle() {
                  var o = document.getElementById('lb').classList.toggle('open');
                  document.getElementById('elv').style.visibility = o ? 'hidden' : 'visible';
                }

                // Tombol lokasi: kembali mengikuti posisi, atau tampilkan seluruh rute.
                function locate() {
                  if (!map) return;
                  follow = true; pinned = false;
                  if (marker) { map.setView(marker.getLatLng(), Math.max(map.getZoom(), 16)); }
                  else if (route && route.getLatLngs().length > 1) { map.fitBounds(route.getBounds(), { padding: [40, 40] }); }
                  else if (endPin) { map.setView(endPin.getLatLng(), 16); }
                }

                // Rute yang sedang direkam: tambahkan titik baru dan ikuti posisi terakhir.
                function addPoints(arr) {
                  if (!map || arr.length === 0) return;
                  recording = true;
                  var first = route.getLatLngs().length === 0;
                  var prevPos = marker ? marker.getLatLng() : null;
                  for (var i = 0; i < arr.length; i++) { route.addLatLng(arr[i]); routeCase.addLatLng(arr[i]); }
                  var last = arr[arr.length - 1];
                  if (first) { startPin = L.marker(arr[0], { icon: pin('g') }).addTo(map); }
                  if (!marker) {
                    marker = gpsMarker(last).addTo(map);
                    map.setView(last, 17);
                  } else {
                    marker.setLatLng(last);
                    noteMove(prevPos, marker.getLatLng());
                    if (follow) map.panTo(last, { animate: true, duration: 0.8, easeLinearity: 0.5 });
                  }
                }

                // Rute perjalanan dari riwayat: tampilkan utuh dengan pin Start dan Finish.
                function showRoute(arr) {
                  if (!map || arr.length === 0) return;
                  route.setLatLngs(arr);
                  routeCase.setLatLngs(arr);
                  startPin = L.marker(arr[0], { icon: pin('g') }).addTo(map);
                  endPin = L.marker(arr[arr.length - 1], { icon: pin('r') }).addTo(map);
                  if (arr.length > 1) { map.fitBounds(route.getBounds(), { padding: [40, 40] }); }
                  else { map.setView(arr[0], 17); }
                }

                // Pin Stop (merah) di titik terakhir rute yang sedang direkam.
                function setEnd(lat, lon) {
                  if (!map) return;
                  if (!endPin) { endPin = L.marker([lat, lon], { icon: pin('r') }).addTo(map); }
                  else { endPin.setLatLng([lat, lon]); }
                }

                // Peta mini: tampilkan seluruh rute dan kunci gerakan jari.
                function fitAll() {
                  if (!map) return;
                  if (route.getLatLngs().length > 1) { map.fitBounds(route.getBounds(), { padding: [34, 34], maxZoom: 18 }); }
                }

                function lockMap() {
                  if (!map) return;
                  map.dragging.disable(); map.touchZoom.disable(); map.doubleClickZoom.disable();
                  map.scrollWheelZoom.disable(); map.boxZoom.disable(); map.keyboard.disable();
                  compactMap = true;
                  if (map.touchRotate) map.touchRotate.disable();
                  cmpEl().style.display = 'none'; hdEl().style.display = 'none';
                  document.getElementById('lb').style.display = 'none';
                  document.getElementById('elv').style.display = 'none';
                }

                // ---- Rencana rute manual: ketuk peta untuk menambah titik yang akan dilewati ----
                var plan = { on: false, road: true, loop: false, profile: 'bike', wps: [], mk: [], line: null, dist: 0 };
                var reqId = 0;

                function planInit() {
                  plan.line = L.polyline([], { color: '#1E90FF', weight: 6, opacity: 0.7, lineJoin: 'round', lineCap: 'round' }).addTo(map);
                  map.on('click', function (e) { if (plan.on) { addWp(e.latlng.lat, e.latlng.lng); } });
                  map.on('contextmenu', function (e) {
                    if (!plan.on) { return; }
                    if (e.originalEvent && e.originalEvent.preventDefault) { e.originalEvent.preventDefault(); }
                    Bridge.onLongPress(e.latlng.lat, e.latlng.lng);
                  });
                }

                function setPlan(on) { plan.on = on; renderWps(); }

                function setRoad(on, profile) { plan.road = on; plan.profile = profile; recompute(); }

                function addWp(lat, lon) { plan.wps.push([lat, lon]); renderWps(); recompute(); }

                function addCenter() { var c = map.getCenter(); addWp(c.lat, c.lng); }

                function startHere(lat, lon) {
                  if (plan.wps.length === 0) { plan.wps.push([lat, lon]); } else { plan.wps[0] = [lat, lon]; }
                  renderWps(); recompute();
                }

                function endHere(lat, lon) {
                  if (plan.wps.length < 2) { plan.wps.push([lat, lon]); } else { plan.wps[plan.wps.length - 1] = [lat, lon]; }
                  renderWps(); recompute();
                }

                function removeWp(i) { plan.wps.splice(i, 1); renderWps(); recompute(); }

                function moveWp(i, dir) {
                  var j = i + dir;
                  if (j < 0 || j >= plan.wps.length) { return; }
                  var t = plan.wps[i]; plan.wps[i] = plan.wps[j]; plan.wps[j] = t;
                  renderWps(); recompute();
                }

                function setLoop(on) { plan.loop = on; recompute(); }

                function searchPlace(q) {
                  fetch('https://nominatim.openstreetmap.org/search?format=json&limit=1&q=' + encodeURIComponent(q))
                    .then(function (r) { return r.json(); }).then(function (j) {
                      if (j && j[0]) { follow = false; pinned = true; map.setView([parseFloat(j[0].lat), parseFloat(j[0].lon)], 16); Bridge.onMsg('Ditemukan: ' + j[0].display_name.split(',')[0]); }
                      else { Bridge.onMsg('Lokasi tidak ditemukan'); }
                    }).catch(function () { Bridge.onMsg('Pencarian butuh internet'); });
                }

                function undoWp() { plan.wps.pop(); renderWps(); recompute(); }

                function clearPlan() { plan.wps = []; renderWps(); recompute(); }

                function loadPlan(arr, road, profile, fit, loop) {
                  plan.wps = arr; plan.road = road; plan.profile = profile; plan.loop = !!loop;
                  renderWps(); recompute();
                  if (fit && arr.length > 1) { map.fitBounds(L.latLngBounds(arr), { padding: [50, 50] }); }
                  else if (fit && arr.length === 1) { map.setView(arr[0], 16); }
                }

                function renderWps() {
                  for (var i = 0; i < plan.mk.length; i++) { map.removeLayer(plan.mk[i]); }
                  plan.mk = [];
                  plan.wps.forEach(function (p, i) {
                    var m = L.marker(p, {
                      draggable: plan.on,
                      icon: L.divIcon({ className: 'wpi', html: '<div class="wp" style="' + (plan.wps.length > 1 && i === 0 ? 'background:#27D282;' : (plan.wps.length > 1 && i === plan.wps.length - 1 ? 'background:#FF3B3B;color:#fff;' : '')) + '">' + (plan.wps.length > 1 && i === 0 ? 'A' : (plan.wps.length > 1 && i === plan.wps.length - 1 ? 'B' : (i + 1))) + '</div>', iconSize: [26, 26], iconAnchor: [13, 13] })
                    }).addTo(map);
                    m.on('dragend', function (ev) { var q = ev.target.getLatLng(); plan.wps[i] = [q.lat, q.lng]; recompute(); });
                    plan.mk.push(m);
                  });
                }

                function straightDist(a) {
                  var d = 0;
                  for (var i = 1; i < a.length; i++) { d += map.distance(a[i - 1], a[i]); }
                  return d;
                }

                // Tanjakan/turunan rute rencana (data tinggi dari Open-Meteo, resolusi sekitar 90 m).
                var elvTimer = null, elvReq = 0;
                var srfTimer = null, srfReq = 0;
                function elevUpdate() {
                  clearTimeout(elvTimer);
                  clearTimeout(srfTimer);
                  var box = document.getElementById('elv');
                  if (!box) { return; }
                  var g = plan.line.getLatLngs();
                  if (g.length < 2 || plan.wps.length < 2) {
                    srfReq++;
                    box.style.display = 'none';
                    document.getElementById('elv_s').innerHTML = '';
                    document.getElementById('elv_b').innerHTML = '';
                    return;
                  }
                  elvTimer = setTimeout(function () { elevFetch(g); }, 700);
                  srfTimer = setTimeout(function () { surfFetch(g); }, 1600);
                }

                // Permukaan jalan dari data OpenStreetMap (Overpass). Banyak jalan belum diberi tag, jadi
                // bagian yang tidak diketahui ditampilkan apa adanya.
                var PAVED = { asphalt: 1, paved: 1, concrete: 1, 'concrete:plates': 1, 'concrete:lanes': 1,
                  paving_stones: 1, sett: 1, cobblestone: 1, bricks: 1, brick: 1, metal: 1, chipseal: 1 };
                var UNPAVED = { unpaved: 1, gravel: 1, fine_gravel: 1, dirt: 1, earth: 1, ground: 1, mud: 1, sand: 1,
                  grass: 1, compacted: 1, pebblestone: 1, rock: 1, woodchips: 1, grass_paver: 1 };
                function overpass(q) {
                  var eps = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'];
                  function tryAt(i) {
                    if (i >= eps.length) { return Promise.resolve(null); }
                    return fetch(eps[i], { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                      body: 'data=' + encodeURIComponent(q) })
                      .then(function (r) { if (!r.ok) { throw new Error('x'); } return r.json(); })
                      .catch(function () { return tryAt(i + 1); });
                  }
                  return tryAt(0);
                }
                function segDist(p, a, b) {
                  var kx = Math.cos(p.lat * Math.PI / 180) * 111320, ky = 110540;
                  var ax = (a.lon - p.lng) * kx, ay = (a.lat - p.lat) * ky;
                  var bx = (b.lon - p.lng) * kx, by = (b.lat - p.lat) * ky;
                  var dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
                  var t = l > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
                  var cx = ax + t * dx, cy = ay + t * dy;
                  return Math.sqrt(cx * cx + cy * cy);
                }
                function surfFetch(g) {
                  var my = ++srfReq;
                  var cum = [0];
                  for (var i = 1; i < g.length; i++) { cum.push(cum[i - 1] + map.distance(g[i - 1], g[i])); }
                  var total = cum[cum.length - 1];
                  if (total < 50) { return; }
                  var n = Math.min(150, Math.max(2, Math.round(total / 40) + 1));
                  var sm = [], j = 0;
                  for (var k = 0; k < n; k++) {
                    var want = total * k / (n - 1);
                    while (j < cum.length - 2 && cum[j + 1] < want) { j++; }
                    var seg = cum[j + 1] - cum[j];
                    var t = seg > 0 ? (want - cum[j]) / seg : 0;
                    sm.push({ lat: g[j].lat + (g[j + 1].lat - g[j].lat) * t, lng: g[j].lng + (g[j + 1].lng - g[j].lng) * t });
                  }
                  var step = Math.max(1, Math.ceil(sm.length / 60));
                  var line = [];
                  for (var m = 0; m < sm.length; m += step) { line.push(sm[m].lat.toFixed(5) + ',' + sm[m].lng.toFixed(5)); }
                  var last = sm[sm.length - 1];
                  line.push(last.lat.toFixed(5) + ',' + last.lng.toFixed(5));
                  var q = '[out:json][timeout:25];way(around:20,' + line.join(',') + ')[highway];out tags geom;';
                  overpass(q).then(function (d) {
                    if (my !== srfReq || !d || !d.elements) { return; }
                    var ways = d.elements.filter(function (w) { return w.geometry && w.geometry.length > 1; });
                    var cnt = { paved: 0, unpaved: 0, unknown: 0 };
                    sm.forEach(function (p) {
                      var best = 1e9, bw = null;
                      ways.forEach(function (w) {
                        for (var x = 1; x < w.geometry.length; x++) {
                          var dd = segDist(p, w.geometry[x - 1], w.geometry[x]);
                          if (dd < best) { best = dd; bw = w; }
                        }
                      });
                      var sf = bw && best <= 25 && bw.tags ? bw.tags.surface : null;
                      if (sf && PAVED[sf]) { cnt.paved++; }
                      else if (sf && UNPAVED[sf]) { cnt.unpaved++; }
                      else { cnt.unknown++; }
                    });
                    surfShow(cnt, sm.length);
                  });
                }
                function surfShow(c, n) {
                  var pa = Math.round(c.paved * 100 / n), un = Math.round(c.unpaved * 100 / n), uk = Math.max(0, 100 - pa - un);
                  var parts = [];
                  if (pa > 0) { parts.push('Aspal/beton ' + pa + '%'); }
                  if (un > 0) { parts.push('Tanah/kerikil ' + un + '%'); }
                  if (uk > 0) { parts.push('Tak diketahui ' + uk + '%'); }
                  document.getElementById('elv_s').innerHTML = parts.join(' &middot; ');
                  var w = 110, x = 0, r = '';
                  [[pa, ACCENT], [un, '#C98B3C'], [uk, '#8A94A0']].forEach(function (it) {
                    var wd = w * it[0] / 100;
                    if (wd > 0) { r += '<rect x="' + x.toFixed(1) + '" y="0" width="' + wd.toFixed(1) + '" height="6" rx="2" fill="' + it[1] + '"/>'; }
                    x += wd;
                  });
                  document.getElementById('elv_b').innerHTML = r;
                  document.getElementById('elv').style.display = 'block';
                }
                function elevFetch(g) {
                  var my = ++elvReq;
                  var cum = [0];
                  for (var i = 1; i < g.length; i++) { cum.push(cum[i - 1] + map.distance(g[i - 1], g[i])); }
                  var total = cum[cum.length - 1];
                  if (total < 50) { return; }
                  var n = Math.min(100, Math.max(2, Math.round(total / 100) + 1));
                  var la = [], lo = [], j = 0;
                  for (var k = 0; k < n; k++) {
                    var want = total * k / (n - 1);
                    while (j < cum.length - 2 && cum[j + 1] < want) { j++; }
                    var seg = cum[j + 1] - cum[j];
                    var t = seg > 0 ? (want - cum[j]) / seg : 0;
                    la.push((g[j].lat + (g[j + 1].lat - g[j].lat) * t).toFixed(5));
                    lo.push((g[j].lng + (g[j + 1].lng - g[j].lng) * t).toFixed(5));
                  }
                  fetch('https://api.open-meteo.com/v1/elevation?latitude=' + la.join(',') + '&longitude=' + lo.join(','))
                    .then(function (r) { return r.json(); })
                    .then(function (d) { if (my === elvReq && d.elevation && d.elevation.length > 1) { elevShow(d.elevation); } })
                    .catch(function () { });
                }
                function elevShow(e) {
                  var s = e.map(function (v, i) { return (e[Math.max(0, i - 1)] + v + e[Math.min(e.length - 1, i + 1)]) / 3; });
                  var up = 0, dn = 0, mn = s[0], mx = s[0];
                  for (var i = 1; i < s.length; i++) {
                    var d = s[i] - s[i - 1];
                    if (d > 0) { up += d; } else { dn -= d; }
                    mn = Math.min(mn, s[i]); mx = Math.max(mx, s[i]);
                  }
                  var w = 110, h = 26, rng = Math.max(mx - mn, 5), pts = [];
                  for (var m = 0; m < s.length; m++) {
                    pts.push((m / (s.length - 1) * w).toFixed(1) + ',' + (h - 2 - (s[m] - mn) / rng * (h - 4)).toFixed(1));
                  }
                  document.getElementById('elv_t').innerHTML = '&#9650; ' + Math.round(up) + ' m &nbsp; &#9660; ' + Math.round(dn) + ' m';
                  document.getElementById('elv_g').innerHTML = '<polyline fill="none" stroke="' + ACCENT + '" stroke-width="2" points="' + pts.join(' ') + '"/>';
                  document.getElementById('elv').style.display = 'block';
                }

                function report(snapped, err) {
                  elevUpdate();
                  Bridge.onPlan(plan.dist, plan.wps.length, snapped, err, JSON.stringify(plan.wps));
                  try {
                    var g = plan.line.getLatLngs();
                    var st = Math.max(1, Math.ceil(g.length / 400));
                    var o = [];
                    for (var i = 0; i < g.length; i += st) { o.push(g[i].lat.toFixed(5) + ',' + g[i].lng.toFixed(5)); }
                    if (g.length > 0 && (g.length - 1) % st !== 0) { var z = g[g.length - 1]; o.push(z.lat.toFixed(5) + ',' + z.lng.toFixed(5)); }
                    if (Bridge.onGeo) { Bridge.onGeo(o.join(';')); }
                  } catch (e) { }
                }

                function recompute() {
                  var id = ++reqId;
                  var pts = plan.wps.slice();
                  if (plan.loop && pts.length >= 2) { pts.push(pts[0]); }
                  plan.line.setLatLngs(pts);
                  plan.dist = straightDist(pts);
                  report(false, false);
                  if (!plan.road || pts.length < 2) { return; }
                  var coords = pts.map(function (p) { return p[1] + ',' + p[0]; }).join(';');
                  var main = plan.profile === 'foot' ? 'routed-foot' : 'routed-bike';
                  var straight = straightDist(pts);
                  function routeVia(prof) {
                    var url = 'https://routing.openstreetmap.de/' + prof + '/route/v1/driving/' + coords + '?overview=full&geometries=geojson';
                    return fetch(url).then(function (r) { return r.json(); })
                      .then(function (j) { return (j.routes && j.routes[0]) ? j.routes[0] : null; })
                      .catch(function () { return null; });
                  }
                  function show(rt) {
                    var c = rt.geometry.coordinates.map(function (q) { return [q[1], q[0]]; });
                    plan.line.setLatLngs(c);
                    plan.dist = rt.distance;
                    report(true, false);
                  }
                  // Jika rute utama memutar terlalu jauh (jalan satu arah / jalan raya
                  // tidak boleh dilewati sepeda), coba profil lain dan pakai yang terpendek.
                  routeVia(main).then(function (best) {
                    if (id !== reqId) { return; }
                    var detour = !best || (straight > 0 && best.distance > straight * 1.6 + 300);
                    if (!detour) { show(best); return; }
                    var others = ['routed-foot', 'routed-car'].filter(function (x) { return x !== main; });
                    Promise.all(others.map(routeVia)).then(function (rs) {
                      if (id !== reqId) { return; }
                      rs.push(best);
                      var pick = null;
                      rs.forEach(function (r) { if (r && (!pick || r.distance < pick.distance)) { pick = r; } });
                      if (pick) { show(pick); } else { report(false, true); }
                    });
                  });
                }

                // Titik survei: penanda oranye yang bisa dipilih.
                var marks = null;
                function addMark(lat, lon, name) {
                  if (!map) return;
                  if (!marks) { marks = L.layerGroup().addTo(map); }
                  L.circleMarker([lat, lon], { radius: 9, color: '#ffffff', weight: 3, fillColor: '#FF9F1C', fillOpacity: 1 })
                    .bindTooltip(name, { permanent: false }).addTo(marks);
                }
                function clearMarks() { if (marks) { marks.clearLayers(); } }
                function focusMark(lat, lon) { if (!map) return; follow = false; pinned = true; map.setView([lat, lon], 18); }

                // Posisi saat ini (tanpa tracking).
                function setPosition(lat, lon, first) {
                  if (!map) return;
                  var p = [lat, lon];
                  if (!marker) { marker = gpsMarker(p).addTo(map); }
                  else { var pv = marker.getLatLng(); marker.setLatLng(p); noteMove(pv, marker.getLatLng()); }
                  if (first) { map.setView(p, 17); } else if (follow) { map.panTo(p); }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
