package com.example.emit.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.emit.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object Notifier {
    private const val CHANNEL = "incoming_sms"
    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Incoming messages", NotificationManager.IMPORTANCE_HIGH))
    }
    fun cancelThread(context: Context, threadId: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(threadId.toInt()) }
    }

    fun showIncoming(context: Context, title: String, body: String, threadId: Long) {
        if (threadId > 0 && com.example.emit.data.ChatPrefs.isMuted(context, threadId)) return  // [m] muted: stored, not announced
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, threadId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_THREAD, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("> $title").setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(0xFF00FF41.toInt()).setAutoCancel(true).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .apply { if (threadId > 0) addAction(QuickReplyReceiver.action(context, threadId)) }.build()
        runCatching { NotificationManagerCompat.from(context).notify(threadId.toInt(), n) }
    }
}

/** Replaces a thread's notification after a quick reply / scheduled-send result (silent). */
fun Notifier.showReplied(context: Context, threadId: Long, text: String) {
    ensureChannel(context)
    val n = NotificationCompat.Builder(context, "incoming_sms").setSmallIcon(android.R.drawable.sym_action_chat)
        .setContentTitle("> Emit").setContentText(text).setSilent(true).setAutoCancel(true).setTimeoutAfter(15_000).build()
    runCatching { NotificationManagerCompat.from(context).notify(threadId.toInt(), n) }
}

/** In-process signal so the open UI refreshes and flashes on new/sent messages. */
object IncomingBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    fun ping() { _events.tryEmit(Unit) }
}
