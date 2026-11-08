package com.spektralab.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MediaSource(val label: String) { IMPORT("导入"), CAMERA("手机拍摄"), MONITOR("相机传输"), EXPORT("冲印成片") }

data class MediaItem(
    val id: String, val uri: String, val name: String, val source: MediaSource,
    val video: Boolean, val createdAt: Long, val recipe: EditState?, val parentUri: String? = null,
)

enum class MediaKind(val label: String) { ALL("全部"), PHOTO("照片"), RAW("RAW / TIFF"), VIDEO("视频") }

fun List<MediaItem>.matching(query: String = "", source: MediaSource? = null, kind: MediaKind = MediaKind.ALL): List<MediaItem> {
    val text = query.trim()
    return filter { item ->
        val native = item.name.substringAfterLast('.', "").lowercase() in setOf("arw", "nef", "nrw", "cr2", "cr3", "crw", "dng", "tif", "tiff", "raf", "rw2", "orf", "pef", "srw")
        (source == null || item.source == source) &&
            (text.isBlank() || item.name.contains(text, ignoreCase = true) || item.source.label.contains(text)) &&
            when (kind) {
                MediaKind.ALL -> true
                MediaKind.PHOTO -> !item.video
                MediaKind.RAW -> !item.video && native
                MediaKind.VIDEO -> item.video
            }
    }
}

/** Only app-created media and explicitly imported documents. Never scans the user's gallery. */
class MediaLibrary(context: Context, storageName: String = "media_library") {
    private val prefs = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<MediaItem>> = _items

    private fun read(): List<MediaItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching {
            val o = array.getJSONObject(i)
            MediaItem(o.getString("id"), o.getString("uri"), o.getString("name"),
                MediaSource.valueOf(o.getString("source")), o.optBoolean("video"), o.getLong("time"),
                o.optJSONObject("recipe")?.let(EditState::fromJson), o.optString("parent").takeIf { it.isNotBlank() })
        }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun write(items: List<MediaItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("id", item.id).put("uri", item.uri)
            .put("name", item.name).put("source", item.source.name).put("video", item.video)
            .put("time", item.createdAt).put("recipe", item.recipe?.toJson()).put("parent", item.parentUri)) }
        check(prefs.edit().putString("items", array.toString()).commit()) { "素材目录保存失败" }
        _items.value = items
    }

    /** Call on IO. Inserting the same URI is idempotent and preserves its original provenance. */
    @Synchronized fun add(uri: Uri, name: String, source: MediaSource, video: Boolean = false,
        recipe: EditState? = null, parentUri: String? = null): MediaItem {
        _items.value.firstOrNull { it.uri == uri.toString() }?.let { return it }
        val item = MediaItem(UUID.randomUUID().toString(), uri.toString(), name, source, video,
            System.currentTimeMillis(), recipe, parentUri)
        write(listOf(item) + _items.value)
        return item
    }

    @Synchronized fun rememberEdit(uri: Uri, state: EditState) {
        if (_items.value.none { it.uri == uri.toString() }) return
        write(_items.value.map { if (it.uri == uri.toString()) it.copy(recipe = state) else it })
    }

    /** Remove a stale private recovery URI after publishing the recovered file. */
    @Synchronized fun replaceUri(old: Uri, new: Uri) {
        if (_items.value.none { it.uri == old.toString() || it.parentUri == old.toString() }) return
        write(_items.value.map { it.copy(uri = if (it.uri == old.toString()) new.toString() else it.uri,
            parentUri = if (it.parentUri == old.toString()) new.toString() else it.parentUri) })
    }

    /** Catalog removal only; originals remain at their existing storage locations. */
    @Synchronized fun remove(id: String) { write(_items.value.filterNot { it.id == id }) }

    companion object {
        @Volatile private var instance: MediaLibrary? = null
        fun get(context: Context): MediaLibrary = instance ?: synchronized(this) {
            instance ?: MediaLibrary(context).also { instance = it }
        }
        fun displayName(context: Context, uri: Uri): String = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "照片"

        /** Sharing apps can grant only temporary read access. Retain such an
         * explicit import privately so a recent item still opens after reboot. */
        fun retainImport(context: Context, uri: Uri): Uri {
            if (uri.scheme == "file" || get(context).items.value.any { it.uri == uri.toString() }) return uri
            if (runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess) return uri
            val name = displayName(context, uri).substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(120)
            val dir = java.io.File(context.filesDir, "library-imports").apply { mkdirs() }
            val target = java.io.File(dir, "${UUID.randomUUID()}_$name")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("无法读取导入文件")
                check(target.length() > 0) { "导入文件为空" }
                return Uri.fromFile(target)
            } catch (e: Exception) { target.delete(); throw e }
        }
    }
}
