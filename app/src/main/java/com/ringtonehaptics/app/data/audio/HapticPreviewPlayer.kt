package com.ringtonehaptics.app.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.ringtonehaptics.app.domain.dsp.AdvancedHapticSynthesizer
import com.ringtonehaptics.app.domain.model.AudioTrackData
import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.domain.model.HapticNode
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HapticPreviewPlayer(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    private var isPlaying = false
    private val patternSynthesizer = AdvancedHapticSynthesizer()

    fun isPlaying(): Boolean = isPlaying

    /**
     * Plays decoded stereo PCM while triggering synchronized tactile impulses at node timings.
     */
    fun startPcmPreview(
        audioData: AudioTrackData,
        nodes: List<HapticNode>,
        startPositionMs: Long = 0L,
        onProgress: (currentMs: Long) -> Unit,
        onCompletion: () -> Unit
    ) {
        stop()
        isPlaying = true

        val sampleRate = audioData.sampleRate
        val totalSamples = audioData.totalSamples
        val startIndex = ((startPositionMs * sampleRate) / 1000L).toInt().coerceIn(0, totalSamples - 1)

        // Interleave stereo PCM 16-bit
        val remainingFrames = totalSamples - startIndex
        val pcmShorts = ShortArray(remainingFrames * 2)
        for (i in 0 until remainingFrames) {
            val sIdx = startIndex + i
            val left = (audioData.leftChannel[sIdx] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            val right = (audioData.rightChannel[sIdx] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            pcmShorts[i * 2] = left
            pcmShorts[i * 2 + 1] = right
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(pcmShorts.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmShorts, 0, pcmShorts.size)
        audioTrack?.play()

        val sortedNodes = nodes.filter { it.timestampMs >= startPositionMs }.sortedBy { it.timestampMs }

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            val startTimeNs = System.nanoTime()
            var nodeIndex = 0

            while (isActive && isPlaying) {
                val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L + startPositionMs

                if (elapsedMs >= audioData.durationMs) {
                    break
                }

                // Trigger node vibration if within window
                while (nodeIndex < sortedNodes.size && sortedNodes[nodeIndex].timestampMs <= elapsedMs + 10) {
                    val node = sortedNodes[nodeIndex]
                    triggerHapticPulse(node.intensity, node.durationMs)
                    nodeIndex++
                }

                onProgress(elapsedMs)
                delay(20)
            }

            stop()
            onCompletion()
        }
    }

    /**
     * Plays decoded stereo PCM while triggering rich tactile clips at timing markers.
     */
    fun startClipsPreview(
        audioData: AudioTrackData,
        clips: List<HapticClip>,
        config: FilterConfig,
        startPositionMs: Long = 0L,
        // Stem Pulse's continuous per-sample envelope (see EditorViewModel.effectiveContinuousEnvelopeFor) -
        // one sample per audio sample at audioData.sampleRate, or empty for every other mode/state,
        // which currently never drives continuous live haptics (only discrete [clips] do).
        continuousEnvelope: FloatArray = FloatArray(0),
        onProgress: (currentMs: Long) -> Unit,
        onCompletion: () -> Unit
    ) {
        stop()
        isPlaying = true

        val sampleRate = audioData.sampleRate
        val totalSamples = audioData.totalSamples
        val startIndex = ((startPositionMs * sampleRate) / 1000L).toInt().coerceIn(0, totalSamples - 1)

        val remainingFrames = totalSamples - startIndex
        val pcmShorts = ShortArray(remainingFrames * 2)
        for (i in 0 until remainingFrames) {
            val sIdx = startIndex + i
            val left = (audioData.leftChannel[sIdx] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            val right = (audioData.rightChannel[sIdx] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            pcmShorts[i * 2] = left
            pcmShorts[i * 2 + 1] = right
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(pcmShorts.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmShorts, 0, pcmShorts.size)
        audioTrack?.play()
        if (continuousEnvelope.isNotEmpty()) {
            startContinuousEnvelopeVibration(continuousEnvelope, sampleRate, startIndex)
        }

        val sortedClips = clips.filter { it.startMs >= startPositionMs }.sortedBy { it.startMs }

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            val startTimeNs = System.nanoTime()
            var clipIndex = 0

            while (isActive && isPlaying) {
                val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L + startPositionMs

                if (elapsedMs >= audioData.durationMs) {
                    break
                }

                while (clipIndex < sortedClips.size && sortedClips[clipIndex].startMs <= elapsedMs + 10) {
                    val clip = sortedClips[clipIndex]
                    triggerSynthesizedClip(clip, config)
                    clipIndex++
                }

                onProgress(elapsedMs)
                delay(20)
            }

            stop()
            onCompletion()
        }
    }

    /**
     * Previews the exported 3-channel OGG Vorbis file using MediaPlayer with unmuted haptic channels!
     */
    fun startCoupledOggPreview(
        uri: Uri,
        onProgress: (currentMs: Long) -> Unit,
        onCompletion: () -> Unit
    ) {
        stop()
        isPlaying = true

        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            // UNMUTE HAPTIC CHANNELS: This is the native Android API for audio-coupled haptics!
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setHapticChannelsMuted(false)
                    .build()
            )
            setOnCompletionListener {
                stop()
                onCompletion()
            }
            prepare()
            start()
        }

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && isPlaying) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        onProgress(mp.currentPosition.toLong())
                    }
                }
                delay(30)
            }
        }
    }

    fun triggerHapticPulse(intensity: Float, durationMs: Int = 80) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255.0f).toInt()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs.toLong(), clampedIntensity)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs.toLong())
        }
    }

    /**
     * Plays the ACTUAL synthesized waveform for [clip] (the same `AdvancedHapticSynthesizer`
     * output that gets encoded into the exported OGG's channel 2) instead of Android's coarse
     * `VibrationEffect.Composition` primitives - closes the "preview feels different than the
     * exported file" gap for editor interactions (quick-tap tests, the main transport preview).
     * Falls back to [triggerPatternHaptic] pre-API 26 or if amplitude-waveform playback fails.
     */
    fun triggerSynthesizedClip(clip: HapticClip, config: FilterConfig) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            triggerPatternHaptic(clip.patternType, clip.intensity, clip.activeBraking)
            return
        }

        try {
            val sampleRate = 44100
            // Pad past the clip's nominal duration so a trailing active-braking tail isn't cut off
            val totalSamples = (((clip.durationMs + 30) * sampleRate) / 1000).coerceAtLeast(64)
            val localClip = clip.copy(startMs = 0)
            val pcm = patternSynthesizer.synthesizeChannel2(
                totalSamples = totalSamples,
                sampleRate = sampleRate,
                clips = listOf(localClip),
                continuousEnvelope = FloatArray(0),
                config = config
            )

            // Rectify into a coarse amplitude envelope an LRA amplitude-waveform can actually
            // follow (a real vibrator can't reproduce individual audio-rate samples as distinct
            // steps) - 5ms steps preserve each pattern's real attack/decay/brake shape.
            val stepMs = 5L
            val stepSamples = ((stepMs * sampleRate) / 1000L).toInt().coerceAtLeast(1)
            val numSteps = (pcm.size / stepSamples).coerceAtLeast(1)
            val timings = LongArray(numSteps) { stepMs }
            val amplitudes = IntArray(numSteps)
            for (i in 0 until numSteps) {
                val start = i * stepSamples
                val end = (start + stepSamples).coerceAtMost(pcm.size)
                var peak = 0.0f
                for (s in start until end) {
                    val a = abs(pcm[s])
                    if (a > peak) peak = a
                }
                amplitudes[i] = (peak.coerceIn(0.0f, 1.0f) * 255).toInt().coerceIn(0, 255)
            }

            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (e: Exception) {
            triggerPatternHaptic(clip.patternType, clip.intensity, clip.activeBraking)
        }
    }

    /**
     * Stem Pulse's live-playback haptic path: builds ONE long amplitude-waveform vibration
     * covering [envelope] from [startSampleIndex] to its end and starts it right alongside the
     * AudioTrack that was just started, so it stays sample-accurately in sync with audio position
     * without needing per-tick retriggering from the 20ms progress-polling loop below (a real LRA
     * can't usefully distinguish steps shorter than a few ms anyway, so 20ms buckets - same order
     * as triggerSynthesizedClip's 5ms - are plenty for how a continuous background layer actually
     * feels, and one long waveform avoids the audible click/gap every VibrationEffect.vibrate()
     * call introduces if it were instead re-triggered clip-by-clip). Cancelled the same way every
     * other in-flight vibration here is: stop()'s unconditional vibrator?.cancel().
     *
     * No carrier-frequency/waveform-shape concept exists here (createWaveform is amplitude+timing
     * only) - VibrationEffect.Composition's frequency-capable primitives need API 30+/R and, even
     * where present, don't accept a continuous custom amplitude curve, only fixed named primitives,
     * so they're not a fit for "follow this arbitrary envelope." Per-stem character therefore has to
     * come entirely from the AMPLITUDE CURVE ITSELF being different per stem, which it already is by
     * the time it reaches here: [envelope] is EditorViewModel.effectiveContinuousEnvelopeFor's output,
     * which applies StemHapticProfile's per-stem sustain-smoothing + transient-emphasis before this
     * function ever sees it (see EnvelopeFollower.applySustainSmoothing/applyTransientEmphasis) - so
     * drums arrive here already carved into sharp isolated bursts and bass arrives already rounded
     * into one smooth swell, purely through the timing/amplitude values this API does control. This
     * is the realistic ceiling for this path on this app's minSdk (26); it is not a full substitute
     * for the export path's actual carrier-frequency change (AdvancedHapticSynthesizer's
     * continuousCarrierFreqHz), which only real audio-coupled LRA playback can reproduce.
     */
    private fun startContinuousEnvelopeVibration(envelope: FloatArray, envelopeSampleRate: Int, startSampleIndex: Int) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (startSampleIndex >= envelope.size) return

        val stepMs = 20L
        val stepSamples = ((stepMs * envelopeSampleRate) / 1000L).toInt().coerceAtLeast(1)
        val remainingSamples = envelope.size - startSampleIndex
        val numSteps = (remainingSamples / stepSamples).coerceAtLeast(1)

        val timings = LongArray(numSteps) { stepMs }
        val amplitudes = IntArray(numSteps)
        for (i in 0 until numSteps) {
            val start = startSampleIndex + i * stepSamples
            val end = (start + stepSamples).coerceAtMost(envelope.size)
            var peak = 0.0f
            for (s in start until end) {
                val a = envelope[s]
                if (a > peak) peak = a
            }
            // Most LRAs don't actually move below roughly amplitude 15/255 - they just draw
            // current for nothing - so quiet stretches of the stem go genuinely silent instead of
            // buzzing continuously at an inaudible-but-not-zero level.
            val scaled = (peak.coerceIn(0.0f, 1.0f) * 255).toInt()
            amplitudes[i] = if (scaled < 15) 0 else scaled.coerceAtMost(255)
        }

        try {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (e: Exception) {
            // Some OEM vibrator HALs cap waveform length/array size - Stem Pulse just goes quiet
            // live in that case rather than crashing; export bakes the envelope straight into the
            // OGG's haptic channel and never goes through this vibrator API at all, so it's unaffected.
            Log.w("HapticPreviewPlayer", "Continuous envelope vibration failed", e)
        }
    }

    fun triggerPatternHaptic(
        type: com.ringtonehaptics.app.domain.model.HapticPatternType,
        intensity: Float = 0.9f,
        activeBraking: Boolean = true
    ) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255.0f).toInt()
        val scale = intensity.coerceIn(0.1f, 1.0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val composition = VibrationEffect.startComposition()
                when (type) {
                    com.ringtonehaptics.app.domain.model.HapticPatternType.SNAP_CLICK -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.THUMP -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.DOUBLE_TAP -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.85f, 40)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.SWELL -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, scale)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.RUMBLE -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, scale)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.CHIRP -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, scale)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.SNARE_HIT -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * 0.6f, 5)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.TOM_HIT -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * 0.4f, 15)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.BUILDUP -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, scale)
                    }
                    com.ringtonehaptics.app.domain.model.HapticPatternType.DROP -> {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * 0.5f, 10)
                    }
                }
                vibrator.vibrate(composition.compose())
                return
            } catch (e: Exception) {
                // Fall back to waveform
            }
        }

        // Fallback for earlier APIs or unsupported primitives
        val duration = when (type) {
            com.ringtonehaptics.app.domain.model.HapticPatternType.SNAP_CLICK -> if (activeBraking) 12L else 25L
            com.ringtonehaptics.app.domain.model.HapticPatternType.THUMP -> 65L
            com.ringtonehaptics.app.domain.model.HapticPatternType.DOUBLE_TAP -> 90L
            com.ringtonehaptics.app.domain.model.HapticPatternType.SWELL -> 160L
            com.ringtonehaptics.app.domain.model.HapticPatternType.RUMBLE -> 200L
            com.ringtonehaptics.app.domain.model.HapticPatternType.CHIRP -> 110L
            com.ringtonehaptics.app.domain.model.HapticPatternType.SNARE_HIT -> 45L
            com.ringtonehaptics.app.domain.model.HapticPatternType.TOM_HIT -> 75L
            com.ringtonehaptics.app.domain.model.HapticPatternType.BUILDUP -> 500L
            com.ringtonehaptics.app.domain.model.HapticPatternType.DROP -> 180L
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, clampedIntensity))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            // Ignore
        }

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            // Ignore
        }

        vibrator?.cancel()
    }
}
