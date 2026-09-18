package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Independent visibility toggles for the timeline's bar/beat/note grid layers (unlike
 * StemLayerPills, these are multi-select - any combination can be on at once, since a layer
 * being enabled only adds lines to the timeline rather than switching what's audible).
 */
@Composable
fun GridLayerPills(
    showBarGrid: Boolean,
    showBeatGrid: Boolean,
    showNoteGrid: Boolean,
    onToggleBarGrid: () -> Unit,
    onToggleBeatGrid: () -> Unit,
    onToggleNoteGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            GridLayerPill(label = "Bars", isOn = showBarGrid, onClick = onToggleBarGrid)
        }
        item {
            GridLayerPill(label = "Beats", isOn = showBeatGrid, onClick = onToggleBeatGrid)
        }
        item {
            GridLayerPill(label = "Notes", isOn = showNoteGrid, onClick = onToggleNoteGrid)
        }
    }
}

@Composable
private fun GridLayerPill(label: String, isOn: Boolean, onClick: () -> Unit) {
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
