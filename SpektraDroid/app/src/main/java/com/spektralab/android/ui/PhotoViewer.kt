package com.spektralab.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 6f

/**
 * The photo canvas. Fit-to-view by default; pinch / double-tap to zoom, drag
 * to pan; press and hold to see the original; the split button turns on a
 * draggable before/after divider.
 */
@Composable
fun PhotoViewer(
    before: Bitmap,
    after: Bitmap?,
    rendering: Boolean,
    modifier: Modifier = Modifier,
) {
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after?.asImageBitmap() }
    var scale by remember(before) { mutableFloatStateOf(1f) }
    var offset by remember(before) { mutableStateOf(Offset.Zero) }
    var split by remember { mutableStateOf(false) }
    var splitPos by remember { mutableFloatStateOf(0.5f) }
    var holding by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    // With Film Edge the result is the film canvas, not the photo's shape:
    // lay out by what is shown, and fit each image into it at its own aspect.
    val aspect = (after ?: before).let { it.width.toFloat() / it.height }
    val hasResult = afterImg != null
    LaunchedEffect(before, hasResult) {
        if (hasResult) { delay(5000); hintVisible = false }
    }

    Box(modifier.background(Palette.Canvas).clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(split, aspect) {
                    if (split) {
                        detectDragGestures(
                            onDragStart = { splitPos = (it.x / size.width).coerceIn(0f, 1f) },
                        ) { change, _ ->
                            splitPos = (change.position.x / size.width).coerceIn(0f, 1f)
                            change.consume()
                        }
                    } else {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val view = Size(size.width.toFloat(), size.height.toFloat())
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val center = Offset(view.width / 2f, view.height / 2f)
                            // keep the point under the fingers fixed while zooming
                            val k = newScale / scale
                            val o = (offset + center - centroid) * k + centroid - center + pan
                            scale = newScale
                            offset = clampOffset(o, newScale, view, aspect)
                        }
                    }
                }
                .pointerInput(split, aspect) {
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            if (split) return@detectTapGestures
                            val view = Size(size.width.toFloat(), size.height.toFloat())
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val target = 2.5f
                                val center = Offset(view.width / 2f, view.height / 2f)
                                scale = target
                                offset = clampOffset((center - pos) * target, target, view, aspect)
                            }
                        },
                        onPress = {
                            if (split) return@detectTapGestures
                            val job = scope.launch {
                                delay(220)
                                holding = true
                                hintVisible = false
                            }
                            tryAwaitRelease()
                            job.cancel()
                            holding = false
                        },
                    )
                },
        ) {
            val dst = imageRect(size, aspect, scale, offset)
            val main = if (holding || afterImg == null) beforeImg else afterImg
            drawPhoto(main, dst)
            if (split && afterImg != null && !holding) {
                val x = size.width * splitPos
                clipRect(right = x) { drawPhoto(beforeImg, dst) }
                drawLine(Color(0xCCFFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                drawCircle(Color(0xEE1A1714), radius = 14.dp.toPx(), center = Offset(x, size.height / 2))
                drawCircle(Palette.Amber, radius = 14.dp.toPx(), center = Offset(x, size.height / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
        }

        // ---- overlays ----
        if (split && afterImg != null && !holding) {
            Tag("原图", Modifier.align(Alignment.TopStart).padding(10.dp))
            Tag("胶片", Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 44.dp), accent = true)
        }
        if (holding) Tag("原图", Modifier.align(Alignment.TopStart).padding(10.dp))
        if (rendering) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp),
                color = Palette.Amber,
                strokeWidth = 2.dp,
                trackColor = Color(0x33FFFFFF),
            )
        }
        AnimatedVisibility(
            visible = hintVisible && afterImg != null && !split,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        ) {
            Text(
                "长按对比原图 · 双击放大",
                color = Palette.TextDim,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (scale > 1.05f && !split) {
                OverlayButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                    Icon(Icons.Outlined.ZoomOutMap, contentDescription = "适合屏幕", modifier = Modifier.size(20.dp))
                }
            }
            if (afterImg != null) {
                OverlayButton(
                    selected = split,
                    onClick = {
                        split = !split
                        hintVisible = false
                        if (split) { scale = 1f; offset = Offset.Zero; splitPos = 0.5f }
                    },
                ) {
                    Icon(Icons.Outlined.Compare, contentDescription = "分屏对比", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(selected: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) Palette.Amber else Color(0xAA000000),
            contentColor = if (selected) Palette.AmberInk else Palette.Text,
        ),
    ) { content() }
}

@Composable
private fun Tag(text: String, modifier: Modifier, accent: Boolean = false) {
    Row(modifier) {
        Text(
            text,
            color = if (accent) Palette.Amber else Palette.Text,
            fontSize = 11.sp,
            modifier = Modifier
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private data class Dst(val left: Float, val top: Float, val width: Float, val height: Float)

private fun imageRect(view: Size, aspect: Float, scale: Float, offset: Offset): Dst {
    val viewAspect = view.width / view.height
    val (w, h) = if (aspect > viewAspect) view.width to view.width / aspect
    else view.height * aspect to view.height
    val dw = w * scale
    val dh = h * scale
    return Dst((view.width - dw) / 2f + offset.x, (view.height - dh) / 2f + offset.y, dw, dh)
}

private fun clampOffset(o: Offset, scale: Float, view: Size, aspect: Float): Offset {
    val r = imageRect(view, aspect, scale, Offset.Zero)
    val mx = max(0f, (r.width - view.width) / 2f)
    val my = max(0f, (r.height - view.height) / 2f)
    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
}

private fun DrawScope.drawPhoto(img: ImageBitmap, box: Dst) {
    // contain: an image of another aspect (the original beside a film canvas) is centred in the box
    val a = img.width.toFloat() / img.height
    val (w, h) = if (a > box.width / box.height) box.width to box.width / a else box.height * a to box.height
    val dst = Dst(box.left + (box.width - w) / 2f, box.top + (box.height - h) / 2f, w, h)
    drawImage(
        img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(max(1, dst.width.roundToInt()), max(1, dst.height.roundToInt())),
        filterQuality = if (min(dst.width / img.width, dst.height / img.height) > 1f) FilterQuality.Low else FilterQuality.High,
    )
}
