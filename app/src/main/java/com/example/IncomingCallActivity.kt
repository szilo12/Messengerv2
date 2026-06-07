package com.example

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

class IncomingCallActivity : ComponentActivity() {

    private val isInPipModeState = mutableStateOf(false)

    fun triggerPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = android.util.Rational(3, 4)
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (CallManager.callStatus.value == CallStatus.ONGOING) {
            triggerPiPMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        super.onDestroy()
        if (CallManager.callStatus.value == CallStatus.ONGOING || CallManager.callStatus.value == CallStatus.INCOMING) {
            CallManager.endCall(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure flags to display on top of lock screen and wake up screen
        configureLockScreenFlags()
        
        enableEdgeToEdge()

        // Check if we should immediately jump to ongoing (e.g. answered from heads-up notification or lock screen)
        handleCallIntent(intent)

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val callStatus = CallManager.callStatus.collectAsStateWithLifecycle()
                val currentCall = CallManager.currentCall.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Sleek slate 900
                ) {
                    val callData = currentCall.value ?: CallData(
                        callerName = "Olyna Contact",
                        callerSubtitle = "Incoming video call"
                    )

                    when (callStatus.value) {
                        CallStatus.INCOMING -> {
                            IncomingCallScreen(
                                callData = callData,
                                onAnswer = {
                                    CallManager.answerCall(context)
                                },
                                onDecline = {
                                    CallManager.declineCall(context)
                                    finish()
                                }
                            )
                        }
                        CallStatus.ONGOING -> {
                            OngoingCallScreen(
                                callData = callData,
                                isInPipMode = isInPipModeState.value,
                                onMinimize = {
                                    triggerPiPMode()
                                },
                                onHangUp = {
                                    CallManager.endCall(context)
                                    finish()
                                }
                            )
                        }
                        else -> {
                            // If IDLE, MISSED or DECLINED, close calling screen
                            LaunchedEffect(Unit) {
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    private fun handleCallIntent(intent: android.content.Intent) {
        val startAsOngoing = intent.getBooleanExtra("start_ongoing", false) || 
                             intent.getBooleanExtra("action_answer", false)
        if (startAsOngoing) {
            CallManager.answerCall(this)
        }
    }

    private fun configureLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}

@Composable
fun IncomingCallScreen(
    callData: CallData,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    var showQuickReplies by remember { mutableStateOf(false) }
    val signalingState by CallManager.signalingState.collectAsStateWithLifecycle()
    val signalingLogs by CallManager.signalingLogs.collectAsStateWithLifecycle()

    // Multi-ripple staggered pulse physics simulations
    val wave1 = remember { Animatable(0f) }
    val wave2 = remember { Animatable(0f) }
    val wave3 = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            while (true) {
                wave1.animateTo(1f, animationSpec = tween(2800, easing = LinearOutSlowInEasing))
                wave1.snapTo(0f)
            }
        }
        launch {
            delay(900)
            while (true) {
                wave2.animateTo(1f, animationSpec = tween(2800, easing = LinearOutSlowInEasing))
                wave2.snapTo(0f)
            }
        }
        launch {
            delay(1800)
            while (true) {
                wave3.animateTo(1f, animationSpec = tween(2800, easing = LinearOutSlowInEasing))
                wave3.snapTo(0f)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E1B4B), // Indigo 950
                        Color(0xFF0F172A), // Slate 900
                        Color(0xFF020617)  // Slate 950
                    )
                )
            )
            .padding(safeDrawingPadding())
    ) {
        // Safe dark overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f))
        )

        // 1. Top Bar: E2E Secured Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = Color(0xFF10B981).copy(alpha = 0.85f),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "End-to-end encrypted • HD Video",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium
            )
        }

        // 2. Caller Avatar with Staggered Multi-waves
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(240.dp)
            ) {
                // Wave Ripple 1
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(1f + wave1.value * 0.9f)
                        .background(
                            Color(callData.callerAvatarHexColor).copy(alpha = (1f - wave1.value) * 0.4f),
                            shape = CircleShape
                        )
                )
                // Wave Ripple 2
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(1f + wave2.value * 0.9f)
                        .background(
                            Color(callData.callerAvatarHexColor).copy(alpha = (1f - wave2.value) * 0.4f),
                            shape = CircleShape
                        )
                )
                // Wave Ripple 3
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(1f + wave3.value * 0.9f)
                        .background(
                            Color(callData.callerAvatarHexColor).copy(alpha = (1f - wave3.value) * 0.4f),
                            shape = CircleShape
                        )
                )

                // Main circular avatar (Frosted dynamic frame)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(132.dp)
                        .background(Color.White.copy(alpha = 0.08f), shape = CircleShape)
                        .padding(6.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(callData.callerAvatarHexColor),
                                        Color(callData.callerAvatarHexColor).copy(alpha = 0.7f)
                                    )
                                ),
                                shape = CircleShape
                            )
                    ) {
                        val initial = callData.callerName.firstOrNull()?.toString()?.uppercase() ?: "?"
                        Text(
                            text = initial,
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Subtitle status: Messenger branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFFEC4899), shape = CircleShape) // Pink glow
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "OLYNA MESSENGER CALL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = Color(0xFFF472B6) // Soft Pink
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = callData.callerName,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (callData.callType == CallType.VIDEO) "Bejövő videohívás..." else "Bejövő hanghívás...",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            WebRTCSignalingHUD(
                signalingState = signalingState,
                logs = signalingLogs,
                modifier = Modifier.widthIn(max = 400.dp)
            )
        }

        // 3. Lower Action Grid (with Message Quick Replies) Safe area drawer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!showQuickReplies) {
                // Secondary Action: Message drawer toggle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { showQuickReplies = true }
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubble,
                        contentDescription = "Quick Reply Message",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Üzenet küldése",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Standard decline / answer action columns row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Refuse Trigger Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = onDecline,
                            modifier = Modifier
                                .size(74.dp)
                                .background(Color(0xFFEF4444), shape = CircleShape)
                                .testTag("decline_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline Call",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Elutasítás",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Accept Trigger Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = onAnswer,
                            modifier = Modifier
                                .size(74.dp)
                                .background(Color(0xFF22C55E), shape = CircleShape)
                                .testTag("answer_call_button")
                        ) {
                            Icon(
                                imageVector = if (callData.callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                                contentDescription = "Answer Call",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Fogadás",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                // Quick Replies panel (Facebook Messenger Interactive response sheet)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .animateContentSize(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Gyorsválasz küldése",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 16.sp
                            )
                            IconButton(
                                onClick = { showQuickReplies = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Zárás",
                                    tint = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val HungarianReplies = listOf(
                            "Szia! Épp úton vagyok, 5 perc múlva hívlak! 🚗",
                            "Most nem alkalmas, visszahívlak hamarosan! 👋",
                            "Szia! Megbeszélésen vagyok, írj üzenetet! 🤫",
                            "Köszönöm, most nem tudok beszélni! 🚫"
                        )

                        HungarianReplies.forEach { reply ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        android.widget.Toast
                                            .makeText(
                                                context,
                                                "Gyorsválasz elküldve: \"$reply\"",
                                                android.widget.Toast.LENGTH_LONG
                                            )
                                            .show()
                                        onDecline()
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.06f)
                            ) {
                                Text(
                                    text = reply,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OngoingCallScreen(
    callData: CallData,
    isInPipMode: Boolean = false,
    onMinimize: () -> Unit = {},
    onHangUp: () -> Unit
) {
    val context = LocalContext.current
    val signalingState by CallManager.signalingState.collectAsStateWithLifecycle()
    val signalingLogs by CallManager.signalingLogs.collectAsStateWithLifecycle()
    var isMuted by remember { mutableStateOf(false) }
    var isCameraOff by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(true) }
    var isCameraFront by remember { mutableStateOf(true) }
    
    var audioRoute by remember { mutableStateOf("Hangszóró") }
    var connectionStateIndex by remember { mutableIntStateOf(0) }
    val connectionStates = listOf(
        Triple("Kapcsolódás...", Color(0xFFF59E0B), Icons.Rounded.Autorenew),
        Triple("Gyenge internetkapcsolat", Color(0xFFEF4444), Icons.Rounded.SignalWifiBad),
        Triple("Kiváló kapcsolat", Color(0xFF10B981), Icons.Rounded.SignalWifi4Bar)
    )
    
    // Aesthetic Filters: 0: None, 1: Neon Glow, 2: Deep Space, 3: Office
    var activeFilterIndex by remember { mutableIntStateOf(0) }
    val filters = listOf("Valós", "Neon Glow 🎆", "Mély Űr 🚀", "Iroda ☕")

    // Draggable local video PIP offset values
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Active call elapsed timer state
    var callSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            callSeconds++
        }
    }

    val timerText = remember(callSeconds) {
        val mins = callSeconds / 60
        val secs = callSeconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    LaunchedEffect(callSeconds) {
        // Auto transition over time to showcase all connection states!
        if (callSeconds == 1) connectionStateIndex = 0 // Kapcsolódás
        if (callSeconds == 5) connectionStateIndex = 2 // Kiváló kapcsolat
        if (callSeconds == 12) connectionStateIndex = 1 // Gyenge internetkapcsolat
        if (callSeconds == 20) connectionStateIndex = 2 // Back to stable
    }

    // Active audio soundwave/equalizer simulation
    val equalizerBarHeights = remember { mutableStateListOf(0.3f, 0.5f, 0.2f, 0.6f, 0.4f, 0.7f, 0.3f) }
    LaunchedEffect(isMuted) {
        if (!isMuted) {
            while (true) {
                delay(130)
                for (i in equalizerBarHeights.indices) {
                    equalizerBarHeights[i] = (15..95).random() / 100f
                }
            }
        } else {
            for (i in equalizerBarHeights.indices) {
                equalizerBarHeights[i] = 0.05f
            }
        }
    }

    if (isInPipMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(4.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(callData.callerAvatarHexColor), shape = CircleShape)
                ) {
                    val initial = callData.callerName.firstOrNull()?.toString()?.uppercase() ?: "?"
                    Text(
                        text = initial,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = callData.callerName,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = timerText,
                    color = Color(0xFF22C55E),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19)) // Deep night background
    ) {
        // 1. Shifting ambient color mesh background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = when (activeFilterIndex) {
                            1 -> listOf(Color(0xFFCD2C7E).copy(alpha = 0.35f), Color(0xFF0F172A)) // Neon Pink Sunset
                            2 -> listOf(Color(0xFF1E3A8A).copy(alpha = 0.4f), Color(0xFF0B122C))  // Space Blue
                            3 -> listOf(Color(0xFF78350F).copy(alpha = 0.3f), Color(0xFF18181B))  // Cozy brown wood
                            else -> listOf(Color(callData.callerAvatarHexColor).copy(alpha = 0.25f), Color(0xFF0B0F19))
                        }
                    )
                )
        )

        // Remote Stream container
        if (!isCameraOff && callData.callType == CallType.VIDEO) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Interactive stream simulation
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .background(Color.White.copy(alpha = 0.04f), shape = CircleShape)
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(callData.callerAvatarHexColor), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val initial = callData.callerName.firstOrNull()?.toString()?.uppercase() ?: "?"
                            Text(text = initial, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Text(
                        text = "Hálózati videokamera aktív",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Simulated audio micro frequency bars under remote participant
                    Row(
                        modifier = Modifier
                            .height(26.dp)
                            .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        equalizerBarHeights.forEach { heightVal ->
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight(heightVal)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color(0xFFA855F7), Color(0xFFEC4899))
                                        ),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        } else {
            // Audio Stream layout centered
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(140.dp)
                        .background(Color(callData.callerAvatarHexColor), shape = CircleShape)
                ) {
                    val initial = callData.callerName.firstOrNull()?.toString()?.uppercase() ?: "?"
                    Text(
                        text = initial,
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "${callData.callerName}",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Kamera kikapcsolva",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Active Audio frequency equalizers (simulates voice peaks)
                Row(
                    modifier = Modifier.height(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    equalizerBarHeights.forEach { heightVal ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight(heightVal)
                                .background(Color(0xFF22C55E), shape = CircleShape)
                        )
                    }
                }
            }
        }

        // Top Information Banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 54.dp, start = 24.dp, end = 24.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = callData.callerName,
                fontSize = 22.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF22C55E), shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timerText,
                    fontSize = 14.sp,
                    color = Color(0xFF22C55E),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Encryption badge
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.25f), shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Végpontok közötti titkosítás",
                    color = Color(0xFF10B981),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real-time Connection Quality & Signaling Status Badge (Tap to cycle manually for testing!)
            val currentQuality = connectionStates[connectionStateIndex]
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(currentQuality.second.copy(alpha = 0.15f))
                    .border(1.dp, currentQuality.second.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clickable {
                        connectionStateIndex = (connectionStateIndex + 1) % connectionStates.size
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = currentQuality.third,
                    contentDescription = null,
                    tint = currentQuality.second,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = currentQuality.first,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when(connectionStateIndex) {
                            0 -> "Jelzések csatlakoztatása..."
                            1 -> "Gyenge internetkapcsolat. Kevesebb sávszélesség."
                            else -> "Nagyszerű hálózati minőség. HD Videó aktív."
                        },
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 9.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            WebRTCSignalingHUD(
                signalingState = signalingState,
                logs = signalingLogs,
                modifier = Modifier.widthIn(max = 340.dp)
            )
        }

        // Picture-in-Picture Local Self Preview Overlay with flip and filter effects
        if (callData.callType == CallType.VIDEO && !isCameraOff) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .padding(bottom = 160.dp, end = 24.dp)
                    .width(110.dp)
                    .height(154.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .background(Color(0xFF1E293B), shape = RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
            ) {
                // Apply simulated filter backings
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            when (activeFilterIndex) {
                                1 -> Brush.verticalGradient(listOf(Color(0xFFF472B6), Color(0xFF9333EA))) // Cyberpink glow
                                2 -> Brush.radialGradient(listOf(Color(0xFF0284C7), Color(0xFF030712)))   // Space dark glow
                                3 -> Brush.verticalGradient(listOf(Color(0xFFD97706), Color(0xFF451A03))) // Cozy warm light
                                else -> Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF1E293B))) // Standard Dark
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Local selfie info
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (isCameraFront) Icons.Default.Face else Icons.Default.Camera,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ön",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isCameraFront) "Előlapi" else "Hátlapi",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Floating Control Bar in Bottom Area
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1E293B).copy(alpha = 0.92f), // Frosted glass translucent
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle Audio Mic
                    IconButton(
                        onClick = { isMuted = !isMuted },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isMuted) Color(0xFFEF4444) else Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute Microphone"
                        )
                    }

                    // Toggle Video Camera
                    if (callData.callType == CallType.VIDEO) {
                        IconButton(
                            onClick = { isCameraOff = !isCameraOff },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isCameraOff) Color(0xFFEF4444) else Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                imageVector = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                                contentDescription = "Toggle Video"
                            )
                        }

                        // Flip Camera Front / Back (New feature!)
                        IconButton(
                            onClick = {
                                isCameraFront = !isCameraFront
                                android.widget.Toast.makeText(
                                    context,
                                    if (isCameraFront) "Előlapi kamerára váltva" else "Hátlapi kamerára váltva",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Flip Camera"
                            )
                        }

                        // Atmospheric Filters cycling selector (New feature!)
                        IconButton(
                            onClick = {
                                activeFilterIndex = (activeFilterIndex + 1) % filters.size
                                android.widget.Toast.makeText(
                                    context,
                                    "Szűrő alkalmazva: ${filters[activeFilterIndex]}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeFilterIndex != 0) Color(0xFFA855F7) else Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterBAndW,
                                contentDescription = "Cyclic Filters"
                            )
                        }
                    }

                    // Audio output route selector (Speaker / Earphone Switcher)
                    val isSpeaker = audioRoute == "Hangszóró"
                    IconButton(
                        onClick = {
                            audioRoute = if (isSpeaker) "Fülhallgató/Fejhallgató" else "Hangszóró"
                            android.widget.Toast.makeText(
                                context,
                                "Hangkimenet váltva: $audioRoute",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isSpeaker) Color(0xFF0084FF) else Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            imageVector = if (isSpeaker) Icons.Rounded.VolumeUp else Icons.Rounded.Headset,
                            contentDescription = "Hangkimenet váltása"
                        )
                    }

                    // Minimize to PiP Button (New feature!)
                    IconButton(
                        onClick = onMinimize,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Minimizálás Picture-in-Picture-be"
                        )
                    }

                    // Hang Up Button
                    IconButton(
                        onClick = onHangUp,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Hang Up call"
                        )
                    }
                }
            }
        }
    }
}

// Custom easing interpolator for gorgeous fluid pulse
private fun twistyTween(duration: Int): TweenSpec<Float> {
    return tween(
        durationMillis = duration,
        easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
    )
}

@Composable
private fun safeDrawingPadding() = WindowInsets.safeDrawing.asPaddingValues()

@Composable
fun WebRTCSignalingHUD(
    signalingState: SignalingState,
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize()
            .testTag("webrtc_signaling_hud"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A).copy(alpha = 0.85f)
        ),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .testTag("webrtc_hud_header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Signaling Console",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WebRTC Signal HUD",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                val (chipColor, label) = when (signalingState) {
                    SignalingState.IDLE -> Color(0xFF64748B) to "IDLE"
                    SignalingState.CONNECTING_WS -> Color(0xFFF59E0B) to "CONNECTING"
                    SignalingState.RECEIVING_OFFER -> Color(0xFF06B6D4) to "OFFER IN"
                    SignalingState.GENERATING_ANSWER -> Color(0xFF8B5CF6) to "GEN ANSWER"
                    SignalingState.EXCHANGING_ICE -> Color(0xFFEC4899) to "ICE SWAP"
                    SignalingState.CONNECTED -> Color(0xFF22C55E) to "CONNECTED"
                    SignalingState.DISCONNECTED -> Color(0xFFEF4444) to "DISCONNECTED"
                }

                Box(
                    modifier = Modifier
                        .background(chipColor.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                        .border(1.dp, chipColor, shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = label,
                        color = chipColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(Color(0xFF020617), shape = RoundedCornerShape(10.dp))
                        .padding(8.dp)
                        .testTag("webrtc_logs_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = "Nincsenek aktív jelzések. Várakozás...",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    } else {
                        items(logs) { logMsg ->
                            Text(
                                text = logMsg,
                                color = if (logMsg.contains("CONNECTED") || logMsg.contains("success")) Color(0xFF34D399)
                                        else if (logMsg.contains("Received") || logMsg.contains("establishing")) Color(0xFF38BDF8)
                                        else if (logMsg.contains("local candidate") || logMsg.contains("Remote")) Color(0xFFF472B6)
                                        else Color.LightGray,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "A hívásokat valós idejű WebSockets + SDP csatornákon bonyolítjuk.",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
