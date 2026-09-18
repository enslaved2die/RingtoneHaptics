package com.ringtonehaptics.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ringtonehaptics.app.domain.ml.MODEL_APPROX_SIZE_MB
import kotlin.math.roundToInt
import com.ringtonehaptics.app.domain.model.HapticGenerationMode
import com.ringtonehaptics.app.ui.components.ClipPropertySheet
import com.ringtonehaptics.app.ui.components.DrumClassPills
import com.ringtonehaptics.app.ui.components.FilterSheet
import com.ringtonehaptics.app.ui.components.BpmAdjustDialog
import com.ringtonehaptics.app.ui.components.GenerationModeSelector
import com.ringtonehaptics.app.ui.components.GridLayerPills
import com.ringtonehaptics.app.ui.components.InstrumentFilterBar
import com.ringtonehaptics.app.ui.components.ManualStampBar
import com.ringtonehaptics.app.ui.components.MultiLaneDawTimeline
import com.ringtonehaptics.app.ui.components.ProjectListItem
import com.ringtonehaptics.app.ui.components.StemLayerPills
import com.ringtonehaptics.app.ui.components.TransportToolbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onPickAudioClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val track = uiState.audioData
    val selectedClip = uiState.hapticClips.find { it.id == uiState.selectedClipId }
    // While editing a clip, everything except the timeline and the edit panel itself collapses
    // out of the layout entirely (not just visually shrunk) - the two split the screen between
    // them instead of competing with header chrome and the palette dock for space. Needed up
    // here (not just inside the track-loaded branch) because the top bar also collapses to just
    // its icon buttons while editing, same as the track-info row used to.
    val isEditing = selectedClip != null

    // There's a single Activity with no navigation back-stack, so without these the system back
    // gesture always falls through to exiting the app instead of closing whatever's open.
    BackHandler(enabled = selectedClip != null) {
        viewModel.selectClip(null)
    }
    BackHandler(enabled = selectedClip == null && track != null) {
        viewModel.closeProject()
    }

    Scaffold(
        topBar = {
            // No static "Pixel Ringtone Haptics" headline anymore - once a track is loaded, this
            // single row IS the track header (title, clip count, BPM, playhead) with the action
            // buttons alongside it, instead of stacking a mostly-empty icon bar on top of a
            // separate track-info row below it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (track != null && !isEditing) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${uiState.hapticClips.size} pattern clips • ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                            Text(
                                text = "${uiState.beatGrid.bpm} BPM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.setBpmAdjustDialogVisible(true) }
                            )
                        }
                    }
                    Text(
                        text = formatTime(uiState.playbackPositionMs) + " / " + formatTime(track.durationMs),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Always visible (not gated behind an already-loaded track) - the ML
                // "auto-run on import" toggle needs to be reachable BEFORE picking a file,
                // otherwise it can only ever take effect on the *next* import.
                IconButton(
                    onClick = { viewModel.setFilterSheetVisible(true) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(
                    onClick = onPickAudioClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Import Soundfile",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Mirrors TransportToolbar's own navigationBarsPadding() + ~92dp visual
                    // footprint (pill padding + icon size) so content never extends behind it -
                    // a flat dp guess here previously didn't account for the nav bar inset.
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp)
            ) {
                // Status message banner
                AnimatedVisibility(
                    visible = uiState.statusMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (uiState.isLoading || uiState.isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.size(10.dp))
                            }
                            Text(
                                text = uiState.statusMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                // Determinate progress banner for the long-running ML model download / separation
                AnimatedVisibility(
                    visible = uiState.mlStage != MlStage.IDLE,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (uiState.mlStage) {
                                        MlStage.DOWNLOADING -> "Downloading ML model (${MODEL_APPROX_SIZE_MB}MB)..."
                                        MlStage.SEPARATING -> "Separating stems with ML (this can take a few minutes)..."
                                        MlStage.IDLE -> ""
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "${(uiState.mlProgress * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { uiState.mlProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f)
                            )
                        }
                    }
                }

                if (track == null) {
                    // Empty state welcoming user, plus saved projects to reopen
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth(0.88f)
                                    .padding(vertical = 20.dp, horizontal = 24.dp),
                                shape = RoundedCornerShape(32.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(32.dp)
                                        .fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(72.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.GraphicEq,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Text(
                                        text = "Pixel DAW Haptic Engine",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Import any audio file to generate multi-band haptic pattern bars with Active Motor Braking & Beat Grids.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(24.dp))

                                    FilledTonalButton(
                                        onClick = onPickAudioClick,
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.AudioFile, contentDescription = null)
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text("Pick Sound File")
                                    }
                                }
                            }
                        }

                        if (uiState.projects.isNotEmpty()) {
                            Text(
                                text = "Recent Projects",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(uiState.projects, key = { it.id }) { project ->
                                    ProjectListItem(
                                        project = project,
                                        onOpen = { viewModel.openProject(project.id) },
                                        onDelete = { viewModel.deleteProject(project.id) }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Track header info (title, clip count, BPM, playhead) now lives in the top
                    // bar itself instead of a separate row here - see Scaffold's topBar above.
                    if (!isEditing) {
                        // 1b. Generation Mode - the single control for what gets generated.
                        GenerationModeSelector(
                            mode = uiState.generationMode,
                            onSelectMode = { viewModel.setGenerationMode(it) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )

                        // 2. Haptic Instrument Mute/Solo Strip
                        InstrumentFilterBar(
                            instruments = uiState.hapticClips.map { it.instrument }.distinct().sortedBy { it.ordinal },
                            mutedInstruments = uiState.mutedInstruments,
                            soloedInstrument = uiState.soloedInstrument,
                            onToggleMute = { viewModel.toggleInstrumentMute(it) },
                            onToggleSolo = { viewModel.toggleInstrumentSolo(it) }
                        )

                        // 2b. Audio Layer Pills - which stem(s) you hear during playback, and which
                        // colored waveform layer(s) render in the timeline below (merged into the
                        // waveform lane itself rather than a separate panel).
                        if (uiState.stemWaveforms != null) {
                            StemLayerPills(
                                stems = uiState.stemWaveforms!!.keys.toList(),
                                selectedStem = uiState.selectedAudioStem,
                                onSelectStem = { viewModel.selectAudioStem(it) }
                            )
                        }

                        // 2b-ii. Drum class multi-select - only meaningful for Stem Pulse with
                        // "drums" as the active stem (see EditorViewModel.buildDrumOnsetEnvelope);
                        // hidden otherwise rather than shown-but-inert.
                        if (uiState.generationMode == HapticGenerationMode.STEM_PULSE && uiState.selectedAudioStem == "drums") {
                            DrumClassPills(
                                selected = uiState.selectedDrumClasses,
                                onToggle = { viewModel.toggleDrumClass(it) }
                            )
                        }

                        // 2c. Grid Layer Pills - independently toggleable bar/beat/note grid
                        // lines drawn over the timeline below (purely visual, unrelated to
                        // snapToGrid which only affects manual clip dragging/stamping).
                        GridLayerPills(
                            showBarGrid = uiState.showBarGrid,
                            showBeatGrid = uiState.showBeatGrid,
                            showNoteGrid = uiState.showNoteGrid,
                            onToggleBarGrid = { viewModel.toggleBarGridVisible() },
                            onToggleBeatGrid = { viewModel.toggleBeatGridVisible() },
                            onToggleNoteGrid = { viewModel.toggleNoteGridVisible() }
                        )

                        // 2d. Manual mode's placement UI - tap a pattern to stamp it at the
                        // playhead. Only shown in Manual mode since nothing else generates clips
                        // automatically there.
                        if (uiState.generationMode == HapticGenerationMode.MANUAL) {
                            ManualStampBar(onStamp = { viewModel.stampPattern(it) })
                        }

                    }

                    // 3. Multi-Lane DAW Timeline (Lane 1 Audio Waveform, Lane 2 Haptic Clips)
                    // Always keeps weight(1f) of whatever space remains - the clip inspector
                    // below takes a fixed height instead of floating over this, so both stay
                    // visible and usable at once (a real split layout, not an overlay).
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        MultiLaneDawTimeline(
                            audioOverview = track.waveformOverview,
                            stemWaveforms = uiState.stemWaveforms,
                            selectedAudioStem = uiState.selectedAudioStem,
                            clips = uiState.audibleClips,
                            beatGrid = uiState.beatGrid,
                            durationMs = track.durationMs,
                            currentPlaybackMs = uiState.playbackPositionMs,
                            isPlaying = uiState.isPlaying,
                            selectedClipId = uiState.selectedClipId,
                            snapToGrid = uiState.snapToGrid,
                            showBarGrid = uiState.showBarGrid,
                            showBeatGrid = uiState.showBeatGrid,
                            showNoteGrid = uiState.showNoteGrid,
                            onSeek = { viewModel.seekTo(it) },
                            onClipSelect = { viewModel.selectClip(it) },
                            onClipMove = { id, start -> viewModel.moveClip(id, start) },
                            onClipTrim = { id, start, dur -> viewModel.trimClip(id, start, dur) },
                            onEmptyAreaTap = { viewModel.seekTo(it) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    }

                    // 3b. Selected Clip Inspector - a fixed-height split pane below the timeline,
                    // not an overlay: both stay visible and interactive at the same time.
                    if (selectedClip != null) {
                        ClipPropertySheet(
                            clip = selectedClip,
                            onChangeType = { viewModel.updateClipType(selectedClip.id, it) },
                            onChangeIntensity = { viewModel.updateClipIntensity(selectedClip.id, it) },
                            onChangeDuration = { viewModel.updateClipDuration(selectedClip.id, it) },
                            onToggleBraking = { viewModel.updateClipBraking(selectedClip.id, it) },
                            onTestClipHaptic = { viewModel.testClipHaptic(selectedClip.id) },
                            onDuplicate = { viewModel.duplicateClip(selectedClip.id) },
                            onDelete = { viewModel.deleteClip(selectedClip.id) },
                            onDismiss = { viewModel.selectClip(null) }
                        )
                    }

                }
            }

            // Floating Transport Toolbar anchored at bottom
            if (track != null) {
                TransportToolbar(
                    isPlaying = uiState.isPlaying,
                    hapticsEnabled = uiState.liveHapticsEnabled,
                    onPlayPauseToggle = { viewModel.togglePlayPause() },
                    onRewind = { viewModel.rewind() },
                    onToggleHaptics = { viewModel.toggleLiveHaptics() },
                    onSaveProject = { viewModel.setSaveProjectDialogVisible(true) },
                    onExportClick = { viewModel.setExportDialogVisible(true) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // DSP Filters Bottom Sheet
            if (uiState.showFilterSheet) {
                FilterSheet(
                    config = uiState.filterConfig,
                    onConfigChange = { viewModel.updateFilterConfig(it) },
                    onDismiss = { viewModel.setFilterSheetVisible(false) },
                    generationMode = uiState.generationMode,
                    mlModelReady = uiState.mlModelReady,
                    mlStage = uiState.mlStage,
                    useMlByDefault = uiState.useMlByDefault,
                    onRequestMlEnhance = { viewModel.requestEnhancedMlMode() },
                    onToggleUseMlByDefault = { viewModel.toggleUseMlByDefault() },
                    snapToGrid = uiState.snapToGrid,
                    onToggleSnapToGrid = { viewModel.toggleSnapToGrid() },
                    onSensitivityCommitted = { viewModel.reanalyzeWithCurrentSensitivity() }
                )
            }

            // ML Enhance Download Confirmation
            if (uiState.showMlEnhanceConfirm) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissMlEnhanceConfirm() },
                    title = { Text("Enhance with ML?") },
                    text = {
                        Text(
                            "Downloads a one-time ${MODEL_APPROX_SIZE_MB}MB on-device stem separation model " +
                                "(drums/bass/vocals/other) for materially cleaner haptic instrument separation. " +
                                "Separation itself can take a few minutes on-device (CPU-only). Wi-Fi recommended."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmMlModelDownload() }) {
                            Text("Download & Run")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissMlEnhanceConfirm() }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Save Project Dialog
            if (uiState.showSaveProjectDialog) {
                var nameInput by remember {
                    mutableStateOf(uiState.currentProjectName ?: track?.title ?: "Untitled Project")
                }
                AlertDialog(
                    onDismissRequest = { viewModel.setSaveProjectDialogVisible(false) },
                    title = { Text(if (uiState.currentProjectId != null) "Update Project" else "Save Project") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine = true,
                            label = { Text("Project name") }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { viewModel.saveCurrentProject(nameInput.ifBlank { "Untitled Project" }) }
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.setSaveProjectDialogVisible(false) }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Replace Existing Project Confirmation
            uiState.pendingReplaceProject?.let { (_, name) ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissReplaceProjectConfirm() },
                    title = { Text("Replace existing project?") },
                    text = { Text("A project named \"$name\" already exists. Replace it with this one?") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmReplaceProject() }) {
                            Text("Replace")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissReplaceProjectConfirm() }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Manual BPM correction
            if (uiState.showBpmAdjustDialog) {
                BpmAdjustDialog(
                    currentBpm = uiState.beatGrid.bpm,
                    onDismiss = { viewModel.setBpmAdjustDialogVisible(false) },
                    onApplyRatio = { num, den -> viewModel.adjustBpm(num, den) },
                    onApplyExact = { bpm -> viewModel.adjustBpm(1, 1, exactBpm = bpm) }
                )
            }

            // Export Dialog
            if (uiState.showExportDialog) {
                ExportDialog(
                    initialTitle = track?.title ?: "Pixel_Haptic_Ringtone",
                    isRenderingPreview = uiState.isRenderingPreview,
                    exportPreviewReady = uiState.exportPreviewReady,
                    isPreviewPlaying = uiState.isPreviewPlaying,
                    isExporting = uiState.isExporting,
                    exportedUri = uiState.exportedUri,
                    canWriteSettings = uiState.canWriteSettings,
                    onRenderPreview = { name -> viewModel.renderExportPreview(name) },
                    onPlayPreview = { viewModel.playExportPreview() },
                    onStopPreview = { viewModel.stopExportPreview() },
                    onSave = { name, setAsActive -> viewModel.saveExportPreview(name, setAsActive) },
                    onPlayExportedPreview = { viewModel.playExportedRingtonePreview() },
                    onDismiss = { viewModel.setExportDialogVisible(false) }
                )
            }

            // Overwrite Existing Ringtone Confirmation
            if (uiState.pendingRingtoneOverwrite != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissOverwriteConfirm() },
                    title = { Text("Ringtone already exists") },
                    text = { Text("A ringtone with this name already exists in your library. Overwrite it?") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmOverwriteRingtone() }) {
                            Text("Overwrite")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissOverwriteConfirm() }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format("%02d:%02d", minutes, seconds)
}
