package com.nahida.touchmap.remote

import android.content.Context
import com.nahida.touchmap.net.TouchServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 接收端（注入层）服务持有器：监听端口 → 指令转 RemoteSink → Shizuku 注入。
 * 游戏设备全程无真实触摸，不触发触摸仲裁。
 */
object RemoteServerHolder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var listening = false
        private set

    @Volatile
    var connected = false
        private set

    private var server: TouchServer? = null
    private var sink: RemoteSink? = null

    fun start(context: Context, port: Int) {
        stop()
        scope.launch {
            val keys = RemoteKeyStore.keysFlow(context).first().associateBy { it.keyId }
            val metrics = context.resources.displayMetrics
            sink = RemoteSink(metrics.widthPixels, metrics.heightPixels, keys)
            server = TouchServer(
                port,
                onCmd = { keyId, action, vx, vy -> sink?.onCmd(keyId, action, vx, vy) },
                onClient = { c -> connected = c }
            )
            server?.start()
            listening = true
        }
    }

    fun stop() {
        server?.stop()
        server = null
        sink = null
        listening = false
        connected = false
    }
}
