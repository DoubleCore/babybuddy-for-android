package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aspect-accurate interactive overlay rendered above the camera preview:
 * - Aligns skeleton & bounding boxes strictly to the FIT_CENTER bitmap viewport (zero letterbox stretch!)
 * - Supports 2-finger pinch-to-zoom (1.0x - 8.0x), 1-finger pan, and double-tap zoom toggle
 * - Renders 17-point skeleton, confidence-graded joints, optional joint angle labels, and ROI minimap
 */
class PoseOverlayAndZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onZoomPanChanged: ((zoomRatio: Float, panX: Float, panY: Float) -> Unit)? = null
    var showJointAngleLabels: Boolean = true

    private var currentZoom: Float = 1.0f
    private var currentPanX: Float = 0f
    private var currentPanY: Float = 0f
    private var frameWidthPx: Int = 0
    private var frameHeightPx: Int = 0
    private var frameResult: PoseFrameResult? = null
    private var snapshot: WakeDecisionSnapshot? = null

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        color = Color.parseColor("#34D399")
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(18, 52, 211, 153)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(225, 255, 255, 255)
    }

    private val headBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(180, 147, 197, 253)
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34D399")
    }

    private val jointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(210, 15, 23, 42)
    }

    private val angleTagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(175, 13, 17, 23)
    }

    private val angleTagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val miniMapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(128, 8, 12, 20)
    }

    private val miniMapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(120, 255, 255, 255)
    }

    private val miniMapRoiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.parseColor("#38BDF8")
    }

    private val contentRect = RectF()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isScaling = false
    private var justEndedScale = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            justEndedScale = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (!factor.isFinite() || factor <= 0f) return false
            val nextZoom = ZoomRoiCropper.clampZoom(currentZoom * factor)
            if (nextZoom <= 1.02f) {
                currentPanX = 0f
                currentPanY = 0f
            }
            currentZoom = nextZoom
            onZoomPanChanged?.invoke(currentZoom, currentPanX, currentPanY)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
            justEndedScale = true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentZoom < 1.8f) {
                currentZoom = 2.5f
                val nx = ((e.x / max(1, width)) - 0.5f) * 2f
                val ny = ((e.y / max(1, height)) - 0.5f) * 2f
                currentPanX = nx.coerceIn(-1f, 1f)
                currentPanY = ny.coerceIn(-1f, 1f)
            } else {
                currentZoom = 1.0f
                currentPanX = 0f
                currentPanY = 0f
            }
            onZoomPanChanged?.invoke(currentZoom, currentPanX, currentPanY)
            invalidate()
            return true
        }
    })

    fun setZoomAndPanState(zoomRatio: Float, panX: Float, panY: Float) {
        currentZoom = ZoomRoiCropper.clampZoom(zoomRatio)
        currentPanX = panX.coerceIn(-1f, 1f)
        currentPanY = panY.coerceIn(-1f, 1f)
        invalidate()
    }

    fun updateDetection(
        result: PoseFrameResult?,
        decision: WakeDecisionSnapshot?,
        bitmapW: Int = frameWidthPx,
        bitmapH: Int = frameHeightPx
    ) {
        frameResult = result
        snapshot = decision
        if (bitmapW > 0 && bitmapH > 0) {
            frameWidthPx = bitmapW
            frameHeightPx = bitmapH
        }
        invalidate()
    }

    private fun computeFitCenterRect(viewW: Float, viewH: Float): RectF {
        if (frameWidthPx <= 0 || frameHeightPx <= 0) {
            contentRect.set(0f, 0f, viewW, viewH)
            return contentRect
        }
        val scale = min(viewW / frameWidthPx.toFloat(), viewH / frameHeightPx.toFloat())
        val drawW = frameWidthPx * scale
        val drawH = frameHeightPx * scale
        val left = (viewW - drawW) * 0.5f
        val top = (viewH - drawH) * 0.5f
        contentRect.set(left, top, left + drawW, top + drawH)
        return contentRect
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                justEndedScale = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && !justEndedScale && event.pointerCount == 1 && currentZoom > 1.05f) {
                    val idx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    lastTouchX = x
                    lastTouchY = y
                    val sens = 2.0f / max(1f, currentZoom * 0.55f)
                    currentPanX = (currentPanX - (dx / max(1, width)) * sens).coerceIn(-1f, 1f)
                    currentPanY = (currentPanY - (dy / max(1, height)) * sens).coerceIn(-1f, 1f)
                    onZoomPanChanged?.invoke(currentZoom, currentPanX, currentPanY)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                val upId = event.getPointerId(upIndex)
                if (upId == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScaling = false
                justEndedScale = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 4f || vh <= 4f) return

        val vp = computeFitCenterRect(vw, vh)
        val ox = vp.left
        val oy = vp.top
        val cw = vp.width()
        val ch = vp.height()

        val res = frameResult
        val snap = snapshot
        val isAlarm = snap?.isAlarmRinging == true
        val minConf = (snap?.minConfidenceThreshold ?: 0.45f).coerceIn(0.15f, 0.90f)

        if (res != null && res.targetDetected && res.targetBox != null) {
            val b = res.targetBox
            val rect = RectF(ox + b.left * cw, oy + b.top * ch, ox + b.right * cw, oy + b.bottom * ch)
            val strokeColor = when {
                isAlarm -> Color.parseColor("#FB7185")
                res.poseLabel == BabyPoseLabel.PRONE_HEAD_UP -> Color.parseColor("#F97316")
                res.poseLabel == BabyPoseLabel.LIMB_ACTIVE || res.poseLabel == BabyPoseLabel.SITTING_AWAKE ->
                    Color.parseColor("#FBBF24")
                else -> Color.parseColor("#34D399")
            }
            boxPaint.color = strokeColor
            jointPaint.color = strokeColor
            boxFillPaint.color = Color.argb(
                18,
                Color.red(strokeColor),
                Color.green(strokeColor),
                Color.blue(strokeColor)
            )
            val r = 12f * density
            canvas.drawRoundRect(rect, r, r, boxFillPaint)
            canvas.drawRoundRect(rect, r, r, boxPaint)

            res.keypoints?.let { kp ->
                // Head orientation contour
                drawBone(canvas, kp.leftEar, kp.leftEye, ox, oy, cw, ch, minConf, headBonePaint)
                drawBone(canvas, kp.leftEye, kp.nose, ox, oy, cw, ch, minConf, headBonePaint)
                drawBone(canvas, kp.nose, kp.rightEye, ox, oy, cw, ch, minConf, headBonePaint)
                drawBone(canvas, kp.rightEye, kp.rightEar, ox, oy, cw, ch, minConf, headBonePaint)

                // Torso & limbs
                drawBone(canvas, kp.leftShoulder, kp.rightShoulder, ox, oy, cw, ch, minConf, bonePaint)
                if (kp.leftElbow != null && kp.leftElbow.confidence >= minConf) {
                    drawBone(canvas, kp.leftShoulder, kp.leftElbow, ox, oy, cw, ch, minConf, bonePaint)
                    drawBone(canvas, kp.leftElbow, kp.leftWrist, ox, oy, cw, ch, minConf, bonePaint)
                } else {
                    drawBone(canvas, kp.leftShoulder, kp.leftWrist, ox, oy, cw, ch, minConf, bonePaint)
                }
                if (kp.rightElbow != null && kp.rightElbow.confidence >= minConf) {
                    drawBone(canvas, kp.rightShoulder, kp.rightElbow, ox, oy, cw, ch, minConf, bonePaint)
                    drawBone(canvas, kp.rightElbow, kp.rightWrist, ox, oy, cw, ch, minConf, bonePaint)
                } else {
                    drawBone(canvas, kp.rightShoulder, kp.rightWrist, ox, oy, cw, ch, minConf, bonePaint)
                }

                drawBone(canvas, kp.leftShoulder, kp.leftHip, ox, oy, cw, ch, minConf, bonePaint)
                drawBone(canvas, kp.rightShoulder, kp.rightHip, ox, oy, cw, ch, minConf, bonePaint)
                drawBone(canvas, kp.leftHip, kp.rightHip, ox, oy, cw, ch, minConf, bonePaint)

                if (kp.leftKnee != null && kp.leftKnee.confidence >= minConf) {
                    drawBone(canvas, kp.leftHip, kp.leftKnee, ox, oy, cw, ch, minConf, bonePaint)
                    drawBone(canvas, kp.leftKnee, kp.leftAnkle, ox, oy, cw, ch, minConf, bonePaint)
                } else {
                    drawBone(canvas, kp.leftHip, kp.leftAnkle, ox, oy, cw, ch, minConf, bonePaint)
                }
                if (kp.rightKnee != null && kp.rightKnee.confidence >= minConf) {
                    drawBone(canvas, kp.rightHip, kp.rightKnee, ox, oy, cw, ch, minConf, bonePaint)
                    drawBone(canvas, kp.rightKnee, kp.rightAnkle, ox, oy, cw, ch, minConf, bonePaint)
                } else {
                    drawBone(canvas, kp.rightHip, kp.rightAnkle, ox, oy, cw, ch, minConf, bonePaint)
                }

                val headRef = kp.headReferencePoint(minConf)
                if (headRef != null && kp.leftShoulder != null && kp.rightShoulder != null &&
                    kp.leftShoulder.confidence >= minConf && kp.rightShoulder.confidence >= minConf
                ) {
                    val midShoulder = NormalizedPoint(
                        (kp.leftShoulder.x + kp.rightShoulder.x) * 0.5f,
                        (kp.leftShoulder.y + kp.rightShoulder.y) * 0.5f,
                        min(kp.leftShoulder.confidence, kp.rightShoulder.confidence)
                    )
                    drawBone(canvas, headRef, midShoulder, ox, oy, cw, ch, minConf, bonePaint)
                }

                for (pt in kp.allPoints()) {
                    if (pt.confidence >= minConf) {
                        val jx = ox + pt.x * cw
                        val jy = oy + pt.y * ch
                        val rad = (2.8f + pt.confidence * 1.4f) * density
                        canvas.drawCircle(jx, jy, rad, jointPaint)
                        canvas.drawCircle(jx, jy, rad, jointRingPaint)
                    }
                }

                if (showJointAngleLabels) {
                    val ap = res.angleProfile
                    drawAngleTag(canvas, kp.leftElbow, ap.leftElbowDeg, ox, oy, cw, ch, minConf)
                    drawAngleTag(canvas, kp.rightElbow, ap.rightElbowDeg, ox, oy, cw, ch, minConf)
                    drawAngleTag(canvas, kp.leftKnee, ap.leftKneeDeg, ox, oy, cw, ch, minConf)
                    drawAngleTag(canvas, kp.rightKnee, ap.rightKneeDeg, ox, oy, cw, ch, minConf)
                }
            }
        }

        // Compact ROI minimap in top-right/bottom-right corner when zoomed in (> 1.05x)
        if (currentZoom > 1.05f) {
            val mapW = 58f * density
            val mapH = 42f * density
            val margin = 12f * density
            val mapTop = max(margin, oy + 44f * density)
            val mapRect = RectF(vw - mapW - margin, mapTop, vw - margin, mapTop + mapH)
            canvas.drawRoundRect(mapRect, 8f * density, 8f * density, miniMapBgPaint)
            canvas.drawRoundRect(mapRect, 8f * density, 8f * density, miniMapBorderPaint)

            val crop = ZoomRoiCropper.computeCropRect(1000, 1000, currentZoom, currentPanX, currentPanY)
            val roiLeft = mapRect.left + (crop.left / 1000f) * mapW
            val roiTop = mapRect.top + (crop.top / 1000f) * mapH
            val roiRight = roiLeft + (crop.width / 1000f) * mapW
            val roiBottom = roiTop + (crop.height / 1000f) * mapH
            canvas.drawRoundRect(RectF(roiLeft, roiTop, roiRight, roiBottom), 2f * density, 2f * density, miniMapRoiPaint)
        }
    }

    private fun drawBone(
        canvas: Canvas,
        a: NormalizedPoint?,
        b: NormalizedPoint?,
        ox: Float,
        oy: Float,
        cw: Float,
        ch: Float,
        minConf: Float,
        paint: Paint
    ) {
        if (a == null || b == null || a.confidence < minConf || b.confidence < minConf) return
        canvas.drawLine(ox + a.x * cw, oy + a.y * ch, ox + b.x * cw, oy + b.y * ch, paint)
    }

    private fun drawAngleTag(
        canvas: Canvas,
        pt: NormalizedPoint?,
        deg: Float?,
        ox: Float,
        oy: Float,
        cw: Float,
        ch: Float,
        minConf: Float
    ) {
        if (pt == null || deg == null || pt.confidence < minConf) return
        val label = "${deg.roundToInt()}°"
        val tw = angleTagTextPaint.measureText(label)
        val px = (ox + pt.x * cw + 6f * density).coerceIn(ox + 2f, ox + cw - tw - 10f * density)
        val py = (oy + pt.y * ch - 6f * density).coerceIn(oy + 14f * density, oy + ch - 4f * density)
        val bgRect = RectF(px - 4f * density, py - 10.5f * density, px + tw + 4f * density, py + 3f * density)
        canvas.drawRoundRect(bgRect, 5f * density, 5f * density, angleTagBgPaint)
        canvas.drawText(label, px, py, angleTagTextPaint)
    }
}
