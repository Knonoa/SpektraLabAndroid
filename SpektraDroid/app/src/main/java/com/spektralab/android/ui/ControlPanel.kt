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
import androidx.compose.material3.ScrollableTabRow
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
import com.spektralab.android.DateBackCorner
import com.spektralab.android.DateBackFace
import com.spektralab.android.DateBackOrder
import com.spektralab.android.DateBackPlacement
import com.spektralab.android.DateBackSettings
import com.spektralab.android.EditState
import com.spektralab.android.FilmEdgeFormat
import com.spektralab.android.FilmEdgeGate
import com.spektralab.android.FilmEdgeHoles
import com.spektralab.android.FilmEdgeSettings
import com.spektralab.android.FilmEdgeView
import com.spektralab.android.FilmFormat
import com.spektralab.android.ShootingData
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
    fun newFilmEdgeBody()
}

private enum class PanelTab(val label: String) {
    FILM("胶片"), PRINT("相纸"), CAMERA("拍摄"), ENLARGER("放大机"), EFFECTS("效果"), EDGE("片边"),
}

@Composable
fun ControlPanel(
    state: EditState,
    catalog: Catalog,
    actions: EditorActions,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    /** the open photo's EXIF, for what the date back will print */
    shooting: ShootingData? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Column(modifier.background(Palette.Panel)) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 0.dp,
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
                    Text(t.label, fontSize = 13.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
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
                PanelTab.EDGE -> ScrollTab { EdgeTab(state, actions, shooting) }
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
    if (state.filmEdge.active) {
        Notice("胶片边框已开启：画幅由「片边」里的胶片格式决定（${state.filmEdge.format.label}）。")
    }
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

// ------------------------------------------------------------------ film edge + date back

@Composable
private fun EdgeTab(state: EditState, actions: EditorActions, shooting: ShootingData?) {
    val edge = state.filmEdge
    val db = state.dateBack
    fun edit(t: (FilmEdgeSettings) -> FilmEdgeSettings, interactive: Boolean = false) =
        actions.update(interactive) { it.copy(filmEdge = t(it.filmEdge)) }
    fun editDate(t: (DateBackSettings) -> DateBackSettings, interactive: Boolean = false) =
        actions.update(interactive) { it.copy(dateBack = t(it.dateBack)) }

    SectionHeader("胶片边框")
    SwitchRow(
        "胶片边框", edge.active,
        { v -> edit({ e -> e.copy(active = v, format = if (v && !e.active) FilmEdgeFormat.of(state.format) else e.format) }) },
        caption = "把画面放回整条胶片：片基、齿孔，以及这款胶片自己的片边字样、DX 码与帧号——作为底片上的曝光一起显影。画面会按片门比例居中裁切。",
    )
    if (edge.active) {
        Text("胶片格式", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Pills(FilmEdgeFormat.entries, edge.format, { it.label }) { f -> edit({ it.copy(format = f) }) }
        Text("扫描范围", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Pills(FilmEdgeView.entries, edge.view, { it.label }) { v -> edit({ it.copy(view = v) }) }
        Text("片门形状", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Pills(FilmEdgeGate.entries, edge.gate, { it.label }) { g -> edit({ it.copy(gate = g) }) }
        if (edge.format.perforated) {
            Text("齿孔", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Pills(FilmEdgeHoles.entries, edge.holes, { it.label }) { h -> edit({ it.copy(holes = h) }) }
        }
        ParamSlider(
            label = "帧号", caption = "片边印的帧号；0 = 随机",
            value = edge.frameNumber.toFloat(), range = 0f..99f, default = 0f, steps = 98,
            format = { if (it < 0.5f) "随机" else it.toInt().toString() },
            onChange = { v -> edit({ it.copy(frameNumber = v.toInt().coerceIn(0, 99)) }, interactive = true) },
            onCommit = actions::commit,
        )
        ParamSlider(
            label = "边缘雾化", caption = "片盒漏进的光让片边泛起暖色",
            value = edge.fog, range = FilmEdgeSettings.FOG_RANGE, default = 1f,
            format = { "×%.1f".format(it) },
            onChange = { v -> edit({ it.copy(fog = snap(v, 0.1f)) }, interactive = true) },
            onCommit = actions::commit,
        )
        ParamSlider(
            label = "漏光", caption = "卷轴边缘的漏光斑",
            value = edge.leaks, range = FilmEdgeSettings.LEAKS_RANGE, default = 0f,
            format = { "×%.1f".format(it) },
            onChange = { v -> edit({ it.copy(leaks = snap(v, 0.1f)) }, interactive = true) },
            onCommit = actions::commit,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions::newFilmEdgeBody) { Text("换一台相机（机身 #${edge.cameraSeed}）", color = Palette.Amber) }
        }
    }

    SectionHeader("日期打印")
    val camera = state.dateCamera
    val hasDate = shooting?.hasDate == true
    val dataLine = shooting?.dataText.orEmpty()
    SwitchRow(
        "日期打印", db.active, { v -> editDate({ it.copy(active = v) }) },
        caption = when {
            !camera.hasDateBack -> "${camera.label} 宽幅相机没有日期后背"
            shooting == null -> "从照片 EXIF 读取拍摄日期，像胶片相机的日期后背一样曝光在底片上"
            hasDate -> "将打印：${shooting.dateText(db.order)}"
            else -> "这张照片没有拍摄日期（EXIF）"
        },
        enabled = camera.hasDateBack,
    )
    if (db.active && camera.hasDateBack) {
        Text("样式", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Pills(DateBackFace.entries, db.face, { it.label }) { f -> editDate({ it.copy(face = f) }) }
        if (db.face != DateBackFace.DATA) {
            Text("顺序", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Pills(DateBackOrder.entries, db.order, { it.label }) { o -> editDate({ it.copy(order = o) }) }
            SwitchRow(
                "同时打印拍摄数据", db.withData, { v -> editDate({ it.copy(withData = v) }) },
                caption = (if (dataLine.isNotEmpty()) "「$dataLine」 · " else "快门 光圈 模式 补偿 焦距 · ") +
                    when {
                        !edge.active -> "印在画面内日期旁"
                        camera.is120 -> "印在片门外的片边空白处"
                        else -> "印在两帧之间"
                    },
            )
        }
        if (!camera.is120 && db.face != DateBackFace.DATA) {
            Text("位置", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Pills(DateBackPlacement.entries, db.placement, { it.label }) { pl -> editDate({ it.copy(placement = pl) }) }
            if (db.placement == DateBackPlacement.BETWEEN && !edge.active) {
                Text("「帧间」需要开启胶片边框，否则仍印在画面内。", color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (db.placement == DateBackPlacement.FRAME || !edge.active) {
            Text("角落", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Pills(DateBackCorner.entries, db.corner, { it.label }) { c -> editDate({ it.copy(corner = c) }) }
        }
        ParamSlider(
            label = "字号", value = db.size, range = DateBackSettings.SIZE_RANGE, default = 1f,
            format = { "×%.2f".format(it) },
            onChange = { v -> editDate({ it.copy(size = snap(v, 0.05f)) }, interactive = true) },
            onCommit = actions::commit,
        )
        ParamSlider(
            label = "亮度", caption = "日期灯在底片红感层上的曝光",
            value = db.brightnessEv, range = DateBackSettings.BRIGHTNESS_RANGE, default = 3.5f,
            format = { "%+.1f EV".format(it) },
            onChange = { v -> editDate({ it.copy(brightnessEv = snap(v, 0.1f)) }, interactive = true) },
            onCommit = actions::commit,
        )
    }
    Text(
        "边框与日期都按 SpektraLab 的方式作为底片上的曝光模拟：片边字样随胶片显影出颜色，日期从片背曝光到红感层，光晕和颗粒一样作用在它们身上。",
        color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
        modifier = Modifier.padding(top = 14.dp),
    )
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
