package com.spektralab.android

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: EditorViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val source by vm.source.collectAsState()
    val result by vm.result.collectAsState()
    val state by vm.state.collectAsState()
    val rendering by vm.rendering.collectAsState()
    val films by vm.films.collectAsState()
    val papers by vm.papers.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.initialize(context) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.loadPhoto(context, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpektraLab · 光谱暗房", fontSize = 18.sp) },
                actions = {
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("选图")
                    }
                    TextButton(onClick = { vm.export(context) }, enabled = source != null && !rendering) {
                        Text("导出")
                    }
                    TextButton(onClick = { showAbout = true }) {
                        Text("关于")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF14100C),
                    titleContentColor = Color(0xFFE8DCC8),
                    actionIconContentColor = Color(0xFFD4B483),
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color(0xFF0E0C09),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .weight(1f, fill = false)
                    .aspectRatio(
                        (source?.width ?: 3).toFloat() / (source?.height ?: 4).toFloat(),
                    )
                    .background(Color.Black, RoundedCornerShape(8.dp)),
            ) {
                if (source == null) {
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .clickable {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("📷", fontSize = 44.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("点击选择一张照片", color = Color(0xFF9A8C74))
                        Text(
                            "拍摄 → 胶片显影 → 放大印相 → 扫描",
                            color = Color(0xFF6E6353), fontSize = 12.sp,
                        )
                    }
                } else {
                    CompareView(source!!, result)
                    if (rendering) {
                        CircularProgressIndicator(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(22.dp),
                            color = Color(0xFFD4B483),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            Controls(
                state = state,
                films = films,
                papers = papers,
                onUpdate = vm::update,
                onFilmPicked = { film ->
                    vm.update {
                        it.copy(filmStock = film, printStock = vm.preferredPaperFor(film))
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
            },
            title = { Text("SpektraLab for Android") },
            text = {
                Text(
                    "光谱胶片与相纸模拟器 —— spektrafilm 物理模型的安卓移植。\n\n" +
                        "光线按 81 个波长穿过胶片染料层与相纸层，" +
                        "颗粒、光晕、成色剂扩散、放大机滤光片均以物理单位建模。\n\n" +
                        "· 胶片模型与 28+ 档案数据：spektrafilm\n" +
                        "  (github.com/andreavolpato/spektrafilm)\n" +
                        "  GPLv-3.0 · 档案数据 CC BY-SA 4.0\n" +
                        "· 参考原生引擎：SpektraLab\n" +
                        "  (github.com/JamesQiu2005/SpektraLab)\n" +
                        "· 本应用以 GPLv-3.0 发布",
                    lineHeight = 20.sp,
                )
            },
        )
    }
}

/** Draggable before/after split view. */
@Composable
fun CompareView(source: Bitmap, result: Bitmap?) {
    var split by remember { mutableFloatStateOf(0.65f) }
    val srcImg = remember(source) { source.asImageBitmap() }
    val resImg = remember(result) { result?.asImageBitmap() }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    split = (change.position.x / size.width).coerceIn(0.02f, 0.98f)
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dst = fitRect(size, srcImg.width, srcImg.height)
            val offset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt())
            val dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt())
            if (resImg != null) {
                drawImage(resImg, dstOffset = offset, dstSize = dstSize)
            }
            // original on the left of the divider
            clipRect(right = size.width * split) {
                drawImage(srcImg, dstOffset = offset, dstSize = dstSize)
            }
            drawLine(
                color = Color(0xFFD4B483),
                start = Offset(size.width * split, 0f),
                end = Offset(size.width * split, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Text(
            "原图", color = Color(0xFFCCCCCC), fontSize = 11.sp,
            modifier = Modifier
                .padding(8.dp)
                .background(Color(0x88000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (resImg != null) {
            Text(
                "胶片", color = Color(0xFFD4B483), fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color(0x88000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun fitRect(size: Size, w: Int, h: Int): Rect {
    if (w <= 0 || h <= 0 || size.width <= 0f || size.height <= 0f)
        return Rect(Offset.Zero, Size.Zero)
    val scale = minOf(size.width / w, size.height / h)
    val dw = w * scale
    val dh = h * scale
    return Rect(
        Offset((size.width - dw) / 2f, (size.height - dh) / 2f),
        Size(dw, dh),
    )
}

@Composable
fun Controls(
    state: EditState,
    films: List<Pair<String, String>>,
    papers: List<Pair<String, String>>,
    onUpdate: ((EditState) -> EditState) -> Unit,
    onFilmPicked: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        SectionLabel("胶片")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            films.forEach { (stock, name) ->
                FilterChip(
                    selected = state.filmStock == stock,
                    onClick = { onFilmPicked(stock) },
                    label = { Text(shortFilmName(name)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF221D16),
                        labelColor = Color(0xFFC8BBA6),
                        selectedContainerColor = Color(0xFF6B4E2E),
                        selectedLabelColor = Color(0xFFFFE9C4),
                    ),
                )
            }
        }

        SectionLabel("相纸 / 印片材料")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            papers.forEach { (stock, name) ->
                FilterChip(
                    selected = state.printStock == stock,
                    onClick = { onUpdate { it.copy(printStock = stock) } },
                    label = { Text(shortPaperName(name)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF221D16),
                        labelColor = Color(0xFFC8BBA6),
                        selectedContainerColor = Color(0xFF4E4436),
                        selectedLabelColor = Color(0xFFEDE2CC),
                    ),
                )
            }
        }

        SectionLabel("画幅（影响颗粒与光晕的物理尺寸）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("35mm" to 35f, "120 六六" to 56f, "6×9" to 61f).forEach { (label, mm) ->
                FilterChip(
                    selected = state.filmFormatMm == mm,
                    onClick = { onUpdate { it.copy(filmFormatMm = mm) } },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF221D16),
                        labelColor = Color(0xFFC8BBA6),
                        selectedContainerColor = Color(0xFF3A4436),
                        selectedLabelColor = Color(0xFFD5E8CC),
                    ),
                )
            }
        }

        SliderRow("曝光补偿", "%.1f EV".format(state.exposureEv), -3f, 3f, state.exposureEv) {
            onUpdate { s -> s.copy(exposureEv = it) }
        }
        SliderRow("黄滤镜 Y", "%.0f CC".format(state.yFilterShift), -50f, 50f, state.yFilterShift) {
            onUpdate { s -> s.copy(yFilterShift = it) }
        }
        SliderRow("品红滤镜 M", "%.0f CC".format(state.mFilterShift), -50f, 50f, state.mFilterShift) {
            onUpdate { s -> s.copy(mFilterShift = it) }
        }
        SliderRow("相纸曝光", "×%.2f".format(state.printExposure), 0.5f, 2.5f, state.printExposure) {
            onUpdate { s -> s.copy(printExposure = it) }
        }
        SliderRow("颗粒强度", "×%.2f".format(state.grainAmount), 0f, 3f, state.grainAmount) {
            onUpdate { s -> s.copy(grainAmount = it) }
        }
        SliderRow("光晕强度", "×%.2f".format(state.halationAmount), 0f, 3f, state.halationAmount) {
            onUpdate { s -> s.copy(halationAmount = it) }
        }
        SliderRow("散射强度", "×%.2f".format(state.scatterAmount), 0f, 3f, state.scatterAmount) {
            onUpdate { s -> s.copy(scatterAmount = it) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF8A7C66),
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    min: Float,
    max: Float,
    current: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color(0xFFC8BBA6), fontSize = 13.sp)
            Text(value, color = Color(0xFF8A7C66), fontSize = 13.sp)
        }
        Slider(
            value = current,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}

private fun shortFilmName(name: String): String = when {
    name.contains("Portra") -> "Portra " + name.substringAfterLast(" ")
    name.contains("Gold") -> "Gold " + name.substringAfterLast(" ")
    name.contains("Ektar") -> "Ektar " + name.substringAfterLast(" ")
    name.contains("Ultramax") -> "Ultramax " + name.substringAfterLast(" ")
    name.contains("Vision3") -> "V3 " + name.substringAfterLast(" ")
    name.contains("Ektachrome") -> "Ektachrome"
    name.contains("Kodachrome") -> "Kodachrome 64"
    name.contains("Velvia") -> "Velvia 100"
    name.contains("Provia") -> "Provia 100F"
    name.contains("Pro 400H") || name.contains("400H") -> "Pro 400H"
    name.contains("C200") -> "C200"
    name.contains("X-TRA") || name.contains("Xtra") -> "X-TRA 400"
    name.contains("CineStill") || name.contains("800T") -> "CineStill 800T"
    name.contains("Verita") -> "Verita 200D"
    name.contains("HP5") -> "HP5 Plus"
    name.contains("Tri-X") -> "Tri-X 400"
    name.contains("T-Max") || name.contains("TMax") -> "T-Max 100"
    name.contains("ACROS") || name.contains("Acros") -> "Acros 100 II"
    else -> name
}

private fun shortPaperName(name: String): String = when {
    name.contains("Portra Endura") -> "Portra Endura"
    name.contains("Supra Endura") -> "Supra Endura"
    name.contains("Ultra Endura") -> "Ultra Endura"
    name.contains("Endura Premier") -> "Endura Premier"
    name.contains("Ektacolor") -> "Ektacolor Edge"
    name.contains("Crystal Archive") -> "Crystal Archive"
    name.contains("2383") -> "2383 印片"
    name.contains("2393") -> "2393 印片"
    name.contains("Multigrade") -> "Multigrade IV"
    else -> name
}
