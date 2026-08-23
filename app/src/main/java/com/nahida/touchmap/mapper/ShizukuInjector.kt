package com.nahida.touchmap.mapper

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * 引擎 B：Shizuku + IInputManager.injectInputEvent 注入真实触摸事件。
 *
 * 原理（官方文档 + 开源实践）：
 * 1. SystemServiceHelper.getSystemService("input") 拿到 input 服务的原始 Binder
 * 2. ShizukuBinderWrapper 包装，让调用以 Shizuku 进程（ADB/ROOT）身份执行
 * 3. 反射 IInputManager$Stub.asInterface 得到输入管理器代理
 * 4. 反射 injectInputEvent(InputEvent, mode) 注入 MotionEvent
 *
 * 关键设计：
 * - 注入在独立后台线程串行执行：WAIT_FOR_FINISH 会阻塞调用线程直到系统处理完事件，
 *   若在 UI 主线程调用会阻塞触摸响应，ColorOS 等系统会立刻 CANCEL 触摸（长按失效/摇杆卡死）
 * - MOVE 事件合并：队列中只保留最新一个，避免高频拖动时事件堆积
 */
class ShizukuInjector : TouchInjector {

    companion object {
        private const val TAG = "ShizukuInjector"
        /**
         * injectInputEvent 注入模式：WAIT_FOR_FINISH（等待事件处理完成，事件必定送达）
         */
        private const val MODE_WAIT_FOR_FINISH = 1
        private const val SOURCE_TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN

        @Volatile
        var available = false
            private set
    }

    /** fingerId -> 指针状态（注入端维护，所有访问在 lock 内） */
    private class Pointer(
        val pointerId: Int,
        val downTime: Long,
        @Volatile var x: Float,
        @Volatile var y: Float
    )

    private val lock = Any()
    private var injectMethod: Method? = null
    private var inputManager: Any? = null
    private val pointers = LinkedHashMap<Int, Pointer>()
    private var nextPointerId = 0
    private var sessionDownTime = 0L
    private var pendingMove: Runnable? = null

    /** 注入线程：WAIT_FOR_FINISH 的阻塞发生在后台，主线程永不阻塞 */
    private val injectThread = HandlerThread("ShizukuInjector").apply { start() }
    private val injectHandler = Handler(injectThread.looper)

    init {
        connect()
    }

    /** 建立与系统输入服务的连接（在 Shizuku 权限下） */
    private fun connect(): Boolean {
        return runCatching {
            val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            // Android 10+ 的 IInputManager 在 android.hardware.input 包；旧版本在 android.view
            val stubCls = runCatching {
                Class.forName("android.hardware.input.IInputManager\$Stub")
            }.getOrElse { Class.forName("android.view.IInputManager\$Stub") }
            val ifaceCls = runCatching {
                Class.forName("android.hardware.input.IInputManager")
            }.getOrElse { Class.forName("android.view.IInputManager") }

            inputManager = stubCls
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
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

    override fun isReady(): Boolean = available && injectMethod != null && inputManager != null

    override fun press(fingerId: Int, x: Float, y: Float) {
        synchronized(lock) {
            if (pointers.isEmpty()) {
                sessionDownTime = SystemClock.uptimeMillis()
            }
            pointers[fingerId] = Pointer(nextPointerId++, sessionDownTime, x, y)

            if (pointers.size == 1) {
                scheduleInject(MotionEvent.ACTION_DOWN)
            } else {
                val index = pointers.keys.indexOf(fingerId)
                scheduleInject(MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            }
        }
    }

    override fun move(fingerId: Int, x: Float, y: Float) {
        synchronized(lock) {
            val p = pointers[fingerId] ?: return
            p.x = x
            p.y = y
            scheduleInject(MotionEvent.ACTION_MOVE)
        }
    }

    override fun release(fingerId: Int) {
        synchronized(lock) {
            if (pointers[fingerId] == null) return
            if (pointers.size == 1) {
                // 关键：先注入 ACTION_UP（事件必须包含该指针），再移除——
                // 若先移除，pointers 为空会跳过注入，游戏永远收不到「抬起」
                scheduleInject(MotionEvent.ACTION_UP)
                pointers.remove(fingerId)
                nextPointerId = 0
            } else {
                val index = pointers.keys.indexOf(fingerId)
                scheduleInject(MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                pointers.remove(fingerId)
            }
        }
    }

    /**
     * 调度一次注入：post 到后台线程串行执行（事件在注入线程读取最新指针状态构造）。
     * MOVE 合并：队列中只保留最新一个。
     */
    private fun scheduleInject(action: Int) {
        val inject = injectMethod ?: return
        val manager = inputManager ?: return

        val runnable = Runnable {
            synchronized(lock) {
                if (pointers.isEmpty()) return@Runnable
                injectNow(inject, manager, action)
            }
        }

        if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE) {
            pendingMove?.let { injectHandler.removeCallbacks(it) }
            pendingMove = runnable
            injectHandler.post(runnable)
        } else {
            // 非 MOVE 事件优先：丢弃队列中未执行的旧 MOVE
            pendingMove?.let { injectHandler.removeCallbacks(it) }
            pendingMove = null
            injectHandler.post(runnable)
        }
    }

    /** 在注入线程构造事件并注入（须持有 lock） */
    private fun injectNow(inject: Method, manager: Any, action: Int) {
        if (pointers.isEmpty()) return

        val count = pointers.size
        val props = arrayOfNulls<MotionEvent.PointerProperties>(count)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(count)

        pointers.values.forEachIndexed { i, p ->
            props[i] = MotionEvent.PointerProperties().apply {
                id = p.pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            coords[i] = MotionEvent.PointerCoords().apply {
                x = p.x
                y = p.y
            }
        }

        val eventTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            sessionDownTime, eventTime, action, count,
            props, coords, 0, 0,
            1f, 1f, 0, 0,
            SOURCE_TOUCHSCREEN, 0
        )

        Log.d(
            TAG,
            "inject action=${actionName(action)} downTime=$sessionDownTime eventTime=$eventTime " +
                    "hold=${eventTime - sessionDownTime}ms pointers=$count " +
                    "pos=(${coords[0]?.x?.toInt()},${coords[0]?.y?.toInt()})"
        )
        val ok = runCatching {
            inject.invoke(manager, event, MODE_WAIT_FOR_FINISH) as Boolean
        }.onFailure {
            Log.e(TAG, "injectInputEvent failed", it)
        }.getOrDefault(false)
        Log.d(TAG, "inject result=$ok")
        event.recycle()
    }

    private fun actionName(action: Int): String = when (action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN(idx=${action shr MotionEvent.ACTION_POINTER_INDEX_SHIFT})"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP(idx=${action shr MotionEvent.ACTION_POINTER_INDEX_SHIFT})"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> "UNKNOWN($action)"
    }
}
