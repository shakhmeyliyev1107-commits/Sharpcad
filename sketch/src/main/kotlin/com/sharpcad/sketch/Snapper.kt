package com.sharpcad.sketch

import kotlin.math.hypot
import kotlin.math.roundToLong

/** What a snapped position was attracted to, strongest first. */
enum class SnapKind { ENDPOINT, CENTER, MIDPOINT, GRID, NONE }

/**
 * A position after snapping.
 *
 * @property point the entity point snapped to, for [SnapKind.ENDPOINT] and
 *   [SnapKind.CENTER]; used to add a coincident constraint. `null` otherwise.
 */
data class Snap(val x: Double, val y: Double, val kind: SnapKind, val point: PointRef? = null)

/** Finds what a touched position should snap to. Pure function of its inputs. */
object Snapper {
    /**
     * Snaps ([x], [y]) to existing geometry or the grid.
     *
     * Entity points win over midpoints, which win over the grid; within a kind the
     * nearest candidate wins. Nothing farther than [tolerance] attracts.
     *
     * @param tolerance snapping radius in sketch millimetres (derive it from a screen
     *   distance at the current zoom).
     * @param gridSpacing grid pitch in millimetres, or `null` to disable grid snapping.
     * @param exclude ids of entities to ignore, e.g. the one being drawn.
     */
    fun snap(
        x: Double,
        y: Double,
        entities: List<SketchEntity>,
        tolerance: Double,
        gridSpacing: Double? = null,
        exclude: Set<Int> = emptySet(),
    ): Snap {
        var best: Snap? = null
        var bestDistance = Double.MAX_VALUE

        fun offer(cx: Double, cy: Double, kind: SnapKind, point: PointRef?) {
            val d = hypot(cx - x, cy - y)
            if (d > tolerance) return
            val current = best
            val stronger = current == null || kind.ordinal < current.kind.ordinal
            val sameButCloser = current != null && kind == current.kind && d < bestDistance
            if (stronger || sameButCloser) {
                best = Snap(cx, cy, kind, point)
                bestDistance = d
            }
        }

        for (entity in entities) {
            if (entity.id in exclude) continue
            when (entity) {
                is SketchLine -> {
                    offer(entity.x1, entity.y1, SnapKind.ENDPOINT, PointRef(entity.id, PointRole.START))
                    offer(entity.x2, entity.y2, SnapKind.ENDPOINT, PointRef(entity.id, PointRole.END))
                    offer((entity.x1 + entity.x2) / 2, (entity.y1 + entity.y2) / 2, SnapKind.MIDPOINT, null)
                }
                is SketchCircle ->
                    offer(entity.cx, entity.cy, SnapKind.CENTER, PointRef(entity.id, PointRole.CENTER))
            }
        }

        if (gridSpacing != null && gridSpacing > 0.0) {
            offer(
                (x / gridSpacing).roundToLong() * gridSpacing,
                (y / gridSpacing).roundToLong() * gridSpacing,
                SnapKind.GRID, null,
            )
        }
        return best ?: Snap(x, y, SnapKind.NONE)
    }
}
