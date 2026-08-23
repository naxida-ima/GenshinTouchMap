package com.nahida.touchmap.mapper

/**
 * 注入引擎管理器：
 * - 引擎 B Shizuku（多指流畅，需授权）优先
 * - 引擎 A 无障碍服务（免配置开箱即用）兜底
 */
object EngineManager {

    @Volatile
    private var shizukuInjector: ShizukuInjector? = null

    @Volatile
    private var shizukuEnabled = false

    /** 尝试启用 Shizuku 引擎（需已授权） */
    fun enableShizuku() {
        if (shizukuInjector == null) {
            shizukuInjector = ShizukuInjector()
        }
        shizukuEnabled = shizukuInjector?.isReady() == true
    }

    fun isShizukuEnabled(): Boolean = shizukuEnabled

    /** 当前生效的注入引擎 */
    fun current(): TouchInjector? =
        if (shizukuEnabled) shizukuInjector else TouchMapperService.instance

    fun engineName(): String = if (shizukuEnabled) "Shizuku" else "无障碍"

    fun engineDescription(): String =
        if (shizukuEnabled) "Shizuku 注入（多指流畅，推荐）" else "无障碍模拟（开箱即用）"
}
