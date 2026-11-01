package com.example.emit.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** The user's own line number(s), used to exclude himself from group participant sets. Empty if unavailable. */
object OwnNumbers {
    @SuppressLint("MissingPermission", "HardwareIds")
    fun get(context: Context): Set<String> {
        val out = HashSet<String>()
        runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList?.forEach { info ->
                val n = if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else @Suppress("DEPRECATION") info.number
                if (!n.isNullOrBlank()) out += n
            }
        }
        runCatching { context.getSystemService(TelephonyManager::class.java).line1Number?.takeIf { it.isNotBlank() }?.let { out += it } }
        return out
    }
}
