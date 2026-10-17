package com.biketracker.app

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.roundToInt

/** Satu baris riwayat. Format: tanggal|mode|km|durasiMs|rata2|maks|target|rute|kkal|tanjakan|bergerakMs|detikPerContoh|contoh
 *  target: km (Sepeda/Lari) atau langkah (Jalan; data lama Jalan memakai km). */
internal class Rec(val f: List<String>) {
    val date get() = f[0]
    val mode get() = f[1]
    val km get() = f[2].toFloatOrNull() ?: 0f
    val elapsed get() = f[3].toLongOrNull() ?: 0L
    val avg get() = f[4]
    val target get() = f.getOrNull(6)?.toFloatOrNull() ?: 0f
    val maxSpeed get() = f.getOrNull(5) ?: "0.0"
    val avgF get() = avg.toFloatOrNull() ?: 0f
    val maxF get() = maxSpeed.toFloatOrNull() ?: 0f
    val steps: Int get() = (km * 1000f * TrackerState.STEPS_PER_METER).roundToInt()
    val paceSec: Int? get() = if (km > 0.05f && elapsed > 0L) ((elapsed / 1000f) / km).roundToInt() else null
    private val stepTarget get() = mode == "Jalan" && target >= 100f
    val reached get() = target > 0f && (if (stepTarget) steps >= target else km >= target)
    val targetText get() = if (stepTarget) Fmt.int(target.toInt()) + " langkah" else target.toInt().toString() + " km"
    val kcal get() = f.getOrNull(8) ?: "0"
    val climb get() = f.getOrNull(9) ?: "0"
    val moving get() = f.getOrNull(10)?.toLongOrNull() ?: 0L
    val sampleSec get() = f.getOrNull(11)?.toIntOrNull() ?: 0
    val samples: List<Float> by lazy {
        (f.getOrNull(12) ?: "").split(",").mapNotNull { it.toFloatOrNull() }
    }
    val route: List<DoubleArray> by lazy {
        (f.getOrNull(7) ?: "").split(";").mapNotNull { s ->
            val p = s.split(",")
            val la = p.getOrNull(0)?.toDoubleOrNull()
            val lo = p.getOrNull(1)?.toDoubleOrNull()
            if (la != null && lo != null) doubleArrayOf(la, lo) else null
        }
    }
}

/**
 * Database riwayat (SQLite bawaan Android, tanpa pustaka tambahan).
 * Isi tiap baris tetap memakai format teks lama (lihat Rec) supaya cadangan/pulihkan riwayat dan semua layar tetap cocok.
 */
internal class RecordDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "ridetrack.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, mode TEXT NOT NULL, line TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_records_date ON records(date)")
        db.execSQL("CREATE INDEX idx_records_mode ON records(mode)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * Akses riwayat. Menyimpan satu aktivitas = satu INSERT (bukan menulis ulang seluruh riwayat), dan hapus = satu DELETE.
 * Riwayat lama di SharedPreferences dipindah otomatis sekali; teks lamanya TIDAK dihapus sebagai cadangan
 * (baru dihapus saat "Hapus semua riwayat"). Kalau database gagal dibuka, penyimpanan lama dipakai sebagai cadangan
 * sehingga riwayat tidak pernah tampak hilang.
 */
internal class RecordStore(private val ctx: Context, private val prefs: SharedPreferences) {
    private val helper by lazy { RecordDb(ctx) }
    private var cache: List<String>? = null
    private var ready = false

    init {
        migrate()
    }

    private fun legacy(): MutableList<String> =
        (prefs.getString("records", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()

    private fun rowCount(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM records", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    private fun insert(db: SQLiteDatabase, line: String): Boolean {
        val f = line.split("|")
        val cv = ContentValues()
        cv.put("date", f.getOrElse(0) { "" })
        cv.put("mode", f.getOrElse(1) { "" })
        cv.put("line", line)
        return db.insert("records", null, cv) != -1L
    }

    /** Pindahkan riwayat lama sekali saja. Hanya ditandai selesai kalau jumlah baris di database sudah cocok. */
    private fun migrate() {
        try {
            val old = legacy()
            val db = helper.writableDatabase
            if (!prefs.getBoolean("records_migrated", false)) {
                if (old.isNotEmpty() && rowCount(db) == 0L) {
                    db.beginTransaction()
                    try {
                        // Riwayat lama terbaru di depan; masukkan dari yang terlama supaya urutan id mengikuti waktu.
                        for (l in old.asReversed()) insert(db, l)
                        if (rowCount(db) == old.size.toLong()) db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
                if (old.isEmpty() || rowCount(db) >= old.size.toLong()) {
                    prefs.edit().putBoolean("records_migrated", true).apply()
                }
            }
            ready = prefs.getBoolean("records_migrated", false)
        } catch (e: Exception) {
            ready = false
        }
    }

    /** Semua baris, terbaru di depan. */
    fun all(): MutableList<String> {
        if (!ready) return legacy()
        cache?.let { return it.toMutableList() }
        val out = ArrayList<String>()
        try {
            helper.readableDatabase.rawQuery("SELECT line FROM records ORDER BY date DESC, id DESC", null).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        } catch (e: Exception) {
            return legacy()
        }
        cache = out
        return out.toMutableList()
    }

    fun add(line: String): Boolean {
        cache = null
        if (!ready) {
            val list = legacy()
            list.add(0, line)
            prefs.edit().putString("records", list.joinToString("\n")).apply()
            return true
        }
        return try {
            insert(helper.writableDatabase, line)
        } catch (e: Exception) {
            false
        }
    }

    /** Hapus satu aktivitas (yang terbaru bila ada dua baris identik). */
    fun delete(line: String) {
        cache = null
        if (!ready) {
            val list = legacy()
            list.remove(line)
            prefs.edit().putString("records", list.joinToString("\n")).apply()
            return
        }
        try {
            helper.writableDatabase.execSQL(
                "DELETE FROM records WHERE id = (SELECT id FROM records WHERE line = ? ORDER BY id DESC LIMIT 1)",
                arrayOf<Any>(line)
            )
        } catch (e: Exception) {
        }
    }

    /** Tambah banyak baris sekaligus (pulihkan dari file); baris yang sudah ada dilewati. Mengembalikan jumlah yang ditambah. */
    fun addNew(lines: List<String>): Int {
        val have = HashSet<String>(all())
        var n = 0
        if (!ready) {
            val list = legacy()
            for (l in lines) if (have.add(l)) { list.add(l); n++ }
            if (n > 0) prefs.edit().putString("records", list.joinToString("\n")).apply()
            cache = null
            return n
        }
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                for (l in lines) if (have.add(l) && insert(db, l)) n++
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            n = 0
        }
        cache = null
        return n
    }

    /** Hapus semua riwayat, termasuk salinan lama di SharedPreferences. */
    fun clear() {
        cache = null
        prefs.edit().remove("records").apply()
        if (ready) {
            try {
                helper.writableDatabase.delete("records", null, null)
            } catch (e: Exception) {
            }
        }
    }
}
