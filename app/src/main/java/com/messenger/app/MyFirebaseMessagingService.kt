package com.messenger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.messaging.RemoteMessage
import com.capacitorjs.plugins.pushnotifications.MessagingService
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.content.ComponentName
import android.os.Bundle
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale

class MyFirebaseMessagingService : MessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val CALL_CHANNEL_ID = "messenger_calls_fullscreen"
        private const val CALL_NOTIFICATION_ID = 7777
        private var mediaPlayer: MediaPlayer? = null
        private var currentRingingCallId: String? = null

        @JvmStatic
        @Synchronized
        fun startRingtone(context: Context, callId: String?) {
            if (callId != null && callId == currentRingingCallId && mediaPlayer != null) {
                try {
                    if (mediaPlayer!!.isPlaying) {
                        Log.d(TAG, "Ringtone already playing for this call, not starting twice: $callId")
                        return
                    }
                } catch (ignored: Exception) {
                }
            }

            stopRingtone()
            currentRingingCallId = callId

            // Check if phone is on silent or vibrate mode
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val ringerMode = audioManager.ringerMode
                if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                    Log.d(TAG, "Phone is in silent/vibrate mode ($ringerMode), skipping manual ringtone playback.")
                    return
                }
            }

            try {
                val soundUri = Uri.parse("android.resource://" + context.packageName + "/raw/ringtone")
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, soundUri)
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .build()
                    setAudioAttributes(attrs)
                    isLooping = true
                    prepare()
                    start()
                }
                Log.d(TAG, "Native ringtone MediaPlayer started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start native ringtone MediaPlayer: " + e.message)
                try {
                    mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_RINGTONE_URI)?.apply {
                        isLooping = true
                        start()
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Ultimate fallback failed: " + e2.message)
                }
            }
        }

        @JvmStatic
        @Synchronized
        fun stopRingtone() {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) {
                        it.stop()
                    }
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping ringtone: " + e.message)
                }
                mediaPlayer = null
            }
            currentRingingCallId = null
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Chat Channel - HIGH IMPORTANCE for Pop-up (Heads-up)
            if (manager.getNotificationChannel("messenger_chat") == null) {
                val chatChannel = NotificationChannel(
                    "messenger_chat",
                    "Üzenetek",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Messenger-style chat messages"
                    enableLights(true)
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setAllowBubbles(true)
                    }
                }
                manager.createNotificationChannel(chatChannel)
            }

            // Call Channel - MAX IMPORTANCE for Full Screen Intent
            if (manager.getNotificationChannel(CALL_CHANNEL_ID) == null) {
                val callChannel = NotificationChannel(
                    CALL_CHANNEL_ID,
                    "Hivasok teljes kepernyon",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming video and audio calls"
                    setSound(null, null) // We play sound manually
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(callChannel)
            }

            // Background Call Channel - IMPORTANCE_DEFAULT for Floating Call Overlay (No heads-up pop-up)
            val bgCallChannelId = "messenger_calls_background_v4"
            if (manager.getNotificationChannel(bgCallChannelId) == null) {
                val bgCallChannel = NotificationChannel(
                    bgCallChannelId,
                    "Hivasok a hatterben",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Incoming calls when screen is unlocked"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(bgCallChannel)
            }

            CallNotificationHelper.ensureChannels(this)
        }
    }

    private fun notificationPrefs(): SharedPreferences {
        return getSharedPreferences("messenger_notification_prefs", Context.MODE_PRIVATE)
    }

    private fun isNotificationSoundEnabled(): Boolean {
        return notificationPrefs().getBoolean("soundEnabled", true)
    }

    private fun isNotificationVibrationEnabled(): Boolean {
        return notificationPrefs().getBoolean("vibrationEnabled", true)
    }

    private fun getMessageSoundName(): String {
        return notificationPrefs().getString("messageSound", "default") ?: "default"
    }

    private fun getMessageChannelId(): String {
        if (!isNotificationSoundEnabled()) return "messenger_chat_silent"
        if ("ringtone" == getMessageSoundName()) return "messenger_chat_ringtone"
        return "messenger_chat"
    }

    private fun ensureMessageChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            "Uzenetek",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Messenger-style chat messages"
            enableLights(true)
            enableVibration(isNotificationVibrationEnabled())
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC

            if ("messenger_chat_silent" == channelId) {
                setSound(null, null)
            } else if ("messenger_chat_ringtone" == channelId) {
                val soundUri = Uri.parse("android.resource://" + packageName + "/raw/ringtone")
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, attrs)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(true)
            }
        }

        manager.createNotificationChannel(channel)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Firebase messaging token refreshed.")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "onMessageReceived: New message from: " + remoteMessage.from)

        createNotificationChannels()

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val type = data["type"]
            val senderName = data["senderName"]
            val messageText = data["messageText"]
            val chatId = data["chatId"]
            val callId = data["callId"]
            val status = data["status"]

            // Terminal call events must immediately stop every native call UI and the ringtone.
            // Supported examples: call_cancel, call:end, call_reject, call_timeout,
            // and status values such as accepted/ended/rejected/cancelled/missed/busy.
            if (!callId.isNullOrEmpty() && isTerminalCallEvent(type, status)) {
                NotificationReceiver.markCallDismissed(applicationContext, callId)
                stopRingtone()
                IncomingCallActivity.dismissCall(callId)
                FloatingCallOverlayService.stop(applicationContext, callId)
                ChatHeadPlugin.sendStopRingtoneEvent()
                managerCancelCallNotification()
                return
            }

            if (isIncomingCallEvent(type, callId)) {
                // Prevent stale call notifications (e.g. delivered late on app launch)
                val sentTime = remoteMessage.sentTime
                if (sentTime > 0) {
                    val ageMs = System.currentTimeMillis() - sentTime
                    if (ageMs > 45000L) {
                        Log.d(TAG, "Ignoring stale incoming call notification: callId=$callId, age=$ageMs ms")
                        return
                    }
                }

                var callerName = data["callerName"]
                if (callerName.isNullOrEmpty()) {
                    callerName = senderName
                }
                data["backendUrl"]?.takeIf { it.isNotBlank() }?.let { backendUrl ->
                    getSharedPreferences("messenger_notification_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("backendUrl", backendUrl)
                        .apply()
                }

                val locked = shouldUseFullScreenIncomingCallUi()
                Log.d(TAG, "Incoming call push received: locked=$locked appVisible=${MainActivity.isAppVisible}")

                // Prefer native Telecom self-managed ConnectionService if O+ and permission is granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.MANAGE_OWN_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    showIncomingCallNotification(
                        if (!callerName.isNullOrEmpty()) callerName else "Messenger hívás",
                        callId,
                        chatId,
                        data["callType"],
                        data["avatarUrl"]
                    )
                } else {
                    // Fallback to legacy UI logic (handles both locked and unlocked cases natively)
                    showIncomingCallNotification(
                        if (!callerName.isNullOrEmpty()) callerName else "Messenger hívás",
                        callId,
                        chatId,
                        data["callType"],
                        data["avatarUrl"]
                    )
                }
                return
            }

            if (MainActivity.isAppVisible && chatId != null && chatId == MainActivity.activeChatId) {
                Log.d(TAG, "Skipping notification for currently open chat: $chatId")
                return
            }

            // If app is not visible, show our custom high-fidelity notifications
            if (!MainActivity.isAppVisible) {
                if (senderName != null && messageText != null) {
                    // Start Chat Head (Legacy custom implementation)
                    val serviceIntent = Intent(applicationContext, ChatHeadService::class.java).apply {
                        putExtra("senderName", senderName)
                        putExtra("messageText", messageText)
                        putExtra("chatId", chatId)
                        putExtra("avatarUrl", data["avatarUrl"])
                        putExtra("isNewMessage", true)
                    }
                    try {
                        ContextCompat.startForegroundService(this, serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "ChatHeadService start failed: " + e.message)
                    }

                    // Show MessagingStyle Notification (Native Bubble support)
                    showMessageNotification(senderName, messageText, chatId)
                    return
                }
            }
        }

        super.onMessageReceived(remoteMessage)
    }

    private fun showIncomingCallNotification(
        callerName: String,
        callId: String?,
        chatId: String?,
        callType: String?,
        avatarUrl: String?
    ) {
        if (NotificationReceiver.isCallDismissed(applicationContext, callId)) {
            Log.d(TAG, "Ignoring already dismissed call notification: $callId")
            return
        }

        // Try registering incoming call natively using self-managed ConnectionService on O+ devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.MANAGE_OWN_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                RtcConnectionService.registerPhoneAccount(this)
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    val componentName = ComponentName(this, RtcConnectionService::class.java)
                    val phoneAccountHandle = PhoneAccountHandle(componentName, "MessengerVoipAccount")
                    val addressUri = Uri.fromParts("tel", callId ?: "0000", null)
                    
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                        putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, addressUri)
                        
                        val incomingCallExtras = Bundle().apply {
                            putString("callerName", callerName)
                            putString("callId", callId)
                            putString("chatId", chatId)
                            putString("callType", callType)
                            putString("avatarUrl", avatarUrl)
                        }
                        putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, incomingCallExtras)
                    }
                    
                    Log.d(TAG, "Requesting TelecomManager to add incoming call: callId=$callId")
                    telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TelecomManager.addNewIncomingCall failed, falling back to legacy flow: ${e.message}")
            }
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        startRingtone(applicationContext, callId)

        // Directly post the incoming call notification. This is guaranteed to display in the
        // status bar/drawer with click options even if starting the service is delayed or blocked.
        val callerAvatar = downloadBitmap(avatarUrl)
        val incomingCallNotification = CallNotificationHelper.buildIncomingCallNotification(
            this,
            callerName,
            callId,
            chatId,
            callType,
            avatarUrl,
            callerAvatar
        )
        manager?.notify(CALL_NOTIFICATION_ID, incomingCallNotification)

        // Also launch the floating call overlay service to display the overlay when unlocked
        FloatingCallOverlayService.show(applicationContext, callerName, callId, chatId, callType, avatarUrl)
    }

    private fun managerCancelCallNotification() {
        FloatingCallOverlayService.stop(applicationContext, null)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(CALL_NOTIFICATION_ID)
    }

    private fun isTerminalCallEvent(type: String?, status: String?): Boolean {
        val normalizedType = normalizeCallValue(type)
        val normalizedStatus = normalizeCallValue(status)
        return normalizedType in setOf(
            "call_cancel", "call_cancelled", "call_canceled", "call_end", "call_ended",
            "call_reject", "call_rejected", "call_timeout", "call_missed", "call_busy",
            "call_accept", "call_accepted", "cancel", "cancelled", "canceled", "end", "ended",
            "reject", "rejected", "timeout", "missed", "busy", "accepted", "failed"
        ) || normalizedStatus in setOf(
            "accepted", "ended", "declined", "rejected", "cancelled", "canceled",
            "missed", "busy", "timeout", "failed"
        )
    }

    private fun isIncomingCallEvent(type: String?, callId: String?): Boolean {
        if (callId.isNullOrEmpty()) return false
        val normalizedType = normalizeCallValue(type)
        return normalizedType.isEmpty() || normalizedType in setOf(
            "call", "incoming_call", "call_invite", "call_invitation", "call_incoming", "call_ringing", "ringing", "invite"
        )
    }

    private fun normalizeCallValue(value: String?): String {
        return value
            ?.lowercase(Locale.ROOT)
            ?.replace(":", "_")
            ?.replace("-", "_")
            ?: ""
    }

    private fun shouldUseFullScreenIncomingCallUi(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        val isDeviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceLocked == true
        } else {
            isKeyguardLocked
        }
        val isInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager?.isInteractive != false
        } else {
            @Suppress("DEPRECATION")
            val isScreenOn = powerManager?.isScreenOn != false
            isScreenOn
        }

        return isKeyguardLocked || isDeviceLocked || !isInteractive
    }

    private fun stableCallRequestCode(callId: String?, salt: Int): Int {
        return salt + (callId?.hashCode()?.let { Math.abs(it % 100000) } ?: 0)
    }

    private fun downloadBitmap(urlString: String?): Bitmap? {
        if (urlString.isNullOrEmpty() || urlString.startsWith("preset:")) {
            return null
        }

        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.connect()
            input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading notification avatar: " + e.message)
            null
        } finally {
            try {
                input?.close()
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun showMessageNotification(senderName: String, messageText: String, chatId: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = chatId?.hashCode() ?: System.currentTimeMillis().toInt()
        val channelId = getMessageChannelId()
        ensureMessageChannel(channelId)

        val user = Person.Builder()
            .setName(senderName)
            .setImportant(true)
            .build()

        // Publish dynamic shortcut for Android 11+ Bubbles
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("chatId", chatId)
        }

        val shortcut = ShortcutInfoCompat.Builder(this, chatId!!)
            .setShortLabel(senderName)
            .setLongLived(true)
            .setIntent(shortcutIntent)
            .setPerson(user)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setCategories(Collections.singleton("com.example.category.MESSAGING"))
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("chatId", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Extract or build MessagingStyle for continuous conversation display
        var messagingStyle: NotificationCompat.MessagingStyle? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && manager != null) {
            try {
                val activeNotifications = manager.activeNotifications
                if (activeNotifications != null) {
                    for (sbn in activeNotifications) {
                        if (sbn.id == notificationId) {
                            messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
                            break
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        if (messagingStyle == null) {
            messagingStyle = NotificationCompat.MessagingStyle(Person.Builder().setName("Én").build())
            messagingStyle.conversationTitle = senderName
        }
        messagingStyle.addMessage(messageText, System.currentTimeMillis(), user)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_call_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShortcutId(chatId) // Link to published shortcut for Bubbles
            .setLocusId(androidx.core.content.LocusIdCompat(chatId))
            .setStyle(messagingStyle)
            .addAction(android.R.drawable.ic_menu_send, "Válasz", pendingIntent)

        if (!isNotificationSoundEnabled()) {
            builder.setSilent(true)
        }

        if (isNotificationVibrationEnabled()) {
            builder.setVibrate(longArrayOf(0, 180, 80, 180))
        } else {
            builder.setVibrate(longArrayOf(0))
        }

        manager?.notify(notificationId, builder.build())
    }
}
