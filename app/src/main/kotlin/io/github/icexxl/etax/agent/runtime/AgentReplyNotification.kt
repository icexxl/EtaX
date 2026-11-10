package io.github.icexxl.etax.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.icexxl.etax.R
import io.github.icexxl.etax.ui.MainActivity

/** A short completion handoff, independent of the execution foreground-service lease. */
internal object AgentReplyNotification {
    private const val CHANNEL = "eta_execution"
    private const val ID = 1108
    /** Google blue, matching the app accent. */
    private const val ACCENT = 0xFF3084FF.toInt()

    fun shouldNotify(inForeground: Boolean, ok: Boolean, operation: String, source: String?, content: String): Boolean =
        !inForeground && ok && operation == AgentRuntimeWire.OP_CHAT &&
            source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE && content.isNotBlank()

    fun show(context: Context, runId: String, conversationId: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                if (!conversationId.isNullOrBlank()) {
                    action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                    putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                }
            }
        val open = PendingIntent.getActivity(context, runId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reply_ready_title))
            .setContentText(context.getString(R.string.reply_ready_detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // A plain notification still appears if the system declines the promotion.
            .setTimeoutAfter(60_000L)
        // Promoted ongoing needs Android 16 QPR1 (API 36.1); keep it off until then.
        // SDK_INT_FULL only exists from API 36; the first check keeps older devices safe.
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= 360100) {
            builder.setShortCriticalText(context.getString(R.string.reply_ready_chip))
                .setRequestPromotedOngoing(true)
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(
                            listOf(Notification.ProgressStyle.Segment(100).setColor(ACCENT)),
                        )
                        .setStyledByProgress(true)
                        .setProgress(100),
                )
        }
        // Promotion is decided by the system; the ordinary notification remains the fallback.
        manager.notify(ID, builder.build())
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
