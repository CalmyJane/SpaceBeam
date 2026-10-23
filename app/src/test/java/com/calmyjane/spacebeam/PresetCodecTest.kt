package com.calmyjane.spacebeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCodecTest {
    private val full = PropertyControl.Snapshot(
        value = 640, active = true, rate = 321, depth = 777, shape = "WOBBLE_SINE", smoothing = 120,
        isSynced = true, syncIndex = 5,
        sensorAccelX = 10, sensorAccelY = 20, sensorAccelZ = 30, sensorPitch = 40, sensorRoll = 50, sensorYaw = 60,
        handIndex = -1000, handMiddle = 250, handRing = -250, handPinky = 1000, handAngle = 500, handDist = -500,
        faceRot = 300, faceDist = -300, faceMouth = 999
    )

    @Test fun roundTripKeepsEverything() {
        val preset = Preset(mapOf("M_ANGLE" to full, "K_AMT" to PropertyControl.Snapshot(1000, false, 0, 0, "SINE", 500)),
            flipX = -1f, flipY = 1f, rot180 = true, axis = 3)
        assertEquals(preset, PresetCodec.decode(PresetCodec.encode(preset)))
    }

    /** Pins the stored key names: renaming one would silently reset that field in every saved preset. */
    @Test fun decodesCurrentFormat() {
        val json = """{"axis":3,"flipX":-1,"flipY":1,"rot180":true,"controls":{"M_ANGLE":{"v":640,"r":321,"d":777,
            "shape":"WOBBLE_SINE","s":120,"synced":true,"syncIdx":5,"sax":10,"say":20,"saz":30,"sp":40,"sro":50,"sy":60,
            "hi":-1000,"hm":250,"hr":-250,"hp":1000,"ha":500,"hd":-500,"fr":300,"fd":-300,"fm":999}}}"""
        val p = PresetCodec.decode(json)
        assertEquals(Preset(mapOf("M_ANGLE" to full), -1f, 1f, true, 3), p)
    }

    /** Presets saved before sensors, hand/face tracking and beat sync existed must still load. */
    @Test fun oldPresetGetsDefaults() {
        val p = PresetCodec.decode("""{"controls":{"K_AMT":{"v":1000},"HUE":{"v":184,"r":298,"d":505,"shape":"RAMP"}}}""")
        assertEquals(2, p.axis)
        assertEquals(1f, p.flipX, 0f)
        assertEquals(-1f, p.flipY, 0f)
        assertFalse(p.rot180)
        assertEquals(PropertyControl.Snapshot(1000, false, 200, 0, "SINE", 500), p.controlSnapshots["K_AMT"])
        val hue = p.controlSnapshots.getValue("HUE")
        assertTrue(hue.active)
        assertEquals(PropertyControl.Snapshot(184, true, 298, 505, "RAMP", 500), hue)
    }

    @Test(expected = Exception::class) fun corruptJsonThrows() {
        PresetCodec.decode("""{"controls":{"K_AMT":""")
    }

    @Test(expected = Exception::class) fun missingValueThrows() {
        PresetCodec.decode("""{"controls":{"K_AMT":{"r":200}}}""")
    }
}
