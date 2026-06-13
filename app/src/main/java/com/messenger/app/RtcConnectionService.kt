package com.messenger.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class RtcConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "RtcConnectionService"
        
        fun registerPhoneAccount(context: Context) {
            try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
                val componentName = ComponentName(context, RtcConnectionService::class.java)
                val phoneAccountHandle = PhoneAccountHandle(componentName, "MessengerVoipAccount")
                
                val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Messenger Calls")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build()
                
                telecomManager.registerPhoneAccount(phoneAccount)
                Log.d(TAG, "PhoneAccount registered successfully with TelecomManager.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PhoneAccount: ${e.message}")
            }
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection? {
        Log.d(TAG, "onCreateIncomingConnection called by system.")
        
        val callExtras = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val callerName = callExtras?.getString("callerName") ?: "Messenger hívás"
        val callId = callExtras?.getString("callId")
        val chatId = callExtras?.getString("chatId")
        val callType = callExtras?.getString("callType")
        val avatarUrl = callExtras?.getString("avatarUrl")
        
        val connection = RtcConnection(
            applicationContext,
            callId,
            chatId,
            callerName,
            callType,
            avatarUrl
        )
        
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        connection.setInitializing()
        // connection.setRinging() // Disabled to prevent duplicate dark blue system-native call banner on Samsung OneUI
        
        RtcConnectionManager.activeConnection = connection
        
        // Determine whether screen is locked to start appropriate UI
        val locked = shouldUseFullScreenUi()
        Log.d(TAG, "Launching UI from ConnectionService: locked=$locked")
        
        MyFirebaseMessagingService.startRingtone(applicationContext, callId)
        FloatingCallOverlayService.show(applicationContext, callerName, callId, chatId, callType, avatarUrl)
        
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed: system refused self-managed connection")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        RtcConnectionManager.clear()
        
        // Direct UI Fallback
        val callExtras = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val callerName = callExtras?.getString("callerName") ?: "Messenger hívás"
        val callId = callExtras?.getString("callId")
        val chatId = callExtras?.getString("chatId")
        val callType = callExtras?.getString("callType")
        val avatarUrl = callExtras?.getString("avatarUrl")
        
        MyFirebaseMessagingService.startRingtone(applicationContext, callId)
        FloatingCallOverlayService.show(applicationContext, callerName, callId, chatId, callType, avatarUrl)
    }

    private fun shouldUseFullScreenUi(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        val isDeviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceLocked == true
        } else {
            isKeyguardLocked
        }
        val isInteractive = powerManager?.isInteractive != false
        
        return isKeyguardLocked || isDeviceLocked || !isInteractive
    }
}
