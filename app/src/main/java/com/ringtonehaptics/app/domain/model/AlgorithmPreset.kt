package com.ringtonehaptics.app.domain.model

enum class AlgorithmPreset(
    val title: String,
    val subtitle: String,
    val iconName: String
) {
    HAPTIC_INSTRUMENTS(
        title = "Haptic Instruments",
        subtitle = "Stem-separated: percussion, bass groove, lead & fades",
        iconName = "Layers"
    ),
    CUSTOM(
        title = "Custom",
        subtitle = "Handcrafted pattern clip arrangement",
        iconName = "Tune"
    )
}
