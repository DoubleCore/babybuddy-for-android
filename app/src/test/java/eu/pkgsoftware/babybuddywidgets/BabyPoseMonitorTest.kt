package eu.pkgsoftware.babybuddywidgets

import eu.pkgsoftware.babybuddywidgets.posemonitor.BabyPoseLabel
import eu.pkgsoftware.babybuddywidgets.posemonitor.BabyWakeUpTracker
import eu.pkgsoftware.babybuddywidgets.posemonitor.LightweightVisionPoseAnalyzer
import eu.pkgsoftware.babybuddywidgets.posemonitor.MlKitPoseClassifier
import eu.pkgsoftware.babybuddywidgets.posemonitor.NormalizedPoint
import eu.pkgsoftware.babybuddywidgets.posemonitor.NormalizedRect
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseAngleProfile
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseDetectionConfig
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseFrameResult
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseGeometryMath
import eu.pkgsoftware.babybuddywidgets.posemonitor.PoseKeypoints
import eu.pkgsoftware.babybuddywidgets.posemonitor.YoloPoseOutputParser
import eu.pkgsoftware.babybuddywidgets.posemonitor.ZoomRoiCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BabyPoseMonitorTest {

    private val emptyFallback = PoseFrameResult(
        targetDetected = false,
        targetBox = null,
        poseLabel = BabyPoseLabel.NONE,
        keypoints = null,
        angleProfile = PoseAngleProfile.EMPTY,
        maxAngleDeltaDeg = 0f,
        confidence = 0f,
        sourceTag = "empty"
    )

    /**
     * Realistic ML Kit / MediaPipe anatomical landmarks for a supine infant facing the camera:
     * Head at top (y=0.22), feet at bottom (y=0.88).
     * Subject's RIGHT shoulder is on viewer-left (x=0.39); LEFT shoulder is on viewer-right (x=0.61).
     */
    private fun canonicalSupineKeypoints(): PoseKeypoints = PoseKeypoints(
        nose = NormalizedPoint(0.50f, 0.22f, 0.94f, -0.04f),
        leftEye = NormalizedPoint(0.53f, 0.20f, 0.92f, -0.04f),
        rightEye = NormalizedPoint(0.47f, 0.20f, 0.92f, -0.04f),
        leftEar = NormalizedPoint(0.56f, 0.22f, 0.86f, 0.01f),
        rightEar = NormalizedPoint(0.44f, 0.22f, 0.86f, 0.01f),
        rightShoulder = NormalizedPoint(0.39f, 0.36f, 0.95f, 0.0f),
        leftShoulder = NormalizedPoint(0.61f, 0.36f, 0.95f, 0.0f),
        rightElbow = NormalizedPoint(0.33f, 0.48f, 0.92f, 0.01f),
        leftElbow = NormalizedPoint(0.67f, 0.48f, 0.92f, 0.01f),
        rightWrist = NormalizedPoint(0.31f, 0.60f, 0.90f, 0.0f),
        leftWrist = NormalizedPoint(0.69f, 0.60f, 0.90f, 0.0f),
        rightHip = NormalizedPoint(0.42f, 0.66f, 0.93f, 0.0f),
        leftHip = NormalizedPoint(0.58f, 0.66f, 0.93f, 0.0f),
        rightKnee = NormalizedPoint(0.41f, 0.78f, 0.90f, 0.0f),
        leftKnee = NormalizedPoint(0.59f, 0.78f, 0.90f, 0.0f),
        rightAnkle = NormalizedPoint(0.42f, 0.90f, 0.88f, 0.0f),
        leftAnkle = NormalizedPoint(0.58f, 0.90f, 0.88f, 0.0f)
    )

    private fun rotateKeypointsAroundCenter(kp: PoseKeypoints, deg: Float): PoseKeypoints {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        fun rot(pt: NormalizedPoint?): NormalizedPoint? {
            if (pt == null) return null
            val dx = pt.x - 0.5f
            val dy = pt.y - 0.5f
            return pt.copy(
                x = (0.5f + dx * c - dy * s).coerceIn(0.01f, 0.99f),
                y = (0.5f + dx * s + dy * c).coerceIn(0.01f, 0.99f)
            )
        }
        return PoseKeypoints(
            nose = rot(kp.nose),
            leftEye = rot(kp.leftEye),
            rightEye = rot(kp.rightEye),
            leftEar = rot(kp.leftEar),
            rightEar = rot(kp.rightEar),
            leftShoulder = rot(kp.leftShoulder),
            rightShoulder = rot(kp.rightShoulder),
            leftElbow = rot(kp.leftElbow),
            rightElbow = rot(kp.rightElbow),
            leftWrist = rot(kp.leftWrist),
            rightWrist = rot(kp.rightWrist),
            leftHip = rot(kp.leftHip),
            rightHip = rot(kp.rightHip),
            leftKnee = rot(kp.leftKnee),
            rightKnee = rot(kp.rightKnee),
            leftAnkle = rot(kp.leftAnkle),
            rightAnkle = rot(kp.rightAnkle)
        )
    }

    @Test
    fun jointAngleDeg_computesExact2DAnd3DCosineLawAngles() {
        val deg90 = PoseGeometryMath.jointAngleDeg(
            NormalizedPoint(0f, 0f, 0.9f),
            NormalizedPoint(0f, 1f, 0.9f),
            NormalizedPoint(1f, 1f, 0.9f)
        )
        assertNotNull(deg90)
        assertEquals(90f, deg90!!, 0.5f)

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
    fun rotationInvariance_supineStaysSupineAt0_90_180_270Degrees_andProneDetectedAccurately() {
        val supine0 = canonicalSupineKeypoints()

        // Test 0°, 90° (horizontal crib), 180° (upside-down crib), 270°
        for (deg in listOf(0f, 90f, 180f, 270f)) {
            val rotated = rotateKeypointsAroundCenter(supine0, deg)
            val (res, _) = MlKitPoseClassifier.classifyFromKeypoints(
                rawKeypoints = rotated,
                objectBox = null,
                prevSmoothedKeypoints = null,
                prevAngleProfile = null,
                fallbackResult = emptyFallback
            )
            assertTrue("Expected targetDetected at ${deg}°", res.targetDetected)
            assertEquals("Supine misclassified at ${deg}°", BabyPoseLabel.SUPINE_CALM, res.poseLabel)
            assertTrue("Chirality should be > 0 (facing camera) at ${deg}°", (res.angleProfile.torsoChirality ?: 0f) > 0.5f)
            assertTrue("Flat supine roll should stay near 0° at ${deg}°, got ${res.angleProfile.bodyRollDeg}", abs(res.angleProfile.bodyRollDeg ?: 99f) < 12f)
        }

        // Now flip subject onto stomach (PRONE: anatomical left shoulder on viewer-left, right shoulder on viewer-right)
        val proneKp = supine0.copy(
            nose = NormalizedPoint(0.50f, 0.22f, 0.25f, 0.06f),
            leftEye = NormalizedPoint(0.48f, 0.20f, 0.18f, 0.06f),
            rightEye = NormalizedPoint(0.52f, 0.20f, 0.18f, 0.06f),
            leftShoulder = NormalizedPoint(0.39f, 0.36f, 0.94f, 0.0f),
            rightShoulder = NormalizedPoint(0.61f, 0.36f, 0.94f, 0.0f),
            leftHip = NormalizedPoint(0.42f, 0.66f, 0.91f, 0.0f),
            rightHip = NormalizedPoint(0.58f, 0.66f, 0.91f, 0.0f)
        )
        val (proneRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = proneKp,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = emptyFallback
        )
        assertEquals(BabyPoseLabel.PRONE_HEAD_UP, proneRes.poseLabel)
        assertTrue((proneRes.angleProfile.torsoChirality ?: 0f) < -0.5f)
    }

    @Test
    fun sleepSackUpperBodyMode_detectsSupineAndSideRollWhenLowerBodyCoveredByBlanket() {
        val supine = canonicalSupineKeypoints()
        // Occlude hips, knees, and ankles completely under a blanket (confidence = 0.05)
        val blanketSupine = supine.copy(
            leftHip = NormalizedPoint(0.55f, 0.66f, 0.05f),
            rightHip = NormalizedPoint(0.45f, 0.66f, 0.05f),
            leftKnee = NormalizedPoint(0.55f, 0.78f, 0.04f),
            rightKnee = NormalizedPoint(0.45f, 0.78f, 0.04f),
            leftAnkle = NormalizedPoint(0.55f, 0.90f, 0.03f),
            rightAnkle = NormalizedPoint(0.45f, 0.90f, 0.03f)
        )
        val (calmUpperRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = blanketSupine,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = emptyFallback
        )
        assertTrue(calmUpperRes.targetDetected)
        assertEquals(BabyPoseLabel.SUPINE_CALM, calmUpperRes.poseLabel)

        // Now roll baby onto anatomical LEFT side while still under blanket:
        // Shoulders foreshorten, nose turns toward left shoulder (viewer-right), right ear faces up, right shoulder closer (z < 0)
        val blanketLeftRoll = blanketSupine.copy(
            rightShoulder = NormalizedPoint(0.46f, 0.36f, 0.95f, -0.08f),
            leftShoulder = NormalizedPoint(0.55f, 0.36f, 0.92f, +0.08f),
            nose = NormalizedPoint(0.57f, 0.23f, 0.90f, -0.02f),
            rightEar = NormalizedPoint(0.49f, 0.22f, 0.93f, -0.06f),
            leftEar = NormalizedPoint(0.56f, 0.23f, 0.22f, +0.07f)
        )
        val (leftRollRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = blanketLeftRoll,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = emptyFallback
        )
        assertTrue(leftRollRes.targetDetected)
        assertEquals(BabyPoseLabel.SIDE_LEFT, leftRollRes.poseLabel)
        assertTrue((leftRollRes.angleProfile.bodyRollDeg ?: 0f) <= -28f)
    }

    @Test
    fun zeroPointCalibration_compensatesObliqueCameraMountAndPreservesRelativeRoll() {
        val obliqueFlatKp = canonicalSupineKeypoints().copy(
            rightShoulder = NormalizedPoint(0.45f, 0.36f, 0.95f, -0.06f),
            leftShoulder = NormalizedPoint(0.56f, 0.36f, 0.95f, +0.06f),
            nose = NormalizedPoint(0.55f, 0.22f, 0.92f, -0.02f),
            rightEar = NormalizedPoint(0.48f, 0.22f, 0.92f, -0.05f),
            leftEar = NormalizedPoint(0.55f, 0.22f, 0.30f, +0.05f)
        )
        val uncalibratedAngles = PoseGeometryMath.computeAngles(obliqueFlatKp, minConf = 0.45f, baselineRollOffsetDeg = 0f)
        val rawOffset = uncalibratedAngles.bodyRollDeg!!
        assertTrue("Oblique mount should produce non-zero raw roll", rawOffset <= -28f)

        // Calibrate baseline with `rawOffset` -> flat posture now reads 0° roll and SUPINE_CALM!
        val calibratedCfg = PoseDetectionConfig.PRESET_BALANCED.copy(baselineRollOffsetDeg = rawOffset)
        val (calibratedRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = obliqueFlatKp,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = emptyFallback,
            config = calibratedCfg
        )
        assertEquals(BabyPoseLabel.SUPINE_CALM, calibratedRes.poseLabel)
        assertEquals(0f, calibratedRes.angleProfile.bodyRollDeg!!, 0.5f)
    }

    @Test
    fun wakeUpTracker_debouncesSingleFrameGlitch_andRespectsUserThresholds() {
        val tracker = BabyWakeUpTracker(
            changeThreshold = 3,
            windowMs = 30_000L,
            minChangeIntervalMs = 1_000L,
            angleThresholdDeg = 22f,
            confirmFrames = 2
        )

        fun liveFrame(pose: BabyPoseLabel): PoseFrameResult = PoseFrameResult(
            targetDetected = true,
            targetBox = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f),
            poseLabel = pose,
            keypoints = null,
            angleProfile = PoseAngleProfile.presetFor(pose),
            maxAngleDeltaDeg = 0f,
            confidence = 0.92f,
            sourceTag = "mlkit-3d-pose"
        )

        // Establish baseline SUPINE_CALM (2 frames)
        tracker.onFrame(liveFrame(BabyPoseLabel.SUPINE_CALM), nowMs = 1_000L)
        val sBase = tracker.onFrame(liveFrame(BabyPoseLabel.SUPINE_CALM), nowMs = 1_200L)
        assertEquals(0, sBase.changeCount)

        // Single-frame glitch to SIDE_LEFT followed immediately by SUPINE_CALM -> MUST NOT increment count!
        val sGlitch = tracker.onFrame(liveFrame(BabyPoseLabel.SIDE_LEFT), nowMs = 3_000L)
        assertEquals("Single-frame glitch must be suppressed by confirmFrames=2", 0, sGlitch.changeCount)
        assertEquals(BabyPoseLabel.SUPINE_CALM, sGlitch.currentPose)
        tracker.onFrame(liveFrame(BabyPoseLabel.SUPINE_CALM), nowMs = 3_200L)

        // Now sustain 3 confirmed posture transitions (2 frames each):
        // 1. SIDE_LEFT
        tracker.onFrame(liveFrame(BabyPoseLabel.SIDE_LEFT), nowMs = 5_000L)
        val s1 = tracker.onFrame(liveFrame(BabyPoseLabel.SIDE_LEFT), nowMs = 5_200L)
        assertEquals(1, s1.changeCount)

        // 2. LIMB_ACTIVE
        tracker.onFrame(liveFrame(BabyPoseLabel.LIMB_ACTIVE), nowMs = 7_000L)
        val s2 = tracker.onFrame(liveFrame(BabyPoseLabel.LIMB_ACTIVE), nowMs = 7_200L)
        assertEquals(2, s2.changeCount)
        assertFalse(s2.isAlarmRinging)

        // 3. SITTING_AWAKE -> reaches threshold=3 -> triggers wake alarm!
        tracker.onFrame(liveFrame(BabyPoseLabel.SITTING_AWAKE), nowMs = 9_500L)
        val s3 = tracker.onFrame(liveFrame(BabyPoseLabel.SITTING_AWAKE), nowMs = 9_700L)
        assertEquals(3, s3.changeCount)
        assertTrue(s3.justTriggeredWake)
        assertTrue(s3.isAlarmRinging)
    }

    @Test
    fun mlKitPoseClassifier_rejectsLowConfidenceNoiseByDefault_withoutFakeContourHallucination() {
        val noisyLowConfKp = canonicalSupineKeypoints().let { kp ->
            PoseKeypoints(
                nose = kp.nose?.copy(confidence = 0.18f),
                leftShoulder = kp.leftShoulder?.copy(confidence = 0.22f),
                rightShoulder = kp.rightShoulder?.copy(confidence = 0.21f),
                leftWrist = kp.leftWrist?.copy(confidence = 0.15f),
                rightWrist = kp.rightWrist?.copy(confidence = 0.16f),
                leftHip = kp.leftHip?.copy(confidence = 0.19f),
                rightHip = kp.rightHip?.copy(confidence = 0.19f),
                leftAnkle = kp.leftAnkle?.copy(confidence = 0.12f),
                rightAnkle = kp.rightAnkle?.copy(confidence = 0.12f)
            )
        }
        val fakeWarmBedFallback = PoseFrameResult(
            targetDetected = true,
            targetBox = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
            poseLabel = BabyPoseLabel.LIMB_ACTIVE,
            keypoints = null,
            angleProfile = PoseAngleProfile.presetFor(BabyPoseLabel.LIMB_ACTIVE),
            maxAngleDeltaDeg = 45f,
            confidence = 0.48f,
            sourceTag = "contour-fallback"
        )

        // With default config (enableContourFallback = false, minLandmarkConfidence = 0.45), warm bedding is NOT hallucinated!
        val (strictRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = noisyLowConfKp,
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = fakeWarmBedFallback,
            config = PoseDetectionConfig.PRESET_BALANCED
        )
        assertFalse(strictRes.targetDetected)
        assertEquals(BabyPoseLabel.NONE, strictRes.poseLabel)

        // If user lowers minLandmarkConfidence to 0.20, the upper-body landmarks become accepted!
        val lowThreshCfg = PoseDetectionConfig.PRESET_BALANCED.copy(minLandmarkConfidence = 0.18f)
        val (acceptedRes, _) = MlKitPoseClassifier.classifyFromKeypoints(
            rawKeypoints = noisyLowConfKp.copy(nose = noisyLowConfKp.nose?.copy(confidence = 0.25f)),
            objectBox = null,
            prevSmoothedKeypoints = null,
            prevAngleProfile = null,
            fallbackResult = emptyFallback,
            config = lowThreshCfg
        )
        assertTrue(acceptedRes.targetDetected)
        assertEquals(BabyPoseLabel.SUPINE_CALM, acceptedRes.poseLabel)
    }

    @Test
    fun mlKitPoseClassifier_distinguishesCalmSupineFromAcuteArmBendAndKick() {
        val calmKp = canonicalSupineKeypoints()
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

        // Pure camera translation (+0.08, +0.05) -> 0° angle change!
        fun shift(pt: NormalizedPoint?) = pt?.copy(x = pt.x + 0.08f, y = pt.y + 0.05f)
        val translatedKp = calmKp.copy(
            nose = shift(calmKp.nose),
            leftEye = shift(calmKp.leftEye),
            rightEye = shift(calmKp.rightEye),
            leftEar = shift(calmKp.leftEar),
            rightEar = shift(calmKp.rightEar),
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
        assertEquals(BabyPoseLabel.SUPINE_CALM, shakenRes.poseLabel)
        assertTrue(shakenRes.maxAngleDeltaDeg < 1.0f)

        // Acute arm raise/bend + differential leg kick -> LIMB_ACTIVE!
        val activeKp = calmKp.copy(
            leftElbow = NormalizedPoint(0.73f, 0.24f, 0.94f),
            leftWrist = NormalizedPoint(0.61f, 0.22f, 0.94f),
            leftKnee = NormalizedPoint(0.70f, 0.69f, 0.93f),
            leftAnkle = NormalizedPoint(0.58f, 0.71f, 0.93f)
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
    fun lightweightVisionAnalyzer_rejectsUniformWarmBlanket_detectsLocalizedSubject() {
        val analyzer = LightweightVisionPoseAnalyzer(48, 48)
        // Uniform warm beige blanket filling the whole frame -> MUST NOT false-trigger!
        val warmBlanketFrame = IntArray(48 * 48) { 0xFFE6C5A8.toInt() }
        val rBlanket = analyzer.analyzeGrid(warmBlanketFrame, 48, 48)
        assertFalse(rBlanket.targetDetected)

        val bgFrame = IntArray(48 * 48) { 0xFF20242C.toInt() }
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

    @Test
    fun yoloPoseOutputParser_decodesBothE2ENmsFreeAndClassicAnchorFreeShapes() {
        // 1. Test YOLO26-pose End-to-End NMS-Free shape [1, 300, 57]
        val e2eArr = FloatArray(300 * 57)
        // Row 0: valid baby detection in 640x640 letterbox (padX=80, padY=0, scaledW=480, scaledH=640)
        e2eArr[0] = 200f // x1
        e2eArr[1] = 128f // y1
        e2eArr[2] = 440f // x2
        e2eArr[3] = 576f // y2
        e2eArr[4] = 0.89f // score
        e2eArr[5] = 0f    // class 0 = person
        // Nose at (320, 160, 0.96) -> unpadded x = (320-80)/480 = 0.50, y = 160/640 = 0.25
        e2eArr[6] = 320f
        e2eArr[7] = 160f
        e2eArr[8] = 0.96f
        // Left shoulder (idx 5) at (380, 240, 0.93)
        e2eArr[6 + 5 * 3] = 380f
        e2eArr[6 + 5 * 3 + 1] = 240f
        e2eArr[6 + 5 * 3 + 2] = 0.93f
        // Right shoulder (idx 6) at (260, 240, 0.94)
        e2eArr[6 + 6 * 3] = 260f
        e2eArr[6 + 6 * 3 + 1] = 240f
        e2eArr[6 + 6 * 3 + 2] = 0.94f

        val detE2E = YoloPoseOutputParser.decodeBestPerson(
            outBuffer = FloatBuffer.wrap(e2eArr),
            shape = longArrayOf(1L, 300L, 57L),
            inputSize = 640,
            padX = 80f,
            padY = 0f,
            scaledW = 480f,
            scaledH = 640f
        )
        assertNotNull(detE2E)
        assertEquals(0.89f, detE2E!!.boxScore, 0.01f)
        assertEquals(0.50f, detE2E.keypoints.nose!!.x, 0.01f)
        assertEquals(0.25f, detE2E.keypoints.nose!!.y, 0.01f)

        // 2. Test YOLO11-pose Classic Anchor-Free shape [1, 56, 8400] with overlapping duplicate suppression
        val m = 8400
        val classicArr = FloatArray(56 * m)
        // Anchor 100 (primary, score=0.91) and Anchor 101 (duplicate high-IoU, score=0.72)
        for ((anchorIdx, sc) in listOf(100 to 0.91f, 101 to 0.72f)) {
            classicArr[0 * m + anchorIdx] = 320f // cx
            classicArr[1 * m + anchorIdx] = 320f // cy
            classicArr[2 * m + anchorIdx] = 240f // w
            classicArr[3 * m + anchorIdx] = 400f // h
            classicArr[4 * m + anchorIdx] = sc   // score
            classicArr[5 * m + anchorIdx] = 320f // nose x
            classicArr[6 * m + anchorIdx] = 160f // nose y
            classicArr[7 * m + anchorIdx] = 0.95f // nose conf
        }
        val detClassic = YoloPoseOutputParser.decodeBestPerson(
            outBuffer = FloatBuffer.wrap(classicArr),
            shape = longArrayOf(1L, 56L, 8400L),
            inputSize = 640,
            padX = 0f,
            padY = 0f,
            scaledW = 640f,
            scaledH = 640f
        )
        assertNotNull(detClassic)
        assertEquals(0.91f, detClassic!!.boxScore, 0.01f)
        assertEquals(0.50f, detClassic.keypoints.nose!!.x, 0.01f)
        assertEquals(0.25f, detClassic.keypoints.nose!!.y, 0.01f)
    }

    @Test
    fun bundledNcnnFp16PoseModels_haveValidMagicHeadersBlobsAndWeights() {
        val baseDir = listOf(
            File("src/main/assets/models"),
            File("app/src/main/assets/models")
        ).firstOrNull { it.exists() }
        assertNotNull("assets/models directory must exist", baseDir)

        for ((prefix, expectedAnchors) in listOf("yolo26n_pose_320" to "2100", "yolo26n_pose_480" to "4725")) {
            val paramFile = File(baseDir!!, "$prefix.param")
            val binFile = File(baseDir, "$prefix.bin")
            assertTrue("$prefix.param must exist", paramFile.exists())
            assertTrue("$prefix.bin must exist and be > 5 MB", binFile.exists() && binFile.length() > 5L * 1024L * 1024L)

            val paramText = paramFile.readText(Charsets.UTF_8)
            assertTrue("$prefix.param must start with ncnn magic 7767517", paramText.trimStart().startsWith("7767517"))
            assertTrue("$prefix.param must declare Input in0", paramText.contains("in0"))
            assertTrue("$prefix.param must declare output out0", paramText.contains("out0"))
            assertTrue("$prefix.param must contain anchor reshape $expectedAnchors", paramText.contains("0=$expectedAnchors"))
        }

        // Verify compact 56-float JNI array decoding
        val compact = FloatArray(56)
        compact[0] = 0.2f; compact[1] = 0.15f; compact[2] = 0.8f; compact[3] = 0.9f; compact[4] = 0.92f
        compact[5] = 0.5f; compact[6] = 0.22f; compact[7] = 0.98f // nose
        val det = YoloPoseOutputParser.fromCompact56Array(compact)
        assertNotNull(det)
        assertEquals(0.92f, det!!.boxScore, 0.01f)
        assertEquals(0.5f, det.keypoints.nose!!.x, 0.01f)
    }
}
