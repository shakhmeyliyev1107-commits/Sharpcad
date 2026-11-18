package com.sharpcad.document

import com.sharpcad.kernel.BooleanOp
import com.sharpcad.kernel.Kernel
import com.sharpcad.kernel.KernelException
import com.sharpcad.kernel.Shape
import kotlin.math.PI

/**
 * Result of replaying a feature history.
 *
 * Owns the shapes in [bodies]; [close] releases them.
 *
 * @property bodies live bodies by id, in creation order.
 * @property errors message per feature that could not be applied; it was skipped.
 */
class BuildResult(val bodies: LinkedHashMap<Long, Shape>, val errors: Map<Long, String>) : AutoCloseable {
    override fun close() {
        bodies.values.forEach { it.close() }
        bodies.clear()
    }
}

/**
 * Replays a feature history from scratch with the kernel. Blocking and CPU-heavy:
 * call on the kernel worker thread only.
 */
object ModelBuilder {

    fun build(features: List<Feature>): BuildResult {
        val bodies = LinkedHashMap<Long, Shape>()
        val errors = LinkedHashMap<Long, String>()
        for (feature in features) {
            try {
                apply(feature, bodies)
            } catch (e: KernelException) {
                errors[feature.id] = e.message ?: "Kernel error ${e.code}"
            } catch (e: IllegalArgumentException) {
                errors[feature.id] = e.message ?: "Invalid feature"
            }
        }
        return BuildResult(bodies, errors)
    }

    private fun apply(feature: Feature, bodies: LinkedHashMap<Long, Shape>) {
        when (feature) {
            is ImportFeature -> bodies[feature.id] = when (feature.path.substringAfterLast('.').lowercase()) {
                "igs", "iges" -> Kernel.importIges(feature.path)
                else -> Kernel.importStep(feature.path)
            }
            is ExtrudeFeature -> {
                val n = feature.profile.plane.normal
                val d = feature.distance
                require(d != 0.0) { "Extrude distance must not be zero" }
                val solid = profileShape(feature.profile).use { profile ->
                    val forward = profile.extrude(n[0] * d, n[1] * d, n[2] * d)
                    if (!feature.symmetric) {
                        forward
                    } else {
                        forward.use { f -> profile.extrude(-n[0] * d, -n[1] * d, -n[2] * d).use { b -> f.booleanWith(b, BooleanOp.UNION) } }
                    }
                }
                combine(feature.id, solid, feature.operation, feature.target, bodies)
            }
            is RevolveFeature -> {
                val plane = feature.profile.plane
                val axis = if (feature.axis == RevolveAxis.U) plane.xAxis else plane.yAxis
                val solid = profileShape(feature.profile).use {
                    it.revolve(plane.origin.toDoubleArray(), axis.toDoubleArray(), feature.angleDegrees * PI / 180.0)
                }
                combine(feature.id, solid, feature.operation, feature.target, bodies)
            }
            is FilletFeature -> replace(feature.body, bodies) { if (feature.edge >= 0) it.blendEdge(feature.radius, feature.edge, false) else it.fillet(feature.radius, feature.face) }
            is ChamferFeature -> replace(feature.body, bodies) { if (feature.edge >= 0) it.blendEdge(feature.distance, feature.edge, true) else it.chamfer(feature.distance, feature.face) }
            is PushPullFeature -> replace(feature.body, bodies) { it.pushPull(feature.face, feature.distance) }
            is MoveFeature -> {
                val source = bodies[feature.body] ?: throw IllegalArgumentException("Body no longer exists")
                val b = source.boundingBox()
                val centre = doubleArrayOf((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2)
                val moved = source.moved(centre, feature.axis.vector, feature.angleDegrees * PI / 180, feature.dx, feature.dy, feature.dz)
                if (feature.copy) bodies[feature.id] = moved else { bodies[feature.body] = moved; source.close() }
            }
            is ScaleFeature -> replace(feature.body, bodies) { s ->
                val b = s.boundingBox()
                s.scaled(doubleArrayOf((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2), feature.factor)
            }
            is SplitFeature -> {
                val source = bodies[feature.body] ?: throw IllegalArgumentException("Body no longer exists")
                val b = source.boundingBox()
                val pad = 10.0
                val lo = doubleArrayOf(b.minX - pad, b.minY - pad, b.minZ - pad)
                val hi = doubleArrayOf(b.maxX + pad, b.maxY + pad, b.maxZ + pad)
                val k = feature.plane.ordinal
                require(feature.offset > lo[k] + pad && feature.offset < hi[k] - pad) { "The plane does not cross the body" }
                lo[k] = feature.offset
                // Half-space box on the positive side of the plane.
                val half = Kernel.makeBox(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]).use { box ->
                    box.moved(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), 0.0, lo[0], lo[1], lo[2])
                }
                half.use { h ->
                    val positive = source.booleanWith(h, com.sharpcad.kernel.BooleanOp.INTERSECT)
                    val negative = try { source.booleanWith(h, com.sharpcad.kernel.BooleanOp.SUBTRACT) } catch (e: Exception) { positive.close(); throw e }
                    bodies[feature.body] = positive
                    bodies[feature.id] = negative
                    source.close()
                }
            }
            is MirrorFeature -> {
                val source = bodies[feature.body] ?: throw IllegalArgumentException("Body no longer exists")
                val mirrored = source.mirrored(doubleArrayOf(0.0, 0.0, 0.0), feature.plane.vector)
                if (feature.keepOriginal) bodies[feature.id] = mirrored else { bodies[feature.body] = mirrored; source.close() }
            }
            is PatternFeature -> {
                require(feature.count in 2..MAX_PATTERN) { "Pattern count must be 2 to $MAX_PATTERN" }
                val source = bodies[feature.body] ?: throw IllegalArgumentException("Body no longer exists")
                val origin = doubleArrayOf(0.0, 0.0, 0.0)
                val a = feature.axis.vector
                // A full circle has no repeated last copy; a partial arc ends on its last angle.
                val full = kotlin.math.abs(feature.angleDegrees - 360.0) < 1e-9
                val step = feature.angleDegrees / (if (full) feature.count else feature.count - 1)
                for (k in 1 until feature.count) {
                    bodies[feature.copyId(k)] = if (feature.circular) {
                        source.moved(origin, a, k * step * PI / 180, 0.0, 0.0, 0.0)
                    } else {
                        source.moved(origin, a, 0.0, a[0] * k * feature.spacing, a[1] * k * feature.spacing, a[2] * k * feature.spacing)
                    }
                }
            }
            is DeleteBodyFeature -> (bodies.remove(feature.body) ?: throw IllegalArgumentException("Body no longer exists")).close()
            is LoftFeature -> {
                val shapes = feature.profiles.map { profileShape(it) }
                val solid = try { Kernel.loft(shapes) } finally { shapes.forEach { it.close() } }
                combine(feature.id, solid, feature.operation, null, bodies)
            }
            is SweepFeature -> {
                val solid = profileShape(feature.profile).use {
                    it.sweep(feature.pathPlane.frame(), feature.pathLines.toDoubleArray(), feature.pathArcs.toDoubleArray())
                }
                combine(feature.id, solid, feature.operation, null, bodies)
            }
            is ShellFeature -> replace(feature.body, bodies) { it.shell(feature.face, feature.thickness) }
            is BooleanFeature -> {
                require(feature.target != feature.tool) { "A body cannot be combined with itself" }
                val tool = bodies[feature.tool] ?: throw IllegalArgumentException("Tool body no longer exists")
                replace(feature.target, bodies) { it.booleanWith(tool, feature.op) }
                bodies.remove(feature.tool)?.close()
            }
        }
    }

    private fun profileShape(profile: Profile): Shape {
        val all = Kernel.profileFromSketch(
            profile.plane.frame(), profile.lines.toDoubleArray(), profile.circles.toDoubleArray(), profile.arcs.toDoubleArray(),
            profile.splines.toDoubleArray(),
        )
        val regions = profile.regions ?: return all
        return all.use { it.subset(regions) }
    }

    /** Adds [solid] as a new body, or merges it into [target] (default: the newest body). */
    private fun combine(id: Long, solid: Shape, requested: Operation, target: Long?, bodies: LinkedHashMap<Long, Shape>) {
        var operation = requested
        var targetBody = target
        if (operation == Operation.AUTO) {
            val (op, body) = autoOperation(solid, bodies)
            operation = op
            targetBody = body
        }
        if (operation == Operation.NEW_BODY) {
            bodies[id] = solid
            return
        }
        solid.use {
            val targetId = targetBody ?: bodies.keys.lastOrNull()
                ?: throw IllegalArgumentException("${operation.label} needs an existing body")
            val op = when (operation) {
                Operation.JOIN -> BooleanOp.UNION
                Operation.CUT -> BooleanOp.SUBTRACT
                else -> BooleanOp.INTERSECT
            }
            replace(targetId, bodies) { body -> body.booleanWith(solid, op) }
        }
    }

    /**
     * Chooses what an automatic extrude/revolve does, newest body first: a solid lying
     * entirely inside a body cuts it, one that overlaps or touches joins it, and one that
     * meets no body becomes a new body.
     */
    private fun autoOperation(solid: Shape, bodies: Map<Long, Shape>): Pair<Operation, Long?> {
        val solidVolume = solid.volume
        for ((id, body) in bodies.entries.reversed()) {
            val common = try {
                body.booleanWith(solid, BooleanOp.INTERSECT).use { it.volume }
            } catch (e: KernelException) {
                0.0
            }
            if (common > solidVolume * AUTO_EPSILON) {
                return if (common >= solidVolume * (1 - AUTO_EPSILON)) Operation.CUT to id else Operation.JOIN to id
            }
            val touching = try { body.distanceTo(solid) < TOUCH_MM } catch (e: KernelException) { false }
            if (touching) return Operation.JOIN to id
        }
        return Operation.NEW_BODY to null
    }

    private const val AUTO_EPSILON = 1e-6
    private const val MAX_PATTERN = 200
    private const val TOUCH_MM = 1e-4

    private fun replace(id: Long, bodies: LinkedHashMap<Long, Shape>, transform: (Shape) -> Shape) {
        val old = bodies[id] ?: throw IllegalArgumentException("Body no longer exists")
        val updated = transform(old)
        bodies[id] = updated // keeps the body's place in creation order
        old.close()
    }
}
