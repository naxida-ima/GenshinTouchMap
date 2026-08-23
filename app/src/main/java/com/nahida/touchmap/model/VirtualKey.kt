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
 * 按键形状（虚拟层与游戏层目标标记同形状）
 */
@Serializable
enum class KeyShape {
    @SerialName("CIRCLE") CIRCLE,
    @SerialName("RECTANGLE") RECTANGLE
}

/**
 * 一个虚拟按键/摇杆。
 * 所有坐标均为「相对屏幕的百分比」（0f ~ 1f），适配任意分辨率。
 * 尺寸：width/height（dp）——圆形 width==height（直径），矩形可独立拉宽高（细长条按键）。
 */
@Serializable
data class VirtualKey(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: KeyType = KeyType.TAP,
    val shape: KeyShape = KeyShape.CIRCLE,
    var label: String = "按键",
    /** overlay 上的位置（百分比） */
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    /** 宽度（dp）：圆形=直径，矩形=宽 */
    var width: Float = 56f,
    /** 高度（dp）：圆形=直径（同宽），矩形=高（可独立拉伸） */
    var height: Float = 56f,
    /** 映射目标坐标（百分比）——选点模式下记录 */
    var targetX: Float = -1f,
    var targetY: Float = -1f,
    /** 摇杆专用：映射半径（百分比），摇杆满行程拖动对应目标上偏移多少 */
    var joystickRadius: Float = 0.15f,
    /** 透明度 0~1 */
    var opacity: Float = 0.5f
)
