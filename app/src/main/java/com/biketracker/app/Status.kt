package com.biketracker.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/** Status singkat (teks + emoji, foto opsional). Tersimpan di HP dan hilang otomatis setelah 24 jam. */
class StatusData(val text: String, val createdAt: Long, val hasPhoto: Boolean) {
    /** Sisa waktu sebelum status kedaluwarsa, dalam milidetik (minimal 0). */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        (createdAt + StatusStore.LIFETIME_MS - now).coerceAtLeast(0L)

    /** Contoh: "Berakhir dalam 5 jam" atau "Berakhir dalam 20 menit". */
    fun remainingLabel(now: Long = System.currentTimeMillis()): String {
        val min = remainingMs(now) / 60_000L
        return if (min >= 60) "Berakhir dalam ${min / 60} jam" else "Berakhir dalam ${maxOf(min, 1L)} menit"
    }
}

/** Penyimpanan status. Semuanya lokal: tanpa akun dan tanpa server. */
object StatusStore {
    const val LIFETIME_MS = 24L * 60L * 60L * 1000L
    const val MAX_CHARS = 120

    private fun p(c: Context) = c.getSharedPreferences("status", Context.MODE_PRIVATE)

    private fun photoFile(c: Context) = File(c.filesDir, "status.jpg")

    /** Status yang masih berlaku, atau null. Status yang sudah lewat 24 jam dihapus. */
    fun current(c: Context, now: Long = System.currentTimeMillis()): StatusData? {
        val s = p(c)
        val text = s.getString("text", "") ?: ""
        val at = s.getLong("at", 0L)
        val photo = photoFile(c).exists()
        if (at == 0L || (text.isEmpty() && !photo)) return null
        if (now - at >= LIFETIME_MS) {
            clear(c)
            return null
        }
        return StatusData(text, at, photo)
    }

    /** Simpan teks status baru. Foto yang sudah dipilih tetap dipakai. Waktu 24 jam dihitung dari sekarang. */
    fun save(c: Context, text: String, now: Long = System.currentTimeMillis()) {
        p(c).edit()
            .putString("text", clean(text))
            .putLong("at", now)
            .apply()
    }

    fun clean(raw: String): String = raw.replace("\u0001", "").replace("\u0002", "").trim().take(MAX_CHARS)

    fun clear(c: Context) {
        p(c).edit().clear().apply()
        removePhoto(c)
    }

    fun removePhoto(c: Context) {
        try { photoFile(c).delete() } catch (e: Exception) { }
    }

    fun loadPhoto(c: Context): Bitmap? = try {
        if (photoFile(c).exists()) BitmapFactory.decodeFile(photoFile(c).absolutePath) else null
    } catch (e: Exception) {
        null
    }

    private fun rotationOf(c: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            c.contentResolver.openInputStream(uri)?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** Baca foto pilihan pengguna, kecilkan (sisi terpanjang 1080 px), lalu simpan. True bila berhasil. */
    fun savePhoto(c: Context, uri: Uri): Boolean {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2160 || bounds.outHeight / sample > 2160) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val rot = rotationOf(c, uri)
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            val longest = maxOf(src.width, src.height).toFloat()
            val scale = if (longest > 1080f) 1080f / longest else 1f
            m.postScale(scale, scale)
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            FileOutputStream(photoFile(c)).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            // Status lama tanpa teks/waktu: pastikan ada waktu supaya foto tidak "menggantung".
            if (p(c).getLong("at", 0L) == 0L) p(c).edit().putLong("at", System.currentTimeMillis()).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** Kalimat status dari satu aktivitas, mis. "🚴 Selesai Sepeda 12,34 km • 45:10". */
    fun fromActivity(mode: String, icon: String, km: Float, elapsedMs: Long): String =
        icon + " Selesai " + mode + " " + Fmt.dec(km, 2) + " km • " + Fmt.time(elapsedMs)
}

/** Kartu gambar status untuk dibagikan ke WhatsApp dan aplikasi lain. */
object StatusCard {
    private const val W = 1080

    fun text(name: String, s: StatusData): String {
        val sb = StringBuilder()
        sb.append("Status ").append(name).append(" di Delaas")
        if (s.text.isNotEmpty()) sb.append('\n').append(s.text)
        sb.append("\n\nTempel kode ini di Delaas (Grup > Status teman) untuk melihatnya:\n")
        sb.append(StatusCode.encode(name, s))
        sb.append("\n#Delaas")
        return sb.toString()
    }

    fun render(c: Context, t: AppTheme, accent: Int, name: String, s: StatusData): Bitmap {
        val photo = if (s.hasPhoto) StatusStore.loadPhoto(c) else null
        val pad = 64f
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.text; textSize = 60f; typeface = Typeface.DEFAULT_BOLD
        }
        val layout = if (s.text.isNotEmpty())
            StaticLayout.Builder.obtain(s.text, 0, s.text.length, textPaint, (W - 2 * pad).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
        else null
        val photoH = if (photo != null) {
            val ratio = photo.height.toFloat() / photo.width.toFloat()
            (W * ratio).coerceIn(480f, 1350f)
        } else 0f
        val head = 190f
        val textH = (layout?.height ?: 0).toFloat()
        val foot = 150f
        val h = (head + photoH + (if (layout != null) textH + pad * 1.2f else 0f) + foot).toInt().coerceAtLeast(720)

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = t.bg
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), p)

        // Kepala: lingkaran inisial + nama
        p.color = accent
        cv.drawCircle(pad + 48f, 95f, 48f, p)
        p.color = 0xFF04202B.toInt()
        p.textSize = 52f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        cv.drawText(name.take(1).uppercase(), pad + 48f, 95f + 18f, p)
        p.textAlign = Paint.Align.LEFT
        p.color = t.text; p.textSize = 48f
        cv.drawText(name.take(24), pad + 120f, 92f, p)
        p.color = t.muted; p.textSize = 32f; p.typeface = Typeface.DEFAULT
        cv.drawText("Status • 24 jam", pad + 120f, 134f, p)

        var y = head
        if (photo != null) {
            val dst = RectF(0f, y, W.toFloat(), y + photoH)
            val srcRatio = photo.width.toFloat() / photo.height.toFloat()
            val dstRatio = W / photoH
            val src = if (srcRatio > dstRatio) {
                val w2 = photo.height * dstRatio
                android.graphics.Rect(((photo.width - w2) / 2).toInt(), 0, ((photo.width + w2) / 2).toInt(), photo.height)
            } else {
                val h2 = photo.width / dstRatio
                android.graphics.Rect(0, ((photo.height - h2) / 2).toInt(), photo.width, ((photo.height + h2) / 2).toInt())
            }
            cv.drawBitmap(photo, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            y += photoH
        }
        if (layout != null) {
            y += pad * 0.6f
            cv.save()
            cv.translate(pad, y)
            layout.draw(cv)
            cv.restore()
        }
        p.color = accent; p.textSize = 36f; p.typeface = Typeface.DEFAULT_BOLD
        cv.drawText("Delaas", pad, h - 70f, p)
        return bmp
    }

    /** Bagikan kartu gambar + teks; di Android lama atau bila gagal, bagikan teks saja. */
    fun share(a: Activity, t: AppTheme, accent: Int, name: String, s: StatusData) {
        val msg = text(name, s)
        var uri: Uri? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val bmp = render(a, t, accent, name, s)
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Delaas_status_" + System.currentTimeMillis() + ".png")
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
            a.startActivity(Intent.createChooser(i, "Bagikan status"))
        } catch (e: Exception) {
            Toast.makeText(a, "Tidak ada aplikasi untuk berbagi.", Toast.LENGTH_LONG).show()
        }
    }
}

/** Base64 URL-safe sederhana (tanpa padding), murni Kotlin supaya bisa diuji tanpa Android. */
internal object B64 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun enc(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val b0 = b[i].toInt() and 255
            val b1 = if (i + 1 < b.size) b[i + 1].toInt() and 255 else 0
            val b2 = if (i + 2 < b.size) b[i + 2].toInt() and 255 else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(A[(n shr 18) and 63]).append(A[(n shr 12) and 63])
            if (i + 1 < b.size) sb.append(A[(n shr 6) and 63])
            if (i + 2 < b.size) sb.append(A[n and 63])
            i += 3
        }
        return sb.toString()
    }

    fun dec(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (ch in s) {
            val v = A.indexOf(ch)
            if (v < 0) return null
            acc = ((acc shl 6) or v) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 255)
            }
        }
        return out.toByteArray()
    }
}

/** Status teman yang diterima lewat kode. */
class FriendStatus(val name: String, val createdAt: Long, val text: String) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        (createdAt + StatusStore.LIFETIME_MS - now).coerceAtLeast(0L)

    fun remainingLabel(now: Long = System.currentTimeMillis()): String =
        StatusData(text, createdAt, false).remainingLabel(now)
}

/**
 * Kode status: cara membagikan status ke teman tanpa server. Isinya nama, waktu, dan teks (tanpa foto).
 * Teman menempelkan kode itu di Delaas mereka.
 */
object StatusCode {
    private const val PREFIX = "DLS1."
    private val FIND = Regex("DLS1\\.[A-Za-z0-9_-]+")

    fun encode(name: String, s: StatusData): String {
        val raw = StatusStore.clean(name).take(30) + "\u0001" + s.createdAt + "\u0001" + StatusStore.clean(s.text)
        return PREFIX + B64.enc(raw.toByteArray(Charsets.UTF_8))
    }

    /** Cari dan baca kode di teks yang ditempel (boleh seluruh pesan). Null bila tidak sah atau sudah berakhir. */
    fun parse(pasted: String, now: Long = System.currentTimeMillis()): FriendStatus? {
        val m = FIND.find(pasted) ?: return null
        val bytes = B64.dec(m.value.removePrefix(PREFIX)) ?: return null
        val parts = String(bytes, Charsets.UTF_8).split("\u0001", limit = 3)
        if (parts.size != 3) return null
        val name = StatusStore.clean(parts[0]).take(30)
        val at = parts[1].toLongOrNull() ?: return null
        val text = StatusStore.clean(parts[2])
        if (name.isEmpty() || text.isEmpty()) return null
        if (now - at >= StatusStore.LIFETIME_MS) return null
        if (at > now + 3_600_000L) return null
        return FriendStatus(name, at, text)
    }
}

/** Daftar status teman (lokal). Status yang lewat 24 jam dibuang otomatis. */
object FriendStatusStore {
    private fun p(c: Context) = c.getSharedPreferences("friend_status", Context.MODE_PRIVATE)

    private fun read(c: Context): MutableList<FriendStatus> =
        (p(c).getString("list", "") ?: "").split("\u0002").mapNotNull { e ->
            val f = e.split("\u0001", limit = 3)
            val at = f.getOrNull(1)?.toLongOrNull()
            if (f.size == 3 && at != null) FriendStatus(f[0], at, f[2]) else null
        }.toMutableList()

    private fun write(c: Context, list: List<FriendStatus>) {
        p(c).edit().putString("list",
            list.joinToString("\u0002") { it.name + "\u0001" + it.createdAt + "\u0001" + it.text }).apply()
    }

    /** Status teman yang masih berlaku, terbaru di depan. */
    fun list(c: Context, now: Long = System.currentTimeMillis()): List<FriendStatus> {
        val all = read(c)
        val live = all.filter { now - it.createdAt < StatusStore.LIFETIME_MS }
        if (live.size != all.size) write(c, live)
        return live.sortedByDescending { it.createdAt }
    }

    /** Tambah atau perbarui status teman (nama sama = diganti bila lebih baru). True bila disimpan. */
    fun put(c: Context, fs: FriendStatus): Boolean {
        val all = read(c)
        val old = all.firstOrNull { it.name.equals(fs.name, ignoreCase = true) }
        if (old != null && old.createdAt >= fs.createdAt) return false
        if (old != null) all.remove(old)
        all.add(fs)
        write(c, all)
        return true
    }

    fun remove(c: Context, name: String) {
        write(c, read(c).filter { !it.name.equals(name, ignoreCase = true) })
    }
}
