package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 虚拟摇杆控件（独立悬浮窗口）。
 * - 运行模式：按住 + 拖动 -> 注入「按下 + 方向位移」，位移比例映射到目标摇杆
 * - 编辑模式：拖动移动位置；右下角把手等比缩放；长按设置摇杆映射中心
 */
class JoystickView(
    context: Context,
    private val key: VirtualKey,
    private val fingerId: Int,
    private var editing: Boolean,
    private val screenW: Int,
    private val screenH: Int,
    private val onKeyEvent: (VirtualKey, Int, String, Float, Float) -> Unit,
    private val onPickRequest: (VirtualKey) -> Unit
) : View(context) {

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
        private const val MIN_SIZE = 48f
    }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private var gestureMode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var moved = false
    private var longPressTriggered = false
    private var layoutPending = false

    /** 摇杆可视半径（px）：按按键可视尺寸（排除触摸余量 padding） */
    private val baseRadius: Float
        get() = minOf(key.width, key.height) / 2f * resources.displayMetrics.density - 8f

    /** 摇杆映射中心（像素） */
    private fun targetCX(): Float = if (key.targetX >= 0f) key.targetX * screenW else screenW / 2f
    private fun targetCY(): Float = if (key.targetY >= 0f) key.targetY * screenH else screenH / 2f

    /** 目标摇杆满行程半径（像素）= joystickRadius(百分比) x 屏幕高度 */
    private fun targetRadiusPx(): Float = key.joystickRadius * screenH

    private val longPressRunnable = Runnable {
        if (editing && gestureMode == MODE_MOVE) {
            longPressTriggered = true
            onPickRequest(key)
        }
    }

    private fun isInHandleArea(e: MotionEvent): Boolean {
        val handleSize = 36f * density
        val cx = width / 2f
        val cy = height / 2f
        return e.x > cx + baseRadius - handleSize && e.y > cy + baseRadius - handleSize
    }

    init {
        alpha = key.opacity
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // 外圈
        basePaint.style = Paint.Style.FILL
        basePaint.color = 0x59000000.toInt()
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        basePaint.style = Paint.Style.STROKE
        basePaint.strokeWidth = 2f
        basePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, baseRadius, basePaint)

        // 摇杆帽
        val knobR = baseRadius * 0.45f
        knobPaint.style = Paint.Style.FILL
        knobPaint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)
        knobPaint.style = Paint.Style.STROKE
        knobPaint.strokeWidth = 2f
        knobPaint.color = Color.WHITE
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)

        // 编辑模式：映射中心指示 + 缩放把手（可视区右下角）
        if (editing) {
            basePaint.style = Paint.Style.STROKE
            basePaint.color = 0x88FF5722.toInt()
            canvas.drawLine(cx, cy, targetCX() - x, targetCY() - y, basePaint)

            val hs = 14f * density
            basePaint.style = Paint.Style.FILL
            basePaint.color = 0xCC00E676.toInt()
            canvas.drawCircle(cx + baseRadius, cy + baseRadius, hs, basePaint)
            basePaint.strokeWidth = 2f
            basePaint.color = Color.WHITE
            canvas.drawLine(cx + baseRadius - hs * 1.1f, cy + baseRadius - hs * 0.5f, cx + baseRadius - hs * 0.5f, cy + baseRadius - hs * 1.1f, basePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                startX = key.x
                startY = key.y
                startW = key.width
                startH = key.height
                moved = false
                longPressTriggered = false
                if (editing) {
                    gestureMode = if (isInHandleArea(event)) MODE_RESIZE else MODE_MOVE
                    if (gestureMode == MODE_MOVE) {
                        handler.postDelayed(longPressRunnable, 600)
                    }
                } else {
                    gestureMode = MODE_NONE
                    knobX = 0f
                    knobY = 0f
                    onKeyEvent(key, fingerId, "press", targetCX(), targetCY())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (editing) {
                    when (gestureMode) {
                        MODE_RESIZE -> {
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            val newSize = max(MIN_SIZE, startW + max(dx, dy))
                            key.width = newSize
                            key.height = newSize
                            scheduleLayout()
                        }

                        MODE_MOVE -> {
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                moved = true
                                handler.removeCallbacks(longPressRunnable)
                            }
                            if (moved) {
                                key.x = (startX + dx / screenW).coerceIn(0f, 1f)
                                key.y = (startY + dy / screenH).coerceIn(0f, 1f)
                                scheduleLayout()
                            }
                        }
                    }
                } else {
                    // 摇杆偏移（限制在可视半径内）
                    var dx = event.x - cx
                    var dy = event.y - cy
                    val dist = hypot(dx, dy)
                    val maxR = baseRadius
                    if (dist > maxR) {
                        dx = dx / dist * maxR
                        dy = dy / dist * maxR
                    }
                    knobX = dx
                    knobY = dy
                    invalidate()

                    // 映射到目标摇杆：偏移比例 x 目标半径
                    val scale = targetRadiusPx() / maxR
                    val tx = targetCX() + dx * scale
                    val ty = targetCY() + dy * scale
                    onKeyEvent(key, fingerId, "move", tx, ty)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (editing) {
                    if ((gestureMode == MODE_MOVE && moved) || gestureMode == MODE_RESIZE) {
                        OverlayService.instance?.saveKeys()
                    }
                } else {
                    knobX = 0f
                    knobY = 0f
                    invalidate()
                    onKeyEvent(key, fingerId, "release", targetCX(), targetCY())
                }
                gestureMode = MODE_NONE
                return true
            }
        }
        return true
    }

    private fun scheduleLayout() {
        if (layoutPending) return
        layoutPending = true
        postOnAnimation {
            layoutPending = false
            val svc = OverlayService.instance ?: return@postOnAnimation
            if (gestureMode == MODE_RESIZE) {
                svc.resizeKeyWindow(this, key.width, key.height)
            } else {
                svc.moveKeyWindow(this, key.x, key.y)
            }
        }
    }

    fun setEditing(e: Boolean) {
        editing = e
        invalidate()
    }
}
