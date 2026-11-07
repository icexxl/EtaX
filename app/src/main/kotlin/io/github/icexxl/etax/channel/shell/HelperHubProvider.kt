package io.github.icexxl.etax.channel.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.icexxl.etax.shell.IEtaShell

/**
 * EtaX 进程里接收助手交接的落点（派生自 Mika 的 HelperHub/HandoffProvider，Apache-2.0，已裁剪）。
 *
 * 助手以 shell 身份经 `getContentProviderExternal` 找到这里，把 IEtaShell 的 Binder 交过来；
 * 这里校验调用方 uid/pid、APK 路径与一次性口令，接受后回传一个令牌 Binder 供助手监听 EtaX 的死亡。
 */
class HelperHubProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ATTACH) return null
        val ctx = context ?: return rejection("no context")
        val hub = EtaShellConnection.hub(ctx)
        // Provider 的调用方身份只能从 Binder 取
        return hub.offer(extras, android.os.Binder.getCallingUid(), android.os.Binder.getCallingPid(), ctx.packageName)
    }

    private fun rejection(reason: String): Bundle = Bundle().apply {
        putString(HelperHandoff.KEY_RESULT, "rejected")
        putString(HelperHandoff.KEY_REASON, reason)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "EtaHelperProvider"
        const val METHOD_ATTACH = "attach"

        /** 交接是否可接受；返回 null 表示通过。 */
        fun validate(
            context: Context,
            extras: Bundle?,
            callingUid: Int,
            callingPid: Int,
        ): String? {
            val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
            HelperHandoff.check(
                callingUid = callingUid,
                callingPid = callingPid,
                reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
                reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
                helperApk = extras?.getString(HelperHandoff.KEY_APK),
                appApk = context.applicationInfo.sourceDir,
                hasBinder = binder != null,
            )?.let { return it.name }
            val shell = IEtaShell.Stub.asInterface(binder!!)
            val ids = runCatching { shell.identity() }.getOrNull()
                ?: return HelperHandoff.Reject.BadBinder.name
            if (ids.size != 2 || ids[0] != callingUid || ids[1] != callingPid) {
                return HelperHandoff.Reject.IdentityMismatch.name
            }
            return null
        }

        fun logReject(reason: String) = Log.w(TAG, "handoff rejected: $reason")
    }
}
