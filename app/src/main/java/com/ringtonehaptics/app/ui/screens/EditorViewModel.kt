package com.ringtonehaptics.app.ui.screens

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ringtonehaptics.app.data.audio.AudioDecoder
import com.ringtonehaptics.app.data.audio.HapticPreviewPlayer
import com.ringtonehaptics.app.data.storage.ProjectRepository
import com.ringtonehaptics.app.data.storage.RingtoneMediaStoreRepository
import com.ringtonehaptics.app.domain.dsp.AdvancedHapticAnalyzer
import com.ringtonehaptics.app.domain.dsp.AdvancedHapticSynthesizer
import com.ringtonehaptics.app.domain.dsp.BpmBeatDetector
import com.ringtonehaptics.app.domain.dsp.EnvelopeFollower
import com.ringtonehaptics.app.domain.ml.DrumClass
import com.ringtonehaptics.app.domain.ml.DrumModelStore
import com.ringtonehaptics.app.domain.ml.DrumOnset
import com.ringtonehaptics.app.domain.ml.DrumTranscriber
import com.ringtonehaptics.app.domain.ml.ModelDownloadManager
import com.ringtonehaptics.app.domain.ml.StemSeparationResult
import com.ringtonehaptics.app.domain.ml.StemSeparator
import com.ringtonehaptics.app.domain.model.AlgorithmPreset
import com.ringtonehaptics.app.domain.model.AudioTrackData
import com.ringtonehaptics.app.domain.model.BeatGridInfo
import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.domain.model.HapticGenerationMode
import com.ringtonehaptics.app.domain.model.HapticInstrument
import com.ringtonehaptics.app.domain.model.HapticPatternType
import com.ringtonehaptics.app.domain.model.HapticProject
import com.ringtonehaptics.app.domain.model.ProjectSummary
import com.ringtonehaptics.app.domain.model.combinedTransientAmount
import com.ringtonehaptics.app.domain.model.stemHapticProfileFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class EditorUiState(
    val audioData: AudioTrackData? = null,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val selectedPreset: AlgorithmPreset = AlgorithmPreset.HAPTIC_INSTRUMENTS,
    val beatGrid: BeatGridInfo = BeatGridInfo(),
    val hapticClips: List<HapticClip> = emptyList(),
    val mutedInstruments: Set<HapticInstrument> = emptySet(),
    val soloedInstrument: HapticInstrument? = null,
    val selectedClipId: Long? = null,
    val snapToGrid: Boolean = true,
    val showBarGrid: Boolean = false,
    val showBeatGrid: Boolean = true,
    val showNoteGrid: Boolean = false,
    val generationMode: HapticGenerationMode = HapticGenerationMode.AUTOMATIC,
    val filterConfig: FilterConfig = FilterConfig(),
    val playbackPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val liveHapticsEnabled: Boolean = true,
    val showFilterSheet: Boolean = false,
    val showExportDialog: Boolean = false,
    val exportedUri: Uri? = null,
    val isRenderingPreview: Boolean = false,
    val exportPreviewReady: Boolean = false, // a rendered-but-not-yet-saved preview file exists
    val isPreviewPlaying: Boolean = false,
    val pendingRingtoneOverwrite: Uri? = null, // existing MediaStore entry awaiting an overwrite confirmation
    val canWriteSettings: Boolean = false,
    val mlModelReady: Boolean = false,
    val mlStage: MlStage = MlStage.IDLE,
    val mlProgress: Float = 0f,
    val showMlEnhanceConfirm: Boolean = false,
    val stemWaveforms: Map<String, FloatArray>? = null,
    val selectedAudioStem: String? = null, // null = full mix ("All"); else one of drums/bass/other/vocals
    // Stem Pulse + "drums" stem only: which DrumTranscriber class(es) drive the onset-pulse haptic
    // layer (see EditorViewModel.effectiveContinuousEnvelopeFor's drums branch). Empty = all 5
    // classes - same null/empty-means-everything convention as selectedAudioStem above, so a fresh
    // "drums" selection starts fully-featured instead of silent until the user opts classes in.
    val selectedDrumClasses: Set<DrumClass> = emptySet(),
    val useMlByDefault: Boolean = false, // auto-run ML Enhance right after import instead of waiting for a manual tap
    val projects: List<ProjectSummary> = emptyList(), // shown on the launch screen, selectable to reopen
    val currentProjectId: String? = null,
    val currentProjectName: String? = null,
    val showSaveProjectDialog: Boolean = false,
    val pendingReplaceProject: Pair<String, String>? = null, // (existingId, name) awaiting a replace confirmation
    val showBpmAdjustDialog: Boolean = false // automatic tempo detection can lock onto the wrong
    // tempo by a simple ratio (2x/0.5x/1.5x/0.75x are the confirmed common cases) - this is a
    // manual correction affordance, same as every real DAW/beat-detection tool ships, since no
    // on-device autocorrelation detector can be 100% reliable
) {
    /** Clips visible/audible after applying instrument mute/solo - used for display, preview and export.
     *
     * Stem Pulse is envelope-only by design (see usesPercussion()/usesEnvelopeLayers() in
     * HapticGenerationMode.kt, both false for it) - it should never play or export a discrete clip,
     * whether auto-generated by another mode or hand-placed in Manual mode. mergeWithManualClips
     * deliberately keeps manually-placed clips in [hapticClips] across mode switches so they
     * reappear when switching back to Manual, so the exclusion has to live here (the read side),
     * not by dropping/filtering hapticClips itself - otherwise a manual clip placed earlier would
     * silently fire underneath Stem Pulse's continuous envelope on every play/export.
     */
    val audibleClips: List<HapticClip>
        get() {
            if (generationMode == HapticGenerationMode.STEM_PULSE) return emptyList()
            return when {
                soloedInstrument != null -> hapticClips.filter { it.instrument == soloedInstrument }
                mutedInstruments.isEmpty() -> hapticClips
                else -> hapticClips.filterNot { mutedInstruments.contains(it.instrument) }
            }
        }
}

enum class MlStage { IDLE, DOWNLOADING, SEPARATING }

private const val PREF_USE_ML_BY_DEFAULT = "use_ml_by_default"
private const val TAG = "EditorViewModel"

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val bpmDetector = BpmBeatDetector()
    private val drumTranscriber = DrumTranscriber(application)
    private val drumModelStore = DrumModelStore(application)
    private val advancedAnalyzer = AdvancedHapticAnalyzer(::transcribeDrumsCached)
    private val advancedSynthesizer = AdvancedHapticSynthesizer()
    private val envelopeFollower = EnvelopeFollower()
    private val mediaStoreRepo = RingtoneMediaStoreRepository(application)
    private val previewPlayer = HapticPreviewPlayer(application)
    private val modelDownloadManager = ModelDownloadManager(application)
    private val projectRepository = ProjectRepository(application)
    // App-wide settings that should survive process death (not per-project state, so
    // ProjectRepository's project JSON is the wrong place for this) - just a single boolean today,
    // plain SharedPreferences is proportionate.
    private val appPrefs = application.getSharedPreferences("editor_prefs", android.content.Context.MODE_PRIVATE)

    private var rawContinuousEnvelope: FloatArray = FloatArray(0)
    private var lastStemResult: StemSeparationResult? = null

    // Caches DrumTranscriber's (slow, ONNX-model-driven) output by the pcm array's identity, so
    // switching HapticGenerationMode back and forth on the same track doesn't silently re-run
    // inference every time - only a genuinely new/decoded track array invalidates it.
    private var lastDrumTranscriptionPcm: FloatArray? = null
    private var lastDrumOnsets: Map<DrumClass, List<DrumOnset>>? = null

    private fun transcribeDrumsCached(pcm: FloatArray, sampleRate: Int): Map<DrumClass, List<DrumOnset>> {
        val cached = lastDrumOnsets
        if (cached != null && lastDrumTranscriptionPcm === pcm) return cached

        // Every drum-analysis path funnels through here (always on a background dispatcher), so this
        // is the one place the ~1.8MB drum model needs fetching. A failed fetch (offline, first run)
        // returns no onsets WITHOUT caching that empty result, so drum detection simply retries on
        // the next analysis instead of being permanently disabled for this track.
        if (!drumModelStore.isReady()) {
            _uiState.update { it.copy(statusMessage = "Downloading drum model (1.8 MB)...") }
            try {
                drumModelStore.ensureDownloaded()
            } catch (error: Exception) {
                Log.w(TAG, "Drum model download failed", error)
                _uiState.update { it.copy(statusMessage = "Drum model download failed - drum detection skipped: ${error.localizedMessage}") }
                return emptyMap()
            }
        }
        val result = drumTranscriber.transcribe(pcm, sampleRate)
        lastDrumTranscriptionPcm = pcm
        lastDrumOnsets = result
        return result
    }
    private var pendingSourceUri: Uri? = null

    // The rendered-but-not-yet-saved export preview file (see renderExportPreview /
    // commitExportPreview) and the ringtone name it was rendered with - not put in EditorUiState
    // since a File isn't meant to survive process death the way that state is.
    private var exportPreviewFile: java.io.File? = null
    private var exportPreviewName: String? = null

    init {
        _uiState.update {
            it.copy(
                canWriteSettings = mediaStoreRepo.canSetSystemRingtone(),
                mlModelReady = modelDownloadManager.isModelReady(),
                projects = projectRepository.listProjects(),
                useMlByDefault = appPrefs.getBoolean(PREF_USE_ML_BY_DEFAULT, false)
            )
        }
    }

    fun loadAudio(uri: Uri) {
        pendingSourceUri = uri
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = "Decoding audio...",
                    currentProjectId = null,
                    currentProjectName = null,
                    // A mute/solo left over from whatever track was open before would otherwise
                    // silently filter this new track's clips out of audibleClips.
                    mutedInstruments = emptySet(),
                    soloedInstrument = null,
                    selectedClipId = null
                )
            }
            previewPlayer.stop()

            val result = AudioDecoder.decodeAudio(getApplication(), uri)
            result.onSuccess { track ->
                _uiState.update { it.copy(audioData = track, statusMessage = "Detecting tempo, beat grid & separating stems...") }

                withContext(Dispatchers.Default) {
                    // 1. Detect BPM and calculate Beat Grid
                    val grid = bpmDetector.detectBeatGrid(track.leftChannel, track.sampleRate, track.durationMs)

                    // 2. Continuous envelope
                    val envResult = envelopeFollower.analyze(track.leftChannel, track.sampleRate, _uiState.value.filterConfig)
                    rawContinuousEnvelope = envResult.envelope
                    // A new track can coincidentally share (stemName/classes, sampleCount) with the
                    // previous one's cache key - invalidate explicitly rather than rely on that.
                    stemEnvelopeCacheKey = null
                    drumOnsetEnvelopeCacheKey = null

                    // 3. Generate the Haptic Instruments clip set (the only pipeline this app runs)
                    val clips = advancedAnalyzer.generateClipsForPreset(
                        pcmSamples = track.leftChannel,
                        sampleRate = track.sampleRate,
                        durationMs = track.durationMs,
                        preset = AlgorithmPreset.HAPTIC_INSTRUMENTS,
                        beatGrid = grid,
                        config = _uiState.value.filterConfig,
                        mode = _uiState.value.generationMode
                    )

                    _uiState.update {
                        it.copy(
                            audioData = track,
                            beatGrid = grid,
                            hapticClips = clips,
                            selectedPreset = AlgorithmPreset.HAPTIC_INSTRUMENTS,
                            isLoading = false,
                            statusMessage = null
                        )
                    }
                }

                // If the user has opted into ML-by-default, chain straight into it - skips the
                // manual "Enhance with ML" tap. Still goes through the download confirmation the
                // first time (166MB is worth a heads-up even for an opted-in toggle); silent and
                // automatic once the model is already cached.
                if (_uiState.value.useMlByDefault) {
                    requestEnhancedMlMode()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Failed to load audio: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun toggleUseMlByDefault() {
        val newValue = !_uiState.value.useMlByDefault
        appPrefs.edit().putBoolean(PREF_USE_ML_BY_DEFAULT, newValue).apply()
        _uiState.update { it.copy(useMlByDefault = newValue) }
    }

    /** Re-runs clip detection with the current FilterConfig (e.g. after the Loudness Threshold
     * slider is released - deliberately NOT on every onValueChange tick, which is what caused the
     * earlier OOM crash by stacking concurrent HPSS passes while dragging). Reuses the cached ML
     * stems if this track was ML-enhanced, so adjusting sensitivity doesn't silently downgrade
     * back to the HPSS path. */
    fun reanalyzeWithCurrentSensitivity() {
        val state = _uiState.value
        val track = state.audioData ?: return

        _uiState.update { it.copy(isLoading = true, statusMessage = "Re-running detection...") }
        viewModelScope.launch(Dispatchers.Default) {
            val clips = advancedAnalyzer.generateInstrumentClips(
                pcmSamples = track.leftChannel,
                sampleRate = track.sampleRate,
                durationMs = track.durationMs,
                beatGrid = state.beatGrid,
                config = state.filterConfig,
                mlStems = lastStemResult,
                mode = state.generationMode
            )
            _uiState.update { it.copy(hapticClips = mergeWithManualClips(clips, it.hapticClips), isLoading = false, statusMessage = null) }
        }
    }

    fun toggleInstrumentMute(instrument: HapticInstrument) {
        _uiState.update { state ->
            val muted = state.mutedInstruments.toMutableSet()
            if (!muted.add(instrument)) muted.remove(instrument)
            state.copy(mutedInstruments = muted, soloedInstrument = null)
        }
        if (_uiState.value.isPlaying) seekTo(_uiState.value.playbackPositionMs)
    }

    fun toggleInstrumentSolo(instrument: HapticInstrument) {
        _uiState.update { state ->
            val newSolo = if (state.soloedInstrument == instrument) null else instrument
            state.copy(soloedInstrument = newSolo, mutedInstruments = emptySet())
        }
        if (_uiState.value.isPlaying) seekTo(_uiState.value.playbackPositionMs)
    }

    /** Entry point for the "Enhance with ML" action - asks to download the model on first use. */
    fun requestEnhancedMlMode() {
        if (_uiState.value.audioData == null) return
        if (_uiState.value.mlModelReady) {
            runMlEnhancedSeparation()
        } else {
            _uiState.update { it.copy(showMlEnhanceConfirm = true) }
        }
    }

    fun dismissMlEnhanceConfirm() {
        _uiState.update { it.copy(showMlEnhanceConfirm = false) }
    }

    fun confirmMlModelDownload() {
        _uiState.update {
            it.copy(showMlEnhanceConfirm = false, mlStage = MlStage.DOWNLOADING, mlProgress = 0f)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                modelDownloadManager.downloadModel { progress ->
                    _uiState.update { it.copy(mlProgress = progress) }
                }
                _uiState.update { it.copy(mlModelReady = true, mlStage = MlStage.IDLE) }
                runMlEnhancedSeparation()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mlStage = MlStage.IDLE,
                        statusMessage = "Model download failed: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun runMlEnhancedSeparation() {
        val track = _uiState.value.audioData ?: return
        _uiState.update { it.copy(mlStage = MlStage.SEPARATING, mlProgress = 0f) }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val stems = StemSeparator().separate(
                    modelPath = modelDownloadManager.modelPath(),
                    leftChannel = track.leftChannel,
                    rightChannel = track.rightChannel,
                    sampleRate = track.sampleRate,
                    onProgress = { progress -> _uiState.update { it.copy(mlProgress = progress) } }
                )

                val clips = advancedAnalyzer.generateInstrumentClips(
                    pcmSamples = track.leftChannel,
                    sampleRate = track.sampleRate,
                    durationMs = track.durationMs,
                    beatGrid = _uiState.value.beatGrid,
                    config = _uiState.value.filterConfig,
                    mlStems = stems,
                    mode = _uiState.value.generationMode
                )

                lastStemResult = stems
                val waveforms = mapOf(
                    "drums" to decimateWaveform(stems.drums),
                    "bass" to decimateWaveform(stems.bass),
                    "other" to decimateWaveform(stems.other),
                    "vocals" to decimateWaveform(stems.vocals)
                )

                _uiState.update {
                    it.copy(
                        hapticClips = mergeWithManualClips(clips, it.hapticClips),
                        selectedPreset = AlgorithmPreset.HAPTIC_INSTRUMENTS,
                        selectedClipId = null,
                        mutedInstruments = emptySet(),
                        soloedInstrument = null,
                        mlStage = MlStage.IDLE,
                        stemWaveforms = waveforms
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mlStage = MlStage.IDLE,
                        statusMessage = "ML separation failed: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun toggleSnapToGrid() {
        _uiState.update { it.copy(snapToGrid = !it.snapToGrid) }
    }

    fun toggleBarGridVisible() {
        _uiState.update { it.copy(showBarGrid = !it.showBarGrid) }
    }

    fun toggleBeatGridVisible() {
        _uiState.update { it.copy(showBeatGrid = !it.showBeatGrid) }
    }

    fun toggleNoteGridVisible() {
        _uiState.update { it.copy(showNoteGrid = !it.showNoteGrid) }
    }

    /** Switches generation mode and immediately regenerates clips for the current track - reuses
     * cached ML stems/drum-onsets (see transcribeDrumsCached), so this doesn't re-run inference. */
    fun setGenerationMode(mode: HapticGenerationMode) {
        // Stop any in-flight live playback/vibration BEFORE swapping modes. Stem Pulse's continuous
        // envelope vibration is a fire-and-forget vibrate() call (see
        // HapticPreviewPlayer.startContinuousEnvelopeVibration) that keeps buzzing independently of
        // Compose/uiState once started - it is never cancelled just because generationMode changes
        // underneath it. Without stopping here, switching away from Stem Pulse mid-playback left that
        // waveform running for its full remaining duration while the new mode's discrete clips also
        // started firing on top of it - the "both haptic modes collide and stay active" bug. Doing
        // this for every mode change (not just away from STEM_PULSE) since regenerating clips for a
        // new mode while the old mode's clips/vibration are still live is generically unsafe.
        if (_uiState.value.isPlaying) {
            previewPlayer.stop()
        }
        _uiState.update { it.copy(generationMode = mode, isPlaying = false) }
        val state = _uiState.value
        val track = state.audioData ?: return

        // Stem Pulse still works before ML separation (falls back to the full-mix envelope, see
        // effectiveContinuousEnvelopeFor), but that's not the point of the mode - nudge toward
        // actually picking an isolated stem instead of leaving the fallback silently in place.
        // Two genuinely different situations get two different hints, not one conflated message:
        // ML hasn't run at all yet (lastStemResult == null - "run ML Enhance" is real advice) vs.
        // ML already ran and a stem just hasn't been picked (selectedAudioStem == null - "run ML
        // Enhance" would be actively wrong/misleading there, since it's already done).
        //
        // mlBusy additionally means "don't touch statusMessage here at all" - mlStage != IDLE means
        // restoreMlStemsAfterReopen (or requestEnhancedMlMode) is actively driving an accurate,
        // live-updating message ("Restoring ML separation...", download progress, etc) right now;
        // this function used to unconditionally overwrite that with a stale/wrong Stem Pulse hint
        // whenever the user switched modes mid-restore.
        val mlBusy = state.mlStage != MlStage.IDLE
        val hint = if (mode == HapticGenerationMode.STEM_PULSE && !mlBusy) {
            when {
                lastStemResult == null ->
                    "Stem Pulse feels an isolated stem - run ML Enhance below, then pick a stem, to hear it isolated (using full mix for now)."
                state.selectedAudioStem == null ->
                    "Stem Pulse feels an isolated stem - pick one below to hear it isolated (using full mix for now)."
                else -> null
            }
        } else null

        _uiState.update {
            it.copy(
                isLoading = true,
                statusMessage = if (mlBusy) it.statusMessage else (hint ?: "Re-running detection...")
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            val clips = advancedAnalyzer.generateInstrumentClips(
                pcmSamples = track.leftChannel,
                sampleRate = track.sampleRate,
                durationMs = track.durationMs,
                beatGrid = state.beatGrid,
                config = state.filterConfig,
                mlStems = lastStemResult,
                mode = mode
            )
            _uiState.update {
                it.copy(
                    hapticClips = mergeWithManualClips(clips, it.hapticClips),
                    isLoading = false,
                    statusMessage = if (mlBusy) it.statusMessage else (if (it.statusMessage == hint) hint else null)
                )
            }
        }
    }

    fun setBpmAdjustDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showBpmAdjustDialog = visible) }
    }

    /** Rescales the detected beat grid to a corrected BPM, keeping the same phase (firstBeatMs) -
     * a cheap, instant grid transform, not a re-detection. [ratioNumerator]/[ratioDenominator]
     * cover the confirmed common autocorrelation failure modes (2x/0.5x octave errors, 1.5x/0.75x
     * compound-meter confusions - see BpmBeatDetector's doc comment); pass 1/1 with an explicit
     * [exactBpm] for a free-form manual correction instead. */
    fun adjustBpm(ratioNumerator: Int, ratioDenominator: Int, exactBpm: Int? = null) {
        val state = _uiState.value
        val track = state.audioData ?: return
        val oldGrid = state.beatGrid
        if (oldGrid.bpm <= 0) return

        val newBpm = (exactBpm ?: ((oldGrid.bpm * ratioNumerator) / ratioDenominator)).coerceIn(40, 300)
        val newIntervalMs = (60_000.0 / newBpm).toLong().coerceAtLeast(1L)

        val beatTimestamps = mutableListOf<Long>()
        var t = oldGrid.firstBeatMs
        while (t <= track.durationMs) {
            beatTimestamps.add(t)
            t += newIntervalMs
        }
        val newGrid = oldGrid.copy(
            bpm = newBpm,
            beatIntervalMs = newIntervalMs,
            beatTimestamps = beatTimestamps,
            downbeatTimestamps = emptySet()
        )
        _uiState.update { it.copy(beatGrid = newGrid, showBpmAdjustDialog = false, isLoading = true, statusMessage = "Updating grid...") }

        viewModelScope.launch(Dispatchers.Default) {
            // Downbeat offset isn't persisted from the original detection, but it can be re-scored
            // cheaply from the raw PCM against the new beat timestamps instead of just guessing
            // offset 0 - see BpmBeatDetector.recomputeDownbeats.
            val downbeats = bpmDetector.recomputeDownbeats(track.leftChannel, track.sampleRate, beatTimestamps)
            val gridWithDownbeats = newGrid.copy(downbeatTimestamps = downbeats)
            _uiState.update { it.copy(beatGrid = gridWithDownbeats) }

            val clips = advancedAnalyzer.generateInstrumentClips(
                pcmSamples = track.leftChannel,
                sampleRate = track.sampleRate,
                durationMs = track.durationMs,
                beatGrid = gridWithDownbeats,
                config = state.filterConfig,
                mlStems = lastStemResult,
                mode = state.generationMode
            )
            _uiState.update { it.copy(hapticClips = mergeWithManualClips(clips, it.hapticClips), isLoading = false, statusMessage = null) }
        }
    }

    fun selectClip(clipId: Long?) {
        _uiState.update { it.copy(selectedClipId = clipId) }
    }

    fun moveClip(clipId: Long, newStartMs: Long) {
        _uiState.update { state ->
            val updated = state.hapticClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(startMs = newStartMs)
                } else clip
            }
            state.copy(hapticClips = updated, selectedPreset = AlgorithmPreset.CUSTOM)
        }
    }

    fun trimClip(clipId: Long, newStartMs: Long, newDurationMs: Int) {
        _uiState.update { state ->
            val updated = state.hapticClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(startMs = newStartMs, durationMs = newDurationMs)
                } else clip
            }
            state.copy(hapticClips = updated, selectedPreset = AlgorithmPreset.CUSTOM)
        }
    }

    fun updateClipType(clipId: Long, newType: HapticPatternType) {
        var updatedClip: HapticClip? = null
        _uiState.update { state ->
            val updated = state.hapticClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(
                        patternType = newType,
                        durationMs = newType.defaultDurationMs
                    ).also { updatedClip = it }
                } else clip
            }
            state.copy(hapticClips = updated, selectedPreset = AlgorithmPreset.CUSTOM)
        }
        updatedClip?.let { previewPlayer.triggerSynthesizedClip(it, _uiState.value.filterConfig) }
    }

    fun updateClipIntensity(clipId: Long, newIntensity: Float) {
        _uiState.update { state ->
            val updated = state.hapticClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(intensity = newIntensity.coerceIn(0.05f, 1.0f))
                } else clip
            }
            state.copy(hapticClips = updated, selectedPreset = AlgorithmPreset.CUSTOM)
        }
    }

    fun updateClipDuration(clipId: Long, newDurationMs: Int) {
        _uiState.update { state ->
            val updated = state.hapticClips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(durationMs = newDurationMs.coerceIn(10, 5000))
                } else clip
            }
            state.copy(hapticClips = updated, selectedPreset = AlgorithmPreset.CUSTOM)
        }
    }

    fun updateClipBraking(clipId: Long, braking: Boolean) {
        _uiState.update { state ->
            val updated = state.hapticClips.map { clip ->
                if (clip.id == clipId) clip.copy(activeBraking = braking) else clip
            }
            state.copy(hapticClips = updated, selectedPreset = AlgorithmPreset.CUSTOM)
        }
    }

    fun duplicateClip(clipId: Long) {
        val clip = _uiState.value.hapticClips.find { it.id == clipId } ?: return
        val newClip = clip.copy(
            id = System.nanoTime(),
            startMs = (clip.endMs + 30).coerceAtMost(_uiState.value.audioData?.durationMs ?: (clip.endMs + 30)),
            isAutoGenerated = false
        )
        _uiState.update { state ->
            state.copy(
                hapticClips = (state.hapticClips + newClip).sortedBy { it.startMs },
                selectedClipId = newClip.id,
                selectedPreset = AlgorithmPreset.CUSTOM
            )
        }
        previewPlayer.triggerSynthesizedClip(clip, _uiState.value.filterConfig)
    }

    fun deleteClip(clipId: Long) {
        _uiState.update { state ->
            state.copy(
                hapticClips = state.hapticClips.filterNot { it.id == clipId },
                selectedClipId = if (state.selectedClipId == clipId) null else state.selectedClipId,
                selectedPreset = AlgorithmPreset.CUSTOM
            )
        }
    }

    /** Re-running detection (sensitivity change, ML enhance, mode switch) must never discard
     * clips the user placed by hand - only the auto-generated portion gets replaced. Without this,
     * switching generation mode away from Manual and back wiped every manually-placed clip, since
     * regeneration always overwrote the whole list with a fresh (and for Manual mode, empty) one. */
    private fun mergeWithManualClips(freshAutoClips: List<HapticClip>, previousClips: List<HapticClip>): List<HapticClip> {
        val manualClips = previousClips.filterNot { it.isAutoGenerated }
        return (freshAutoClips + manualClips).sortedBy { it.startMs }
    }

    private fun instrumentForPattern(pattern: HapticPatternType): HapticInstrument = when (pattern) {
        HapticPatternType.THUMP -> HapticInstrument.KICK
        HapticPatternType.SNARE_HIT -> HapticInstrument.SNARE
        HapticPatternType.SNAP_CLICK -> HapticInstrument.HIHAT
        HapticPatternType.TOM_HIT -> HapticInstrument.TOM
        HapticPatternType.SWELL -> HapticInstrument.CYMBAL
        HapticPatternType.RUMBLE -> HapticInstrument.GROOVE_BASS
        HapticPatternType.DOUBLE_TAP, HapticPatternType.CHIRP -> HapticInstrument.LEAD_CADENCE
        HapticPatternType.BUILDUP, HapticPatternType.DROP -> HapticInstrument.DYNAMIC_ENVELOPE
    }

    fun stampPattern(patternType: HapticPatternType) {
        val state = _uiState.value
        val track = state.audioData ?: return

        var targetTime = state.playbackPositionMs
        if (state.snapToGrid && state.beatGrid.beatTimestamps.isNotEmpty()) {
            val nearestBeat = state.beatGrid.beatTimestamps.minByOrNull { abs(it - targetTime) }
            if (nearestBeat != null && abs(nearestBeat - targetTime) < 120L) {
                targetTime = nearestBeat
            }
        }

        val newClip = HapticClip(
            patternType = patternType,
            startMs = targetTime.coerceIn(0L, track.durationMs - patternType.defaultDurationMs),
            durationMs = patternType.defaultDurationMs,
            intensity = 0.90f,
            activeBraking = true,
            carrierFreqHz = state.filterConfig.resonantFrequencyHz,
            isAutoGenerated = false,
            instrument = instrumentForPattern(patternType)
        )

        _uiState.update {
            it.copy(
                hapticClips = (it.hapticClips + newClip).sortedBy { c -> c.startMs },
                // Deliberately NOT auto-selecting the new clip: selecting any clip collapses the
                // header (including this stamp bar) into an edit-only view, so auto-selecting
                // here meant placing one pattern made it impossible to place a second without
                // first dismissing the clip editor - stamping is meant to be quick and repeatable.
                // Tap the clip on the timeline afterward to fine-tune it, same as any other clip.
                selectedPreset = AlgorithmPreset.CUSTOM
            )
        }
    }

    fun testPatternHaptic(patternType: HapticPatternType) {
        previewPlayer.triggerPatternHaptic(patternType)
    }

    fun testClipHaptic(clipId: Long) {
        val clip = _uiState.value.hapticClips.find { it.id == clipId } ?: return
        previewPlayer.triggerSynthesizedClip(clip, _uiState.value.filterConfig)
    }

    /**
     * Resonant frequency / gain / blend only affect synthesis (applied fresh at preview/export
     * time from `state.filterConfig`), not clip generation - so this just updates state. It used
     * to re-run the full analysis pipeline per call, which on a slider (firing dozens of times a
     * second while dragging) stacked up concurrent multi-second HPSS passes and OOM-crashed.
     */
    fun updateFilterConfig(newConfig: FilterConfig) {
        _uiState.update { it.copy(filterConfig = newConfig) }
    }

    private fun stemPcm(stems: StemSeparationResult, stemName: String): FloatArray? = when (stemName) {
        "drums" -> stems.drums
        "bass" -> stems.bass
        "other" -> stems.other
        "vocals" -> stems.vocals
        else -> null
    }

    // StemSeparator always resamples to a fixed MODEL_SAMPLE_RATE (44.1kHz - the ONNX model's
    // required input rate) before separating, and its output stems stay at that rate - they are
    // NOT at the original track's sample rate. Every consumer of a stem array (audio playback,
    // the continuous haptic envelope) needs it at the TRACK's rate to stay aligned with the real
    // audio timeline; every previous call site instead passed the stem array straight through
    // labeled as if it were already at track.sampleRate. For a 44.1kHz source (the common case,
    // and what this app's own test tracks happen to be) that's a no-op and invisible. For anything
    // else (48kHz is a very common Android decode rate) the mismatch compounds over the length of
    // the track - e.g. at 48kHz the code would treat 48000 stem samples as "1 second" when they
    // actually span 48000/44100 ≈ 1.088s of real audio, so a stem-driven haptic layer (or an
    // isolated-stem audition) drifts further out of sync with the real track the longer playback
    // runs, exactly the "feels async" symptom - not a rounding error, a genuine wrong-speed bug.
    // Single-slot cache since only one stem is ever in play at a time; re-resampling on every
    // stem/track switch is fine, this just avoids redoing it on every play/pause/seek tick.
    private var resampledStemSource: StemSeparationResult? = null
    private var resampledStemName: String? = null
    private var resampledStemRate: Int = -1
    private var resampledStemPcm: FloatArray? = null

    private fun stemPcmAtRate(stems: StemSeparationResult, stemName: String, targetSampleRate: Int): FloatArray? {
        if (resampledStemSource === stems && resampledStemName == stemName && resampledStemRate == targetSampleRate) {
            return resampledStemPcm
        }
        val raw = stemPcm(stems, stemName) ?: return null
        val resampled = if (targetSampleRate == com.ringtonehaptics.app.domain.ml.MODEL_SAMPLE_RATE) {
            raw
        } else {
            com.ringtonehaptics.app.domain.ml.Resampler.resample(raw, com.ringtonehaptics.app.domain.ml.MODEL_SAMPLE_RATE, targetSampleRate)
        }
        resampledStemSource = stems
        resampledStemName = stemName
        resampledStemRate = targetSampleRate
        resampledStemPcm = resampled
        return resampled
    }

    /** The original mix, or a single isolated stem swapped into both channels when one is selected.
     *
     * Stem Pulse is the one exception: there, the stem selection steers HAPTICS only (see
     * [effectiveContinuousEnvelopeFor]) - the user still wants to hear the full mix while feeling
     * the isolated stem, not audition the stem in isolation the way the other modes' stem picker
     * does. So this always returns the full mix in that mode regardless of selectedAudioStem.
     */
    private fun effectivePlaybackTrack(state: EditorUiState): AudioTrackData? {
        val track = state.audioData ?: return null
        if (state.generationMode == HapticGenerationMode.STEM_PULSE) return track
        val stemName = state.selectedAudioStem ?: return track
        val stems = lastStemResult ?: return track
        val pcm = stemPcmAtRate(stems, stemName, track.sampleRate) ?: return track
        return track.copy(leftChannel = pcm, rightChannel = pcm)
    }

    /**
     * The continuous background envelope fed into export synthesis. Same GROOVE_BASS mute/solo
     * gate as before for every mode (see the old inline comment this replaced) - Stem Pulse's
     * envelope represents the same physical thing (a continuous background layer, not a discrete
     * clip), so it rides the same gate rather than inventing a second, parallel mute mechanism the
     * mute/solo UI has no clip-driven affordance to show anyway (InstrumentFilterBar only lists
     * instruments actually present in hapticClips, and Stem Pulse deliberately produces none).
     *
     * In STEM_PULSE mode this is re-derived from the selected stem's own PCM through the same
     * EnvelopeFollower used for the full-mix envelope, via [stemPcmAtRate] which resamples the
     * stem (natively at StemSeparator's fixed model rate) to the track's real sample rate first -
     * without that, the envelope's index-to-time mapping silently drifts from the actual audio
     * timeline (see stemPcmAtRate's doc comment), which is exactly what made a stem-driven haptic
     * layer feel like it was going out of sync with the track the longer playback ran.
     * Falls back to the full-mix envelope (not silence) when ML separation hasn't run yet or no
     * stem is chosen - the mode still produces something instead of a dead export.
     *
     * Also the single source live playback (togglePlayPause/seekTo) uses to drive the vibrator
     * continuously in real time - see [continuousEnvelopeForLivePlayback]. That call site runs
     * synchronously on the main thread (matching the rest of the playback-control functions in
     * this file), so the per-stem analysis below is memoized by (stemName, filterConfig): normal
     * play/pause/seek only ever re-hit the cache, and the one-time EnvelopeFollower.analyze cost
     * only recurs when the user actually picks a different stem or changes a filter slider.
     */
    private var stemEnvelopeCacheKey: Pair<String, FilterConfig>? = null
    private var stemEnvelopeCacheValue: FloatArray = FloatArray(0)

    // Stem Pulse's "drums" case is NOT the raw-envelope path above - see buildDrumOnsetEnvelope's
    // doc comment for why. Cache key is (selected classes, sample count) rather than FilterConfig:
    // the onset-pulse shape doesn't read cutoff/attack/decay/threshold at all (those are all
    // EnvelopeFollower-specific), only Punch (folded in separately below since it affects pulse
    // decay tau, not gating).
    private var drumOnsetEnvelopeCacheKey: Pair<Set<DrumClass>, Int>? = null
    private var drumOnsetEnvelopeCacheValue: FloatArray = FloatArray(0)

    /** Empty [EditorUiState.selectedDrumClasses] means "all classes" - see that field's doc comment. */
    private fun effectiveDrumClasses(state: EditorUiState): Set<DrumClass> =
        state.selectedDrumClasses.ifEmpty { DrumClass.entries.toSet() }

    private fun effectiveContinuousEnvelopeFor(state: EditorUiState, track: AudioTrackData): FloatArray {
        val grooveBassSilenced = HapticInstrument.GROOVE_BASS in state.mutedInstruments ||
            (state.soloedInstrument != null && state.soloedInstrument != HapticInstrument.GROOVE_BASS)
        if (grooveBassSilenced) return FloatArray(0)

        if (state.generationMode == HapticGenerationMode.STEM_PULSE) {
            val stemName = state.selectedAudioStem

            // "drums" is deliberately NOT routed through EnvelopeFollower at all (see
            // buildDrumOnsetEnvelope doc comment - both the 140Hz bass-tuned lowpass and the
            // attack/decay follower itself measurably misrepresent real drum hits). Runs on the
            // full mix (drumTranscriber.transcribe's own docs: no separation needed for this
            // model), so unlike the branch below it doesn't require lastStemResult/stemPcmAtRate at
            // all - only that the user has picked "drums" as the active stem.
            if (stemName == "drums") {
                val classes = effectiveDrumClasses(state)
                val cacheKey = classes to track.leftChannel.size
                if (drumOnsetEnvelopeCacheKey != cacheKey) {
                    val onsets = transcribeDrumsCached(track.leftChannel, track.sampleRate)
                    // Punch still means something here: it sharpens the pulse decay itself (shorter
                    // tau = snappier) rather than gating/expanding an amplitude-follower curve that
                    // no longer exists in this path.
                    val profile = stemHapticProfileFor("drums")
                    val punchAmount = combinedTransientAmount(profile.transientEmphasisBase, state.filterConfig.transientEmphasis)
                    drumOnsetEnvelopeCacheValue = buildDrumOnsetEnvelope(
                        onsets, classes, track.leftChannel.size, track.sampleRate, punchAmount
                    )
                    drumOnsetEnvelopeCacheKey = cacheKey
                }
                return drumOnsetEnvelopeCacheValue
            }

            val stems = lastStemResult
            val stemPcm = if (stems != null && stemName != null) stemPcmAtRate(stems, stemName, track.sampleRate) else null
            if (stemPcm != null && stemName != null) {
                val cacheKey: Pair<String, FilterConfig> = stemName to state.filterConfig
                if (stemEnvelopeCacheKey != cacheKey) {
                    val rawStemEnvelope = envelopeFollower.analyze(stemPcm, track.sampleRate, state.filterConfig).envelope
                    // Per-stem character (see StemHapticProfile) - a stem-tuned smoothing pass
                    // (no-op for every stem except bass) rounds the shape off BEFORE transient
                    // emphasis sharpens contrast, so the two don't fight each other on stems that
                    // want both (they currently don't, but order still matters if that changes).
                    val profile = stemHapticProfileFor(stemName)
                    val smoothedStemEnvelope = envelopeFollower.applySustainSmoothing(
                        rawStemEnvelope, track.sampleRate, profile.sustainSmoothingMs
                    )
                    // Punch/transient-emphasis only ever applies here - it's Stem Pulse-specific
                    // (see FilterConfig.transientEmphasis) and this is the one call site that feeds
                    // Stem Pulse's envelope, never the full-mix rawContinuousEnvelope other modes use.
                    // combinedTransientAmount layers the user's Punch slider on top of the stem's own
                    // default (drums spiky, bass smooth by default) as an override, not a replacement.
                    val amount = combinedTransientAmount(profile.transientEmphasisBase, state.filterConfig.transientEmphasis)
                    stemEnvelopeCacheValue = envelopeFollower.applyTransientEmphasis(smoothedStemEnvelope, amount)
                    stemEnvelopeCacheKey = cacheKey
                }
                return stemEnvelopeCacheValue
            }
        }
        return rawContinuousEnvelope
    }

    /**
     * Stem Pulse's "drums" haptic source, built directly from DrumTranscriber's per-class onset
     * timestamps instead of amplitude-following the separated drum stem's waveform. Two real,
     * separately-confirmed problems with the EnvelopeFollower path this replaces:
     *
     * 1. EnvelopeFollower.analyze() runs FilterConfig's Biquad (LOWPASS @ ~140Hz by default) before
     *    anything else - tuned for isolating BASS rhythm (see that filter's own call-site comment),
     *    but applied unconditionally to whatever stem is selected. A drums stem's hihat/cymbal/snare
     *    transient energy lives almost entirely above 140Hz, so that filter was discarding most of
     *    what actually makes a drum stem "busy" before the envelope follower ever saw it - a dense,
     *    consistently-loud hihat pattern collapses to a sparse, kick-only trickle downstream. This
     *    is the root cause behind a GarageBand export comparison where the isolated Drum Stem track
     *    showed dense, consistently-loud transients throughout, but the exported Haptic Layer
     *    channel only produced a couple of tall clusters and stayed flat everywhere else.
     *
     * 2. Independent of the filter, the attack/decay follower itself conflates hit DENSITY with hit
     *    INTENSITY. Verified numerically (same attackMs=8/decayMs=60 coefficients as FilterConfig's
     *    defaults, sampleRate=44100): five isolated same-amplitude "hits" 400ms apart each settle at
     *    envelope peak ~0.466, while the same-amplitude hits packed 60ms apart (a plausible hihat
     *    pattern) ratchet the envelope up to ~0.592 by the third hit - 27% higher purely because the
     *    decay coefficient never fully resets between hits, not because any hit was actually louder.
     *    Global peak-normalization then treats whatever the busiest cluster reached as "1.0" and
     *    scores every isolated hit against that inflated ceiling, and applyTransientEmphasis's power
     *    curve (correctly, by its own design - see that function's doc comment) pushes anything
     *    below peak toward zero even harder. Net effect: hard, isolated hits read as quiet; busy
     *    clusters (even of equal or lesser true hits) read as loud - the "high peaks have no haptic
     *    impact, low/dense peaks do" symptom, and not fixable by retuning attack/decay constants,
     *    since a generic amplitude follower is structurally the wrong tool for a dense onset stream.
     *
     * DrumOnset.intensity (the ADTOF model's own per-hit sigmoid confidence, computed from the full
     * unfiltered mix - see DrumTranscriber) sidesteps both problems: it's a genuine per-hit
     * measurement, not an accumulated/filtered proxy for one. Each onset becomes an independent,
     * precisely-timed pulse using THUMP's proven fast-attack/tau-decay shape (see
     * AdvancedHapticSynthesizer.synthesizeChannel2's THUMP case - established in this codebase as
     * "feels like an instant hit" for discrete clips) applied to the continuous envelope amplitude
     * instead of raw PCM, so it still rides Stem Pulse's carrier/blend/gain pipeline unchanged.
     * Overlapping pulses (e.g. kick+hihat on the same frame) max-combine rather than sum, so a
     * cluster of simultaneous onsets can't accumulate past 1.0 the way the old follower did.
     */
    private fun buildDrumOnsetEnvelope(
        onsetsByClass: Map<DrumClass, List<DrumOnset>>,
        classes: Set<DrumClass>,
        totalSamples: Int,
        sampleRate: Int,
        punchAmount: Float
    ): FloatArray {
        val envelope = FloatArray(totalSamples)
        // Punch shortens the decay tau (snappier) rather than reshaping amplitude - up to 40%
        // shorter at punchAmount=1, floor left generous enough that a hit still reads as a real
        // pulse (not an unfeelable click) at max Punch.
        val tau = 0.012f * (1.0f - 0.4f * punchAmount.coerceIn(0f, 1f))
        val attackSamples = (sampleRate * 0.001f).toInt().coerceAtLeast(1) // ~1ms linear ramp, click-free
        val decayWindowSamples = (sampleRate * tau * 6f).toInt().coerceAtLeast(attackSamples + 1) // ~6 tau ~= fully decayed
        var onsetCount = 0
        for (drumClass in classes) {
            val onsets = onsetsByClass[drumClass] ?: continue
            for (onset in onsets) {
                val startSample = ((onset.timestampMs * sampleRate) / 1000L).toInt()
                if (startSample < 0 || startSample >= totalSamples) continue
                val amp = onset.intensity.coerceIn(0.3f, 1.0f)
                for (s in 0 until decayWindowSamples) {
                    val idx = startSample + s
                    if (idx >= totalSamples) break
                    val value = if (s < attackSamples) {
                        amp * (s.toFloat() / attackSamples)
                    } else {
                        val t = (s - attackSamples).toFloat() / sampleRate
                        amp * kotlin.math.exp(-t / tau)
                    }
                    if (value > envelope[idx]) envelope[idx] = value // max-combine, never sum
                }
                onsetCount++
            }
        }
        Log.i(TAG, "buildDrumOnsetEnvelope: $onsetCount onsets across $classes driving Stem Pulse (tau=${tau * 1000}ms)")
        return envelope
    }

    /** Continuous-blend carrier frequency for [AdvancedHapticSynthesizer.synthesizeChannel2] - the
     * stem's tuned ratio (see StemHapticProfile) times the user's resonance setting for Stem Pulse
     * with a stem selected, or unchanged resonance (the old, single-carrier behavior) otherwise. */
    private fun continuousCarrierFreqHzFor(state: EditorUiState): Float {
        if (state.generationMode != HapticGenerationMode.STEM_PULSE) return state.filterConfig.resonantFrequencyHz
        val profile = stemHapticProfileFor(state.selectedAudioStem)
        return state.filterConfig.resonantFrequencyHz * profile.carrierFreqRatio
    }

    /** Continuous envelope to feed live playback's vibrator, or empty for every mode besides Stem
     * Pulse - the other 4 modes never drove continuous haptics live before this and aren't meant
     * to start now, only their per-clip triggers (see HapticPreviewPlayer.startClipsPreview). */
    private fun continuousEnvelopeForLivePlayback(state: EditorUiState, track: AudioTrackData): FloatArray =
        if (state.generationMode == HapticGenerationMode.STEM_PULSE) effectiveContinuousEnvelopeFor(state, track) else FloatArray(0)

    fun togglePlayPause() {
        val state = _uiState.value
        val track = state.audioData ?: return
        val playbackTrack = effectivePlaybackTrack(state) ?: return

        if (state.isPlaying) {
            previewPlayer.stop()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            val activeClips = if (state.liveHapticsEnabled) state.audibleClips else emptyList()
            val continuousEnvelope = if (state.liveHapticsEnabled) continuousEnvelopeForLivePlayback(state, track) else FloatArray(0)
            previewPlayer.startClipsPreview(
                audioData = playbackTrack,
                clips = activeClips,
                continuousEnvelope = continuousEnvelope,
                config = state.filterConfig,
                startPositionMs = state.playbackPositionMs,
                onProgress = { curMs ->
                    _uiState.update { it.copy(playbackPositionMs = curMs) }
                },
                onCompletion = {
                    _uiState.update { it.copy(isPlaying = false, playbackPositionMs = 0L) }
                }
            )
            _uiState.update { it.copy(isPlaying = true) }
        }
    }

    fun seekTo(positionMs: Long) {
        val track = _uiState.value.audioData ?: return
        val clamped = positionMs.coerceIn(0L, track.durationMs)
        _uiState.update { it.copy(playbackPositionMs = clamped) }

        if (_uiState.value.isPlaying) {
            previewPlayer.stop()
            val state = _uiState.value
            val playbackTrack = effectivePlaybackTrack(state) ?: return
            val activeClips = if (state.liveHapticsEnabled) state.audibleClips else emptyList()
            val continuousEnvelope = if (state.liveHapticsEnabled) continuousEnvelopeForLivePlayback(state, track) else FloatArray(0)
            previewPlayer.startClipsPreview(
                audioData = playbackTrack,
                clips = activeClips,
                continuousEnvelope = continuousEnvelope,
                config = state.filterConfig,
                startPositionMs = clamped,
                onProgress = { curMs ->
                    _uiState.update { it.copy(playbackPositionMs = curMs) }
                },
                onCompletion = {
                    _uiState.update { it.copy(isPlaying = false, playbackPositionMs = 0L) }
                }
            )
        }
    }

    fun rewind() {
        seekTo(0L)
    }

    /** Returns to the launch screen (Recent Projects list) without deleting anything - lets the
     * system back gesture do something sensible instead of exiting the app outright. */
    fun closeProject() {
        previewPlayer.stop()
        pendingSourceUri = null
        lastStemResult = null
        rawContinuousEnvelope = FloatArray(0)
        _uiState.update {
            EditorUiState(
                canWriteSettings = it.canWriteSettings,
                mlModelReady = it.mlModelReady,
                useMlByDefault = it.useMlByDefault,
                projects = projectRepository.listProjects()
            )
        }
    }

    /** Which stem you hear during playback (null = the full mix). Switches source live if playing. */
    fun selectAudioStem(stemName: String?) {
        _uiState.update { it.copy(selectedAudioStem = stemName) }
        if (_uiState.value.isPlaying) {
            seekTo(_uiState.value.playbackPositionMs)
        }
    }

    /** Multi-select toggle for which drum class(es) drive Stem Pulse's onset-pulse envelope when
     * "drums" is the active stem (see buildDrumOnsetEnvelope) - only meaningful in that combination,
     * but harmless to call/store otherwise. Re-selecting every class collapses back to the empty
     * "all classes" representation rather than an explicit full set, keeping selectAudioStem's
     * null-means-everything convention consistent and the persisted JSON minimal. */
    fun toggleDrumClass(drumClass: DrumClass) {
        _uiState.update { state ->
            val current = effectiveDrumClasses(state)
            val next = if (drumClass in current) current - drumClass else current + drumClass
            val normalized = if (next.size == DrumClass.entries.size) emptySet() else next
            state.copy(selectedDrumClasses = normalized)
        }
        if (_uiState.value.isPlaying) {
            seekTo(_uiState.value.playbackPositionMs)
        }
    }

    fun toggleLiveHaptics() {
        _uiState.update { it.copy(liveHapticsEnabled = !it.liveHapticsEnabled) }
        if (_uiState.value.isPlaying) {
            seekTo(_uiState.value.playbackPositionMs)
        }
    }

    fun setFilterSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showFilterSheet = visible) }
    }

    fun setExportDialogVisible(visible: Boolean) {
        if (!visible) {
            previewPlayer.stop()
            exportPreviewFile?.delete()
            exportPreviewFile = null
            exportPreviewName = null
            _uiState.update {
                it.copy(
                    showExportDialog = false,
                    exportPreviewReady = false,
                    isPreviewPlaying = false,
                    pendingRingtoneOverwrite = null,
                    // Root cause of "export dialog gets stuck" - exportedUri was never cleared once
                    // a save completed, and ExportDialog's branching checks `exportedUri != null`
                    // BEFORE `exportPreviewReady`, so every re-open of the dialog for the rest of the
                    // project session re-showed the old "Ringtone Ready!" screen (Done/Test-only,
                    // no Preview button) regardless of what stem/mode/settings the user changed
                    // afterward - there was no way back to the preview form short of leaving the
                    // project entirely (closeProject() rebuilds EditorUiState from scratch).
                    exportedUri = null
                )
            }
        } else {
            _uiState.update { it.copy(showExportDialog = true) }
        }
    }

    /** Synthesizes + encodes the ringtone into an app-private file and plays it back through the
     * real audio-coupled-haptics path (MediaPlayer + unmuted haptic channel) - lets the user feel
     * exactly what will be saved before anything touches their actual Ringtones library. */
    fun renderExportPreview(ringtoneName: String) {
        val state = _uiState.value
        val track = state.audioData ?: return

        viewModelScope.launch {
            previewPlayer.stop()
            exportPreviewFile?.delete()
            _uiState.update {
                it.copy(
                    isRenderingPreview = true,
                    exportPreviewReady = false,
                    statusMessage = "Synthesizing LRA physical haptic waveforms with Active Braking..."
                )
            }

            val effectiveContinuousEnvelope = withContext(Dispatchers.Default) { effectiveContinuousEnvelopeFor(state, track) }

            val hapticPcm = withContext(Dispatchers.Default) {
                advancedSynthesizer.synthesizeChannel2(
                    totalSamples = track.totalSamples,
                    sampleRate = track.sampleRate,
                    clips = state.audibleClips,
                    continuousEnvelope = effectiveContinuousEnvelope,
                    config = state.filterConfig,
                    continuousCarrierFreqHz = continuousCarrierFreqHzFor(state)
                )
            }

            _uiState.update { it.copy(statusMessage = "Encoding 3-channel OGG Vorbis with ANDROID_HAPTIC=1...") }
            val renderResult = mediaStoreRepo.renderRingtonePreview(
                audioData = track,
                hapticPcm = hapticPcm,
                ringtoneName = ringtoneName
            )

            renderResult.onSuccess { file ->
                exportPreviewFile = file
                exportPreviewName = ringtoneName
                _uiState.update { it.copy(isRenderingPreview = false, exportPreviewReady = true, statusMessage = null) }
                playExportPreview()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isRenderingPreview = false, statusMessage = "Preview failed: ${error.localizedMessage}")
                }
            }
        }
    }

    fun playExportPreview() {
        val file = exportPreviewFile ?: return
        previewPlayer.startCoupledOggPreview(
            uri = Uri.fromFile(file),
            onProgress = { curMs ->
                _uiState.update { it.copy(playbackPositionMs = curMs) }
            },
            onCompletion = {
                _uiState.update { it.copy(isPreviewPlaying = false, playbackPositionMs = 0L) }
            }
        )
        _uiState.update { it.copy(isPreviewPlaying = true) }
    }

    fun stopExportPreview() {
        previewPlayer.stop()
        _uiState.update { it.copy(isPreviewPlaying = false) }
    }

    /** The actual "commit to the Ringtones library" step, only reachable after a preview has been
     * rendered - checks for a same-named existing ringtone first and asks before clobbering it. */
    fun saveExportPreview(ringtoneName: String, setAsActive: Boolean) {
        val file = exportPreviewFile ?: return
        viewModelScope.launch {
            val existing = mediaStoreRepo.findExistingRingtone(ringtoneName)
            if (existing != null) {
                pendingSaveName = ringtoneName
                pendingSaveSetAsActive = setAsActive
                _uiState.update { it.copy(pendingRingtoneOverwrite = existing) }
                return@launch
            }
            commitExportPreview(file, ringtoneName, setAsActive, overwriteUri = null)
        }
    }

    fun confirmOverwriteRingtone() {
        val file = exportPreviewFile ?: return
        val overwriteUri = _uiState.value.pendingRingtoneOverwrite ?: return
        val name = pendingSaveName ?: return
        _uiState.update { it.copy(pendingRingtoneOverwrite = null) }
        viewModelScope.launch {
            commitExportPreview(file, name, pendingSaveSetAsActive, overwriteUri)
        }
    }

    fun dismissOverwriteConfirm() {
        _uiState.update { it.copy(pendingRingtoneOverwrite = null) }
    }

    private var pendingSaveName: String? = null
    private var pendingSaveSetAsActive: Boolean = false

    private suspend fun commitExportPreview(file: java.io.File, ringtoneName: String, setAsActive: Boolean, overwriteUri: Uri?) {
        _uiState.update { it.copy(isExporting = true, statusMessage = "Saving to Pixel Sounds...") }
        val result = mediaStoreRepo.commitRingtoneToLibrary(
            previewFile = file,
            ringtoneName = ringtoneName,
            overwriteUri = overwriteUri
        )
        result.onSuccess { uri ->
            if (setAsActive) {
                mediaStoreRepo.setAsActiveRingtone(uri)
            }
            previewPlayer.stop()
            exportPreviewFile?.delete()
            exportPreviewFile = null
            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportPreviewReady = false,
                    isPreviewPlaying = false,
                    exportedUri = uri,
                    statusMessage = "Ringtone successfully exported to Pixel Sounds!"
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(isExporting = false, statusMessage = "Export failed: ${error.localizedMessage}")
            }
        }
    }

    fun playExportedRingtonePreview() {
        val uri = _uiState.value.exportedUri ?: return
        previewPlayer.startCoupledOggPreview(
            uri = uri,
            onProgress = { curMs ->
                _uiState.update { it.copy(playbackPositionMs = curMs) }
            },
            onCompletion = {
                _uiState.update { it.copy(isPlaying = false, playbackPositionMs = 0L) }
            }
        )
        _uiState.update { it.copy(isPlaying = true) }
    }

    fun setSaveProjectDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showSaveProjectDialog = visible) }
    }

    /** Entry point from the Save dialog - if saving as a NEW project under a name that already
     * matches an existing one, asks to replace it instead of silently creating a duplicate. */
    fun saveCurrentProject(name: String) {
        val state = _uiState.value
        if (state.currentProjectId == null) {
            val existing = state.projects.find { it.name.equals(name, ignoreCase = true) }
            if (existing != null) {
                _uiState.update {
                    it.copy(showSaveProjectDialog = false, pendingReplaceProject = existing.id to name)
                }
                return
            }
        }
        performSaveProject(id = state.currentProjectId, name = name)
    }

    fun confirmReplaceProject() {
        val (id, name) = _uiState.value.pendingReplaceProject ?: return
        performSaveProject(id = id, name = name)
    }

    fun dismissReplaceProjectConfirm() {
        _uiState.update { it.copy(pendingReplaceProject = null) }
    }

    private fun performSaveProject(id: String?, name: String) {
        val state = _uiState.value
        val track = state.audioData ?: return
        val sourceUri = pendingSourceUri ?: return

        val savedId = projectRepository.saveProject(
            id = id,
            name = name,
            sourceUri = sourceUri.toString(),
            durationMs = track.durationMs,
            sampleRate = track.sampleRate,
            hapticClips = state.hapticClips,
            filterConfig = state.filterConfig,
            beatGrid = state.beatGrid,
            selectedAudioStem = state.selectedAudioStem,
            selectedDrumClasses = state.selectedDrumClasses,
            wasMlEnhanced = state.stemWaveforms != null
        )

        _uiState.update {
            it.copy(
                currentProjectId = savedId,
                currentProjectName = name,
                showSaveProjectDialog = false,
                pendingReplaceProject = null,
                projects = projectRepository.listProjects(),
                statusMessage = "Project saved"
            )
        }
        // Unlike every other statusMessage in this file, this one was never cleared - it stuck
        // around until something else happened to overwrite it (reopening the project, which
        // clears it via openProject(), was the only way out - looked like the save screen was
        // permanently stuck showing "just saved"). Auto-dismiss after a couple seconds like the
        // banner is clearly meant to; guarded so a newer message that appeared meanwhile isn't
        // clobbered.
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.update { if (it.statusMessage == "Project saved") it.copy(statusMessage = null) else it }
        }

        // Cache the ML stem PCM alongside the project so reopening it restores separation
        // instantly instead of re-running the multi-minute ONNX pass - off the calling thread,
        // this can be tens of MB of disk I/O. lastStemResult only reflects the CURRENT track (see
        // its own caching-by-array-identity in transcribeDrumsCached), so this is always correct
        // for whatever track is actually open right now.
        lastStemResult?.let { stems ->
            viewModelScope.launch(Dispatchers.IO) { projectRepository.saveStems(savedId, stems) }
        }
    }

    fun deleteProject(id: String) {
        projectRepository.deleteProject(id)
        _uiState.update { it.copy(projects = projectRepository.listProjects()) }
    }

    /** Reopens a saved project - re-decodes audio from its stored URI but skips re-running the
     * (possibly multi-minute) analysis pipeline entirely, since the clips are already saved. */
    fun openProject(id: String) {
        val project = projectRepository.loadProject(id) ?: return
        val uri = Uri.parse(project.sourceUri)
        pendingSourceUri = uri

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Reopening project...") }
            previewPlayer.stop()

            val result = AudioDecoder.decodeAudio(getApplication(), uri)
            result.onSuccess { track ->
                withContext(Dispatchers.Default) {
                    val envResult = envelopeFollower.analyze(track.leftChannel, track.sampleRate, project.filterConfig)
                    rawContinuousEnvelope = envResult.envelope
                    stemEnvelopeCacheKey = null
                    drumOnsetEnvelopeCacheKey = null
                }
                _uiState.update {
                    it.copy(
                        audioData = track,
                        beatGrid = project.beatGrid,
                        hapticClips = project.hapticClips,
                        filterConfig = project.filterConfig,
                        selectedAudioStem = project.selectedAudioStem,
                        selectedDrumClasses = project.selectedDrumClasses,
                        selectedPreset = AlgorithmPreset.HAPTIC_INSTRUMENTS,
                        currentProjectId = project.id,
                        currentProjectName = project.name,
                        isLoading = false,
                        statusMessage = null,
                        stemWaveforms = null, // stem preview audio isn't persisted; re-run ML Enhance to get it back
                        // A mute/solo left over from whatever was open before (this session's
                        // previous track, or the project's own state pre-save) would otherwise
                        // keep filtering audibleClips after reopening - the clips are genuinely
                        // there in hapticClips, just invisibly hidden from view/preview/export.
                        mutedInstruments = emptySet(),
                        soloedInstrument = null,
                        selectedClipId = null
                    )
                }
                // Without restoring stems, a project saved after "Enhance with ML" would silently
                // fall back to lower-quality on-device HPSS the moment anything triggered
                // regeneration after reopening (sensitivity change, mode switch, BPM correction) -
                // restoring hapticClips alone isn't enough, lastStemResult backing further edits
                // needs to come back too. Does NOT touch the just-restored clips either way.
                if (project.wasMlEnhanced) {
                    restoreMlStemsAfterReopen(project.id)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Failed to reopen project - the source file may have moved: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun restoreMlStemsAfterReopen(projectId: String) {
        val track = _uiState.value.audioData ?: return

        _uiState.update { it.copy(mlStage = MlStage.SEPARATING, mlProgress = 0f, statusMessage = "Restoring ML separation...") }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Cached from the save that made this project (see performSaveProject) - the
                // whole point of caching is to skip re-running the multi-minute ONNX pass here.
                // Only tracks saved before caching existed (or a cache file that got cleared)
                // fall through to actually re-separating.
                val cached = projectRepository.loadStems(projectId)
                val stems = cached ?: run {
                    if (!modelDownloadManager.isModelReady()) return@run null
                    StemSeparator().separate(
                        modelPath = modelDownloadManager.modelPath(),
                        leftChannel = track.leftChannel,
                        rightChannel = track.rightChannel,
                        sampleRate = track.sampleRate,
                        onProgress = { progress -> _uiState.update { it.copy(mlProgress = progress) } }
                    ).also { projectRepository.saveStems(projectId, it) }
                }
                if (stems == null) {
                    _uiState.update { it.copy(mlStage = MlStage.IDLE, statusMessage = null) }
                    return@launch
                }
                lastStemResult = stems
                val waveforms = mapOf(
                    "drums" to decimateWaveform(stems.drums),
                    "bass" to decimateWaveform(stems.bass),
                    "other" to decimateWaveform(stems.other),
                    "vocals" to decimateWaveform(stems.vocals)
                )
                _uiState.update { it.copy(mlStage = MlStage.IDLE, stemWaveforms = waveforms, statusMessage = null) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mlStage = MlStage.IDLE,
                        statusMessage = "Could not restore ML separation: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    // Matches AudioDecoder's overview resolution reasoning - enough points that zooming into the
    // timeline reveals real stem detail instead of just stretching a handful of bars wider.
    private fun decimateWaveform(pcm: FloatArray, points: Int = 2000): FloatArray {
        if (pcm.isEmpty()) return FloatArray(points)
        val out = FloatArray(points)
        val step = pcm.size.toFloat() / points
        for (i in 0 until points) {
            val start = (i * step).toInt().coerceIn(0, pcm.size - 1)
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, pcm.size)
            var peak = 0.0f
            for (s in start until end) {
                val a = abs(pcm[s])
                if (a > peak) peak = a
            }
            out[i] = peak.coerceIn(0.0f, 1.0f)
        }
        return out
    }

    override fun onCleared() {
        super.onCleared()
        previewPlayer.stop()
    }
}
