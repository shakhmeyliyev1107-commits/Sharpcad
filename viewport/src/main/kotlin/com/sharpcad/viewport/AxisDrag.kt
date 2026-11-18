package com.sharpcad.viewport

import kotlin.math.abs

/** Maps a screen drag to a distance along a model-space axis (direct manipulation). */
object AxisDrag {
    /**
     * Parameter s of the point A + s·n on the axis line closest to the ray O + t·d, or
     * `null` when the ray runs (nearly) parallel to the axis. [n] and [d] need not be unit.
     */
    fun along(anchor: DoubleArray, axis: DoubleArray, origin: DoubleArray, direction: DoubleArray): Double? {
        val w = DoubleArray(3) { anchor[it] - origin[it] }
        val a = dot(axis, axis)
        val b = dot(axis, direction)
        val c = dot(direction, direction)
        val d = dot(axis, w)
        val e = dot(direction, w)
        val den = a * c - b * b
        if (abs(den) < 1e-9 * a * c) return null
        return (b * e - c * d) / den
    }

    /** Rounds [value] to the nearest multiple of [step]. */
    fun snap(value: Double, step: Double): Double = if (step <= 0) value else Math.round(value / step) * step

    private fun dot(p: DoubleArray, q: DoubleArray) = p[0] * q[0] + p[1] * q[1] + p[2] * q[2]
}
