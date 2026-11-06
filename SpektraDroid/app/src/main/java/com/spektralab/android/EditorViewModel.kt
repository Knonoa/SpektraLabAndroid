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
)

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
            engineReady.value = true
            startRenderLoop()
            if (photo.value != null) requestRender(RenderMode.DRAFT_THEN_FULL)
        }
    }

    // ---------------------------------------------------------------- photo

    fun loadPhoto(uri: Uri) {
        val ctx = getApplication<Application>()
        loadingPhoto.value = true
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val info = PhotoIO.info(ctx, uri)
                    val preview = PhotoIO.decode(ctx, uri, PREVIEW_EDGE) ?: return@runCatching null
                    val draft = PhotoIO.scaled(preview, DRAFT_EDGE)
                    Photo(
                        uri = uri,
                        name = info?.name ?: "photo",
                        id = ++photoCounter,
                        fullWidth = info?.width ?: preview.width,
                        fullHeight = info?.height ?: preview.height,
                        preview = preview,
                        draft = draft,
                    )
                }.getOrNull()
            }
            loadingPhoto.value = false
            if (loaded == null) {
                _messages.tryEmit(UiMessage.Text("无法读取这张照片"))
                return@launch
            }
            photo.value = loaded
            result.value = null
            lastPublished = token.get()
            requestRender(RenderMode.DRAFT_THEN_FULL)
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
        EditState(filmStock = it.filmStock, printStock = it.printStock, scanFilm = it.scanFilm, format = it.format)
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
                    val params = r.state.toNative(seed = r.photo.id * 7919L)
                    if (r.mode != RenderMode.FULL) {
                        draftInFlight = true
                        try {
                            renderTier(draftHandle, r.photo.draft, r.photo.id * 2 + 1, params, r.token, draft = true)
                        } finally {
                            draftInFlight = false
                        }
                    }
                    if (r.mode != RenderMode.DRAFT && token.get() == r.token) {
                        renderTier(previewHandle, r.photo.preview, r.photo.id * 2, params, r.token, draft = false)
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
    ) {
        val out = try {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            _messages.tryEmit(UiMessage.Text("内存不足"))
            return
        }
        val start = SystemClock.elapsedRealtime()
        val err = SpektraNative.render(handle, src, sourceId, out, params, t)
        when {
            // a superseded draft still shows (live feedback while dragging); a
            // full render only when it is the newest request
            err == null -> if ((token.get() == t || draft) && t > lastPublished) {
                lastPublished = t
                result.value = RenderResult(out, draft, SystemClock.elapsedRealtime() - start)
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
                    val bmp = withContext(Dispatchers.IO) { PhotoIO.decode(ctx, p.uri, edge) }
                        ?: throw IllegalStateException("无法读取原图")
                    val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    val err = withContext(Dispatchers.Default) {
                        val h = SpektraNative.create()
                        exportHandle = h
                        try {
                            SpektraNative.setWanted(h, 1L)
                            SpektraNative.render(h, bmp, -1L, out, s.toNative(seed = p.id * 7919L), 1L)
                        } finally {
                            exportHandle = 0L
                            SpektraNative.destroy(h)
                        }
                    }
                    bmp.recycle()
                    when (err) {
                        null -> savedUri = withContext(Dispatchers.IO) {
                            PhotoIO.saveJpeg(ctx, out, exportName(p, s))
                        }.also { out.recycle() }
                        "cancelled" -> { out.recycle(); return@launch }
                        "out of memory" -> {
                            out.recycle()
                            if (edge <= 1600) throw IllegalStateException("内存不足")
                            edge = (edge * 0.7f).toInt()
                        }
                        else -> { out.recycle(); throw IllegalStateException(err) }
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

    private fun exportName(p: Photo, s: EditState): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = p.name.substringBeforeLast('.').take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "${base}_${s.filmStock}_$stamp"
    }

    override fun onCleared() {
        token.incrementAndGet()
        val handles = listOf(previewHandle, draftHandle)
        handles.forEach { if (it != 0L) SpektraNative.setWanted(it, -1L) }
        renderDispatcher.executor.execute {
            handles.forEach { if (it != 0L) SpektraNative.destroy(it) }
        }
        renderDispatcher.close()
        super.onCleared()
    }
}
