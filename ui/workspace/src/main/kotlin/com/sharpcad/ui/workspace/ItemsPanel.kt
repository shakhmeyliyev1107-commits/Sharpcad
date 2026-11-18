package com.sharpcad.ui.workspace

import com.sharpcad.ui.designsystem.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens

/** One body in the items list. */
class ItemRow(val id: Long, val name: String, val visible: Boolean, val selected: Boolean)

/** Bodies of the model: tap to select, rename in place, hide/show, delete. */
@Composable
fun ItemsPanel(
    items: List<ItemRow>,
    onSelect: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onToggleVisible: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(240.dp).clip(RoundedCornerShape(SharpCadDimens.PanelRadius)).background(SharpCadColors.Panel).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tr("Items"), color = SharpCadColors.Content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (items.isEmpty()) Text(tr("No bodies yet"), color = SharpCadColors.ContentMuted, fontSize = 12.sp)
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (item.selected) SharpCadColors.Accent.copy(alpha = 0.22f) else SharpCadColors.PanelRaised)
                        .clickable { onSelect(item.id) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    key(item.id, item.name) {
                        var text by remember { mutableStateOf(item.name) }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = TextStyle(color = if (item.visible) SharpCadColors.Content else SharpCadColors.ContentDisabled, fontSize = 12.sp),
                            cursorBrush = SolidColor(SharpCadColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onRename(item.id, text.trim()) }),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Icon(
                        if (item.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (item.visible) "Hide" else "Show",
                        tint = SharpCadColors.ContentMuted,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).clickable { onToggleVisible(item.id) }.padding(6.dp),
                    )
                    Icon(
                        Icons.Outlined.Delete, contentDescription = "Delete", tint = SharpCadColors.ContentMuted,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).clickable { onDelete(item.id) }.padding(6.dp),
                    )
                }
            }
        }
    }
}
