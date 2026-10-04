package com.andsi.airlyrics.ui.state

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainInteractionStateTest {
    @Test fun snapshotsRestoreIntoNewOwnerAndPanelCloseClearsOnlyPanelState() {
        val handle = SavedStateHandle()
        val state = MainInteractionState(handle)
        state.panel = FloatingPanelId.FONT
        state.write("panel.control.font") { putBoolean("expanded", true) }
        state.write("scroll.SETTINGS.LYRICS") { putFloat("y", 200f) }
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Bundle>(it) })
        val restored = MainInteractionState(restoredHandle)
        assertEquals(FloatingPanelId.FONT, restored.panel)
        assertTrue(restored.read("panel.control.font")!!.getBoolean("expanded"))
        restored.panel = null
        assertNull(restored.read("panel.control.font"))
        assertEquals(200f, restored.read("scroll.SETTINGS.LYRICS")!!.getFloat("y"))
        assertEquals(FloatingPanelId.FONT, state.panel)
    }

    @Test fun undoRestoresOnlyFieldsChangedByReset() {
        val original = FloatingLyricsStyleStore.getPresetDefaults(FloatingLyricsStyleStore.DEFAULT_PRESET)
            .copy(textSizeSp = 40f)
        val reset = original.copy(textSizeSp = 18f)
        val undo = original.undoChangesTo(reset)
        val subsequentlyEdited = reset.copy(textColor = 0xff123456.toInt())
        val restored = subsequentlyEdited.restoreFields(undo)
        assertEquals(40f, restored.textSizeSp)
        assertEquals(subsequentlyEdited.textColor, restored.textColor)
        assertEquals(setOf("textSizeSp"), undo.keySet())
    }
}
