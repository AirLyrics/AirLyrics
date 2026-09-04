package com.andsi.airlyrics.displayscope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScopeCapabilityTest {
    @Test
    fun supportStartsAtAndroid10() {
        assertFalse(DisplayScopeCapability.isSupported(sdkInt = 28))
        assertTrue(DisplayScopeCapability.isSupported(sdkInt = 29))
        assertTrue(DisplayScopeCapability.isSupported(sdkInt = 37))
    }
}
