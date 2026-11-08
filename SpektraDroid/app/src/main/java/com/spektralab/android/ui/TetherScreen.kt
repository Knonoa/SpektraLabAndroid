package com.spektralab.android.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spektralab.android.BuildConfig
import com.spektralab.android.EditState
import com.spektralab.android.EditorViewModel
import com.spektralab.android.MediaLibrary
import com.spektralab.android.MediaSource
import com.spektralab.android.MediaItem
import com.spektralab.android.tether.SonyDatasets
import com.spektralab.android.tether.TetherViewModel
import java.util.Locale

@Composable
fun TetherScreen(editor: EditorViewModel, state: EditState, filmName: String, onBack: () -> Unit, onPhoto: (MediaItem) -> Unit) {
    val vm: TetherViewModel = viewModel()
    val devices by vm.devices.collectAsState()
    val status by vm.status.collectAsState()
    val connected by vm.connected.collectAsState()
    val busy by vm.busy.collectAsState()
    val frame by vm.frame.collectAsState()
    val params by vm.parameters.collectAsState()
    val recording by vm.recording.collectAsState()
    val requested by vm.recordRequested.collectAsState()
    val received by vm.received.collectAsState()
    val resolution by vm.resolution.collectAsState()
    val film by vm.filmEnabled.collectAsState()
    val include by vm.includeParameters.collectAsState()
    val fps by vm.fps.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var leaving by remember { mutableStateOf(false) }
    var editPhoto by remember { mutableStateOf<MediaItem?>(null) }
    var showFilm by remember { mutableStateOf(false) }
    val library = remember(context) { MediaLibrary.get(context) }
    SideEffect { vm.updateLook(state) }
    fun leave(edit: MediaItem? = null) {
        editPhoto = edit; leaving = true; vm.stop()
        if (!connected && !busy) { leaving=false; if(edit!=null) onPhoto(edit) else onBack() }
    }
    LaunchedEffect(connected, busy) {
        if (leaving && !connected && !busy) { leaving=false; editPhoto?.let(onPhoto) ?: onBack() }
    }
    DisposableEffect(owner) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) vm.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); vm.stop() }
    }
    BackHandler { leave() }
    CompositionLocalProvider(LocalContentColor provides Palette.Text) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val previewHeight = minOf(maxWidth*9/16,maxHeight*.45f,420.dp)
            val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
            Column(Modifier.fillMaxSize().background(Palette.Background)) {
                Row(Modifier.fillMaxWidth().padding(end=16.dp), verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={leave()}) { Icon(Icons.Outlined.ArrowBack, "返回") }
                    Column(Modifier.weight(1f)) { Text("监视器", fontSize=20.sp); Text(status,fontSize=12.sp,color=Palette.TextDim) }
                    if(recording) Text("● 录制中", color=Palette.Error)
                }
                if (connected) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.recordRequested.value = !requested }, enabled = frame != null || requested,
                        modifier = Modifier.weight(1f)) { Text(if (requested) "停止并保存视频" else "录制监看画面") }
                    OutlinedButton(onClick = vm::stop) { Text("断开") }
                }
                val preview: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(Palette.Canvas), contentAlignment=Alignment.Center) {
                    if (frame != null) Image(frame!!.asImageBitmap(),"相机实时取景",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    else Text(if(busy) "正在等待相机" else "USB 直连索尼相机",color=Palette.TextDim)
                    if(include && frame!=null) Column(Modifier.align(Alignment.TopStart).background(Palette.Canvas.copy(alpha=.7f)).padding(8.dp)) {
                        Text(params, fontSize=12.sp); if(film) Text(filmName, fontSize=11.sp)
                    }
                }

                }
                val controls: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    MediaStrip(library, MediaSource.MONITOR, { leave(it) }, enabled = !recording && !requested)
                    if (showFilm) { LiveFilmControls(editor, monitor = true) }

                    Text("${resolution}p 上限 · ${String.format(Locale.US,"%.1f",fps)} fps · 已接收 $received 张",fontSize=12.sp,color=Palette.TextDim)
                    if(frame!=null) Text("有效画面 ${frame!!.width} × ${frame!!.height}",fontSize=12.sp,color=Palette.TextDim)
                    if(message.isNotBlank()) Text(message,fontSize=13.sp,color=Palette.Amber)
                    OutlinedButton(onClick={showFilm=!showFilm},modifier=Modifier.fillMaxWidth()) { Text(if(showFilm) "收起胶片参数" else "调整胶片参数") }
                    if(!connected) {
                        Text("相机设为「PC 遥控」或「远程拍摄」，连接方式选择 USB。手机需支持 OTG，使用可传数据的线缆。",fontSize=13.sp)
                        Text("PC 保存：电脑＋相机；传输尺寸：原始大小；文件格式按需选择 RAW＋JPEG。请关闭占用相机的其他应用。",fontSize=12.sp,color=Palette.TextDim)
                        devices.forEach { device ->
                            Button(onClick={vm.connect(device,state)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                                Text("连接 Sony ${SonyDatasets.models[device.productId] ?: device.productName.orEmpty()}")
                            }
                        }
                        if(devices.isEmpty()) Text("未检测到 Sony USB 设备",color=Palette.TextDim)
                        TextButton(onClick=vm::refresh,enabled=!busy) { Text("重新检测") }
                        TextButton(onClick=vm::recoverMedia,enabled=!busy) { Text("重试保存未完成的文件") }
                        if(BuildConfig.DEBUG) OutlinedButton(onClick={vm.demo(state)},enabled=!busy) { Text("模拟器演示") }
                        if(busy) TextButton(onClick={vm.stop()}) { Text("停止等待") }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(480,720).forEach { value -> FilterChip(selected=resolution==value,onClick={vm.setResolution(value)},enabled=!recording&&!requested,label={Text("${value}p")}) }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("胶片监看"); Text(filmName,fontSize=12.sp,color=Palette.TextDim) }
                        Switch(checked=film,onCheckedChange={vm.filmEnabled.value=it},enabled=!recording&&!requested)
                    }
                    Text("实时滤镜保留胶片色调，使用轻量颜色查找表；不模拟照片中的颗粒、光晕和空间效果。",fontSize=12.sp,color=Palette.TextDim)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("录入相机参数",Modifier.weight(1f)); Switch(checked=include,onCheckedChange={vm.includeParameters.value=it},enabled=!recording&&!requested)
                    }
                    Text("参数由相机返回后绘制；关闭后录制纯取景流。若相机已把文字烧入画面，请在相机中关闭显示。",fontSize=12.sp,color=Palette.TextDim)
                    if(connected) {
                        Text("MP4 · 无音频 · 分辨率不超过输入画面。传输照片时取景可能暂停；退到后台或断开连接会结束并保存视频。",fontSize=12.sp,color=Palette.TextDim)
                        if(status.startsWith("演示")) OutlinedButton(onClick={vm.demoCapture=true}) { Text("模拟相机快门") }
                    }
                    Text("目标：A7 V / A1 II / A7R V / A7 IV / A1 / A6700 / A7R IV / A7R III / A7 III。当前按公开 USB 协议适配，实体机兼容性待逐台验证。",fontSize=12.sp,color=Palette.TextDim)
                    Spacer(Modifier.height(16.dp))
                }
                }
                if (landscape) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        preview(Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
                    }
                } else {
                    preview(Modifier.fillMaxWidth().height(if (showFilm) previewHeight * .72f else previewHeight))
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { controls() }
                }
            }
        }
    }
}
