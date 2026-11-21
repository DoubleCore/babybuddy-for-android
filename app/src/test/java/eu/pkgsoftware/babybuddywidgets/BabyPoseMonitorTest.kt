package eu.pkgsoftware.babybuddywidgets

import eu.pkgsoftware.babybuddywidgets.posemonitor.BabyPoseLabel
import eu.pkgsoftware.babybuddywidgets.posemonitor.BabyWakeUpTracker
import eu.pkgsoftware.babybuddywidgets.posemonitor.LightweightVisionPoseAnalyzer
import eu.pkgsoftware.babybuddywidgets.posemonitor.MlKitPoseClassifier
import eu.pkgsoftware.babybuddywidgets.posemonitor.NormalizedPoint
import eu.pkgsoftware.babybuddywidgets.posemonitor.NormalizedRect
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseAngleProfile
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseFrameResult
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseGeometryMath
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseKeypoints
import eu.pkgsoftware.babybuddywidgets.posemonitor.ZoomRoiCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyPoseMonitorTest {

    @Test
    fun jointAngleDeg_computesExactCosineLawAngles() {
        // Right angle (90°): shoulder(0, 0) -> elbow(0, 1) -> wrist(1, 1)
        val deg90 = PoseGeometryMath.jointAngleDeg(
            NormalizedPoint(0f, 0f, 0.9f),
            NormalizedPoint(0f, 1f, 0.9f),
            NormalizedPoint(1f, 1f, 0.9f)
        )
        assertNotNull(deg90)
        assertEquals(90f, deg90!!, 0.5f)

        // Straight arm (180°): shoulder(0, 0) -> elbow(0, 0.5) -> wrist(0, 1.0)
        val deg180 = PoseGeometryMath.jointAngleDeg(
            NormalizedPoint(0f, 0f, 0.9f),
            NormalizedPoint(0f, 0.5f, 0.9f),
            NormalizedPoint(0f, 1.0f, 0.9f)
        )
        assertNotNull(deg180)
        assertEquals(180f, deg180!!, 0.5f)
    }

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
    fun wakeUpTracker_ignoresPixelJitterAndTranslation_triggersOnLargeJointAngleChanges() {
        val tracker = BabyWakeUpTracker(
            changeThreshold = 4,
            windowMs = 30_000L,
            minChangeIntervalMs = 1_000L,
            angleThresholdDeg = 25f
        )

        fun makeAngleFrame(pose: BabyPoseLabel, angles: PoseAngleProfile, shiftX: Float = 0f): PoseFrameResult {
            return PoseFrameResult(
                targetDetected = true,
                targetBox = NormalizedRect(0.20f + shiftX, 0.2f, 0.70f + shiftX, 0.8f),
                poseLabel = pose,
                keypoints = null,
                angleProfile = angles,
                maxAngleDeltaDeg = 0f,
                confidence = 0.92f,
                sourceTag = "test-angle"
            )
        }

        val calmAngles = PoseAngleProfile.presetFor(BabyPoseLabel.SUPINE_CALM)

        // Baseline supine pose
        val s0 = tracker.onFrame(makeAngleFrame(BabyPoseLabel.SUPINE_CALM, calmAngles), nowMs = 1_000L)
        assertEquals(0, s0.changeCount)

        // Simulate camera shake / whole-body pixel translation (+15% X shift) + minor 4° angle noise -> MUST BE IGNORED!
        val jitteredAngles = calmAngles.copy(
            leftElbowDeg = calmAngles.leftElbowDeg!! + 4f,
            rightKneeDeg = calmAngles.rightKneeDeg!! - 3f
        )
        val sJitter1 = tracker.onFrame(makeAngleFrame(BabyPoseLabel.SUPINE_CALM, jitteredAngles, shiftX = 0.15f), nowMs = 3_000L)
        val sJitter2 = tracker.onFrame(makeAngleFrame(BabyPoseLabel.SUPINE_CALM, calmAngles, shiftX = -0.12f), nowMs = 5_000L)
        assertEquals(0, sJitter1.changeCount)
        assertEquals(0, sJitter2.changeCount)
        assertFalse(sJitter2.isAlarmRinging)

        // Now perform 4 genuine large joint-angle posture changes (>= 25° bend/roll):
        // 1. Roll to Left Side (right elbow bends 138° -> 72°, roll 0° -> -52°)
        val s1 = tracker.onFrame(
            makeAngleFrame(BabyPoseLabel.SIDE_LEFT, PoseAngleProfile.presetFor(BabyPoseLabel.SIDE_LEFT)),
            nowMs = 7_000L
        )
        assertEquals(1, s1.changeCount)

        // 2. Active Arm Bend & Leg Kick (left elbow 105° -> 54°, left shoulder 55° -> 142°, right knee 96° -> 168°)
        val s2 = tracker.onFrame(
            makeAngleFrame(BabyPoseLabel.LIMB_ACTIVE, PoseAngleProfile.presetFor(BabyPoseLabel.LIMB_ACTIVE)),
            nowMs = 9_000L
        )
        assertEquals(2, s2.changeCount)

        // 3. Prone Forearm Prop (both elbows ~67°, knees straighten ~152°)
        val s3 = tracker.onFrame(
            makeAngleFrame(BabyPoseLabel.PRONE_HEAD_UP, PoseAngleProfile.presetFor(BabyPoseLabel.PRONE_HEAD_UP)),
            nowMs = 11_500L
        )
        assertEquals(3, s3.changeCount)
        assertFalse(s3.isAlarmRinging)

        // 4. Push Up / Hip-Flexed Sitting (elbows extend 67° -> 157°, hips flex 157° -> 87°) -> Triggers Wake Alarm!
        val s4 = tracker.onFrame(
            makeAngleFrame(BabyPoseLabel.SITTING_AWAKE, PoseAngleProfile.presetFor(BabyPoseLabel.SITTING_AWAKE)),
            nowMs = 14_000L
        )
        assertEquals(4, s4.changeCount)
        assertTrue(s4.justTriggeredWake)
        assertTrue(s4.isAlarmRinging)
    }

    @Test
    fun mlKitPoseClassifier_distinguishesCalmSupineFromAcuteArmBendAndKick() {
        val calmKp = PoseKeypoints(
            nose = NormalizedPoint(0.50f, 0.22f, 0.9f),
            leftShoulder = NormalizedPoint(0.38f, 0.36f, 0.9f),
            rightShoulder = NormalizedPoint(0.62f, 0.36f, 0.9f),
            leftElbow = NormalizedPoint(0.32f, 0.48f, 0.9f),
            rightElbow = NormalizedPoint(0.68f, 0.48f, 0.9f),
            leftWrist = NormalizedPoint(0.30f, 0.60f, 0.9f),
            rightWrist = NormalizedPoint(0.70f, 0.60f, 0.9f),
            leftHip = NormalizedPoint(0.42f, 0.62f, 0.9f),
            rightHip = NormalizedPoint(0.58f, 0.62f, 0.9f),
            leftKnee = NormalizedPoint(0.41f, 0.75f, 0.9f),
            rightKnee = NormalizedPoint(0.59f, 0.75f, 0.9f),
            leftAnkle = NormalizedPoint(0.42f, 0.88f, 0.9f),
            rightAnkle = NormalizedPoint(0.58f, 0.88f, 0.9f)
        )
        val emptyFallback = PoseFrameResult(
            targetDetected = false,
            targetBox = null,
            poseLabel = BabyPoseLabel.NONE,
            keypoints = null,
            angleProfile = PoseAngleProfile.EMPTY,
            maxAngleDeltaDeg = 0f,
            confidence = 0f,
            sourceTag = "empty"
        )

        val (calmRes, smoothed0) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = calmKp,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = emptyFallback
        )
        assertTrue(calmRes.targetDetected)
        assertEquals(BabyPoseLabel.SUPINE_CALM, calmRes.poseLabel)
        assertTrue((calmRes.angleProfile.leftElbowDeg ?: 0f) > 130f)

        // Translate every single landmark by (+0.08, +0.05) -> pure camera shake, angles stay identical!
        fun shift(pt: NormalizedPoint?) = pt?.copy(x = pt.x + 0.08f, y = pt.y + 0.05f)
        val translatedKp = PoseKeypoints(
            nose = shift(calmKp.nose),
            leftShoulder = shift(calmKp.leftShoulder),
            rightShoulder = shift(calmKp.rightShoulder),
            leftElbow = shift(calmKp.leftElbow),
            rightElbow = shift(calmKp.rightElbow),
            leftWrist = shift(calmKp.leftWrist),
            rightWrist = shift(calmKp.rightWrist),
            leftHip = shift(calmKp.leftHip),
            rightHip = shift(calmKp.rightHip),
            leftKnee = shift(calmKp.leftKnee),
            rightKnee = shift(calmKp.rightKnee),
            leftAnkle = shift(calmKp.leftAnkle),
            rightAnkle = shift(calmKp.rightAnkle)
        )
        val (shakenRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = translatedKp,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = calmRes.angleProfile,
            fallbackResult = emptyFallback
        )
        // Pure pixel shift produces 0° angle change and stays SUPINE_CALM!
        assertEquals(BabyPoseLabel.SUPINE_CALM, shakenRes.poseLabel)
        assertTrue(shakenRes.maxAngleDeltaDeg < 1.0f)

        // Now bend left arm sharply overhead & flex left knee sharply while right leg kicks straight
        val activeKp = calmKp.copy(
            leftElbow = NormalizedPoint(0.26f, 0.24f, 0.9f),
            leftWrist = NormalizedPoint(0.38f, 0.22f, 0.9f),
            leftKnee = NormalizedPoint(0.30f, 0.66f, 0.9f),
            leftAnkle = NormalizedPoint(0.42f, 0.68f, 0.9f)
        )
        val (activeRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = activeKp,
            objectBox = null,
            prevSmoothedKeypoints = smoothed0,
            prevAngleProfile = calmRes.angleProfile,
            fallbackResult = emptyFallback
        )
        assertEquals(BabyPoseLabel.LIMB_ACTIVE, activeRes.poseLabel)
        assertTrue(activeRes.maxAngleDeltaDeg >= 30f)
    }

    @Test
    fun lightweightVisionAnalyzer_detectsContourAnglesWithoutMotionJitter() {
        val analyzer = LightweightVisionPoseAnalyzer(48, 48)
        val bgFrame = IntArray(48 * 48) { 0xFF20242C.toInt() }
        val r0 = analyzer.analyzeGrid(bgFrame, 48, 48)
        assertFalse(r0.targetDetected)

        val babyFrame = bgFrame.copyOf()
        for (y in 12..36) {
            for (x in 18..30) {
                babyFrame[y * 48 + x] = 0xFFE8B49A.toInt()
            }
        }
        val r1 = analyzer.analyzeGrid(babyFrame, 48, 48)
        assertTrue(r1.targetDetected)
        assertNotNull(r1.angleProfile.leftElbowDeg)
    }
}
