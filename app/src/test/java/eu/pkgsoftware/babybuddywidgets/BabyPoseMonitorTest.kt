package eu.pkgsoftware.babybuddywidgets

import eu.pkgsoftware.babybuddywidgets.posemonitor.BabyPoseLabel
import eu.pkgsoftware.babybuddywidgets.posemonitor.BabyWakeUpTracker
import eu.pkgsoftware.babybuddywidgets.posemonitor.LightweightVisionPoseAnalyzer
import eu.pkgsoftware.babybuddywidgets.posemonitor.MlKitPoseClassifier
import eu.pkgsoftware.babybuddywidgets.posemonitor.NormalizedPoint
import eu.pkgsoftware.babybuddywidgets.posemonitor.NormalizedRect
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseFrameResult
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseKeypoints
import eu.pkgsoftware.babybuddywidgets.posemonitor.ZoomRoiCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyPoseMonitorTest {

    @Test
    fun zoomCropRect_magnifiesAndPansWithinBounds() {
        val full = ZoomRoiCropper.computeCropRect(1920, 1080, 1.0f, 0f, 0f)
        assertEquals(0, full.left)
        assertEquals(0, full.top)
        assertEquals(1920, full.width)
        assertEquals(1080, full.height)

        val zoom4xCenter = ZoomRoiCropper.computeCropRect(1920, 1080, 4.0f, 0f, 0f)
        assertEquals(480, zoom4xCenter.width)
        assertEquals(270, zoom4xCenter.height)
        assertEquals(720, zoom4xCenter.left)
        assertEquals(405, zoom4xCenter.top)

        val zoom4xTopRight = ZoomRoiCropper.computeCropRect(1920, 1080, 4.0f, 1.0f, -1.0f)
        assertEquals(1920 - 480, zoom4xTopRight.left)
        assertEquals(0, zoom4xTopRight.top)
        assertTrue(zoom4xTopRight.left + zoom4xTopRight.width <= 1920)
        assertTrue(zoom4xTopRight.top + zoom4xTopRight.height <= 1080)
    }

    @Test
    fun wakeUpTracker_triggersAlarmOnlyAfterFrequentPoseChanges() {
        val tracker = BabyWakeUpTracker(
            changeThreshold = 4,
            windowMs = 30_000L,
            minChangeIntervalMs = 1_000L
        )

        fun makeFrame(pose: BabyPoseLabel, motion: Float = 0.1f): PoseFrameResult {
            return PoseFrameResult(
                targetDetected = true,
                targetBox = NormalizedRect(0.25f, 0.2f, 0.75f, 0.8f),
                poseLabel = pose,
                keypoints = null,
                motionScore = motion,
                confidence = 0.9f,
                poseVector = floatArrayOf(0.5f, 0.5f, 0f, motion, motion, 0.5f),
                sourceTag = "test"
            )
        }

        // Initial baseline pose
        val s0 = tracker.onFrame(makeFrame(BabyPoseLabel.SUPINE_CALM), nowMs = 1_000L)
        assertEquals(0, s0.changeCount)
        assertFalse(s0.isAlarmRinging)

        // Change 1
        val s1 = tracker.onFrame(makeFrame(BabyPoseLabel.SIDE_LEFT), nowMs = 3_000L)
        assertEquals(1, s1.changeCount)
        assertFalse(s1.isAlarmRinging)

        // Change 2
        val s2 = tracker.onFrame(makeFrame(BabyPoseLabel.SUPINE_CALM), nowMs = 5_000L)
        assertEquals(2, s2.changeCount)
        assertFalse(s2.isAlarmRinging)

        // Change 3
        val s3 = tracker.onFrame(makeFrame(BabyPoseLabel.LIMB_ACTIVE, motion = 0.45f), nowMs = 7_500L)
        assertEquals(3, s3.changeCount)
        assertFalse(s3.isAlarmRinging)

        // Change 4 -> hits threshold 4 within 30s -> triggers wake alarm immediately!
        val s4 = tracker.onFrame(makeFrame(BabyPoseLabel.SIDE_RIGHT), nowMs = 10_000L)
        assertEquals(4, s4.changeCount)
        assertTrue(s4.justTriggeredWake)
        assertTrue(s4.isAlarmRinging)

        // Dismiss alarm -> clears ringing & resets counter
        tracker.dismissAlarm(nowMs = 11_000L)
        val afterDismiss = tracker.currentSnapshot(nowMs = 11_500L)
        assertFalse(afterDismiss.isAlarmRinging)
        assertEquals(0, afterDismiss.changeCount)

        // Snooze suppresses wake trigger during snooze window
        tracker.snoozeAlarm(durationMs = 60_000L, nowMs = 20_000L)
        tracker.onFrame(makeFrame(BabyPoseLabel.SUPINE_CALM), nowMs = 22_000L)
        tracker.onFrame(makeFrame(BabyPoseLabel.SIDE_LEFT), nowMs = 24_000L)
        tracker.onFrame(makeFrame(BabyPoseLabel.LIMB_ACTIVE), nowMs = 26_000L)
        tracker.onFrame(makeFrame(BabyPoseLabel.SIDE_RIGHT), nowMs = 28_000L)
        val duringSnooze = tracker.onFrame(makeFrame(BabyPoseLabel.PRONE_HEAD_UP), nowMs = 30_000L)
        assertFalse(duringSnooze.isAlarmRinging)
        assertTrue(duringSnooze.snoozedRemainingSec > 0)
    }

    @Test
    fun mlKitPoseClassifier_detectsSupineAndActiveKicking() {
        val calmKp = PoseKeypoints(
            nose = NormalizedPoint(0.50f, 0.25f, 0.9f),
            leftShoulder = NormalizedPoint(0.38f, 0.38f, 0.9f),
            rightShoulder = NormalizedPoint(0.62f, 0.38f, 0.9f),
            leftWrist = NormalizedPoint(0.34f, 0.52f, 0.9f),
            rightWrist = NormalizedPoint(0.66f, 0.52f, 0.9f),
            leftHip = NormalizedPoint(0.42f, 0.62f, 0.9f),
            rightHip = NormalizedPoint(0.58f, 0.62f, 0.9f),
            leftAnkle = NormalizedPoint(0.42f, 0.84f, 0.9f),
            rightAnkle = NormalizedPoint(0.58f, 0.84f, 0.9f)
        )
        val emptyFallback = PoseFrameResult(
            targetDetected = false,
            targetBox = null,
            poseLabel = BabyPoseLabel.NONE,
            keypoints = null,
            motionScore = 0.02f,
            confidence = 0f,
            poseVector = FloatArray(6),
            sourceTag = "empty"
        )

        val calmRes = MlKitPoseClassifier.classifyFromKeypoints(calmKp, null, calmKp, emptyFallback)
        assertTrue(calmRes.targetDetected)
        assertEquals(BabyPoseLabel.SUPINE_CALM, calmRes.poseLabel)

        val activeKp = calmKp.copy(
            leftWrist = NormalizedPoint(0.30f, 0.20f, 0.9f),
            rightWrist = NormalizedPoint(0.72f, 0.18f, 0.9f),
            leftAnkle = NormalizedPoint(0.35f, 0.65f, 0.9f),
            rightAnkle = NormalizedPoint(0.66f, 0.90f, 0.9f)
        )
        val activeRes = MlKitPoseClassifier.classifyFromKeypoints(activeKp, null, calmKp, emptyFallback)
        assertEquals(BabyPoseLabel.LIMB_ACTIVE, activeRes.poseLabel)
        assertTrue(activeRes.motionScore > 0.2f)
    }

    @Test
    fun lightweightVisionAnalyzer_detectsWarmInfantRegionAndMotion() {
        val analyzer = LightweightVisionPoseAnalyzer(48, 48)
        val bgFrame = IntArray(48 * 48) { 0xFF20242C.toInt() }
        val r0 = analyzer.analyzeGrid(bgFrame, 48, 48)
        assertFalse(r0.targetDetected)

        // Draw warm infant subject in center
        val babyFrame = bgFrame.copyOf()
        for (y in 14..34) {
            for (x in 16..32) {
                babyFrame[y * 48 + x] = 0xFFE8B49A.toInt()
            }
        }
        val r1 = analyzer.analyzeGrid(babyFrame, 48, 48)
        assertTrue(r1.targetDetected)
        assertNotNull(r1.targetBox)
    }
}
