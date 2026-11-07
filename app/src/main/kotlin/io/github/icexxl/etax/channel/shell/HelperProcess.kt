package io.github.icexxl.etax.channel.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File

/**
 * 助手进程入口（派生自 Mika 的 HelperProcess / HelperHandoff，Apache-2.0，已裁剪）。
 *
 * EtaX 经本机无线调试以 shell 身份启动它：
 * `CLASSPATH=<base.apk> app_process /system/bin io.github.icexxl.etax.channel.shell.HelperProcess
 *  <包名> <EtaX uid> <证书指纹> <APK 路径>`
 * 一次性口令从标准输入读一行。特权逻辑全在 [EtaShellService]，这里只负责把它的 Binder 交给 EtaX；
 * EtaX 进程结束后不退出，撤掉自己留下的改动，等它重新交接。
 */
object HelperProcess {
    private const val TAG = "EtaHelper"

    /** 诊断输出：app_process 没有 logcat 之外的可见渠道，直接写 stdout 供 App 侧读取。 */
    private fun say(message: String) {
        println("[$TAG] $message")
        System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        say("start args=${args.size}")
        if (args.size < 4) {
            Log.e(TAG, "usage: HelperProcess <package> <uid> <cert-sha256> <apk> (nonce on stdin)")
            System.exit(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull()
        if (appUid == null) {
            Log.e(TAG, "bad uid argument")
            System.exit(2)
            return
        }
        val certPin = args[2]
        val apkPath = args[3]
        val nonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        runCatching { System.`in`.close() }
        if (nonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            System.exit(2)
            return
        }

        Looper.prepareMainLooper()
        say("uid=${Process.myUid()} apk=$apkPath")
        val service = runCatching { EtaShellService(allowedUid = appUid) }
            .onFailure { say("service init failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse {
                // 初始化就失败时直接退出，让 App 侧看到原因
                System.out.flush()
                System.exit(5)
                return
            }
        // 上次静音中被杀、没来得及恢复的，先把声音还回去
        runCatching { service.restoreMuted() }

        say("service ready, handoff to $packageName")
        val helper = Helper(packageName, appUid, certPin, apkPath, nonce, service)
        when (helper.start()) {
            Outcome.Accepted -> {
                helper.killOtherInstances()
                Looper.loop()
            }
            Outcome.Rejected -> {
                runCatching { service.releaseAll() }
                System.exit(0)
            }
            Outcome.Failed -> System.exit(1)
        }
    }

    private enum class Outcome { Accepted, Rejected, Failed }

    private class Helper(
        private val packageName: String,
        private val appUid: Int,
        @Suppress("unused") private val certPin: String,
        private val apkPath: String,
        private var nonce: String,
        private val service: EtaShellService,
    ) {
        private val authority = HelperHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        private var appToken: IBinder? = null

        fun start(): Outcome = deliver(nonce)

        /**
         * 经 ActivityManager 拿到 EtaX 的 provider（同 `content call` 的做法），
         * 把 Binder 与自报的 uid/pid/APK 路径交过去，并接收回传的死亡令牌。
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?): Outcome {
            if (n == null) return Outcome.Rejected
            val am = runCatching {
                Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            }.getOrNull() ?: return Outcome.Failed
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                    IBinder::class.java, String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                say("getContentProviderExternal failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed

            try {
                // 同名 authority 只可能属于一个包，仍核对一次，不把 Binder 交给别的 App
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, n)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putString(HelperHandoff.KEY_APK, apkPath)
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 &&
                        it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid())
                    .setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(
                    provider, caller, authority, METHOD_ATTACH, null, extras,
                ) as Bundle?
                if (result?.getString(HelperHandoff.KEY_RESULT) != HelperHandoff.RESULT_OK) {
                    say("handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_BINDER) ?: return Outcome.Rejected
                result.getString(HelperHandoff.KEY_NONCE)?.let { nonce = it }
                appToken = appBinder
                runCatching {
                    appBinder.linkToDeath({ onAppDied(appBinder) }, 0)
                }.onFailure { onAppDied(appBinder) }
                say("handed off to $packageName")
                    Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                say("handoff failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser", String::class.java,
                        IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        /** EtaX 进程没了：它建的屏、静音跟着撤掉（和服务结束时一样），助手本身留着。 */
        private fun onAppDied(binder: IBinder) {
            if (appToken !== binder) return
            appToken = null
            runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "cleanup failed", it) }
            Log.i(TAG, "$packageName died; waiting for it to come back")
        }

        /**
         * 清掉之前残留的助手实例。
         *
         * 只认真正的 `app_process` 助手进程：
         * - 排除自己（PID 数字比较）和自己的父进程；
         * - 排除 adbd 那层 `sh -c "… app_process …"` 包装进程。
         *
         * 最后这条曾经踩坑：包装进程的命令行里同样含助手类名，被一起杀掉后，
         * 助手作为它的子进程收到 SIGHUP，交接刚成功就消失（日志上看起来“连上又断开”，且没有崩溃记录）。
         */
        fun killOtherInstances() {
            runCatching {
                val myPid = Process.myPid()
                val parentPid = runCatching {
                    File("/proc/self/stat").readText().split(' ')
                        .getOrNull(3)?.toIntOrNull()
                }.getOrNull()
                val output = ProcessBuilder(
                    "sh", "-c", "ps -A -o PID,ARGS | grep -F '$COMPONENT' || true",
                ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                output.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    val pid = trimmed.substringBefore(' ').toIntOrNull() ?: return@forEach
                    if (pid <= 0 || pid == myPid || pid == parentPid) return@forEach
                    // app_process 启动后会把进程名改成类名，所以按类名匹配；
                    // 但必须排除 adbd 的 `sh -c` 包装层（它的命令行里同样含类名）和 grep 自身。
                    if (!trimmed.contains(COMPONENT)) return@forEach
                    if (trimmed.contains(" sh -c ") || trimmed.contains("grep ")) return@forEach
                    runCatching { Process.killProcess(pid) }
                }
            }.onFailure { Log.w(TAG, "killOtherInstances failed", it) }
        }
    }

    private const val METHOD_ATTACH = "attach"
    private const val PER_USER_RANGE = 100000
    private const val COMPONENT = "io.github.icexxl.etax.channel.shell.HelperProcess"
}
