package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 虚拟摇杆控件（独立悬浮窗口）。
 *
 * 运行模式：按住 + 拖动 -> 向目标摇杆注入「按下(base 中心) + 方向位移」。
 *   - 第一次按下先注入 base 中心（press），之后每次拖动注入位移（move）。
 *   - 偏移按 base 半径归一化后乘以目标摇杆满行程半径。
 * 编辑模式：拖动移动位置；右下角把手等比缩放；按住 600ms 进入映射选点。
 */
class JoystickView(
    context: Context,
    private val key: VirtualKey,
    private val fingerId: Int,
    private var editing: Boolean,
    private val screenW: Int,
    private val screenH: Int,
    private val onKeyEvent: (VirtualKey, Int, String, Float, Float) -> Unit,
    private val onPickRequest: (VirtualKey) -> Unit,
    /** 双机发射模式：move 发归一化向量（接收端按摇杆中心+半径换算） */
    private val remoteMode: Boolean = false
) : View(context) {

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
        private const val MODE_DRAG = 3
        private const val LONG_PRESS_MS = 600
    }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private var gestureMode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var moved = false

    /** 运行模式按下状态：保证 press 与 release 严格配对 */
    private var downActive = false

    /** 摇杆可视半径（px）：按按键可视尺寸（排除触摸余量 padding） */
    private val baseRadius: Float
        get() = minOf(key.width, key.height) / 2f * density - 8f

    private fun targetCX(): Float = if (key.targetX >= 0f) key.targetX * screenW else screenW / 2f
    private fun targetCY(): Float = if (key.targetY >= 0f) key.targetY * screenH else screenH / 2f

    /** 目标摇杆满行程半径（像素）= joystickRadius(百分比) × 屏幕高度 */
    private fun targetRadiusPx(): Float = key.joystickRadius * screenH

    private val longPressRunnable = Runnable {
        if (editing && gestureMode == MODE_MOVE) {
            onPickRequest(key)
        }
    }

    init {
        alpha = key.opacity
    }

    private fun isInHandleArea(e: MotionEvent): Boolean {
        val handleSize = 36f * density
        val cx = width / 2f
        val cy = height / 2f
        val rw = key.width * density / 2f
        val rh = key.height * density / 2f
        return e.x > cx + rw - handleSize && e.y > cy + rh - handleSize
    }

    private fun triggerDown() {
        if (editing) return
        if (remoteMode) {
            onKeyEvent(key, fingerId, "press", 0f, 0f)
            return
        }
        if (key.targetX < 0f || key.targetY < 0f) return
        onKeyEvent(key, fingerId, "press", targetCX(), targetCY())
    }

    private fun triggerMove(ratioX: Float, ratioY: Float) {
        if (editing) return
        if (remoteMode) {
            // 发射端：发归一化向量，接收端按摇杆中心+半径换算
            onKeyEvent(key, fingerId, "move", ratioX, ratioY)
            return
        }
        onKeyEvent(
            key, fingerId, "move",
            targetCX() + ratioX * targetRadiusPx(),
            targetCY() + ratioY * targetRadiusPx()
        )
    }

    private fun triggerUp() {
        if (editing) return
        onKeyEvent(key, fingerId, "release", 0f, 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("TouchMap", "JSV ${key.label} ${MotionEvent.actionToString(event.actionMasked)} " +
                "t=${SystemClock.uptimeMillis()} x=${event.x.toInt()} y=${event.y.toInt()}")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startX = key.x
                startY = key.y
                startW = key.width
                startH = key.height
                moved = false
                if (editing) {
                    gestureMode = if (isInHandleArea(event)) MODE_RESIZE else MODE_MOVE
                    if (gestureMode == MODE_MOVE) {
                        handler.postDelayed(longPressRunnable, LONG_PRESS_MS.toLong())
                    }
                } else {
                    gestureMode = MODE_DRAG
                    knobX = 0f
                    knobY = 0f
                    if (!downActive) {
                        downActive = true
                        triggerDown()
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editing) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved && hypot(dx, dy) > touchSlop) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        when (gestureMode) {
                            MODE_MOVE -> moveWindow(dx, dy)
                            MODE_RESIZE -> resizeWindow(dx, dy)
                        }
                    }
                } else if (gestureMode == MODE_DRAG) {
                    updateKnobAndInject(event)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (editing) {
                    if (moved) save()
                } else if (gestureMode == MODE_DRAG) {
                    gestureMode = MODE_NONE
                    knobX = 0f
                    knobY = 0f
                    if (downActive) {
                        downActive = false
                        triggerUp()
                    }
                    invalidate()
                }
                gestureMode = MODE_NONE
                return true
            }
        }
        return true
    }

    private fun updateKnobAndInject(event: MotionEvent) {
        val cx = width / 2f
        val cy = height / 2f
        var dx = event.x - cx
        var dy = event.y - cy
        val maxR = baseRadius.coerceAtLeast(1f)
        val dist = hypot(dx, dy)
        if (dist > maxR) {
            dx *= maxR / dist
            dy *= maxR / dist
        }
        knobX = dx
        knobY = dy
        val ratioX = dx / maxR
        val ratioY = dy / maxR
        triggerMove(ratioX, ratioY)
        invalidate()
    }

    private fun moveWindow(dx: Float, dy: Float) {
        key.x = (startX + dx / screenW).coerceIn(0f, 1f)
        key.y = (startY + dy / screenH).coerceIn(0f, 1f)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val lp = layoutParams as android.view.WindowManager.LayoutParams
        lp.x = (key.x * screenW - width / 2f).roundToInt()
        lp.y = (key.y * screenH - height / 2f).roundToInt()
        wm.updateViewLayout(this, lp)
    }

    private fun resizeWindow(dx: Float, dy: Float) {
        val newW = max(36f, startW + dx / density)
        val newH = max(36f, startH + dy / density)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val lp = layoutParams as android.view.WindowManager.LayoutParams
        val padPx = (28 * density).toInt()
        lp.width = (newW * density + padPx * 2).toInt()
        lp.height = (newH * density + padPx * 2).toInt()
        lp.x = (key.x * screenW - lp.width / 2f).roundToInt()
        lp.y = (key.y * screenH - lp.height / 2f).roundToInt()
        wm.updateViewLayout(this, lp)
        key.width = newW
        key.height = newH
    }

    private fun save() {
        OverlayService.instance?.saveKeys()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        basePaint.style = Paint.Style.FILL
        basePaint.color = 0x59000000.toInt()
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        basePaint.style = Paint.Style.STROKE
        basePaint.strokeWidth = 2f
        basePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, baseRadius, basePaint)

        val knobR = baseRadius * 0.45f
        knobPaint.style = Paint.Style.FILL
        knobPaint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)
        knobPaint.style = Paint.Style.STROKE
        knobPaint.strokeWidth = 2f
        knobPaint.color = Color.WHITE
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)

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
            canvas.drawLine(
                cx + baseRadius - hs * 1.1f, cy + baseRadius - hs * 0.5f,
                cx + baseRadius - hs * 0.5f, cy + baseRadius - hs * 1.1f, basePaint
            )
        }
    }
}
