package com.sharpcad.ui.workspace

import com.sharpcad.ui.designsystem.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens

/** One row of the history panel. [error] is set when the feature failed to rebuild. */
class HistoryItem(val id: Long, val label: String, val error: String?)

/** The feature history, oldest first. Tap a row to edit it. */
@Composable
fun HistoryPanel(
    items: List<HistoryItem>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(220.dp)
            .clip(RoundedCornerShape(SharpCadDimens.PanelRadius))
            .background(SharpCadColors.Panel)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tr("History"), color = SharpCadColors.Content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (items.isEmpty()) {
            Text(tr("No features yet"), color = SharpCadColors.ContentMuted, fontSize = 12.sp)
        }
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                val selected = item.id == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) SharpCadColors.Accent.copy(alpha = 0.22f) else SharpCadColors.PanelRaised)
                        .clickable { onSelect(item.id) }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        color = SharpCadColors.ContentMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(22.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(tr(item.label), color = SharpCadColors.Content, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.error?.let {
                            Text(it, color = SharpCadColors.Error, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A row of mutually exclusive options, e.g. New body / Join / Cut. */
class ChoiceField(val label: String, val options: List<String>, val selected: String)

/** A button in a panel. [accent] marks the main action. */
class PanelAction(val label: String, val accent: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * A floating panel of parameters and actions: the editor for the selected feature, and
 * the contextual tools for the current selection.
 */
@Composable
fun ParameterPanel(
    title: String,
    fields: List<DimensionField>,
    onField: (index: Int, value: Double) -> Unit,
    choices: List<ChoiceField>,
    onChoice: (index: Int, option: String) -> Unit,
    actions: List<PanelAction>,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(SharpCadDimens.PanelRadius))
            .background(SharpCadColors.Panel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr(title), color = SharpCadColors.Content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (fields.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fields.forEachIndexed { index, field ->
                    key(index, field.label, field.value) { DimensionInput(field) { onField(index, it) } }
                }
            }
        }
        choices.forEachIndexed { index, choice ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(choice.label), color = SharpCadColors.ContentMuted, fontSize = 12.sp)
                choice.options.forEach { option ->
                    Chip(option, selected = option == choice.selected) { onChoice(index, option) }
                }
            }
        }
        if (actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                actions.forEach { Chip(it.label, accent = it.accent, enabled = it.enabled, onClick = it.onClick) }
            }
        }
        if (message.isNotEmpty()) {
            Text(tr(message), color = SharpCadColors.ContentMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(1.dp))
    }
}
