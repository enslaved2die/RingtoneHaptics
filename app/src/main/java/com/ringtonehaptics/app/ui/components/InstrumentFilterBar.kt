package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ringtonehaptics.app.domain.model.HapticInstrument

/**
 * Compact mute/solo strip for the "Haptic Instruments" pipeline - tap to mute an instrument's
 * clips, long-tap-style double-tap semantics aren't used here (single tap mutes, a small solo
 * affordance is exposed via [onSoloInstrument] triggered by tapping an already-muted-others chip).
 */
@Composable
fun InstrumentFilterBar(
    instruments: List<HapticInstrument>,
    mutedInstruments: Set<HapticInstrument>,
    soloedInstrument: HapticInstrument?,
    onToggleMute: (HapticInstrument) -> Unit,
    onToggleSolo: (HapticInstrument) -> Unit,
    modifier: Modifier = Modifier
) {
    if (instruments.size <= 1) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(instruments) { instrument ->
            val isMuted = mutedInstruments.contains(instrument)
            val isSoloed = soloedInstrument == instrument
            val isDimmed = soloedInstrument != null && !isSoloed
            val color = getInstrumentColor(instrument)

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = when {
                    isSoloed -> color.copy(alpha = 0.30f)
                    isMuted || isDimmed -> MaterialTheme.colorScheme.surfaceVariant
                    else -> color.copy(alpha = 0.15f)
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onToggleMute(instrument) }
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isMuted) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Icon(
                            imageVector = getInstrumentIcon(instrument),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else color
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = instrument.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMuted || isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = { onToggleSolo(instrument) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = "Solo ${instrument.displayName}",
                            modifier = Modifier.size(13.dp),
                            tint = if (isSoloed) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

private fun getInstrumentIcon(instrument: HapticInstrument): ImageVector {
    return when (instrument) {
        HapticInstrument.KICK -> Icons.Default.Bolt
        HapticInstrument.SNARE -> Icons.Default.Adjust
        HapticInstrument.HIHAT -> Icons.Default.Grain
        HapticInstrument.TOM -> Icons.Default.Circle
        HapticInstrument.CYMBAL -> Icons.Default.Star
        HapticInstrument.GROOVE_BASS -> Icons.Default.Waves
        HapticInstrument.LEAD_CADENCE -> Icons.Default.MusicNote
        HapticInstrument.DYNAMIC_ENVELOPE -> Icons.Default.Whatshot
    }
}

fun getInstrumentColor(instrument: HapticInstrument): androidx.compose.ui.graphics.Color {
    return when (instrument) {
        HapticInstrument.KICK -> com.ringtonehaptics.app.ui.theme.ClipThump
        HapticInstrument.SNARE -> com.ringtonehaptics.app.ui.theme.ClipSnareHit
        HapticInstrument.HIHAT -> com.ringtonehaptics.app.ui.theme.ClipSnap
        HapticInstrument.TOM -> com.ringtonehaptics.app.ui.theme.ClipTomHit
        HapticInstrument.CYMBAL -> com.ringtonehaptics.app.ui.theme.ClipSwell
        HapticInstrument.GROOVE_BASS -> com.ringtonehaptics.app.ui.theme.ClipRumble
        HapticInstrument.LEAD_CADENCE -> com.ringtonehaptics.app.ui.theme.ClipChirp
        HapticInstrument.DYNAMIC_ENVELOPE -> com.ringtonehaptics.app.ui.theme.ClipSwell
    }
}
