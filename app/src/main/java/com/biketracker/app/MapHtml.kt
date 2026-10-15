package com.biketracker.app

/** Halaman HTML Leaflet yang dipakai MapActivity dan panel peta di layar tracking. */
object MapHtml {
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
              <script>
                var map = null, route = null, routeCase = null, marker = null, startPin = null, endPin = null;
                var osm = null, sat = null, follow = true;
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
                  osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '&copy; OpenStreetMap contributors'
                  }).addTo(map);
                  sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
                    maxZoom: 19,
                    attribution: 'Tiles &copy; Esri'
                  });
                  map.on('dragstart', function () { follow = false; });
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
                  'https://unpkg.com/leaflet@1.9.4/dist/',
                  'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/'
                ], 0);

                // Ganti tampilan peta: 'osm' (Peta) atau 'sat' (Satelit).
                function setLayer(n) {
                  if (!map) return;
                  if (n === 'sat') { if (map.hasLayer(osm)) map.removeLayer(osm); if (!map.hasLayer(sat)) sat.addTo(map); }
                  else { if (map.hasLayer(sat)) map.removeLayer(sat); if (!map.hasLayer(osm)) osm.addTo(map); }
                }

                // Tombol lokasi: kembali mengikuti posisi, atau tampilkan seluruh rute.
                function locate() {
                  if (!map) return;
                  follow = true;
                  if (marker) { map.setView(marker.getLatLng(), Math.max(map.getZoom(), 16)); }
                  else if (route && route.getLatLngs().length > 1) { map.fitBounds(route.getBounds(), { padding: [40, 40] }); }
                  else if (endPin) { map.setView(endPin.getLatLng(), 16); }
                }

                // Rute yang sedang direkam: tambahkan titik baru dan ikuti posisi terakhir.
                function addPoints(arr) {
                  if (!map || arr.length === 0) return;
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
                    if (follow) map.panTo(last);
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
                      if (j && j[0]) { follow = false; map.setView([parseFloat(j[0].lat), parseFloat(j[0].lon)], 16); Bridge.onMsg('Ditemukan: ' + j[0].display_name.split(',')[0]); }
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

                function report(snapped, err) {
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
                  var prof = plan.profile === 'foot' ? 'routed-foot' : 'routed-bike';
                  var url = 'https://routing.openstreetmap.de/' + prof + '/route/v1/driving/' + coords + '?overview=full&geometries=geojson';
                  fetch(url).then(function (r) { return r.json(); }).then(function (j) {
                    if (id !== reqId) { return; }
                    if (j.routes && j.routes[0]) {
                      var c = j.routes[0].geometry.coordinates.map(function (q) { return [q[1], q[0]]; });
                      plan.line.setLatLngs(c);
                      plan.dist = j.routes[0].distance;
                      report(true, false);
                    } else { report(false, true); }
                  }).catch(function () { if (id === reqId) { report(false, true); } });
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
                function focusMark(lat, lon) { if (!map) return; follow = false; map.setView([lat, lon], 18); }

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
