package com.example.emit.sms

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import com.example.emit.data.SmsRepository

/** Delivered only to the default SMS app. Persists to inbox and notifies. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val address = msgs[0].displayOriginatingAddress ?: "unknown"
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        val subId = intent.getIntExtra("subscription", -1)
        val repo = SmsRepository(context)
        repo.insertInbox(address, body, msgs[0].timestampMillis, subId)
        Notifier.showIncoming(context, com.example.emit.data.DisplayName.person(repo.contactName(address), address), body, repo.threadIdFor(address))
        IncomingBus.ping()
    }
}

/** WAP push MMS notification (default app only): parse M-Notification.ind and download via the platform MMS service. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data") ?: return
        val n = com.example.emit.mms.Pdu.parseNotification(data)
        if (n?.contentLocation == null) { Log.w("MatrixSMS", "WAP push not an MMS notification (${data.size} bytes)"); return }
        val subId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        Log.i("MatrixSMS", "mms receive WAP push from=${n.from} tid=${n.transactionId} size=${n.size}")
        com.example.emit.mms.MmsTransport.download(context, n, subId)
    }
}

/** Moves an outbox row to SENT or FAILED based on the radio result. */
class SentStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val type = if (resultCode == Activity.RESULT_OK) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        SmsRepository(context).setType(uri, type)
        IncomingBus.ping()
    }
}

/** Carrier delivery report -> Telephony.Sms.STATUS (0 complete, 32 pending, 64 failed). Shown as [DLV] / [ERR]. */
class DeliveryStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val pdu = intent.getByteArrayExtra("pdu") ?: return
        val msg = android.telephony.SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")) ?: return
        val st = msg.status
        val status = when { st == 0 -> Telephony.Sms.STATUS_COMPLETE; st >= 64 -> Telephony.Sms.STATUS_FAILED; else -> Telephony.Sms.STATUS_PENDING }
        SmsRepository(context).setStatus(uri, status)
        if (status == Telephony.Sms.STATUS_COMPLETE) uri.lastPathSegment?.toLongOrNull()?.let { com.example.emit.data.DeliveryTimes(context).put(it, System.currentTimeMillis()) }
        IncomingBus.ping()
    }
}

/**
 * Required for default-SMS eligibility ("respond via message" when declining a call).
 * Phase 1: intentionally does NOT send anything; logs and stops.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("MatrixSMS", "Respond-via-message requested; not implemented in phase 1")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
