package com.andsi.airlyrics.ui.state

import android.graphics.Color
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingStyleUndoTest {
    @Test fun highlightResetCanBeUndoneWithoutRevertingOtherStyleEdits() {
        val defaults = FloatingLyricsStyleStore.getPresetDefaults(FloatingLyricsStyleStore.PRESET_BUBBLE)
        val original = defaults.copy(wordByWordHighlightColor = Color.MAGENTA, glow = LyricsGlowStyle(false, 160, 180))
        val reset = original.copy(wordByWordHighlightColor = defaults.wordByWordHighlightColor, glow = defaults.glow)
        val snapshot = original.undoChangesTo(reset)
        val later = reset.copy(textSizeSp = 36f)
        val restored = later.restoreFields(snapshot)
        assertEquals(original.wordByWordHighlightColor, restored.wordByWordHighlightColor)
        assertEquals(original.glow, restored.glow)
        assertEquals(36f, restored.textSizeSp)
    }

    @Test fun undoRestoresOnlyTheGlowFieldsActuallyReset() {
        val defaults = FloatingLyricsStyleStore.getPresetDefaults(FloatingLyricsStyleStore.PRESET_BUBBLE)
        val original = defaults.copy(glow = LyricsGlowStyle(enabled = false))
        val reset = original.copy(glow = defaults.glow)
        val restored = reset.copy(glow = defaults.glow.copy(strengthPercent = 180))
            .restoreFields(original.undoChangesTo(reset))
        assertEquals(LyricsGlowStyle(false, 180, 100), restored.glow)
    }
}
