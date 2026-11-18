package com.sharpcad.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens

/** One entry on a rail. [selected] marks the active workspace. */
internal class RailItem(val label: String, val icon: ImageVector, val selected: Boolean = false)

/** Label of the rail item that toggles sketch mode. */
internal const val SKETCH_ITEM = "Sketch"

/** Label of the rail item that toggles the history panel. */
internal const val HISTORY_ITEM = "History"
internal const val VISUALIZATION_ITEM = "Visual"
internal const val DRAWINGS_ITEM = "Drawings"
internal const val APPEARANCE_ITEM = "Appearance"
internal const val SECTION_ITEM = "Section"
internal const val ITEMS_ITEM = "Items"
internal const val DISPLAY_ITEM = "Display mode"

internal val LeftRailGroups: List<List<RailItem>> = listOf(
    listOf(
        RailItem("Modeling", Icons.Outlined.ViewInAr, selected = true),
        RailItem(VISUALIZATION_ITEM, Icons.Outlined.Texture),
        RailItem(DRAWINGS_ITEM, Icons.Outlined.Architecture),
        RailItem(ITEMS_ITEM, Icons.Outlined.Layers),
    ),
    listOf(
        RailItem("Search", Icons.Outlined.Search),
        RailItem(SKETCH_ITEM, Icons.Outlined.Draw),
        RailItem("Add", Icons.Outlined.Add),
        RailItem("Transform", Icons.Outlined.OpenWith),
        RailItem("Tools", Icons.Outlined.Build),
    ),
    listOf(
        RailItem(SECTION_ITEM, Icons.Outlined.ContentCut),
        RailItem("Measure", Icons.Outlined.Straighten),
    ),
)

internal val RightRailGroups: List<List<RailItem>> = listOf(
    listOf(
        RailItem("Snap settings", Icons.Outlined.GridOn),
        RailItem("Units", Icons.Outlined.Tune),
        RailItem("Views", Icons.Outlined.Widgets),
        RailItem(APPEARANCE_ITEM, Icons.Outlined.Palette),
        RailItem(DISPLAY_ITEM, Icons.Outlined.Visibility),
    ),
    listOf(
        RailItem("Screenshot", Icons.Outlined.CameraAlt),
        RailItem(HISTORY_ITEM, Icons.Outlined.History),
    ),
)

/**
 * A floating vertical tool rail. Most buttons only show the planned tools and are
 * deliberately not clickable; the items in [actions] are wired up, and [active] ones are
 * highlighted.
 */
@Composable
internal fun ToolRail(
    groups: List<List<RailItem>>,
    modifier: Modifier = Modifier,
    actions: Map<String, () -> Unit> = emptyMap(),
    active: Set<String> = emptySet(),
    showLabels: Boolean = false,
) {
    Column(
        modifier = modifier
            .width(if (showLabels) 76.dp else SharpCadDimens.RailWidth)
            .clip(RoundedCornerShape(SharpCadDimens.PanelRadius))
            .background(SharpCadColors.Panel.copy(alpha = 0.94f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEachIndexed { index, group ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = SharpCadColors.Divider,
                )
            }
            group.forEach { RailButton(it, it.selected || it.label in active, actions[it.label], showLabels) }
        }
    }
}

@Composable
private fun RailButton(item: RailItem, highlighted: Boolean, onClick: (() -> Unit)?, showLabel: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        modifier = Modifier
            .size(SharpCadDimens.RailButton)
            .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
            .background(if (highlighted) SharpCadColors.Accent.copy(alpha = 0.18f) else SharpCadColors.Panel.copy(alpha = 0f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = when {
                highlighted -> SharpCadColors.Accent
                onClick != null -> SharpCadColors.Content
                else -> SharpCadColors.ContentMuted
            },
            modifier = Modifier.size(SharpCadDimens.RailIcon),
        )
    }
    if (showLabel) {
        androidx.compose.material3.Text(
            item.label,
            color = SharpCadColors.ContentMuted,
            fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
            maxLines = 1,
        )
    }
    }
}
