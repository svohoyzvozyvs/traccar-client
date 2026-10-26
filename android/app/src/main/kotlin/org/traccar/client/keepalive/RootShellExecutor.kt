package org.traccar.client.keepalive

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Root Shell 执行器，借鉴自 xinyi-relay 的 Root 执行核心。
 * 提供 su 检测、命令执行、OOM 分值锁定、系统 Doze/待机白名单注入以及独立的后台看门狗守护。
 */
object RootShellExecutor {

    private const val TAG = "TraccarRootShell"
    private const val DEFAULT_TIMEOUT_SEC = 10L

    data class ShellResult(
        val exitCode: Int,
        val output: String,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * 校验当前设备是否具备 Root (su) 提权权限
     */
    fun canUseRoot(): Boolean {
        val result = run("id -u", timeoutSec = 5)
        val usable = result.success && result.output.trim() == "0"
        Log.d(TAG, "canUseRoot check: $usable (exitCode=${result.exitCode}, out='${result.output.trim()}')")
        return usable
    }

    /**
     * 执行指定 Shell 提权命令
     */
    fun run(command: String, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                Log.w(TAG, "Command timed out: $command")
                return ShellResult(exitCode = -2, output = "Timeout")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            ShellResult(exitCode = process.exitValue(), output = output)
        } catch (e: IOException) {
            Log.w(TAG, "Root execution failed for: $command, err: ${e.message}")
            ShellResult(exitCode = -1, output = e.message ?: "IOException")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Root execution interrupted for: $command")
            ShellResult(exitCode = -1, output = "Interrupted")
        }
    }

    /**
     * 将本进程的 Linux OOM Score 锁定为 -1000 (免死金牌)
     */
    fun lockOomScoreAdj(targetPid: Int = Process.myPid()): Boolean {
        val cmd = "echo -1000 > /proc/$targetPid/oom_score_adj 2>/dev/null; cat /proc/$targetPid/oom_score_adj"
        val result = run(cmd)
        val currentScore = result.output.trim()
        val success = result.success && currentScore == "-1000"
        Log.i(TAG, "lockOomScoreAdj pid=$targetPid, score=$currentScore, success=$success")
        return success
    }

    /**
     * 获取当前进程的 OOM Score Adj 值
     */
    fun getOomScoreAdj(targetPid: Int = Process.myPid()): Int? {
        return try {
            val file = File("/proc/$targetPid/oom_score_adj")
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull()
            } else {
                val res = run("cat /proc/$targetPid/oom_score_adj", timeoutSec = 2)
                res.output.trim().toIntOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 向系统级注入 Doze、AppOps、Standby 白名单
     */
    fun injectWhitelists(packageName: String): Boolean {
        val script = """
            dumpsys deviceidle whitelist +$packageName
            cmd appops set $packageName RUN_IN_BACKGROUND allow
            cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow
            am set-standby-bucket $packageName active
            am set-inactive $packageName false
        """.trimIndent()
        val result = run(script, timeoutSec = 15)
        Log.i(TAG, "injectWhitelists result: success=${result.success}, out=${result.output}")
        return result.success
    }

    /**
     * 启动独立的 Root 看门狗守护脚本 (Detached nohup 运行)
     */
    fun startWatchdog(context: Context, packageName: String, intervalSeconds: Int = 30): Boolean {
        stopWatchdog(packageName)

        val watchdogFile = File(context.filesDir, "traccar_watchdog.sh")
        val serviceComponent = "$packageName/org.traccar.client.keepalive.TraccarAccessibilityService"
        val scriptContent = """
            #!/system/bin/sh
            while true; do
                # 1. 自动巡检并保证无障碍服务开启（防系统开机掉线）
                SERVICES=$(settings get secure enabled_accessibility_services)
                if [ -z "${'$'}SERVICES" ] || [ "${'$'}SERVICES" = "null" ]; then
                    settings put secure enabled_accessibility_services "$serviceComponent"
                    settings put secure accessibility_enabled 1
                elif ! echo "${'$'}SERVICES" | grep -q "$serviceComponent"; then
                    settings put secure enabled_accessibility_services "${'$'}SERVICES:$serviceComponent"
                    settings put secure accessibility_enabled 1
                fi

                # 2. 自动巡检进程存活状态，若异常退出则自动拉起
                if ! pidof $packageName > /dev/null; then
                    am start-foreground-service -n $packageName/.keepalive.KeepAliveWakeupService > /dev/null 2>&1
                    am start -n $packageName/org.traccar.client.MainActivity --ez autostart true > /dev/null 2>&1
                fi
                sleep $intervalSeconds
            done
        """.trimIndent()

        try {
            watchdogFile.writeText(scriptContent)
            watchdogFile.setExecutable(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write watchdog script: ${e.message}")
            return false
        }

        val launchCmd = "nohup sh ${watchdogFile.absolutePath} > /dev/null 2>&1 &"
        val result = run(launchCmd)
        Log.i(TAG, "startWatchdog launched: ${result.success}")
        return isWatchdogRunning(packageName)
    }

    /**
     * 检查看门狗进程是否在运行
     */
    fun isWatchdogRunning(packageName: String): Boolean {
        val result = run("ps -ef | grep traccar_watchdog.sh | grep -v grep")
        return result.success && result.output.isNotBlank()
    }

    /**
     * 停止看门狗守护
     */
    fun stopWatchdog(packageName: String): Boolean {
        val result = run("pkill -f traccar_watchdog.sh")
        Log.i(TAG, "stopWatchdog executed: ${result.success}")
        return true
    }

    /**
     * 通过 Root 权限静默激活无障碍服务 (免手动跳转)
     */
    fun enableAccessibilityServiceViaRoot(packageName: String, serviceClass: String): Boolean {
        val serviceComponent = "$packageName/$serviceClass"
        val cmd = """
            CURRENT_SERVICES=$(settings get secure enabled_accessibility_services)
            if [ -z "${'$'}CURRENT_SERVICES" ] || [ "${'$'}CURRENT_SERVICES" = "null" ]; then
                NEW_SERVICES="$serviceComponent"
            elif echo "${'$'}CURRENT_SERVICES" | grep -q "$serviceComponent"; then
                NEW_SERVICES="${'$'}CURRENT_SERVICES"
            else
                NEW_SERVICES="${'$'}CURRENT_SERVICES:$serviceComponent"
            fi
            settings put secure enabled_accessibility_services "${'$'}NEW_SERVICES"
            settings put secure accessibility_enabled 1
        """.trimIndent()
        val result = run(cmd)
        Log.i(TAG, "enableAccessibilityViaRoot result=${result.success}, out=${result.output}")
        return result.success
    }

    /**
     * 通过 Root 检索 LSPosed 系统数据库，检测本模块是否在框架中被勾选启用
     */
    fun isLSPosedModuleEnabledInSystem(packageName: String): Boolean {
        return try {
            val cmd = "strings /data/adb/lspd/config/modules_config.db* 2>/dev/null | grep -i $packageName"
            val res = run(cmd, timeoutSec = 3)
            res.success && res.output.contains(packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "isLSPosedModuleEnabledInSystem failed: ${t.message}")
            false
        }
    }
}
