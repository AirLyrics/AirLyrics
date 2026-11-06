package com.andsi.airlyrics.ui.pages.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MascotArtTest {
    @Test fun allInteractiveLayersDecodeOnTheSharedCanvas() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val art = MascotArt(context)
        for (layer in MascotArt.Layer.entries) {
            val bitmap = art.bitmap(layer)
            assertEquals(layer.file, 512, bitmap.width)
            assertEquals(layer.file, 512, bitmap.height)
            val encoded = context.assets.open("${layer.file}.png.b64").use { it.readBytes() }
            val png = Base64.getDecoder().decode(encoded)
            assertEquals(layer.file, 0x89, png[0].toInt() and 0xff)
            assertEquals(layer.file, 6, png[25].toInt() and 0xff) // PNG RGBA color type
            assertTrue(layer.file, png.size > 100)
        }
    }

    @Test fun cloudTopTracksPawsWhileLowerEdgeHasMoreGive() {
        val art = MascotArt(ApplicationProvider.getApplicationContext())
        val rest = art.cloudMesh(0f, 0f, 0f).copyOf()
        val pulled = art.cloudMesh(40f, 24f, 0.07f)
        val upper = ((4 * 9) + 3) * 2 // x=192, y=256, next to the left paw
        val lower = ((7 * 9) + 3) * 2
        val pawX = 40f * MascotArt.CLOUD_ATTACHMENT_FRACTION
        val pawY = (24f * MascotArt.CLOUD_ATTACHMENT_FRACTION) +
            (0.07f * MascotArt.CLOUD_PAW_COMPRESSION_Y)
        assertEquals(pawX, pulled[upper] - rest[upper], 2f)
        assertEquals(pawY, pulled[upper + 1] - rest[upper + 1], 2f)
        assertTrue((pulled[lower] - rest[lower]) > (pulled[upper] - rest[upper]))
        assertEquals(1f, cloudSpringRemaining(0f), 0.001f)
        assertTrue(cloudSpringRemaining(0.3f) < 0f)
        assertEquals(0f, cloudSpringRemaining(1f), 0f)
    }

    @Test fun groundMeshKeepsTheSuppliedBodySilhouettes() {
        val art = MascotArt(ApplicationProvider.getApplicationContext())
        for ((compression, layer) in listOf(
            0f to MascotArt.Layer.GROUND_REST_BODY,
            1f to MascotArt.Layer.GROUND_SQUASH_BODY,
        )) {
            val generated = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val reference = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            art.drawGroundBody(Canvas(generated), 1f, compression)
            art.draw(Canvas(reference), layer)
            var intersection = 0
            var union = 0
            for (y in 80 until 420 step 4) for (x in 55 until 455 step 4) {
                val a = Color.alpha(generated.getPixel(x, y)) > 64
                val b = Color.alpha(reference.getPixel(x, y)) > 64
                if (a || b) union++
                if (a && b) intersection++
            }
            val overlap = intersection.toFloat() / union
            assertTrue("body compression=$compression overlap=$overlap", overlap > 0.80f)
        }
    }

    @Test fun deformedBodyAndLayeredCloudRender() {
        val art = MascotArt(ApplicationProvider.getApplicationContext())
        val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        art.drawSoftBody(canvas, 0.65f)
        art.draw(canvas, MascotArt.Layer.HANG, 0.3f)
        art.draw(canvas, MascotArt.Layer.FACE_SURPRISE)
        assertTrue(output.getPixel(256, 220) != Color.TRANSPARENT)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        art.drawElasticCloud(canvas, MascotArt.Layer.CLOUD_FRONT, 32f, 18f, 0.06f)
        art.drawElasticCloud(canvas, MascotArt.Layer.CLOUD_LYRICS, 32f, 18f, 0.06f)
        assertTrue(output.getPixel(256, 355) != Color.TRANSPARENT)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        art.drawGroundBody(canvas, 1f, 0f)
        art.drawGroundPaws(canvas, 1f, 0f)
        assertTrue(output.getPixel(251, 300) != Color.TRANSPARENT)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        art.drawGroundBody(canvas, 1f, 1f)
        art.drawGroundPaws(canvas, 1f, 1f)
        assertTrue(output.getPixel(251, 300) != Color.TRANSPARENT)
    }
}
