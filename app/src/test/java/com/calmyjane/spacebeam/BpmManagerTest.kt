package com.calmyjane.spacebeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BpmManagerTest {
    private var nowNanos = 1_000_000_000L
    private val bpm = BpmManager { nowNanos }

    private fun advanceMs(ms: Long) { nowNanos += ms * 1_000_000 }

    private fun tapEvery(ms: Long, count: Int) {
        repeat(count) { i ->
            if (i > 0) advanceMs(ms)
            bpm.tap()
        }
    }

    @Test fun beatClockAdvancesWithBpm() {
        bpm.bpm = 120f
        repeat(60) { bpm.tick(1f / 60f) }  // one second
        assertEquals(2.0, bpm.beatPos, 1e-4)
    }

    @Test fun twoTapsDoNotChangeTempo() {
        tapEvery(400, 2)
        assertEquals(120f, bpm.bpm, 0f)
    }

    @Test fun tapsSetTempo() {
        tapEvery(500, 4)
        assertEquals(120f, bpm.bpm, 0.01f)
        advanceMs(400)
        tapEvery(400, 8)  // the 8-tap window forgets the 500 ms taps
        assertEquals(150f, bpm.bpm, 0.01f)
    }

    @Test fun pauseStartsNewTapSeries() {
        tapEvery(300, 3)
        assertEquals(200f, bpm.bpm, 0.01f)
        advanceMs(3000)  // > 2.5 s gap: old taps are dropped
        tapEvery(600, 3)
        assertEquals(100f, bpm.bpm, 0.01f)
    }

    @Test fun tempoIsClamped() {
        tapEvery(50, 4)
        assertEquals(300f, bpm.bpm, 0f)
        advanceMs(3000)
        tapEvery(2400, 4)
        assertEquals(30f, bpm.bpm, 0f)
    }

    @Test fun tapSnapsBeatClockToNearestBeat() {
        bpm.bpm = 120f
        repeat(10) { bpm.tick(0.033f) }  // beatPos 0.66, off the beat
        bpm.tap()
        advanceMs(100)
        bpm.tick(0.1f)  // tap is processed 0.1 s (0.2 beats) later
        assertEquals(1.2, bpm.beatPos, 1e-6)
    }

    @Test fun syncBlendFadesOutAndIn() {
        assertEquals(1f, bpm.syncBlend, 0f)
        bpm.syncEnabled = false
        bpm.tick(0.4f)
        assertEquals(0.5f, bpm.syncBlend, 1e-4f)  // smoothstep midpoint
        bpm.tick(0.5f)
        assertEquals(0f, bpm.syncBlend, 0f)
        bpm.syncEnabled = true
        var last = 0f
        repeat(60) {
            bpm.tick(1f / 60f)
            assertTrue(bpm.syncBlend >= last)
            last = bpm.syncBlend
        }
        assertEquals(1f, bpm.syncBlend, 0f)
    }
}
