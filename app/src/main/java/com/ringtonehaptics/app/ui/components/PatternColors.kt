package com.ringtonehaptics.app.ui.components

import androidx.compose.ui.graphics.Color
import com.ringtonehaptics.app.domain.model.HapticPatternType
import com.ringtonehaptics.app.ui.theme.ClipBuildup
import com.ringtonehaptics.app.ui.theme.ClipChirp
import com.ringtonehaptics.app.ui.theme.ClipDoubleTap
import com.ringtonehaptics.app.ui.theme.ClipDrop
import com.ringtonehaptics.app.ui.theme.ClipRumble
import com.ringtonehaptics.app.ui.theme.ClipSnap
import com.ringtonehaptics.app.ui.theme.ClipSnareHit
import com.ringtonehaptics.app.ui.theme.ClipSwell
import com.ringtonehaptics.app.ui.theme.ClipThump
import com.ringtonehaptics.app.ui.theme.ClipTomHit

fun getPatternColor(type: HapticPatternType): Color {
    return when (type) {
        HapticPatternType.THUMP -> ClipThump
        HapticPatternType.SNAP_CLICK -> ClipSnap
        HapticPatternType.SNARE_HIT -> ClipSnareHit
        HapticPatternType.TOM_HIT -> ClipTomHit
        HapticPatternType.RUMBLE -> ClipRumble
        HapticPatternType.SWELL -> ClipSwell
        HapticPatternType.DOUBLE_TAP -> ClipDoubleTap
        HapticPatternType.CHIRP -> ClipChirp
        HapticPatternType.BUILDUP -> ClipBuildup
        HapticPatternType.DROP -> ClipDrop
    }
}
