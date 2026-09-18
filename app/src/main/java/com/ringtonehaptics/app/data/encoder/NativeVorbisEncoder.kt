package com.ringtonehaptics.app.data.encoder

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeVorbisEncoder {

    private const val TAG = "NativeVorbisEncoder"

    init {
        try {
            System.loadLibrary("ringtonehaptics_native")
            Log.i(TAG, "Successfully loaded native library ringtonehaptics_native")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library ringtonehaptics_native", e)
        }
    }

    private external fun nativeEncode3ChannelOgg(
        leftBuffer: ByteBuffer,
        rightBuffer: ByteBuffer,
        hapticBuffer: ByteBuffer,
        numSamples: Int,
        sampleRate: Int,
        quality: Float,
        outputPath: String,
        title: String?,
        artist: String?
    ): Int

    /**
     * Encodes 3 channels (Left, Right, Haptic) into an OGG Vorbis file tagged with ANDROID_HAPTIC=1.
     * Uses Direct ByteBuffers for zero-copy memory access into C++.
     */
    fun encode3ChannelOgg(
        leftPcm: FloatArray,
        rightPcm: FloatArray,
        hapticPcm: FloatArray,
        sampleRate: Int,
        outputPath: String,
        title: String = "Pixel Ringtone",
        artist: String = "RingtoneHaptics",
        quality: Float = 0.5f // VBR quality 0.0 to 1.0 (0.5 is ~160kbps)
    ): Result<Unit> {
        val numSamples = leftPcm.size
        if (numSamples == 0 || rightPcm.size != numSamples || hapticPcm.size != numSamples) {
            return Result.failure(IllegalArgumentException("Channel lengths do not match or are empty"))
        }

        return try {
            val byteCount = numSamples * 4 // 4 bytes per float

            val leftBuf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            leftBuf.asFloatBuffer().put(leftPcm)

            val rightBuf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            rightBuf.asFloatBuffer().put(rightPcm)

            val hapticBuf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            hapticBuf.asFloatBuffer().put(hapticPcm)

            val ret = nativeEncode3ChannelOgg(
                leftBuffer = leftBuf,
                rightBuffer = rightBuf,
                hapticBuffer = hapticBuf,
                numSamples = numSamples,
                sampleRate = sampleRate,
                quality = quality,
                outputPath = outputPath,
                title = title,
                artist = artist
            )

            if (ret == 0) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Native Vorbis encoding failed with code $ret"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OGG encoding", e)
            Result.failure(e)
        }
    }
}
