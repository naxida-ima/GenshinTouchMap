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
    /** 双机模式：接收端注入点编号（0-255，对应接收端 RemoteKey 配置） */
    var keyId: Int = 0,
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

/**
 * 手柄映射预置布局：原神 7.0 至冬射击模式官方手柄键位。
 * keyId：0=左摇杆(移动) 1=右摇杆(视角·全屏滑动层，非控件)
 * 2=跳跃(A) 3=交互(X) 4=枪械技能(Y) 5=换弹(B) 6=冲刺/滑铲(RB)
 * 7=主武器(十字键上) 8=副武器(十字键右) 9=榴晶(十字键左)
 */
val GAMEPAD_PRESET = listOf(
    VirtualKey(type = KeyType.JOYSTICK, label = "移动", keyId = 0, x = 0.20f, y = 0.78f, width = 120f, height = 120f),
    VirtualKey(type = KeyType.TAP, label = "跳跃", keyId = 2, x = 0.80f, y = 0.68f, width = 60f, height = 60f),
    VirtualKey(type = KeyType.TAP, label = "交互", keyId = 3, x = 0.90f, y = 0.78f, width = 60f, height = 60f),
    VirtualKey(type = KeyType.TAP, label = "技能", keyId = 4, x = 0.72f, y = 0.60f, width = 56f, height = 56f),
    VirtualKey(type = KeyType.TAP, label = "换弹", keyId = 5, x = 0.90f, y = 0.62f, width = 60f, height = 60f),
    VirtualKey(type = KeyType.TAP, label = "冲刺", keyId = 6, x = 0.83f, y = 0.84f, width = 68f, height = 56f, shape = KeyShape.RECTANGLE),
    VirtualKey(type = KeyType.TAP, label = "主武器", keyId = 7, x = 0.08f, y = 0.28f, width = 52f, height = 52f),
    VirtualKey(type = KeyType.TAP, label = "副武器", keyId = 8, x = 0.17f, y = 0.28f, width = 52f, height = 52f),
    VirtualKey(type = KeyType.TAP, label = "榴晶", keyId = 9, x = 0.26f, y = 0.28f, width = 52f, height = 52f)
)
