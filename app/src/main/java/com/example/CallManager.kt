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

    var isAppInForeground: Boolean = false

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
        val activeCall = _currentCall.value
        _callStatus.value = CallStatus.DECLINED
        _currentCall.value = null
        
        // Stop foreground service
        val serviceIntent = Intent(context, CallNotificationService::class.java)
        context.stopService(serviceIntent)

        stopRingingInternal()
        CallNotificationHelper.cancelNotification(context)
        
        // Auto-insert a missed/declined call record in Room chat database
        if (activeCall != null) {
            scope.launch {
                try {
                    val db = androidx.room.Room.databaseBuilder(
                        context.applicationContext,
                        com.example.data.AppDatabase::class.java,
                        "olyna_messenger_db"
                    ).fallbackToDestructiveMigration().build()
                    
                    val callerId = when {
                        activeCall.callerName.contains("Olyna", ignoreCase = true) -> "olyna"
                        activeCall.callerName.contains("Szilárd", ignoreCase = true) -> "szilard"
                        activeCall.callerName.contains("Anya", ignoreCase = true) || activeCall.callerName.contains("Család", ignoreCase = true) -> "anyuka"
                        else -> "olyna"
                    }
                    
                    val callLogMessage = com.example.data.DbMessage(
                        senderId = callerId,
                        receiverId = "me",
                        content = if (activeCall.callType == CallType.VIDEO) "Nem fogadott videóhívás" else "Nem fogadott hanghívás",
                        timestamp = System.currentTimeMillis(),
                        isRead = false,
                        isAccepted = true
                    )
                    db.chatDao().insertMessage(callLogMessage)
                    db.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed inserting call decline log", e)
                }
            }
        }
        
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
        val activeCall = _currentCall.value
        _callStatus.value = CallStatus.IDLE
        _currentCall.value = null
        
        // Stop foreground service
        val serviceIntent = Intent(context, CallNotificationService::class.java)
        context.stopService(serviceIntent)

        stopRingingInternal()
        CallNotificationHelper.cancelNotification(context)

        // Auto-insert an ongoing call log in Room chat database to show complete call history
        if (activeCall != null) {
            scope.launch {
                try {
                    val db = androidx.room.Room.databaseBuilder(
                        context.applicationContext,
                        com.example.data.AppDatabase::class.java,
                        "olyna_messenger_db"
                    ).fallbackToDestructiveMigration().build()
                    
                    val callerId = when {
                        activeCall.callerName.contains("Olyna", ignoreCase = true) -> "olyna"
                        activeCall.callerName.contains("Szilárd", ignoreCase = true) -> "szilard"
                        activeCall.callerName.contains("Anya", ignoreCase = true) || activeCall.callerName.contains("Család", ignoreCase = true) -> "anyuka"
                        else -> "olyna"
                    }
                    
                    val callLogMessage = com.example.data.DbMessage(
                        senderId = "me",
                        receiverId = callerId,
                        content = if (activeCall.callType == CallType.VIDEO) "Videóhívás véget ért" else "Hanghívás véget ért",
                        timestamp = System.currentTimeMillis(),
                        isRead = true,
                        isAccepted = true
                    )
                    db.chatDao().insertMessage(callLogMessage)
                    db.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed inserting end call log", e)
                }
            }
        }
        
        scope.launch {
            _signalingState.value = SignalingState.DISCONNECTED
            addLog("🟥 [Signaling] Live call terminated. Closing all RTCPeerConnections.")
            delay(1000)
            _signalingState.value = SignalingState.IDLE
        }
    }

    private var toneGenerator: android.media.ToneGenerator? = null
    private var toneJob: kotlinx.coroutines.Job? = null

    fun startRingingInternal(context: Context) {
        stopRingingInternal()

        // Launch async job on IO to fetch user settings, then configure call ringing
        scope.launch(Dispatchers.IO) {
            var customRingtone = "Alapértelmezett"
            var customVibration = "Alapértelmezett"

            val callData = _currentCall.value
            if (callData != null) {
                try {
                    val db = androidx.room.Room.databaseBuilder(
                        context.applicationContext,
                        com.example.data.AppDatabase::class.java,
                        "olyna_messenger_db"
                    ).fallbackToDestructiveMigration().build()
                    
                    val callerId = when {
                        callData.callerName.contains("Olyna", ignoreCase = true) -> "olyna"
                        callData.callerName.contains("Szilárd", ignoreCase = true) -> "szilard"
                        callData.callerName.contains("Anya", ignoreCase = true) || callData.callerName.contains("Család", ignoreCase = true) -> "anyuka"
                        else -> "olyna"
                    }
                    val user = db.chatDao().getUserById(callerId)
                    if (user != null) {
                        customRingtone = user.customRingtone
                        customVibration = user.customVibration
                    }
                    db.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking user call settings from Db", e)
                }
            }

            // Bring sound/vibration setups of the selected values back onto the main loop
            scope.launch(Dispatchers.Main) {
                Log.d(TAG, "Applying ringtone: $customRingtone, vibration: $customVibration for incoming call")
                
                // Sound setup
                if (customRingtone == "Alapértelmezett") {
                    try {
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error playing default ringtone", e)
                    }
                } else {
                    // Start synthesized melody beep thread
                    playCustomToneMelody(customRingtone)
                }

                // Vibration setup
                try {
                    @Suppress("DEPRECATION")
                    vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    
                    val pattern = getVibrationPattern(customVibration)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(pattern, 0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error vibrating", e)
                }
            }
        }
    }

    private fun getVibrationPattern(vibrationName: String): LongArray {
        return when (vibrationName) {
            "Szuper Gyors" -> longArrayOf(0, 150, 150, 150, 150, 150)
            "Szívverés" -> longArrayOf(0, 150, 150, 150, 500, 150, 150, 150, 500)
            "SOS Jelzés" -> longArrayOf(0, 200, 150, 200, 150, 200, 300, 450, 150, 450, 150, 450, 300, 200, 150, 200, 150, 200, 600)
            "Egyenletes Hosszú" -> longArrayOf(0, 2500, 1000, 2500)
            else -> longArrayOf(0, 1000, 1000, 1000) // Alapértelmezett
        }
    }

    private fun playCustomToneMelody(ringtoneName: String) {
        toneJob?.cancel()
        toneJob = scope.launch(Dispatchers.Default) {
            try {
                val generator = android.media.ToneGenerator(android.media.AudioManager.STREAM_RING, 100)
                toneGenerator = generator
                while (_callStatus.value == CallStatus.INCOMING) {
                    when (ringtoneName) {
                        "Klasszikus dallam" -> {
                            generator.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 800)
                            delay(2000)
                        }
                        "Neon dallam" -> {
                            generator.startTone(android.media.ToneGenerator.TONE_DTMF_D, 150)
                            delay(200)
                            generator.startTone(android.media.ToneGenerator.TONE_DTMF_A, 150)
                            delay(250)
                            generator.startTone(android.media.ToneGenerator.TONE_DTMF_9, 200)
                            delay(1500)
                        }
                        "Lágy ütem" -> {
                            generator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 300)
                            delay(500)
                            generator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 300)
                            delay(1800)
                        }
                        "Szirén csengő" -> {
                            generator.startTone(android.media.ToneGenerator.TONE_SUP_ERROR, 500)
                            delay(1000)
                        }
                        else -> {
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in custom tone playing", e)
            } finally {
                toneGenerator?.release()
                toneGenerator = null
            }
        }
    }

    fun stopRingingInternal() {
        try {
            toneJob?.cancel()
            toneJob = null
            toneGenerator?.release()
            toneGenerator = null

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
