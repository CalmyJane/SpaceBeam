package com.calmyjane.spacebeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoManagerTest {
    private val a = PropertyControl("A", "A")
    private val b = PropertyControl("B", "B")
    private val ui = PropertyControl("UI", "UI", includeInPreset = false)
    private val controls = listOf(a, b, ui)
    private val undo = UndoManager()

    private fun change(c: PropertyControl, v: Int) {
        undo.captureBeforeChange(controls, -1)
        c.setProgress(v)
        undo.commitChange(controls, -1)
    }

    /** Undo/redo animate with duration 0: one frame lands them. */
    private fun settle() = controls.forEach { it.update(0.016f) }

    @Test fun undoAndRedoRestoreValues() {
        change(a, 300)
        change(b, 700)
        undo.undo(controls, 0f); settle()
        assertEquals(300, a.value); assertEquals(0, b.value)
        undo.undo(controls, 0f); settle()
        assertEquals(0, a.value)
        assertFalse(undo.canUndo)
        undo.redo(controls, 0f); settle()
        undo.redo(controls, 0f); settle()
        assertEquals(300, a.value); assertEquals(700, b.value)
        assertFalse(undo.canRedo)
    }

    @Test fun unchangedValueIsNotRecorded() {
        change(a, 0)
        assertFalse(undo.canUndo)
    }

    @Test fun controlsOutsidePresetsAreIgnored() {
        change(ui, 500)
        assertFalse(undo.canUndo)
        change(a, 100)
        ui.setProgress(900)
        undo.undo(controls, 0f); settle()
        assertEquals(900, ui.value)
    }

    @Test fun newChangeClearsRedo() {
        change(a, 100)
        undo.undo(controls, 0f); settle()
        assertTrue(undo.canRedo)
        change(b, 200)
        assertFalse(undo.canRedo)
        assertNull(undo.redo(controls, 0f))
    }

    @Test fun historyIsCapped() {
        undo.maxHistory = 5
        for (v in 1..10) change(a, v * 10)
        var steps = 0
        while (undo.undo(controls, 0f) != null) { settle(); steps++ }
        assertEquals(5, steps)
        assertEquals(50, a.value)
    }
}
