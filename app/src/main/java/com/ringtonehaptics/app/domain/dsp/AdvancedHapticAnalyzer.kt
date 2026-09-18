package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.AlgorithmPreset
import com.ringtonehaptics.app.domain.model.BeatGridInfo
import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.FilterType
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.domain.model.HapticGenerationMode
import com.ringtonehaptics.app.domain.model.HapticInstrument
import com.ringtonehaptics.app.domain.model.HapticPatternType
import com.ringtonehaptics.app.domain.model.boostedForEnvelopeMode
import com.ringtonehaptics.app.domain.model.usesEnvelopeLayers
import com.ringtonehaptics.app.domain.model.usesLeadCadence
import com.ringtonehaptics.app.domain.model.usesPercussion
import com.ringtonehaptics.app.domain.ml.DrumClass
import com.ringtonehaptics.app.domain.ml.DrumOnset
import com.ringtonehaptics.app.domain.ml.MODEL_SAMPLE_RATE
import com.ringtonehaptics.app.domain.ml.StemSeparationResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * [transcribeDrums] is a function rather than a concrete DrumTranscriber so this class stays
 * plain-JVM testable - DrumTranscriber needs a real Android Context (asset access, ONNX Runtime's
 * native libs), neither available under a pure JUnit run.
 */
class AdvancedHapticAnalyzer(
    private val transcribeDrums: (pcm: FloatArray, sampleRate: Int) -> Map<DrumClass, List<DrumOnset>>
) {

    /**
     * "Haptic Instruments" pipeline. By default separates the mix into harmonic/percussive stems
     * via lightweight on-device HPSS (see implementation_plan.md, Phase 1). When [mlStems] is
     * supplied (Phase 2 - real 4-stem ML separation, drums/bass/other/vocals), those stems drive
     * the per-instrument generators instead, for materially cleaner separation at a much higher
     * one-time processing cost.
     */
    fun generateInstrumentClips(
        pcmSamples: FloatArray,
        sampleRate: Int,
        durationMs: Long,
        beatGrid: BeatGridInfo,
        config: FilterConfig,
        mlStems: StemSeparationResult? = null,
        mode: HapticGenerationMode = HapticGenerationMode.AUTOMATIC
    ): List<HapticClip> {
        if (pcmSamples.isEmpty() || durationMs <= 0) return emptyList()
        // Manual mode generates nothing automatically - the user places every clip themselves.
        if (mode == HapticGenerationMode.MANUAL) return emptyList()

        val clips = mutableListOf<HapticClip>()
        // Automatic keeps its tuned defaults byte-for-byte; only modes that lean on the envelope
        // as the whole point (not one blended layer among several) get pushed harder.
        val envelopeConfig = if (mode.usesEnvelopeLayers() && mode != HapticGenerationMode.AUTOMATIC) {
            config.boostedForEnvelopeMode()
        } else config

        if (mlStems != null) {
            // mlStems is always produced at StemSeparator.MODEL_SAMPLE_RATE (44100), regardless of
            // this track's own native sampleRate - passing the track's rate here would silently
            // misalign every timestamp derived from it (drift growing across the track) for any
            // source file that wasn't already 44.1kHz.
            if (mode.usesPercussion()) {
                clips += generatePercussionInstrument(pcmSamples, sampleRate, mlStems.drums, MODEL_SAMPLE_RATE, config)
            }
            if (mode.usesEnvelopeLayers()) {
                clips += generateGrooveBassInstrument(mlStems.bass, sampleRate, durationMs, beatGrid, envelopeConfig)
            }
            if (mode.usesLeadCadence()) {
                val leadSignal = FloatArray(min(mlStems.vocals.size, mlStems.other.size)) { mlStems.vocals[it] + mlStems.other[it] }
                clips += generateLeadCadenceInstrument(leadSignal, sampleRate, config)
            }
        } else {
            val hpss = HpssProcessor().separate(pcmSamples, sampleRate)
            if (mode.usesPercussion()) {
                clips += generatePercussionInstrument(pcmSamples, sampleRate, hpss.percussive, sampleRate, config)
            }
            if (mode.usesEnvelopeLayers()) {
                clips += generateGrooveBassInstrument(hpss.harmonic, sampleRate, durationMs, beatGrid, envelopeConfig)
            }
            if (mode.usesLeadCadence()) {
                clips += generateLeadCadenceInstrument(hpss.harmonic, sampleRate, config)
            }
        }

        // Fades are a whole-mix loudness phenomenon regardless of separation mode
        if (mode.usesEnvelopeLayers()) {
            clips += generateDynamicEnvelopeInstrument(pcmSamples, sampleRate, envelopeConfig)
        }

        return clips.sortedBy { it.startMs }
    }

    /**
     * Drum classification via a real pretrained model (ADTOF Frame_RNN - see DrumTranscriber) run
     * directly on the full mix, since drum transcription from polyphonic music is exactly what
     * that model is trained for; no source separation needed for this step. Replaces the old
     * band-energy/centroid heuristic, which - verified against a real drum-heavy track - collapsed
     * almost everything into SNARE and detected KICK essentially never (1 hit in 148 on a 16s
     * drum-heavy song).
     *
     * [percussivePcm]/[percussiveSampleRate] (the HPSS percussive component, or the ML drums stem)
     * are used only to refine each ADTOF onset to the true sample-accurate transient peak - ADTOF's
     * own frame resolution is 10ms, plenty for classification but coarser than a haptic pulse
     * should be for timing.
     *
     * Never snaps to the beat grid, in ANY mode - Beatmatching mode briefly did (an opt-in
     * quantize-to-grid path), but per explicit user correction that was wrong: beat snapping is
     * only ever a MANUAL editing aid (the Snap to Grid toggle used when dragging/stamping a clip
     * by hand), never something auto-generation should do silently. "Beatmatching" mode's actual
     * distinction from Automatic is which layers it includes (percussion only, no envelope/lead),
     * not forced grid alignment.
     */
    private fun generatePercussionInstrument(
        pcmSamples: FloatArray,
        sampleRate: Int,
        percussivePcm: FloatArray,
        percussiveSampleRate: Int,
        config: FilterConfig
    ): List<HapticClip> {
        val onsetsByClass = transcribeDrums(pcmSamples, sampleRate)
        val clips = mutableListOf<HapticClip>()

        for ((drumClass, onsets) in onsetsByClass) {
            for (onset in onsets) {
                // Always the precise, sample-refined onset time.
                val startMs = refineOnsetTiming(onset.timestampMs, percussivePcm, percussiveSampleRate)

                val type = when (drumClass) {
                    DrumClass.KICK -> HapticPatternType.THUMP
                    DrumClass.SNARE -> HapticPatternType.SNARE_HIT
                    DrumClass.HIHAT -> HapticPatternType.SNAP_CLICK
                    DrumClass.TOM -> HapticPatternType.TOM_HIT
                    DrumClass.CYMBAL -> HapticPatternType.SWELL
                }
                // Kick hits strong, snare medium, toms rounded-strong, hihat spiky-but-light, cymbal
                // sweeping-but-moderate - per user feedback that the drum elements should feel
                // distinctly different, not just "loud vs quiet".
                val intensity = when (drumClass) {
                    DrumClass.KICK -> onset.intensity.coerceIn(0.75f, 1.0f)
                    DrumClass.SNARE -> (onset.intensity * 0.8f).coerceIn(0.45f, 0.85f)
                    DrumClass.HIHAT -> (onset.intensity * 0.7f).coerceIn(0.35f, 0.70f)
                    DrumClass.TOM -> (onset.intensity * 0.85f).coerceIn(0.55f, 0.90f)
                    DrumClass.CYMBAL -> (onset.intensity * 0.75f).coerceIn(0.50f, 0.85f)
                }
                val durationMs = when (drumClass) {
                    DrumClass.HIHAT -> 10
                    DrumClass.CYMBAL -> 200
                    else -> type.defaultDurationMs
                }

                val instrument = when (drumClass) {
                    DrumClass.KICK -> HapticInstrument.KICK
                    DrumClass.SNARE -> HapticInstrument.SNARE
                    DrumClass.HIHAT -> HapticInstrument.HIHAT
                    DrumClass.TOM -> HapticInstrument.TOM
                    DrumClass.CYMBAL -> HapticInstrument.CYMBAL
                }

                clips.add(
                    HapticClip(
                        patternType = type,
                        startMs = startMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        activeBraking = true,
                        carrierFreqHz = config.resonantFrequencyHz,
                        isAutoGenerated = true,
                        instrument = instrument
                    )
                )
            }
        }

        return clips
    }

    /** Searches a small window around a 10ms-resolution ADTOF onset for the true sample-accurate peak. */
    private fun refineOnsetTiming(coarseMs: Long, percussivePcm: FloatArray, percussiveSampleRate: Int): Long {
        if (percussivePcm.isEmpty()) return coarseMs
        val centerSample = ((coarseMs * percussiveSampleRate) / 1000L).toInt().coerceIn(0, percussivePcm.size - 1)
        val searchRadius = (percussiveSampleRate * 0.015).toInt() // +-15ms, a bit wider than ADTOF's 10ms frame step
        val lo = (centerSample - searchRadius).coerceAtLeast(0)
        val hi = (centerSample + searchRadius).coerceAtMost(percussivePcm.size - 1)
        var peakSample = centerSample
        var peakAbs = abs(percussivePcm[centerSample])
        for (s in lo..hi) {
            val a = abs(percussivePcm[s])
            if (a > peakAbs) {
                peakAbs = a
                peakSample = s
            }
        }
        return (peakSample.toLong() * 1000L) / percussiveSampleRate
    }

    private fun generateGrooveBassInstrument(
        harmonicPcm: FloatArray,
        sampleRate: Int,
        durationMs: Long,
        beatGrid: BeatGridInfo,
        config: FilterConfig
    ): List<HapticClip> {
        val subBassFilter = BiquadFilter().apply {
            configure(FilterType.LOWPASS, cutoffHz = 100.0f, sampleRate = sampleRate)
        }
        val subBassPcm = subBassFilter.processBuffer(harmonicPcm)
        val subBassEnv = EnvelopeFollower().analyze(subBassPcm, sampleRate, config.copy(cutoffFrequencyHz = 100f)).envelope

        val clips = mutableListOf<HapticClip>()
        val stepMs = 250L
        var currentMs = 0L

        while (currentMs < durationMs) {
            val sampleIdx = ((currentMs * sampleRate) / 1000L).toInt().coerceIn(0, subBassEnv.size - 1)
            val level = subBassEnv[sampleIdx]

            // Was a hardcoded 0.35f - now the shared sensitivity setting, so a quiet intro (low
            // relative to the track's global peak, which EnvelopeFollower normalizes against)
            // can still produce clips instead of silently generating nothing.
            if (level > config.sensitivityThreshold) {
                val isDownbeat = beatGrid.downbeatTimestamps.any { abs(it - currentMs) < 80L }
                clips.add(
                    HapticClip(
                        patternType = HapticPatternType.RUMBLE,
                        startMs = currentMs,
                        durationMs = if (isDownbeat) 260 else 220,
                        intensity = (level * 0.95f).coerceIn(0.4f, 1.0f),
                        activeBraking = false,
                        carrierFreqHz = 140.0f,
                        isAutoGenerated = true,
                        instrument = HapticInstrument.GROOVE_BASS
                    )
                )
                currentMs += 240L
            } else {
                currentMs += stepMs
            }
        }

        return clips
    }

    private fun generateLeadCadenceInstrument(
        harmonicPcm: FloatArray,
        sampleRate: Int,
        config: FilterConfig
    ): List<HapticClip> {
        val punchFilter = BiquadFilter().apply {
            configure(FilterType.BANDPASS, cutoffHz = 220.0f, sampleRate = sampleRate, q = 1.0f)
        }
        val punchPcm = punchFilter.processBuffer(harmonicPcm)
        val punchNodes = EnvelopeFollower().analyze(punchPcm, sampleRate, config.copy(cutoffFrequencyHz = 220f)).detectedNodes

        val clips = mutableListOf<HapticClip>()
        var lastTime = -180L
        for (node in punchNodes) {
            if (node.timestampMs - lastTime < 180L) continue
            clips.add(
                HapticClip(
                    patternType = HapticPatternType.CHIRP,
                    startMs = node.timestampMs,
                    durationMs = HapticPatternType.CHIRP.defaultDurationMs,
                    intensity = node.intensity.coerceIn(0.5f, 0.95f),
                    activeBraking = true,
                    carrierFreqHz = config.resonantFrequencyHz,
                    isAutoGenerated = true,
                    instrument = HapticInstrument.LEAD_CADENCE
                )
            )
            lastTime = node.timestampMs
        }

        return clips
    }

    private fun generateDynamicEnvelopeInstrument(
        harmonicPcm: FloatArray,
        sampleRate: Int,
        config: FilterConfig
    ): List<HapticClip> {
        val fadeSpans = FadeEnvelopeDetector().detect(harmonicPcm, sampleRate)
        val clips = mutableListOf<HapticClip>()
        // Above this raw span length, a Buildup->Drop pair reads better than one SWELL - a long
        // steady quadratic ramp starts to feel static, where an accelerating tremolo + a real
        // impact at the end gives a long structural crescendo (verse->chorus, cold open, etc.) an
        // actual climax. Below it, SWELL (now with an optional frequency glide toward resonance -
        // the same "arrive at peak motor efficiency right at the snap" idea as Buildup/Drop, just
        // without the tremolo/long-form treatment) stays the better fit for a shorter, punchier rise.
        val buildupThresholdMs = 1200L

        for (span in fadeSpans) {
            val rawDuration = span.endMs - span.startMs
            if (span.isCrescendo && rawDuration >= buildupThresholdMs) {
                // Snap the climax to the real local loudness peak near the span's end, rather than
                // trusting FadeEnvelopeDetector's raw slope-threshold endpoint - a crescendo's
                // detected "stops rising" point and the track's actual loudest instant nearby aren't
                // always the same sample, and landing Drop's impact off the true peak is exactly the
                // kind of mistiming this app's whole percussion pipeline was fixed for earlier.
                val peakMs = snapToLocalPeak(harmonicPcm, sampleRate, span.endMs, searchWindowMs = 300L)
                val buildupDuration = (peakMs - span.startMs).toInt().coerceIn(400, 4000)
                val intensity = (abs(span.deltaDb) / 24.0f).coerceIn(0.5f, 1.0f)
                clips.add(
                    HapticClip(
                        patternType = HapticPatternType.BUILDUP,
                        startMs = span.startMs,
                        durationMs = buildupDuration,
                        intensity = intensity,
                        activeBraking = false,
                        carrierFreqHz = (config.resonantFrequencyHz * 0.75f).coerceIn(100f, 250f),
                        carrierFreqEndHz = config.resonantFrequencyHz.coerceIn(100f, 250f),
                        isAutoGenerated = true,
                        instrument = HapticInstrument.DYNAMIC_ENVELOPE
                    )
                )
                clips.add(
                    HapticClip(
                        patternType = HapticPatternType.DROP,
                        startMs = peakMs,
                        durationMs = HapticPatternType.DROP.defaultDurationMs,
                        intensity = intensity.coerceAtLeast(0.85f),
                        activeBraking = true,
                        carrierFreqHz = config.resonantFrequencyHz.coerceIn(100f, 250f),
                        isAutoGenerated = true,
                        instrument = HapticInstrument.DYNAMIC_ENVELOPE
                    )
                )
            } else {
                val duration = rawDuration.toInt().coerceIn(200, 3000)
                val intensity = (abs(span.deltaDb) / 24.0f).coerceIn(0.5f, 1.0f)
                clips.add(
                    HapticClip(
                        patternType = if (span.isCrescendo) HapticPatternType.SWELL else HapticPatternType.RUMBLE,
                        startMs = span.startMs,
                        durationMs = duration,
                        intensity = intensity,
                        activeBraking = span.isCrescendo,
                        carrierFreqHz = (if (span.isCrescendo) config.resonantFrequencyHz * 0.85f else config.resonantFrequencyHz).coerceIn(100f, 250f),
                        carrierFreqEndHz = if (span.isCrescendo) config.resonantFrequencyHz.coerceIn(100f, 250f) else null,
                        isAutoGenerated = true,
                        instrument = HapticInstrument.DYNAMIC_ENVELOPE
                    )
                )
            }
        }

        return clips
    }

    /** Searches forward/backward from [coarseMs] for the true local RMS-energy peak, using a
     * short sliding window - a crescendo's real climax and FadeEnvelopeDetector's slope-threshold
     * endpoint aren't always the same instant. */
    private fun snapToLocalPeak(pcm: FloatArray, sampleRate: Int, coarseMs: Long, searchWindowMs: Long): Long {
        if (pcm.isEmpty()) return coarseMs
        val rmsWindowSamples = (sampleRate * 0.030).toInt().coerceAtLeast(1)
        val centerSample = ((coarseMs * sampleRate) / 1000L).toInt().coerceIn(0, pcm.size - 1)
        val searchRadius = ((searchWindowMs * sampleRate) / 1000L).toInt()
        val lo = (centerSample - searchRadius).coerceAtLeast(0)
        val hi = (centerSample + searchRadius).coerceAtMost(pcm.size - 1)

        var bestSample = centerSample
        var bestRms = -1f
        var s = lo
        while (s <= hi) {
            val end = (s + rmsWindowSamples).coerceAtMost(pcm.size)
            var sumSq = 0f
            for (i in s until end) sumSq += pcm[i] * pcm[i]
            val rms = sumSq / (end - s).coerceAtLeast(1)
            if (rms > bestRms) {
                bestRms = rms
                bestSample = s
            }
            s += rmsWindowSamples / 2
        }
        return (bestSample.toLong() * 1000L) / sampleRate
    }

    fun generateClipsForPreset(
        pcmSamples: FloatArray,
        sampleRate: Int,
        durationMs: Long,
        preset: AlgorithmPreset,
        beatGrid: BeatGridInfo,
        config: FilterConfig,
        mode: HapticGenerationMode = HapticGenerationMode.AUTOMATIC
    ): List<HapticClip> {
        if (pcmSamples.isEmpty() || durationMs <= 0) return emptyList()
        return generateInstrumentClips(pcmSamples, sampleRate, durationMs, beatGrid, config, mode = mode)
    }
}
