package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/**
 * Interactive overlay rendered above the zoomed camera preview:
 * - Draws detected baby target bounding box, skeletal pose lines/keypoints, and wake alert state
 * - Draws a picture-in-picture zoom ROI minimap when zoom > 1.0x
 * - Supports 2-finger pinch-to-zoom (1.0x - 8.0x), 1-finger pan when zoomed in, and double-tap zoom toggle
 */
class PoseOverlayAndZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onZoomPanChanged: ((zoomRatio: Float, panX: Float, panY: Float) -> Unit)? = null

    private var currentZoom: Float = 1.8f
    private var currentPanX: Float = 0f
    private var currentPanY: Float = 0f
    private var frameResult: PoseFrameResult? = null
    private var snapshot: WakeDecisionSnapshot? = null

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#2EC4B6")
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(32, 46, 196, 182)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.parseColor("#FFD166")
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 15, 23, 42)
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        isFakeBoldText = true
    }

    private val miniMapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 10, 14, 24)
    }

    private val miniMapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(180, 203, 213, 225)
    }

    private val miniMapRoiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.parseColor("#38BDF8")
    }

    private val guideCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(90, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextZoom = ZoomRoiCropper.clampZoom(currentZoom * detector.scaleFactor)
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
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentZoom < 2.2f) {
                currentZoom = 3.2f
                // Center zoom toward tap position
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
                lastTouchX = event.x
                lastTouchY = event.y
                if (currentZoom > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && event.pointerCount == 1 && currentZoom > 1.05f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    // Dragging finger right moves view left across the zoomed image
                    val sens = 2.2f / max(1f, currentZoom * 0.55f)
                    currentPanX = (currentPanX - (dx / max(1, width)) * sens).coerceIn(-1f, 1f)
                    currentPanY = (currentPanY - (dy / max(1, height)) * sens).coerceIn(-1f, 1f)
                    onZoomPanChanged?.invoke(currentZoom, currentPanX, currentPanY)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScaling = false
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

        // Subtle center guide lines
        canvas.drawLine(w * 0.5f, h * 0.44f, w * 0.5f, h * 0.56f, guideCrossPaint)
        canvas.drawLine(w * 0.44f, h * 0.5f, w * 0.56f, h * 0.5f, guideCrossPaint)

        if (res != null && res.targetDetected && res.targetBox != null) {
            val b = res.targetBox
            val rect = RectF(b.left * w, b.top * h, b.right * w, b.bottom * h)
            val strokeColor = when {
                isAlarm -> Color.parseColor("#FF3B5C")
                res.poseLabel == BabyPoseLabel.LIMB_ACTIVE || res.poseLabel == BabyPoseLabel.SITTING_AWAKE ->
                    Color.parseColor("#FF9F1C")
                else -> Color.parseColor("#2EC4B6")
            }
            boxPaint.color = strokeColor
            boxFillPaint.color = Color.argb(
                28,
                Color.red(strokeColor),
                Color.green(strokeColor),
                Color.blue(strokeColor)
            )
            val r = 10f * density
            canvas.drawRoundRect(rect, r, r, boxFillPaint)
            canvas.drawRoundRect(rect, r, r, boxPaint)

            // Draw skeletal pose lines & joints
            res.keypoints?.let { kp ->
                drawBone(canvas, kp.leftShoulder, kp.rightShoulder, w, h)
                drawBone(canvas, kp.leftShoulder, kp.leftWrist, w, h)
                drawBone(canvas, kp.rightShoulder, kp.rightWrist, w, h)
                drawBone(canvas, kp.leftShoulder, kp.leftHip, w, h)
                drawBone(canvas, kp.rightShoulder, kp.rightHip, w, h)
                drawBone(canvas, kp.leftHip, kp.rightHip, w, h)
                drawBone(canvas, kp.leftHip, kp.leftAnkle, w, h)
                drawBone(canvas, kp.rightHip, kp.rightAnkle, w, h)
                if (kp.nose != null && kp.leftShoulder != null && kp.rightShoulder != null) {
                    val midShoulder = NormalizedPoint(
                        (kp.leftShoulder.x + kp.rightShoulder.x) * 0.5f,
                        (kp.leftShoulder.y + kp.rightShoulder.y) * 0.5f
                    )
                    drawBone(canvas, kp.nose, midShoulder, w, h)
                }
                for (pt in kp.allPoints()) {
                    if (pt.confidence >= 0.3f) {
                        canvas.drawCircle(pt.x * w, pt.y * h, 4f * density, jointPaint)
                    }
                }
            }

            // Draw pose tag above target box
            val tagText = "${res.poseLabel.zhLabel} (${(res.confidence * 100).toInt()}%)"
            val textW = labelTextPaint.measureText(tagText)
            val padH = 8f * density
            val tagH = 22f * density
            val tagLeft = rect.left.coerceIn(6f * density, (w - textW - padH * 2 - 6f * density).coerceAtLeast(6f * density))
            val tagTop = if (rect.top > tagH + 8f * density) rect.top - tagH - 4f * density else rect.top + 4f * density
            val tagRect = RectF(tagLeft, tagTop, tagLeft + textW + padH * 2, tagTop + tagH)
            canvas.drawRoundRect(tagRect, 8f * density, 8f * density, labelBgPaint)
            canvas.drawText(tagText, tagLeft + padH, tagTop + tagH - 6.5f * density, labelTextPaint)
        }

        // Draw Zoom ROI Minimap when zoomed in (> 1.05x)
        if (currentZoom > 1.05f) {
            val mapW = 74f * density
            val mapH = 52f * density
            val margin = 10f * density
            val mapRect = RectF(w - mapW - margin, margin, w - margin, margin + mapH)
            canvas.drawRoundRect(mapRect, 6f * density, 6f * density, miniMapBgPaint)
            canvas.drawRoundRect(mapRect, 6f * density, 6f * density, miniMapBorderPaint)

            val crop = ZoomRoiCropper.computeCropRect(1000, 1000, currentZoom, currentPanX, currentPanY)
            val roiLeft = mapRect.left + (crop.left / 1000f) * mapW
            val roiTop = mapRect.top + (crop.top / 1000f) * mapH
            val roiRight = roiLeft + (crop.width / 1000f) * mapW
            val roiBottom = roiTop + (crop.height / 1000f) * mapH
            canvas.drawRect(roiLeft, roiTop, roiRight, roiBottom, miniMapRoiPaint)
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
