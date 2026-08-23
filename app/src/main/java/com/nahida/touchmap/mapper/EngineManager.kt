package com.nahida.touchmap.mapper

/**
 * 注入引擎管理器（引擎分离，显式选择）：
 * - 引擎 C 手柄映射（假映射）：overlay 触摸 → 手柄事件注入（原神手柄模式），无触摸仲裁，推荐
 * - 引擎 B Shizuku 触摸注入：injectInputEvent 触摸（部分系统触发仲裁）
 * - 引擎 A 无障碍触摸：dispatchGesture（开箱即用）
 */
object EngineManager {

    @Volatile
    private var shizukuInjector: ShizukuInjector? = null

    @Volatile
    private var gamepadInjector: GamepadInjector? = null

    /** 触摸引擎选择：true = Shizuku，false = 无障碍（本机触摸注入用） */
    @Volatile
    var useShizuku = true

    /** 手柄映射模式（假映射）：true 时触摸事件转手柄输入注入 */
    @Volatile
    var useGamepad = false

    fun enableShizuku() {
        if (shizukuInjector == null) {
            shizukuInjector = ShizukuInjector()
        }
    }

    fun enableGamepad() {
        if (gamepadInjector == null) {
            gamepadInjector = GamepadInjector()
        }
    }

    fun isShizukuReady(): Boolean = shizukuInjector?.isReady() == true

    fun isAccessibilityReady(): Boolean = TouchMapperService.instance != null

    fun isGamepadReady(): Boolean = gamepadInjector?.isReady() == true

    /** 手柄映射注入器（假映射模式用） */
    fun gamepad(): TouchInjector? = gamepadInjector?.takeIf { it.isReady() }

    /** 当前生效的触摸注入引擎（本机触摸注入用，严格按用户选择） */
    fun current(): TouchInjector? =
        if (useShizuku) shizukuInjector?.takeIf { it.isReady() } else TouchMapperService.instance

    fun engineName(): String = if (useShizuku) "Shizuku" else "无障碍"

    fun engineDescription(): String = when {
        useGamepad -> "手柄映射（假映射，无仲裁，推荐）"
        useShizuku && isShizukuReady() -> "Shizuku 注入（⚠️ 部分系统会触发触摸仲裁导致长按失效）"
        useShizuku -> "Shizuku（未授权/未运行，注入不可用）"
        isAccessibilityReady() -> "无障碍模拟（开箱即用）"
        else -> "无障碍（未开启，注入不可用）"
    }
}
