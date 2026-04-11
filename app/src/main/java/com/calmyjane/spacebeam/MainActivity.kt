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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.opengl.GLUtils
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
import android.util.Range
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.ContentUris
import android.util.LruCache
import java.util.concurrent.Executors
import android.graphics.drawable.ColorDrawable

// --- MAIN ACTIVITY ---
@OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {
    private var originalScreenTimeout = -1
    var forceScreenOn = false
    private var pendingShaderSaveCode: String? = null
    val bpmManager = BpmManager()
    var activePlaylistEditor: MediaSourceControl? = null

    @SuppressLint("ClickableViewAccessibility")
    fun applyRobustTouch(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // Return false so the normal onClickListener still fires
        }
    }

    val shaderSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && pendingShaderSaveCode != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(pendingShaderSaveCode!!.toByteArray())
                }
                Toast.makeText(this, "Shader Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var fpsTextView: TextView? = null
    private var sensorDebugTextView: TextView? = null
    private var cachedTempC = 0
    private var cachedHeadroomStr = ""
    private var lastThermalPollMs = 0L
    fun updateFpsUI(fps: Int) {
        val now = System.currentTimeMillis()
        if (now - lastThermalPollMs > 5000L) {
            lastThermalPollMs = now
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            cachedTempC = (batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10

            val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            cachedHeadroomStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val headroom = pm?.getThermalHeadroom(0)
                    if (headroom != null) {
                        val pct = (headroom * 100).toInt()
                        if (pct >= 100) "  T:>100%" else "  T:${pct}%"
                    } else ""
                } catch (e: Exception) { Log.w("SpaceBeam", "getThermalHeadroom failed", e); "" }
            } else ""
        }

        fpsTextView?.text = "FPS: $fps  ${cachedTempC}°C$cachedHeadroomStr"
        val sh = sensorHelper
        sensorDebugTextView?.text = "P:${"%.2f".format(sh.pitch)}  R:${"%.2f".format(sh.roll)}  Y:${"%.2f".format(sh.yaw)}"
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


    val effectChain = EffectChain()
    lateinit var glView: GLSurfaceView
    private val sourceControls = mutableListOf<PropertyControl>()
    private var mixerGroupContainer: LinearLayout? = null
    private lateinit var saveConfirmBtn: View
    internal lateinit var renderer: KaleidoscopeRenderer
    private data class CameraEntry(val id: String, val name: String, val isFront: Boolean)
    private var allCameras: List<CameraEntry> = emptyList()
    private var currentCameraIndex = 0
    private var currentSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    lateinit var overlayHUD: FrameLayout
    private lateinit var displayHelper: ExternalDisplayHelper
    lateinit var midiHelper: MidiHelper
    lateinit var sensorHelper: SensorHelper
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
        // Force ExoPlayer to use a strict 64KB segment allocator
        val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(true, 65536)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                1500, // Min buffer 1.5s
                3000, // Max buffer 3s
                500,  // Buffer for playback 0.5s
                500   // Buffer for playback after rebuffer 0.5s
            )
            .setTargetBufferBytes(4 * 1024 * 1024) // Strictly clamp to 4MB per player
            .setPrioritizeTimeOverSizeThresholds(false) // CRITICAL: Obey byte limit even if time isn't met
            .build()

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }

    @SuppressLint("Range")
    fun getFileNameFromUri(uri: android.net.Uri): String {
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

    val maskManager = MaskManager()
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
    private var transitionMs: Long = 2500L
    private var transitionStartTime: Long = 0L
    private var isHudVisible = true
    private var isMenuExpanded = true
    private var isRecording = false
    private var recordingSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var recordTicker: Runnable? = null
    private var readabilityLevel = 2
    private lateinit var parameterPanel: ScrollView
    private lateinit var controlBox: LinearLayout
    private lateinit var presetPanel: LinearLayout
    private lateinit var recordControls: LinearLayout
    private lateinit var tapBtn: Button

    private lateinit var settingsBtn: ImageButton
    private val expandedGroups = mutableSetOf<String>()
    private var lastScrollY = 0
    private var isRebuildingHUD = false

    private var isAutoPlaying = false
    var autoPlayRandom = false
    var autoPlayDurationMs = 3000L // 3 seconds hold time by default
    private val autoPlayRunnable = Runnable { triggerNextAutoPlay() }
    private lateinit var playBtn: ImageButton

    val undoManager = UndoManager()
    var undoHistorySize: Int = 20
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private lateinit var undoRedoPanel: LinearLayout
    private var undoRedoAnimator: ValueAnimator? = null
    private lateinit var undoDrawable: ProgressUndoRedoDrawable
    private lateinit var redoDrawable: ProgressUndoRedoDrawable

    // For filling the button visual
    private var presetAnimators = mutableMapOf<Int, ValueAnimator>()
    private val presetDrawables = mutableMapOf<Int, ProgressButtonDrawable>()

    // --- 1. SHARED GL UTILITIES (Renamed to avoid conflict) ---




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

    fun getRendererSource(id: String): KaleidoscopeRenderer.SourceChannel? {
        return renderer.getSource(id)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        isRebuildingHUD = true

        // 0. Handle mask editor — rebuild overlay without touching backup/node data
        val wasMaskEditorOpen = maskEditorOverlay != null
        if (wasMaskEditorOpen) {
            maskEditorOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            maskEditorOverlay = null
        }

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
            if (wasMaskEditorOpen) {
                buildMaskEditorOverlay()
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
                        if (::playBtn.isInitialized) updatePlayBtnBackground()
                    }
                    applyPreset(idx)
                }
                return@runOnUiThread
            }

            // Handle specific Commands
            when (commandId) {
                "CMD_TAP_TEMPO" -> bpmManager.tap()
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
                "CMD_UNDO" -> performUndo()
                "CMD_REDO" -> performRedo()
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

    val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val intentData = result.data
            val uris = mutableListOf<android.net.Uri>()

            if (intentData?.clipData != null) {
                val count = intentData.clipData!!.itemCount
                for (i in 0 until count) {
                    uris.add(intentData.clipData!!.getItemAt(i).uri)
                }
            } else if (intentData?.data != null) {
                uris.add(intentData.data!!)
            }

            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {}
                }
                attemptAddMediaSource(uris)
            }
        }
    }

    private fun attemptAddMediaSource(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val uniqueId = "SRC_${System.currentTimeMillis()}"

        val playlist = uris.map { uri ->
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = mimeType.startsWith("image")
            val name = getFileNameFromUri(uri)
            PlaylistItem(uri, !isImage, name, if (isImage) 3.0f else 1.0f, 1.0f) // 1s crossfade default
        }.toMutableList()

        if (activePlaylistEditor != null) {
            activePlaylistEditor?.addItems(playlist)
            Toast.makeText(this, "Added to playlist", Toast.LENGTH_SHORT).show()
        } else {
            val channel = renderer.addSource(SourceType.PLAYLIST, uniqueId)
            if (channel != null) {
                val ctrl = MediaSourceControl(uniqueId, "PLAYLIST", uniqueId, this, playlist)
                ctrl.subtitle = if (playlist.size == 1) playlist[0].name else "${playlist.size} Items"
                addDynamicSourceControl(ctrl)
                Toast.makeText(this, "Playlist Added", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midiHelper = MidiHelper(this)
        sensorHelper = SensorHelper(this)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        hideSystemUI()

        renderer = KaleidoscopeRenderer(this)

        effectChain.effects.clear()
        effectChain.effects.add(MixerEffect(this))
        effectChain.effects.add(TransformEffect("C", "CAMERA TRANSFORM", this))
        effectChain.effects.add(ColorEffect(this))
        effectChain.effects.add(EdgeEffect(this))
        effectChain.effects.add(KaleidoscopeEffect(this))
        effectChain.effects.add(TransformEffect("M", "MASTER TRANSFORM", this))
        effectChain.effects.add(TunnelEffect(this))
        effectChain.effects.add(SwirlEffect(this))

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setPreserveEGLContextOnPause(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        renderer.startContinuousRendering()
        glView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && saveConfirmBtn.visibility == View.VISIBLE) {
                saveConfirmBtn.visibility = View.GONE
                pendingSaveIndex = null
            }

            handleInteraction(event)
            true
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView.keepScreenOn = true
        setupOverlayHUD()
        initDefaultPresets()
        val prefs = getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
        autoPlayRandom = prefs.getBoolean("AP_RANDOM", false)
        undoHistorySize = prefs.getInt("UNDO_HISTORY", 20)
        undoManager.maxHistory = undoHistorySize
        forceScreenOn = prefs.getBoolean("FORCE_SCREEN_ON", false)
        maskManager.loadFromPrefs(prefs)
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
            undoManager.clear()
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
            // Support for Android 14 (API 34) partial selection
            if (Build.VERSION.SDK_INT >= 34) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
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
        if (allCameras.isEmpty()) enumerateCameras()
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val provider = cpFuture.get()
            provider.unbindAll()
            val cam = allCameras.getOrNull(currentCameraIndex) ?: return@addListener
            currentSelector = CameraSelector.Builder()
                .addCameraFilter { cameras -> cameras.filter { Camera2CameraInfo.from(it).cameraId == cam.id } }
                .build()
            glView.queueEvent {
                runOnUiThread {
                    val preview = Preview.Builder()
                        .setTargetRotation(Surface.ROTATION_90)
                        .build()

                    preview.setSurfaceProvider { req -> renderer.provideCameraSurface(req) }
                    try { provider.bindToLifecycle(this, currentSelector, preview) } catch (e: Exception) { Log.e("Camera", "Bind failed", e) }
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enumerateCameras() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        data class RawCam(val id: String, val isFront: Boolean, val fl: Float?)

        val raw = mutableListOf<RawCam>()
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
            val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            raw.add(RawCam(id, isFront, fl))
        }

        // Name cameras relative to others with same facing
        fun nameGroup(group: List<RawCam>): List<CameraEntry> {
            if (group.size == 1) {
                val prefix = if (group[0].isFront) "Front" else "Back"
                return listOf(CameraEntry(group[0].id, prefix, group[0].isFront))
            }
            val sorted = group.sortedBy { it.fl ?: Float.MAX_VALUE }
            return sorted.mapIndexed { i, cam ->
                val prefix = if (cam.isFront) "Front" else "Back"
                val type = when {
                    cam.fl == null -> "Camera ${i + 1}"
                    i == 0 && (cam.fl < 2.5f || cam.fl < (sorted.getOrNull(1)?.fl ?: cam.fl) * 0.7f) -> "Ultrawide"
                    i == sorted.lastIndex && cam.fl > 6.0f -> "Telephoto"
                    i == sorted.lastIndex -> "Narrow"
                    else -> "Wide"
                }
                CameraEntry(cam.id, "$prefix $type", cam.isFront)
            }
        }

        val front = nameGroup(raw.filter { it.isFront })
        val back = nameGroup(raw.filter { !it.isFront })
        allCameras = front + back

        // Default to first front camera
        val frontIdx = allCameras.indexOfFirst { it.isFront }
        if (frontIdx >= 0) currentCameraIndex = frontIdx
    }

    override fun onResume() {
        super.onResume()
        sensorHelper.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyForceScreenOn()
    }

    override fun onPause() {
        super.onPause()
        sensorHelper.stop()
        restoreScreenTimeout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        restoreScreenTimeout()
        midiHelper.close()
        // Detach all UI from controls to prevent context leaks
        controls.forEach { it.detach() }

        exoPlayer?.release()
        exoPlayer = null
        renderer.stopContinuousRendering()
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

                controlsMap[pTxId]?.let { it.setProgress((it.value - (dx * 500).toInt()).coerceIn(0, 1000)) }
                controlsMap[pTyId]?.let { it.setProgress((it.value + (dy * 500).toInt()).coerceIn(0, 1000)) }

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
        val b = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply {
            this.color = color
            this.textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }

        val finalSize = if (size > 0f) size else {
            // Auto-size: start large and shrink to fit within 460px width
            var s = 250f
            p.textSize = s
            while (p.measureText(t) > 460f && s > 30f) { s -= 10f; p.textSize = s }
            s
        }
        p.textSize = finalSize
        c.drawText(t, 250f, 250f + (finalSize / 3f), p)

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



    @SuppressLint("ClickableViewAccessibility")
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
        val controlBoxView = createControlBox(isPortrait)
        val presetPanel = createPresetPanel()

        // Wire undo capture to all property controls
        undoManager.onStateChanged = { runOnUiThread { updateUndoRedoButtons() } }
        controls.forEach { control ->
            if (control.includeInPreset) {
                control.onTouchDown = { undoManager.captureBeforeChange(controls, activePreset) }
                control.onTouchUp = {
                    undoManager.commitChange(controls, -1)
                    setActivePresetVisual(-1)
                }
            }
        }

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

        val controlBoxParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = 20
                topMargin = 280
            } else {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 20
                rightMargin = 220
            }
        }
        overlayHUD.addView(controlBoxView, controlBoxParams)

        val presetParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = 50
                leftMargin = 20
            } else {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 15
                rightMargin = 220
            }
        }
        overlayHUD.addView(presetPanel, presetParams)

        overlayHUD.addView(settingsBtn)

        updateSidebarVisuals()
        applyReadabilityStyle()
        updateUndoRedoButtons()

        // Restore ongoing transition animations after orientation change
        restoreUndoRedoAnimation()
        restorePresetTransitionAnimation()
    }

    /** Restart preset fill animation after orientation change if one was in progress */
    private fun restorePresetTransitionAnimation() {
        if (activePreset == -1) return
        val elapsed = System.currentTimeMillis() - transitionStartTime
        val remaining = transitionMs - elapsed
        if (remaining <= 0) return
        val btnDrawable = presetDrawables[activePreset] ?: return
        val startProgress = elapsed.toFloat() / transitionMs.toFloat()
        btnDrawable.isActive = true
        val anim = ValueAnimator.ofFloat(startProgress, 1f).apply {
            duration = remaining
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                btnDrawable.setProgress(va.animatedValue as Float)
                btnDrawable.invalidateSelf()
            }
            start()
        }
        presetAnimators[activePreset] = anim
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

    fun showMidiClearOverlay() {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            isClickable = true
            elevation = 1000f
            setOnClickListener { /* block passthrough */ }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(900, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.parseColor("#880000"))
                cornerRadius = 30f
            }
            setPadding(40, 40, 40, 40)
            isClickable = true
            setOnClickListener { }
        }

        content.addView(TextView(this).apply {
            text = "CLEAR MIDI MAPPINGS"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        val hintText = TextView(this).apply {
            text = "Move a knob/slider on your MIDI controller to filter"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        content.addView(hintText)

        // Filter label
        val filterLabel = TextView(this).apply {
            text = ""
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.GRAY)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        content.addView(filterLabel)

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = 50
        }

        // null = show nothing, -1 = show all, >= 0 = filter to that CC
        var currentCc: Int? = null

        fun refreshList() {
            listContainer.removeAllViews()
            if (currentCc == null) return
            val allBindings = midiHelper.getAllBindings()
            val visible = if (currentCc == -1) allBindings else allBindings.filter { it.first == currentCc }

            if (visible.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = "No mappings."
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    gravity = Gravity.CENTER
                    setPadding(0, 20, 0, 20)
                })
            } else {
                visible.forEach { (cc, targetId, binding) ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#333333"))
                            cornerRadius = 10f
                        }
                        setPadding(20, 12, 10, 12)
                    }

                    row.addView(TextView(this).apply {
                        text = "CC $cc  →  $targetId  (${binding.mode})"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })

                    val delBtn = Button(this).apply {
                        text = "🗑"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setPadding(0, 0, 0, 0)
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#880000"))
                            cornerRadius = 40f
                        }
                        layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 15 }
                        setOnClickListener {
                            midiHelper.removeBinding(cc, targetId)
                            refreshList()
                        }
                    }
                    row.addView(delBtn)
                    listContainer.addView(row)
                }
            }
        }

        // Listen for incoming MIDI CC to filter
        midiHelper.onCCReceived = { cc ->
            currentCc = cc
            filterLabel.text = "SHOWING: CC $cc"
            hintText.text = "Move a different knob/slider to switch filter"
            refreshList()
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 400)
            addView(listContainer)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 15f
            }
            setPadding(10, 10, 10, 10)
        }
        content.addView(scrollView)

        // Button row: SHOW ALL | DELETE VISIBLE
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 120).apply { topMargin = 20 }
        }

        val showAllBtn = Button(this).apply {
            text = "SHOW ALL"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = 10 }
            setOnClickListener {
                currentCc = -1
                filterLabel.text = "SHOWING: ALL"
                hintText.text = "Move a knob/slider on your MIDI controller to filter"
                refreshList()
            }
        }

        val deleteVisibleBtn = Button(this).apply {
            text = "DELETE VISIBLE"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#880000"))
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = 10 }
            setOnClickListener {
                // Confirmation step
                val confirmFrame = FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(Color.argb(200, 0, 0, 0))
                    elevation = 1100f
                }
                val confirmBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(700, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#222222"))
                        setStroke(2, Color.parseColor("#880000"))
                        cornerRadius = 20f
                    }
                    setPadding(40, 40, 40, 40)
                }
                confirmBox.addView(TextView(this@MainActivity).apply {
                    text = "Delete Mappings?"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 30)
                })
                val confirmRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(-1, 110)
                }
                confirmRow.addView(Button(this@MainActivity).apply {
                    text = "CANCEL"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 15f }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = 10 }
                    setOnClickListener { (confirmFrame.parent as ViewGroup).removeView(confirmFrame) }
                })
                confirmRow.addView(Button(this@MainActivity).apply {
                    text = "DELETE"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#880000")); cornerRadius = 15f }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = 10 }
                    setOnClickListener {
                        val toDelete = if (currentCc == -1) midiHelper.getAllBindings()
                                       else midiHelper.getAllBindings().filter { it.first == currentCc }
                        toDelete.forEach { (cc, targetId, _) -> midiHelper.removeBinding(cc, targetId) }
                        (confirmFrame.parent as ViewGroup).removeView(confirmFrame)
                        refreshList()
                    }
                })
                confirmBox.addView(confirmRow)
                confirmFrame.addView(confirmBox)
                overlayHUD.addView(confirmFrame)
            }
        }

        btnRow.addView(showAllBtn)
        btnRow.addView(deleteVisibleBtn)
        content.addView(btnRow)

        // Close button
        content.addView(Button(this).apply {
            text = "CLOSE"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply { topMargin = 10 }
            setOnClickListener {
                midiHelper.onCCReceived = null
                (frame.parent as ViewGroup).removeView(frame)
            }
        })

        frame.addView(content)
        overlayHUD.addView(frame)
    }

    // Add this inside MainActivity class
    fun loadScaledBitmap(uri: android.net.Uri): Bitmap? {
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

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(15, 0, 0, 0)
        }
        val logoSize = (36 * resources.displayMetrics.density).toInt()
        val logoParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
            bottomMargin = (3 * resources.displayMetrics.density).toInt()
            rightMargin = (6 * resources.displayMetrics.density).toInt()
        }
        headerRow.addView(logoView, logoParams)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fpsTextView = TextView(this).apply {
            text = "FPS: --"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(15, 0, 15, 4)
        }
        textColumn.addView(fpsTextView)

        sensorDebugTextView = TextView(this).apply {
            text = "P:0.00  R:0.00  Y:0.00"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(15, 0, 15, 20)
        }
        textColumn.addView(sensorDebugTextView)

        headerRow.addView(textColumn)
        menuLayout.addView(headerRow)

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

                // 3. Restore the Original '+' Button
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

        val items = arrayOf("Media (Image/Video)", "RTSP Stream", "Generative Shader", "Feedback Loop")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Source")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        // Launch the new custom in-app Media Browser
                        MediaPickerDialog(this) { uris ->
                            attemptAddMediaSource(uris)
                        }.show()
                    }
                    1 -> showRtspDialog()
                    2 -> showShaderSourceDialog()
                    3 -> attemptAddFeedbackSource()
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

    private fun attemptAddFeedbackSource() {
        val feedbackCount = renderer.sources.count { it.type == SourceType.FEEDBACK }
        val uniqueId = "FEEDBACK_${System.currentTimeMillis()}"
        val channel = renderer.addSource(SourceType.FEEDBACK, uniqueId)
        if (channel != null) {
            val label = if (feedbackCount == 0) "FEEDBACK" else "FEEDBACK ${feedbackCount + 1}"
            val ctrl = FeedbackSourceControl(uniqueId, label, uniqueId, this)
            ctrl.subtitle = "Tap: Final Output"
            addDynamicSourceControl(ctrl)
            Toast.makeText(this, "Feedback Loop Added", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Mixer Full", Toast.LENGTH_SHORT).show()
        }
    }

    fun showShaderSourceDialog(
        existingCode: String? = null,
        isEditing: Boolean = false,
        onUpdate: ((String) -> Unit)? = null
    ) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            isClickable = true
        }

        // CLOSE BUTTON: Only dismisses the dialog, discarding changes
        val closeBtn = Button(this).apply {
            text = "✕"; textSize = 24f; setTextColor(Color.GRAY); background = null
            layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 10; rightMargin = 10
            }
            setOnClickListener { dialog.dismiss() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = 50; rightMargin = 50
                topMargin = 130; bottomMargin = 30
            }
        }

        content.addView(TextView(this).apply {
            text = if (isEditing) "EDIT SHADER" else "ADD SHADER"
            textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 15)
        })

        // --- TOP ROW: SELECTOR, LOAD, & SAVE ---
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 15 }
        }

        val spinnerKeys = BUILTIN_SHADERS.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerKeys)
        val spinner = Spinner(this).apply {
            this.adapter = adapter
            background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 12f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 10 }
        }

        val loadBtn = Button(this).apply {
            text = "LOAD"; setTextColor(Color.WHITE); textSize = 11f
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 12f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.5f).apply { rightMargin = 10 }
            setOnClickListener { shaderFileLauncher.launch(arrayOf("text/plain", "application/octet-stream")) }
        }

        val saveBtn = Button(this).apply {
            text = "SAVE"; setTextColor(Color.WHITE); textSize = 11f
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 12f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.5f)
            setOnClickListener {
                val code = activeShaderInput?.text.toString()
                if (code.isNotEmpty()) {
                    pendingShaderSaveCode = code
                    shaderSaveLauncher.launch("shader_export.txt")
                }
            }
        }

        topRow.addView(spinner); topRow.addView(loadBtn); topRow.addView(saveBtn)
        content.addView(topRow)

        // --- FLEXIBLE CODE EDITOR ---
        activeShaderInput = EditText(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 12f
            }
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setHorizontallyScrolling(true)
            // Set initial text if provided (only once at creation)
            if (existingCode != null) setText(existingCode)
        }
        content.addView(activeShaderInput)

        // --- DROPDOWN LOGIC: Forces update of the text field ---
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isFirstSelection = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                // When editing, we ignore the initial "automatic" selection triggered by Spinner on creation
                if (isEditing && isFirstSelection) {
                    isFirstSelection = false
                    return
                }

                val selectedCode = BUILTIN_SHADERS[spinnerKeys[pos]]
                activeShaderInput?.setText(selectedCode)
                currentShaderName = spinnerKeys[pos]
                isFirstSelection = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- APPLY/ADD BUTTON ---
        val actionBtn = Button(this).apply {
            text = if (isEditing) "APPLY CHANGES" else "ADD TO MIXER"
            setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (isEditing) Color.parseColor("#228B22") else Color.parseColor("#0066CC"))
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(-1, 110).apply { topMargin = 20 }
            setOnClickListener {
                val code = activeShaderInput?.text.toString().trim()
                if (code.isNotEmpty()) {
                    if (isEditing && onUpdate != null) {
                        onUpdate(code)
                    } else {
                        attemptAddShaderSource(code, currentShaderName)
                    }
                    dialog.dismiss()
                }
            }
        }
        content.addView(actionBtn)

        rootLayout.addView(content); rootLayout.addView(closeBtn)
        dialog.setContentView(rootLayout)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialog.setOnDismissListener {
            hideSystemUI()
            activeShaderInput = null
        }
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

    private var cameraToast: Toast? = null

    fun switchCamera() {
        if (allCameras.isEmpty()) return
        currentCameraIndex = (currentCameraIndex + 1) % allCameras.size
        cameraToast?.cancel()
        cameraToast = Toast.makeText(this, allCameras[currentCameraIndex].name, Toast.LENGTH_SHORT)
        cameraToast?.show()
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

    inner class ProgressUndoRedoDrawable(private val symbol: String) : android.graphics.drawable.Drawable() {
        private var progress = 0f
        var enabled = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 140f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
        }

        override fun getIntrinsicWidth(): Int = 150
        override fun getIntrinsicHeight(): Int = 150

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().let { if (it > 0) it else 150f }
            val h = bounds.height().toFloat().let { if (it > 0) it else 150f }

            // Fill from bottom (transition progress)
            if (progress > 0f && progress < 1f) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(80, 255, 255, 255)
                val fillHeight = h * progress
                canvas.drawRect(0f, h - fillHeight, w, h, paint)
            }

            // Symbol
            textPaint.color = if (enabled) Color.WHITE else Color.argb(128, 255, 255, 255)
            val xPos = w / 2
            val yPos = (h / 2) - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(symbol, xPos, yPos, textPaint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; textPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun updateUndoRedoButtons() {
        if (::undoDrawable.isInitialized) {
            undoDrawable.enabled = undoManager.canUndo
            undoDrawable.invalidateSelf()
        }
        if (::redoDrawable.isInitialized) {
            redoDrawable.enabled = undoManager.canRedo
            redoDrawable.invalidateSelf()
        }
    }

    // Track which button is currently animating: true=undo, false=redo, null=none
    private var activeUndoRedoIsUndo: Boolean? = null
    private var undoRedoTransitionStart: Long = 0L

    private val activeUndoRedoDrawable: ProgressUndoRedoDrawable?
        get() = when (activeUndoRedoIsUndo) {
            true -> if (::undoDrawable.isInitialized) undoDrawable else null
            false -> if (::redoDrawable.isInitialized) redoDrawable else null
            null -> null
        }

    private fun animateUndoRedoButton(isUndo: Boolean) {
        undoRedoAnimator?.cancel()
        // Reset previous drawable if switching between undo/redo
        if (activeUndoRedoIsUndo != null && activeUndoRedoIsUndo != isUndo) {
            activeUndoRedoDrawable?.setProgress(0f)
            activeUndoRedoDrawable?.invalidateSelf()
        }
        activeUndoRedoIsUndo = isUndo
        undoRedoTransitionStart = System.currentTimeMillis()
        val drawable = activeUndoRedoDrawable ?: return
        drawable.setProgress(0f)
        drawable.invalidateSelf()
        undoRedoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                drawable.setProgress(va.animatedValue as Float)
                drawable.invalidateSelf()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    drawable.setProgress(0f)
                    drawable.invalidateSelf()
                    activeUndoRedoIsUndo = null
                }
            })
            start()
        }
    }

    /** Restart undo/redo fill animation after orientation change if one was in progress */
    private fun restoreUndoRedoAnimation() {
        if (activeUndoRedoIsUndo == null) return
        val elapsed = System.currentTimeMillis() - undoRedoTransitionStart
        val remaining = transitionMs - elapsed
        val drawable = activeUndoRedoDrawable
        if (remaining <= 0 || drawable == null) {
            drawable?.setProgress(0f)
            drawable?.invalidateSelf()
            activeUndoRedoIsUndo = null
            return
        }
        val startProgress = elapsed.toFloat() / transitionMs.toFloat()
        undoRedoAnimator?.cancel()
        undoRedoAnimator = ValueAnimator.ofFloat(startProgress, 1f).apply {
            duration = remaining
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                drawable.setProgress(va.animatedValue as Float)
                drawable.invalidateSelf()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    drawable.setProgress(0f)
                    drawable.invalidateSelf()
                    activeUndoRedoIsUndo = null
                }
            })
            start()
        }
    }

    private fun performUndo() {
        val durationSec = transitionMs / 1000f
        val result = undoManager.undo(controls, durationSec) ?: return
        setActivePresetVisual(result.activePreset, animate = true)
        animateUndoRedoButton(true)
    }

    private fun performRedo() {
        val durationSec = transitionMs / 1000f
        val result = undoManager.redo(controls, durationSec) ?: return
        setActivePresetVisual(result.activePreset, animate = true)
        animateUndoRedoButton(false)
    }

    private fun setActivePresetVisual(presetIdx: Int, animate: Boolean = false) {
        presetAnimators.values.forEach { it.cancel() }
        presetAnimators.clear()

        activePreset = presetIdx

        presetDrawables.forEach { (id, drawable) ->
            drawable.setProgress(0f)
            drawable.isActive = (id == presetIdx)
            drawable.invalidateSelf()
        }

        if (animate && presetIdx != -1) {
            val btnDrawable = presetDrawables[presetIdx]
            if (btnDrawable != null) {
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = transitionMs
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { va ->
                        btnDrawable.setProgress(va.animatedValue as Float)
                        btnDrawable.invalidateSelf()
                    }
                    start()
                }
                presetAnimators[presetIdx] = anim
            }
        }

        updatePresetHighlights()
    }

    private fun createUndoRedoPanel(): LinearLayout {
        undoRedoPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }

        undoDrawable = ProgressUndoRedoDrawable("\u21B6")
        redoDrawable = ProgressUndoRedoDrawable("\u21B7")

        undoBtn = ImageButton(this).apply {
            setImageDrawable(undoDrawable)
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(150, 150)
            setOnClickListener { performUndo() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_UNDO", "UNDO")
                    true
                } else false
            }
        }

        redoBtn = ImageButton(this).apply {
            setImageDrawable(redoDrawable)
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(150, 150)
            setOnClickListener { performRedo() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_REDO", "REDO")
                    true
                } else false
            }
        }

        undoRedoPanel.addView(undoBtn)
        undoRedoPanel.addView(redoBtn)
        return undoRedoPanel
    }

    private fun createHudDivider(isVertical: Boolean): View {
        return View(this).apply {
            setBackgroundColor(Color.argb(100, 128, 128, 128))
            layoutParams = if (isVertical) {
                LinearLayout.LayoutParams(2, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    topMargin = 15; bottomMargin = 15
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    leftMargin = 15; rightMargin = 15
                }
            }
        }
    }

    private fun createControlBox(isPortrait: Boolean): LinearLayout {
        createRecordControls()
        createUndoRedoPanel()

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

        tapBtn = Button(this).apply {
            text = "TAP\nBPM"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(150, 150)
        }
        val resetTapTextRunnable = Runnable { tapBtn.text = "TAP\nBPM" }
        tapBtn.setOnClickListener {
            bpmManager.tap()
            tapBtn.text = "${bpmManager.bpm.toInt()}"
            handler.removeCallbacks(resetTapTextRunnable)
            handler.postDelayed(resetTapTextRunnable, 2000)
        }
        tapBtn.setOnLongClickListener {
            if (midiHelper.isConnected) {
                showMidiLearnOverlay("CMD_TAP_TEMPO", "TAP TEMPO")
                true
            } else false
        }

        controlBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(15, 5, 5, 5)
        }

        if (isPortrait) {
            controlBox.orientation = LinearLayout.VERTICAL
            recordControls.orientation = LinearLayout.VERTICAL
            (recordBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = 0 }
            undoRedoPanel.orientation = LinearLayout.VERTICAL
            (redoBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = 0 }

            controlBox.addView(recordControls)
            controlBox.addView(createHudDivider(false))
            controlBox.addView(undoRedoPanel)
            controlBox.addView(createHudDivider(false))
            controlBox.addView(tapBtn)
            controlBox.addView(createHudDivider(false))
            controlBox.addView(switchBtn)
        } else {
            controlBox.orientation = LinearLayout.HORIZONTAL
            recordControls.orientation = LinearLayout.HORIZONTAL
            (recordBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = 0 }
            undoRedoPanel.orientation = LinearLayout.HORIZONTAL
            (redoBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = 0 }

            controlBox.addView(recordControls)
            controlBox.addView(createHudDivider(true))
            controlBox.addView(undoRedoPanel)
            controlBox.addView(createHudDivider(true))
            controlBox.addView(tapBtn)
            controlBox.addView(createHudDivider(true))
            controlBox.addView(switchBtn)
        }

        return controlBox
    }

    // ==================== MASK EDITOR ====================

    private var maskEditorOverlay: FrameLayout? = null
    private var maskBackupNodes: List<MaskManager.Node>? = null
    private var maskBackupSmoothness: Float = 0f
    private var maskBackupEnabled: Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    fun showMaskEditor() {
        if (maskEditorOverlay != null) return

        // Backup current state for cancel
        maskBackupNodes = maskManager.nodes.map { MaskManager.Node(it.x, it.y) }
        maskBackupSmoothness = maskManager.smoothness
        maskBackupEnabled = maskManager.enabled

        // Enable mask live preview
        maskManager.enabled = true
        maskManager.needsRegenerate = true

        buildMaskEditorOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMaskEditorOverlay() {
        // Hide HUD
        overlayHUD.visibility = View.GONE

        val editor = FrameLayout(this)
        maskEditorOverlay = editor

        // Semi-transparent overlay to see the mask shape
        val dimOverlay = View(this).apply {
            setBackgroundColor(Color.argb(40, 0, 0, 0))
            isClickable = false
            isFocusable = false
        }
        editor.addView(dimOverlay, FrameLayout.LayoutParams(-1, -1))

        // The node canvas handles drawing lines and node handles
        val nodeView = MaskNodeView(this)
        editor.addView(nodeView, FrameLayout.LayoutParams(-1, -1))

        // Controls panel (centered, draggable)
        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(30, 20, 30, 20)
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 20, 20, 20))
                cornerRadius = 25f
                setStroke(2, Color.argb(120, 80, 80, 80))
            }
        }

        // Make panel draggable
        var dragStartX = 0f
        var dragStartY = 0f
        var panelStartX = 0f
        var panelStartY = 0f
        controlPanel.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    panelStartX = v.x
                    panelStartY = v.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = panelStartX + (event.rawX - dragStartX)
                    v.y = panelStartY + (event.rawY - dragStartY)
                    true
                }
                else -> false
            }
        }

        // Buttons row
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Enable/Disable toggle
        val enableBtn = TextView(this).apply {
            text = if (maskManager.enabled) "ENABLED" else "DISABLED"
            textSize = 14f
            setTextColor(if (maskManager.enabled) Color.WHITE else Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 20)
            setOnClickListener {
                maskManager.enabled = !maskManager.enabled
                if (maskManager.enabled) {
                    maskManager.needsRegenerate = true
                }
                text = if (maskManager.enabled) "ENABLED" else "DISABLED"
                setTextColor(if (maskManager.enabled) Color.WHITE else Color.GRAY)
            }
        }
        buttonsRow.addView(enableBtn)

        // Reset button
        buttonsRow.addView(TextView(this).apply {
            text = "RESET"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 20)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("RESET MASK?")
                    .setMessage("This will reset the mask to default shape (full rectangle).")
                    .setPositiveButton("Reset") { _, _ ->
                        maskManager.initDefaults()
                        maskManager.smoothness = 0.005f
                        maskManager.needsRegenerate = true
                        nodeView.invalidate()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        // Spacer
        buttonsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(40, 1)
        })

        // Cancel button (X)
        buttonsRow.addView(ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_close_clear_cancel))
            setColorFilter(Color.WHITE)
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { rightMargin = 20 }
            setOnClickListener { cancelMaskEditor() }
        })

        // Accept button (checkmark)
        buttonsRow.addView(ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_save))
            setColorFilter(Color.WHITE)
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setOnClickListener { acceptMaskEditor() }
        })

        controlPanel.addView(buttonsRow)

        // Smoothness slider row (full width, below buttons)
        val smoothRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 15, 10, 0)
        }

        smoothRow.addView(TextView(this).apply {
            text = "SMOOTH"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 20, 0)
        })

        smoothRow.addView(SeekBar(this).apply {
            max = 1000
            progress = (maskManager.smoothness * 5000f).toInt().coerceIn(0, 1000)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(40, 40); cornerRadius = 20f }
            thumbOffset = 0
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    if (f) {
                        maskManager.smoothness = p / 5000f
                        maskManager.needsRegenerate = true
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    maskManager.needsHiResRegenerate = true
                }
            })
        })

        controlPanel.addView(smoothRow, LinearLayout.LayoutParams(-1, -2))

        val dm = resources.displayMetrics
        val panelWidth = (min(dm.widthPixels, dm.heightPixels) * 0.85f).toInt()
        editor.addView(controlPanel, FrameLayout.LayoutParams(panelWidth, -2).apply {
            gravity = Gravity.CENTER
        })

        addContentView(editor, ViewGroup.LayoutParams(-1, -1))
    }

    private fun acceptMaskEditor() {
        // Save mask and keep it enabled
        val prefs = getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
        maskManager.saveToPrefs(prefs)
        maskBackupNodes = null
        dismissMaskEditor()
    }

    private fun cancelMaskEditor() {
        // Restore backup
        maskBackupNodes?.let { backup ->
            maskManager.nodes.clear()
            maskManager.nodes.addAll(backup)
        }
        maskManager.smoothness = maskBackupSmoothness
        maskManager.enabled = maskBackupEnabled
        maskManager.needsRegenerate = true
        maskBackupNodes = null
        dismissMaskEditor()
    }

    private fun dismissMaskEditor() {
        maskEditorOverlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        maskEditorOverlay = null
        overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MaskNodeView(context: Context) : View(context) {
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val nodeRadius = 30f
        private val hitRadius = 80f
        private var dragIndex = -1
        private var longPressHandler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var hasMoved = false
        private var downX = 0f
        private var downY = 0f

        private fun isPortrait(): Boolean =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        /** Calculate viewport rect with padding — 16:9 in landscape, 9:16 in portrait */
        private fun getViewport(): RectF {
            val padding = 0.06f
            val availW = width * (1f - 2 * padding)
            val availH = height * (1f - 2 * padding)
            val aspect = if (isPortrait()) 9f / 16f else 16f / 9f
            val fitW: Float
            val fitH: Float
            if (availW / availH > aspect) {
                fitH = availH
                fitW = fitH * aspect
            } else {
                fitW = availW
                fitH = fitW / aspect
            }
            val left = (width - fitW) / 2f
            val top = (height - fitH) / 2f
            return RectF(left, top, left + fitW, top + fitH)
        }

        /** Convert node coords (0..1 in FBO landscape space) to screen pixel coords */
        private fun nodeToScreen(node: MaskManager.Node): Pair<Float, Float> {
            val vp = getViewport()
            return if (isPortrait()) {
                // After -90° rotation: FBO x→screen Y, FBO y→screen X (inverted)
                Pair(vp.left + (1f - node.y) * vp.width(), vp.top + node.x * vp.height())
            } else {
                Pair(vp.left + node.x * vp.width(), vp.top + node.y * vp.height())
            }
        }

        /** Convert screen pixel coords back to node coords (0..1 in FBO landscape space) */
        private fun screenToNode(sx: Float, sy: Float): Pair<Float, Float> {
            val vp = getViewport()
            return if (isPortrait()) {
                val nx = ((sy - vp.top) / vp.height()).coerceIn(0f, 1f)
                val ny = (1f - (sx - vp.left) / vp.width()).coerceIn(0f, 1f)
                Pair(nx, ny)
            } else {
                val nx = ((sx - vp.left) / vp.width()).coerceIn(0f, 1f)
                val ny = ((sy - vp.top) / vp.height()).coerceIn(0f, 1f)
                Pair(nx, ny)
            }
        }

        override fun onDraw(canvas: Canvas) {
            // Draw 16:9 viewport border
            val vp = getViewport()
            canvas.drawRect(vp, borderPaint)

            val nodes = maskManager.nodes
            if (nodes.size < 2) return

            // Draw lines
            for (i in nodes.indices) {
                val (x1, y1) = nodeToScreen(nodes[i])
                val (x2, y2) = nodeToScreen(nodes[(i + 1) % nodes.size])
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }

            // Draw nodes
            for (node in nodes) {
                val (sx, sy) = nodeToScreen(node)
                canvas.drawCircle(sx, sy, nodeRadius, nodePaint)
                canvas.drawCircle(sx, sy, nodeRadius, nodeStrokePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val nodes = maskManager.nodes
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    hasMoved = false
                    // Find closest node
                    dragIndex = -1
                    var minDist = hitRadius
                    for (i in nodes.indices) {
                        val (sx, sy) = nodeToScreen(nodes[i])
                        val d = hypot(event.x - sx, event.y - sy)
                        if (d < minDist) {
                            minDist = d
                            dragIndex = i
                        }
                    }

                    // Set up long press detection
                    longPressRunnable = Runnable {
                        if (!hasMoved) {
                            if (dragIndex >= 0 && nodes.size > 3) {
                                // Long press on a node: delete it
                                nodes.removeAt(dragIndex)
                                dragIndex = -1
                                maskManager.needsRegenerate = true
                                invalidate()
                            } else if (dragIndex < 0) {
                                // Long press on empty area near a line: add node
                                val (nx, ny) = screenToNode(event.x, event.y)
                                val insertIdx = findClosestEdge(nx, ny)
                                nodes.add(insertIdx + 1, MaskManager.Node(nx, ny))
                                dragIndex = insertIdx + 1
                                maskManager.needsRegenerate = true
                                invalidate()
                            }
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, 500)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > 20f) {
                        hasMoved = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    }
                    if (dragIndex >= 0 && hasMoved) {
                        val (nx, ny) = screenToNode(event.x, event.y)
                        nodes[dragIndex].x = nx
                        nodes[dragIndex].y = ny
                        maskManager.needsRegenerate = true
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    dragIndex = -1
                    maskManager.needsHiResRegenerate = true
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun findClosestEdge(nx: Float, ny: Float): Int {
            val nodes = maskManager.nodes
            var bestIdx = 0
            var bestDist = Float.MAX_VALUE
            for (i in nodes.indices) {
                val j = (i + 1) % nodes.size
                val dist = pointToSegmentDist(nx, ny, nodes[i].x, nodes[i].y, nodes[j].x, nodes[j].y)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            return bestIdx
        }

        private fun pointToSegmentDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val lenSq = dx * dx + dy * dy
            if (lenSq == 0f) return hypot(px - ax, py - ay)
            val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
            val ct = t.coerceIn(0f, 1f)
            val cx = ax + ct * dx; val cy = ay + ct * dy
            return hypot(px - cx, py - cy)
        }
    }

    // ==================== END MASK EDITOR ====================

    private fun createPresetPanel(): LinearLayout {
        presetPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10, 10, 10, 10)
            clipChildren = false
            clipToPadding = false
        }

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
                min = 0, max = 30000, sliderMax = 30000,
                defaultValue = 2500,
                layoutStyle = PropertyControl.LayoutStyle.ROW,
                iconResId = android.R.drawable.ic_menu_recent_history,
                includeInPreset = false,
                hasModulation = false,
                logPower = 2,
                showValue = true,
                allowSmoothing = false,
                valueFormatter = { "%.1fs".format(it / 1000f) }
            ) { transitionMs = it.toLong() }
            controls.add(transCtrl)
            controlsMap[transId] = transCtrl
        }

        val sliderWrapper = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        transCtrl.attachTo(this, sliderWrapper)
        transContainer.addView(sliderWrapper)

        playBtn = ImageButton(this).apply {
            setImageDrawable(createPlayIcon())
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(90, 80).apply { leftMargin = 12; topMargin = 5; bottomMargin = 5 }
            setPadding(22, 22, 22, 22)
            setOnClickListener { toggleAutoPlay() }
            setOnLongClickListener {
                if (midiHelper.isConnected) {
                    showMidiLearnOverlay("CMD_AUTOPLAY", "AUTO-PLAY")
                    true
                } else false
            }
        }
        updatePlayButtonState()
        updatePlayBtnBackground()

        transContainer.addView(playBtn)

        presetPanel.addView(transContainer)

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

        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(240, 0,0,0)); cornerRadius = 15f }
            layoutParams = FrameLayout.LayoutParams(-2, 120, Gravity.CENTER)
            elevation = 100f
            isClickable = true
        }

        fun showOptionsForPreset(idx: Int) {
            optionsContainer.removeAllViews()
            pendingSaveIndex = idx

            optionsContainer.addView(Button(this).apply {
                text = "SAVE $idx"
                setTextColor(Color.BLACK)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12f }
                layoutParams = LinearLayout.LayoutParams(180, 100).apply { setMargins(15,10,10,10) }
                setOnClickListener {
                    savePreset(idx)
                    optionsContainer.visibility = View.GONE
                }
            })

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
                layoutParams = LinearLayout.LayoutParams(90, 110).apply { setMargins(2, 0, 2, 0) }
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
            applyRobustTouch(b)
            btnRow.addView(b)
        }

        scroller.addView(btnRow)
        presetRow.addView(scroller)
        presetRow.addView(optionsContainer)

        saveConfirmBtn = Button(this)

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
            updatePlayBtnBackground()
            Toast.makeText(this, "Auto-Play Started", Toast.LENGTH_SHORT).show()
            triggerNextAutoPlay()
        }
    }

    private fun stopAutoPlay() {
        isAutoPlaying = false
        handler.removeCallbacks(autoPlayRunnable)
        if (::playBtn.isInitialized) updatePlayBtnBackground() // Grey

        // Save Settings on stop
        val prefs = getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
        val filterStr = autoPlayFilter.joinToString(",")
        prefs.edit()
            .putBoolean("AP_RANDOM", autoPlayRandom)
            .putString("AP_FILTER", filterStr)
            .putInt("UNDO_HISTORY", undoHistorySize)
            .putBoolean("FORCE_SCREEN_ON", forceScreenOn)
            .apply()
        maskManager.saveToPrefs(prefs)
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

    private fun createPlayIcon(): BitmapDrawable {
        val size = 200
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val path = Path()
        path.moveTo(50f, 40f)
        path.lineTo(50f, 160f)
        path.lineTo(160f, 100f)
        path.close()
        c.drawPath(path, p)
        return BitmapDrawable(resources, b)
    }

    private val playBtnDrawable = object : android.graphics.drawable.Drawable() {
        var active = false
        var progress = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w == 0f || h == 0f) return
            val r = 12f
            val box = RectF(2f, 2f, w - 2f, h - 2f)

            // Fill
            if (progress > 0f) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(100, 255, 255, 255)
                canvas.save()
                canvas.clipRect(0f, h - h * progress, w, h)
                canvas.drawRoundRect(box, r, r, paint)
                canvas.restore()
            }

            // Border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = if (active) Color.WHITE else Color.parseColor("#505050")
            canvas.drawRoundRect(box, r, r, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private var playBtnAnimator: ValueAnimator? = null

    private fun updatePlayBtnBackground() {
        if (!::playBtn.isInitialized) return
        playBtnDrawable.active = isAutoPlaying
        if (!isAutoPlaying) {
            playBtnAnimator?.cancel()
            playBtnDrawable.progress = 0f
        }
        playBtn.background = playBtnDrawable
        playBtnDrawable.invalidateSelf()
    }

    private fun startPlayBtnFill() {
        if (!::playBtn.isInitialized || !isAutoPlaying) return
        playBtnAnimator?.cancel()
        playBtnDrawable.progress = 0f
        playBtnDrawable.invalidateSelf()
        playBtnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = autoPlayDurationMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                playBtnDrawable.progress = it.animatedValue as Float
                playBtnDrawable.invalidateSelf()
            }
            start()
        }
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

        val panels = listOf(controlBox, presetPanel)
        val utils = listOf(menuBtn, settingsBtn)

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
                    recordingSeconds++; val h = recordingSeconds / 3600; val m = (recordingSeconds % 3600) / 60; val s = recordingSeconds % 60
                    val timeStr = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
                    recordBtn.setImageDrawable(textToIcon(timeStr, 0f, Color.RED)); handler.postDelayed(this, 1000)
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

    fun applyForceScreenOn() {
        if (!forceScreenOn) return
        if (android.provider.Settings.System.canWrite(this)) {
            if (originalScreenTimeout == -1) {
                originalScreenTimeout = android.provider.Settings.System.getInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 30000
                )
            }
            android.provider.Settings.System.putInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE
            )
        }
    }

    fun restoreScreenTimeout() {
        if (android.provider.Settings.System.canWrite(this) && originalScreenTimeout > 0) {
            android.provider.Settings.System.putInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, originalScreenTimeout
            )
            originalScreenTimeout = -1
        }
    }

    fun requestForceScreenOnPermission() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        startActivity(intent)
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

        undoManager.pushStateDirectly(controls, activePreset)

        presetAnimators.values.forEach { it.cancel() }
        presetAnimators.clear()

        presetDrawables.forEach { (id, drawable) ->
            drawable.setProgress(0f)
            drawable.isActive = (id == idx)
            drawable.invalidateSelf()
        }

        activePreset = idx
        transitionStartTime = System.currentTimeMillis()

        val durationSec = transitionMs / 1000f

        // Clear play button fill at start of each transition
        playBtnAnimator?.cancel()
        playBtnDrawable.progress = 0f
        playBtnDrawable.invalidateSelf()

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
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (isAutoPlaying) startPlayBtnFill()
                    }
                })
                start()
            }
            presetAnimators[idx] = anim
        }

        val startMRot = renderer.mRotAccum
        val startCRot = renderer.cRotAccum
        val targetMRot = round(startMRot / 360.0) * 360.0
        val targetCRot = round(startCRot / 360.0) * 360.0

        renderer.animateRotationTo(targetMRot, targetCRot, durationSec)

        val targetSnapshots = mutableMapOf<String, PropertyControl.Snapshot>()
        controls.forEach { control ->
            if (!control.includeInPreset) return@forEach

            if (control.isLocked) {
                // Locked controls are not changed by presets — record their current state
                targetSnapshots[control.id] = control.getSnapshot()
                return@forEach
            }

            var snap = p.controlSnapshots[control.id]

            if (snap == null) {
                if (control.id == "AXIS") {
                    snap = PropertyControl.Snapshot(
                        value = p.axis,
                        active = false,
                        rate = 0, depth = 0, shape = "SINE", smoothing = 0
                    )
                } else {
                    snap = PropertyControl.Snapshot(
                        value = control.defaultValue,
                        active = false,
                        rate = 200, depth = 0, shape = "SINE", smoothing = 500,
                        isSynced = false, syncIndex = 3
                    )
                }
            }

            control.restore(snap, durationSec)
            targetSnapshots[control.id] = snap
        }
        // Record what we're transitioning towards so undo/redo stays consistent
        undoManager.targetState = UndoManager.UndoState(targetSnapshots, idx)
        updateSidebarVisuals()

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
                snapObj.put("synced", snap.isSynced)
                snapObj.put("syncIdx", snap.syncIndex)
                snapObj.put("sax", snap.sensorAccelX)
                snapObj.put("say", snap.sensorAccelY)
                snapObj.put("saz", snap.sensorAccelZ)
                snapObj.put("sp",  snap.sensorPitch)
                snapObj.put("sro", snap.sensorRoll)
                snapObj.put("sy",  snap.sensorYaw)
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
                            s.optInt("s", 500),
                            s.optBoolean("synced", false),
                            s.optInt("syncIdx", 3),
                            s.optInt("sax", 500),
                            s.optInt("say", 500),
                            s.optInt("saz", 500),
                            s.optInt("sp",  500),
                            s.optInt("sro", 500),
                            s.optInt("sy",  500)
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
            else -> pFunc(2, emptyArray<Any>())
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

}
