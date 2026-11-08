package com.spektralab.android.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spektralab.android.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** API 35 clamps this dialog's window to the bars but Compose can still measure
 * against the whole display. Read host insets, which the dialog has consumed. */
@Composable
private fun Modifier.mediaDialogSize(): Modifier {
    val screen = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    val hostBars = activity?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
        ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    val metricsVertical = if (android.os.Build.VERSION.SDK_INT >= 30) activity?.windowManager?.currentWindowMetrics?.windowInsets
        ?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        ?.let { with(density) { (it.top + it.bottom).toDp() } } else null
    val vertical = metricsVertical
        ?: hostBars?.let { with(density) { (it.top + it.bottom).toDp() } }
        ?: (bars.calculateTopPadding() + bars.calculateBottomPadding())
    val height = (screen.screenHeightDp.dp - vertical).coerceAtLeast(120.dp)
    return fillMaxWidth().height(height).padding(start = bars.calculateStartPadding(LocalLayoutDirection.current),
        end = bars.calculateEndPadding(LocalLayoutDirection.current))
}

// Full-sensor RAW unpacking is expensive even for a small thumbnail.
private val thumbnailGate = Semaphore(1)
private data class MediaPreview(val bitmap: Bitmap? = null, val loading: Boolean = true, val error: String? = null)

@Composable
private fun mediaBitmap(item: MediaItem, edge: Int, retry: Int = 0): State<MediaPreview> {
    val context = LocalContext.current
    return produceState(MediaPreview(), item.uri, edge, retry) {
        value = MediaPreview()
        try {
            val bitmap = withContext(Dispatchers.IO) { thumbnailGate.withPermit {
                val dir = File(context.cacheDir, "media-thumbnails").apply { mkdirs() }
                val cache = File(dir, "${item.id}-$edge.jpg")
                val uri = Uri.parse(item.uri)
                // Detail views must report an unavailable original even if an old thumbnail exists.
                if (edge > 160 || retry > 0) context.contentResolver.openInputStream(uri)?.use { check(it.read() >= 0) { "文件为空" } }
                    ?: error("无法读取文件")
                android.graphics.BitmapFactory.decodeFile(cache.path)?.let { return@withPermit it }
                val decoded = if (item.video) MediaMetadataRetriever().let { reader ->
                    try {
                        reader.setDataSource(context, uri)
                        if (android.os.Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
                        else reader.getFrameAtTime(0)?.let { frame ->
                            PhotoIO.scaled(frame, edge).also { if (it !== frame) frame.recycle() }
                        }
                    } finally { reader.release() }
                } else PhotoIO.decode(context, uri, edge)
                checkNotNull(decoded) { "格式暂不支持或文件已损坏" }
                // Cache failure must not hide a successfully decoded photo.
                runCatching {
                    val temp = File.createTempFile("preview-", ".jpg", dir)
                    try {
                        temp.outputStream().use { check(decoded.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                        temp.renameTo(cache)
                    } finally { temp.delete() }
                }
                decoded
            } }
            value = MediaPreview(bitmap, loading = false)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { value = MediaPreview(loading = false, error = e.message ?: "无法读取文件") }
    }
}

@Composable
private fun MediaThumbnail(item: MediaItem, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val preview by mediaBitmap(item, 160)
    Box(modifier.background(Palette.Canvas).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        val bitmap = preview.bitmap
        if (bitmap != null) Image(bitmap.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (preview.loading) "读取中" else "不可用", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        if (item.video) Text("▶", Modifier.align(Alignment.BottomEnd).background(Palette.Canvas).padding(2.dp))
        if (item.source == MediaSource.EXPORT) Text("成片", Modifier.align(Alignment.BottomStart).background(Palette.Canvas), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MediaStrip(library: MediaLibrary, source: MediaSource? = null, onEdit: (MediaItem) -> Unit, enabled: Boolean = true) {
    val all by library.items.collectAsState()
    val items = all.matching(source = source)
    var selectedId by remember { mutableStateOf<String?>(null) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().background(Palette.Panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("素材 · ${items.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) TextButton(onClick = { gallery = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("查看全部") }
        }
        if (items.isEmpty()) Text("拍摄、接收或导入的素材会保留在这里", color = Palette.TextDim,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        else LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.take(30), key = { it.id }) { item -> MediaThumbnail(item, Modifier.size(64.dp), enabled) { selectedId = item.id } }
        }
    }
    items.firstOrNull { it.id == selectedId }?.let { item -> MediaDetail(item, { selectedId = null }, { selectedId = null; onEdit(item) }, {
        selectedId = null; scope.launch(Dispatchers.IO) { library.remove(item.id) }
    }, onPrevious = items.getOrNull(items.indexOf(item)-1)?.let { previous -> { selectedId = previous.id } },
        onNext = items.getOrNull(items.indexOf(item)+1)?.let { next -> { selectedId = next.id } }) }
    if (gallery) MediaGallery(library, source, { gallery = false }, { gallery = false; onEdit(it) })
}

@Composable
private fun MediaGallery(library: MediaLibrary, initialSource: MediaSource?, onDismiss: () -> Unit, onEdit: (MediaItem) -> Unit) {
    val all by library.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(initialSource) }
    var kind by rememberSaveable { mutableStateOf(MediaKind.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val items = remember(all, query, source, kind) { all.matching(query, source, kind) }
    val scope = rememberCoroutineScope()
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.mediaDialogSize(), color = Palette.Background) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("返回") }
                    Text("素材库 · ${items.size}", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text("搜索文件名") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(source == null, { source = null }, label = { Text("全部来源") }) }
                    items(MediaSource.entries) { value -> FilterChip(source == value, { source = value }, label = { Text(value.label) }) }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MediaKind.entries) { value -> FilterChip(kind == value, { kind = value }, label = { Text(value.label) }) }
                }
                if (items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("没有符合条件的素材", color = Palette.TextDim) }
                else LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items, key = { it.id }) { item -> Column(Modifier.clickable { selectedId = item.id }) {
                        MediaThumbnail(item, Modifier.fillMaxWidth().aspectRatio(1f), true) { selectedId = item.id }
                        Text(item.name, style = MaterialTheme.typography.labelSmall)
                        Text(item.source.label, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    } }
                }
            }
        }
    }
    items.firstOrNull { it.id == selectedId }?.let { item -> MediaDetail(item, { selectedId = null }, { onEdit(item) }, {
        selectedId = null; scope.launch(Dispatchers.IO) { library.remove(item.id) }
    }, onPrevious = items.getOrNull(items.indexOf(item)-1)?.let { previous -> { selectedId = previous.id } },
        onNext = items.getOrNull(items.indexOf(item)+1)?.let { next -> { selectedId = next.id } }) }
}

@Composable
fun MediaDetail(item: MediaItem, onDismiss: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit,
    onPrevious: (() -> Unit)? = null, onNext: (() -> Unit)? = null) {
    key(item.id) {
        var retry by remember { mutableIntStateOf(0) }
        val preview = if (item.video) MediaPreview(loading = false) else mediaBitmap(item, 1600, retry).value
        var videoError by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.mediaDialogSize(), color = Palette.Background) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("返回") }
                        Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (!item.video) TextButton(onClick = onEdit) { Text(if (item.parentUri != null) "编辑原片" else "进入工作台") }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.Center) {
                        if (item.video && !videoError) VideoPlayback(item) { videoError = true }
                        else if (preview.bitmap != null) Image(preview.bitmap.asImageBitmap(), item.name,
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val limitX = size.width * (scale-1) / 2; val limitY = size.height * (scale-1) / 2
                                    offset = androidx.compose.ui.geometry.Offset((offset.x+pan.x).coerceIn(-limitX, limitX), (offset.y+pan.y).coerceIn(-limitY, limitY))
                                }
                            }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                        else if (preview.loading && !item.video) CircularProgressIndicator(color = Palette.Amber)
                        else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (videoError) "无法播放视频" else "无法读取素材", style = MaterialTheme.typography.titleMedium)
                            Text("原文件可能已移除、授权失效或格式不支持。可重新导入文件，或移除此记录。", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                            TextButton(onClick = { retry++; videoError = false }) { Text("重新读取") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Text("上一张") }
                        if (!item.video && scale > 1) TextButton(onClick = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) { Text("恢复缩放") }
                        TextButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("下一张") }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                    Text("${item.source.label} · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))}" +
                        if (item.parentUri != null) " · 已关联原片" else "", modifier = Modifier.padding(horizontal = 12.dp), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    if (!item.video && item.source != MediaSource.EXPORT) Text("当前查看原片；进入工作台后应用胶片设置并导出成片。",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    TextButton(onClick = onRemove) { Text("从素材列表移除（保留原文件）", color = Palette.TextDim) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayback(item: MediaItem, onError: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    var video by remember { mutableStateOf<VideoView?>(null) }
    val latestError by rememberUpdatedState(onError)
    AndroidView(factory = { ctx -> VideoView(ctx).apply {
        video = this
        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
        setVideoURI(Uri.parse(item.uri)); setOnPreparedListener { start() }
        setOnErrorListener { _, _, _ -> latestError(); true }
    } }, onRelease = { it.stopPlayback(); video = null }, modifier = Modifier.fillMaxSize())
    DisposableEffect(owner, video) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) video?.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
