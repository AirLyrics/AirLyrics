package com.andsi.airlyrics.lyrics.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LostEchoBracketTest {
    @Test
    fun detectsTheEchoThatLostItsOpeningBracket() {
        assertTrue(LyricsStorage.hasLostEchoBracket("看不見妳的笑 我怎麼睡得著著)"))
        assertTrue(LyricsStorage.hasLostEchoBracket("妳的聲音這麼近我卻抱不到到)"))
        assertTrue(LyricsStorage.hasLostEchoBracket("沒有地球太陽還是會繞是會繞)"))
        assertTrue(LyricsStorage.hasLostEchoBracket("妳要離開我知道很簡單很簡單)"))
        assertTrue(LyricsStorage.hasLostEchoBracket("妳說依賴是我們的阻礙的阻礙)"))
    }

    @Test
    fun keepsBalancedOrOrdinaryLines() {
        assertFalse(LyricsStorage.hasLostEchoBracket("看不見妳的笑 我怎麼睡得著 (睡得著)"))
        assertFalse(LyricsStorage.hasLostEchoBracket("妳的聲音這麼近我卻抱不到"))
        assertFalse(LyricsStorage.hasLostEchoBracket("(Oh) Baby"))
    }
}
