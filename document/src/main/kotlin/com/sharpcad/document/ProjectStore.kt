package com.sharpcad.document

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Everything saved for one project. Bump [FORMAT] when the layout changes incompatibly. */
@Serializable
data class ProjectData(
    val format: Int = FORMAT,
    val name: String,
    val features: List<Feature>,
    /** Material preset id per body id. */
    val materials: Map<Long, String> = emptyMap(),
    /** User-given body names by body id. */
    val bodyNames: Map<Long, String> = emptyMap(),
    /** Ids of hidden bodies. */
    val hidden: Set<Long> = emptySet(),
    /** Named values typed in size fields (`w=20`), in millimetres. */
    val variables: Map<String, Double> = emptyMap(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** A project on disk, for the recent-projects list. */
data class ProjectEntry(val id: String, val name: String, val modifiedMillis: Long)

/**
 * Offline project storage: one folder per project under [root] holding `project.json`
 * (feature history, names, materials). Imported STEP/IGES files stay where the import
 * feature points. Writes are atomic: a crash mid-save keeps the previous version.
 */
class ProjectStore(private val root: File) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true // newer files with extra fields still open
        classDiscriminator = "type"
    }

    init {
        root.mkdirs()
    }

    fun newId(): String = "p" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

    fun save(id: String, data: ProjectData) {
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, FILE)
        val temp = File(dir, "$FILE.tmp")
        temp.writeText(json.encodeToString(ProjectData.serializer(), data))
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not save project $id" }
        }
    }

    /** The project, or `null` if it is missing or unreadable. */
    fun load(id: String): ProjectData? {
        val file = File(File(root, id), FILE)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(ProjectData.serializer(), file.readText()).takeIf { it.format <= ProjectData.FORMAT }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Saved projects, most recently modified first. */
    fun list(): List<ProjectEntry> = root.listFiles().orEmpty()
        .mapNotNull { dir ->
            val file = File(dir, FILE)
            if (!file.isFile) return@mapNotNull null
            val name = load(dir.name)?.name ?: return@mapNotNull null
            ProjectEntry(dir.name, name, file.lastModified())
        }
        .sortedByDescending { it.modifiedMillis }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private companion object {
        const val FILE = "project.json"
    }
}
