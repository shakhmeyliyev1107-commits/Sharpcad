package com.sharpcad.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens

/** One value the user can type for the entity just drawn, e.g. "Length". */
class DimensionField(val label: String, val value: Double)

/**
 * Controls shown while sketching: plane choice, drawing tools, and — right after an
 * entity is drawn — fields to type its exact size.
 *
 * @param planesEnabled false once the sketch has geometry, since it cannot change plane.
 * @param selectedTool label of the active tool, or `null` when navigating.
 * @param dimensions fields for the entity just drawn; empty hides the dimension row.
 * @param onDimension called with the field index and the entered millimetres.
 * @param message one-line feedback such as the remaining degrees of freedom.
 */
@Composable
fun SketchControls(
    planes: List<String>,
    selectedPlane: String,
    planesEnabled: Boolean,
    onPlane: (String) -> Unit,
    tools: List<String>,
    selectedTool: String?,
    onTool: (String) -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    dimensions: List<DimensionField>,
    onDimension: (index: Int, millimetres: Double) -> Unit,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SharpCadDimens.PanelRadius))
            .background(SharpCadColors.Panel.copy(alpha = 0.94f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            planes.forEach { plane ->
                Chip(plane, selected = plane == selectedPlane, enabled = planesEnabled || plane == selectedPlane) {
                    onPlane(plane)
                }
            }
            Separator()
            tools.forEach { tool -> Chip(tool, selected = tool == selectedTool) { onTool(tool) } }
            Separator()
            Chip("Clear") { onClear() }
            Chip("Done", accent = true) { onDone() }
        }
        if (dimensions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                dimensions.forEachIndexed { index, field ->
                    // Keyed on the value so a re-solve refreshes what the field shows.
                    androidx.compose.runtime.key(field.label, field.value) {
                        DimensionInput(field) { onDimension(index, it) }
                    }
                }
            }
        }
        if (message.isNotEmpty()) {
            Text(message, color = SharpCadColors.ContentMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        selected || accent -> SharpCadColors.Accent
        else -> SharpCadColors.PanelRaised
    }
    val content = when {
        !enabled -> SharpCadColors.ContentDisabled
        selected || accent -> SharpCadColors.OnAccent
        else -> SharpCadColors.Content
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = content, fontSize = 13.sp)
    }
}

@Composable
private fun Separator() {
    Spacer(Modifier.width(1.dp).height(20.dp).background(SharpCadColors.Divider))
}

@Composable
private fun DimensionInput(field: DimensionField, onCommit: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatMillimetres(field.value)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    val commit = { if (parsed != null) onCommit(parsed) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(field.label, color = SharpCadColors.ContentMuted, fontSize = 12.sp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(
                color = if (parsed != null) SharpCadColors.Content else SharpCadColors.Error,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(SharpCadColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SharpCadColors.Background)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text("mm", color = SharpCadColors.ContentMuted, fontSize = 12.sp)
        Chip("Set", enabled = parsed != null) { commit() }
    }
}

/** Up to three decimals, without trailing zeros: 12.5, 40, 0.125. */
internal fun formatMillimetres(value: Double): String {
    val rounded = Math.round(value * 1000.0) / 1000.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}
