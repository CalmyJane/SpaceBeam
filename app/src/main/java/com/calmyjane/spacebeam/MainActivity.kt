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
import android.animation.LayoutTransition
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import javax.microedition.khronos.egl.EGLConfig as GL10EGLConfig
import android.opengl.EGLConfig as EGL14EGLConfig
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.C
import kotlin.apply
import android.view.inputmethod.InputMethodManager
import android.content.Context.INPUT_METHOD_SERVICE


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

        // Auto-Play Duration using PropertyControl
        autoPlayDurationControl = PropertyControl(
            "AUTO_DUR", "DURATION",
            min = 0, max = 300000, sliderMax = 60000,
            defaultValue = activity.autoPlayDurationMs.toInt(),
            layoutStyle = PropertyControl.LayoutStyle.STACKED,
            includeInPreset = false,
            hasModulation = false,
            logPower = 2,
            showValue = true,
            valueFormatter = { "%.1fs".format(it / 1000f) }
        ) {
            activity.autoPlayDurationMs = it.toLong()
        }

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
        presentation?.dismiss()
        presentation = null
    }

    private fun updatePresentation() {
        // Look for secondary displays (HDMI, Wireless Display)
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (displays.isNotEmpty()) {
            val externalDisplay = displays[0]
            // If we are already showing on this display, do nothing
            if (presentation != null && presentation!!.display.displayId == externalDisplay.displayId) {
                // Optional: You might want to update the renderer's surface size if the *external* display changed res
                // but usually, we just return here to be safe.
                return
            }
            // Dismiss old one if display changed
            try { presentation?.dismiss() } catch(e: Exception) {}
            presentation = null
            // Create new Presentation
            presentation = CleanFeedPresentation(context, externalDisplay, renderer).apply {
                try {
                    show()
                } catch (e: WindowManager.InvalidDisplayException) {
                    dismiss()
                }
            }
        } else {
        // No external display, clean up
            try { presentation?.dismiss() } catch(e: Exception) {}
            presentation = null
            renderer.removeExternalSurface()
        }
    }

    /**
     * Inner class representing the Window on the secondary screen.
     * It contains ONLY the SurfaceView (no UI buttons).
     */
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
                    renderer.setExternalSurface(holder.surface, display.width, display.height)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    renderer.setExternalSurface(holder.surface, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    renderer.removeExternalSurface()
                }
            })
        }
    }
}

class PropertyControl(
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
    val iconResId: Int? = null,
    val layoutStyle: LayoutStyle = LayoutStyle.STACKED,
    val includeInPreset: Boolean = true,
    val logPower: Int = 1,
    val showValue: Boolean = false,
    val valueSuffix: String = "",
    val valueFormatter: ((Int) -> String)? = null,
    private val onValueChanged: ((Int) -> Unit)? = null
) {
    enum class ModMode { WRAP, CLAMP }
    enum class WaveShape { SINE, TRIANGLE, RAMP, WOBBLE_SINE, RANDOM_SMOOTH, RANDOM_STEP }
    enum class LayoutStyle { STACKED, ROW }

    // New property to control layering depth
    var popupElevation: Float = 40f

    companion object {
        var activeControl: PropertyControl? = null
        fun closeActiveMenu() {
            activeControl?.closeMenu()
        }
    }

    @Volatile var value: Int = defaultValue
        private set
    @Volatile var preciseValue: Float = defaultValue.toFloat()
        private set

    private var animTarget: Float? = null
    private var animStart: Float = 0f
    private var animDuration: Float = 0f
    private var animTime: Float = 0f
    private var isAnimating = false

    var modRate: Int = 200
    var modDepth: Int = 0
    var modShape: WaveShape = if (modMode == ModMode.WRAP) WaveShape.RAMP else WaveShape.SINE

    var preciseModRate: Float = 200f
    var preciseModDepth: Float = 0f
    var lfoPhase: Double = 0.0

    private var lastComputedNormalized: Float = 0f
    private var modSnapshotValue: Float = 0f
    private var modRateStart = 0f
    private var modRateTarget: Float? = null
    private var modDepthStart = 0f
    private var modDepthTarget: Float? = null

    private var mainSeekBar: SeekBar? = null
    private var modIndicator: View? = null
    private var valueDisplay: TextView? = null
    private var mainRowLayout: LinearLayout? = null

    private var floatingPanel: LinearLayout? = null
    private var modPanelSpeedSeekBar: SeekBar? = null
    private var modPanelDepthSeekBar: SeekBar? = null
    private var liveValueDisplay: TextView? = null
    private var baseValueInput: EditText? = null
    private var shapeBtn: Button? = null
    private var currentContext: Context? = null

    val computedValue: Float
        get() {
            val norm = lastComputedNormalized
            return outMin + (norm * (outMax - outMin))
        }

    data class Snapshot(val value: Int, val active: Boolean, val rate: Int, val depth: Int, val shape: String)

    fun getSnapshot(): Snapshot = Snapshot(value, modDepth > 0, modRate, modDepth, modShape.name)

    fun restore(s: Snapshot, durationSec: Float) {
        animateTo(s.value.toFloat(), durationSec, s.shape)
        if (hasModulation) {
            animateModulation(s.rate.toFloat(), s.depth.toFloat(), durationSec)
        }
    }

    fun animateTo(target: Float, durationSec: Float, newShape: String? = null) {
        modSnapshotValue = lastComputedNormalized
        animTarget = target
        animStart = preciseValue
        animDuration = durationSec
        animTime = 0f
        isAnimating = true
        if (newShape != null) {
            try {
                val targetShape = WaveShape.valueOf(newShape)
                modShape = targetShape
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
        val ease = 1f - (1f - t).pow(3f)

        if (isAnimating && animTarget != null) {
            animTime += deltaTime
            if (animTime >= animDuration) {
                preciseValue = animTarget!!
                value = preciseValue.toInt()
                modRateTarget?.let { preciseModRate = it; modRate = it.toInt() }
                modDepthTarget?.let { preciseModDepth = it; modDepth = it.toInt() }
                modRateTarget = null; modDepthTarget = null
                isAnimating = false
                syncUiElements()
            } else {
                preciseValue = animStart + (animTarget!! - animStart) * ease
                value = preciseValue.toInt()
                modRateTarget?.let { preciseModRate = modRateStart + (it - modRateStart) * ease }
                modDepthTarget?.let { preciseModDepth = modDepthStart + (it - modDepthStart) * ease }
                syncUiElements()
            }
        }

        val ratio = (preciseValue / sliderMax.toFloat()).coerceAtLeast(0f)
        val curvedNorm = if (logPower > 1) ratio.pow(logPower) else ratio

        if (!hasModulation || (preciseModRate == 0f && preciseModDepth == 0f && modDepthTarget == null)) {
            lastComputedNormalized = curvedNorm
            updateLiveValueUI(value)
            return
        }

        val baseSpeed = (preciseModRate / 1000f + 0.05f).pow(3f)
        lfoPhase += baseSpeed * deltaTime * 2.0 * Math.PI
        if (lfoPhase > 2.0 * Math.PI) lfoPhase -= 2.0 * Math.PI

        val rawWave: Double = when (modShape) {
            WaveShape.SINE -> sin(lfoPhase) * 0.5 + 0.5
            WaveShape.TRIANGLE -> { val p = (lfoPhase / (2.0 * Math.PI)); if (p < 0.5) p * 2.0 else 2.0 - (p * 2.0) }
            WaveShape.RAMP -> (lfoPhase / (2.0 * Math.PI)) % 1.0
            WaveShape.WOBBLE_SINE -> { val w = sin(lfoPhase + sin(lfoPhase)); w * 0.5 + 0.5 }
            WaveShape.RANDOM_SMOOTH -> (sin(lfoPhase) * 0.5 + 0.5 + sin(lfoPhase * 2.3) * 0.2) / 1.4
            WaveShape.RANDOM_STEP -> Math.random()
        }

        val depthNorm = (preciseModDepth / 1000f).pow(2f)
        val targetVal = if (modMode == ModMode.WRAP) {
            (curvedNorm + (rawWave * depthNorm)) % 1.0f
        } else {
            curvedNorm + (rawWave.toFloat() * depthNorm * (1.0f - curvedNorm))
        }

        lastComputedNormalized = if (isAnimating) {
            (modSnapshotValue * (1.0f - ease)) + (targetVal.toFloat() * ease)
        } else {
            targetVal.toFloat()
        }

        modIndicator?.postInvalidate()
        val displayVal = if (logPower > 1) {
            (lastComputedNormalized.pow(1.0f/logPower) * sliderMax).toInt()
        } else {
            (lastComputedNormalized * sliderMax).toInt()
        }
        updateLiveValueUI(displayVal)
    }

    private fun syncUiElements() {
        val ratio = (value.toFloat() / sliderMax.toFloat()).coerceIn(0f, 1f)
        val sliderT = if (logPower > 1) ratio.pow(1.0f / logPower) else ratio
        val seekProgress = (sliderT * 1000).toInt()

        val sb = mainSeekBar
        if (sb != null) {
            sb.post {
                if (sb.progress != seekProgress) {
                    try { sb.progress = seekProgress } catch (e: Exception) {}
                }
            }
        }

        if (showValue && valueDisplay != null) {
            valueDisplay?.post { valueDisplay?.text = formatValue(value) }
        }

        if (activeControl == this) {
            baseValueInput?.post {
                if (baseValueInput?.hasFocus() == false) baseValueInput?.setText(value.toString())
            }
            modPanelSpeedSeekBar?.post { modPanelSpeedSeekBar?.progress = preciseModRate.toInt() }
            modPanelDepthSeekBar?.post { modPanelDepthSeekBar?.progress = preciseModDepth.toInt() }
        }
    }

    private fun formatValue(v: Int): String {
        return if (valueFormatter != null) valueFormatter!!(v) else "$v$valueSuffix"
    }

    private fun updateLiveValueUI(v: Int) {
        if (activeControl == this && liveValueDisplay != null) {
            liveValueDisplay?.post { liveValueDisplay?.text = formatValue(v) }
        }
    }

    private fun setProgressFromSlider(p: Int) {
        if (isAnimating) stopAnimation()
        val t = p / 1000f
        val curvedT = if (logPower > 1) t.pow(logPower) else t
        val calcVal = (curvedT * sliderMax).toInt().coerceIn(min, max)
        value = calcVal
        preciseValue = calcVal.toFloat()

        if (showValue && valueDisplay != null) valueDisplay?.text = formatValue(value)
        if (activeControl == this && baseValueInput != null && !baseValueInput!!.hasFocus()) {
            baseValueInput!!.setText("$calcVal")
        }
        onValueChanged?.invoke(calcVal)
    }

    fun setProgress(v: Int) {
        if (isAnimating) stopAnimation()
        val clamped = v.coerceIn(min, max)
        value = clamped
        preciseValue = clamped.toFloat()
        syncUiElements()
        onValueChanged?.invoke(clamped)
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

    fun reset() {
        stopAnimation()
        setProgress(defaultValue)
        if (hasModulation) {
            updateModRate(200)
            updateModDepth(0)
            modShape = if (modMode == ModMode.WRAP) WaveShape.RAMP else WaveShape.SINE
            updateIndicatorVisuals()
            shapeBtn?.text = modShape.name
        }
    }

    fun stopAnimation() {
        isAnimating = false; animTarget = null; modRateTarget = null; modDepthTarget = null
    }

    fun detach() {
        mainSeekBar = null
        modIndicator = null
        valueDisplay = null
        mainRowLayout = null
        if (activeControl == this) closeMenu()
        currentContext = null
    }

    fun attachTo(context: Context, parent: ViewGroup) {
        detach()
        currentContext = context

        val container = LinearLayout(context).apply {
            orientation = if (layoutStyle == LayoutStyle.STACKED) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

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

            val params = if (layoutStyle == LayoutStyle.STACKED) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60).apply { bottomMargin = 5 }
            } else {
                LinearLayout.LayoutParams(220, 70).apply { rightMargin = 15 }
            }
            layoutParams = params
        }

        if (iconResId != null) {
            val iv = ImageView(context).apply {
                setImageResource(iconResId)
                setColorFilter(Color.WHITE)
                alpha = 0.8f
                layoutParams = LinearLayout.LayoutParams(36, 36).apply { rightMargin = 8 }
            }
            labelContainer.addView(iv)
        }

        val tv = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            alpha = 0.9f
            gravity = Gravity.CENTER
        }
        labelContainer.addView(tv)
        container.addView(labelContainer)

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, 55, 1f)
        }

        if (layoutStyle == LayoutStyle.STACKED) {
            sliderRow.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 55)
        }
        this.mainRowLayout = sliderRow

        if (showValue) {
            valueDisplay = TextView(context).apply {
                text = formatValue(value)
                setTextColor(Color.LTGRAY)
                textSize = 9f
                minWidth = 50
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0,0,8,0)
            }
            sliderRow.addView(valueDisplay)
        }

        val sb = SeekBar(context).apply {
            max = 1000
            val ratio = (value.toFloat() / sliderMax.toFloat()).coerceIn(0f, 1f)
            val sliderT = if (logPower > 1) ratio.pow(1.0f / logPower) else ratio
            progress = (sliderT * 1000).toInt()

            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            thumbOffset = 0
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        this.mainSeekBar = sb
        sb.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                stopAnimation()
                if (activeControl != null && activeControl != this@PropertyControl) closeActiveMenu()
            }
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) v.parent.requestDisallowInterceptTouchEvent(false)
            v.onTouchEvent(event); true
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setProgressFromSlider(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sliderRow.addView(sb)

        if (hasModulation) {
            modIndicator = object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f; val r = (Math.min(width, height) / 2f) - 2f; val active = modDepth > 0
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.WHITE; paint.alpha = 255
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL; paint.color = if (active) Color.WHITE else Color.LTGRAY; paint.alpha = if (active) 255 else 100
                    var dotRadius = r * 0.3f
                    if (active) {
                        val waveVal = if (preciseModDepth > 0) (lastComputedNormalized - (preciseValue/sliderMax)) / (preciseModDepth/1000f) else 0f
                        dotRadius = (r * (0.3 + (abs(waveVal)*0.7))).toFloat()
                    }
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(55, 55).apply { leftMargin = 15 }; setOnClickListener { toggleMenu(context) } }
            sliderRow.addView(modIndicator)
        }

        container.addView(sliderRow)
        parent.addView(container)
    }

    private fun updateIndicatorVisuals() { modIndicator?.invalidate() }

    fun toggleMenu(ctx: Context? = currentContext) {
        if (activeControl == this) closeMenu()
        else {
            activeControl?.closeMenu()
            if (ctx != null) openMenu(ctx)
        }
    }

    fun closeMenu() {
        if (floatingPanel != null) {
            val ctx = currentContext ?: return

            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            baseValueInput?.windowToken?.let { imm?.hideSoftInputFromWindow(it, 0) }

            (floatingPanel?.parent as? ViewGroup)?.removeView(floatingPanel)

            floatingPanel = null
            modPanelSpeedSeekBar = null
            modPanelDepthSeekBar = null
            liveValueDisplay = null
            baseValueInput = null
            shapeBtn = null

            (ctx as? MainActivity)?.hideSystemUI()
        }
        if (activeControl == this) activeControl = null
    }

    private fun openMenu(context: Context) {
        val activity = context as? MainActivity ?: return
        val rootLayout = activity.overlayHUD
        val dm = context.resources.displayMetrics
        val isPortrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        floatingPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 15, 15, 15))
                cornerRadius = 20f
                setStroke(2, Color.GRAY)
            }

            elevation = popupElevation

            isClickable = true
            layoutParams = if (isPortrait) {
                val menuHeight = (dm.heightPixels * 0.40).toInt()
                FrameLayout.LayoutParams(700, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = menuHeight + 20 }
            } else {
                FrameLayout.LayoutParams(600, -2).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; leftMargin = 880 }
            }
        }

        floatingPanel?.addView(TextView(context).apply {
            text = label; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
        })

        val numRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140).apply { bottomMargin = 10 }
        }

        val btnDec = createNumButton(context, "-") { setProgress(value - 1) }

        baseValueInput = EditText(context).apply {
            setText(value.toString())
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = null
            setPadding(0, 10, 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f)

            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    try {
                        val num = v.text.toString().toIntOrNull() ?: value
                        setProgress(num)
                        v.clearFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(v.windowToken, 0)
                        (context as? MainActivity)?.hideSystemUI()
                    } catch (e: Exception) {}
                    true
                } else false
            }
        }

        val btnInc = createNumButton(context, "+") { setProgress(value + 1) }

        numRow.addView(btnDec); numRow.addView(baseValueInput); numRow.addView(btnInc)
        floatingPanel?.addView(numRow)

        val liveRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
        }
        liveValueDisplay = TextView(context).apply {
            text = formatValue(value)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
        }
        liveRow.addView(liveValueDisplay)
        floatingPanel?.addView(liveRow)

        floatingPanel?.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { bottomMargin = 20 }
            setBackgroundColor(Color.DKGRAY)
        })

        if (hasModulation) {
            shapeBtn = Button(context).apply {
                text = modShape.name
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = 10f
                    setStroke(1, Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 25 }
                setOnClickListener {
                    val nextOrdinal = (modShape.ordinal + 1) % WaveShape.values().size
                    modShape = WaveShape.values()[nextOrdinal]
                    text = modShape.name
                }
            }
            floatingPanel?.addView(shapeBtn)

            modPanelSpeedSeekBar = addSliderToPanel(context, "SPEED", modRate) { updateModRate(it) }
            modPanelDepthSeekBar = addSliderToPanel(context, "DEPTH", modDepth) { updateModDepth(it); updateIndicatorVisuals() }
            floatingPanel?.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 20) })
        }

        floatingPanel?.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 10) })

        val resetBtn = Button(context).apply {
            text = "RESET"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            // FIX: Remove padding and font padding to ensure centering
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER

            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(2, Color.DKGRAY)
                cornerRadius = 12f
            }
            // FIX: Increased height to 110 and added margins to prevent clipping
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110).apply {
                bottomMargin = 10
                topMargin = 5
            }
            setOnClickListener {
                reset()
            }
        }
        floatingPanel?.addView(resetBtn)

        rootLayout.addView(floatingPanel)
        activeControl = this
    }

    private fun createNumButton(ctx: Context, txt: String, action: () -> Unit): Button {
        return Button(ctx).apply {
            text = txt
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 15f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(5, 5, 5, 5)
            }
            setOnClickListener { action() }
        }
    }

    private fun addSliderToPanel(ctx: Context, name: String, current: Int, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
        row.addView(TextView(ctx).apply { text=name; textSize=10f; setTextColor(Color.LTGRAY); layoutParams=LinearLayout.LayoutParams(120, -2) })
        val sb = SeekBar(ctx).apply {
            max = 1000; progress = current; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(sb)
        floatingPanel?.addView(row)
        return sb
    }
}

// --- MAIN ACTIVITY ---
class MainActivity : AppCompatActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: KaleidoscopeRenderer
    private var currentSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    lateinit var overlayHUD: FrameLayout
    private lateinit var displayHelper: ExternalDisplayHelper
    private lateinit var axisSb: SeekBar
    private lateinit var transSeekBar: SeekBar
    val controls = java.util.concurrent.CopyOnWriteArrayList<PropertyControl>()
    val controlsMap = java.util.concurrent.ConcurrentHashMap<String, PropertyControl>()
    private val presetButtons = mutableMapOf<Int, Button>()
    private lateinit var menuBtn: Button
    // private var currentAnimator: ValueAnimator? = null // REMOVED
    private var activePreset: Int = -1
    private lateinit var flipXBtn: ImageButton
    private lateinit var flipYBtn: ImageButton
    private lateinit var rot180Btn: ImageButton

    private var settingsMenu: SettingsMenu? = null
    private lateinit var photoBtn: ImageButton
    private lateinit var recordBtn: ImageButton
    private lateinit var flashOverlay: View
    private lateinit var leftHUDContainer: LinearLayout
    private var axisLocked = true
    private lateinit var lockBtn: Button
    private lateinit var saveConfirmBtn: Button
    private var lastFingerDist = 0f
    private var lastFingerAngle = 0f
    private var lastFingerFocusX = 0f
    private var lastFingerFocusY = 0f
    private var exoPlayer: ExoPlayer? = null
    private var isRtspMode = false
    private var lastRtspUrl: String = "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4"

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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        isRebuildingHUD = true

        // 1. Save State
        val activeControlId = PropertyControl.activeControl?.id
        PropertyControl.closeActiveMenu()

        var wasSettingsOpen = false
        var savedScrollY = 0
        if (settingsMenu != null && settingsMenu!!.isOpen()) {
            wasSettingsOpen = true
            savedScrollY = settingsMenu!!.getScrollY()
            settingsMenu!!.cleanup()
        }

        val savedTransProgress = if (::transSeekBar.isInitialized) transSeekBar.progress else 333
        val savedIsAutoPlaying = isAutoPlaying

        // 2. Rebuild
        // IMPORTANT: Just remove children, do not nullify overlayHUD
        if (::overlayHUD.isInitialized) {
            overlayHUD.removeAllViews()
        }

        presetButtons.clear()

        // This will now refill the EXISTING overlayHUD
        setupOverlayHUD()

        // 3. Restore State
        if (::overlayHUD.isInitialized) {
            overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE
        }
        applyReadabilityStyle()
        updateSidebarVisuals()

        if (::transSeekBar.isInitialized) transSeekBar.progress = savedTransProgress

        if (activePreset != -1) {
            val drawable = presetDrawables[activePreset]
            drawable?.isActive = true
            drawable?.invalidateSelf()
        }

        isAutoPlaying = savedIsAutoPlaying
        if (::playBtn.isInitialized) playBtn.setImageDrawable(createPlayIcon(isAutoPlaying))

        // Restore Menus - Using the Thread-Safe map
        if (activeControlId != null && controlsMap.containsKey(activeControlId)) {
            handler.postDelayed({ controlsMap[activeControlId]?.toggleMenu() }, 50)
        }

        if (wasSettingsOpen) {
            settingsMenu = SettingsMenu(this, overlayHUD)
            settingsMenu?.show()
            settingsMenu?.restoreScrollY(savedScrollY)
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

    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {}
                startLocalMedia(uri)
            }
        }
    }

    private fun startLocalMedia(uri: android.net.Uri) {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({ try { cpFuture.get().unbindAll() } catch (e: Exception) {} }, ContextCompat.getMainExecutor(this))
        val mimeType = contentResolver.getType(uri)
        val isImage = mimeType?.startsWith("image") == true

        glView.queueEvent {
            renderer.resetVideoTexture()
            val surface = renderer.getPlayerSurface() ?: return@queueEvent
            runOnUiThread {
                if (isImage) {
                    try {
                        exoPlayer?.stop()
                        exoPlayer?.clearVideoSurface()
                        val inputStream = contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            renderer.updateTextureSize(bitmap.width, bitmap.height)
                            val canvas = surface.lockCanvas(null)
                            canvas.drawColor(Color.BLACK)
                            val destRect = Rect(0, 0, canvas.width, canvas.height)
                            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                            canvas.drawBitmap(bitmap, srcRect, destRect, null)
                            surface.unlockCanvasAndPost(canvas)
                            isRtspMode = true
                            Toast.makeText(this, "Image Loaded", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) { Log.e("Media", "Image Load Failed", e) }
                } else {
                    if (exoPlayer == null) exoPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)).build()
                    exoPlayer?.stop()
                    exoPlayer?.clearVideoSurface()
                    exoPlayer?.setVideoSurface(surface)
                    exoPlayer?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    val mediaItem = MediaItem.fromUri(uri)
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
                    exoPlayer?.volume = 0f
                    exoPlayer?.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) renderer.updateTextureSize(videoSize.width, videoSize.height)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Toast.makeText(this@MainActivity, "Video Error: Try a different file", Toast.LENGTH_SHORT).show()
                        }
                    })
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    isRtspMode = true
                    Toast.makeText(this, "Playing Video", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        hideSystemUI()

        renderer = KaleidoscopeRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
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
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
        } else {
            startCamera()
        }
    }

    fun startCamera() {
        stopRtsp()
        isRtspMode = false
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val provider = cpFuture.get()
            provider.unbindAll()
            glView.queueEvent {
                renderer.resetVideoTexture()
                runOnUiThread {
                    val preview = Preview.Builder().setTargetRotation(Surface.ROTATION_90).build()
                    preview.setSurfaceProvider { req -> renderer.provideSurface(req) }
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

    private fun startRtsp(url: String) {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({ try { cpFuture.get().unbindAll() } catch (e: Exception) {} }, ContextCompat.getMainExecutor(this))
        glView.queueEvent {
            renderer.resetVideoTexture()
            val surface = renderer.getPlayerSurface()
            runOnUiThread {
                if (surface != null) {
                    if (exoPlayer == null) exoPlayer = ExoPlayer.Builder(this).build()
                    exoPlayer?.volume = 0f
                    exoPlayer?.stop()
                    exoPlayer?.clearVideoSurface()
                    exoPlayer?.setVideoSurface(surface)
                    val rtspSource = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(5000).createMediaSource(MediaItem.fromUri(url))
                    exoPlayer?.setMediaSource(rtspSource)
                    exoPlayer?.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) renderer.updateTextureSize(videoSize.width, videoSize.height)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Toast.makeText(this@MainActivity, "Stream Error: ${error.message}", Toast.LENGTH_LONG).show() }
                    })
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    isRtspMode = true
                    lastRtspUrl = url
                    Toast.makeText(this, "Connecting (TCP)...", Toast.LENGTH_SHORT).show()
                } else { Toast.makeText(this, "Renderer not ready", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun stopRtsp() {
        exoPlayer?.stop()
        exoPlayer?.clearVideoSurface()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Detach all UI from controls to prevent context leaks
        controls.forEach { it.detach() }

        exoPlayer?.release()
        exoPlayer = null
        displayHelper.stop()
    }


    private fun handleInteraction(event: MotionEvent) {
        if (event.pointerCount >= 2) {
            val p1x = event.getX(0); val p1y = event.getY(0)
            val p2x = event.getX(1); val p2y = event.getY(1)
            val focusX = (p1x + p2x) / 2f; val focusY = (p1y + p2y) / 2f
            val dist = hypot(p1x - p2x, p1y - p2y)
            val angle = Math.toDegrees(atan2((p1y - p2y).toDouble(), (p1x - p2x).toDouble())).toFloat()
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = (focusX - lastFingerFocusX) / glView.width.toFloat() * 2.0f
                val dy = (focusY - lastFingerFocusY) / glView.height.toFloat() * 2.0f
                controlsMap["M_TX"]?.let { it.setProgress((it.value - (dx * 500).toInt()).coerceIn(0, 1000)) }
                controlsMap["M_TY"]?.let { it.setProgress((it.value + (dy * 500).toInt()).coerceIn(0, 1000)) }
                val scaleFactor = dist / lastFingerDist
                if (scaleFactor > 0) {
                    controlsMap["M_ZOOM"]?.let { it.setProgress((it.value - (log2(scaleFactor) * 300).toInt()).coerceIn(0, 1000)) }
                }
                val dAngle = angle - lastFingerAngle
                controlsMap["M_ANGLE"]?.let { it.setProgress((it.value - (dAngle * (1000f / 360f)).toInt() + 1000) % 1000) }
            }
            lastFingerDist = dist; lastFingerAngle = angle; lastFingerFocusX = focusX; lastFingerFocusY = focusY
        } else if (event.action == MotionEvent.ACTION_UP) {
            if (PropertyControl.activeControl != null) {
                // Action 1: If a menu is open, close it.
                PropertyControl.closeActiveMenu()
            } else {
                // Action 2: If no menu is open, toggle the HUD.
                toggleHud()
            }
        }
    }

    private fun textToIcon(t: String, size: Float = 60f, color: Int = Color.WHITE): BitmapDrawable {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { this.color = color; textSize = size; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
        c.drawText(t, 80f, 80f + (size / 3f), p); return BitmapDrawable(resources, b)
    }

    private fun createClockDrawable(): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
        c.drawCircle(50f, 55f, 35f, p); c.drawLine(50f, 55f, 50f, 35f, p); c.drawLine(50f, 55f, 65f, 55f, p)
        c.drawLine(40f, 15f, 60f, 15f, p); c.drawLine(50f, 15f, 50f, 20f, p); return BitmapDrawable(resources, b)
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

        // FIX: Only create the container ONCE.
        // If it already exists, we just clean it (which is done in onConfigChanged) and re-add children.
        if (!::overlayHUD.isInitialized) {
            overlayHUD = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
            addContentView(overlayHUD, ViewGroup.LayoutParams(-1, -1))
        } else {
            // Safety measure: ensure it's clean if called from somewhere else
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

    private fun setupParameterMenu() {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val dm = resources.displayMetrics
        val menuHeight = (dm.heightPixels * 0.40).toInt()

        leftHUDContainer = LinearLayout(this).apply {
            if (isPortrait) {
                orientation = LinearLayout.VERTICAL
                // FIXED: Explicitly cap the container height so the background matches the ScrollView
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
                // Ensure the ScrollView doesn't request more space than its allotment
                layoutParams = LinearLayout.LayoutParams(-1, menuHeight)
            } else {
                layoutParams = LinearLayout.LayoutParams(850, -1)
            }

            id = View.generateViewId()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isVerticalScrollBarEnabled = true
            // CHANGE: Move scrollbar style to outside to prevent it from expanding the view
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    if (!isRebuildingHUD) lastScrollY = scrollY
                }
            }

            // Restore scroll position
            post {
                scrollTo(0, lastScrollY)
            }
        }

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Adjust bottom padding to ensure the last item isn't cut off by the rounded corners
            setPadding(25, 20, 10, if (isPortrait) 60 else 240)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
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

        fun addControl(c: PropertyControl) {
            if (controlsMap.containsKey(c.id)) {
                val existing = controlsMap[c.id]!!
                currentGroupContent?.let { existing.attachTo(this, it) } ?: existing.attachTo(this, menuLayout)
            } else {
                controls.add(c)
                controlsMap[c.id] = c
                currentGroupContent?.let { c.attachTo(this, it) } ?: c.attachTo(this, menuLayout)
            }
        }

        createGroup("GEOMETRY", startOpen = true)
        setupGeometrySpecifics(currentGroupContent!!)

        createGroup("3D")
        addControl(PropertyControl("3D_MIX", "STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("S_SHAPE", "SHAPE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("S_FOV", "FISHEYE", defaultValue = 500, outMin=0.2f, outMax=1.5f, hasModulation = true))
        addControl(PropertyControl("S_SPEED", "SPEED", defaultValue = 500, outMin=-2.0f, outMax=2.0f, hasModulation = true))
        addControl(PropertyControl("T_HUE_STR", "RAINBOW STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("T_HUE_POS", "RAINBOW POS", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("T_WAVE_STR", "WAVE STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("T_WAVE_POS", "WAVE POS", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))

        createGroup("MORPH (Careful)")
        addControl(PropertyControl("CURVE", "CURVE", defaultValue = 250, outMin=0.0f, outMax=4.0f, hasModulation = true))
        addControl(PropertyControl("TWIST", "VORTEX", defaultValue = 500, outMin=-5.0f, outMax=5.0f, hasModulation = true))
        addControl(PropertyControl("FLUX", "FLUX", defaultValue = 0, outMin=0f, outMax=0.5f, hasModulation = true))

        createGroup("MASTER TRANSFORM")
        addControl(PropertyControl("M_ANGLE", "ANGLE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("M_ZOOM", "ZOOM", defaultValue = 130, outMin=0.1f, outMax=4.0f, hasModulation = true))
        addControl(PropertyControl("M_TX", "MOVE X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("M_TY", "MOVE Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("M_TILTX", "TILT X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("M_TILTY", "TILT Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("M_RGB", "RGB SHIFT", defaultValue = 0, outMin=0f, outMax=0.1f, hasModulation = true))

        createGroup("CAMERA TRANSFORM")
        setupCameraOrientationControls(currentGroupContent!!)
        addControl(PropertyControl("C_ANGLE", "ANGLE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("WARP", "WARP DISTORT", defaultValue = 0, outMin=0f, outMax=1f))
        addControl(PropertyControl("C_ZOOM", "ZOOM", defaultValue = 320, outMin=0.3f, outMax=2.5f, hasModulation = true))
        addControl(PropertyControl("C_TX", "MOVE X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("C_TY", "MOVE Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("C_TILTX", "TILT X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("C_TILTY", "TILT Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("RGB", "RGB SHIFT", defaultValue = 0, outMin=0f, outMax=0.05f, hasModulation = true))

        createGroup("COLOR")
        addControl(PropertyControl("BRIT", "BRIGHTNESS", defaultValue = 500, outMin=0f, outMax=2f))
        addControl(PropertyControl("HUE", "HUE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("NEG", "NEGATIVE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("GLOW", "GLOW", defaultValue = 0, outMin=0f, outMax=2f, hasModulation = true))
        addControl(PropertyControl("CONTRAST", "CONTRAST", defaultValue = 500, outMin=0f, outMax=2f))
        addControl(PropertyControl("VIBRANCE", "SATURATION", defaultValue = 500, outMin=0f, outMax=2f))
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
                includeInPreset = false,
                hasModulation = false,
                logPower = 1,
                showValue = true
            ) {
                renderer.axisCount = it.toFloat()
            }
            controls.add(axisCtrl)
            controlsMap[axisId] = axisCtrl
        }
        axisCtrl.attachTo(this, parent)
    }

    private fun setupCameraOrientationControls(parent: LinearLayout) {
        val orientationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 10, 0, 20); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120) }
        fun createParamBtn(icon: BitmapDrawable, action: () -> Unit): ImageButton {
            return ImageButton(this).apply { setImageDrawable(icon); background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = 12f }; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(6, 0, 6, 0) }; setOnClickListener { action(); updateSidebarVisuals() } }
        }
        flipXBtn = createParamBtn(createCustomIcon(0)) { renderer.flipX = if (renderer.flipX == 1f) -1f else 1f }
        flipYBtn = createParamBtn(createCustomIcon(1)) { renderer.flipY = if (renderer.flipY == 1f) -1f else 1f }
        rot180Btn = createParamBtn(createCustomIcon(2)) { renderer.rot180 = !renderer.rot180 }
        orientationRow.addView(flipXBtn); orientationRow.addView(flipYBtn); orientationRow.addView(rot180Btn)
        parent.addView(orientationRow)
    }

    private fun createCameraSettingsPanel(): LinearLayout {
        cameraSettingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun createSideBtn(resId: Int, action: () -> Unit) = ImageButton(this).apply { setImageResource(resId); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.85f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { action(); updateSidebarVisuals() } }
        cameraSettingsPanel.addView(createSideBtn(android.R.drawable.ic_menu_camera) { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() })
        cameraSettingsPanel.addView(createSideBtn(android.R.drawable.ic_menu_gallery) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")) }
            mediaPickerLauncher.launch(intent)
        })
        cameraSettingsPanel.addView(createSideBtn(android.R.drawable.ic_menu_compass) { showRtspDialog() })
        return cameraSettingsPanel
    }

    private fun createRecordControls(): LinearLayout {
        recordControls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(10, 10, 10, 10) }
        photoBtn = ImageButton(this).apply { setImageDrawable(textToIcon("[ ]", 50f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.8f; scaleX = 1.5f; scaleY = 1.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() } }
        recordBtn = ImageButton(this).apply { setImageDrawable(textToIcon("REC", 40f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { toggleRecording() } }
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Transition Time Control
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
                valueFormatter = { "%.1fs".format(it / 1000f) }
            ) {
                transitionMs = it.toLong()
            }
            controls.add(transCtrl)
            controlsMap[transId] = transCtrl
        }

        val sliderWrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        transCtrl.attachTo(this, sliderWrapper)
        transContainer.addView(sliderWrapper)

        // --- Play Button ---
        playBtn = ImageButton(this).apply {
            setImageDrawable(createPlayIcon(isAutoPlaying))
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { leftMargin = 5 }
            setPadding(10, 10, 10, 10)
            setOnClickListener { toggleAutoPlay() }
        }
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

        (9 downTo 1).forEach { idx ->
            val pd = ProgressButtonDrawable(idx.toString())

            if (idx == activePreset) {
                pd.isActive = true
                val timePassed = System.currentTimeMillis() - transitionStartTime
                if (timePassed < transitionMs && transitionMs > 0) {
                    val startProgress = (timePassed.toFloat() / transitionMs).coerceIn(0f, 1f)
                    pd.setProgress(startProgress)
                    val anim = ValueAnimator.ofFloat(startProgress, 1f).apply {
                        duration = (transitionMs - timePassed).coerceAtLeast(0)
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { va ->
                            pd.setProgress(va.animatedValue as Float)
                            pd.invalidateSelf()
                        }
                        start()
                    }
                    presetAnimators[idx] = anim
                } else {
                    pd.setProgress(1f)
                }
            }

            presetDrawables[idx] = pd

            val b = TextView(this).apply {
                text = ""
                background = pd
                alpha = 1.0f
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(83, 110).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener { stopAutoPlay(); applyPreset(idx) }
                setOnLongClickListener {
                    pendingSaveIndex = idx
                    saveConfirmBtn.visibility = View.VISIBLE
                    saveConfirmBtn.text = "SAVE $idx?"
                    true
                }
            }
            btnRow.addView(b)
        }

        scroller.addView(btnRow)
        presetRow.addView(scroller)

        saveConfirmBtn = Button(this).apply {
            visibility = View.GONE
            setTextColor(Color.BLACK)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12f }
            layoutParams = FrameLayout.LayoutParams(280, 90, Gravity.CENTER)
            setOnClickListener { pendingSaveIndex?.let { savePreset(it) }; visibility = View.GONE }
        }
        presetRow.addView(saveConfirmBtn)

        presetPanel.addView(presetRow)

        return presetPanel
    }

    private fun toggleAutoPlay() {
        if (isAutoPlaying) {
            stopAutoPlay()
        } else {
            isAutoPlaying = true
            playBtn.setImageDrawable(createPlayIcon(true)) // White
            Toast.makeText(this, "Auto-Play Started", Toast.LENGTH_SHORT).show()
            triggerNextAutoPlay()
        }
    }

    private fun stopAutoPlay() {
        isAutoPlaying = false
        handler.removeCallbacks(autoPlayRunnable)
        playBtn.setImageDrawable(createPlayIcon(false)) // Grey
    }

    private fun triggerNextAutoPlay() {
        if (!isAutoPlaying) return

        // Calculate next index
        val nextIdx = if (autoPlayRandom) {
            (1..9).random()
        } else {
            if (activePreset >= 9 || activePreset < 1) 1 else activePreset + 1
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

    private fun createFlashView() = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; layoutParams = FrameLayout.LayoutParams(-1, -1) }
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

        val groupContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }; layoutTransition = LayoutTransition() }
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
    private fun createCustomIcon(type: Int): BitmapDrawable {
        val size = 100; val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
        val center = size / 2f
        when (type) {
            0 -> { paint.strokeWidth = 9f; val range = 20f; c.drawLine(center - range, center, center + range, center, paint); val p = Path().apply { moveTo(center - 8, center - 12); lineTo(center - range - 4, center); lineTo(center - 8, center + 12); moveTo(center + 8, center - 12); lineTo(center + range + 4, center); lineTo(center + 8, center + 12) }; c.drawPath(p, paint) }
            1 -> { paint.strokeWidth = 9f; val range = 20f; c.drawLine(center, center - range, center, center + range, paint); val p = Path().apply { moveTo(center - 12, center - 8); lineTo(center, center - range - 4); lineTo(center + 12, center - 8); moveTo(center - 12, center + 8); lineTo(center, center + range + 4); lineTo(center + 12, center + 8) }; c.drawPath(p, paint) }
            2 -> { paint.strokeWidth = 9f; val r = 24f; val box = RectF(center - r, center - r, center + r, center + r); c.drawArc(box, 180f + 20f, 140f, false, paint); c.drawArc(box, 0f + 20f, 140f, false, paint); val p = Path(); val endX1 = center + (r * cos(Math.toRadians(340.0))).toFloat(); val endY1 = center + (r * sin(Math.toRadians(340.0))).toFloat(); p.moveTo(endX1 - 5f, endY1 - 15f); p.lineTo(endX1, endY1); p.lineTo(endX1 - 18f, endY1 - 2f); val endX2 = center + (r * cos(Math.toRadians(160.0))).toFloat(); val endY2 = center + (r * sin(Math.toRadians(160.0))).toFloat(); p.moveTo(endX2 + 5f, endY2 + 15f); p.lineTo(endX2, endY2); p.lineTo(endX2 + 18f, endY2 + 2f); paint.style = Paint.Style.STROKE; c.drawPath(p, paint) }
        }
        return BitmapDrawable(resources, b)
    }

    private fun showRtspDialog() {
        val prefs = getSharedPreferences("SpaceBeam_RTSP", Context.MODE_PRIVATE)
        val historyKey = "RTSP_HISTORY"
        val rawSet = prefs.getStringSet(historyKey, null)
        val historyList = rawSet?.toMutableList() ?: mutableListOf()
        if (historyList.isEmpty()) historyList.add("rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(40, 20, 40, 0) }
        val input = AutoCompleteTextView(this).apply { setText(lastRtspUrl); setTextColor(Color.BLACK); textSize = 16f; setPadding(20, 30, 20, 30); threshold = 1; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, historyList)) }
        val arrowBtn = ImageButton(this).apply { setImageResource(android.R.drawable.arrow_down_float); setBackgroundColor(Color.LTGRAY); alpha = 0.7f; scaleType = ImageView.ScaleType.CENTER_INSIDE; layoutParams = LinearLayout.LayoutParams(120, 100).apply { leftMargin = 10 }; setOnClickListener { val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; imm.hideSoftInputFromWindow(input.windowToken, 0); input.showDropDown() } }
        row.addView(input); row.addView(arrowBtn)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Enter RTSP/Video URL").setView(row).setPositiveButton("Load", null).setNegativeButton("Cancel", null).create()
        fun performLoad() {
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                if (historyList.contains(url)) historyList.remove(url)
                historyList.add(0, url)
                while (historyList.size > 20) historyList.removeAt(historyList.lastIndex)
                prefs.edit().putStringSet(historyKey, historyList.toHashSet()).apply()
                startRtsp(url)
                dialog.dismiss()
            }
        }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { performLoad(); true } else false }
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        dialog.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { performLoad() }
    }

    private fun applyReadabilityStyle() {
        val getBg = { alpha: Int -> GradientDrawable().apply {
            setColor(Color.argb(alpha, 10, 10, 10))
            setStroke(2, Color.argb(120, 80, 80, 80))
            cornerRadius = 25f
        } }
        val getCircleBg = { alpha: Int -> getBg(alpha).apply { shape = GradientDrawable.OVAL } }

        val panels = listOf(cameraSettingsPanel, presetPanel, recordControls)
        // Ensure orientationBtn is included here to share the circle background style
        val utils = listOf(menuBtn, orientationBtn, settingsBtn)

        panels.forEach { it.background = null; it.setPadding(15, 15, 15, 15); it.clipToOutline = true }
        parameterPanel.background = null

        when (readabilityLevel) {
            1, 2 -> {
                val alpha = if (readabilityLevel == 1) 180 else 120
                panels.forEach { it.background = getBg(alpha) }
                parameterPanel.background = getBg(alpha)
                parameterPanel.clipToOutline = true

                // This applies the shared circle background to toggle button and settings
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
                    recordBtn.setImageDrawable(textToIcon("%d:%02d".format(m, s), 38f, Color.RED)); handler.postDelayed(this, 1000)
                }
            }; handler.post(recordTicker!!)
        } else {
            renderer.stopRecording { savedFile ->
                isRecording = false; recordTicker?.let { handler.removeCallbacks(it) }
                runOnUiThread {
                    recordBtn.setImageDrawable(textToIcon("REC", 40f)); recordBtn.alpha = 0.5f; if (savedFile != null && savedFile.exists()) saveVideoToGallery(savedFile)
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
        renderer.scrollAccum = 0.0f
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
        flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f; flipYBtn.alpha = if (renderer.flipY > 0f) 1.0f else 0.3f; rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f
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
                interpolator = android.view.animation.LinearInterpolator() // Matches time flow
                addUpdateListener { va ->
                    val progress = va.animatedValue as Float
                    btnDrawable.setProgress(progress)
                    btnDrawable.invalidateSelf()
                }
                start()
            }
            presetAnimators[idx] = anim
        }

        // 3. GL & Control Animations
        if (!axisLocked) {
            renderer.axisCount = p.axis.toFloat()
            axisSb.progress = p.axis - 1
            controlsMap["AXIS"]?.setProgress(p.axis - 1)
        }

        val startMRot = renderer.mRotAccum
        val startCRot = renderer.cRotAccum
        val targetMRot = round(startMRot / 360.0) * 360.0
        val targetCRot = round(startCRot / 360.0) * 360.0

        renderer.animateRotationTo(targetMRot, targetCRot, durationSec)

        controls.forEach { control ->
            // FIX: Explicitly skip controls that should not be part of the preset (like TRANS_TIME)
            if (control.id == "AXIS" || !control.includeInPreset) return@forEach

            val snap = p.controlSnapshots[control.id]
            if (snap != null) {
                control.animateTo(snap.value.toFloat(), durationSec, snap.shape)
                if (control.hasModulation) {
                    control.animateModulation(snap.rate.toFloat(), snap.depth.toFloat(), durationSec)
                }
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
        // FIX: Added filter && it.includeInPreset to ensure Transition Time isn't saved
        val snapshots = controls.filter { it.id != "AXIS" && it.includeInPreset }.associate { it.id to it.getSnapshot() }

        val axisVal = controlsMap["AXIS"]?.value ?: 0
        val newPreset = Preset(snapshots, renderer.flipX, renderer.flipY, renderer.rot180, axisVal + 1)
        presets[idx] = newPreset
        activePreset = idx
        updatePresetHighlights()
        try {
            val rootObj = JSONObject()
            rootObj.put("axis", newPreset.axis)
            rootObj.put("flipX", newPreset.flipX.toDouble())
            rootObj.put("flipY", newPreset.flipY.toDouble())
            rootObj.put("rot180", newPreset.rot180)
            val controlsObj = JSONObject()
            newPreset.controlSnapshots.forEach { (key, snap) ->
                val snapObj = JSONObject()
                snapObj.put("v", snap.value); snapObj.put("r", snap.rate); snapObj.put("d", snap.depth); snapObj.put("shape", snap.shape)
                controlsObj.put(key, snapObj)
            }
            rootObj.put("controls", controlsObj)
            getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE).edit().putString("PRESET_$idx", rootObj.toString()).apply()
            Toast.makeText(this, "Preset $idx Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show() }
    }

    // Add this helper function inside MainActivity
    private fun dumpPresetToLog(source: String) {
        val debugObj = JSONObject()
        try {
            debugObj.put("source", source)
            debugObj.put("axis", renderer.axisCount.toInt())
            debugObj.put("flipX", renderer.flipX.toDouble())
            debugObj.put("flipY", renderer.flipY.toDouble())
            debugObj.put("rot180", renderer.rot180)

            val controlsObj = JSONObject()
            controls.forEach { c ->
                val snap = JSONObject()
                snap.put("v", c.value)
                if (c.hasModulation) {
                    snap.put("r", c.modRate)
                    snap.put("d", c.modDepth)
                    snap.put("shape", c.modShape.name)
                }
                controlsObj.put(c.id, snap)
            }
            debugObj.put("controls", controlsObj)

            // Single line, no formatting, red text for visibility
            Log.e("PRESET_DUMP", debugObj.toString())

        } catch (e: Exception) {
            Log.e("PRESET_DUMP", "Failed to log preset", e)
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
        // Robust Helper Function
        fun p(ax: Int, vararg overrides: Any): Preset {
            val baseSnapshots = controls.associate { it.id to it.getSnapshot() }.toMutableMap()

            // Default M_ANGLE to 0 if not specified
            if (!overrides.contains("M_ANGLE")) {
                baseSnapshots["M_ANGLE"] = PropertyControl.Snapshot(0, false, 0, 0, "SINE")
            }

            var i = 0
            while (i < overrides.size) {
                val key = overrides[i] as String
                val value = overrides[i + 1] as Int

                // Check for Modulation (Rate/Depth)
                if (i + 3 < overrides.size && overrides[i + 2] is Int && overrides[i + 3] is Int) {
                    val rate = overrides[i + 2] as Int
                    val depth = overrides[i + 3] as Int

                    var shape = "SINE"
                    var step = 4

                    // Check for Shape String safely
                    if (i + 4 < overrides.size && overrides[i + 4] is String) {
                        val potentialShape = overrides[i + 4] as String
                        // Verify it is a valid WaveShape enum
                        val isValid = try {
                            PropertyControl.WaveShape.valueOf(potentialShape)
                            true
                        } catch (e: Exception) {
                            false
                        }

                        if (isValid) {
                            shape = potentialShape
                            step = 5
                        }
                    }
                    baseSnapshots[key] = PropertyControl.Snapshot(value, true, rate, depth, shape)
                    i += step
                } else {
                    // Static value
                    baseSnapshots[key] = PropertyControl.Snapshot(value, false, 0, 0, "SINE")
                    i += 2
                }
            }
            return Preset(baseSnapshots, 1f, -1f, false, ax)
        }

        // --- PRESET DEFINITIONS (From Logs) ---

        presets[1] = p(2,
            "M_ZOOM", 130,
            "M_TX", 500,
            "M_TY", 500,
            "C_ZOOM", 320,
            "BRIT", 500, "CONTRAST", 500, "VIBRANCE", 500
        )

        presets[2] = p(2,
            "M_ZOOM", 49,
            "M_TX", 688, "M_TY", 609,
            "C_ZOOM", 320,
            "BRIT", 500, "CONTRAST", 500, "VIBRANCE", 500
        )

        presets[3] = p(2,
            "M_ANGLE", 0, 169, 1000, "RAMP",
            "M_ZOOM", 168,
            "M_TX", 500, 480, 378,
            "M_TY", 546, 340, 698,
            "M_TILTX", 500, 268, 788,
            "M_TILTY", 500, 241, 732,
            "C_ZOOM", 320,
            "BRIT", 500, "CONTRAST", 500, "VIBRANCE", 500
        )

        presets[4] = p(2,
            "M_ANGLE", 172, 262, 287,
            "M_ZOOM", 160, 531, 316,
            "M_TX", 500, 235, 184,
            "M_TY", 500, 217, 218,
            "M_TILTX", 500, 242, 305,
            "M_TILTY", 500, 318, 343,
            "C_ZOOM", 500, 583, 365,
            "HUE", 184, 298, 505, "RAMP",
            "GLOW", 172,
            "CONTRAST", 718, "VIBRANCE", 899
        )

        presets[5] = p(2,
            "M_ANGLE", 172, 262, 287,
            "M_ZOOM", 518, 531, 576,
            "M_TX", 500, 431, 525,
            "M_TY", 500, 217, 644, "RANDOM_SMOOTH",
            "M_TILTX", 500, 498, 1000,
            "M_TILTY", 500, 318, 1000,
            "C_ZOOM", 500, 583, 365,
            "GLOW", 485,
            "CONTRAST", 788, "VIBRANCE", 899
        )

        presets[6] = p(2,
            "3D_MIX", 1000,
            "S_FOV", 884,
            "M_ANGLE", 172, 262, 287,
            "M_ZOOM", 130, 200, 0,
            "M_TX", 500, 431, 40,
            "M_TY", 500, 217, 34,
            "M_TILTX", 500, 498, 303,
            "M_TILTY", 500, 318, 345, "RANDOM_SMOOTH",
            "C_ZOOM", 500, 583, 365,
            "GLOW", 178,
            "CONTRAST", 522, "VIBRANCE", 853
        )

        presets[7] = p(2,
            "3D_MIX", 1000,
            "S_SHAPE", 0, 343, 0,
            "S_SPEED", 206,
            "S_FOV", 481,
            "T_WAVE_STR", 454,
            "T_WAVE_POS", 20, 375, 1000, "RAMP",
            "M_ANGLE", 870,
            "M_ZOOM", 77,
            "M_TX", 500, 320, 328,
            "M_TY", 500, 323, 343,
            "M_TILTY", 500, 318, 0,
            "C_ZOOM", 500, 583, 0,
            "GLOW", 178,
            "CONTRAST", 522, "VIBRANCE", 853
        )

        presets[8] = p(2,
            "3D_MIX", 1000,
            "S_SHAPE", 1000, 343, 785,
            "S_FOV", 481, 496, 704,
            "S_SPEED", 1000,
            "M_ANGLE", 172, 262, 287,
            "M_ZOOM", 160,
            "M_TX", 500, 431, 40,
            "M_TY", 500, 217, 34,
            "M_TILTX", 500, 498, 303,
            "M_TILTY", 500, 318, 469,
            "C_ZOOM", 500, 583, 365,
            "RGB", 490, 534, 634,
            "GLOW", 285,
            "CONTRAST", 786, "VIBRANCE", 828
        )

        presets[9] = p(6, // High Axis for complex geometry
            "3D_MIX", 1000,          // Fully 3D
            "S_FOV", 600,            // Moderate FOV
            "S_SPEED", 150,          // Slow, graceful forward movement

            // "Breathing" Geometry: Modulate Shape between Circle and Square slowly
            "S_SHAPE", 0, 150, 600, "SINE",

            // Subtle Zoom breathing synced roughly with shape
            "M_ZOOM", 120, 150, 200, "SINE",

            // Slow, constant rotation to keep it dynamic but not dizzying
            "M_ANGLE", 0, 60, 1000, "RAMP",

            // Deep Color Cycle
            "HUE", 0, 80, 1000, "RAMP",
            "VIBRANCE", 600,
            "GLOW", 600,             // High glow for "Neon" look
            "RGB", 150,              // Slight chromatic aberration for "Wild" edge

            // Center the view
            "M_TX", 500, "M_TY", 500,
            "M_TILTX", 500, "M_TILTY", 500
        )

        // Load saved overrides from SharedPreferences
        val prefs = getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE)
        // UPDATED LOOP LIMIT TO 9
        for (i in 1..9) {
            val jsonStr = prefs.getString("PRESET_$i", null)
            if (jsonStr != null) {
                // ... (Keep existing loading logic) ...
                try {
                    val rootObj = JSONObject(jsonStr)
                    // ... (rest of parsing logic) ...
                    val loadedAxis = rootObj.getInt("axis")
                    val loadedFlipX = rootObj.getDouble("flipX").toFloat()
                    val loadedFlipY = rootObj.getDouble("flipY").toFloat()
                    val loadedRot180 = rootObj.getBoolean("rot180")
                    val controlsObj = rootObj.getJSONObject("controls")

                    val loadedSnapshots = mutableMapOf<String, PropertyControl.Snapshot>()
                    loadedSnapshots.putAll(presets[i]?.controlSnapshots ?: emptyMap())

                    val keysIterator = controlsObj.keys()
                    while (keysIterator.hasNext()) {
                        val key = keysIterator.next()
                        val snapObj = controlsObj.getJSONObject(key)
                        loadedSnapshots[key] = PropertyControl.Snapshot(
                            snapObj.getInt("v"),
                            snapObj.optBoolean("active", false),
                            snapObj.optInt("r", 0),
                            snapObj.optInt("d", 0),
                            snapObj.optString("shape", "SINE")
                        )
                    }
                    presets[i] = Preset(loadedSnapshots, loadedFlipX, loadedFlipY, loadedRot180, loadedAxis)
                } catch (e: Exception) { Log.e("PRESET", "Error loading preset $i", e) }
            }
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
        private var kaleidoProgram = 0
        private var simpleProgram = 0
        @Volatile private var isSurfaceReady = false
        private val mvpMatrix = FloatArray(16)
        private val identityMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

        // State
        var scrollAccum = 0.0f
        var mRotAccum = 0.0
        var cRotAccum = 0.0
        var lRotAccum = 0.0
        var axisCount = 2.0f
        var flipX = 1.0f
        var flipY = -1.0f
        var rot180 = false

        // Resolution Tracking
        private var texWidth = 1280
        private var texHeight = 720

        // Timing
        private var lastTime = System.nanoTime()
        private var deltaTime = 0.0f

        // GL Surfaces & Textures
        private var cameraTexId = -1
        private var surfaceTexture: SurfaceTexture? = null
        private var playerSurface: Surface? = null
        private var fboId = 0
        private var fboTexId = 0
        private var fboWidth = 1920
        private var fboHeight = 1080

        // Recorder / External
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
        private var extWidth = 0
        private var extHeight = 0

        // Buffers & Uniforms
        private lateinit var pBuf: FloatBuffer
        private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()
        private var simpleULocs = mutableMapOf<String, Int>()
        private var viewWidth = 1
        private var viewHeight = 1
        private val FIXED_WIDTH = 1920
        private val FIXED_HEIGHT = 1080

        // --- Rotation Animation State ---
        private var rotTargetM: Double? = null
        private var rotStartM: Double = 0.0
        private var rotTargetC: Double? = null
        private var rotStartC: Double = 0.0
        private var rotAnimDuration: Float = 0f
        private var rotAnimTime: Float = 0f
        private var isRotAnimating = false

        fun animateRotationTo(targetM: Double, targetC: Double, duration: Float) {
            rotTargetM = targetM
            rotStartM = mRotAccum
            rotTargetC = targetC
            rotStartC = cRotAccum
            rotAnimDuration = duration
            rotAnimTime = 0f
            isRotAnimating = true
        }

        fun stopRotationAnim() { isRotAnimating = false }

        fun resetPhases() { ctx.controls.forEach { it.lfoPhase = 0.0 }; mRotAccum = 0.0; cRotAccum = 0.0; lRotAccum = 0.0 }
        fun capturePhoto() { captureRequested = true }
        fun stopRecording(callback: (File?) -> Unit) { onStopCallback = callback; isStopRequested = true }
        fun getPlayerSurface(): Surface? { if (surfaceTexture == null) return null; if (playerSurface == null) { playerSurface = Surface(surfaceTexture) }; return playerSurface }
        fun startRecording(file: File) { pendingRecordFile = file; recordStartTimeNs = 0 }
        fun resetVideoTexture() {
            playerSurface?.release(); playerSurface = null; surfaceTexture?.release(); surfaceTexture = null
            if (cameraTexId != -1) { val t = IntArray(1); t[0] = cameraTexId; GLES20.glDeleteTextures(1, t, 0); cameraTexId = -1 }
            cameraTexId = createOESTex(); surfaceTexture = SurfaceTexture(cameraTexId); surfaceTexture?.setDefaultBufferSize(texWidth, texHeight)
        }
        fun provideSurface(req: SurfaceRequest) {
            glView.queueEvent {
                surfaceTexture?.let { st ->
                    texWidth = req.resolution.width
                    texHeight = req.resolution.height
                    st.setDefaultBufferSize(texWidth, texHeight)
                    val s = Surface(st)
                    req.provideSurface(s, ContextCompat.getMainExecutor(ctx)) { s.release() }
                }
            }
        }
        fun setExternalSurface(s: Surface, w: Int, h: Int) { extSurfaceArgs = Triple(s, w, h) }
        fun removeExternalSurface() { extSurfaceArgs = null }

        fun updateTextureSize(width: Int, height: Int) {
            texWidth = width
            texHeight = height
            glView.queueEvent { surfaceTexture?.setDefaultBufferSize(width, height) }
        }

        override fun onSurfaceCreated(gl: GL10?, config: GL10EGLConfig?) {
            setupEGL(); GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"

            // --- SHADER (Includes Aspect Fill for Camera->FBO) ---
            val fSrc = """#extension GL_OES_EGL_image_external : require
            precision highp float; varying vec2 v; uniform samplerExternalOES uTex;
            uniform float uMR, uCR, uCZ, uA, uMZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB, uMRGB, uWarp;
            uniform float uBrit, uTHueStr, uTHuePos, uTWaveStr, uTWavePos;
            uniform vec2 uMT, uCT, uF, uMTilt, uCTilt;
            uniform float uTexAspect; 
            uniform float uCurve, uTwist, uFlux, uSShape, uSFov, uScroll, uMode;

            vec3 hueShift(vec3 color, float hue) { const vec3 k = vec3(0.57735, 0.57735, 0.57735); float cosAngle = cos(hue); return vec3(color * cosAngle + cross(k, color) * sin(hue) + k * dot(k, color) * (1.0 - cosAngle)); }

            vec3 sampleCamera(vec2 uv, float rgbShift) {
                vec2 centered = uv - 0.5;
                float z = 1.0 + (centered.x * uCTilt.x) + (centered.y * uCTilt.y); centered /= max(z, 0.1);
                centered *= uCZ;

                // Aspect Fill: Camera to FBO
                if (uA > uTexAspect) { centered.y *= uTexAspect / uA; } 
                else { centered.x *= uA / uTexAspect; }

                float aspectFactor = mix(uA, 1.0, uWarp); centered.x *= aspectFactor;
                float cr = uCR * 0.01745329; float c = cos(cr); float s = sin(cr);
                centered = vec2(centered.x * c - centered.y * s, centered.x * s + centered.y * c);
                centered.x /= aspectFactor; centered += uCT;
                vec2 rotatedUV = centered + 0.5; rotatedUV.x += rgbShift; rotatedUV = (rotatedUV - 0.5) * uF + 0.5;

                vec2 mirroredUV = abs(mod(rotatedUV + 1.0, 2.0) - 1.0);
                return texture2D(uTex, mirroredUV).rgb;
            }

            void main() {
                vec3 finalColor = vec3(0.0);
                float a1 = -uMR * 0.01745329; float cosA1 = cos(a1); float sinA1 = sin(a1);
                float modeBlend = smoothstep(0.0, 1.0, uMode);
                vec2 effectiveTilt = mix(uMTilt, vec2(0.0), modeBlend);
                vec2 effectiveTrans = uMT + mix(vec2(0.0), uMTilt * 2.0, modeBlend);
                for(int i=0; i<3; i++) {
                    float mOff = (i==0) ? uMRGB : (i==2) ? -uMRGB : 0.0;
                    vec2 uv = v - 0.5;
                    float zM = 1.0 + (uv.x * effectiveTilt.x) + (uv.y * effectiveTilt.y); uv /= max(zM, 0.1); uv.x *= uA; uv.x += mOff;
                    uv = (uv + effectiveTrans) * uMZ * 4.0;
                    uv = vec2(uv.x * cosA1 - uv.y * sinA1, uv.x * sinA1 + uv.y * cosA1);
                    if(uAx > 1.1) {
                        float r = length(uv); float slice = 6.2831853 / uAx; float angle = atan(uv.y, uv.x);
                        float a = mod(angle, slice); if(mod(uAx, 2.0) < 0.1) a = abs(a - slice * 0.5);
                        uv = vec2(cos(a), sin(a)) * r;
                    }
                    float rCircle = length(uv); float rBox = max(abs(uv.x), abs(uv.y)); float dist = mix(rCircle, rBox, uSShape);
                    float angle = atan(uv.y, uv.x); dist += sin(angle * 4.0 + dist * 10.0) * uFlux * dist; float safeDist = max(dist, 0.01);
                    float projection = (uSFov * 0.8 + 0.2) / safeDist;
                    vec2 tunnelUV; tunnelUV.x = (angle + (1.0/safeDist) * uTwist) / 3.14159; tunnelUV.y = projection;
                    if(abs(uCurve - 1.0) > 0.01) tunnelUV *= 1.0 + (uCurve - 1.0) * (1.0 - safeDist);
                    vec2 flatUV = uv; flatUV.x /= uA;
                    vec2 mixedUV = mix(flatUV, tunnelUV * 0.8, modeBlend);
                    mixedUV.y += uScroll;
                    vec2 cameraUV = abs(mod(mixedUV + 1.0, 2.0) - 1.0);
                    float sOff = (i==0) ? uRGB : (i==2) ? -uRGB : 0.0;
                    vec3 smp = sampleCamera(cameraUV, sOff);
                    if (uMode > 0.01) {
                        if (uTHueStr > 0.01) { float hueArg = (mixedUV.y * 0.5) + uTHuePos; vec3 rainbow = 0.5 + 0.5 * cos(6.28318 * (hueArg + vec3(0.0, 0.33, 0.67))); smp = mix(smp, smp * rainbow * 2.0, uTHueStr * uMode); }
                        if (uTWaveStr > 0.01) { float waveDomain = mixedUV.y - (uTWavePos * 10.0); float distFromWave = abs(fract(waveDomain) - 0.5); float width = 0.15 + (uTWaveStr * 0.2); float wavePulse = smoothstep(width, 0.0, distFromWave); wavePulse = wavePulse * wavePulse; float intensity = (uTWaveStr * uTWaveStr) * 0.8; vec3 waveColor = vec3(0.5, 0.8, 1.0) * wavePulse * intensity; smp += waveColor; }
                    }
                    if(i==0) finalColor.r = smp.r; else if(i==1) finalColor.g = smp.g; else finalColor.b = smp.b;
                }
                finalColor = abs(finalColor - uSol);
                if(uHue > 0.01) finalColor = hueShift(finalColor, uHue * 6.28318);
                finalColor = (finalColor - 0.5) * uC + 0.5;
                float l = dot(finalColor, vec3(0.299, 0.587, 0.114)); finalColor = mix(vec3(l), finalColor, uS);
                if(uBloom > 0.01) finalColor += smoothstep(0.4, 1.0, l) * finalColor * uBloom * 2.0;
                finalColor *= uBrit;
                gl_FragColor = vec4(finalColor, 1.0);
            }""".trimIndent()

            kaleidoProgram = createProgram(vSrc, fSrc)
            val activeUniforms = IntArray(1); GLES20.glGetProgramiv(kaleidoProgram, GLES20.GL_ACTIVE_UNIFORMS, activeUniforms, 0)
            val lenBuf = IntArray(1); val sizeBuf = IntArray(1); val typeBuf = IntArray(1); val nameBuf = ByteArray(256)
            for (i in 0 until activeUniforms[0]) {
                GLES20.glGetActiveUniform(kaleidoProgram, i, 256, lenBuf, 0, sizeBuf, 0, typeBuf, 0, nameBuf, 0)
                val name = String(nameBuf, 0, lenBuf[0]); val loc = GLES20.glGetUniformLocation(kaleidoProgram, name)
                if (loc != -1) uLocs[name] = loc
            }

            val vSrcSimple = "attribute vec4 p; attribute vec2 t; varying vec2 v; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * p; v = t; }"
            val fSrcSimple = "precision mediump float; varying vec2 v; uniform sampler2D uTex; void main() { gl_FragColor = texture2D(uTex, v); }"
            simpleProgram = createProgram(vSrcSimple, fSrcSimple)
            if (simpleProgram != 0) {
                simpleULocs["uTex"] = GLES20.glGetUniformLocation(simpleProgram, "uTex")
                simpleULocs["uMVPMatrix"] = GLES20.glGetUniformLocation(simpleProgram, "uMVPMatrix")
            }

            cameraTexId = createOESTex()
            surfaceTexture = SurfaceTexture(cameraTexId)
            surfaceTexture?.setDefaultBufferSize(texWidth, texHeight)
            initFBO(FIXED_WIDTH, FIXED_HEIGHT)
            GLES20.glUseProgram(kaleidoProgram)
            uLocs["uA"]?.let { GLES20.glUniform1f(it, FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat()) }
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0) }
            ctx.runOnUiThread { ctx.startCamera() }
        }

        private fun initFBO(w: Int, h: Int) {
            if (fboId != 0) { val fb = IntArray(1) { fboId }; val tx = IntArray(1) { fboTexId }; GLES20.glDeleteFramebuffers(1, fb, 0); GLES20.glDeleteTextures(1, tx, 0) }
            fboWidth = w; fboHeight = h; val fb = IntArray(1); val tx = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); GLES20.glGenTextures(1, tx, 0)
            fboId = fb[0]; fboTexId = tx[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { if (w == 0 || h == 0) return; viewWidth = w; viewHeight = h; isSurfaceReady = true }

        override fun onDrawFrame(gl: GL10?) {
            if (!isSurfaceReady) return
            val now = System.nanoTime()
            deltaTime = (now - lastTime) / 1e9f
            lastTime = now

            if (isRotAnimating && rotTargetM != null) {
                rotAnimTime += deltaTime
                if (rotAnimTime >= rotAnimDuration) {
                    mRotAccum = rotTargetM!!
                    cRotAccum = rotTargetC!!
                    isRotAnimating = false
                } else {
                    val t = (rotAnimTime / rotAnimDuration).coerceIn(0f, 1f)
                    val ease = 1f - (1f - t).pow(3f)
                    mRotAccum = rotStartM + (rotTargetM!! - rotStartM) * ease
                    cRotAccum = rotStartC + (rotTargetC!! - rotStartC) * ease
                }
            }

            ctx.controls.forEach { it.update(deltaTime) }

            try { surfaceTexture?.updateTexImage() } catch (e: Exception) { return }
            manageSurfaces()
            updateMovementPhysics(deltaTime)
            renderToFBO()
            renderToScreen()
            renderToExternal()
            renderToRecorder()
            handleCapture()
        }

        private fun updateMovementPhysics(d: Float) {
            // 1. Handle Scroll (Tunnel) Physics
            val speedCtrl = ctx.controlsMap["S_SPEED"]
            if (speedCtrl != null) {
                // Get the raw speed (-2.0 to 2.0)
                val rawSpeed = speedCtrl.computedValue

                // Standard movement
                // We use a power curve for the speed to make low speeds more precise
                val sign = sign(rawSpeed)
                val curvedSpeed = sign * (abs(rawSpeed)).pow(2.2f)

                scrollAccum += curvedSpeed * d * 0.6f

                // --- THE SOFT LANDING FIX ---
                // If speed is very low (effectively stopped), gently pull towards the nearest integer
                if (abs(rawSpeed) < 0.05f) {
                    val nearestCenter = round(scrollAccum)
                    val distToCenter = nearestCenter - scrollAccum

                    // The factor '3.0f' determines how fast it snaps to center.
                    // using deltaTime ensures it is smooth regardless of frame rate.
                    scrollAccum += distToCenter * d * 3.0f
                }

                // Keep the accumulator within reasonable bounds to prevent float precision issues
                // (Assuming texture repeats every 1.0 or 2.0 units)
                if (abs(scrollAccum) > 1000.0f) scrollAccum %= 2.0f
            }

        }

        private fun renderToFBO() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId); GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(kaleidoProgram)
            fun safeUni(name: String, v: Float) { uLocs[name]?.let { GLES20.glUniform1f(it, v) } }
            fun safeUni2(name: String, v1: Float, v2: Float) { uLocs[name]?.let { GLES20.glUniform2f(it, v1, v2) } }

            val widthF = FIXED_WIDTH.toFloat()
            val heightF = FIXED_HEIGHT.toFloat()
            val fboAspect = widthF / heightF

            val texAspect = if (texHeight > 0) texWidth.toFloat() / texHeight.toFloat() else 1.0f
            safeUni("uTexAspect", texAspect)
            safeUni("uA", fboAspect)

            // GET COMPUTED VALUES DIRECTLY (Already Scaled by PropertyControl)
            val vMAngle = ctx.controlsMap["M_ANGLE"]?.computedValue ?: 0f
            val vMZoom = ctx.controlsMap["M_ZOOM"]?.computedValue ?: 1f
            val vMTx = ctx.controlsMap["M_TX"]?.computedValue ?: 0f
            val vMTy = ctx.controlsMap["M_TY"]?.computedValue ?: 0f
            val vMTiltX = ctx.controlsMap["M_TILTX"]?.computedValue ?: 0f
            val vMTiltY = ctx.controlsMap["M_TILTY"]?.computedValue ?: 0f
            val v3DMix = ctx.controlsMap["3D_MIX"]?.computedValue ?: 0f

            // Note: vMAngle is 0.0-1.0. We multiply by 360 here plus the accumulated physics rotation.
            safeUni("uAx", axisCount)
            safeUni("uMR", (vMAngle * 360f + mRotAccum).toFloat() + 90f)
            safeUni("uMZ", vMZoom)
            safeUni2("uMT", vMTx * 2f, vMTy * 2f)
            safeUni2("uMTilt", vMTiltX * 1.5f, vMTiltY * 1.5f)

            safeUni("uMode", v3DMix.pow(2.0f))
            safeUni("uScroll", scrollAccum)
            safeUni("uSShape", ctx.controlsMap["S_SHAPE"]?.computedValue ?: 0f)
            safeUni("uSFov", ctx.controlsMap["S_FOV"]?.computedValue ?: 0.5f)
            safeUni("uTHueStr", ctx.controlsMap["T_HUE_STR"]?.computedValue ?: 0f)
            safeUni("uTHuePos", ctx.controlsMap["T_HUE_POS"]?.computedValue ?: 0f)
            safeUni("uTWaveStr", ctx.controlsMap["T_WAVE_STR"]?.computedValue ?: 0f)
            safeUni("uTWavePos", ctx.controlsMap["T_WAVE_POS"]?.computedValue ?: 0f)

            safeUni("uCurve", ctx.controlsMap["CURVE"]?.computedValue ?: 1.0f)
            safeUni("uTwist", ctx.controlsMap["TWIST"]?.computedValue ?: 0f)
            safeUni("uFlux", ctx.controlsMap["FLUX"]?.computedValue ?: 0f)

            val vCZoom = ctx.controlsMap["C_ZOOM"]?.computedValue ?: 1f
            val vCAngle = ctx.controlsMap["C_ANGLE"]?.computedValue ?: 0f
            val vCTx = ctx.controlsMap["C_TX"]?.computedValue ?: 0f
            val vCTy = ctx.controlsMap["C_TY"]?.computedValue ?: 0f
            val vCTiltX = ctx.controlsMap["C_TILTX"]?.computedValue ?: 0f
            val vCTiltY = ctx.controlsMap["C_TILTY"]?.computedValue ?: 0f

            safeUni("uCZ", vCZoom)
            safeUni("uCR", (vCAngle * 360f + cRotAccum).toFloat())
            safeUni2("uCT", vCTx, vCTy)
            safeUni2("uCTilt", vCTiltX * 1.2f, vCTiltY * 1.2f)

            safeUni2("uF", if (rot180) -flipX else flipX, if (rot180) -flipY else flipY)
            safeUni("uWarp", ctx.controlsMap["WARP"]?.computedValue ?: 0f)

            safeUni("uC", ctx.controlsMap["CONTRAST"]?.computedValue ?: 1f)
            safeUni("uS", ctx.controlsMap["VIBRANCE"]?.computedValue ?: 1f)
            safeUni("uHue", ctx.controlsMap["HUE"]?.computedValue ?: 0f)
            safeUni("uSol", ctx.controlsMap["NEG"]?.computedValue ?: 0f)
            safeUni("uBloom", ctx.controlsMap["GLOW"]?.computedValue ?: 0f)
            safeUni("uRGB", ctx.controlsMap["RGB"]?.computedValue ?: 0f)
            safeUni("uMRGB", ctx.controlsMap["M_RGB"]?.computedValue ?: 0f)
            safeUni("uBrit", ctx.controlsMap["BRIT"]?.computedValue ?: 1.0f)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            uLocs["uTex"]?.let { GLES20.glUniform1i(it, 0) }
            bindCommonAttribs(kaleidoProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        private fun renderToScreen() {
            if (simpleProgram == 0) return
            GLES20.glViewport(0, 0, viewWidth, viewHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val isPortrait = viewWidth < viewHeight

            // --- Aspect Fill Calculation (Screen vs FBO) ---
            // The FBO is 16:9. The screen might be 21:9 or 4:3.
            // We want to scale the quad so it fills the screen without stretching.
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0)

            val fboRatio = FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat() // ~1.77 (16:9)
            val screenRatio = viewWidth.toFloat() / viewHeight.toFloat()

            if (isPortrait) {
                // Rotate quad -90 degrees
                android.opengl.Matrix.rotateM(mvpMatrix, 0, -90f, 0f, 0f, 1f)

                // Effective FBO ratio after rotation (1080/1920 = ~0.56)
                val rotatedFboRatio = 1f / fboRatio

                if (screenRatio < rotatedFboRatio) {
                    // Screen is taller/narrower (e.g. 0.45 vs 0.56)
                    // We must scale the Long Dimension (which is Local Y / Screen X) to fill.
                    val scale = rotatedFboRatio / screenRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, scale, 1f)
                } else {
                    // Screen is wider/shorter
                    val scale = screenRatio / rotatedFboRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1f, 1f)
                }
            } else {
                // Landscape
                if (screenRatio > fboRatio) {
                    // Screen wider than 16:9 (e.g. 21:9)
                    // Scale Y (Height) UP to fill width (Aspect Fill)
                    val scale = screenRatio / fboRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, scale, 1f)
                } else {
                    // Screen narrower than 16:9 (e.g. 4:3)
                    // Scale X (Width) UP to fill height
                    val scale = fboRatio / screenRatio
                    android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1f, 1f)
                }
            }

            GLES20.glUseProgram(simpleProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glUniform1i(simpleULocs["uTex"] ?: -1, 0); GLES20.glUniformMatrix4fv(simpleULocs["uMVPMatrix"] ?: -1, 1, false, mvpMatrix, 0)
            bindCommonAttribs(simpleProgram); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderToExternal() {
            if (extEglSurface != EGL14.EGL_NO_SURFACE) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, extEglSurface, extEglSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, extWidth, extHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); drawSimpleTexture(fboTexId)
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, extEglSurface!!, System.nanoTime()); EGL14.eglSwapBuffers(mSavedDisplay, extEglSurface)
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
                    GLES20.glUseProgram(simpleProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
                    GLES20.glUniform1i(simpleULocs["uTex"] ?: -1, 0)
                    GLES20.glUniformMatrix4fv(simpleULocs["uMVPMatrix"] ?: -1, 1, false, identityMatrix, 0)
                    bindCommonAttribs(simpleProgram)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
            GLES20.glUseProgram(simpleProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(simpleULocs["uTex"] ?: -1, 0); GLES20.glUniformMatrix4fv(simpleULocs["uMVPMatrix"] ?: -1, 1, false, identityMatrix, 0)
            bindCommonAttribs(simpleProgram); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun bindCommonAttribs(prog: Int) {
            val pL = GLES20.glGetAttribLocation(prog, "p"); val tL = GLES20.glGetAttribLocation(prog, "t")
            GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        }
        private fun createProgram(vSrc: String, fSrc: String): Int {
            val vShader = compile(GLES20.GL_VERTEX_SHADER, vSrc); val fShader = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
            if (vShader == 0 || fShader == 0) return 0
            val prog = GLES20.glCreateProgram(); GLES20.glAttachShader(prog, vShader); GLES20.glAttachShader(prog, fShader); GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) { Log.e("GL", "Link Failed: " + GLES20.glGetProgramInfoLog(prog)); GLES20.glDeleteProgram(prog); return 0 }
            return prog
        }
        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, src); GLES20.glCompileShader(shader)
            val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) { Log.e("GL", "Compile Failed: " + GLES20.glGetShaderInfoLog(shader)); GLES20.glDeleteShader(shader); return 0 }
            return shader
        }
        private fun createOESTex(): Int { val t=IntArray(1); GLES20.glGenTextures(1,t,0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,t[0]); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR); return t[0] }
        private fun setupEGL() { mSavedDisplay = EGL14.eglGetCurrentDisplay(); mSavedContext = EGL14.eglGetCurrentContext(); val currentConfigId = IntArray(1); EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0); val configs = arrayOfNulls<EGL14EGLConfig>(1); val num = IntArray(1); EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0); mEglConfig = configs[0] }
        private fun manageSurfaces() {
            val args = extSurfaceArgs
            if (args != null && extEglSurface == EGL14.EGL_NO_SURFACE) { val rawSurf = args.first; extWidth = args.second; extHeight = args.third; extEglSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, rawSurf, intArrayOf(EGL14.EGL_NONE), 0) }
            if (args == null && extEglSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(mSavedDisplay, extEglSurface); extEglSurface = EGL14.EGL_NO_SURFACE }
            if (pendingRecordFile != null) { videoRecorder = VideoRecorder(ctx, viewWidth, viewHeight, pendingRecordFile!!); recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0); pendingRecordFile = null }
        }
        private fun handleStopRecording() { if (isStopRequested) { videoRecorder?.drain(true); val out = videoRecorder?.file; if (recordSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(mSavedDisplay, recordSurface); recordSurface = EGL14.EGL_NO_SURFACE }; videoRecorder?.release(); videoRecorder = null; isStopRequested = false; onStopCallback?.invoke(out) } }
        private fun handleCapture() {
            if (captureRequested) {
                captureRequested = false; val b = ByteBuffer.allocate(fboWidth * fboHeight * 4); GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(fboWidth, fboHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "SB_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceBeam") }
                    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                }.start()
            }
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

            // FIX: Enforce Baseline Profile.
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

    // Note: Add 'context' to constructor or pass it to setupAudio if specific permission checks needed inside class,
    // but assuming permission is already granted in MainActivity, this logic holds.
}