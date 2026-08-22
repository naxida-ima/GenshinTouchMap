package com.nahida.touchmap.mapper

/**
 * 触摸注入引擎抽象。
 * - 引擎 A：无障碍服务 dispatchGesture（免 root / 免 adb，开箱即用）
 * - 引擎 B（v2 预留）：Shizuku + InputManager.injectInputEvent（多指更流畅，需 adb 激活一次）
 */
interface TouchInjector {
    /** 手指按下（fingerId 由调用方分配：0=左摇杆, 1=右摇杆/视角, 2=射击, 3=跳跃 …） */
    fun press(fingerId: Int, x: Float, y: Float)

    /** 手指移动到新坐标 */
    fun move(fingerId: Int, x: Float, y: Float)

    /** 手指抬起 */
    fun release(fingerId: Int)

    /** 当前引擎是否可用 */
    fun isReady(): Boolean
}
