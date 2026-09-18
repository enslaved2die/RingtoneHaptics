package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ringtonehaptics.app.domain.ml.DrumClass

/**
 * Stem Pulse + "drums" stem only - which DrumTranscriber class(es) (kick/snare/tom/hihat/cymbal)
 * drive the onset-pulse haptic layer (see EditorViewModel.buildDrumOnsetEnvelope). Multi-select,
 * like GridLayerPills, not single-select like StemLayerPills - any combination can be felt at once,
 * this only narrows which onsets feed the same continuous envelope rather than switching sources.
 * `selected` empty means "all classes" (EditorViewModel.effectiveDrumClasses's convention), so every
 * pill renders "on" by default the first time a user reaches "drums" without having to opt in.
 */
@Composable
fun DrumClassPills(
    selected: Set<DrumClass>,
    onToggle: (DrumClass) -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveSelected = selected.ifEmpty { DrumClass.entries.toSet() }
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DrumClass.entries) { drumClass ->
            DrumClassPill(
                label = DRUM_CLASS_LABELS[drumClass] ?: drumClass.name,
                isOn = drumClass in effectiveSelected,
                onClick = { onToggle(drumClass) }
            )
        }
    }
}

@Composable
private fun DrumClassPill(label: String, isOn: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isOn) color.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isOn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val DRUM_CLASS_LABELS = mapOf(
    DrumClass.KICK to "Kick",
    DrumClass.SNARE to "Snare",
    DrumClass.TOM to "Tom",
    DrumClass.HIHAT to "Hi-Hat",
    DrumClass.CYMBAL to "Cymbal"
)
