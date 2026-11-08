package com.spektralab.android.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.spektralab.android.PhotoIO
import com.spektralab.android.EditState
import com.spektralab.android.EditorViewModel
import com.spektralab.android.ExportSize
import com.spektralab.android.Photo
import com.spektralab.android.UiMessage
import com.spektralab.android.engine.Catalog

@Composable
fun EditorScreen(vm: EditorViewModel, onCamera: () -> Unit = {}, onTether: () -> Unit = {}) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val photo by vm.photo.collectAsState()
    val result by vm.result.collectAsState()
    val rendering by vm.rendering.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val fullSizeEnabled by vm.fullSizeEnabled.collectAsState()
    val exportStage by vm.exportStage.collectAsState()
    val loading by vm.loadingPhoto.collectAsState()
    val engineError by vm.engineError.collectAsState()
    val engineReady by vm.engineReady.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showRecipes by rememberSaveable { mutableStateOf(false) }
    var savedItem by remember { mutableStateOf<com.spektralab.android.MediaItem?>(null) }
    val library = remember(context) { com.spektralab.android.MediaLibrary.get(context) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadPhoto(uri)
    }
    val pick = { picker.launch(PhotoIO.IMPORT_MIME_TYPES) }

    var pendingExport by remember { mutableStateOf<ExportSize?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val size = pendingExport
        pendingExport = null
        if (granted && size != null) vm.export(size)
    }
    val startExport: (ExportSize) -> Unit = { size ->
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = size
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else vm.export(size)
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            when (m) {
                is UiMessage.Text -> snackbar.showSnackbar(m.text, withDismissAction = true)
                is UiMessage.Saved -> {
                    val r = snackbar.showSnackbar(
                        "已保存到 相册 › Pictures/SpektraLab", actionLabel = "查看",
                        withDismissAction = true, duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) savedItem = library.items.value.firstOrNull { it.uri == m.uri.toString() }
                }
            }
        }
    }

    val actions = rememberEditorActions(vm)

    EditorContent(
        state = state,
        catalog = catalog,
        photo = photo,
        result = result?.bitmap,
        rendering = rendering,
        loading = loading || !engineReady,
        exporting = exporting,
        engineError = engineError,
        snackbar = snackbar,
        actions = actions,
        onPick = pick,
        onCamera = onCamera,
        onTether = onTether,
        onExport = { showExport = true },
        onCancelExport = vm::cancelExport,
        onAbout = { showAbout = true },
        exportStage = exportStage,
        onRecipes = { showRecipes = true },
    )

    if (showExport) {
        photo?.let { p ->
            ExportDialog(
                fullWidth = p.fullWidth, fullHeight = p.fullHeight,
                fullSizeEnabled = fullSizeEnabled, onFullSizeEnabled = vm::enableFullSize,
                hasBorder = state.filmEdge.active,
                onDismiss = { showExport = false },
                onExport = { size -> showExport = false; startExport(size) },
            )
        }
    }
    if (showAbout) AboutDialog { showAbout = false }
    if (showRecipes) RecipePicker(vm) { showRecipes = false }
    savedItem?.let { item -> MediaDetail(item, { savedItem = null }, {
        savedItem = null; vm.loadPhoto(Uri.parse(item.parentUri ?: item.uri), item.recipe)
    }, { savedItem = null; scope.launch(Dispatchers.IO) { library.remove(item.id) } }) }
}

/** The stateless editor: everything it shows comes in as arguments. */
@Composable
fun EditorContent(
    state: EditState,
    catalog: Catalog,
    photo: Photo?,
    result: android.graphics.Bitmap?,
    rendering: Boolean,
    loading: Boolean,
    exporting: Boolean,
    engineError: String?,
    snackbar: SnackbarHostState,
    actions: EditorActions,
    onPick: () -> Unit,
    onCamera: () -> Unit,
    onTether: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onAbout: () -> Unit,
    exportStage: String = "",
    onRecipes: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
            val viewer: @Composable (Modifier) -> Unit = { m ->
                Box(m) {
                    when {
                        engineError != null -> CenterMessage("引擎初始化失败", engineError)
                        photo == null -> EmptyState(loading = loading, onPick = onPick, onCamera = onCamera, onTether = onTether)
                        else -> PhotoViewer(
                            before = photo.preview,
                            after = result,
                            rendering = rendering || loading,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            val topBar: @Composable () -> Unit = {
                TopBar(state, catalog, photo != null, exporting, onPick, onCamera, onTether, onExport, actions::resetAdjustments, onAbout, onRecipes)
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
                    ) {
                        topBar()
                        viewer(Modifier.fillMaxWidth().weight(1f))
                    }
                    VerticalDivider(color = Palette.Hairline)
                    ControlPanel(
                        state, catalog, actions,
                        Modifier.width(380.dp).fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
                        shooting = photo?.shooting,
                    )
                }
            } else {
                // the photo gets whatever the panel leaves; the panel never overlaps it
                val panelHeight = (maxHeight * 0.42f).coerceIn(260.dp, 400.dp)
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                        topBar()
                    }
                    viewer(Modifier.fillMaxWidth().weight(1f))
                    ControlPanel(
                        state, catalog, actions,
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .height(panelHeight),
                        shooting = photo?.shooting,
                    )
                }
            }
        }

        if (exporting) ExportOverlay(onCancel = onCancelExport, stage = exportStage)

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 64.dp),
        )
    }
}

@Composable
private fun TopBar(
    state: EditState,
    catalog: Catalog,
    hasPhoto: Boolean,
    exporting: Boolean,
    onPick: () -> Unit,
    onCamera: () -> Unit,
    onTether: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onAbout: () -> Unit,
    onRecipes: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("工作台", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val film = catalog.film(state.filmStock)?.name ?: state.filmStock
            Text(
                film,
                color = Palette.TextDim, fontSize = 11.sp,
            )
        }
        IconButton(onClick = onCamera) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = "拍摄", tint = Palette.Text)
        }
        IconButton(onClick = onPick) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "选择照片", tint = Palette.Text)
        }
        IconButton(onClick = onExport, enabled = hasPhoto && !exporting) {
            Icon(
                Icons.Outlined.FileDownload, contentDescription = "导出",
                tint = if (hasPhoto && !exporting) Palette.Amber else Palette.TextFaint,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = Palette.Text)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("胶片配方 · 保存 / 调用") }, onClick = { menu = false; onRecipes() }, enabled = !exporting)
                DropdownMenuItem(text = { Text("连接 USB 相机") }, onClick = { menu = false; onTether() })
                DropdownMenuItem(
                    text = { Text("恢复默认参数") },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                    onClick = { menu = false; onReset() },
                )
                DropdownMenuItem(
                    text = { Text("关于") },
                    leadingIcon = { Icon(Icons.Outlined.Info, null) },
                    onClick = { menu = false; onAbout() },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, onPick: () -> Unit, onCamera: () -> Unit, onTether: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Canvas), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Box(
                    Modifier.size(72.dp).background(Palette.PanelRaised, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = Palette.Amber, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("选择一张照片", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("曝光 → 显影 → 放大印相 → 扫描", color = Palette.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onPick,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.AmberInk),
                ) { Text("打开相册") }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = onCamera) {
                    Icon(Icons.Outlined.PhotoCamera, null, tint = Palette.Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("拍摄", color = Palette.Text)
                }
                androidx.compose.material3.OutlinedButton(onClick = onTether) { Text("连接 USB 相机", color = Palette.Text) }
            }
        }
    }
}

@Composable
private fun CenterMessage(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().background(Palette.Canvas).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Palette.Error, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Palette.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun ExportOverlay(onCancel: () -> Unit, stage: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(280.dp).background(Palette.PanelRaised, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stage.ifBlank { "正在冲印" }, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                color = Palette.Amber, trackColor = Palette.Hairline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text("正在处理所选尺寸；大图可能需要较长时间", color = Palette.TextFaint, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCancel) { Text("取消", color = Palette.Amber) }
        }
    }
}

@Composable
private fun ExportDialog(
    fullWidth: Int, fullHeight: Int,
    fullSizeEnabled: Boolean, onFullSizeEnabled: (Boolean) -> Unit, hasBorder: Boolean,
    onDismiss: () -> Unit, onExport: (ExportSize) -> Unit,
) {
    val long = maxOf(fullWidth, fullHeight)
    val options = ExportSize.entries.filter { fullSizeEnabled || it != ExportSize.ORIGINAL }
    // the smallest option that keeps the original size, else the largest
    var selected by remember { mutableStateOf(options.firstOrNull { it.maxEdge >= long && it != ExportSize.ORIGINAL } ?: ExportSize.S4096) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.PanelRaised,
        title = { Text("导出 JPEG") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("原图 $fullWidth × $fullHeight", color = Palette.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许全尺寸处理", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = fullSizeEnabled, onCheckedChange = {
                        if (!it && selected == ExportSize.ORIGINAL) selected = ExportSize.S4096
                        onFullSizeEnabled(it)
                    })
                }
                Text("建议 16 GB 内存、64 位手机。导出前检查可用内存，不足时停止，不自动降尺寸。", fontSize = 11.sp, color = Palette.TextDim)
                if (hasBorder) Text("完整尺寸模式保留构图，本次不应用胶片片边；日期后背仍可保留。", fontSize = 11.sp, color = Palette.Amber)
                options.forEach { o ->
                    val edge = minOf(o.maxEdge, long)
                    val s = edge.toFloat() / long
                    val dims = "${(fullWidth * s).toInt()} × ${(fullHeight * s).toInt()}"
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = o == selected, onClick = { selected = o })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = o == selected, onClick = { selected = o },
                            colors = RadioButtonDefaults.colors(selectedColor = Palette.Amber),
                        )
                        Column {
                            Text(if (o == ExportSize.ORIGINAL) "完整尺寸（不缩图、不裁切）" else "长边 ${o.label}", color = Palette.Text, fontSize = 14.sp)
                            Text(if (o.maxEdge >= long) "$dims（原尺寸）" else dims, color = Palette.TextFaint, fontSize = 11.sp)
                        }
                    }
                }
                if (selected == ExportSize.ORIGINAL) {
                    val gb = com.spektralab.android.FullSizeExport.estimatedBytes(fullWidth,fullHeight,fullWidth,fullHeight)/1e9
                    Text("预计峰值内存约 %.1f GB；输出 JPEG，无法恢复相机拍摄时已裁切的像素。".format(gb), fontSize=11.sp, color=Palette.TextDim)
                }
                HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "颗粒与光晕按导出尺寸的真实像素尺度计算，与预览的细节会略有不同。",
                    color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(selected) }) { Text("导出", color = Palette.Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextDim) } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.PanelRaised,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = Palette.Amber) } },
        title = { Text("SpektraLab for Android") },
        text = {
            Text(
                "光谱胶片模拟：光线以 81 个波长穿过胶片染料层，经放大机滤色印到相纸，再在标准光源下扫描。" +
                    "颗粒、光晕、DIR 成色剂、相纸眩光均以物理单位建模。\n\n" +
                    "· 物理模型与胶片/相纸数据：spektrafilm（Andrea Volpato）\n" +
                    "  github.com/andreavolpato/spektrafilm · GPLv3 · 数据 CC BY-SA 4.0\n" +
                    "· 参考原生实现：SpektraLab\n" +
                    "  github.com/JamesQiu2005/SpektraLab\n" +
                    "· 本应用以 GPLv3 发布",
                fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Text,
            )
        },
    )
}

private fun openImage(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}
