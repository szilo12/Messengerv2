package com.example

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.net.Uri
import android.media.MediaPlayer
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.example.data.*
import com.example.ui.theme.MyApplicationTheme
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val isNotificationPermissionGranted = mutableStateOf(false)

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isNotificationPermissionGranted.value = isGranted
        if (isGranted) {
            Toast.makeText(this, "Értesítési engedély megadva!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Figyelem! Engedély híján bejövő hívások nem fognak ringatni.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        CallManager.isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        CallManager.isAppInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkNotificationPermission()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val application = context.applicationContext as android.app.Application
                
                // Initialize Firebase on start
                LaunchedEffect(Unit) {
                    com.example.data.FirebaseService.init(context)
                }

                val isFirebaseLoggedIn by com.example.data.FirebaseService.isUserLoggedIn.collectAsState()
                var bypassLoginLocal by remember { mutableStateOf(false) }

                if (!isFirebaseLoggedIn && !bypassLoginLocal) {
                    com.example.ui.AuthScreen(onAuthSuccess = { bypassLoginLocal = true })
                } else {
                    // Initialize modern Messenger ViewModel with local Room persistence
                    val chatViewModel = remember { ChatViewModel(application) }
                    val scope = rememberCoroutineScope()

                    val allUsers by chatViewModel.allUsers.collectAsState()
                    val allMessages by chatViewModel.allMessages.collectAsState()
                    val settings by chatViewModel.settings.collectAsState()
                    val selectedUser by chatViewModel.selectedUser.collectAsState()
                    val activeChatMessages by chatViewModel.activeChatMessages.collectAsState()

                val callStatus by CallManager.callStatus.collectAsState()
                val currentCall by CallManager.currentCall.collectAsState()

                // Modern Startup Prompt Dialog for HUD Overlay / SYSTEM_ALERT_WINDOW permission
                var showOverlayPermissionPrompt by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            !android.provider.Settings.canDrawOverlays(context)
                        } else {
                            false
                        }
                    )
                }

                if (showOverlayPermissionPrompt) {
                    AlertDialog(
                        onDismissRequest = { showOverlayPermissionPrompt = false },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Layers,
                                    contentDescription = null,
                                    tint = Color(0xFF0084FF)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Lebegő Hívásablak",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Az Olyna Messenger Facebook Messenger-szerű lebegő hívásablakot használ, amely akkor is jelzi a hívást, ha Ön más alkalmazásban vagy a kezdőképernyőn tartózkodik.",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Text(
                                    text = "A működéshez engedélyeznie kell a 'Megjelenítés más alkalmazások felett' opciót a rendszerbeállításokban.",
                                    color = Color(0xFFF59E0B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 16.sp
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showOverlayPermissionPrompt = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                android.net.Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                            context.startActivity(intent)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF))
                            ) {
                                Text("Engedélyezés", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showOverlayPermissionPrompt = false }
                            ) {
                                Text("Később", color = Color.White.copy(alpha = 0.6f))
                            }
                        },
                        containerColor = Color(0xFF1E293B)
                    )
                }

                var currentTab by remember { mutableStateOf("chats") } // chats, friends, requests, settings
                val schedulerTimeLeft by CallManager.schedulerSecondsLeft.collectAsState()
                var scheduledCallerName by remember { mutableStateOf("") }

                // Physical Android Back Button Interception logic
                if (selectedUser != null) {
                    BackHandler {
                        chatViewModel.selectUser(null)
                    }
                } else if (currentTab != "chats") {
                    BackHandler {
                        currentTab = "chats"
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B141B) // Sleek Messenger dark blue theme background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color(0xFF0F172A), // Slate Dark
                            topBar = {
                                if (selectedUser == null) {
                                    CenterAlignedTopAppBar(
                                        title = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Chat,
                                                    contentDescription = null,
                                                    tint = Color(0xFF0084FF), // Messenger blue
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Messenger",
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 22.sp,
                                                    color = Color.White
                                                )
                                            }
                                        },
                                        navigationIcon = {
                                            Row(modifier = Modifier.padding(start = 12.dp)) {
                                                UserAvatar(
                                                    name = settings.currentUserName,
                                                    avatarColor = 0xFF6366F1,
                                                    avatarUrl = settings.avatarUrl,
                                                    size = 36.dp,
                                                    showActiveDot = true,
                                                    onClick = {
                                                        currentTab = "settings"
                                                    },
                                                    modifier = Modifier.testTag("appbar_self_profile")
                                                )
                                            }
                                        },
                                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                            containerColor = Color(0xFF0F172A)
                                        )
                                    )
                                }
                            },
                            bottomBar = {
                                if (selectedUser == null) {
                                    NavigationBar(
                                        containerColor = Color(0xFF1E293B),
                                        tonalElevation = 8.dp
                                    ) {
                                        NavigationBarItem(
                                            selected = currentTab == "chats",
                                            onClick = { currentTab = "chats" },
                                            icon = { Icon(Icons.Rounded.ChatBubble, contentDescription = "Csevegések") },
                                            label = { Text("Csevegések", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color(0xFF0084FF),
                                                selectedTextColor = Color(0xFF0084FF),
                                                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                                                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                                                indicatorColor = Color(0xFF0084FF).copy(alpha = 0.15f)
                                            )
                                        )
                                        NavigationBarItem(
                                            selected = currentTab == "friends",
                                            onClick = { currentTab = "friends" },
                                            icon = { Icon(Icons.Rounded.People, contentDescription = "Ismerősök") },
                                            label = { Text("Ismerősök", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color(0xFF0084FF),
                                                selectedTextColor = Color(0xFF0084FF),
                                                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                                                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                                                indicatorColor = Color(0xFF0084FF).copy(alpha = 0.15f)
                                            )
                                        )
                                        NavigationBarItem(
                                            selected = currentTab == "requests",
                                            onClick = { currentTab = "requests" },
                                            icon = { 
                                                BadgedBox(badge = {
                                                    val requestCount = allUsers.count { !it.isFriend && it.isRequestReceived } +
                                                                       allMessages.count { !it.isAccepted && it.senderId != "me" }
                                                    if (requestCount > 0) {
                                                        Badge(containerColor = Color.Red) {
                                                            Text(requestCount.toString(), color = Color.White, fontSize = 10.sp)
                                                        }
                                                    }
                                                }) {
                                                    Icon(Icons.Rounded.ForwardToInbox, contentDescription = "Engedélyek")
                                                }
                                            },
                                            label = { Text("Engedélyek", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color(0xFF0084FF),
                                                selectedTextColor = Color(0xFF0084FF),
                                                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                                                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                                                indicatorColor = Color(0xFF0084FF).copy(alpha = 0.15f)
                                            )
                                        )
                                        NavigationBarItem(
                                            selected = currentTab == "settings",
                                            onClick = { currentTab = "settings" },
                                            icon = { Icon(Icons.Rounded.Settings, contentDescription = "Beállítások") },
                                            label = { Text("Beállítások", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color(0xFF0084FF),
                                                selectedTextColor = Color(0xFF0084FF),
                                                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                                                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                                                indicatorColor = Color(0xFF0084FF).copy(alpha = 0.15f)
                                            )
                                        )
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                if (selectedUser != null) {
                                    // Live Selected Conversation Stream Screen
                                    val user = selectedUser!!
                                    ChatDetailScreen(
                                        user = user,
                                        messages = activeChatMessages,
                                        stealthActive = settings.isStealthMode,
                                        onBack = { chatViewModel.selectUser(null) },
                                        onSendMessage = { content ->
                                            if (com.example.data.FirebaseService.isUserLoggedIn.value) {
                                                scope.launch {
                                                    com.example.data.FirebaseService.sendRealtimeMessage(context, user.id, content, user.isFriend)
                                                }
                                            } else {
                                                if (user.isFriend) {
                                                    chatViewModel.sendAndReceiveMockReply(content)
                                                } else {
                                                    chatViewModel.sendMessage(content)
                                                }
                                            }
                                        },
                                        onTriggerImmediateCall = {
                                            if (com.example.data.FirebaseService.isUserLoggedIn.value) {
                                                com.example.data.FirebaseService.startRealtimeCall(context, user.id, user.name, CallType.VIDEO)
                                            } else {
                                                CallManager.triggerIncomingCall(
                                                    context,
                                                    CallData(
                                                        callerName = user.name,
                                                        callerSubtitle = "Bejövő Videó Hívás",
                                                        callType = CallType.VIDEO,
                                                        callerAvatarHexColor = user.avatarColor
                                                    )
                                                )
                                            }
                                        },
                                        onTriggerScheduledCall = { delaySec ->
                                            scheduledCallerName = user.name
                                            // scheduledCallerColor removed
                                            CallManager.scheduleCall(
                                                context,
                                                delaySec,
                                                CallData(
                                                    callerName = user.name,
                                                    callerSubtitle = "Bejövő Videó Hívás",
                                                    callType = CallType.VIDEO,
                                                    callerAvatarHexColor = user.avatarColor
                                                )
                                            )
                                            Toast.makeText(context, "$delaySec mp múlva szimulált hívás indul! Zárold le vagy lépj ki a teszthez!", Toast.LENGTH_LONG).show()
                                        },
                                        onAcceptMessageRequest = {
                                            chatViewModel.acceptMessageRequest(user.id)
                                        },
                                        onDeclineMessageRequest = {
                                            chatViewModel.rejectMessageRequest(user.id)
                                        },
                                        onUpdateCustomCall = { ringtone, vibration ->
                                            chatViewModel.updateCustomCallSettings(user.id, ringtone, vibration)
                                        }
                                    )
                                } else {
                                    // Main tab views
                                    if (schedulerTimeLeft > 0) {
                                        CountdownIndicator(secondsLeft = schedulerTimeLeft, callerName = scheduledCallerName)
                                    }

                                    when (currentTab) {
                                        "chats" -> ChatsTabScreen(
                                            users = allUsers.filter { user -> 
                                                user.isFriend || allMessages.any { msg -> 
                                                    (msg.senderId == user.id && msg.receiverId == "me") || 
                                                    (msg.senderId == "me" && msg.receiverId == user.id) 
                                                }
                                            },
                                            allMessages = allMessages,
                                            onUserSelect = { chatViewModel.selectUser(it) }
                                        )
                                        "friends" -> FriendsTabScreen(
                                            allUsers = allUsers,
                                            onSendFriendRequest = { chatViewModel.sendFriendRequest(it) },
                                            onAcceptFriendRequest = { chatViewModel.acceptFriendRequest(it) },
                                            onRejectFriendRequest = { chatViewModel.rejectFriendRequest(it) },
                                            onChatSelect = { chatViewModel.selectUser(it) }
                                        )
                                        "requests" -> RequestsTabScreen(
                                            allUsers = allUsers,
                                            allMessages = allMessages,
                                            onSelectUser = { chatViewModel.selectUser(it) }
                                        )
                                        "settings" -> SettingsTabScreen(
                                            settings = settings,
                                            onStealthChange = { chatViewModel.updateStealthMode(it) },
                                            onProfileSave = { name, avatar -> chatViewModel.updateUserProfile(name, avatar) }
                                        )
                                    }
                                }
                            }
                        }

                        // Floating Top-Down Heads-Up Call Notification Banner (like Facebook Messenger)
                        AnimatedVisibility(
                            visible = callStatus == CallStatus.INCOMING && currentCall != null,
                            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(12.dp)
                                .align(Alignment.TopCenter)
                                .zIndex(100f)
                        ) {
                            currentCall?.let { callData ->
                                HeadsUpCallBanner(
                                    callData = callData,
                                    onAccept = {
                                        scope.launch {
                                            if (com.example.data.FirebaseService.isUserLoggedIn.value) {
                                                com.example.data.FirebaseService.acceptRealtimeCall(context)
                                            } else {
                                                CallManager.answerCall(context)
                                            }
                                            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    onDecline = {
                                        if (com.example.data.FirebaseService.isUserLoggedIn.value) {
                                            com.example.data.FirebaseService.declineRealtimeCall(context)
                                        } else {
                                            CallManager.declineCall(context)
                                        }
                                    },
                                    chatViewModel = chatViewModel
                                )
                            }
                        }
                    }
                }
                } // Ends the auth else block
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            isNotificationPermissionGranted.value = isGranted
            if (!isGranted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            isNotificationPermissionGranted.value = true
        }
    }
}

@Composable
fun CountdownIndicator(secondsLeft: Int, callerName: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Hívás érkezik '$callerName' személytől $secondsLeft mp múlva... Lépj ki vagy zárd le!",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ChatsTabScreen(
    users: List<User>,
    allMessages: List<DbMessage>,
    onUserSelect: (User) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredUsers = users.filter { it.name.contains(searchQuery, ignoreCase = true) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Keresés", color = Color.White.copy(alpha = 0.5f)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White.copy(alpha = 0.5f)) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1E293B),
                unfocusedContainerColor = Color(0xFF1E293B),
                focusedBorderColor = Color(0xFF0084FF),
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        // Simulated Heads-up call test bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .background(Color(0xFF0084FF).copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, Color(0xFF0084FF).copy(alpha = 0.25f)), shape = RoundedCornerShape(12.dp))
                .clickable {
                    CallManager.triggerIncomingCall(
                        context,
                        CallData(
                            callerName = "Olyna",
                            callerSubtitle = "Csörög...",
                            callType = CallType.VIDEO,
                            callerAvatarHexColor = 0xFFEC4899
                        )
                    )
                    Toast.makeText(context, "Leugró hívás teszt elindítva!", Toast.LENGTH_SHORT).show()
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFF0084FF), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Kattints ide a leugró hívás TESZTELÉSÉHEZ! ⚡", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Szimulál egy bejövő Messenger hívást felülről lecsúszva.", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Active horizontal friends status bar (standard Messenger feature)
        Text(
            text = "Aktív Ismerősök",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(users.filter { it.status == "Aktív most" || it.status == "Online" }) { onlineUser ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onUserSelect(onlineUser) }
                ) {
                    UserAvatar(
                        name = onlineUser.name,
                        avatarColor = onlineUser.avatarColor,
                        avatarUrl = onlineUser.avatarUrl,
                        size = 54.dp,
                        showActiveDot = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = onlineUser.name.split(" ").firstOrNull() ?: "",
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(54.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Divider(color = Color.White.copy(alpha = 0.1f))

        // Csevegések List
        Text(
            text = "Csevegések",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )

        if (filteredUsers.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Forum, contentDescription = null, tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Nincsenek aktív csevegések", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filteredUsers) { user ->
                    val userMessages = allMessages.filter {
                        (it.senderId == "me" && it.receiverId == user.id) ||
                        (it.senderId == user.id && it.receiverId == "me")
                    }
                    val lastMsg = userMessages.lastOrNull()
                    val hasUnread = lastMsg != null && !lastMsg.isRead && lastMsg.senderId != "me"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUserSelect(user) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar bubble with active statuses
                        UserAvatar(
                            name = user.name,
                            avatarColor = user.avatarColor,
                            avatarUrl = user.avatarUrl,
                            size = 54.dp,
                            showActiveDot = (user.status == "Aktív most" || user.status == "Online")
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Message detail columns
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.name,
                                fontWeight = if (hasUnread) FontWeight.Black else FontWeight.Bold,
                                color = if (hasUnread) Color.White else Color.White.copy(alpha = 0.9f),
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = lastMsg?.content ?: "Kezdjetek el írogatni!",
                                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                                color = if (hasUnread) Color(0xFF0084FF) else Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right Status Indicator
                        Column(horizontalAlignment = Alignment.End) {
                            val timeText = if (user.status == "Aktív most" || user.status == "Online") "Most" else "Aktív"
                            Text(
                                text = timeText,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                            if (hasUnread) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .size(10.dp)
                                        .background(Color(0xFF0084FF), shape = CircleShape)
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
fun FriendsTabScreen(
    allUsers: List<User>,
    onSendFriendRequest: (String) -> Unit,
    onAcceptFriendRequest: (String) -> Unit,
    onRejectFriendRequest: (String) -> Unit,
    onChatSelect: (User) -> Unit
) {
    var selectedSubTab by remember { mutableStateOf("összes") } // összes, jelölések, felfedezés

    val friends = allUsers.filter { it.isFriend && !it.isSelf }
    val receivedRequests = allUsers.filter { !it.isFriend && it.isRequestReceived }
    val sentRequests = allUsers.filter { !it.isFriend && it.isRequestSent }
    val discoverableUsers = allUsers.filter { !it.isFriend && !it.isRequestReceived && !it.isRequestSent && !it.isSelf }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = if (selectedSubTab == "összes") 0 else if (selectedSubTab == "jelölések") 1 else 2,
            containerColor = Color(0xFF1E293B),
            contentColor = Color(0xFF0084FF)
        ) {
            Tab(
                selected = selectedSubTab == "összes",
                onClick = { selectedSubTab = "összes" },
                text = { Text("Ismerősök (${friends.size})", fontWeight = FontWeight.Bold, color = Color.White) }
            )
            Tab(
                selected = selectedSubTab == "jelölések",
                onClick = { selectedSubTab = "jelölések" },
                text = { 
                    BadgedBox(badge = {
                        if (receivedRequests.isNotEmpty()) {
                            Badge(containerColor = Color.Red) { Text(receivedRequests.size.toString()) }
                        }
                    }) {
                        Text("Kérések", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            )
            Tab(
                selected = selectedSubTab == "felfedezés",
                onClick = { selectedSubTab = "felfedezés" },
                text = { Text("Keresés", fontWeight = FontWeight.Bold, color = Color.White) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedSubTab) {
                "összes" -> {
                    if (friends.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                Text("Nincs még felvett ismerősöd. Jelölj be másokat!", color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    } else {
                        items(friends) { friend ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onChatSelect(friend) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    name = friend.name,
                                    avatarColor = friend.avatarColor,
                                    avatarUrl = friend.avatarUrl,
                                    size = 50.dp,
                                    showActiveDot = (friend.status == "Aktív most")
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(friend.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(friend.status, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                "jelölések" -> {
                    if (receivedRequests.isEmpty() && sentRequests.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                Text("Nincsenek függőben lévő jelöléseid.", color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    } else {
                        if (receivedRequests.isNotEmpty()) {
                            item {
                                Text("Kapott jelölések", color = Color(0xFF0084FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            items(receivedRequests) { req ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UserAvatar(
                                            name = req.name,
                                            avatarColor = req.avatarColor,
                                            avatarUrl = req.avatarUrl,
                                            size = 46.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(req.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(
                                            onClick = { onAcceptFriendRequest(req.id) },
                                            modifier = Modifier.background(Color(0xFF22C55E), shape = CircleShape).size(36.dp)
                                        ) {
                                            Icon(Icons.Filled.Check, contentDescription = "Accept", tint = Color.White)
                                        }
                                        IconButton(
                                            onClick = { onRejectFriendRequest(req.id) },
                                            modifier = Modifier.background(Color(0xFFEF4444), shape = CircleShape).size(36.dp)
                                        ) {
                                            Icon(Icons.Filled.Close, contentDescription = "Decline", tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        if (sentRequests.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text("Elküldött jelölések", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            items(sentRequests) { req ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UserAvatar(
                                            name = req.name,
                                            avatarColor = req.avatarColor,
                                            avatarUrl = req.avatarUrl,
                                            size = 46.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(req.name, color = Color.White)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Függőben", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                        TextButton(onClick = { onRejectFriendRequest(req.id) }) {
                                            Text("Visszavonás", color = Color(0xFFEF4444), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "felfedezés" -> {
                    if (discoverableUsers.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                Text("Nincs több bejelölhető felhasználó.", color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    } else {
                        items(discoverableUsers) { user ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).clickable { onChatSelect(user) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UserAvatar(
                                        name = user.name,
                                        avatarColor = user.avatarColor,
                                        avatarUrl = user.avatarUrl,
                                        size = 46.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(user.name, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { onSendFriendRequest(user.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Jelölés", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RequestsTabScreen(
    allUsers: List<User>,
    allMessages: List<DbMessage>,
    onSelectUser: (User) -> Unit
) {
    val requestsList = allUsers.filter { !it.isFriend && !it.isSelf }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = Color(0xFF0084FF))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Titkos adatvédelem aktív", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ha megnézel egy engedélykérést, a feladó nem fogja látni hogy elolvastad vagy fent vagy-e egészen addig, amíg el nem fogadod a kapcsolatfelvételt.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Text("Engedélykérések csatornák", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        // Query messages that are not accepted on user relations
        val usersWithRequest = requestsList.filter { u ->
            val uMsgs = allMessages.filter { it.senderId == u.id && it.receiverId == "me" }
            uMsgs.any { !it.isAccepted }
        }

        if (usersWithRequest.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Nincsenek beérkező engedélykérések", color = Color.White.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(usersWithRequest) { user ->
                    val lastMsg = allMessages.filter { it.senderId == user.id && it.receiverId == "me" }.lastOrNull()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B), shape = RoundedCornerShape(10.dp))
                            .clickable { onSelectUser(user) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            name = user.name,
                            avatarColor = user.avatarColor,
                            avatarUrl = user.avatarUrl,
                            size = 46.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(lastMsg?.content ?: "Kapcsolatfelvételi kísérlet", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTabScreen(
    settings: UserSettings,
    onStealthChange: (Boolean) -> Unit,
    onProfileSave: (String, String?) -> Unit
) {
    val initialSplit = remember(settings.currentUserName) {
        val parts = settings.currentUserName.split(" ", limit = 2)
        val last = parts.firstOrNull() ?: ""
        val first = if (parts.size > 1) parts[1] else ""
        Pair(last, first)
    }

    var lastNameInput by remember(initialSplit) { mutableStateOf(initialSplit.first) }
    var firstNameInput by remember(initialSplit) { mutableStateOf(initialSplit.second) }
    var selectedAvatarUrl by remember(settings.avatarUrl) { mutableStateOf(settings.avatarUrl) }

    val presetAvatars = listOf(
        null, // No photo (fallback to text initials)
        "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=150&q=80", // Olyna
        "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=150&q=80", // Szilárd
        "https://images.unsplash.com/photo-1570295999919-56ceb5ecca61?auto=format&fit=crop&w=150&q=80",  // Elegant look
        "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?auto=format&fit=crop&w=150&q=80"   // Classic look
    )

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Saját Profil Beállítások", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)

        // Main User Profile Showcase Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Interactive active profile showcase avatar
                UserAvatar(
                    name = "$lastNameInput $firstNameInput".trim().ifEmpty { "Én" },
                    avatarColor = 0xFF6366F1,
                    avatarUrl = selectedAvatarUrl,
                    size = 80.dp,
                    showActiveDot = true,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                )

                Text(
                    text = "$lastNameInput $firstNameInput".trim().ifEmpty { "Névtelen Felhasználó" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Text(
                    text = "ProfilKép Kiválasztása:",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start)
                )

                // presets selection row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presetAvatars.forEach { url ->
                        val isSelected = selectedAvatarUrl == url
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color(0xFF0084FF) else Color.Transparent)
                                .clickable { selectedAvatarUrl = url }
                                .padding(if (isSelected) 3.dp else 0.dp)
                                .clip(CircleShape)
                        ) {
                            if (url != null) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF6366F1)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lastNameInput.firstOrNull()?.toString()?.uppercase() ?: "É",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Editable full name input detail card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Meta Profil Adatok Szerkesztése", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                // Last name field
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Vezetéknév", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = lastNameInput,
                        onValueChange = { lastNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0084FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }

                // First name field
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Keresztnév", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = firstNameInput,
                        onValueChange = { firstNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0084FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        val fullName = "$lastNameInput $firstNameInput".trim()
                        if (fullName.isNotEmpty()) {
                            onProfileSave(fullName, selectedAvatarUrl)
                            Toast.makeText(context, "Profil sikeresen frissítve!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Kérlek adj meg egy érvényes nevet!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mentés", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Stealth mode controller
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Láthatatlan Mód (Stealth Mode)", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Kapcsold be, és mások nem fogják látni, ha elolvastad az üzenetet (\"Megtekintve\") vagy jelenleg felelsz-e.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = settings.isStealthMode,
                        onCheckedChange = { onStealthChange(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF0084FF))
                    )
                }
            }
        }

        // Floating Call Overlay Permission Card
        val isOverlayGranted = remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isOverlayGranted) {
                        try {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            context.startActivity(intent)
                        }
                    } else {
                        Toast.makeText(context, "A lebegő hívásablak engedélye már aktív! 🌟", Toast.LENGTH_SHORT).show()
                    }
                }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Lebegő Hívásablak Overlay",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isOverlayGranted) Color(0xFF22C55E).copy(alpha = 0.15f)
                                        else Color(0xFFF59E0B).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isOverlayGranted) "Engedélyezve" else "Beállítás szükséges",
                                    color = if (isOverlayGranted) Color(0xFF22C55E) else Color(0xFFF59E0B),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Engedélyezd a megjelenítést más alkalmazások felett a Facebook Messenger stílusú lebegő híváspanelhez.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Layers,
                        contentDescription = null,
                        tint = if (isOverlayGranted) Color(0xFF0084FF) else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Background WebRTC + Firebase server status card
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF22C55E), shape = CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Render WebRTC és Firebase szerver", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text("Backend Host: https://olyna-messenger.onrender.com", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                Text("Firebase Config Status: AKTÍV csevegéshez", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }

        // Log out card in SettingsTabScreen if logged into Firebase
        val isFirebaseLoggedIn = com.example.data.FirebaseService.isUserLoggedIn.collectAsState()
        if (isFirebaseLoggedIn.value) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.12f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        com.example.data.FirebaseService.logout(context)
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(intent)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.ExitToApp,
                            contentDescription = "Logout",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Kijelentkezés a Fiókból", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Bontja a valós idejű Firebase szinkronizációt.", color = Color(0xFFFCA5A5).copy(alpha = 0.6f), fontSize = 10.sp)
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFFEF4444).copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatDetailScreen(
    user: User,
    messages: List<DbMessage>,
    stealthActive: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onTriggerImmediateCall: () -> Unit,
    onTriggerScheduledCall: (Int) -> Unit,
    onAcceptMessageRequest: () -> Unit,
    onDeclineMessageRequest: () -> Unit,
    onUpdateCustomCall: (String, String) -> Unit = { _, _ -> }
) {
    var textInput by remember { mutableStateOf("") }
    var showCustomCallDialog by remember { mutableStateOf(false) }
    
    // Only block input with accept/decline choices if we have incoming UNACCEPTED messages
    val hasIncomingUnaccepted = remember(messages) {
        messages.any { it.senderId == user.id && it.receiverId == "me" && !it.isAccepted }
    }
    val isRequest = !user.isFriend && hasIncomingUnaccepted
    
    // Limit to load max 100 messages initially ("száz beszélgetés után ne töltse be a regényeket csak ha visszamegyünk")
    var messageLimit by remember { mutableStateOf(100) }
    
    LaunchedEffect(user.id) {
        messageLimit = 100
    }

    val displayedMessages = remember(messages, messageLimit) {
        messages.takeLast(messageLimit)
    }

    val listState = rememberLazyListState()

    // Scroll to bottom when opening the chat
    LaunchedEffect(user.id) {
        if (displayedMessages.isNotEmpty()) {
            listState.scrollToItem(displayedMessages.size - 1)
        }
    }

    // Scroll to bottom when a new message is sent or received (meaning the absolute last message ID changes)
    var lastMsgId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(messages) {
        val lastId = messages.lastOrNull()?.id
        if (lastId != null && lastId != lastMsgId) {
            listState.animateScrollToItem(displayedMessages.size - 1)
            lastMsgId = lastId
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Appbar header styled like Facebook Messenger
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color(0xFF0084FF))
            }

            UserAvatar(
                name = user.name,
                avatarColor = user.avatarColor,
                avatarUrl = user.avatarUrl,
                size = 38.dp,
                showActiveDot = (user.status == "Aktív most" && !isRequest)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Calling action buttons placed directly on Top Bar like standard Facebook Messenger
            if (user.isFriend) {
                val context = LocalContext.current
                // Direct Audio Call Button
                IconButton(onClick = {
                    if (com.example.data.FirebaseService.isUserLoggedIn.value) {
                        com.example.data.FirebaseService.startRealtimeCall(context, user.id, user.name, CallType.VOICE)
                    } else {
                        CallManager.triggerIncomingCall(
                            context,
                            CallData(
                                callerName = user.name,
                                callerSubtitle = "Bejövő Hang Hívás",
                                callType = CallType.VOICE,
                                callerAvatarHexColor = user.avatarColor
                            )
                        )
                    }
                }) {
                    Icon(Icons.Rounded.Call, contentDescription = "Hanghívás", tint = Color(0xFF0084FF))
                }

                // Direct Video Call Button
                IconButton(onClick = {
                    if (com.example.data.FirebaseService.isUserLoggedIn.value) {
                        com.example.data.FirebaseService.startRealtimeCall(context, user.id, user.name, CallType.VIDEO)
                    } else {
                        CallManager.triggerIncomingCall(
                            context,
                            CallData(
                                callerName = user.name,
                                callerSubtitle = "Bejövő Videó Hívás",
                                callType = CallType.VIDEO,
                                callerAvatarHexColor = user.avatarColor
                            )
                        )
                    }
                }) {
                    Icon(Icons.Rounded.VideoCall, contentDescription = "Videóhívás", tint = Color(0xFF0084FF))
                }

                // Info Menu for test utilities
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "Infó", tint = Color(0xFF0084FF))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1E293B))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Hívás indítása 📞", color = Color.White, fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                onTriggerImmediateCall()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hívás 1s múlva ⏰", color = Color.White, fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                onTriggerScheduledCall(1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hívás 5s múlva ⏰", color = Color.White, fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                onTriggerScheduledCall(5)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Egyedi hang & rezgés 🎵", color = Color.White, fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                showCustomCallDialog = true
                            }
                        )
                    }
                }
            }
        }

        // Custom Call Settings Dialog
        if (showCustomCallDialog) {
            val context = LocalContext.current
            var selectedRingtone by remember { mutableStateOf(user.customRingtone) }
            var selectedVibration by remember { mutableStateOf(user.customVibration) }
            var previewMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

            // File picker for mp3
            val mp3PickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val targetFile = File(context.filesDir, "custom_ringtone_${user.id}.mp3")
                            targetFile.outputStream().use { output ->
                                inputStream.copyTo(output)
                            }
                            selectedRingtone = "Saját MP3 (.mp3)"
                            Toast.makeText(context, "Egyedi csengőhang betöltve! 🎉", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("Ringtone", "Error copying mp3 file", e)
                        Toast.makeText(context, "Hiba az MP3 másolása közben: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    try {
                        previewMediaPlayer?.stop()
                        previewMediaPlayer?.release()
                        previewMediaPlayer = null
                    } catch (e: Exception) {}
                }
            }

            AlertDialog(
                onDismissRequest = { showCustomCallDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFF0084FF))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Egyedi hívásbeállítások", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "${user.name} számára egyedi csengőhangot és rezgési mintát állíthatsz be.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )

                        // Ringtone Selection Card
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Csengőhang dallama", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val ringtones = listOf("Alapértelmezett", "Klasszikus dallam", "Neon dallam", "Lágy ütem", "Szirén csengő", "Saját MP3 (.mp3)")
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ringtones.forEach { r ->
                                    val active = selectedRingtone == r
                                    FilterChip(
                                        selected = active,
                                        onClick = { 
                                            if (r == "Saját MP3 (.mp3)") {
                                                val hasFile = File(context.filesDir, "custom_ringtone_${user.id}.mp3").exists()
                                                if (!hasFile) {
                                                    mp3PickerLauncher.launch("audio/*")
                                                } else {
                                                    selectedRingtone = r
                                                }
                                            } else {
                                                selectedRingtone = r 
                                            }
                                        },
                                        label = { Text(r, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF0084FF),
                                            selectedLabelColor = Color.White,
                                            containerColor = Color(0xFF1E293B),
                                            labelColor = Color.White.copy(alpha = 0.6f)
                                        )
                                    )
                                }
                            }

                            val hasExistMp3 = remember(selectedRingtone) { 
                                mutableStateOf(File(context.filesDir, "custom_ringtone_${user.id}.mp3").exists()) 
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (hasExistMp3.value) "✅ Van feltöltött MP3 fájl" else "❌ Nincs egyedi MP3 feltöltve",
                                    color = if (hasExistMp3.value) Color(0xFF10B981) else Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp
                                )
                                TextButton(
                                    onClick = { mp3PickerLauncher.launch("audio/*") },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("MP3 csere...", color = Color(0xFF38BDF8), fontSize = 12.sp)
                                }
                            }
                        }

                        // Vibration Selection Card
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Rezgési ritmus", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val vibrations = listOf("Alapértelmezett", "Szuper Gyors", "Szívverés", "SOS Jelzés", "Egyenletes Hosszú")
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                vibrations.forEach { v ->
                                    val active = selectedVibration == v
                                    FilterChip(
                                        selected = active,
                                        onClick = { selectedVibration = v },
                                        label = { Text(v, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF0084FF),
                                            selectedLabelColor = Color.White,
                                            containerColor = Color(0xFF1E293B),
                                            labelColor = Color.White.copy(alpha = 0.6f)
                                        )
                                    )
                                }
                            }
                        }

                        // Preview Button
                        Button(
                            onClick = {
                                Toast.makeText(context, "Minta hanglejátszás és rezgés...", Toast.LENGTH_SHORT).show()
                                
                                // Clean up activeMediaPlayer if exists
                                try {
                                    previewMediaPlayer?.stop()
                                    previewMediaPlayer?.release()
                                    previewMediaPlayer = null
                                } catch (ex: Exception) {}

                                if (selectedRingtone == "Saját MP3 (.mp3)") {
                                    try {
                                        val file = File(context.filesDir, "custom_ringtone_${user.id}.mp3")
                                        if (file.exists()) {
                                            val mp = MediaPlayer().apply {
                                                setDataSource(file.absolutePath)
                                                prepare()
                                                start()
                                            }
                                            previewMediaPlayer = mp
                                            // Auto-stop preview after 4 seconds
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                try {
                                                    mp.stop()
                                                    mp.release()
                                                    if (previewMediaPlayer == mp) {
                                                        previewMediaPlayer = null
                                                    }
                                                } catch (ex: Exception) {}
                                            }, 4000)
                                        } else {
                                            Toast.makeText(context, "Nincsen egyedi MP3 fájl feltöltve ehhez a felhasználóhoz!", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Preview", "Error playing custom mp3 preview", e)
                                    }
                                } else if (selectedRingtone != "Alapértelmezett") {
                                    try {
                                        val gen = android.media.ToneGenerator(android.media.AudioManager.STREAM_RING, 100)
                                        when (selectedRingtone) {
                                            "Klasszikus dallam" -> gen.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 600)
                                            "Neon dallam" -> {
                                                gen.startTone(android.media.ToneGenerator.TONE_DTMF_D, 150)
                                            }
                                            "Lágy ütem" -> gen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 300)
                                            "Szirén csengő" -> gen.startTone(android.media.ToneGenerator.TONE_SUP_ERROR, 350)
                                        }
                                        // Auto release after sound finishes
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            try { gen.release() } catch (ex: Exception) {}
                                        }, 1000)
                                    } catch (e: Exception) {
                                        Log.e("Preview", "Error playing tone", e)
                                    }
                                } else {
                                    try {
                                        val gen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
                                        gen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            try { gen.release() } catch (ex: Exception) {}
                                        }, 500)
                                    } catch (e: Exception) {}
                                }
                                
                                // Vibration preview
                                try {
                                    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                    val pat = when (selectedVibration) {
                                        "Szuper Gyors" -> longArrayOf(0, 150, 150, 150)
                                        "Szívverés" -> longArrayOf(0, 150, 150, 150, 400, 150, 150, 150)
                                        "SOS Jelzés" -> longArrayOf(0, 200, 100, 200, 300, 400, 100, 400)
                                        "Egyenletes Hosszú" -> longArrayOf(0, 600, 200, 600)
                                        else -> longArrayOf(0, 400)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vib?.vibrate(android.os.VibrationEffect.createWaveform(pat, -1))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vib?.vibrate(pat, -1)
                                    }
                                } catch (e: Exception) {}
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = Color(0xFF10B981))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tesztelés 🔊", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onUpdateCustomCall(selectedRingtone, selectedVibration)
                            showCustomCallDialog = false
                            Toast.makeText(context, "Sikeresen elmentve!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF))
                    ) {
                        Text("Mentés", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomCallDialog = false }) {
                        Text("Mégse", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF111827)
            )
        }

        // Action banners for requests or stealth
        if (isRequest) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B).copy(alpha = 0.8f))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Privát kuckó: Az elolvasás és online státusz információk rejtve maradnak.",
                    color = Color(0xFFF472B6),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else if (stealthActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFD97706).copy(alpha = 0.2f))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Láthatatlan Mód (Stealth) jelenleg Aktív!", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Messages Bubble Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
        ) {
            // Profile Info Header inside list scroll content (so it scrolls with chat exactly as in Messenger)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    UserAvatar(
                        name = user.name,
                        avatarColor = user.avatarColor,
                        avatarUrl = user.avatarUrl,
                        size = 96.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = user.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF242526))
                            .clickable { /* open profile view or details */ }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Profil megtekintése",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = buildAnnotatedString {
                            append("🔒 Az üzeneteket és a hívásokat végpontok közötti titkosítás védi. Csak az ebben a chatben részt vevők tudják elolvasni, meghallgatni vagy megosztani őket. ")
                            withStyle(style = androidx.compose.ui.text.SpanStyle(color = Color(0xFF0084FF))) {
                                append("További információ")
                            }
                        },
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "11:43",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            // Load more option if there are more older messages
            if (messages.size > messageLimit) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { messageLimit += 100 }) {
                            Text(
                                "Korábbi üzenetek betöltése...",
                                color = Color(0xFF0084FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            items(displayedMessages.size) { index ->
                val msg = displayedMessages[index]
                val isMe = msg.senderId == "me"
                val isLastMsg = index == displayedMessages.size - 1
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (!isMe) {
                            UserAvatar(
                                name = user.name,
                                avatarColor = user.avatarColor,
                                avatarUrl = user.avatarUrl,
                                size = 28.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Box(
                            modifier = Modifier
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 18.dp,
                                        topEnd = 18.dp,
                                        bottomStart = if (isMe) 18.dp else 4.dp,
                                        bottomEnd = if (isMe) 4.dp else 18.dp
                                    )
                                )
                                .background(if (isMe) Color(0xFF0084FF) else Color(0xFF242526))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(msg.content, color = Color.White, fontSize = 15.sp)
                        }
                    }
                    if (isMe && isLastMsg) {
                        Text(
                            text = "Elküldve",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                        )
                    }
                }
            }
        }

        // Bottom Controls interface (Dynamic Input or Friend Choice actions)
        if (isRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Szeretnéd fogadni a beszélgetést tőle: ${user.name}?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDeclineMessageRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Elutasítás", color = Color.White)
                    }
                    Button(
                        onClick = onAcceptMessageRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Elfogadás", color = Color.White)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (textInput.isEmpty()) {
                    IconButton(onClick = { /* plus attachments */ }) {
                        Icon(Icons.Filled.AddCircle, contentDescription = "Csatolmányok", tint = Color(0xFF0084FF))
                    }
                    IconButton(onClick = { /* camera */ }) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Kamera", tint = Color(0xFF0084FF))
                    }
                    IconButton(onClick = { /* gallery */ }) {
                        Icon(Icons.Filled.Image, contentDescription = "Képek", tint = Color(0xFF0084FF))
                    }
                    IconButton(onClick = { /* microphone */ }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Hang", tint = Color(0xFF0084FF))
                    }
                } else {
                    IconButton(onClick = { /* expand chevron */ }) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Továbbiak", tint = Color(0xFF0084FF))
                    }
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Üzenet", color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    trailingIcon = {
                        IconButton(onClick = { /* emoji list */ }) {
                            Icon(Icons.Filled.EmojiEmotions, contentDescription = "Hangulatjelek", tint = Color(0xFF0084FF))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF242526),
                        unfocusedContainerColor = Color(0xFF242526),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (textInput.isEmpty()) {
                    IconButton(onClick = { onSendMessage("👍") }) {
                        Icon(Icons.Filled.ThumbUp, contentDescription = "Lájk", tint = Color(0xFF0084FF))
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendMessage(textInput)
                                textInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Send, contentDescription = "Küldés", tint = Color(0xFF0084FF))
                    }
                }
            }
        }
    }
}

@Composable
fun UserAvatar(
    name: String,
    avatarColor: Long,
    avatarUrl: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    showActiveDot: Boolean = false,
    showMessengerBadge: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        contentAlignment = Alignment.BottomEnd,
        modifier = modifier
            .then(clickModifier)
            .size(size)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(avatarColor)),
            contentAlignment = Alignment.Center
        ) {
            if (!avatarUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val initials = name.split(" ")
                    .mapNotNull { it.firstOrNull()?.toString() }
                    .take(2)
                    .joinToString("")
                    .uppercase()
                    .ifEmpty { "?" }
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.38f).sp
                )
            }
        }

        if (showActiveDot) {
            Box(
                modifier = Modifier
                    .size((size.value * 0.28f).coerceAtLeast(10f).dp)
                    .background(Color(0xFF22C55E), shape = CircleShape)
                    .border(1.5.dp, Color(0xFF0F172A), CircleShape)
            )
        } else if (showMessengerBadge) {
            // Messenger Blue circle badge containing lightning icon on bottom right
            Box(
                modifier = Modifier
                    .size((size.value * 0.42f).coerceAtLeast(16f).dp)
                    .background(Color(0xFF0084FF), shape = CircleShape)
                    .border(1.5.dp, Color(0xFF1E293B), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Chat,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size((size.value * 0.24f).coerceAtLeast(10f).dp)
                )
            }
        }
    }
}

@Composable
fun VoiceEqualizerWaves(color: Color = Color(0xFF0084FF)) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    
    // Create 14 animated heights
    val animations = (0 until 14).map { index ->
        val duration = remember(index) { (500 + (index % 4) * 120) }
        val startDelay = remember(index) { (index % 3) * 80 }
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, delayMillis = startDelay, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        animations.forEach { anim ->
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = (4.dp + (12.dp * anim.value)))
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun HeadsUpCallBanner(
    callData: CallData,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    chatViewModel: ChatViewModel
) {
    var showQuickReplies by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val quickRepliesList = listOf(
        "Szia! Most nem alkalmas, később hívlak! 📞",
        "Éppen úton vagyok, sietek! 🏎️",
        "Írj inkább itt üzenetben! 💬",
        "Szia, most megbeszélésem van. 👔"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xE60F172A)), // Semi-translucent dark slate (90% opacity)
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.5.dp, Color(0xFF1E293B)), // Polished thin edge
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(26.dp))
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main Control Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Left avatar layout with overlapping blue active call badge
                Box(
                    modifier = Modifier.size(48.dp)
                ) {
                    UserAvatar(
                        name = callData.callerName,
                        avatarColor = callData.callerAvatarHexColor,
                        avatarUrl = if (callData.callerName == "Olyna") "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=150&q=80" else null,
                        size = 48.dp,
                        showMessengerBadge = false
                    )
                    
                    // Small Blue Active Call Icon Badge (replicates bottom-right badge in reference image)
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(Color(0xFF0084FF))
                            .border(1.dp, Color(0xFF0F172A), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 2. Middle Texts: Name, Subtitle, and Soundwaves
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = callData.callerName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (callData.callType == CallType.VIDEO) "Bejövő videóhívás" else "Bejövő hanghívás",
                        color = Color(0xFF94A3B8), // slate-400
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    
                    // Live Audio Equalizer Wave Animation (from the design picture!)
                    VoiceEqualizerWaves()
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 3. Right side Button Row: Chat Shortcut, Decline Call, Accept Call
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Chat option toggles quick replies list below
                    IconButton(
                        onClick = { showQuickReplies = !showQuickReplies },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Message,
                            contentDescription = "Gyorsválasz",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Decline Button (Red)
                    IconButton(
                        onClick = onDecline,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFFEF4444) // Sweet Red
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallEnd,
                            contentDescription = "Elutasítás",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Accept Button (Green)
                    IconButton(
                        onClick = onAccept,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFF22C55E) // Bright Green
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = "Felvétel",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Expandable panel for Quick Replies sends text & automatically declines call
            if (showQuickReplies) {
                Divider(color = Color.White.copy(alpha = 0.08f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Küldj válaszüzenetet és utasítsd el:",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Mégse",
                            color = Color(0xFF0084FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { showQuickReplies = false }
                                .padding(4.dp)
                        )
                    }

                    quickRepliesList.forEach { reply ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(8.dp))
                                .clickable {
                                    onDecline()
                                    val callerId = if (callData.callerName == "Olyna") "olyna" else "szilard"
                                    scope.launch {
                                        val repo = ChatRepository(context.applicationContext as android.app.Application)
                                        repo.sendMessage(callerId, reply, isAccepted = true)
                                        Toast.makeText(context, "Sikeresen megválaszolva és elutasítva!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = reply, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
