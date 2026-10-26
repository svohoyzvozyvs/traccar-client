package org.traccar.client.keepalive

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 保活中枢管理器，统一协调 Root 模式、无障碍模式与应用层策略。
 */
object KeepAliveManager {

    private const val TAG = "TraccarKeepAlive"
    private const val PREFS_NAME = "traccar_keepalive_prefs"

    const val KEY_ROOT_OOM_LOCK = "keepalive_root_oom_lock"
    const val KEY_ROOT_WHITELIST = "keepalive_root_whitelist"
    const val KEY_ROOT_WATCHDOG = "keepalive_root_watchdog"
    const val KEY_ACCESSIBILITY = "keepalive_accessibility"

    private var accessibilityGuardJob: Job? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isRootOomLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ROOT_OOM_LOCK, false)
    }

    /**
     * 应用启动或系统开机时初始化保活策略（如果有配置则自动生效并恢复）
     */
    suspend fun onAppStart(context: Context) = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context)
        val oomLock = prefs.getBoolean(KEY_ROOT_OOM_LOCK, false)
        val watchdog = prefs.getBoolean(KEY_ROOT_WATCHDOG, false)
        val accessibility = prefs.getBoolean(KEY_ACCESSIBILITY, false)

        if (oomLock && RootShellExecutor.canUseRoot()) {
            RootShellExecutor.lockOomScoreAdj()
        }

        if (watchdog && RootShellExecutor.canUseRoot()) {
            if (!RootShellExecutor.isWatchdogRunning(context.packageName)) {
                RootShellExecutor.startWatchdog(context, context.packageName)
            }
        }

        if (accessibility) {
            // 开机/启动时如果配置了无障碍保活，且无障碍服务当前未连接，若有 Root 则立即恢复
            if (!TraccarAccessibilityService.isAccessibilityEnabled(context) && RootShellExecutor.canUseRoot()) {
                Log.i(TAG, "onAppStart: accessibility was disconnected, restoring via Root...")
                RootShellExecutor.enableAccessibilityServiceViaRoot(
                    context.packageName,
                    TraccarAccessibilityService::class.java.canonicalName ?: "${context.packageName}.keepalive.TraccarAccessibilityService"
                )
            }
            startPeriodicAccessibilityGuard(context)
        }
    }

    /**
     * 启动应用内的定时无障碍服务巡检（每 30 秒检测一次，掉线自动静默重连）
     */
    fun startPeriodicAccessibilityGuard(context: Context) {
        if (accessibilityGuardJob?.isActive == true) return
        accessibilityGuardJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Periodic accessibility guard started.")
            while (isActive) {
                delay(30_000L)
                val prefs = getPrefs(context)
                val configured = prefs.getBoolean(KEY_ACCESSIBILITY, false)
                if (configured) {
                    val isAlive = TraccarAccessibilityService.isAccessibilityEnabled(context)
                    if (!isAlive && RootShellExecutor.canUseRoot()) {
                        Log.w(TAG, "Periodic guard detected accessibility service was dropped, auto restoring via Root...")
                        RootShellExecutor.enableAccessibilityServiceViaRoot(
                            context.packageName,
                            TraccarAccessibilityService::class.java.canonicalName ?: "${context.packageName}.keepalive.TraccarAccessibilityService"
                        )
                    }
                }
            }
        }
    }

    /**
     * 采集当前系统的保活全量状态数据返回给 Flutter 端
     */
    suspend fun getStatus(context: Context): Map<String, Any?> = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context)
        val hasRoot = RootShellExecutor.canUseRoot()
        val oomAdj = RootShellExecutor.getOomScoreAdj()
        val watchdogRunning = if (hasRoot) RootShellExecutor.isWatchdogRunning(context.packageName) else false
        val accessibilityActive = TraccarAccessibilityService.isAccessibilityEnabled(context)
        val xposedActive = XposedState.checkActive() || 
            (hasRoot && RootShellExecutor.isLSPosedModuleEnabledInSystem(context.packageName))
        val batteryIgnored = isIgnoringBatteryOptimizations(context)

        Log.i(TAG, "KeepAlive getStatus: isXposedActive=$xposedActive, oomScoreAdj=$oomAdj, watchdog=$watchdogRunning, accessibility=$accessibilityActive, hasRoot=$hasRoot")

        mapOf(
            "hasRoot" to hasRoot,
            "oomScoreAdj" to oomAdj,
            "isWatchdogRunning" to watchdogRunning,
            "isAccessibilityActive" to accessibilityActive,
            "isXposedActive" to xposedActive,
            "isIgnoringBatteryOptimizations" to batteryIgnored,
            "rootOomLockConfigured" to prefs.getBoolean(KEY_ROOT_OOM_LOCK, false),
            "rootWhitelistConfigured" to prefs.getBoolean(KEY_ROOT_WHITELIST, false),
            "rootWatchdogConfigured" to prefs.getBoolean(KEY_ROOT_WATCHDOG, false),
            "accessibilityConfigured" to prefs.getBoolean(KEY_ACCESSIBILITY, false),
        )
    }

    /**
     * 更新并立即应用保活配置项
     */
    suspend fun applySettings(context: Context, settings: Map<String, Any>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()

        if (settings.containsKey("rootOomLock")) {
            val enabled = settings["rootOomLock"] as? Boolean ?: false
            editor.putBoolean(KEY_ROOT_OOM_LOCK, enabled)
            if (enabled && RootShellExecutor.canUseRoot()) {
                RootShellExecutor.lockOomScoreAdj()
            }
        }

        if (settings.containsKey("rootWhitelist")) {
            val enabled = settings["rootWhitelist"] as? Boolean ?: false
            editor.putBoolean(KEY_ROOT_WHITELIST, enabled)
            if (enabled && RootShellExecutor.canUseRoot()) {
                RootShellExecutor.injectWhitelists(context.packageName)
            }
        }

        if (settings.containsKey("rootWatchdog")) {
            val enabled = settings["rootWatchdog"] as? Boolean ?: false
            editor.putBoolean(KEY_ROOT_WATCHDOG, enabled)
            if (enabled && RootShellExecutor.canUseRoot()) {
                RootShellExecutor.startWatchdog(context, context.packageName)
            } else {
                RootShellExecutor.stopWatchdog(context.packageName)
            }
        }

        if (settings.containsKey("accessibility")) {
            val enabled = settings["accessibility"] as? Boolean ?: false
            editor.putBoolean(KEY_ACCESSIBILITY, enabled)
            if (enabled) {
                startPeriodicAccessibilityGuard(context)
            }
        }

        editor.apply()
        getStatus(context)
    }

    /**
     * 检查是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }
        return true
    }

    /**
     * 请求忽略电池优化弹窗
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Request ignore battery optimizations failed: ${e.message}")
            }
        }
    }

    /**
     * 跳转至系统无障碍设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Open accessibility settings failed: ${e.message}")
        }
    }

    /**
     * 通过 Root 权限免手动一键激活无障碍服务
     */
    suspend fun enableAccessibilityViaRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!RootShellExecutor.canUseRoot()) return@withContext false
        val serviceClass = TraccarAccessibilityService::class.java.canonicalName ?: return@withContext false
        val ok = RootShellExecutor.enableAccessibilityServiceViaRoot(context.packageName, serviceClass)
        if (ok) {
            getPrefs(context).edit().putBoolean(KEY_ACCESSIBILITY, true).apply()
            startPeriodicAccessibilityGuard(context)
        }
        ok
    }
}
