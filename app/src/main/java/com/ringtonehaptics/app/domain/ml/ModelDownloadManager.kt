package com.ringtonehaptics.app.domain.ml

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val MODEL_URL = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx"
private const val MODEL_FILE_NAME = "htdemucs_fp16weights.onnx"
const val MODEL_APPROX_SIZE_MB = 166

private const val DRUM_MODEL_URL = "https://github.com/enslaved2die/RingtoneHaptics/releases/download/models-v1/adtof_frame_rnn.onnx"
private const val DRUM_MODEL_FILE_NAME = "adtof_frame_rnn.onnx"
private const val DRUM_MODEL_SHA256 = "e6109473f7ce0790b47134bbecf31d40559099e823ea4f6375a9d2ce18a03845"

/**
 * Streams [url] into [target] through a `.part` file that is only renamed into place once the
 * transfer completes, so an interrupted download never leaves a truncated model that
 * isModelReady()-style checks would mistake for a good one. Reports 0f..1f progress.
 * Throws on network/IO failure.
 */
private fun downloadFile(url: String, target: File, onProgress: (Float) -> Unit) {
    target.parentFile?.mkdirs()
    val tempFile = File(target.parentFile, "${target.name}.part")

    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    try {
        connection.connect()
        val totalBytes = connection.contentLengthLong.coerceAtLeast(1L)
        var downloaded = 0L

        connection.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
            }
        }

        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
    } finally {
        connection.disconnect()
    }
}

/**
 * Downloads the htdemucs ONNX model (~166MB) into app-private storage on first opt-in, per
 * implementation_plan.md Phase 2 ("Enhanced Mode" is opt-in, not bundled into the APK for
 * every user). Subsequent runs reuse the cached file.
 */
class ModelDownloadManager(private val context: Context) {

    private val modelFile: File
        get() = File(context.filesDir, "models/$MODEL_FILE_NAME")

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun modelPath(): String = modelFile.absolutePath

    /** Streams the model to disk, reporting 0f..1f progress. Throws on network/IO failure. */
    fun downloadModel(onProgress: (Float) -> Unit) = downloadFile(MODEL_URL, modelFile, onProgress)
}

/**
 * The ADTOF Frame_RNN drum-hit classifier (~1.8MB, CC BY-NC-SA 4.0 - see README). Fetched on
 * first use instead of being bundled in the APK so the weights' license terms and attribution
 * travel with the download rather than being silently baked into every APK copy.
 *
 * Unlike the (much larger, already-trusted-host) stem model this one is pinned by SHA-256: it is a
 * tiny file whose only failure modes are a corrupt transfer or a swapped file at the URL, and an
 * ONNX graph is executable-ish input to the runtime, so it is cheap and worth verifying.
 */
class DrumModelStore(private val context: Context) {

    val modelFile: File
        get() = File(context.filesDir, "models/$DRUM_MODEL_FILE_NAME")

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    /** No-op when already present; otherwise downloads + verifies. Throws on network failure or checksum mismatch. */
    fun ensureDownloaded() {
        if (isReady()) return
        downloadFile(DRUM_MODEL_URL, modelFile) {}
        if (sha256Hex(modelFile) != DRUM_MODEL_SHA256) {
            modelFile.delete()
            throw IOException("Downloaded drum model failed its SHA-256 check")
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
