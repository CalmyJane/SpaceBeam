package com.calmyjane.spacebeam

import org.json.JSONObject

data class Preset(
    val controlSnapshots: Map<String, PropertyControl.Snapshot>,
    val flipX: Float,
    val flipY: Float,
    val rot180: Boolean,
    val axis: Int
)

/**
 * Preset <-> JSON as stored in SharedPreferences ("SpaceBeam_Presets" / "PRESET_n").
 * Keys are short and every field except "v" is optional, so presets saved by older versions keep loading:
 * never rename a key, only add new ones with an opt default.
 */
object PresetCodec {
    fun encode(preset: Preset): String {
        val rootObj = JSONObject()
        rootObj.put("axis", preset.axis)
        rootObj.put("flipX", preset.flipX.toDouble())
        rootObj.put("flipY", preset.flipY.toDouble())
        rootObj.put("rot180", preset.rot180)

        val controlsObj = JSONObject()
        preset.controlSnapshots.forEach { (key, snap) ->
            val snapObj = JSONObject()
            snapObj.put("v", snap.value)
            snapObj.put("r", snap.rate)
            snapObj.put("d", snap.depth)
            snapObj.put("shape", snap.shape)
            snapObj.put("s", snap.smoothing)
            snapObj.put("synced", snap.isSynced)
            snapObj.put("syncIdx", snap.syncIndex)
            snapObj.put("sax", snap.sensorAccelX)
            snapObj.put("say", snap.sensorAccelY)
            snapObj.put("saz", snap.sensorAccelZ)
            snapObj.put("sp",  snap.sensorPitch)
            snapObj.put("sro", snap.sensorRoll)
            snapObj.put("sy",  snap.sensorYaw)
            snapObj.put("hi", snap.handIndex)
            snapObj.put("hm", snap.handMiddle)
            snapObj.put("hr", snap.handRing)
            snapObj.put("hp", snap.handPinky)
            snapObj.put("ha", snap.handAngle)
            snapObj.put("hd", snap.handDist)
            snapObj.put("fr", snap.faceRot)
            snapObj.put("fd", snap.faceDist)
            snapObj.put("fm", snap.faceMouth)
            controlsObj.put(key, snapObj)
        }
        rootObj.put("controls", controlsObj)
        return rootObj.toString()
    }

    /** Throws on corrupt JSON; the caller falls back to the factory preset. */
    fun decode(json: String): Preset {
        val obj = JSONObject(json)
        val ax = obj.optInt("axis", 2)
        val fx = obj.optDouble("flipX", 1.0).toFloat()
        val fy = obj.optDouble("flipY", -1.0).toFloat()
        val r180 = obj.optBoolean("rot180", false)

        val snapshots = mutableMapOf<String, PropertyControl.Snapshot>()
        val controlsObj = obj.getJSONObject("controls")
        val keys = controlsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val s = controlsObj.getJSONObject(k)
            snapshots[k] = PropertyControl.Snapshot(
                s.getInt("v"),
                s.optInt("d", 0) > 0,
                s.optInt("r", 200),
                s.optInt("d", 0),
                s.optString("shape", "SINE"),
                s.optInt("s", 500),
                s.optBoolean("synced", false),
                s.optInt("syncIdx", 3),
                s.optInt("sax", 500),
                s.optInt("say", 500),
                s.optInt("saz", 500),
                s.optInt("sp",  500),
                s.optInt("sro", 500),
                s.optInt("sy",  500),
                s.optInt("hi", 0),
                s.optInt("hm", 0),
                s.optInt("hr", 0),
                s.optInt("hp", 0),
                s.optInt("ha", 0),
                s.optInt("hd", 0),
                s.optInt("fr", 0),
                s.optInt("fd", 0),
                s.optInt("fm", 0)
            )
        }
        return Preset(snapshots, fx, fy, r180, ax)
    }
}
