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
import com.nahida.touchmap.model.KeyType
import com.nahida.touchmap.model.VirtualKey
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 虚拟按键控件（独立悬浮窗口）。
 *
 * 运行模式：单纯把触摸转成 press / release —— 按住即注入「按下并保持」，抬起才注入「抬起」，
 *           不存在「自动长按」逻辑（长按 = 不抬起，由注入器持续保持即可）。
 * 编辑模式：拖动移动位置；右下角把手等比缩放；按住 600ms 进入映射选点。
 */
class KeyButtonView(
    context: Context,
    private val key: VirtualKey,
    private val fingerId: Int,
    private var editing: Boolean,
    private val screenW: Int,
    private val screenH: Int,
    private val onKeyEvent: (VirtualKey, Int, String, Float, Float) -> Unit,
    private val onPickRequest: (VirtualKey) -> Unit,
    /** 双机发射模式：press/release 只发 keyId+手势，坐标置 0（接收端按配置换算） */
    private val remoteMode: Boolean = false
) : View(context) {

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
        private const val LONG_PRESS_MS = 600
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private var moved = false

    /** 运行模式按下状态：保证 press 与 release 严格配对，杜绝重复注入 */
    private var downActive = false

    private val longPressRunnable = Runnable {
        if (editing && gestureMode == MODE_MOVE) {
            onPickRequest(key)
        }
    }

    init {
        alpha = key.opacity
    }

    /** 运行模式：按下注入目标点；编辑模式不注入；发射模式只发 keyId */
    private fun triggerDown() {
        if (editing) return
        if (remoteMode) {
            onKeyEvent(key, fingerId, "press", 0f, 0f)
            return
        }
        if (key.targetX < 0f || key.targetY < 0f) return
        val tx = key.targetX * screenW
        val ty = key.targetY * screenH
        onKeyEvent(key, fingerId, "press", tx, ty)
    }

    /** 运行模式：抬起注入释放；编辑模式不注入；发射模式只发 keyId */
    private fun triggerUp() {
        if (editing) return
        onKeyEvent(key, fingerId, "release", 0f, 0f)
    }

    private fun isInHandleArea(e: MotionEvent): Boolean {
        val handleSize = 36f * density
        val cx = width / 2f
        val cy = height / 2f
        val rw = key.width * density / 2f
        val rh = key.height * density / 2f
        return e.x > cx + rw - handleSize && e.y > cy + rh - handleSize
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("TouchMap", "KBV ${key.label} ${MotionEvent.actionToString(event.actionMasked)} " +
                "t=${SystemClock.uptimeMillis()} x=${event.x.toInt()} y=${event.y.toInt()} raw=(${event.rawX.toInt()},${event.rawY.toInt()})")
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
                    gestureMode = MODE_NONE
                    if (!downActive) {
                        downActive = true
                        triggerDown()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editing) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved && kotlin.math.hypot(dx, dy) > touchSlop) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        when (gestureMode) {
                            MODE_MOVE -> {
                                key.x = (startX + dx / screenW).coerceIn(0f, 1f)
                                key.y = (startY + dy / screenH).coerceIn(0f, 1f)
                                val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                                val lp = layoutParams as android.view.WindowManager.LayoutParams
                                lp.x = (key.x * screenW - width / 2f).roundToInt()
                                lp.y = (key.y * screenH - height / 2f).roundToInt()
                                wm.updateViewLayout(this, lp)
                            }
                            MODE_RESIZE -> {
                                val newW = max(36f, startW + dx / density)
                                val newH = max(36f, startH + dy / density)
                                val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                                val lp = layoutParams as android.view.WindowManager.LayoutParams
                                val padPx = (28 * density).toInt()
                                val wPx = (newW * density + padPx * 2).toInt()
                                val hPx = (newH * density + padPx * 2).toInt()
                                lp.width = wPx
                                lp.height = hPx
                                lp.x = (key.x * screenW - wPx / 2f).roundToInt()
                                lp.y = (key.y * screenH - hPx / 2f).roundToInt()
                                wm.updateViewLayout(this, lp)
                                key.width = newW
                                key.height = newH
                            }
                        }
                    }
                }
                // 运行模式下手指在按键内微动：不处理（靠触摸余量防滑出）
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (editing) {
                    if (moved && gestureMode == MODE_MOVE) save()
                    else if (gestureMode == MODE_RESIZE) save()
                } else {
                    if (downActive) {
                        downActive = false
                        triggerUp()
                    }
                }
                gestureMode = MODE_NONE
                return true
            }
        }
        return true
    }

    private fun save() {
        OverlayService.instance?.saveKeys()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // 可视尺寸（排除触摸余量 padding，内容居中绘制）
        val rw = max(0f, key.width * density / 2f - 4f)
        val rh = max(0f, key.height * density / 2f - 4f)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#66000000")
        canvas.drawRoundRect(cx - rw, cy - rh, cx + rw, cy + rh, 8f * density, 8f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (key.type == KeyType.JOYSTICK) 0x99FF9800.toInt() else Color.WHITE
        canvas.drawRoundRect(cx - rw, cy - rh, cx + rw, cy + rh, 8f * density, 8f * density, paint)

        // 图标/标签
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 13f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(key.label, cx, cy + paint.textSize / 3f, paint)
        paint.textAlign = Paint.Align.LEFT

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
}
