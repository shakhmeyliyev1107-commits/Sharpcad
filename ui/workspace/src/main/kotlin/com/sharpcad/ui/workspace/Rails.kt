package com.sharpcad.ui.workspace

import androidx.compose.foundation.background
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

internal val LeftRailGroups: List<List<RailItem>> = listOf(
    listOf(
        RailItem("Modeling", Icons.Outlined.ViewInAr, selected = true),
        RailItem("Visualization", Icons.Outlined.Texture),
        RailItem("Drawings", Icons.Outlined.Architecture),
        RailItem("Items", Icons.Outlined.Layers),
    ),
    listOf(
        RailItem("Search", Icons.Outlined.Search),
        RailItem("Sketch", Icons.Outlined.Draw),
        RailItem("Add", Icons.Outlined.Add),
        RailItem("Transform", Icons.Outlined.OpenWith),
        RailItem("Tools", Icons.Outlined.Build),
    ),
    listOf(
        RailItem("Section view", Icons.Outlined.ContentCut),
        RailItem("Measure", Icons.Outlined.Straighten),
    ),
)

internal val RightRailGroups: List<List<RailItem>> = listOf(
    listOf(
        RailItem("Snap settings", Icons.Outlined.GridOn),
        RailItem("Units", Icons.Outlined.Tune),
        RailItem("Views", Icons.Outlined.Widgets),
        RailItem("Appearance", Icons.Outlined.Palette),
        RailItem("Display mode", Icons.Outlined.Visibility),
    ),
    listOf(
        RailItem("Screenshot", Icons.Outlined.CameraAlt),
        RailItem("History", Icons.Outlined.History),
    ),
)

/**
 * A floating vertical tool rail. Static in Phase 1: the buttons show the planned tools
 * but do nothing yet, so they are deliberately not clickable.
 */
@Composable
internal fun ToolRail(groups: List<List<RailItem>>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(SharpCadDimens.RailWidth)
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
            group.forEach { RailButton(it) }
        }
    }
}

@Composable
private fun RailButton(item: RailItem) {
    Box(
        modifier = Modifier
            .size(SharpCadDimens.RailButton)
            .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
            .background(if (item.selected) SharpCadColors.Accent.copy(alpha = 0.18f) else SharpCadColors.Panel.copy(alpha = 0f))
            .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = if (item.selected) SharpCadColors.Accent else SharpCadColors.ContentMuted,
            modifier = Modifier.size(SharpCadDimens.RailIcon),
        )
    }
}
