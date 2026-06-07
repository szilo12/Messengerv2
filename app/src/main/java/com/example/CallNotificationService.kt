package com.example

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class CallNotificationService : Service() {

    companion object {
        private const val TAG = "CallNotificationService"
        const val ACTION_START_INCOMING = "com.example.ACTION_START_INCOMING"
        const val ACTION_STOP = "com.example.ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallNotificationService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        if (action == ACTION_START_INCOMING) {
            val callerName = intent?.getStringExtra("caller_name") ?: "Olyna"
            val callerSubtitle = intent?.getStringExtra("caller_subtitle") ?: "Bejövő Videó Hívás"
            val callerAvatarColor = intent?.getLongExtra("caller_avatar_color", 0xFFEC4899) ?: 0xFFEC4899
            val callTypeName = intent?.getStringExtra("call_type") ?: CallType.VIDEO.name
            val callType = try {
                CallType.valueOf(callTypeName)
            } catch (e: Exception) {
                CallType.VIDEO
            }

            val callData = CallData(
                callerName = callerName,
                callerSubtitle = callerSubtitle,
                callerAvatarHexColor = callerAvatarColor,
                callType = callType
            )

            // Build VoIP CallStyle Notification
            val notification = CallNotificationHelper.buildIncomingCallNotification(this, callData)

            // Start Foreground Service
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        1001,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(1001, notification)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Android 14+ Phone Call service permission issue, fallback to basic startForeground", e)
                startForeground(1001, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error starting foreground", e)
                startForeground(1001, notification)
            }

            // Play Ringtone and Start Vibration via centralized CallManager
            CallManager.startRingingInternal(this)

        } else if (action == ACTION_STOP) {
            stopForegroundAndRelease()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun stopForegroundAndRelease() {
        Log.d(TAG, "Stopping foreground service and releasing media resources")
        CallManager.stopRingingInternal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopForegroundAndRelease()
        super.onDestroy()
        Log.d(TAG, "CallNotificationService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
