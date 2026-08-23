package com.nahida.touchmap.mapper

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 引擎 B：Shizuku + IInputManager.injectInputEvent 注入真实触摸事件。
 *
 * 设计要点（重写版，解决历史竞态/卡顿问题）：
 * 1. 主线程只维护指针状态快照，注入统一在单线程后台执行器串行进行。
 *    WAIT_FOR_FINISH 会阻塞调用线程直到系统处理完事件——放在后台线程，
 *    主线程永不阻塞，避免 ColorOS 等系统因主线程无响应而 CANCEL 触摸（长按失效/摇杆卡死）。
 * 2. 每一次注入都携带「事件构造时刻的指针快照」，后台执行器不依赖任何可变状态，
 *    彻底消除 release 时「主线程先删指针、后台读不到 → UP 丢失 → 自动长按」的竞态。
 * 3. MOVE 合并：高频拖动只保留最新一帧，避免后台队列堆积。
 */
class ShizukuInjector : TouchInjector {

    companion object {
        private const val TAG = "ShizukuInjector"
        private const val MODE_WAIT_FOR_FINISH = 1
        private const val SOURCE_TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN

        @Volatile
        var available = false
            private set
    }

    /** 注入端指针（data class 便于快照 copy） */
    private data class Ptr(val id: Int, var x: Float, var y: Float)

    /** 主线程维护的指针状态（所有访问在 synchronized 内） */
    private val pointers = LinkedHashMap<Int, Ptr>()
    private var nextPointerId = 0
    private var sessionDownTime = 0L

    /** 单线程后台执行器：串行注入，互不交错 */
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ShizukuInjector").also { it.isDaemon = true }
    }

    /**
     * 按下注入延迟：真实触摸 DOWN 与注入 DOWN 几乎同时到达系统时，
     * ColorOS 触摸仲裁会取消真实触摸（CANCEL）→ 长按失效。
     * 延迟到真实触摸稳定后再注入，可避开仲裁窗口（用户已接受该延迟）。
     */
    private val PRESS_DELAY_MS = 50L

    /** 快速点击时 DOWN 与 UP 的间隔 */
    private val CLICK_GAP_MS = 15L

    /** 尚未执行的延迟按下任务（fingerId -> ScheduledFuture），访问需持有 lock */
    private val pendingPressFutures = HashMap<Int, ScheduledFuture<*>>()

    /** MOVE 合并门：保证队列中最多一个待执行的 MOVE 任务 */
    private val moveGate = AtomicBoolean(false)
    @Volatile
    private var latestMoveSnapshot: List<Ptr>? = null

    init {
        connect()
    }

    private fun connect(): Boolean {
        return runCatching {
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            val stubCls = runCatching {
                Class.forName("android.hardware.input.IInputManager\$Stub")
            }.getOrElse { Class.forName("android.view.IInputManager\$Stub") }
            val ifaceCls = runCatching {
                Class.forName("android.hardware.input.IInputManager")
            }.getOrElse { Class.forName("android.view.IInputManager") }

            inputManager = stubCls.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            injectMethod = ifaceCls.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            available = true
            Log.i(TAG, "IInputManager connected via Shizuku")
            true
        }.onFailure {
            available = false
            Log.e(TAG, "Shizuku input connect failed", it)
        }.getOrDefault(false)
    }

    private var injectMethod: Method? = null
    private var inputManager: Any? = null

    override fun isReady(): Boolean = available && injectMethod != null && inputManager != null

    /** 取 fingerId 在 pointers 中的顺序索引（POINTER_DOWN/UP 需要） */
    private fun pointerIndex(fingerId: Int): Int = pointers.keys.indexOf(fingerId)

    override fun press(fingerId: Int, x: Float, y: Float) {
        val action: Int
        val snapshot: List<Ptr>
        synchronized(pointers) {
            if (pointers.isEmpty()) sessionDownTime = SystemClock.uptimeMillis()
            val pid = nextPointerId++
            pointers[fingerId] = Ptr(pid, x, y)
            snapshot = pointers.values.map { it.copy() }
            action = if (snapshot.size == 1) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or
                    (pointerIndex(fingerId) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        // 延迟注入（避开触摸仲裁窗口）；期间若收到 release 则取消并转为点击
        val future = executor.schedule({
            doInject(action, snapshot)
            synchronized(pointers) { pendingPressFutures.remove(fingerId) }
        }, PRESS_DELAY_MS, TimeUnit.MILLISECONDS)
        synchronized(pointers) { pendingPressFutures[fingerId] = future }
    }

    override fun move(fingerId: Int, x: Float, y: Float) {
        synchronized(pointers) {
            val p = pointers[fingerId] ?: return
            p.x = x
            p.y = y
            latestMoveSnapshot = pointers.values.map { it.copy() }
        }
        // 只有队列空时才提交新任务；否则只更新 latestMoveSnapshot，由在途任务读取最新值
        if (moveGate.compareAndSet(false, true)) {
            executor.submit {
                try {
                    val snap = latestMoveSnapshot ?: return@submit
                    doInject(MotionEvent.ACTION_MOVE, snap)
                } finally {
                    moveGate.set(false)
                }
            }
        }
    }

    override fun release(fingerId: Int) {
        val action: Int
        val snapshot: List<Ptr>
        val pressPending: Boolean
        synchronized(pointers) {
            val p = pointers[fingerId] ?: return
            val idx = pointerIndex(fingerId)
            // 先构造快照（含待释放指针，UP 必须包含它），再移除
            snapshot = pointers.values.map { it.copy() }
            pointers.remove(fingerId)
            if (pointers.isEmpty()) nextPointerId = 0
            action = if (snapshot.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or
                    (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            // 若延迟按下尚未注入：取消它，转为「点击」（DOWN + 短间隔 UP）
            val pending = pendingPressFutures.remove(fingerId)
            pending?.cancel(false)
            pressPending = pending != null
        }
        if (pressPending) {
            executor.schedule({ doInject(MotionEvent.ACTION_DOWN, snapshot) }, 0, TimeUnit.MILLISECONDS)
            executor.schedule({ doInject(MotionEvent.ACTION_UP, snapshot) }, CLICK_GAP_MS, TimeUnit.MILLISECONDS)
        } else {
            executor.schedule({ doInject(action, snapshot) }, 0, TimeUnit.MILLISECONDS)
        }
    }

    /** 用快照构造事件并注入（在后台线程执行） */
    private fun doInject(action: Int, snapshot: List<Ptr>) {
        val inject = injectMethod ?: return
        val manager = inputManager ?: return
        if (snapshot.isEmpty()) return

        val count = snapshot.size
        val props = Array(count) {
            MotionEvent.PointerProperties().apply {
                id = snapshot[it].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) {
            MotionEvent.PointerCoords().apply {
                x = snapshot[it].x
                y = snapshot[it].y
            }
        }
        val eventTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            sessionDownTime, eventTime, action, count,
            props, coords, 0, 0,
            1f, 1f, -1, 0,
            SOURCE_TOUCHSCREEN, 0
        )
        Log.d(
            TAG,
            "inject action=${actionName(action)} downTime=$sessionDownTime eventTime=$eventTime " +
                    "hold=${eventTime - sessionDownTime}ms ptrs=$count " +
                    "pos=(${coords[0].x.toInt()},${coords[0].y.toInt()})"
        )
        val ok = runCatching {
            inject.invoke(manager, event, MODE_WAIT_FOR_FINISH) as Boolean
        }.onFailure { Log.e(TAG, "injectInputEvent failed", it) }.getOrDefault(false)
        Log.d(TAG, "inject result=$ok")
        event.recycle()
    }

    private fun actionName(action: Int): String = when (action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_POINTER_DOWN -> "PTR_DOWN(${action shr MotionEvent.ACTION_POINTER_INDEX_SHIFT})"
        MotionEvent.ACTION_POINTER_UP -> "PTR_UP(${action shr MotionEvent.ACTION_POINTER_INDEX_SHIFT})"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> "UNKNOWN($action)"
    }
}
