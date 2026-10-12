package com.biketracker.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Peta sungguhan (Leaflet) yang ditanam di halaman "Rute" layar tracking:
 * tab Peta/Satelit, tombol + − ◎, pin Start, dan garis rute yang bertambah langsung.
 * Butuh internet untuk memuat peta; rute tetap direkam walau peta gagal dimuat.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MapPanel(
    ctx: Context,
    private val t: AppTheme,
    private val accent: Int,
    private val compact: Boolean = false
) : FrameLayout(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private val d = resources.displayMetrics.density
    private val web = WebView(ctx)
    private val status: TextView
    private val tabs = ArrayList<TextView>()
    private var ready = false
    private var failed = false
    private var satellite = false
    private var sent = 0
    private var pending: List<DoubleArray> = emptyList()

    inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            handler.post {
                ready = true
                status.visibility = View.GONE
                styleTabs()
                web.evaluateJavascript("setLayer('${if (satellite) "sat" else "osm"}')", null)
                if (compact) web.evaluateJavascript("lockMap()", null)
                flush()
            }
        }

        @JavascriptInterface
        fun onFail() {
            handler.post {
                failed = true
                status.text = "Peta butuh internet. Rute tetap direkam."
                status.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun styleTabs() {
        tabs.forEachIndexed { i, v ->
            val sel = (i == 1) == satellite
            v.setTextColor(if (sel) onColor(accent) else t.muted)
            v.background = if (sel) round(accent, 14) else null
        }
    }

    private fun roundButton(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 20f
        setTextColor(t.text)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.surface) }
        elevation = 4f * d
        setOnClickListener { action() }
    }

    init {
        background = round(t.surface, 22)
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        MapHtml.applyCache(web)
        web.setBackgroundColor(t.surface)
        web.addJavascriptInterface(Bridge(), "Bridge")
        web.webViewClient = WebViewClient()
        // Geser di dalam peta menggeser peta; geser dari tepi kiri/kanan tetap pindah halaman.
        web.setOnTouchListener { v, e ->
            if (!compact && e.actionMasked == MotionEvent.ACTION_DOWN) {
                val edge = 28f * d
                val inside = e.x > edge && e.x < v.width - edge
                parent?.requestDisallowInterceptTouchEvent(inside)
            }
            false
        }
        addView(web, LayoutParams(-1, -1))

        val hex = String.format("#%06X", 0xFFFFFF and accent)
        web.loadDataWithBaseURL("https://www.openstreetmap.org/", MapHtml.build(hex), "text/html", "UTF-8", null)

        val tabRow = LinearLayout(context).apply {
            background = round(t.surface, 18)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = 4f * d
        }
        listOf("Peta", "Satelit").forEachIndexed { i, name ->
            val v = TextView(context).apply {
                text = name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    satellite = i == 1
                    styleTabs()
                    if (ready) web.evaluateJavascript("setLayer('${if (satellite) "sat" else "osm"}')", null)
                }
            }
            tabs.add(v)
            tabRow.addView(v, LinearLayout.LayoutParams(-2, dp(28)))
        }
        styleTabs()
        if (!compact) addView(tabRow, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(12); topMargin = dp(12)
        })

        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(roundButton("+") { if (ready) web.evaluateJavascript("if(map)map.zoomIn()", null) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) })
        controls.addView(roundButton("−") { if (ready) web.evaluateJavascript("if(map)map.zoomOut()", null) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) })
        controls.addView(roundButton("◎") { if (ready) web.evaluateJavascript("locate()", null) },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        if (!compact) addView(controls, LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(12); bottomMargin = dp(12)
        })

        // Legenda Start / Stop
        val legend = LinearLayout(context)
        fun chip(color: Int, label: String): View {
            val c = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(10), dp(4))
                background = round(t.surface, 14)
                elevation = 4f * d
            }
            c.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }, LinearLayout.LayoutParams(dp(9), dp(9)))
            c.addView(TextView(context).apply {
                text = label; textSize = 11f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) })
            return c
        }
        legend.addView(chip(0xFF27D282.toInt(), "Start"))
        legend.addView(chip(0xFFFF3B3B.toInt(), "Stop"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        addView(legend, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(10); bottomMargin = dp(10)
        })

        status = TextView(context).apply {
            text = "Memuat peta…"
            textSize = 12f
            setTextColor(t.muted)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        addView(status, LayoutParams(-2, -2, Gravity.CENTER))

        handler.postDelayed({
            if (!ready && !failed) {
                status.text = "Peta belum termuat. Periksa koneksi internet."
            }
        }, 15000)
    }

    /** Kirim titik rute baru ke peta (aman dipanggil tiap detik). */
    fun sync(route: List<DoubleArray>) {
        pending = route
        if (ready) flush()
    }

    private fun flush() {
        val r = pending
        if (r.size < sent) {
            sent = 0
            web.evaluateJavascript("route.setLatLngs([])", null)
        }
        if (r.size > sent) {
            val sb = StringBuilder("[")
            for (i in sent until r.size) {
                if (i > sent) sb.append(',')
                sb.append(String.format(Locale.US, "[%.6f,%.6f]", r[i][0], r[i][1]))
            }
            sb.append(']')
            web.evaluateJavascript("addPoints($sb)", null)
            val last = r[r.size - 1]
            web.evaluateJavascript(String.format(Locale.US, "setEnd(%.6f,%.6f)", last[0], last[1]), null)
            if (compact) web.evaluateJavascript("fitAll()", null)
            sent = r.size
        }
    }

    fun onPause() = web.onPause()

    fun onResume() = web.onResume()

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            web.stopLoading()
            web.destroy()
        } catch (e: Exception) {
        }
    }
}
