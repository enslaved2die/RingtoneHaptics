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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Which stem(s) you hear during playback - null/"All" means the original mix + haptics as normal. */
@Composable
fun StemLayerPills(
    stems: List<String>,
    selectedStem: String?,
    onSelectStem: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val ordered = STEM_ORDER.filter { it in stems }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StemPill(
                label = "All",
                color = MaterialTheme.colorScheme.primary,
                isSelected = selectedStem == null,
                icon = Icons.Default.GraphicEq,
                onClick = { onSelectStem(null) }
            )
        }
        items(ordered) { name ->
            StemPill(
                label = STEM_LABELS[name] ?: name,
                color = STEM_COLORS[name] ?: MaterialTheme.colorScheme.primary,
                isSelected = selectedStem == name,
                icon = null,
                onClick = { onSelectStem(name) }
            )
        }
    }
}

@Composable
private fun StemPill(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) color.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal val STEM_ORDER = listOf("drums", "bass", "other", "vocals")
internal val STEM_LABELS = mapOf(
    "drums" to "Drums",
    "bass" to "Bass",
    "other" to "Other",
    "vocals" to "Vocals"
)
internal val STEM_COLORS = mapOf(
    "drums" to androidx.compose.ui.graphics.Color(0xFFFF6E4A),
    "bass" to androidx.compose.ui.graphics.Color(0xFF00E5FF),
    "other" to androidx.compose.ui.graphics.Color(0xFFB388FF),
    "vocals" to androidx.compose.ui.graphics.Color(0xFF69F0AE)
)
