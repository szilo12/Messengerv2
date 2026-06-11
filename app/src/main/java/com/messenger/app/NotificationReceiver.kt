package com.messenger.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationReceiver"
        private const val PREFS_NAME = "messenger_notification_prefs"
        private const val DISMISSED_CALL_TTL_MS = 10 * 60 * 1000L
        const val ACTION_STOP_RINGTONE = "ACTION_STOP_RINGTONE"
        const val ACTION_DECLINE_CALL = "ACTION_DECLINE_CALL"
        const val ACTION_ACCEPT_CALL = "ACTION_ACCEPT_CALL"
        const val ACTION_END_CALL = "ACTION_END_CALL"

        @JvmStatic
        fun markCallDismissed(context: Context?, callId: String?) {
            if (context == null || callId.isNullOrEmpty()) return

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong("dismissed_call_$callId", System.currentTimeMillis())
                .apply()
        }

        @JvmStatic
        fun isCallDismissed(context: Context?, callId: String?): Boolean {
            if (context == null || callId.isNullOrEmpty()) return false

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dismissedAt = prefs.getLong("dismissed_call_$callId", 0L)
            if (dismissedAt <= 0L) return false

            val stillBlocked = System.currentTimeMillis() - dismissedAt < DISMISSED_CALL_TTL_MS
            if (!stillBlocked) {
                prefs.edit().remove("dismissed_call_$callId").apply()
            }
            return stillBlocked
        }

        @JvmStatic
        fun reportDeclineToBackend(context: Context?, callId: String?) {
            if (context == null || callId.isNullOrEmpty()) return

            var backendUrl = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("backendUrl", "")
                ?.trim()
                ?.trimEnd('/')
                ?: ""

            if (backendUrl.isEmpty()) {
                backendUrl = "https://messenger-push-backend.onrender.com"
            }

            Thread {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("$backendUrl/decline-call-bg")
                    val payload = JSONObject().put("callId", callId).toString().toByteArray(Charsets.UTF_8)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 40000
                        readTimeout = 40000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }
                    connection.outputStream.use { it.write(payload) }
                    val code = connection.responseCode
                    Log.d(TAG, "reportDeclineToBackend: callId=$callId response=$code")
                } catch (error: Exception) {
                    Log.e(TAG, "reportDeclineToBackend failed: ${error.message}")
                } finally {
                    connection?.disconnect()
                }
            }.start()
        }

        @JvmStatic
        fun reportAcceptToBackend(context: Context?, callId: String?) {
            if (context == null || callId.isNullOrEmpty()) return

            var backendUrl = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("backendUrl", "")
                ?.trim()
                ?.trimEnd('/')
                ?: ""

            if (backendUrl.isEmpty()) {
                backendUrl = "https://messenger-push-backend.onrender.com"
            }

            Thread {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("$backendUrl/accept-call-bg")
                    val payload = JSONObject().put("callId", callId).toString().toByteArray(Charsets.UTF_8)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 40000
                        readTimeout = 40000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }
                    connection.outputStream.use { it.write(payload) }
                    val code = connection.responseCode
                    Log.d(TAG, "reportAcceptToBackend: callId=$callId response=$code")
                } catch (error: Exception) {
                    Log.e(TAG, "reportAcceptToBackend failed: ${error.message}")
                } finally {
                    connection?.disconnect()
                }
            }.start()
        }

        @JvmStatic
        fun reportEndToBackend(context: Context?, callId: String?) {
            if (context == null || callId.isNullOrEmpty()) return

            var backendUrl = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("backendUrl", "")
                ?.trim()
                ?.trimEnd('/')
                ?: ""

            if (backendUrl.isEmpty()) {
                backendUrl = "https://messenger-push-backend.onrender.com"
            }

            Thread {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("$backendUrl/end-call-bg")
                    val payload = JSONObject().put("callId", callId).toString().toByteArray(Charsets.UTF_8)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 40000
                        readTimeout = 40000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }
                    connection.outputStream.use { it.write(payload) }
                    val code = connection.responseCode
                    Log.d(TAG, "reportEndToBackend: callId=$callId response=$code")
                } catch (error: Exception) {
                    Log.e(TAG, "reportEndToBackend failed: ${error.message}")
                } finally {
                    connection?.disconnect()
                }
            }.start()
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        val callId = intent.getStringExtra("callId")
        val chatId = intent.getStringExtra("chatId")
        val callerName = intent.getStringExtra("callerName")
        val callType = intent.getStringExtra("callType")
        val avatarUrl = intent.getStringExtra("avatarUrl")
        Log.d(TAG, "onReceive: $action callId=$callId")

        when (action) {
            ACTION_STOP_RINGTONE -> {
                stopLocalCallUi(context, callId, false)
            }
            ACTION_DECLINE_CALL -> {
                stopLocalCallUi(context, callId, true)
                clearActiveCallState()
                ChatHeadPlugin.queueCallAction(context, callId, "decline", chatId, callerName ?: "", callType ?: "", avatarUrl ?: "")
                ChatHeadPlugin.sendStopRingtoneEvent()
                reportDeclineToBackend(context, callId)
            }
            ACTION_ACCEPT_CALL -> {
                stopLocalCallUi(context, callId, true)
                ChatHeadPlugin.queueCallAction(context, callId, "accept", chatId, callerName ?: "", callType ?: "", avatarUrl ?: "")
                ChatHeadPlugin.sendStopRingtoneEvent()
                reportAcceptToBackend(context, callId)
                showOngoingCallSurface(context, callId, chatId, callerName, callType, avatarUrl)

                val appIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("chatId", chatId)
                    putExtra("callId", callId)
                    putExtra("callAction", "accept")
                    putExtra("callerName", callerName)
                    putExtra("callType", callType)
                    putExtra("avatarUrl", avatarUrl)
                }
                context.startActivity(appIntent)

                val activeCallIntent = Intent(context, ActiveCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("chatId", chatId)
                    putExtra("callId", callId)
                    putExtra("callerName", callerName)
                    putExtra("callType", callType)
                    putExtra("avatarUrl", avatarUrl)
                }
                context.startActivity(activeCallIntent)
            }
            ACTION_END_CALL -> {
                stopLocalCallUi(context, callId, true)
                clearActiveCallState()
                ChatHeadPlugin.queueCallAction(context, callId, "end", chatId, callerName ?: "", callType ?: "", avatarUrl ?: "")
                ChatHeadPlugin.sendStopRingtoneEvent()
                reportEndToBackend(context, callId)
            }
        }
    }

    private fun clearActiveCallState() {
        ChatHeadPlugin.activeOngoingCall = false
        ChatHeadPlugin.activeCallId = null
        ChatHeadPlugin.activeChatId = null
        ChatHeadPlugin.activeCallerName = null
        ChatHeadPlugin.activeCallType = null
        ChatHeadPlugin.activeAvatarUrl = null
    }

    private fun showOngoingCallSurface(
        context: Context,
        callId: String?,
        chatId: String?,
        callerName: String?,
        callType: String?,
        avatarUrl: String?
    ) {
        ChatHeadPlugin.activeOngoingCall = true
        ChatHeadPlugin.activeCallId = callId
        ChatHeadPlugin.activeChatId = chatId
        ChatHeadPlugin.activeCallerName = callerName ?: "Messenger"
        ChatHeadPlugin.activeCallType = callType ?: "audio"
        ChatHeadPlugin.activeAvatarUrl = avatarUrl ?: ""

        val ongoingIntent = Intent(context, FloatingCallOverlayService::class.java).apply {
            action = FloatingCallOverlayService.ACTION_SHOW_NOTIFICATION_ONLY
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName ?: "Messenger")
            putExtra("callType", callType ?: "audio")
            putExtra("avatarUrl", avatarUrl ?: "")
            putExtra("statusText", "Hivas...")
            putExtra("isOngoingOrOutgoing", true)
        }
        try {
            ContextCompat.startForegroundService(context, ongoingIntent)
        } catch (error: Exception) {
            Log.e(TAG, "showOngoingCallSurface failed: ${error.message}")
        }
    }

    private fun stopLocalCallUi(context: Context, callId: String?, markDismissed: Boolean) {
        if (markDismissed) {
            markCallDismissed(context, callId)
        }

        MyFirebaseMessagingService.stopRingtone()
        IncomingCallActivity.dismissCall(callId)
        FloatingCallOverlayService.stop(context, callId)
        ChatHeadPlugin.sendStopRingtoneEvent()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(7777)
        manager?.cancel(8888)
    }
}
