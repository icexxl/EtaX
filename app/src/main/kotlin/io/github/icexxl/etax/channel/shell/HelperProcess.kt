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

    @JvmStatic
    fun main(args: Array<String>) {
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
        val service = EtaShellService(allowedUid = appUid)
        // 上次静音中被杀、没来得及恢复的，先把声音还回去
        runCatching { service.restoreMuted() }

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
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_BINDER) ?: return Outcome.Rejected
                result.getString(HelperHandoff.KEY_NONCE)?.let { nonce = it }
                appToken = appBinder
                runCatching {
                    appBinder.linkToDeath({ onAppDied(appBinder) }, 0)
                }.onFailure { onAppDied(appBinder) }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
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

        /** 之前起的或残留的助手实例都该走了：只认进程名完全相同的同 uid 进程。 */
        fun killOtherInstances() {
            runCatching {
                val myPid = Process.myPid()
                ProcessBuilder("sh", "-c", "ps -A -o PID,ARGS | grep -F '$COMPONENT' | grep -v ' $myPid ' | awk '{print \$1}'")
                    .redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
                    .mapNotNull { it.trim().toIntOrNull() }
                    .forEach { pid -> runCatching { Process.killProcess(pid) } }
            }.onFailure { Log.w(TAG, "killOtherInstances failed", it) }
        }
    }

    private const val METHOD_ATTACH = "attach"
    private const val PER_USER_RANGE = 100000
    private const val COMPONENT = "io.github.icexxl.etax.channel.shell.HelperProcess"
}
