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

/**
 * 引擎 B：Shizuku + IInputManager.injectInputEvent 注入真实触摸事件。
 *
 * 原理（官方文档 + 开源实践）：
 * 1. SystemServiceHelper.getSystemService("input") 拿到 input 服务的原始 Binder
 * 2. ShizukuBinderWrapper 包装，让调用以 Shizuku 进程（ADB/ROOT）身份执行
 * 3. 反射 IInputManager$Stub.asInterface 得到输入管理器代理
 * 4. 反射 injectInputEvent(InputEvent, mode) 注入 MotionEvent（mode=0 异步，最流畅）
 *
 * 相比无障碍引擎：真正的多指并行（每个指针独立 down/move/up），无"全量重建打断"问题，
 * 摇杆连续拖动不断档，延迟更低。
 */
class ShizukuInjector : TouchInjector {

    companion object {
        private const val TAG = "ShizukuInjector"
        /**
         * injectInputEvent 注入模式：WAIT_FOR_FINISH（等待事件处理完成）。
         * 相比 ASYNC 更可靠：down/up 事件必定送达，长按/连发不会因异步丢事件而失效。
         */
        private const val MODE_WAIT_FOR_FINISH = 1
        private const val SOURCE_TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN

        @Volatile
        var available = false
            private set
    }

    /** fingerId -> 指针状态（注入端维护） */
    private class Pointer(
        val pointerId: Int,
        val downTime: Long,
        var x: Float,
        var y: Float
    )

    private var injectMethod: Method? = null
    private var inputManager: Any? = null
    private val pointers = LinkedHashMap<Int, Pointer>()
    private var nextPointerId = 0
    private var sessionDownTime = 0L

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
        if (pointers.isEmpty()) {
            sessionDownTime = SystemClock.uptimeMillis()
        }
        val pointer = Pointer(nextPointerId++, sessionDownTime, x, y)
        pointers[fingerId] = pointer

        if (pointers.size == 1) {
            injectEvent(MotionEvent.ACTION_DOWN)
        } else {
            val index = pointers.keys.indexOf(fingerId)
            injectEvent(MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
    }

    override fun move(fingerId: Int, x: Float, y: Float) {
        val p = pointers[fingerId] ?: return
        p.x = x
        p.y = y
        injectEvent(MotionEvent.ACTION_MOVE)
    }

    override fun release(fingerId: Int) {
        val p = pointers[fingerId] ?: return
        if (pointers.size == 1) {
            // 关键：先注入 ACTION_UP（事件必须包含该指针），再移除——
            // 若先移除，pointers 为空会跳过注入，游戏永远收不到「抬起」= 轻点变长按
            injectEvent(MotionEvent.ACTION_UP)
            pointers.remove(fingerId)
            nextPointerId = 0
        } else {
            val index = pointers.keys.indexOf(fingerId)
            // POINTER_UP 事件需包含被抬起指针，之后才移除
            injectEvent(MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            pointers.remove(fingerId)
        }
    }

    /** 构造包含当前全部活动指针的 MotionEvent 并注入 */
    private fun injectEvent(action: Int) {
        val inject = injectMethod ?: return
        val manager = inputManager ?: return
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

        runCatching {
            inject.invoke(manager, event, MODE_WAIT_FOR_FINISH)
        }.onFailure {
            Log.e(TAG, "injectInputEvent failed", it)
        }.also {
            event.recycle()
        }
    }
}
