package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Automatic tempo detection can lock onto the wrong tempo by a simple ratio - confirmed common
 * cases are 2x/0.5x (octave errors) and 1.5x/2:3 (compound-meter/triplet-feel confusions). This is
 * a manual correction affordance, the same as every real DAW/beat-detection tool ships, since no
 * on-device autocorrelation detector can be 100% reliable on arbitrary real-world music.
 */
@Composable
fun BpmAdjustDialog(
    currentBpm: Int,
    onDismiss: () -> Unit,
    onApplyRatio: (numerator: Int, denominator: Int) -> Unit,
    onApplyExact: (bpm: Int) -> Unit
) {
    var draftBpm by remember(currentBpm) { mutableIntStateOf(currentBpm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct Tempo") },
        text = {
            Column {
                Text(
                    "Detected $currentBpm BPM. If the grid looks off, it's most often locked onto " +
                        "double/half tempo, or a 3:2 compound-meter mix-up - try these first:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { onApplyRatio(1, 2) }) { Text("½×") }
                    OutlinedButton(onClick = { onApplyRatio(2, 1) }) { Text("2×") }
                    OutlinedButton(onClick = { onApplyRatio(2, 3) }) { Text("⅔×") }
                    OutlinedButton(onClick = { onApplyRatio(3, 2) }) { Text("1.5×") }
                }

                Text(
                    "Or fine-tune it directly:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = { draftBpm = (draftBpm - 1).coerceIn(40, 300) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease BPM")
                    }
                    Text(
                        text = "$draftBpm BPM",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    IconButton(onClick = { draftBpm = (draftBpm + 1).coerceIn(40, 300) }) {
                        Icon(Icons.Default.Add, contentDescription = "Increase BPM")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApplyExact(draftBpm) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
