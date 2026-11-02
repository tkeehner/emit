package com.example.emit.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 0.1.51: Beacon's "Prime text <name> <message>" voice command. Guarded by SEND_FROM_BEACON (signature|knownSigner:
 * only Beacon's signing cert can hold it), sent as an ordered broadcast; the result goes back to Beacon as the result
 * code (RESULT_OK = handed to the radio through Emit's normal send path, so it lands in the thread like any send) or
 * an error string. No UI: on failure Beacon says so and opens Emit's compose with the body prefilled.
 */
class BeaconSendReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.example.emit.action.SEND_FROM_BEACON"
        const val EXTRA_NUMBER = "number"; const val EXTRA_BODY = "body"
        const val MAX_BODY = 1000
        private const val MIN_GAP_MS = 3000L
        @Volatile private var lastSend = 0L
        private val EMERGENCY = setOf("911", "112", "999", "000", "988", "110", "119", "933")

        /** Why a send is refused, or null if it may go. Pure (BeaconSendTest). */
        fun refusal(number: String?, body: String?, isDefaultSms: Boolean, nowMs: Long, lastMs: Long): String? {
            val digits = number.orEmpty().filter { it.isDigit() }
            return when {
                !isDefaultSms -> "Emit isn't the default SMS app"
                number.isNullOrBlank() || number.any { !(it.isDigit() || it in "+-() .") } -> "bad number"
                digits in EMERGENCY || digits.trimStart('1') in EMERGENCY -> "won't text emergency numbers"
                digits.length < 7 -> "won't text short codes"
                body.isNullOrBlank() -> "empty message"
                body.length > MAX_BODY -> "message too long"
                nowMs - lastMs < MIN_GAP_MS -> "too many sends in a row"
                else -> null
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val number = intent.getStringExtra(EXTRA_NUMBER); val body = intent.getStringExtra(EXTRA_BODY)?.trim()
        val isDefault = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        val now = System.currentTimeMillis()
        refusal(number, body, isDefault, now, lastSend)?.let { resultCode = 1; resultData = it; return }
        lastSend = now
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tid = runCatching { SmsSender.sendNew(context.applicationContext, number!!, body!!) }.getOrNull()
                if (tid != null) { pending.resultCode = Activity.RESULT_OK; pending.resultData = tid.toString() }
                else { pending.resultCode = 1; pending.resultData = "send failed" }
            } finally { pending.finish() }
        }
    }
}
