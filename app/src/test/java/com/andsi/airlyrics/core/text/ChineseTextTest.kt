package com.andsi.airlyrics.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTextTest {

    private val chars = mapOf(
        '蓋' to '盖',
        '蘇' to '苏',
        '產' to '产',
        '繡' to '绣',
        '絲' to '丝',
        '乾' to '干',
        '擁' to '拥',
        '麼' to '么'
    )
    private val variants = mapOf('著' to '着')
    private val variantPhrases = mapOf(
        "著名" to "著名",
        "著作" to "著作"
    )
    private val phrases = mapOf(
        "乾隆" to "乾隆",
        "瞭解" to "了解",
        "藉口" to "借口"
    )

    private fun install() = ChineseText.installTableForTest(chars, variants, variantPhrases, phrases)

    @Test
    fun t2s_convertsTheVariantCharacterZhe() {
        install()
        assertEquals(
            "姑苏盛产的丝绣盖着我消瘦",
            ChineseText.toSimplified("姑蘇盛產的絲繡蓋著我消瘦")
        )
    }

    @Test
    fun t2s_keepsPhraseLevelCharactersThatLookAmbiguous() {
        install()
        assertEquals("著名", ChineseText.toSimplified("著名"))
        assertEquals("著作", ChineseText.toSimplified("著作"))
        assertEquals("乾隆", ChineseText.toSimplified("乾隆"))
    }

    @Test
    fun t2s_usesPhrasesThatTheCharacterTableCannotCover() {
        install()
        assertEquals("了解", ChineseText.toSimplified("瞭解"))
        assertEquals("借口", ChineseText.toSimplified("藉口"))
    }

    @Test
    fun scriptConversion_keepsPunctuationAndSpacesOutOfTheComparison() {
        install()
        assertTrue(ChineseText.isScriptConversion("姑蘇盛產的絲繡，蓋著我消瘦", "姑苏盛产的丝绣 盖着我消瘦。"))
    }

    @Test
    fun scriptConversion_acceptsShenMeWrittenWithShi() {
        install()
        assertTrue(
            ChineseText.isScriptConversion("但缺乏你我又擁有甚麼", "但缺乏你我又拥有什么")
        )
    }

    @Test
    fun scriptConversion_isFalseForARealRewrite() {
        install()
        assertFalse(ChineseText.isScriptConversion("我係一個凡人", "我只是一个普通人"))
    }

    @Test
    fun scriptConversion_isFalseForShortLinesThatChangeMeaning() {
        install()
        assertFalse(ChineseText.isScriptConversion("我愛你", "我恨你"))
    }

    @Test
    fun scriptConversion_isFalseWhenTooManyCharactersDiffer() {
        install()
        assertFalse(ChineseText.isScriptConversion("但缺乏你我又擁有甚麼", "但我从来没有真正拥有过什么"))
    }
}
