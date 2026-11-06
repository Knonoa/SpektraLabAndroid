package com.spektralab.android.engine

import android.content.res.AssetManager
import android.graphics.Bitmap

/** Mirror of the C++ RenderParams; field names are read via JNI. */
class NativeParams(
    @JvmField var filmStock: String,
    @JvmField var printStock: String,
    @JvmField var exposureEv: Float,
    @JvmField var yFilterShift: Float,
    @JvmField var mFilterShift: Float,
    @JvmField var cFilterShift: Float,
    @JvmField var printExposure: Float,
    @JvmField var filmFormatMm: Float,
    @JvmField var grainAmount: Float,
    @JvmField var halationAmount: Float,
    @JvmField var scatterAmount: Float,
    @JvmField var autoExposure: Boolean,
)

object SpektraNative {
    init {
        System.loadLibrary("spektra")
    }

    fun loadEngineData(assets: AssetManager): Boolean =
        nativeLoadEngineData(assets)

    /** flat array [stock0, name0, stock1, name1, ...] */
    fun listFilms(): Array<String> = nativeListFilms()
    fun listPapers(): Array<String> = nativeListPapers()

    /** Renders bitmapIn into bitmapOut (both ARGB_8888, same size). */
    fun render(bitmapIn: Bitmap, bitmapOut: Bitmap, params: NativeParams): Boolean =
        nativeRender(bitmapIn, bitmapOut, params)

    private external fun nativeLoadEngineData(assets: AssetManager): Boolean
    private external fun nativeListFilms(): Array<String>
    private external fun nativeListPapers(): Array<String>
    private external fun nativeRender(
        bitmapIn: Bitmap, bitmapOut: Bitmap, params: NativeParams,
    ): Boolean
}
