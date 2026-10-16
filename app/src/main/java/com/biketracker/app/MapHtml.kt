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