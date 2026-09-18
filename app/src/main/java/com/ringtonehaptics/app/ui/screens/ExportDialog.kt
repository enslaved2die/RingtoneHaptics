package com.ringtonehaptics.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ExportDialog(
    initialTitle: String,
    isRenderingPreview: Boolean,
    exportPreviewReady: Boolean,
    isPreviewPlaying: Boolean,
    isExporting: Boolean,
    exportedUri: Uri?,
    canWriteSettings: Boolean,
    onRenderPreview: (ringtoneName: String) -> Unit,
    onPlayPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onSave: (ringtoneName: String, setAsActive: Boolean) -> Unit,
    onPlayExportedPreview: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var setAsActive by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    exportedUri != null -> "Ringtone Ready!"
                    exportPreviewReady -> "Preview: How It Feels"
                    else -> "Export Pixel Ringtone"
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (exportedUri != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = "Saved to Ringtones/ with ANDROID_HAPTIC=1.\nIt is now selectable in the Pixel Sounds app!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    FilledTonalButton(
                        onClick = onPlayExportedPreview,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Test Hardware Audio-Coupled Haptics")
                    }
                } else if (isRenderingPreview || isExporting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.size(16.dp))
                        Text(
                            if (isExporting) "Saving to your Ringtones library..."
                            else "Encoding 3-channel OGG Vorbis with synchronized haptics..."
                        )
                    }
                } else if (exportPreviewReady) {
                    Text(
                        text = "This is exactly what will be saved - feel it on this device's " +
                            "hardware before it touches your Ringtones library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilledTonalButton(
                        onClick = { if (isPreviewPlaying) onStopPreview() else onPlayPreview() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(if (isPreviewPlaying) "Stop" else "Replay Preview")
                    }

                    // "Replay Preview" just replays the already-rendered file, and previously the
                    // ONLY way to get a fresh render reflecting a stem/setting change made after
                    // this preview was generated was to fully Cancel out and reopen the dialog from
                    // scratch. Re-renders from whatever the current title field holds right now -
                    // note this cannot pick up a new stem/filter change made outside this dialog
                    // (the dialog is modal, so those can only be changed before opening it or after
                    // Cancelling out), but it does cover the title-only-changed case and gives an
                    // explicit "try again" affordance instead of forcing a full cancel/reopen cycle.
                    TextButton(
                        onClick = { onRenderPreview(title) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = title.isNotBlank()
                    ) {
                        Text("Re-render Preview")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Ringtone Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    SetAsActiveRow(setAsActive, canWriteSettings, context) { setAsActive = it }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Ringtone Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    SetAsActiveRow(setAsActive, canWriteSettings, context) { setAsActive = it }
                }
            }
        },
        confirmButton = {
            when {
                exportedUri != null -> Button(onClick = onDismiss) { Text("Done") }
                isRenderingPreview || isExporting -> {}
                exportPreviewReady -> Button(
                    onClick = { onSave(title, setAsActive) },
                    enabled = title.isNotBlank()
                ) { Text("Save to Ringtones") }
                else -> Button(
                    onClick = { onRenderPreview(title) },
                    enabled = title.isNotBlank()
                ) { Text("Preview") }
            }
        },
        dismissButton = {
            if (!isRenderingPreview && !isExporting && exportedUri == null) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun SetAsActiveRow(
    setAsActive: Boolean,
    canWriteSettings: Boolean,
    context: android.content.Context,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = setAsActive,
            onCheckedChange = { checked ->
                if (checked && !canWriteSettings) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
                onCheckedChange(checked)
            }
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = "Set as default ringtone immediately",
                style = MaterialTheme.typography.bodyMedium
            )
            if (!canWriteSettings) {
                Text(
                    text = "Requires system write settings permission",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
