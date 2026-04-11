package com.calmyjane.spacebeam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.annotation.SuppressLint
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.*

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

    @Volatile var value: Int = defaultValue
        private set
    @Volatile var preciseValue: Float = defaultValue.toFloat()
        private set

    var isLocked: Boolean = defaultLocked
    var subtitle: String? = null
    var onTouchDown: (() -> Unit)? = null
    var onTouchUp: (() -> Unit)? = null
    private var lockButton: Button? = null

    var smoothing: Int = 500
    private var smoothedNormalized: Float = 0f
    @Volatile private var modulatedNormalized: Float = 0f

    private var smoothedModRate: Float = 200f
    private var smoothedModDepth: Float = 0f

    private var animTarget: Float? = null
    private var animStart: Float = 0f
    private var animDuration: Float = 0f
    private var animTime: Float = 0f
    private var isAnimating = false

    var modRate: Int = 200
    var modDepth: Int = 0
    var modShape: WaveShape = if (modMode == ModMode.WRAP) WaveShape.RAMP else WaveShape.SINE

    // Sensor modulation: 500 = no effect, 0 = full negative, 1000 = full positive
    var sensorAccelX: Int = 500
    var sensorAccelY: Int = 500
    var sensorAccelZ: Int = 500
    var sensorPitch: Int = 500
    var sensorRoll: Int = 500
    var sensorYaw: Int = 500

    var preciseModRate: Float = 200f
    var preciseModDepth: Float = 0f
    var lfoPhase: Double = 0.0
    private var rampAccum: Double = 0.0
    private var rampAccumStart: Double = 0.0
    private var rampAccumTarget: Double? = null
    private var noiseValA: Float = Math.random().toFloat()
    private var noiseValB: Float = Math.random().toFloat()

    var isBeatSynced: Boolean = false
    var beatMultiplierIndex: Int = 3
    val beatMultipliers = floatArrayOf(0.125f, 0.25f, 0.5f, 1.0f, 2.0f, 4.0f)
    val multiplierLabels = arrayOf("8 BEATS", "4 BEATS", "2 BEATS", "1 BEAT", "1/2 BEAT", "1/4 BEAT")
    private var smoothedFrequency: Float = 0f

    private var modRateStart = 0f
    private var modRateTarget: Float? = null
    private var modDepthStart = 0f
    private var modDepthTarget: Float? = null

    private var oldModShape: WaveShape? = null
    private var shapeFadeProgress: Float = 1f

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

    private var isRateDragging = false
    private var isDepthDragging = false

    private var lastDisplayedValue: Int = -Int.MAX_VALUE
    private var lastSyncedModRate: Int = -1
    private var lastSyncedModDepth: Int = -1
    private var lastSyncedSmoothing: Int = -1
    private var lastInputSyncValue: Int = -Int.MAX_VALUE
    private var lastSyncSpeedLabel: String = ""

    init {
        val ratio = (defaultValue.toFloat() / sliderMax.toFloat()).coerceAtLeast(0f)
        smoothedNormalized = ratio
        modulatedNormalized = ratio
    }

    val computedValue: Float
        get() {
            return outMin + (modulatedNormalized * (outMax - outMin))
        }

    data class Snapshot(val value: Int, val active: Boolean, val rate: Int, val depth: Int, val shape: String, val smoothing: Int, val isSynced: Boolean = false, val syncIndex: Int = 3,
                        val sensorAccelX: Int = 500, val sensorAccelY: Int = 500, val sensorAccelZ: Int = 500,
                        val sensorPitch: Int = 500, val sensorRoll: Int = 500, val sensorYaw: Int = 500)

    fun getSnapshot(): Snapshot = Snapshot(value, modDepth > 0, modRate, modDepth, modShape.name, smoothing, isBeatSynced, beatMultiplierIndex,
        sensorAccelX, sensorAccelY, sensorAccelZ, sensorPitch, sensorRoll, sensorYaw)

    fun restore(s: Snapshot, durationSec: Float) {
        if (isLocked) return
        applySnapshot(s, durationSec)
    }

    fun restoreForUndo(s: Snapshot, durationSec: Float) {
        applySnapshot(s, durationSec)
    }

    private fun applySnapshot(s: Snapshot, durationSec: Float) {
        animateTo(s.value.toFloat(), durationSec, s.shape)
        if (hasModulation) {
            animateModulation(s.rate.toFloat(), s.depth.toFloat(), durationSec)
        }
        if (modMode == ModMode.WRAP) {
            rampAccumStart = rampAccum
            rampAccumTarget = Math.round(rampAccum).toDouble()
        }
        this.smoothing = s.smoothing
        this.isBeatSynced = s.isSynced
        this.beatMultiplierIndex = s.syncIndex
        this.sensorAccelX = s.sensorAccelX
        this.sensorAccelY = s.sensorAccelY
        this.sensorAccelZ = s.sensorAccelZ
        this.sensorPitch  = s.sensorPitch
        this.sensorRoll   = s.sensorRoll
        this.sensorYaw    = s.sensorYaw
    }

    fun animateTo(target: Float, durationSec: Float, newShape: String? = null) {
        animTarget = target
        animStart = preciseValue
        animDuration = durationSec
        animTime = 0f
        isAnimating = true
        if (newShape != null) {
            try {
                val parsedShape = WaveShape.valueOf(newShape)
                if (parsedShape != modShape) {
                    if (oldModShape != null && parsedShape == oldModShape) {
                        oldModShape = modShape
                        modShape = parsedShape
                        shapeFadeProgress = 1f - shapeFadeProgress
                    } else {
                        oldModShape = modShape
                        modShape = parsedShape
                        shapeFadeProgress = 0f
                    }
                    shapeBtn?.post { shapeBtn?.text = modShape.name }
                }
            } catch (e: Exception) {}
        }
    }

    fun animateModulation(targetRate: Float, targetDepth: Float, durationSec: Float) {
        modRateStart = preciseModRate
        modRateTarget = targetRate
        modDepthStart = preciseModDepth
        modDepthTarget = targetDepth
    }

    private fun getWaveValue(shape: WaveShape, phase: Double): Double {
        return when (shape) {
            WaveShape.SINE -> Math.sin(phase) * 0.5 + 0.5
            WaveShape.TRIANGLE -> { val p = (phase / (2.0 * Math.PI)); if (p < 0.5) p * 2.0 else 2.0 - (p * 2.0) }
            WaveShape.RAMP -> (phase / (2.0 * Math.PI)) % 1.0
            WaveShape.WOBBLE_SINE -> { val w = Math.sin(phase + Math.sin(phase)); w * 0.5 + 0.5 }
            WaveShape.RANDOM_SMOOTH -> {
                val progress = (phase / (2.0 * Math.PI)).toFloat()
                val smoothT = (1.0 - Math.cos(progress * Math.PI)) * 0.5
                (noiseValA * (1.0 - smoothT) + noiseValB * smoothT)
            }
            WaveShape.RANDOM_STEP -> noiseValA.toDouble()
        }
    }

    open fun update(deltaTime: Float) {
        val t = if (isAnimating && animDuration > 0) (animTime / animDuration).coerceIn(0f, 1f) else 1f
        val ease = t * t * (3.0f - 2.0f * t)

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

        // Animate rampAccum toward nearest full revolution during preset transitions
        if (rampAccumTarget != null && modMode == ModMode.WRAP) {
            if (isAnimating && animDuration > 0) {
                val at = (animTime / animDuration).coerceIn(0f, 1f)
                val ae = at * at * (3f - 2f * at)
                rampAccum = rampAccumStart + (rampAccumTarget!! - rampAccumStart) * ae
            }
            if (!isAnimating) {
                rampAccum = rampAccumTarget!!
                rampAccumTarget = null
                rampAccum -= Math.floor(rampAccum) // normalize to [0, 1)
            }
        }

        if (hasModulation && (smoothedModRate > 1f || smoothedModDepth > 1f || isBeatSynced)) {
            val baseSpeed: Float
            if (isBeatSynced && currentContext is MainActivity) {
                val bpm = (currentContext as MainActivity).bpmManager.bpm
                val targetHz = (bpm / 60f) * beatMultipliers[beatMultiplierIndex]
                smoothedFrequency += (targetHz - smoothedFrequency) * baseLerp
                baseSpeed = smoothedFrequency
            } else {
                baseSpeed = (smoothedModRate / 1000f + 0.05f).toDouble().pow(3.0).toFloat()
                smoothedFrequency = baseSpeed
            }

            lfoPhase += baseSpeed * deltaTime * 2.0 * Math.PI

            while (lfoPhase >= 2.0 * Math.PI) {
                lfoPhase -= 2.0 * Math.PI
                noiseValA = noiseValB
                noiseValB = Math.random().toFloat()
            }

            if (oldModShape != null) {
                if (animDuration > 0) {
                    shapeFadeProgress += deltaTime / animDuration
                } else {
                    shapeFadeProgress = 1f
                }

                if (shapeFadeProgress >= 1f) {
                    shapeFadeProgress = 1f
                    oldModShape = null
                }
            }

            var rawWave = if (modShape == WaveShape.RAMP && modMode == ModMode.WRAP) {
                ((rampAccum % 1.0) + 1.0) % 1.0
            } else {
                getWaveValue(modShape, lfoPhase)
            }

            if (oldModShape != null && shapeFadeProgress < 1f) {
                val oldWave = if (oldModShape == WaveShape.RAMP && modMode == ModMode.WRAP) {
                    ((rampAccum % 1.0) + 1.0) % 1.0
                } else {
                    getWaveValue(oldModShape!!, lfoPhase)
                }
                val fadeEased = shapeFadeProgress * shapeFadeProgress * (3.0f - 2.0f * shapeFadeProgress)
                rawWave = oldWave + (rawWave - oldWave) * fadeEased
            }

            val depthNorm = (smoothedModDepth / 1000f).toDouble().pow(2.0).toFloat()

            if (modMode == ModMode.WRAP) {
                if (modShape == WaveShape.RAMP && oldModShape == null && rampAccumTarget == null) {
                    // Pure RAMP+WRAP steady state: depth controls accumulation speed, not amplitude
                    rampAccum += baseSpeed * depthNorm * deltaTime.toDouble()
                    currentCalculatedOutput = ((smoothedNormalized + rampAccum.toFloat()) % 1.0f + 1.0f) % 1.0f
                } else {
                    // During transitions/crossfade: use waveform*depth model with rampAccum fraction
                    val raw = smoothedNormalized + (rawWave.toFloat() * depthNorm)
                    currentCalculatedOutput = ((raw % 1.0f) + 1.0f) % 1.0f
                }
            } else {
                currentCalculatedOutput = (smoothedNormalized * (1.0f - depthNorm)) + (rawWave.toFloat() * depthNorm)
            }
        }

        val sh = (currentContext as? MainActivity)?.sensorHelper
        if (sh != null && (sensorAccelX != 500 || sensorAccelY != 500 || sensorAccelZ != 500 ||
                           sensorPitch != 500 || sensorRoll != 500 || sensorYaw != 500)) {
            // t*|t| applies a quadratic curve while preserving sign:
            // small slider deviations from center → very small effect, full deflection → full effect
            fun sensorScale(v: Int): Float { val t = (v - 500) / 500f; return t * abs(t) }
            var sensorOffset = 0f
            sensorOffset += sensorScale(sensorAccelX) * sh.accelX
            sensorOffset += sensorScale(sensorAccelY) * sh.accelY
            sensorOffset += sensorScale(sensorAccelZ) * sh.accelZ
            sensorOffset += sensorScale(sensorPitch)  * sh.pitch
            sensorOffset += sensorScale(sensorRoll)   * sh.roll
            sensorOffset += sensorScale(sensorYaw)    * sh.yaw
            currentCalculatedOutput = if (modMode == ModMode.WRAP) {
                ((currentCalculatedOutput + sensorOffset) % 1.0f + 1.0f) % 1.0f
            } else {
                (currentCalculatedOutput + sensorOffset).coerceIn(0f, 1f)
            }
        }

        modulatedNormalized = currentCalculatedOutput

        syncUiElements()
        modIndicator?.postInvalidate()

        val displayVal = (modulatedNormalized * sliderMax).roundToInt()
        updateLiveValueUI(displayVal)
    }

    private fun syncUiElements() {
        if (sliderView != null) {
            val visualT = if (logPower > 1) smoothedNormalized.toDouble().pow(1.0/logPower).toFloat() else smoothedNormalized
            // setVisualState uses postInvalidate() internally, so it is inherently thread-safe
            sliderView!!.setVisualState(visualT, formatValue(value))
        }

        if (activeControl == this) {
            // THREAD-SAFE: Sync the base value input box
            if (value != lastInputSyncValue) {
                lastInputSyncValue = value
                val valStr = value.toString()
                baseValueInput?.post {
                    if (baseValueInput?.hasFocus() == false) {
                        baseValueInput?.setText(valStr)
                    }
                }
            }

            // THREAD-SAFE: Sync the "SPEED" vs "SYNC" label
            val speedLabel = floatingPanel?.findViewWithTag<TextView>("SPEED_LABEL")
            if (isBeatSynced) {
                val newLabel = multiplierLabels[beatMultiplierIndex]
                if (newLabel != lastSyncSpeedLabel) {
                    lastSyncSpeedLabel = newLabel
                    speedLabel?.post { speedLabel.text = newLabel }
                }

                val curRate = (beatMultiplierIndex * 200).coerceIn(0, 1000)
                if (!isRateDragging) modPanelSpeedSeekBar?.post { modPanelSpeedSeekBar?.progress = curRate }
            } else {
                if (lastSyncSpeedLabel != "SPEED") {
                    lastSyncSpeedLabel = "SPEED"
                    speedLabel?.post { speedLabel.text = "SPEED" }
                }

                val curRate = smoothedModRate.toInt()
                if (curRate != lastSyncedModRate && !isRateDragging) {
                    lastSyncedModRate = curRate
                    modPanelSpeedSeekBar?.post { modPanelSpeedSeekBar?.progress = curRate }
                }
            }

            // THREAD-SAFE: Sync the Depth Slider
            val curDepth = smoothedModDepth.toInt()
            if (curDepth != lastSyncedModDepth && !isDepthDragging) {
                lastSyncedModDepth = curDepth
                modPanelDepthSeekBar?.post { modPanelDepthSeekBar?.progress = curDepth }
            }

            // THREAD-SAFE: Sync the Smoothing Slider
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

        if (activeControl == this && baseValueInput != null && !baseValueInput!!.hasFocus()) {
            baseValueInput!!.setText(clamped.toString())
        }
    }

    fun updateModRate(v: Int) {
        if (isBeatSynced) {
            beatMultiplierIndex = (v / 200).coerceIn(0, 5)
        } else {
            modRate = v.coerceIn(0, 1000)
            preciseModRate = modRate.toFloat()
        }
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
        setProgress(defaultValue)

        if (hasModulation) {
            updateModRate(200)
            updateModDepth(0)
            smoothedModRate = 200f
            smoothedModDepth = 0f

            updateSmoothing(500)
            modShape = if (modMode == ModMode.WRAP) WaveShape.RAMP else WaveShape.SINE
            oldModShape = null
            shapeFadeProgress = 1f
            updateIndicatorVisuals()
            shapeBtn?.post { shapeBtn?.text = modShape.name }
        }
    }

    fun stopAnimation() {
        isAnimating = false; animTarget = null; modRateTarget = null; modDepthTarget = null
        oldModShape = null; shapeFadeProgress = 1f
        rampAccumTarget = null
    }

    fun resetRampAccum() { rampAccum = 0.0; rampAccumTarget = null }

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

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, 70, 1f)
        }
        mainRowLayout = sliderRow

        val ratio = (value.toFloat() / sliderMax.toFloat()).coerceIn(0f, 1f)
        val initialT = if (logPower > 1) ratio.toDouble().pow(1.0/logPower).toFloat() else ratio

        sliderView = SliderBox(context).apply {
            setVisualState(initialT, formatValue(value))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(15, 0, 15, 0)
            }
        }
        sliderRow.addView(sliderView)

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
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isFocusable = true
                isFocusableInTouchMode = true
                isSelected = true
                setPadding(0, 2, 20, 0)
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

        val numRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, 140).apply { bottomMargin = 12 } }
        val btnDec = createNumButton(context, "-") { setProgress(value - 1) }

        val centerColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f)
        }

        baseValueInput = EditText(context).apply {
            setText(value.toString())
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = null
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE; layoutParams = LinearLayout.LayoutParams(-1, -2)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val num = v.text.toString().toIntOrNull() ?: value; setProgress(num); v.clearFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0); (context as? MainActivity)?.hideSystemUI()
                    true
                } else false
            }
        }
        centerColumn.addView(baseValueInput)

        if (hasModulation) {
            liveValueDisplay = TextView(context).apply {
                text = formatValue(value)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            centerColumn.addView(liveValueDisplay)
        }

        val btnInc = createNumButton(context, "+") { setProgress(value + 1) }
        numRow.addView(btnDec); numRow.addView(centerColumn); numRow.addView(btnInc)
        contentLayout.addView(numRow)

        if (hasModulation) {

            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 2).apply { bottomMargin = 20 }; setBackgroundColor(Color.DKGRAY) })

            // Hook for subclasses to add controls above smooth (e.g. flip/rotate)
            addGeometryControls(contentLayout, context)

            // Smooth slider (outside any category)
            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 16) })
            val smoothSb = addSliderToPanel(context, contentLayout, "SMOOTH", smoothing) { updateSmoothing(it) }
            smoothSb.tag = "SMOOTH_SEEK"
            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 16) })

            // Hook for subclasses to add collapsible categories above LFO (e.g. TRANSFORM for sources)
            addCategoryControls(contentLayout, context)
            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 10) })

            // --- LFO category (collapsed by default) ---
            val (lfoGroup, lfoContent) = createCollapsibleDetailGroup(context, "LFO", false)

            val shapeSyncRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 25; topMargin = 6 }
            }

            shapeBtn = Button(context).apply {
                text = modShape.name; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply { setColor(Color.parseColor("#444444")); cornerRadius = 10f; setStroke(1, Color.GRAY) }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 10 }
                setOnClickListener { modShape = WaveShape.values()[(modShape.ordinal + 1) % WaveShape.values().size]; text = modShape.name }
            }

            val syncBtn = Button(context).apply {
                text = if (isBeatSynced) "SYNC: ON" else "SYNC: OFF"
                textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    setColor(if(isBeatSynced) Color.parseColor("#0066CC") else Color.parseColor("#444444"))
                    cornerRadius = 10f; setStroke(1, Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 10 }
                setOnClickListener {
                    isBeatSynced = !isBeatSynced
                    text = if (isBeatSynced) "SYNC: ON" else "SYNC: OFF"
                    background = GradientDrawable().apply {
                        setColor(if(isBeatSynced) Color.parseColor("#0066CC") else Color.parseColor("#444444"))
                        cornerRadius = 10f; setStroke(1, Color.GRAY)
                    }
                    syncUiElements()
                }
            }

            shapeSyncRow.addView(shapeBtn)
            shapeSyncRow.addView(syncBtn)
            lfoContent.addView(shapeSyncRow)

            modPanelSpeedSeekBar = addSliderToPanel(context, lfoContent, "SPEED", modRate) { updateModRate(it) }
            modPanelSpeedSeekBar?.setOnTouchListener { v, event ->
                if(event.action == MotionEvent.ACTION_DOWN) { isRateDragging = true; onTouchDown?.invoke() }
                if(event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) { isRateDragging = false; onTouchUp?.invoke() }
                v.onTouchEvent(event); true
            }

            modPanelDepthSeekBar = addSliderToPanel(context, lfoContent, "DEPTH", modDepth) { updateModDepth(it) }
            modPanelDepthSeekBar?.setOnTouchListener { v, event ->
                if(event.action == MotionEvent.ACTION_DOWN) { isDepthDragging = true; onTouchDown?.invoke() }
                if(event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) { isDepthDragging = false; onTouchUp?.invoke() }
                v.onTouchEvent(event); true
            }

            contentLayout.addView(lfoGroup)

            contentLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 10) })
        }

        // --- Gyro category (collapsed by default) ---
        val (gyroGroup, gyroContent) = createCollapsibleDetailGroup(context, "GYRO", false)

        gyroContent.addView(TextView(context).apply {
            text = "ROTATION"
            textSize = 10f; setTextColor(Color.GRAY); setPadding(0, 0, 0, 5)
        })
        addSensorAxisSlider(context, gyroContent, "Pitch", sensorPitch) { sensorPitch = it }
        addSensorAxisSlider(context, gyroContent, "Roll",  sensorRoll)  { sensorRoll  = it }
        addSensorAxisSlider(context, gyroContent, "Yaw",   sensorYaw)   { sensorYaw   = it }
        gyroContent.addView(TextView(context).apply {
            text = "ACCELERATION"
            textSize = 10f; setTextColor(Color.GRAY); setPadding(0, 10, 0, 5)
        })
        addSensorAxisSlider(context, gyroContent, "X", sensorAccelX) { sensorAccelX = it }
        addSensorAxisSlider(context, gyroContent, "Y", sensorAccelY) { sensorAccelY = it }
        addSensorAxisSlider(context, gyroContent, "Z", sensorAccelZ) { sensorAccelZ = it }

        contentLayout.addView(gyroGroup)
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
            tag = "${name}_LABEL"
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
        parent.addView(row)
        return sb
    }

    private fun addSensorAxisSlider(ctx: Context, parent: ViewGroup, name: String, current: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 5, 0, 5)
        }
        var seekBarRef: SeekBar? = null
        val labelView = TextView(ctx).apply {
            text = name
            textSize = 10f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            setOnClickListener { onChange(500); seekBarRef?.progress = 500 }
        }
        val sb = SeekBar(ctx).apply {
            max = 1000
            progress = current
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            setPadding(0, 0, 0, 0)
            thumbOffset = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        seekBarRef = sb
        row.addView(labelView)
        row.addView(sb)
        parent.addView(row)
    }

    protected fun createCollapsibleDetailGroup(context: Context, title: String, startOpen: Boolean): Pair<LinearLayout, LinearLayout> {
        val groupContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 } }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(15, 12, 15, 12); background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = 8f; setStroke(1, Color.parseColor("#44FFFFFF")) } }
        val arrow = TextView(context).apply { text = "▶"; textSize = 9f; setTextColor(Color.LTGRAY); layoutParams = LinearLayout.LayoutParams(50, -2); rotation = if (startOpen) 90f else 0f }
        val label = TextView(context).apply { text = title; textSize = 10f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); letterSpacing = 0.15f }
        header.addView(arrow); header.addView(label)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = if (startOpen) View.VISIBLE else View.GONE; setPadding(6, 6, 6, 6) }
        header.setOnClickListener {
            val isVisible = content.visibility == View.VISIBLE
            if (isVisible) { content.visibility = View.GONE; arrow.animate().rotation(0f).setDuration(200).start() }
            else { content.visibility = View.VISIBLE; arrow.animate().rotation(90f).setDuration(200).start() }
        }
        groupContainer.addView(header); groupContainer.addView(content)
        return Pair(groupContainer, content)
    }

    protected fun buildTransformCategory(panel: LinearLayout, context: Context, channel: KaleidoscopeRenderer.SourceChannel) {
        val (transformGroup, transformContent) = createCollapsibleDetailGroup(context, "TRANSFORM", false)

        // Flip/rotate buttons at the top of the TRANSFORM group
        addFlipRotateButtons(transformContent, context, channel)

        // Slider style matching LFO/GYRO: horizontal row, label 130px, seekbar flex
        fun addRow(label: String, min: Float, max: Float, defaultVal: Float, getter: () -> Float, setter: (Float) -> Unit) {
            var sbRef: SeekBar? = null
            val rangeMin = min; val rangeMax = max
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 5, 0, 5)
            }
            val lv = TextView(context).apply {
                text = label; textSize = 10f; setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.MATCH_PARENT)
                isClickable = true
                setOnClickListener {
                    setter(defaultVal)
                    sbRef?.progress = ((defaultVal - rangeMin) / (rangeMax - rangeMin) * 1000).toInt().coerceIn(0, 1000)
                }
            }
            val sb = SeekBar(context).apply {
                this.max = 1000
                progress = ((getter() - rangeMin) / (rangeMax - rangeMin) * 1000).toInt().coerceIn(0, 1000)
                thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
                setPadding(0, 0, 0, 0); thumbOffset = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) setter(rangeMin + (p / 1000f) * (rangeMax - rangeMin)) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            sbRef = sb
            row.addView(lv); row.addView(sb)
            transformContent.addView(row)
        }

        addRow("ZOOM", 0.1f, 4.0f, 1.0f, { channel.srcZoom }) { channel.srcZoom = it }
        addRow("ANGLE", -180f, 180f, 0f, { channel.srcAngle }) { channel.srcAngle = it }
        addRow("MOVE X", -1f, 1f, 0f, { channel.srcMoveX }) { channel.srcMoveX = it }
        addRow("MOVE Y", -1f, 1f, 0f, { channel.srcMoveY }) { channel.srcMoveY = it }

        // Wrap mode dropdown
        val wrapRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 5, 0, 5)
        }
        wrapRow.addView(TextView(context).apply {
            text = "WRAP"; textSize = 10f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val wrapSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, listOf("MIRROR", "HOLD", "REPEAT")).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(channel.srcWrapMode)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333")); cornerRadius = 8f; setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(0, 90, 1f)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                    (parent?.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
                    channel.srcWrapMode = pos
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        wrapRow.addView(wrapSpinner)
        transformContent.addView(wrapRow)

        panel.addView(transformGroup)
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

    open fun addGeometryControls(panel: LinearLayout, context: Context) {}
    open fun addCategoryControls(panel: LinearLayout, context: Context) {}
    open fun addExtraControls(panel: LinearLayout, context: Context) {}
    protected fun createNumButton(ctx: Context, txt: String, action: () -> Unit): Button {
        return Button(ctx).apply { text = txt; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 15f; setStroke(1, Color.GRAY) }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(5, 5, 5, 5) }
            setOnClickListener { action() } }
    }

    private inner class SliderBox(context: Context) : View(context) {
        private var visualProgress = 0f
        private var displayText = ""
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#222222")
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCCCCC")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val cornerRadius = 12f

        fun setVisualState(p: Float, text: String) {
            visualProgress = p.coerceIn(0f, 1f)
            displayText = text
            postInvalidate()
        }

        private var touchDownX = 0f
        private var touchDownY = 0f
        private var touchDownLocalX = 0f
        private var directionLocked = false
        private var isHorizontal = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    touchDownLocalX = event.x
                    directionLocked = false
                    isHorizontal = false
                    stopAnimation()
                    if (activeControl != null && activeControl != this@PropertyControl) closeActiveMenu()
                    onTouchDown?.invoke()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!directionLocked) {
                        val dx = abs(event.rawX - touchDownX)
                        val dy = abs(event.rawY - touchDownY)
                        if (dx > 15 || dy > 15) {
                            directionLocked = true
                            isHorizontal = dx > dy
                            parent.requestDisallowInterceptTouchEvent(isHorizontal)
                            if (isHorizontal) updateFromTouch(touchDownLocalX)
                        }
                    }
                    if (directionLocked && isHorizontal) {
                        updateFromTouch(event.x)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!directionLocked) {
                        // Short tap — apply the touch position
                        updateFromTouch(event.x)
                    }
                    parent.requestDisallowInterceptTouchEvent(false)
                    directionLocked = false
                    onTouchUp?.invoke()
                }
            }
            return true
        }

        private fun updateFromTouch(x: Float) {
            val w = width.toFloat()
            if (w <= 0) return
            val t = (x / w).coerceIn(0f, 1f)
            val curvedT = if (logPower > 1) t.toDouble().pow(logPower.toDouble()).toFloat() else t
            val calcVal = (curvedT * sliderMax).toInt().coerceIn(min, max)

            value = calcVal
            preciseValue = calcVal.toFloat()
            onValueChanged?.invoke(calcVal)

            visualProgress = t
            displayText = formatValue(calcVal)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val box = RectF(0f, 0f, w, h)

            canvas.drawRoundRect(box, cornerRadius, cornerRadius, bgPaint)

            val fillW = w * visualProgress
            if (fillW > 0) {
                canvas.save()
                canvas.clipRect(0f, 0f, fillW, h)
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, fillPaint)
                canvas.restore()
            }

            canvas.drawRoundRect(box, cornerRadius, cornerRadius, strokePaint)

            val cx = w / 2f
            val cy = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)

            canvas.save()
            canvas.clipRect(fillW, 0f, w, h)
            textPaint.color = Color.WHITE
            canvas.drawText(displayText, cx, cy, textPaint)
            canvas.restore()

            canvas.save()
            canvas.clipRect(0f, 0f, fillW, h)
            textPaint.color = Color.BLACK
            canvas.drawText(displayText, cx, cy, textPaint)
            canvas.restore()
        }
    }
}
