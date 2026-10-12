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
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                var map = null, route = null, marker = null, startPin = null, endPin = null;
                var osm = null, sat = null, follow = true;
                var ACCENT = '$accentHex';

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
                  map = L.map('map', { zoomControl: false }).setView([-2.5, 118], 5);
                  osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '&copy; OpenStreetMap contributors'
                  }).addTo(map);
                  sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
                    maxZoom: 19,
                    attribution: 'Tiles &copy; Esri'
                  });
                  map.on('dragstart', function () { follow = false; });
                  route = L.polyline([], { color: ACCENT, weight: 5 }).addTo(map);
                  planInit();
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
                  for (var i = 0; i < arr.length; i++) { route.addLatLng(arr[i]); }
                  var last = arr[arr.length - 1];
                  if (first) { startPin = L.marker(arr[0], { icon: pin('g') }).addTo(map); }
                  if (!marker) {
                    marker = L.circleMarker(last, dotStyle(ACCENT)).addTo(map);
                    map.setView(last, 17);
                  } else {
                    marker.setLatLng(last);
                    if (follow) map.panTo(last);
                  }
                }

                // Rute perjalanan dari riwayat: tampilkan utuh dengan pin Start dan Finish.
                function showRoute(arr) {
                  if (!map || arr.length === 0) return;
                  route.setLatLngs(arr);
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
                }

                // ---- Rencana rute manual: ketuk peta untuk menambah titik yang akan dilewati ----
                var plan = { on: false, road: true, loop: false, profile: 'bike', wps: [], mk: [], line: null, dist: 0 };
                var reqId = 0;

                function planInit() {
                  plan.line = L.polyline([], { color: '#FFB020', weight: 5, dashArray: '10 8' }).addTo(map);
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
                    // Penanda rute tanpa huruf/angka (A, B, 1, 2, 3); titik tetap bisa digeser saat mode edit.
                    var m = L.marker(p, {
                      draggable: plan.on,
                      icon: L.divIcon({ className: '', html: '<div style="width:12px;height:12px;border-radius:50%;background:#43C5FF;border:3px solid #fff;box-shadow:0 1px 5px #0008;"></div>', iconSize: [18, 18], iconAnchor: [9, 9] })
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
                  if (!marker) { marker = L.circleMarker(p, dotStyle(ACCENT)).addTo(map); }
                  else { marker.setLatLng(p); }
                  if (first) { map.setView(p, 17); } else if (follow) { map.panTo(p); }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
