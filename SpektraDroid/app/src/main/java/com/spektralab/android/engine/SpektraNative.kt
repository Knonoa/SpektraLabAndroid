package com.spektralab.android.engine

import android.content.res.AssetManager
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

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
    // Film Edge (RFC-032): the film outside the frame
    @JvmField val overscanActive: Boolean = false,
    @JvmField val overscanFormat: String = "135",
    @JvmField val overscanMode: String = "strip",
    @JvmField val overscanGate: String = "auto",
    @JvmField val overscanHoles: String = "white",
    @JvmField val overscanCameraSeed: Int = 1,
    @JvmField val overscanFrameSeed: Int = 1,
    @JvmField val overscanFrameNumber: Int = 0,
    @JvmField val overscanFog: Float = 1f,
    @JvmField val overscanLeaks: Float = 0f,
    @JvmField val overscanEdgeText: String = "",
    @JvmField val overscanFNumber: Float = 0f,
    // Date Back (RFC-031): the date and the shooting data, exposed from behind
    @JvmField val dateActive: Boolean = false,
    @JvmField val dateStyle: String = "lcd",
    @JvmField val dateText: String = "",
    @JvmField val dateDataText: String = "",
    @JvmField val datePlacement: String = "frame",
    @JvmField val dateCorner: String = "br",
    @JvmField val dateInsetX: Float = 3f,
    @JvmField val dateInsetY: Float = 2.4f,
    @JvmField val dateSize: Float = 1f,
    @JvmField val dateEv: Float = 3.5f,
    @JvmField val dateDataInFrame: Boolean = true,
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
     * The size [render] writes for a [w] x [h] source: the same, or with Film
     * Edge the film canvas (the frame cut to the gate, plus the film around
     * it). Null when the engine refuses the layout.
     */
    fun outputSize(params: NativeParams, w: Int, h: Int): IntArray? = nativeOutputSize(params, w, h)

    /** Full-precision native pixels. Bitmap is only a display copy. */
    class LinearImage internal constructor(id: Long) : Closeable {
        private val nativeId = AtomicLong(id)
        internal val id: Long get() = nativeId.get().also { check(it != 0L) { "Image closed" } }
        private val size = nativeLinearSize(id)
        val width: Int get() = size[0]
        val height: Int get() = size[1]
        fun bitmap(): Bitmap = nativeLinearBitmap(id)
        fun scaled(maxEdge: Int): LinearImage = LinearImage(nativeScaleLinear(id, maxEdge))
        /** For decoder diagnostics and precision regression tests. */
        fun pixel(x: Int, y: Int): FloatArray = nativeLinearPixel(id, x, y)
        override fun close() { nativeId.getAndSet(0L).takeIf { it != 0L }?.let(::nativeCloseLinear) }
    }

    fun imageInfo(path: String): IntArray = nativeImageInfo(path)
    /** maxEdge=0 decodes the complete active image, without downscaling. */
    fun loadLinear(path: String, maxEdge: Int): LinearImage = LinearImage(nativeLoadLinear(path, maxEdge))
    fun renderLinear(handle: Long, source: LinearImage, out: Bitmap, params: NativeParams, token: Long): String? =
        nativeRenderLinear(handle, source.id, out, params, token)

    /**
     * Renders [source] (ARGB_8888 sRGB) into [out] ([outputSize]). [sourceId] keys the
     * engine's linear copy and developed negative. Returns null on success,
     * "cancelled", or an error message.
     */
    fun render(
        handle: Long, source: Bitmap, sourceId: Long, out: Bitmap,
        params: NativeParams, token: Long,
    ): String? = nativeRender(handle, source, sourceId, out, params, token)

    external fun applyMonitorLut(source: Bitmap, output: Bitmap, table: IntArray)

    private external fun nativeInit(assets: AssetManager): String?
    private external fun nativeCatalog(): String
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetWanted(handle: Long, token: Long)
    private external fun nativeDropCaches(handle: Long)
    private external fun nativeOutputSize(params: NativeParams, w: Int, h: Int): IntArray?
    private external fun nativeImageInfo(path: String): IntArray
    private external fun nativeLoadLinear(path: String, maxEdge: Int): Long
    private external fun nativeScaleLinear(id: Long, maxEdge: Int): Long
    private external fun nativeLinearSize(id: Long): IntArray
    private external fun nativeLinearBitmap(id: Long): Bitmap
    private external fun nativeLinearPixel(id: Long, x: Int, y: Int): FloatArray
    private external fun nativeCloseLinear(id: Long)
    private external fun nativeRenderLinear(handle: Long, imageId: Long, out: Bitmap, params: NativeParams, token: Long): String?
    private external fun nativeRender(
        handle: Long, source: Bitmap, sourceId: Long, out: Bitmap,
        params: NativeParams, token: Long,
    ): String?
}
