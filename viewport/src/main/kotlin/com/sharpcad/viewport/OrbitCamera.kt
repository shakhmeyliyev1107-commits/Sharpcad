package com.sharpcad.viewport

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Orbit camera for a Z-up CAD scene. Pure math, no Android or Filament types.
 *
 * The camera looks at [target] from [distance] away, at [yaw] around +Z (0 = from +X)
 * and [pitch] above the XY plane. Angles are radians, lengths millimetres.
 */
class OrbitCamera(
    val verticalFov: Double = Math.toRadians(35.0),
) {
    var targetX = 0.0; private set
    var targetY = 0.0; private set
    var targetZ = 0.0; private set
    var distance = 300.0; private set
    var yaw = Math.toRadians(-60.0); private set
    var pitch = Math.toRadians(30.0); private set

    /** Radius of everything that must stay inside the clip range (model plus grid). */
    var sceneRadius = 250.0
        set(value) { field = max(value, 1e-3) }

    val eyeX: Double get() = targetX + distance * cos(pitch) * cos(yaw)
    val eyeY: Double get() = targetY + distance * cos(pitch) * sin(yaw)
    val eyeZ: Double get() = targetZ + distance * sin(pitch)

    /** Near plane: as far out as the scene allows, to keep depth precision. */
    val near: Double get() = max(distance * 0.02, distance - distanceToFarthestScenePoint())
    val far: Double get() = max(distance + distanceToFarthestScenePoint(), near * 2.0)

    /** Rotates around the target. Positive [dx] drags the scene to the right. */
    fun orbit(dxPixels: Float, dyPixels: Float, viewportHeight: Int) {
        if (viewportHeight <= 0) return
        val radiansPerPixel = PI / viewportHeight
        yaw = wrapAngle(yaw - dxPixels * radiansPerPixel)
        pitch = (pitch + dyPixels * radiansPerPixel).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /** Moves the target so that the point under the finger follows the finger. */
    fun pan(dxPixels: Float, dyPixels: Float, viewportHeight: Int) {
        if (viewportHeight <= 0) return
        val worldPerPixel = 2.0 * distance * tan(verticalFov / 2.0) / viewportHeight
        // Camera right and up vectors in world space.
        val rightX = -sin(yaw)
        val rightY = cos(yaw)
        val upX = -sin(pitch) * cos(yaw)
        val upY = -sin(pitch) * sin(yaw)
        val upZ = cos(pitch)
        val right = -dxPixels * worldPerPixel
        val up = dyPixels * worldPerPixel
        targetX += right * rightX + up * upX
        targetY += right * rightY + up * upY
        targetZ += up * upZ
    }

    /** Looks at the target from the given direction; [pitch] is clamped short of the poles. */
    fun setView(yaw: Double, pitch: Double) {
        if (!yaw.isFinite() || !pitch.isFinite()) return
        this.yaw = wrapAngle(yaw)
        this.pitch = pitch.coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /** Model-space size of one screen pixel at the target's depth. */
    fun millimetresPerPixel(viewportHeight: Int): Double =
        if (viewportHeight <= 0) 0.0 else 2.0 * distance * tan(verticalFov / 2.0) / viewportHeight

    /**
     * The view ray through a screen pixel, as `[originX, originY, originZ, dirX, dirY, dirZ]`
     * in model space, or `null` for an empty viewport. The direction is not normalised.
     */
    fun ray(xPixels: Float, yPixels: Float, viewportWidth: Int, viewportHeight: Int): DoubleArray? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val halfHeight = tan(verticalFov / 2.0)
        val halfWidth = halfHeight * viewportWidth / viewportHeight
        val sx = (2.0 * xPixels / viewportWidth - 1.0) * halfWidth
        val sy = (1.0 - 2.0 * yPixels / viewportHeight) * halfHeight
        // Camera basis: forward towards the target, right and up as seen on screen.
        val fx = -cos(pitch) * cos(yaw)
        val fy = -cos(pitch) * sin(yaw)
        val fz = -sin(pitch)
        val rx = -sin(yaw)
        val ry = cos(yaw)
        val ux = -sin(pitch) * cos(yaw)
        val uy = -sin(pitch) * sin(yaw)
        val uz = cos(pitch)
        return doubleArrayOf(
            eyeX, eyeY, eyeZ,
            fx + sx * rx + sy * ux,
            fy + sx * ry + sy * uy,
            fz + sy * uz,
        )
    }

    /** Scales the view; [factor] > 1 zooms in (pinch out). */
    fun zoom(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** Frames a sphere of [radius] around the given centre for the given aspect ratio. */
    fun fit(centerX: Double, centerY: Double, centerZ: Double, radius: Double, aspect: Double) {
        targetX = centerX
        targetY = centerY
        targetZ = centerZ
        val safeAspect = if (aspect.isFinite() && aspect > 0.0) aspect else 1.0
        val horizontalFov = 2.0 * atan(tan(verticalFov / 2.0) * safeAspect)
        val limitingFov = min(verticalFov, horizontalFov)
        val r = max(radius, 1e-3)
        distance = (r / sin(limitingFov / 2.0) * FIT_MARGIN).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    private fun distanceToFarthestScenePoint(): Double {
        val t = sqrt(targetX * targetX + targetY * targetY + targetZ * targetZ)
        return t + sceneRadius
    }

    private fun wrapAngle(a: Double): Double {
        var r = a % (2.0 * PI)
        if (r > PI) r -= 2.0 * PI
        if (r < -PI) r += 2.0 * PI
        return r
    }

    companion object {
        /** Stop just short of the poles so the up vector never degenerates. */
        val MAX_PITCH = Math.toRadians(89.0)
        const val MIN_DISTANCE = 0.5
        const val MAX_DISTANCE = 1.0e6
        const val FIT_MARGIN = 1.15
    }
}
