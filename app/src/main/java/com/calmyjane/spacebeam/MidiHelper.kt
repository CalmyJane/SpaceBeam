package com.calmyjane.spacebeam

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

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
    var isModified: Boolean = false
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
    var onCCReceived: ((cc: Int) -> Unit)? = null

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
            isModified = false
            true
        } catch (e: Exception) {
            Log.e("MIDI", "Import failed", e)
            false
        }
    }

    fun loadDefault() {
        importConfig(DEFAULT_MAPPING_JSON)
    }


    private fun addBinding(cc: Int, target: String, mode: String, scale: Float, updateReverse: Boolean) {
        if (!bindingMap.containsKey(cc)) {
            bindingMap[cc] = java.util.concurrent.CopyOnWriteArrayList()
        }
        bindingMap[cc]?.add(MidiBinding(target, mode, scale))
        if (updateReverse && mode == "VAL") {
            reverseMap[target] = cc
        }
        isModified = true
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
        isModified = true
    }

    fun getAllBindings(): List<Triple<Int, String, MidiBinding>> {
        val result = mutableListOf<Triple<Int, String, MidiBinding>>()
        bindingMap.forEach { (cc, list) ->
            list.forEach { binding -> result.add(Triple(cc, binding.target, binding)) }
        }
        return result.sortedWith(compareBy({ it.second }, { it.first }))
    }

    fun clearAllBindings() {
        bindingMap.clear()
        reverseMap.clear()
        isModified = true
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

                val mode = forcedMode ?: (if (target.startsWith("CMD_") || target.startsWith("PRESET_")) "TRIG" else "VAL")

                // Remove existing bindings for this specific target & mode pair (to avoid duplicates)
                if (reverseMap.containsKey(target) && mode == "VAL") {
                    val oldCC = reverseMap[target]!!
                    bindingMap[oldCC]?.removeIf { it.target == target && it.mode == "VAL" }
                }

                bindingMap[cc]?.removeIf { it.target == target && it.mode == mode }

                addBinding(cc, target, mode, 1.0f, true)

                learningTargetId = null
                onLearningComplete?.invoke()
                Toast.makeText(activity, "Mapped CC $cc to $target ($mode)", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }

            // 2. Execution Mode
            onCCReceived?.invoke(cc)
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

