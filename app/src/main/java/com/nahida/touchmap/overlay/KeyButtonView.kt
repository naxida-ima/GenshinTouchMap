package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.nahida.touchmap.model.KeyType
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 虚拟按键控件（独立悬浮窗口）。
 * - 运行模式：TAP 点击 / HOLD 按住，注入模拟触摸
 * - 编辑模式：拖动移动位置；长按进入选点模式设置映射目标
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
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressTriggered = false

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

        // 背景圆
        paint.style = Paint.Style.FILL
        paint.color = if (key.type == KeyType.HOLD) 0x66FF9800 else 0x6600BFFF
        canvas.drawCircle(cx, cy, r, paint)

        // 描边
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)

        // 标签
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 13f * resources.displayMetrics.density
        paint.textAlign = Paint.Align.CENTER
        val text = if (editing) "${key.label}•🎯" else key.label
        canvas.drawText(text, cx, cy + paint.textSize / 3f, paint)

        // 编辑模式：画映射目标指示线
        if (editing && key.targetX >= 0f) {
            paint.color = 0x88FF5722
            paint.strokeWidth = 2f
            canvas.drawLine(cx, cy, key.targetX * screenW - x, key.targetY * screenH - y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
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
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                        // 拖动：更新窗口位置（视图坐标 -> 屏幕百分比）
                        val screenX = (x + event.x) / screenW
                        val screenY = (y + event.y) / screenH
                        key.x = screenX
                        key.y = screenY
                        OverlayService.instance?.moveKeyWindow(this, screenX, screenY)
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
                    // 选点已触发或单击：忽略
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
            // 长按：按住期间持续按压
            onKeyEvent(key, fingerId, "press", targetXPx(), targetYPx())
        } else {
            // 点击：press + 延迟 release
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
