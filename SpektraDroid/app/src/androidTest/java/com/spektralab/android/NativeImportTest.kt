package com.spektralab.android

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.android.engine.SpektraNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import org.junit.Assume.assumeTrue

/** Real Android JNI regressions: full precision, common encodings and lifetime. */
@RunWith(AndroidJUnit4::class)
class NativeImportTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String): File = File(context.cacheDir, "test-$name").also { file ->
        instrumentation.context.assets.open("import/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun commonTiffCompressionAndOrientationAgree() {
        var reference: Bitmap? = null
        try {
            for (compression in listOf(1, 5, 8, 32773)) {
                val file = fixture("rgb8-$compression.tif")
                try {
                    assertArrayEquals(intArrayOf(2, 3), SpektraNative.imageInfo(file.path))
                    SpektraNative.loadLinear(file.path, 10).use { source ->
                        assertEquals(2, source.width)
                        assertEquals(3, source.height)
                        val bitmap = source.bitmap()
                        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
                        if (reference == null) reference = bitmap else {
                            assertTrue("TIFF compression $compression", reference!!.sameAs(bitmap))
                            bitmap.recycle()
                        }
                    }
                } finally { file.delete() }
            }
        } finally { reference?.recycle() }
    }

    @Test fun adjacent16BitCodesRemainDistinctThroughNativeScaling() {
        val file = fixture("precision16.tif")
        try {
            SpektraNative.loadLinear(file.path, 16).use { source ->
                val a = source.pixel(0, 0)
                val b = source.pixel(1, 0)
                for (c in 0..2) {
                    assertTrue("Adjacent 16-bit codes must survive in float RGB", b[c] > a[c])
                    assertEquals(1f / 65535f, b[c] - a[c], 0.0000002f)
                }
                source.bitmap().also { bitmap ->
                    assertEquals("Display rounds these codes together", bitmap.getPixel(0, 0), bitmap.getPixel(1, 0))
                    bitmap.recycle()
                }
                source.scaled(1).use { small ->
                    assertEquals((a[0] + b[0]) / 2, small.pixel(0, 0)[0], 0.0000002f)
                }
            }
        } finally { file.delete() }
    }

    @Test fun floatHighlightsReachFilmBeforeDisplayClipsThem() {
        assertNull(SpektraNative.init(context.assets))
        val file = fixture("float-highlight.tif")
        val handle = SpektraNative.create()
        try {
            SpektraNative.loadLinear(file.path, 16).use { source ->
                assertTrue("Above-white samples must be preserved", source.pixel(1, 0).all { it > 3.99f })
                val p = EditState(autoExposure = false, exposureEv = -4f, grainActive = false, glareActive = false).toNative(1)
                val original = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val clipped = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val display = source.bitmap()
                try {
                    SpektraNative.setWanted(handle, 1)
                    assertNull(SpektraNative.renderLinear(handle, source, original, p, 1))
                    assertNull(SpektraNative.render(handle, display, -1, clipped, p, 1))
                    assertFalse("Film must see the highlights lost by an 8-bit Bitmap", original.sameAs(clipped))
                } finally { original.recycle(); clipped.recycle(); display.recycle() }
            }
        } finally { SpektraNative.destroy(handle); file.delete() }
    }

    @Test fun bigEndian16BitGreyAndSourceCloseAreSafe() {
        val file = fixture("grey16be.tif")
        try {
            val source = SpektraNative.loadLinear(file.path, 10)
            assertTrue(source.pixel(0, 0).all { it == 0f })
            assertTrue(source.pixel(1, 0).all { it > .99f })
            source.close(); source.close()
            assertThrows(IllegalStateException::class.java) { source.pixel(0, 0) }
        } finally { file.delete() }
    }

    @Test fun dngDecodesSensorSamplesAndFeedsFilm() {
        assertNull(SpektraNative.init(context.assets))
        val file = fixture("synthetic.dng")
        val handle = SpektraNative.create()
        try {
            SpektraNative.loadLinear(file.path, 32).use { source ->
                assertEquals(32, maxOf(source.width, source.height))
                assertTrue(source.pixel(source.width - 4, source.height / 2)[1] > source.pixel(4, source.height / 2)[1])
                val params = EditState(grainActive = false, glareActive = false,
                    filmEdge = FilmEdgeSettings(active = true, cameraSeed = 42)).toNative(1)
                val size = SpektraNative.outputSize(params, source.width, source.height)!!
                val target = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
                try {
                    SpektraNative.setWanted(handle, 1)
                    assertNull(SpektraNative.renderLinear(handle, source, target, params, 1))
                } finally { target.recycle() }
            }
        } finally { SpektraNative.destroy(handle); file.delete() }
    }

    @Test fun boundedRawDecodeMatchesFullPrecisionAreaScaling() {
        val file = fixture("synthetic.dng")
        try {
            SpektraNative.loadLinear(file.path, 8192).use { full ->
                // Noninteger scale checks fractional edge coverage, with RAW
                // codes kept above Bitmap precision on both routes.
                full.scaled(19).use { reference ->
                    SpektraNative.loadLinear(file.path, 19).use { bounded ->
                        assertEquals(reference.width, bounded.width)
                        assertEquals(reference.height, bounded.height)
                        for (y in 0 until bounded.height) for (x in 0 until bounded.width) {
                            assertArrayEquals(reference.pixel(x, y), bounded.pixel(x, y), 0.000002f)
                        }
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun malformedRawReturnsAnErrorAndNextImportStillWorks() {
        val bad = File(context.cacheDir, "corrupt.cr3").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        try { assertThrows(IOException::class.java) { SpektraNative.loadLinear(bad.path, 100) } }
        finally { bad.delete() }
        val valid = fixture("precision16.tif")
        try { assertNotNull(PhotoIO.decode(context, Uri.fromFile(valid), 20)) }
        finally { valid.delete() }
    }

    /** Optional external fixtures are pushed only for this developer's test run. */
    @Test fun availableCameraRawFixturesDecodeAtPreviewAndExportTiers() {
        val dir = InstrumentationRegistry.getArguments().getString("rawFixtureDir")?.let(::File)
            ?: File(context.getExternalFilesDir(null), "raw-fixtures")
        val samples = dir.listFiles()?.filter { it.extension.lowercase() in listOf("arw", "nef", "cr2", "cr3", "dng") }.orEmpty().sortedBy { it.name }
        val required = InstrumentationRegistry.getArguments().getString("rawFixtureCount")?.toInt()
        Log.i("SpektraImportTest", "External fixtures dir=$dir readable=${dir.canRead()} count=${samples.size}")
        if (required != null) assertEquals("All requested camera fixtures must be readable", required, samples.size)
        assumeTrue("Push real camera RAW fixtures before running this test", samples.isNotEmpty())
        assertNull(SpektraNative.init(context.assets))
        val failures = mutableListOf<String>()
        for (file in samples) {
            val started = SystemClock.elapsedRealtime()
            Log.i("SpektraImportTest", "BEGIN ${file.name} bytes=${file.length()}")
            try {
                SpektraNative.loadLinear(file.path, 640).use { preview ->
                    val previewMs = SystemClock.elapsedRealtime() - started
                    SpektraNative.loadLinear(file.path, 1024).use { export ->
                        val decodeMs = SystemClock.elapsedRealtime() - started - previewMs
                        assertTrue(preview.width > 0 && export.width >= preview.width)
                        assertEquals(640, maxOf(preview.width, preview.height))
                        assertEquals(1024, maxOf(export.width, export.height))
                        val values = (1..9).flatMap { y -> (1..9).flatMap { x ->
                            export.pixel(export.width * x / 10, export.height * y / 10).toList()
                        } }
                        assertTrue("Pixels must be finite", values.all { it.isFinite() })
                        assertTrue("Decoded image must contain scene detail", values.max() - values.min() > 0.001f)
                        val handle = SpektraNative.create()
                        val target = Bitmap.createBitmap(export.width, export.height, Bitmap.Config.ARGB_8888)
                        try {
                            SpektraNative.setWanted(handle, 1)
                            val params = EditState(grainActive = false, glareActive = false).toNative(1)
                            assertNull(SpektraNative.renderLinear(handle, export, target, params, 1))
                            File(context.getExternalFilesDir(null), "raw-results").also { it.mkdirs() }
                                .resolve("${file.name}.jpg").outputStream().use {
                                    assertTrue(target.compress(Bitmap.CompressFormat.JPEG, 90, it))
                                }
                        } finally { target.recycle(); SpektraNative.destroy(handle) }
                        Log.i("SpektraImportTest", "PASS ${file.name}: preview=${preview.width}x${preview.height} ${previewMs}ms, export=${export.width}x${export.height} ${decodeMs}ms, float film render + JPEG OK")
                    }
                }
            } catch (failure: Exception) {
                failures += "${file.name}: ${failure.message}"
                Log.e("SpektraImportTest", "FAIL ${file.name}", failure)
            } catch (failure: AssertionError) {
                failures += "${file.name}: ${failure.message}"
                Log.e("SpektraImportTest", "FAIL ${file.name}", failure)
            }
        }
        Log.i("SpektraImportTest", "External camera RAW fixtures tested=${samples.size} failures=${failures.size}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
