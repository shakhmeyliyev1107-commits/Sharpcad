package com.sharpcad.document

import com.sharpcad.kernel.BooleanOp
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStoreTest {
    private val rect = listOf(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0, 10.0, 0.0, 10.0, 0.0, 0.0)

    private fun every(): List<Feature> = listOf(
        ImportFeature(1, "/data/a.step", "a"),
        ExtrudeFeature(2, Profile(PlaneSpec.XZ, rect, listOf(5.0, 5.0, 2.0), regions = listOf(0)), -4.0, Operation.CUT, target = 1, symmetric = true),
        RevolveFeature(3, Profile(PlaneSpec.XY, rect, emptyList()), RevolveAxis.U, 90.0),
        FilletFeature(4, body = 2, radius = 1.5, face = 3),
        ChamferFeature(5, body = 2, distance = 0.5),
        PushPullFeature(6, body = 2, face = 1, distance = 2.0),
        FilletFeature(8, body = 2, radius = 1.0, edge = 3),
        BooleanFeature(7, target = 2, tool = 3, op = BooleanOp.INTERSECT),
    )

    @Test
    fun everyFeatureTypeRoundTrips() {
        val root = Files.createTempDirectory("projects").toFile()
        val store = ProjectStore(root)
        val data = ProjectData(name = "Bracket ü", features = every(), materials = mapOf(2L to "metal.gold_polished"), variables = mapOf("w" to 20.0))
        val id = store.newId()
        store.save(id, data)
        assertEquals(data, store.load(id))
        assertTrue(File(root, "$id/project.json").readText().contains("\"type\":\"extrude\""))
        root.deleteRecursively()
    }

    @Test
    fun listIsNewestFirstAndBrokenFilesAreSkipped() {
        val root = Files.createTempDirectory("projects").toFile()
        val store = ProjectStore(root)
        store.save("old", ProjectData(name = "Old", features = emptyList()))
        File(root, "old/project.json").setLastModified(1_000_000)
        store.save("new", ProjectData(name = "New", features = emptyList()))
        File(root, "broken").mkdirs()
        File(root, "broken/project.json").writeText("{not json")
        assertEquals(listOf("New", "Old"), store.list().map { it.name })
        assertNull(store.load("broken"))
        assertNull(store.load("missing"))
        store.delete("old")
        assertEquals(listOf("new"), store.list().map { it.id })
        root.deleteRecursively()
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val root = Files.createTempDirectory("projects").toFile()
        File(root, "x").mkdirs()
        File(root, "x/project.json").writeText("""{"format":1,"name":"X","features":[],"futureField":42}""")
        assertEquals("X", ProjectStore(root).load("x")?.name)
        root.deleteRecursively()
    }
}
