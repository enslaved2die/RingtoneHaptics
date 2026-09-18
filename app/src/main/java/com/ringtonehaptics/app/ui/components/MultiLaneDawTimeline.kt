package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ringtonehaptics.app.domain.model.BeatGridInfo
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.ui.theme.WaveformAudioBar
import kotlin.math.abs
import kotlin.math.max

enum class DragMode {
    MOVE,
    TRIM_START,
    TRIM_END
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 20f

/** pixels-per-ms at the current zoom, for a canvas of [widthPx]. */
private fun pxPerMs(widthPx: Float, zoom: Float, durationMs: Long): Float {
    if (durationMs <= 0) return 0f
    return (widthPx * zoom) / durationMs
}

private fun msToX(ms: Long, widthPx: Float, zoom: Float, panMs: Long, durationMs: Long): Float {
    val ppm = pxPerMs(widthPx, zoom, durationMs)
    return (ms - panMs) * ppm
}

private fun xToMs(x: Float, widthPx: Float, zoom: Float, panMs: Long, durationMs: Long): Long {
    val ppm = pxPerMs(widthPx, zoom, durationMs)
    if (ppm <= 0f) return panMs
    return panMs + (x / ppm).toLong()
}

private fun maxPanMs(zoom: Float, durationMs: Long): Long {
    val visibleMs = (durationMs / zoom).toLong()
    return (durationMs - visibleMs).coerceAtLeast(0L)
}

private class BarPlan(val startIdx: Int, val endIdx: Int, val stride: Float, val barWidthPx: Float)

/**
 * Picks which of a fixed-resolution waveform array's points to draw for the current zoom/pan,
 * with a fixed on-screen bar width/slot - NOT scaled by zoom. Zooming in reveals more of the
 * real underlying points (up to the array's own resolution ceiling) rather than stretching the
 * same few points into fatter bars, which read as "more zoomed in" without showing any more
 * actual detail.
 */
private fun computeBarPlan(totalPoints: Int, durationMs: Long, zoom: Float, panMs: Long, widthPx: Float, slotPx: Float): BarPlan? {
    if (totalPoints <= 0 || durationMs <= 0 || widthPx <= 0f) return null
    val visibleMs = (durationMs / zoom).toLong().coerceAtLeast(1L)
    val visibleStartMs = panMs.coerceIn(0L, durationMs)
    val visibleEndMs = (panMs + visibleMs).coerceIn(0L, durationMs)
    val startIdx = ((visibleStartMs.toFloat() / durationMs) * totalPoints).toInt().coerceIn(0, totalPoints - 1)
    val endIdx = ((visibleEndMs.toFloat() / durationMs) * totalPoints).toInt().coerceIn(startIdx + 1, totalPoints)
    val maxBarsOnScreen = (widthPx / slotPx).toInt().coerceAtLeast(1)
    val pointsInWindow = endIdx - startIdx
    val stride = (pointsInWindow.toFloat() / maxBarsOnScreen).coerceAtLeast(1f)
    return BarPlan(startIdx, endIdx, stride, slotPx * 0.65f)
}

@Composable
fun MultiLaneDawTimeline(
    audioOverview: FloatArray,
    stemWaveforms: Map<String, FloatArray>? = null,
    selectedAudioStem: String? = null,
    clips: List<HapticClip>,
    beatGrid: BeatGridInfo,
    durationMs: Long,
    currentPlaybackMs: Long,
    isPlaying: Boolean,
    selectedClipId: Long?,
    snapToGrid: Boolean,
    showBarGrid: Boolean = false,
    showBeatGrid: Boolean = true,
    showNoteGrid: Boolean = false,
    onSeek: (positionMs: Long) -> Unit,
    onClipSelect: (clipId: Long) -> Unit,
    onClipMove: (clipId: Long, newStartMs: Long) -> Unit,
    onClipTrim: (clipId: Long, newStartMs: Long, newDurationMs: Int) -> Unit,
    onEmptyAreaTap: (tapMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    onZoomChange: (Float) -> Unit = {}
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    // Keyed on durationMs so loading a different track resets the view instead of leaving the
    // previous track's zoom/scroll position stuck on the new one.
    var zoom by remember(durationMs) { mutableFloatStateOf(MIN_ZOOM) }
    var panMs by remember(durationMs) { mutableLongStateOf(0L) }
    var isGesturing by remember { mutableStateOf(false) }
    var canvasWidthPx by remember { mutableFloatStateOf(0f) }

    // The drag/tap gesture handlers below read these every frame of a potentially long-running
    // gesture (a scrub or clip-drag), and that same gesture is what changes them (onSeek/onClipMove
    // feed straight back into currentPlaybackMs/clips). Keying pointerInput on them directly was
    // the actual playhead-scrub bug: Compose tore down and recreated the gesture-detector coroutine
    // on every such recomposition, which cancels an in-flight drag after its first event. Reading
    // through rememberUpdatedState keeps the closures fresh without ever restarting the coroutine.
    val latestClips by rememberUpdatedState(clips)
    val latestPlaybackMs by rememberUpdatedState(currentPlaybackMs)
    val latestBeatGrid by rememberUpdatedState(beatGrid)
    val latestSnapToGrid by rememberUpdatedState(snapToGrid)
    val latestSelectedClipId by rememberUpdatedState(selectedClipId)
    val playheadHitPx = with(LocalDensity.current) { 24.dp.toPx() }

    // Keeps the live playhead on-screen DURING PLAYBACK only - must not react merely to a gesture
    // ending, otherwise zooming/panning into a region away from a paused playhead immediately
    // snapped back the moment fingers lifted (isGesturing flipping false re-triggered this effect
    // even though nothing was actually playing).
    LaunchedEffect(currentPlaybackMs, isGesturing, isPlaying) {
        if (!isPlaying || isGesturing || durationMs <= 0 || canvasWidthPx <= 0f || zoom <= MIN_ZOOM) return@LaunchedEffect
        val x = msToX(currentPlaybackMs, canvasWidthPx, zoom, panMs, durationMs)
        if (x < 0f || x > canvasWidthPx) {
            val visibleMs = (durationMs / zoom).toLong()
            val recentered = (currentPlaybackMs - visibleMs / 3).coerceAtLeast(0L)
            panMs = recentered.coerceIn(0L, maxPanMs(zoom, durationMs))
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("dawTimeline")
            // Two-finger pinch/pan only - never consumes with fewer than 2 pointers, so it never
            // competes with the single-finger clip drag/tap gestures below.
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount < 2) {
                            if (event.changes.none { it.pressed }) break
                            continue
                        }
                        isGesturing = true
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            val width = size.width.toFloat()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val centroidMs = xToMs(centroid.x, width, zoom, panMs, durationMs)
                            val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val newPpm = pxPerMs(width, newZoom, durationMs)
                            var newPanMs = if (newPpm > 0f) centroidMs - (centroid.x / newPpm).toLong() else panMs
                            if (newPpm > 0f) newPanMs -= (panChange.x / newPpm).toLong()
                            zoom = newZoom
                            panMs = newPanMs.coerceIn(0L, maxPanMs(newZoom, durationMs))
                            onZoomChange(newZoom)
                            event.changes.forEach { it.consume() }
                        }
                    }
                    isGesturing = false
                }
            }
            .pointerInput(durationMs) {
                var activeDragClipId: Long? = null
                var isPanningViewport = false
                var isScrubbingPlayhead = false
                var dragMode = DragMode.MOVE
                var initialStartMs = 0L
                var initialDurationMs = 0
                var accumulatedDeltaMs = 0L

                detectDragGestures(
                    onDragStart = { offset ->
                        if (durationMs <= 0) return@detectDragGestures
                        val width = size.width.toFloat()
                        val height = size.height
                        val hapticLaneTop = height * 0.42f
                        val hapticLaneBottom = height * 0.96f
                        // Grabbing near the playhead line (either lane) scrubs it directly,
                        // checked ahead of the empty-area fallbacks below but after a clip hit -
                        // a clip you're dragging always wins over a playhead that happens to be
                        // nearby.
                        val playbackX = msToX(latestPlaybackMs, width, zoom, panMs, durationMs)
                        val nearPlayhead = abs(offset.x - playbackX) < playheadHitPx

                        // Check if dragging inside Haptic Lane
                        if (offset.y in hapticLaneTop..hapticLaneBottom) {
                            // First priority: check if user is grabbing selected clip's handles or body
                            val selectedClip = latestClips.find { it.id == latestSelectedClipId }
                            val hitClip = if (selectedClip != null) {
                                val sX = msToX(selectedClip.startMs, width, zoom, panMs, durationMs)
                                val sW = max((selectedClip.durationMs.toFloat() / durationMs) * width * zoom, 4f)
                                // Generous hit area around selected clip, especially the right extend handle
                                if (abs(offset.x - (sX + sW)) < 60f || offset.x in (sX - 30f)..(sX + sW + 60f)) {
                                    selectedClip
                                } else {
                                    latestClips.findLast { clip ->
                                        val clipX = msToX(clip.startMs, width, zoom, panMs, durationMs)
                                        val clipW = max((clip.durationMs.toFloat() / durationMs) * width * zoom, 4f)
                                        offset.x in (clipX - 25f)..(clipX + clipW + 45f)
                                    }
                                }
                            } else {
                                latestClips.findLast { clip ->
                                    val clipX = msToX(clip.startMs, width, zoom, panMs, durationMs)
                                    val clipW = max((clip.durationMs.toFloat() / durationMs) * width * zoom, 4f)
                                    offset.x in (clipX - 25f)..(clipX + clipW + 45f)
                                }
                            }

                            if (hitClip != null) {
                                activeDragClipId = hitClip.id
                                isPanningViewport = false
                                isScrubbingPlayhead = false
                                initialStartMs = hitClip.startMs
                                initialDurationMs = hitClip.durationMs
                                accumulatedDeltaMs = 0L
                                onClipSelect(hitClip.id)

                                val clipX = msToX(hitClip.startMs, width, zoom, panMs, durationMs)
                                val clipW = max((hitClip.durationMs.toFloat() / durationMs) * width * zoom, 4f)

                                // Generous hit test: Right 45% or near right edge triggers TRIM_END to extend length!
                                val isNearRightEdge = abs(offset.x - (clipX + clipW)) < 55f || (offset.x > clipX + clipW * 0.55f)
                                val isNearLeftEdge = !isNearRightEdge && (abs(offset.x - clipX) < 40f || offset.x < clipX + clipW * 0.25f)

                                dragMode = when {
                                    isNearRightEdge -> DragMode.TRIM_END
                                    isNearLeftEdge -> DragMode.TRIM_START
                                    else -> DragMode.MOVE
                                }
                            } else if (nearPlayhead) {
                                activeDragClipId = null
                                isPanningViewport = false
                                isScrubbingPlayhead = true
                                onSeek(xToMs(offset.x, width, zoom, panMs, durationMs).coerceIn(0L, durationMs))
                            } else {
                                activeDragClipId = null
                                isScrubbingPlayhead = false
                                // No clip hit - once zoomed in, a single-finger drag pans the
                                // viewport instead of doing nothing. At default zoom (whole track
                                // visible) there's nowhere to pan to, so this is a no-op there,
                                // preserving today's behavior exactly when unzoomed.
                                isPanningViewport = zoom > MIN_ZOOM
                            }
                        } else if (nearPlayhead) {
                            activeDragClipId = null
                            isPanningViewport = false
                            isScrubbingPlayhead = true
                            onSeek(xToMs(offset.x, width, zoom, panMs, durationMs).coerceIn(0L, durationMs))
                        } else {
                            activeDragClipId = null
                            isScrubbingPlayhead = false
                            isPanningViewport = zoom > MIN_ZOOM
                        }
                        if (activeDragClipId != null || isPanningViewport || isScrubbingPlayhead) isGesturing = true
                    },
                    onDrag = { change, dragAmount ->
                        val clipId = activeDragClipId
                        val width = size.width.toFloat()
                        if (clipId != null && durationMs > 0) {
                            change.consume()
                            val ppm = pxPerMs(width, zoom, durationMs)
                            accumulatedDeltaMs += if (ppm > 0f) (dragAmount.x / ppm).toLong() else 0L

                            when (dragMode) {
                                DragMode.MOVE -> {
                                    var newStart = (initialStartMs + accumulatedDeltaMs).coerceIn(0L, durationMs - initialDurationMs)
                                    if (latestSnapToGrid && latestBeatGrid.beatTimestamps.isNotEmpty()) {
                                        val nearestBeat = latestBeatGrid.beatTimestamps.minByOrNull { abs(it - newStart) }
                                        if (nearestBeat != null && abs(nearestBeat - newStart) < 40L) {
                                            newStart = nearestBeat
                                        }
                                    }
                                    onClipMove(clipId, newStart)
                                }
                                DragMode.TRIM_END -> {
                                    var newDur = (initialDurationMs + accumulatedDeltaMs.toInt()).coerceIn(15, (durationMs - initialStartMs).toInt())
                                    if (latestSnapToGrid && latestBeatGrid.beatTimestamps.isNotEmpty()) {
                                        val targetEnd = initialStartMs + newDur
                                        val nearestBeat = latestBeatGrid.beatTimestamps.minByOrNull { abs(it - targetEnd) }
                                        if (nearestBeat != null && abs(nearestBeat - targetEnd) < 35L && nearestBeat > initialStartMs) {
                                            newDur = (nearestBeat - initialStartMs).toInt()
                                        }
                                    }
                                    onClipTrim(clipId, initialStartMs, newDur)
                                }
                                DragMode.TRIM_START -> {
                                    var newStart = (initialStartMs + accumulatedDeltaMs).coerceIn(0L, initialStartMs + initialDurationMs - 15)
                                    val newDur = (initialDurationMs - (newStart - initialStartMs).toInt()).coerceAtLeast(15)
                                    onClipTrim(clipId, newStart, newDur)
                                }
                            }
                        } else if (isScrubbingPlayhead && durationMs > 0) {
                            change.consume()
                            onSeek(xToMs(change.position.x, width, zoom, panMs, durationMs).coerceIn(0L, durationMs))
                        } else if (isPanningViewport && durationMs > 0) {
                            change.consume()
                            val ppm = pxPerMs(width, zoom, durationMs)
                            if (ppm > 0f) {
                                val deltaMs = (dragAmount.x / ppm).toLong()
                                panMs = (panMs - deltaMs).coerceIn(0L, maxPanMs(zoom, durationMs))
                            }
                        }
                    },
                    onDragEnd = {
                        activeDragClipId = null
                        isPanningViewport = false
                        isScrubbingPlayhead = false
                        accumulatedDeltaMs = 0L
                        isGesturing = false
                    },
                    onDragCancel = {
                        activeDragClipId = null
                        isPanningViewport = false
                        isScrubbingPlayhead = false
                        accumulatedDeltaMs = 0L
                        isGesturing = false
                    }
                )
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs <= 0) return@detectTapGestures
                    val width = size.width.toFloat()
                    val height = size.height
                    val tapMs = xToMs(offset.x, width, zoom, panMs, durationMs).coerceIn(0L, durationMs)

                    val hapticLaneTop = height * 0.42f
                    val hapticLaneBottom = height * 0.96f

                    if (offset.y in hapticLaneTop..hapticLaneBottom) {
                        val hitClip = latestClips.findLast { clip ->
                            val clipX = msToX(clip.startMs, width, zoom, panMs, durationMs)
                            val clipW = max((clip.durationMs.toFloat() / durationMs) * width * zoom, 4f)
                            offset.x in clipX..(clipX + clipW)
                        }

                        if (hitClip != null) {
                            onClipSelect(hitClip.id)
                        } else {
                            onEmptyAreaTap(tapMs)
                        }
                    } else {
                        // Tapped on Audio Lane: Seek
                        onSeek(tapMs)
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        canvasWidthPx = width

        if (durationMs <= 0) return@Canvas

        val audioLaneHeight = height * 0.38f
        val laneDividerY = height * 0.40f
        val hapticLaneTop = height * 0.42f
        val hapticLaneHeight = height * 0.54f

        val playbackX = msToX(currentPlaybackMs, width, zoom, panMs, durationMs)

        // 1. Draw Bar/Beat/Note grid lines across all lanes, each an independently toggleable
        // layer (culled to the visible window) so they don't clutter the view stacked together.
        // Notes drawn first (faintest, so bars/beats visually sit on top where they coincide).
        if (showNoteGrid) {
            // Draws all 4 subdivisions per beat, including n=0 (the beat/bar position itself) -
            // same reasoning as the Beats layer below: each layer must be visually complete on
            // its own since they're independently toggleable, not skip positions on the
            // assumption a different layer will cover them.
            val sortedBeats = beatGrid.beatTimestamps.sorted()
            for (i in sortedBeats.indices) {
                val beatStart = sortedBeats[i]
                val beatEnd = if (i + 1 < sortedBeats.size) sortedBeats[i + 1] else beatStart + beatGrid.beatIntervalMs
                val subdivisionMs = (beatEnd - beatStart) / 4
                if (subdivisionMs <= 0) continue
                for (n in 0..3) {
                    val noteTime = beatStart + subdivisionMs * n
                    val noteX = msToX(noteTime, width, zoom, panMs, durationMs)
                    if (noteX < -5f || noteX > width + 5f) continue
                    drawLine(
                        color = outlineColor.copy(alpha = 0.45f),
                        start = Offset(noteX, 0f),
                        end = Offset(noteX, height),
                        strokeWidth = 1f
                    )
                }
            }
        }
        if (showBeatGrid) {
            // Draws every beat, including downbeats - each layer is independently toggleable, so
            // skipping downbeats here (to leave that tick to the separate Bars layer) meant a
            // visible gap at every bar boundary whenever Bars was off. Bars is drawn last/bolder
            // below, so where both are on it simply draws on top of this line, no double-look.
            for (beatTime in beatGrid.beatTimestamps) {
                val beatX = msToX(beatTime, width, zoom, panMs, durationMs)
                if (beatX < -5f || beatX > width + 5f) continue
                drawLine(
                    color = outlineColor.copy(alpha = 0.65f),
                    start = Offset(beatX, 0f),
                    end = Offset(beatX, height),
                    strokeWidth = 1.3f
                )
            }
        }
        if (showBarGrid) {
            for (barTime in beatGrid.downbeatTimestamps) {
                val barX = msToX(barTime, width, zoom, panMs, durationMs)
                if (barX < -5f || barX > width + 5f) continue
                drawLine(
                    color = outlineColor.copy(alpha = 0.90f),
                    start = Offset(barX, 0f),
                    end = Offset(barX, height),
                    strokeWidth = 2.4f
                )
            }
        }

        // 2. Draw Lane Divider
        drawLine(
            color = outlineColor.copy(alpha = 0.35f),
            start = Offset(0f, laneDividerY),
            end = Offset(width, laneDividerY),
            strokeWidth = 1.5f
        )

        // 3. Draw Audio Waveform (Lane 1) - overlaid colored stem layers when available,
        // otherwise the original single mono waveform. Each bar's real timestamp is converted
        // through msToX so zoom/pan spread the same fixed-resolution overview across whichever
        // time window is currently visible, and off-screen bars are skipped.
        if (stemWaveforms != null && stemWaveforms.isNotEmpty()) {
            val centerY = audioLaneHeight / 2f
            for (stemName in STEM_ORDER) {
                val wave = stemWaveforms[stemName] ?: continue
                val isDimmed = selectedAudioStem != null && selectedAudioStem != stemName
                val stemColor = STEM_COLORS[stemName] ?: primaryColor
                val plan = computeBarPlan(wave.size, durationMs, zoom, panMs, width, 5f) ?: continue

                var idxF = plan.startIdx.toFloat()
                while (idxF.toInt() < plan.endIdx) {
                    val i = idxF.toInt().coerceIn(0, wave.size - 1)
                    val barTimeMs = ((i.toFloat() / wave.size) * durationMs).toLong()
                    val x = msToX(barTimeMs, width, zoom, panMs, durationMs)
                    if (x in -plan.barWidthPx..(width + plan.barWidthPx)) {
                        val amp = wave[i].coerceIn(0.04f, 1.0f)
                        val barH = amp * (audioLaneHeight * 0.80f)
                        val alpha = if (isDimmed) 0.12f else 0.55f
                        drawRoundRect(
                            color = stemColor.copy(alpha = alpha),
                            topLeft = Offset(x, centerY - barH / 2f),
                            size = Size(plan.barWidthPx, barH),
                            cornerRadius = CornerRadius(plan.barWidthPx / 2f, plan.barWidthPx / 2f)
                        )
                    }
                    idxF += plan.stride
                }
            }
        } else if (audioOverview.isNotEmpty()) {
            val centerY = audioLaneHeight / 2f
            val plan = computeBarPlan(audioOverview.size, durationMs, zoom, panMs, width, 6f)
            if (plan != null) {
                var idxF = plan.startIdx.toFloat()
                while (idxF.toInt() < plan.endIdx) {
                    val i = idxF.toInt().coerceIn(0, audioOverview.size - 1)
                    val barTimeMs = ((i.toFloat() / audioOverview.size) * durationMs).toLong()
                    val x = msToX(barTimeMs, width, zoom, panMs, durationMs)
                    if (x in -plan.barWidthPx..(width + plan.barWidthPx)) {
                        val amp = audioOverview[i].coerceIn(0.04f, 1.0f)
                        val barH = amp * (audioLaneHeight * 0.80f)

                        val isPlayed = x <= playbackX
                        val color = if (isPlayed) primaryColor.copy(alpha = 0.85f) else WaveformAudioBar.copy(alpha = 0.4f)

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, centerY - barH / 2f),
                            size = Size(plan.barWidthPx, barH),
                            cornerRadius = CornerRadius(plan.barWidthPx / 2f, plan.barWidthPx / 2f)
                        )
                    }
                    idxF += plan.stride
                }
            }
        }

        // 4. Draw Haptic Pattern Clips / Bars (Lane 2)
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isFakeBoldText = true
            isAntiAlias = true
        }

        for (clip in clips) {
            val clipX = msToX(clip.startMs, width, zoom, panMs, durationMs)
            // Floor is a thin sliver (4px), not a real minimum tap target - the drag/tap hit-tests
            // below add their own generous fixed padding around clipX/clipW independent of this
            // value, so it doesn't need to be inflated for tappability. It used to be 32px, which
            // swallowed real duration differences entirely: at whole-track zoom a 10ms hihat and a
            // 260ms rumble both round to well under a pixel of true width, so every clip rendered
            // at the exact same 32px regardless of its actual duration.
            val clipW = max((clip.durationMs.toFloat() / durationMs) * width * zoom, 4f)
            if (clipX + clipW < -10f || clipX > width + 10f) continue

            // Height is modulated by clip intensity (min 36dp, max lane height)
            val intensityFraction = clip.intensity.coerceIn(0.2f, 1.0f)
            val clipH = hapticLaneHeight * intensityFraction
            val clipY = (hapticLaneTop + hapticLaneHeight) - clipH

            val isSelected = clip.id == selectedClipId
            val baseColor = getPatternColor(clip.patternType)

            // Draw Clip Bar Container
            drawRoundRect(
                color = if (isSelected) baseColor else baseColor.copy(alpha = 0.85f),
                topLeft = Offset(clipX, clipY),
                size = Size(clipW, clipH),
                cornerRadius = CornerRadius(16f, 16f)
            )

            // Selected Glow Border
            if (isSelected) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(clipX, clipY),
                    size = Size(clipW, clipH),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = 3.5f)
                )

                // Left & Right Tactile DAW Trim Handles
                val handleW = 16f
                val handleH = (clipH * 0.70f).coerceAtLeast(32f)
                val handleY = clipY + (clipH - handleH) / 2f

                // Prominent Right Extend Pill Handle
                val rightHandleX = clipX + clipW - (handleW / 2f)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(rightHandleX, handleY),
                    size = Size(handleW, handleH),
                    cornerRadius = CornerRadius(8f, 8f)
                )
                // Grip lines inside right handle
                drawLine(
                    color = Color(0xFF1E1E1E),
                    start = Offset(rightHandleX + 5f, handleY + 6f),
                    end = Offset(rightHandleX + 5f, handleY + handleH - 6f),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color(0xFF1E1E1E),
                    start = Offset(rightHandleX + 11f, handleY + 6f),
                    end = Offset(rightHandleX + 11f, handleY + handleH - 6f),
                    strokeWidth = 2f
                )

                // Left Trim Handle
                val leftHandleX = clipX - (handleW / 2f)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(leftHandleX, handleY),
                    size = Size(handleW, handleH),
                    cornerRadius = CornerRadius(8f, 8f)
                )
                drawLine(
                    color = Color(0xFF1E1E1E),
                    start = Offset(leftHandleX + 8f, handleY + 6f),
                    end = Offset(leftHandleX + 8f, handleY + handleH - 6f),
                    strokeWidth = 2f
                )
            }

            // Draw Clip Name Label if clip width is wide enough
            if (clipW > 45f) {
                drawContext.canvas.nativeCanvas.drawText(
                    clip.patternType.displayName,
                    clipX + 14f,
                    clipY + 34f,
                    textPaint
                )
            }

            // Draw Active Braking Indicator Dot
            if (clip.activeBraking && clipW > 30f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 4.5f,
                    center = Offset(clipX + clipW - 12f, clipY + 12f)
                )
            }
        }

        // 5. Draw Playhead Scrubber Line across entire height (only when in view)
        if (playbackX in -5f..(width + 5f)) {
            drawLine(
                color = Color.White,
                start = Offset(playbackX, 0f),
                end = Offset(playbackX, height),
                strokeWidth = 3f
            )
            drawCircle(
                color = primaryColor,
                radius = 9f,
                center = Offset(playbackX, 10f)
            )
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = Offset(playbackX, 10f)
            )
        }
    }
}
