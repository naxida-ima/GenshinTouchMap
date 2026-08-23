package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.nahida.touchmap.model.KeyShape
import com.nahida.touchmap.model.KeyType
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.abs

/**
 * 虚拟按键控件（独立悬浮窗口）。
 * - 运行模式：TAP 点击 / HOLD 按住，注入模拟触摸
 * - 编辑模式：拖动移动位置（raw 绝对坐标计算，保证跟手且不抽搐）；长按进入选点模式
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // 编辑模式拖动状态（基于 raw 绝对坐标，不依赖 View.x 避免累加误差）
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var longPressTriggered = false
    private var movePending = false

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
        val r = minOf(width, height) / 2f - 4f

        paint.style = Paint.Style.FILL
        paint.color = if (key.type == KeyType.HOLD) 0x66FF9800.toInt() else 0x6600BFFF.toInt()

        if (key.shape == KeyShape.RECTANGLE) {
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawRoundRect(rect, 10f, 10f, paint)
        } else {
            canvas.drawCircle(cx, cy, r, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        if (key.shape == KeyShape.RECTANGLE) {
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawRoundRect(rect, 10f, 10f, paint)
        } else {
            canvas.drawCircle(cx, cy, r, paint)
        }

        // 标签
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 13f * resources.displayMetrics.density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (editing) "${key.label}•🎯" else key.label, cx, cy + paint.textSize / 3f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = key.x
                startY = key.y
                moved = false
                longPressTriggered = false
                if (editing) {
                    handler.postDelayed(longPressRunnable, 600)
                } else {
                    triggerDown()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (editing) {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        // raw 绝对坐标：起始位置 + 手指偏移，绝对跟手
                        key.x = (startX + dx / screenW).coerceIn(0f, 1f)
                        key.y = (startY + dy / screenH).coerceIn(0f, 1f)
                        // 帧节流：每帧最多更新一次窗口位置
                        if (!movePending) {
                            movePending = true
                            postOnAnimation {
                                movePending = false
                                OverlayService.instance?.moveKeyWindow(this, key.x, key.y)
                            }
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (editing) {
                    if (moved) {
                        OverlayService.instance?.saveKeys()
                    }
                } else {
                    triggerUp()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (!editing) triggerUp()
                return true
            }
        }
        return true
    }

    private fun triggerDown() {
        if (key.type == KeyType.HOLD) {
            onKeyEvent(key, fingerId, "press", targetXPx(), targetYPx())
        } else {
            onKeyEvent(key, fingerId, "tap", targetXPx(), targetYPx())
        }
    }

    private fun triggerUp() {
        if (key.type == KeyType.HOLD) {
            onKeyEvent(key, fingerId, "release", targetXPx(), targetYPx())
        }
    }

    private fun targetXPx(): Float = if (key.targetX >= 0f) key.targetX * screenW else 0f
    private fun targetYPx(): Float = if (key.targetY >= 0f) key.targetY * screenH else 0f

    fun setEditing(e: Boolean) {
        editing = e
        invalidate()
    }
}
