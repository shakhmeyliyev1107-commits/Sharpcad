package com.sharpcad.document

import com.sharpcad.kernel.BooleanOp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.sharpcad.kernel.PlaneFrame

/**
 * A sketch plane, stored as plain values so features can be compared and saved.
 * Points on it are `origin + u * xAxis + v * yAxis` (model millimetres).
 */
@Serializable
@SerialName("plane")
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
@Serializable
@SerialName("profile")
data class Profile(
    val plane: PlaneSpec,
    val lines: List<Double>,
    val circles: List<Double>,
    val regions: List<Int>? = null,
    /** cx, cy, radius, start angle, end angle per arc (radians, counter-clockwise). */
    val arcs: List<Double> = emptyList(),
    /** Per spline: point count n, then n (x, y) points (interpolated B-spline). */
    val splines: List<Double> = emptyList(),
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
@Serializable
sealed interface Feature {
    val id: Long
    val label: String
}

/** A STEP or IGES file copied into app storage (format from the extension). Creates a body. */
@Serializable
@SerialName("import")
data class ImportFeature(override val id: Long, val path: String, val name: String) : Feature {
    override val label: String get() = "Import $name"
}

/** Extrudes [profile] along the plane normal by [distance] (negative goes the other way). */
@Serializable
@SerialName("extrude")
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
@Serializable
@SerialName("revolve")
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
@Serializable
@SerialName("fillet")
/** [edge] ≥ 0 rounds only that edge (and ignores [face]). */
data class FilletFeature(override val id: Long, val body: Long, val radius: Double, val face: Int = -1, val edge: Int = -1) : Feature {
    override val label: String get() = "Fillet ${formatMm(radius)} mm"
}

/** Bevels the edges of [face] of [body], or all its edges when [face] is -1. */
@Serializable
@SerialName("chamfer")
/** [edge] ≥ 0 bevels only that edge (and ignores [face]). */
data class ChamferFeature(override val id: Long, val body: Long, val distance: Double, val face: Int = -1, val edge: Int = -1) : Feature {
    override val label: String get() = "Chamfer ${formatMm(distance)} mm"
}

/** Moves flat [face] of [body] along its normal. */
@Serializable
@SerialName("pushPull")
data class PushPullFeature(override val id: Long, val body: Long, val face: Int, val distance: Double) : Feature {
    override val label: String get() = "Push/Pull ${formatMm(distance)} mm"
}

/** Hollows [body] to [thickness] mm walls with [face] removed (-1 keeps it closed). */
@Serializable
@SerialName("shell")
data class ShellFeature(override val id: Long, val body: Long, val face: Int, val thickness: Double) : Feature {
    override val label: String get() = "Shell ${formatMm(thickness)} mm"
}

/** Solid through the profiles, in order (each profile's first region boundary). */
@Serializable
@SerialName("loft")
data class LoftFeature(
    override val id: Long,
    val profiles: List<Profile>,
    val operation: Operation = Operation.AUTO,
) : Feature {
    override val label: String get() = "Loft (${profiles.size} profiles)"
}

/** Sweeps [profile] along the lines and arcs [pathLines]/[pathArcs] drawn on [pathPlane]. */
@Serializable
@SerialName("sweep")
data class SweepFeature(
    override val id: Long,
    val profile: Profile,
    val pathPlane: PlaneSpec,
    val pathLines: List<Double>,
    val pathArcs: List<Double> = emptyList(),
    val operation: Operation = Operation.AUTO,
) : Feature {
    override val label: String get() = "Sweep"
}

/** A world axis. */
enum class WorldAxis(val label: String, val vector: DoubleArray) {
    X("X", doubleArrayOf(1.0, 0.0, 0.0)),
    Y("Y", doubleArrayOf(0.0, 1.0, 0.0)),
    Z("Z", doubleArrayOf(0.0, 0.0, 1.0)),
}

/**
 * Moves [body] by (dx, dy, dz) after rotating it [angleDegrees] about [axis] through its
 * own centre. With [copy] the original stays and the moved copy is a new body.
 */
@Serializable
@SerialName("move")
data class MoveFeature(
    override val id: Long,
    val body: Long,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val dz: Double = 0.0,
    val axis: WorldAxis = WorldAxis.Z,
    val angleDegrees: Double = 0.0,
    val copy: Boolean = false,
) : Feature {
    override val label: String get() = if (copy) "Copy" else "Move"
}

/** Mirrors [body] across a base plane through the origin; [keepOriginal] adds a new body. */
@Serializable
@SerialName("mirror")
data class MirrorFeature(override val id: Long, val body: Long, val plane: WorldAxis, val keepOriginal: Boolean = true) : Feature {
    override val label: String get() = "Mirror"
}

/** Scales [body] by [factor] about its bounding-box centre. */
@Serializable
@SerialName("scale")
data class ScaleFeature(override val id: Long, val body: Long, val factor: Double = 2.0) : Feature {
    override val label: String get() = "Scale ×${formatMm(factor)}"
}

/**
 * Cuts [body] in two with the world plane whose normal is [plane] at [offset] mm. The part
 * on the positive side keeps [body]'s id; the other part becomes a new body ([id]).
 */
@Serializable
@SerialName("split")
data class SplitFeature(override val id: Long, val body: Long, val plane: WorldAxis = WorldAxis.Z, val offset: Double = 0.0) : Feature {
    override val label: String get() = "Split"
}

/**
 * Copies [body] [count] − 1 times: in a line along [axis] every [spacing] mm, or
 * ([circular]) around [axis] through the origin over [angleDegrees]. Copies are new bodies.
 */
@Serializable
@SerialName("pattern")
data class PatternFeature(
    override val id: Long,
    val body: Long,
    val axis: WorldAxis = WorldAxis.X,
    val count: Int = 3,
    val spacing: Double = 20.0,
    val circular: Boolean = false,
    val angleDegrees: Double = 360.0,
) : Feature {
    override val label: String get() = "Pattern ×$count"

    /** Body id of copy [k] (1 ≤ k < count). */
    fun copyId(k: Int): Long = id * 1000 + k
}

/** Removes [body] from the model. */
@Serializable
@SerialName("deleteBody")
data class DeleteBodyFeature(override val id: Long, val body: Long) : Feature {
    override val label: String get() = "Delete body"
}

/** Combines [tool] into [target]; the tool body is consumed. */
@Serializable
@SerialName("boolean")
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
