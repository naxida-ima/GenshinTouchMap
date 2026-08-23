package com.nahida.touchmap.mapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 引擎 A：无障碍服务 + dispatchGesture 实现多指触摸注入。
 *
 * 设计要点：
 * 1. 全量重建：任何手指状态变化（按下/移动/抬起）都重建整个手势集合并 dispatch。
 *    dispatchGesture 会取消上一个手势，因此用「状态机 + 全量重建」保证多指并行。
 * 2. 原地按住用长 duration（HOLD_MS），减少重建频率，降低对移动摇杆的打断；
 *    心跳（HEARTBEAT_MS）在超时前重新提交原地 stroke，维持按压不中断。
 * 3. move 节流：位移超过阈值才重建，防止高频 dispatch 打爆无障碍服务。
 */
class TouchMapperService : AccessibilityService(), TouchInjector {

    companion object {
        @Volatile
        var instance: TouchMapperService? = null

        /** 原地按住 stroke 的时长（尽量长，减少打断） */
        private const val HOLD_MS = 6000L

        /** 心跳间隔：HOLD_MS 一半以内即可 */
        private const val HEARTBEAT_MS = 2000L

        /** 移动 stroke 的时长（跟手） */
        private const val MOVE_MS = 24L

        /** 移动节流：位移阈值（px） */
        private const val MOVE_THRESHOLD = 3f
    }

    private data class Finger(
        var x: Float,
        var y: Float,
        var lastX: Float,
        var lastY: Float
    )

    /** fingerId -> 手指状态（LinkedHashMap 保证稳定顺序） */
    private val fingers = LinkedHashMap<Int, Finger>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob())
    private var heartbeat: Job? = null
    @Suppress("unused")
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        heartbeat?.cancel()
        heartbeat = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun isReady(): Boolean = true

    override fun press(fingerId: Int, x: Float, y: Float) {
        synchronized(lock) {
            fingers[fingerId] = Finger(x, y, x, y)
            syncLocked()
        }
        ensureHeartbeat()
    }

    override fun move(fingerId: Int, x: Float, y: Float) {
        synchronized(lock) {
            val f = fingers[fingerId] ?: return
            f.x = x
            f.y = y
            if (distance(f.x, f.lastX, f.y, f.lastY) >= MOVE_THRESHOLD) {
                syncLocked()
            }
        }
    }

    override fun release(fingerId: Int) {
        synchronized(lock) {
            val removed = fingers.remove(fingerId) ?: return
            if (fingers.isEmpty()) {
                // 关键：最后一个手指抬起时，必须 dispatch 一个新手势取消旧的按住 stroke，
                // 否则旧 stroke 会一直撑到 HOLD_MS 结束，游戏会把轻点识别成「长按」
                cancelAllLocked(removed.x, removed.y)
                heartbeat?.cancel()
                heartbeat = null
            } else {
                syncLocked()
            }
        }
    }

    /**
     * 取消所有按住：dispatch 一个 1ms 的瞬时原地 stroke。
     * dispatchGesture 会取消上一个手势（= 手指抬起），1ms 后新 stroke 自然结束。
     */
    private fun cancelAllLocked(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun ensureHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                synchronized(lock) {
                    if (fingers.isNotEmpty()) syncLocked()
                }
            }
        }
    }

    /** 全量重建当前所有手指的手势（dispatchGesture 自动取消上一个） */
    private fun syncLocked() {
        if (fingers.isEmpty()) return
        val builder = GestureDescription.Builder()
        for ((_, f) in fingers) {
            val path = Path()
            if (distance(f.x, f.lastX, f.y, f.lastY) < 1f) {
                // 原地按住：从当前点出发保持不动
                path.moveTo(f.x, f.y)
                path.lineTo(f.x, f.y)
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, HOLD_MS))
            } else {
                // 移动：从上次同步位置移动到当前位置
                path.moveTo(f.lastX, f.lastY)
                path.lineTo(f.x, f.y)
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, MOVE_MS))
            }
            f.lastX = f.x
            f.lastY = f.y
        }
        dispatchGesture(builder.build(), null, null)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
