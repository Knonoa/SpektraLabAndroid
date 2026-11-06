package com.spektralab.android.engine

import android.content.res.AssetManager
import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Mirror of the C++ spk::RenderParams. Field names are read by name through
 * JNI (jni_bridge.cpp) -- keep them in sync, and keep this class out of R8
 * renaming (proguard-rules.pro).
 */
class NativeParams(
    @JvmField val filmStock: String,
    @JvmField val printStock: String,
    @JvmField val scanFilm: Boolean,
    @JvmField val exposureEv: Float,
    @JvmField val autoExposure: Boolean,
    @JvmField val filmFormatMm: Float,
    @JvmField val halationActive: Boolean,
    @JvmField val halationAmount: Float,
    @JvmField val scatterAmount: Float,
    @JvmField val couplersActive: Boolean,
    @JvmField val couplersAmount: Float,
    @JvmField val grainActive: Boolean,
    @JvmField val grainSublayers: Boolean,
    @JvmField val grainAmount: Float,
    @JvmField val printExposure: Float,
    @JvmField val yFilterShift: Float,
    @JvmField val mFilterShift: Float,
    @JvmField val printExposureCompensation: Boolean,
    @JvmField val glareActive: Boolean,
    @JvmField val glareAmount: Float,
    @JvmField val unsharpMask: Boolean,
    @JvmField val seed: Long,
)

data class FilmStock(
    val stock: String,
    val name: String,
    val positive: Boolean,
    val bw: Boolean,
    val cine: Boolean,
    /** the paper the profile was made for; "" for slide films */
    val targetPrint: String,
)

data class PaperStock(
    val stock: String,
    val name: String,
    val bw: Boolean,
    val cine: Boolean,
)

data class Catalog(val films: List<FilmStock>, val papers: List<PaperStock>) {
    fun film(stock: String) = films.firstOrNull { it.stock == stock }
    fun paper(stock: String) = papers.firstOrNull { it.stock == stock }

    companion object {
        val EMPTY = Catalog(emptyList(), emptyList())

        fun parse(json: String): Catalog {
            val o = JSONObject(json)
            val films = o.optJSONArray("films")?.let { a ->
                (0 until a.length()).map { i ->
                    val f = a.getJSONObject(i)
                    FilmStock(
                        stock = f.getString("stock"),
                        name = f.getString("name"),
                        positive = f.optBoolean("positive"),
                        bw = f.optBoolean("bw"),
                        cine = f.optBoolean("cine"),
                        targetPrint = f.optString("target"),
                    )
                }
            } ?: emptyList()
            val papers = o.optJSONArray("papers")?.let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    PaperStock(
                        stock = p.getString("stock"),
                        name = p.getString("name"),
                        bw = p.optBoolean("bw"),
                        cine = p.optBoolean("cine"),
                    )
                }
            } ?: emptyList()
            return Catalog(films, papers)
        }
    }
}

object SpektraNative {
    init {
        System.loadLibrary("spektra")
    }

    /** Loads engine.bin and builds the CAM16 tables once. Returns an error or null. */
    fun init(assets: AssetManager): String? = nativeInit(assets)

    fun catalog(): Catalog = Catalog.parse(nativeCatalog())

    /** A render handle with its own cached negative. 0 when the engine is not ready. */
    fun create(): Long = nativeCreate()
    fun destroy(handle: Long) = nativeDestroy(handle)

    /** Publishes the newest request token; a running render with another token aborts. */
    fun setWanted(handle: Long, token: Long) = nativeSetWanted(handle, token)
    fun dropCaches(handle: Long) = nativeDropCaches(handle)

    /**
     * Renders [source] (ARGB_8888 sRGB) into [out] (same size). [sourceId] keys the
     * engine's linear copy and developed negative. Returns null on success,
     * "cancelled", or an error message.
     */
    fun render(
        handle: Long, source: Bitmap, sourceId: Long, out: Bitmap,
        params: NativeParams, token: Long,
    ): String? = nativeRender(handle, source, sourceId, out, params, token)

    private external fun nativeInit(assets: AssetManager): String?
    private external fun nativeCatalog(): String
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetWanted(handle: Long, token: Long)
    private external fun nativeDropCaches(handle: Long)
    private external fun nativeRender(
        handle: Long, source: Bitmap, sourceId: Long, out: Bitmap,
        params: NativeParams, token: Long,
    ): String?
}
