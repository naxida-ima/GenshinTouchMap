# 提瓦特触控映射 (GenshinTouchMap)

把 FPS 玩家的按键习惯，映射到《原神》手游上。悬浮层自定义按键 + 虚拟摇杆 + 多指同时模拟触摸，免 root。

## 功能

- **自定义按键**：新建任意数量的按键（点击键 / 长按键），可选手形（圆形/矩形），拖拽摆放位置，长按进入「选点模式」点击原神中对应按键的位置完成映射
- **两层可视化**：编辑模式下，游戏层目标位置显示同形状的虚线框标记，一眼看清「虚拟按键 -> 游戏按键」的对应关系
- **虚拟摇杆**：FPS 习惯的移动摇杆，拖动方向与幅度按比例映射到原神的摇杆
- **双注入引擎**：
  - 引擎 B **Shizuku**（推荐）：`IInputManager.injectInputEvent` 注入真实触摸事件，多指并行流畅、无打断
  - 引擎 A **无障碍服务**：免 root 免 adb 开箱即用，多指有轻微重建打断
- **多指同时操作**：移动 + 瞄准 + 射击 + 跳跃可同时进行
- **完全隔离**：每个按键是独立的小悬浮窗口，只拦截自己的触摸区域，其余区域透传给游戏

## 技术栈

- Kotlin 2.3 + Compose (Material 3 / Material You 动态取色) + coroutines + Flow
- DataStore (Preferences) 配置持久化
- kotlinx.serialization JSON 序列化
- Shizuku API 13.1.5（高权限输入注入）
- targetSdk 36 (Android 16) / minSdk 26（Android 13 设备完全兼容，APK 使用 v2 签名）

## 构建

```bash
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 已配置：push 到 main 自动构建并上传 APK artifact。

## 使用方法

1. **安装并打开 App**，依次授予：
   - 悬浮窗权限（点击"去开启"）
   - 无障碍权限（点击"去开启"，找到"提瓦特触控映射（触摸模拟服务）"并打开）
   - 通知权限（Android 13+ 自动弹窗请求）
2. **（推荐）启用 Shizuku 引擎**：安装 [Shizuku](https://shizuku.rikka.app/) 并按说明启动（无线调试/ADB），回到本 App 点击「启用 Shizuku」并授权——多指操作更流畅
3. 点「启动悬浮层」
4. 打开原神，屏幕右侧会出现一个悬浮球（点击可在 编辑/运行 模式间切换）
5. **编辑模式**：
   - 拖动按键/摇杆 → 调整摆放位置（跟随手指，跟手无抽搐）
   - 长按按键 → 进入选点模式，点击原神里对应按键的位置，完成映射；游戏层会出现同形状虚线框标记
   - 摇杆长按 → 设置摇杆映射中心（原神移动摇杆的中心）
6. **运行模式**：按键和摇杆正常工作

## 架构

```
MainActivity (Compose 配置界面 · Material You)
      │  增删改配置
      ▼
OverlayService (前台服务)
  ├── KeyButtonView / JoystickView / FloatBallView / TargetMarkerView
  │     —— 每个按键一个独立 Window 窗口（只拦截自己的触摸区域）
  ├── 编辑模式: 拖动位置（raw 坐标跟手）/ 选点映射 / 目标标记
  └── 运行模式: 触摸事件 → EngineManager
      │
      ▼
EngineManager (引擎选择：Shizuku 优先，无障碍兜底)
  ├── ShizukuInjector (引擎 B · 推荐)
  │     SystemServiceHelper.getSystemService("input")
  │     + ShizukuBinderWrapper + 反射 IInputManager$Stub.asInterface
  │     + injectInputEvent(MotionEvent, ASYNC) —— 真实多指注入
  └── TouchMapperService (引擎 A · 无障碍 dispatchGesture)
        手指状态机 + 全量重建手势 + 长按住心跳
```

## 已知限制（务必阅读）

1. **Shizuku 需要安装并启动 Shizuku 应用**（无线调试激活一次）；未启用时自动回退无障碍引擎
2. **无障碍引擎的物理上限**：每次手指状态变化会短暂"重建"所有手指，未变化的手指有毫秒级中断——Shizuku 引擎无此问题
3. **封号风险**：输入模拟类工具均存在被反作弊检测的风险，**强烈建议先用小号验证**
4. **未设置映射目标的按键**：运行时按到屏幕 (0,0)，请确保每个按键都完成选点映射

## Roadmap

- [ ] 按键配置模板（FPS 布局一键套用）
- [ ] 按键大小 / 透明度调节
- [ ] 灵敏度曲线（摇杆响应曲线）

## 免责声明

本工具仅供学习与技术研究。使用第三方输入模拟工具可能违反游戏用户协议，由此产生的账号风险由使用者自行承担。
