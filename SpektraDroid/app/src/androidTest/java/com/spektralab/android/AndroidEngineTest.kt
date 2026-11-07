package com.spektralab.android

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.android.camera.CameraInventory
import com.spektralab.android.engine.SpektraNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Exercises the real JNI bitmap boundary on Android, including expanded canvas strides. */
@RunWith(AndroidJUnit4::class)
class AndroidEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun filmCanvasAndDateSurviveJniAndCachedPrintEdits() {
        assertNull(SpektraNative.init(context.assets))
        val pixels = IntArray(240 * 160) { i ->
            val x = i % 240; val y = i / 240
            android.graphics.Color.rgb(30 + x * 200 / 240, 30 + y * 190 / 160, 80)
        }
        val source = Bitmap.createBitmap(pixels, 240, 160, Bitmap.Config.ARGB_8888)
        val base = EditState(grainActive = false, glareActive = false)
        val framed = base.copy(
            filmEdge = FilmEdgeSettings(active = true, cameraSeed = 42, frameNumber = 12),
            dateBack = DateBackSettings(active = true, withData = true),
        )
        val frame = EditState.Frame(
            shooting = ShootingData(year = 2026, month = 11, day = 7, exposureTime = 1.0 / 250, fNumber = 5.6),
            frameSeed = 17, edgeText = "KODAK PORTRA 400",
        )
        val handle = SpektraNative.create()
        val cold = SpektraNative.create()
        assertTrue(handle != 0L && cold != 0L)
        try {
            fun render(h: Long, s: EditState, id: Long = 5): Bitmap {
                val p = s.toNative(42, frame)
                val size = SpektraNative.outputSize(p, source.width, source.height)!!
                val out = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
                SpektraNative.setWanted(h, 1)
                assertNull(SpektraNative.render(h, source, id, out, p, 1))
                return out
            }
            val plain = render(handle, base)
            assertEquals(source.width, plain.width)
            val withEdge = render(handle, framed)
            assertTrue(withEdge.width != source.width || withEdge.height != source.height)
            val noDate = render(cold, framed.copy(dateBack = DateBackSettings()), -1)
            assertFalse("Date back must change real output pixels", withEdge.sameAs(noDate))
            val changedPrint = framed.copy(printBrightness = 0.7f)
            val warm = render(handle, changedPrint)
            val fresh = render(cold, changedPrint, -1)
            assertTrue("Cached negative must match a cold render", warm.sameAs(fresh))
            listOf(plain, withEdge, noDate, warm, fresh).forEach(Bitmap::recycle)
        } finally {
            source.recycle()
            SpektraNative.destroy(handle)
            SpektraNative.destroy(cold)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @Test fun reportPublicCameraAccessWithoutGuessingHiddenIds() {
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val cameras = CameraInventory.list(context)
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val bindable = provider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }
        Log.i("SpektraCameraTest", "CameraManager IDs=${manager.cameraIdList.toList()}; CameraX IDs=$bindable")
        for (c in cameras) Log.i("SpektraCameraTest", "id=${c.id} ${c.facingLabel} physical=${c.physical.map { it.id }} RAW=${c.raw} exposure=${c.exposureRange}")
        assertTrue("Inventory must contain only public camera IDs", cameras.all { it.id in manager.cameraIdList })
    }
}
