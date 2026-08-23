package com.nahida.touchmap.net

import java.nio.ByteBuffer

/**
 * 双机通信协议（低延迟设计）：
 * 统一帧：1 字节类型 + 负载
 * - TYPE_CMD（0x00）：触摸指令，负载 9 字节 = keyId(1B) + action(1B) + vx(float 4B) + vy(float 4B)
 * - TYPE_PING / TYPE_PONG：心跳
 * - TYPE_HELLO：连接建立握手
 * - TYPE_QUIT：断开
 */
object TouchProtocol {

    const val TYPE_CMD = 0x00
    const val TYPE_PING = 0x01
    const val TYPE_PONG = 0x02
    const val TYPE_HELLO = 0x03
    const val TYPE_QUIT = 0xFF.toInt()

    // 指令动作
    const val ACTION_DOWN = 0
    const val ACTION_MOVE = 1
    const val ACTION_UP = 2

    /** 编码触摸指令：keyId + action + vx + vy -> ByteArray(10) */
    fun encodeCmd(keyId: Int, action: Int, vx: Float, vy: Float): ByteArray {
        val buf = ByteBuffer.allocate(10)
        buf.put(TYPE_CMD.toByte())
        buf.put(keyId.toByte())
        buf.put(action.toByte())
        buf.putFloat(vx)
        buf.putFloat(vy)
        return buf.array()
    }

    /** 编码单字节控制帧 */
    fun encodeCtrl(type: Int): ByteArray = byteArrayOf(type.toByte())
}
