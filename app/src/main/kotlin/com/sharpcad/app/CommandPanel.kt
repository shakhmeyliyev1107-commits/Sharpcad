package com.sharpcad.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens
import com.sharpcad.ui.designsystem.tr

/**
 * Search / Tools: every command as a chip, filtered by the typed text in the current
 * language or in English. Running a command closes the panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandPanel(query: String, onQuery: (String) -> Unit, commands: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
    val q = query.trim().lowercase()
    val shown = commands.filter { (name, _) -> q.isEmpty() || q in name.lowercase() || q in tr(name).lowercase() }
    Column(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(SharpCadDimens.PanelRadius))
            .background(SharpCadColors.Panel.copy(alpha = 0.94f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = SharpCadColors.Content, fontSize = 14.sp),
            cursorBrush = SolidColor(SharpCadColors.Accent),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(tr("Search"), color = SharpCadColors.ContentMuted, fontSize = 14.sp)
                inner()
            },
            modifier = Modifier
                .widthIn(min = 240.dp)
                .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
                .background(SharpCadColors.PanelRaised)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { (name, run) ->
                Text(
                    tr(name),
                    color = SharpCadColors.Content,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
                        .background(SharpCadColors.PanelRaised)
                        .clickable { onClose(); run() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(
                tr("Close"),
                color = SharpCadColors.OnAccent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
                    .background(SharpCadColors.Accent)
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
