package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.domain.model.HapticPatternType
import kotlin.math.roundToInt

private val MIN_PANEL_HEIGHT = 220.dp
private val MAX_PANEL_HEIGHT = 520.dp
private val DEFAULT_PANEL_HEIGHT = 360.dp
private val DISMISS_BUFFER = 90.dp

/**
 * Floating (non-modal, no scrim) panel - deliberately NOT a ModalBottomSheet: a scrim would block
 * drag gestures on the DAW timeline underneath, which is how clip duration is also extended
 * directly (the right-edge handle). Drag the handle to resize freely between MIN and MAX height
 * (the timeline above shrinks/grows to match, since it holds `weight(1f)` in the parent Column) -
 * drag far enough past the minimum and it dismisses, same gesture, no separate close button.
 */
@Composable
fun ClipPropertySheet(
    clip: HapticClip,
    onChangeType: (HapticPatternType) -> Unit,
    onChangeIntensity: (Float) -> Unit,
    onChangeDuration: (Int) -> Unit,
    onToggleBraking: (Boolean) -> Unit,
    onTestClipHaptic: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = getPatternColor(clip.patternType)
    val density = LocalDensity.current

    val minPx = with(density) { MIN_PANEL_HEIGHT.toPx() }
    val maxPx = with(density) { MAX_PANEL_HEIGHT.toPx() }
    val dismissFloorPx = minPx - with(density) { DISMISS_BUFFER.toPx() }
    var heightPx by remember { mutableFloatStateOf(with(density) { DEFAULT_PANEL_HEIGHT.toPx() }) }
    val heightDp = with(density) { heightPx.toDp() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Drag handle - drag up/down to resize the panel freely (the timeline above shrinks
            // to match); drag far enough below the minimum height and it dismisses instead.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (heightPx < minPx) onDismiss()
                                else heightPx = heightPx.coerceIn(minPx, maxPx)
                            },
                            onDragCancel = {
                                heightPx = heightPx.coerceIn(minPx, maxPx)
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            // Dragging up (negative dragAmount) grows the panel
                            heightPx = (heightPx - dragAmount).coerceIn(dismissFloorPx, maxPx)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${clip.patternType.displayName} Pattern Clip",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Starts at ${clip.startMs}ms • Duration ${clip.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row {
                    // Test Vibration Button
                    IconButton(
                        onClick = onTestClipHaptic,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Test Haptic",
                            tint = color,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Duplicate
                    IconButton(
                        onClick = onDuplicate,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Delete
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pattern Type Selector Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(HapticPatternType.values()) { type ->
                    val isCurrent = type == clip.patternType
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) color else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onChangeType(type) }
                    ) {
                        Text(
                            text = type.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Intensity Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Vibration Force", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${(clip.intensity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Slider(
                value = clip.intensity,
                onValueChange = onChangeIntensity,
                valueRange = 0.05f..1.0f
            )

            // Duration Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Clip Duration / Length", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onChangeDuration((clip.durationMs - 50).coerceAtLeast(15)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("-", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = color)
                    }
                    Text(
                        "${clip.durationMs} ms",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { onChangeDuration((clip.durationMs + 50).coerceAtMost(5000)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = color)
                    }
                }
            }

            // Quick Extend Preset Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Extend:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val quickDeltas = listOf("+50ms" to 50, "+150ms" to 150, "+300ms" to 300, "+500ms" to 500, "+1.0s" to 1000)
                for ((label, delta) in quickDeltas) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onChangeDuration((clip.durationMs + delta).coerceAtMost(5000)) }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = color.copy(alpha = 0.25f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onChangeDuration((clip.durationMs * 2).coerceAtMost(5000)) }
                ) {
                    Text(
                        text = "2×",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Active Braking Toggle (180° anti-phase damping)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "180° Active Motor Braking",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Injects anti-phase cycle to kill motor overhang & prevent buzz",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = clip.activeBraking,
                    onCheckedChange = onToggleBraking
                )
            }
        }
    }
}
