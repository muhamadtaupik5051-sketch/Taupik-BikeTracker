package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExportTest {

    @Test
    fun titikTanpaTinggiDanWaktuTetapValid() {
        val gpx = GpxExport.build("Tes", listOf(doubleArrayOf(-6.2, 106.8), doubleArrayOf(-6.21, 106.81)))
        assertTrue(gpx.contains("<trkpt lat=\"-6.200000\" lon=\"106.800000\"/>"))
        assertFalse(gpx.contains("<time>"))
        assertFalse(gpx.contains("<ele>"))
    }

    @Test
    fun titikLengkapMemuatTinggiDanWaktuUtc() {
        // 2026-10-16 04:00:00 UTC
        val t = 1792123200000.0
        val gpx = GpxExport.build("Tes", listOf(doubleArrayOf(-6.2, 106.8, 12.34, t), doubleArrayOf(-6.21, 106.81, Double.NaN, t + 1000.0)))
        assertTrue(gpx.contains("<ele>12.3</ele>"))
        assertTrue(gpx.contains("<time>2026-10-16T04:00:00Z</time>"))
        assertTrue(gpx.contains("<time>2026-10-16T04:00:01Z</time>"))
        // Titik kedua tidak punya tinggi, jadi hanya ada satu <ele>.
        assertEquals(1, Regex("<ele>").findAll(gpx).count())
    }

    @Test
    fun namaDiloloskanDanNamaBerkasAman() {
        val gpx = GpxExport.build("A & B <x>", emptyList())
        assertTrue(gpx.contains("A &amp; B &lt;x&gt;"))
        assertEquals("Delaas_Sepeda_2026-10-16_06_30.gpx", GpxExport.fileName("Sepeda", "2026-10-16 06:30"))
    }
}
