package com.andsi.airlyrics.ui.pages.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MascotCollisionExpressionTest {
    @Test fun smallContactsDoNotReplaceTheFace() {
        val reaction = MascotCollisionExpression(1f)
        reaction.onCollision(299f, 1_000L)
        assertEquals(MascotCollisionExpression.Face.NONE, reaction.faceAt(1_000L))
    }

    @Test fun hardHitShowsDizzyThenSpeechlessAndClears() {
        val reaction = MascotCollisionExpression(1f)
        reaction.onCollision(1_200f, 1_000L)
        assertEquals(MascotCollisionExpression.Face.DIZZY, reaction.faceAt(1_500L))
        assertEquals(MascotCollisionExpression.Face.SPEECHLESS, reaction.faceAt(1_700L))
        assertEquals(MascotCollisionExpression.Face.NONE, reaction.faceAt(2_100L))
    }

    @Test fun cooldownPreventsRapidExpressionRestart() {
        val reaction = MascotCollisionExpression(1f)
        reaction.onCollision(500f, 1_000L)
        reaction.onCollision(1_500f, 1_050L)
        assertEquals(MascotCollisionExpression.Face.SPEECHLESS, reaction.faceAt(1_200L))
        reaction.onCollision(1_500f, 1_250L)
        assertEquals(MascotCollisionExpression.Face.DIZZY, reaction.faceAt(1_300L))
        reaction.clear()
        assertEquals(MascotCollisionExpression.Face.NONE, reaction.faceAt(1_300L))
    }
}
