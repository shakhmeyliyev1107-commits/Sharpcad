package com.sharpcad.app

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.document.DrawingSheet
import com.sharpcad.ui.designsystem.SharpCadColors
import java.io.OutputStream
import kotlin.math.abs

/** Full-screen preview of a drawing sheet with export actions. */
@Composable
fun DrawingScreen(sheet: DrawingSheet, onExport: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SharpCadColors.Background).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drawing · scale ${sheet.scaleLabel}", color = SharpCadColors.Content, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = SharpCadColors.Content, maxLines = 1) }
        }
        // Export buttons on their own scrollable row so narrow (portrait) screens fit.
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for ((label, ext) in listOf("SVG" to "svg", "DXF" to "dxf", "PDF" to "pdf")) {
                TextButton(onClick = { onExport(ext) }) { Text("Export $label", color = SharpCadColors.Accent, maxLines = 1) }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.aspectRatio(sheet.width / sheet.height).background(Color.White)) {
                SheetPainter.draw(drawContext.canvas.nativeCanvas, sheet, size.width / sheet.width)
            }
        }
    }
}

/** Draws a sheet on an Android canvas; shared by the preview and PDF export. */
object SheetPainter {

    /** [pxPerMm] scales paper millimetres to canvas units; the sheet's y axis points up. */
    fun draw(canvas: android.graphics.Canvas, sheet: DrawingSheet, pxPerMm: Float) {
        val h = sheet.height
        fun x(v: Float) = v * pxPerMm
        fun y(v: Float) = (h - v) * pxPerMm
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.BLACK }
        fun segments(segs: FloatArray, width: Float, colour: Int, dashed: Boolean) {
            line.strokeWidth = width * pxPerMm
            line.color = colour
            line.pathEffect = if (dashed) DashPathEffect(floatArrayOf(2f * pxPerMm, 1f * pxPerMm), 0f) else null
            for (i in segs.indices step 4) canvas.drawLine(x(segs[i]), y(segs[i + 1]), x(segs[i + 2]), y(segs[i + 3]), line)
        }
        segments(sheet.frame, 0.5f, android.graphics.Color.BLACK, false)
        segments(sheet.hidden, 0.25f, 0xFF555555.toInt(), true)
        segments(sheet.visible, 0.35f, android.graphics.Color.BLACK, false)

        val dimColour = 0xFF1565C0.toInt()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColour; textSize = 3f * pxPerMm; textAlign = Paint.Align.CENTER }
        for (d in sheet.dimensions) {
            segments(floatArrayOf(d.x1, d.y1, d.x2, d.y2), 0.18f, dimColour, false)
            val mx = x((d.x1 + d.x2) / 2)
            val my = y((d.y1 + d.y2) / 2)
            if (abs(d.x1 - d.x2) < 1e-3f) {
                canvas.save()
                canvas.rotate(-90f, mx - pxPerMm, my)
                canvas.drawText(d.text, mx - pxPerMm, my, text)
                canvas.restore()
            } else {
                canvas.drawText(d.text, mx, my - pxPerMm, text)
            }
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
        for (t in sheet.texts) {
            label.textSize = t.size * pxPerMm
            canvas.drawText(t.text, x(t.x), y(t.y), label)
        }
    }

    /** One-page PDF at true size (paper millimetres to PostScript points). */
    fun writePdf(sheet: DrawingSheet, out: OutputStream) {
        val pointsPerMm = 72f / 25.4f
        val pdf = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(
                (sheet.width * pointsPerMm).toInt(), (sheet.height * pointsPerMm).toInt(), 1,
            ).create()
            val page = pdf.startPage(info)
            draw(page.canvas, sheet, pointsPerMm)
            pdf.finishPage(page)
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
