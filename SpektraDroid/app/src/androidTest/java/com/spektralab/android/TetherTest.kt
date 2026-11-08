package com.spektralab.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.android.engine.SpektraNative
import com.spektralab.android.tether.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class TetherTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun le(vararg values: Int) = ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    private fun container(type: Int, code: Int, tx: Int, data: ByteArray = byteArrayOf()) =
        ByteBuffer.allocate(12+data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(12+data.size).putShort(type.toShort()).putShort(code.toShort()).putInt(tx).put(data).array()
    private class FragmentLink(private val incoming: ByteArray, val chunk: Int) : PtpLink {
        var offset = 0
        val sent = ByteArrayOutputStream()
        override fun read(buffer: ByteArray): Int {
            val n = minOf(chunk, buffer.size, incoming.size-offset)
            if(n<=0) return -1
            incoming.copyInto(buffer,0,offset,offset+n);offset+=n;return n
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(3,length); sent.write(buffer,offset,n);return n
        }
        override fun close() {}
    }
    @Test fun fragmentedCoalescedStreamingAndResponseErrors() {
        val photo=ByteArray(100_000) { (it*13).toByte() }
        for(chunk in listOf(1,7,16384)) {
            val link=FragmentLink(container(2,0x1009,1,photo)+container(3,0x2001,1)+container(3,0x2019,2),chunk)
            PtpSession(link).use { ptp ->
                val out=ByteArrayOutputStream();var last=0L
                ptp.execute(0x1009,7,sink=out,limit=photo.size.toLong(),progress={assertTrue(it>last);last=it})
                assertArrayEquals(photo,out.toByteArray());assertEquals(photo.size.toLong(),last)
                val error=assertThrows(PtpFailure::class.java) { ptp.execute(0x9209) }
                assertEquals(0x2019,error.response)
            }
            assertEquals(28,link.sent.size())
        }
        assertThrows(IOException::class.java) { PtpSession(FragmentLink(container(2,0x1009,99),12)).execute(0x1009) }
        assertThrows(IOException::class.java) { PtpSession(FragmentLink(container(2,0x1009,1,photo),12)).execute(0x1009,limit=10) }
        assertThrows(IOException::class.java) { PtpSession(FragmentLink(byteArrayOf(12,0),1)).execute(0x1009) }
    }
    @Test fun usbZeroLengthPacketBetweenDataAndResponseIsNotDisconnect() {
        val photo=ByteArray(500) { it.toByte() } // 12-byte container + 500-byte data = one 512-byte USB packet.
        val input=FragmentLink(container(2,0x1009,1,photo)+container(3,0x2001,1),512)
        var emitted=false
        val link=object:PtpLink {
            override fun read(buffer:ByteArray):Int {
                if(input.offset==512 && !emitted) { emitted=true;return 0 }
                return input.read(buffer)
            }
            override fun write(buffer:ByteArray,offset:Int,length:Int)=input.write(buffer,offset,length)
            override fun close() {}
        }
        assertArrayEquals(photo,PtpSession(link).use { it.execute(0x1009) })
        assertTrue(emitted)
    }
    @Test fun sonyNewAndOldPropertiesAndEmbeddedJpegOffset() {
        // Standard enum followed by a second enum on 2024+ cameras. No second list on old bodies.
        val first = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0xd222.toShort());putShort(4);put(1);put(1);putShort(16);putShort(17);put(2);putShort(0);putShort(1);putShort(17)
        }.array()
        val second = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0xd215.toShort());putShort(4);put(0);put(2);putShort(0);putShort(0x8001.toShort());put(0)
        }.array()
        val p=SonyDatasets.properties(le(2,0)+first+second)
        assertEquals(listOf(17L),p[0xd222]!!.choices);assertEquals(0x8001L,p[0xd215]!!.value)
        assertFalse(p[0xd215]!!.writable)
        assertThrows(IllegalArgumentException::class.java) { SonyDatasets.properties(le(2,0)+first+second.copyOf(4)) }
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe0.toByte(),1,2,0xff.toByte(),0xd9.toByte())
        val header=le(16,0,0,0).also { it[6]=0xff.toByte();it[7]=0xd8.toByte() }
        assertArrayEquals(jpeg,SonyDatasets.jpeg(header+jpeg+byteArrayOf(0,0)))
        assertNull(SonyDatasets.jpeg(byteArrayOf(1,2,3)))
        assertEquals(11,SonyDatasets.models.size)
    }
    @Test fun prematureSonyPhotoReadIsPrevented() {
        val camera=SonyUsbCamera(PtpSession(FragmentLink(byteArrayOf(),1)))
        assertFalse(camera.hasPhoto())
        assertThrows(IllegalStateException::class.java) { camera.photoInfo() }
        camera.close()
    }
    @Test fun monitorRecordsBothSizesAndParameterChoiceAndFilmLut() {
        assertNull(SpektraNative.init(context.assets))
        val look=MonitorLook(EditState())
        for(resolution in listOf(480,720)) for(overlay in listOf(false,true)) {
            val file=File(context.cacheDir,"test-monitor-$resolution-$overlay.mp4")
            val source=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff8d6644.toInt()) }
            val scaled=TetherViewModel.fitPreview(source,resolution)
            val start=SystemClock.elapsedRealtime()
            val filtered=look.apply(scaled)
            assertFalse(scaled.sameAs(filtered))
            Log.i("TetherTest","LUT ${filtered.width}x${filtered.height}: ${SystemClock.elapsedRealtime()-start}ms")
            val moving = filtered.copy(Bitmap.Config.ARGB_8888,true)
            try {
                MonitorRecorder(file,filtered.width,filtered.height).use { recorder ->
                    repeat(18) { i ->
                        Canvas(moving).apply {
                            drawBitmap(filtered,0f,0f,null)
                            drawCircle(moving.width*(.1f+i*.8f/17),moving.height*.6f,30f,Paint().apply { color=Color.WHITE })
                        }
                        recorder.frame(moving,if(overlay) "ISO 400 · f/2.8 · 1/125" else null,"Portra 400");Thread.sleep(34)
                    }
                }
                assertTrue(file.length()>1000)
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    assertEquals(1,extractor.trackCount)
                    val format=extractor.getTrackFormat(0)
                    assertEquals("video/avc",format.getString(MediaFormat.KEY_MIME))
                    assertEquals(if(resolution==480) 852 else 1280,format.getInteger(MediaFormat.KEY_WIDTH))
                    assertEquals(resolution,format.getInteger(MediaFormat.KEY_HEIGHT))
                    extractor.selectTrack(0);var count=0;var last=-1L
                    while(extractor.sampleTime>=0) { assertTrue(extractor.sampleTime>last);last=extractor.sampleTime;count++;if(!extractor.advance()) break }
                    assertTrue("$count encoded frames",count>=15)
                    assertTrue("duration=$last",last>400_000)
                    Log.i("TetherTest","MP4 $resolution overlay=$overlay frames=$count durationUs=$last bytes=${file.length()}")
                } finally { extractor.release() }
                val decoder=MediaMetadataRetriever()
                try {
                    decoder.setDataSource(file.path)
                    val first=decoder.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST)!!
                    val last=decoder.getFrameAtTime(550_000,MediaMetadataRetriever.OPTION_CLOSEST)!!
                    try {
                        assertFalse("Motion survives encoding",first.sameAs(last))
                        val expected=filtered.getPixel(filtered.width/2,filtered.height/2)
                        val actual=first.getPixel(first.width/2,first.height/2)
                        for(shift in listOf(0,8,16)) assertTrue("Film colour survives H264",kotlin.math.abs((actual shr shift and 255)-(expected shr shift and 255))<20)
                        val top=first.getPixel(first.width*9/10,first.height*3/100)
                        fun light(c: Int)=Color.red(c)+Color.green(c)+Color.blue(c)
                        if(overlay) assertTrue("Parameter strip visible",light(top)<light(expected)*.6)
                        else assertTrue("Clean frame has no strip",kotlin.math.abs(light(top)-light(expected))<35)
                    } finally { first.recycle();last.recycle() }
                } finally { decoder.release() }
            } finally { file.delete();source.recycle();if(scaled!==source)scaled.recycle();filtered.recycle();moving.recycle() }
        }
    }
    @Test fun demoReceivesPhotosAndFinalizesRecordingsOnStopAndReconnect() {
        val app=context.applicationContext as android.app.Application
        lateinit var vm:TetherViewModel
        val store=androidx.lifecycle.ViewModelStore()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            vm=androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[TetherViewModel::class.java]
        }
        fun waitFor(message:String, condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+15_000
            while(!condition() && SystemClock.elapsedRealtime()<end) Thread.sleep(40)
            assertTrue("$message: ${vm.status.value} / ${vm.message.value}",condition())
        }
        fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
        val captures=mutableListOf<android.net.Uri>()
        val library = MediaLibrary.get(context)
        val existing = library.items.value.map { it.id }.toSet()
        try {
            repeat(2) { index ->
                main { vm.demo(EditState());vm.setResolution(if(index==0)480 else 720) }
                waitFor("Demo starts") { vm.connected.value && vm.frame.value!=null }
                vm.demoCapture=true
                waitFor("Photo received") { vm.received.value==index+1 }
                captures+=requireNotNull(vm.lastPhoto.value)
                PhotoIO.decode(context,captures.last(),1024)!!.also { assertTrue(it.width>0);it.recycle() }
                vm.recordRequested.value=true
                waitFor("Recording starts") { vm.recording.value }
                Thread.sleep(650)
                main { vm.stop() } // The same stop called by the screen's ON_STOP observer.
                waitFor("Disconnect completes") { !vm.connected.value && !vm.busy.value }
                assertFalse(vm.recording.value);assertFalse(vm.recordRequested.value)
                assertEquals("监看视频已保存",vm.message.value)
            }
            assertNotEquals(captures[0],captures[1])
        } finally {
            main { vm.stop() }
            waitFor("Cleanup") { !vm.connected.value && !vm.busy.value }
            main { store.clear() }
            library.items.value.filter { it.id !in existing }.forEach {
                context.contentResolver.delete(android.net.Uri.parse(it.uri), null, null)
                library.remove(it.id)
            }
        }
    }
    @Test fun sonyHandshakeTransferAndDisconnectRestore() {
        fun text(s: String) = byteArrayOf((s.length+1).toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0,0)
        val di = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(100).putInt(17).putShort(100).array() +
            text("Sony") + byteArrayOf(0,0) + le(1) + byteArrayOf(2,0x92.toByte()) + le(0,0,0,0) + text("Sony") + text("ILCE-7M5") + text("1.0") + text("test")
        fun property(code: Int, value: Int, options: List<Int>) = ByteBuffer.allocate(13+options.size*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(code.toShort());putShort(4);put(1);put(1);putShort(value.toShort());putShort(value.toShort());put(2);putShort(options.size.toShort()); options.forEach { putShort(it.toShort()) }
        }.array()
        fun properties(destination: Int, original: Int, pending: Int) = le(3,0) + property(0xd222,destination,listOf(16,17)) +
            property(0xd268,original,listOf(1,2)) + property(0xd215,pending,emptyList())
        val photograph=ByteArray(65537) { (it*3).toByte() }
        val oi=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(1);putShort(0xb101.toShort());putShort(0);putInt(photograph.size)
            putShort(0);repeat(3){putInt(0)};putInt(9504);putInt(6336);putInt(14);putInt(0);putShort(0);putInt(0);putInt(0)
        }.array()+text("DSC00001.ARW")
        val steps=listOf(Triple(0x1002,null,null),Triple(0x1001,di,null),Triple(0x9201,byteArrayOf(),null),
            Triple(0x9201,byteArrayOf(),null),Triple(0x9202,byteArrayOf(0x2c,1),null),Triple(0x9201,byteArrayOf(),null),
            Triple(0x9209,properties(16,2,0),null),Triple(0x9205,null,17),Triple(0x9205,null,1),
            Triple(0x9209,properties(17,1,0),null),Triple(0x9209,properties(17,1,0x8001),null),
            Triple(0x1008,oi,null),Triple(0x1009,photograph,null),Triple(0x9205,null,2),Triple(0x9205,null,16),Triple(0x1003,null,null))
        val responses=ByteArrayOutputStream()
        steps.forEachIndexed { id, step ->
            step.second?.let { responses.write(container(2,step.first,id,it)) }
            responses.write(container(3,0x2001,id))
        }
        val link=FragmentLink(responses.toByteArray(),31)
        val camera=SonyUsbCamera(PtpSession(link))
        val file=File(context.cacheDir,"test-usb-photo.arw")
        try {
            camera.connect();assertEquals("ILCE-7M5",camera.info.model);assertFalse(camera.hasPhoto())
            camera.poll();assertTrue(camera.hasPhoto());val objectInfo=camera.photoInfo()
            assertEquals("DSC00001.ARW",objectInfo.name);camera.receive(file,objectInfo) {}
            assertArrayEquals(photograph,file.readBytes());camera.finish()
            val stream=ByteBuffer.wrap(link.sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            var command=0
            while(stream.remaining()>0) {
                val begin=stream.position();val length=stream.int;val type=stream.short.toInt();val code=stream.short.toInt() and 65535;val tx=stream.int
                if(type==1) { assertEquals(command,tx);assertEquals(steps[command].first,code);command++ }
                stream.position(begin+length)
            }
            assertEquals(steps.size,command)
        } finally { file.delete();camera.close() }
    }
    @Test fun fullSizeDecodeKeepsActivePixelsAndRejectsUnsafeBudget() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val f=File(context.cacheDir,"full-size.dng")
        try {
            instrumentation.context.assets.open("import/synthetic.dng").use { input -> f.outputStream().use { input.copyTo(it) } }
            SpektraNative.loadLinear(f.path,0).use { full ->
                val dimensions=SpektraNative.imageInfo(f.path)
                assertArrayEquals(dimensions,intArrayOf(full.width,full.height))
                SpektraNative.loadLinear(f.path,16).use { assertTrue(it.width<full.width) }
            }
        } finally { f.delete() }
        val small=FullSizeExport.estimatedBytes(1000,1000,1000,1000)
        val large=FullSizeExport.estimatedBytes(9504,6336,9504,6336)
        assertTrue(large>6_000_000_000)
        assertTrue(small<1_000_000_000)
        assertThrows(IllegalStateException::class.java) { FullSizeExport.checkMemory(context,Long.MAX_VALUE) }
    }
    @Test fun editorFullSizeExportPreservesDimensionsWithoutChangingBorderSetting() = kotlinx.coroutines.runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val prefsName="full-export-test-${SystemClock.elapsedRealtimeNanos()}"
        val app=object:android.app.Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(prefsName,mode)
        }
        val store=androidx.lifecycle.ViewModelStore()
        lateinit var vm:EditorViewModel
        val f=File(context.cacheDir,"full-export-editor.dng")
        val library = MediaLibrary.get(context)
        var saved:android.net.Uri?=null
        val message=kotlinx.coroutines.CompletableDeferred<UiMessage>()
        var observer:kotlinx.coroutines.Job?=null
        fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
        suspend fun waitFor(condition:()->Boolean) {
            kotlinx.coroutines.withTimeout(15_000) { while(!condition()) kotlinx.coroutines.delay(30) }
        }
        try {
            instrumentation.context.assets.open("import/synthetic.dng").use { i -> f.outputStream().use { i.copyTo(it) } }
            main { vm=androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[EditorViewModel::class.java] }
            waitFor { vm.engineReady.value }
            main { vm.loadPhoto(android.net.Uri.fromFile(f)) }
            waitFor { vm.photo.value!=null && !vm.loadingPhoto.value }
            main { vm.update { it.copy(filmEdge=it.filmEdge.copy(active=true)) };vm.enableFullSize(true) }
            waitFor { vm.result.value!=null && !vm.rendering.value }
            observer=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                vm.messages.collect { if(it is UiMessage.Saved || it is UiMessage.Text && it.text.startsWith("导出失败")) message.complete(it) }
            }
            main { vm.export(ExportSize.ORIGINAL) }
            val result=kotlinx.coroutines.withTimeout(15_000) { message.await() }
            assertTrue("$result",result is UiMessage.Saved)
            saved=(result as UiMessage.Saved).uri
            val info=PhotoIO.info(context,saved!!)!!
            val dimensions=SpektraNative.imageInfo(f.path)
            assertArrayEquals(dimensions,intArrayOf(info.width,info.height))
            assertTrue("Border preference remains enabled",vm.state.value.filmEdge.active)
            waitFor { !vm.exporting.value }
        } finally {
            observer?.cancel();main { store.clear() };f.delete()
            saved?.let { context.contentResolver.delete(it,null,null) }
            library.items.value.filter { it.uri == android.net.Uri.fromFile(f).toString() || it.uri == saved?.toString() }
                .forEach { library.remove(it.id) }
            context.deleteSharedPreferences(prefsName)
        }
    }
}
