package org.traccar.client.xposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Traccar Client LSPosed / Xposed 系统级保活核心入口。
 * 实现标准 IXposedHookLoadPackage 接口，被 LSPosed 框架在 Zygote 初始化时动态加载：
 * 1. 目标包自身 (org.traccar.client)：打桩标记激活状态，供 Flutter UI 和保活中枢展示。
 * 2. 系统服务进程 (android / system_server)：拦截 AMS 杀后台请求、锁定待机桶与打盹状态。
 */
class TraccarXposedEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "TraccarXposed"
        const val TARGET_PACKAGE = "org.traccar.client"

        /**
         * 模块在 LSPosed 中的激活状态标识
         */
        @JvmStatic
        var isModuleActive: Boolean = false

        @JvmStatic
        fun checkActive(): Boolean {
            return isModuleActive
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return

        if (packageName == TARGET_PACKAGE) {
            // 在应用本进程中，将激活状态打桩置为 true
            isModuleActive = true
            try {
                XposedHelpers.findAndHookMethod(
                    "org.traccar.client.keepalive.XposedState",
                    lpparam.classLoader,
                    "checkActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    },
                )
                Log.i(TAG, "LSPosed active hook successfully applied to XposedState in Traccar Client process.")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook checkActive in self process: ${t.message}")
            }
            return
        }

        if (packageName == "android" || packageName == "system") {
            // 在 system_server 系统进程中挂载防杀与保活钩子
            installSystemServerHooks(lpparam.classLoader)
        }
    }

    private fun installSystemServerHooks(classLoader: ClassLoader) {
        XposedBridge.log("Traccar Client KeepAlive: system_server reached, installing hooks for $TARGET_PACKAGE")

        // 1. 拦截 AMS 的 killBackgroundProcesses
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.ActivityManagerService",
                classLoader,
                "killBackgroundProcesses",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args[0] as? String
                        if (pkg == TARGET_PACKAGE) {
                            param.result = null // 直接拦截取消杀进程
                            XposedBridge.log("Traccar Client KeepAlive: intercepted killBackgroundProcesses for $TARGET_PACKAGE")
                        }
                    }
                },
            )
            Log.i(TAG, "Hooked killBackgroundProcesses successfully.")
        } catch (t: Throwable) {
            Log.d(TAG, "killBackgroundProcesses hook skipped or not matched: ${t.message}")
        }

        // 2. 拦截 AppStandbyController.setAppStandbyBucket (待机桶保护)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.usage.AppStandbyController",
                classLoader,
                "setAppStandbyBucket",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args[0] as? String
                        if (pkg == TARGET_PACKAGE) {
                            // 强制将待机桶锁定为 STANDBY_BUCKET_ACTIVE (10)
                            param.args[1] = 10
                            XposedBridge.log("Traccar Client KeepAlive: locked app standby bucket to ACTIVE (10)")
                        }
                    }
                },
            )
            Log.i(TAG, "Hooked setAppStandbyBucket successfully.")
        } catch (t: Throwable) {
            Log.d(TAG, "setAppStandbyBucket hook skipped: ${t.message}")
        }
    }
}
