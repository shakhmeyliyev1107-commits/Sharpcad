package com.sharpcad.sketch

import kotlin.math.abs

/**
 * A plane in model space with a 2D coordinate system on it.
 *
 * [xAxis] and [yAxis] must be unit length and perpendicular; they give the directions of
 * the sketch's u and v coordinates. All values are millimetres in model space.
 */
class SketchPlane(
    val name: String,
    private val origin: DoubleArray,
    private val xAxis: DoubleArray,
    private val yAxis: DoubleArray,
) {
    /** Unit normal, [xAxis] × [yAxis]. */
    val normal: DoubleArray = doubleArrayOf(
        xAxis[1] * yAxis[2] - xAxis[2] * yAxis[1],
        xAxis[2] * yAxis[0] - xAxis[0] * yAxis[2],
        xAxis[0] * yAxis[1] - xAxis[1] * yAxis[0],
    )

    /** Model-space position of the sketch point ([u], [v]). */
    fun toWorld(u: Double, v: Double): DoubleArray = doubleArrayOf(
        origin[0] + u * xAxis[0] + v * yAxis[0],
        origin[1] + u * xAxis[1] + v * yAxis[1],
        origin[2] + u * xAxis[2] + v * yAxis[2],
    )

    /**
     * Sketch coordinates where a ray meets the plane, or `null` if the ray is parallel to
     * the plane or meets it behind its start.
     *
     * @param rayOrigin start of the ray in model space.
     * @param rayDirection direction of the ray; need not be unit length.
     */
    fun intersect(rayOrigin: DoubleArray, rayDirection: DoubleArray): DoubleArray? {
        val denominator = dot(rayDirection, normal)
        if (abs(denominator) < PARALLEL_EPSILON * length(rayDirection)) return null
        val toPlane = doubleArrayOf(
            origin[0] - rayOrigin[0], origin[1] - rayOrigin[1], origin[2] - rayOrigin[2],
        )
        val t = dot(toPlane, normal) / denominator
        if (t < 0.0) return null
        val hit = doubleArrayOf(
            rayOrigin[0] + t * rayDirection[0] - origin[0],
            rayOrigin[1] + t * rayDirection[1] - origin[1],
            rayOrigin[2] + t * rayDirection[2] - origin[2],
        )
        return doubleArrayOf(dot(hit, xAxis), dot(hit, yAxis))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun length(a: DoubleArray) = kotlin.math.sqrt(dot(a, a))

    companion object {
        /** Rays closer than about 0.06° to the plane are treated as parallel. */
        private const val PARALLEL_EPSILON = 1e-3

        private val ZERO = doubleArrayOf(0.0, 0.0, 0.0)

        /** Ground plane, seen from above (+Z). */
        val XY = SketchPlane("XY", ZERO, doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0))

        /** Front plane, seen from the front (−Y). */
        val XZ = SketchPlane("XZ", ZERO, doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))

        /** Side plane, seen from the right (+X). */
        val YZ = SketchPlane("YZ", ZERO, doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    }
}
