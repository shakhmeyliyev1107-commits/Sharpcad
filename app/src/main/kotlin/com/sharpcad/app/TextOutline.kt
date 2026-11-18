package com.sharpcad.app

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface

/** Glyph outlines of a text as closed sketch polylines, from the system font. */
object TextOutline {
    private const val FONT_SIZE = 100f

    /**
     * Contours of [text] with its baseline starting at ([x], [y]) on the sketch plane and
     * [height] mm as the font size. Each contour is x,y pairs, closed (last = first).
     */
    fun contours(text: String, x: Double, y: Double, height: Double): List<List<Double>> {
        if (text.isBlank() || height <= 0) return emptyList()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = FONT_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val scale = height / FONT_SIZE
        val step = FONT_SIZE / 80f
        val measure = PathMeasure(path, true)
        val pos = FloatArray(2)
        val out = mutableListOf<List<Double>>()
        do {
            val length = measure.length
            if (length < step) continue
            val n = (length / step).toInt().coerceAtLeast(8)
            val pts = ArrayList<Double>(n * 2 + 2)
            for (i in 0 until n) {
                measure.getPosTan(length * i / n, pos, null)
                // Android's y grows downwards; the sketch's grows upwards.
                pts += x + pos[0] * scale
                pts += y - pos[1] * scale
            }
            pts += pts[0]; pts += pts[1]
            out += pts
        } while (measure.nextContour())
        return out
    }

    /** A closed contour as line segments x1,y1,x2,y2. */
    fun segments(contour: List<Double>): List<Double> {
        val out = ArrayList<Double>(contour.size * 2)
        for (i in 0 until contour.size / 2 - 1) {
            out += contour[2 * i]; out += contour[2 * i + 1]; out += contour[2 * i + 2]; out += contour[2 * i + 3]
        }
        return out
    }
}
