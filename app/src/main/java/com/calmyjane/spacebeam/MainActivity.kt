//SpaceBeam - A Kaleidoscope Camera Visual Synthesizer
//Copyright (C) 2026 Jan Goebel
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <https://www.gnu.org/licenses/>.

//Contact: info@calmyjane.com

package com.calmyjane.spacebeam


import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.media.*
import android.opengl.*
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.common.VideoSize
import androidx.media3.common.Player
import android.util.Log
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import javax.microedition.khronos.egl.EGLConfig as GL10EGLConfig
import android.opengl.EGLConfig as EGL14EGLConfig
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.apply
import android.view.inputmethod.InputMethodManager
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.abs
import org.json.JSONArray
import android.annotation.SuppressLint
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo

class MidiHelper(private val activity: MainActivity) {
    // Standard BLE MIDI UUIDs
    private val MIDI_SERVICE_UUID = java.util.UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private val MIDI_CHAR_UUID    = java.util.UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
    private val CCCD_UUID         = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothAdapter: BluetoothAdapter? = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var activeGatt: BluetoothGatt? = null
    var isConnected = false
        private set

    // --- MAPPING CONFIGURATION ---
    private val DEFAULT_MAPPING_JSON = """
    {
      "name": "Factory Default",
      "map": [
          {"cc":40, "t":"CAM_MAIN", "m":"VAL"},
          {"cc":41, "t":"SRC_DYN_0", "m":"VAL"},
          {"cc":42, "t":"SRC_DYN_1", "m":"VAL"},
          {"cc":43, "t":"SRC_DYN_2", "m":"VAL"},
          {"cc":44, "t":"M_TILTX", "m":"DEPTH"},
          {"cc":44, "t":"M_TILTY", "m":"DEPTH"},
          {"cc":45, "t":"S_SPEED", "m":"VAL"},
          {"cc":46, "t":"M_ZOOM", "m":"VAL"},
          {"cc":47, "t":"TRANS_TIME", "m":"VAL"},
          {"cc":34, "t":"M_TILTX", "m":"RATE"},
          {"cc":34, "t":"M_TILTY", "m":"RATE", "s":0.8849},
          {"cc":35, "t":"3D_MIX", "m":"VAL"},
          {"cc":36, "t":"M_ANGLE", "m":"RATE"},
          {"cc":37, "t":"AUTO_DUR", "m":"VAL"},
          {"cc":62, "t":"PRESET_1", "m":"TRIG"},
          {"cc":61, "t":"PRESET_2", "m":"TRIG"},
          {"cc":60, "t":"PRESET_3", "m":"TRIG"},
          {"cc":59, "t":"PRESET_4", "m":"TRIG"},
          {"cc":58, "t":"PRESET_5", "m":"TRIG"},
          {"cc":57, "t":"PRESET_6", "m":"TRIG"},
          {"cc":56, "t":"PRESET_7", "m":"TRIG"},
          {"cc":55, "t":"PRESET_8", "m":"TRIG"},
          {"cc":54, "t":"CMD_RECORD", "m":"TRIG"},
          {"cc":53, "t":"CMD_PHOTO", "m":"TRIG"},
          {"cc":52, "t":"CMD_AUTOPLAY", "m":"TRIG"}
      ]
    }
    """.trimIndent()

    var mappingName: String = "Default"
        private set

    data class MidiBinding(val target: String, val mode: String, val scale: Float = 1.0f)

    private val bindingMap = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CopyOnWriteArrayList<MidiBinding>>()
    private val reverseMap = java.util.concurrent.ConcurrentHashMap<String, Int>()

    var isScanning = false
    private var scanCallback: ScanCallback? = null
    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null

    // Supports "TARGET_ID" or "TARGET_ID|MODE" (e.g. M_ZOOM|RATE)
    var learningTargetId: String? = null
    var onLearningComplete: (() -> Unit)? = null

    init {
        importConfig(DEFAULT_MAPPING_JSON)
    }

    fun exportConfig(): String {
        val root = JSONObject()
        root.put("name", mappingName)

        val mapArray = JSONArray()
        bindingMap.forEach { (cc, bindings) ->
            bindings.forEach { b ->
                val obj = JSONObject()
                obj.put("cc", cc)
                obj.put("t", b.target)
                obj.put("m", b.mode)
                if (b.scale != 1.0f) obj.put("s", b.scale.toDouble())
                mapArray.put(obj)
            }
        }
        root.put("map", mapArray)
        return root.toString() // You might want .toString(2) for pretty print if using a newer JSON lib, but standard org.json is compact
    }

    // NEW: Imports JSON string, clears old map, fills new map
    fun importConfig(jsonString: String): Boolean {
        return try {
            // Clear current
            bindingMap.clear()
            reverseMap.clear()

            // Detect if it's the old array format or new object format
            val isArray = jsonString.trim().startsWith("[")

            val mapArray: JSONArray
            if (isArray) {
                mapArray = JSONArray(jsonString)
                mappingName = "Imported Legacy"
            } else {
                val root = JSONObject(jsonString)
                mappingName = root.optString("name", "Imported")
                mapArray = root.getJSONArray("map")
            }

            for (i in 0 until mapArray.length()) {
                val obj = mapArray.getJSONObject(i)
                val cc = obj.getInt("cc")
                val t = obj.getString("t")
                val m = obj.getString("m")
                val s = obj.optDouble("s", 1.0).toFloat()
                addBinding(cc, t, m, s, updateReverse = true)
            }
            true
        } catch (e: Exception) {
            Log.e("MIDI", "Import failed", e)
            false
        }
    }


    private fun addBinding(cc: Int, target: String, mode: String, scale: Float, updateReverse: Boolean) {
        if (!bindingMap.containsKey(cc)) {
            bindingMap[cc] = java.util.concurrent.CopyOnWriteArrayList()
        }
        bindingMap[cc]?.add(MidiBinding(target, mode, scale))

        if (updateReverse && mode == "VAL") {
            reverseMap[target] = cc
        }
    }

    fun getBindingsForTarget(targetId: String): List<Pair<Int, MidiBinding>> {
        val result = mutableListOf<Pair<Int, MidiBinding>>()
        bindingMap.forEach { (cc, list) ->
            list.forEach { binding ->
                if (binding.target == targetId) {
                    result.add(Pair(cc, binding))
                }
            }
        }
        return result
    }

    fun removeBinding(cc: Int, targetId: String) {
        bindingMap[cc]?.removeIf { it.target == targetId }
        if (bindingMap[cc]?.isEmpty() == true) {
            bindingMap.remove(cc)
        }
        if (reverseMap[targetId] == cc) {
            reverseMap.remove(targetId)
            val other = getBindingsForTarget(targetId).firstOrNull { it.second.mode == "VAL" }
            if (other != null) {
                reverseMap[targetId] = other.first
            }
        }
    }

    fun getMappedCC(controlId: String): Int? {
        return reverseMap[controlId]
    }

    fun unmap(controlId: String) {
        val cc = reverseMap[controlId] ?: return
        reverseMap.remove(controlId)
        bindingMap[cc]?.removeIf { it.target == controlId && it.mode == "VAL" }
        if (bindingMap[cc]?.isEmpty() == true) {
            bindingMap.remove(cc)
        }
    }

    fun startLeScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(activity, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Permission missing for BT Scan", Toast.LENGTH_SHORT).show()
            return
        }
        stopLeScan()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                try {
                    if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        if (device.name != null) onDeviceFound?.invoke(device)
                    } else {
                        if (device.name != null) onDeviceFound?.invoke(device)
                    }
                } catch (e: SecurityException) { }
            }
        }
        try {
            isScanning = true
            bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
            Handler(Looper.getMainLooper()).postDelayed({ stopLeScan() }, 10000)
        } catch (e: SecurityException) { }
    }

    fun stopLeScan() {
        if (isScanning && scanCallback != null && bluetoothAdapter != null) {
            try {
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
                }
            } catch (e: SecurityException) { }
            isScanning = false
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        stopLeScan()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        close()
        try {
            activeGatt = device.connectGatt(activity, false, gattCallback)
            activity.runOnUiThread { Toast.makeText(activity, "Connecting...", Toast.LENGTH_SHORT).show() }
        } catch (e: SecurityException) { }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                try {
                    if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        if (!gatt.requestMtu(512)) gatt.discoverServices()
                    }
                } catch (e: SecurityException) { gatt.discoverServices() }
                activity.runOnUiThread { Toast.makeText(activity, "Connected!", Toast.LENGTH_SHORT).show() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                activeGatt = null
                activity.runOnUiThread { Toast.makeText(activity, "Disconnected", Toast.LENGTH_SHORT).show() }
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            try { if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) gatt?.discoverServices() } catch (e: SecurityException) {}
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(MIDI_SERVICE_UUID)
                service?.getCharacteristic(MIDI_CHAR_UUID)?.let { enableMidiNotification(gatt, it) }
            }
        }
        private fun enableMidiNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            try {
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            } catch (e: SecurityException) { }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleRawPacket(value) }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { handleRawPacket(characteristic.value) }
    }

    private fun handleRawPacket(value: ByteArray) {
        if (value.size > 2) {
            val status = value[2].toInt() and 0xFF
            if ((status and 0xF0) == 0xB0 && value.size > 4) {
                processCC(value[3].toInt() and 0x7F, value[4].toInt() and 0x7F)
            } else if ((status and 0xF0) == 0x90 && value.size > 4) {
                val velocity = value[4].toInt() and 0x7F
                processCC(value[3].toInt() and 0x7F, if (velocity > 0) 127 else 0)
            }
        }
    }

    private fun processCC(cc: Int, val7Bit: Int) {
        activity.runOnUiThread {
            // 1. Learning Mode
            if (learningTargetId != null) {
                var target = learningTargetId!!
                var forcedMode: String? = null

                // Check for pipe syntax "TARGET|MODE"
                if (target.contains("|")) {
                    val parts = target.split("|")
                    target = parts[0]
                    forcedMode = parts[1]
                }

                // If specific mode is forced (RATE/DEPTH), use it. Otherwise guess based on prefix.
                val mode = forcedMode ?: (if (target.startsWith("CMD_") || target.startsWith("PRESET_")) "TRIG" else "VAL")

                // Remove existing bindings for this specific target & mode pair (to avoid duplicates)
                if (reverseMap.containsKey(target) && mode == "VAL") {
                    val oldCC = reverseMap[target]!!
                    bindingMap[oldCC]?.removeIf { it.target == target && it.mode == "VAL" }
                }

                // Also clean up if this CC was already doing something else?
                // For simplicity in learning, we usually wipe the CC for this specific binding type.
                bindingMap[cc]?.removeIf { it.target == target && it.mode == mode }

                addBinding(cc, target, mode, 1.0f, true)

                learningTargetId = null
                onLearningComplete?.invoke()
                Toast.makeText(activity, "Mapped CC $cc to $target ($mode)", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }

            // 2. Execution Mode
            val bindings = bindingMap[cc] ?: return@runOnUiThread

            bindings.forEach { binding ->
                if (binding.target.startsWith("CMD_") || binding.target.startsWith("PRESET_")) {
                    if (val7Bit > 64) activity.handleMidiCommand(binding.target, val7Bit)
                    return@forEach
                }

                var control: PropertyControl? = null
                if (binding.target.startsWith("SRC_DYN_")) {
                    val index = binding.target.removePrefix("SRC_DYN_").toIntOrNull() ?: -1
                    if (index >= 0 && index < activity.getSourceControlsList().size) {
                        control = activity.getSourceControlsList()[index]
                    }
                } else {
                    control = activity.controlsMap[binding.target]
                }

                if (control != null) {
                    val scaledVal = (val7Bit * binding.scale).coerceIn(0f, 127f)
                    when (binding.mode) {
                        "VAL" -> control.setProgress((scaledVal / 127.0f * control.sliderMax).toInt())
                        "RATE" -> control.updateModRate((scaledVal / 127.0f * 1000).toInt())
                        "DEPTH" -> control.updateModDepth((scaledVal / 127.0f * 1000).toInt())
                    }
                }
            }
        }
    }

    fun close() {
        try {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                activeGatt?.disconnect(); activeGatt?.close()
            }
            activeGatt = null
        } catch (e: SecurityException) {}
    }
}

class SettingsMenu(private val activity: MainActivity, private val parentView: ViewGroup) {
    private var overlay: FrameLayout? = null
    private var confirmationOverlay: FrameLayout? = null
    private var scrollContainer: ScrollView? = null
    private var autoPlayDurationControl: PropertyControl? = null

    fun isOpen(): Boolean = overlay != null && overlay!!.parent != null

    fun getScrollY(): Int {
        return scrollContainer?.scrollY ?: 0
    }

    fun restoreScrollY(y: Int) {
        scrollContainer?.post {
            scrollContainer?.scrollTo(0, y)
        }
    }

    fun cleanup() {
        overlay?.animate()?.cancel()
        confirmationOverlay?.animate()?.cancel()

        PropertyControl.closeActiveMenu()
        autoPlayDurationControl = null

        if (overlay != null && overlay!!.parent != null) {
            parentView.removeView(overlay)
        }
        overlay = null
        confirmationOverlay = null
        scrollContainer = null
    }

    fun show() {
        if (overlay != null) {
            if (overlay!!.parent == null) {
                parentView.addView(overlay, ViewGroup.LayoutParams(-1, -1))
            }
            overlay!!.bringToFront()
            overlay!!.alpha = 1f
            return
        }

        overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            elevation = 500f
            setOnClickListener { dismiss() }
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }

        val dm = activity.resources.displayMetrics
        val targetWidth = (min(dm.widthPixels, dm.heightPixels) * 0.9f).toInt()

        scrollContainer = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                setMargins(20, 50, 20, 50)
            }
            setOnClickListener { /* consume click */ }
            background = getPanelBackground()
            elevation = 510f
            isVerticalScrollBarEnabled = false
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 50, 40, 50)
        }

        // --- TITLE ---
        contentLayout.addView(TextView(activity).apply {
            text = "SETTINGS"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        })

        // --- GENERAL ITEMS ---
        contentLayout.addView(createStyledButton("toggle background") {
            activity.toggleReadability()
        })

        contentLayout.addView(createStyledButton("reset view") {
            activity.globalReset()
            dismiss()
        })

        contentLayout.addView(createStyledButton("reset presets") {
            showConfirmation(
                "RESET ALL PRESETS?",
                "This cannot be undone. All saved presets in slots 1-9 will be permanently replaced with factory defaults."
            ) {
                activity.resetPresetsToDefault()
                dismiss()
            }
        })

        contentLayout.addView(createStyledDivider())

        // --- MIDI CONTROLLER CONNECTION ---
        contentLayout.addView(TextView(activity).apply {
            text = "MIDI CONTROLLER"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        })

        contentLayout.addView(createStyledButton("connect bluetooth midi") {
            showMidiScanner()
        })

        // --- MIDI MAPPING SECTION (Visible only if connected) ---
        if (activity.midiHelper.isConnected) {
            contentLayout.addView(createStyledDivider())

            contentLayout.addView(TextView(activity).apply {
                text = "MIDI MAPPING"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 5)
            })

            contentLayout.addView(TextView(activity).apply {
                text = "CURRENT: \"${activity.midiHelper.mappingName}\""
                textSize = 12f
                setTextColor(Color.GREEN)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            })

            val mapRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply {
                    bottomMargin = 10
                }
            }

            val btnLoad = Button(activity).apply {
                text = "LOAD"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    rightMargin = 15
                }
                setOnClickListener { showLoadOptions() }
            }

            val btnSave = Button(activity).apply {
                text = "SAVE"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = 15
                }
                setOnClickListener { showSaveOptions() }
            }

            mapRow.addView(btnLoad)
            mapRow.addView(btnSave)
            contentLayout.addView(mapRow)
        }

        contentLayout.addView(createStyledDivider())

        // --- AUTO-PLAY SECTION ---
        contentLayout.addView(TextView(activity).apply {
            text = "AUTO-PLAY"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        })

        // Random Toggle
        val randomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { bottomMargin = 10 }
        }
        randomRow.addView(TextView(activity).apply {
            text = "RANDOM ORDER"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val randomCheck = CheckBox(activity).apply {
            isChecked = activity.autoPlayRandom
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnCheckedChangeListener { _, isChecked -> activity.autoPlayRandom = isChecked }
        }
        randomRow.addView(randomCheck)
        contentLayout.addView(randomRow)

        // Preset Filter Row (Grid 1-9)
        contentLayout.addView(TextView(activity).apply {
            text = "INCLUDE PRESETS:"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 5 }
        })

        // Container for the 9 checkboxes
        val filterContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 15
            }
        }

        for (i in 1..9) {
            val cbContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val cb = CheckBox(activity).apply {
                isChecked = activity.autoPlayFilter.contains(i)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                scaleX = 0.8f; scaleY = 0.8f; setPadding(0,0,0,0)
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) activity.autoPlayFilter.add(i) else activity.autoPlayFilter.remove(i)
                    activity.updatePlayButtonState()
                }
            }
            val lbl = TextView(activity).apply { text = "$i"; textSize = 10f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
            cbContainer.addView(lbl); cbContainer.addView(cb)
            filterContainer.addView(cbContainer)
        }
        contentLayout.addView(filterContainer)

        // Auto-Play Duration
        autoPlayDurationControl = PropertyControl(
            "AUTO_DUR", "DURATION",
            min = 0, max = 300000, sliderMax = 60000,
            defaultValue = activity.autoPlayDurationMs.toInt(),
            layoutStyle = PropertyControl.LayoutStyle.ROW,
            includeInPreset = false, hasModulation = false, logPower = 2, showValue = true,
            valueFormatter = { "%.1fs".format(it / 1000f) }
        ) { activity.autoPlayDurationMs = it.toLong() }

        autoPlayDurationControl?.popupElevation = 600f
        autoPlayDurationControl?.attachTo(activity, contentLayout)

        contentLayout.addView(createStyledDivider())

        // --- CLOSE ---
        contentLayout.addView(Button(activity).apply {
            text = "close"
            setTextColor(Color.LTGRAY)
            background = null
            textSize = 16f
            setPadding(0, 30, 0, 0)
            setOnClickListener { dismiss() }
        })

        scrollContainer!!.addView(contentLayout)
        overlay!!.addView(scrollContainer)
        parentView.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        overlay!!.bringToFront()
    }

    private fun getButtonBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#333333"))
            cornerRadius = 15f
            setStroke(1, Color.GRAY)
        }
    }

    private fun showSaveOptions() {
        showCustomDialog("SAVE MAPPING", "Select destination:") { container ->
            container.addView(createStyledButton("save to file") {
                val json = activity.midiHelper.exportConfig()
                val safeName = activity.midiHelper.mappingName.replace("[^a-zA-Z0-9]".toRegex(), "_")
                activity.saveMidiMappingToFile("MidiMap_$safeName.json", json)
                dismissConfirmation()
            })

            container.addView(createStyledButton("copy to clipboard") {
                val json = activity.midiHelper.exportConfig()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("SpaceBeam Map", json)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "Copied to Clipboard", Toast.LENGTH_SHORT).show()
                dismissConfirmation()
            })
        }
    }

    private fun showLoadOptions() {
        showCustomDialog("LOAD MAPPING", "Select source:") { container ->
            container.addView(createStyledButton("load from file") {
                activity.loadMappingLauncher.launch(arrayOf("application/json"))
                dismissConfirmation()
                dismiss() // Close settings to refresh name
            })

            container.addView(createStyledButton("ENTER TEXT") {
                dismissConfirmation()
                // Use the new fullscreen dialog method
                showPasteDialog()
            })
        }
    }

    private fun showPasteDialog() {
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // Root container (Black background)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }

        // Close Button (Top Right)
        val closeBtn = Button(activity).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.GRAY)
            background = null
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 30
                rightMargin = 30
            }
            setOnClickListener {
                dialog.dismiss()
                activity.hideSystemUI()
            }
        }

        // Content Container (Centered)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                leftMargin = 50
                rightMargin = 50
            }
        }

        // Title
        panel.addView(TextView(activity).apply {
            text = "ENTER TEXT"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        // Input Field (Big, Multi-line)
        val inputObj = EditText(activity).apply {
            hint = "Paste JSON code here..."
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 15f
            }
            // Multi-line configuration
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600) // Taller height
            setHorizontallyScrolling(false)
            minLines = 10
        }
        panel.addView(inputObj)

        // Buttons Row
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { topMargin = 40 }
        }

        val pasteBtn = Button(activity).apply {
            text = "PASTE"
            setTextColor(Color.BLACK)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.LTGRAY); cornerRadius = 15f }
            layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply { rightMargin = 20 }
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (clipboard.hasPrimaryClip()) {
                    val text = clipboard.primaryClip?.getItemAt(0)?.text
                    if (text != null) inputObj.setText(text)
                }
            }
        }

        val loadBtn = Button(activity).apply {
            text = "LOAD"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0066CC")); cornerRadius = 15f }
            layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply { leftMargin = 20 }
            setOnClickListener {
                val txt = inputObj.text.toString()
                if (txt.isNotEmpty()) {
                    val success = activity.midiHelper.importConfig(txt)
                    if (success) {
                        Toast.makeText(activity, "Loaded: ${activity.midiHelper.mappingName}", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        dismiss() // Close the underlying settings menu
                        activity.hideSystemUI()
                    } else {
                        Toast.makeText(activity, "Invalid Mapping Data", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnRow.addView(pasteBtn)
        btnRow.addView(loadBtn)
        panel.addView(btnRow)

        root.addView(panel)
        root.addView(closeBtn)

        dialog.setContentView(root)
        dialog.setOnDismissListener { activity.hideSystemUI() }
        // Force soft keyboard mode for visibility
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun showMidiScanner() {
        val scanOverlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(230, 0, 0, 0))
            elevation = 600f
            isClickable = true
            isFocusable = true
        }

        scanOverlay.setOnClickListener {
            activity.midiHelper.stopLeScan()
            this@SettingsMenu.overlay?.removeView(scanOverlay)
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = getPanelBackground()
            setPadding(40,40,40,40)
            isClickable = true
            setOnClickListener { }
        }

        content.addView(TextView(activity).apply { text = "SCANNING FOR MIDI..."; setTextColor(Color.WHITE); textSize=18f; gravity=Gravity.CENTER })

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 600)
            setPadding(0, 20, 0, 20)
        }
        val scroller = ScrollView(activity).apply { addView(listContainer) }
        content.addView(scroller)

        val foundMacs = mutableSetOf<String>()

        activity.midiHelper.onDeviceFound = { device ->
            activity.runOnUiThread {
                if (!foundMacs.contains(device.address)) {
                    foundMacs.add(device.address)
                    val btn = Button(activity).apply {
                        val dName = if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) device.name else "Unknown"
                        text = "${dName ?: "Unknown"}\n${device.address}"

                        setTextColor(Color.LTGRAY)
                        textSize = 12f
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#333333"))
                            setStroke(1, Color.DKGRAY)
                            cornerRadius = 8f
                        }
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin=10 }
                        setOnClickListener {
                            activity.midiHelper.connectToDevice(device)
                            this@SettingsMenu.overlay?.removeView(scanOverlay)
                            // Refresh menu to show mapping section
                            dismiss()
                            show()
                        }
                    }
                    listContainer.addView(btn)
                }
            }
        }

        content.addView(Button(activity).apply {
            text = "CANCEL"
            setTextColor(Color.WHITE)
            background = null
            setOnClickListener {
                activity.midiHelper.stopLeScan()
                this@SettingsMenu.overlay?.removeView(scanOverlay)
            }
        })

        scanOverlay.addView(content)
        overlay?.addView(scanOverlay, ViewGroup.LayoutParams(-1,-1))

        activity.midiHelper.startLeScan()
    }

    private fun showCustomDialog(title: String, subtitle: String, contentFiller: (LinearLayout) -> Unit) {
        if (confirmationOverlay != null) return

        confirmationOverlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            isClickable = true
            elevation = 550f
            setOnClickListener { dismissConfirmation() }
            alpha = 0f
            animate().alpha(1f).setDuration(150).start()
        }

        val dm = activity.resources.displayMetrics
        val targetWidth = (min(dm.widthPixels, dm.heightPixels) * 0.85f).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = getPanelBackground()
            setPadding(40, 40, 40, 40)
            setOnClickListener { }
        }

        panel.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(activity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 30)
        })

        contentFiller(panel)

        val cancelBtn = Button(activity).apply {
            text = "CANCEL"
            setTextColor(Color.GRAY)
            background = null
            setOnClickListener { dismissConfirmation() }
        }
        panel.addView(cancelBtn)

        confirmationOverlay!!.addView(panel)
        overlay!!.addView(confirmationOverlay, ViewGroup.LayoutParams(-1, -1))
    }

    fun dismiss() {
        overlay?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            cleanup()
        }?.start()
    }

    private fun showConfirmation(titleStr: String, messageStr: String, onConfirm: () -> Unit) {
        if (confirmationOverlay != null) return

        confirmationOverlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            isClickable = true
            elevation = 520f
            setOnClickListener { dismissConfirmation() }
            alpha = 0f
            animate().alpha(1f).setDuration(150).start()
        }

        val dm = activity.resources.displayMetrics
        val targetWidth = (min(dm.widthPixels, dm.heightPixels) * 0.85f).toInt()

        val dialogPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = getPanelBackground()
            setPadding(50, 50, 50, 50)
            setOnClickListener { /* consume click */ }
            elevation = 530f
        }

        dialogPanel.addView(TextView(activity).apply {
            text = titleStr
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
        })

        dialogPanel.addView(TextView(activity).apply {
            text = messageStr
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 50)
        })

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val cancelBtn = Button(activity).apply {
            text = "CANCEL"
            setTextColor(Color.LTGRAY)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { dismissConfirmation() }
        }

        val confirmBtn = Button(activity).apply {
            text = "CONFIRM"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA2200"))
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 20 }
            setOnClickListener {
                onConfirm()
                dismissConfirmation()
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(confirmBtn)
        dialogPanel.addView(buttonRow)

        confirmationOverlay!!.addView(dialogPanel)
        overlay!!.addView(confirmationOverlay, ViewGroup.LayoutParams(-1, -1))
        confirmationOverlay!!.bringToFront()
    }

    private fun dismissConfirmation() {
        confirmationOverlay?.animate()?.alpha(0f)?.setDuration(100)?.withEndAction {
            overlay?.removeView(confirmationOverlay)
            confirmationOverlay = null
        }?.start()
    }

    private fun getPanelBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.argb(240, 28, 28, 30))
            cornerRadius = 30f
            setStroke(3, Color.argb(150, 70, 70, 70))
        }
    }

    private fun createStyledButton(textStr: String, action: () -> Unit): Button {
        return Button(activity).apply {
            text = textStr
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110).apply {
                setMargins(0, 15, 0, 15)
            }
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 45, 45, 50))
                cornerRadius = 15f
                setStroke(2, Color.parseColor("#555555"))
            }
            elevation = 10f
            setOnClickListener { action() }
        }
    }

    private fun createStyledDivider(): View {
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(20, 25, 20, 25)
            }
            setBackgroundColor(Color.argb(50, 255, 255, 255))
        }
    }
}

class ExternalDisplayHelper(
    private val context: Context,
    private val renderer: MainActivity.KaleidoscopeRenderer
) {
    private var presentation: CleanFeedPresentation? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()
        override fun onDisplayChanged(displayId: Int) = updatePresentation()
        override fun onDisplayRemoved(displayId: Int) = updatePresentation()
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, null)
        updatePresentation()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
        dismissPresentation()
    }

    private fun dismissPresentation() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.e("ExternalDisplay", "Error dismissing", e)
        }
        presentation = null
    }

    fun updatePresentation() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (displays.isNotEmpty()) {
            val externalDisplay = displays[0]

            // If we have a presentation but it's for a different display, kill it
            if (presentation != null && presentation!!.display.displayId != externalDisplay.displayId) {
                dismissPresentation()
            }

            // Create new if null
            if (presentation == null) {
                presentation = CleanFeedPresentation(context, externalDisplay, renderer)
                try {
                    presentation?.show()
                } catch (e: Exception) {
                    Log.e("ExternalDisplay", "Failed to show presentation", e)
                    presentation = null
                }
            }
        } else {
            dismissPresentation()
            renderer.removeExternalSurface()
        }
    }

    private class CleanFeedPresentation(
        ctx: Context,
        display: Display,
        val renderer: MainActivity.KaleidoscopeRenderer
    ) : Presentation(ctx, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val surfaceView = SurfaceView(context)
            setContentView(surfaceView)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    // Force the renderer to use the new surface
                    renderer.setExternalSurface(holder.surface, display.width, display.height)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    renderer.setExternalSurface(holder.surface, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // Important: only remove if this is still the active surface
                    renderer.removeExternalSurface()
                }
            })
        }
    }
}


open class PropertyControl(
    val id: String,
    val label: String,
    val min: Int = 0,
    val max: Int = 1000,
    val sliderMax: Int = max,
    val defaultValue: Int = 0,
    val outMin: Float = 0.0f,
    val outMax: Float = 1.0f,
    val hasModulation: Boolean = false,
    val modMode: ModMode = ModMode.CLAMP,
    val layoutStyle: LayoutStyle = LayoutStyle.STACKED,
    val iconResId: Int? = null,
    val includeInPreset: Boolean = true,
    val logPower: Int = 1,
    val showValue: Boolean = false,
    val valueSuffix: String = "",
    val allowSmoothing: Boolean = true,
    val defaultLocked: Boolean = false,
    val valueFormatter: ((Int) -> String)? = null,
    private val onValueChanged: ((Int) -> Unit)? = null
)
{
    enum class ModMode { WRAP, CLAMP }
    enum class WaveShape { SINE, TRIANGLE, RAMP, WOBBLE_SINE, RANDOM_SMOOTH, RANDOM_STEP }
    enum class LayoutStyle { STACKED, ROW }

    var popupElevation: Float = 40f
    private var rootLayout: View? = null

    companion object {
        var activeControl: PropertyControl? = null
        fun closeActiveMenu() {
            activeControl?.closeMenu()
        }
    }

    // --- STATE VARIABLES ---
    @Volatile var value: Int = defaultValue
        private set
    @Volatile var preciseValue: Float = defaultValue.toFloat()
        private set

    // Locking State
    var isLocked: Boolean = defaultLocked
    var subtitle: String? = null
    private var lockButton: Button? = null

    // Default Smoothing: 50%
    var smoothing: Int = 500

    // Base value (slider position) smoothed over time
    private var smoothedNormalized: Float = 0f

    // Final Output (Base + LFO)
    @Volatile private var modulatedNormalized: Float = 0f

    // LFO Smoothing State
    private var smoothedModRate: Float = 200f
    private var smoothedModDepth: Float = 0f

    // Animation / LFO State
    private var animTarget: Float? = null
    private var animStart: Float = 0f
    private var animDuration: Float = 0f
    private var animTime: Float = 0f
    private var isAnimating = false

    // Transition Crossfade State
    private var isTransitioning = false
    private var transitionStartVal: Float = 0f
    private var transitionTotalTime: Float = 0f
    private var transitionElapsed: Float = 0f

    var modRate: Int = 200
    var modDepth: Int = 0
    var modShape: WaveShape = if (modMode == ModMode.WRAP) WaveShape.RAMP else WaveShape.SINE

    var preciseModRate: Float = 200f
    var preciseModDepth: Float = 0f
    var lfoPhase: Double = 0.0
    private var noiseValA: Float = Math.random().toFloat()
    private var noiseValB: Float = Math.random().toFloat()

    private var modRateStart = 0f
    private var modRateTarget: Float? = null
    private var modDepthStart = 0f
    private var modDepthTarget: Float? = null

    // UI References
    private var sliderView: SliderBox? = null
    private var modIndicator: View? = null
    private var mainRowLayout: LinearLayout? = null
    protected var floatingPanel: LinearLayout? = null
    private var modPanelSpeedSeekBar: SeekBar? = null
    private var modPanelDepthSeekBar: SeekBar? = null
    private var liveValueDisplay: TextView? = null
    private var baseValueInput: EditText? = null
    private var shapeBtn: Button? = null
    protected var currentContext: Context? = null

    // Interaction Flags
    private var isRateDragging = false
    private var isDepthDragging = false

    // --- OPTIMIZATION FLAGS ---
    // Prevent flooding the UI thread with redundant posts
    private var lastDisplayedValue: Int = -Int.MAX_VALUE
    private var lastSyncedModRate: Int = -1
    private var lastSyncedModDepth: Int = -1
    private var lastSyncedSmoothing: Int = -1

    init {
        val ratio = (defaultValue.toFloat() / sliderMax.toFloat()).coerceAtLeast(0f)
        smoothedNormalized = ratio
        modulatedNormalized = ratio
    }

    val computedValue: Float
        get() {
            return outMin + (modulatedNormalized * (outMax - outMin))
        }

    data class Snapshot(val value: Int, val active: Boolean, val rate: Int, val depth: Int, val shape: String, val smoothing: Int)

    fun getSnapshot(): Snapshot = Snapshot(value, modDepth > 0, modRate, modDepth, modShape.name, smoothing)

    fun restore(s: Snapshot, durationSec: Float) {
        if (isLocked) return

        transitionStartVal = modulatedNormalized
        transitionTotalTime = durationSec
        transitionElapsed = 0f
        isTransitioning = true

        animateTo(s.value.toFloat(), durationSec, s.shape)
        if (hasModulation) {
            animateModulation(s.rate.toFloat(), s.depth.toFloat(), durationSec)
        }
        this.smoothing = s.smoothing
    }

    fun animateTo(target: Float, durationSec: Float, newShape: String? = null) {
        animTarget = target
        animStart = preciseValue
        animDuration = durationSec
        animTime = 0f
        isAnimating = true
        if (newShape != null) {
            try {
                modShape = WaveShape.valueOf(newShape)
                shapeBtn?.text = modShape.name
            } catch (e: Exception) {}
        }
    }

    fun animateModulation(targetRate: Float, targetDepth: Float, durationSec: Float) {
        modRateStart = preciseModRate
        modRateTarget = targetRate
        modDepthStart = preciseModDepth
        modDepthTarget = targetDepth
    }

    fun update(deltaTime: Float) {
        val t = if (isAnimating && animDuration > 0) (animTime / animDuration).coerceIn(0f, 1f) else 1f
        val ease = 1f - (1f - t).toDouble().pow(3.0).toFloat()

        if (isAnimating && animTarget != null) {
            animTime += deltaTime
            val newValueInt: Int
            if (animTime >= animDuration) {
                preciseValue = animTarget!!
                newValueInt = preciseValue.toInt()
                modRateTarget?.let { preciseModRate = it; modRate = it.toInt() }
                modDepthTarget?.let { preciseModDepth = it; modDepth = it.toInt() }
                modRateTarget = null; modDepthTarget = null
                isAnimating = false
            } else {
                // SPECIAL CASE: Modular lerp for ANGLE parameters to prevent 360->0 spinning
                if (id.endsWith("_ANGLE")) {
                    val diff = animTarget!! - animStart
                    val modDiff = ((diff + 500f) % 1000f + 1000f) % 1000f - 500f
                    preciseValue = (animStart + modDiff * ease + 1000f) % 1000f
                } else {
                    preciseValue = animStart + (animTarget!! - animStart) * ease
                }
                newValueInt = preciseValue.toInt()
                modRateTarget?.let { preciseModRate = modRateStart + (it - modRateStart) * ease }
                modDepthTarget?.let { preciseModDepth = modDepthStart + (it - modDepthStart) * ease }
            }
            if (newValueInt != value) {
                value = newValueInt
                onValueChanged?.invoke(value)
            }
        }

        val baseLerp = if (smoothing == 0 || !allowSmoothing) 1.0f else {
            val s = smoothing / 1000f
            val speed = 10.0f * (1.0f - s) * (1.0f - s) + 0.1f
            (speed * deltaTime).coerceIn(0f, 1f)
        }

        val targetNormalized = (preciseValue / sliderMax.toFloat()).coerceAtLeast(0f)

        // Applying Shortest-Path Smoothing for ANGLE even outside of animations (for touch)
        if (isAnimating || baseLerp >= 1.0f) {
            smoothedNormalized = targetNormalized
        } else {
            if (id.endsWith("_ANGLE")) {
                val diff = targetNormalized - smoothedNormalized
                val modDiff = ((diff + 0.5f) % 1.0f + 1.0f) % 1.0f - 0.5f
                smoothedNormalized = ((smoothedNormalized + modDiff * baseLerp) + 1.0f) % 1.0f
            } else {
                smoothedNormalized += (targetNormalized - smoothedNormalized) * baseLerp
            }
        }

        smoothedModRate += (preciseModRate - smoothedModRate) * baseLerp
        smoothedModDepth += (preciseModDepth - smoothedModDepth) * baseLerp

        var currentCalculatedOutput = smoothedNormalized

        if (hasModulation && (smoothedModRate > 1f || smoothedModDepth > 1f)) {
            val baseSpeed = (smoothedModRate / 1000f + 0.05f).toDouble().pow(3.0).toFloat()
            lfoPhase += baseSpeed * deltaTime * 2.0 * Math.PI

            if (lfoPhase > 2.0 * Math.PI) {
                lfoPhase -= 2.0 * Math.PI
                noiseValA = noiseValB
                noiseValB = Math.random().toFloat()
            }

            val rawWave: Double = when (modShape) {
                WaveShape.SINE -> sin(lfoPhase) * 0.5 + 0.5
                WaveShape.TRIANGLE -> { val p = (lfoPhase / (2.0 * Math.PI)); if (p < 0.5) p * 2.0 else 2.0 - (p * 2.0) }
                WaveShape.RAMP -> (lfoPhase / (2.0 * Math.PI)) % 1.0
                WaveShape.WOBBLE_SINE -> { val w = sin(lfoPhase + sin(lfoPhase)); w * 0.5 + 0.5 }
                WaveShape.RANDOM_SMOOTH -> {
                    val progress = (lfoPhase / (2.0 * Math.PI)).toFloat()
                    val smoothT = (1.0 - cos(progress * Math.PI)) * 0.5
                    (noiseValA * (1.0 - smoothT) + noiseValB * smoothT)
                }
                WaveShape.RANDOM_STEP -> noiseValA.toDouble()
            }

            val depthNorm = (smoothedModDepth / 1000f).toDouble().pow(2.0).toFloat()

            if (modMode == ModMode.WRAP) {
                currentCalculatedOutput = (smoothedNormalized + (rawWave.toFloat() * depthNorm)) % 1.0f
            } else {
                currentCalculatedOutput = (smoothedNormalized * (1.0f - depthNorm)) + (rawWave.toFloat() * depthNorm)
            }
        }

        if (isTransitioning) {
            transitionElapsed += deltaTime
            if (transitionElapsed >= transitionTotalTime) {
                isTransitioning = false
                modulatedNormalized = currentCalculatedOutput
            } else {
                val progress = (transitionElapsed / transitionTotalTime).coerceIn(0f, 1f)
                val fadeT = progress * progress * (3.0f - 2.0f * progress)

                if (id.endsWith("_ANGLE")) {
                    val diff = currentCalculatedOutput - transitionStartVal
                    val modDiff = ((diff + 0.5f) % 1.0f + 1.0f) % 1.0f - 0.5f
                    modulatedNormalized = ((transitionStartVal + modDiff * fadeT) + 1.0f) % 1.0f
                } else {
                    modulatedNormalized = transitionStartVal + (currentCalculatedOutput - transitionStartVal) * fadeT
                }
            }
        } else {
            modulatedNormalized = currentCalculatedOutput
        }

        syncUiElements()
        modIndicator?.postInvalidate()

        val displayVal = if (logPower > 1) {
            (modulatedNormalized.toDouble().pow(1.0/logPower) * sliderMax).roundToInt()
        } else {
            (modulatedNormalized * sliderMax).roundToInt()
        }
        updateLiveValueUI(displayVal)
    }

    private fun syncUiElements() {
        // SliderBox uses postInvalidate(), safe to call frequently
        if (sliderView != null) {
            val visualT = if (logPower > 1) smoothedNormalized.toDouble().pow(1.0/logPower).toFloat() else smoothedNormalized
            sliderView!!.setVisualState(visualT, formatValue(value))
        }

        // Dirty Checks: Only post to UI thread if values actually changed visually
        if (activeControl == this) {
            // NEW: Update Big Base Value Indicator if not focused
            if (baseValueInput != null && !baseValueInput!!.hasFocus()) {
                val currentText = baseValueInput!!.text.toString()
                if (currentText != value.toString()) {
                    baseValueInput!!.post {
                        if (!baseValueInput!!.hasFocus()) {
                            baseValueInput!!.setText(value.toString())
                        }
                    }
                }
            }

            // SeekBars - Only post if value changed
            val curRate = smoothedModRate.toInt()
            if (curRate != lastSyncedModRate && !isRateDragging) {
                lastSyncedModRate = curRate
                modPanelSpeedSeekBar?.post { modPanelSpeedSeekBar?.progress = curRate }
            }

            val curDepth = smoothedModDepth.toInt()
            if (curDepth != lastSyncedModDepth && !isDepthDragging) {
                lastSyncedModDepth = curDepth
                modPanelDepthSeekBar?.post { modPanelDepthSeekBar?.progress = curDepth }
            }

            val curSmooth = smoothing
            if (curSmooth != lastSyncedSmoothing) {
                lastSyncedSmoothing = curSmooth
                floatingPanel?.findViewWithTag<SeekBar>("SMOOTH_SEEK")?.let {
                    if (!it.isPressed) it.post { it.progress = curSmooth }
                }
            }
        }
    }

    private fun formatValue(v: Int): String {
        return if (valueFormatter != null) valueFormatter!!(v) else "$v$valueSuffix"
    }

    private fun updateLiveValueUI(v: Int) {
        // Dirty Check: Only Post if text value changed
        if (activeControl == this && liveValueDisplay != null) {
            if (v != lastDisplayedValue) {
                lastDisplayedValue = v
                liveValueDisplay?.post { liveValueDisplay?.text = formatValue(v) }
            }
        }
    }

    fun setProgress(v: Int) {
        if (isAnimating) stopAnimation()
        val clamped = v.coerceIn(min, max)
        value = clamped
        preciseValue = clamped.toFloat()
        onValueChanged?.invoke(clamped)

        // Explicitly update the text field immediately for +/- button responsiveness
        if (activeControl == this && baseValueInput != null && !baseValueInput!!.hasFocus()) {
            baseValueInput!!.setText(clamped.toString())
        }
    }

    fun updateModRate(v: Int) {
        modRate = v.coerceIn(0, 1000)
        preciseModRate = modRate.toFloat()
    }

    fun updateModDepth(v: Int) {
        modDepth = v.coerceIn(0, 1000)
        preciseModDepth = modDepth.toFloat()
        updateIndicatorVisuals()
    }

    fun updateSmoothing(v: Int) {
        smoothing = v.coerceIn(0, 1000)
    }

    fun reset() {
        stopAnimation()
        isTransitioning = false
        setProgress(defaultValue)

        val ratio = (defaultValue.toFloat() / sliderMax.toFloat()).coerceAtLeast(0f)
        smoothedNormalized = ratio
        modulatedNormalized = ratio

        if (hasModulation) {
            updateModRate(200)
            updateModDepth(0)
            smoothedModRate = 200f
            smoothedModDepth = 0f

            updateSmoothing(500)
            modShape = if (modMode == ModMode.WRAP) WaveShape.RAMP else WaveShape.SINE
            updateIndicatorVisuals()
            shapeBtn?.text = modShape.name
        }
    }

    fun stopAnimation() {
        isAnimating = false; animTarget = null; modRateTarget = null; modDepthTarget = null; isTransitioning = false
    }

    fun detach() {
        sliderView = null; modIndicator = null; mainRowLayout = null
        if (activeControl == this) closeMenu()
        currentContext = null
    }

    fun removeFromParent() {
        detach()
        if (rootLayout != null && rootLayout?.parent != null) {
            (rootLayout?.parent as? ViewGroup)?.removeView(rootLayout)
        }
        rootLayout = null
    }

    fun attachTo(context: Context, parent: ViewGroup) {
        detach()
        currentContext = context

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        rootLayout = container

        // --- LABEL BOX ---
        val labelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 12f
            }
            isClickable = true
            setOnClickListener { toggleMenu(context) }
            setOnLongClickListener {
                (context as? MainActivity)?.showMidiLearnOverlay(this@PropertyControl.id, this@PropertyControl.label)
                true
            }
            layoutParams = LinearLayout.LayoutParams(210, 70).apply { rightMargin = 12 }
        }

        if (iconResId != null) {
            labelContainer.addView(ImageView(context).apply {
                setImageResource(iconResId)
                setColorFilter(Color.WHITE)
                alpha = 0.8f
                layoutParams = LinearLayout.LayoutParams(36, 36).apply { rightMargin = 8 }
            })
        }

        labelContainer.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            alpha = 0.9f
            gravity = Gravity.CENTER
        })
        container.addView(labelContainer)

        // --- CUSTOM SLIDER BOX ---
        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, 70, 1f) // Matches label height
        }
        mainRowLayout = sliderRow

        val ratio = (value.toFloat() / sliderMax.toFloat()).coerceIn(0f, 1f)
        val initialT = if (logPower > 1) ratio.toDouble().pow(1.0/logPower).toFloat() else ratio

        // Create Custom Slider View
        sliderView = SliderBox(context).apply {
            setVisualState(initialT, formatValue(value))
            // Apply margins to mimic original SeekBar padding so it doesn't look too wide/touch edges
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(15, 0, 15, 0)
            }
        }
        sliderRow.addView(sliderView)

        // --- LFO INDICATOR ---
        if (hasModulation) {
            modIndicator = object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f; val r = (Math.min(width, height) / 2f) - 2f
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.WHITE; paint.alpha = 255
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL; paint.color = if (modDepth > 0) Color.WHITE else Color.LTGRAY; paint.alpha = if (modDepth > 0) 255 else 100
                    var dotRadius = r * 0.3f
                    if (modDepth > 0) {
                        val normDiff = modulatedNormalized - (preciseValue / sliderMax.toFloat())
                        dotRadius = (r * (0.3 + (abs(normDiff) * 3.0))).toFloat().coerceAtMost(r)
                    }
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(55, 55).apply { leftMargin = 15 }
                setOnClickListener { toggleMenu(context) }
            }
            sliderRow.addView(modIndicator)
        }
        container.addView(sliderRow)
        parent.addView(container)
    }

    private fun updateIndicatorVisuals() { modIndicator?.postInvalidate() }

    fun toggleMenu(ctx: Context? = currentContext) {
        if (activeControl == this) closeMenu() else { activeControl?.closeMenu(); if (ctx != null) openMenu(ctx) }
    }

    fun closeMenu() {
        if (floatingPanel != null) {
            val ctx = currentContext ?: return
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            baseValueInput?.windowToken?.let { imm?.hideSoftInputFromWindow(it, 0) }
            (floatingPanel?.parent as? ViewGroup)?.removeView(floatingPanel)
            floatingPanel = null; modPanelSpeedSeekBar = null; modPanelDepthSeekBar = null; liveValueDisplay = null; baseValueInput = null; shapeBtn = null; lockButton = null
            (ctx as? MainActivity)?.hideSystemUI()
        }
        if (activeControl == this) activeControl = null
    }

    open fun openMenu(context: Context) {
        val activity = context as? MainActivity ?: return
        val rootLayout = activity.overlayHUD
        val dm = context.resources.displayMetrics
        val isPortrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        floatingPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 30, 10, 30)
            background = GradientDrawable().apply { setColor(Color.argb(245, 15, 15, 15)); cornerRadius = 20f; setStroke(2, Color.GRAY) }
            elevation = popupElevation; isClickable = true
            layoutParams = if (isPortrait) FrameLayout.LayoutParams(700, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (dm.heightPixels * 0.40).toInt() + 20
                bottomMargin = 40
            }
            else FrameLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                leftMargin = 880
                topMargin = 40
                bottomMargin = 40
            }
        }

        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isVerticalScrollBarEnabled = false
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 } }

        val titleTextContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleTextContainer.addView(TextView(context).apply {
            text = label; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.LTGRAY)
        })

        if (subtitle != null) {
            titleTextContainer.addView(TextView(context).apply {
                text = subtitle
                textSize = 10f
                setTextColor(Color.GRAY)
                // --- Marquee Setup ---
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1 // Infinite scroll
                isFocusable = true
                isFocusableInTouchMode = true
                isSelected = true // Required to trigger the marquee animation
                // ---------------------
                setPadding(0, 2, 20, 0) // Extra right padding so it doesn't touch the lock button
            })
        }
        titleRow.addView(titleTextContainer)

        lockButton = Button(context).apply {
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0,0,0,0)
            layoutParams = LinearLayout.LayoutParams(180, 70)
            setOnClickListener {
                isLocked = !isLocked
                updateLockButtonVisuals()
            }
        }
        updateLockButtonVisuals()
        titleRow.addView(lockButton)

        contentLayout.addView(titleRow)

        val numRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, 140).apply { bottomMargin = 10 } }
        val btnDec = createNumButton(context, "-") { setProgress(value - 1) }

        baseValueInput = EditText(context).apply {
            setText(value.toString())
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = null
            includeFontPadding = false
            setPadding(0, 10, 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE; layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val num = v.text.toString().toIntOrNull() ?: value; setProgress(num); v.clearFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0); (context as? MainActivity)?.hideSystemUI()
                    true
                } else false
            }
        }
        val btnInc = createNumButton(context, "+") { setProgress(value + 1) }
        numRow.addView(btnDec); numRow.addView(baseValueInput); numRow.addView(btnInc)
        contentLayout.addView(numRow)

        if (hasModulation) {
            val liveRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 } }
            liveValueDisplay = TextView(context).apply {
                text = formatValue(value)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.LTGRAY)
                setPadding(0, 10, 0, 5)
            }
            liveRow.addView(liveValueDisplay)
            contentLayout.addView(liveRow)

            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 2).apply { bottomMargin = 20 }; setBackgroundColor(Color.DKGRAY) })

            shapeBtn = Button(context).apply {
                text = modShape.name; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply { setColor(Color.parseColor("#444444")); cornerRadius = 10f; setStroke(1, Color.GRAY) }
                layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 25 }
                setOnClickListener { modShape = WaveShape.values()[(modShape.ordinal + 1) % WaveShape.values().size]; text = modShape.name }
            }
            contentLayout.addView(shapeBtn)

            modPanelSpeedSeekBar = addSliderToPanel(context, contentLayout, "SPEED", modRate) { updateModRate(it) }
            modPanelSpeedSeekBar?.setOnTouchListener { v, event ->
                if(event.action == MotionEvent.ACTION_DOWN) isRateDragging = true
                if(event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) isRateDragging = false
                v.onTouchEvent(event); true
            }

            modPanelDepthSeekBar = addSliderToPanel(context, contentLayout, "DEPTH", modDepth) { updateModDepth(it) }
            modPanelDepthSeekBar?.setOnTouchListener { v, event ->
                if(event.action == MotionEvent.ACTION_DOWN) isDepthDragging = true
                if(event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) isDepthDragging = false
                v.onTouchEvent(event); true
            }

            val smoothSb = addSliderToPanel(context, contentLayout, "SMOOTH", smoothing) { updateSmoothing(it) }
            smoothSb.tag = "SMOOTH_SEEK"

            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 20) })
        }

        contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 10) })
        val resetBtn = Button(context).apply {
            text = "RESET"; textSize = 14f; setTextColor(Color.LTGRAY); includeFontPadding = false; setPadding(0, 0, 0, 0); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(2, Color.DKGRAY); cornerRadius = 12f }
            layoutParams = LinearLayout.LayoutParams(-1, 110).apply { bottomMargin = 10; topMargin = 5 }
            setOnClickListener { reset() }
        }
        contentLayout.addView(resetBtn)

        addExtraControls(contentLayout, context)

        scroller.addView(contentLayout)
        floatingPanel?.addView(scroller)
        rootLayout.addView(floatingPanel)
        activeControl = this
    }

    protected fun addSliderToPanel(ctx: Context, parent: ViewGroup, name: String, current: Int, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 5, 0, 5)
        }
        val mapMode = when(name) {
            "SPEED" -> "RATE"
            "DEPTH" -> "DEPTH"
            else -> null
        }
        val labelView = TextView(ctx).apply {
            text = name
            textSize = 10f
            setTextColor(Color.LTGRAY)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.MATCH_PARENT)
            if (mapMode != null) {
                isClickable = true
                setOnLongClickListener {
                    (ctx as? MainActivity)?.showMidiLearnOverlay(
                        "${this@PropertyControl.id}|$mapMode",
                        "${this@PropertyControl.label} $name"
                    )
                    true
                }
            }
        }
        row.addView(labelView)
        val sb = SeekBar(ctx).apply {
            max = 1000
            progress = current
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            setPadding(0, 0, 0, 0)
            thumbOffset = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(0, 0, 0, 0)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(sb)
        parent.addView(row) // Adds directly to the specified parent container
        return sb
    }

    private fun updateLockButtonVisuals() {
        lockButton?.text = if (isLocked) "LOCKED" else "UNLOCKED"
        lockButton?.setTextColor(if (isLocked) Color.parseColor("#FF6666") else Color.parseColor("#66FF66"))
        lockButton?.background = GradientDrawable().apply {
            setColor(Color.parseColor("#333333"))
            setStroke(2, if (isLocked) Color.parseColor("#AA3333") else Color.parseColor("#33AA33"))
            cornerRadius = 10f
        }
    }

    open fun addExtraControls(panel: LinearLayout, context: Context) {}
    protected fun createNumButton(ctx: Context, txt: String, action: () -> Unit): Button {
        return Button(ctx).apply { text = txt; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 15f; setStroke(1, Color.GRAY) }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(5, 5, 5, 5) }
            setOnClickListener { action() } }
    }

    // --- INNER CLASS: Custom SliderBox View ---
    private inner class SliderBox(context: Context) : View(context) {
        private var visualProgress = 0f
        private var displayText = ""
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#222222")
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCCCCC") // Light Grey Fill
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f // Will be adjusted in onSizeChanged if needed
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val cornerRadius = 12f

        fun setVisualState(p: Float, text: String) {
            visualProgress = p.coerceIn(0f, 1f)
            displayText = text
            postInvalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    stopAnimation()
                    if (activeControl != null && activeControl != this@PropertyControl) closeActiveMenu()
                    updateFromTouch(event.x)
                }
                MotionEvent.ACTION_MOVE -> {
                    updateFromTouch(event.x)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        private fun updateFromTouch(x: Float) {
            val w = width.toFloat()
            if (w <= 0) return
            val t = (x / w).coerceIn(0f, 1f)
            // Apply curve logic matching PropertyControl's logic
            val curvedT = if (logPower > 1) t.toDouble().pow(logPower.toDouble()).toFloat() else t
            val calcVal = (curvedT * sliderMax).toInt().coerceIn(min, max)

            // Update PropertyControl
            value = calcVal
            preciseValue = calcVal.toFloat()
            onValueChanged?.invoke(calcVal)

            // Update local visual immediately for responsiveness
            visualProgress = t
            displayText = formatValue(calcVal)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val box = RectF(0f, 0f, w, h)

            // 1. Background
            canvas.drawRoundRect(box, cornerRadius, cornerRadius, bgPaint)

            // 2. Fill (Clipped to progress)
            val fillW = w * visualProgress
            if (fillW > 0) {
                canvas.save()
                canvas.clipRect(0f, 0f, fillW, h)
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, fillPaint)
                canvas.restore()
            }

            // 3. Border
            canvas.drawRoundRect(box, cornerRadius, cornerRadius, strokePaint)

            // 4. Text - Center coordinates
            val cx = w / 2f
            val cy = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)

            // 5. Draw Text (Double Draw for Contrast)
            // Pass 1: White text on Dark Background (Right Side)
            canvas.save()
            canvas.clipRect(fillW, 0f, w, h)
            textPaint.color = Color.WHITE
            canvas.drawText(displayText, cx, cy, textPaint)
            canvas.restore()

            // Pass 2: Black text on Light Fill (Left Side)
            canvas.save()
            canvas.clipRect(0f, 0f, fillW, h)
            textPaint.color = Color.BLACK
            canvas.drawText(displayText, cx, cy, textPaint)
            canvas.restore()
        }
    }
}


// --- MAIN ACTIVITY ---
class MainActivity : AppCompatActivity() {
    private var fpsTextView: TextView? = null
    fun updateFpsUI(fps: Int) {
        fpsTextView?.text = "FPS: $fps"
    }

    private var activeShaderInput: EditText? = null
    private var currentShaderName = "Custom GLSL"

    val shaderFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val code = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (code != null) {
                    activeShaderInput?.setText(code)
                    // UPDATE NAME FROM FILE
                    currentShaderName = getFileNameFromUri(uri)
                }
            } catch(e: Exception) {
                Toast.makeText(this, "Failed to load shader file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val BUILTIN_SHADERS = mapOf(
        "Matrix Rain" to """
        #define SPEED 4.0
        #define DENSITY 25.0
        #define COLOR vec3(0.2, 1.0, 0.3)
        #define ROT_FREQ 0.2
        #define ROT_AMOUNT 0.15
        
        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = U / iResolution.y;
            
            float angle = sin(iTime * ROT_FREQ) * ROT_AMOUNT;
            vec2 center = vec2(iResolution.x / iResolution.y * 0.5, 0.5);
            uv -= center;
            uv *= rot(angle);
            uv += center;

            uv.x *= DENSITY;
            
            float id = floor(uv.x);
            float offset = fract(sin(id * 34.23) * 543.21);
            uv.y = uv.y * 5.0 + iTime * SPEED * (0.5 + offset);
            
            vec2 cell = fract(uv) - 0.5;
            float drop = smoothstep(0.3, 0.0, abs(cell.x));
            float tail = smoothstep(1.0, 0.0, fract(uv.y));
            
            float hash = fract(sin(floor(uv.y) * 23.4 + id) * 43.1);
            float active = smoothstep(0.85, 1.0, hash);
            
            O = vec4(COLOR * drop * tail * active, 1.0);
        }
    """.trimIndent(),

        "Magnetic Fluid" to """
        #define SPEED 0.3
        #define SCALE 3.0
        #define COLOR vec3(0.9, 0.4, 0.1)

        void mainImage(out vec4 O, in vec2 U) {
            vec2 p = U / iResolution.y * SCALE;
            for(int i = 1; i < 4; i++) {
                vec2 newp = p;
                newp.x += 0.6 / float(i) * sin(float(i) * p.y + iTime * SPEED + 0.3);
                newp.y += 0.6 / float(i) * cos(float(i) * p.x + iTime * SPEED + 0.3);
                p = newp;
            }
            float val = cos(p.x + p.y);
            float glow = smoothstep(0.85, 1.0, val) + smoothstep(0.95, 1.0, val) * 2.0;
            O = vec4(COLOR * glow, 1.0);
        }
    """.trimIndent(),

        "Cosmic Pulse" to """
        #define SPEED 1.0
        #define COLOR_A vec3(0.1, 0.5, 0.9)
        #define COLOR_B vec3(0.9, 0.2, 0.5)
        #define INTENSITY 0.015

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U * 2.0 - iResolution.xy) / min(iResolution.x, iResolution.y);
            vec3 col = vec3(0.0);
            float d = length(uv);
            
            for(float i = 0.0; i < 3.0; i++) {
                vec2 p = fract(uv * (1.5 + i * 0.2)) - 0.5;
                float a = atan(p.y, p.x);
                float r = length(p) + sin(a * 4.0 + iTime * 0.5) * 0.1; 
                float glow = INTENSITY / (abs(sin(r * 8.0 - iTime * SPEED)) + 0.01);
                col += mix(COLOR_A, COLOR_B, d) * glow;
            }
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Wireframe Grid" to """
        #define SPEED 0.5
        #define GRID_SIZE 8.0
        #define LINE_WIDTH 0.05
        #define WARP_STRENGTH 0.3
        #define COLOR vec3(0.7, 0.1, 0.9)
        #define ROT_FREQ 0.15
        #define ROT_AMOUNT 0.4
        
        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - iResolution.xy * 0.5) / iResolution.y;
            uv *= rot(sin(iTime * ROT_FREQ) * ROT_AMOUNT);
            uv *= 1.0 + sin(iTime * SPEED + length(uv) * 3.0) * WARP_STRENGTH; 
            vec2 g = fract(uv * GRID_SIZE) - 0.5;
            float line = min(abs(g.x), abs(g.y));
            float glow = LINE_WIDTH / (line + 0.01);
            O = vec4(COLOR * glow, 1.0);
        }
    """.trimIndent(),

        "Monochrome Distortion" to """
        #define SPEED 0.4

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = U / iResolution.y;
            float t = iTime * SPEED;
            
            // Base asymmetric warping
            vec2 p = uv * 3.0;
            p.x += sin(p.y * 2.0 + t) * 1.5;
            p.y += cos(p.x * 2.2 - t * 0.8) * 1.5;
            
            // Secondary chaotic warping for jagged details
            p.x += sin(p.y * 4.5 + t * 1.5) * 0.5;
            p.y += cos(p.x * 4.1 - t * 1.2) * 0.5;
            
            // Topographical zebra stripes
            float stripes = sin(p.x * 5.0 + p.y * 3.0);
            
            // Sharp threshold for pure black and white, with minimal anti-aliasing
            float col = smoothstep(0.0, 0.05, stripes);
            
            // Carve out asymmetric black voids and white clumps
            float clumps = cos(p.x * 2.0 - p.y * 2.0);
            col *= smoothstep(-0.2, 0.0, clumps);
            
            O = vec4(vec3(col), 1.0);
        }
    """.trimIndent(),

        "Kaleidoscope Core" to """
        #define SPEED 1.2
        #define LAYERS 6.0
        #define COLOR vec3(0.0, 0.8, 1.0)
        
        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            vec3 col = vec3(0.0);
            
            for(float i = 0.0; i < LAYERS; i++) {
                vec2 p = uv;
                p *= rot(iTime * 0.2 + i * 0.5);
                float scale = mod(iTime * SPEED - i * (3.0 / LAYERS), 3.0);
                p *= scale * 1.5;
                
                float a = atan(p.y, p.x);
                float r = length(p);
                
                float shape = abs(cos(a * 3.0) * sin(a * 2.0)) * 0.5 + 0.5;
                float dist = abs(r - shape);
                
                float glow = 0.02 / (dist + 0.01);
                col += COLOR * glow * smoothstep(3.0, 0.0, scale);
            }
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Void Eclipse" to """
        #define SPEED 0.4
        #define RADIUS 0.25
        #define GLOW_INTENSITY 0.03
        #define COLOR vec3(1.0, 0.3, 0.05)
        #define SECONDARY_COLOR vec3(0.2, 0.5, 1.0)

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            float t = iTime * SPEED;
            
            float aspect = iResolution.x / iResolution.y;
            
            vec2 c1;
            c1.x = sin(t * 0.8) * (aspect * 0.5 + 0.2) + cos(t * 0.3) * 0.3;
            c1.y = cos(t * 0.6) * (0.5 + 0.2) + sin(t * 0.4) * 0.2;
            
            vec2 c2;
            c2.x = cos(t * 0.5 + 1.0) * (aspect * 0.5 + 0.2) + sin(t * 0.7) * 0.3;
            c2.y = sin(t * 0.9 - 2.0) * (0.5 + 0.2) + cos(t * 0.2) * 0.2;
            
            vec2 l1 = uv - c1;
            vec2 l2 = uv - c2;
            
            float a1 = atan(l1.y, l1.x);
            float r1 = length(l1);
            float a2 = atan(l2.y, l2.x);
            float r2 = length(l2);
            
            float w1 = sin(a1 * 2.0 + t * 2.0) * 0.04 + cos(a1 * 5.0 - t * 1.5) * 0.02;
            float w2 = sin(a2 * 3.0 - t * 1.8) * 0.04 + cos(a2 * 4.0 + t * 2.1) * 0.02;
            
            float rw1 = r1 + w1;
            float rw2 = r2 + w2;
            
            float d1 = abs(rw1 - RADIUS);
            float d2 = abs(rw2 - RADIUS);
            
            float m1 = smoothstep(RADIUS - 0.02, RADIUS + 0.02, rw1);
            float m2 = smoothstep(RADIUS - 0.02, RADIUS + 0.02, rw2);
            float combinedMask = m1 * m2;
            
            float f1 = sin(a1 - t * 2.0) * 0.5 + 0.5;
            float f2 = sin(a2 + t * 1.5) * 0.5 + 0.5;
            
            float g1 = GLOW_INTENSITY / (d1 + 0.005) * (0.8 + f1 * 1.2);
            float g2 = GLOW_INTENSITY / (d2 + 0.005) * (0.8 + f2 * 1.2);
            
            vec3 col1 = mix(COLOR, SECONDARY_COLOR, sin(a1 * 3.0 + t) * 0.5 + 0.5);
            vec3 col2 = mix(SECONDARY_COLOR, COLOR, sin(a2 * 2.0 - t) * 0.5 + 0.5);
            
            vec3 finalCol = (col1 * g1 + col2 * g2) * combinedMask;
            
            O = vec4(finalCol, 1.0);
        }
    """.trimIndent(),

        "Neon Symmetry" to """
        #define SPEED 1.5
        #define SHAPE_SIDES 4.0
        #define PULSE_SPEED 3.0
        #define BASE_COLOR vec3(1.0, 0.2, 0.5)
        #define ALT_COLOR vec3(0.2, 0.8, 1.0)

        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            vec3 col = vec3(0.0);
            
            uv *= rot(sin(iTime * 0.3) * 0.5);
            
            for(float i = 0.0; i < 4.0; i++) {
                vec2 p = uv * (1.0 + i * 0.5);
                p *= rot(iTime * 0.2 * (mod(i, 2.0) == 0.0 ? 1.0 : -1.0));
                
                float a = atan(p.y, p.x) + iTime * 0.5;
                float r = length(p);
                
                float poly = cos(floor(0.5 + a / 6.283 * SHAPE_SIDES) * 6.283 / SHAPE_SIDES - a) * r;
                
                float wave = fract(poly * 5.0 - iTime * SPEED);
                float line = smoothstep(0.1, 0.0, abs(wave - 0.5));
                
                vec3 c = mix(BASE_COLOR, ALT_COLOR, sin(iTime + i) * 0.5 + 0.5);
                col += c * line * (0.1 / (r + 0.1)) * (sin(iTime * PULSE_SPEED + i) * 0.5 + 0.5 + 0.5);
            }
            
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Cyber Thread" to """
        #define SPEED 0.3
        #define THICKNESS 0.015
        #define INTENSITY 1.2
        #define ZOOM 0.4
        #define COLOR vec3(0.0, 0.9, 0.6)
        #define SHIFT_COLOR vec3(0.8, 0.1, 0.9)

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            uv *= ZOOM;
            float t = iTime * SPEED;
            vec3 col = vec3(0.0);
            
            for(float i = 1.0; i <= 3.0; i++) {
                vec2 p = uv;
                
                p.x += sin(p.y * 3.0 + t * i) * 0.2;
                p.y += cos(p.x * 2.5 + t * i * 0.8) * 0.3;
                
                float wave = abs(p.y + sin(p.x * 4.0 - t * 1.2) * 0.2);
                float glow = THICKNESS / (wave + 0.002);
                
                vec3 curCol = mix(COLOR, SHIFT_COLOR, i * 0.3 + sin(t + p.x)*0.2);
                col += curCol * glow * INTENSITY;
            }
            
            col *= smoothstep(1.5, 0.2, length(uv));
            
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Fast Nebula" to """
        #define SPEED 0.15
        #define COLOR vec3(0.6, 0.1, 1.0)
        
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
        }
        
        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        
        float fbm(vec2 p) {
            float f = 0.0;
            float amp = 0.5;
            for(int i = 0; i < 4; i++) {
                f += amp * noise(p);
                p *= 2.0;
                amp *= 0.5;
            }
            return f;
        }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = U / iResolution.y;
            
            float q = fbm(uv * 3.0 + iTime * SPEED);
            float n = fbm(uv * 5.0 - iTime * SPEED * 0.8 + vec2(q));
            
            float glow = smoothstep(0.2, 0.8, n);
            vec3 col = COLOR * glow * 2.0;
            
            col += vec3(0.2, 0.5, 0.8) * smoothstep(0.4, 1.0, q) * 0.5;
            
            O = vec4(col, 1.0);
        }
    """.trimIndent()
    )

    val effectChain = EffectChain()
    private lateinit var glView: GLSurfaceView
    private val sourceControls = mutableListOf<PropertyControl>()
    private var mixerGroupContainer: LinearLayout? = null
    private lateinit var saveConfirmBtn: View
    private lateinit var renderer: KaleidoscopeRenderer
    private var currentSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    lateinit var overlayHUD: FrameLayout
    private lateinit var displayHelper: ExternalDisplayHelper
    lateinit var midiHelper: MidiHelper
    var autoPlayFilter = mutableSetOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
    val controls = java.util.concurrent.CopyOnWriteArrayList<PropertyControl>()
    val controlsMap = java.util.concurrent.ConcurrentHashMap<String, PropertyControl>()
    private val presetButtons = mutableMapOf<Int, Button>()
    private lateinit var menuBtn: Button
    private var activePreset: Int = -1

    private var isDraggingOrGesturing = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val CLICK_DRAG_TOLERANCE = 10f

    private var pendingMidiExportJson: String? = null

    private fun createOptimizedExoPlayer(): ExoPlayer {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10000, // Min buffer 10s
                20000, // Max buffer 20s
                1000,  // Buffer for playback 1s
                1000   // Buffer for playback after rebuffer 1s
            )
            .setTargetBufferBytes(15 * 1024 * 1024) // Limit to 15 MB per player
            .build()

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
    }

    @SuppressLint("Range")
    private fun getFileNameFromUri(uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FileName", "Error extracting file name from URI", e)
            }
        }

        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }

        // Clean up URL-encoded characters (like %20 spaces) if fallback was used
        try {
            if (result != null) {
                result = java.net.URLDecoder.decode(result, "UTF-8")
            }
        } catch (e: Exception) {}

        return if (result.isNullOrBlank()) "Unknown File" else result!!
    }


    val saveMappingLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && pendingMidiExportJson != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(pendingMidiExportJson!!.toByteArray())
                }
                Toast.makeText(this, "Mapping Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val loadMappingLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { it.readText() }
                    val success = midiHelper.importConfig(json)
                    if (success) {
                        Toast.makeText(this, "Loaded: ${midiHelper.mappingName}", Toast.LENGTH_LONG).show()
                        // If Settings is open, refresh it (Close and Reopen or strictly refresh UI)
                        // Simple approach: Close settings if open to reflect name change next time
                        if (settingsMenu?.isOpen() == true) {
                            settingsMenu?.dismiss()
                            // Optionally immediately reopen:
                            // settingsMenu = SettingsMenu(this, overlayHUD); settingsMenu?.show()
                        }
                    } else {
                        Toast.makeText(this, "Invalid Mapping File", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Load Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveMidiMappingToFile(filename: String, json: String) {
        pendingMidiExportJson = json
        saveMappingLauncher.launch(filename)
    }

    private var settingsMenu: SettingsMenu? = null
    private lateinit var photoBtn: ImageButton
    private lateinit var recordBtn: ImageButton
    private lateinit var flashOverlay: View
    private lateinit var leftHUDContainer: LinearLayout
    private var lastFingerDist = 0f
    private var lastFingerAngle = 0f
    private var lastFingerFocusX = 0f
    private var lastFingerFocusY = 0f
    private var exoPlayer: ExoPlayer? = null

    private data class Preset(
        val controlSnapshots: Map<String, PropertyControl.Snapshot>,
        val flipX: Float,
        val flipY: Float,
        val rot180: Boolean,
        val axis: Int
    )
    private val presets = mutableMapOf<Int, Preset>()
    private var pendingSaveIndex: Int? = null
    private var transitionMs: Long = 1000L
    private var transitionStartTime: Long = 0L
    private var isHudVisible = true
    private var isMenuExpanded = true
    private var isRecording = false
    private var recordingSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var recordTicker: Runnable? = null
    private var readabilityLevel = 2
    private lateinit var parameterPanel: ScrollView
    private lateinit var cameraSettingsPanel: LinearLayout
    private lateinit var presetPanel: LinearLayout
    private lateinit var recordControls: LinearLayout
    private lateinit var orientationBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private var isOrientationLocked = false
    private val expandedGroups = mutableSetOf<String>()
    private var lastScrollY = 0
    private var isRebuildingHUD = false

    private var isAutoPlaying = false
    var autoPlayRandom = false
    var autoPlayDurationMs = 3000L // 3 seconds hold time by default
    private val autoPlayRunnable = Runnable { triggerNextAutoPlay() }
    private lateinit var playBtn: ImageButton

    // For filling the button visual
    private var presetAnimators = mutableMapOf<Int, ValueAnimator>()
    private val presetDrawables = mutableMapOf<Int, ProgressButtonDrawable>()

    // --- 1. SHARED GL UTILITIES (Renamed to avoid conflict) ---
    object ShaderHelper {
        var pBuf: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }
        var tBuf: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
        }

        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("GL", "Compile Failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        fun createProgram(vSrc: String, fSrc: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vSrc)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
            if (v == 0 || f == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("GL", "Link Failed: ${GLES20.glGetProgramInfoLog(p)}")
                GLES20.glDeleteProgram(p)
                return 0
            }
            return p
        }

        fun bindQuad(prog: Int) {
            val pL = GLES20.glGetAttribLocation(prog, "p"); val tL = GLES20.glGetAttribLocation(prog, "t")
            GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        }
    }

    class EffectChain {
        val effects = mutableListOf<MainActivity.ShaderEffect>()
        private var fboA = 0
        private var texA = 0
        private var fboB = 0
        private var texB = 0
        private var width = 0
        private var height = 0
        private var isReady = false

        fun init(w: Int, h: Int) {
            if (isReady && width == w && height == h) return
            width = w; height = h
            release() // Re-create if size changed

            fun createFBO(): Pair<Int, Int> {
                val f = IntArray(1); val t = IntArray(1)
                GLES20.glGenFramebuffers(1, f, 0); GLES20.glGenTextures(1, t, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
                return Pair(f[0], t[0])
            }

            val a = createFBO(); fboA = a.first; texA = a.second
            val b = createFBO(); fboB = b.first; texB = b.second
            isReady = true
            effects.forEach { it.init() }
        }

        fun process(renderer: MainActivity.KaleidoscopeRenderer): Int {
            if (!isReady || effects.isEmpty()) return 0

            // 1. First pass: Mixer (Special case, takes no single input texture, uses source list)
            // We render the first effect into FBO A
            effects[0].render(0, fboA, width, height)

            var currentInput = texA
            var currentOutputFbo = fboB
            var currentOutputTex = texB

            // 2. Subsequent passes
            for (i in 1 until effects.size) {
                val effect = effects[i]
                if (effect.active) {
                    effect.render(currentInput, currentOutputFbo, width, height)

                    // Output becomes next Input
                    currentInput = currentOutputTex

                    // Swap Ping-Pong
                    if (currentOutputFbo == fboA) {
                        currentOutputFbo = fboB; currentOutputTex = texB
                    } else {
                        currentOutputFbo = fboA; currentOutputTex = texA
                    }
                }
            }
            return currentInput
        }

        fun release() {
            if (fboA != 0) { val f = IntArray(2){ if(it==0) fboA else fboB }; val t = IntArray(2){ if(it==0) texA else texB }; GLES20.glDeleteFramebuffers(2, f, 0); GLES20.glDeleteTextures(2, t, 0) }
            fboA = 0; isReady = false
            effects.forEach { it.release() }
        }
    }

    // SHADERS
    class MixerEffect(val activity: MainActivity) : MainActivity.ShaderEffect("FX_MIXER", "MIXER", activity) {
        private var prog = 0
        override fun init() {
            val fSrc = """
            precision mediump float; varying vec2 v; 
            uniform sampler2D uTex[8]; uniform float uMix[8]; uniform int uCount;
            void main() {
                vec4 sum = vec4(0.0);
                if (uCount > 0 && uMix[0] > 0.0) sum += texture2D(uTex[0], v) * uMix[0];
                if (uCount > 1 && uMix[1] > 0.0) sum += texture2D(uTex[1], v) * uMix[1];
                if (uCount > 2 && uMix[2] > 0.0) sum += texture2D(uTex[2], v) * uMix[2];
                if (uCount > 3 && uMix[3] > 0.0) sum += texture2D(uTex[3], v) * uMix[3];
                if (uCount > 4 && uMix[4] > 0.0) sum += texture2D(uTex[4], v) * uMix[4];
                if (uCount > 5 && uMix[5] > 0.0) sum += texture2D(uTex[5], v) * uMix[5];
                if (uCount > 6 && uMix[6] > 0.0) sum += texture2D(uTex[6], v) * uMix[6];
                if (uCount > 7 && uMix[7] > 0.0) sum += texture2D(uTex[7], v) * uMix[7];
                gl_FragColor = clamp(sum, 0.0, 1.0);
            }"""
            // Use ShaderHelper here
            prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        }

        override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)

            val sources = activity.renderer.sources
            val cnt = min(sources.size, 8)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uCount"), cnt)

            for(i in 0 until cnt) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sources[i].fboTexId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex[$i]"), i)
                val v = activity.controlsMap[sources[i].id]?.computedValue ?: 0f
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uMix[$i]"), v)
            }
            // Use ShaderHelper here
            ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { GLES20.glDeleteProgram(prog) }
    }

    class TransformEffect(idPrefix: String, title: String, activity: MainActivity) : MainActivity.ShaderEffect(idPrefix, title, activity) {
        private var prog = 0
        private val pZoom = "${idPrefix}_ZOOM"
        private val pAngle = "${idPrefix}_ANGLE"
        private val pTx = "${idPrefix}_TX"
        private val pTy = "${idPrefix}_TY"
        private val pTiltX = "${idPrefix}_TILTX"
        private val pTiltY = "${idPrefix}_TILTY"
        private val pRgb = "${idPrefix}_RGB"

        init {
            addControl(PropertyControl(pAngle, "ANGLE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
            addControl(PropertyControl(pZoom, "ZOOM", defaultValue = 700, outMin=0.05f, outMax=2.0f, hasModulation = true, logPower = 2))
            addControl(PropertyControl(pTx, "MOVE X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
            addControl(PropertyControl(pTy, "MOVE Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
            addControl(PropertyControl(pTiltX, "TILT X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
            addControl(PropertyControl(pTiltY, "TILT Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
            if(idPrefix == "C") addControl(PropertyControl("WARP", "DISTORT", defaultValue = 0, outMin=0f, outMax=1f))
            addControl(PropertyControl(pRgb, "RGB SHIFT", defaultValue = 0, outMin=0f, outMax=0.1f, hasModulation = true))
        }

        override fun init() {
            val fSrc = """
            precision highp float; varying vec2 v; uniform sampler2D uTex;
            uniform float uZ, uA, uR, uTx, uTy, uTiX, uTiY, uWarp, uRGB, uRatio;
            void main() {
                vec3 col = vec3(0.0);
                for(int i=0; i<3; i++) {
                    float off = (i==0) ? uRGB : (i==2) ? -uRGB : 0.0;
                    vec2 uv = v - 0.5;
                    
                    // 1. Tilt (Perspective)
                    float z = 1.0 + (uv.x * uTiX) + (uv.y * uTiY); 
                    uv /= max(z, 0.1);
                    
                    // 2. Zoom 
                    // We divide by uZ so that Higher Values = Zoom IN, Lower = Zoom OUT
                    uv /= uZ;
                    
                    // 3. Distort
                    float af = mix(uRatio, 1.0, uWarp); 
                    uv.x *= af;
                    
                    // 4. Rotation
                    float c = cos(uR); float s = sin(uR);
                    uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                    
                    uv.x /= af; 
                    
                    // 5. Move
                    uv += vec2(uTx, uTy);
                    uv.x += off;
                    
                    // 6. Wrap/Mirror
                    vec2 f = abs(mod(uv + 0.5, 2.0) - 1.0);
                    
                    vec4 sC = texture2D(uTex, f);
                    if(i==0) col.r = sC.r; else if(i==1) col.g = sC.g; else col.b = sC.b;
                }
                gl_FragColor = vec4(col, 1.0);
            }"""
            prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        }

        override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)

            val rotAccum = if (id.startsWith("M")) mainActivity.getRendererMRot() else mainActivity.getRendererCRot()
            val angle = (mainActivity.controlsMap[pAngle]?.computedValue ?: 0f) * 360f + rotAccum

            // --- SCALE CORRECTION ---
            // User requested that slider 320 behaves like 231 (1.0).
            // Value at 320 is ~1.348. Value at 231 is ~1.00.
            // Correction factor = 1.0 / 1.348 = ~0.7418
            val rawZoom = mainActivity.controlsMap[pZoom]?.computedValue ?: 1f
            val correctedZoom = rawZoom * 0.7067f

            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRatio"), w.toFloat()/h.toFloat())

            // Pass the corrected zoom value
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uZ"), correctedZoom)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uR"), Math.toRadians(angle).toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTx"), mainActivity.controlsMap[pTx]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTy"), mainActivity.controlsMap[pTy]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTiX"), (mainActivity.controlsMap[pTiltX]?.computedValue ?: 0f) * 1.5f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTiY"), (mainActivity.controlsMap[pTiltY]?.computedValue ?: 0f) * 1.5f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWarp"), mainActivity.controlsMap["WARP"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRGB"), mainActivity.controlsMap[pRgb]?.computedValue ?: 0f)

            ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { GLES20.glDeleteProgram(prog) }
    }

    class KaleidoscopeEffect(activity: MainActivity) : MainActivity.ShaderEffect("FX_KALEIDO", "KALEIDOSCOPE", activity) {
        private var prog = 0
        init {
            addControl(PropertyControl("AXIS", "AXIS", min=1, max=25, sliderMax=25, defaultValue=2, includeInPreset=true, defaultLocked=true, allowSmoothing=false))
            addControl(PropertyControl("K_AMT", "AMOUNT", defaultValue=1000, outMin=0f, outMax=1f, hasModulation=true))
            // Zoom starts at 0 (1.0x) and goes up to 1000 (5.0x zoom out)
            addControl(PropertyControl("K_ZOOM", "K-ZOOM", defaultValue=0, outMin=1.0f, outMax=5.0f, hasModulation=true))
        }

        override fun init() {
            val fSrc = """
            precision highp float; varying vec2 v; uniform sampler2D uTex;
            uniform float uAx, uAmt, uZoom, uRatio;
            void main() {
                vec2 uv = v - 0.5;
                
                // Transition logic for Amount (Normal vs Kaleidoscope)
                float zoomAmt = mix(1.0, 2.0, uAmt);
                float shift = mix(0.5, 0.0, uAmt);
                
                // Apply internal Zoom Out
                // uZoom starts at 1.0 and increases, so dividing by it zooms OUT.
                uv *= uZoom;
                
                uv *= zoomAmt;
                
                // Radial Logic (Axis > 2)
                if (uAx > 2.1) {
                    vec2 rUV = uv;
                    rUV.x *= uRatio;
                    
                    float r = length(rUV);
                    float angle = atan(rUV.y, rUV.x);
                    
                    float slice = 6.2831853 / uAx;
                    float a = mod(angle, slice);
                    a = abs(a - slice * 0.5);
                    
                    rUV = vec2(cos(a), sin(a)) * r;
                    rUV.x /= uRatio;
                    
                    uv = mix(uv, rUV, smoothstep(0.0, 1.0, uAmt));
                }
                
                // Mirroring Logic
                uv += shift;
                
                // INFINITE MIRROR REPEAT
                // This replaces the clamp. It reflects the coordinates whenever they cross 0.0 or 1.0.
                vec2 mirroredUV = abs(mod(uv - 1.0, 2.0) - 1.0);
                
                gl_FragColor = texture2D(uTex, mirroredUV);
            }"""
            prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        }

        override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)

            val axis = mainActivity.controlsMap["AXIS"]?.value?.toFloat() ?: 2f
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAx"), axis)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAmt"), mainActivity.controlsMap["K_AMT"]?.computedValue ?: 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uZoom"), mainActivity.controlsMap["K_ZOOM"]?.computedValue ?: 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRatio"), w.toFloat()/h.toFloat())

            ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { GLES20.glDeleteProgram(prog) }
    }

    class TunnelEffect(activity: MainActivity) : MainActivity.ShaderEffect("FX_TUNNEL", "3D TUNNEL", activity) {
        private var prog = 0
        private var scrollAccum = 0.0f

        init {
            addControl(PropertyControl("3D_MIX", "STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))

            // Shape & Speed
            addControl(PropertyControl("S_SHAPE", "SHAPE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
            addControl(PropertyControl("S_FOV", "FISHEYE", defaultValue = 500, outMin=0.2f, outMax=1.5f, hasModulation = true))
            addControl(PropertyControl("S_SPEED", "SPEED", defaultValue = 500, outMin=-2.0f, outMax=2.0f, hasModulation = true))

            // Fog
            addControl(PropertyControl("T_FOG", "FOG DIST", defaultValue = 0, outMin=0.0f, outMax=0.5f, hasModulation = true))
            addControl(PropertyControl("T_FOG_H", "FOG HUE", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
            addControl(PropertyControl("T_FOG_S", "FOG SAT", defaultValue = 0, outMin=0.0f, outMax=1.0f))
            addControl(PropertyControl("T_FOG_V", "FOG BRIT", defaultValue = 1000, outMin=0.0f, outMax=1.0f))

            // Color & Distortion
            addControl(PropertyControl("T_HUE_STR", "RAINBOW STR", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
            addControl(PropertyControl("T_HUE_POS", "RAINBOW POS", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
            addControl(PropertyControl("T_WAVE_STR", "WAVE STR", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
            addControl(PropertyControl("T_WAVE_POS", "WAVE POS", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))

            // Geometry
            // Curve: 1.0 = Straight. 0.0 = Left, 2.0 = Right.
            addControl(PropertyControl("CURVE", "CURVE", defaultValue = 500, outMin=0.0f, outMax=2.0f, hasModulation = true))
            // Twist: Reduced range to 10% (was +/- 5.0, now +/- 0.5)
            addControl(PropertyControl("TWIST", "VORTEX", defaultValue = 500, outMin=-0.5f, outMax=0.5f, hasModulation = true))
            addControl(PropertyControl("FLUX", "FLUX", defaultValue = 0, outMin=0f, outMax=0.5f, hasModulation = true))
        }

        override fun reset() {
            scrollAccum = 0.0f
        }

        override fun update(deltaTime: Float) {
            val speedCtrl = mainActivity.controlsMap["S_SPEED"] ?: return
            val rawSpeed = speedCtrl.computedValue
            val sign = sign(rawSpeed)
            val curvedSpeed = sign * (abs(rawSpeed)).pow(2.2f)
            scrollAccum += curvedSpeed * deltaTime * 0.6f
        }

        override fun init() {
            val fSrc = """
            precision highp float; varying vec2 v; uniform sampler2D uTex;
            uniform float uMix, uShape, uFov, uScroll, uHStr, uHPos, uWStr, uWPos, uRatio;
            uniform float uCurve, uTwist, uFlux;
            uniform float uFogD, uFogH, uFogS, uFogV;

            vec3 hsb2rgb(vec3 c) {
                vec3 rgb = clamp(abs(mod(c.x*6.0+vec3(0.0,4.0,2.0), 6.0)-3.0)-1.0, 0.0, 1.0);
                return c.z * mix(vec3(1.0), rgb, c.y);
            }

            void main() {
                vec2 flatUV = v - 0.5; 
                vec2 uv = flatUV;
                uv.x *= uRatio;
                
                // --- 1. Curve (Parabolic view bend) ---
                // We bend the view plane BEFORE calculating tunnel geometry.
                // uCurve 1.0 = 0.0 offset.
                // x += factor * y^2 creates a parabolic bend.
                float curveFactor = (uCurve - 1.0) * 2.0; 
                uv.x += curveFactor * (uv.y * uv.y);

                // --- 2. Geometry Calculation ---
                float rC = length(uv); 
                float rB = max(abs(uv.x), abs(uv.y)); 
                float dist = mix(rC, rB, uShape);
                
                // Flux distortion
                dist += sin(atan(uv.y, uv.x) * 4.0 + dist * 10.0) * uFlux * dist; 
                float safe = max(dist, 0.01);
                
                // Projection (Depth)
                float proj = (uFov * 0.8 + 0.2) / safe;
                
                // --- 3. Calculate Tunnel UVs ---
                vec2 tUV; 
                float ang = atan(uv.y, uv.x);
                tUV.x = (ang + (1.0/safe) * uTwist) / 3.14159; 
                tUV.y = proj + uScroll; // Global depth coordinate
                
                // --- 4. Mix & Wrap ---
                // Wrap Coordinates BEFORE Mixing (Fixes jump glitch)
                vec2 wrappedTunnel = abs(mod(tUV + 0.5, 2.0) - 1.0);
                vec2 wrappedFlat = abs(mod(flatUV + 0.5, 2.0) - 1.0); 

                vec2 finalUV = mix(wrappedFlat, wrappedTunnel, uMix * uMix);
                vec4 col = texture2D(uTex, finalUV);

                // --- 5. Color Effects (Global Movement) ---
                // Use 'tUV.y' (infinite depth) instead of 'finalUV.y' (wrapped tile)
                // This ensures rainbow/wave move DOWN the tunnel, not ON the tile.
                
                // Rainbow
                if (uHStr > 0.01) { 
                    float effectiveStr = uHStr * uMix; // Fade out if flat
                    float ha = (tUV.y * 0.2) + uHPos; // *0.2 controls rainbow frequency
                    vec3 rb = 0.5 + 0.5 * cos(6.28 * (ha + vec3(0.0, 0.33, 0.67))); 
                    col.rgb = mix(col.rgb, col.rgb * rb * 2.0, effectiveStr); 
                }
                
                // Wave
                if (uWStr > 0.01) { 
                    float effectiveWStr = uWStr * uMix; // Fade out if flat
                    float wd = tUV.y - (uWPos * 10.0); // Use global depth
                    float dw = abs(fract(wd) - 0.5); 
                    float wp = smoothstep(0.15 + effectiveWStr*0.2, 0.0, dw); 
                    col.rgb += vec3(0.5, 0.8, 1.0) * wp * effectiveWStr; 
                }
                
                // --- 6. Fog ---
                if (uFogD > 0.001) {
                    float depth = 1.0 / safe;
                    float fogAmt = 1.0 - exp(-depth * depth * uFogD * 0.1);
                    fogAmt = clamp(fogAmt, 0.0, 1.0);
                    
                    vec3 fogColor = hsb2rgb(vec3(uFogH, uFogS, uFogV));
                    float effectiveFog = fogAmt * smoothstep(0.0, 1.0, uMix);
                    col.rgb = mix(col.rgb, fogColor, effectiveFog);
                }

                gl_FragColor = col;
            }"""
            prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        }

        override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRatio"), w.toFloat()/h.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uMix"), mainActivity.controlsMap["3D_MIX"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShape"), mainActivity.controlsMap["S_SHAPE"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFov"), mainActivity.controlsMap["S_FOV"]?.computedValue ?: 0.5f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uScroll"), scrollAccum)

            // Color
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHStr"), mainActivity.controlsMap["T_HUE_STR"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHPos"), mainActivity.controlsMap["T_HUE_POS"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWStr"), mainActivity.controlsMap["T_WAVE_STR"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWPos"), mainActivity.controlsMap["T_WAVE_POS"]?.computedValue ?: 0f)

            // Distort
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uCurve"), mainActivity.controlsMap["CURVE"]?.computedValue ?: 1.0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTwist"), mainActivity.controlsMap["TWIST"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlux"), mainActivity.controlsMap["FLUX"]?.computedValue ?: 0f)

            // Fog
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogD"), mainActivity.controlsMap["T_FOG"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogH"), mainActivity.controlsMap["T_FOG_H"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogS"), mainActivity.controlsMap["T_FOG_S"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogV"), mainActivity.controlsMap["T_FOG_V"]?.computedValue ?: 1f)

            ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { GLES20.glDeleteProgram(prog) }
    }

    class SwirlEffect(activity: MainActivity) : MainActivity.ShaderEffect("FX_SWIRL", "SWIRL", activity) {
        private var prog = 0
        private var scrollAccum = 0.0f
        private var swayAccum = 0.0f

        init {
            addControl(PropertyControl("UTWIRL", "STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))

            addControl(PropertyControl("S_WIDE", "WIDENESS", defaultValue = 500, outMin=0.0f, outMax=2.0f, hasModulation = true))
            addControl(PropertyControl("S_ACTIVITY", "ACTIVITY", defaultValue = 200, outMin=0.0f, outMax=2.0f, hasModulation = true))
            addControl(PropertyControl("SWIRL_SPEED", "SPEED", defaultValue = 500, outMin=-4.0f, outMax=4.0f, hasModulation = true))

            addControl(PropertyControl("S_FOG", "FOG DIST", defaultValue = 100, outMin=0.0f, outMax=1.0f, hasModulation = true))
            addControl(PropertyControl("S_FOG_FALLOFF", "FOG SOFT", defaultValue = 150, outMin=0.0f, outMax=80.0f))
            addControl(PropertyControl("S_FOG_H", "FOG HUE", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
            addControl(PropertyControl("S_FOG_S", "FOG SAT", defaultValue = 0, outMin=0.0f, outMax=1.0f))
            addControl(PropertyControl("S_FOG_V", "FOG BRIT", defaultValue = 0, outMin=0.0f, outMax=1.0f))
        }

        override fun reset() {
            scrollAccum = 0.0f
            swayAccum = 0.0f
        }

        override fun update(deltaTime: Float) {
            val speedCtrl = mainActivity.controlsMap["SWIRL_SPEED"]
            if (speedCtrl != null) {
                val rawSpeed = speedCtrl.computedValue
                val sign = sign(rawSpeed)
                val curvedSpeed = sign * (abs(rawSpeed)).pow(2.0f)
                scrollAccum -= curvedSpeed * deltaTime * 2.0f
            }

            val actCtrl = mainActivity.controlsMap["S_ACTIVITY"]
            if (actCtrl != null) {
                swayAccum += actCtrl.computedValue * deltaTime
            }
        }

        override fun init() {
            val fSrc = """
            precision highp float; varying vec2 v; uniform sampler2D uTex;
            uniform float uStr, uWide, uScroll, uSwayTime, uRatio;
            uniform float uFogD, uFogF, uFogH, uFogS, uFogV;
            
            #define PI 3.14159
            #define FAR 80.0
            
            mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
            mat3 lookAt(vec3 dir) {
                vec3 up = vec3(0., 1., 0.);
                vec3 rt = normalize(cross(dir, up));
                return mat3(rt, cross(rt, dir), dir);
            }
            
            vec3 hsb2rgb(vec3 c) {
                vec3 rgb = clamp(abs(mod(c.x*6.0+vec3(0.0,4.0,2.0), 6.0)-3.0)-1.0, 0.0, 1.0);
                return c.z * mix(vec3(1.0), rgb, c.y);
            }

            float gyroid(vec3 p) { return dot(cos(p), sin(p.zxy)) + 1.0; }

            float map(vec3 p) {
                vec3 q = p * 0.8;
                float d1 = gyroid(q);
                float d2 = gyroid(q - vec3(0.0, 0.0, PI));
                return min(d1, d2) * 0.5; 
            }

            void main() {
                vec2 flatUV = v - 0.5;
                vec2 aspectUV = flatUV;
                aspectUV.x *= uRatio; 
                
                vec3 ro = vec3(PI/2.0, 0.0, uScroll); 
                vec3 rd = normalize(vec3(aspectUV, -0.5)); 

                rd.xy = rot(sin(uSwayTime * 0.2) * uWide * 0.5) * rd.xy;
                vec3 targetOffsets = vec3(cos(uSwayTime * 0.4), sin(uSwayTime * 0.4), 4.0);
                targetOffsets.xy *= uWide; 
                vec3 ta = normalize(targetOffsets);
                rd = lookAt(ta) * rd;

                float t = 0.0;
                float d = 0.0;
                vec3 p = ro;
                
                for(int i = 0; i < 60; i++) {
                    p = ro + rd * t;
                    d = map(p);
                    if(abs(d) < 0.01 || t > FAR) break;
                    t += d;
                }
                
                vec2 tunnelUV;
                float fogAmt = 0.0;
                
                if(t < FAR) {
                    float ang = atan(p.y, p.x);
                    tunnelUV = vec2(ang / PI, p.z * 0.2);
                    
                    if (uFogD > 0.001) {
                        float fogWall = mix(FAR, 0.0, uFogD);
                        float fogStart = fogWall - max(uFogF, 0.001);
                        fogAmt = smoothstep(fogStart, fogWall, t);
                    }
                } else {
                    tunnelUV = flatUV; 
                    fogAmt = 1.0; 
                }

                vec2 wrappedTunnel = abs(mod(tunnelUV + 0.5, 2.0) - 1.0);
                vec2 wrappedFlat = abs(mod(flatUV + 0.5, 2.0) - 1.0); 

                vec2 finalUV = mix(wrappedFlat, wrappedTunnel, smoothstep(0.0, 1.0, uStr));
                vec4 col = texture2D(uTex, finalUV);
                
                if (uFogD > 0.001) {
                    vec3 fogColor = hsb2rgb(vec3(uFogH, uFogS, uFogV));
                    float effectiveFog = fogAmt * smoothstep(0.0, 1.0, uStr);
                    col.rgb = mix(col.rgb, fogColor, effectiveFog);
                }

                gl_FragColor = col;
            }"""
            prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        }

        override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRatio"), w.toFloat()/h.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uStr"), mainActivity.controlsMap["UTWIRL"]?.computedValue ?: 0f)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWide"), mainActivity.controlsMap["S_WIDE"]?.computedValue ?: 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uScroll"), scrollAccum)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSwayTime"), swayAccum)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogD"), mainActivity.controlsMap["S_FOG"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogF"), mainActivity.controlsMap["S_FOG_FALLOFF"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogH"), mainActivity.controlsMap["S_FOG_H"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogS"), mainActivity.controlsMap["S_FOG_S"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFogV"), mainActivity.controlsMap["S_FOG_V"]?.computedValue ?: 0f)

            ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { GLES20.glDeleteProgram(prog) }
    }

    class ColorEffect(activity: MainActivity) : MainActivity.ShaderEffect("FX_COLOR", "COLOR", activity) {
        private var prog = 0
        init {
            addControl(PropertyControl("BRIT", "BRIGHTNESS", defaultValue = 500, outMin=0f, outMax=2f))
            addControl(PropertyControl("HUE", "HUE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
            addControl(PropertyControl("NEG", "NEGATIVE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
            addControl(PropertyControl("GLOW", "GLOW", defaultValue = 0, outMin=0f, outMax=2f, hasModulation = true))
            addControl(PropertyControl("CONTRAST", "CONTRAST", defaultValue = 500, outMin=0f, outMax=2f))
            addControl(PropertyControl("VIBRANCE", "SATURATION", defaultValue = 500, outMin=0f, outMax=2f))
        }
        override fun init() {
            val fSrc = """
            precision mediump float; varying vec2 v; uniform sampler2D uTex;
            uniform float uBrit, uHue, uNeg, uGlow, uCon, uVib;
            vec3 hueShift(vec3 c, float h) { const vec3 k = vec3(0.57735); float ca = cos(h); return c * ca + cross(k, c) * sin(h) + k * dot(k, c) * (1.0 - ca); }
            void main() {
                vec3 c = texture2D(uTex, v).rgb;
                c = abs(c - uNeg);
                if(uHue > 0.01) c = hueShift(c, uHue * 6.28);
                c = (c - 0.5) * uCon + 0.5;
                float l = dot(c, vec3(0.299, 0.587, 0.114));
                c = mix(vec3(l), c, uVib);
                if(uGlow > 0.01) c += smoothstep(0.4, 1.0, l) * c * uGlow * 2.0;
                c *= uBrit;
                gl_FragColor = vec4(c, 1.0);
            }"""
            prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        }
        override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBrit"), mainActivity.controlsMap["BRIT"]?.computedValue ?: 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHue"), mainActivity.controlsMap["HUE"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uNeg"), mainActivity.controlsMap["NEG"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGlow"), mainActivity.controlsMap["GLOW"]?.computedValue ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uCon"), mainActivity.controlsMap["CONTRAST"]?.computedValue ?: 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uVib"), mainActivity.controlsMap["VIBRANCE"]?.computedValue ?: 1f)
            ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { GLES20.glDeleteProgram(prog) }
    }



    fun updatePlayButtonState() {
        if (!::playBtn.isInitialized) return
        val enabled = autoPlayFilter.isNotEmpty()
        playBtn.isEnabled = enabled
        playBtn.alpha = if (enabled) 1.0f else 0.3f

        // If we are playing but suddenly have no allowed presets, stop
        if (!enabled && isAutoPlaying) {
            stopAutoPlay()
        }
    }

    fun getSourceControlsList(): List<PropertyControl> {
        return sourceControls
    }

    fun getRendererSource(id: String): MainActivity.KaleidoscopeRenderer.SourceChannel? {
        return renderer.getSource(id)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        isRebuildingHUD = true

        // 1. Close active UI menus
        PropertyControl.closeActiveMenu()

        // 2. Handle Settings state
        var wasSettingsOpen = false
        var savedScrollY = 0
        if (settingsMenu != null && settingsMenu!!.isOpen()) {
            wasSettingsOpen = true
            savedScrollY = settingsMenu!!.getScrollY()
            settingsMenu!!.cleanup()
        }

        // 3. Clear and rebuild HUD
        if (::overlayHUD.isInitialized) {
            overlayHUD.removeAllViews()
        }
        presetButtons.clear()
        setupOverlayHUD()

        // 4. Refresh external display
        // Instead of re-instantiating, just tell the existing helper to refresh the presentation
        if (::displayHelper.isInitialized) {
            displayHelper.updatePresentation()
        } else {
            displayHelper = ExternalDisplayHelper(this, renderer)
            displayHelper.start()
        }

        // 5. Restore UI states
        glView.post {
            applyReadabilityStyle()
            if (wasSettingsOpen) {
                settingsMenu = SettingsMenu(this, overlayHUD)
                settingsMenu?.show()
                settingsMenu?.restoreScrollY(savedScrollY)
            }
        }

        isRebuildingHUD = false
    }

    fun resetPresetsToDefault() {
        val prefs = getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        presets.clear()
        initDefaultPresets()
        globalReset()
        applyPreset(1)

        // Force UI refresh to fix "disappearing buttons"
        runOnUiThread {
            overlayHUD.removeAllViews()
            setupOverlayHUD()
            overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE
            applyReadabilityStyle()
            updateSidebarVisuals()
        }

        Toast.makeText(this, "All presets reset to factory defaults.", Toast.LENGTH_LONG).show()
    }

    fun removeSource(ctrl: SourcePropertyControl) {
        // 1. Close menu first
        ctrl.closeMenu()

        // 2. Remove from Maps/Lists
        controls.remove(ctrl)
        controlsMap.remove(ctrl.id)
        sourceControls.remove(ctrl)

        // 3. Remove from Renderer
        renderer.removeSource(ctrl.sourceId)

        // 4. Clean up specifics (ExoPlayer)
        ctrl.onRemove()

        // 5. IMPORTANT: Remove the visual slider row from the parent layout
        ctrl.removeFromParent()

        Toast.makeText(this, "Source Removed", Toast.LENGTH_SHORT).show()
    }

    // Central handler for Global/Trigger MIDI events
    fun handleMidiCommand(commandId: String, value: Int) {
        runOnUiThread {
            // Handle Presets (PRESET_1 to PRESET_9)
            if (commandId.startsWith("PRESET_")) {
                val idx = commandId.removePrefix("PRESET_").toIntOrNull()
                if (idx != null) {
                    if (isAutoPlaying) {
                        isAutoPlaying = false
                        if (::playBtn.isInitialized) playBtn.setImageDrawable(createPlayIcon(false))
                    }
                    applyPreset(idx)
                }
                return@runOnUiThread
            }

            // Handle specific Commands
            when (commandId) {
                "CMD_RECORD" -> toggleRecording()
                "CMD_PHOTO" -> {
                    renderer.capturePhoto()
                    triggerFlashPulse()
                }
                "CMD_AUTOPLAY" -> toggleAutoPlay()
                "CMD_CAM_SWITCH" -> switchCamera()
                "CMD_FLIP_X" -> {
                    renderer.flipX = if (renderer.flipX == 1f) -1f else 1f
                    updateSidebarVisuals()
                }
                "CMD_FLIP_Y" -> {
                    renderer.flipY = if (renderer.flipY == 1f) -1f else 1f
                    updateSidebarVisuals()
                }
                "CMD_ROT_180" -> {
                    renderer.rot180 = !renderer.rot180
                    updateSidebarVisuals()
                }
            }
        }
    }

    private fun createSwitchCameraDrawable(): BitmapDrawable {
        val size = 300
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)

        // 1. Main Camera Icon
        val cameraDrawable = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_camera)?.mutate()
        if (cameraDrawable != null) {
            cameraDrawable.setTint(Color.WHITE)
            // Fill 90% of the canvas from top-left
            // Was 255, increased to 270 to push it closer to the border
            cameraDrawable.setBounds(0, 0, 270, 270)
            cameraDrawable.draw(c)
        }

        // 2. Refresh Icon (Overlapping)
        val refreshDrawable = ContextCompat.getDrawable(this, android.R.drawable.ic_popup_sync)?.mutate()
        if (refreshDrawable != null) {
            refreshDrawable.setTint(Color.WHITE)
            // Position: Overlap significantly to keep the overall footprint tight
            // Fills the gap in the bottom right
            refreshDrawable.setBounds(160, 160, 300, 300)

            // Draw a stroke behind refresh to cut the camera icon
            val p = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 10f
                isAntiAlias = true
                alpha = 180 // Increased alpha to cut clearly
            }
            c.drawCircle(230f, 230f, 65f, p)

            refreshDrawable.draw(c)
        }

        return BitmapDrawable(resources, b)
    }

    private fun addDynamicSourceControl(ctrl: PropertyControl) {
        if (mixerGroupContainer == null) return
        val idx = mixerGroupContainer!!.childCount - 1

        controls.add(ctrl)
        controlsMap[ctrl.id] = ctrl
        sourceControls.add(ctrl)

        ctrl.attachTo(this, mixerGroupContainer!!)

        val view = mixerGroupContainer!!.getChildAt(mixerGroupContainer!!.childCount - 1)
        mixerGroupContainer!!.removeView(view)
        mixerGroupContainer!!.addView(view, idx)

    }

    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {}
                attemptAddMediaSource(uri)
            }
        }
    }

    // Replace your existing attemptAddMediaSource with this:
    private fun attemptAddMediaSource(uri: android.net.Uri) {
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val isImage = mimeType.startsWith("image")
        val uniqueId = "SRC_${System.currentTimeMillis()}"
        val fileName = getFileNameFromUri(uri)

        if (isImage) {
            val bitmap = loadScaledBitmap(uri)
            if (bitmap != null) {
                val channel = renderer.addSource(SourceType.MEDIA_IMAGE, uniqueId, bitmap)
                if (channel != null) {
                    if (bitmap.height > bitmap.width) channel.rotation = -90f else channel.rotation = 0f
                    val ctrl = MediaSourceControl(uniqueId, "IMAGE", uniqueId, this, null)
                    ctrl.subtitle = fileName
                    addDynamicSourceControl(ctrl)
                    Toast.makeText(this, "Image Added", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } else {
            val channel = renderer.addSource(SourceType.MEDIA_VIDEO, uniqueId)

            if (channel != null) {
                val player = createOptimizedExoPlayer()
                player.volume = 0f
                player.repeatMode = Player.REPEAT_MODE_ONE

                val ctrl = MediaSourceControl(uniqueId, "VIDEO", uniqueId, this@MainActivity, player)
                ctrl.subtitle = fileName
                addDynamicSourceControl(ctrl)

                channel.onSurfaceReady = { surface ->
                    Log.d("SpaceBeamDebug", "Surface Ready! Attaching to ExoPlayer.")
                    try {
                        player.setVideoSurface(surface)
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.prepare()
                        player.play()

                        player.addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                if (videoSize.width > 0) {
                                    channel.updateSize(videoSize.width, videoSize.height)
                                    if (videoSize.height > videoSize.width) channel.rotation = -90f else channel.rotation = 0f
                                }
                            }
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                Toast.makeText(this@MainActivity, "Video Error", Toast.LENGTH_SHORT).show()
                                removeSource(ctrl)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e("SpaceBeamDebug", "Player Attach Error", e)
                    }
                }

                Toast.makeText(this, "Video Added (Loading...)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midiHelper = MidiHelper(this)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        hideSystemUI()

        renderer = KaleidoscopeRenderer(this)

        effectChain.effects.clear()
        effectChain.effects.add(MixerEffect(this))
        effectChain.effects.add(TransformEffect("C", "CAMERA TRANSFORM", this))
        effectChain.effects.add(KaleidoscopeEffect(this))
        effectChain.effects.add(TransformEffect("M", "MASTER TRANSFORM", this))
        effectChain.effects.add(TunnelEffect(this))
        effectChain.effects.add(SwirlEffect(this))
        effectChain.effects.add(ColorEffect(this))

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setPreserveEGLContextOnPause(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView.setOnTouchListener { _, event ->
            // Clear save confirmation if user touches anywhere
            if (event.action == MotionEvent.ACTION_DOWN && saveConfirmBtn.visibility == View.VISIBLE) {
                saveConfirmBtn.visibility = View.GONE
                pendingSaveIndex = null
            }

            // Pass the event to our unified interaction handler
            handleInteraction(event)
            true
        }
        setContentView(glView)
        setupOverlayHUD()
        initDefaultPresets()
        val prefs = getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
        autoPlayRandom = prefs.getBoolean("AP_RANDOM", false)
        val filterStr = prefs.getString("AP_FILTER", null)
        if (filterStr != null) {
            autoPlayFilter.clear()
            if (filterStr.isNotEmpty()) {
                filterStr.split(",").mapNotNull { it.toIntOrNull() }.forEach { autoPlayFilter.add(it) }
            }
        }
        glView.post {
            globalReset()
            applyPreset(1)
            applyReadabilityStyle()
        }
        displayHelper = ExternalDisplayHelper(this, renderer)
        displayHelper.start()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        // Bluetooth Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
        } else {
            startCamera()
        }
    }

    fun startCamera() {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val provider = cpFuture.get()
            provider.unbindAll()
            glView.queueEvent {
                runOnUiThread {
                    val preview = Preview.Builder().setTargetRotation(Surface.ROTATION_90).build()
                    preview.setSurfaceProvider { req -> renderer.provideCameraSurface(req) }
                    try { provider.bindToLifecycle(this, currentSelector, preview) } catch (e: Exception) { Log.e("Camera", "Bind failed", e) }
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        midiHelper.close()
        // Detach all UI from controls to prevent context leaks
        controls.forEach { it.detach() }

        exoPlayer?.release()
        exoPlayer = null
        displayHelper.stop()
    }


    private fun handleInteraction(event: MotionEvent) {
        var targetEffect: TransformEffect? = null
        for (i in effectChain.effects.indices.reversed()) {
            val fx = effectChain.effects[i]
            if (fx is TransformEffect && fx.active) {
                targetEffect = fx
                break
            }
        }
        val pAngleId = if (targetEffect != null) "${targetEffect.id}_ANGLE" else "M_ANGLE"
        val pZoomId = if (targetEffect != null) "${targetEffect.id}_ZOOM" else "M_ZOOM"
        val pTxId = if (targetEffect != null) "${targetEffect.id}_TX" else "M_TX"
        val pTyId = if (targetEffect != null) "${targetEffect.id}_TY" else "M_TY"

        if (event.pointerCount >= 2) {
            isDraggingOrGesturing = true

            val p1x = event.getX(0); val p1y = event.getY(0)
            val p2x = event.getX(1); val p2y = event.getY(1)
            val focusX = (p1x + p2x) / 2f; val focusY = (p1y + p2y) / 2f
            val dist = hypot(p1x - p2x, p1y - p2y)
            val angle = Math.toDegrees(atan2((p1y - p2y).toDouble(), (p1x - p2x).toDouble())).toFloat()

            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                if (lastFingerDist == 0f) {
                    lastFingerDist = dist
                    lastFingerAngle = angle
                    lastFingerFocusX = focusX
                    lastFingerFocusY = focusY
                }

                val dx = (focusX - lastFingerFocusX) / glView.width.toFloat() * 2.0f
                val dy = (focusY - lastFingerFocusY) / glView.height.toFloat() * 2.0f

                controlsMap[pTxId]?.let { it.setProgress((it.value + (dx * 500).toInt()).coerceIn(0, 1000)) }
                controlsMap[pTyId]?.let { it.setProgress((it.value - (dy * 500).toInt()).coerceIn(0, 1000)) }

                val scaleFactor = dist / lastFingerDist
                if (scaleFactor > 0 && lastFingerDist > 0) {
                    controlsMap[pZoomId]?.let { it.setProgress((it.value + (log2(scaleFactor) * 450).toInt()).coerceIn(0, 1000)) }
                }

                val dAngle = angle - lastFingerAngle
                controlsMap[pAngleId]?.let {
                    it.setProgress((it.value + (dAngle * (1000f / 360f)).toInt() + 1000) % 1000)
                }
            }

            lastFingerDist = dist; lastFingerAngle = angle; lastFingerFocusX = focusX; lastFingerFocusY = focusY
        } else {
            if (event.pointerCount < 2) { lastFingerDist = 0f }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingOrGesturing = false
                    touchDownX = event.x
                    touchDownY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - touchDownX)
                    val dy = abs(event.y - touchDownY)
                    if (dx > CLICK_DRAG_TOLERANCE || dy > CLICK_DRAG_TOLERANCE) {
                        isDraggingOrGesturing = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && !isDraggingOrGesturing) {
                        if (PropertyControl.activeControl != null) {
                            PropertyControl.closeActiveMenu()
                        } else {
                            toggleHud()
                        }
                    }
                    isDraggingOrGesturing = false
                }
            }
        }
    }

    private fun textToIcon(t: String, size: Float = 60f, color: Int = Color.WHITE): BitmapDrawable {
        // CHANGE 3: Increase bitmap resolution to 500x500 so text is crisp
        val b = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply {
            this.color = color
            this.textSize = size
            this.textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }

        // CHANGE 4: Adjust center point to 250 (half of 500)
        c.drawText(t, 250f, 250f + (size / 3f), p)

        return BitmapDrawable(resources, b)
    }

    private fun createLogoDrawable(): ShapeDrawable {
        val p = Path().apply { moveTo(46f, 131f); lineTo(46f, 162f); lineTo(159f, 162f); lineTo(159f, 144f); lineTo(64f, 144f); lineTo(64f, 131f); close() }
        return ShapeDrawable(PathShape(p, 200f, 200f)).apply { paint.color = Color.WHITE; paint.isAntiAlias = true }
    }

    private fun createLockDrawable(locked: Boolean): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
        val shackle = RectF(30f, 20f, 70f, 60f); if (locked) c.drawArc(shackle, 180f, 180f, false, p) else c.drawArc(shackle, 160f, 180f, false, p)
        p.style = Paint.Style.FILL; c.drawRoundRect(RectF(25f, 50f, 75f, 85f), 8f, 8f, p); return BitmapDrawable(resources, b)
    }



    private fun setupOverlayHUD() {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        if (!::overlayHUD.isInitialized) {
            overlayHUD = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
            addContentView(overlayHUD, ViewGroup.LayoutParams(-1, -1))
        } else {
            overlayHUD.removeAllViews()
        }

        flashOverlay = createFlashView()
        val logoView = createLogoView()
        setupParameterMenu()
        val cameraPanel = createCameraSettingsPanel()
        val recordPanel = createRecordControls()
        val presetPanel = createPresetPanel()

        // Create Orientation Button
        val orientationBtnView = createOrientationButton()

        // Create Settings Gear Button
        settingsBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            alpha = 0.85f
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(20, 20, 20, 20)
            layoutParams = FrameLayout.LayoutParams(130, 130).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 30
                rightMargin = 35
            }
            setOnClickListener {
                if (settingsMenu == null) {
                    settingsMenu = SettingsMenu(this@MainActivity, overlayHUD)
                }
                settingsMenu?.show()
            }
        }

        overlayHUD.addView(flashOverlay)
        overlayHUD.addView(logoView)
        overlayHUD.addView(leftHUDContainer)

        val recordParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) {
                recordPanel.orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = 450; leftMargin = 30
                (recordBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 40; leftMargin = 0 }
            } else {
                recordPanel.orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 30; leftMargin = 250;
                (recordBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = 30 }
            }
        }
        overlayHUD.addView(recordPanel, recordParams)

        val presetParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 50
                rightMargin = 44
                presetPanel.scaleX = 1.0f; presetPanel.scaleY = 1.0f
            } else {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 15
                rightMargin = 400
                presetPanel.scaleX = 1.1f; presetPanel.scaleY = 1.1f
            }
        }
        overlayHUD.addView(presetPanel, presetParams)

        // Add Utility Buttons (Right Side)
        overlayHUD.addView(settingsBtn)
        overlayHUD.addView(orientationBtnView)

        val cameraParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 500; rightMargin = 20 }
            else { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 }
        }
        overlayHUD.addView(cameraPanel, cameraParams)

        // Removed addContentView here because it's handled in the check at the top
        updateSidebarVisuals()
    }
    fun showMidiLearnOverlay(targetId: String, label: String) {
        if (!midiHelper.isConnected) {
            Toast.makeText(this, "Connect Bluetooth MIDI first", Toast.LENGTH_SHORT).show()
            return
        }

        // Reuse the logic from SettingsMenu confirmation style
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(220, 0, 0, 0)) // Slightly darker for contrast
            isClickable = true
            elevation = 1000f
            setOnClickListener {
                midiHelper.learningTargetId = null
                (parent as ViewGroup).removeView(this)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Use WRAP_CONTENT
            layoutParams = FrameLayout.LayoutParams(900, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.RED)
                cornerRadius = 30f
            }
            setPadding(40, 40, 40, 40)

            // Consume clicks to prevent closing when clicking the box itself
            isClickable = true
            setOnClickListener { }
        }

        // --- Header ---
        content.addView(TextView(this).apply {
            text = "MAPPING: $label"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        content.addView(TextView(this).apply {
            text = "Press a button/knob on your MIDI controller..."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 30)
        })

        // --- Active Mappings List ---
        content.addView(TextView(this).apply {
            text = "CURRENT MAPPINGS:"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.GRAY)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        })

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = 50
        }

        fun refreshMappings() {
            listContainer.removeAllViews()
            val bindings = midiHelper.getBindingsForTarget(targetId)

            if (bindings.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = "No active mappings."
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    gravity = Gravity.CENTER
                    setPadding(0, 20, 0, 20)
                })
            } else {
                bindings.forEach { (cc, binding) ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#333333"))
                            cornerRadius = 10f
                        }
                        setPadding(20, 15, 10, 15)
                    }

                    // Info Text
                    val infoText = TextView(this).apply {
                        text = "CC $cc  (${binding.mode})"
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }

                    // Delete Button (X)
                    val delBtn = Button(this).apply {
                        text = "✕"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER

                        includeFontPadding = false
                        setPadding(0, 0, 0, 0)

                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#880000")) // Red
                            cornerRadius = 40f // Perfect circle (half of width 80)
                        }
                        layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 15 }
                        setOnClickListener {
                            midiHelper.removeBinding(cc, targetId)
                            refreshMappings()
                        }
                    }

                    row.addView(infoText)
                    row.addView(delBtn)
                    listContainer.addView(row)
                }
            }
        }

        // Initial Load
        refreshMappings()

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 300) // Limited height for scrolling
            addView(listContainer)
            // Add a subtle border or background to the scroll area
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 15f
            }
            setPadding(10,10,10,10)
        }
        content.addView(scrollView)

        // --- Cancel Button ---
        val cancelBtn = Button(this).apply {
            text = "CANCEL"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = null
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply {
                topMargin = 20
            }
            setOnClickListener {
                midiHelper.learningTargetId = null
                (frame.parent as ViewGroup).removeView(frame)
            }
        }
        content.addView(cancelBtn)

        frame.addView(content)
        overlayHUD.addView(frame)

        // Start Learning
        midiHelper.learningTargetId = targetId
        midiHelper.onLearningComplete = {
            // Close the frame when learning is complete so they see it worked,
            // or you can call refreshMappings() here if you prefer it to stay open.
            (frame.parent as? ViewGroup)?.removeView(frame)
        }
    }

    // Add this inside MainActivity class
    private fun loadScaledBitmap(uri: android.net.Uri): Bitmap? {
        Log.d("SpaceBeamDebug", "--- Loading Bitmap: $uri ---")
        try {
            val stream = contentResolver.openInputStream(uri)
            if (stream == null) {
                Log.e("SpaceBeamDebug", "FAIL: InputStream is null")
                return null
            }

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(stream, null, options)
            stream.close()

            Log.d("SpaceBeamDebug", "Bitmap Bounds: ${options.outWidth}x${options.outHeight}, Mime: ${options.outMimeType}")

            if (options.outWidth == -1 || options.outHeight == -1) {
                Log.e("SpaceBeamDebug", "FAIL: Invalid dimensions")
                return null
            }

            val maxDim = 1920
            var sampleSize = 1
            if (options.outHeight > maxDim || options.outWidth > maxDim) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / sampleSize) >= maxDim && (halfWidth / sampleSize) >= maxDim) {
                    sampleSize *= 2
                }
            }
            Log.d("SpaceBeamDebug", "Calculated sampleSize: $sampleSize")

            val decodeOptions = BitmapFactory.Options()
            decodeOptions.inSampleSize = sampleSize
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                decodeOptions.inMutable = true
            }

            val inputStream2 = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
            inputStream2?.close()

            if (bitmap == null) {
                Log.e("SpaceBeamDebug", "FAIL: Decoded bitmap is null")
                return null
            }

            Log.d("SpaceBeamDebug", "Decoded Bitmap: ${bitmap.width}x${bitmap.height}, Config: ${bitmap.config}")

            // Handle Hardware Bitmaps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                Log.w("SpaceBeamDebug", "Bitmap is HARDWARE. Copying to ARGB_8888...")
                val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                return software
            }

            return bitmap
        } catch (e: Exception) {
            Log.e("SpaceBeamDebug", "CRITICAL EXCEPTION loading bitmap", e)
            return null
        }
    }

    private fun setupParameterMenu() {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val dm = resources.displayMetrics
        val menuHeight = (dm.heightPixels * 0.40).toInt()

        leftHUDContainer = LinearLayout(this).apply {
            if (isPortrait) {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                    gravity = Gravity.TOP
                }
            } else {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(-2, -1).apply {
                    gravity = Gravity.START
                }
            }
            clipChildren = false
            clipToPadding = false
        }

        parameterPanel = ScrollView(this).apply {
            if (isPortrait) {
                layoutParams = LinearLayout.LayoutParams(-1, menuHeight)
            } else {
                layoutParams = LinearLayout.LayoutParams(850, -1)
            }

            id = View.generateViewId()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    if (!isRebuildingHUD) lastScrollY = scrollY
                }
            }

            post {
                scrollTo(0, lastScrollY)
            }
        }

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 20, 10, if (isPortrait) 60 else 240)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        fpsTextView = TextView(this).apply {
            text = "FPS: --"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(15, 0, 15, 20)
        }
        menuLayout.addView(fpsTextView)

        parameterPanel.addView(menuLayout)

        val toggleBtn = createMenuUtilityButton()

        if (isPortrait) {
            leftHUDContainer.addView(parameterPanel)
            leftHUDContainer.addView(toggleBtn, LinearLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.END
                topMargin = 10
                rightMargin = 30
            })
        } else {
            leftHUDContainer.addView(parameterPanel)
            leftHUDContainer.addView(toggleBtn, LinearLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP
                leftMargin = 10
                topMargin = 50
            })
        }

        populateParameterGroups(menuLayout)
    }

    private fun populateParameterGroups(menuLayout: LinearLayout) {
        var currentGroupContent: LinearLayout? = null

        fun createGroup(title: String, startOpen: Boolean = false) {
            val (container, content) = createCollapsibleGroupView(title, startOpen)
            menuLayout.addView(container)
            currentGroupContent = content
        }

        // Iterate the Chain
        effectChain.effects.forEachIndexed { index, effect ->
            createGroup(effect.name, startOpen = (index == 0))

            // Special case for Mixer to add Source Controls
            if (effect is MixerEffect) {
                // 1. Main Camera (Ensure it exists)
                if (controlsMap.containsKey("CAM_MAIN")) {
                    currentGroupContent?.let { controlsMap["CAM_MAIN"]!!.attachTo(this, it) }
                } else {
                    val camCtrl = CameraSourceControl(this)
                    controls.add(camCtrl); controlsMap[camCtrl.id] = camCtrl
                    renderer.addSource(SourceType.CAMERA, "CAM_MAIN")
                    currentGroupContent?.let { camCtrl.attachTo(this, it) }
                }

                // 2. Dynamic Sources
                sourceControls.forEach { currentGroupContent?.let { p -> it.attachTo(this, p) } }

                // 3. Add Button
                val addBtn = Button(this).apply {
                    text = "+"
                    textSize = 28f
                    setTextColor(Color.WHITE)

                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(0, 0, 0, 0)

                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#333333"))
                        cornerRadius = 15f
                        setStroke(1, Color.GRAY)
                    }
                    layoutParams = LinearLayout.LayoutParams(160, 90).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setMargins(20, 10, 20, 10)
                    }
                    setOnClickListener { showAddSourceDialog() }
                }
                currentGroupContent?.addView(addBtn)
                mixerGroupContainer = currentGroupContent
            } else {
                // Standard Effects
                effect.controls.forEach { ctrl ->
                    currentGroupContent?.let { ctrl.attachTo(this, it) }
                }
            }
        }
    }

    fun getRendererMRot(): Double = renderer.mRotAccum
    fun getRendererCRot(): Double = renderer.cRotAccum

    private fun showAddSourceDialog() {
        if (renderer.sources.size >= 8) {
            Toast.makeText(this, "Max 8 sources reached", Toast.LENGTH_SHORT).show()
            return
        }

        val items = arrayOf("Media (Image/Video)", "RTSP Stream", "Generative Shader")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Source")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        }
                        mediaPickerLauncher.launch(intent)
                    }
                    1 -> showRtspDialog()
                    2 -> showShaderSourceDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun wrapShaderCode(rawCode: String): String {
        val isShadertoy = rawCode.contains("mainImage")
        val prefix = """
            precision highp float;
            varying vec2 v;
            uniform float iTime;
            uniform float uTime;
            uniform vec2 iResolution;
        """.trimIndent()

        return if (isShadertoy) {
            prefix + "\n" + rawCode + "\n" + """
                void main() {
                    mainImage(gl_FragColor, v * iResolution);
                }
            """.trimIndent()
        } else {
            val needsPrecision = !rawCode.contains("precision")
            (if (needsPrecision) prefix + "\n" else "") + rawCode
        }
    }

    private fun attemptAddShaderSource(code: String, shaderName: String) {
        val uniqueId = "SHD_${System.currentTimeMillis()}"
        val channel = renderer.addSource(SourceType.SHADER, uniqueId)
        if (channel != null) {
            channel.customShaderCode = code
            val ctrl = ShaderSourceControl(uniqueId, "SHADER", uniqueId, this)
            // Use the tracked shader name as the subtitle here!
            ctrl.subtitle = shaderName
            addDynamicSourceControl(ctrl)
            Toast.makeText(this, "Shader Added", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Mixer Full", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShaderSourceDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            isClickable = true
        }

        val closeBtn = Button(this).apply {
            text = "✕"; textSize = 24f; setTextColor(Color.GRAY); background = null
            layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 30; rightMargin = 30
            }
            setOnClickListener { dialog.dismiss(); hideSystemUI() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.CENTER; leftMargin = 50; rightMargin = 50
            }
        }

        content.addView(TextView(this).apply {
            text = "ADD GENERATIVE SHADER"; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 30)
        })

        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, 120).apply { bottomMargin=20 }
        }

        val spinnerKeys = BUILTIN_SHADERS.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerKeys)
        val spinner = Spinner(this).apply {
            this.adapter = adapter
            background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius=15f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin=10 }
        }

        val fileBtn = Button(this).apply {
            text = "LOAD FILE"
            setTextColor(Color.WHITE); textSize=12f
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius=15f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin=10 }
            setOnClickListener {
                shaderFileLauncher.launch(arrayOf("*/*"))
            }
        }

        topControls.addView(spinner); topControls.addView(fileBtn)
        content.addView(topControls)

        activeShaderInput = EditText(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); setStroke(2, Color.DKGRAY); cornerRadius = 15f }
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(-1, 600)
            setHorizontallyScrolling(true)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                activeShaderInput?.setText(BUILTIN_SHADERS[spinnerKeys[pos]])
                currentShaderName = spinnerKeys[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        content.addView(activeShaderInput)

        content.addView(Button(this).apply {
            text = "ADD SHADER"
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0066CC")); cornerRadius = 15f }
            layoutParams = LinearLayout.LayoutParams(-1, 120).apply { topMargin = 40 }
            setOnClickListener {
                val code = activeShaderInput?.text.toString().trim()
                if (code.isNotEmpty()) {
                    // Pass the tracked name into the creation function
                    attemptAddShaderSource(code, currentShaderName)
                    dialog.dismiss()
                    hideSystemUI()
                }
            }
        })

        rootLayout.addView(content); rootLayout.addView(closeBtn)
        dialog.setContentView(rootLayout)
        dialog.setOnDismissListener { hideSystemUI(); activeShaderInput = null }
        dialog.show()
    }

    private fun setupGeometrySpecifics(parent: LinearLayout) {
        val axisId = "AXIS"
        val axisCtrl: PropertyControl
        if (controlsMap.containsKey(axisId)) {
            axisCtrl = controlsMap[axisId]!!
        } else {
            axisCtrl = PropertyControl(
                axisId, "COUNT",
                min = 1, max = 25, sliderMax = 25,
                defaultValue = 2,
                layoutStyle = PropertyControl.LayoutStyle.ROW,
                includeInPreset = true, // MUST BE TRUE
                hasModulation = false,
                logPower = 1,
                showValue = true,
                allowSmoothing = false,
                defaultLocked = true    // Default to locked
            ) {
                renderer.axisCount = it.toFloat()
            }
            controls.add(axisCtrl)
            controlsMap[axisId] = axisCtrl
        }
        axisCtrl.attachTo(this, parent)
    }

    private fun createCameraSettingsPanel(): LinearLayout {
        cameraSettingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        val switchBtn = ImageButton(this).apply {
            setImageDrawable(createSwitchCameraDrawable())
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.9f
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(150, 150)
            setOnClickListener { switchCamera(); updateSidebarVisuals() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_CAM_SWITCH", "SWITCH CAMERA")
                    true
                } else false
            }
        }

        cameraSettingsPanel.addView(switchBtn)
        return cameraSettingsPanel
    }

    fun switchCamera() {
        currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    private fun createRecordControls(): LinearLayout {
        // CHANGED: Tight fit (0 padding)
        recordControls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }

        photoBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_camera));
            setBackgroundColor(Color.TRANSPARENT);
            setColorFilter(Color.WHITE);
            alpha = 0.8f;
            scaleX = 1.2f;
            scaleY = 1.2f;
            layoutParams = LinearLayout.LayoutParams(150, 150);
            setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_PHOTO", "TAKE PHOTO")
                    true
                } else false
            }
        }
        recordBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(context, android.R.drawable.presence_video_online))
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            alpha = 0.5f
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(150, 150)
            setOnClickListener { toggleRecording() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_RECORD", "TOGGLE RECORDING")
                    true
                } else false
            }
        }
        recordControls.addView(photoBtn); recordControls.addView(recordBtn)
        return recordControls
    }

    private fun createPresetPanel(): LinearLayout {
        presetPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10, 10, 10, 10)
            clipChildren = false
            clipToPadding = false
        }

        // --- Transition Time + Play Button Row ---
        val transContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 0, 10, 5)
            layoutParams = LinearLayout.LayoutParams(850, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val transId = "TRANS_TIME"
        var transCtrl = controlsMap[transId]
        if (transCtrl == null) {
            transCtrl = PropertyControl(
                transId, "TIME",
                min = 0, max = 300000, sliderMax = 30000,
                defaultValue = 1000,
                layoutStyle = PropertyControl.LayoutStyle.ROW,
                iconResId = android.R.drawable.ic_menu_recent_history,
                includeInPreset = false,
                hasModulation = false,
                logPower = 3,
                showValue = true,
                allowSmoothing = false, // DISABLE SMOOTHING FOR TRANSITION TIME
                valueFormatter = { "%.1fs".format(it / 1000f) }
            ) { transitionMs = it.toLong() }
            controls.add(transCtrl)
            controlsMap[transId] = transCtrl
        }

        val sliderWrapper = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        transCtrl.attachTo(this, sliderWrapper)
        transContainer.addView(sliderWrapper)

        // Play Button with Long Press Mapping
        playBtn = ImageButton(this).apply {
            setImageDrawable(createPlayIcon(isAutoPlaying))
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { leftMargin = 0 }
            setPadding(10, 10, 10, 10)
            setOnClickListener { toggleAutoPlay() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_AUTOPLAY", "AUTO-PLAY")
                    true
                } else false
            }
        }
        updatePlayButtonState()
        transContainer.addView(playBtn)

        presetPanel.addView(transContainer)

        // --- Preset Buttons ---
        val presetRow = FrameLayout(this)
        val scroller = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        presetButtons.clear()
        presetDrawables.clear()
        presetAnimators.values.forEach { it.cancel() }
        presetAnimators.clear()

        // Container for the Long-Press Option Buttons (Save/Map)
        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(240, 0,0,0)); cornerRadius = 15f }
            // Increased height from 110 to 120 to fit text comfortably
            layoutParams = FrameLayout.LayoutParams(-2, 120, Gravity.CENTER)
            elevation = 100f
            isClickable = true
        }

        // We need references to add buttons dynamically
        fun showOptionsForPreset(idx: Int) {
            optionsContainer.removeAllViews()
            pendingSaveIndex = idx

            // SAVE BUTTON
            optionsContainer.addView(Button(this).apply {
                text = "SAVE $idx"
                setTextColor(Color.BLACK)
                textSize = 14f // Increased text size slightly
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false // Important for centering
                setPadding(0, 0, 0, 0) // Remove padding to prevent clipping
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12f }
                layoutParams = LinearLayout.LayoutParams(180, 100).apply { setMargins(15,10,10,10) }
                setOnClickListener {
                    savePreset(idx)
                    optionsContainer.visibility = View.GONE
                }
            })

            // MAP BUTTON (Only if connected)
            if (midiHelper.isConnected) {
                optionsContainer.addView(Button(this).apply {
                    text = "MAP"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(0, 0, 0, 0)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#004400")); setStroke(2, Color.GREEN); cornerRadius = 12f }
                    layoutParams = LinearLayout.LayoutParams(160, 100).apply { setMargins(5,10,15,10) }
                    setOnClickListener {
                        optionsContainer.visibility = View.GONE
                        showMidiLearnOverlay("PRESET_$idx", "PRESET $idx")
                    }
                })
            }

            optionsContainer.visibility = View.VISIBLE
        }

        (9 downTo 1).forEach { idx ->
            val pd = ProgressButtonDrawable(idx.toString())
            if (idx == activePreset) {
                pd.isActive = true
                pd.setProgress(1f)
            }
            presetDrawables[idx] = pd

            val b = TextView(this).apply {
                text = ""
                background = pd
                alpha = 1.0f
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(83, 110).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener {
                    optionsContainer.visibility = View.GONE
                    stopAutoPlay()
                    applyPreset(idx)
                }
                setOnLongClickListener {
                    showOptionsForPreset(idx)
                    true
                }
            }
            btnRow.addView(b)
        }

        scroller.addView(btnRow)
        presetRow.addView(scroller)
        presetRow.addView(optionsContainer)

        saveConfirmBtn = Button(this) // Dummy ref

        presetPanel.addView(presetRow)
        return presetPanel
    }

    private fun toggleAutoPlay() {
        if (isAutoPlaying) {
            stopAutoPlay()
        } else {
            if (autoPlayFilter.isEmpty()) {
                Toast.makeText(this, "No presets selected for Auto-Play", Toast.LENGTH_SHORT).show()
                return
            }
            isAutoPlaying = true
            playBtn.setImageDrawable(createPlayIcon(true)) // White
            Toast.makeText(this, "Auto-Play Started", Toast.LENGTH_SHORT).show()
            triggerNextAutoPlay()
        }
    }

    private fun stopAutoPlay() {
        isAutoPlaying = false
        handler.removeCallbacks(autoPlayRunnable)
        if (::playBtn.isInitialized) playBtn.setImageDrawable(createPlayIcon(false)) // Grey

        // Save Settings on stop
        val prefs = getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
        val filterStr = autoPlayFilter.joinToString(",")
        prefs.edit()
            .putBoolean("AP_RANDOM", autoPlayRandom)
            .putString("AP_FILTER", filterStr)
            .apply()
    }

    private fun triggerNextAutoPlay() {
        if (!isAutoPlaying) return
        if (autoPlayFilter.isEmpty()) {
            stopAutoPlay()
            return
        }

        val sortedFilter = autoPlayFilter.sorted()

        val nextIdx = if (autoPlayRandom) {
            sortedFilter.random()
        } else {
            // Find the next number in the filter larger than current
            // If none, wrap around to the first one in the filter
            sortedFilter.firstOrNull { it > activePreset } ?: sortedFilter.first()
        }

        applyPreset(nextIdx)
    }


    inner class ProgressButtonDrawable(private val label: String) : android.graphics.drawable.Drawable() {
        private var progress = 0f
        var isActive = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            invalidateSelf()
        }

        // Providing intrinsic size helps the layout manager on startup
        override fun getIntrinsicWidth(): Int = 83
        override fun getIntrinsicHeight(): Int = 110

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()

            //
            // Fallback to intrinsic if bounds are 0 (e.g. during animation startup)
            val drawW = if (w > 0) w else 83f
            val drawH = if (h > 0) h else 110f

            val r = 12f

            // 1. Draw Border/Frame
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = if (isActive) Color.WHITE else Color.parseColor("#505050")

            val box = RectF(2f, 2f, drawW - 2f, drawH - 2f)
            canvas.drawRoundRect(box, r, r, paint)

            // 2. Draw Fill
            if (progress > 0) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(100, 255, 255, 255)

                canvas.save()
                val fillHeight = drawH * progress
                canvas.clipRect(0f, drawH - fillHeight, drawW, drawH)
                canvas.drawRoundRect(box, r, r, paint)
                canvas.restore()
            }

            // 3. Draw Text
            val xPos = drawW / 2
            val yPos = (drawH / 2) - ((textPaint.descent() + textPaint.ascent()) / 2)
            textPaint.alpha = if (isActive) 255 else 180
            canvas.drawText(label, xPos, yPos, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            textPaint.alpha = alpha
        }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun createPlayIcon(playing: Boolean): BitmapDrawable {
        // High resolution bitmap
        val size = 200
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply {
            color = if (playing) Color.WHITE else Color.GRAY
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Draw Triangle logic scaled to new size
        val path = Path()
        // Triangle coordinates: Left-Top, Left-Bottom, Right-Middle
        path.moveTo(50f, 40f)
        path.lineTo(50f, 160f)
        path.lineTo(160f, 100f)
        path.close()

        c.drawPath(path, p)

        return BitmapDrawable(resources, b)
    }

    private fun createFlashView() = View(this).apply { setBackgroundColor(Color.WHITE)
        alpha = 0f
        layoutParams = FrameLayout.LayoutParams(-1, -1)
        isClickable = false
        isFocusable = false
    }
    private fun createLogoView() = ImageView(this).apply { setImageDrawable(createLogoDrawable()); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(180, 180).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 40; leftMargin = 40 } }
    private fun createMenuUtilityButton() = Button(this).apply {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        // Set initial icon based on current state
        text = if (isPortrait) {
            if (isMenuExpanded) "▲" else "▼"
        } else {
            if (isMenuExpanded) "◀" else "▶"
        }

        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = null
        alpha = 0.85f
        gravity = Gravity.CENTER
        setPadding(0, 0, 10, 10)
        menuBtn = this
        setOnClickListener { toggleMenu() }
    }
    private fun createCollapsibleGroupView(title: String, startOpen: Boolean): Pair<LinearLayout, LinearLayout> {
        // Check if this group was previously expanded
        val effectivelyOpen = if (expandedGroups.contains(title)) true else startOpen
        if (effectivelyOpen) expandedGroups.add(title)

        val groupContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 } }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(15, 12, 15, 12); background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = 8f; setStroke(1, Color.parseColor("#44FFFFFF")) } }
        val arrow = TextView(this).apply { text = "▶"; textSize = 9f; setTextColor(Color.LTGRAY); layoutParams = LinearLayout.LayoutParams(50, -2); rotation = if (effectivelyOpen) 90f else 0f }
        val label = TextView(this).apply { text = title; textSize = 10f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); letterSpacing = 0.15f }
        header.addView(arrow); header.addView(label)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (effectivelyOpen) View.VISIBLE else View.GONE; setPadding(6, 6, 6, 6) }

        header.setOnClickListener {
            val isVisible = content.visibility == View.VISIBLE
            if (isVisible) {
                content.visibility = View.GONE
                arrow.animate().rotation(0f).setDuration(200).start()
                expandedGroups.remove(title)
            } else {
                content.visibility = View.VISIBLE
                arrow.animate().rotation(90f).setDuration(200).start()
                expandedGroups.add(title)
            }
        }
        groupContainer.addView(header); groupContainer.addView(content)
        return Pair(groupContainer, content)
    }

    private fun showRtspDialog() {
        val prefs = getSharedPreferences("SpaceBeam_RTSP", Context.MODE_PRIVATE)
        val historyKey = "RTSP_HISTORY_LIST"

        // 1. Define Defaults
        val defaults = listOf(
            "rtsp://192.168.xx.xx:8554/screen",
            "rtsp://192.168.xx.xx:8080/h264_ulaw.sdp"
        )

        // 2. Load User History
        val userHistory = mutableListOf<String>()
        val jsonStr = prefs.getString(historyKey, "[]")
        try {
            val jsonArr = JSONArray(jsonStr)
            for (i in 0 until jsonArr.length()) {
                userHistory.add(jsonArr.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Combine List
        val displayList = (defaults + userHistory).distinct().toMutableList()
        val lastUsed = prefs.getString("LAST_RTSP", "")

        // --- FULLSCREEN DIALOG SETUP ---
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // Root Container (Black Background)
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }

        // Close Button (Top Right)
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.GRAY)
            background = null
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 30
                rightMargin = 30
            }
            setOnClickListener {
                dialog.dismiss()
                hideSystemUI() // Restore immersive mode
            }
        }

        // Center Content Container
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Position slightly above center to avoid keyboard coverage
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                topMargin = 300 // Push down from top
                leftMargin = 50
                rightMargin = 50
            }
        }

        // Title
        val titleView = TextView(this).apply {
            text = "CONNECT TO RTSP STREAM"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }

        // Input Row (Input + Arrow)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 15f
            }
            setPadding(20, 10, 20, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayList)

        val input = AutoCompleteTextView(this).apply {
            setText(if (lastUsed!!.isNotEmpty()) lastUsed else displayList[0])
            setTextColor(Color.WHITE)
            textSize = 18f
            background = null // Remove default underline
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            threshold = 1
            dropDownHeight = 600 // Limit dropdown height

            // Allow white text on dropdown
            setDropDownBackgroundDrawable(GradientDrawable().apply { setColor(Color.DKGRAY) })

            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setAdapter(adapter)
        }

        val arrowBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setColorFilter(Color.GRAY) // Tint arrow
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                input.showDropDown()
                // Hide keyboard to see dropdown better
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(input.windowToken, 0)
            }
        }

        inputRow.addView(input)
        inputRow.addView(arrowBtn)

        // Connect Button
        val connectBtn = Button(this).apply {
            text = "CONNECT"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0066CC")) // Blue-ish
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply {
                topMargin = 60
            }
        }

        // Logic
        fun performConnect() {
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                // Update History
                userHistory.remove(url)
                userHistory.add(0, url)
                if (userHistory.size > 10) {
                    val trimmed = userHistory.take(10)
                    userHistory.clear()
                    userHistory.addAll(trimmed)
                }

                val saveArray = JSONArray()
                userHistory.forEach { saveArray.put(it) }
                prefs.edit().putString(historyKey, saveArray.toString()).putString("LAST_RTSP", url).apply()

                // Hide Keyboard
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(input.windowToken, 0)

                dialog.dismiss()
                hideSystemUI()
                attemptConnectRtspFromFullscreen(url)
            }
        }

        connectBtn.setOnClickListener { performConnect() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performConnect()
                true
            } else false
        }

        contentContainer.addView(titleView)
        contentContainer.addView(inputRow)
        contentContainer.addView(connectBtn)

        rootLayout.addView(contentContainer)
        rootLayout.addView(closeBtn)

        dialog.setContentView(rootLayout)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialog.setOnDismissListener { hideSystemUI() }
        dialog.show()

        input.requestFocus()
        input.setSelection(input.text.length)
    }

    // A small helper to bridge the gap since the original function expected an AlertDialog
    private fun attemptConnectRtspFromFullscreen(url: String) {
        val uniqueId = "RTSP_${System.currentTimeMillis()}"
        val player = createOptimizedExoPlayer()
        player.volume = 0f

        val channel = renderer.addSource(SourceType.RTSP, uniqueId)
        if (channel == null) {
            Toast.makeText(this, "Mixer Full", Toast.LENGTH_SHORT).show()
            player.release()
            return
        }

        Toast.makeText(this, "Connecting to Stream...", Toast.LENGTH_SHORT).show()

        glView.queueEvent {
            val s = channel.getSurfaceForInput()
            runOnUiThread {
                try {
                    player.setVideoSurface(s)
                    val rtspSource = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .createMediaSource(MediaItem.fromUri(url))

                    player.setMediaSource(rtspSource)
                    player.prepare()
                    player.play()

                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                if (!controlsMap.containsKey(uniqueId)) {
                                    val ctrl = RtspSourceControl(uniqueId, "STREAM", uniqueId, this@MainActivity, player)
                                    ctrl.subtitle = url
                                    addDynamicSourceControl(ctrl)
                                    Toast.makeText(this@MainActivity, "Connected", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0) {
                                channel.updateSize(videoSize.width, videoSize.height)
                                if (videoSize.height > videoSize.width) channel.rotation = -90f else channel.rotation = 0f
                            }
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Toast.makeText(this@MainActivity, "Connection Failed", Toast.LENGTH_LONG).show()
                            renderer.removeSource(uniqueId)
                            player.release()
                        }
                    })
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    renderer.removeSource(uniqueId)
                    player.release()
                }
            }
        }
    }

    private fun applyReadabilityStyle() {
        val getBg = { alpha: Int -> GradientDrawable().apply {
            setColor(Color.argb(alpha, 10, 10, 10))
            setStroke(2, Color.argb(120, 80, 80, 80))
            cornerRadius = 25f
        } }
        val getCircleBg = { alpha: Int -> getBg(alpha).apply { shape = GradientDrawable.OVAL } }

        val panels = listOf(cameraSettingsPanel, presetPanel, recordControls)
        val utils = listOf(menuBtn, orientationBtn, settingsBtn)

        // Reset clip
        panels.forEach { it.clipToOutline = true }
        parameterPanel.background = null

        when (readabilityLevel) {
            1, 2 -> {
                val alpha = if (readabilityLevel == 1) 180 else 120

                // CHANGED: Reduced padding from 15 to 5 for a tighter fit
                panels.forEach {
                    it.background = getBg(alpha)
                    it.setPadding(5, 5, 5, 5)
                }

                parameterPanel.background = getBg(alpha)
                parameterPanel.clipToOutline = true

                utils.forEach { it.background = getCircleBg(alpha) }

                applyRecursiveGlow(overlayHUD, readabilityLevel == 2)
            }
            else -> {
                panels.forEach { it.setPadding(0, 0, 0, 0); it.background = null }
                parameterPanel.background = null
                utils.forEach { it.background = null }
                applyRecursiveGlow(overlayHUD, false)
            }
        }
    }


    private fun applyRecursiveGlow(view: View, enabled: Boolean) {
        if (view is TextView) { if (enabled) view.setShadowLayer(50f, 0f, 0f, Color.BLACK) else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) }
        else if (view is ImageButton || view is Button) { view.elevation = if (enabled) 50f else 0f }
        if (view is ViewGroup) (0 until view.childCount).forEach { applyRecursiveGlow(view.getChildAt(it), enabled) }
    }

    public fun toggleReadability() { readabilityLevel = (readabilityLevel + 1) % 3; applyReadabilityStyle() }

    private fun toggleRecording() {
        if (!isRecording) {
            val fileName = "SB_${System.currentTimeMillis()}.mp4"
            val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
            renderer.startRecording(tempFile); isRecording = true; recordingSeconds = 0; recordBtn.alpha = 1.0f
            recordTicker = object : Runnable {
                override fun run() {
                    recordingSeconds++; val m = recordingSeconds / 60; val s = recordingSeconds % 60
                    recordBtn.setImageDrawable(textToIcon("%d:%02d".format(m, s), 250f, Color.RED)); handler.postDelayed(this, 1000)
                }
            }; handler.post(recordTicker!!)
        } else {
            renderer.stopRecording { savedFile ->
                isRecording = false; recordTicker?.let { handler.removeCallbacks(it) }
                runOnUiThread {
                    recordBtn.setImageDrawable(ContextCompat.getDrawable(this, android.R.drawable.presence_video_online)); recordBtn.alpha = 0.5f; if (savedFile != null && savedFile.exists()) saveVideoToGallery(savedFile)
                }
            }
        }
    }

    private fun saveVideoToGallery(file: File) {
        if (file.length() == 0L) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpaceBeam"); put(MediaStore.Video.Media.IS_PENDING, 1) }
        }
        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(it, values, null, null) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                file.delete(); Toast.makeText(this, "Video Saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    public fun globalReset() {
        renderer.stopRotationAnim()
        controls.forEach { it.stopAnimation() }

        renderer.mRotAccum = 0.0
        renderer.cRotAccum = 0.0
        renderer.lRotAccum = 0.0

        effectChain.effects.forEach { it.reset() }

        renderer.resetPhases()
        renderer.flipX = 1f
        renderer.flipY = -1f
        renderer.rot180 = false
        activePreset = -1
        updatePresetHighlights()

        // Reset all controls
        controls.forEach { it.reset() }

        updateSidebarVisuals()
    }

    private fun updatePresetHighlights() {
        presetButtons.forEach { (idx, btn) -> btn.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); if (idx == activePreset) setStroke(4, Color.WHITE); cornerRadius = 12f } }
    }

    private fun triggerFlashPulse() {
        flashOverlay.alpha = 0.6f; flashOverlay.animate().alpha(0f).setDuration(400).start()
        photoBtn.animate().scaleX(1.8f).scaleY(1.8f).setDuration(100).withEndAction { photoBtn.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start() }.start()
    }

    private fun updateSidebarVisuals() {
        // Global Flip/Rot buttons are removed.
        // This function is kept empty to prevent calls in onConfigurationChanged from crashing.
    }

    fun hideSystemUI() {
        // Enables "Immersive Sticky" mode.
        // The layout is laid out as if the screen is full (LAYOUT_FULLSCREEN),
        // preventing resizing/squeezing when bars appear briefly.
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return

        // 1. Cancel previous UI animations
        presetAnimators.values.forEach { it.cancel() }
        presetAnimators.clear()

        // Reset all buttons
        presetDrawables.forEach { (id, drawable) ->
            drawable.setProgress(0f)
            drawable.isActive = (id == idx)
            drawable.invalidateSelf()
        }

        activePreset = idx
        // Mark time for rotation persistence
        transitionStartTime = System.currentTimeMillis()

        val durationSec = transitionMs / 1000f

        // 2. Animate the Button Fill
        val btnDrawable = presetDrawables[idx]
        if (btnDrawable != null) {
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = transitionMs
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { va ->
                    val progress = va.animatedValue as Float
                    btnDrawable.setProgress(progress)
                    btnDrawable.invalidateSelf()
                }
                start()
            }
            presetAnimators[idx] = anim
        }

        val startMRot = renderer.mRotAccum
        val startCRot = renderer.cRotAccum
        val targetMRot = round(startMRot / 360.0) * 360.0
        val targetCRot = round(startCRot / 360.0) * 360.0

        renderer.animateRotationTo(targetMRot, targetCRot, durationSec)

        controls.forEach { control ->
            if (!control.includeInPreset) return@forEach

            var snap = p.controlSnapshots[control.id]

            // Legacy support: If loading an old preset where AXIS wasn't in the map
            if (snap == null && control.id == "AXIS") {
                snap = PropertyControl.Snapshot(
                    value = p.axis, // Direct value (e.g. 6 remains 6)
                    active = false,
                    rate = 0, depth = 0, shape = "SINE", smoothing = 0
                )
            }

            if (snap != null) {
                // The 'restore' function handles the "isLocked" check internally
                control.restore(snap, durationSec)
            }
        }
        updateSidebarVisuals()

        // 4. Schedule next Auto-Play
        if (isAutoPlaying) {
            handler.removeCallbacks(autoPlayRunnable)
            handler.postDelayed(autoPlayRunnable, transitionMs + autoPlayDurationMs)
        }
    }

    private fun savePreset(idx: Int) {
        // associate snapshots using the control's current live state
        val snapshots = controls.filter { it.includeInPreset }.associate { it.id to it.getSnapshot() }
        val axisVal = controlsMap["AXIS"]?.value ?: 2

        val newPreset = Preset(snapshots, renderer.flipX, renderer.flipY, renderer.rot180, axisVal)
        presets[idx] = newPreset
        activePreset = idx

        try {
            val rootObj = JSONObject()
            rootObj.put("axis", newPreset.axis)
            rootObj.put("flipX", newPreset.flipX.toDouble())
            rootObj.put("flipY", newPreset.flipY.toDouble())
            rootObj.put("rot180", newPreset.rot180)

            val controlsObj = JSONObject()
            newPreset.controlSnapshots.forEach { (key, snap) ->
                val snapObj = JSONObject()
                snapObj.put("v", snap.value)
                snapObj.put("r", snap.rate)
                snapObj.put("d", snap.depth)
                snapObj.put("shape", snap.shape)
                snapObj.put("s", snap.smoothing)
                controlsObj.put(key, snapObj)
            }
            rootObj.put("controls", controlsObj)
            val jsonOutput = rootObj.toString()
            // LOGGING LINE ADDED HERE
            Log.d("SpaceBeamPreset", "SAVED PRESET $idx: $jsonOutput")

            getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE)
                .edit()
                .putString("PRESET_$idx", rootObj.toString())
                .apply()

            updatePresetHighlights()
            Toast.makeText(this, "Preset $idx Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createLockedIconDrawable(locked: Boolean): BitmapDrawable {
        // 1. Create a larger canvas (240x240) for high resolution
        val size = 240
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)

        // 2. Draw the standard System Rotate Icon (The arrows)
        val icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_rotate)?.mutate()
        if (icon != null) {
            // Minimal padding so the icon uses the full size
            icon.setBounds(10, 10, size - 10, size - 10)
            icon.setTint(Color.WHITE) // Arrows always white
            icon.draw(c)
        }

        // 3. Draw the center dot to indicate state
        // User Request: Locked (On) = White, Unlocked (Off) = Grey
        val p = Paint().apply {
            color = if (locked) Color.WHITE else Color.parseColor("#505050") // Dark Grey for Off
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // 4. Draw circle at center (Increased radius for visibility)
        c.drawCircle(size / 2f, size / 2f, 25f, p)

        return BitmapDrawable(resources, b)
    }

    private fun createOrientationButton() = ImageButton(this).apply {
        // Set initial icon
        setImageDrawable(createLockedIconDrawable(isOrientationLocked))

        // Visual state
        background = null
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(15, 15, 15, 15) // Reduced padding to maximize icon size
        alpha = 0.9f

        orientationBtn = this

        layoutParams = FrameLayout.LayoutParams(140, 140).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 180
            rightMargin = 35
        }

        setOnClickListener {
            isOrientationLocked = !isOrientationLocked

            // Update Icon state
            setImageDrawable(createLockedIconDrawable(isOrientationLocked))

            if (isOrientationLocked) {
                // LOCK: Freezes the screen in the CURRENT orientation
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                Toast.makeText(context, "Orientation Locked", Toast.LENGTH_SHORT).show()
            } else {
                // UNLOCK: Allow sensor rotation
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                Toast.makeText(context, "Orientation Unlocked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initDefaultPresets() {
        val prefs = getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE)

        fun p(ax: Int, vararg overrides: Any): Preset {
            val baseSnapshots = controls.associate {
                it.id to PropertyControl.Snapshot(
                    value = it.defaultValue,
                    active = false,
                    rate = 200,
                    depth = 0,
                    shape = "SINE",
                    smoothing = 500
                )
            }.toMutableMap()

            var i = 0
            while (i < overrides.size) {
                val key = overrides[i] as String
                val value = overrides[i + 1] as Int
                if (i + 3 < overrides.size && overrides[i + 2] is Int && overrides[i + 3] is Int) {
                    val rate = overrides[i + 2] as Int
                    val depth = overrides[i + 3] as Int
                    var shape = "SINE"
                    var step = 4
                    if (i + 4 < overrides.size && overrides[i + 4] is String) {
                        val potentialShape = overrides[i + 4] as String
                        val isValid = try { PropertyControl.WaveShape.valueOf(potentialShape); true } catch (e: Exception) { false }
                        if (isValid) { shape = potentialShape; step = 5 }
                    }
                    baseSnapshots[key] = PropertyControl.Snapshot(value, true, rate, depth, shape, 500)
                    i += step
                } else {
                    baseSnapshots[key] = PropertyControl.Snapshot(value, false, 0, 0, "SINE", 500)
                    i += 2
                }
            }
            return Preset(baseSnapshots, 1f, -1f, false, ax)
        }

        // Load presets 1-9
        for (idx in 1..9) {
            val savedJson = prefs.getString("PRESET_$idx", null)
            if (savedJson != null) {
                try {
                    val obj = JSONObject(savedJson)
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
                            s.optInt("s", 500)
                        )
                    }
                    presets[idx] = Preset(snapshots, fx, fy, r180, ax)
                } catch (e: Exception) {
                    Log.e("SpaceBeam", "Failed to load preset $idx, using factory default", e)
                    // Fallback inside the loop if JSON is corrupted
                    loadFactoryDefault(idx, ::p)
                }
            } else {
                loadFactoryDefault(idx, ::p)
            }
        }
    }

    private fun loadFactoryDefault(idx: Int, pFunc: (Int, Array<out Any>) -> Preset) {
        val base = arrayOf("CAM_MAIN", 1000, "BRIT", 500, "CONTRAST", 500, "VIBRANCE", 500, "K_AMT", 1000)

        presets[idx] = when(idx) {
            1 -> pFunc(2, base + arrayOf("M_ZOOM", 700, "C_ZOOM", 700))
            2 -> pFunc(2, base + arrayOf("M_TX", 725, "M_TY", 628, "M_ANGLE", 0, 122, 758, "M_ZOOM", 345, 400, 478, "WOBBLE_SINE"))
            3 -> pFunc(2, base + arrayOf("M_ANGLE", 0, 169, 1000, "RAMP", "M_ZOOM", 366, "M_TX", 500, 480, 378, "M_TY", 546, 340, 698, "M_TILTX", 500, 268, 788, "M_TILTY", 500, 241, 732, "M_RGB", 0, 200, 1000, "WOBBLE_SINE"))
            4 -> pFunc(2, base + arrayOf("M_ANGLE", 172, 262, 287, "M_ZOOM", 161, 531, 316, "M_TX", 500, 235, 184, "M_TY", 500, 217, 218, "M_TILTX", 500, 242, 305, "M_TILTY", 500, 318, 343, "C_ZOOM", 1000, 583, 365, "HUE", 184, 298, 505, "RAMP", "GLOW", 172, "CONTRAST", 718, "VIBRANCE", 899))
            5 -> pFunc(2, base + arrayOf("M_ANGLE", 172, 262, 287, "M_ZOOM", 1000, 531, 576, "M_TX", 500, 431, 525, "M_TY", 500, 217, 644, "RANDOM_SMOOTH", "M_TILTX", 500, 498, 1000, "M_TILTY", 500, 318, 1000, "C_ZOOM", 1000, 583, 365, "NEG", 1000, "GLOW", 485, "CONTRAST", 788, "VIBRANCE", 899))
            6 -> pFunc(2, base + arrayOf("3D_MIX", 1000, "S_FOV", 675, 553, 453, "WOBBLE_SINE", "S_SPEED", 775, "T_FOG", 13, "M_ANGLE", 172, 262, 287, "M_ZOOM", 597, "M_TX", 500, 431, 40, "M_TY", 500, 217, 34, "M_TILTX", 500, 498, 303, "M_TILTY", 500, 318, 345, "RANDOM_SMOOTH", "C_ZOOM", 1000, 583, 365, "TWIST", 465, 351, 842, "GLOW", 178, "CONTRAST", 522, "VIBRANCE", 853))
            7 -> pFunc(2, base + arrayOf("3D_MIX", 1000, "S_SPEED", 206, "S_FOV", 481, "T_WAVE_STR", 454, "T_WAVE_POS", 20, 375, 1000, "RAMP", "M_ANGLE", 870, "M_ZOOM", 168, "M_TX", 500, 320, 328, "M_TY", 500, 323, 343, "CURVE", 332, 200, 718, "WOBBLE_SINE", "FLUX", 0, 389, 358, "WOBBLE_SINE", "GLOW", 178, "CONTRAST", 522, "VIBRANCE", 853))
            8 -> pFunc(2, base + arrayOf("3D_MIX", 1000, "S_SHAPE", 1000, 343, 785, "S_FOV", 481, 496, 704, "S_SPEED", 1000, "T_FOG", 12, "T_FOG_H", 0, 200, 1000, "RAMP", "T_FOG_S", 1000, "M_ANGLE", 172, 262, 287, "M_ZOOM", 349, "M_TX", 500, 431, 40, "M_TY", 500, 217, 34, "M_TILTX", 500, 498, 303, "M_TILTY", 500, 318, 469, "C_ZOOM", 1000, 583, 365, "GLOW", 285, "CONTRAST", 786, "VIBRANCE", 828))
            9 -> pFunc(2, base + arrayOf("UTWIRL", 1000, "S_WIDE", 1000, "S_ACTIVITY", 677, "SWIRL_SPEED", 609, "S_FOG", 255, "S_FOG_FALLOFF", 422, "M_ANGLE", 0, 60, 1000, "RAMP", "M_ZOOM", 262, 150, 200, "HUE", 0, 80, 0, "RAMP", "GLOW", 600, "VIBRANCE", 600))
            else -> pFunc(2, emptyArray())
        }
    }

    private fun toggleHud() {
        isHudVisible = !isHudVisible; overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE; if (isHudVisible) hideSystemUI()
    }

    private fun toggleMenu() {
        PropertyControl.closeActiveMenu()
        isMenuExpanded = !isMenuExpanded

        // Toggle visibility of the panel (and its background)
        parameterPanel.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE

        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        menuBtn.text = if (isPortrait) {
            if (isMenuExpanded) "▲" else "▼" // Down to hide, Up to show
        } else {
            if (isMenuExpanded) "◀" else "▶" // Left to hide, Right to show
        }
    }

    inner class KaleidoscopeRenderer(private val ctx: MainActivity) : GLSurfaceView.Renderer {
        private var fpsFrameCount = 0
        private var fpsLastCalcTime = System.currentTimeMillis()
        var globalTime = 0f
        private var simpleProgram = 0
        private var copyOesProgram = 0
        private var copy2dProgram = 0
        val stMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

        @Volatile private var isSurfaceReady = false
        private val mvpMatrix = FloatArray(16)
        private val identityMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

        // Rotation accumulation logic (shared context)
        var mRotAccum = 0.0
        var cRotAccum = 0.0
        var lRotAccum = 0.0
        var axisCount = 2.0f
        var flipX = 1.0f
        var flipY = -1.0f
        var rot180 = false

        // Standard 1080p Resolution
        private val FIXED_WIDTH = 1920
        private val FIXED_HEIGHT = 1080
        private var viewWidth = 1
        private var viewHeight = 1

        private var lastTime = System.nanoTime()
        private var deltaTime = 0.0f

        val sources = java.util.concurrent.CopyOnWriteArrayList<SourceChannel>()
        private val MAX_SOURCES = 8

        inner class SourceChannel(val type: SourceType, val id: String) : SurfaceTexture.OnFrameAvailableListener {
            @Volatile var isReady = false
            var onSurfaceReady: ((Surface) -> Unit)? = null
            var inputTexId = 0
            var surfaceTexture: SurfaceTexture? = null
            var surface: Surface? = null
            var fboId = 0; var fboTexId = 0

            var width = 1920; var height = 1080

            var rotation = 0f
            var userFlipX = 1.0f; var userFlipY = 1.0f; var userRot180 = false
            var bitmap: Bitmap? = null
            @Volatile var imageUploaded = false
            @Volatile var frameAvailable = false
            private val frameSync = Object()

            var customShaderCode: String? = null
            var customProgram: Int = 0

            fun init() {
                if (isReady) return
                while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {}

                val fb = IntArray(1); val tx = IntArray(1)
                GLES20.glGenFramebuffers(1, fb, 0); GLES20.glGenTextures(1, tx, 0)
                fboId = fb[0]; fboTexId = tx[0]
                if (fboId == 0 || fboTexId == 0) return

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, FIXED_WIDTH, FIXED_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                // Skip Surface generation for Shaders
                if (type == SourceType.SHADER) {
                    val vSrc = """
                        attribute vec4 p; attribute vec2 t; varying vec2 v;
                        uniform vec2 uFlip; uniform float uRotation;
                        void main() {
                            gl_Position = p;
                            vec2 uv = t - 0.5;
                            uv = uv * uFlip;
                            float c = cos(uRotation); float s = sin(uRotation);
                            uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                            v = uv + 0.5;
                        }
                    """.trimIndent()

                    val fSrc = ctx.wrapShaderCode(customShaderCode ?: "void main(){ gl_FragColor=vec4(0.0); }")
                    customProgram = ShaderHelper.createProgram(vSrc, fSrc)

                    if (customProgram == 0) {
                        val errSrc = ctx.wrapShaderCode("void main(){ gl_FragColor=vec4(1.0, 0.0, 0.0, 1.0); }")
                        customProgram = ShaderHelper.createProgram(vSrc, errSrc)
                        ctx.runOnUiThread { Toast.makeText(ctx, "Shader Compile Error", Toast.LENGTH_LONG).show() }
                    }
                    isReady = true
                    return
                }

                val inp = IntArray(1); GLES20.glGenTextures(1, inp, 0)
                inputTexId = inp[0]
                if (inputTexId == 0) return

                if (type == SourceType.MEDIA_IMAGE) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    imageUploaded = false
                } else {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    surfaceTexture = SurfaceTexture(inputTexId)
                    surfaceTexture?.setDefaultBufferSize(width, height)
                    surfaceTexture?.setOnFrameAvailableListener(this)
                    surface = Surface(surfaceTexture)
                    if (onSurfaceReady != null) {
                        val s = surface!!
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onSurfaceReady?.invoke(s) }
                    }
                }
                isReady = true
            }

            fun getSurfaceForInput(): Surface? {
                if (!isReady && inputTexId == 0) init()
                return surface
            }

            override fun onFrameAvailable(st: SurfaceTexture?) {
                synchronized(frameSync) { frameAvailable = true }
                glView.requestRender()
            }

            fun release() {
                isReady = false
                if (customProgram != 0) { GLES20.glDeleteProgram(customProgram); customProgram = 0 }
                if (surface != null) { surface?.release(); surface = null }
                if (surfaceTexture != null) { surfaceTexture?.release(); surfaceTexture = null }
                if (inputTexId != 0) { val t = IntArray(1){inputTexId}; GLES20.glDeleteTextures(1, t, 0); inputTexId = 0 }
                if (fboId != 0) { val f = IntArray(1){fboId}; GLES20.glDeleteFramebuffers(1, f, 0); fboId = 0 }
                if (fboTexId != 0) { val t = IntArray(1){fboTexId}; GLES20.glDeleteTextures(1, t, 0); fboTexId = 0 }
            }

            fun updateSize(w: Int, h: Int) {
                width = w; height = h
                if (type != SourceType.MEDIA_IMAGE && type != SourceType.SHADER) {
                    glView.queueEvent { surfaceTexture?.setDefaultBufferSize(w, h) }
                }
            }

            fun processToFbo() {
                if (!isReady) return

                if (type == SourceType.SHADER) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                    GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(customProgram)

                    val time1 = GLES20.glGetUniformLocation(customProgram, "iTime")
                    if (time1 >= 0) GLES20.glUniform1f(time1, globalTime)

                    val time2 = GLES20.glGetUniformLocation(customProgram, "uTime")
                    if (time2 >= 0) GLES20.glUniform1f(time2, globalTime)

                    val resLoc = GLES20.glGetUniformLocation(customProgram, "iResolution")
                    if (resLoc >= 0) GLES20.glUniform2f(resLoc, FIXED_WIDTH.toFloat(), FIXED_HEIGHT.toFloat())

                    val flipLoc = GLES20.glGetUniformLocation(customProgram, "uFlip")
                    if (flipLoc >= 0) GLES20.glUniform2f(flipLoc, userFlipX, userFlipY)

                    val rotLoc = GLES20.glGetUniformLocation(customProgram, "uRotation")
                    if (rotLoc >= 0) GLES20.glUniform1f(rotLoc, Math.toRadians((rotation + if(userRot180) 180f else 0f).toDouble()).toFloat())

                    ShaderHelper.bindQuad(customProgram)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    return
                }

                val program = if (type == SourceType.MEDIA_IMAGE) copy2dProgram else copyOesProgram
                val target = if (type == SourceType.MEDIA_IMAGE) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES

                if (program == 0) return

                if (type == SourceType.MEDIA_IMAGE) {
                    if (!imageUploaded && bitmap != null) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(target, inputTexId)
                        try {
                            android.opengl.GLUtils.texImage2D(target, 0, bitmap, 0)
                            imageUploaded = true
                        } catch (e: Exception) { }
                    }
                    if (!imageUploaded) return
                } else {
                    synchronized(frameSync) {
                        if (frameAvailable) {
                            try { surfaceTexture?.updateTexImage(); frameAvailable = false } catch (e: Exception) { }
                        }
                    }
                    if (type == SourceType.CAMERA) {
                        android.opengl.Matrix.setIdentityM(stMatrix, 0)
                    } else {
                        surfaceTexture?.getTransformMatrix(stMatrix)
                    }
                }

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(target, inputTexId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)

                val extraRot = if (userRot180) 180f else 0f
                val finalRot = rotation + extraRot
                val rad = Math.toRadians(-finalRot.toDouble()).toFloat()
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRotation"), rad)

                GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFlip"), userFlipX, userFlipY)

                val isSideways = (kotlin.math.abs(rotation) % 180f) > 45f
                val effectiveW = if (isSideways) height.toFloat() else width.toFloat()
                val effectiveH = if (isSideways) width.toFloat() else height.toFloat()
                val fboAspect = FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat()
                val safeH = if (effectiveH > 0) effectiveH else 1.0f
                val srcAspect = effectiveW / safeH
                var sx = 1.0f; var sy = 1.0f
                if (fboAspect > srcAspect) { sy = srcAspect / fboAspect } else { sx = fboAspect / srcAspect }
                if (isSideways) { val temp = sx; sx = sy; sy = temp }

                GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), sx, sy)

                if (program == copyOesProgram) {
                    val stLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
                    GLES20.glUniformMatrix4fv(stLoc, 1, false, stMatrix, 0)
                }

                ShaderHelper.bindQuad(program)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }

        fun addSource(type: SourceType, id: String, bitmap: Bitmap? = null): SourceChannel? {
            if (sources.size >= MAX_SOURCES) return null
            val ch = SourceChannel(type, id)
            ch.bitmap = bitmap
            if (bitmap != null) {
                ch.width = bitmap.width; ch.height = bitmap.height
            }
            sources.add(ch)
            return ch
        }

        fun removeSource(id: String) {
            val toRemove = sources.find { it.id == id }
            if (toRemove != null) {
                sources.remove(toRemove)
                glView.queueEvent { toRemove.release() }
            }
        }

        fun getSource(id: String): SourceChannel? = sources.find { it.id == id }

        private var fboId = 0; private var fboTexId = 0
        private var captureRequested = false
        private var videoRecorder: VideoRecorder? = null
        private var recordSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var pendingRecordFile: File? = null
        private var onStopCallback: ((File?) -> Unit)? = null
        private var isStopRequested = false
        private var recordStartTimeNs: Long = 0
        private var mSavedDisplay = EGL14.EGL_NO_DISPLAY
        private var mSavedContext = EGL14.eglGetCurrentContext()
        private var mEglConfig: EGL14EGLConfig? = null
        private var extSurfaceArgs: Triple<Surface, Int, Int>? = null
        private var extEglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var extWidth = 0; private var extHeight = 0

        private var rotTargetM: Double? = null; private var rotStartM: Double = 0.0
        private var rotTargetC: Double? = null; private var rotStartC: Double = 0.0
        private var rotAnimDuration: Float = 0f; private var rotAnimTime: Float = 0f; private var isRotAnimating = false

        fun animateRotationTo(targetM: Double, targetC: Double, duration: Float) {
            rotTargetM = targetM; rotStartM = mRotAccum
            rotTargetC = targetC; rotStartC = cRotAccum
            rotAnimDuration = duration; rotAnimTime = 0f; isRotAnimating = true
        }
        fun stopRotationAnim() { isRotAnimating = false }
        fun resetPhases() { ctx.controls.forEach { it.lfoPhase = 0.0 }; mRotAccum = 0.0; cRotAccum = 0.0; lRotAccum = 0.0 }
        fun capturePhoto() { captureRequested = true }
        fun stopRecording(callback: (File?) -> Unit) { onStopCallback = callback; isStopRequested = true }
        fun startRecording(file: File) { pendingRecordFile = file; recordStartTimeNs = 0 }
        fun setExternalSurface(s: Surface, w: Int, h: Int) { extSurfaceArgs = Triple(s, w, h) }
        fun removeExternalSurface() { extSurfaceArgs = null }
        fun provideCameraSurface(req: SurfaceRequest) {
            val cam = getSource("CAM_MAIN") ?: return
            if (cam.isReady && cam.surface != null) {
                req.provideSurface(cam.surface!!, ContextCompat.getMainExecutor(ctx)) {}
            } else {
                cam.onSurfaceReady = { surface -> req.provideSurface(surface, ContextCompat.getMainExecutor(ctx)) {} }
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: GL10EGLConfig?) {
            setupEGL()
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"

            val fSrcCopyOes = """#extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 v; 
            uniform samplerExternalOES uTex; 
            uniform vec2 uScale; 
            uniform float uRotation;
            uniform vec2 uFlip;
            uniform mat4 uSTMatrix;
            void main() {
                vec2 uv = v - 0.5;
                uv = uv * uScale;
                uv = uv * uFlip; 
                float c = cos(uRotation);
                float s = sin(uRotation);
                uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                uv = uv + 0.5;
                uv = abs(mod(uv + 1.0, 2.0) - 1.0);
                
                vec2 stUV = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(uTex, stUV);
            }""".trimIndent()
            copyOesProgram = ShaderHelper.createProgram(vSrc, fSrcCopyOes)

            val fSrcCopy2d = """
            precision mediump float; varying vec2 v; 
            uniform sampler2D uTex; 
            uniform vec2 uScale; 
            uniform float uRotation;
            uniform vec2 uFlip;
            void main() {
                vec2 uv = v - 0.5;
                uv = uv * uScale;
                uv = uv * uFlip;
                float c = cos(uRotation);
                float s = sin(uRotation);
                uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                uv = uv + 0.5;
                uv = abs(mod(uv + 1.0, 2.0) - 1.0);
                
                gl_FragColor = texture2D(uTex, uv);
            }""".trimIndent()
            copy2dProgram = ShaderHelper.createProgram(vSrc, fSrcCopy2d)

            val fSimple = "precision mediump float; varying vec2 v; uniform sampler2D uTex; void main() { gl_FragColor = texture2D(uTex, v); }"
            simpleProgram = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * p; v = t; }", fSimple)

            initMainFBO(FIXED_WIDTH, FIXED_HEIGHT)
            ctx.effectChain.init(FIXED_WIDTH, FIXED_HEIGHT)

            sources.forEach { it.init() }
            ctx.runOnUiThread { ctx.startCamera() }
        }

        private fun initMainFBO(w: Int, h: Int) {
            if (fboId != 0) { val fb = IntArray(1) { fboId }; val tx = IntArray(1) { fboTexId }; GLES20.glDeleteFramebuffers(1, fb, 0); GLES20.glDeleteTextures(1, tx, 0) }
            val fb = IntArray(1); val tx = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); GLES20.glGenTextures(1, tx, 0)
            fboId = fb[0]; fboTexId = tx[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            if (w == 0 || h == 0) return
            viewWidth = w; viewHeight = h
            isSurfaceReady = true
        }

        override fun onDrawFrame(gl: GL10?) {
            if (!isSurfaceReady) return
            sources.forEach { if (!it.isReady) it.init() }

            val now = System.nanoTime()
            deltaTime = (now - lastTime) / 1e9f
            globalTime += deltaTime
            lastTime = now

            fpsFrameCount++
            val currentMillis = System.currentTimeMillis()
            if (currentMillis - fpsLastCalcTime >= 500) {
                val fps = (fpsFrameCount * 1000f / (currentMillis - fpsLastCalcTime)).toInt()
                fpsFrameCount = 0
                fpsLastCalcTime = currentMillis
                ctx.runOnUiThread {
                    ctx.updateFpsUI(fps)
                }
            }

            if (isRotAnimating && rotTargetM != null) {
                rotAnimTime += deltaTime
                if (rotAnimTime >= rotAnimDuration) {
                    mRotAccum = rotTargetM!!; cRotAccum = rotTargetC!!; isRotAnimating = false
                } else {
                    val t = (rotAnimTime / rotAnimDuration).coerceIn(0f, 1f)
                    val ease = 1f - (1f - t).pow(3f)
                    mRotAccum = rotStartM + (rotTargetM!! - rotStartM) * ease
                    cRotAccum = rotStartC + (rotTargetC!! - rotStartC) * ease
                }
            }

            ctx.controls.forEach { it.update(deltaTime) }
            ctx.effectChain.effects.forEach { if(it.active) it.update(deltaTime) }

            sources.forEach { it.processToFbo() }
            manageSurfaces()

            val finalTex = ctx.effectChain.process(this)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(simpleProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTex)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(simpleProgram, "uTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(simpleProgram, "uMVPMatrix"), 1, false, identityMatrix, 0)

            ShaderHelper.bindQuad(simpleProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            renderToScreen()
            renderToExternal()
            renderToRecorder()
            handleCapture()
        }

        private fun renderToScreen() {
            if (simpleProgram == 0) return
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val isPortrait = viewWidth < viewHeight
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0)

            val fboRatio = FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat()
            val screenRatio = viewWidth.toFloat() / viewHeight.toFloat()

            if (isPortrait) {
                android.opengl.Matrix.rotateM(mvpMatrix, 0, -90f, 0f, 0f, 1f)
                val rotatedFboRatio = 1f / fboRatio
                if (screenRatio < rotatedFboRatio) {
                    val scale = rotatedFboRatio / screenRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, scale, 1f)
                } else {
                    val scale = screenRatio / rotatedFboRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1f, 1f)
                }
            } else {
                if (screenRatio > fboRatio) {
                    val scale = screenRatio / fboRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, scale, 1f)
                } else {
                    val scale = fboRatio / screenRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1f, 1f)
                }
            }

            GLES20.glUseProgram(simpleProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(simpleProgram, "uTex") ?: -1, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(simpleProgram, "uMVPMatrix") ?: -1, 1, false, mvpMatrix, 0)

            ShaderHelper.bindQuad(simpleProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderToExternal() {
            if (extEglSurface != EGL14.EGL_NO_SURFACE) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, extEglSurface, extEglSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, extWidth, extHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    drawSimpleTexture(fboTexId)
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, extEglSurface!!, System.nanoTime())
                    EGL14.eglSwapBuffers(mSavedDisplay, extEglSurface)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)
            }
        }

        private fun renderToRecorder() {
            if (recordSurface != EGL14.EGL_NO_SURFACE && videoRecorder != null) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, recordSurface, recordSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, videoRecorder!!.width, videoRecorder!!.height)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    drawSimpleTexture(fboTexId)
                    val timeNow = System.nanoTime()
                    if (recordStartTimeNs == 0L) recordStartTimeNs = timeNow
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, recordSurface!!, timeNow - recordStartTimeNs)
                    EGL14.eglSwapBuffers(mSavedDisplay, recordSurface)
                    videoRecorder?.drain(false)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)
                handleStopRecording()
            }
        }

        private fun drawSimpleTexture(texId: Int) {
            if (simpleProgram == 0) return
            GLES20.glUseProgram(simpleProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(simpleProgram, "uTex") ?: -1, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(simpleProgram, "uMVPMatrix") ?: -1, 1, false, identityMatrix, 0)

            ShaderHelper.bindQuad(simpleProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun setupEGL() {
            mSavedDisplay = EGL14.eglGetCurrentDisplay()
            mSavedContext = EGL14.eglGetCurrentContext()
            val currentConfigId = IntArray(1)
            EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0)
            val configs = arrayOfNulls<EGL14EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
            mEglConfig = configs[0]
        }

        private fun manageSurfaces() {
            val args = extSurfaceArgs
            if (args != null && extEglSurface == EGL14.EGL_NO_SURFACE) {
                val rawSurf = args.first; extWidth = args.second; extHeight = args.third
                extEglSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, rawSurf, intArrayOf(EGL14.EGL_NONE), 0)
            }
            if (args == null && extEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mSavedDisplay, extEglSurface)
                extEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (pendingRecordFile != null) {
                videoRecorder = VideoRecorder(ctx, viewWidth, viewHeight, pendingRecordFile!!)
                recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                pendingRecordFile = null
            }
        }

        private fun handleStopRecording() {
            if (isStopRequested) {
                videoRecorder?.drain(true)
                val out = videoRecorder?.file
                if (recordSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mSavedDisplay, recordSurface)
                    recordSurface = EGL14.EGL_NO_SURFACE
                }
                videoRecorder?.release()
                videoRecorder = null
                isStopRequested = false
                onStopCallback?.invoke(out)
            }
        }

        private fun handleCapture() {
            if (captureRequested) {
                captureRequested = false
                val b = ByteBuffer.allocate(FIXED_WIDTH * FIXED_HEIGHT * 4)
                GLES20.glReadPixels(0, 0, FIXED_WIDTH, FIXED_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(FIXED_WIDTH, FIXED_HEIGHT, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "SB_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceBeam")
                    }
                    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    }
                }.start()
            }
        }
    }

    abstract class ShaderEffect(
        val id: String,
        val name: String,
        val mainActivity: MainActivity
    ) {
        val controls = mutableListOf<PropertyControl>()
        var active: Boolean = true

        abstract fun init()
        abstract fun release()
        abstract fun render(inputTexId: Int, outputFbo: Int, width: Int, height: Int)

        // Allow effects to update physics
        open fun update(deltaTime: Float) {}

        // NEW: Allow effects to reset internal state (like scroll position)
        open fun reset() {}

        protected fun addControl(control: PropertyControl) {
            controls.add(control)
            mainActivity.controlsMap[control.id] = control
            mainActivity.controls.add(control)
        }
    }

}
class VideoRecorder(private val context: Context, val rawWidth: Int, val rawHeight: Int, val file: File) {

    private var muxer: MediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var muxerStarted = false

    // --- VIDEO VARIABLES ---
    private var videoEncoder: MediaCodec
    val inputSurface: Surface
    private var videoTrackIndex = -1

    // Expose the "Safe" dimensions to the Renderer
    val width: Int
    val height: Int

    // --- AUDIO VARIABLES ---
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrackIndex = -1
    private var audioThread: Thread? = null
    private var isRecording = true

    // Audio configuration
    private val sampleRate = 44100
    private val channelCount = 1
    private val audioBitRate = 128000

    init {
        // 1. Calculate Safe Dimensions (Multiple of 16)
        // We calculate this once and store it in the public properties
        width = 1920
        height = 1080

        // 2. Configure Format
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1 second

            // This ensures maximum compatibility for playback on the same device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            }
        }

        // Use standard AVC (H.264)
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        setupAudio()
    }

    // ... (Keep the rest of the class: setupAudio, drain, audioLoop, drainEncoder, startMuxerIfReady, release same as before) ...
    // Just ensure you include the rest of the functions from the previous version here.

    private fun setupAudio() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = minBufferSize * 4

            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    // In a real app, handle this. Here we assume permission is checked in MainActivity
                    return
                }
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: Exception) {
                Log.e("VideoRecorder", "Audio Init Failed", e)
                audioRecord = null
                return
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = null
                return
            }

            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder?.start()
            isRecording = true
            audioRecord?.startRecording()
            audioThread = Thread { audioLoop() }
            audioThread?.start()
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Audio setup crashed", e)
            audioEncoder = null
            audioRecord = null
        }
    }

    fun drain(endOfStream: Boolean) {
        if (endOfStream) {
            try { videoEncoder.signalEndOfInputStream() } catch (e: Exception) { }
        }
        drainEncoder(videoEncoder, isVideo = true)
    }

    private fun audioLoop() {
        val buffer = ByteArray(2048)
        var totalBytesRead = 0L

        while (isRecording && audioEncoder != null && audioRecord != null) {
            val readBytes = audioRecord!!.read(buffer, 0, buffer.size)
            if (readBytes > 0) {
                totalBytesRead += readBytes
                try {
                    val inputBufferIndex = audioEncoder!!.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = audioEncoder!!.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(buffer, 0, readBytes)
                        val pts = (totalBytesRead * 1_000_000L) / (sampleRate * 2)
                        audioEncoder!!.queueInputBuffer(inputBufferIndex, 0, readBytes, pts, 0)
                    }
                    drainEncoder(audioEncoder!!, isVideo = false)
                } catch (e: Exception) { }
            }
        }
    }

    private fun drainEncoder(encoder: MediaCodec, isVideo: Boolean) {
        val timeoutUs = if (isVideo) 0L else 10000L
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val idx = try { encoder.dequeueOutputBuffer(bufferInfo, timeoutUs) } catch (e: Exception) { -1 }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!isVideo && !isRecording) break
                break
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(this) {
                    if (muxerStarted) return
                    val newFormat = encoder.outputFormat
                    if (isVideo) videoTrackIndex = muxer.addTrack(newFormat)
                    else audioTrackIndex = muxer.addTrack(newFormat)
                    startMuxerIfReady()
                }
            } else if (idx >= 0) {
                val encodedData = encoder.getOutputBuffer(idx) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0
                if (bufferInfo.size != 0) {
                    synchronized(this) {
                        if (muxerStarted) {
                            val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                            if (trackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(idx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun startMuxerIfReady() {
        val audioReady = (audioEncoder == null) || (audioTrackIndex >= 0)
        val videoReady = (videoTrackIndex >= 0)
        if (videoReady && audioReady && !muxerStarted) {
            muxer.start()
            muxerStarted = true
        }
    }

    fun release() {
        isRecording = false
        try { audioThread?.join(500) } catch (e: Exception) {}
        try {
            if (muxerStarted) muxer.stop()
            muxer.release()
            videoEncoder.stop()
            videoEncoder.release()
            inputSurface.release()
            audioEncoder?.stop()
            audioEncoder?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { }
    }
}
enum class SourceType {
    CAMERA,
    MEDIA_VIDEO,
    MEDIA_IMAGE,
    RTSP,
    SHADER
}
// In MainActivity.kt

abstract class SourcePropertyControl(
    id: String,
    label: String,
    defaultValue: Int,
    val sourceId: String,
    val mainActivity: MainActivity
) : PropertyControl(
    id = id,
    label = label,
    defaultValue = defaultValue,
    outMin = 0f,
    outMax = 1f,
    hasModulation = true,
    includeInPreset = false,
    layoutStyle = LayoutStyle.ROW,
    iconResId = android.R.drawable.presence_video_online,
    defaultLocked = true
) {

    override fun addExtraControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource(sourceId) ?: return

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 20; topMargin = 20 }
        }

        fun mkBtn(txt: String, action: () -> Unit): Button {
            return Button(context).apply {
                text = txt
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = 10f
                    setStroke(1, Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(4,0,4,0) }
                setOnClickListener { action() }
            }
        }

        row.addView(mkBtn("FLIP X") { channel.userFlipX *= -1f })
        row.addView(mkBtn("FLIP Y") { channel.userFlipY *= -1f })
        row.addView(mkBtn("ROT 180") { channel.userRot180 = !channel.userRot180 })

        panel.addView(TextView(context).apply {
            text = "SOURCE GEOMETRY"; textSize=10f; setTextColor(Color.LTGRAY)
        })
        panel.addView(row)

        val removeBtn = Button(context).apply {
            text = "REMOVE SOURCE"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#882222"))
                cornerRadius = 10f
                setStroke(1, Color.RED)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply {
                topMargin = 10
                bottomMargin = 10
            }
            // Deletion logic moved directly here
            setOnClickListener {
                mainActivity.removeSource(this@SourcePropertyControl)
            }
        }
        panel.addView(removeBtn)
    }

    abstract fun onRemove()
}

class ShaderSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {
    override fun onRemove() {
        // GL resources are automatically handled by SourceChannel.release()
    }
}

class CameraSourceControl(val mainActivity: MainActivity) : PropertyControl(
    id = "CAM_MAIN",
    label = "CAMERA",
    defaultValue = 1000,
    outMin = 0f,
    outMax = 1f,
    hasModulation = true,
    includeInPreset = true,
    layoutStyle = LayoutStyle.ROW,
    iconResId = android.R.drawable.ic_menu_camera,
    defaultLocked = true
) {
    override fun addExtraControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource("CAM_MAIN") ?: return

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 20; topMargin = 20 }
        }

        fun mkBtn(txt: String, action: () -> Unit): Button {
            return Button(context).apply {
                text = txt
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = 10f
                    setStroke(1, Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(4,0,4,0) }
                setOnClickListener { action() }
            }
        }

        row.addView(mkBtn("FLIP X") { channel.userFlipX *= -1f })
        row.addView(mkBtn("FLIP Y") { channel.userFlipY *= -1f })
        row.addView(mkBtn("ROT 180") { channel.userRot180 = !channel.userRot180 })

        panel.addView(TextView(context).apply {
            text = "SOURCE GEOMETRY"; textSize=10f; setTextColor(Color.LTGRAY)
        })
        panel.addView(row)
    }
}

class MediaSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity,
    private val exoPlayer: ExoPlayer?
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {
    override fun onRemove() {
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }
}

class RtspSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity,
    private val exoPlayer: ExoPlayer
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {
    override fun onRemove() {
        try {
            exoPlayer.stop()
            exoPlayer.release()
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }
}