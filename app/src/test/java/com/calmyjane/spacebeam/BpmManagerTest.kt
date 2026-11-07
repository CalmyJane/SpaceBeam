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

    @Test fun msToNextBeat() {
        bpm.bpm = 120f  // 500 ms per beat
        bpm.tick(0.125f)  // beatPos 0.25
        assertEquals(375L, bpm.msToNextBeat())
        bpm.tick(0.235f)  // beatPos 0.72
        assertEquals(140L, bpm.msToNextBeat())
        bpm.tick(0.17f)  // beatPos 1.06: just after a beat counts as on it
        assertEquals(0L, bpm.msToNextBeat())
    }

    @Test fun beatDurationFollowsTempoWhenSynced() {
        bpm.bpm = 120f
        val d = BeatDuration({ bpm.bpm }, 30000, 2, 2500L)
        assertEquals(2500L, d.ms)  // free: the slider's ms
        d.synced = true
        d.rawMs = 30000L
        assertEquals("16 BARS", d.label(30000))
        assertEquals(32000L, d.ms)  // 64 beats at 120 BPM
        bpm.bpm = 60f
        assertEquals(64000L, d.ms)
        d.rawMs = 0L
        assertEquals("0", d.label(0))
        assertEquals(0L, d.ms)
    }

    @Test fun beatDurationStepsAreMonotonic() {
        val d = BeatDuration({ 120f }, 30000, 2, 0L)
        d.synced = true
        var last = -1f
        for (v in 0..30000 step 100) {
            d.rawMs = v.toLong()
            assertTrue(d.beats >= last)
            last = d.beats
        }
        assertEquals(64f, last, 0f)  // default range ends at 16 bars
    }

    @Test fun beatDurationLongerRange() {
        val d = BeatDuration({ 120f }, 60000, 2, 60000L, maxBeats = 512f)
        d.synced = true
        assertEquals("128 BARS", d.label(60000))
        assertEquals(256000L, d.ms)  // 512 beats at 120 BPM
        assertEquals("0", d.label(0))
    }
}
