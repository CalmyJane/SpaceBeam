package com.calmyjane.spacebeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyControlTest {
    private val frame = 1f / 60f

    private fun PropertyControl.run(seconds: Float, each: (PropertyControl) -> Unit = {}) {
        repeat(kotlin.math.round(seconds / frame).toInt()) { update(frame); each(this) }
    }

    private fun lfoSnapshot(value: Int, shape: PropertyControl.WaveShape, rate: Int = 500, depth: Int = 1000) =
        PropertyControl.Snapshot(value, depth > 0, rate, depth, shape.name, 0)

    @Test fun valueMapsToOutputRange() {
        val c = PropertyControl("X", "X", defaultValue = 250, outMin = -2f, outMax = 2f)
        assertEquals(-1f, c.computedValue, 1e-6f)
        c.smoothing = 0
        c.setProgress(1000); c.update(frame)
        assertEquals(2f, c.computedValue, 1e-6f)
    }

    @Test fun setProgressClamps() {
        val c = PropertyControl("X", "X", min = 100, max = 900)
        c.setProgress(5000); assertEquals(900, c.value)
        c.setProgress(-5); assertEquals(100, c.value)
    }

    @Test fun transitionEasesAndLandsExactly() {
        val c = PropertyControl("X", "X")
        c.animateTo(1000f, 1f)
        c.run(0.5f)
        assertEquals(500f, c.preciseValue, 30f)  // smoothstep midpoint (ease lags one frame)
        c.run(0.6f)
        assertEquals(1000, c.value)
        assertEquals(1000f, c.preciseValue, 0f)
        assertEquals(1f, c.computedValue, 0.002f)  // last frame finishes through smoothing, not snapped
    }

    @Test fun transitionIsMonotonic() {
        val c = PropertyControl("X", "X", defaultValue = 800)
        var last = c.preciseValue
        c.animateTo(100f, 2f)
        c.run(2.5f) {
            assertTrue(it.preciseValue <= last)
            last = it.preciseValue
        }
        assertEquals(100, c.value)
    }

    /** Rotation 950 -> 50 must turn forward through 0 (100 steps), not back through 500 (900 steps). */
    @Test fun angleTakesShortestWay() {
        val c = PropertyControl("M_ANGLE", "ANGLE", defaultValue = 950, modMode = PropertyControl.ModMode.WRAP)
        c.animateTo(50f, 1f)
        c.run(1.2f) {
            val v = it.preciseValue
            assertTrue("went the long way: $v", v >= 950f || v <= 50f)
        }
        assertEquals(50, c.value)
    }

    @Test fun smoothingGlidesToTarget() {
        val c = PropertyControl("X", "X")
        c.smoothing = 500
        c.setProgress(1000)
        c.update(frame)
        assertTrue("jumped instead of gliding", c.computedValue < 0.5f)
        var last = c.computedValue
        c.run(5f) {
            assertTrue(it.computedValue >= last)
            last = it.computedValue
        }
        assertEquals(1f, c.computedValue, 0.01f)
    }

    @Test fun noSmoothingIsImmediate() {
        val c = PropertyControl("X", "X")
        c.smoothing = 0
        c.setProgress(700); c.update(frame)
        assertEquals(0.7f, c.computedValue, 1e-6f)
    }

    @Test fun lfoStaysInRangeForEveryShape() {
        for (mode in PropertyControl.ModMode.values()) {
            for (shape in PropertyControl.WaveShape.values()) {
                val c = PropertyControl("X", "X", outMin = -1f, outMax = 3f, hasModulation = true, modMode = mode)
                c.restore(lfoSnapshot(500, shape, rate = 900), 0f)
                var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
                c.run(20f) {
                    val v = it.computedValue
                    assertFalse("$mode/$shape NaN", v.isNaN())
                    assertTrue("$mode/$shape out of range: $v", v in -1f..3f)
                    min = minOf(min, v); max = maxOf(max, v)
                }
                assertTrue("$mode/$shape does not move", max - min > 0.5f)
            }
        }
    }

    @Test fun zeroDepthLeavesValueAlone() {
        val c = PropertyControl("X", "X", hasModulation = true)
        c.restore(lfoSnapshot(300, PropertyControl.WaveShape.SINE, depth = 0), 0f)
        c.run(5f) { assertEquals(0.3f, it.computedValue, 1e-6f) }
    }

    @Test fun shapeChangeCrossfadesWithoutJump() {
        val c = PropertyControl("X", "X", hasModulation = true)
        c.restore(lfoSnapshot(500, PropertyControl.WaveShape.SINE), 0f)
        c.run(1f)
        c.restore(lfoSnapshot(500, PropertyControl.WaveShape.RANDOM_STEP), 2f)
        var last = c.computedValue
        c.run(3f) {
            assertTrue("jump during shape fade", kotlin.math.abs(it.computedValue - last) < 0.1f)
            last = it.computedValue
        }
    }

    @Test fun restoreThenSnapshotRoundTrips() {
        val c = PropertyControl("X", "X", hasModulation = true)
        val s = PropertyControl.Snapshot(420, true, 333, 666, "TRIANGLE", 250, isSynced = false, syncIndex = 2,
            sensorPitch = 800, handIndex = -1000, faceMouth = 400)
        c.restore(s, 0.5f)
        c.run(1f)
        assertEquals(s, c.getSnapshot())
    }

    @Test fun lockedControlIgnoresPresets() {
        val c = PropertyControl("X", "X", defaultValue = 100, defaultLocked = true)
        c.restore(PropertyControl.Snapshot(900, false, 0, 0, "SINE", 0), 0f)
        c.run(1f)
        assertEquals(100, c.value)
    }
}
