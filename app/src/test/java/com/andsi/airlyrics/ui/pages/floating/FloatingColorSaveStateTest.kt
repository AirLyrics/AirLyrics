package com.andsi.airlyrics.ui.pages.floating

import org.junit.Assert.*
import org.junit.Test

class FloatingColorSaveStateTest {
    @Test fun repeatedClicksSubmitOnceAndFailureCanBeRetried() {
        val state = FloatingColorSaveState(12, saved = false)
        val first = requireNotNull(state.begin())
        repeat(10) { assertNull(state.begin()) }
        assertEquals(ColorSaveStatus.SAVING, state.status)
        assertTrue(state.complete(first, success = false))
        assertEquals(ColorSaveStatus.FAILED, state.status)
        state.reconcile(saved = false)
        assertEquals(ColorSaveStatus.FAILED, state.status)
        assertTrue(state.canSave)
        val retry = requireNotNull(state.begin())
        assertFalse(state.complete(first, success = true))
        assertEquals(ColorSaveStatus.SAVING, state.status)
        assertTrue(state.complete(retry, success = true))
        assertEquals(ColorSaveStatus.SAVED, state.status)
        assertFalse(state.canSave)
        assertNull(state.begin())
    }

    @Test fun editsInvalidateTheOldResultEvenWhenReturningToTheSameRgb() {
        val state = FloatingColorSaveState(12, saved = false)
        val first = requireNotNull(state.begin())
        state.edit(34, saved = false)
        state.edit(12, saved = true)
        assertEquals(ColorSaveStatus.UNSAVED, state.status)
        assertFalse(state.canSave)
        assertNull(state.begin())
        assertTrue(state.complete(first, success = true))
        state.reconcile(saved = true)
        assertEquals(ColorSaveStatus.UNSAVED, state.status)
        assertTrue(state.canSave)
    }

    @Test fun editingAfterSuccessAllowsAnotherCompleteSave() {
        val state = FloatingColorSaveState(12, saved = false)
        state.complete(requireNotNull(state.begin()), success = true)
        state.edit(34, saved = false)
        assertEquals(ColorSaveStatus.UNSAVED, state.status)
        val second = requireNotNull(state.begin())
        assertEquals(34, second.color)
        state.complete(second, success = true)
        assertEquals(ColorSaveStatus.SAVED, state.status)
    }

    @Test fun closedSessionCannotAffectReopenedOrEditedEditors() {
        val old = FloatingColorSaveState(12, saved = false)
        val pending = requireNotNull(old.begin())
        old.close()
        assertFalse(old.complete(pending, success = true))
        assertNull(old.begin())
        val reopened = FloatingColorSaveState(12, saved = false)
        reopened.reconcile(saved = true)
        assertEquals(ColorSaveStatus.SAVED, reopened.status)
        val edited = FloatingColorSaveState(34, saved = false)
        edited.edit(56, saved = false)
        edited.reconcile(saved = true)
        assertEquals(ColorSaveStatus.UNSAVED, edited.status)
    }
}
