package com.ringtonehaptics.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TransportToolbar(
    isPlaying: Boolean,
    hapticsEnabled: Boolean,
    onPlayPauseToggle: () -> Unit,
    onRewind: () -> Unit,
    onToggleHaptics: () -> Unit,
    onSaveProject: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticBgColor by animateColorAsState(
        targetValue = if (hapticsEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "hapticBg"
    )

    Surface(
        modifier = modifier
            // Explicit rather than relying on Scaffold's default content insets already covering
            // it - keeps this pill clear of the gesture nav bar regardless of how the caller's
            // Scaffold is configured.
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .shadow(elevation = 12.dp, shape = CircleShape)
            .clip(CircleShape),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Save Project Button
            IconButton(
                onClick = onSaveProject,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save Project",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Rewind to start
            IconButton(
                onClick = onRewind,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = "Rewind",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // Big Play/Pause Button (Pixel Player Style)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onPlayPauseToggle() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = isPlaying, label = "playPause") { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Tactile Vibration Preview Toggle
            IconButton(
                onClick = onToggleHaptics,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(hapticBgColor)
            ) {
                Icon(
                    imageVector = Icons.Default.Vibration,
                    contentDescription = "Live Haptics",
                    tint = if (hapticsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Export Button
            IconButton(
                onClick = onExportClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = Icons.Outlined.IosShare,
                    contentDescription = "Export Ringtone",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
