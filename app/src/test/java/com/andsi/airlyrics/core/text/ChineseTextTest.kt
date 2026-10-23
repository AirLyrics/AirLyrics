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
    fun scriptConversion_rejectsVariantsWithoutDictionarySupport() {
        install()
        assertFalse(
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
    @Test
    fun scriptConversion_rejectsSingleCharacterRewritesInLongLines() {
        install()
        assertFalse(ChineseText.isScriptConversion("我今天真的很想你", "我今天真的很想他"))
        assertFalse(ChineseText.isScriptConversion("我今天真的很想你", "我今天真的不想你"))
    }

    @Test
    fun scriptConversion_ignoresARepeatedPerformanceTail() {
        install()
        // Singers echo the tail ("…會繞會繞", "…睡得著(睡得著)"); the translation does not.
        assertTrue(ChineseText.isScriptConversion("蘇產繡蓋著(蓋著)", "苏产绣盖着"))
        assertTrue(ChineseText.isScriptConversion("蘇產繡蓋著蓋著", "苏产绣盖着"))
        assertTrue(ChineseText.isScriptConversion("蓋著蓋著", "盖着"))
    }

    @Test
    fun scriptConversion_stillRejectsARealRewriteThatCarriesARepeatedTail() {
        install()
        assertFalse(ChineseText.isScriptConversion("我今天真的很想你(想你)", "我今天真的很想他"))
    }

    @Test
    fun stripPerformanceRepeat_onlyTrimsTrailingEchoes() {
        assertEquals("會繞", ChineseText.stripPerformanceRepeat("會繞會繞"))
        assertEquals("我怎麼睡得著", ChineseText.stripPerformanceRepeat("我怎麼睡得著(睡得著)"))
        assertEquals("我想想", ChineseText.stripPerformanceRepeat("我想想"))
        assertEquals("我愛你", ChineseText.stripPerformanceRepeat("我愛你"))
    }

    @Test
    fun containsTraditional_respectsProtectedPhrasesAndUnloadedTables() {
        install()
        assertFalse(ChineseText.containsTraditional("著作"))
        assertTrue(ChineseText.containsTraditional("蓋著我"))
        ChineseText.resetForTest()
        assertFalse(ChineseText.containsTraditional("蓋著我"))
    }

}
