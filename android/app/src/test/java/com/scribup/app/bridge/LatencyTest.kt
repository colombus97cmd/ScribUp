package com.scribup.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTest {
    private val beatMs = 60_000.0 / 90 // 90 BPM

    @Test
    fun regularTapsGiveTheDelay() {
        // tapes 240 ms après chaque temps de FL, avec un peu d'imprécision humaine
        val jitter = listOf(0.0, 12.0, -9.0, 5.0, -14.0, 8.0, -3.0, 10.0)
        val taps = jitter.mapIndexed { i, j -> 10 + i + (240 + j) / beatMs }
        val result = Latency.fromTaps(taps, beatMs)!!
        assertEquals(241.0, result.ms.toDouble(), 10.0)
        assertTrue(result.regularity > 0.95)
    }

    @Test
    fun longDelayAtFastTempoIsNotTakenForAnticipation() {
        // 360 ms à 140 BPM = 0,84 temps : c'est bien un retard, pas des tapes en avance
        val fastBeatMs = 60_000.0 / 140
        val taps = (0 until 8).map { 4 + it + 360 / fastBeatMs }
        assertEquals(360.0, Latency.fromTaps(taps, fastBeatMs)!!.ms.toDouble(), 2.0)
    }

    @Test
    fun slightAnticipationCountsAsZero() {
        val taps = (0 until 8).map { 4 + it - 20 / beatMs }
        assertEquals(0, Latency.fromTaps(taps, beatMs)!!.ms)
    }

    @Test
    fun irregularTapsAreFlagged() {
        val taps = listOf(1.0, 2.3, 3.6, 4.1, 5.8, 6.45, 7.2, 8.9)
        assertTrue(Latency.fromTaps(taps, beatMs)!!.regularity < 0.8)
    }

    @Test
    fun needsEnoughTaps() {
        assertNull(Latency.fromTaps(listOf(1.2, 2.2), beatMs))
    }
}
