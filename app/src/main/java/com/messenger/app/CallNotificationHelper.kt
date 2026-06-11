package com.messenger.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

object CallNotificationHelper {
    private const val CHANNEL_ID_LOCKSCREEN = "messenger_call_lockscreen_v3"
    private const val CHANNEL_ID_UNLOCKED = "messenger_call_unlocked_v3"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (manager.getNotificationChannel(CHANNEL_ID_LOCKSCREEN) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID_LOCKSCREEN,
                "Messenger hivasok zarolt kepernyon",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Bejovo hang- es videohivasok teljes kepernyos megjelenitessel"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        if (manager.getNotificationChannel(CHANNEL_ID_UNLOCKED) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID_UNLOCKED,
                "Messenger hivasok feloldott kepernyon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bejovo hivasok csendes panelje, amikor a lebego hivasablak lathato"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildIncomingCallNotification(
        context: Context,
        callerName: String,
        callId: String?,
        chatId: String?,
        callType: String?,
        avatarUrl: String?,
        callerAvatar: Bitmap?
    ): Notification {
        ensureChannels(context)

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        val typeText = if (callType == "video") "Bejovo videohivas" else "Bejovo hanghivas"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            stableCallRequestCode(callId, 7799),
            fullScreenIntent,
            flags
        )

        val acceptIntent = Intent(context, IncomingCallActivity::class.java).apply {
            action = IncomingCallActivity.ACTION_ACCEPT_CALL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        val acceptPending = PendingIntent.getActivity(
            context,
            stableCallRequestCode(callId, 7777),
            acceptIntent,
            flags
        )

        val declineIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_DECLINE_CALL
            putExtra("callId", callId)
            putExtra("chatId", chatId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context,
            stableCallRequestCode(callId, 7778),
            declineIntent,
            flags
        )

        val deleteIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_STOP_RINGTONE
            putExtra("callId", callId)
        }
        val deletePending = PendingIntent.getBroadcast(
            context,
            stableCallRequestCode(callId, 7780),
            deleteIntent,
            flags
        )

        val avatar = callerAvatar ?: createInitialAvatar(callerName)
        val collapsed = RemoteViews(context.packageName, R.layout.custom_call_notification_collapsed).apply {
            setTextViewText(R.id.notification_title, callerName)
            setTextViewText(R.id.notification_subtitle, typeText)
            setImageViewBitmap(R.id.notification_avatar, avatar)
            setOnClickPendingIntent(R.id.btn_accept, acceptPending)
            setOnClickPendingIntent(R.id.btn_decline, declinePending)
        }
        val expanded = RemoteViews(context.packageName, R.layout.custom_call_notification_expanded).apply {
            setTextViewText(R.id.notification_title, callerName)
            setTextViewText(R.id.notification_subtitle, typeText)
            setImageViewBitmap(R.id.notification_avatar, avatar)
            setOnClickPendingIntent(R.id.btn_accept, acceptPending)
            setOnClickPendingIntent(R.id.btn_decline, declinePending)
        }

        val channelId = if (isLocked) CHANNEL_ID_LOCKSCREEN else CHANNEL_ID_UNLOCKED
        val priority = if (isLocked) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_call_chat)
            .setContentTitle(callerName)
            .setContentText(typeText)
            .setPriority(priority)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColor(Color.rgb(99, 102, 241))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPending)
            .setDeleteIntent(deletePending)
            .setLargeIcon(avatar)
            .setTimeoutAfter(60_000L)

        builder
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(collapsed)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        if (isLocked) {
            builder
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPending, true)
        }

        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    private fun createInitialAvatar(name: String): Bitmap {
        val size = 120
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 99, 235)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, y, textPaint)
        return bitmap
    }

    private fun stableCallRequestCode(callId: String?, salt: Int): Int {
        return salt + kotlin.math.abs((callId?.hashCode() ?: 0) % 100000)
    }
}
