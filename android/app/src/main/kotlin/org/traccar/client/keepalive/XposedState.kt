package org.traccar.client.keepalive

/**
 * 独立的 Xposed 激活状态单例。
 * 纯粹的平台状态持有者，不继承或实现任何 Xposed API 接口，
 * 避免在未注入 Xposed 运行时的普通应用进程中因类链接解析缺失接口而抛出 NoClassDefFoundError。
 */
object XposedState {

    @Volatile
    var isModuleActive: Boolean = false

    @JvmStatic
    fun checkActive(): Boolean {
        return isModuleActive
    }
}
