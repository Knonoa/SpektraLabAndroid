package com.spektralab.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spektralab.android.engine.NativeParams
import com.spektralab.android.engine.Renderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All edit state, bundled so a change always triggers one coherent render. */
data class EditState(
    val filmStock: String = "kodak_portra_400",
    val printStock: String = "kodak_portra_endura",
    val exposureEv: Float = 0f,
    val yFilterShift: Float = 0f,
    val mFilterShift: Float = 0f,
    val filmFormatMm: Float = 35f,
    val grainAmount: Float = 1f,
    val halationAmount: Float = 1f,
    val scatterAmount: Float = 1f,
    val printExposure: Float = 1f,
)

class EditorViewModel : ViewModel() {
    val source = MutableStateFlow<Bitmap?>(null)
    val result = MutableStateFlow<Bitmap?>(null)
    val state = MutableStateFlow(EditState())
    val rendering = MutableStateFlow(false)
    val films = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val papers = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    private var renderJob: Job? = null
    private var exportJob: Job? = null
    private var engineReady = false
    private var renderGeneration = 0L

    /** last full-resolution uri for export; null until a photo is loaded */
    private var sourceUri: Uri? = null

    fun initialize(context: Context) {
        if (films.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = Renderer.initialize(context.assets)
            if (!ok) {
                message.value = "引擎资源加载失败"
                return@launch
            }
            engineReady = true
            films.value = Renderer.films()
            papers.value = Renderer.papers()
            // default to each film's preferred paper where known
            state.value = state.value
            if (source.value != null) scheduleRender()
        }
    }

    fun loadPhoto(context: Context, uri: Uri) {
        sourceUri = uri
        viewModelScope.launch {
            val bmp = Renderer.decodePreview(context, uri)
            if (bmp == null) {
                message.value = "无法读取照片"
                return@launch
            }
            source.value = bmp
            scheduleRender()
        }
    }

    fun update(transform: (EditState) -> EditState) {
        state.value = transform(state.value)
        scheduleRender()
    }

    private fun paramsOf(s: EditState) = NativeParams(
        filmStock = s.filmStock,
        printStock = s.printStock,
        exposureEv = s.exposureEv,
        yFilterShift = s.yFilterShift,
        mFilterShift = s.mFilterShift,
        cFilterShift = 0f,
        printExposure = s.printExposure,
        filmFormatMm = s.filmFormatMm,
        grainAmount = s.grainAmount,
        halationAmount = s.halationAmount,
        scatterAmount = s.scatterAmount,
        autoExposure = true,
    )

    private fun scheduleRender() {
        val src = source.value ?: return
        if (!engineReady) return
        val generation = ++renderGeneration
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            rendering.value = true
            try {
                val s = state.value
                val out = withContext(Dispatchers.Default) {
                    val safe = src.copy(Bitmap.Config.ARGB_8888, false)
                    Renderer.render(safe, paramsOf(s))
                }
                // A cancelled/older render must never replace a newer edit.
                if (generation == renderGeneration && out != null) result.value = out
            } finally {
                if (generation == renderGeneration) rendering.value = false
            }
        }
    }

    /** Re-render at full resolution and save as JPEG into Pictures/SpektraDroid. */
    fun export(context: Context) {
        val uri = sourceUri ?: return
        val s = state.value
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            rendering.value = true
            message.value = "正在以全分辨率渲染…"
            try {
                val full = withContext(Dispatchers.IO) {
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val maxEdge = 4096
                    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }
                } ?: throw IllegalStateException("decode failed")
                val out = withContext(Dispatchers.Default) {
                    val safe = full.copy(Bitmap.Config.ARGB_8888, false)
                    Renderer.render(safe, paramsOf(s))
                } ?: throw IllegalStateException("render failed")
                withContext(Dispatchers.IO) {
                    saveJpeg(context, out, s)
                }
                message.value = "已保存到 Pictures/SpektraDroid"
            } catch (e: Exception) {
                message.value = "导出失败: ${e.message}"
            } finally {
                rendering.value = false
            }
        }
    }

    private fun saveJpeg(context: Context, bitmap: Bitmap, s: EditState): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val film = s.filmStock.substringAfterLast('_')
        val name = "spektralab_${film}_$stamp.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/SpektraDroid",
            )
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: throw IllegalStateException("cannot create media entry")
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                throw IllegalStateException("jpeg encode failed")
        }
        return uri
    }

    /** Default paper pairing when the user switches film. */
    fun preferredPaperFor(filmStock: String): String = when (filmStock) {
        "fujifilm_c200", "fujifilm_xtra_400", "fujifilm_pro_400h",
        "fujifilm_velvia_100", "fujifilm_provia_100f",
        "fujifilm_neopan_acros_100_ii",
        -> "fujifilm_crystal_archive_typeii"
        "cinestill_800t", "kodak_vision3_50d", "kodak_vision3_250d",
        "kodak_vision3_200t", "kodak_vision3_500t", "kodak_verita_200d",
        -> "kodak_2383"
        "ilford_hp5_plus_400", "kodak_tmax_100", "kodak_tri_x_400",
        -> "ilford_multigrade_iv_rc"
        else -> "kodak_portra_endura"
    }
}
