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

        // Xbox 布局手柄键码（原神 7.0 至冬射击模式官方布局）
        const val KEY_A = KeyEvent.KEYCODE_BUTTON_A        // 跳跃 / 翻越掩体
        const val KEY_X = KeyEvent.KEYCODE_BUTTON_X        // 拾取 / 交互
        const val KEY_Y = KeyEvent.KEYCODE_BUTTON_Y        // 枪械技能（小技能）
        const val KEY_B = KeyEvent.KEYCODE_BUTTON_B        // 装填弹药（换弹）
        const val KEY_RB = KeyEvent.KEYCODE_BUTTON_R1      // 冲刺 / 滑铲（疾跑）
        const val KEY_DPAD_UP = KeyEvent.KEYCODE_DPAD_UP           // 切枪械方案1（主武器）
        const val KEY_DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT     // 切枪械方案2（副武器）
        const val KEY_DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT       // 切换榴晶（大招）

        /**
         * keyId -> 手柄键码（0=左摇杆移动，1=右摇杆视角，2+ 按键）
         * 布局对应原神 7.0 手柄官方键位：
         * 2=跳跃(A) 3=交互(X) 4=技能(Y) 5=换弹(B) 6=冲刺(RB)
         * 7=主武器(十字键上) 8=副武器(十字键右) 9=榴晶(十字键左)
         */
        val DEFAULT_KEYMAP = mapOf(
            2 to KEY_A,
            3 to KEY_X,
            4 to KEY_Y,
            5 to KEY_B,
            6 to KEY_RB,
            7 to KEY_DPAD_UP,
            8 to KEY_DPAD_RIGHT,
            9 to KEY_DPAD_LEFT
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
