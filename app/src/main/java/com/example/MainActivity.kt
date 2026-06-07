package com.example

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkNotificationPermission()

        setContent {
            MyApplicationTheme {
                // Initialize modern Messenger ViewModel with local Room persistence
                val context = LocalContext.current
                val application = context.applicationContext as android.app.Application
                val chatViewModel = remember { ChatViewModel(application) }
                val scope = rememberCoroutineScope()

                val allUsers by chatViewModel.allUsers.collectAsState()
                val allMessages by chatViewModel.allMessages.collectAsState()
                val settings by chatViewModel.settings.collectAsState()
                val selectedUser by chatViewModel.selectedUser.collectAsState()
                val activeChatMessages by chatViewModel.activeChatMessages.collectAsState()

                val callStatus by CallManager.callStatus.collectAsState()
                val currentCall by CallManager.currentCall.collectAsState()

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
                                            if (user.isFriend) {
                                                chatViewModel.sendAndReceiveMockReply(content)
                                            } else {
                                                chatViewModel.sendMessage(content)
                                            }
                                        },
                                        onTriggerImmediateCall = {
                                            CallManager.triggerIncomingCall(
                                                context,
                                                CallData(
                                                    callerName = user.name,
                                                    callerSubtitle = "Bejövő Videó Hívás",
                                                    callType = CallType.VIDEO,
                                                    callerAvatarHexColor = user.avatarColor
                                                )
                                            )
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
                                        }
                                    )
                                } else {
                                    // Main tab views
                                    if (schedulerTimeLeft > 0) {
                                        CountdownIndicator(secondsLeft = schedulerTimeLeft, callerName = scheduledCallerName)
                                    }

                                    when (currentTab) {
                                        "chats" -> ChatsTabScreen(
                                            users = allUsers.filter { it.isFriend },
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
                                            CallManager.answerCall(context)
                                            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    onDecline = {
                                        CallManager.declineCall(context)
                                    },
                                    chatViewModel = chatViewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isNotificationPermissionGranted.value = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
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
                                    Text("Függőben", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
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
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text("Signaling Token ID: b488-95ce-41b0-8a5a", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp)
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
    onDeclineMessageRequest: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val isRequest = !user.isFriend

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // Appbar header styled like Facebook Messenger
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B))
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
                                                size = 40.dp,
                                                showActiveDot = (user.status == "Aktív most" && !isRequest)
                                            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(user.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (isRequest) "Engedélykérés" else if (stealthActive) "Láthatatlan hívó" else user.status,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }

            // Quick Call Triggers
            if (!isRequest) {
                IconButton(onClick = onTriggerImmediateCall) {
                    Icon(Icons.Rounded.Videocam, contentDescription = "Hívás most", tint = Color(0xFF0084FF))
                }
                IconButton(onClick = { onTriggerScheduledCall(5) }) {
                    Icon(Icons.Rounded.Schedule, contentDescription = "Hívás 5s mulva", tint = Color(0xFF0084FF))
                }
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isMe = msg.senderId == "me"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    if (!isMe) {
                        UserAvatar(
                            name = user.name,
                            avatarColor = user.avatarColor,
                            avatarUrl = user.avatarUrl,
                            size = 28.dp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (isMe) 16.dp else 4.dp,
                                    bottomEnd = if (isMe) 4.dp else 16.dp
                                )
                            )
                            .background(if (isMe) Color(0xFF0084FF) else Color(0xFF334155))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(msg.content, color = Color.White, fontSize = 14.sp)
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
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.EmojiEmotions, contentDescription = null, tint = Color(0xFF0084FF), modifier = Modifier.padding(horizontal = 6.dp))
                Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFF0084FF), modifier = Modifier.padding(horizontal = 6.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Üzenet írása...", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedBorderColor = Color(0xFF0084FF),
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendMessage(textInput)
                            textInput = ""
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Send", tint = Color(0xFF0084FF))
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
fun HeadsUpCallBanner(
    callData: CallData,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    chatViewModel: ChatViewModel
) {
    var isExpanded by remember { mutableStateOf(false) }
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left avatar with messenger badge
                UserAvatar(
                    name = callData.callerName,
                    avatarColor = callData.callerAvatarHexColor,
                    avatarUrl = if (callData.callerName == "Olyna") "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=150&q=80" else null,
                    size = 46.dp,
                    showMessengerBadge = true
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Middle Text Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = callData.callerName,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (callData.callType == CallType.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.Call,
                            contentDescription = null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (callData.callType == CallType.VIDEO) "Bejövő videóhívás..." else "Bejövő hanghívás...",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }

                // If not expanded, show red decline, green accept, and chevron
                if (!isExpanded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Decline round button
                        IconButton(
                            onClick = onDecline,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Elutasítás", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        // Accept round button
                        IconButton(
                            onClick = onAccept,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF22C55E)),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Felvétel", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Expand Chevron Button
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Részletek",
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            // Expanded Options Block
            if (isExpanded) {
                Divider(color = Color.White.copy(alpha = 0.08f))

                if (!showQuickReplies) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Elutasítás column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { onDecline() }
                                .padding(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f), shape = CircleShape)
                                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color(0xFFEF4444))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Elutasítás", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Üzenet column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showQuickReplies = true }
                                .padding(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFF0084FF).copy(alpha = 0.15f), shape = CircleShape)
                                    .border(1.dp, Color(0xFF0084FF).copy(alpha = 0.3f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Quickreply, contentDescription = null, tint = Color(0xFF0084FF))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Gyorsválasz", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Felvétel column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { onAccept() }
                                .padding(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFF22C55E).copy(alpha = 0.15f), shape = CircleShape)
                                    .border(1.dp, Color(0xFF22C55E).copy(alpha = 0.3f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF22C55E))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Felvétel", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Quick replies selection list
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Küldj válaszüzenetet és utasítsd el:", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            Text(
                                text = "Vissza",
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
}
