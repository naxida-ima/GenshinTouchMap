package com.nahida.touchmap.btgamepad

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_A
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_B
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_BACK
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_L3
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_LB
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_R3
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_RB
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_START
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_X
import com.nahida.touchmap.btgamepad.BtGamepad.BTN_Y
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 蓝牙手柄页：把本机伪装成 Xbox 手柄，连接到运行原神的手机
 */
@Composable
fun GamepadTabContent() {
    val context = LocalContext.current
    val status by BtGamepad.status.collectAsState()
    val hostName by BtGamepad.hostName.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) BtGamepad.init(context)
        else Toast.makeText(context, "需要蓝牙权限才能虚拟手柄", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        if (BtGamepad.hasConnectPermission(context)) {
            BtGamepad.init(context)
        } else if (Build.VERSION.SDK_INT >= 31) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (status) {
                        BtGamepad.Status.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    val statusText = when (status) {
                        BtGamepad.Status.IDLE -> "未初始化"
                        BtGamepad.Status.REGISTERING -> "正在注册虚拟手柄…"
                        BtGamepad.Status.READY -> "就绪：选择下方已配对的主机连接"
                        BtGamepad.Status.CONNECTING -> "连接中…"
                        BtGamepad.Status.CONNECTED -> "已连接 → $hostName"
                        BtGamepad.Status.DISCONNECTING -> "断开中…"
                    }
                    Text("虚拟 Xbox 手柄", style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    if (status == BtGamepad.Status.CONNECTED) {
                        Text(
                            "主机蓝牙设置里应显示「Xbox Wireless Controller」，打开原神 → 设置 → 控制设备 切换为手柄",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 主机列表（连接前显示）
        if (status != BtGamepad.Status.CONNECTED) {
            item {
                Text("已配对设备（选运行原神的手机）：", style = MaterialTheme.typography.titleSmall)
            }
            val devices = remember(status) {
                runCatching { BtGamepad.bondedDevices() }.getOrDefault(emptyList())
            }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "没有已配对设备。请先在两台手机的系统蓝牙设置里完成配对。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            items(devices) { dev ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        try {
                            BtGamepad.connect(dev)
                        } catch (e: SecurityException) {
                            Toast.makeText(context, "缺少蓝牙权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dev.name ?: "未知设备", style = MaterialTheme.typography.bodyLarge)
                            Text(dev.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("连接", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 手柄控制面板（已连接显示）
        if (status == BtGamepad.Status.CONNECTED) {
            item {
                // 释放所有 + 断开
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        BtGamepad.releaseAll()
                        BtGamepad.disconnect()
                    }
                ) {
                    Text(
                        "断开虚拟手柄",
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            item { Text("左摇杆（移动）", style = MaterialTheme.typography.titleSmall) }
            item { StickPad { x, y -> BtGamepad.setLeftStick(x, y) } }
            item { Text("右摇杆（视角）", style = MaterialTheme.typography.titleSmall) }
            item { StickPad { x, y -> BtGamepad.setRightStick(x, y) } }
            item { Text("按键", style = MaterialTheme.typography.titleSmall) }
            item { GamepadButtons() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 摇杆触摸区 */
@Composable
private fun StickPad(onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        active = true
                        val r = min(size.width, size.height) / 2f
                        knob = off
                        onMove(off.x / r - 1f, off.y / r - 1f)
                    },
                    onDrag = { change, _ ->
                        val r = min(size.width, size.height) / 2f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        var dx = change.position.x - c.x
                        var dy = change.position.y - c.y
                        val len = sqrt(dx * dx + dy * dy)
                        if (len > r) { dx *= r / len; dy *= r / len }
                        knob = c + Offset(dx, dy)
                        onMove(dx / r, dy / r)
                    },
                    onDragEnd = {
                        active = false
                        knob = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        active = false
                        knob = Offset.Zero
                        onMove(0f, 0f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(48.dp)) {
            drawCircle(
                color = if (active) Color(0xFF7C4DFF) else Color.Gray,
                radius = size.minDimension / 2f
            )
        }
        if (!active) {
            Text("拖动", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        // knob 位置指示由背景圆环替代，避免复杂层
    }
}

/** 按键网格 */
@Composable
private fun GamepadButtons() {
    val rows = listOf(
        listOf("A 跳" to BTN_A, "B" to BTN_B, "X" to BTN_X, "Y" to BTN_Y),
        listOf("LB" to BTN_LB, "RB" to BTN_RB, "L3" to BTN_L3, "R3" to BTN_R3),
        listOf("Back" to BTN_BACK, "Start" to BTN_START)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, bit) ->
                    var pressed by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(
                                if (pressed) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                1.dp, MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            )
                            .pointerInput(bit) {
                                detectTapGestures(
                                    onPress = {
                                        pressed = true
                                        BtGamepad.setButton(bit, true)
                                        tryAwaitRelease()
                                        pressed = false
                                        BtGamepad.setButton(bit, false)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label)
                    }
                }
                // 补齐空位
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
