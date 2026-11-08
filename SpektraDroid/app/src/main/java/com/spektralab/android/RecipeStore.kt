package com.spektralab.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FilmRecipe(val id: String, val name: String, val state: EditState)

/** Named recipes store processing settings; exposure hardware and source media stay separate. */
class RecipeStore(context: Context, storageName: String = "film_recipes") {
    private val prefs = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val _recipes = MutableStateFlow(read())
    val recipes: StateFlow<List<FilmRecipe>> = _recipes

    private fun read(): List<FilmRecipe> = runCatching {
        val array = JSONArray(prefs.getString("recipes", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            FilmRecipe(item.getString("id"), item.getString("name"), EditState.fromJson(item.getJSONObject("state")))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun write(items: List<FilmRecipe>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("state", it.state.toJson())) }
        check(prefs.edit().putString("recipes", array.toString()).commit()) { "配方保存失败，请检查剩余空间" }
        _recipes.value = items
    }

    private fun validName(name: String, excluding: String? = null): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 40) { "配方名称需为 1–40 个字" }
        require(_recipes.value.none { it.id != excluding && it.name.equals(trimmed, ignoreCase = true) }) { "已有同名配方，请使用其他名称" }
        return trimmed
    }

    /** Writes are called from IO; a unique name never silently replaces another recipe. */
    @Synchronized fun save(name: String, state: EditState): FilmRecipe {
        val item = FilmRecipe(UUID.randomUUID().toString(), validName(name), state)
        write(listOf(item) + _recipes.value)
        return item
    }

    @Synchronized fun rename(id: String, name: String) {
        require(_recipes.value.any { it.id == id }) { "配方已移除" }
        val trimmed = validName(name, id)
        write(_recipes.value.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    @Synchronized fun remove(id: String) { write(_recipes.value.filterNot { it.id == id }) }

    companion object {
        @Volatile private var instance: RecipeStore? = null
        fun get(context: Context): RecipeStore = instance ?: synchronized(this) {
            instance ?: RecipeStore(context).also { instance = it }
        }
    }
}
