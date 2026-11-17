package com.sharpcad.sketch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Turns sketch entities into model-space line segments for display. */
object SketchLines {
    /** Segments used to approximate a full circle. */
    const val CIRCLE_SEGMENTS = 72

    /**
     * Line segments for [entities] on [plane]: xyz float triples, two points per segment.
     * Circles become [CIRCLE_SEGMENTS]-sided polygons.
     */
    fun segments(entities: List<SketchEntity>, plane: SketchPlane): FloatArray {
        val count = entities.sumOf { if (it is SketchLine) 1 else CIRCLE_SEGMENTS }
        val out = FloatArray(count * 6)
        var o = 0
        fun put(u: Double, v: Double) {
            val w = plane.toWorld(u, v)
            out[o++] = w[0].toFloat()
            out[o++] = w[1].toFloat()
            out[o++] = w[2].toFloat()
        }
        for (entity in entities) {
            when (entity) {
                is SketchLine -> {
                    put(entity.x1, entity.y1)
                    put(entity.x2, entity.y2)
                }
                is SketchArc -> for (i in 0 until CIRCLE_SEGMENTS) {
                    val span = entity.endAngle - entity.startAngle
                    val a0 = entity.startAngle + span * i / CIRCLE_SEGMENTS
                    val a1 = entity.startAngle + span * (i + 1) / CIRCLE_SEGMENTS
                    put(entity.cx + entity.radius * cos(a0), entity.cy + entity.radius * sin(a0))
                    put(entity.cx + entity.radius * cos(a1), entity.cy + entity.radius * sin(a1))
                }
                is SketchCircle -> for (i in 0 until CIRCLE_SEGMENTS) {
                    val a0 = 2.0 * PI * i / CIRCLE_SEGMENTS
                    val a1 = 2.0 * PI * (i + 1) / CIRCLE_SEGMENTS
                    put(entity.cx + entity.radius * cos(a0), entity.cy + entity.radius * sin(a0))
                    put(entity.cx + entity.radius * cos(a1), entity.cy + entity.radius * sin(a1))
                }
            }
        }
        return out
    }

    /** A small diagonal cross at ([u], [v]) marking a snap; [size] is its half-width. */
    fun marker(u: Double, v: Double, size: Double, plane: SketchPlane): FloatArray {
        val a = plane.toWorld(u - size, v - size)
        val b = plane.toWorld(u + size, v + size)
        val c = plane.toWorld(u - size, v + size)
        val d = plane.toWorld(u + size, v - size)
        return floatArrayOf(
            a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), b[0].toFloat(), b[1].toFloat(), b[2].toFloat(),
            c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), d[0].toFloat(), d[1].toFloat(), d[2].toFloat(),
        )
    }
}
