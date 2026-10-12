package com.biketracker.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.Toast
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
    private val compact: Boolean = false,
    private val survey: Boolean = false,
    private val profile: String = "bike"
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
    private var planBtn: TextView? = null
    private var planBar: LinearLayout? = null
    private var planInfo: TextView? = null
    private var planRoadBtn: TextView? = null
    private var planOn = false
    private var planRoad = true
    private var planDist = 0.0
    private var planWps: List<DoubleArray> = emptyList()
    private var firstPos = true

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
                if (survey) {
                    loadActivePlan()
                    loadSavedMarks()
                }
            }
        }

        @JavascriptInterface
        fun onPlan(dist: Double, count: Int, snapped: Boolean, err: Boolean, json: String) {
            handler.post { onPlanChanged(dist, count, snapped, err, json) }
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
        background = round(t.surface, 18)
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
            leftMargin = dp(10); topMargin = dp(10)
        })

        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (survey) {
            planBtn = roundButton("✎") { togglePlan() }
            controls.addView(planBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(6) })
        }
        controls.addView(roundButton("+") { if (ready) web.evaluateJavascript("if(map)map.zoomIn()", null) },
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(6) })
        controls.addView(roundButton("−") { if (ready) web.evaluateJavascript("if(map)map.zoomOut()", null) },
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(6) })
        controls.addView(roundButton("◎") { if (ready) web.evaluateJavascript("locate()", null) },
            LinearLayout.LayoutParams(dp(34), dp(34)))
        if (!compact) addView(controls, LayoutParams(-2, -2, if (survey) Gravity.END or Gravity.TOP else Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(10); bottomMargin = dp(12); topMargin = dp(10)
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
        if (!survey) addView(legend, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
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
        if (survey) buildPlanBar()

        handler.postDelayed({
            if (!ready && !failed) {
                status.text = "Peta belum termuat. Periksa koneksi internet."
            }
        }, 15000)
    }

    // ---------- Rencana rute (hanya mode survei) ----------

    private fun prefs() = context.getSharedPreferences("survey", Context.MODE_PRIVATE)

    private fun pill(label: String, fill: Int, textColor: Int = onColor(fill), action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(textColor)
        background = round(fill, 15)
        setOnClickListener { action() }
    }

    private fun buildPlanBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = round((t.surface and 0x00FFFFFF) or 0xEE000000.toInt(), 14)
            elevation = 6f * d
            visibility = View.GONE
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = "Rencana rute"; textSize = 12f; setTextColor(t.text); typeface = Typeface.DEFAULT_BOLD
        })
        val info = TextView(context).apply {
            text = " · 0,00 km · 0 titik · ketuk peta untuk menambah titik"
            textSize = 12f; setTextColor(t.muted)
            maxLines = 1
            setOnClickListener {
                planRoad = !planRoad
                if (ready) web.evaluateJavascript("setRoad($planRoad,'$profile')", null)
                onPlanChanged(planDist, planWps.size, false, false, wpsJson(planWps))
            }
        }
        planInfo = info
        head.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(context).apply {
            text = "☰"; textSize = 16f; setTextColor(t.text); gravity = Gravity.CENTER
            setOnClickListener { choosePlanDialog() }
        }, LinearLayout.LayoutParams(dp(30), dp(26)))
        bar.addView(head)

        val row = LinearLayout(context)
        row.addView(pill("↶ Urungkan", t.bg, t.text) { if (ready) web.evaluateJavascript("undoWp()", null) },
            LinearLayout.LayoutParams(-2, dp(30)))
        row.addView(pill("Hapus", t.bg, t.text) { if (ready) web.evaluateJavascript("clearPlan()", null) },
            LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(6) })
        row.addView(pill("Simpan", 0xFFFFB020.toInt(), 0xFF111111.toInt()) { savePlanDialog() },
            LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(6) })
        row.addView(pill("Google Maps ↗", t.bg, t.text) {
            if (planWps.size < 2) Toast.makeText(context, "Tambahkan minimal 2 titik.", Toast.LENGTH_SHORT).show()
            else openDirections(planWps, profile)
        }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(6) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        planBar = bar
        addView(bar, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(8)
        })
    }

    private fun togglePlan() {
        if (!ready) {
            Toast.makeText(context, "Peta belum siap.", Toast.LENGTH_SHORT).show()
            return
        }
        planOn = !planOn
        planBar?.visibility = if (planOn) View.VISIBLE else View.GONE
        planBtn?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (planOn) 0xFFFFB020.toInt() else t.surface)
        }
        planBtn?.setTextColor(if (planOn) 0xFF111111.toInt() else t.text)
        web.evaluateJavascript("setPlan($planOn)", null)
    }

    private fun onPlanChanged(dist: Double, count: Int, snapped: Boolean, err: Boolean, json: String) {
        planDist = dist
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
        val how = when {
            count < 2 -> "ketuk peta untuk menambah titik"
            !planRoad -> "garis lurus (ketuk: ikuti jalan)"
            err -> "jalan tidak ditemukan"
            snapped -> "mengikuti jalan"
            else -> "menghitung jalan…"
        }
        planInfo?.text = " · " + Fmt.dec(dist / 1000.0, 2) + " km · " + count + " titik · " + how
    }

    private fun wpsText(w: List<DoubleArray>) = w.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it[0], it[1]) }

    private fun parseWps(s: String): List<DoubleArray> = s.split(";").mapNotNull { q ->
        val p = q.split(",")
        val la = p.getOrNull(0)?.toDoubleOrNull()
        val lo = p.getOrNull(1)?.toDoubleOrNull()
        if (la != null && lo != null) doubleArrayOf(la, lo) else null
    }

    private fun wpsJson(w: List<DoubleArray>): String {
        val sb = StringBuilder("[")
        for ((i, p) in w.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(String.format(Locale.US, "[%.6f,%.6f]", p[0], p[1]))
        }
        return sb.append(']').toString()
    }

    /** Rencana tersimpan: id|nama|road(0/1)|profil|jarakM|lat,lon;lat,lon (sama dengan menu Peta). */
    private fun loadPlans(): MutableList<List<String>> =
        (prefs().getString("plans", "") ?: "").lines().filter { it.isNotBlank() }
            .map { it.split("|") }.filter { it.size >= 6 }.toMutableList()

    private fun loadPlanOnMap(p: List<String>, fit: Boolean) {
        if (!ready) return
        planRoad = p[2] == "1"
        web.evaluateJavascript("loadPlan(${wpsJson(parseWps(p[5]))},$planRoad,'${p[3]}',$fit)", null)
    }

    private fun loadActivePlan() {
        val act = prefs().getString("active_plan", "") ?: ""
        loadPlans().firstOrNull { it[0] == act }?.let { loadPlanOnMap(it, false) }
    }

    private fun choosePlanDialog() {
        val plans = loadPlans()
        if (plans.isEmpty()) {
            Toast.makeText(context, "Belum ada rencana tersimpan.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = plans.map { it[1] + " · " + Fmt.dec((it[4].toDoubleOrNull() ?: 0.0) / 1000.0, 2) + " km" }
        AlertDialog.Builder(context)
            .setTitle("Rencana tersimpan")
            .setItems(names.toTypedArray()) { _, i ->
                if (!planOn) togglePlan()
                loadPlanOnMap(plans[i], true)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun savePlanDialog() {
        if (planWps.size < 2) {
            Toast.makeText(context, "Tambahkan minimal 2 titik.", Toast.LENGTH_SHORT).show()
            return
        }
        val et = android.widget.EditText(context).apply {
            setText("Rute " + (loadPlans().size + 1))
            setSingleLine()
        }
        AlertDialog.Builder(context)
            .setTitle("Simpan rencana rute")
            .setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                val name = et.text.toString().replace("|", " ").replace("\n", " ").trim().ifEmpty { "Rute" }
                val list = loadPlans()
                list.add(0, listOf(
                    System.currentTimeMillis().toString(), name, if (planRoad) "1" else "0",
                    profile, planDist.toInt().toString(), wpsText(planWps)
                ))
                prefs().edit().putString("plans", list.take(20).joinToString("\n") { it.joinToString("|") }).apply()
                Toast.makeText(context, "Rencana \"$name\" disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openDirections(w: List<DoubleArray>, prof: String) {
        val pts = if (w.size <= 10) w else List(10) { i -> w[(i * (w.size - 1)) / 9] }
        val sb = StringBuilder("https://www.google.com/maps/dir/")
        for (q in pts) sb.append(String.format(Locale.US, "%.6f,%.6f/", q[0], q[1]))
        sb.append("?travelmode=").append(if (prof == "bike") "bicycling" else "walking")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString())))
        } catch (e: Exception) {
            Toast.makeText(context, "Tidak bisa membuka peta.", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSavedMarks() {
        for (line in (prefs().getString("points", "") ?: "").lines()) {
            val f = line.split("|")
            if (f.size >= 5) markPoint(f[1].toDoubleOrNull() ?: continue, f[2].toDoubleOrNull() ?: continue, f[4])
        }
    }

    /** Penanda titik survei (oranye) di peta. */
    fun markPoint(lat: Double, lon: Double, name: String) {
        if (!ready) return
        val safe = name.replace("'", "").replace("\\", "")
        web.evaluateJavascript(String.format(Locale.US, "addMark(%.6f,%.6f,'%s')", lat, lon, safe), null)
    }

    /** Pusatkan peta pada satu titik. */
    fun focusPoint(lat: Double, lon: Double) {
        if (ready) web.evaluateJavascript(String.format(Locale.US, "focusMark(%.6f,%.6f)", lat, lon), null)
    }

    /** Posisi GPS saat ini (dipakai saat belum merekam). */
    fun position(lat: Double, lon: Double) {
        if (!ready) return
        web.evaluateJavascript(String.format(Locale.US, "setPosition(%.6f,%.6f,%b)", lat, lon, firstPos), null)
        firstPos = false
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
