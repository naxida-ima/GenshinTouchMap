package com.nahida.touchmap.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.nahida.touchmap.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 接收端注入点配置：在游戏画面上盖一层半透明遮罩（能看到游戏），
 * 逐个提示点击各 keyId 对应的原神按键位置，所见即所得（无需调坐标）。
 */
class ConfigPickerService : Service() {

    companion object {
        private const val CHANNEL_ID = "config_picker"

        @Volatile
        private var instance: ConfigPickerService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConfigPickerService::class.java))
        }

        fun stop() {
            instance?.stopSelf()
        }
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screenW = 0
    private var screenH = 0
    private var keys: MutableList<RemoteKey> = mutableListOf()
    private var index = 0
    private var maskView: View? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(real)
        screenW = real.x
        screenH = real.y
        startForeground(1002, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            keys = RemoteKeyStore.keysFlow(this@ConfigPickerService).first().toMutableList()
            index = 0
            showNext()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        removeMask()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showNext() {
        if (index >= keys.size) {
            Toast.makeText(this, "全部按键配置完成", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        val k = keys[index]
        val hint = if (k.isJoystick) "点击原神【${k.name}】的中心位置（移动摇杆）" else "点击原神【${k.name}】按键的位置"
        showMask(hint, index + 1, keys.size)
    }

    private fun showMask(hint: String, cur: Int, total: Int) {
        removeMask()
        val mask = HintMaskView(this, hint, cur, total)
        mask.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val k = keys[index]
                k.targetX = (e.x / screenW).coerceIn(0f, 1f)
                k.targetY = (e.y / screenH).coerceIn(0f, 1f)
                scope.launch { RemoteKeyStore.saveKeys(this@ConfigPickerService, keys) }
                index++
                showNext()
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        runCatching {
            wm.addView(mask, params)
            maskView = mask
        }
    }

    private fun removeMask() {
        runCatching { maskView?.let { wm.removeView(it) } }
        maskView = null
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "注入点配置", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("注入点配置中")
            .setContentText("在游戏画面上点击各按键位置")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }
}

/** 半透明提示遮罩：能看到游戏，顶部显示当前配置提示 */
class HintMaskView(
    context: Context,
    private val hint: String,
    private val cur: Int,
    private val total: Int
) : View(context) {

    private val bgPaint = Paint().apply { color = 0x40000000.toInt() }
    private val barPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22 * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF9800")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 半透明层（能看到游戏）
        canvas.drawColor(bgPaint.color)
        // 顶部提示条
        val barH = 160 * resources.displayMetrics.density
        canvas.drawRect(0f, 0f, width.toFloat(), barH, barPaint)
        canvas.drawText("点击原神按键位置 ($cur/$total)", width / 2f, barH / 2f - 6 * resources.displayMetrics.density, textPaint)
        canvas.drawText(hint, width / 2f, barH / 2f + 26 * resources.displayMetrics.density, textPaint)
        // 中央十字准星
        canvas.drawLine(width / 2f - 40 * resources.displayMetrics.density, height / 2f, width / 2f + 40 * resources.displayMetrics.density, height / 2f, crossPaint)
        canvas.drawLine(width / 2f, height / 2f - 40 * resources.displayMetrics.density, width / 2f, height / 2f + 40 * resources.displayMetrics.density, crossPaint)
        canvas.drawCircle(width / 2f, height / 2f, 10 * resources.displayMetrics.density, crossPaint)
    }
}
