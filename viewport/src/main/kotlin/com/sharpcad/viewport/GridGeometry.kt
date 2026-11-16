package com.sharpcad.viewport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Builds the ground grid (XY plane) and the coordinate axes as coloured line segments.
 * Vertex layout: x, y, z floats followed by four colour bytes (linear RGBA), 16 bytes.
 */
internal object GridGeometry {
    const val STRIDE = 16

    /** Half the side length of the grid, in millimetres. */
    const val EXTENT = 200f
    private const val SPACING = 10f
    private const val MAJOR_EVERY = 5

    private const val MINOR = 0xFF2B313B.toInt()
    private const val MAJOR = 0xFF3D4654.toInt()
    private const val AXIS_X = 0xFFE5484D.toInt()
    private const val AXIS_Y = 0xFF46A758.toInt()
    private const val AXIS_Z = 0xFF3E8BFF.toInt()

    class Lines(val vertices: ByteBuffer, val vertexCount: Int)

    fun build(): Lines {
        val steps = (EXTENT / SPACING).toInt()
        val lineCount = (2 * steps + 1) * 2 + 3
        val buffer = ByteBuffer.allocateDirect(lineCount * 2 * STRIDE).order(ByteOrder.nativeOrder())

        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, srgb: Int) {
            val colour = linearBytes(srgb)
            buffer.putFloat(x0).putFloat(y0).putFloat(z0).put(colour)
            buffer.putFloat(x1).putFloat(y1).putFloat(z1).put(colour)
        }

        for (i in -steps..steps) {
            if (i == 0) continue // the axes are drawn there instead
            val offset = i * SPACING
            val colour = if (i % MAJOR_EVERY == 0) MAJOR else MINOR
            line(offset, -EXTENT, 0f, offset, EXTENT, 0f, colour)
            line(-EXTENT, offset, 0f, EXTENT, offset, 0f, colour)
        }
        // Negative halves of the X and Y axes stay grey so the positive direction is obvious.
        line(-EXTENT, 0f, 0f, 0f, 0f, 0f, MAJOR)
        line(0f, -EXTENT, 0f, 0f, 0f, 0f, MAJOR)
        line(0f, 0f, 0f, EXTENT, 0f, 0f, AXIS_X)
        line(0f, 0f, 0f, 0f, EXTENT, 0f, AXIS_Y)
        line(0f, 0f, 0f, 0f, 0f, EXTENT / 2, AXIS_Z)

        val count = buffer.position() / STRIDE
        buffer.flip()
        return Lines(buffer, count)
    }

    /** Converts 0xAARRGGBB sRGB to the four linear RGBA bytes Filament expects. */
    private fun linearBytes(srgb: Int): ByteArray {
        fun channel(shift: Int): Byte {
            val linear = srgbToLinear(((srgb shr shift) and 0xFF) / 255f)
            return (linear * 255f + 0.5f).toInt().toByte()
        }
        return byteArrayOf(channel(16), channel(8), channel(0), (srgb ushr 24).toByte())
    }
}

/** sRGB transfer function for one channel in 0..1. */
internal fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

/** Converts 0xRRGGBB sRGB to linear RGB floats. */
internal fun srgbToLinear(rgb: Int): FloatArray = floatArrayOf(
    srgbToLinear(((rgb shr 16) and 0xFF) / 255f),
    srgbToLinear(((rgb shr 8) and 0xFF) / 255f),
    srgbToLinear((rgb and 0xFF) / 255f),
)
