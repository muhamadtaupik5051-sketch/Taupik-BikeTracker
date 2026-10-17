package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxImportTest {

    // 2026-10-16 04:00:00 UTC (sama dengan GpxExportTest)
    private val t0 = 1792123200000L

    private fun pt(lat: Double, lon: Double, ele: Double, time: String) =
        "<trkpt lat=\"$lat\" lon=\"$lon\"><ele>$ele</ele><time>$time</time></trkpt>"

    private fun gpx(vararg pts: String) = "<gpx><trk><trkseg>" + pts.joinToString("") + "</trkseg></trk></gpx>"

    @Test
    fun waktuIsoDenganPecahanDanSelisihZona() {
        assertEquals(t0, GpxImport.parseTime("2026-10-16T04:00:00Z")!!)
        assertEquals(t0, GpxImport.parseTime("2026-10-16T04:00:00.000Z")!!)
        assertEquals(t0, GpxImport.parseTime("2026-10-16T11:00:00+07:00")!!)
        assertNull(GpxImport.parseTime("bukan waktu"))
    }

    @Test
    fun jalurBerwaktuMenghasilkanStatistik() {
        // 0,01 derajat bujur di lintang -6,2 sekitar 1,105 km, ditempuh dalam 60 detik.
        val r = GpxImport.parse(
            gpx(
                pt(-6.2, 106.80, 10.0, "2026-10-16T04:00:00Z"),
                pt(-6.2, 106.81, 10.5, "2026-10-16T04:01:00Z")
            )
        )!!
        assertEquals(1.105, r.km, 0.01)
        assertEquals(60_000L, r.elapsedMs)
        assertEquals(60_000L, r.movingMs)
        assertEquals(66.3, r.avgKmh, 1.0)
        assertEquals(66.3, r.maxKmh, 1.0)
        assertEquals(0.0, r.climbM, 0.001)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(r.date))
    }

    @Test
    fun tanjakanMengabaikanDerauKecil() {
        val r = GpxImport.parse(
            gpx(
                pt(-6.2, 106.800, 100.0, "2026-10-16T04:00:00Z"),
                pt(-6.2, 106.801, 101.0, "2026-10-16T04:00:10Z"),
                pt(-6.2, 106.802, 106.0, "2026-10-16T04:00:20Z"),
                pt(-6.2, 106.803, 112.0, "2026-10-16T04:00:30Z"),
                pt(-6.2, 106.804, 100.0, "2026-10-16T04:00:40Z")
            )
        )!!
        // Naik 100 -> 106 -> 112 = 12 m; turun tidak dihitung; naik 1 m di awal diabaikan.
        assertEquals(12.0, r.climbM, 0.001)
    }

    @Test
    fun jalurTanpaWaktuTetapBisaDiimpor() {
        val r = GpxImport.parse(
            "<gpx><trk><trkseg><trkpt lat=\"-6.2\" lon=\"106.8\"/><trkpt lat=\"-6.2\" lon=\"106.81\"/></trkseg></trk></gpx>"
        )!!
        assertEquals(1.105, r.km, 0.01)
        assertEquals(0L, r.elapsedMs)
        assertEquals(0.0, r.avgKmh, 0.001)
        assertTrue(r.samples.isEmpty())
    }

    @Test
    fun kurangDariDuaTitikAtauBukanGpxDitolak() {
        assertNull(GpxImport.parse("halo"))
        assertNull(GpxImport.parse("<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk></gpx>"))
    }

    @Test
    fun atributTanpaUrutanTetapDanKutipTunggal() {
        val pts = GpxImport.parsePoints("<trkpt lon='106.8' lat='-6.2'><ele>5</ele></trkpt><trkpt lat=\"1\" lon=\"2\"/>")
        assertEquals(2, pts.size)
        assertEquals(-6.2, pts[0][0], 0.0001)
        assertEquals(106.8, pts[0][1], 0.0001)
        assertEquals(5.0, pts[0][2], 0.0001)
        assertTrue(pts[1][2].isNaN())
    }

    @Test
    fun koordinatDiLuarRentangDilewati() {
        val pts = GpxImport.parsePoints("<trkpt lat=\"95\" lon=\"2\"/><trkpt lat=\"1\" lon=\"200\"/><trkpt lat=\"1\" lon=\"2\"/>")
        assertEquals(1, pts.size)
    }

    @Test
    fun lonjakanGpsTidakJadiKecepatanMaksimum() {
        // 1 derajat dalam 10 detik = ribuan km/jam: jelas derau.
        val r = GpxImport.parse(
            gpx(
                pt(0.0, 0.0, 0.0, "2026-10-16T04:00:00Z"),
                pt(0.0, 1.0, 0.0, "2026-10-16T04:00:10Z")
            )
        )!!
        assertEquals(0.0, r.maxKmh, 0.001)
    }

    @Test
    fun ekspordanImporBolakBalik() {
        val exported = GpxExport.build(
            "Tes",
            listOf(
                doubleArrayOf(-6.2, 106.80, 10.0, t0.toDouble()),
                doubleArrayOf(-6.2, 106.81, 12.0, (t0 + 60_000L).toDouble())
            )
        )
        val r = GpxImport.parse(exported)!!
        assertEquals(2, r.points.size)
        assertEquals(60_000L, r.elapsedMs)
        assertEquals(1.105, r.km, 0.01)
    }

    @Test
    fun barisRiwayatCocokDenganFormatRec() {
        val r = GpxImport.parse(
            gpx(
                pt(-6.2, 106.80, 10.0, "2026-10-16T04:00:00Z"),
                pt(-6.2, 106.81, 10.5, "2026-10-16T04:01:00Z")
            )
        )!!
        val line = GpxImport.recordLine(r, "Lari", "5")
        val f = line.split("|")
        assertEquals(13, f.size)
        val rec = Rec(f)
        assertEquals("Lari", rec.mode)
        assertEquals(1.105f, rec.km, 0.01f)
        assertEquals(60_000L, rec.elapsed)
        assertEquals(5f, rec.target, 0.001f)
        assertEquals(2, rec.route.size)
        assertEquals(1, rec.samples.size)
        assertEquals(60, rec.sampleSec)
    }

    @Test
    fun ruteRiwayatDipadatkanTapiTitikAkhirTetapAda() {
        val many = (0 until 2000).map { doubleArrayOf(-6.0 + it * 0.0001, 106.0, Double.NaN, 0.0) }
        val enc = GpxImport.encodeRoute(many)
        val parts = enc.split(";")
        assertTrue(parts.size <= 801)
        assertNotNull(parts.last())
        assertEquals(String.format(java.util.Locale.US, "%.5f,%.5f", many.last()[0], many.last()[1]), parts.last())
    }
}
