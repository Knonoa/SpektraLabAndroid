package com.spektralab.android.tether

import android.app.Application
import android.app.PendingIntent
import android.content.*
import android.graphics.*
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spektralab.android.EditState
import com.spektralab.android.MediaLibrary
import com.spektralab.android.MediaSource
import com.spektralab.android.engine.SpektraNative
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class TetherViewModel(app: Application) : AndroidViewModel(app) {
    val devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val status = MutableStateFlow("未连接相机")
    val connected = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val frame = MutableStateFlow<Bitmap?>(null)
    val parameters = MutableStateFlow("")
    val recording = MutableStateFlow(false)
    val recordRequested = MutableStateFlow(false)
    val lastPhoto = MutableStateFlow<Uri?>(null)
    val received = MutableStateFlow(0)
    val fps = MutableStateFlow(0f)
    val message = MutableStateFlow("")
    val resolution = MutableStateFlow(480)
    val filmEnabled = MutableStateFlow(false)
    val includeParameters = MutableStateFlow(false)
    private val manager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${app.packageName}.USB_CAMERA_PERMISSION"
    private var task: Job? = null
    @Volatile private var stopping = false
    private var selected: UsbDevice? = null
    private var permissionRequest: Uri? = null
    @Volatile private var lookState = EditState()
    fun updateLook(state: EditState) { lookState = state }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                if (permissionRequest == null || intent.data != permissionRequest) return
                permissionRequest = null
                val device = selected
                if (device != null && !stopping && manager.hasPermission(device)) start(device)
                else { busy.value = false; status.value = "USB 授权未获准，可重新选择相机" }
            } else {
                refresh()
                if (selected != null && manager.deviceList.values.none { it.deviceId == selected?.deviceId }) stop()
            }
        }
    }
    init {
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }
    fun refresh() { devices.value = SonyUsbCamera.devices(getApplication()) }
    fun connect(device: UsbDevice, state: EditState) {
        if (task?.isActive == true || busy.value) return
        stopping = false; selected = device; lookState = state; busy.value = true
        if (manager.hasPermission(device)) start(device)
        else {
            status.value = "请允许访问 USB 相机"
            permissionRequest = Uri.parse("spektralab://usb-permission/${device.deviceId}/${SystemClock.elapsedRealtimeNanos()}")
            manager.requestPermission(device, PendingIntent.getBroadcast(getApplication(), device.deviceId,
                Intent(permissionAction).setPackage(getApplication<Application>().packageName).setData(permissionRequest),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }
    fun stop() {
        stopping = true; permissionRequest = null; recordRequested.value = false
        if (task?.isActive != true) { busy.value = false; status.value = "已断开连接" }
    }
    fun setResolution(value: Int) { if (!recording.value && !recordRequested.value) resolution.value = value }
    fun demo(state: EditState) { if (task?.isActive != true && !busy.value) { selected = null; lookState = state; start(null) } }
    private fun start(device: UsbDevice?) {
        if (task?.isActive == true) return
        stopping = false; busy.value = true; message.value = ""; frame.value = null
        task = viewModelScope.launch(Dispatchers.IO) {
            var camera: SonyUsbCamera? = null
            var recorder: MonitorRecorder? = null
            var video: File? = null
            var currentLook: MonitorLook? = null
            var appliedState: EditState? = null
            var lastLookChange = 0L
            var videoRecipe: EditState? = null
            var lastPoll = 0L
            var lastFrame = SystemClock.elapsedRealtime()
            var count = 0
            var demoIndex = 0
            var demoPhotoAt = 0L
            var streamMissingSince = 0L
            fun finishRecording() {
                val r = recorder ?: return
                recorder = null; recording.value = false; recordRequested.value = false
                try {
                    r.close()
                    val f = video!!
                    if (f.isFile) {
                        try {
                            val name = "Monitor_${System.currentTimeMillis()}.mp4"
                            val uri = TetherMedia.save(getApplication(), f, name, true)
                            MediaLibrary.get(getApplication()).add(uri, name, MediaSource.MONITOR, true, videoRecipe)
                            f.delete(); message.value = "监看视频已保存"
                        } catch(e: Exception) {
                            message.value = "视频已保留在应用恢复目录，可重试保存：${e.message}"
                        }
                    }
                } finally { video = null }
            }
            try {
                if (device != null) {
                    status.value = "正在连接 Sony USB 相机"
                    // Retain ownership even if handshake or setup fails, so finally closes USB.
                    camera = SonyUsbCamera(PtpSession(AndroidPtpLink(manager, device)))
                    camera.connect()
                    status.value = "${camera.info.model} · USB 已连接"
                    message.value = camera.setupMessage
                } else { status.value = "演示模式 · 非实体相机"; parameters.value = "ISO 400 · f/2.8 · 1/125" }
                connected.value = true; busy.value = false
                while (isActive && !stopping) {
                    val now = SystemClock.elapsedRealtime()
                    if (camera != null && now - lastPoll >= 500) {
                        val p = try { camera.poll() } catch(e: PtpFailure) {
                            if(e.response==0x2019) { delay(100); continue } else throw e
                        }
                        lastPoll = now
                        parameters.value = shootingText(p)
                        if (camera.hasPhoto()) {
                            val info = camera.photoInfo()
                            val name = info.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_")
                                .take(90).takeIf { it.endsWith(".arw",true) || it.endsWith(".jpg",true) || it.endsWith(".jpeg",true) || it.endsWith(".hif",true) }
                                ?: "Capture_${now}.${if(info.format==0xb101) "ARW" else "JPG"}"
                            val f = File.createTempFile("tether-", ".partial", recoveryDir())
                            val photoRecipe = lookState
                            try {
                                check(f.usableSpace > info.bytes * 2 + 100L*1024*1024) { "手机剩余空间不足" }
                                camera.receive(f, info) { n -> message.value = "接收 $name · ${n*100/info.bytes}%" }
                                val target = File(f.parentFile,"${System.currentTimeMillis()}_$name")
                                check(f.renameTo(target)) { "无法完成照片保存" }
                                received.value++
                                try {
                                    lastPhoto.value = TetherMedia.save(getApplication(), target, target.name, false)
                                    MediaLibrary.get(getApplication()).add(lastPhoto.value!!, target.name, MediaSource.MONITOR, recipe = photoRecipe)
                                    target.delete(); message.value = "已接收 $name · 原文件已保存"
                                } catch(e: Exception) {
                                    lastPhoto.value = Uri.fromFile(target)
                                    MediaLibrary.get(getApplication()).add(lastPhoto.value!!, target.name, MediaSource.MONITOR, recipe = photoRecipe)
                                    message.value = "照片已保留在应用恢复目录：${e.message}"
                                }
                            } finally { f.delete() }
                        }
                    }
                    if (!recordRequested.value) finishRecording()
                    val raw = if (camera != null) camera.liveJpeg()?.let { BitmapFactory.decodeByteArray(it,0,it.size) }
                        else demoFrame(demoIndex++)
                    if (raw == null) {
                        if (streamMissingSince == 0L) streamMissingSince = now
                        if (now - streamMissingSince > 5000) { finishRecording(); status.value = "USB 已连接 · 暂无取景画面，请检查 PC 遥控设置" }
                        delay(80); continue
                    }
                    if (streamMissingSince > 0 && camera != null) status.value = "${camera.info.model} · USB 已连接"
                    streamMissingSince = 0
                    val scaled = fitPreview(raw, resolution.value)
                    if (scaled !== raw) raw.recycle()
                    val shown = if (filmEnabled.value) {
                        val state = lookState
                        if (currentLook == null || (state != appliedState && now-lastLookChange >= 180)) {
                            check(SpektraNative.init(getApplication<Application>().assets) == null)
                            currentLook = MonitorLook(state); appliedState = state; lastLookChange = now
                        }
                        currentLook!!.apply(scaled).also { scaled.recycle() }
                    } else scaled
                    if (recordRequested.value && recorder == null) {
                        video = File.createTempFile("monitor-", ".mp4", recoveryDir())
                        videoRecipe = if (filmEnabled.value) appliedState else null
                        recorder = MonitorRecorder(video!!, shown.width, shown.height); recording.value = true
                    }
                    if (!recordRequested.value) finishRecording()
                    if (recorder != null && recoveryDir().usableSpace < 120L*1024*1024) {
                        finishRecording(); message.value = "剩余空间不足，已结束录制并保存"
                    }
                    recorder?.frame(shown, if(includeParameters.value) parameters.value else null, if(filmEnabled.value) "胶片监看" else null)
                    frame.value = shown
                    count++
                    if (now - lastFrame >= 1000) { fps.value = count*1000f/(now-lastFrame); lastFrame=now; count=0 }
                    if (device == null && demoPhotoAt > 0) {
                        val f = File.createTempFile("demo-", ".jpg", getApplication<Application>().cacheDir)
                        try {
                            // Simulated originals must not bake in the monitor LUT.
                            val original = demoFrame(demoIndex)
                            try { f.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG,95,it) } } finally { original.recycle() }
                            lastPhoto.value = TetherMedia.save(getApplication(),f,"DEMO_${System.currentTimeMillis()}.jpg",false)
                            MediaLibrary.get(getApplication()).add(lastPhoto.value!!, "DEMO_${System.currentTimeMillis()}.jpg", MediaSource.MONITOR, recipe = lookState)
                            received.value++; message.value = "演示照片已接收（模拟快门）"
                        } finally { f.delete() }
                        demoPhotoAt = 0
                    }
                    if (demoCapture) { demoCapture = false; demoPhotoAt = now }
                    delay(if(device==null) 33 else 15)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.value = "连接已停止：${e.message}" }
            finally {
                try { finishRecording() } catch(e: Exception) { message.value = "视频保存失败：${e.message}" }
                camera?.finish(); connected.value = false; busy.value = false; recordRequested.value = false
                if (stopping) status.value = "已断开连接"
            }
        }
    }
    private fun recoveryDir() = File(getApplication<Application>().filesDir, "tether-recovery").apply { mkdirs() }
    fun recoverMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            var saved = 0
            recoveryDir().listFiles()?.filter { it.extension.lowercase() in listOf("arw","jpg","jpeg","hif","mp4") && it.length()>0 }?.forEach { f ->
                try {
                    val uri = TetherMedia.save(getApplication(),f,f.name,f.extension=="mp4")
                    val library = MediaLibrary.get(getApplication())
                    library.replaceUri(Uri.fromFile(f), uri)
                    library.add(uri, f.name, MediaSource.MONITOR, f.extension=="mp4")
                    if(f.extension!="mp4") lastPhoto.value=uri
                    f.delete(); saved++
                } catch(e: Exception) { message.value="重试保存失败：${e.message}";return@launch }
            }
            message.value="已恢复保存 $saved 个文件"
        }
    }
    @Volatile var demoCapture = false
    override fun onCleared() { stop(); getApplication<Application>().unregisterReceiver(receiver); super.onCleared() }
    companion object {
        fun fitPreview(source: Bitmap, resolution: Int): Bitmap {
            // 480p/720p bounding box; never crop or upscale a smaller camera feed.
            val ratio = minOf(1f, resolution*16f/9/source.width, resolution.toFloat()/source.height)
            if (ratio == 1f) return source
            return Bitmap.createScaledBitmap(source, (source.width*ratio).roundToInt().coerceAtLeast(2)/2*2,
                (source.height*ratio).roundToInt().coerceAtLeast(2)/2*2, true)
        }
        fun shootingText(p: Map<Int,SonyProperty>): String {
            val iso = p[0xd226]?.value ?: p[0xd21e]?.value ?: p[0x500f]?.value
            val aperture = p[0x5007]?.value
            val shutter = p[0xd20d]?.value ?: p[0xd229]?.value
            return listOfNotNull(iso?.let { "ISO ${it and 0xffffff}" }, aperture?.let { String.format(Locale.US,"f/%.1f",it/100.0) },
                shutter?.let { val a=it ushr 16; val b=it and 65535; if(a>0 && b>0) "$a/$b s" else null }).joinToString(" · ")
        }
        fun demoFrame(index: Int): Bitmap {
            val b = Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888)
            val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f,0f,960f,540f,Color.rgb(55,120,185),Color.rgb(220,165,75),Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,960f,540f,p); p.shader=null; p.color=Color.WHITE
            c.drawCircle((index*7%1000).toFloat(),270f,55f,p)
            p.textSize=32f; c.drawText("USB MONITOR DEMO",36f,60f,p)
            return b
        }
    }
}
