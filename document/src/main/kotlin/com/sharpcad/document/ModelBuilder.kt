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
            is FilletFeature -> replace(feature.body, bodies) { it.fillet(feature.radius, feature.face) }
            is ChamferFeature -> replace(feature.body, bodies) { it.chamfer(feature.distance, feature.face) }
            is PushPullFeature -> replace(feature.body, bodies) { it.pushPull(feature.face, feature.distance) }
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
            profile.plane.frame(), profile.lines.toDoubleArray(), profile.circles.toDoubleArray(),
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
    private const val TOUCH_MM = 1e-4

    private fun replace(id: Long, bodies: LinkedHashMap<Long, Shape>, transform: (Shape) -> Shape) {
        val old = bodies[id] ?: throw IllegalArgumentException("Body no longer exists")
        val updated = transform(old)
        bodies[id] = updated // keeps the body's place in creation order
        old.close()
    }
}
