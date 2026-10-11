package com.biketracker.app

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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

/** Speedometer analog: busur segmen hijau -> merah, skala, penunjuk, angka digital di tengah. */
class GaugeView(ctx: Context, private val t: AppTheme) : View(ctx) {
    var speed = 0f
    var maxSpeed = 0f
    var scaleMax = 60f
    var digital = false
    var accent: Int = t.accent

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
        setMeasuredDimension(w, (w * 0.8f).toInt())
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
        val s = width / 300f
        c.save()
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

/** Grafik garis sederhana (kecepatan atau pace terhadap waktu). Nilai lebih tinggi = garis lebih atas. */
class ChartView(ctx: Context, private val t: AppTheme, var accent: Int) : View(ctx) {
    var values: List<Float> = emptyList()
    var minutes = 0f
    var label: (Float) -> String = { v -> Fmt.dec(v, 0) }
    var hint = "Grafik muncul setelah mulai bergerak"

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        txt.color = t.muted
        txt.textSize = 10f * d

        if (values.size < 2) {
            txt.textAlign = Paint.Align.CENTER
            txt.textSize = 12f * d
            c.drawText(hint, w / 2f, h / 2f, txt)
            return
        }

        val x0 = 40f * d
        val x1 = w - 8f * d
        val y0 = 8f * d
        val y1 = h - 24f * d
        val top = (ceil((values.maxOrNull() ?: 0f) / 8f) * 8f).coerceAtLeast(8f)

        grid.color = t.surface2
        grid.strokeWidth = 1f * d
        txt.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = y1 - (y1 - y0) * i / 4f
            c.drawLine(x0, y, x1, y, grid)
            val s = label(top * i / 4f)
            if (s.isNotEmpty()) c.drawText(s, x0 - 6f * d, y + 3.5f * d, txt)
        }

        path.reset()
        area.reset()
        val n = values.size
        for (k in 0 until n) {
            val x = x0 + (x1 - x0) * k / (n - 1f)
            val y = y1 - (y1 - y0) * (values[k] / top).coerceIn(0f, 1f)
            if (k == 0) {
                path.moveTo(x, y)
                area.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(x1, y1)
        area.lineTo(x0, y1)
        area.close()
        fill.color = (accent and 0x00FFFFFF) or 0x33000000
        c.drawPath(area, fill)
        line.color = accent
        line.strokeWidth = 2.5f * d
        c.drawPath(path, line)

        txt.textAlign = Paint.Align.LEFT
        c.drawText("0", x0, h - 6f * d, txt)
        txt.textAlign = Paint.Align.CENTER
        c.drawText(Fmt.dec(minutes / 2f, 0), (x0 + x1) / 2f, h - 6f * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        c.drawText(Fmt.dec(minutes, 0) + " mnt", x1, h - 6f * d, txt)
    }
}
