package com.nahida.touchmap.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 接收端按键配置：keyId -> 注入点（游戏屏幕百分比坐标）。
 * 发射端只发 keyId + 手势，接收端按此配置换算成游戏坐标注入。
 */
@Serializable
data class RemoteKey(
    val keyId: Int = 0,
    var name: String = "按键",
    /** 摇杆：注入的是中心点 + 归一化向量 */
    var isJoystick: Boolean = false,
    /** 注入点（百分比）——摇杆为移动中心 */
    var targetX: Float = 0.5f,
    var targetY: Float = 0.5f,
    /** 摇杆满行程半径（屏幕高度百分比） */
    var joystickRadius: Float = 0.12f
)

/** 原神默认手机布局预置模板（用户可在接收端调整） */
val GENSHIN_PRESET = listOf(
    RemoteKey(0, "移动摇杆", isJoystick = true, targetX = 0.22f, targetY = 0.78f, joystickRadius = 0.12f),
    RemoteKey(1, "攻击", targetX = 0.85f, targetY = 0.78f),
    RemoteKey(2, "跳跃", targetX = 0.74f, targetY = 0.65f),
    RemoteKey(3, "元素战技", targetX = 0.92f, targetY = 0.62f),
    RemoteKey(4, "元素爆发", targetX = 0.80f, targetY = 0.55f),
    RemoteKey(5, "冲刺", targetX = 0.90f, targetY = 0.76f),
    RemoteKey(6, "切换角色", targetX = 0.96f, targetY = 0.50f),
    RemoteKey(7, "自定义1", targetX = 0.30f, targetY = 0.55f),
    RemoteKey(8, "自定义2", targetX = 0.35f, targetY = 0.62f),
    RemoteKey(9, "自定义3", targetX = 0.40f, targetY = 0.70f)
)
