package com.biketracker.app

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val GAUGE_SCALE = 0.72f

/** Speedometer analog: busur segmen hijau -> merah, skala, penunjuk, angka digital di tengah. */
class GaugeView(ctx: Context, private val t: AppTheme) : View(ctx) {
    var speed = 0f
    var maxSpeed = 0f
    var scaleMax = 60f
    var digital = false
    var accent: Int = t.accent
    var scale = GAUGE_SCALE

    private val ev = ArgbEvaluator()
    private val stops = intArrayOf(
        0xFF27E35A.toInt(), 0xFFE6D93A.toInt(), 0xFFFF8A1F.toInt(), 0xFFFF2A2A.toInt()
    )
    private val at = floatArrayOf(0f, 0.4f, 0.7f, 1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF(150f - 122f, 150f - 122f, 150f + 122f, 150f + 122f)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * 0.8f * scale).toInt())
    }

    private fun mix(a: Int, b: Int, k: Float): Int = ev.evaluate(k, a, b) as Int

    private fun grad(f: Float): Int {
        for (i in 1 until stops.size) {
            if (f <= at[i]) return mix(stops[i - 1], stops[i], (f - at[i - 1]) / (at[i] - at[i - 1]))
        }
        return stops[stops.size - 1]
    }

    private fun rad(a: Float) = a * Math.PI.toFloat() / 180f
    private fun px(r: Float, a: Float) = 150f + r * cos(rad(a))
    private fun py(r: Float, a: Float) = 150f + r * sin(rad(a))

    private fun lab(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.01f) v.roundToInt().toString()
        else String.format(Locale.US, "%.1f", v)

    override fun onDraw(c: Canvas) {
        val s = width * scale / 300f
        c.save()
        c.translate((width - 300f * s) / 2f, 0f)
        c.scale(s, s)
        c.translate(0f, -18f)

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = 14f
        val n = 54
        for (i in 0 until n) {
            val a0 = 135f + i * 5f
            val mid = (a0 + 2f - 135f) / 270f * scaleMax
            var col = grad(i / (n - 1f))
            if (mid > speed) col = mix(col, t.surface2, 0.75f)
            p.color = col
            c.drawArc(rect, a0, 4f, false, p)
        }

        if (!digital) {
            for (k in 0..30) {
                val a = 135f + 270f * k / 30f
                val major = k % 5 == 0
                p.color = if (major) t.text else t.muted
                p.strokeWidth = if (major) 2.5f else 1.2f
                val r2 = if (major) 108f else 104f
                c.drawLine(px(99f, a), py(99f, a), px(r2, a), py(r2, a), p)
            }
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.textSize = 11f
            p.color = t.muted
            p.typeface = Typeface.DEFAULT
            for (k in 0..6) {
                val v = k * scaleMax / 6f
                val a = 135f + 270f * k / 6f
                c.drawText(lab(v), px(87f, a), py(87f, a) + 4f, p)
            }
        }

        if (maxSpeed > 0f) {
            val a = 135f + 270f * min(maxSpeed, scaleMax) / scaleMax
            p.style = Paint.Style.STROKE
            p.color = t.text
            p.strokeWidth = 3f
            c.drawLine(px(112f, a), py(112f, a), px(128f, a), py(128f, a), p)
        }

        p.style = Paint.Style.FILL
        p.color = t.bg
        c.drawCircle(150f, 150f, 62f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = t.surface2
        c.drawCircle(150f, 150f, 62f, p)

        if (!digital) {
            val a = 135f + 270f * min(speed, scaleMax) / scaleMax
            path.reset()
            path.moveTo(px(82f, a), py(82f, a))
            path.lineTo(px(72f, a + 5f), py(72f, a + 5f))
            path.lineTo(px(72f, a - 5f), py(72f, a - 5f))
            path.close()
            p.style = Paint.Style.FILL
            p.color = t.text
            c.drawPath(path, p)
        }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = accent
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.textSize = if (digital) 44f else 34f
        c.drawText(Fmt.dec(speed, 1), 150f, if (digital) 166f else 160f, p)
        p.typeface = Typeface.DEFAULT
        p.color = t.muted
        p.textSize = 12f
        c.drawText("km/jam", 150f, if (digital) 188f else 182f, p)

        c.restore()
    }
}

/** Bar progres bersegmen (20 kotak) untuk target. */
class SegBar(ctx: Context, private val t: AppTheme) : View(ctx) {
    var fraction = 0f
    var color: Int = t.accent
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val n = 20
        val gap = 3f * d
        val w = (width - gap * (n - 1)) / n
        val lit = if (fraction > 0f) max(1, (fraction * n).roundToInt()) else 0
        for (i in 0 until n) {
            p.color = if (i < lit) color else t.surface2
            val x = i * (w + gap)
            c.drawRoundRect(RectF(x, 0f, x + w, height.toFloat()), 3f * d, 3f * d, p)
        }
    }
}

/** Peta mini offline: menggambar rute dari titik GPS (lat, lon). */
class RouteView(ctx: Context, private val t: AppTheme, private val thumb: Boolean = false) : View(ctx) {
    var points: List<DoubleArray> = emptyList()
    var hint = "Menunggu sinyal GPS…"
    var accent: Int = t.accent

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rr = (if (thumb) 8f else 12f) * d
        bg.color = if (thumb) t.bg else t.surface
        c.drawRoundRect(RectF(0f, 0f, w, h), rr, rr, bg)

        if (!thumb) {
            line.color = t.surface2
            line.strokeWidth = 6f * d
            c.drawLine(0f, h * 0.68f, w, h * 0.5f, line)
            c.drawLine(w * 0.27f, 0f, w * 0.33f, h, line)
            c.drawLine(w * 0.66f, 0f, w * 0.6f, h, line)
        }

        if (points.size < 2) {
            if (!thumb) {
                txt.color = t.muted
                txt.textSize = 12f * d
                c.drawText(hint, w / 2f, h / 2f + 4f * d, txt)
            }
            return
        }

        var minLa = 1e9; var maxLa = -1e9; var minLo = 1e9; var maxLo = -1e9
        for (q in points) {
            minLa = min(minLa, q[0]); maxLa = max(maxLa, q[0])
            minLo = min(minLo, q[1]); maxLo = max(maxLo, q[1])
        }
        val kx = cos(Math.toRadians((minLa + maxLa) / 2.0))
        val gw = max((maxLo - minLo) * kx, 2e-4)
        val gh = max(maxLa - minLa, 2e-4)
        val pad = (if (thumb) 6f else 14f) * d
        val sc = min((w - 2 * pad) / gw, (h - 2 * pad) / gh)
        val cLa = (minLa + maxLa) / 2.0
        val cLo = (minLo + maxLo) / 2.0

        path.reset()
        var ex = 0f; var ey = 0f; var sx = 0f; var sy = 0f
        points.forEachIndexed { i, q ->
            val x = (w / 2.0 + (q[1] - cLo) * kx * sc).toFloat()
            val y = (h / 2.0 - (q[0] - cLa) * sc).toFloat()
            if (i == 0) { path.moveTo(x, y); sx = x; sy = y } else path.lineTo(x, y)
            ex = x; ey = y
        }
        line.color = accent
        line.strokeWidth = (if (thumb) 2.5f else 4f) * d
        c.drawPath(path, line)

        if (!thumb) {
            dot.color = t.good
            c.drawCircle(sx, sy, 4f * d, dot)
            dot.color = t.text
            c.drawCircle(ex, ey, 8f * d, dot)
            dot.color = accent
            c.drawCircle(ex, ey, 5.5f * d, dot)
        }
    }
}

/** Cincin progres (dipakai untuk langkah pada mode Jalan). Tinggi mengikuti lebar. */
class RingView(ctx: Context, private val t: AppTheme, var accent: Int) : View(ctx) {
    var fraction = 0f
    var label = "LANGKAH"
    var value = "0"
    var sub = ""

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = w * 0.075f
        rect.set(s / 2f, s / 2f, w - s / 2f, w - s / 2f)

        p.style = Paint.Style.STROKE
        p.strokeWidth = s
        p.strokeCap = Paint.Cap.BUTT
        p.color = t.surface2
        c.drawArc(rect, 0f, 360f, false, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            p.color = accent
            p.strokeCap = Paint.Cap.ROUND
            c.drawArc(rect, -90f, 360f * f, false, p)
        }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER

        p.typeface = Typeface.DEFAULT_BOLD
        p.color = t.muted
        p.textSize = 12f * d
        p.letterSpacing = 0.16f
        c.drawText(label, w / 2f, h * 0.33f, p)
        p.letterSpacing = 0f

        p.color = accent
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.textSize = w * 0.22f
        val maxW = w * 0.62f
        val mw = p.measureText(value)
        if (mw > maxW) p.textSize = p.textSize * maxW / mw
        c.drawText(value, w / 2f, h * 0.57f, p)

        p.typeface = Typeface.DEFAULT
        p.color = t.muted
        p.textSize = 13f * d
        c.drawText(sub, w / 2f, h * 0.69f, p)
    }
}

/** Pager sederhana tanpa AndroidX: geser ke samping, berhenti pas di tiap halaman. */
class PagerScroll(ctx: Context, private val pageW: Int, private val onPage: (Int) -> Unit) :
    HorizontalScrollView(ctx) {

    var pages = 3
    var page = 0
        private set
    private var downX = 0

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    fun goTo(p: Int, animate: Boolean) {
        val q = p.coerceIn(0, pages - 1)
        page = q
        if (animate) smoothScrollTo(q * pageW, 0) else scrollTo(q * pageW, 0)
        onPage(q)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downX = scrollX
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downX = scrollX
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val d = scrollX - downX
            val th = pageW * 0.12f
            val target = when {
                d > th -> page + 1
                d < -th -> page - 1
                else -> page
            }
            post { goTo(target, true) }
        }
        return handled
    }
}

/** Grafik area (kecepatan atau pace terhadap waktu): kurva halus, isi gradasi, sumbu berjudul. */
class ChartView(ctx: Context, private val t: AppTheme, var accent: Int) : View(ctx) {
    var values: List<Float> = emptyList()
    var minutes = 0f
    var label: (Float) -> String = { v -> Fmt.dec(v, 0) }
    var yTitle = "km/jam"
    var hint = "Menunggu data, mulai bergerak"

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    private fun niceStep(total: Float): Float {
        val opts = floatArrayOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f)
        for (o in opts) if (total / o <= 6f) return o
        return 240f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val has = values.size >= 2
        txt.color = t.muted
        txt.textSize = 10f * d

        val x0 = 42f * d
        val x1 = w - 10f * d
        val y0 = 22f * d
        val y1 = h - 34f * d
        val top = if (has) (ceil((values.maxOrNull() ?: 0f) / 8f) * 8f).coerceAtLeast(8f) else 40f
        val total = if (has) max(minutes, 1f) else 10f

        // garis bantu mendatar + label sumbu Y
        grid.color = t.surface2
        grid.strokeWidth = 1f * d
        txt.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = y1 - (y1 - y0) * i / 4f
            if (i > 0) c.drawLine(x0, y, x1, y, grid)
            val s = label(top * i / 4f)
            if (s.isNotEmpty()) c.drawText(s, x0 - 6f * d, y + 3.5f * d, txt)
        }
        txt.textAlign = Paint.Align.LEFT
        c.drawText(yTitle, 4f * d, y0 - 9f * d, txt)

        // sumbu X: garis dasar, tanda, angka menit
        axis.color = t.muted
        axis.strokeWidth = 1f * d
        c.drawLine(x0, y1, x1, y1, axis)
        val step = niceStep(total)
        txt.textAlign = Paint.Align.CENTER
        var m = 0f
        while (m <= total + 0.01f) {
            val x = x0 + (x1 - x0) * m / total
            c.drawLine(x, y1, x, y1 + 4f * d, axis)
            c.drawText(Fmt.dec(m, 0), x, y1 + 16f * d, txt)
            m += step
        }
        txt.textAlign = Paint.Align.RIGHT
        c.drawText("Menit", x1, h - 3f * d, txt)

        if (!has) {
            txt.textAlign = Paint.Align.CENTER
            txt.textSize = 12f * d
            c.drawText(hint, (x0 + x1) / 2f, (y0 + y1) / 2f, txt)
            return
        }

        // kurva halus (kubik) + area isi gradasi
        val n = values.size
        val px = FloatArray(n)
        val py = FloatArray(n)
        for (k in 0 until n) {
            px[k] = x0 + (x1 - x0) * k / (n - 1f)
            py[k] = y1 - (y1 - y0) * (values[k] / top).coerceIn(0f, 1f)
        }
        path.reset()
        path.moveTo(px[0], py[0])
        for (k in 1 until n) {
            val mx = (px[k - 1] + px[k]) / 2f
            path.cubicTo(mx, py[k - 1], mx, py[k], px[k], py[k])
        }
        area.set(path)
        area.lineTo(px[n - 1], y1)
        area.lineTo(px[0], y1)
        area.close()
        val rgb = accent and 0x00FFFFFF
        fill.shader = LinearGradient(0f, y0, 0f, y1, rgb or 0x70000000, rgb or 0x08000000, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = accent
        line.strokeWidth = 2.5f * d
        c.drawPath(path, line)

        // titik posisi terakhir
        dot.color = accent
        c.drawCircle(px[n - 1], py[n - 1], 4f * d, dot)
    }
}


/**
 * Grafik mingguan: garis cyan halus per hari (Senin sampai hari ini), garis putus target,
 * dan penanda hijau pada hari yang mencapai target. Nilai berasal dari riwayat nyata.
 */
class WeekChartView(ctx: Context, private val t: AppTheme) : View(ctx) {
    var values: List<Float> = List(7) { 0f }
    var todayIndex = 0
    var target = 0f
    var steps = false
    var names: List<String> = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

    private val d = resources.displayMetrics.density
    private val cyan get() = if (t.dark) 0xFF00E5FF.toInt() else 0xFF00A8C6.toInt()
    private val tgtColor = 0xFFFFB020.toInt()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 4f * d), 0f)
    }
    private val tgtLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 5f * d), 0f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    private fun fmt(v: Float) = if (steps) Fmt.int(v.roundToInt()) else Fmt.dec(v, 1)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = 6f * d
        val x1 = w - 6f * d
        val y0 = 26f * d
        val y1 = h - 24f * d
        val shown = min(max(todayIndex, 0), 6) + 1
        val vals = List(shown) { values.getOrElse(it) { 0f } }
        val top = max(max(vals.maxOrNull() ?: 0f, target) * 1.2f, 1f)
        fun xAt(i: Int) = x0 + (x1 - x0) * (i + 0.5f) / 7f
        fun yAt(v: Float) = y1 - (y1 - y0) * (v / top).coerceIn(0f, 1f)

        // garis bantu
        grid.color = t.surface2
        grid.strokeWidth = 1f * d
        for (i in 1..3) {
            val y = y1 - (y1 - y0) * i / 3f
            c.drawLine(x0, y, x1, y, grid)
        }
        grid.pathEffect = null
        grid.color = t.muted
        c.drawLine(x0, y1, x1, y1, grid)
        grid.pathEffect = DashPathEffect(floatArrayOf(3f * d, 4f * d), 0f)

        // garis target jarak
        if (target > 0f) {
            val ty = yAt(target)
            tgtLine.color = tgtColor
            tgtLine.strokeWidth = 1.5f * d
            c.drawLine(x0, ty, x1, ty, tgtLine)
            txt.color = tgtColor
            txt.textSize = 10f * d
            txt.typeface = Typeface.DEFAULT_BOLD
            txt.textAlign = Paint.Align.RIGHT
            c.drawText("Target " + fmt(target) + (if (steps) " langkah" else " km"), x1, ty - 5f * d, txt)
        }

        // nama hari
        txt.textSize = 11f * d
        txt.textAlign = Paint.Align.CENTER
        for (i in 0..6) {
            val isToday = i == todayIndex
            txt.color = if (isToday) cyan else t.muted
            txt.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.drawText(names[i], xAt(i), h - 6f * d, txt)
        }

        // kurva cyan + area gradasi
        val n = shown
        val px = FloatArray(n) { xAt(it) }
        val py = FloatArray(n) { yAt(vals[it]) }
        if (n >= 2) {
            path.reset()
            path.moveTo(px[0], py[0])
            for (k in 1 until n) {
                val mx = (px[k - 1] + px[k]) / 2f
                path.cubicTo(mx, py[k - 1], mx, py[k], px[k], py[k])
            }
            area.set(path)
            area.lineTo(px[n - 1], y1)
            area.lineTo(px[0], y1)
            area.close()
            val rgb = cyan and 0x00FFFFFF
            fill.shader = LinearGradient(0f, y0, 0f, y1, rgb or 0x66000000, rgb or 0x05000000, Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            line.color = cyan
            line.strokeWidth = 3f * d
            c.drawPath(path, line)
        }

        // titik per hari + angka
        txt.textSize = 9.5f * d
        for (k in 0 until n) {
            val v = vals[k]
            val isToday = k == todayIndex
            val reached = target > 0f && v >= target
            if (v <= 0f && !isToday) continue
            dot.color = t.surface
            c.drawCircle(px[k], py[k], (if (isToday) 7f else 5.5f) * d, dot)
            dot.color = if (reached) t.good else cyan
            c.drawCircle(px[k], py[k], (if (isToday) 5f else 3.8f) * d, dot)
            if (v > 0f) {
                txt.color = if (reached) t.good else t.text
                txt.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                txt.textAlign = Paint.Align.CENTER
                c.drawText(fmt(v), px[k], py[k] - 10f * d, txt)
            }
        }
    }
}


/** Ikon navigasi bawah yang digambar sendiri (garis halus, rapi di semua ukuran layar). */
class NavIconView(ctx: Context, private val kind: Int, private val tint: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 24f
        path.reset()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * s
        fun mv(x: Float, y: Float) = path.moveTo(x * s, y * s)
        fun ln(x: Float, y: Float) = path.lineTo(x * s, y * s)
        when (kind) {
            0 -> { // beranda
                mv(3f, 11f); ln(12f, 3.5f); ln(21f, 11f)
                mv(5.5f, 9.5f); ln(5.5f, 20f); ln(18.5f, 20f); ln(18.5f, 9.5f)
                mv(10f, 20f); ln(10f, 14.5f); ln(14f, 14.5f); ln(14f, 20f)
                c.drawPath(path, p)
            }
            1 -> { // rute
                c.drawCircle(6f * s, 18f * s, 2.6f * s, p)
                c.drawCircle(18f * s, 6f * s, 2.6f * s, p)
                mv(6f, 15.4f)
                path.cubicTo(6f * s, 8f * s, 18f * s, 16f * s, 18f * s, 8.6f * s)
                c.drawPath(path, p)
            }
            2 -> { // mulai
                p.style = Paint.Style.FILL_AND_STROKE
                p.strokeWidth = 1.6f * s
                mv(9f, 6.5f); ln(18f, 12f); ln(9f, 17.5f); path.close()
                c.drawPath(path, p)
            }
            3 -> { // riwayat
                c.drawCircle(12f * s, 12f * s, 8.5f * s, p)
                mv(12f, 7f); ln(12f, 12f); ln(15.5f, 14f)
                c.drawPath(path, p)
            }
            else -> { // atur
                val rows = listOf(6f to 9f, 12f to 15f, 18f to 8f)
                for ((y, kx) in rows) {
                    c.drawLine(4f * s, y * s, (kx - 3.2f) * s, y * s, p)
                    c.drawLine((kx + 3.2f) * s, y * s, 20f * s, y * s, p)
                    c.drawCircle(kx * s, y * s, 2.2f * s, p)
                }
            }
        }
    }
}

/** Bilah navigasi bawah bersama: Beranda, Rute, Mulai (tombol bulat), Riwayat, Atur. */
fun buildNavBar(ctx: Context, t: AppTheme, sel: Int, onTap: (Int) -> Unit): LinearLayout {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(t.surface)
    }
    root.addView(View(ctx).apply { setBackgroundColor(t.surface2) }, LinearLayout.LayoutParams(-1, dp(1)))
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    val labels = listOf("Beranda", "Rute", "Mulai", "Riwayat", "Atur")
    labels.forEachIndexed { i, label ->
        val on = i == sel
        val tint = if (on) t.accent else t.muted
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { onTap(i) }
        }
        val slot = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        cell.addView(slot, LinearLayout.LayoutParams(-1, dp(40)))
        if (i == 2) {
            val circle = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.accent) }
            }
            circle.addView(NavIconView(ctx, 2, onColor(t.accent)), LinearLayout.LayoutParams(dp(22), dp(22)))
            slot.addView(circle, LinearLayout.LayoutParams(dp(40), dp(40)))
        } else {
            val pill = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                if (on) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor((t.accent and 0x00FFFFFF) or 0x33000000)
                    }
                }
            }
            pill.addView(NavIconView(ctx, i, tint), LinearLayout.LayoutParams(dp(24), dp(24)))
            slot.addView(pill, LinearLayout.LayoutParams(dp(56), dp(30)))
        }
        cell.addView(TextView(ctx).apply {
            text = label
            textSize = 11f
            setTextColor(tint)
            typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
    }
    root.addView(row, LinearLayout.LayoutParams(-1, -2))
    return root
}
