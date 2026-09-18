package com.ringtonehaptics.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class HapticPatternType(
    val displayName: String,
    val description: String,
    val defaultDurationMs: Int
) {
    THUMP(
        displayName = "Thump",
        description = "Deep resonant kick thump with active motor braking",
        defaultDurationMs = 55
    ),
    SNAP_CLICK(
        displayName = "Snap Click",
        description = "Ultra-crisp transient click with 180° active motor braking",
        defaultDurationMs = 15
    ),
    SNARE_HIT(
        displayName = "Snare Hit",
        description = "Medium broadband-feeling burst, between a thump and a click",
        defaultDurationMs = 45
    ),
    TOM_HIT(
        displayName = "Tom Hit",
        description = "Deep single-partial hit, rounder and longer than a kick",
        defaultDurationMs = 75
    ),
    RUMBLE(
        displayName = "Sub Rumble",
        description = "Continuous low-end groove with subtle flutter modulation",
        defaultDurationMs = 250
    ),
    SWELL(
        displayName = "Crescendo Swell",
        description = "Quadratic energy ramp culminating in an explosive snap",
        defaultDurationMs = 350
    ),
    DOUBLE_TAP(
        displayName = "Double Tap",
        description = "Paired micro-bursts with active braking between taps",
        defaultDurationMs = 120
    ),
    CHIRP(
        displayName = "Ascending Chirp",
        description = "Frequency modulation glide (140Hz -> 180Hz) gesture",
        defaultDurationMs = 160
    ),
    BUILDUP(
        displayName = "Buildup",
        description = "Long accelerating tremolo ramp, frequency arriving at resonance at the peak",
        defaultDurationMs = 1500
    ),
    DROP(
        displayName = "Drop",
        description = "Double-exponential impact with a pitch-down tail - the release after a Buildup",
        defaultDurationMs = 180
    )
}
