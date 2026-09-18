package com.ringtonehaptics.app.domain.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log
import com.ringtonehaptics.app.domain.dsp.FftUtil
import kotlinx.serialization.Serializable
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private const val TAG = "DrumTranscriber"

/** The 5 drum classes the ADTOF Frame_RNN model was trained on, in the model's own output order.
 * @Serializable so Stem Pulse's drum-class multi-select (EditorUiState.selectedDrumClasses) can be
 * persisted verbatim in the saved project JSON, same as every other bit of editor state. */
@Serializable
enum class DrumClass { KICK, SNARE, TOM, HIHAT, CYMBAL }

data class DrumOnset(val timestampMs: Long, val drumClass: DrumClass, val intensity: Float)

// Fixed by the ONNX model + filterbank asset - these are baked into training, not tunable.
private const val ADTOF_SAMPLE_RATE = 44100
private const val N_FFT = 2048
private const val HOP_LENGTH = 441 // round(44100 / 100 fps)
private const val N_MEL_BINS = 84
private const val FPS = 100

// Per-class thresholds from the original ADTOF Frame_RNN paper, in DrumClass enum order
// (kick, snare, tom, hihat, cymbal) - matches LABELS_5 = [35, 38, 47, 42, 49] / FRAME_RNN_THRESHOLDS.
private val CLASS_THRESHOLDS = floatArrayOf(0.22f, 0.24f, 0.32f, 0.22f, 0.30f)

/**
 * Real drum transcription via a small (1.8MB) pretrained CRNN (ADTOF Frame_RNN, ONNX-exported from
 * https://github.com/xavriley/ADTOF-pytorch - CC BY-NC-SA 4.0, downloaded on first use by
 * [DrumModelStore] rather than bundled, see README). Runs directly on the full mixed track - no source separation needed,
 * since drum transcription from polyphonic music is exactly what this model is trained for -
 * replacing the old band-energy/centroid heuristic (which classified almost everything as SNARE
 * on real dense drum content, see the Spyro investigation) with a model actually trained on
 * labeled real-world drum hits.
 *
 * The log-frequency filterbank (84 mel-like bins from a 2048-point STFT) is precomputed in Python
 * to match madmom's LogarithmicFilterbank exactly (see .model-cache tooling) and bundled as a flat
 * float32 asset - reimplementing that bin-mapping/triangular-filter construction in Kotlin from
 * scratch would be a much easier place to introduce a subtle mismatch than a straight STFT +
 * matrix-multiply against a known-correct precomputed matrix.
 */
class DrumTranscriber(private val context: Context) {

    private val filterbank: Array<FloatArray> by lazy { loadFilterbank() }

    fun transcribe(pcmSamples: FloatArray, sampleRate: Int): Map<DrumClass, List<DrumOnset>> {
        if (pcmSamples.isEmpty()) return emptyMap()

        val resampled = if (sampleRate != ADTOF_SAMPLE_RATE) {
            Resampler.resample(pcmSamples, sampleRate, ADTOF_SAMPLE_RATE)
        } else pcmSamples

        val features = computeLogFilterbankFrames(resampled) // [numFrames][84]
        if (features.isEmpty()) return emptyMap()

        val activations = runInference(features) // [numFrames][5], sigmoid outputs

        val result = mutableMapOf<DrumClass, List<DrumOnset>>()
        for (drumClass in DrumClass.entries) {
            val classIdx = drumClass.ordinal
            val activation = FloatArray(activations.size) { activations[it][classIdx] }
            result[drumClass] = pickPeaks(activation, CLASS_THRESHOLDS[classIdx]).map { (frame, value) ->
                DrumOnset(
                    timestampMs = (frame * 1000L) / FPS,
                    drumClass = drumClass,
                    intensity = value.coerceIn(0f, 1f)
                )
            }
        }
        Log.i(TAG, "transcribe(): ${features.size} frames -> " +
            DrumClass.entries.joinToString { "${it.name}=${result[it]?.size ?: 0}" })
        return result
    }

    // --- Feature extraction: STFT (n_fft=2048, hop=441, Hann, centered/zero-padded) -> log filterbank ---
    private fun computeLogFilterbankFrames(pcm: FloatArray): Array<FloatArray> {
        val pad = N_FFT / 2
        val padded = FloatArray(pcm.size + 2 * pad)
        System.arraycopy(pcm, 0, padded, pad, pcm.size)

        val numFrames = 1 + pcm.size / HOP_LENGTH
        if (numFrames <= 0) return emptyArray()

        val window = hannWindow(N_FFT)
        val numFftBins = N_FFT / 2 // matches audio_processing.py: magnitude[:n_fft//2], excludes Nyquist
        val real = FloatArray(N_FFT)
        val imag = FloatArray(N_FFT)
        val magnitude = FloatArray(numFftBins)

        return Array(numFrames) { f ->
            val base = f * HOP_LENGTH
            for (i in 0 until N_FFT) {
                val srcIdx = base + i
                real[i] = (if (srcIdx < padded.size) padded[srcIdx] else 0f) * window[i]
                imag[i] = 0f
            }
            FftUtil.transform(real, imag, inverse = false)
            for (b in 0 until numFftBins) {
                magnitude[b] = kotlin.math.sqrt(real[b] * real[b] + imag[b] * imag[b])
            }
            // filtered[m] = sum_b filterbank[m][b] * magnitude[b], then log10(1+x)
            FloatArray(N_MEL_BINS) { m ->
                val row = filterbank[m]
                var sum = 0f
                for (b in 0 until numFftBins) sum += row[b] * magnitude[b]
                log10(1.0f + sum)
            }
        }
    }

    private fun hannWindow(size: Int): FloatArray {
        val w = FloatArray(size)
        for (i in 0 until size) {
            w[i] = (0.5f * (1.0f - cos(2.0 * Math.PI * i / (size - 1)))).toFloat()
        }
        return w
    }

    private fun loadFilterbank(): Array<FloatArray> {
        val bytes = context.assets.open("adtof_filterbank_84x1024.bin").use { it.readBytes() }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val numFftBins = N_FFT / 2
        require(buffer.remaining() == N_MEL_BINS * numFftBins) {
            "adtof_filterbank_84x1024.bin size mismatch: expected ${N_MEL_BINS * numFftBins} floats, got ${buffer.remaining()}"
        }
        return Array(N_MEL_BINS) { m ->
            FloatArray(numFftBins).also { buffer.get(it) }
        }
    }

    // --- ONNX inference: [1, time, 84, 1] -> [1, time, 5] sigmoid activations ---
    private fun runInference(features: Array<FloatArray>): Array<FloatArray> {
        val modelBytes = DrumModelStore(context).modelFile.readBytes()
        val env = OrtEnvironment.getEnvironment()
        val numFrames = features.size

        val flat = FloatArray(numFrames * N_MEL_BINS)
        for (f in 0 until numFrames) System.arraycopy(features[f], 0, flat, f * N_MEL_BINS, N_MEL_BINS)

        env.createSession(modelBytes).use { session ->
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(flat),
                longArrayOf(1, numFrames.toLong(), N_MEL_BINS.toLong(), 1)
            ).use { inputTensor ->
                val inputName = session.inputNames.iterator().next()
                session.run(mapOf(inputName to inputTensor)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val buf = out.floatBuffer
                    return Array(numFrames) { f ->
                        FloatArray(5) { c -> buf.get(f * 5 + c) }
                    }
                }
            }
        }
    }

    // --- Peak picking: mirrors NotePeakPickingProcessor (mean-subtract, local-maxima, combine-nearby) ---
    private fun pickPeaks(activation: FloatArray, threshold: Float): List<Pair<Int, Float>> {
        val n = activation.size
        if (n == 0) return emptyList()

        val preAvgFrames = Math.round(0.1 * FPS).toInt() // 10 frames
        val postAvgFrames = Math.round(0.01 * FPS).toInt() // 1 frame
        val proc = FloatArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - preAvgFrames)
            val hi = min(n - 1, i + postAvgFrames)
            var sum = 0f
            for (j in lo..hi) sum += activation[j]
            val avg = sum / (hi - lo + 1)
            proc[i] = max(0f, activation[i] - avg)
        }

        val preMaxFrames = Math.round(0.02 * FPS).toInt() // 2 frames
        val postMaxFrames = Math.round(0.01 * FPS).toInt() // 1 frame
        val isPeak = BooleanArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - preMaxFrames)
            val hi = min(n - 1, i + postMaxFrames)
            var windowMax = proc[lo]
            for (j in (lo + 1)..hi) if (proc[j] > windowMax) windowMax = proc[j]
            isPeak[i] = proc[i] >= windowMax && proc[i] >= threshold
        }

        val peakIndices = (0 until n).filter { isPeak[it] }
        if (peakIndices.isEmpty()) return emptyList()

        val combineFrames = max(1, Math.round(0.02 * FPS).toInt()) // 2 frames
        val kept = mutableListOf<Int>()
        var group = mutableListOf(peakIndices[0])
        for (idx in peakIndices.drop(1)) {
            if (idx - group.last() <= combineFrames) {
                group.add(idx)
            } else {
                kept.add(group.maxBy { proc[it] })
                group = mutableListOf(idx)
            }
        }
        kept.add(group.maxBy { proc[it] })

        return kept.map { it to activation[it] }
    }
}
