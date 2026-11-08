package com.spektralab.android.ui

import android.Manifest
import com.spektralab.android.PhotoIO
import com.spektralab.android.EditorViewModel
import com.spektralab.android.MediaLibrary
import com.spektralab.android.MediaItem
import com.spektralab.android.MediaSource
import com.spektralab.android.camera.MeteringMode
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spektralab.android.camera.CameraDevice
import com.spektralab.android.camera.CameraViewModel
import com.spektralab.android.camera.CaptureMode
import com.spektralab.android.camera.Flash
import com.spektralab.android.engine.NativeParams
import java.util.Locale

/**
 * The capture page. CameraX on whichever camera the phone exposes, its lenses
 * by zoom ratio, the maker's own HDR / night processing where the device has
 * it, and a hand-off to the system camera app for everything else. A shot
 * stays on this page and appears in the media strip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(
    editor: EditorViewModel,
    onEdit: (MediaItem) -> Unit,
    onBack: () -> Unit,
    /** the editor's film and paper, for the live film preview */
    liveParams: () -> NativeParams,
    filmName: String,
) {
    val context = LocalContext.current
    val vm: CameraViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val live by vm.liveFrame.collectAsState()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf("") }
    val library = remember(context) { MediaLibrary.get(context) }
    vm.recipe = { editor.state.value }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    vm.liveParams = liveParams
    BackHandler(onBack = onBack)



    // The system camera app: the phone's full processing (HDR, night, its own lens logic).
    var systemUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var showSystemCamera by rememberSaveable { mutableStateOf(false) }
    var offerImport by rememberSaveable { mutableStateOf(false) }
    val importPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSystemPhoto(uri)
    }
    val fullCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // STILL_IMAGE_CAMERA has no result contract. The user picks the file
        // explicitly; guessing the newest MediaStore entry can import someone else's shot.
        offerImport = true
    }
    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = systemUriText?.let(Uri::parse)
        systemUriText = null
        if (uri != null) {
            if (ok) vm.acceptSystemCapture(uri) else vm.discard(uri)
        }
    }
    val launchSystem: () -> Unit = {
        // An app that declares CAMERA must hold it for ACTION_IMAGE_CAPTURE too (else SecurityException).
        if (!granted) {
            permission.launch(Manifest.permission.CAMERA)
        } else {
            val uri = vm.newSystemCaptureUri()
            if (uri != null) {
                systemUriText = uri.toString()
                runCatching { systemCamera.launch(uri) }.onFailure {
                    vm.discard(uri); systemUriText = null
                    vm.reportError("无法打开系统相机：${it.localizedMessage}")
                }
            } else vm.reportError("无法创建照片，请检查相册存储权限和剩余空间")
        }
    }

    // keep the JPEG's orientation right when the phone is turned
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                vm.setRotation(
                    when (o) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    },
                )
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Palette.Text) }
            Column(Modifier.weight(1f)) {
                Text("相机", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(ui.selected?.let { describe(it, ui.zoom, ui.physicalId, ui.activePhysicalId) } ?: "", color = Palette.TextDim, fontSize = 11.sp)
            }
            if (ui.selected?.hasFlash == true) {
                IconButton(onClick = { vm.setFlash(Flash.entries[(ui.flash.ordinal + 1) % Flash.entries.size]) }) {
                    Icon(
                        when (ui.flash) { Flash.OFF -> Icons.Outlined.FlashOff; Flash.AUTO -> Icons.Outlined.FlashAuto; Flash.ON -> Icons.Outlined.FlashOn },
                        "闪光灯 ${ui.flash.label}", tint = if (ui.flash == Flash.OFF) Palette.Text else Palette.Amber,
                    )
                }
            }
            IconButton(onClick = { showInfo = true }, enabled = ui.cameras.isNotEmpty()) {
                Icon(Icons.Outlined.Info, "摄像头参数", tint = Palette.Text)
            }
        }

        val finder: @Composable (Modifier) -> Unit = { modifier ->
        BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            if (granted) {
                Box(Modifier.size(width = minOf(maxWidth, maxHeight * .75f), height = minOf(maxHeight, maxWidth / .75f)).clipToBounds()) {
                    Viewfinder(vm)
                    val frame = live
                    if (ui.filmPreview && frame != null) {
                        Image(frame.asImageBitmap(), "胶片预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    if (ui.filmPreview) {
                        Text(
                            "胶片预览 · $filmName", color = Palette.Amber, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    ui.selected?.let { cam ->
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val stops = if (ui.physicalId != null) listOf(1f, 2f).filter { it in ui.zoomMin..ui.zoomMax }
                                else cam.lensStops.filter { it in ui.zoomMin..ui.zoomMax }
                            stops.forEach { z ->
                                val sel = kotlin.math.abs(ui.zoom - z) < 0.06f
                                Text(
                                    zoomLabel(z) + if (sel) "×" else "",
                                    color = if (sel) Palette.Amber else Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.size(40.dp).background(Color(0x99000000), CircleShape)
                                        .semantics { role = Role.Button; selected = sel }
                                        .clickable { vm.setZoom(z) }.padding(top = 11.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                    if (ui.focusX != null && ui.focusY != null) Canvas(Modifier.fillMaxSize()) {
                        val x = ui.focusX!! * size.width; val y = ui.focusY!! * size.height
                        val edge = 44.dp.toPx()
                        drawRect(Palette.Amber, androidx.compose.ui.geometry.Offset(x-edge/2,y-edge/2),
                            androidx.compose.ui.geometry.Size(edge,edge), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                    if (ui.capturing) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Amber)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(if (denied) "没有相机权限" else "需要相机权限", color = Palette.Text, fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { permission.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.AmberInk),
                    ) { Text("授权") }
                }
            }
        }

        }

        val controls: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 16.dp, vertical = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureMode.entries.filter { it in ui.modes }.forEach { m ->
                    Chip(m.label, m == ui.mode && ui.physicalId == null, enabled = !ui.capturing) { vm.setMode(m) }
                }
                Chip("拍摄参数", panel == "shoot") { panel = if (panel == "shoot") "" else "shoot" }
                Chip("胶片参数", panel == "film") { panel = if (panel == "film") "" else "film" }
                val extensionSession = ui.mode != CaptureMode.STANDARD && !(ui.mode == CaptureMode.HDR && ui.hdrVia == "HAL HDR 场景模式")
                Chip("胶片预览", ui.filmPreview, enabled = !extensionSession && !ui.capturing) { vm.setFilmPreview(!ui.filmPreview) }
            }
        }

        if (panel == "film") LiveFilmControls(editor, height = 210.dp)
        if (panel == "shoot") Column(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 16.dp)) {
            if (ui.exposureMin < ui.exposureMax) {
                var exposure by remember(ui.exposureIndex, ui.selectedId, ui.mode) { mutableStateOf(ui.exposureIndex.toFloat()) }
                ParamSlider("相机曝光补偿", exposure,
                    ui.exposureMin.toFloat()..ui.exposureMax.toFloat(), 0f,
                    { String.format(Locale.US, "%+.1f EV", it * ui.exposureStep) },
                    { exposure = it }, { vm.setExposure(exposure.toInt()) }, enabled = ui.bound && !ui.capturing,
                    steps = (ui.exposureMax-ui.exposureMin-1).coerceAtLeast(0))
            } else Text("此摄像头 / 模式未开放曝光补偿", color = Palette.TextDim, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeteringMode.entries.forEach { mode -> Chip(mode.label, ui.metering == mode,
                    enabled = ui.bound && !ui.capturing && (mode == MeteringMode.AUTO || ui.regionMetering)) { vm.setMetering(mode) } }
            }
            Text(ui.meteringMessage.ifBlank { "点按画面选择对焦 / 测光区域，双指缩放；相机曝光与胶片曝光分别调整。" },
                color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        MediaStrip(library, MediaSource.CAMERA, onEdit, enabled = !ui.capturing)
        Column(
            Modifier.fillMaxWidth().background(Palette.Panel)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val note = when {
                !ui.bound -> null
                ui.mode == CaptureMode.HDR && ui.hdrVia == "HAL HDR 场景模式" -> "已请求手机的 HDR 场景模式；实际处理效果由手机决定。"
                ui.mode == CaptureMode.HDR -> "HDR：使用厂商扩展，取景画面不一定显示合成效果。"
                CaptureMode.HDR !in ui.modes -> "这台设备没有向第三方开放 HDR；可用「系统相机」拍摄，用手机自带的处理。"
                else -> null
            }
            if (note != null && panel.isBlank()) {
                Text(note, color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        }
        val shutter: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showSystemCamera = true }, enabled = !ui.capturing) { Text("系统相机", color = Palette.Text) }
                Box(
                    Modifier.size(72.dp).border(3.dp, Palette.Text, CircleShape).padding(6.dp)
                        .background(if (ui.bound && !ui.capturing) Palette.Amber else Palette.Hairline, CircleShape)
                        .clickable(enabled = ui.bound && !ui.capturing) { vm.capture() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.PhotoCamera, "拍摄", tint = Palette.AmberInk) }
                IconButton(onClick = vm::flip, enabled = ui.cameras.map { it.facing }.distinct().size > 1) {
                    Icon(Icons.Outlined.Cameraswitch, "切换前后摄像头", tint = Palette.Text)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val controlsHeight = maxHeight * .57f
            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                Row(Modifier.fillMaxSize()) {
                    finder(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(340.dp).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
                        shutter()
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                finder(Modifier.fillMaxWidth().weight(1f))
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState())) { controls() }
                shutter()
            }
        }
    }

    if (showSystemCamera) AlertDialog(
        onDismissRequest = { showSystemCamera = false },
        title = { Text("使用手机相机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("复用手机已有的拍摄算法和模式。")
                TextButton(onClick = { showSystemCamera = false; launchSystem() }) {
                    Text("拍照并回传", color = Palette.Amber)
                }
                Text("拍完保留在本页素材栏。部分手机在此入口只提供基础拍照模式。", fontSize = 12.sp)
                TextButton(onClick = {
                    showSystemCamera = false
                    runCatching { fullCamera.launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
                        .onFailure { vm.reportError("没有可用的系统相机：${it.localizedMessage}") }
                }) { Text("打开完整系统相机", color = Palette.Amber) }
                Text("在手机相机中选择长曝光、专业、RAW 等模式；实际选项由手机提供。拍完返回后选择照片导入。", fontSize = 12.sp)
                TextButton(onClick = {
                    showSystemCamera = false
                    importPhoto.launch(PhotoIO.IMPORT_MIME_TYPES)
                }) { Text("导入已拍照片", color = Palette.Amber) }
            }
        },
        confirmButton = { TextButton(onClick = { showSystemCamera = false }) { Text("关闭", color = Palette.Amber) } },
        containerColor = Palette.PanelRaised,
    )
    if (offerImport) AlertDialog(
        onDismissRequest = { offerImport = false },
        title = { Text("导入系统相机照片") },
        text = { Text("完整系统相机将照片保存在相册中，选择一张继续冲印。支持 DNG、Sony ARW、Nikon NEF、Canon CR2/CR3 和 TIFF，也可以选择 JPEG。") },
        confirmButton = { TextButton(onClick = {
            offerImport = false
            importPhoto.launch(PhotoIO.IMPORT_MIME_TYPES)
        }) { Text("选择照片", color = Palette.Amber) } },
        dismissButton = { TextButton(onClick = { offerImport = false }) { Text("稍后", color = Palette.TextDim) } },
        containerColor = Palette.PanelRaised,
    )

    ui.error?.let { e ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("好", color = Palette.Amber) } },
            text = { Text(e) },
            containerColor = Palette.PanelRaised,
        )
    }
    if (showInfo) CameraInfoDialog(
        ui.cameras, ui.selectedId, ui.physicalId, ui.activePhysicalId,
        onSelect = { vm.selectCamera(it); showInfo = false },
        onPhysical = { vm.selectPhysical(it); showInfo = false },
        onDismiss = { showInfo = false },
    )
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Viewfinder(vm: CameraViewModel) {
    val lifecycle = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean { vm.scaleZoom(d.scaleFactor); return true }
                })
                val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        if (!pinch.isInProgress && width > 0 && height > 0) {
                            vm.tapMeter(meteringPointFactory.createPoint(e.x, e.y, .12f), e.x/width, e.y/height)
                            performClick()
                        }
                        return true
                    }
                })
                setOnTouchListener { _, ev ->
                    pinch.onTouchEvent(ev)
                    taps.onTouchEvent(ev)
                    true
                }
                vm.start(lifecycle, this)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) { onDispose { vm.stop() } }
}

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = when { !enabled -> Palette.TextFaint; selected -> Palette.AmberInk; else -> Palette.Text },
        fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .semantics { role = Role.Button; this.selected = selected }
            .background(if (selected && enabled) Palette.Amber else Palette.PanelRaised, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun zoomLabel(z: Float): String =
    if (z < 1f || z % 1f != 0f) String.format(Locale.US, "%.1f", z).removeSuffix(".0") else z.toInt().toString()

private fun describe(cam: CameraDevice, zoom: Float, requestedId: String?, activeId: String?): String {
    val lens = cam.physical.firstOrNull { it.id == (requestedId ?: activeId) } ?: cam.main
    val eq = ((if (requestedId != null) lens else cam.main).equivMm * zoom).toInt()
    val f = lens.aperture?.let { " f/" + String.format(Locale.US, "%.1f", it) } ?: ""
    val physical = (activeId ?: requestedId)?.let { " · 镜头 #$it" } ?: ""
    return "${cam.facingLabel} #${cam.id}$physical · ${eq}mm 等效$f · ${String.format(Locale.US, "%.1f", zoom)}×"
}

@Composable
private fun CameraInfoDialog(
    cams: List<CameraDevice>, selected: String?, physicalId: String?, activeId: String?,
    onSelect: (String) -> Unit, onPhysical: (String?) -> Unit, onDismiss: () -> Unit,
) {
    // A physical sensor may be listed under more than one logical camera.
    val rear = cams.filter { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
        .flatMap { c -> c.physical.ifEmpty { listOf(c.main) } }.distinctBy { it.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = Palette.Amber) } },
        title = { Text("摄像头与镜头") },
        containerColor = Palette.PanelRaised,
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                item {
                    Text("可用相机入口 ${cams.size} 个 · 已识别后置物理镜头 ${rear.size} 颗", color = Palette.Text, fontSize = 13.sp)
                    Text("一个逻辑相机可管理多颗镜头；变焦按钮不等于镜头数量。物理镜头能否独立打开由手机决定。", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(cams) { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onSelect(c.id) }
                            .background(if (c.id == selected) Palette.AmberDim.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Text("${c.facingLabel}摄像头 #${c.id}" + if (c.logical) "（多摄融合）" else "",
                            color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(lensLine(c.main), color = Palette.TextDim, fontSize = 12.sp)
                        Text(
                            "变焦 ${fmt(c.zoomMin)}–${fmt(c.zoomMax)}× · 硬件级别 ${c.hardwareLevel}" +
                                (if (c.raw) " · RAW" else "") + (if (c.manualSensor) " · 手动曝光" else "") +
                                (if (c.sceneHdr) " · HDR 场景" else "") + (if (c.hasFlash) " · 闪光灯" else ""),
                            color = Palette.TextDim, fontSize = 12.sp,
                        )
                        if (c.isoRange != null || c.exposureRange != null) Text(
                            listOfNotNull(c.isoRange?.let { "ISO $it" }, c.exposureRange?.let { "曝光 $it" }).joinToString(" · "),
                            color = Palette.TextDim, fontSize = 12.sp,
                        )
                        if (c.id == selected && c.physical.isNotEmpty() && Build.VERSION.SDK_INT >= 28) {
                            TextButton(onClick = { onPhysical(null) }) {
                                Text("自动镜头" + (activeId?.let { " · 当前 #$it" } ?: ""), color = if (physicalId == null) Palette.Amber else Palette.Text)
                            }
                        }
                        c.physical.forEach { p ->
                            Text("  └ 物理镜头 #${p.id}：" + lensLine(p), color = Palette.TextFaint, fontSize = 11.sp)
                            if (c.id == selected && Build.VERSION.SDK_INT >= 28) TextButton(onClick = { onPhysical(p.id) }) {
                                Text("使用镜头 #${p.id}（${p.equivMm.toInt()}mm）", color = if (physicalId == p.id) Palette.Amber else Palette.Text)
                            }
                        }
                    }
                    HorizontalDivider(color = Palette.Hairline)
                }
            }
        },
    )
}

private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)

private fun lensLine(l: com.spektralab.android.camera.Lens): String =
    "焦距 ${fmt(l.focalMm)}mm（等效 ${l.equivMm.toInt()}mm）" +
        (l.aperture?.let { " · f/${fmt(it)}" } ?: "") +
        " · 传感器 ${fmt(l.sensorWmm)}×${fmt(l.sensorHmm)}mm · ${l.megapixels.toInt()}MP"
