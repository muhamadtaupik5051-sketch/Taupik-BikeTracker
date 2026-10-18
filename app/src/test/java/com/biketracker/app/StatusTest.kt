package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {

    @Test
    fun teksStatusDirapikanDanDibatasi() {
        assertEquals("Halo", StatusStore.clean("  Halo  "))
        assertEquals(StatusStore.MAX_CHARS, StatusStore.clean("a".repeat(500)).length)
        assertEquals("", StatusStore.clean("   "))
    }

    @Test
    fun statusBerakhirSetelah24Jam() {
        val s = StatusData("Gowes", 1_000_000L, false)
        assertEquals(StatusStore.LIFETIME_MS, s.remainingMs(1_000_000L))
        assertEquals(0L, s.remainingMs(1_000_000L + StatusStore.LIFETIME_MS))
        assertEquals(0L, s.remainingMs(1_000_000L + StatusStore.LIFETIME_MS + 5_000L))
    }

    @Test
    fun labelSisaWaktu() {
        val s = StatusData("Gowes", 0L, false)
        val oneHourLeft = StatusStore.LIFETIME_MS - 3_600_000L
        assertTrue(s.remainingLabel(oneHourLeft).contains("1 jam"))
        val tenMinLeft = StatusStore.LIFETIME_MS - 10 * 60_000L - 0L
        assertTrue(s.remainingLabel(tenMinLeft).contains("10 menit"))
    }

    @Test
    fun statusDariAktivitas() {
        val t = StatusStore.fromActivity("Sepeda", "🚴", 12.34f, 2_710_000L)
        assertTrue(t.startsWith("🚴 Selesai Sepeda"))
        assertTrue(t.contains("km"))
    }

    @Test
    fun teksBagikanMemuatNamaDanTagar() {
        val txt = StatusCard.text("Budi", StatusData("Lagi gowes 🚴", 0L, false))
        assertTrue(txt.contains("Budi"))
        assertTrue(txt.contains("Lagi gowes"))
        assertTrue(txt.endsWith("#Delaas"))
        assertTrue(txt.contains("DLS1."))
    }

    @Test
    fun base64Bolakbalik() {
        val samples = listOf("", "a", "ab", "abc", "Halo \uD83D\uDEB4 gowes!", "x".repeat(200))
        for (s in samples) {
            val b = s.toByteArray(Charsets.UTF_8)
            assertEquals(s, String(B64.dec(B64.enc(b))!!, Charsets.UTF_8))
        }
    }

    @Test
    fun kodeStatusBolakbalik() {
        val now = 5_000_000L
        val code = StatusCode.encode("Budi", StatusData("Lagi gowes \uD83D\uDEB4", now, false))
        val fs = StatusCode.parse("Ini status saya:\n$code\n#Delaas", now + 60_000L)!!
        assertEquals("Budi", fs.name)
        assertEquals("Lagi gowes \uD83D\uDEB4", fs.text)
        assertEquals(now, fs.createdAt)
    }

    @Test
    fun kodeStatusSudahBerakhirAtauRusakDitolak() {
        val now = 5_000_000L
        val code = StatusCode.encode("Budi", StatusData("Halo", now, false))
        assertNull(StatusCode.parse(code, now + StatusStore.LIFETIME_MS))
        assertNull(StatusCode.parse("DLS1.@@@", now))
        assertNull(StatusCode.parse("tidak ada kode", now))
        // waktu di masa depan yang tidak wajar
        assertNull(StatusCode.parse(code, now - 10 * 3_600_000L))
    }

    @Test
    fun pemisahInternalDibuang() {
        assertEquals("ab", StatusStore.clean("a\u0001b\u0002"))
    }
}
