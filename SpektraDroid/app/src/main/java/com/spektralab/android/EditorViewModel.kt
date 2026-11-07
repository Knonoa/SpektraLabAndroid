package com.spektralab.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spektralab.android.engine.Catalog
import com.spektralab.android.engine.SpektraNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The loaded photo at the two interactive tiers. */
class Photo(
    val uri: Uri,
    val name: String,
    val id: Long,
    val fullWidth: Int,
    val fullHeight: Int,
    /** sRGB preview tier (long edge [EditorViewModel.PREVIEW_EDGE]) -- the "before" image */
    val preview: Bitmap,
    /** small tier rendered while a slider is being dragged */
    val draft: Bitmap,
    /** The EXIF the date back prints and the gate's f-number (Film Edge) */
    val shooting: ShootingData = ShootingData(),
    /** Film Edge's per-frame seed (the advance and the scan): stable for the same file */
    val frameSeed: Int = 1,
    /** Display bitmaps are never the film input for RAW/TIFF. */
    val linearPreview: SpektraNative.LinearImage? = null,
    val linearDraft: SpektraNative.LinearImage? = null,
    val nativeSourcePath: String? = null,
) {
    fun closeLinear() {
        linearDraft?.close()
        linearPreview?.close()
    }
}

data class RenderResult(val bitmap: Bitmap, val draft: Boolean, val millis: Long)

sealed interface UiMessage {
    data class Text(val text: String) : UiMessage
    data class Saved(val uri: Uri) : UiMessage
}

enum class ExportSize(val label: String, val maxEdge: Int) {
    S2048("2048 px", 2048),
    S3072("3072 px", 3072),
    S4096("4096 px", 4096),
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val PREVIEW_EDGE = 1600
        const val DRAFT_EDGE = 640
        private const val PREFS = "spektralab"
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(EditState.load(prefs))
    val state: StateFlow<EditState> = _state

    val catalog = MutableStateFlow(Catalog.EMPTY)
    val engineReady = MutableStateFlow(false)
    val engineError = MutableStateFlow<String?>(null)
    val photo = MutableStateFlow<Photo?>(null)
    val loadingPhoto = MutableStateFlow(false)
    val result = MutableStateFlow<RenderResult?>(null)
    val rendering = MutableStateFlow(false)
    val exporting = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages

    // One render thread: requests queue behind each other, and each new one
    // publishes its token so the native render in flight aborts early.
    private val renderDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spektra-render").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()
    private val token = AtomicLong(0)
    private var previewHandle = 0L
    private var draftHandle = 0L
    @Volatile private var exportHandle = 0L
    private var exportJob: Job? = null
    private var saveJob: Job? = null
    private var photoCounter = 0L
    private var loadJob: Job? = null

    init {
        viewModelScope.launch(renderDispatcher) {
            val err = SpektraNative.init(app.assets)
            if (err != null) {
                engineError.value = err
                return@launch
            }
            catalog.value = SpektraNative.catalog()
            previewHandle = SpektraNative.create()
            draftHandle = SpektraNative.create()
            // a stock that no longer exists in the catalogue falls back to defaults
            val s = _state.value
            val cat = catalog.value
            if (cat.film(s.filmStock) == null || (!s.scanFilm && cat.paper(s.printStock) == null)) {
                _state.value = EditState()
            }
            if (_state.value.filmEdge.cameraSeed <= 0) {
                val body = 1 + kotlin.random.Random.nextInt(999)
                _state.value = _state.value.let { it.copy(filmEdge = it.filmEdge.copy(cameraSeed = body)) }
                persist()
            }
            engineReady.value = true
            startRenderLoop()
            if (photo.value != null) requestRender(RenderMode.DRAFT_THEN_FULL)
        }
    }

    // ---------------------------------------------------------------- photo

    fun loadPhoto(uri: Uri) {
        if (exporting.value) {
            _messages.tryEmit(UiMessage.Text("请等待当前照片保存完成"))
            return
        }
        val ctx = getApplication<Application>()
        loadJob?.cancel()
        loadingPhoto.value = true
        loadJob = viewModelScope.launch {
            var loaded: Photo? = null
            var published = false
            try {
                withContext(Dispatchers.IO) {
                    var linear: SpektraNative.LinearImage? = null
                    var linearDraft: SpektraNative.LinearImage? = null
                    try {
                        val info = PhotoIO.info(ctx, uri)
                        val nativePath = PhotoIO.nativePath(ctx, uri)
                        linear = nativePath?.let { SpektraNative.loadLinear(it, PREVIEW_EDGE) }
                        val preview = linear?.bitmap() ?: PhotoIO.decode(ctx, uri, PREVIEW_EDGE)
                            ?: error("无法读取这张照片")
                        linearDraft = linear?.scaled(DRAFT_EDGE)
                        val draft = linearDraft?.bitmap() ?: PhotoIO.scaled(preview, DRAFT_EDGE)
                        loaded = Photo(
                            uri = uri, name = info?.name ?: "photo", id = ++photoCounter,
                            fullWidth = info?.width ?: preview.width,
                            fullHeight = info?.height ?: preview.height,
                            preview = preview, draft = draft,
                            shooting = ShootingData.read(ctx, uri),
                            frameSeed = 1 + (uri.toString().hashCode() and 0x7fffffff) % 1_000_000,
                            linearPreview = linear, linearDraft = linearDraft, nativeSourcePath = nativePath,
                        )
                    } catch (t: Throwable) {
                        linearDraft?.close(); linear?.close()
                        throw t
                    }
                }
                val previous = photo.value
                photo.value = loaded
                published = true
                // Serialize retirement with any render still using the previous image.
                renderDispatcher.executor.execute { previous?.closeLinear() }
                result.value = null
                lastPublished = token.get()
                requestRender(RenderMode.DRAFT_THEN_FULL)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _messages.tryEmit(UiMessage.Text("图片过大，内存不足"))
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage.Text("无法读取照片：${e.message}"))
            } finally {
                if (!published) loaded?.closeLinear()
                loadingPhoto.value = false
            }
        }
    }

    // ---------------------------------------------------------------- edits

    /**
     * Applies an edit. [interactive] = a slider is being dragged: only the
     * draft tier renders until [commit] is called on release.
     */
    fun update(interactive: Boolean = false, transform: (EditState) -> EditState) {
        val old = _state.value
        val new = transform(old)
        if (new == old) return
        _state.value = new
        persist()
        val mode = when {
            interactive -> RenderMode.DRAFT
            new.printSideOnlyChangeFrom(old) -> RenderMode.FULL   // negative is cached: fast
            else -> RenderMode.DRAFT_THEN_FULL
        }
        requestRender(mode)
    }

    /**
     * Slider released: render the full preview tier. The draft already in
     * flight is allowed to land first -- it is the fastest feedback there is.
     */
    fun commit() = requestRender(RenderMode.FULL, keepDraft = true)

    fun selectFilm(stock: String) {
        val film = catalog.value.film(stock) ?: return
        update { s ->
            val papers = catalog.value
            val target = film.targetPrint.takeIf { it.isNotEmpty() && papers.paper(it) != null }
            s.copy(
                filmStock = stock,
                printStock = target ?: s.printStock,
                // slide films are viewed directly (SpektraLab's "Positive" row)
                scanFilm = film.positive,
            )
        }
    }

    fun selectPaper(stock: String) = update { it.copy(printStock = stock, scanFilm = false) }
    fun selectScanFilm() = update { it.copy(scanFilm = true) }

    /** Back to the defaults, keeping the chosen stocks and format. */
    fun resetAdjustments() = update {
        EditState(
            filmStock = it.filmStock, printStock = it.printStock, scanFilm = it.scanFilm, format = it.format,
            filmEdge = it.filmEdge, dateBack = it.dateBack,
        )
    }

    private fun persist() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            _state.value.save(prefs)
        }
    }

    // ---------------------------------------------------------------- render

    private enum class RenderMode { DRAFT, FULL, DRAFT_THEN_FULL }

    private class Request(val token: Long, val mode: RenderMode, val state: EditState, val photo: Photo)

    // Conflated: while one render runs, only the newest request waits.
    private val requests = Channel<Request>(Channel.CONFLATED)
    @Volatile private var draftInFlight = false
    @Volatile private var lastPublished = 0L

    private fun startRenderLoop() {
        viewModelScope.launch(renderDispatcher) {
            for (r in requests) {
                if (token.get() != r.token) continue          // superseded while queued
                try {
                    SpektraNative.setWanted(previewHandle, r.token)
                    SpektraNative.setWanted(draftHandle, r.token)
                    val params = r.state.toNative(seed = r.photo.id * 7919L, frame = frameOf(r.state, r.photo))
                    if (r.mode != RenderMode.FULL) {
                        draftInFlight = true
                        try {
                            renderTier(draftHandle, r.photo.draft, r.photo.id * 2 + 1, params, r.token, draft = true, linear = r.photo.linearDraft)
                        } finally {
                            draftInFlight = false
                        }
                    }
                    if (r.mode != RenderMode.DRAFT && token.get() == r.token) {
                        renderTier(previewHandle, r.photo.preview, r.photo.id * 2, params, r.token, draft = false, linear = r.photo.linearPreview)
                    }
                } catch (e: Exception) {
                    // never let one bad render kill the loop
                    _messages.tryEmit(UiMessage.Text("渲染失败：${e.message}"))
                }
                if (token.get() == r.token) rendering.value = false
            }
        }
    }

    private fun requestRender(mode: RenderMode, keepDraft: Boolean = false) {
        val p = photo.value ?: return
        if (!engineReady.value) return
        val t = token.incrementAndGet()
        // A slider drag must not abort the draft already in flight, or nothing
        // would ever finish while the finger moves; everything else does.
        if (!((mode == RenderMode.DRAFT || keepDraft) && draftInFlight)) {
            SpektraNative.setWanted(previewHandle, t)
            SpektraNative.setWanted(draftHandle, t)
        }
        rendering.value = true
        requests.trySend(Request(t, mode, _state.value, p))
    }

    private fun renderTier(
        handle: Long, src: Bitmap, sourceId: Long,
        params: com.spektralab.android.engine.NativeParams, t: Long, draft: Boolean,
        linear: SpektraNative.LinearImage? = null,
    ) {
        val size = SpektraNative.outputSize(params, src.width, src.height) ?: run {
            _messages.tryEmit(UiMessage.Text("胶片边框无法用于这个画幅"))
            return
        }
        val out = try {
            Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            _messages.tryEmit(UiMessage.Text("内存不足"))
            return
        }
        val start = SystemClock.elapsedRealtime()
        val err = if (linear != null) SpektraNative.renderLinear(handle, linear, out, params, t)
            else SpektraNative.render(handle, src, sourceId, out, params, t)
        when {
            // A draft is allowed to land while a newer request is being
            // prepared, so slider feedback stays responsive.  A full render
            // must always replace the draft for the same request token: both
            // tiers intentionally share `t`, and using `t > lastPublished`
            // here used to discard the full result after the draft published.
            // That left the editor showing the 640 px draft, whose
            // auto-exposure statistics can differ visibly from the final
            // preview tier (especially in ordinary, non-HDR camera scenes).
            err == null -> {
                val publish = if (draft) {
                    draft || t >= lastPublished
                } else {
                    token.get() == t
                }
                if (publish) {
                    if (!draft) lastPublished = maxOf(lastPublished, t)
                    result.value = RenderResult(out, draft, SystemClock.elapsedRealtime() - start)
                } else {
                    out.recycle()
                }
            }
            err == "cancelled" -> Unit
            else -> _messages.tryEmit(UiMessage.Text("渲染失败：$err"))
        }
    }

    // ---------------------------------------------------------------- export

    fun export(size: ExportSize) {
        val p = photo.value ?: return
        if (exporting.value) return
        val ctx = getApplication<Application>()
        val s = _state.value
        exporting.value = true
        exportJob = viewModelScope.launch {
            try {
                var edge = minOf(size.maxEdge, maxOf(p.fullWidth, p.fullHeight))
                var savedUri: Uri? = null
                while (savedUri == null) {
                    var source: SpektraNative.LinearImage? = null
                    var bmp: Bitmap? = null
                    var out: Bitmap? = null
                    val err: String?
                    try {
                        // Decode the original again at the export tier, retaining float
                        // precision through metering, film exposure and development.
                        withContext(Dispatchers.IO) {
                            if (p.nativeSourcePath != null) source = SpektraNative.loadLinear(p.nativeSourcePath, edge)
                            else bmp = PhotoIO.decode(ctx, p.uri, edge) ?: error("无法读取原图")
                        }
                        val w = source?.width ?: bmp!!.width
                        val h = source?.height ?: bmp!!.height
                        val params = s.toNative(seed = p.id * 7919L, frame = frameOf(s, p))
                        val outSize = SpektraNative.outputSize(params, w, h) ?: error("胶片边框无法用于这个画幅")
                        out = Bitmap.createBitmap(outSize[0], outSize[1], Bitmap.Config.ARGB_8888)
                        val target = out
                        err = withContext(Dispatchers.Default) {
                            val handle = SpektraNative.create()
                            exportHandle = handle
                            try {
                                SpektraNative.setWanted(handle, 1L)
                                source?.let { SpektraNative.renderLinear(handle, it, target, params, 1L) }
                                    ?: if (source == null) SpektraNative.render(handle, bmp!!, -1L, target, params, 1L) else null
                            } finally {
                                exportHandle = 0L
                                SpektraNative.destroy(handle)
                            }
                        }
                    } catch (t: Throwable) {
                        out?.recycle()
                        throw t
                    } finally {
                        source?.close()
                        bmp?.recycle()
                    }
                    val output = requireNotNull(out)
                    when (err) {
                        null -> savedUri = withContext(Dispatchers.IO) {
                            PhotoIO.saveJpeg(ctx, output, exportName(p, s))
                        }.also { output.recycle() }
                        "cancelled" -> { output.recycle(); return@launch }
                        "out of memory" -> {
                            output.recycle()
                            if (edge <= 1600) throw IllegalStateException("内存不足")
                            edge = (edge * 0.7f).toInt()
                        }
                        else -> { output.recycle(); throw IllegalStateException(err) }
                    }
                }
                _messages.tryEmit(UiMessage.Saved(savedUri!!))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // the user cancelled: no message
            } catch (e: OutOfMemoryError) {
                _messages.tryEmit(UiMessage.Text("导出失败：内存不足，请选择更小的尺寸"))
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage.Text("导出失败：${e.message}"))
            } finally {
                exporting.value = false
            }
        }
    }

    fun cancelExport() {
        val h = exportHandle
        if (h != 0L) SpektraNative.setWanted(h, -1L)
        exportJob?.cancel()
        exporting.value = false
    }

    /** The live film preview's params: the chosen film and paper, no film edge and no date (a viewfinder). */
    fun liveParams(): com.spektralab.android.engine.NativeParams =
        _state.value.copy(filmEdge = _state.value.filmEdge.copy(active = false),
            dateBack = _state.value.dateBack.copy(active = false)).toNative(seed = 1L)

    fun filmName(): String = catalog.value.film(_state.value.filmStock)?.name ?: _state.value.filmStock

    /** The frame's own contribution to the negative: its EXIF, its seed, the stock's edge print. */
    private fun frameOf(s: EditState, p: Photo) = EditState.Frame(
        shooting = p.shooting,
        frameSeed = p.frameSeed,
        edgeText = EdgeText.of(s.filmStock, catalog.value.film(s.filmStock), s.filmEdge.format),
    )

    /** Film Edge: another camera body (gate shape, its gap, where frames sit on the perforations). */
    fun newFilmEdgeBody() = update { st ->
        var body: Int
        do { body = 1 + kotlin.random.Random.nextInt(999) } while (body == st.filmEdge.cameraSeed)
        st.copy(filmEdge = st.filmEdge.copy(cameraSeed = body))
    }

    private fun exportName(p: Photo, s: EditState): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = p.name.substringBeforeLast('.').take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "${base}_${s.filmStock}_$stamp"
    }

    override fun onCleared() {
        loadJob?.cancel()
        cancelExport()
        token.incrementAndGet()
        val handles = listOf(previewHandle, draftHandle)
        handles.forEach { if (it != 0L) SpektraNative.setWanted(it, -1L) }
        renderDispatcher.executor.execute {
            handles.forEach { if (it != 0L) SpektraNative.destroy(it) }
            photo.value?.closeLinear()
        }
        renderDispatcher.close()
        super.onCleared()
    }
}
