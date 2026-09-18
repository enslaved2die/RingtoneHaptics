package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticGenerationMode
import com.ringtonehaptics.app.domain.ml.MODEL_APPROX_SIZE_MB
import com.ringtonehaptics.app.ui.screens.MlStage
import kotlin.math.roundToInt

/**
 * Synthesis-time controls, plus the ML Enhance entry point (moved here from the main editor
 * header - this is "settings", stamping/muting/soloing lives on the main screen). This app runs
 * a single "Haptic Instruments" analysis pipeline (HPSS/transient/fade detection, none of which
 * read FilterConfig) - filter type, cutoff and sensitivity used to drive the old per-preset
 * heuristics and are gone with them. What's left of FilterConfig (resonance/gain/blend) is
 * applied fresh at preview/export synthesis time, so changing it never needs to re-run analysis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    config: FilterConfig,
    onConfigChange: (FilterConfig) -> Unit,
    onDismiss: () -> Unit,
    generationMode: HapticGenerationMode = HapticGenerationMode.AUTOMATIC,
    mlModelReady: Boolean = false,
    mlStage: MlStage = MlStage.IDLE,
    useMlByDefault: Boolean = false,
    onRequestMlEnhance: () -> Unit = {},
    onToggleUseMlByDefault: () -> Unit = {},
    snapToGrid: Boolean = true,
    onToggleSnapToGrid: () -> Unit = {},
    onSensitivityCommitted: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Vibration Output",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tunes the LRA waveform synthesis, not clip placement",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Resonant Frequency Slider (Pixel LRA)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pixel Actuator Resonance (F_res)", style = MaterialTheme.typography.bodyLarge)
                Text("${config.resonantFrequencyHz.roundToInt()} Hz", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Nominal resonance for Pixel 6 - 9 is 160 Hz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = config.resonantFrequencyHz,
                onValueChange = { onConfigChange(config.copy(resonantFrequencyHz = it)) },
                valueRange = 120.0f..240.0f
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Master Haptic Gain
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Master Haptic Gain", style = MaterialTheme.typography.bodyLarge)
                Text("${config.masterGainDb.roundToInt()} dBFS", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Keeps output below 0 dB to avoid motor chassis buzz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = config.masterGainDb,
                onValueChange = { onConfigChange(config.copy(masterGainDb = it)) },
                valueRange = -12.0f..0.0f
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Blend Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Continuous vs Pulse Beats", style = MaterialTheme.typography.bodyLarge)
                Text("${(config.blendContinuousEnvelope * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = config.blendContinuousEnvelope,
                onValueChange = { onConfigChange(config.copy(blendContinuousEnvelope = it)) },
                valueRange = 0.0f..1.0f
            )

            if (generationMode == HapticGenerationMode.STEM_PULSE) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Punch (Transient Emphasis)", style = MaterialTheme.typography.bodyLarge)
                    Text("${(config.transientEmphasis * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Pushes sustained/quiet parts of the stem down further so loud hits (e.g. kick/snare in a drums stem) stand out as isolated spikes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.transientEmphasis,
                    onValueChange = { onConfigChange(config.copy(transientEmphasis = it)) },
                    valueRange = 0.0f..1.0f
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Clip Detection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "These affect where clips get placed - changes re-run detection",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Loudness Threshold", style = MaterialTheme.typography.bodyLarge)
                Text("${(config.sensitivityThreshold * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Lower this if quiet sections (e.g. a soft intro) aren't getting any Groove/Bass or Lead Cadence clips",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = config.sensitivityThreshold,
                onValueChange = { onConfigChange(config.copy(sensitivityThreshold = it)) },
                onValueChangeFinished = onSensitivityCommitted,
                valueRange = 0.05f..0.90f
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Snap to Beat Grid", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Dragging a clip snaps its start to the nearest detected beat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = snapToGrid, onCheckedChange = { onToggleSnapToGrid() })
            }

            Spacer(modifier = Modifier.height(20.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "ML-Enhanced Separation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Real 4-stem separation (drums/bass/other/vocals) instead of on-device HPSS - materially cleaner, but a one-time ${MODEL_APPROX_SIZE_MB}MB download and a few minutes of processing per song.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-run on import", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Skip the manual step - run ML Enhance right after picking a file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useMlByDefault, onCheckedChange = { onToggleUseMlByDefault() })
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (mlStage == MlStage.IDLE) {
                TextButton(onClick = onRequestMlEnhance) {
                    Text(
                        text = if (mlModelReady) "Re-run with ML Enhance" else "Enhance with ML (${MODEL_APPROX_SIZE_MB}MB)",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Text(
                    text = if (mlStage == MlStage.DOWNLOADING) "Downloading model..." else "Separating stems...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
