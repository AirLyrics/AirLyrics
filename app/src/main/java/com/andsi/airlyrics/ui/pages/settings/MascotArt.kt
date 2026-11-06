package com.andsi.airlyrics.ui.pages.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Base64InputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * All supplied layers share a 512 × 512 canvas. Decode at half resolution for the 220 dp About
 * illustration; no bitmap or path is allocated during a frame.
 */
internal class MascotArt(context: Context) {
    enum class Layer(val file: String) {
        REST("mascot_cat_rest_body"),
        HANG("mascot_cat_hang_body"),
        CLOUD_FRONT("mascot_cloud_front"),
        CLOUD_LYRICS("mascot_cloud_lyrics"),
        CLOUD_COMPLETE("mascot_cloud_complete"),
        FACE_IDLE("mascot_face_idle"),
        FACE_BLINK("mascot_face_blink"),
        FACE_HAPPY("mascot_face_happy"),
        FACE_SURPRISE("mascot_face_surprise"),
        FACE_SHY("mascot_face_shy"),
        PAW_LEFT("mascot_cat_paw_left"),
        PAW_RIGHT("mascot_cat_paw_right"),
        HANG_PAWS("mascot_cat_hang_forepaws"),
        NOTES("mascot_music_notes"),
        GROUND_REST_BODY("mascot_cat_ground_rest_body"),
        GROUND_REST_PAWS("mascot_cat_ground_rest_forepaws"),
        GROUND_SQUASH_BODY("mascot_cat_ground_squash_body"),
        GROUND_SQUASH_PAWS("mascot_cat_ground_squash_forepaws"),
        FACE_DIZZY("mascot_face_dizzy"),
        FACE_DIZZY_BASE("mascot_face_dizzy_base"),
        DIZZY_EYE_LEFT("mascot_eye_dizzy_left"),
        DIZZY_EYE_RIGHT("mascot_eye_dizzy_right"),
        FACE_SPEECHLESS("mascot_face_speechless"),
        SPEECHLESS_LINES("mascot_effect_speechless_lines"),
    }

    private val assets = context.assets
    private val cache = arrayOfNulls<Bitmap>(Layer.entries.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF(0f, 0f, 512f, 512f)
    private val mesh = FloatArray(9 * 9 * 2)
    private val groundMesh = FloatArray(17 * 33 * 2)
    private val pawMesh = FloatArray(33 * 33 * 2)

    fun bitmap(layer: Layer): Bitmap {
        val index = layer.ordinal
        cache[index]?.let { return it }
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bytes = assets.open("${layer.file}.png.b64").use { input ->
            Base64InputStream(input, Base64.DEFAULT).use { it.readBytes() }
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("Unable to decode mascot layer ${layer.file}")
        cache[index] = decoded
        return decoded
    }

    fun draw(canvas: Canvas, layer: Layer, alpha: Float = 1f) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(bitmap(layer), null, destination, paint)
        paint.alpha = 255
    }

    companion object {
        const val CLOUD_ATTACHMENT_FRACTION = 0.75f
        const val CLOUD_PAW_COMPRESSION_Y = 78f
    }

    /**
     * Keeps the cloud's upper edge close to the paws while its lower edge has more give.
     * The same mapping is used for the cloud shape and its lyric marks.
     */
    fun drawElasticCloud(canvas: Canvas, layer: Layer, pullX: Float, pullY: Float, compression: Float) {
        canvas.drawBitmapMesh(bitmap(layer), 8, 8, cloudMesh(pullX, pullY, compression), 0, null, 0, paint)
    }

    internal fun cloudMesh(pullX: Float, pullY: Float, compression: Float): FloatArray {
        val squash = compression.coerceIn(0f, 0.09f)
        for (row in 0..8) {
            val y = row * 64f
            val depth = ((y - 276f) / 200f).coerceIn(0f, 1f)
            val followX = CLOUD_ATTACHMENT_FRACTION + (0.35f * depth)
            val followY = CLOUD_ATTACHMENT_FRACTION + (0.30f * depth)
            for (col in 0..8) {
                val x = col * 64f
                val i = ((row * 9) + col) * 2
                mesh[i] = 261f + ((x - 261f) * (1f + (0.16f * squash))) + (pullX * followX)
                mesh[i + 1] = 354f + ((y - 354f) * (1f - squash)) + (pullY * followY)
            }
        }
        return mesh
    }

    /** Maps the same hanging-body texture into the supplied ground rig; no pose crossfade. */
    fun drawGroundBody(canvas: Canvas, groundMix: Float, compression: Float, alpha: Float = 1f) {
        val mix = groundMix.coerceIn(0f, 1f)
        val q = compression.coerceIn(0f, 1f)
        for (row in 0..32) {
            val y = row * 16f
            val baseY = if (y <= 260f) y else 260f + (((y - 260f) * 110f) / 150f)
            val pressY = if (y <= 260f) 32f else (32f * (410f - y)) / 150f
            val t = ((y - 240f) / 100f).coerceIn(0f, 1f)
            val belly = (t * t) * (3f - (2f * t))
            val widening = (0.025f + (0.12f * q)) * belly
            for (col in 0..16) {
                val x = col * 32f
                val index = ((row * 17) + col) * 2
                groundMesh[index] = x + ((251f - x) * (-widening * mix))
                groundMesh[index + 1] = y + (((baseY - y) + (q * pressY)) * mix)
            }
        }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmapMesh(bitmap(Layer.HANG), 16, 32, groundMesh, 0, null, 0, paint)
        paint.alpha = 255
    }

    /** One paw texture follows both the airborne and ground anchors without doubled outlines. */
    fun drawGroundPaws(canvas: Canvas, groundMix: Float, compression: Float, alpha: Float = 1f) {
        val mix = groundMix.coerceIn(0f, 1f)
        val q = compression.coerceIn(0f, 1f) * mix
        val leftAngle = Math.toRadians(((-12f * (1f - mix)) - (12f * q)).toDouble())
        val rightAngle = -leftAngle
        val leftCos = cos(leftAngle).toFloat()
        val leftSin = sin(leftAngle).toFloat()
        val rightCos = cos(rightAngle).toFloat()
        val rightSin = sin(rightAngle).toFloat()
        for (row in 0..32) {
            val y = row * 16f
            for (col in 0..32) {
                val x = col * 16f
                val index = ((row * 33) + col) * 2
                val left = x < 251f
                val sourceX = if (left) 193f else 309f
                val targetX = if (left) 196f - (3f * mix) - (14f * q) else 308f + mix + (14f * q)
                val targetY = if (left) 294f + (60f * mix) + (3f * q) else 290f + (64f * mix) + (3f * q)
                val cosine = if (left) leftCos else rightCos
                val sine = if (left) leftSin else rightSin
                val localX = (x - sourceX) * (1f + (0.1f * q))
                val localY = (y - 354f) * (1f - (0.32f * q))
                pawMesh[index] = (targetX + (localX * cosine)) - (localY * sine)
                pawMesh[index + 1] = targetY + (localX * sine) + (localY * cosine)
            }
        }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmapMesh(bitmap(Layer.GROUND_REST_PAWS), 32, 32, pawMesh, 0, null, 0, paint)
        paint.alpha = 255
    }

    fun release() {
        for (index in cache.indices) {
            cache[index]?.recycle()
            cache[index] = null
        }
    }

    /** Pulls the lower half down while the head remains anchored to the grabbing hand. */
    fun drawSoftBody(canvas: Canvas, lift: Float, alpha: Float = 1f) {
        val t = lift.coerceIn(0f, 1f)
        for (row in 0..8) {
            val y = row * 64f
            val weight = ((y - 165f) / 275f).coerceIn(0f, 1f)
            for (col in 0..8) {
                val x = col * 64f
                val i = ((row * 9) + col) * 2
                mesh[i] = 256f + ((x - 256f) * (1f - ((0.18f * t) * weight)))
                mesh[i + 1] = y + ((105f * t) * weight)
            }
        }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmapMesh(bitmap(Layer.REST), 8, 8, mesh, 0, null, 0, paint)
        paint.alpha = 255
    }
}
