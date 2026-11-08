package com.spektralab.android.tether

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object TetherMedia {
    fun save(context: Context, file: File, name: String, video: Boolean): Uri {
        val mime = if (video) "video/mp4" else when(name.substringAfterLast('.').lowercase()) {
            "arw" -> "image/x-sony-arw"; "dng" -> "image/x-adobe-dng"
            "hif", "heif" -> "image/heif"; else -> "image/jpeg"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if(video) "Movies/SpektraLab" else "Download/SpektraLab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: error("无法创建保存文件")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法写入文件")
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, values, null, null)
                return uri
            } catch(e: Exception) { resolver.delete(uri,null,null); throw e }
        }
        val dir = File(context.getExternalFilesDir(if(video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "Tether").apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
        return Uri.fromFile(target)
    }
}
