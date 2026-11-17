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
            is ImportFeature -> bodies[feature.id] = Kernel.importStep(feature.path)
            is ExtrudeFeature -> {
                val n = feature.profile.plane.normal
                val d = feature.distance
                require(d != 0.0) { "Extrude distance must not be zero" }
                val solid = profileShape(feature.profile).use { it.extrude(n[0] * d, n[1] * d, n[2] * d) }
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
    private fun combine(id: Long, solid: Shape, operation: Operation, target: Long?, bodies: LinkedHashMap<Long, Shape>) {
        if (operation == Operation.NEW_BODY) {
            bodies[id] = solid
            return
        }
        solid.use {
            val targetId = target ?: bodies.keys.lastOrNull()
                ?: throw IllegalArgumentException("${operation.label} needs an existing body")
            val op = when (operation) {
                Operation.JOIN -> BooleanOp.UNION
                Operation.CUT -> BooleanOp.SUBTRACT
                else -> BooleanOp.INTERSECT
            }
            replace(targetId, bodies) { body -> body.booleanWith(solid, op) }
        }
    }

    private fun replace(id: Long, bodies: LinkedHashMap<Long, Shape>, transform: (Shape) -> Shape) {
        val old = bodies[id] ?: throw IllegalArgumentException("Body no longer exists")
        val updated = transform(old)
        bodies[id] = updated // keeps the body's place in creation order
        old.close()
    }
}
