package com.spektralab.android.tether

import android.graphics.Bitmap
import com.spektralab.android.EditState
import com.spektralab.android.engine.SpektraNative
import kotlin.math.roundToInt

/** A bounded 17³ colour LUT sampled from the photo engine. Spatial effects stay in photo processing. */
class MonitorLook(state: EditState) {
    private val n = 17
    private val table = IntArray(n*n*n)
    init {
        val input = Bitmap.createBitmap(n*n, n, Bitmap.Config.ARGB_8888)
        val colors = IntArray(table.size) { i ->
            val r = i % n; val g = i / n % n; val b = i / (n*n)
            (0xff shl 24) or ((r*255/16) shl 16) or ((g*255/16) shl 8) or (b*255/16)
        }
        input.setPixels(colors, 0, n*n, 0, 0, n*n, n)
        val output = Bitmap.createBitmap(n*n, n, Bitmap.Config.ARGB_8888)
        val handle = SpektraNative.create()
        try {
            val p = state.copy(autoExposure = false, halationActive = false, grainActive = false,
                glareActive = false, couplersActive = false, sharpen = false,
                filmEdge = state.filmEdge.copy(active = false), dateBack = state.dateBack.copy(active = false)).toNative(1)
            SpektraNative.setWanted(handle, 1)
            val error = SpektraNative.render(handle, input, 1, output, p, 1)
            check(error == null) { error.orEmpty() }
            output.getPixels(table, 0, n*n, 0, 0, n*n, n)
        } finally { SpektraNative.destroy(handle); input.recycle(); output.recycle() }
    }
    fun apply(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        SpektraNative.applyMonitorLut(source, out, table)
        return out
    }
}
