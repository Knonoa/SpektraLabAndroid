package com.spektralab.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.spektralab.android.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipePicker(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val listHeight = (LocalConfiguration.current.screenHeightDp.dp * .4f).coerceIn(80.dp, 360.dp)
    val bodyHeight = LocalConfiguration.current.screenHeightDp.dp * .65f
    val store = remember(context) { RecipeStore.get(context) }
    val recipes by store.recipes.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val ready by vm.engineReady.collectAsState()
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FilmRecipe?>(null) }
    var deleting by remember { mutableStateOf<FilmRecipe?>(null) }
    var name by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(vm.state.value) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("胶片配方") }, text = {
        Column(Modifier.heightIn(max = bodyHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("保存当前胶片、相纸、效果与片边设置，在三个模式中调用。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                snapshot = vm.state.value; name = ""; renaming = null; naming = true; error = null
            }, enabled = !busy && !exporting && ready) { Text("保存当前设置为配方") }
            if (recipes.isEmpty()) Text("还没有配方，调好喜欢的效果后就可以保存。", color = Palette.TextDim)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recipes, key = { it.id }) { recipe ->
                    Column {
                        Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                        Text(catalog.film(recipe.state.filmStock)?.name ?: recipe.state.filmStock, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { error = vm.applyRecipe(recipe.state); if (error == null) onDismiss() }, enabled = ready && !exporting && !busy) { Text("应用") }
                            TextButton(onClick = { name = recipe.name; renaming = recipe; naming = true; error = null }, enabled = !busy) { Text("改名") }
                            TextButton(onClick = { deleting = recipe }, enabled = !busy) { Text("移除") }
                        }
                    }
                    HorizontalDivider()
                }
            }
            error?.let { Text(it, color = Palette.Error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })

    if (naming) AlertDialog(onDismissRequest = { if (!busy) naming = false }, title = { Text(if (renaming == null) "保存配方" else "配方改名") }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("配方名称") }, singleLine = true)
            error?.let { Text(it, color = Palette.Error) }
        }
    }, confirmButton = { TextButton(onClick = {
        busy = true; error = null
        val currentName = name; val selected = renaming; val captured = snapshot
        scope.launch {
            try {
                withContext(Dispatchers.IO) { if (selected == null) store.save(currentName, captured) else store.rename(selected.id, currentName) }
                naming = false
            } catch (e: Exception) { error = e.message ?: "保存失败" }
            finally { busy = false }
        }
    }, enabled = name.isNotBlank() && !busy) { Text(if (busy) "保存中" else "保存") } },
        dismissButton = { TextButton(onClick = { naming = false; error = null }, enabled = !busy) { Text("取消") } })

    deleting?.let { recipe -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("移除配方「${recipe.name}」？") },
        text = { Text("已拍摄素材和当前调整会保留。") }, confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                try { withContext(Dispatchers.IO) { store.remove(recipe.id) }; deleting = null }
                catch (e: Exception) { error = e.message }
                finally { busy = false }
            }
        }, enabled = !busy) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("取消") } }) }
}
