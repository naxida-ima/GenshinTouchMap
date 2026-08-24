package com.nahida.touchmap.btgamepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceApp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 虚拟 Xbox 手柄（蓝牙 HID 设备）。
 *
 * 原理：通过系统 BluetoothHidDevice API 把本机注册成一个
 * "Xbox Wireless Controller" 蓝牙 HID 游戏手柄，连接到主机
 * （运行原神的手机）后，主机系统会出现一个真实的手柄输入设备，
 * 原神即可在设置中切换为手柄模式。
 *
 * HID Report 结构（16 字节，无 Report ID）：
 *  [0..1]  buttons 位掩码（LE）
 *  [2..13] X, Y, Z, Rz, Rx, Ry（int16 LE）
 *          X/Y = 左摇杆，Rx/Ry = 右摇杆，Z/Rz 保留（置中）
 *  [14]    LT 扳机（0-255）
 *  [15]    RT 扳机（0-255）
 */
object BtGamepad {

    private const val TAG = "BtGamepad"

    /** 按键位定义（Linux HID button index → Android 按键） */
    // bit0=A(SOUTH) bit1=B(EAST) bit3=Y(NORTH) bit4=X(WEST)
    // bit5=LB(TL) bit6=RB(TR) bit7=Back(SELECT) bit8=Start bit9=Guide bit10=L3 bit11=R3
    const val BTN_A = 1 shl 0
    const val BTN_B = 1 shl 1
    const val BTN_Y = 1 shl 3
    const val BTN_X = 1 shl 4
    const val BTN_LB = 1 shl 5
    const val BTN_RB = 1 shl 6
    const val BTN_BACK = 1 shl 7
    const val BTN_START = 1 shl 8
    const val BTN_GUIDE = 1 shl 9
    const val BTN_L3 = 1 shl 10
    const val BTN_R3 = 1 shl 11

    /** 简化版 HID Gamepad Report Descriptor */
    private val REPORT_MAP = byteArrayOf(
        0x05, 0x01,        // Usage Page (Generic Desktop)
        0x09, 0x05,        // Usage (Game Pad)
        0xA1, 0x01,        // Collection (Application)
        0xA1, 0x00,        //   Collection (Physical)
        // ---- Buttons 1..16 ----
        0x05, 0x09,        //   Usage Page (Button)
        0x19, 0x01,        //   Usage Minimum (1)
        0x29, 0x10,        //   Usage Maximum (16)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x95, 0x10,        //   Report Count (16)
        0x75, 0x01,        //   Report Size (1)
        0x81, 0x02,        //   Input (Data, Var, Abs)
        // ---- Axes X/Y/Z/Rz/Rx/Ry, 16bit signed ----
        0x05, 0x01,        //   Usage Page (Generic Desktop)
        0x09, 0x30,        //   Usage (X)      左摇杆 X
        0x09, 0x31,        //   Usage (Y)      左摇杆 Y
        0x09, 0x32,        //   Usage (Z)      保留
        0x09, 0x35,        //   Usage (Rz)     保留
        0x09, 0x33,        //   Usage (Rx)     右摇杆 X
        0x09, 0x34,        //   Usage (Ry)     右摇杆 Y
        0x16, 0x01, 0x80,  //   Logical Minimum (-32767)
        0x26, 0xFF, 0x7F,  //   Logical Maximum (32767)
        0x75, 0x10,        //   Report Size (16)
        0x95, 0x06,        //   Report Count (6)
        0x81, 0x02,        //   Input (Data, Var, Abs)
        // ---- Triggers LT/RT, 8bit（brake/throttle → AXIS_LTRIGGER/RTRIGGER）----
        0x09, 0xC4,        //   Usage (Brake)  LT
        0x09, 0xC5,        //   Usage (Throttle) RT
        0x15, 0x00,        //   Logical Minimum (0)
        0x26, 0xFF, 0x00,  //   Logical Maximum (255)
        0x75, 0x08,        //   Report Size (8)
        0x95, 0x02,        //   Report Count (2)
        0x81, 0x02,        //   Input (Data, Var, Abs)
        0xC0,              //   End Collection (Physical)
        0xC0               // End Collection (Application)
    )

    enum class Status {
        IDLE,            // 未初始化
        REGISTERING,     // 正在注册 HID 应用
        READY,           // 已注册，等待选择主机连接
        CONNECTING,      // 连接中
        CONNECTED,       // 已连接（主机已出现虚拟手柄）
        DISCONNECTING    // 断开中
    }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val _hostName = MutableStateFlow("")
    val hostName: StateFlow<String> = _hostName

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var proxyConnected = false
    private var appRegistered = false

    private val scope = CoroutineScope(Dispatchers.IO)

    // -------- 当前手柄状态 --------
    @Volatile private var buttons = 0
    @Volatile private var lx = 0
    @Volatile private var ly = 0
    @Volatile private var rx = 0
    @Volatile private var ry = 0
    @Volatile private var lt = 0
    @Volatile private var rt = 0

    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    /** 初始化：获取 HID Device 代理并注册虚拟手柄 */
    fun init(context: Context) {
        if (proxyConnected) {
            _status.value = if (appRegistered) Status.READY else Status.REGISTERING
            return
        }
        _status.value = Status.REGISTERING
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = btManager.adapter
        val gotProxy = adapter?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    hid = proxy as? BluetoothHidDevice
                    registerApp()
                }

                override fun onServiceDisconnected(profile: Int) {
                    hid = null
                    proxyConnected = false
                    appRegistered = false
                    _status.value = Status.IDLE
                }
            },
            BluetoothProfile.HID_DEVICE
        ) ?: false
        if (!gotProxy) _status.value = Status.IDLE
    }

    private fun registerApp() {
        val h = hid ?: return
        val sdp = android.bluetooth.BluetoothHidDeviceApp.SdpRecord(
            "Xbox Wireless Controller",  // 伪装名称
            "Virtual Gamepad",
            "Android"
        )
        val app = if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ 可伪装 VID/PID（045E:02FD = Xbox Wireless Controller BLE）
            try {
                BluetoothHidDeviceApp.Builder(sdp, REPORT_MAP)
                    .setVendorId(0x045E)
                    .setProductId(0x02FD)
                    .setVersion(0x0100)
                    .build()
            } catch (_: Throwable) {
                BluetoothHidDeviceApp(sdp, REPORT_MAP, null, null)
            }
        } else {
            BluetoothHidDeviceApp(sdp, REPORT_MAP, null, null)
        }
        val ok = h.registerApp(app, { it.run() }, object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                appRegistered = registered
                if (registered) {
                    if (_status.value == Status.CONNECTING || host != null) {
                        // 注册成功后自动连接已选主机
                        host?.let { dv -> h.connect(dv) }
                    }
                    _status.value = if (_status.value == Status.CONNECTING) Status.CONNECTING else Status.READY
                } else {
                    _status.value = Status.IDLE
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        _hostName.value = device.name ?: device.address
                        _status.value = Status.CONNECTED
                        // 连接成功先发一帧全中位报告，让主机生成完整布局
                        sendReport()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (_status.value == Status.CONNECTED) {
                            _status.value = Status.READY
                            _hostName.value = ""
                        }
                    }
                }
            }
        })
        if (!ok) _status.value = Status.IDLE
    }

    /** 已配对设备列表（作为主机候选） */
    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    /** 连接到主机（运行原神的手机，需先配对） */
    fun connect(device: BluetoothDevice) {
        host = device
        _status.value = Status.CONNECTING
        val h = hid
        if (h == null) {
            // 代理还没好，init 后注册完成回调里会自动 connect
            return
        }
        if (!appRegistered) {
            registerApp()
            return
        }
        h.connect(device)
    }

    fun disconnect() {
        _status.value = Status.DISCONNECTING
        host?.let { hid?.disconnect(it) }
        // 有些实现需要再 unregisterApp 才能被重新连接
        scope.launch {
            delay(500)
            _status.value = Status.READY
        }
    }

    // -------- 状态更新 API（UI 调用） --------

    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
        sendReportThrottled()
    }

    /** 摇杆：x/y ∈ [-1.0, 1.0] */
    fun setLeftStick(x: Float, y: Float) {
        lx = clamp16(x); ly = clamp16(y)
        sendReportThrottled()
    }

    fun setRightStick(x: Float, y: Float) {
        rx = clamp16(x); ry = clamp16(y)
        sendReportThrottled()
    }

    /** 扳机：v ∈ [0.0, 1.0] */
    fun setTriggers(left: Float, right: Float) {
        lt = (left.coerceIn(0f, 1f) * 255).roundToInt()
        rt = (right.coerceIn(0f, 1f) * 255).roundToInt()
        sendReportThrottled()
    }

    fun releaseAll() {
        buttons = 0; lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0
        sendReport()
    }

    private fun clamp16(v: Float): Int =
        (v.coerceIn(-1f, 1f) * 32767f).roundToInt()

    @Volatile private var lastSend = 0L

    private fun sendReportThrottled() {
        if (_status.value != Status.CONNECTED) return
        val now = System.currentTimeMillis()
        if (now - lastSend < 15) return  // ~60fps 上限
        lastSend = now
        sendReport()
    }

    private fun sendReport() {
        val h = hid ?: return
        val hostDev = host ?: return
        val report = ByteArray(16)
        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()
        put16(report, 2, lx)   // X
        put16(report, 4, ly)   // Y
        put16(report, 6, 0)    // Z  保留
        put16(report, 8, 0)    // Rz 保留
        put16(report, 10, rx)  // Rx
        put16(report, 12, ry)  // Ry
        report[14] = lt.toByte()
        report[15] = rt.toByte()
        try {
            h.sendReport(hostDev, 0, report)
        } catch (e: Exception) {
            Log.w(TAG, "sendReport failed: ${e.message}")
        }
    }

    private fun put16(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
