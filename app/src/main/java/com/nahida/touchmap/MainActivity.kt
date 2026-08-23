package com.nahida.touchmap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nahida.touchmap.data.ConfigStore
import com.nahida.touchmap.mapper.EngineManager
import com.nahida.touchmap.model.KeyShape
import com.nahida.touchmap.model.KeyType
import com.nahida.touchmap.model.VirtualKey
import com.nahida.touchmap.overlay.OverlayService
import com.nahida.touchmap.remote.ConfigPickerService
import com.nahida.touchmap.remote.RemoteKey
import com.nahida.touchmap.remote.RemoteKeyStore
import com.nahida.touchmap.remote.RemoteServerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

/** Material You 动态取色主题（Android 12+ 跟随系统壁纸；低版本回退静态配色） */
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keys by remember { mutableStateOf<List<VirtualKey>>(emptyList()) }
    var editMode by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(OverlayService.isRunning()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var shizukuReady by remember { mutableStateOf(EngineManager.isShizukuReady()) }
    var useShizuku by remember { mutableStateOf(EngineManager.useShizuku) }
    // 模式：0=本机 1=发射 2=接收（sender 精简版只有发射）
    var mode by remember { mutableStateOf(if (BuildConfig.IS_FULL) 0 else 1) }

    // 收集配置流
    LaunchedEffect(Unit) {
        launch { ConfigStore.keysFlow(context).collect { keys = it } }
        launch { ConfigStore.editModeFlow(context).collect { editMode = it } }
    }

    // Shizuku 授权结果监听
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                EngineManager.enableShizuku()
                shizukuReady = EngineManager.isShizukuReady()
                Toast.makeText(context, "Shizuku 引擎已启用", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    fun requestShizuku() {
        val granted = runCatching {
            !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            EngineManager.enableShizuku()
            shizukuReady = EngineManager.isShizukuReady()
            Toast.makeText(context, "Shizuku 引擎已启用（注意：部分系统上注入会触发触摸仲裁导致长按失效）", Toast.LENGTH_SHORT).show()
        } else {
            runCatching { Shizuku.requestPermission(1001) }
                .onFailure {
                    Toast.makeText(context, "Shizuku 未运行，请先启动 Shizuku 应用", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // 请求通知权限（Android 13+）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("提瓦特触控映射", style = MaterialTheme.typography.headlineSmall)
            Text(
                "双机触控：发射端（虚拟按键）+ 接收端（注入游戏），彻底避开触摸仲裁",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            // 模式切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeButton("本机模式", mode == 0) { mode = 0 }
                ModeButton("发射模式", mode == 1) { mode = 1 }
                if (BuildConfig.IS_FULL) {
                    ModeButton("接收模式", mode == 2) { mode = 2 }
                }
            }
            Spacer(Modifier.height(8.dp))

            when (mode) {
                0 -> {
                    // ===== 本机模式 =====
            // 权限状态
            PermissionRow(context)
            Spacer(Modifier.height(8.dp))

            // 注入引擎（两个引擎彻底分开，显式选择）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("注入引擎：${EngineManager.engineName()}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                EngineManager.engineDescription(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = useShizuku,
                            onCheckedChange = { e ->
                                useShizuku = e
                                EngineManager.useShizuku = e
                                Toast.makeText(
                                    context,
                                    if (e) "已切换：Shizuku 引擎" else "已切换：无障碍引擎",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                    if (useShizuku && !shizukuReady) {
                        TextButton(onClick = { requestShizuku() }) { Text("授权 Shizuku") }
                    }
                    if (!useShizuku && !EngineManager.isAccessibilityReady()) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("开启无障碍服务") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 服务开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        context.startForegroundService(
                            Intent(context, OverlayService::class.java)
                        )
                        running = true
                    },
                    enabled = !running
                ) { Text("启动悬浮层") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        context.stopService(Intent(context, OverlayService::class.java))
                        running = false
                    },
                    enabled = running
                ) { Text("停止") }
                Spacer(Modifier.weight(1f))
                Text(if (running) "● 运行中" else "○ 已停止")
            }

            // 编辑模式
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("编辑模式（拖动按键位置 / 长按设置映射目标）")
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = editMode,
                    onCheckedChange = { e ->
                        scope.launch { ConfigStore.setEditMode(context, e) }
                        OverlayService.setEditModeExternal(e)
                    }
                )
            }
            Spacer(Modifier.height(12.dp))

            // 按键列表
            Text("按键配置（${keys.size}）", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(keys, key = { it.id }) { key ->
                    KeyRow(key = key, onDelete = {
                        scope.launch {
                            ConfigStore.saveKeys(context, keys.filterNot { it.id == key.id })
                            OverlayService.refresh()
                        }
                    })
                }
            }
            Spacer(Modifier.height(8.dp))

            // 新建
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("新建按键 / 摇杆")
            }
                }
                // ===== 本机模式结束 =====

                1 -> SenderTabContent(context, scope)

                2 -> if (BuildConfig.IS_FULL) ReceiverTabContent(context)
            }
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            onConfirm = { type, shape, label ->
                val newKey = VirtualKey(
                    type = type,
                    shape = shape,
                    label = label,
                    keyId = keys.size,
                    x = 0.5f,
                    y = 0.5f
                )
                scope.launch {
                    ConfigStore.saveKeys(context, keys + newKey)
                    OverlayService.refresh()
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (selected) {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

/** 发射端（操作层）：连接接收端，触摸转发远端 */
@Composable
private fun SenderTabContent(context: Context, scope: CoroutineScope) {
    var ip by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("7890") }
    var connected by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf<List<VirtualKey>>(emptyList()) }
    var editMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { ConfigStore.keysFlow(context).collect { keys = it } }
        launch { ConfigStore.editModeFlow(context).collect { editMode = it } }
    }

    Column {
        Text("发射端（操作层）", style = MaterialTheme.typography.titleMedium)
        Text("本机显示虚拟按键，触摸实时转发给接收端注入游戏", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("接收端 IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                context.startForegroundService(Intent(context, OverlayService::class.java))
                OverlayService.startRemoteMode(ip.trim(), port.toIntOrNull() ?: 7890)
                connected = true
                Toast.makeText(context, "发射层已启动，请确保接收端已监听", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("连接并启动发射层") }
        TextButton(
            onClick = {
                OverlayService.stopRemoteMode()
                context.stopService(Intent(context, OverlayService::class.java))
                connected = false
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("断开") }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("编辑模式（拖动位置 / 长按选点 / 右下角调大小）", modifier = Modifier.weight(1f))
            Switch(
                checked = editMode,
                onCheckedChange = { e ->
                    scope.launch { ConfigStore.setEditMode(context, e) }
                    OverlayService.setEditModeExternal(e)
                }
            )
        }
        Text("发射按键（${keys.size}）—— 编号 #N = keyId，须与接收端配置一致（0=摇杆 1=攻击 2=跳跃…）", style = MaterialTheme.typography.bodySmall)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(keys, key = { it.id }) { key ->
                KeyRow(key = key, onDelete = {
                    scope.launch {
                        ConfigStore.saveKeys(context, keys.filterNot { it.id == key.id })
                        OverlayService.refresh()
                    }
                })
            }
        }
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("新建发射按键 / 摇杆")
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            onConfirm = { type, shape, label ->
                val newKey = VirtualKey(
                    type = type,
                    shape = shape,
                    label = label,
                    keyId = keys.size,
                    x = 0.5f,
                    y = 0.5f
                )
                scope.launch {
                    ConfigStore.saveKeys(context, keys + newKey)
                    OverlayService.refresh()
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

/** 接收端（注入层）：监听发射端指令，Shizuku 注入游戏 */
@Composable
private fun ReceiverTabContent(context: Context) {
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf("7890") }
    var listening by remember { mutableStateOf(RemoteServerHolder.listening) }
    var connected by remember { mutableStateOf(RemoteServerHolder.connected) }
    var remoteKeys by remember { mutableStateOf<List<RemoteKey>>(emptyList()) }
    var shizukuReady by remember { mutableStateOf(EngineManager.isShizukuReady()) }

    LaunchedEffect(Unit) {
        launch { RemoteKeyStore.keysFlow(context).collect { remoteKeys = it } }
    }

    Column {
        Text("接收端（注入层）", style = MaterialTheme.typography.titleMedium)
        Text("监听发射端指令 → Shizuku 注入游戏。游戏设备无真实触摸 → 无仲裁，长按/多指稳定", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("注入引擎：${EngineManager.engineName()}（${if (shizukuReady) "可用" else "不可用"}）", style = MaterialTheme.typography.titleSmall)
                Text(EngineManager.engineDescription(), style = MaterialTheme.typography.bodySmall)
                if (!shizukuReady) {
                    TextButton(onClick = {
                        runCatching { Shizuku.requestPermission(1002) }
                            .onFailure { Toast.makeText(context, "Shizuku 未运行，请先启动", Toast.LENGTH_SHORT).show() }
                    }) { Text("授权 Shizuku") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("监听端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (listening) {
                    RemoteServerHolder.stop()
                } else {
                    RemoteServerHolder.start(context, port.toIntOrNull() ?: 7890)
                }
                listening = RemoteServerHolder.listening
                connected = RemoteServerHolder.connected
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (listening) "停止监听" else "开始监听") }
        Text(
            if (connected) "● 发射端已连接" else "○ 等待发射端连接（同一 WiFi，IP:端口 $port）",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        // 注入点配置：游戏上点选（所见即所得，无需调坐标）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("注入点配置（预置原神模板）", style = MaterialTheme.typography.titleSmall)
                Text("打开游戏后点下方按钮，直接在游戏画面上点击各按键位置", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            ConfigPickerService.start(context)
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                            Toast.makeText(context, "需要悬浮窗权限才能显示配置层", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("在游戏上点选配置注入点") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("当前配置：", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(remoteKeys, key = { it.keyId }) { rk ->
                RemoteKeyRow(rk)
            }
        }
    }
}

@Composable
private fun RemoteKeyRow(key: RemoteKey) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "#${key.keyId} ${key.name}${if (key.isJoystick) "（摇杆）" else ""}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "注入点 (${"%.0f".format(key.targetX * 100)}%, ${"%.0f".format(key.targetY * 100)}%)" +
                            if (key.isJoystick) "  半径 ${"%.0f".format(key.joystickRadius * 100)}%" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(context: Context) {
    val canOverlay = Settings.canDrawOverlays(context)
    val accessibilityEnabled = isAccessibilityEnabled(context)
    val missing = !canOverlay || !accessibilityEnabled

    if (missing) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("需要以下权限：", style = MaterialTheme.typography.titleSmall)
                if (!canOverlay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• 悬浮窗：")
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }) { Text("去开启") }
                    }
                }
                if (!accessibilityEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• 无障碍（触摸模拟）：")
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("去开启") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${com.nahida.touchmap.mapper.TouchMapperService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

@Composable
private fun KeyRow(key: VirtualKey, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${key.label}  #${key.keyId}", style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(if (key.type == KeyType.JOYSTICK) "摇杆" else "按键")
                        append(" · 位置(").append("%.0f".format(key.x * 100)).append("%,")
                            .append("%.0f".format(key.y * 100)).append("%)")
                        if (key.targetX >= 0) {
                            append(" · 映射(").append("%.0f".format(key.targetX * 100))
                                .append("%,").append("%.0f".format(key.targetY * 100)).append("%)")
                        } else {
                            append(" · 未设置映射")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun AddKeyDialog(
    onConfirm: (KeyType, KeyShape, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by remember { mutableStateOf(KeyType.TAP) }
    var selectedShape by remember { mutableStateOf(KeyShape.CIRCLE) }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建按键 / 摇杆") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（如：跳跃 / 切枪）") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    KeyType.TAP to "按键（轻点=单发，长按=连发，开火键首选）",
                    KeyType.JOYSTICK to "摇杆（移动）"
                ).forEach { (type, desc) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type }
                        )
                        Text(desc)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("按键形状（虚拟层与游戏层标记同形状）", style = MaterialTheme.typography.bodySmall)
                listOf(
                    KeyShape.CIRCLE to "圆形",
                    KeyShape.RECTANGLE to "矩形（适合切枪等按键）"
                ).forEach { (shape, desc) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedShape == shape,
                            onClick = { selectedShape = shape }
                        )
                        Text(desc)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        selectedType,
                        selectedShape,
                        label.ifBlank { if (selectedType == KeyType.JOYSTICK) "摇杆" else "按键" }
                    )
                }
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
