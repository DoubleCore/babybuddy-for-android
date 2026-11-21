package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/**
 * Minimalist interactive overlay rendered above the camera preview:
 * - Supports smooth 2-finger pinch-to-zoom (1.0x - 8.0x), 1-finger pan when zoomed in,
 *   and double-tap zoom toggle
 * - Draws a clean target bounding box, minimal skeleton overlay, and a compact ROI minimap when zoomed
 */
class PoseOverlayAndZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onZoomPanChanged: ((zoomRatio: Float, panX: Float, panY: Float) -> Unit)? = null

    private var currentZoom: Float = 1.0f
    private var currentPanX: Float = 0f
    private var currentPanY: Float = 0f
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
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(210, 255, 255, 255)
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34D399")
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

    fun updateDetection(result: PoseFrameResult?, decision: WakeDecisionSnapshot?) {
        frameResult = result
        snapshot = decision
        invalidate()
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val res = frameResult
        val snap = snapshot
        val isAlarm = snap?.isAlarmRinging == true

        if (res != null && res.targetDetected && res.targetBox != null) {
            val b = res.targetBox
            val rect = RectF(b.left * w, b.top * h, b.right * w, b.bottom * h)
            val strokeColor = when {
                isAlarm -> Color.parseColor("#FB7185")
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
                drawBone(canvas, kp.leftShoulder, kp.rightShoulder, w, h)
                if (kp.leftElbow != null && kp.leftElbow.confidence >= 0.3f) {
                    drawBone(canvas, kp.leftShoulder, kp.leftElbow, w, h)
                    drawBone(canvas, kp.leftElbow, kp.leftWrist, w, h)
                } else {
                    drawBone(canvas, kp.leftShoulder, kp.leftWrist, w, h)
                }
                if (kp.rightElbow != null && kp.rightElbow.confidence >= 0.3f) {
                    drawBone(canvas, kp.rightShoulder, kp.rightElbow, w, h)
                    drawBone(canvas, kp.rightElbow, kp.rightWrist, w, h)
                } else {
                    drawBone(canvas, kp.rightShoulder, kp.rightWrist, w, h)
                }

                drawBone(canvas, kp.leftShoulder, kp.leftHip, w, h)
                drawBone(canvas, kp.rightShoulder, kp.rightHip, w, h)
                drawBone(canvas, kp.leftHip, kp.rightHip, w, h)

                if (kp.leftKnee != null && kp.leftKnee.confidence >= 0.3f) {
                    drawBone(canvas, kp.leftHip, kp.leftKnee, w, h)
                    drawBone(canvas, kp.leftKnee, kp.leftAnkle, w, h)
                } else {
                    drawBone(canvas, kp.leftHip, kp.leftAnkle, w, h)
                }
                if (kp.rightKnee != null && kp.rightKnee.confidence >= 0.3f) {
                    drawBone(canvas, kp.rightHip, kp.rightKnee, w, h)
                    drawBone(canvas, kp.rightKnee, kp.rightAnkle, w, h)
                } else {
                    drawBone(canvas, kp.rightHip, kp.rightAnkle, w, h)
                }

                if (kp.nose != null && kp.leftShoulder != null && kp.rightShoulder != null) {
                    val midShoulder = NormalizedPoint(
                        (kp.leftShoulder.x + kp.rightShoulder.x) * 0.5f,
                        (kp.leftShoulder.y + kp.rightShoulder.y) * 0.5f
                    )
                    drawBone(canvas, kp.nose, midShoulder, w, h)
                }

                for (pt in kp.allPoints()) {
                    if (pt.confidence >= 0.3f) {
                        canvas.drawCircle(pt.x * w, pt.y * h, 3.0f * density, jointPaint)
                    }
                }
            }
        }

        // Compact ROI minimap in bottom-right corner when zoomed in (> 1.05x)
        if (currentZoom > 1.05f) {
            val mapW = 58f * density
            val mapH = 42f * density
            val margin = 12f * density
            val mapRect = RectF(w - mapW - margin, h - mapH - margin, w - margin, h - margin)
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
        w: Float,
        h: Float
    ) {
        if (a == null || b == null || a.confidence < 0.3f || b.confidence < 0.3f) return
        canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, bonePaint)
    }
}
