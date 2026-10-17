package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecTest {

    private val full = "2026-10-16 06:30|Sepeda|12.500|3600000|12.5|30.1|20|-6.20000,106.80000;-6.21000,106.81000|300|45|3000000|20|10.0,12.5"

    @Test
    fun barisLengkapDibacaPerKolom() {
        val r = Rec(full.split("|"))
        assertEquals("2026-10-16 06:30", r.date)
        assertEquals("Sepeda", r.mode)
        assertEquals(12.5f, r.km, 0.001f)
        assertEquals(3_600_000L, r.elapsed)
        assertEquals(20f, r.target, 0.001f)
        assertEquals("300", r.kcal)
        assertEquals("45", r.climb)
        assertEquals(3_000_000L, r.moving)
        assertEquals(20, r.sampleSec)
        assertEquals(listOf(10.0f, 12.5f), r.samples)
        assertEquals(2, r.route.size)
        assertEquals(-6.21, r.route[1][0], 0.00001)
        assertFalse(r.reached)
        assertEquals(288, r.paceSec)
    }

    @Test
    fun barisLamaTanpaKolomTambahanPakaiNilaiBawaan() {
        val r = Rec("2026-10-16 06:30|Lari|5.000|1800000|10.0|14.0".split("|"))
        assertEquals("0", r.kcal)
        assertEquals("0", r.climb)
        assertEquals(0L, r.moving)
        assertTrue(r.samples.isEmpty())
        assertTrue(r.route.isEmpty())
        assertEquals(0f, r.target, 0.001f)
    }

    @Test
    fun targetTercapaiBilaJarakCukup() {
        val r = Rec("2026-10-16 06:30|Lari|5.000|1800000|10.0|14.0|5".split("|"))
        assertTrue(r.reached)
    }
}
