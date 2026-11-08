package com.spektralab.android.tether

import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.Closeable
import java.io.File

/** Encodes exactly the monitored frames. No microphone or camera-original video download. */
class MonitorRecorder(private val file: File, width: Int, height: Int) : Closeable {
    val width = width / 2 * 2
    val height = height / 2 * 2
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private var muxer: MediaMuxer? = null
    private var started = false
    private var track = -1
    private var frames = 0
    private var origin = 0L
    private var lastPts = -1L
    private var closed = false
    private val surface: android.view.Surface
    init {
        var createdSurface: android.view.Surface? = null
        try {
            codec.configure(MediaFormat.createVideoFormat("video/avc", this.width, this.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 1000) 5_000_000 else 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            createdSurface = codec.createInputSurface()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            surface = createdSurface
        } catch (e: Exception) { codec.release(); createdSurface?.release(); muxer?.release(); file.delete(); throw e }
    }
    fun frame(bitmap: Bitmap, parameters: String?, film: String?) {
        check(!closed)
        drain(false)
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.BLACK)
            val ratio = minOf(width.toFloat()/bitmap.width, height.toFloat()/bitmap.height)
            val w = bitmap.width * ratio; val h = bitmap.height * ratio
            canvas.drawBitmap(bitmap, null, RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2), Paint(Paint.FILTER_BITMAP_FLAG))
            if (!parameters.isNullOrBlank()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = height * .035f; typeface = Typeface.MONOSPACE }
                canvas.drawRect(0f, 0f, width.toFloat(), height * .14f, Paint().apply { color = 0xb0000000.toInt() })
                canvas.drawText(parameters, width * .02f, height * .055f, p)
                if (!film.isNullOrBlank()) canvas.drawText(film, width * .02f, height * .11f, p)
            }
        } finally { surface.unlockCanvasAndPost(canvas) }
        frames++
        drain(false)
    }
    private fun drain(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    check(SystemClock.elapsedRealtime() < deadline) { "视频编码结束超时" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started); track = muxer!!.addTrack(codec.outputFormat); muxer!!.start(); started = true
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(started)
                            if (lastPts < 0) origin = info.presentationTimeUs
                            info.presentationTimeUs = maxOf(lastPts + 1, info.presentationTimeUs - origin)
                            lastPts = info.presentationTimeUs
                            muxer!!.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
    /** Finishes an independently playable MP4, including on disconnect. */
    override fun close() {
        if (closed) return
        closed = true
        var valid = false
        try {
            codec.signalEndOfInputStream(); drain(true)
            if (started) { muxer!!.stop(); valid = frames > 0 && lastPts >= 0 }
        } finally {
            try { codec.stop() } finally { codec.release(); surface.release(); muxer?.release() }
            if (!valid) file.delete()
        }
    }
}
