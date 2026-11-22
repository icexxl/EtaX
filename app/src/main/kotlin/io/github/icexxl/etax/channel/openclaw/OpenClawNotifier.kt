package io.github.icexxl.etax.channel.openclaw

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.icexxl.etax.R
import io.github.icexxl.etax.ui.MainActivity

/**
 * OpenClaw 消息通知。
 *
 * 不使用前台服务，只发普通通知；同一会话 30 秒内的多条消息合并（只响一次）。
 */
internal class OpenClawNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val lastAlertAt = mutableMapOf<String, Long>()
    private val pendingText = mutableMapOf<String, String>()

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenClaw 消息", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    /**
     * @param sessionKey 会话 key，用于通知分组与深链
     * @param title      展示标题（agent 名或会话 label）
     * @param text       消息正文
     */
    fun notify(sessionKey: String, title: String, text: String) {
        if (!canNotify()) {
            android.util.Log.w("OpenClawNotif", "skip: no permission")
            return
        }
        ensureChannel()

        val now = System.currentTimeMillis()
        val last = lastAlertAt[sessionKey] ?: 0L
        val merge = now - last < MERGE_WINDOW_MS
        val merged = if (merge) {
            val previous = pendingText[sessionKey]
            if (previous.isNullOrBlank() || previous == text) text else "$previous\n$text"
        } else {
            text
        }
        pendingText[sessionKey] = merged
        lastAlertAt[sessionKey] = now

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPENCLAW_SESSION, sessionKey)
            putExtra(MainActivity.EXTRA_OPENCLAW_AGENT, agentIdOf(sessionKey))
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_openclaw)
            .setContentTitle(title.ifBlank { "OpenClaw" })
            .setContentText(merged.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(merged))
            .setAutoCancel(true)
            .setOnlyAlertOnce(merge)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        android.util.Log.i("OpenClawNotif", "post: $title | ${merged.take(40)}")
        manager.notify(sessionKey.hashCode(), notification)
    }

    fun clear(sessionKey: String) {
        pendingText.remove(sessionKey)
        lastAlertAt.remove(sessionKey)
        manager.cancel(sessionKey.hashCode())
    }

    private fun agentIdOf(sessionKey: String): String =
        Regex("^agent:([^:]+):").find(sessionKey)?.groupValues?.getOrNull(1).orEmpty()

    private companion object {
        const val CHANNEL_ID = "openclaw_messages"
        const val MERGE_WINDOW_MS = 30_000L
    }
}
