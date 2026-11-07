package com.spektralab.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import androidx.annotation.RequiresApi
import com.spektralab.android.engine.SpektraNative
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Photo decoding (EXIF-oriented, sRGB, ARGB_8888) and saving. */
object PhotoIO {

    data class Info(val width: Int, val height: Int, val name: String)

    /** Oriented pixel size and display name, without decoding pixels. */
    fun info(context: Context, uri: Uri): Info? {
        val name = displayName(context, uri)
        if (isNativeFormat(context, uri, name)) {
            val file = materialize(context, uri, name)
            val size = SpektraNative.imageInfo(file.absolutePath)
            return Info(size[0], size[1], name)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var w = bounds.outWidth
        var h = bounds.outHeight
        if (w <= 0 || h <= 0) {
            // formats BitmapFactory cannot sniff (some HEIF): ask ImageDecoder
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { d, i, _ ->
                        w = i.size.width; h = i.size.height
                        d.setTargetSize(1, 1)
                    }
                }
            }
            if (w <= 0 || h <= 0) return null
        }
        if (orientationSwapsAxes(context, uri)) { val t = w; w = h; h = t }
        return Info(w, h, name)
    }

    /**
     * Decodes [uri] with its EXIF orientation applied, scaled so the long edge
     * is at most [maxEdge], as a software ARGB_8888 sRGB bitmap.
     */
    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val name = displayName(context, uri)
        if (isNativeFormat(context, uri, name)) {
            return SpektraNative.loadLinear(materialize(context, uri, name).absolutePath, maxEdge).use { it.bitmap() }
        }
        val bmp = if (Build.VERSION.SDK_INT >= 28) decodeP(context, uri, maxEdge)
        else decodeLegacy(context, uri, maxEdge)
        return bmp?.let { ensureArgb(it) }
    }

    @RequiresApi(28)
    private fun decodeP(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxEdge) {
                val s = maxEdge.toFloat() / long
                decoder.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }
        }
    }.getOrNull() ?: decodeLegacy(context, uri, maxEdge)

    private fun decodeLegacy(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val long = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val rotation = exifMatrix(context, uri)
        if (rotation != null) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, rotation, true)
        }
        val l = max(bmp.width, bmp.height)
        if (l > maxEdge) {
            val s = maxEdge.toFloat() / l
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).roundToInt().coerceAtLeast(1),
                (bmp.height * s).roundToInt().coerceAtLeast(1), true,
            )
        }
        return bmp
    }

    private fun ensureArgb(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888) b
        else b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() }

    /** Downscale to a long edge of [maxEdge] (no-op when already smaller). */
    fun scaled(b: Bitmap, maxEdge: Int): Bitmap {
        val l = max(b.width, b.height)
        if (l <= maxEdge) return b
        val s = maxEdge.toFloat() / l
        return Bitmap.createScaledBitmap(
            b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true,
        )
    }

    private fun exifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orientationSwapsAxes(context: Context, uri: Uri): Boolean =
        when (exifOrientation(context, uri)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
            else -> false
        }

    private fun exifMatrix(context: Context, uri: Uri): Matrix? {
        val m = Matrix()
        when (exifOrientation(context, uri)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path?.let(::File)?.name
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "photo"

    private fun isNativeFormat(context: Context, uri: Uri, name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (RAW_EXTENSIONS.any { lower.endsWith(it) } || TIFF_EXTENSIONS.any { lower.endsWith(it) }) return true
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: return false
        return mime.contains("tiff") || mime.contains("dng") || mime == "image/x-adobe-raw" ||
            mime == "application/x-raw" || listOf("sony", "nikon", "canon").any { mime.startsWith("image/x-$it") }
    }

    /** Non-null for high-precision formats. Their engine input must stay native. */
    fun nativePath(context: Context, uri: Uri): String? {
        val name = displayName(context, uri)
        return if (isNativeFormat(context, uri, name)) materialize(context, uri, name).absolutePath else null
    }

    // RAW MIME types vary across document providers; some export all RAW as
    // octet-stream. OpenDocument must allow those files too.
    val IMPORT_MIME_TYPES = arrayOf("image/*", "application/octet-stream")

    /** LibRaw and the TIFF reader need a seekable path; SAF can expose pipes. */
    private fun materialize(context: Context, uri: Uri, displayName: String): File {
        if (uri.scheme == "file") return File(requireNotNull(uri.path))
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        val suffix = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val extension = when {
            ".$suffix" in RAW_EXTENSIONS || ".$suffix" in TIFF_EXTENSIONS -> suffix
            mime.contains("tiff") -> "tif"
            else -> "dng" // LibRaw sniffs RAW containers regardless of camera brand.
        }
        val metadata = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0).toString() else ""
            }
        }.getOrNull().orEmpty()
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$uri|$displayName|$metadata".toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "native-imports").apply { mkdirs() }
        val out = File(dir, "$key.$extension")
        synchronized(this) {
            if (out.isFile && out.length() > 0) { out.setLastModified(System.currentTimeMillis()); return out }
            // Bound retained imports to the newest file; the editor owns one source.
            dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
            val tmp = File.createTempFile(key.take(12), ".partial", dir)
            try {
                val input = context.contentResolver.openInputStream(uri) ?: error("无法读取文件")
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                check(tmp.length() > 0 && tmp.renameTo(out)) { "无法缓存文件，请检查剩余空间" }
                return out
            } finally { tmp.delete() }
        }
    }

    private val TIFF_EXTENSIONS = setOf(".tif", ".tiff")
    private val RAW_EXTENSIONS = setOf(
        ".dng", ".arw", ".srf", ".sr2", ".nef", ".nrw", ".cr2", ".cr3", ".crw",
        ".raf", ".orf", ".rw2", ".pef", ".srw", ".3fr", ".iiq", ".erf", ".mos",
        ".mrw", ".x3f", ".raw", ".rwl", ".kdc", ".dcr", ".mef", ".fff", ".gpr",
    )

    /**
     * Saves [bitmap] as a JPEG into Pictures/SpektraLab and returns its uri.
     * API 29+: MediaStore with RELATIVE_PATH (no permission). API 26-28: a file
     * in the public Pictures directory (needs WRITE_EXTERNAL_STORAGE).
     */
    fun saveJpeg(context: Context, bitmap: Bitmap, baseName: String, quality: Int = 95): Uri {
        val name = "${baseName}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SpektraLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册条目")
            try {
                resolver.openOutputStream(uri)?.use { s ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, s))
                        throw IllegalStateException("JPEG 编码失败")
                } ?: throw IllegalStateException("无法写入相册")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SpektraLab")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建目录 ${dir.path}")
        val file = File(dir, name)
        FileOutputStream(file).use { s ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, s))
                throw IllegalStateException("JPEG 编码失败")
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
        return Uri.fromFile(file)
    }
}
