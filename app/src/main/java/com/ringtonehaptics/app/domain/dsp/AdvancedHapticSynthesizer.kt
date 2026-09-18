package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.domain.model.HapticPatternType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class AdvancedHapticSynthesizer {

    /**
     * Synthesizes the 3rd Channel PCM buffer from modular HapticClips and continuous envelopes.
     * Incorporates Active Braking (180° anti-phase damping) to eliminate LRA motor overhang.
     */
    fun synthesizeChannel2(
        totalSamples: Int,
        sampleRate: Int,
        clips: List<HapticClip>,
        continuousEnvelope: FloatArray,
        config: FilterConfig,
        // Carrier for the continuous-envelope blend ONLY (step 2 below) - defaults to
        // config.resonantFrequencyHz, i.e. every caller except Stem Pulse gets the exact old
        // behavior unchanged. Stem Pulse passes StemHapticProfile.carrierFreqRatio * resonance so a
        // continuous bass stem drives sub-resonant (rounder/"solid", see StemHapticProfile's doc
        // comment) while drums drive AT resonance for max punch - the discrete clip loop above
        // still uses each clip's own carrierFreqHz and is untouched by this parameter.
        continuousCarrierFreqHz: Float = config.resonantFrequencyHz
    ): FloatArray {
        val hapticPcm = FloatArray(totalSamples)
        if (totalSamples == 0) return hapticPcm

        val linearGain = 10.0f.pow(config.masterGainDb / 20.0f)

        // 1. Synthesize each pattern clip into the PCM buffer
        for (clip in clips) {
            val startSample = ((clip.startMs * sampleRate) / 1000L).toInt()
            val durationSamples = ((clip.durationMs * sampleRate) / 1000L).toInt().coerceAtLeast(8)
            val endSample = min(totalSamples, startSample + durationSamples)
            val clipLen = endSample - startSample
            if (clipLen <= 0 || startSample >= totalSamples) continue

            val fRes = clip.carrierFreqHz.coerceIn(100.0f, 250.0f)
            val wRes = (2.0 * PI * fRes).toFloat()
            val amp = clip.intensity.coerceIn(0.05f, 1.0f) * linearGain

            when (clip.patternType) {
                HapticPatternType.SNAP_CLICK -> {
                    // 1 cycle drive + 0.5 cycle active anti-phase brake!
                    val periodSamples = (sampleRate / fRes).toInt().coerceAtLeast(4)
                    val brakeSamples = (periodSamples * 0.5f).toInt()
                    val totalClickSamples = periodSamples + brakeSamples

                    for (s in 0 until min(clipLen, totalClickSamples)) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break

                        val t = s.toFloat() / sampleRate
                        val sampleVal = if (s < periodSamples) {
                            // Forward drive phase
                            amp * sin(wRes * t)
                        } else {
                            // Active 180-degree inverted braking phase (kills inertia immediately)
                            if (clip.activeBraking) {
                                -amp * 0.90f * sin(wRes * (t - (periodSamples.toFloat() / sampleRate)))
                            } else {
                                0.0f
                            }
                        }
                        // Accumulate and clamp
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.THUMP -> {
                    // Fast attack, tau ~12ms decay, then an active-braking tail - this used to
                    // have NO braking at all (unlike every other pattern type), so even though the
                    // envelope decayed on paper the LRA's own mechanical Q kept it physically
                    // ringing for 20-50ms past that, making kicks feel like a hum instead of a hit.
                    val tau = 0.012f
                    val periodSamples = (sampleRate / fRes).toInt().coerceAtLeast(4)
                    val brakeSamples = (periodSamples * 0.5f).toInt()
                    val bodySamples = (clipLen - brakeSamples).coerceAtLeast(periodSamples)

                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val sampleVal = if (s < bodySamples) {
                            val env = exp(-t / tau)
                            amp * env * sin(wRes * t)
                        } else if (clip.activeBraking) {
                            val tb = (s - bodySamples).toFloat() / sampleRate
                            -amp * 0.6f * exp(-tb / tau) * sin(wRes * (t - bodySamples.toFloat() / sampleRate))
                        } else {
                            0.0f
                        }
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.SNARE_HIT -> {
                    // Two-partial burst (fRes + ~2.2x) for a broader/less pure-tonal feel than
                    // THUMP, with a faster decay + a softer 0.4-cycle brake than SNAP_CLICK -
                    // sits deliberately between "deep kick" and "ultra-crisp click".
                    val tau = 0.015f
                    val periodSamples = (sampleRate / fRes).toInt().coerceAtLeast(4)
                    val brakeSamples = (periodSamples * 0.4f).toInt()
                    val bodySamples = (clipLen - brakeSamples).coerceAtLeast(periodSamples)

                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val sampleVal = if (s < bodySamples) {
                            val env = exp(-t / tau)
                            amp * env * (0.7f * sin(wRes * t) + 0.3f * sin(wRes * 2.2f * t))
                        } else if (clip.activeBraking) {
                            val tb = (s - bodySamples).toFloat() / sampleRate
                            -amp * 0.5f * exp(-tb / tau) * sin(wRes * (t - bodySamples.toFloat() / sampleRate))
                        } else {
                            0.0f
                        }
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.TOM_HIT -> {
                    // Same active-braking drive/brake shape as THUMP but a longer, slower decay
                    // (rounder, more resonant tail) - toms physically ring longer than a kick.
                    val tau = 0.022f
                    val periodSamples = (sampleRate / fRes).toInt().coerceAtLeast(4)
                    val brakeSamples = (periodSamples * 0.5f).toInt()
                    val bodySamples = (clipLen - brakeSamples).coerceAtLeast(periodSamples)

                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val sampleVal = if (s < bodySamples) {
                            val env = exp(-t / tau)
                            amp * env * sin(wRes * t)
                        } else if (clip.activeBraking) {
                            val tb = (s - bodySamples).toFloat() / sampleRate
                            -amp * 0.6f * exp(-tb / tau) * sin(wRes * (t - bodySamples.toFloat() / sampleRate))
                        } else {
                            0.0f
                        }
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.RUMBLE -> {
                    // Continuous carrier (slightly sub-resonance) with 16 Hz flutter modulation
                    val fOff = 140.0f
                    val wOff = (2.0 * PI * fOff).toFloat()
                    val wLfo = (2.0 * PI * 16.0f).toFloat()
                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        // Hann window at edges to prevent click
                        val edgeFade = (0.5f * (1.0f - cos(2.0f * PI.toFloat() * (s.toFloat() / clipLen))))
                        val flutter = 0.6f + 0.4f * sin(wLfo * t)
                        val sampleVal = amp * edgeFade * flutter * sin(wOff * t)
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.SWELL -> {
                    // Quadratic ramp crescendo (t/T)^2 followed by sharp click. When carrierFreqEndHz
                    // is set, the carrier itself glides fRes -> fRes_end across the ramp (phase is
                    // the integral of the linearly-changing frequency, same closed form as CHIRP
                    // below - sampling sin(2*pi*f(t)*t) directly would introduce phase discontinuities)
                    // - an LRA's output amplitude falls off away from its tuned resonance, so ending
                    // the glide AT resonance means the terminal snap lands at peak motor efficiency.
                    val rampDuration = (clipLen * 0.85f).toInt()
                    val fEndSwell = clip.carrierFreqEndHz?.coerceIn(100.0f, 250.0f)
                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val phase = if (fEndSwell != null) {
                            val durSec = rampDuration.toFloat() / sampleRate
                            (2.0 * PI * (fRes * t + 0.5f * ((fEndSwell - fRes) / durSec) * t * t)).toFloat()
                        } else {
                            wRes * t
                        }
                        val sampleVal = if (s < rampDuration) {
                            val progress = s.toFloat() / rampDuration
                            val ramp = progress * progress
                            amp * ramp * sin(phase)
                        } else {
                            // Terminal snap click, at the carrier's arrival frequency if ramping
                            amp * sin(phase)
                        }
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.BUILDUP -> {
                    // Long accelerating-tremolo ramp: amplitude rises steeply (^2.4, steeper than
                    // SWELL's ^2 - this is meant to read as a longer, more deliberate buildup, not
                    // a quick swell), frequency glides toward resonance so the climax lands at peak
                    // motor efficiency, and an accelerating tremolo (2Hz -> 12Hz) gives a felt
                    // "rising tension" texture distinct from a flat ramp. No terminal brake here -
                    // this is designed to segue directly into a DROP clip placed right after it.
                    val fEndBuildup = (clip.carrierFreqEndHz ?: fRes).coerceIn(100.0f, 250.0f)
                    val durSec = clipLen.toFloat() / sampleRate
                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val progress = (t / durSec).coerceIn(0f, 1f)
                        val phase = (2.0 * PI * (fRes * t + 0.5f * ((fEndBuildup - fRes) / durSec) * t * t)).toFloat()
                        val ampEnv = progress.pow(2.4f)
                        val tremoloRate = 2.0f + 10.0f * progress
                        val tremolo = 0.5f + 0.5f * sin((2.0 * PI * tremoloRate * t).toFloat())
                        val sampleVal = amp * ampEnv * tremolo * sin(phase)
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.DROP -> {
                    // The impact/release after a Buildup (or any structurally significant decrescendo
                    // onset) - a double-exponential envelope (sharp initial punch + a rounder
                    // secondary "chest thump", vs THUMP's single-tau decay) with a slight pitch-down
                    // through the tail (mirrors the "bass drop" pitch-bend convention - reinforces a
                    // sense of landing). This is deliberately the loudest single moment Dynamic
                    // Envelope mode produces, so it gets a full-cycle (not half-cycle) brake.
                    val periodSamples = (sampleRate / fRes).toInt().coerceAtLeast(4)
                    val brakeSamples = periodSamples
                    val bodySamples = (clipLen - brakeSamples).coerceAtLeast(periodSamples)
                    val fEndDrop = fRes * 0.85f
                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val sampleVal = if (s < bodySamples) {
                            val bodySec = bodySamples.toFloat() / sampleRate
                            val env = 0.7f * exp(-t / 0.008f) + 0.3f * exp(-t / 0.035f)
                            val phase = (2.0 * PI * (fRes * t + 0.5f * ((fEndDrop - fRes) / bodySec) * t * t)).toFloat()
                            amp * env * sin(phase)
                        } else if (clip.activeBraking) {
                            val tb = (s - bodySamples).toFloat() / sampleRate
                            -amp * 0.95f * exp(-tb / 0.012f) * sin(wRes * (t - bodySamples.toFloat() / sampleRate))
                        } else {
                            0.0f
                        }
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.DOUBLE_TAP -> {
                    // Two 1-cycle pulses separated by 40ms
                    val periodSamples = (sampleRate / fRes).toInt().coerceAtLeast(4)
                    val gapSamples = ((0.040f * sampleRate)).toInt()
                    val secondStart = periodSamples + gapSamples

                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val sampleVal = if (s < periodSamples) {
                            val t = s.toFloat() / sampleRate
                            amp * sin(wRes * t)
                        } else if (s >= secondStart && s < secondStart + periodSamples) {
                            val t = (s - secondStart).toFloat() / sampleRate
                            amp * 0.85f * sin(wRes * t)
                        } else {
                            0.0f
                        }
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }

                HapticPatternType.CHIRP -> {
                    // Linear frequency chirp from 135Hz to 185Hz
                    val fStart = 135.0f
                    val fEnd = 185.0f
                    val durSec = clipLen.toFloat() / sampleRate
                    for (s in 0 until clipLen) {
                        val outIdx = startSample + s
                        if (outIdx >= totalSamples) break
                        val t = s.toFloat() / sampleRate
                        val phase = 2.0 * PI * (fStart * t + 0.5f * ((fEnd - fStart) / durSec) * t * t)
                        val window = (0.5f * (1.0f - cos(2.0f * PI.toFloat() * (s.toFloat() / clipLen))))
                        val sampleVal = (amp * window * sin(phase)).toFloat()
                        hapticPcm[outIdx] = (hapticPcm[outIdx] + sampleVal).coerceIn(-1.0f, 1.0f)
                    }
                }
            }
        }

        // 2. Blend continuous background envelope - this is the PRIMARY haptic layer (matching a
        // reference audio-coupled-haptics track's always-on bass-envelope feel), with the discrete
        // per-instrument clips above layered on top as sharp accents. headroom=0.90 (was 0.40,
        // which made this layer nearly inaudible even at full blend) leaves just enough room below
        // full-scale for accent peaks to still punch through when they land on top of a loud bar.
        val blend = config.blendContinuousEnvelope.coerceIn(0.0f, 1.0f)
        if (blend > 0.05f && continuousEnvelope.isNotEmpty()) {
            val wRes = (2.0 * PI * continuousCarrierFreqHz.coerceIn(100.0f, 250.0f)).toFloat()
            val headroom = 0.90f
            for (i in 0 until min(totalSamples, continuousEnvelope.size)) {
                val t = i.toFloat() / sampleRate
                val contSample = continuousEnvelope[i] * sin(wRes * t) * linearGain * blend * headroom
                hapticPcm[i] = (hapticPcm[i] + contSample).coerceIn(-1.0f, 1.0f)
            }
        }

        return hapticPcm
    }
}
