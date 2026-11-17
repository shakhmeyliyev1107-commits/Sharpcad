package com.sharpcad.document

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingTest {
    private val rect = listOf(0.0, 0.0, 40.0, 0.0, 40.0, 0.0, 40.0, 20.0, 40.0, 20.0, 0.0, 20.0, 0.0, 20.0, 0.0, 0.0)

    @Test
    fun viewsOfABlockAreLaidOutWithDimensionsAndATitleBlock() = runBlocking<Unit> {
        Document().use { doc ->
            assertTrue(doc.drawingViews().isEmpty())
            doc.reset(listOf(ExtrudeFeature(1, Profile(PlaneSpec.XY, rect, listOf(20.0, 10.0, 4.0)), 10.0)))
            val views = doc.drawingViews()
            assertEquals(4, views.size)
            val front = views.first { it.view == DrawingView.FRONT }.bounds
            assertEquals(40f, front[2] - front[0], 1e-3f)
            assertEquals(10f, front[3] - front[1], 1e-3f)

            val sheet = DrawingLayout.layout("Block", views)
            assertEquals("2:1", sheet.scaleLabel) // 40 mm wide fits A4 at 2:1, not 5:1
            assertEquals(6, sheet.dimensions.size) // width and height for three orthographic views
            assertTrue(sheet.dimensions.any { it.text == "40" } && sheet.dimensions.any { it.text == "20" })
            // Everything drawn stays on the paper.
            for (segs in listOf(sheet.visible, sheet.hidden)) for (i in segs.indices step 2) {
                assertTrue(segs[i] in 0f..sheet.width && segs[i + 1] in 0f..sheet.height)
            }
        }
    }

    @Test
    fun largePartsGetAReductionScale() {
        val big = ViewLines(DrawingView.FRONT, floatArrayOf(0f, 0f, 2000f, 0f, 2000f, 0f, 2000f, 500f), FloatArray(0))
        // 2000 mm across 223 mm of usable paper: 1:10 is the largest scale that fits.
        assertEquals("1:10", DrawingLayout.layout("Big", listOf(big)).scaleLabel)
    }

    @Test
    fun svgAndDxfContainEveryLine() {
        val v = ViewLines(DrawingView.FRONT, floatArrayOf(0f, 0f, 10f, 0f, 10f, 0f, 10f, 5f), floatArrayOf(0f, 0f, 10f, 5f))
        val sheet = DrawingLayout.layout("T & <test>", listOf(v))
        val svg = DrawingExport.svg(sheet)
        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("stroke-dasharray")) // hidden lines dashed
        assertTrue(svg.contains("T &amp; &lt;test&gt;"))
        val lineCount = (sheet.frame.size + sheet.visible.size + sheet.hidden.size) / 4 + sheet.dimensions.size
        assertEquals(lineCount, Regex("<line ").findAll(svg).count())

        val dxf = DrawingExport.dxf(sheet)
        assertTrue(dxf.trimEnd().endsWith("EOF"))
        assertEquals(lineCount, Regex("^LINE$", RegexOption.MULTILINE).findAll(dxf).count())
        assertTrue(dxf.contains("HIDDEN\n70\n0\n62\n8\n6\nDASHED"))
    }
}
