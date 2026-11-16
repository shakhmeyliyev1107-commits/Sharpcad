package com.sharpcad.viewport

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Turns touch events into camera navigation:
 * one finger drags to pan, two fingers drag to orbit and pinch to zoom, double tap fits.
 */
internal class NavigationGestures(context: Context, private val listener: Listener) {

    interface Listener {
        fun onPan(dx: Float, dy: Float)
        fun onOrbit(dx: Float, dy: Float)
        fun onZoom(factor: Float)
        fun onFit()
    }

    private var pointers = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f

    private val doubleTap = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                listener.onFit()
                return true
            }
        },
    )

    fun onTouchEvent(event: MotionEvent): Boolean {
        doubleTap.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> rebase(event, skip = -1)
            // The lifted pointer is still listed in the event; leave it out of the new baseline.
            MotionEvent.ACTION_POINTER_UP -> rebase(event, skip = event.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointers = 0
            MotionEvent.ACTION_MOVE -> move(event)
        }
        return true
    }

    private fun move(event: MotionEvent) {
        if (pointers == 0 || event.pointerCount != pointers) {
            rebase(event, skip = -1)
            return
        }
        val x = centroidX(event, -1)
        val y = centroidY(event, -1)
        val dx = x - lastX
        val dy = y - lastY
        if (pointers == 1) {
            if (dx != 0f || dy != 0f) listener.onPan(dx, dy)
        } else {
            if (dx != 0f || dy != 0f) listener.onOrbit(dx, dy)
            val span = span(event, x, y)
            if (lastSpan > MIN_SPAN && span > MIN_SPAN) listener.onZoom(span / lastSpan)
            lastSpan = span
        }
        lastX = x
        lastY = y
    }

    /** Restarts delta tracking, so adding or lifting a finger never makes the view jump. */
    private fun rebase(event: MotionEvent, skip: Int) {
        pointers = event.pointerCount - if (skip >= 0) 1 else 0
        if (pointers <= 0) return
        lastX = centroidX(event, skip)
        lastY = centroidY(event, skip)
        lastSpan = if (pointers >= 2) span(event, lastX, lastY, skip) else 0f
    }

    private fun centroidX(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getX(i); n++ }
        return if (n > 0) sum / n else 0f
    }

    private fun centroidY(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getY(i); n++ }
        return if (n > 0) sum / n else 0f
    }

    /** Mean distance of the pointers from their centroid. */
    private fun span(event: MotionEvent, cx: Float, cy: Float, skip: Int = -1): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            sum += hypot(event.getX(i) - cx, event.getY(i) - cy)
            n++
        }
        return if (n > 0) sum / n else 0f
    }

    private companion object {
        /** Below this span (pixels) the ratio between two spans is mostly noise. */
        const val MIN_SPAN = 10f
    }
}
