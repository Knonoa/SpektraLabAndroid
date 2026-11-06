package com.spektralab.android.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Renders photos through the native spectral film engine.
 *
 * Preview renders run on a downscaled copy (fast, interactive); exports
 * re-render at full resolution.
 */
class Renderer private constructor() {
    companion object {
        const val PREVIEW_MAX_EDGE = 1440

        /** Loads engine.bin and builds the CAM16 gamut tables once. */
        fun initialize(assets: android.content.res.AssetManager): Boolean =
            SpektraNative.loadEngineData(assets)

        fun films(): List<Pair<String, String>> =
            SpektraNative.listFilms().toList().chunked(2).map { it[0] to it[1] }

        fun papers(): List<Pair<String, String>> =
            SpektraNative.listPapers().toList().chunked(2).map { it[0] to it[1] }

        /** Decodes a preview-sized sRGB bitmap from a content uri. */
        suspend fun decodePreview(context: android.content.Context, uri: Uri): Bitmap? =
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longEdge / (sample * 2) >= PREVIEW_MAX_EDGE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext null
                if (longEdge / sample > PREVIEW_MAX_EDGE) {
                    val scale = PREVIEW_MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    bmp
                }
            }

        /** Renders [source] through the engine into a new ARGB_8888 bitmap. */
        fun render(source: Bitmap, params: NativeParams): Bitmap? {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val ok = SpektraNative.render(source, out, params)
            return if (ok) out else null
        }
    }
}
