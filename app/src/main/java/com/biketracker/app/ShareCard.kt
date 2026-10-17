package com.biketracker.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Ringkasan satu aktivitas yang akan dibagikan. */
class ShareData(
    val mode: String,
    val icon: String,
    val date: String,
    val km: Float,
    val elapsedMs: Long,
    val avgKmh: Float,
    val maxKmh: Float,
    val kcal: Int,
    val climbM: Int,
    val steps: Int,
    val paceSec: Int?,
    val route: List<DoubleArray>
)

/** Membuat kartu gambar ringkasan aktivitas dan membagikannya (gambar + teks). */
object ShareCard {

    fun text(d: ShareData): String {
        val sb = StringBuilder()
        sb.append(d.icon).append(' ').append(d.mode).append(" • ").append(d.date).append('\n')
        if (d.mode == "Jalan") sb.append(Fmt.int(d.steps)).append(" langkah • ")
        sb.append(Fmt.dec(d.km, 2)).append(" km • ").append(Fmt.time(d.elapsedMs)).append('\n')
        if (d.mode == "Lari") sb.append("Pace ").append(Fmt.pace(d.paceSec)).append(" /km • ")
        else sb.append("Rata-rata ").append(Fmt.dec(d.avgKmh, 1)).append(" km/jam • ")
        sb.append("Maks ").append(Fmt.dec(d.maxKmh, 1)).append(" km/jam • ")
        sb.append(d.kcal).append(" kkal")
        val s = d.route.firstOrNull()
        if (s != null) {
            sb.append("\nTitik start: https://maps.google.com/?q=")
                .append(String.format(Locale.US, "%.5f,%.5f", s[0], s[1]))
        }
        sb.append("\n#Delaas")
        return sb.toString()
    }

    fun render(t: AppTheme, accent: Int, d: ShareData): Bitmap {
        val w = 1080
        val h = 1440
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(t.bg)

        p.typeface = Typeface.DEFAULT_BOLD
        p.color = accent
        p.textSize = 34f
        p.letterSpacing = 0.3f
        c.drawText("DELAAS", 70f, 100f, p)
        p.letterSpacing = 0f

        p.color = t.text
        p.textSize = 64f
        c.drawText(d.icon + "  " + d.mode, 70f, 190f, p)
        p.typeface = Typeface.DEFAULT
        p.color = t.muted
        p.textSize = 34f
        c.drawText(d.date, 70f, 240f, p)

        // Kotak rute
        val box = RectF(70f, 290f, w - 70f, 830f)
        p.color = t.surface
        c.drawRoundRect(box, 40f, 40f, p)
        drawRoute(c, box, d.route, accent, t)

        // Angka utama
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.color = accent
        p.textSize = 150f
        val big = if (d.mode == "Jalan") Fmt.int(d.steps) else Fmt.dec(d.km, 2)
        c.drawText(big, 70f, 1000f, p)
        val bw = p.measureText(big)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = t.muted
        p.textSize = 52f
        c.drawText(if (d.mode == "Jalan") "langkah" else "km", 70f + bw + 20f, 1000f, p)

        // Statistik 2 x 2
        val second = if (d.mode == "Lari") "Pace rata-rata" to Fmt.pace(d.paceSec) + " /km"
        else "Rata-rata" to Fmt.dec(d.avgKmh, 1) + " km/jam"
        val cells = listOf(
            "Durasi" to Fmt.time(d.elapsedMs),
            second,
            "Maksimum" to Fmt.dec(d.maxKmh, 1) + " km/jam",
            "Kalori" to d.kcal.toString() + " kkal"
        )
        val cw = (w - 140f - 30f) / 2f
        for (i in cells.indices) {
            val x = 70f + (i % 2) * (cw + 30f)
            val y = 1050f + (i / 2) * 150f
            val r = RectF(x, y, x + cw, y + 130f)
            p.color = t.surface
            c.drawRoundRect(r, 28f, 28f, p)
            p.typeface = Typeface.DEFAULT
            p.color = t.muted
            p.textSize = 30f
            c.drawText(cells[i].first, x + 28f, y + 48f, p)
            p.typeface = Typeface.DEFAULT_BOLD
            p.color = t.text
            p.textSize = 46f
            c.drawText(cells[i].second, x + 28f, y + 105f, p)
        }

        p.typeface = Typeface.DEFAULT
        p.color = t.muted
        p.textSize = 28f
        p.textAlign = Paint.Align.CENTER
        c.drawText("Dibagikan dari Delaas", w / 2f, h - 36f, p)
        return bmp
    }

    private fun drawRoute(c: Canvas, box: RectF, route: List<DoubleArray>, accent: Int, t: AppTheme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (route.size < 2) {
            p.color = t.muted
            p.textSize = 34f
            p.textAlign = Paint.Align.CENTER
            c.drawText("Rute tidak terekam", box.centerX(), box.centerY(), p)
            return
        }
        var minLa = Double.MAX_VALUE; var maxLa = -Double.MAX_VALUE
        var minLo = Double.MAX_VALUE; var maxLo = -Double.MAX_VALUE
        for (q in route) {
            minLa = min(minLa, q[0]); maxLa = max(maxLa, q[0])
            minLo = min(minLo, q[1]); maxLo = max(maxLo, q[1])
        }
        val kx = cos(Math.toRadians((minLa + maxLa) / 2.0))
        val spanX = max((maxLo - minLo) * kx, 1e-6)
        val spanY = max(maxLa - minLa, 1e-6)
        val pad = 60f
        val aw = box.width() - pad * 2
        val ah = box.height() - pad * 2
        val sc = min(aw / spanX, ah / spanY).toFloat()
        val ox = box.left + pad + (aw - (spanX * sc).toFloat()) / 2f
        val oy = box.top + pad + (ah - (spanY * sc).toFloat()) / 2f
        fun px(q: DoubleArray) = ox + (((q[1] - minLo) * kx) * sc).toFloat()
        fun py(q: DoubleArray) = oy + (((maxLa - q[0])) * sc).toFloat()

        val path = Path()
        path.moveTo(px(route[0]), py(route[0]))
        for (i in 1 until route.size) path.lineTo(px(route[i]), py(route[i]))
        p.style = Paint.Style.STROKE
        p.strokeWidth = 12f
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        p.color = accent
        c.drawPath(path, p)

        p.style = Paint.Style.FILL
        val a = route.first()
        val b = route.last()
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(px(a), py(a), 22f, p)
        c.drawCircle(px(b), py(b), 22f, p)
        p.color = 0xFF27D282.toInt()
        c.drawCircle(px(a), py(a), 15f, p)
        p.color = 0xFFFF3B3B.toInt()
        c.drawCircle(px(b), py(b), 15f, p)
    }

    /** Bagikan kartu gambar + teks; bila gambar tidak bisa disimpan, bagikan teks saja. */
    fun share(a: Activity, t: AppTheme, accent: Int, d: ShareData) {
        val msg = text(d)
        var uri: android.net.Uri? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val bmp = render(t, accent, d)
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Delaas_" + System.currentTimeMillis() + ".png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Delaas")
                }
                val u = a.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (u != null) {
                    a.contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    uri = u
                }
            } catch (e: Exception) {
                uri = null
            }
        }
        val i = Intent(Intent.ACTION_SEND)
        i.putExtra(Intent.EXTRA_TEXT, msg)
        if (uri != null) {
            i.type = "image/png"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            i.type = "text/plain"
        }
        try {
            a.startActivity(Intent.createChooser(i, "Bagikan aktivitas"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(a, "Tidak ada aplikasi untuk berbagi.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
