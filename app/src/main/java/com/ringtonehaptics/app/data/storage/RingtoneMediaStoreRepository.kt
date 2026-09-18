package com.ringtonehaptics.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.ringtonehaptics.app.data.encoder.NativeVorbisEncoder
import com.ringtonehaptics.app.domain.model.AudioTrackData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class RingtoneMediaStoreRepository(private val context: Context) {

    companion object {
        private const val TAG = "RingtoneMediaStore"
    }

    /**
     * Encodes the 3-channel OGG into an app-private cache file only - no MediaStore write yet.
     * Split out from the old one-shot exportRingtoneWithHaptics so the caller can preview this
     * exact file (real audio-coupled haptic playback, not a simulation) before anything touches
     * the user's actual Ringtones library. [commitRingtoneToLibrary] does that second step.
     */
    suspend fun renderRingtonePreview(
        audioData: AudioTrackData,
        hapticPcm: FloatArray,
        ringtoneName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanName = ringtoneName.trim().ifEmpty { "Pixel_Haptic_Ringtone" }
        val tempFile = File(context.cacheDir, "preview_ringtone_${System.currentTimeMillis()}.ogg")
        val encodeResult = NativeVorbisEncoder.encode3ChannelOgg(
            leftPcm = audioData.leftChannel,
            rightPcm = audioData.rightChannel,
            hapticPcm = hapticPcm,
            sampleRate = audioData.sampleRate,
            outputPath = tempFile.absolutePath,
            title = cleanName,
            artist = "RingtoneHaptics"
        )
        if (encodeResult.isFailure) {
            tempFile.delete()
            return@withContext Result.failure(encodeResult.exceptionOrNull() ?: RuntimeException("Encoding failed"))
        }
        Result.success(tempFile)
    }

    /**
     * Looks up an existing ringtone in the library with the same target file name, so the caller
     * can warn before silently creating a "Name (1).ogg" duplicate (MediaStore.insert never
     * overwrites - it just renames on conflict, which is not what a user expects "Export" to do).
     */
    suspend fun findExistingRingtone(ringtoneName: String): Uri? = withContext(Dispatchers.IO) {
        val fileName = ringtoneFileName(ringtoneName)
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(fileName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                return@withContext Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        null
    }

    /**
     * Copies an already-rendered preview file into the real Ringtones library. [overwriteUri],
     * when non-null (the caller already confirmed with the user via [findExistingRingtone]),
     * deletes that existing entry first so this truly replaces it instead of MediaStore silently
     * renaming the new file around it.
     */
    suspend fun commitRingtoneToLibrary(
        previewFile: File,
        ringtoneName: String,
        overwriteUri: Uri?
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cleanName = ringtoneName.trim().ifEmpty { "Pixel_Haptic_Ringtone" }
        val fileName = ringtoneFileName(cleanName)

        try {
            if (overwriteUri != null) {
                resolver.delete(overwriteUri, null, null)
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.TITLE, cleanName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                put(MediaStore.Audio.Media.IS_ALARM, 1)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val targetUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(RuntimeException("Failed to insert MediaStore record"))

            resolver.openOutputStream(targetUri)?.use { outStream ->
                FileInputStream(previewFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: run {
                resolver.delete(targetUri, null, null)
                return@withContext Result.failure(RuntimeException("Failed to open output stream for ringtone"))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(targetUri, updateValues, null, null)
            }

            Log.i(TAG, "Successfully exported ringtone with audio-coupled haptics: $targetUri")
            Result.success(targetUri)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving ringtone to MediaStore", e)
            Result.failure(e)
        }
    }

    private fun ringtoneFileName(ringtoneName: String): String {
        val cleanName = ringtoneName.trim().ifEmpty { "Pixel_Haptic_Ringtone" }
        return "${cleanName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")}.ogg"
    }

    /**
     * Checks if the app has permission to write system settings (required to set default ringtone).
     */
    fun canSetSystemRingtone(): Boolean {
        return Settings.System.canWrite(context)
    }

    /**
     * Directly configures the exported ringtone as the active phone ringtone.
     */
    fun setAsActiveRingtone(ringtoneUri: Uri): Result<Unit> {
        return try {
            if (!canSetSystemRingtone()) {
                Result.failure(SecurityException("WRITE_SETTINGS permission not granted"))
            } else {
                RingtoneManager.setActualDefaultRingtoneUri(
                    context,
                    RingtoneManager.TYPE_RINGTONE,
                    ringtoneUri
                )
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
