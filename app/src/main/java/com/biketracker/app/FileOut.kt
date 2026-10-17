package com.biketracker.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast

/** Menyimpan file teks ke folder Download dan membagikannya (GPX dan cadangan riwayat). */
object FileOut {

    /** Simpan ke Download/Delaas (Android 10+). Mengembalikan Uri, atau null kalau tidak bisa. */
    fun save(a: Activity, name: String, mime: String, text: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Delaas")
            }
            val u = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (u != null) {
                a.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            u
        } catch (e: Exception) {
            null
        }
    }

    /** Simpan lalu buka menu bagikan. Di Android lama, isi file dibagikan sebagai teks. */
    fun saveAndShare(a: Activity, name: String, mime: String, text: String, chooser: String) {
        val uri = save(a, name, mime, text)
        val i = Intent(Intent.ACTION_SEND)
        if (uri != null) {
            i.type = mime
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Toast.makeText(a, "Disimpan di Download/Delaas/$name", Toast.LENGTH_LONG).show()
        } else {
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            a.startActivity(Intent.createChooser(i, chooser))
        } catch (e: Exception) {
            Toast.makeText(a, "Tidak ada aplikasi untuk berbagi.", Toast.LENGTH_LONG).show()
        }
    }
}
