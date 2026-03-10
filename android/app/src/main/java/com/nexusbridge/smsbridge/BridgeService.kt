package com.nexusbridge.smsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "nexusbridge_channel"
        const val EXTRA_SESSION_TOKEN = "extra_session_token"
        const val ACTION_STATUS_UPDATE = "com.nexusbridge.smsbridge.STATUS_UPDATE"
        const val ACTION_DISCONNECT = "com.nexusbridge.smsbridge.DISCONNECT"
        const val EXTRA_CONNECTED = "extra_connected"
        const val EXTRA_PIN = "extra_pin"

        private const val WS_BASE = "wss://your.domain.com/ws"
        private const val PING_INTERVAL_MS = 30_000L
    }

    // ── Binder ─────────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()

    // ── State ──────────────────────────────────────────────────────────────────
    private val gson = Gson()
    private lateinit var httpClient: OkHttpClient

    private var webSocket: WebSocket? = null
    private var sessionToken: String = ""
    val currentPin: String get() = _currentPin
    private var _currentPin: String = ""

    val isConnected: Boolean get() = _isConnected.get()
    private val _isConnected = AtomicBoolean(false)

    private val isReconnecting = AtomicBoolean(false)
    private var reconnectDelayMs = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var smsRepository: SmsRepository? = null

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (_isConnected.get()) {
                send(buildMessage("ping", JsonObject()))
                mainHandler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        httpClient = TlsHelper.buildClient(this)
        smsRepository = SmsRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
            "com.nexusbridge.smsbridge.SMS_INCOMING" -> {
                // Forwarded from SmsReceiver
                val from        = intent.getStringExtra("from") ?: return START_STICKY
                val displayName = intent.getStringExtra("displayName")
                val body        = intent.getStringExtra("body") ?: return START_STICKY
                val threadId    = intent.getLongExtra("threadId", -1L)
                val date        = intent.getLongExtra("date", System.currentTimeMillis())
                sendIncomingSms(from, displayName, body, threadId, date)
                return START_STICKY
            }
        }

        val token = intent?.getStringExtra(EXTRA_SESSION_TOKEN)
        if (!token.isNullOrBlank()) {
            sessionToken = token
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        connectWebSocket()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(pingRunnable)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
    }

    // ── WebSocket ──────────────────────────────────────────────────────────────

    fun connectWebSocket() {
        if (sessionToken.isBlank()) {
            Log.e(TAG, "No session token — cannot connect")
            return
        }

        val url = "$WS_BASE/$sessionToken?role=phone"
        Log.i(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _isConnected.set(true)
                reconnectDelayMs = 1000L
                isReconnecting.set(false)

                mainHandler.post {
                    updateNotification("Connected")
                    broadcastStatus(true)
                    mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
                }

                // Proactively push conversations once connected.
                // Small delay lets the server register us as the phone before
                // we send data, so the relay to the web client succeeds.
                Thread {
                    Thread.sleep(1000)
                    pushConversations()
                }.start()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "← $text")
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                onDisconnected()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onDisconnected()
            }
        })
    }

    private fun onDisconnected() {
        _isConnected.set(false)
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.post {
            updateNotification("Disconnected — reconnecting...")
            broadcastStatus(false)
            scheduleReconnect()
        }
    }

    fun reconnectNow() {
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        connectWebSocket()
    }

    private fun scheduleReconnect() {
        if (isReconnecting.getAndSet(true)) return
        Log.i(TAG, "Reconnecting in ${reconnectDelayMs}ms")
        mainHandler.postDelayed({
            isReconnecting.set(false)
            connectWebSocket()
        }, reconnectDelayMs)
        reconnectDelayMs = minOf(reconnectDelayMs * 2, 30_000L)
    }

    fun disconnect() {
        mainHandler.removeCallbacks(pingRunnable)
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _isConnected.set(false)
    }

    // ── Message handling ───────────────────────────────────────────────────────

    private fun handleMessage(raw: String) {
        val obj = try {
            gson.fromJson(raw, JsonObject::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON: $raw")
            return
        }

        val type = obj.get("type")?.asString ?: return
        val payload = obj.getAsJsonObject("payload") ?: JsonObject()

        when (type) {
            "ping" -> {
                send(buildMessage("pong", JsonObject()))
            }

            "pong" -> {
                // Keepalive acknowledged
            }

            "sms_list" -> {
                // Client requests conversation list
                Thread { pushConversations() }.start()
            }

            "sms_thread" -> {
                val threadId = payload.get("threadId")?.asLong ?: return
                Thread {
                    val messages = smsRepository!!.getThreadMessages(threadId, 100)
                    val response = buildMessage("sms_thread", gson.toJsonTree(
                        mapOf("threadId" to threadId, "messages" to messages)
                    ).asJsonObject)
                    send(response)
                }.start()
            }

            "sms_send" -> {
                val to    = payload.get("to")?.asString   ?: return
                val body  = payload.get("body")?.asString ?: return
                val msgId = payload.get("msgId")?.asString
                smsRepository!!.sendSms(to, body) { success, threadId ->
                    val ackData = com.google.gson.JsonObject().apply {
                        addProperty("to", to)
                        addProperty("success", success)
                        addProperty("threadId", threadId)
                        if (msgId != null) addProperty("msgId", msgId)
                    }
                    send(buildMessage("sms_sent_ack", ackData))
                    if (success) pushConversations()   // refresh sidebar
                }
            }

            "read_receipt" -> {
                val threadId = payload.get("threadId")?.asLong ?: return
                smsRepository!!.markThreadRead(threadId)
            }

            "typing_indicator" -> {
                // Phone doesn't display typing; ignore
            }

            else -> Log.d(TAG, "Unknown message type: $type")
        }
    }

    // ── Send helpers ───────────────────────────────────────────────────────────

    /** Call from any thread — fetches conversations and pushes sms_list to client. */
    private fun pushConversations() {
        try {
            val conversations = smsRepository!!.getConversations(50)
            Log.i(TAG, "pushConversations: ${conversations.size} conversations")
            val payload = gson.toJsonTree(mapOf("conversations" to conversations)).asJsonObject
            send(buildMessage("sms_list", payload))
        } catch (e: Exception) {
            Log.e(TAG, "pushConversations failed: ${e.message}", e)
        }
    }

    /** Public — called from HomeFragment refresh button. Runs on a background thread. */
    fun refreshConversations() {
        Thread { pushConversations() }.start()
    }

    fun send(json: String) {
        if (_isConnected.get()) {
            Log.d(TAG, "→ $json")
            webSocket?.send(json)
        } else {
            Log.w(TAG, "Cannot send — not connected")
        }
    }

    fun sendIncomingSms(from: String, displayName: String?, body: String, threadId: Long, date: Long) {
        val payloadObj = JsonObject().apply {
            addProperty("threadId", threadId)
            addProperty("address", from)
            addProperty("displayName", displayName ?: from)
            addProperty("body", body)
            addProperty("date", date)
        }
        send(buildMessage("sms_incoming", payloadObj))
    }

    private fun buildMessage(type: String, payload: JsonObject): String {
        val obj = JsonObject()
        obj.addProperty("type", type)
        obj.add("payload", payload)
        return gson.toJson(obj)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NexusBridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "NexusBridge connection status"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, BridgeService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPi = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("NexusBridge")
            .setContentText(status)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Disconnect", disconnectPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    private fun broadcastStatus(connected: Boolean) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_CONNECTED, connected)
            putExtra(EXTRA_PIN, _currentPin)
            `package` = packageName
        }
        sendBroadcast(intent)
    }
}
