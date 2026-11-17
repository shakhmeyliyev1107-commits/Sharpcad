package com.sharpcad.document

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** A mesh with normals and a material, for glTF export. Arrays as in [MeshPart]. */
class GlbPart(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    val material: MaterialPreset,
)

/**
 * Writes binary glTF 2.0 (.glb), the format AR viewers load. Converts millimetres to
 * metres and the CAD Z-up axes to glTF's Y-up. Pure Kotlin.
 */
object GlbExport {

    fun write(parts: List<GlbPart>, out: OutputStream) {
        require(parts.isNotEmpty()) { "Nothing to export" }
        val bin = ByteArrayBuilder()
        val json = StringBuilder()
        val meshes = ArrayList<String>()
        val accessors = ArrayList<String>()
        val views = ArrayList<String>()
        val materials = ArrayList<String>()

        for ((i, part) in parts.withIndex()) {
            require(part.positions.size == part.normals.size) { "One normal per vertex is required" }
            val count = part.positions.size / 3
            // Z-up millimetres → Y-up metres: (x, y, z) → (x, z, -y) / 1000.
            val pos = FloatArray(part.positions.size)
            val nor = FloatArray(part.normals.size)
            for (v in 0 until count) {
                pos[v * 3] = part.positions[v * 3] / 1000f
                pos[v * 3 + 1] = part.positions[v * 3 + 2] / 1000f
                pos[v * 3 + 2] = -part.positions[v * 3 + 1] / 1000f + 0f // + 0f turns -0.0 into 0.0
                nor[v * 3] = part.normals[v * 3]
                nor[v * 3 + 1] = part.normals[v * 3 + 2]
                nor[v * 3 + 2] = -part.normals[v * 3 + 1] + 0f
            }
            val min = FloatArray(3) { k -> (0 until count).minOf { pos[it * 3 + k] } }
            val max = FloatArray(3) { k -> (0 until count).maxOf { pos[it * 3 + k] } }

            val posView = views.size
            views += view(bin.append(pos), pos.size * 4, 34962)
            val norView = views.size
            views += view(bin.append(nor), nor.size * 4, 34962)
            val idxView = views.size
            views += view(bin.append(part.indices), part.indices.size * 4, 34963)

            val posAcc = accessors.size
            accessors += String.format(
                Locale.US, """{"bufferView":%d,"componentType":5126,"count":%d,"type":"VEC3","min":[%f,%f,%f],"max":[%f,%f,%f]}""",
                posView, count, min[0], min[1], min[2], max[0], max[1], max[2],
            )
            val norAcc = accessors.size
            accessors += """{"bufferView":$norView,"componentType":5126,"count":$count,"type":"VEC3"}"""
            val idxAcc = accessors.size
            accessors += """{"bufferView":$idxView,"componentType":5125,"count":${part.indices.size},"type":"SCALAR"}"""

            val m = part.material
            materials += String.format(
                Locale.US, """{"name":"%s","pbrMetallicRoughness":{"baseColorFactor":[%f,%f,%f,1],"metallicFactor":%f,"roughnessFactor":%f},"doubleSided":true}""",
                jsonText(m.name), linear(m.color shr 16), linear(m.color shr 8), linear(m.color), m.metallic, m.roughness,
            )
            meshes += """{"name":"${jsonText(part.name)}","primitives":[{"attributes":{"POSITION":$posAcc,"NORMAL":$norAcc},"indices":$idxAcc,"material":$i}]}"""
        }

        json.append("""{"asset":{"version":"2.0","generator":"SharpCAD"},"scene":0,"scenes":[{"nodes":[""")
        json.append(parts.indices.joinToString(","))
        json.append("]}],\"nodes\":[")
        json.append(parts.indices.joinToString(",") { """{"mesh":$it}""" })
        json.append("],\"meshes\":[").append(meshes.joinToString(","))
        json.append("],\"materials\":[").append(materials.joinToString(","))
        json.append("],\"accessors\":[").append(accessors.joinToString(","))
        json.append("],\"bufferViews\":[").append(views.joinToString(","))
        json.append("],\"buffers\":[{\"byteLength\":${bin.size}}]}")

        val jsonBytes = pad(json.toString().toByteArray(Charsets.UTF_8), 0x20)
        val binBytes = pad(bin.toByteArray(), 0)
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val header = ByteBuffer.allocate(12 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(total) // "glTF", version 2
            .putInt(jsonBytes.size).putInt(0x4E4F534A) // "JSON"
        out.write(header.array())
        out.write(jsonBytes)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(binBytes.size).putInt(0x004E4942).array()) // "BIN\0"
        out.write(binBytes)
    }

    private fun view(offset: Int, length: Int, target: Int) =
        """{"buffer":0,"byteOffset":$offset,"byteLength":$length,"target":$target}"""

    private fun linear(channel: Int): Float {
        val c = (channel and 0xFF) / 255f
        return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    private fun pad(bytes: ByteArray, fill: Int): ByteArray {
        val padded = (bytes.size + 3) / 4 * 4
        return if (padded == bytes.size) bytes else bytes.copyOf(padded).also { for (i in bytes.size until padded) it[i] = fill.toByte() }
    }

    private fun jsonText(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Little-endian binary chunk builder; every block starts 4-byte aligned. */
    private class ByteArrayBuilder {
        private val out = java.io.ByteArrayOutputStream()
        val size: Int get() = out.size()

        fun append(values: FloatArray): Int = block(values.size * 4) { b -> values.forEach { b.putFloat(it) } }

        fun append(values: IntArray): Int = block(values.size * 4) { b -> values.forEach { b.putInt(it) } }

        private fun block(bytes: Int, fill: (ByteBuffer) -> Unit): Int {
            while (out.size() % 4 != 0) out.write(0)
            val offset = out.size()
            val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fill(b)
            out.write(b.array())
            return offset
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
