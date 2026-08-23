package com.nahida.touchmap.mapper

/**
 * 注入引擎管理器（引擎彻底分离，由用户显式选择，不做静默回退）：
 * - 引擎 B Shizuku：多指流畅、低延迟（推荐，需授权）
 * - 引擎 A 无障碍：开箱即用（作为独立选择，与 Shizuku 互不影响）
 */
object EngineManager {

    @Volatile
    private var shizukuInjector: ShizukuInjector? = null

    /** 用户选择：true = Shizuku，false = 无障碍。
     *  默认无障碍：Shizuku 的 injectInputEvent 会触发 ColorOS 触摸仲裁（取消真实触摸 → 长按失效），
     *  无障碍 dispatchGesture 走不同注入通道，长按正常（实测验证）。 */
    @Volatile
    var useShizuku = false

    /** 尝试初始化 Shizuku 引擎（需已授权；失败则保持可用状态为 false） */
    fun enableShizuku() {
        if (shizukuInjector == null) {
            shizukuInjector = ShizukuInjector()
        }
    }

    fun isShizukuReady(): Boolean = shizukuInjector?.isReady() == true

    fun isAccessibilityReady(): Boolean = TouchMapperService.instance != null

    /** 当前生效的注入引擎（严格按用户选择，不静默回退） */
    fun current(): TouchInjector? =
        if (useShizuku) shizukuInjector?.takeIf { it.isReady() } else TouchMapperService.instance

    fun engineName(): String = if (useShizuku) "Shizuku" else "无障碍"

    fun engineDescription(): String = when {
        useShizuku && isShizukuReady() -> "Shizuku 注入（⚠️ 部分系统会触发触摸仲裁导致长按失效）"
        useShizuku -> "Shizuku（未授权/未运行，注入不可用）"
        isAccessibilityReady() -> "无障碍模拟（长按稳定，推荐）"
        else -> "无障碍（未开启，注入不可用）"
    }
}
