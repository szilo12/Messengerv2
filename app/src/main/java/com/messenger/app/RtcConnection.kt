package com.messenger.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class RtcConnection(
    private val context: Context,
    val callId: String?,
    val chatId: String?,
    val callerName: String,
    val callType: String?,
    val avatarUrl: String?
) : Connection() {

    companion object {
        private const val TAG = "RtcConnection"
    }

    init {
        // Set self-managed capability configuration
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        audioModeIsVoip = true
    }

    override fun onAnswer(videoState: Int) {
        Log.d(TAG, "onAnswer called (e.g. from Bluetooth/Smartwatch/System UI)")
        super.onAnswer(videoState)
        
        setActive()
        
        // Notify local receiver of acceptance to sync with frontend
        val acceptIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_ACCEPT_CALL
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        context.sendBroadcast(acceptIntent)
    }

    override fun onReject() {
        Log.d(TAG, "onReject called (e.g. from Bluetooth/Smartwatch/System UI)")
        super.onReject()
        
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        RtcConnectionManager.clear()
        
        // Notify local receiver of decline to sync with backend and frontend
        val declineIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_DECLINE_CALL
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        context.sendBroadcast(declineIntent)
    }

    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect called")
        super.onDisconnect()
        
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        RtcConnectionManager.clear()
        
        // Notify local receiver of end to sync with backend and frontend
        val endIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_END_CALL
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        context.sendBroadcast(endIntent)
    }

    override fun onAbort() {
        Log.d(TAG, "onAbort called")
        super.onAbort()
        
        setDisconnected(DisconnectCause(DisconnectCause.MISSED))
        destroy()
        RtcConnectionManager.clear()
        
        val declineIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_DECLINE_CALL
            putExtra("callId", callId)
            putExtra("chatId", chatId)
        }
        context.sendBroadcast(declineIntent)
    }
}
