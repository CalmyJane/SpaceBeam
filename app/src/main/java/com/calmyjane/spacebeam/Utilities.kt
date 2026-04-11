package com.calmyjane.spacebeam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

data class PlaylistItem(
    val uri: android.net.Uri,
    val isVideo: Boolean,
    var name: String,
    var durationVal: Float, // For images: seconds (0.5 to 30.0). For videos: percent (0.0 to 1.0)
    var crossfade: Float    // Seconds (0.0 to 15.0)
)

class BpmManager {
    var bpm = 120f
    private val tapTimes = mutableListOf<Long>()
    private val MAX_TAPS = 8

    fun tap() {
        val now = System.currentTimeMillis()

        if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2500) {
            tapTimes.clear()
        }

        tapTimes.add(now)
        if (tapTimes.size > MAX_TAPS) {
            tapTimes.removeAt(0)
        }

        if (tapTimes.size >= 3) {
            val intervals = mutableListOf<Long>()
            for (i in 1 until tapTimes.size) {
                intervals.add(tapTimes[i] - tapTimes[i - 1])
            }

            val avgInterval = intervals.average()
            if (avgInterval > 0) {
                bpm = (60000.0 / avgInterval).toFloat().coerceIn(30f, 300f)
            }
        }
    }
}

class UndoManager(var maxHistory: Int = 20) {
    data class UndoState(
        val controlSnapshots: Map<String, PropertyControl.Snapshot>,
        val activePreset: Int = -1
    )

    private val undoStack = ArrayDeque<UndoState>()
    private val redoStack = ArrayDeque<UndoState>()
    private var pendingState: UndoState? = null

    // The state we are currently transitioning towards (or have settled at).
    // This is the "logical current state" even if animations haven't finished.
    var targetState: UndoState? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    var onStateChanged: (() -> Unit)? = null

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        pendingState = null
        onStateChanged?.invoke()
    }

    fun captureBeforeChange(controls: List<PropertyControl>, activePreset: Int) {
        // Use targetState if mid-transition, otherwise capture live
        pendingState = targetState ?: captureCurrentState(controls, activePreset)
    }

    fun commitChange(controls: List<PropertyControl>, activePreset: Int) {
        val before = pendingState ?: return
        pendingState = null
        val current = captureCurrentState(controls, activePreset)
        if (before.controlSnapshots == current.controlSnapshots) return
        undoStack.addLast(before)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        targetState = current
        onStateChanged?.invoke()
    }

    fun pushStateDirectly(controls: List<PropertyControl>, activePreset: Int) {
        // Push the logical current state (target if mid-transition)
        val state = targetState ?: captureCurrentState(controls, activePreset)
        undoStack.addLast(state)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        onStateChanged?.invoke()
    }

    fun undo(controls: List<PropertyControl>, durationSec: Float): UndoState? {
        if (!canUndo) return null
        // Push the logical current target to redo (not mid-animation values)
        val current = targetState ?: return null
        redoStack.addLast(current)
        val target = undoStack.removeLast()
        targetState = target
        applyState(target, controls, durationSec)
        onStateChanged?.invoke()
        return target
    }

    fun redo(controls: List<PropertyControl>, durationSec: Float): UndoState? {
        if (!canRedo) return null
        val current = targetState ?: return null
        undoStack.addLast(current)
        val target = redoStack.removeLast()
        targetState = target
        applyState(target, controls, durationSec)
        onStateChanged?.invoke()
        return target
    }

    private fun captureCurrentState(controls: List<PropertyControl>, activePreset: Int): UndoState {
        val snapshots = controls.filter { it.includeInPreset }.associate { it.id to it.getSnapshot() }
        return UndoState(snapshots, activePreset)
    }

    private fun applyState(state: UndoState, controls: List<PropertyControl>, durationSec: Float) {
        controls.forEach { control ->
            if (!control.includeInPreset) return@forEach
            val snap = state.controlSnapshots[control.id] ?: return@forEach
            control.restoreForUndo(snap, durationSec)
        }
    }
}

enum class BlendMode(val label: String) {
    ADD("Add"),
    SCREEN("Screen"),
    MULTIPLY("Multiply"),
    DIFFERENCE("Difference"),
    OVERLAY("Overlay"),
    MAX("Max (Lighten)"),
    MIN("Min (Darken)"),
    SUBTRACT("Subtract")
}

enum class SourceType {
    CAMERA,
    MEDIA_VIDEO,
    MEDIA_IMAGE,
    RTSP,
    SHADER,
    PLAYLIST,
    FEEDBACK
}

fun addFlipRotateButtons(panel: LinearLayout, context: Context, channel: KaleidoscopeRenderer.SourceChannel) {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 110).apply {
            bottomMargin = 6; topMargin = 6
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    fun mkBtn(txt: String, topPad: Int, action: () -> Unit): FrameLayout {
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(110, -1).apply { setMargins(12, 0, 12, 0) }
            setOnClickListener { action() }
        }
        val label = TextView(context).apply {
            text = txt
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER; topMargin = -topPad }
        }
        frame.addView(label)
        return frame
    }

    row.addView(mkBtn("\u2194", 14) { channel.userFlipX *= -1f })
    row.addView(mkBtn("\u2195", 4) { channel.userFlipY *= -1f })
    row.addView(mkBtn("\u21BB", 4) { channel.userRot180 = !channel.userRot180 })
    panel.addView(row)
}
