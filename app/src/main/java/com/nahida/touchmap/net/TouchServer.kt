package com.nahida.touchmap.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 接收端网络服务端：监听端口，接收发射端的触摸指令并回调。
 * 支持多客户端（同一时间只保留最新一个连接，简化状态管理）。
 */
class TouchServer(
    private val port: Int,
    private val onCmd: (keyId: Int, action: Int, vx: Float, vy: Float) -> Unit,
    private val onClient: (connected: Boolean) -> Unit = {}
) {

    companion object {
        private const val TAG = "TouchServer"
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var clientSocket: Socket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            runCatching {
                val ss = ServerSocket(port)
                server = ss
                Log.i(TAG, "listening on $port")
                while (running) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    // 只保留最新客户端
                    synchronized(this) {
                        runCatching { clientSocket?.close() }
                        clientSocket = s
                    }
                    onClient(true)
                    handleClient(s)
                }
            }.onFailure {
                Log.e(TAG, "server error", it)
                running = false
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        synchronized(this) {
            runCatching { clientSocket?.close() }
            clientSocket = null
            runCatching { server?.close() }
            server = null
        }
        onClient(false)
    }

    private fun handleClient(socket: Socket) {
        Thread {
            runCatching {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                while (running && !socket.isClosed) {
                    val type = input.read()
                    when (type) {
                        TouchProtocol.TYPE_CMD -> {
                            val keyId = input.read()
                            val action = input.read()
                            val vx = input.readFloat()
                            val vy = input.readFloat()
                            onCmd(keyId, action, vx, vy)
                        }
                        TouchProtocol.TYPE_PING -> {
                            output.write(TouchProtocol.encodeCtrl(TouchProtocol.TYPE_PONG))
                            output.flush()
                        }
                        TouchProtocol.TYPE_HELLO -> { /* 握手确认 */ }
                        TouchProtocol.TYPE_QUIT -> break
                        -1 -> break
                    }
                }
            }.onFailure {
                Log.e(TAG, "client error", it)
            }
            synchronized(this) {
                if (clientSocket === socket) {
                    clientSocket = null
                    onClient(false)
                }
            }
            runCatching { socket.close() }
        }.apply { isDaemon = true }.start()
    }
}
