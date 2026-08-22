package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 虚拟摇杆控件（独立悬浮窗口）。
 * - 运行模式：按住 + 拖动 -> 注入「按下 + 方向位移」，位移比例映射到目标摇杆
 * - 编辑模式：拖动移动位置；长按设置摇杆映射中心
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

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var moved = false
    private var longPressTriggered = false

    /** 摇杆可视半径（px） */
    private val baseRadius: Float
        get() = minOf(width, height) / 2f - 8f

    /** 摇杆映射中心（像素） */
    private fun targetCX(): Float = if (key.targetX >= 0f) key.targetX * screenW else screenW / 2f
    private fun targetCY(): Float = if (key.targetY >= 0f) key.targetY * screenH else screenH / 2f

    /** 目标摇杆满行程半径（像素）= joystickRadius(百分比) x 屏幕高度 */
    private fun targetRadiusPx(): Float = key.joystickRadius * screenH

    private val longPressRunnable = Runnable {
        if (editing) {
            longPressTriggered = true
            onPickRequest(key)
        }
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
        basePaint.color = 0x59000000
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        basePaint.style = Paint.Style.STROKE
        basePaint.strokeWidth = 2f
        basePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, baseRadius, basePaint)

        // 摇杆帽
        val knobR = baseRadius * 0.45f
        knobPaint.style = Paint.Style.FILL
        knobPaint.color = 0x99FFFFFF
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)
        knobPaint.style = Paint.Style.STROKE
        knobPaint.strokeWidth = 2f
        knobPaint.color = Color.WHITE
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)

        // 编辑模式：画映射中心指示
        if (editing) {
            basePaint.style = Paint.Style.STROKE
            basePaint.color = 0x88FF5722
            canvas.drawLine(cx, cy, targetCX() - x, targetCY() - y, basePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                longPressTriggered = false
                if (editing) {
                    handler.postDelayed(longPressRunnable, 600)
                } else {
                    knobX = 0f
                    knobY = 0f
                    onKeyEvent(key, fingerId, "press", targetCX(), targetCY())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (editing) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                        val screenX = (x + event.x) / screenW
                        val screenY = (y + event.y) / screenH
                        key.x = screenX
                        key.y = screenY
                        OverlayService.instance?.moveKeyWindow(this, screenX, screenY)
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
                    if (moved) {
                        OverlayService.instance?.saveKeys()
                    }
                } else {
                    knobX = 0f
                    knobY = 0f
                    invalidate()
                    onKeyEvent(key, fingerId, "release", targetCX(), targetCY())
                }
                return true
            }
        }
        return true
    }

    fun setEditing(e: Boolean) {
        editing = e
        invalidate()
    }
}
