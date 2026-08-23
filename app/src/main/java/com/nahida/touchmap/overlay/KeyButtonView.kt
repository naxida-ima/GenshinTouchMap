package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.nahida.touchmap.model.KeyShape
import com.nahida.touchmap.model.KeyType
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.abs
import kotlin.math.max

/**
 * 虚拟按键控件（独立悬浮窗口）。
 * - 运行模式：TAP 点击 / HOLD 按住，注入模拟触摸
 * - 编辑模式：
 *   - 拖动主体 → 移动位置（raw 绝对坐标，跟手不抽搐）
 *   - 拖动右下角把手 → 调整大小（圆形等比缩放，矩形可独立拉宽/拉高）
 *   - 长按 → 进入选点模式
 * - 未设置映射目标时，编辑模式显示「未映射」提示
 */
class KeyButtonView(
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
        private const val MIN_SIZE = 32f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private var gestureMode = MODE_NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private var moved = false
    private var longPressTriggered = false
    private var layoutPending = false

    private val longPressRunnable = Runnable {
        if (editing && gestureMode == MODE_MOVE) {
            longPressTriggered = true
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // 可视尺寸（排除触摸余量 padding，内容居中绘制）
        val rw = max(0f, key.width * density / 2f - 4f)
        val rh = max(0f, key.height * density / 2f - 4f)

        paint.style = Paint.Style.FILL
        paint.color = if (key.type == KeyType.HOLD) 0x66FF9800.toInt() else 0x6600BFFF.toInt()
        if (key.shape == KeyShape.RECTANGLE) {
            canvas.drawRoundRect(RectF(cx - rw, cy - rh, cx + rw, cy + rh), 10f, 10f, paint)
        } else {
            canvas.drawCircle(cx, cy, minOf(rw, rh), paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        if (key.shape == KeyShape.RECTANGLE) {
            canvas.drawRoundRect(RectF(cx - rw, cy - rh, cx + rw, cy + rh), 10f, 10f, paint)
        } else {
            canvas.drawCircle(cx, cy, minOf(rw, rh), paint)
        }

        // 标签
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 13f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (editing) "${key.label}•🎯" else key.label, cx, cy + paint.textSize / 3f, paint)

        // 编辑模式：右下角缩放把手（可视区右下角）
        if (editing) {
            val hs = 14f * density
            paint.style = Paint.Style.FILL
            paint.color = 0xCC00E676.toInt()
            canvas.drawCircle(cx + rw, cy + rh, hs, paint)
            paint.strokeWidth = 2f
            paint.color = Color.WHITE
            canvas.drawLine(cx + rw - hs * 1.1f, cy + rh - hs * 0.5f, cx + rw - hs * 0.5f, cy + rh - hs * 1.1f, paint)
        }

        // 编辑模式：未映射提示
        if (editing && key.targetX < 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF5252")
            paint.textSize = 11f * density
            canvas.drawText("未映射", cx, cy + rh + 14f * density, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        android.util.Log.d("TouchMap", "KBV ${key.label} ${event.actionToString(event.actionMasked)} " +
                "t=${SystemClock.uptimeMillis()} x=${event.x.toInt()} y=${event.y.toInt()} raw=(${event.rawX.toInt()},${event.rawY.toInt()})")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                    triggerDown()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (editing) {
                    when (gestureMode) {
                        MODE_RESIZE -> {
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (key.shape == KeyShape.RECTANGLE) {
                                key.width = max(MIN_SIZE, startW + dx)
                                key.height = max(MIN_SIZE, startH + dy)
                            } else {
                                val newSize = max(MIN_SIZE, startW + max(dx, dy))
                                key.width = newSize
                                key.height = newSize
                            }
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
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (editing) {
                    if (gestureMode == MODE_MOVE && moved) {
                        OverlayService.instance?.saveKeys()
                    } else if (gestureMode == MODE_RESIZE) {
                        OverlayService.instance?.saveKeys()
                    }
                } else {
                    triggerUp()
                }
                gestureMode = MODE_NONE
                return true
            }
        }
        return true
    }

    /** 帧节流：每帧最多一次窗口布局更新（移动/缩放共用） */
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

    private fun triggerDown() {
        // 统一行为：按下即注入 press（按住持续 = 连发，快速抬起 = 单点）
        onKeyEvent(key, fingerId, "press", targetXPx(), targetYPx())
    }

    private fun triggerUp() {
        onKeyEvent(key, fingerId, "release", targetXPx(), targetYPx())
    }

    private fun targetXPx(): Float = if (key.targetX >= 0f) key.targetX * screenW else 0f
    private fun targetYPx(): Float = if (key.targetY >= 0f) key.targetY * screenH else 0f

    fun setEditing(e: Boolean) {
        editing = e
        invalidate()
    }
}
