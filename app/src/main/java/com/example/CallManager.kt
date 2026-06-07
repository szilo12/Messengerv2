package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CallStatus {
    IDLE, INCOMING, ONGOING, MISSED, DECLINED
}

enum class CallType {
    VOICE, VIDEO
}

enum class SignalingState {
    IDLE,
    CONNECTING_WS,
    RECEIVING_OFFER,
    GENERATING_ANSWER,
    EXCHANGING_ICE,
    CONNECTED,
    DISCONNECTED
}

data class CallData(
    val callerName: String,
    val callerSubtitle: String = "Video call from Messenger",
    val callerAvatarHexColor: Long = 0xFF4F46E5, // Indigo standard
    val callType: CallType = CallType.VIDEO
)

object CallManager {
    private const val TAG = "CallManager"
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _callStatus = MutableStateFlow(CallStatus.IDLE)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _currentCall = MutableStateFlow<CallData?>(null)
    val currentCall: StateFlow<CallData?> = _currentCall.asStateFlow()

    private val _signalingState = MutableStateFlow(SignalingState.IDLE)
    val signalingState: StateFlow<SignalingState> = _signalingState.asStateFlow()

    private val _signalingLogs = MutableStateFlow<List<String>>(emptyList())
    val signalingLogs: StateFlow<List<String>> = _signalingLogs.asStateFlow()

    private val _schedulerSecondsLeft = MutableStateFlow(0)
    val schedulerSecondsLeft: StateFlow<Int> = _schedulerSecondsLeft.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    fun scheduleCall(context: Context, delaySeconds: Int, callData: CallData) {
        scope.launch {
            _schedulerSecondsLeft.value = delaySeconds
            while (_schedulerSecondsLeft.value > 0) {
                delay(1000)
                _schedulerSecondsLeft.value = _schedulerSecondsLeft.value - 1
            }
            triggerIncomingCall(context.applicationContext, callData)
        }
    }

    private fun addLog(message: String) {
        val currentList = _signalingLogs.value.toMutableList()
        currentList.add(message)
        _signalingLogs.value = currentList
        Log.d(TAG, " Signaling Log: $message")
    }

    fun clearLogs() {
        _signalingLogs.value = emptyList()
    }

    fun triggerIncomingCall(context: Context, callData: CallData) {
        Log.d(TAG, "Triggering incoming call for ${callData.callerName}")
        _currentCall.value = callData
        _callStatus.value = CallStatus.INCOMING
        clearLogs()

        // Start asynchronous mock WebRTC signaling offer phase simulation
        scope.launch {
            _signalingState.value = SignalingState.CONNECTING_WS
            addLog("⚡ [WebSocket] Connecting to Olyna WebRTC signaling broker...")
            delay(500)
            
            addLog("🔒 [WebSocket] TLS 1.3 secured WebSocket connection established.")
            delay(400)

            _signalingState.value = SignalingState.RECEIVING_OFFER
            addLog("📥 [WebRTC] Received remote SDP OFFER from ${callData.callerName}")
            delay(450)

            addLog("⚙️ [WebRTC] SDP offer codec checklist: H.264 Video, Opus Audio, VP9, VP8.")
            delay(400)
            
            addLog("🔍 [WebRTC] SDPMedia: Verified secure RTP profile with SAVPF streaming.")
        }

        // Delegate incoming call alerting + background persistence to the Foreground CallNotificationService
        val serviceIntent = Intent(context, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_START_INCOMING
            putExtra("caller_name", callData.callerName)
            putExtra("caller_subtitle", callData.callerSubtitle)
            putExtra("caller_avatar_color", callData.callerAvatarHexColor)
            putExtra("call_type", callData.callType.name)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting CallNotificationService, using legacy in-process fallback", e)
            startRingingInternal(context)
            CallNotificationHelper.showIncomingCallNotification(context, callData)
        }
    }

    fun answerCall(context: Context) {
        Log.d(TAG, "Answering call")
        _callStatus.value = CallStatus.ONGOING
        
        // Stop the foreground service
        val serviceIntent = Intent(context, CallNotificationService::class.java)
        context.stopService(serviceIntent)
        
        // Ensure in-process assets are cleared too
        stopRingingInternal()

        // Simulate local answer generation and interactive peer connection setup
        scope.launch {
            _signalingState.value = SignalingState.GENERATING_ANSWER
            addLog("⚙️ [Local PeerConnection] Executing SetRemoteDescription with remote SDP offer...")
            delay(500)

            addLog("📤 [WebRTC] Local SDP ANSWER session generated successfully.")
            delay(400)

            _signalingState.value = SignalingState.EXCHANGING_ICE
            addLog("🔄 [ICE] Gathering local network candidates & dispatching to peer via WebSocket...")
            delay(600)

            addLog("❇️ [ICE] Local candidate candidate:1 typ host raddr 192.168.1.5 rport 58242 exchanged.")
            addLog("❇️ [ICE] Remote STUN/TURN reflexive candidate verified successfully!")
            delay(400)

            _signalingState.value = SignalingState.CONNECTED
            addLog("🤝 [WebRTC State] Connection CONNECTED! Media channels opened natively.")
            addLog("📡 [Media Channel] Audio stream active: Opus @ 48kHz, dual-band.")
            if (_currentCall.value?.callType == CallType.VIDEO) {
                addLog("📡 [Media Channel] Video stream active: H.264 High-Profile @ 30fps.")
            }
        }

        CallNotificationHelper.cancelNotification(context)
    }

    fun declineCall(context: Context) {
        Log.d(TAG, "Declining call")
        _callStatus.value = CallStatus.DECLINED
        _currentCall.value = null
        
        // Stop foreground service
        val serviceIntent = Intent(context, CallNotificationService::class.java)
        context.stopService(serviceIntent)

        stopRingingInternal()
        CallNotificationHelper.cancelNotification(context)
        
        scope.launch {
            _signalingState.value = SignalingState.DISCONNECTED
            addLog("❌ [Signaling] Remote connection was declined by receiver.")
            delay(1000)
            _signalingState.value = SignalingState.IDLE
            _callStatus.value = CallStatus.IDLE
        }
    }

    fun endCall(context: Context) {
        Log.d(TAG, "Ending call")
        _callStatus.value = CallStatus.IDLE
        _currentCall.value = null
        
        // Stop foreground service
        val serviceIntent = Intent(context, CallNotificationService::class.java)
        context.stopService(serviceIntent)

        stopRingingInternal()
        CallNotificationHelper.cancelNotification(context)
        
        scope.launch {
            _signalingState.value = SignalingState.DISCONNECTED
            addLog("🟥 [Signaling] Live call terminated. Closing all RTCPeerConnections.")
            delay(1000)
            _signalingState.value = SignalingState.IDLE
        }
    }

    fun startRingingInternal(context: Context) {
        try {
            stopRingingInternal()

            // Initialize MediaPlayer with default ringtone
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            // Start vibration
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000, 1000) // Vibrate 1s, pause 1s
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone or vibrating", e)
        }
    }

    fun stopRingingInternal() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null

            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone or vibration", e)
        }
    }
}
