package com.nahida.touchmap.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.nahida.touchmap.R
import com.nahida.touchmap.data.ConfigStore
import com.nahida.touchmap.mapper.TouchMapperService
import com.nahida.touchmap.model.KeyType
import com.nahida.touchmap.model.VirtualKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗服务：
 * - 每个按键/摇杆/悬浮球都是独立的 WindowManager 窗口（小尺寸窗口只拦截自己的触摸区域，
 *   其余区域自然透传给底层游戏 —— 映射工具的标准做法）
 * - 运行模式：按键触摸 -> 注入引擎模拟触摸
 * - 编辑模式：拖动移动位置；长按进入「选点模式」设置映射目标
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "touchmap_overlay_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: OverlayService? = null

        fun isRunning(): Boolean = instance != null

        /** MainActivity 增删改配置后调用：重建所有悬浮窗口 */
        fun refresh() {
            instance?.rebuildAll()
        }

        /** 切换编辑/运行模式（悬浮球或 MainActivity 触发） */
        fun toggleEditMode() {
            instance?.setEditMode(!(instance?.editing ?: false))
        }

        fun setEditModeExternal(edit: Boolean) {
            instance?.setEditMode(edit)
        }
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenW = 0
    private var screenH = 0

    /** 内存中的按键配置 */
    private var keys: MutableList<VirtualKey> = mutableListOf()
    /** 每个按键对应的悬浮窗口 */
    private val keyWindows = mutableListOf<Pair<VirtualKey, View>>()
    private var floatBallView: View? = null
    private var pickerView: View? = null
    private var editing = false
    private var pickingKey: VirtualKey? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        // 用真实屏幕尺寸（含系统栏），全屏游戏以真实尺寸为准
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(real)
        screenW = real.x
        screenH = real.y
        if (screenW <= 0) screenW = metrics.widthPixels
        if (screenH <= 0) screenH = metrics.heightPixels
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        instance = this
        scope.launch {
            editing = ConfigStore.editModeFlow(this@OverlayService).first()
        }
        rebuildAll()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        removeAllWindows()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 窗口管理 ----------

    private fun removeAllWindows() {
        keyWindows.forEach { (_, v) -> runCatching { wm.removeView(v) } }
        keyWindows.clear()
        runCatching { floatBallView?.let { wm.removeView(it) } }
        floatBallView = null
        runCatching { pickerView?.let { wm.removeView(it) } }
        pickerView = null
    }

    fun rebuildAll() {
        scope.launch {
            keys = ConfigStore.keysFlow(this@OverlayService).first().toMutableList()
            applyWindows()
        }
    }

    private fun applyWindows() {
        removeAllWindows()
        if (pickingKey != null) {
            // 选点模式只显示遮罩
            showPicker(pickingKey!!)
            return
        }
        // 按键 / 摇杆窗口
        keys.forEachIndexed { index, key ->
            val v = if (key.type == KeyType.JOYSTICK) {
                JoystickView(this, key, index, editing, screenW, screenH, ::onKeyEvent, ::onPickRequest)
            } else {
                KeyButtonView(this, key, index, editing, screenW, screenH, ::onKeyEvent, ::onPickRequest)
            }
            addKeyWindow(v, key)
        }
        // 悬浮球
        addFloatBall()
    }

    private fun addKeyWindow(view: View, key: VirtualKey) {
        val sizePx = key.size.dp(this)
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (key.x * screenW - sizePx / 2f).roundToInt()
        params.y = (key.y * screenH - sizePx / 2f).roundToInt()
        runCatching {
            wm.addView(view, params)
            keyWindows.add(key to view)
        }
    }

    private fun addFloatBall() {
        val sizePx = 56.dp(this)
        val ball = FloatBallView(this, sizePx, editing) {
            // 悬浮球点击：切换编辑/运行模式
            setEditMode(!editing)
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = screenW - sizePx - 16.dp(this)
        params.y = screenH / 2 - sizePx / 2
        runCatching {
            wm.addView(ball, params)
            floatBallView = ball
        }
    }

    private fun setEditMode(edit: Boolean) {
        if (editing == edit) return
        editing = edit
        scope.launch { ConfigStore.setEditMode(this@OverlayService, edit) }
        Toast.makeText(this, if (edit) "编辑模式：拖动移动位置，长按设置映射目标" else "运行模式", Toast.LENGTH_SHORT).show()
        rebuildAll()
    }

    // ---------- 事件回调 ----------

    /**
     * 按键事件回调（来自 KeyButtonView / JoystickView）
     * @param type press / move / release / tap
     */
    private fun onKeyEvent(key: VirtualKey, fingerId: Int, type: String, x: Float, y: Float) {
        val injector = TouchMapperService.instance
        if (injector == null) {
            Toast.makeText(this, "无障碍服务未开启，无法模拟触摸", Toast.LENGTH_SHORT).show()
            return
        }
        when (type) {
            "press" -> injector.press(fingerId, x, y)
            "move" -> injector.move(fingerId, x, y)
            "release" -> injector.release(fingerId)
            "tap" -> {
                injector.press(fingerId, x, y)
                scope.launch {
                    kotlinx.coroutines.delay(40)
                    injector.release(fingerId)
                }
            }
        }
    }

    /** 进入选点模式：为指定按键设置映射目标 */
    private fun onPickRequest(key: VirtualKey) {
        pickingKey = key
        applyWindows()
    }

    private fun showPicker(key: VirtualKey) {
        val mask = View(this)
        mask.setBackgroundColor(0x99000000.toInt())
        mask.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val tx = (e.x / screenW).coerceIn(0f, 1f)
                val ty = (e.y / screenH).coerceIn(0f, 1f)
                key.targetX = tx
                key.targetY = ty
                Toast.makeText(this, "${key.label} 已映射到 (${"%.2f".format(tx)}, ${"%.2f".format(ty)})", Toast.LENGTH_SHORT).show()
                scope.launch {
                    ConfigStore.saveKeys(this@OverlayService, keys)
                    pickingKey = null
                    applyWindows()
                }
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        runCatching {
            wm.addView(mask, params)
            pickerView = mask
        }
    }

    // ---------- 通知 ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "悬浮按键",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    // ---------- 工具 ----------

    /** 供 KeyButtonView / JoystickView 更新窗口位置（拖动时） */
    fun moveKeyWindow(view: View, centerXPercent: Float, centerYPercent: Float) {
        val pair = keyWindows.firstOrNull { it.second === view } ?: return
        val key = pair.first
        val sizePx = key.size.dp(this)
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = (centerXPercent * screenW - sizePx / 2f).roundToInt()
        params.y = (centerYPercent * screenH - sizePx / 2f).roundToInt()
        runCatching { wm.updateViewLayout(view, params) }
    }
}

/** dp -> px */
fun Int.dp(context: Context): Int =
    (this * context.resources.displayMetrics.density).roundToInt()

/** Float 版 dp -> px（按键尺寸是百分比外的 dp 数值） */
fun Float.dp(context: Context): Int =
    (this * context.resources.displayMetrics.density).roundToInt()

/** 悬浮球 */
class FloatBallView(
    context: Context,
    private val sizePx: Int,
    private var editing: Boolean,
    private val onClick: () -> Unit
) : View(context) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (editing) 0xCCFF9800.toInt() else 0xCC00BFFF.toInt()
        textSize = 14 * context.resources.displayMetrics.density
        textAlign = android.graphics.Paint.Align.CENTER
    }

    fun setEditing(e: Boolean) {
        editing = e
        paint.color = if (editing) 0xCCFF9800.toInt() else 0xCC00BFFF.toInt()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, minOf(width, height) / 2f - 2f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(if (editing) "运" else "编", cx, cy + paint.textSize / 3f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) onClick()
        return true
    }
}
