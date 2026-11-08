package com.spektralab.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.android.engine.SpektraNative
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, mandatory-fixture full image validation. Select explicitly on a high-memory emulator. */
@RunWith(AndroidJUnit4::class)
class FullRawExportTest {
    @Test fun realSonyRawKeepsOriginalDimensionsThroughFilmAndJpeg() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        val fixturePath = args.getString("fullRawDir")
        assumeTrue("Opt-in full RAW run: set fullRawDir and fullRawCount", fixturePath != null)
        val dir=File(requireNotNull(fixturePath))
        val expected=requireNotNull(args.getString("fullRawCount")).toInt()
        val samples=dir.listFiles()?.filter { it.extension.equals("arw",true) }?.sortedBy { it.name }.orEmpty()
        assertEquals(expected,samples.size)
        assertTrue(samples.isNotEmpty())
        assertNull(SpektraNative.init(context.assets))
        val output=File(context.cacheDir,"full-raw-results").apply { mkdirs() }
        for(file in samples) {
            val before=SystemClock.elapsedRealtime()
            val dimensions=SpektraNative.imageInfo(file.path)
            val estimate=FullSizeExport.estimatedBytes(dimensions[0],dimensions[1],dimensions[0],dimensions[1])
            FullSizeExport.checkMemory(context,estimate)
            Log.i("FullRawTest","BEGIN ${file.name} source=${dimensions[0]}x${dimensions[1]} estimatedBytes=$estimate")
            SpektraNative.loadLinear(file.path,0).use { source ->
                assertArrayEquals(dimensions,intArrayOf(source.width,source.height))
                val decoded=SystemClock.elapsedRealtime()
                val out=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
                val handle=SpektraNative.create()
                try {
                    // Product defaults: colour, spatial effects, grain and sharpening at original pixels.
                    val params=EditState().toNative(123)
                    assertArrayEquals(dimensions,SpektraNative.outputSize(params,source.width,source.height))
                    SpektraNative.setWanted(handle,1)
                    assertNull(SpektraNative.renderLinear(handle,source,out,params,1))
                    val jpeg=File(output,"${file.name}.jpg")
                    jpeg.outputStream().use { assertTrue(out.compress(Bitmap.CompressFormat.JPEG,95,it)) }
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(jpeg.path,bounds)
                    assertArrayEquals(dimensions,intArrayOf(bounds.outWidth,bounds.outHeight))
                    assertTrue(jpeg.length()>10000)
                    Log.i("FullRawTest","PASS ${file.name} full=${source.width}x${source.height} decodeMs=${decoded-before} totalMs=${SystemClock.elapsedRealtime()-before} jpeg=${jpeg.length()} nativeHeap=${Debug.getNativeHeapAllocatedSize()}")
                } finally { SpektraNative.destroy(handle);out.recycle() }
            }
            System.gc()
        }
    }
}
