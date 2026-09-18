package com.ringtonehaptics.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class HapticInstrument(
    val displayName: String,
    val iconName: String
) {
    // Split from a single "Percussion" bucket so each drum can be muted/soloed independently
    // instead of only as a whole kit - see DrumTranscriber.DrumClass.
    KICK(
        displayName = "Kick",
        iconName = "Bolt"
    ),
    SNARE(
        displayName = "Snare",
        iconName = "Bolt"
    ),
    HIHAT(
        displayName = "Hihat",
        iconName = "Bolt"
    ),
    TOM(
        displayName = "Tom",
        iconName = "Bolt"
    ),
    CYMBAL(
        displayName = "Cymbal",
        iconName = "Bolt"
    ),
    GROOVE_BASS(
        displayName = "Groove / Bass",
        iconName = "Waves"
    ),
    LEAD_CADENCE(
        displayName = "Lead Cadence",
        iconName = "MusicNote"
    ),
    DYNAMIC_ENVELOPE(
        displayName = "Dynamic Envelope",
        iconName = "Whatshot"
    )
}
