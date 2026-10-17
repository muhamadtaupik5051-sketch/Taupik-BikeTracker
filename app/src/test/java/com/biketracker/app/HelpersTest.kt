package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {

    @Test
    fun kunciBerkasJalurAman() {
        assertEquals("2026-10-16_06_30_1234", TrackStore.key("2026-10-16 06:30", 1234L))
    }

    @Test
    fun kodeUndanganGrup() {
        assertEquals("RIDE2A", GroupStore.cleanCode(" ride2a "))
        assertNull(GroupStore.cleanCode("ab"))
        assertNull(GroupStore.cleanCode("ride-1"))
        assertNull(GroupStore.cleanCode("ABCDEFGHI"))
        assertTrue(Regex("RIDE[A-HJ-NP-Z2-9]{4}").matches(GroupStore.newCode()))
    }

    @Test
    fun formatWaktuDanPace() {
        assertEquals("01:02:05", Fmt.time(3_725_000L))
        assertEquals("00:00:00", Fmt.time(-5L))
        assertEquals("5:42", Fmt.pace(342))
        assertEquals("--:--", Fmt.pace(null))
        assertEquals("--:--", Fmt.pace(6000))
        assertEquals("3 j 05 m", Fmt.hm(11_100_000L))
    }
}
