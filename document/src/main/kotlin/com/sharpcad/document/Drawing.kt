package com.sharpcad.document

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Standard views of a drawing: direction towards the viewer and the view's up. */
enum class DrawingView(val label: String, val toViewer: DoubleArray, val up: DoubleArray, val dimensioned: Boolean) {
    FRONT("Front", doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), true),
    TOP("Top", doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 1.0, 0.0), true),
    RIGHT("Right", doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), true),
    ISOMETRIC("Isometric", doubleArrayOf(1 / sqrt(3.0), -1 / sqrt(3.0), 1 / sqrt(3.0)), doubleArrayOf(0.0, 0.0, 1.0), false),
}

/** Hidden-line segments of one view in model millimetres (x1, y1, x2, y2 …). */
class ViewLines(val view: DrawingView, val visible: FloatArray, val hidden: FloatArray) {
    /** minX, minY, maxX, maxY of all segments, or zeros when empty. */
    val bounds: FloatArray by lazy {
        val all = visible + hidden
        if (all.isEmpty()) return@lazy floatArrayOf(0f, 0f, 0f, 0f)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in all.indices step 2) {
            x0 = min(x0, all[i]); x1 = max(x1, all[i])
            y0 = min(y0, all[i + 1]); y1 = max(y1, all[i + 1])
        }
        floatArrayOf(x0, y0, x1, y1)
    }
}

/** A linear dimension on the sheet: a line from (x1, y1) to (x2, y2) with its value. */
class SheetDimension(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val text: String)

/** A text label on the sheet; [size] is the cap height in millimetres. */
class SheetText(val x: Float, val y: Float, val text: String, val size: Float)

/**
 * A finished drawing sheet in paper millimetres, origin bottom-left, y up.
 * [visible] and [hidden] are segments already scaled and placed.
 */
class DrawingSheet(
    val width: Float,
    val height: Float,
    val scaleLabel: String,
    val visible: FloatArray,
    val hidden: FloatArray,
    val frame: FloatArray,
    val dimensions: List<SheetDimension>,
    val texts: List<SheetText>,
)

/** Arranges views on an A-series landscape sheet with a title block and overall dimensions. */
object DrawingLayout {
    const val A4_WIDTH = 297f
    const val A4_HEIGHT = 210f
    private const val MARGIN = 10f
    private const val TITLE_HEIGHT = 18f
    private const val GAP = 18f

    /** Scales used on drawings, from enlargement to reduction. */
    private val SCALES = listOf(10f, 5f, 2f, 1f, 1 / 2f, 1 / 5f, 1 / 10f, 1 / 20f, 1 / 50f, 1 / 100f, 1 / 200f, 1 / 500f, 1 / 1000f)

    fun layout(title: String, views: List<ViewLines>, width: Float = A4_WIDTH, height: Float = A4_HEIGHT): DrawingSheet {
        val byView = views.associateBy { it.view }
        fun size(v: DrawingView) = byView[v]?.bounds?.let { floatArrayOf(it[2] - it[0], it[3] - it[1]) } ?: floatArrayOf(0f, 0f)
        val front = size(DrawingView.FRONT)
        val top = size(DrawingView.TOP)
        val right = size(DrawingView.RIGHT)
        val iso = size(DrawingView.ISOMETRIC)

        // Third-angle layout: top above front, right beside front, isometric top-right.
        val areaW = width - 2 * MARGIN
        val areaH = height - 2 * MARGIN - TITLE_HEIGHT
        val needW = max(front[0] + right[0], top[0] + iso[0])
        val needH = max(front[1] + top[1], right[1] + iso[1])
        val fit = min((areaW - 3 * GAP) / max(needW, 1e-3f), (areaH - 3 * GAP) / max(needH, 1e-3f))
        val scale = SCALES.firstOrNull { it <= fit } ?: SCALES.last()

        val colLeft = MARGIN + GAP
        val colRight = colLeft + max(front[0], top[0]) * scale + GAP
        val rowBottom = MARGIN + TITLE_HEIGHT + GAP
        val rowTop = rowBottom + max(front[1], right[1]) * scale + GAP
        val origins = mapOf(
            DrawingView.FRONT to (colLeft to rowBottom),
            DrawingView.TOP to (colLeft to rowTop),
            DrawingView.RIGHT to (colRight to rowBottom),
            DrawingView.ISOMETRIC to (colRight to rowTop),
        )

        val visible = ArrayList<Float>()
        val hidden = ArrayList<Float>()
        val dimensions = ArrayList<SheetDimension>()
        val texts = ArrayList<SheetText>()
        for (lines in views) {
            val (ox, oy) = origins.getValue(lines.view)
            val b = lines.bounds
            fun place(src: FloatArray, dst: MutableList<Float>) {
                for (i in src.indices step 2) {
                    dst += ox + (src[i] - b[0]) * scale
                    dst += oy + (src[i + 1] - b[1]) * scale
                }
            }
            place(lines.visible, visible)
            place(lines.hidden, hidden)
            val w = (b[2] - b[0]) * scale
            val h = (b[3] - b[1]) * scale
            texts += SheetText(ox, oy - 6f, lines.view.label, 3f)
            if (lines.view.dimensioned && w > 0f && h > 0f) {
                dimensions += SheetDimension(ox, oy + h + 5f, ox + w, oy + h + 5f, formatMm((b[2] - b[0]).toDouble()))
                dimensions += SheetDimension(ox - 5f, oy, ox - 5f, oy + h, formatMm((b[3] - b[1]).toDouble()))
            }
        }

        // Border and title block.
        val x0 = MARGIN; val y0 = MARGIN; val x1 = width - MARGIN; val y1 = height - MARGIN
        val tb = x1 - 120f
        val frame = floatArrayOf(
            x0, y0, x1, y0, x1, y0, x1, y1, x1, y1, x0, y1, x0, y1, x0, y0,
            tb, y0, tb, y0 + TITLE_HEIGHT, tb, y0 + TITLE_HEIGHT, x1, y0 + TITLE_HEIGHT,
            tb + 80f, y0, tb + 80f, y0 + TITLE_HEIGHT,
        )
        val scaleLabel = if (scale >= 1f) "${formatMm(scale.toDouble())}:1" else "1:${formatMm((1 / scale).toDouble())}"
        texts += SheetText(tb + 3f, y0 + 10f, title, 5f)
        texts += SheetText(tb + 3f, y0 + 3f, "Units: mm · third-angle projection", 2.5f)
        texts += SheetText(tb + 83f, y0 + 10f, "Scale $scaleLabel", 3.5f)
        texts += SheetText(tb + 83f, y0 + 3f, "SharpCAD", 2.5f)

        return DrawingSheet(width, height, scaleLabel, visible.toFloatArray(), hidden.toFloatArray(), frame, dimensions, texts)
    }
}

/** Writes drawing sheets as SVG and DXF (R12, the most widely readable). Pure Kotlin. */
object DrawingExport {

    fun svg(sheet: DrawingSheet): String = buildString {
        val h = sheet.height
        fun y(v: Float) = h - v // SVG y grows downwards
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append(String.format(Locale.US, """<svg xmlns="http://www.w3.org/2000/svg" width="%.1fmm" height="%.1fmm" viewBox="0 0 %.1f %.1f">""", sheet.width, h, sheet.width, h)).append('\n')
        append("""<rect width="100%" height="100%" fill="white"/>""").append('\n')
        fun lines(segs: FloatArray, style: String) {
            append("<g $style>\n")
            for (i in segs.indices step 4) {
                append(String.format(Locale.US, "<line x1=\"%.3f\" y1=\"%.3f\" x2=\"%.3f\" y2=\"%.3f\"/>\n", segs[i], y(segs[i + 1]), segs[i + 2], y(segs[i + 3])))
            }
            append("</g>\n")
        }
        lines(sheet.frame, """stroke="black" stroke-width="0.5" fill="none"""")
        lines(sheet.hidden, """stroke="#555" stroke-width="0.25" stroke-dasharray="2 1" fill="none"""")
        lines(sheet.visible, """stroke="black" stroke-width="0.35" fill="none"""")
        append("""<g stroke="#1565C0" stroke-width="0.18" fill="#1565C0" font-family="sans-serif">""").append('\n')
        for (d in sheet.dimensions) {
            append(String.format(Locale.US, "<line x1=\"%.3f\" y1=\"%.3f\" x2=\"%.3f\" y2=\"%.3f\"/>\n", d.x1, y(d.y1), d.x2, y(d.y2)))
            val mx = (d.x1 + d.x2) / 2; val my = (d.y1 + d.y2) / 2
            val vertical = abs(d.x1 - d.x2) < 1e-3f
            val rotate = if (vertical) String.format(Locale.US, " transform=\"rotate(-90 %.3f %.3f)\"", mx - 1f, y(my)) else ""
            append(String.format(Locale.US, "<text x=\"%.3f\" y=\"%.3f\" font-size=\"3\" text-anchor=\"middle\" stroke=\"none\"%s>%s</text>\n", if (vertical) mx - 1f else mx, y(my) - 1f, rotate, escape(d.text)))
        }
        append("</g>\n")
        for (t in sheet.texts) {
            append(String.format(Locale.US, "<text x=\"%.3f\" y=\"%.3f\" font-size=\"%.2f\" font-family=\"sans-serif\">%s</text>\n", t.x, y(t.y), t.size, escape(t.text)))
        }
        append("</svg>\n")
    }

    fun dxf(sheet: DrawingSheet): String = buildString {
        fun pair(code: Int, value: String) = append(code).append('\n').append(value).append('\n')
        fun num(v: Float) = String.format(Locale.US, "%.4f", v)
        pair(0, "SECTION"); pair(2, "HEADER"); pair(9, "\$INSUNITS"); pair(70, "4"); pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "TABLES")
        pair(0, "TABLE"); pair(2, "LTYPE"); pair(70, "1")
        pair(0, "LTYPE"); pair(2, "DASHED"); pair(70, "0"); pair(3, "Dashed"); pair(72, "65"); pair(73, "2"); pair(40, "3.0"); pair(49, "2.0"); pair(49, "-1.0")
        pair(0, "ENDTAB")
        pair(0, "TABLE"); pair(2, "LAYER"); pair(70, "4")
        for ((name, color, type) in listOf(Triple("VISIBLE", "7", "CONTINUOUS"), Triple("HIDDEN", "8", "DASHED"), Triple("DIMENSIONS", "5", "CONTINUOUS"), Triple("FRAME", "7", "CONTINUOUS"))) {
            pair(0, "LAYER"); pair(2, name); pair(70, "0"); pair(62, color); pair(6, type)
        }
        pair(0, "ENDTAB"); pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "ENTITIES")
        fun lines(segs: FloatArray, layer: String) {
            for (i in segs.indices step 4) {
                pair(0, "LINE"); pair(8, layer)
                pair(10, num(segs[i])); pair(20, num(segs[i + 1])); pair(30, "0")
                pair(11, num(segs[i + 2])); pair(21, num(segs[i + 3])); pair(31, "0")
            }
        }
        lines(sheet.frame, "FRAME")
        lines(sheet.visible, "VISIBLE")
        lines(sheet.hidden, "HIDDEN")
        for (d in sheet.dimensions) {
            lines(floatArrayOf(d.x1, d.y1, d.x2, d.y2), "DIMENSIONS")
            val vertical = abs(d.x1 - d.x2) < 1e-3f
            pair(0, "TEXT"); pair(8, "DIMENSIONS")
            pair(10, num((d.x1 + d.x2) / 2 - if (vertical) 1.5f else 0f)); pair(20, num((d.y1 + d.y2) / 2 + if (vertical) 0f else 1f)); pair(30, "0")
            pair(40, "3"); pair(1, d.text); if (vertical) pair(50, "90")
        }
        for (t in sheet.texts) {
            pair(0, "TEXT"); pair(8, "FRAME"); pair(10, num(t.x)); pair(20, num(t.y)); pair(30, "0"); pair(40, num(t.size)); pair(1, t.text)
        }
        pair(0, "ENDSEC"); pair(0, "EOF")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
