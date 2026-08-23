package com.nahida.touchmap.mapper

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * 引擎 C：手柄事件注入（「假映射」方案）。
 *
 * 原理：原神切换手柄模式后，把 overlay 触摸转成手柄输入注入——
 * - 按键 → KeyEvent（SOURCE_GAMEPAD，按下保持/抬起释放 → 长按天然支持）
 * - 摇杆 → MotionEvent（SOURCE_JOYSTICK，AXIS_X/Y 轴值持续注入）
 * 手柄输入不经过触摸通道 → 不触发 ColorOS 触摸仲裁 → 长按/多指稳定。
 *
 * fingerId 语义（对应发射/本机按键列表顺序）：
 * - 0 = 移动摇杆（AXIS_X/Y）
 * - 1 = 视角摇杆（AXIS_RX/RY）
 * - 2+ = 按键（keyId 映射手柄键码）
 */
class GamepadInjector : TouchInjector {

    companion object {
        private const val TAG = "GamepadInjector"
        private const val MODE_WAIT_FOR_FINISH = 1

        // Xbox 布局手柄键码（原神手柄模式）
        const val KEY_A = KeyEvent.KEYCODE_BUTTON_A        // 跳跃
        const val KEY_X = KeyEvent.KEYCODE_BUTTON_X        // 攻击
        const val KEY_B = KeyEvent.KEYCODE_BUTTON_B        // 元素战技
        const val KEY_Y = KeyEvent.KEYCODE_BUTTON_Y        // 元素爆发
        const val KEY_L1 = KeyEvent.KEYCODE_BUTTON_L1      // 切角色左
        const val KEY_R1 = KeyEvent.KEYCODE_BUTTON_R1      // 切角色右
        const val KEY_L2 = KeyEvent.KEYCODE_BUTTON_L2      // 冲刺/加速（按住）
        const val KEY_R2 = KeyEvent.KEYCODE_BUTTON_R2      // 瞄准（按住）

        /** keyId -> 手柄键码（默认：0/1 为摇杆，2+ 为按键） */
        val DEFAULT_KEYMAP = mapOf(
            2 to KEY_X,   // 攻击
            3 to KEY_A,   // 跳跃
            4 to KEY_B,   // 元素战技
            5 to KEY_Y,   // 元素爆发
            6 to KEY_L2,  // 冲刺
            7 to KEY_R2,  // 瞄准
            8 to KEY_L1,  // 切角色
            9 to KEY_R1   // 切角色
        )

        @Volatile
        var available = false
            private set
    }

    private var injectMethod: Method? = null
    private var inputManager: Any? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GamepadInjector").also { it.isDaemon = true }
    }
    private val pressedKeys = HashMap<Int, Int>()  // fingerId -> keyCode
    private var downTime = 0L

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
            Log.i(TAG, "GamepadInjector connected")
            true
        }.onFailure {
            available = false
            Log.e(TAG, "GamepadInjector connect failed", it)
        }.getOrDefault(false)
    }

    override fun isReady(): Boolean = available && injectMethod != null && inputManager != null

    override fun press(fingerId: Int, x: Float, y: Float) {
        val keyCode = DEFAULT_KEYMAP[fingerId] ?: return
        pressedKeys[fingerId] = keyCode
        downTime = SystemClock.uptimeMillis()
        val t = SystemClock.uptimeMillis()
        val event = KeyEvent(
            downTime, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 0, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_GAMEPAD
        )
        executor.submit { inject(event) }
    }

    override fun move(fingerId: Int, x: Float, y: Float) {
        // 摇杆轴注入：fingerId 0=移动(AXIS_X/Y) 1=视角(AXIS_RX/RY)
        val axisX = if (fingerId == 1) MotionEvent.AXIS_RX else MotionEvent.AXIS_X
        val axisY = if (fingerId == 1) MotionEvent.AXIS_RY else MotionEvent.AXIS_Y
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = fingerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                setAxisValue(axisX, x.coerceIn(-1f, 1f))
                setAxisValue(axisY, y.coerceIn(-1f, 1f))
            }
        )
        val t = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            downTime, t, MotionEvent.ACTION_MOVE, 1,
            props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_JOYSTICK, 0
        )
        executor.submit { inject(event) }
    }

    override fun release(fingerId: Int) {
        val keyCode = pressedKeys.remove(fingerId) ?: return
        val t = SystemClock.uptimeMillis()
        val event = KeyEvent(
            downTime, t, KeyEvent.ACTION_UP, keyCode, 0, 0, 0, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_GAMEPAD
        )
        executor.submit { inject(event) }
    }

    private fun inject(event: InputEvent) {
        val m = injectMethod ?: return
        val im = inputManager ?: return
        runCatching {
            m.invoke(im, event, MODE_WAIT_FOR_FINISH)
        }.onFailure { Log.e(TAG, "inject failed", it) }
        if (event is MotionEvent) {
            event.recycle()
        }
    }
}
