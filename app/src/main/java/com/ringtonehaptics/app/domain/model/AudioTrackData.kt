package com.ringtonehaptics.app.domain.model

data class AudioTrackData(
    val title: String,
    val artist: String = "Unknown",
    val sampleRate: Int = 48000,
    val durationMs: Long,
    val leftChannel: FloatArray,
    val rightChannel: FloatArray,
    val waveformOverview: FloatArray // Decimated amplitude points for fast overview rendering
) {
    val totalSamples: Int get() = leftChannel.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioTrackData
        return title == other.title &&
                sampleRate == other.sampleRate &&
                durationMs == other.durationMs &&
                leftChannel.contentEquals(other.leftChannel) &&
                rightChannel.contentEquals(other.rightChannel)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs.hashCode()
        return result
    }
}
