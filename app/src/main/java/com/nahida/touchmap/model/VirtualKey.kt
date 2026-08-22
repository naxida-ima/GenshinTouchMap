package com.nahida.touchmap.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 按键类型
 * - TAP: 普通点击（按下→抬起）
 * - HOLD: 长按（按住期间持续按压目标）
 * - JOYSTICK: 摇杆（按住 + 拖动，映射为对目标点的方向位移）
 */
@Serializable
enum class KeyType {
    @SerialName("TAP") TAP,
    @SerialName("HOLD") HOLD,
    @SerialName("JOYSTICK") JOYSTICK
}

/**
 * 一个虚拟按键/摇杆。
 * 所有坐标均为「相对屏幕的百分比」（0f ~ 1f），适配任意分辨率。
 */
@Serializable
data class VirtualKey(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: KeyType = KeyType.TAP,
    var label: String = "按键",
    /** overlay 上的位置（百分比） */
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    /** overlay 上的尺寸（dp） */
    var size: Float = 56f,
    /** 映射目标坐标（百分比）——选点模式下记录 */
    var targetX: Float = -1f,
    var targetY: Float = -1f,
    /** 摇杆专用：映射半径（百分比），摇杆满行程拖动对应目标上偏移多少 */
    var joystickRadius: Float = 0.15f,
    /** 透明度 0~1 */
    var opacity: Float = 0.5f
)
