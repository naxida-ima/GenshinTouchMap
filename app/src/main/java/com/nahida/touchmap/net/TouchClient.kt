package com.nahida.touchmap.net

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 发射端网络客户端：连接接收端，发送触摸指令。
 * TCP_NODELAY 禁用 Nagle 算法，小包即时发出（低延迟关键）。
 */
class TouchClient(
    private val host: String,
    private val port: Int,
    private val onStatus: (connected: Boolean) -> Unit = {}
) {

    companion object {
        private const val TAG = "TouchClient"
        private const val HEARTBEAT_MS = 3000L
    }

    private val lock = Any()
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var heartbeatThread: Thread? = null
    @Volatile
    var connected = false
        private set

    /** 连接（后台线程） */
    fun connect() {
        Thread {
            runCatching {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 3000)
                val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                synchronized(lock) {
                    socket = s
                    out = o
                    connected = true
                }
                o.write(TouchProtocol.encodeCtrl(TouchProtocol.TYPE_HELLO))
                o.flush()
                onStatus(true)
                startHeartbeat()
                readLoop(s)
            }.onFailure {
                Log.e(TAG, "connect failed", it)
                synchronized(lock) {
                    socket = null
                    out = null
                    connected = false
                }
                onStatus(false)
            }
        }.apply { isDaemon = true }.start()
    }

    fun disconnect() {
        synchronized(lock) {
            connected = false
            runCatching { socket?.close() }
            socket = null
            out = null
        }
        onStatus(false)
    }

    /** 发送触摸指令（线程安全，低延迟路径） */
    fun sendCmd(keyId: Int, action: Int, vx: Float, vy: Float) {
        val data = TouchProtocol.encodeCmd(keyId, action, vx, vy)
        synchronized(lock) {
            val o = out ?: return
            runCatching {
                o.write(data)
                o.flush()
            }.onFailure {
                Log.e(TAG, "send failed", it)
                disconnect()
            }
        }
    }

    private fun startHeartbeat() {
        val t = Thread {
            while (connected) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    synchronized(lock) {
                        runCatching { out?.write(TouchProtocol.encodeCtrl(TouchProtocol.TYPE_PING)); out?.flush() }
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true }
        heartbeatThread = t
        t.start()
    }

    private fun readLoop(socket: Socket) {
        Thread {
            runCatching {
                val input = DataInputStream(socket.getInputStream())
                while (connected) {
                    val type = input.read()
                    when (type) {
                        TouchProtocol.TYPE_PONG -> { /* 心跳回执，无需处理 */ }
                        TouchProtocol.TYPE_QUIT -> break
                        -1 -> break
                    }
                }
            }.onFailure { Log.e(TAG, "read failed", it) }
            disconnect()
        }.apply { isDaemon = true }.start()
    }
}
