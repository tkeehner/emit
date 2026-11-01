package com.example.emit.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.emit.data.SmsRepository

/**
 * Notification quick reply. Sends ONLY the text the user typed into the notification, to that notification's thread.
 * An @bot reply goes through BotCommands.onComposerSend exactly like the composer (Prime answers as an approval draft).
 */
class QuickReplyReceiver : BroadcastReceiver() {
    companion object {
        const val KEY = "quick_reply_text"; const val EXTRA_THREAD = "thread"
        fun action(c: Context, threadId: Long): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(c, threadId.toInt(), Intent(c, QuickReplyReceiver::class.java).putExtra(EXTRA_THREAD, threadId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)  // MUTABLE is required for RemoteInput
            return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "reply", pi)
                .addRemoteInput(RemoteInput.Builder(KEY).setLabel("> reply_").build()).setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
        val tid = intent.getLongExtra(EXTRA_THREAD, -1)
        if (text.isBlank() || tid <= 0) return
        val pr = goAsync()
        Thread {
            try {
                val conv = SmsRepository(context).conversation(tid)
                val res = when {
                    conv == null -> SmsSender.Result.Refused("thread not found")
                    conv.isGroup -> SmsSender.sendGroupToThread(context, tid, conv.addresses, text, emptyList())
                    else -> SmsSender.sendToThread(context, tid, conv.singleAddress ?: "", text)
                }
                if (res is SmsSender.Result.Sent) runCatching { com.example.emit.bot.BotCommands.onComposerSend(context, tid, text) }
                Notifier.showReplied(context, tid, if (res is SmsSender.Result.Refused) "reply not sent: ${res.reason}" else "> you: $text")
                IncomingBus.ping()
            } finally { pr.finish() }
        }.start()
    }
}
