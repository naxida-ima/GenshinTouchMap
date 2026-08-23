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
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private var moved = false
    private var layoutPending = false

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
                downRawX = event.rawX
                downRawY = event.rawY
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
                    // raw 绝对坐标：窗口移动不影响位移计算（相对坐标会双重累积误差 → 抽搐不跟手）
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && kotlin.math.hypot(dx, dy) > touchSlop) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        when (gestureMode) {
                            MODE_MOVE -> {
                                key.x = (startX + dx / screenW).coerceIn(0f, 1f)
                                key.y = (startY + dy / screenH).coerceIn(0f, 1f)
                                scheduleLayout()
                            }
                            MODE_RESIZE -> {
                                val newW = max(36f, startW + dx / density)
                                val newH = max(36f, startH + dy / density)
                                key.width = newW
                                key.height = newH
                                scheduleLayout()
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

    /** 帧节流：每帧最多一次窗口布局更新（移动/缩放共用，防抖动） */
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

        // 图标（按 keyId 自绘 SVG 风格符号）+ 标签
        drawIcon(canvas, cx, cy, minOf(rw, rh))
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 11f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (editing) "${key.label}•🎯" else key.label, cx, cy + rh + 12f * density, paint)
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

    /** 按 keyId 绘制 SVG 风格图标（跳跃/交互/技能/换弹/冲刺/武器/榴晶） */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        when (key.keyId) {
            2 -> { // 跳跃：上箭头
                canvas.drawLine(cx, cy + r * 0.35f, cx, cy - r * 0.35f, paint)
                canvas.drawLine(cx - r * 0.25f, cy - r * 0.1f, cx, cy - r * 0.35f, paint)
                canvas.drawLine(cx + r * 0.25f, cy - r * 0.1f, cx, cy - r * 0.35f, paint)
            }
            3 -> { // 交互：手（圆 + 五指线）
                canvas.drawCircle(cx, cy + r * 0.1f, r * 0.25f, paint)
                canvas.drawLine(cx, cy - r * 0.1f, cx, cy - r * 0.45f, paint)
                canvas.drawLine(cx - r * 0.12f, cy - r * 0.12f, cx - r * 0.25f, cy - r * 0.35f, paint)
                canvas.drawLine(cx + r * 0.12f, cy - r * 0.12f, cx + r * 0.25f, cy - r * 0.35f, paint)
            }
            4 -> { // 技能：闪电
                val path = android.graphics.Path()
                path.moveTo(cx + r * 0.2f, cy - r * 0.4f)
                path.lineTo(cx - r * 0.2f, cy + r * 0.1f)
                path.lineTo(cx + r * 0.02f, cy + r * 0.1f)
                path.lineTo(cx - r * 0.2f, cy + r * 0.4f)
                path.lineTo(cx + r * 0.25f, cy - r * 0.15f)
                path.lineTo(cx + r * 0.02f, cy - r * 0.15f)
                path.close()
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            5 -> { // 换弹：循环弧 + 箭头
                val rect = android.graphics.RectF(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f)
                canvas.drawArc(rect, 30f, 300f, false, paint)
                canvas.drawLine(cx + r * 0.28f, cy - r * 0.1f, cx + r * 0.28f, cy - r * 0.3f, paint)
                canvas.drawLine(cx + r * 0.1f, cy - r * 0.28f, cx + r * 0.28f, cy - r * 0.3f, paint)
            }
            6 -> { // 冲刺：双箭头
                canvas.drawLine(cx - r * 0.35f, cy - r * 0.3f, cx + r * 0.2f, cy, paint)
                canvas.drawLine(cx - r * 0.35f, cy + r * 0.3f, cx + r * 0.2f, cy, paint)
                canvas.drawLine(cx + r * 0.05f, cy - r * 0.3f, cx + r * 0.42f, cy, paint)
                canvas.drawLine(cx + r * 0.05f, cy + r * 0.3f, cx + r * 0.42f, cy, paint)
            }
            7, 8, 9 -> { // 主武器/副武器/榴晶
                paint.style = Paint.Style.FILL
                paint.textSize = r * 1.1f
                paint.textAlign = Paint.Align.CENTER
                val t = if (key.keyId == 7) "1" else if (key.keyId == 8) "2" else "◆"
                canvas.drawText(t, cx, cy + r * 0.4f, paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
    }
}
