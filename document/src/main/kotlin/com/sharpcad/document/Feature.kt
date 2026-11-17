package com.sharpcad.document

import com.sharpcad.kernel.BooleanOp
import com.sharpcad.kernel.PlaneFrame

/**
 * A sketch plane, stored as plain values so features can be compared and saved.
 * Points on it are `origin + u * xAxis + v * yAxis` (model millimetres).
 */
data class PlaneSpec(
    val name: String,
    val origin: List<Double>,
    val xAxis: List<Double>,
    val yAxis: List<Double>,
) {
    fun frame(): PlaneFrame = PlaneFrame(origin.toDoubleArray(), xAxis.toDoubleArray(), yAxis.toDoubleArray())

    /** Unit normal, xAxis × yAxis. */
    val normal: List<Double>
        get() = frame().normal.toList()

    /** Model-space point for plane coordinates ([u], [v]). */
    fun point(u: Double, v: Double): List<Double> = List(3) { origin[it] + u * xAxis[it] + v * yAxis[it] }

    companion object {
        val XY = PlaneSpec("XY", listOf(0.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0))
        val XZ = PlaneSpec("XZ", listOf(0.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0))
        val YZ = PlaneSpec("YZ", listOf(0.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    }
}

/**
 * Sketch geometry a feature was built from: a snapshot, so later sketch edits do not
 * silently change the model.
 *
 * @property lines x1, y1, x2, y2 per line (plane coordinates).
 * @property circles cx, cy, radius per circle.
 * @property regions indices of the closed regions to use, or `null` for all of them.
 */
data class Profile(
    val plane: PlaneSpec,
    val lines: List<Double>,
    val circles: List<Double>,
    val regions: List<Int>? = null,
)

/** How a feature's new solid combines with the existing body it targets. */
enum class Operation(val label: String) {
    /** Decided from the geometry: cut when inside a body, join when touching, else new. */
    AUTO("Auto"),
    NEW_BODY("New body"),
    JOIN("Join"),
    CUT("Cut"),
    INTERSECT("Intersect"),
}

/** Axis of the sketch plane that a revolve turns around (through the plane origin). */
enum class RevolveAxis(val label: String) { U("Horizontal axis"), V("Vertical axis") }

/**
 * One step of the model history. Features are immutable values: editing a parameter
 * means replacing the feature, after which the whole history is rebuilt.
 *
 * A body is identified by the [id] of the feature that created it.
 */
sealed interface Feature {
    val id: Long
    val label: String
}

/** A STEP or IGES file copied into app storage (format from the extension). Creates a body. */
data class ImportFeature(override val id: Long, val path: String, val name: String) : Feature {
    override val label: String get() = "Import $name"
}

/** Extrudes [profile] along the plane normal by [distance] (negative goes the other way). */
data class ExtrudeFeature(
    override val id: Long,
    val profile: Profile,
    val distance: Double,
    val operation: Operation = Operation.AUTO,
    val target: Long? = null,
    /** Extends [distance] to both sides of the profile. */
    val symmetric: Boolean = false,
) : Feature {
    override val label: String get() = "Extrude ${formatMm(distance)} mm" + if (symmetric) " ↔" else ""
}

/** Revolves [profile] around one of its plane axes by [angleDegrees]. */
data class RevolveFeature(
    override val id: Long,
    val profile: Profile,
    val axis: RevolveAxis,
    val angleDegrees: Double,
    val operation: Operation = Operation.AUTO,
    val target: Long? = null,
) : Feature {
    override val label: String get() = "Revolve ${formatMm(angleDegrees)}°"
}

/** Rounds the edges of [face] of [body], or all its edges when [face] is -1. */
data class FilletFeature(override val id: Long, val body: Long, val radius: Double, val face: Int = -1) : Feature {
    override val label: String get() = "Fillet ${formatMm(radius)} mm"
}

/** Bevels the edges of [face] of [body], or all its edges when [face] is -1. */
data class ChamferFeature(override val id: Long, val body: Long, val distance: Double, val face: Int = -1) : Feature {
    override val label: String get() = "Chamfer ${formatMm(distance)} mm"
}

/** Moves flat [face] of [body] along its normal. */
data class PushPullFeature(override val id: Long, val body: Long, val face: Int, val distance: Double) : Feature {
    override val label: String get() = "Push/Pull ${formatMm(distance)} mm"
}

/** Combines [tool] into [target]; the tool body is consumed. */
data class BooleanFeature(override val id: Long, val target: Long, val tool: Long, val op: BooleanOp) : Feature {
    override val label: String
        get() = when (op) {
            BooleanOp.UNION -> "Union"
            BooleanOp.SUBTRACT -> "Subtract"
            BooleanOp.INTERSECT -> "Intersect"
        }
}

/** Up to three decimals, no trailing zeros. */
fun formatMm(value: Double): String {
    val rounded = Math.round(value * 1000.0) / 1000.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}
