package com.spektralab.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spektralab.android.EditState
import com.spektralab.android.EditorViewModel

@Composable
fun rememberEditorActions(vm: EditorViewModel): EditorActions = remember(vm) {
    object : EditorActions {
        override fun update(interactive: Boolean, transform: (EditState) -> EditState) = vm.update(interactive, transform)
        override fun commit() = vm.commit()
        override fun selectFilm(stock: String) = vm.selectFilm(stock)
        override fun selectPaper(stock: String) = vm.selectPaper(stock)
        override fun selectScanFilm() = vm.selectScanFilm()
        override fun resetAdjustments() = vm.resetAdjustments()
        override fun newFilmEdgeBody() = vm.newFilmEdgeBody()
    }
}

/** A compact in-page panel keeps the live view visible while the recipe changes. */
@Composable
fun LiveFilmControls(vm: EditorViewModel, monitor: Boolean = false, height: androidx.compose.ui.unit.Dp = 220.dp) {
    val state by vm.state.collectAsState()
    val catalog by vm.catalog.collectAsState()
    var recipes by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { recipes = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("胶片配方 · 保存 / 调用") }
        Text(if (monitor) "色调调整实时生效；颗粒、光晕、片边和日期在照片冲印时应用。"
            else "调整实时胶片预览；片边和日期在照片冲印时应用。", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        ControlPanel(state, catalog, rememberEditorActions(vm), Modifier.fillMaxWidth().height(height))
    }
    if (recipes) RecipePicker(vm) { recipes = false }
}
