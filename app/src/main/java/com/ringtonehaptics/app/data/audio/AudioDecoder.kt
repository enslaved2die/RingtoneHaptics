package com.ringtonehaptics.app.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.ringtonehaptics.app.domain.model.AudioTrackData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TIMEOUT_US = 5000L

    suspend fun decodeAudio(context: Context, audioUri: Uri): Result<AudioTrackData> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, audioUri, null)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var mime: String? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null || mime == null) {
                return@withContext Result.failure(IllegalArgumentException("No supported audio track found"))
            }

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val durationMs = durationUs / 1000L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEos = false

            val leftSamplesList = ArrayList<FloatArray>()
            val rightSamplesList = ArrayList<FloatArray>()
            var totalDecodedSamples = 0

            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numShorts = shortBuffer.remaining()
                        val frames = numShorts / channelCount

                        val leftChunk = FloatArray(frames)
                        val rightChunk = FloatArray(frames)

                        for (f in 0 until frames) {
                            if (channelCount == 1) {
                                val sample = shortBuffer.get().toFloat() / 32768.0f
                                leftChunk[f] = sample
                                rightChunk[f] = sample
                            } else {
                                leftChunk[f] = shortBuffer.get().toFloat() / 32768.0f
                                rightChunk[f] = shortBuffer.get().toFloat() / 32768.0f
                                // Skip extra channels if surround sound
                                for (c in 2 until channelCount) {
                                    shortBuffer.get()
                                }
                            }
                        }

                        leftSamplesList.add(leftChunk)
                        rightSamplesList.add(rightChunk)
                        totalDecodedSamples += frames
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }

                    outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                }
            }

            // Flatten chunks into contiguous arrays
            val fullLeft = FloatArray(totalDecodedSamples)
            val fullRight = FloatArray(totalDecodedSamples)
            var offset = 0
            for (chunk in leftSamplesList) {
                System.arraycopy(chunk, 0, fullLeft, offset, chunk.size)
                offset += chunk.size
            }
            offset = 0
            for (chunk in rightSamplesList) {
                System.arraycopy(chunk, 0, fullRight, offset, chunk.size)
                offset += chunk.size
            }

            // Generate overview waveform. High enough resolution that zooming into the timeline
            // (up to 20x) keeps revealing real detail instead of running out of points and just
            // stretching the same few bars wider - see MultiLaneDawTimeline's computeBarPlan.
            val overviewPoints = 6000
            val waveformOverview = FloatArray(overviewPoints)
            val step = totalDecodedSamples.toFloat() / overviewPoints
            for (p in 0 until overviewPoints) {
                val start = (p * step).toInt().coerceIn(0, totalDecodedSamples - 1)
                val end = ((p + 1) * step).toInt().coerceIn(start + 1, totalDecodedSamples)
                var maxAmp = 0.0f
                for (s in start until end) {
                    val amp = max(abs(fullLeft[s]), abs(fullRight[s]))
                    if (amp > maxAmp) maxAmp = amp
                }
                waveformOverview[p] = maxAmp.coerceIn(0.0f, 1.0f)
            }

            val title = queryFileName(context, audioUri) ?: "Ringtone"

            Result.success(
                AudioTrackData(
                    title = title,
                    sampleRate = sampleRate,
                    durationMs = if (durationMs > 0) durationMs else (totalDecodedSamples * 1000L / sampleRate),
                    leftChannel = fullLeft,
                    rightChannel = fullRight,
                    waveformOverview = waveformOverview
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio file", e)
            Result.failure(e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up decoder resources", e)
            }
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.substringBeforeLast(".")
            } else null
        }
    }
}
