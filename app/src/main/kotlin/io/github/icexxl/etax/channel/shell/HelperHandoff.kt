package io.github.icexxl.etax.channel.shell

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 助手交接协议（派生自 Mika 的 HelperHandoff，Apache-2.0，已裁剪）。
 *
 * 助手以 shell 身份启动，凭一次性口令把 Binder 交回 EtaX。口令经标准输入或握手 Bundle 传递，
 * 只用于校验本次交接；校验同时要求调用方 uid/pid 与助手自报一致、跑的确实是当前这份 APK。
 */
internal object HelperHandoff {
    const val AUTHORITY_SUFFIX = ".helper"
    const val KEY_BINDER = "binder"
    const val KEY_NONCE = "nonce"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"
    const val KEY_PACKAGE = "package"
    const val KEY_APP_UID = "app_uid"
    const val KEY_CERT = "cert"
    const val KEY_RESULT = "result"
    const val KEY_REASON = "reason"

    const val RESULT_OK = "ok"

    enum class Reject { NotShell, IdentityMismatch, WrongApk, BadNonce, BadBinder }

    fun authority(packageName: String) = packageName + AUTHORITY_SUFFIX

    /** 由 APK 路径、包名、目标 uid、证书指纹拼出 app_process 启动命令。 */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "CLASSPATH=${quote(apkPath)} app_process /system/bin " +
            "${HelperProcess::class.java.name} ${quote(packageName)} $appUid ${quote(certPin)}"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /** 交接前的静态校验；返回 null 表示通过。 */
    fun check(
        callingUid: Int,
        callingPid: Int,
        reportedUid: Int,
        reportedPid: Int,
        helperApk: String?,
        appApk: String?,
        hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdentityMismatch
        helperApk == null || appApk == null || helperApk != appApk -> Reject.WrongApk
        !hasBinder -> Reject.BadBinder
        else -> null
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 生成一次性口令。 */
    fun newNonce(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 常量时间比较，避免按耗时猜口令。 */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    const val SHELL_UID = 2000
}
