package com.spektralab.android.camera

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.spektralab.android.engine.NativeParams
import com.spektralab.android.engine.SpektraNative
import com.spektralab.android.EditState
import com.spektralab.android.MediaLibrary
import com.spektralab.android.MediaSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How the picture is taken. Everything but [STANDARD] is the phone maker's own
 * processing through the CameraX Extensions API -- the multi-frame HDR and
 * night modes the system camera uses -- so it is offered only where the
 * device has it.
 */
enum class CaptureMode(val label: String, val extension: Int?) {
    STANDARD("标准", null),
    HDR("HDR", ExtensionMode.HDR),
    NIGHT("夜景", ExtensionMode.NIGHT),
    BOKEH("人像", ExtensionMode.BOKEH),
    RETOUCH("美颜", ExtensionMode.FACE_RETOUCH),
    AUTO("自动", ExtensionMode.AUTO),
}

enum class Flash(val label: String, val mode: Int) {
    OFF("关", ImageCapture.FLASH_MODE_OFF),
    AUTO("自动", ImageCapture.FLASH_MODE_AUTO),
    ON("开", ImageCapture.FLASH_MODE_ON),
}

enum class MeteringMode(val label: String) { AUTO("自动测光"), CENTER("中央区域"), SPOT("点按测光") }

data class CameraUiState(
    val cameras: List<CameraDevice> = emptyList(),
    val selectedId: String? = null,
    val modes: Set<CaptureMode> = setOf(CaptureMode.STANDARD),
    val mode: CaptureMode = CaptureMode.STANDARD,
    val flash: Flash = Flash.OFF,
    val zoom: Float = 1f,
    val filmPreview: Boolean = false,
    val bound: Boolean = false,
    val capturing: Boolean = false,
    val error: String? = null,
    /** how HDR is done on this camera: the maker's CameraX extension, or the HAL's HDR scene mode */
    val hdrVia: String? = null,
    /** null lets the phone select its lens; a physical id requests a specific exposed sensor. */
    val physicalId: String? = null,
    val activePhysicalId: String? = null,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
    val exposureIndex: Int = 0,
    val exposureStep: Float = 0f,
    val metering: MeteringMode = MeteringMode.AUTO,
    val regionMetering: Boolean = false,
    val focusX: Float? = null,
    val focusY: Float? = null,
    val meteringMessage: String = "",
) {
    val selected: CameraDevice? get() = cameras.firstOrNull { it.id == selectedId }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "SpektraCamera"
        /** the live film preview's long edge: small enough to render several times a second */
        private const val LIVE_EDGE = 480
    }

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui

    /** the latest live film-preview frame (upright, as the viewfinder shows it) */
    val liveFrame = MutableStateFlow<Bitmap?>(null)

    private val _captured = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val captured: SharedFlow<Uri> = _captured

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var targetRotation = Surface.ROTATION_0
    @Volatile private var bindingGeneration = 0
    private val zoomObserver = Observer<ZoomState> { z ->
        _ui.update { it.copy(zoom = z.zoomRatio, zoomMin = z.minZoomRatio, zoomMax = z.maxZoomRatio) }
    }

    private val liveExecutor = Executors.newSingleThreadExecutor { Thread(it, "spektra-live") }
    /**
     * The film preview must start from the same processed stream the user sees.
     * ImageAnalysis is a separate YUV stream on several OEMs: in ordinary mode
     * vivo gives it a different AE/tonemap from the JPEG, which made the film
     * preview look substantially darker or flatter than the finished shot.
     */
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewView: PreviewView? = null
    private val previewPoll = object : Runnable {
        override fun run() {
            val view = previewView
            if (_ui.value.filmPreview && _ui.value.bound && view != null) {
                runCatching { view.bitmap }.getOrNull()?.let(::submitPreviewBitmap)
                previewHandler.postDelayed(this, 140L)
            }
        }
    }
    private val liveBusy = AtomicBoolean(false)
    private val liveToken = AtomicLong(0)
    private var liveHandle = 0L
    /** the editor's film and paper, for the live preview (no film edge, no date: a viewfinder) */
    @Volatile var liveParams: (() -> NativeParams)? = null
    @Volatile var recipe: (() -> EditState)? = null
    private var exposureRequest = 0L

    fun start(lifecycleOwner: LifecycleOwner, view: PreviewView) {
        owner = lifecycleOwner
        previewView = view
        surfaceProvider = view.surfaceProvider
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val p = provider ?: ProcessCameraProvider.getInstance(ctx).await().also { provider = it }
                if (extensions == null) {
                    extensions = runCatching { ExtensionsManager.getInstanceAsync(ctx, p).await() }
                        .onFailure { Log.w(TAG, "extensions unavailable", it) }.getOrNull()
                }
                if (_ui.value.cameras.isEmpty()) {
                    // what CameraX can bind, described by Camera2
                    val bindable = p.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet()
                    val inventory = CameraInventory.list(ctx)
                    Log.i(TAG, "Public camera IDs=${ctx.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.toList()}; CameraX IDs=$bindable")
                    inventory.forEach { c -> Log.i(TAG, "Camera #${c.id} ${c.facingLabel}: physical=${c.physical.map { it.id }}, RAW=${c.raw}, exposure=${c.exposureRange}") }
                    val cams = inventory.filter { it.id in bindable }
                    val first = cams.firstOrNull { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
                        ?: cams.firstOrNull()
                    _ui.value = _ui.value.copy(cameras = cams, selectedId = first?.id,
                        error = if (cams.isEmpty()) "没有可用的摄像头" else null)
                }
                if (owner === lifecycleOwner && previewView === view) bind()
            } catch (e: Exception) {
                Log.e(TAG, "start", e)
                _ui.value = _ui.value.copy(error = "相机启动失败：${e.message}")
            }
        }
    }

    fun stop() {
        bindingGeneration++
        previewHandler.removeCallbacks(previewPoll)
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        provider?.unbindAll()
        camera = null
        imageCapture = null
        _ui.value = _ui.value.copy(bound = false)
        liveFrame.value = null
        owner = null
        surfaceProvider = null
        previewView = null
    }

    private fun selectorFor(id: String) = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()

    private fun bind() {
        val p = provider ?: return
        val lo = owner ?: return
        val sp = surfaceProvider ?: return
        val state = _ui.value
        val generation = ++bindingGeneration
        val id = state.selectedId ?: return
        val base = selectorFor(id)
        val ext = extensions
        fun hasExtension(m: CaptureMode) =
            m.extension != null && ext != null && runCatching { ext.isExtensionAvailable(base, m.extension) }.getOrDefault(false)
        // HDR: the maker's extension where it is offered, else the HAL's own HDR scene
        // mode (the phone's processing all the same, asked for through Camera2)
        val sceneHdr = state.selected?.sceneHdr == true && !hasExtension(CaptureMode.HDR)
        val modes = CaptureMode.entries.filter { m ->
            m.extension == null || hasExtension(m) || (m == CaptureMode.HDR && sceneHdr)
        }.toSet()
        val mode = if (state.mode in modes && state.physicalId == null) state.mode else CaptureMode.STANDARD
        val useScene = mode == CaptureMode.HDR && sceneHdr
        val viaExtension = mode.extension != null && !useScene && ext != null
        val selector = if (viaExtension) ext!!.getExtensionEnabledCameraSelector(base, mode.extension!!) else base
        val hdrVia = when {
            CaptureMode.HDR !in modes -> null
            sceneHdr -> "HAL HDR 场景模式"
            else -> "厂商 HDR 扩展"
        }
        fun <T> Camera2Interop.Extender<T>.hdrScene() {
            setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        }

        val aspect = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        val previewBuilder = Preview.Builder().setResolutionSelector(aspect).setTargetRotation(targetRotation)
        val previewInterop = Camera2Interop.Extender(previewBuilder)
        // CameraX exposes logical cameras; Camera2 can route a stream to one of
        // their advertised physical sensors. OEM support still varies by device.
        if (Build.VERSION.SDK_INT >= 28) state.physicalId?.let { previewInterop.setPhysicalCameraId(it) }
        previewInterop.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val active = if (Build.VERSION.SDK_INT >= 29) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
                if (active != _ui.value.activePhysicalId) {
                    androidx.core.content.ContextCompat.getMainExecutor(getApplication()).execute {
                        if (generation == bindingGeneration) _ui.update { it.copy(activePhysicalId = active) }
                        if (generation == bindingGeneration) Log.i(TAG, "Active physical lens=$active logical=$id requested=${state.physicalId}")
                    }
                }
            }
        })
        if (useScene) Camera2Interop.Extender(previewBuilder).hdrScene()
        val preview = previewBuilder.build().also { it.surfaceProvider = sp }
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(state.flash.mode)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build(),
            )
            .setTargetRotation(targetRotation)
        if (Build.VERSION.SDK_INT >= 28) state.physicalId?.let { Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(it) }
        if (useScene) Camera2Interop.Extender(captureBuilder).hdrScene()
        val capture = captureBuilder.build()
        // Deliberately keep only Preview + ImageCapture. The film preview is
        // sampled from PreviewView below, so it sees the same OEM processed
        // stream as the user instead of a separately toned ImageAnalysis YUV.
        val useCases = listOf<UseCase>(preview, capture)
        liveFrame.value = null
        try {
            camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
            p.unbindAll()
            val cam = p.bindToLifecycle(lo, selector, *useCases.toTypedArray())
            camera = cam
            imageCapture = capture
            liveFrame.value = null
            cam.cameraInfo.zoomState.observeForever(zoomObserver)
            _ui.value = _ui.value.copy(modes = modes, mode = mode, bound = true, error = null, hdrVia = hdrVia, activePhysicalId = null)
            val exposure = cam.cameraInfo.exposureState
            val center = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
            val canMeter = cam.cameraInfo.isFocusMeteringSupported(FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AE).build())
            _ui.update { it.copy(exposureMin = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange.lower else 0,
                exposureMax = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange.upper else 0,
                exposureIndex = exposure.exposureCompensationIndex, exposureStep = exposure.exposureCompensationStep.toFloat(),
                regionMetering = canMeter, metering = MeteringMode.AUTO, focusX = null, focusY = null, meteringMessage = "") }
            Log.i(TAG, "Bound camera=$id physical=${state.physicalId ?: "auto"} mode=$mode HDR=$hdrVia filmPreview=${state.filmPreview && !viaExtension}")
            if (state.filmPreview && !viaExtension) {
                previewHandler.removeCallbacks(previewPoll)
                previewHandler.post(previewPoll)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bind $id $mode", e)
            if (state.physicalId != null) {
                _ui.update { it.copy(physicalId = null, bound = false) }
                bind()
                reportError("这颗物理镜头无法单独打开，已恢复自动镜头。可用完整系统相机使用厂商的多摄能力。")
            } else if (mode != CaptureMode.STANDARD) {
                // an extension the device claims but cannot open with this camera: fall back
                _ui.value = _ui.value.copy(mode = CaptureMode.STANDARD)
                bind()
                reportError("${mode.label} 不可用，已恢复标准模式：${e.message}")
            } else _ui.value = _ui.value.copy(bound = false, error = "无法打开摄像头 $id：${e.message}")
        }
    }

    fun selectCamera(id: String) {
        if (id == _ui.value.selectedId) return
        if (_ui.value.capturing) return
        _ui.value = _ui.value.copy(selectedId = id, physicalId = null, activePhysicalId = null, zoom = 1f)
        bind()
    }

    fun selectPhysical(id: String?) {
        val s = _ui.value
        if (s.capturing || id == s.physicalId) return
        if (id != null && s.selected?.physical?.any { it.id == id } != true) return
        _ui.update { it.copy(physicalId = id, activePhysicalId = null, mode = CaptureMode.STANDARD, zoom = 1f) }
        bind()
    }

    /** Front <-> back: the first camera facing the other way. */
    fun flip() {
        val s = _ui.value
        val cur = s.selected ?: return
        s.cameras.firstOrNull { it.facing != cur.facing }?.let { selectCamera(it.id) }
    }

    fun setMode(m: CaptureMode) {
        if (_ui.value.capturing || (m == _ui.value.mode && _ui.value.physicalId == null) || m !in _ui.value.modes) return
        _ui.value = _ui.value.copy(mode = m, physicalId = null)
        bind()
    }

    fun setFlash(f: Flash) {
        _ui.value = _ui.value.copy(flash = f)
        imageCapture?.flashMode = f.mode
    }

    fun setZoom(ratio: Float) {
        val s = _ui.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(s.zoomMin, s.zoomMax))
    }

    fun scaleZoom(factor: Float) = setZoom(_ui.value.zoom * factor)

    fun setExposure(index: Int) {
        val cam = camera ?: return
        val state = _ui.value
        if (!state.bound || state.capturing || state.exposureMin == state.exposureMax) return
        val request = ++exposureRequest
        val generation = bindingGeneration
        viewModelScope.launch {
            try {
                val applied = cam.cameraControl.setExposureCompensationIndex(index.coerceIn(state.exposureMin, state.exposureMax)).await()
                if (generation == bindingGeneration && request == exposureRequest) _ui.update { it.copy(exposureIndex = applied) }
            } catch (e: Exception) {
                if (generation == bindingGeneration && request == exposureRequest) reportError("曝光调整未生效：${e.message}")
            }
        }
    }

    fun setMetering(mode: MeteringMode) {
        val cam = camera ?: return
        if (_ui.value.capturing) return
        if (mode != MeteringMode.AUTO && !_ui.value.regionMetering) return
        cam.cameraControl.cancelFocusAndMetering()
        _ui.update { it.copy(metering = mode, focusX = null, focusY = null,
            meteringMessage = if (mode == MeteringMode.SPOT) "点按取景画面选择测光点" else "") }
        if (mode == MeteringMode.CENTER) previewView?.let { view ->
            meter(view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f, .35f), .5f, .5f, false)
        }
    }

    fun tapMeter(point: MeteringPoint, x: Float, y: Float) {
        if (_ui.value.capturing || !_ui.value.bound) return
        _ui.update { it.copy(metering = if (it.regionMetering) MeteringMode.SPOT else it.metering) }
        meter(point, x, y, true)
    }

    private fun meter(point: MeteringPoint, x: Float, y: Float, focus: Boolean) {
        val cam = camera ?: return
        val generation = bindingGeneration
        val ae = _ui.value.regionMetering
        val aeAction = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
        val afAction = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
        val af = focus && cam.cameraInfo.isFocusMeteringSupported(afAction)
        if ((!ae || !cam.cameraInfo.isFocusMeteringSupported(aeAction)) && !af) {
            _ui.update { it.copy(meteringMessage = "此模式不支持区域测光或对焦") }; return
        }
        val flags = (if (ae) FocusMeteringAction.FLAG_AE else 0) or (if (af) FocusMeteringAction.FLAG_AF else 0)
        val action = FocusMeteringAction.Builder(point, flags).disableAutoCancel().build()
        _ui.update { it.copy(focusX = x, focusY = y, meteringMessage = if (ae) "测光区域已更新" else "已请求点按对焦；设备不支持区域测光") }
        viewModelScope.launch {
            runCatching { cam.cameraControl.startFocusAndMetering(action).await() }.onFailure { e ->
                if (generation == bindingGeneration) _ui.update { it.copy(meteringMessage = "测光 / 对焦未生效：${e.message}") }
            }
        }
    }

    fun setFilmPreview(on: Boolean) {
        if (_ui.value.capturing || on == _ui.value.filmPreview) return
        val s = _ui.value
        val extensionSession = s.mode != CaptureMode.STANDARD && !(s.mode == CaptureMode.HDR && s.hdrVia == "HAL HDR 场景模式")
        if (on && extensionSession) return
        _ui.value = _ui.value.copy(filmPreview = on)
        // Preview processing does not need a new camera session. Rebinding here
        // would silently reset the user's exposure and selected AE/AF region.
        previewHandler.removeCallbacks(previewPoll)
        if (on && s.bound) previewHandler.post(previewPoll) else liveFrame.value = null
    }

    /** From an OrientationEventListener: keeps the JPEG's orientation right when the phone turns. */
    fun setRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null) }

    fun reportError(message: String) { _ui.update { it.copy(error = message) } }

    // ------------------------------------------------------------- capture

    fun capture() {
        val ic = imageCapture ?: return
        if (_ui.value.capturing) return
        val ctx = getApplication<Application>()
        _ui.value = _ui.value.copy(capturing = true)
        val zoomAtShot = _ui.value.zoom
        val recipeAtShot = recipe?.invoke()
        val cam = _ui.value.selected
        val name = "SPK_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/SpektraLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
        val physicalAtShot = _ui.value.physicalId
        Log.i(TAG, "Capture camera=${cam?.id} physical=$physicalAtShot zoom=$zoomAtShot mode=${_ui.value.mode}")
        ic.takePicture(opts, androidx.core.content.ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri
                if (uri != null && cam != null) fillEquivalentFocal(uri, cam, zoomAtShot, physicalAtShot)
                if (uri != null) publish(uri)
                Log.i(TAG, "Capture saved; adding to in-app library")
                if (uri != null) viewModelScope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            MediaLibrary.get(ctx).add(uri, "$name.jpg", MediaSource.CAMERA, recipe = recipeAtShot)
                        }
                        _captured.emit(uri)
                    } catch (e: Exception) { reportError("照片已保存，素材目录更新失败：${e.message}") }
                    finally { _ui.update { it.copy(capturing = false) } }
                }
                else _ui.value = _ui.value.copy(capturing = false, error = "照片已拍摄，但没有拿到保存位置")
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "capture", e)
                _ui.value = _ui.value.copy(capturing = false, error = "拍摄失败：${e.message}")
            }
        })
    }

    /**
     * Phones often leave FocalLengthIn35mmFilm out of the JPEG; the date back's
     * data line prints it, so it is filled in from the lens actually in use
     * (the zoom picks it) when missing. Best effort.
     */
    private fun fillEquivalentFocal(uri: Uri, cam: CameraDevice, zoom: Float, physicalId: String?) {
        runCatching {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0) > 0) return
                val lens = cam.physical.firstOrNull { it.id == physicalId } ?: cam.main
                val eq = (lens.equivMm * zoom).roundToInt()
                if (eq <= 0) return
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, eq.toString())
                exif.saveAttributes()
            }
        }.onFailure { Log.w(TAG, "35mm focal not written", it) }
    }

    /** A MediaStore entry for the system camera app to write into (ACTION_IMAGE_CAPTURE). */
    fun newSystemCaptureUri(): Uri? {
        val ctx = getApplication<Application>()
        val name = "SPK_SYS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/SpektraLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return runCatching { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            getApplication<Application>().contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        }.onFailure { Log.w(TAG, "publish photo", it) }
    }

    fun acceptSystemCapture(uri: Uri) {
        viewModelScope.launch {
            val valid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true }.getOrDefault(false)
            }
            if (valid) {
                publish(uri)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    MediaLibrary.get(getApplication()).add(uri, MediaLibrary.displayName(getApplication(), uri),
                        MediaSource.CAMERA, recipe = recipe?.invoke())
                }
                _captured.emit(uri)
            } else {
                discard(uri)
                reportError("系统相机没有返回照片，可用完整系统相机拍摄后从相册导入。")
            }
        }
    }

    fun importSystemPhoto(uri: Uri) {
        val state = recipe?.invoke()
        viewModelScope.launch {
            try {
                val retained = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val saved = MediaLibrary.retainImport(ctx, uri)
                    MediaLibrary.get(ctx).add(saved, MediaLibrary.displayName(ctx, uri), MediaSource.CAMERA, recipe = state)
                    saved
                }
                _captured.emit(retained)
            } catch (e: Exception) { reportError("导入失败：${e.message}") }
        }
    }

    fun discard(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
    }

    // --------------------------------------------------- live film preview

    /** Submit a frame copied from PreviewView's displayed, processed camera stream. */
    private fun submitPreviewBitmap(source: Bitmap) {
        val params = liveParams
        val generation = bindingGeneration
        if (params == null || !liveBusy.compareAndSet(false, true)) { source.recycle(); return }
        val frame = try {
            val s = LIVE_EDGE.toFloat() / max(source.width, source.height)
            val m = Matrix().apply {
                postScale(s, s)
            }
            val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
            if (transformed.config == Bitmap.Config.ARGB_8888) transformed
            else transformed.copy(Bitmap.Config.ARGB_8888, false).also { transformed.recycle() }
        } catch (e: Exception) {
            Log.w(TAG, "live frame", e)
            null
        } finally {
            source.recycle()
        }
        if (frame == null) { liveBusy.set(false); return }
        liveExecutor.execute {
            try {
                if (liveHandle == 0L) liveHandle = SpektraNative.create()
                val h = liveHandle
                if (h == 0L) return@execute
                val t = liveToken.incrementAndGet()
                SpektraNative.setWanted(h, t)
                val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                // a new source every frame: no cached negative to reuse
                val err = SpektraNative.render(h, frame, -1L, out, params(), t)
                if (err == null && _ui.value.filmPreview && generation == bindingGeneration && _ui.value.bound) liveFrame.value = out
                else out.recycle()
            } catch (e: Throwable) {
                Log.w(TAG, "live render", e)
            } finally {
                frame.recycle()
                liveBusy.set(false)
            }
        }
    }

    override fun onCleared() {
        stop()
        liveExecutor.execute { if (liveHandle != 0L) { SpektraNative.destroy(liveHandle); liveHandle = 0L } }
        liveExecutor.shutdown()
        super.onCleared()
    }
}
