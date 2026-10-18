package com.biketracker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.random.Random

/** Nama dan foto profil. Semuanya tersimpan di HP (tanpa akun dan tanpa server). */
object ProfileStore {
    private fun p(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun name(c: Context): String = (p(c).getString("name", "") ?: "").trim()

    fun setName(c: Context, v: String) {
        p(c).edit().putString("name", v.trim().take(30)).apply()
    }

    /** Kode Delaas pribadi (mis. DLS-7K3Q9X). Dibuat sekali, lalu tetap sama di HP ini. */
    fun myCode(c: Context): String {
        val saved = p(c).getString("my_code", "") ?: ""
        if (PersonalCode.clean(saved) != null) return saved
        val code = PersonalCode.generate()
        p(c).edit().putString("my_code", code).apply()
        return code
    }

    /** Nama untuk ditampilkan; kosong diganti sebutan umum. */
    fun displayName(c: Context): String = name(c).ifEmpty { "Pengendara" }

    private fun photoFile(c: Context) = File(c.filesDir, "profile.jpg")

    fun hasPhoto(c: Context) = photoFile(c).exists()

    fun loadPhoto(c: Context): Bitmap? = try {
        if (hasPhoto(c)) BitmapFactory.decodeFile(photoFile(c).absolutePath) else null
    } catch (e: Exception) {
        null
    }

    fun removePhoto(c: Context) {
        try { photoFile(c).delete() } catch (e: Exception) { }
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

    /** Baca gambar pilihan pengguna, potong jadi persegi, kecilkan, lalu simpan. Mengembalikan true bila berhasil. */
    fun savePhoto(c: Context, uri: Uri): Boolean {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val side = minOf(src.width, src.height)
            val m = Matrix()
            val rot = rotationOf(c, uri)
            if (rot != 0) m.postRotate(rot.toFloat())
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
            val out = Bitmap.createScaledBitmap(square, 384, 384, true)
            FileOutputStream(photoFile(c)).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}

/**
 * Grup bersepeda bareng teman. Versi ini bersifat LOKAL: grup, kode undangan, daftar teman, dan titik kumpul
 * tersimpan di HP ini saja. Posisi teman secara langsung butuh server, jadi belum ada.
 */
object GroupStore {
    private fun p(c: Context) = c.getSharedPreferences("group", Context.MODE_PRIVATE)

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun code(c: Context): String = p(c).getString("code", "") ?: ""

    fun name(c: Context): String = p(c).getString("name", "") ?: ""

    fun inGroup(c: Context) = code(c).isNotEmpty()

    fun newCode(): String = "RIDE" + (1..4).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    /** Kode undangan yang sah: 4 sampai 8 huruf atau angka. */
    fun cleanCode(raw: String): String? {
        val s = raw.trim().uppercase(Locale.US)
        return if (s.length in 4..8 && s.all { it in 'A'..'Z' || it in '0'..'9' }) s else null
    }

    fun create(c: Context, name: String): String {
        val code = newCode()
        p(c).edit().clear()
            .putString("code", code)
            .putString("name", name.trim().ifEmpty { "Grup perjalanan" }.take(30))
            .putBoolean("owner", true)
            .apply()
        return code
    }

    fun join(c: Context, code: String) {
        p(c).edit().clear()
            .putString("code", code)
            .putString("name", "Grup $code")
            .putBoolean("owner", false)
            .apply()
    }

    fun leave(c: Context) {
        p(c).edit().clear().apply()
    }

    fun members(c: Context): List<String> =
        (p(c).getString("members", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun addMember(c: Context, n: String) {
        val name = n.trim().take(30)
        if (name.isEmpty()) return
        val list = members(c).toMutableList()
        if (list.none { it.equals(name, ignoreCase = true) }) list.add(name)
        p(c).edit().putString("members", list.joinToString("\n")).apply()
    }

    fun removeMember(c: Context, n: String) {
        val list = members(c).filter { it != n }
        p(c).edit().putString("members", list.joinToString("\n")).apply()
    }

    /** Titik kumpul [lat, lon], atau null bila belum ditandai. */
    fun meet(c: Context): DoubleArray? {
        val s = p(c)
        if (!s.contains("meet_lat")) return null
        return doubleArrayOf(
            java.lang.Double.longBitsToDouble(s.getLong("meet_lat", 0L)),
            java.lang.Double.longBitsToDouble(s.getLong("meet_lon", 0L))
        )
    }

    fun setMeet(c: Context, lat: Double, lon: Double) {
        p(c).edit()
            .putLong("meet_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("meet_lon", java.lang.Double.doubleToRawLongBits(lon))
            .apply()
    }

    fun clearMeet(c: Context) {
        p(c).edit().remove("meet_lat").remove("meet_lon").apply()
    }
}

/** Kode pribadi pengguna Delaas: "DLS-" diikuti 6 huruf/angka (tanpa huruf yang mirip: I, O, 0, 1). */
object PersonalCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(): String = "DLS-" + (1..6).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    /** Rapikan kode yang diketik atau ditempel (boleh tanpa tanda minus). Null bila bentuknya tidak sah. */
    fun clean(raw: String): String? {
        val s = raw.trim().uppercase(Locale.US).removePrefix("DLS").removePrefix("-").trim()
        return if (s.length == 6 && s.all { it in ALPHABET }) "DLS-$s" else null
    }
}
