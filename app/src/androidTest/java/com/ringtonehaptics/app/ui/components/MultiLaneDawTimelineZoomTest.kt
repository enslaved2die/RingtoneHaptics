package com.ringtonehaptics.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.ringtonehaptics.app.domain.model.BeatGridInfo
import com.ringtonehaptics.app.domain.model.HapticClip
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the real two-finger pinch gesture handler in MultiLaneDawTimeline actually changes
 * zoom - a genuine device/emulator touch-injection test (via Compose's TouchInjectionScope, which
 * works without root, unlike raw /dev/input sendevent), not just a code-review of the gesture math.
 */
class MultiLaneDawTimelineZoomTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pinchGestureIncreasesZoom() {
        var lastZoom = 1f

        composeTestRule.setContent {
            MultiLaneDawTimeline(
                audioOverview = FloatArray(50) { 0.5f },
                clips = listOf(
                    HapticClip(startMs = 1000L, durationMs = 50),
                    HapticClip(startMs = 5000L, durationMs = 50)
                ),
                beatGrid = BeatGridInfo(),
                durationMs = 10000L,
                currentPlaybackMs = 0L,
                isPlaying = false,
                selectedClipId = null,
                snapToGrid = false,
                onSeek = {},
                onClipSelect = {},
                onClipMove = { _, _ -> },
                onClipTrim = { _, _, _ -> },
                onEmptyAreaTap = {},
                modifier = Modifier.size(400.dp, 300.dp),
                onZoomChange = { lastZoom = it }
            )
        }

        composeTestRule.onNodeWithTag("dawTimeline").performTouchInput {
            val cy = center.y
            down(0, Offset(center.x - 20f, cy))
            down(1, Offset(center.x + 20f, cy))
            moveTo(0, Offset(center.x - 150f, cy))
            moveTo(1, Offset(center.x + 150f, cy))
            up(0)
            up(1)
        }

        composeTestRule.waitForIdle()
        assertTrue("Pinching outward should increase zoom above 1x, was $lastZoom", lastZoom > 1f)
    }
}
