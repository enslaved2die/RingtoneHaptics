package com.ringtonehaptics.app.data.storage

import android.content.Context
import com.ringtonehaptics.app.domain.ml.DrumClass
import com.ringtonehaptics.app.domain.ml.StemSeparationResult
import com.ringtonehaptics.app.domain.model.HapticProject
import com.ringtonehaptics.app.domain.model.ProjectSummary
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Persists editing sessions as JSON files in app-private storage, selectable again from the
 * launch screen instead of always starting from "Pick Sound File".
 */
class ProjectRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val projectsDir: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    private fun fileFor(id: String) = File(projectsDir, "$id.json")
    private fun stemsFileFor(id: String) = File(projectsDir, "$id.stems")

    fun listProjects(): List<ProjectSummary> {
        val dir = projectsDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val project = json.decodeFromString<HapticProject>(file.readText())
                ProjectSummary(
                    id = project.id,
                    name = project.name,
                    updatedAtMs = project.updatedAtMs,
                    durationMs = project.durationMs,
                    clipCount = project.hapticClips.size
                )
            }.getOrNull()
        }.sortedByDescending { it.updatedAtMs }
    }

    fun loadProject(id: String): HapticProject? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<HapticProject>(file.readText()) }.getOrNull()
    }

    /** Creates a new project (id == null) or overwrites an existing one (id != null). Returns the saved id. */
    fun saveProject(
        id: String?,
        name: String,
        sourceUri: String,
        durationMs: Long,
        sampleRate: Int,
        hapticClips: List<com.ringtonehaptics.app.domain.model.HapticClip>,
        filterConfig: com.ringtonehaptics.app.domain.model.FilterConfig,
        beatGrid: com.ringtonehaptics.app.domain.model.BeatGridInfo,
        selectedAudioStem: String?,
        selectedDrumClasses: Set<DrumClass> = emptySet(),
        wasMlEnhanced: Boolean
    ): String {
        val now = System.currentTimeMillis()
        val existing = id?.let { loadProject(it) }
        val project = HapticProject(
            id = id ?: UUID.randomUUID().toString(),
            name = name,
            sourceUri = sourceUri,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            durationMs = durationMs,
            sampleRate = sampleRate,
            hapticClips = hapticClips,
            filterConfig = filterConfig,
            beatGrid = beatGrid,
            selectedAudioStem = selectedAudioStem,
            selectedDrumClasses = selectedDrumClasses,
            wasMlEnhanced = wasMlEnhanced
        )
        fileFor(project.id).writeText(json.encodeToString(HapticProject.serializer(), project))
        return project.id
    }

    fun deleteProject(id: String) {
        fileFor(id).delete()
        deleteStems(id)
    }

    /**
     * Caches ML-separated stem PCM (drums/bass/other/vocals) to app-private storage so reopening
     * a project that was ML-enhanced restores it instantly instead of re-running the multi-minute
     * on-device ONNX separation every time - a raw binary blob (not JSON) since this is tens of MB
     * per track, plain float32 arrays back to back behind a tiny header. Local-device-only format
     * (native byte order) - it never needs to be portable, only read back by this same install.
     */
    fun saveStems(id: String, stems: StemSeparationResult) {
        val file = stemsFileFor(id)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val channel = raf.channel
            val header = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
            header.putInt(stems.drums.size).flip()
            channel.write(header)
            for (arr in listOf(stems.drums, stems.bass, stems.other, stems.vocals)) {
                val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.nativeOrder())
                buf.asFloatBuffer().put(arr)
                channel.write(buf)
            }
        }
    }

    fun loadStems(id: String): StemSeparationResult? {
        val file = stemsFileFor(id)
        if (!file.exists()) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val header = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                channel.read(header)
                header.flip()
                val n = header.int

                fun readArr(): FloatArray {
                    val buf = ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder())
                    var readTotal = 0
                    while (readTotal < buf.capacity()) {
                        val r = channel.read(buf)
                        if (r < 0) break
                        readTotal += r
                    }
                    buf.flip()
                    val arr = FloatArray(n)
                    buf.asFloatBuffer().get(arr)
                    return arr
                }

                StemSeparationResult(drums = readArr(), bass = readArr(), other = readArr(), vocals = readArr())
            }
        }.getOrNull()
    }

    fun deleteStems(id: String) {
        stemsFileFor(id).delete()
    }
}
