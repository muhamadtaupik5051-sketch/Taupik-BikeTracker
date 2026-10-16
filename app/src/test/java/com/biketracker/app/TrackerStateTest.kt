package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TrackerStateTest {

    @Before
    fun reset() {
        TrackerState.running = false
        TrackerState.clearDisplay()
        TrackerState.mode = "Sepeda"
    }

    @Test
    fun langkahDihitungDariJarak() {
        TrackerState.distanceMeters = 1000f
        assertEquals(1300, TrackerState.steps())
    }

    @Test
    fun kaloriSepedaMemakaiMetSesuaiKecepatan() {
        // 20 km dalam 1 jam = 20 km/jam -> MET 8; 8 x 70 kg x 1 jam = 560 kkal
        TrackerState.distanceMeters = 20000f
        TrackerState.elapsedBeforeRun = 3_600_000L
        assertEquals(560, TrackerState.calories(70))
    }

    @Test
    fun labelSinyalGps() {
        assertEquals("GPS bagus ±5 m", TrackerState.gpsLabel(5f))
        assertEquals("GPS cukup ±12 m", TrackerState.gpsLabel(12f))
        assertEquals("GPS lemah ±20 m", TrackerState.gpsLabel(20f))
    }

    @Test
    fun paceKosongKetikaTerlaluLambat() {
        assertEquals(null, TrackerState.paceNowSec())
    }

    @Test
    fun rataRataKecepatan() {
        TrackerState.distanceMeters = 10000f
        TrackerState.elapsedBeforeRun = 3_600_000L
        assertEquals(10f, TrackerState.averageSpeed(), 0.01f)
    }
}
