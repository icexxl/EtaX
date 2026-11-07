package io.github.icexxl.etax.channel.shell

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.icexxl.etax.shell.IEtaShell
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EtaX 侧与 shell 助手之间的连接中枢（派生自 Mika 的 HelperHub，Apache-2.0，已裁剪）。
 *
 * 职责：
 * 1. 生成一次性口令，经本机无线调试以 shell 身份拉起指南针助手进程；
 * 2. 接收助手交来的 [IEtaShell]，校验通过后持有；
 * 3. 助手失效（进程死、断线）时清空句柄，供上层重连。
 */
internal object EtaShellConnection {

    private const val TAG = "EtaShellConn"

    @Volatile
    private var hub: HelperHub? = null

    fun hub(context: Context): HelperHub {
        val existing = hub
        if (existing != null) return existing
        return synchronized(this) {
            hub ?: HelperHub(context.applicationContext).also { hub = it }
        }
    }

    /** 当前可用的助手接口；未连接时为 null。 */
    fun shell(context: Context): IEtaShell? = hub(context).shell.value

    fun isConnected(context: Context): Boolean = shell(context) != null

    /** 截断连接（例如用户解除配对、虚拟屏释放后）。 */
    fun forget(context: Context) {
        hub(context).forget()
    }
}

/**
 * 助手 Binder 的落点（派生自 Mika 的 HelperHub）。
 * 只接受 uid 2000、自报 uid/pid 与调用方一致、跑的是当前这份 APK、口令正确的交接。
 */
internal class HelperHub(private val appContext: Context) {

    private val mutableShell = MutableStateFlow<IEtaShell?>(null)
    val shell: StateFlow<IEtaShell?> = mutableShell.asStateFlow()

    /** 最近一次交接被拒的原因，仅用于诊断，不含口令与路径。 */
    @Volatile
    var lastRejection: String? = null
        private set

    private val nonces = HandoffNonces()

    /** 交给助手的令牌：EtaX 进程活着时它有效，助手据此监听死亡。 */
    private val appToken = Binder()

    fun issueNonce(): String = nonces.issue()

    /** 清理口令并放掉手里的接口；还活着的助手下次交接会被拒绝。 */
    fun forget() {
        nonces.clear()
        mutableShell.value = null
    }

    /** 助手来交接。[callingUid] / [callingPid] 由 provider 的 call() 给出。 */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int, packageName: String): Bundle {
        val reason = HelperHubProvider.validate(appContext, extras, callingUid, callingPid)
        if (reason != null) {
            lastRejection = reason
            HelperHubProvider.logReject(reason)
            return rejected(reason)
        }
        // 口令不对就不碰对方给的 Binder
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        if (!nonces.accept(nonce)) {
            lastRejection = HelperHandoff.Reject.BadNonce.name
            return rejected(HelperHandoff.Reject.BadNonce.name)
        }
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
            ?: return rejected(HelperHandoff.Reject.BadBinder.name)
        val shell = IEtaShell.Stub.asInterface(binder)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size != 2 || ids[0] != callingUid || ids[1] != callingPid) {
            lastRejection = HelperHandoff.Reject.IdentityMismatch.name
            return rejected(HelperHandoff.Reject.IdentityMismatch.name)
        }
        lastRejection = null
        mutableShell.value = shell
        Log.i(TAG, "helper connected: $packageName uid=$callingUid pid=$callingPid")
        return Bundle().apply {
            putString(HelperHandoff.KEY_RESULT, HelperHandoff.RESULT_OK)
            putBinder(HelperHandoff.KEY_BINDER, appToken)
            putString(HelperHandoff.KEY_NONCE, nonces.issue())
        }
    }

    private fun rejected(reason: String) = Bundle().apply {
        putString(HelperHandoff.KEY_RESULT, "rejected")
        putString(HelperHandoff.KEY_REASON, reason)
    }

    private companion object {
        const val TAG = "EtaHelperHub"
    }
}

/**
 * 一次性口令槽：issue 生成，accept 校验并换发下一枚（常量时间比较）。
 * 只保留最近一枚，重放旧口令无效。
 */
internal class HandoffNonces {

    private val current = AtomicReference<String?>(null)

    fun issue(): String = HelperHandoff.newNonce().also { current.set(it) }

    /** 校验并当次作废：通过后立即换发新口令，旧口令不能重放。 */
    fun accept(candidate: String?): Boolean {
        val expected = current.get() ?: return false
        if (!HelperHandoff.constantTimeEquals(expected, candidate)) return false
        current.set(HelperHandoff.newNonce())
        return true
    }

    fun clear() {
        current.set(null)
    }
}
