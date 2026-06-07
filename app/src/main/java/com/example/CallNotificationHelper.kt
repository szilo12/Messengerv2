package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

object CallNotificationHelper {
    private const val CHANNEL_ID_VOIP = "olyna_messenger_call_channel_voip_v2"
    private const val CHANNEL_NAME_VOIP = "Olyna Lockscreen Calls"
    private const val CHANNEL_ID_SILENT = "olyna_messenger_call_channel_silent_v2"
    private const val CHANNEL_NAME_SILENT = "Olyna Incoming Calls"
    private const val NOTIFICATION_ID = 1001

    private fun createAvatarIconBitmap(context: Context, name: String, hexColor: Long): Bitmap {
        val size = 120 // Perfect size for system notifications
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Draw background circle
        val paint = Paint().apply {
            isAntiAlias = true
            color = hexColor.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // Center text initial
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        
        val initial = name.firstOrNull()?.toString()?.uppercase() ?: "?"
        val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, size / 2f, yPos, textPaint)
        
        return bitmap
    }

    fun buildIncomingCallNotification(context: Context, callData: CallData): Notification {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true

        // 1. Create Notification Channels on Android Oreo (API 26) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // VOIP High Importance Channel (Required for full screen intent on Lockscreen)
            val voipChannel = NotificationChannel(
                CHANNEL_ID_VOIP,
                CHANNEL_NAME_VOIP,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies of incoming VoIP video and voice calls on Olyna Messenger when locked"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(voipChannel)

            // Silent/Default Importance Channel (For unlocked state, prevents system heads-up overlap)
            val silentChannel = NotificationChannel(
                CHANNEL_ID_SILENT,
                CHANNEL_NAME_SILENT,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies of incoming VoIP calls on Olyna Messenger when unlocked"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(silentChannel)
        }

        // 2. Prepare Intent to launch IncomingCallActivity in full screen
        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flagMutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            flagMutable
        )

        // 3. Prepare Broadcast Intents for Decline and Answer actions
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            declineIntent,
            flagMutable
        )

        // Directly launch the IncomingCallActivity to answer, which Android permits for notifications
        val answerIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action_answer", true)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            2,
            answerIntent,
            flagMutable
        )

        // 4. Create custom RemoteViews with responsive layout bindings
        val avatarBitmap = createAvatarIconBitmap(context, callData.callerName, callData.callerAvatarHexColor)

        val remoteViewsCollapsed = android.widget.RemoteViews(context.packageName, R.layout.custom_call_notification_collapsed).apply {
            setTextViewText(R.id.notification_title, callData.callerName)
            setTextViewText(R.id.notification_subtitle, callData.callerSubtitle)
            setImageViewBitmap(R.id.notification_avatar, avatarBitmap)
            
            setOnClickPendingIntent(R.id.btn_accept, answerPendingIntent)
            setOnClickPendingIntent(R.id.btn_decline, declinePendingIntent)
        }

        val remoteViewsExpanded = android.widget.RemoteViews(context.packageName, R.layout.custom_call_notification_expanded).apply {
            setTextViewText(R.id.notification_title, callData.callerName)
            setTextViewText(R.id.notification_subtitle, callData.callerSubtitle)
            setImageViewBitmap(R.id.notification_avatar, avatarBitmap)
            
            setOnClickPendingIntent(R.id.btn_accept, answerPendingIntent)
            setOnClickPendingIntent(R.id.btn_decline, declinePendingIntent)
        }

        // Determine target channel ID and priority based on screen status
        val targetChannelId = if (isLocked) CHANNEL_ID_VOIP else CHANNEL_ID_SILENT
        val priority = if (isLocked) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT

        val builder = NotificationCompat.Builder(context, targetChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColor(0xFF6366F1.toInt()) // Indigo theme accent tint
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Only start the full screen activity if the device screen is locked!
        // If the device is unlocked, we rely on the custom lebegő hívásablak FloatingCallOverlay.
        if (isLocked) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        } else {
            builder.setFullScreenIntent(null, false)
        }

        builder.setCustomContentView(remoteViewsCollapsed)
            .setCustomBigContentView(remoteViewsExpanded)
            .setCustomHeadsUpContentView(remoteViewsCollapsed)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        val notification = builder.build()
        
        // Ensure flag has FLAG_INSISTENT (to ring continuously until dismissed)
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        return notification
    }

    fun showIncomingCallNotification(context: Context, callData: CallData) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildIncomingCallNotification(context, callData)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
