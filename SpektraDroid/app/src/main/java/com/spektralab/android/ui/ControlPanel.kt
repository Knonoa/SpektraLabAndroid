package com.spektralab.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.EditState
import com.spektralab.android.FilmFormat
import com.spektralab.android.engine.Catalog
import com.spektralab.android.engine.FilmStock

/** Everything the panel can ask of the editor. */
interface EditorActions {
    fun update(interactive: Boolean = false, transform: (EditState) -> EditState)
    fun commit()
    fun selectFilm(stock: String)
    fun selectPaper(stock: String)
    fun selectScanFilm()
    fun resetAdjustments()
}

private enum class PanelTab(val label: String) {
    FILM("胶片"), PRINT("相纸"), CAMERA("拍摄"), ENLARGER("放大机"), EFFECTS("效果"),
}

@Composable
fun ControlPanel(
    state: EditState,
    catalog: Catalog,
    actions: EditorActions,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Column(modifier.background(Palette.Panel)) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = Palette.Panel,
            contentColor = Palette.Text,
            indicator = { pos ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(pos[tab]),
                    color = Palette.Amber,
                    height = 2.dp,
                )
            },
            divider = { HorizontalDivider(color = Palette.Hairline) },
        ) {
            PanelTab.entries.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    modifier = Modifier.height(44.dp),
                    selectedContentColor = Palette.Amber,
                    unselectedContentColor = Palette.TextDim,
                ) {
                    Text(t.label, fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (PanelTab.entries[tab]) {
                PanelTab.FILM -> FilmTab(state, catalog, actions)
                PanelTab.PRINT -> PrintTab(state, catalog, actions)
                PanelTab.CAMERA -> ScrollTab { CameraTab(state, actions) }
                PanelTab.ENLARGER -> ScrollTab { EnlargerTab(state, actions) }
                PanelTab.EFFECTS -> ScrollTab { EffectsTab(state, actions) }
            }
        }
    }
}

@Composable
private fun ScrollTab(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) { content() }
}

// ------------------------------------------------------------------ film

private fun filmGroups(c: Catalog): List<Pair<String, List<FilmStock>>> = listOf(
    "彩色负片" to c.films.filter { !it.positive && !it.bw && !it.cine },
    "电影负片" to c.films.filter { !it.positive && !it.bw && it.cine },
    "反转片 · 正片" to c.films.filter { it.positive },
    "黑白负片" to c.films.filter { it.bw },
).filter { it.second.isNotEmpty() }

@Composable
private fun FilmTab(state: EditState, catalog: Catalog, actions: EditorActions) {
    val listState = rememberLazyListState()
    val groups = filmGroups(catalog)
    // open on the selected stock
    LaunchedEffect(catalog) {
        var index = 0
        for ((_, films) in groups) {
            index++
            val i = films.indexOfFirst { it.stock == state.filmStock }
            if (i >= 0) { listState.scrollToItem((index + i - 1).coerceAtLeast(0)); break }
            index += films.size
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
    ) {
        groups.forEach { (title, films) ->
            header(title)
            items(films, key = { it.stock }) { f ->
                val paper = catalog.paper(f.targetPrint)?.name
                StockRow(
                    name = f.name,
                    selected = f.stock == state.filmStock,
                    onClick = { actions.selectFilm(f.stock) },
                    badges = buildList {
                        if (f.stock == "cinestill_800t") add("无防光晕层")
                    },
                    subtitle = if (f.stock == state.filmStock) {
                        if (f.positive) "反转片：直接观看（不印相）" else paper?.let { "推荐相纸：$it" }
                    } else null,
                )
            }
        }
    }
}

private fun LazyListScope.header(title: String) {
    item(key = "h_$title") { SectionHeader(title, Modifier.padding(horizontal = 8.dp)) }
}

// ------------------------------------------------------------------ print

@Composable
private fun PrintTab(state: EditState, catalog: Catalog, actions: EditorActions) {
    val film = catalog.film(state.filmStock)
    val groups = listOf(
        "彩色相纸" to catalog.papers.filter { !it.bw && !it.cine },
        "电影拷贝片" to catalog.papers.filter { !it.bw && it.cine },
        "黑白相纸" to catalog.papers.filter { it.bw },
    ).filter { it.second.isNotEmpty() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
    ) {
        header("观看方式")
        item(key = "scan") {
            StockRow(
                name = "不印相 · 直接扫描胶片",
                selected = state.scanFilm,
                onClick = actions::selectScanFilm,
                badges = if (film?.positive == true) listOf("推荐") else emptyList(),
                subtitle = if (film?.positive == true) "反转片的观看方式" else "负片会显示为带橙色片基的负像",
            )
        }
        groups.forEach { (title, papers) ->
            header(title)
            items(papers, key = { it.stock }) { p ->
                val recommended = film?.targetPrint == p.stock
                StockRow(
                    name = p.name,
                    selected = !state.scanFilm && p.stock == state.printStock,
                    onClick = { actions.selectPaper(p.stock) },
                    badges = if (recommended) listOf("推荐") else emptyList(),
                    subtitle = if (film?.positive == true && !state.scanFilm && p.stock == state.printStock)
                        "反转片印在负性相纸上会得到负像" else null,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ camera

@Composable
private fun CameraTab(state: EditState, actions: EditorActions) {
    SectionHeader("曝光")
    SwitchRow(
        "自动测光",
        checked = state.autoExposure,
        caption = "中央重点测光，把画面中心曝光到 18% 灰",
        onChange = { v -> actions.update { it.copy(autoExposure = v) } },
    )
    ParamSlider(
        label = "胶片曝光",
        caption = if (state.autoExposure) "在测光基础上补偿" else "相对原片",
        value = state.exposureEv,
        range = EditState.EV_RANGE,
        default = 0f,
        format = { "%+.1f EV".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(exposureEv = snap(v, 0.1f)) } },
        onCommit = actions::commit,
    )
    SectionHeader("画幅", trailing = "长边 ${state.format.longEdgeMm.toInt()} mm")
    Pills(FilmFormat.entries, state.format, { it.label }) { f -> actions.update { it.copy(format = f) } }
    Text(
        "画幅决定每个像素对应的胶片物理尺寸：画幅越大，颗粒、光晕与散射相对画面越细。",
        color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
}

// ------------------------------------------------------------------ enlarger

@Composable
private fun EnlargerTab(state: EditState, actions: EditorActions) {
    val enabled = !state.scanFilm
    if (!enabled) {
        Notice("当前为“直接扫描胶片”，放大机参数不参与渲染。到「相纸」选择一种相纸即可启用。")
    }
    SectionHeader("曝光")
    ParamSlider(
        label = "亮度",
        caption = "放大机曝光",
        value = state.printBrightness,
        range = EditState.BRIGHTNESS_RANGE,
        default = 0f,
        enabled = enabled,
        format = { "%+.2f 档".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(printBrightness = snap(v, 0.05f)) } },
        onCommit = actions::commit,
    )
    SectionHeader("滤色 (CC)")
    ParamSlider(
        label = "黄",
        caption = "← 偏蓝 · 偏黄 →",
        value = state.yFilter,
        range = EditState.FILTER_RANGE,
        default = 0f,
        enabled = enabled,
        format = { "%+.0f".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(yFilter = snap(v, 1f)) } },
        onCommit = actions::commit,
    )
    ParamSlider(
        label = "品红",
        caption = "← 偏绿 · 偏品红 →",
        value = state.mFilter,
        range = EditState.FILTER_RANGE,
        default = 0f,
        enabled = enabled,
        format = { "%+.0f".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(mFilter = snap(v, 1f)) } },
        onCommit = actions::commit,
    )
    Text(
        "滤色值叠加在该胶片/相纸组合的中性滤色包上（柯达 CC 单位）。在暗房里，加黄让照片更蓝、加品红让照片更绿——这里的滑块已换算为照片的视觉方向。",
        color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// ------------------------------------------------------------------ effects

@Composable
private fun EffectsTab(state: EditState, actions: EditorActions) {
    SectionHeader("颗粒")
    SwitchRow("胶片颗粒", state.grainActive, { v -> actions.update { it.copy(grainActive = v) } },
        caption = "按银盐颗粒的泊松-二项模型逐层生成")
    ParamSlider(
        label = "强度", value = state.grainAmount, range = EditState.GRAIN_RANGE, default = 1f,
        enabled = state.grainActive, format = { "×%.2f".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(grainAmount = snap(v, 0.05f)) } },
        onCommit = actions::commit,
    )
    SectionHeader("光晕与散射")
    SwitchRow("光晕", state.halationActive, { v -> actions.update { it.copy(halationActive = v) } },
        caption = "片基背面反射造成的高光红晕，以及乳剂内的光散射")
    ParamSlider(
        label = "光晕强度", value = state.halationAmount, range = EditState.HALATION_RANGE, default = 1f,
        enabled = state.halationActive, format = { "×%.2f".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(halationAmount = snap(v, 0.05f)) } },
        onCommit = actions::commit,
    )
    ParamSlider(
        label = "散射", value = state.scatterAmount, range = EditState.SCATTER_RANGE, default = 1f,
        enabled = state.halationActive, format = { "%.0f%%".format(it * 100) },
        onChange = { v -> actions.update(interactive = true) { it.copy(scatterAmount = snap(v, 0.01f)) } },
        onCommit = actions::commit,
    )
    SectionHeader("色彩")
    SwitchRow("DIR 成色剂", state.couplersActive, { v -> actions.update { it.copy(couplersActive = v) } },
        caption = "层间抑制：决定胶片的色彩分离与边缘锐度")
    ParamSlider(
        label = "强度", value = state.couplersAmount, range = EditState.COUPLERS_RANGE, default = 1f,
        enabled = state.couplersActive, format = { "×%.2f".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(couplersAmount = snap(v, 0.05f)) } },
        onCommit = actions::commit,
    )
    SectionHeader("相纸与扫描")
    SwitchRow("相纸眩光", state.glareActive, { v -> actions.update { it.copy(glareActive = v) } },
        caption = "相纸表面的漫反射，轻微抬起暗部", enabled = !state.scanFilm)
    ParamSlider(
        label = "眩光强度", value = state.glareAmount, range = EditState.GLARE_RANGE, default = 1f,
        enabled = state.glareActive && !state.scanFilm, format = { "×%.1f".format(it) },
        onChange = { v -> actions.update(interactive = true) { it.copy(glareAmount = snap(v, 0.1f)) } },
        onCommit = actions::commit,
    )
    SwitchRow("扫描锐化", state.sharpen, { v -> actions.update { it.copy(sharpen = v) } },
        caption = "扫描仪的 USM（σ 0.7，强度 0.7）")
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = actions::resetAdjustments) { Text("恢复全部默认参数", color = Palette.Amber) }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        color = Palette.Text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .background(Palette.AmberDim.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

private fun snap(v: Float, step: Float): Float = Math.round(v / step) * step
