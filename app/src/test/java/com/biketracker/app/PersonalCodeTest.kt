package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCodeTest {

    @Test
    fun kodePribadiBentukDanPembersihan() {
        repeat(50) {
            val c = PersonalCode.generate()
            assertTrue(Regex("DLS-[A-HJ-NP-Z2-9]{6}").matches(c))
            assertEquals(c, PersonalCode.clean(c))
        }
        assertEquals("DLS-7K3Q9X", PersonalCode.clean(" dls-7k3q9x "))
        assertEquals("DLS-7K3Q9X", PersonalCode.clean("7K3Q9X"))
        assertNull(PersonalCode.clean("DLS-ABC"))
        assertNull(PersonalCode.clean("DLS-7K3Q0X"))
        assertNull(PersonalCode.clean(""))
    }
}
