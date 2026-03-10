package com.nexusbridge.smsbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

/**
 * Wraps Android SMS/MMS ContentProvider queries and SmsManager sends.
 */
class SmsRepository(private val context: Context) {

    companion object {
        private const val TAG = "SmsRepository"
    }

    // ── Conversations (inbox thread list) ─────────────────────────────────────

    data class Conversation(
        val threadId: Long,
        val address: String,
        val displayName: String?,
        val snippet: String,
        val date: Long,
        val unreadCount: Int,
        val messageCount: Int
    )

    fun getConversations(limit: Int = 50): List<Conversation> {
        val conversations = mutableListOf<Conversation>()

        // Query all SMS messages, pick the latest per thread
        // This is more reliable than content://mms-sms/conversations which varies by OEM
        try {
            val threadsSeen = mutableSetOf<Long>()

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ,
                    Telephony.Sms.TYPE
                ),
                null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                Log.d(TAG, "getConversations: total SMS rows=${cursor.count}")

                val idxThread  = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                val idxAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val idxBody    = cursor.getColumnIndex(Telephony.Sms.BODY)
                val idxDate    = cursor.getColumnIndex(Telephony.Sms.DATE)
                val idxRead    = cursor.getColumnIndex(Telephony.Sms.READ)

                if (idxThread < 0 || idxAddress < 0) {
                    Log.e(TAG, "getConversations: required columns missing")
                    return conversations
                }

                while (cursor.moveToNext() && conversations.size < limit) {
                    val threadId = cursor.getLong(idxThread)
                    if (threadId in threadsSeen) continue
                    threadsSeen.add(threadId)

                    val address     = cursor.getString(idxAddress) ?: continue
                    val snippet     = if (idxBody >= 0) cursor.getString(idxBody) ?: "" else ""
                    val date        = if (idxDate >= 0) cursor.getLong(idxDate) else 0L
                    val readVal     = if (idxRead >= 0) cursor.getInt(idxRead) else 1

                    val displayName = lookupContactName(address)

                    // Count unread in this thread
                    val unreadCount = countUnread(threadId)

                    conversations.add(
                        Conversation(
                            threadId     = threadId,
                            address      = address,
                            displayName  = displayName,
                            snippet      = snippet,
                            date         = date,
                            unreadCount  = unreadCount,
                            messageCount = 0  // skip expensive count per thread
                        )
                    )
                }
            } ?: Log.w(TAG, "getConversations: query returned null")
        } catch (e: Exception) {
            Log.e(TAG, "getConversations error: ${e.message}", e)
        }

        Log.i(TAG, "getConversations: returning ${conversations.size} conversations")
        return conversations
    }

    private fun countUnread(threadId: Long): Int {
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0",
                arrayOf(threadId.toString()),
                null
            )?.use { it.count } ?: 0
        } catch (e: Exception) { 0 }
    }

    // ── Thread Messages ───────────────────────────────────────────────────────

    data class SmsMessage(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,          // 1 = inbox, 2 = sent
        val read: Boolean,
        val outgoing: Boolean
    )

    fun getThreadMessages(threadId: Long, limit: Int = 100): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.READ
                ),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                Log.d(TAG, "getThreadMessages: threadId=$threadId rows=${cursor.count}")

                val idxId      = cursor.getColumnIndex(Telephony.Sms._ID)
                val idxThread  = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                val idxAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val idxBody    = cursor.getColumnIndex(Telephony.Sms.BODY)
                val idxDate    = cursor.getColumnIndex(Telephony.Sms.DATE)
                val idxType    = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val idxRead    = cursor.getColumnIndex(Telephony.Sms.READ)

                if (idxId < 0 || idxBody < 0) {
                    Log.e(TAG, "getThreadMessages: required columns missing")
                    return messages
                }

                // Skip to most recent `limit` messages
                val count = cursor.count
                val skip = maxOf(0, count - limit)
                if (!cursor.moveToPosition(skip)) return@use

                do {
                    val msgType = if (idxType >= 0) cursor.getInt(idxType) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    messages.add(
                        SmsMessage(
                            id       = if (idxId >= 0) cursor.getLong(idxId) else 0L,
                            threadId = if (idxThread >= 0) cursor.getLong(idxThread) else threadId,
                            address  = if (idxAddress >= 0) cursor.getString(idxAddress) ?: "" else "",
                            body     = cursor.getString(idxBody) ?: "",
                            date     = if (idxDate >= 0) cursor.getLong(idxDate) else 0L,
                            type     = msgType,
                            read     = if (idxRead >= 0) cursor.getInt(idxRead) == 1 else true,
                            outgoing = msgType == Telephony.Sms.MESSAGE_TYPE_SENT
                        )
                    )
                } while (cursor.moveToNext())
            } ?: Log.w(TAG, "getThreadMessages: null cursor for threadId=$threadId")
        } catch (e: Exception) {
            Log.e(TAG, "getThreadMessages error: ${e.message}", e)
        }

        Log.i(TAG, "getThreadMessages: returning ${messages.size} messages for thread $threadId")
        return messages
    }

    // ── Send SMS ──────────────────────────────────────────────────────────────

    /**
     * Send an SMS and save it to the device's sent box so it appears in the
     * default messaging app. Calls [onResult] with success=true/false on the
     * calling thread (background-safe).
     */
    fun sendSms(to: String, body: String, onResult: ((success: Boolean, threadId: Long) -> Unit)? = null) {
        try {
            @Suppress("DEPRECATION")
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val sentAction = "com.nexusbridge.SMS_SENT_${System.currentTimeMillis()}"
            val sentPi = PendingIntent.getBroadcast(
                context, 0,
                Intent(sentAction),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // Register a one-shot receiver to know if the SMS was sent
            val sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val success = resultCode == android.app.Activity.RESULT_OK
                    Log.i(TAG, "SMS sent to $to: success=$success resultCode=$resultCode")
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}

                    if (success) {
                        // Save to sent box — required for it to appear in messaging apps
                        val threadId = saveSentMessage(to, body)
                        onResult?.invoke(true, threadId)
                    } else {
                        onResult?.invoke(false, -1L)
                    }
                }
            }
            context.registerReceiver(sentReceiver, IntentFilter(sentAction),
                Context.RECEIVER_NOT_EXPORTED)

            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(to, null, body, sentPi, null)
            } else {
                val sentPis = ArrayList<PendingIntent?>(parts.size).apply {
                    add(sentPi)          // fire ack on first part only
                    repeat(parts.size - 1) { add(null) }
                }
                smsManager.sendMultipartTextMessage(to, null, parts, sentPis, null)
            }
            Log.i(TAG, "SMS dispatched to $to (${parts.size} part(s))")
        } catch (e: Exception) {
            Log.e(TAG, "sendSms error: ${e.message}", e)
            onResult?.invoke(false, -1L)
        }
    }

    /**
     * Insert a sent message into the SMS content provider so it shows up
     * in the device's default messaging app.
     * Returns the threadId of the conversation.
     */
    private fun saveSentMessage(to: String, body: String): Long {
        return try {
            val threadId = Telephony.Threads.getOrCreateThreadId(context, to)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, to)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, threadId)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            Log.i(TAG, "Saved sent SMS to DB: threadId=$threadId to=$to")
            threadId
        } catch (e: Exception) {
            Log.e(TAG, "saveSentMessage error: ${e.message}", e)
            -1L
        }
    }

    // ── Mark Thread Read ──────────────────────────────────────────────────────

    fun markThreadRead(threadId: Long) {
        try {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "markThreadRead error: ${e.message}")
        }
    }

    // ── Contact lookup ────────────────────────────────────────────────────────

    fun lookupContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Get thread ID for address ─────────────────────────────────────────────

    fun getOrCreateThreadId(address: String): Long {
        return try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            -1L
        }
    }
}
