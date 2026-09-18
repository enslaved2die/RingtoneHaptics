package com.ringtonehaptics.app.domain.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "StemSeparator"

data class StemSeparationResult(
    val drums: FloatArray,
    val bass: FloatArray,
    val other: FloatArray,
    val vocals: FloatArray
)

const val MODEL_SAMPLE_RATE = 44100
private const val SEGMENT_SAMPLES = 343980 // 7.8s at 44.1kHz - fixed shape baked into the htdemucs ONNX export
private const val NUM_CHANNELS = 2

/**
 * Chunked htdemucs ONNX inference (CPU-only - see implementation_plan.md Phase 2: no reliable
 * third-party NPU path exists on Tensor hardware today). Fixed 7.8s input window, quarter-length
 * overlap with a linear crossfade, weighted overlap-add reconstruction - mirrors the reference
 * infer.py script published alongside the model (huggingface.co/StemSplitio/htdemucs-onnx)
 * exactly, since getting the windowing wrong produces audible seams every ~5.8s (the stride).
 *
 * Output stems are downmixed to mono to match the rest of this app's (mono) DSP pipeline
 * (BiquadFilter/EnvelopeFollower/HpssProcessor all operate on a single FloatArray channel).
 */
class StemSeparator {

    fun separate(
        modelPath: String,
        leftChannel: FloatArray,
        rightChannel: FloatArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {}
    ): StemSeparationResult {
        require(leftChannel.size == rightChannel.size) { "L/R channel length mismatch" }

        val left = if (sampleRate != MODEL_SAMPLE_RATE) Resampler.resample(leftChannel, sampleRate, MODEL_SAMPLE_RATE) else leftChannel
        val right = if (sampleRate != MODEL_SAMPLE_RATE) Resampler.resample(rightChannel, sampleRate, MODEL_SAMPLE_RATE) else rightChannel
        val total = min(left.size, right.size)
        if (total == 0) return StemSeparationResult(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))

        val overlap = SEGMENT_SAMPLES / 4
        val stride = SEGMENT_SAMPLES - overlap
        val numChunks = ((total + stride - 1) / stride).coerceAtLeast(1)
        val window = makeWindow(SEGMENT_SAMPLES, overlap)

        // index 0=drums, 1=bass, 2=other, 3=vocals (fixed order baked into the model export)
        val out = Array(4) { FloatArray(total) }
        val weight = FloatArray(total)

        Log.i(TAG, "separate(): total=${total} samples (${total / MODEL_SAMPLE_RATE}s), numChunks=$numChunks, modelPath=$modelPath")
        val overallStart = System.currentTimeMillis()

        val env = OrtEnvironment.getEnvironment()
        val loadStart = System.currentTimeMillis()
        env.createSession(modelPath).use { session ->
            Log.i(TAG, "ONNX session loaded in ${System.currentTimeMillis() - loadStart}ms")
            val inputName = session.inputNames.iterator().next()
            val chunkBuffer = FloatArray(NUM_CHANNELS * SEGMENT_SAMPLES)

            for (i in 0 until numChunks) {
                val chunkStart = System.currentTimeMillis()
                val start = i * stride
                val end = min(start + SEGMENT_SAMPLES, total)
                val chunkLen = end - start

                java.util.Arrays.fill(chunkBuffer, 0.0f)
                for (s in 0 until chunkLen) {
                    chunkBuffer[s] = left[start + s]                    // channel 0
                    chunkBuffer[SEGMENT_SAMPLES + s] = right[start + s] // channel 1
                }

                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(chunkBuffer),
                    longArrayOf(1, NUM_CHANNELS.toLong(), SEGMENT_SAMPLES.toLong())
                ).use { inputTensor ->
                    session.run(mapOf(inputName to inputTensor)).use { result ->
                        val stemsTensor = result.get(0) as OnnxTensor
                        val flat = stemsTensor.floatBuffer // flat (4, 2, SEGMENT_SAMPLES)

                        for (stemIdx in 0 until 4) {
                            val base = stemIdx * NUM_CHANNELS * SEGMENT_SAMPLES
                            val dst = out[stemIdx]
                            for (s in 0 until chunkLen) {
                                val l = flat.get(base + s)
                                val r = flat.get(base + SEGMENT_SAMPLES + s)
                                dst[start + s] += ((l + r) * 0.5f) * window[s]
                            }
                        }
                        for (s in 0 until chunkLen) weight[start + s] += window[s]
                    }
                }

                onProgress((i + 1).toFloat() / numChunks)
                Log.i(TAG, "chunk ${i + 1}/$numChunks done in ${System.currentTimeMillis() - chunkStart}ms")
            }
        }

        for (idx in 0 until total) {
            val w = weight[idx].coerceAtLeast(1e-8f)
            for (stemIdx in 0 until 4) out[stemIdx][idx] /= w
        }

        val totalMs = System.currentTimeMillis() - overallStart
        val names = listOf("drums", "bass", "other", "vocals")
        for (i in 0 until 4) {
            var sumSq = 0.0
            for (v in out[i]) sumSq += v.toDouble() * v
            val rms = sqrt(sumSq / out[i].size.coerceAtLeast(1))
            Log.i(TAG, "stem ${names[i]} RMS=$rms")
        }
        Log.i(TAG, "separate() total time ${totalMs}ms for ${total / MODEL_SAMPLE_RATE}s of audio (RTF=${totalMs / 1000.0 / (total.toDouble() / MODEL_SAMPLE_RATE)})")

        return StemSeparationResult(drums = out[0], bass = out[1], other = out[2], vocals = out[3])
    }

    private fun makeWindow(n: Int, overlap: Int): FloatArray {
        val w = FloatArray(n) { 1.0f }
        for (i in 0 until overlap) {
            val fade = i.toFloat() / (overlap - 1).coerceAtLeast(1)
            w[i] = fade
            w[n - 1 - i] = fade
        }
        return w
    }
}
