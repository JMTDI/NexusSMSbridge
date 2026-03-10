package com.nexusbridge.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * Receives incoming SMS broadcasts and forwards them to BridgeService for relay to web client.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group PDUs by originating address
        val grouped = mutableMapOf<String, StringBuilder>()
        var timestamp = System.currentTimeMillis()

        messages.forEach { msg ->
            val address = msg.displayOriginatingAddress ?: msg.originatingAddress ?: "Unknown"
            grouped.getOrPut(address) { StringBuilder() }.append(msg.messageBody ?: "")
            timestamp = msg.timestampMillis
        }

        for ((address, bodyBuilder) in grouped) {
            val body = bodyBuilder.toString()
            Log.i(TAG, "Incoming SMS from $address: ${body.take(40)}…")

            val repo = SmsRepository(context)
            val displayName = repo.lookupContactName(address)
            val threadId = repo.getOrCreateThreadId(address)

            // Deliver to BridgeService if it's running
            // We use startService (not start foreground) because the service is already running
            val serviceIntent = Intent(context, BridgeService::class.java).apply {
                action = "com.nexusbridge.smsbridge.SMS_INCOMING"
                putExtra("from", address)
                putExtra("displayName", displayName)
                putExtra("body", body)
                putExtra("threadId", threadId)
                putExtra("date", timestamp)
            }

            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not notify BridgeService: ${e.message}")
            }
        }
    }
}
