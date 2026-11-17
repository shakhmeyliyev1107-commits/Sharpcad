package com.sharpcad.document

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A triangle mesh as plain arrays: xyz per vertex (mm), three vertex indices per triangle. */
class MeshPart(val name: String, val positions: FloatArray, val indices: IntArray) {
    init {
        require(positions.size % 3 == 0 && indices.size % 3 == 0) { "Positions and indices must be triples" }
        val vertices = positions.size / 3
        require(indices.all { it in 0 until vertices }) { "Index out of range" }
    }

    val triangleCount: Int get() = indices.size / 3
}

/** Mesh file formats for 3D printing and other tools. */
enum class MeshFormat(val extension: String, val mimeType: String) {
    STL("stl", "model/stl"),
    OBJ("obj", "model/obj"),
    THREE_MF("3mf", "model/3mf"),
}

/**
 * Writes triangle meshes to STL (binary), OBJ and 3MF. Pure Kotlin, no kernel calls,
 * so it can run on any thread. Units are millimetres in every format.
 */
object MeshExport {

    fun write(format: MeshFormat, parts: List<MeshPart>, file: File) {
        file.outputStream().buffered().use { write(format, parts, it) }
    }

    fun write(format: MeshFormat, parts: List<MeshPart>, out: OutputStream) {
        require(parts.isNotEmpty()) { "Nothing to export" }
        when (format) {
            MeshFormat.STL -> writeStl(parts, out)
            MeshFormat.OBJ -> writeObj(parts, out)
            MeshFormat.THREE_MF -> write3mf(parts, out)
        }
    }

    /** Binary STL: 80-byte header, triangle count, then normal + 3 vertices + attribute per triangle. */
    private fun writeStl(parts: List<MeshPart>, out: OutputStream) {
        val header = ByteArray(80)
        "SharpCAD binary STL, millimetres".toByteArray().copyInto(header)
        out.write(header)
        val total = parts.sumOf { it.triangleCount }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(total).array())
        val record = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        for (part in parts) {
            val p = part.positions
            for (t in 0 until part.triangleCount) {
                val a = part.indices[t * 3] * 3
                val b = part.indices[t * 3 + 1] * 3
                val c = part.indices[t * 3 + 2] * 3
                val n = normal(p, a, b, c)
                record.clear()
                record.putFloat(n[0]).putFloat(n[1]).putFloat(n[2])
                for (v in intArrayOf(a, b, c)) record.putFloat(p[v]).putFloat(p[v + 1]).putFloat(p[v + 2])
                record.putShort(0)
                out.write(record.array())
            }
        }
    }

    /** Wavefront OBJ: one object per part, 1-based indices. */
    private fun writeObj(parts: List<MeshPart>, out: OutputStream) {
        val w = out.bufferedWriter()
        w.write("# SharpCAD OBJ export, millimetres\n")
        var base = 1
        for (part in parts) {
            w.write("o ${part.name.replace(Regex("\\s+"), "_")}\n")
            val p = part.positions
            for (i in p.indices step 3) w.write(String.format(Locale.US, "v %.6f %.6f %.6f\n", p[i], p[i + 1], p[i + 2]))
            for (t in 0 until part.triangleCount) {
                w.write("f ${part.indices[t * 3] + base} ${part.indices[t * 3 + 1] + base} ${part.indices[t * 3 + 2] + base}\n")
            }
            base += p.size / 3
        }
        w.flush()
    }

    /** 3MF: a zip package with one mesh object per part, all placed in the build. */
    private fun write3mf(parts: List<MeshPart>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun entry(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
        entry(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>""",
        )
        entry(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>""",
        )
        zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
        val w = zip.bufferedWriter()
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n<resources>\n")
        parts.forEachIndexed { i, part ->
            w.write("<object id=\"${i + 1}\" type=\"model\" name=\"${xml(part.name)}\"><mesh><vertices>\n")
            val p = part.positions
            for (v in p.indices step 3) w.write(String.format(Locale.US, "<vertex x=\"%.6f\" y=\"%.6f\" z=\"%.6f\"/>\n", p[v], p[v + 1], p[v + 2]))
            w.write("</vertices><triangles>\n")
            for (t in 0 until part.triangleCount) {
                w.write("<triangle v1=\"${part.indices[t * 3]}\" v2=\"${part.indices[t * 3 + 1]}\" v3=\"${part.indices[t * 3 + 2]}\"/>\n")
            }
            w.write("</triangles></mesh></object>\n")
        }
        w.write("</resources>\n<build>\n")
        parts.indices.forEach { w.write("<item objectid=\"${it + 1}\"/>\n") }
        w.write("</build>\n</model>\n")
        w.flush()
        zip.closeEntry()
        zip.finish()
    }

    private fun normal(p: FloatArray, a: Int, b: Int, c: Int): FloatArray {
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        return if (len > 0f) floatArrayOf(nx / len, ny / len, nz / len) else floatArrayOf(0f, 0f, 0f)
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
