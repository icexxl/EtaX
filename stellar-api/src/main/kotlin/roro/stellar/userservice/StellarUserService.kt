package roro.stellar.userservice

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.stellar.server.IUserServiceCallback
import roro.stellar.Stellar
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "StellarUserService"

object StellarUserService {

    interface ServiceCallback {
        fun onServiceConnected(service: IBinder)
        fun onServiceDisconnected()
        fun onServiceStartFailed(errorCode: Int, message: String) {}
    }

    private val activeConnections = ConcurrentHashMap<String, ConnectionRecord>()

    private class ConnectionRecord(
        val args: UserServiceArgs,
        val callback: ServiceCallback,
        val handler: Handler?,
        var token: String? = null,
        var binder: IBinder? = null,
        var verificationToken: String = stableVerificationToken(args)
    )

    @JvmStatic
    @JvmOverloads
    fun bindUserService(
        args: UserServiceArgs,
        callback: ServiceCallback,
        handler: Handler? = Handler(Looper.getMainLooper())
    ) {
        Log.i(TAG, "class=${args.className}, suffix=${args.processNameSuffix}")

        val service = Stellar.getService()
        if (service == null) {
            Log.e(TAG, "")
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "")
            }
            return
        }
        Log.d(TAG, "")

        val packageName = Stellar.getPackageName()
        if (packageName == null) {
            Log.e(TAG, "")
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "")
            }
            return
        }
        Log.d(TAG, "packageName=$packageName")

        val key = getConnectionKey(args)

        val existing = activeConnections[key]
        if (existing?.binder?.pingBinder() == true) {
            dispatchCallback(handler) {
                callback.onServiceConnected(existing.binder!!)
            }
            return
        }

        val record = ConnectionRecord(args, callback, handler)
        activeConnections[key] = record

        val aidlCallback = createAidlCallback(record, key, handler)

        try {
            Log.d(TAG, "")
            val bundle = args.toBundle(packageName)
            bundle.putString(UserServiceArgs.ARG_VERIFICATION_TOKEN, record.verificationToken)
            val token = service.startUserService(bundle, aidlCallback)
            Log.i(TAG, "token=$token")
            record.token = token
        } catch (e: RemoteException) {
            Log.e(TAG, "", e)
            activeConnections.remove(key)
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, ": ${e.message}")
            }
        }
    }

    @JvmStatic
    fun unbindUserService(args: UserServiceArgs) {
        val key = getConnectionKey(args)
        Log.i(TAG, "key=$key")

        val record = activeConnections[key]
        if (record == null) {
            Log.w(TAG, "key=$key")
            return
        }

        val token = record.token
        if (token == null) {
            Log.w(TAG, "")
            activeConnections.remove(key)
            dispatchCallback(record.handler) {
                record.callback.onServiceDisconnected()
            }
            return
        }

        val service = Stellar.getService()
        if (service == null) {
            Log.w(TAG, "")
            activeConnections.remove(key)
            dispatchCallback(record.handler) {
                record.callback.onServiceDisconnected()
            }
            return
        }

        activeConnections.remove(key)

        try {
            Log.i(TAG, "token=$token")
            service.stopUserService(token)
        } catch (e: RemoteException) {
            Log.e(TAG, "", e)
        }

        dispatchCallback(record.handler) {
            record.callback.onServiceDisconnected()
        }
    }

    @JvmStatic
    fun peekUserService(args: UserServiceArgs): IBinder? {
        val key = getConnectionKey(args)
        val record = activeConnections[key] ?: return null
        return if (record.binder?.pingBinder() == true) record.binder else null
    }

    @JvmStatic
    fun getUserServiceCount(): Int {
        val service = Stellar.getService() ?: return 0
        return try {
            service.userServiceCount
        } catch (e: RemoteException) {
            0
        }
    }

    /**
     * 稳定的验证令牌。
     *
     * 上游按每次绑定生成随机 UUID，但 Stellar 服务端会【复用】进程内已存在的
     * 同名服务，并把创建时的令牌回传。App 进程重启后随机令牌必然与之不符，
     * 表现为 "验证令牌不匹配，拒绝连接"，副屏就永远接不回来。
     * 这里按类名与进程后缀派生固定值，使重连可以命中同一个服务实例。
     */
    private fun stableVerificationToken(args: UserServiceArgs): String =
        "user-service:" + args.className + "@" + args.processNameSuffix

    private fun getConnectionKey(args: UserServiceArgs): String =
        "${args.className}:${args.processNameSuffix}"

    private fun dispatchCallback(handler: Handler?, action: () -> Unit) {
        handler?.post(action) ?: action()
    }

    private fun createAidlCallback(
        record: ConnectionRecord,
        key: String,
        handler: Handler?
    ): IUserServiceCallback.Stub {
        return object : IUserServiceCallback.Stub() {
            override fun onServiceConnected(service: IBinder, returnedToken: String?) {
                Log.i(TAG, "服务已连接, binder=$service")
                if (returnedToken != record.verificationToken) {
                    Log.e(TAG, "验证令牌不匹配，拒绝连接")
                    activeConnections.remove(key)
                    dispatchCallback(handler) {
                        record.callback.onServiceStartFailed(-2, "验证令牌不匹配")
                    }
                    return
                }
                record.binder = service
                dispatchCallback(handler) {
                    record.callback.onServiceConnected(service)
                }
            }

            override fun onServiceDisconnected() {
                Log.i(TAG, "服务断开连接")
                record.binder = null
                // 令牌保持稳定：服务端会复用进程内已有的服务实例，
                // 换新令牌会让下一次绑定必然被判为不匹配。
                activeConnections.remove(key)
                dispatchCallback(handler) {
                    record.callback.onServiceDisconnected()
                }
            }

            override fun onServiceStartFailed(errorCode: Int, message: String) {
                Log.e(TAG, "服务启动失败, code=$errorCode, msg=$message")
                activeConnections.remove(key)
                dispatchCallback(handler) {
                    record.callback.onServiceStartFailed(errorCode, message)
                }
            }
        }
    }
}
