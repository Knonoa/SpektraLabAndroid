package com.spektralab.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            color = Palette.TextFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, color = Palette.TextFaint, fontSize = 11.sp)
    }
}

/**
 * Labelled slider. [onChange] fires continuously while dragging, [onCommit]
 * on release. A reset button appears when the value is off its default.
 */
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    caption: String? = null,
    steps: Int = 0,
) {
    Column(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            if (caption != null) {
                Spacer(Modifier.width(6.dp))
                Text(caption, color = Palette.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            val changed = abs(value - default) > 1e-4f
            if (changed && enabled) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clickable { onChange(default); onCommit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = "重置$label",
                        tint = Palette.TextDim, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                format(value),
                style = NumberStyle,
                color = if (changed) Palette.Amber else Palette.TextDim,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Palette.Amber,
                activeTrackColor = Palette.Amber,
                inactiveTrackColor = Palette.Hairline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            if (caption != null) Text(caption, color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.AmberInk,
                checkedTrackColor = Palette.Amber,
                uncheckedThumbColor = Palette.TextDim,
                uncheckedTrackColor = Palette.PanelRaised,
                uncheckedBorderColor = Palette.Hairline,
            ),
        )
    }
}

/** A selectable stock row: name, optional badges, check mark when selected. */
@Composable
fun StockRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    badges: List<String> = emptyList(),
    subtitle: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (selected) Palette.AmberDim.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (selected) Palette.Text else Palette.Text.copy(alpha = 0.9f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, color = Palette.TextFaint, fontSize = 11.sp, maxLines = 2)
        }
        badges.forEach { Badge(it) }
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Palette.Amber, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun Badge(text: String, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) Palette.Amber else Palette.TextDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(1.dp, if (accent) Palette.AmberDim else Palette.Hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Single-choice pill row (film formats etc.). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> Pills(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            Text(
                label(o),
                color = if (sel) Palette.AmberInk else Palette.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(if (sel) Palette.Amber else Palette.PanelRaised, RoundedCornerShape(16.dp))
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
