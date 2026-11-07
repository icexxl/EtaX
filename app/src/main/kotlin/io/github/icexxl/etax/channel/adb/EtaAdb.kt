package io.github.icexxl.etax.channel.adb

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

/**
 * Android 侧的 adb 身份与 TLS（派生自 Mika 的 AndroidAdb，Apache-2.0，已裁剪）。
 *
 * 密钥用 Android Keystore 里的 AES 密钥包装后放在 App 私有目录；
 * 导出密钥优先走平台的 `SSLSockets.exportKeyingMaterial`，不可用时退回 Conscrypt。
 */
internal object EtaAdb {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "etax_adb_key_wrap"
    private const val TAG = "EtaAdbKey"

    val exporter = KeyingMaterialExporter { socket, label, length ->
        exportKeyingMaterial(socket, label, length)
    }

    @Volatile
    private var source: AdbIdentitySource? = null

    fun identities(context: Context): AdbIdentitySource = source ?: synchronized(this) {
        source ?: AdbIdentitySource(keyStore(context), { "etax@${Build.MODEL}" }) { message, error ->
            Log.w(TAG, message, error)
        }.also { source = it }
    }

    fun identity(context: Context): AdbIdentity = identities(context).forConnect()

    fun pairingIdentity(context: Context): AdbIdentity = identities(context).forPairing()

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    fun forget(context: Context) = identities(context).forget()

    /** TLS 1.3 导出密钥：平台 `SSLSockets.exportKeyingMaterial`（API 29+，EtaX 最低 33）。 */
    private fun exportKeyingMaterial(
        socket: javax.net.ssl.SSLSocket,
        label: String,
        length: Int,
    ): ByteArray = android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length)
        ?: throw SSLException("keying material export failed")

    private fun keyStore(context: Context): AdbKeyStore {
        val file = File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")
        val wrapped = WrappedKeyFile(file) { wrappingKey() }
        return object : AdbKeyStore by wrapped {
            override fun clear() {
                wrapped.clear()
                runCatching {
                    KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS)
                }
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
