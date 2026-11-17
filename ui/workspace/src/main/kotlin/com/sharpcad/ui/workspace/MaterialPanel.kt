package com.sharpcad.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens

/** One material to show as a swatch. [color] is 0xRRGGBB sRGB. */
class MaterialSwatch(val id: String, val name: String, val color: Int, val metallic: Float, val roughness: Float)

/**
 * Material picker: category tabs and a grid of swatches. Tapping a swatch applies it to
 * the target named in [subtitle] (the selected body, or every body).
 */
@Composable
fun MaterialPanel(
    categories: List<String>,
    selectedCategory: String,
    onCategory: (String) -> Unit,
    swatches: List<MaterialSwatch>,
    currentId: String?,
    onPick: (String) -> Unit,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(320.dp)
            .clip(RoundedCornerShape(SharpCadDimens.PanelRadius))
            .background(SharpCadColors.Panel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Materials", color = SharpCadColors.Content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = SharpCadColors.ContentMuted, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { Chip(it, selected = it == selectedCategory) { onCategory(it) } }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(68.dp),
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(swatches, key = { it.id }) { swatch ->
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(swatch.id) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(swatchBrush(swatch))
                            .border(
                                width = if (swatch.id == currentId) 3.dp else 1.dp,
                                color = if (swatch.id == currentId) SharpCadColors.Accent else SharpCadColors.Divider,
                                shape = CircleShape,
                            ),
                    )
                    Text(
                        swatch.name,
                        color = SharpCadColors.Content,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A diagonal highlight that hints at shininess: sharper and brighter for smooth materials. */
private fun swatchBrush(s: MaterialSwatch): Brush {
    val base = Color(0xFF000000.toInt() or s.color)
    val shine = 0.6f * (1f - s.roughness) * (0.4f + 0.6f * s.metallic) + 0.1f
    val light = Color(
        red = base.red + (1f - base.red) * shine,
        green = base.green + (1f - base.green) * shine,
        blue = base.blue + (1f - base.blue) * shine,
    )
    return Brush.linearGradient(listOf(light, base, base.copy(red = base.red * 0.7f, green = base.green * 0.7f, blue = base.blue * 0.7f)))
}
