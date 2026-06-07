package com.example.data

import android.content.Context
import android.util.Log
import com.example.CallData
import com.example.CallManager
import com.example.CallStatus
import com.example.CallType
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FirebaseService {
    private const val TAG = "FirebaseService"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var auth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null

    private var usersListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var callsListener: ListenerRegistration? = null
    private var activeCallListener: ListenerRegistration? = null

    private val _isFirebaseAvailable = MutableStateFlow(false)
    val isFirebaseAvailable: StateFlow<Boolean> = _isFirebaseAvailable.asStateFlow()

    private val _isUserLoggedIn = MutableStateFlow(false)
    val isUserLoggedIn: StateFlow<Boolean> = _isUserLoggedIn.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Holds the ID of the current active call document Alice is waiting on
    private var currentCallDocId: String? = null

    fun init(context: Context) {
        try {
            // Attempt to initialize Firebase securely
            FirebaseApp.initializeApp(context)
            auth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()
            _isFirebaseAvailable.value = true
            _isUserLoggedIn.value = auth?.currentUser != null
            Log.d(TAG, "Firebase initialized successfully. Logged in: ${_isUserLoggedIn.value}")
            
            if (_isUserLoggedIn.value) {
                startRealtimeListeners(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed. Running in graceful local offline mode.", e)
            _isFirebaseAvailable.value = false
        }
    }

    fun getMyUserId(): String {
        return auth?.currentUser?.uid ?: "local_me"
    }

    fun getMyEmail(): String {
        return auth?.currentUser?.email ?: "szilardcseke010@gmail.com"
    }

    suspend fun registerUser(context: Context, email: String, password: String, displayName: String): Boolean {
        _isLoading.value = true
        _authError.value = null
        try {
            val mAuth = auth ?: throw IllegalStateException("Firebase Auth cannot be loaded")
            val mFirestore = firestore ?: throw IllegalStateException("Firestore cannot be loaded")

            val result = mAuth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                val uid = user.uid
                val userData = hashMapOf(
                    "id" to uid,
                    "name" to displayName,
                    "email" to email,
                    "status" to "Aktív most",
                    "avatarColor" to 0xFF6366F1, // Premium Indigo Default
                    "avatarUrl" to null,
                    "isFriend" to false,
                    "isRequestSent" to false,
                    "isRequestReceived" to false,
                    "customRingtone" to "Alapértelmezett",
                    "customVibration" to "Alapértelmezett",
                    "isSelf" to false // Other users see this user as non-self
                )
                mFirestore.collection("users").document(uid).set(userData).await()
                
                // Save user details locally in SQL database too
                saveLocalSelfProfile(context, uid, displayName, email)

                _isUserLoggedIn.value = true
                startRealtimeListeners(context)
                _isLoading.value = false
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            _authError.value = translateAuthError(e.message ?: "Ismeretlen hiba történt a regisztráció során.")
        }
        _isLoading.value = false
        return false
    }

    suspend fun loginUser(context: Context, email: String, password: String): Boolean {
        _isLoading.value = true
        _authError.value = null
        try {
            val mAuth = auth ?: throw IllegalStateException("Firebase Auth cannot be loaded")
            val result = mAuth.signInWithEmailAndPassword(email, password).await()
            if (result.user != null) {
                // Fetch details from Firestore and update local self profile
                val uid = result.user!!.uid
                val doc = firestore?.collection("users")?.document(uid)?.get()?.await()
                val name = doc?.getString("name") ?: email.substringBefore("@")
                saveLocalSelfProfile(context, uid, name, email)

                // Mark current user as online
                updateMyStatus("Aktív most")

                _isUserLoggedIn.value = true
                startRealtimeListeners(context)
                _isLoading.value = false
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            _authError.value = translateAuthError(e.message ?: "Hibás email vagy jelszó.")
        }
        _isLoading.value = false
        return false
    }

    fun logout(context: Context) {
        scope.launch {
            updateMyStatus("Offline")
            stopRealtimeListeners()
            auth?.signOut()
            _isUserLoggedIn.value = false
            
            // Clear local user settings and messages or reset Room database
            try {
                val repository = ChatRepository(context)
                // We could let the user see default mock data or clean up
                repository.initializeDataIfEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning repository on logout", e)
            }
        }
    }

    private suspend fun saveLocalSelfProfile(context: Context, uid: String, name: String, email: String) {
        try {
            val repository = ChatRepository(context)
            // Save self details to settings entity
            repository.updateUserProfile(name, "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=150&q=80")
        } catch (e: Exception) {
            Log.e(TAG, "saveLocalSelfProfile failed", e)
        }
    }

    fun updateMyStatus(status: String) {
        val mFirestore = firestore ?: return
        val myId = getMyUserId()
        if (myId != "local_me") {
            scope.launch {
                try {
                    mFirestore.collection("users").document(myId).update("status", status).await()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed updating online status", e)
                }
            }
        }
    }

    private fun translateAuthError(msg: String): String {
        return when {
            msg.contains("badly formatted", ignoreCase = true) -> "A megadott e-mail cím formátuma érvénytelen."
            msg.contains("already in use", ignoreCase = true) -> "Ez az e-mail cím már regisztrálva van."
            msg.contains("weak password", ignoreCase = true) -> "A jelszónak legalább 6 karakterből kell állnia."
            msg.contains("no user record", ignoreCase = true) -> "Nincs ilyen e-mail címmel regisztrált felhasználó."
            msg.contains("wrong password", ignoreCase = true) -> "A megadott jelszó helytelen."
            msg.contains("blocked", ignoreCase = true) -> "A felhasználói fiók ideiglenesen le van tiltva túl sok sikertelen kísérlet miatt."
            else -> msg
        }
    }

    // --- REALTIME FIRESTORE DATA SYNC ---
    fun startRealtimeListeners(context: Context) {
        stopRealtimeListeners()

        val myId = getMyUserId()
        val mFirestore = firestore ?: return
        if (myId == "local_me") return

        val repository = ChatRepository(context)

        Log.d(TAG, "Starting Firestore Realtime Syncer for user $myId")

        // 1. Sync USERS
        usersListener = mFirestore.collection("users")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Users listener failed", e)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    scope.launch {
                        val localUsers = ArrayList<User>()
                        for (doc in snapshot.documents) {
                            val id = doc.getString("id") ?: continue
                            if (id == myId) continue // Skip saving self in normal friends list
                            
                            val name = doc.getString("name") ?: "Felhasználó"
                            val status = doc.getString("status") ?: "Offline"
                            val avatarColor = doc.getLong("avatarColor") ?: 0xFF3B82F6
                            val avatarUrl = doc.getString("avatarUrl")
                            val isFriend = doc.getBoolean("isFriend") ?: true // default true inside chat
                            val isRequestSent = doc.getBoolean("isRequestSent") ?: false
                            val isRequestReceived = doc.getBoolean("isRequestReceived") ?: false
                            
                            val user = User(
                                id = id,
                                name = name,
                                status = status,
                                avatarColor = avatarColor,
                                isFriend = isFriend,
                                isRequestSent = isRequestSent,
                                isRequestReceived = isRequestReceived,
                                avatarUrl = avatarUrl
                            )
                            localUsers.add(user)
                        }
                        if (localUsers.isNotEmpty()) {
                            // Sync with local SQLite Room
                            for (user in localUsers) {
                                repository.insertOrUpdateUserDirectly(user)
                            }
                        }
                    }
                }
            }

        // 2. Sync MESSAGES
        // We listen to all messages either sent by me, or received by me.
        messagesListener = mFirestore.collection("messages")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Messages listener failed", e)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    scope.launch {
                        for (doc in snapshot.documents) {
                            val senderId = doc.getString("senderId") ?: continue
                            val receiverId = doc.getString("receiverId") ?: continue
                            
                            // Only process messages involving me
                            if (senderId == myId || receiverId == myId) {
                                val content = doc.getString("content") ?: ""
                                val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                val isRead = doc.getBoolean("isRead") ?: false
                                val isAccepted = doc.getBoolean("isAccepted") ?: true

                                val msg = DbMessage(
                                    senderId = senderId,
                                    receiverId = receiverId,
                                    content = content,
                                    timestamp = timestamp,
                                    isRead = isRead,
                                    isAccepted = isAccepted
                                )
                                repository.insertMessageDirectlyIfNotExist(msg)
                            }
                        }
                    }
                }
            }

        // 3. Sync REAL-TIME VOIP CALL SIGNALING (Incoming Calls)
        // We listen for calls where calleeId == myId and status == "ringing"
        callsListener = mFirestore.collection("calls")
            .whereEqualTo("calleeId", myId)
            .whereEqualTo("status", "ringing")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Calls signaling listener failed", e)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    for (doc in snapshot.documents) {
                        val callId = doc.id
                        val callerId = doc.getString("callerId") ?: continue
                        val callerName = doc.getString("callerName") ?: "Ismeretlen"
                        val callerAvatarHexColor = doc.getLong("callerAvatarHexColor") ?: 0xFFEC4899
                        val callTypeStr = doc.getString("callType") ?: "VIDEO"
                        
                        Log.d(TAG, "📞 Real-time Incoming Call Detected! ID: $callId from $callerName")
                        
                        currentCallDocId = callId
                        val callType = if (callTypeStr == "VOICE") CallType.VOICE else CallType.VIDEO
                        
                        // Listen closely for terminating signal of this call (if Alice cancels it before Bob answers)
                        listenToActiveCallStatus(context, callId)

                        // Trigger CallManager incoming call UI overlay and ringing sound
                        CallManager.triggerIncomingCall(
                            context,
                            CallData(
                                callerName = callerName,
                                callerSubtitle = "Valós idejű bejövő ${if (callType == CallType.VIDEO) "Videó" else "Hang"} hívás...",
                                callerAvatarHexColor = callerAvatarHexColor,
                                callType = callType
                            )
                        )
                    }
                }
            }
    }

    private fun listenToActiveCallStatus(context: Context, callId: String) {
        val mFirestore = firestore ?: return
        activeCallListener?.remove()
        activeCallListener = mFirestore.collection("calls").document(callId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val status = snapshot.getString("status")
                if (status == "ended" || status == "declined") {
                    Log.d(TAG, "Call $callId was terminated by remote party: $status")
                    if (CallManager.callStatus.value != CallStatus.IDLE) {
                        CallManager.stopRingingInternal()
                        CallManager.endCall(context)
                    }
                    activeCallListener?.remove()
                    activeCallListener = null
                }
            }
    }

    fun stopRealtimeListeners() {
        usersListener?.remove()
        usersListener = null
        messagesListener?.remove()
        messagesListener = null
        callsListener?.remove()
        callsListener = null
        activeCallListener?.remove()
        activeCallListener = null
    }

    // --- FIRESTORE USER SEND ACTIONS ---
    suspend fun sendRealtimeMessage(context: Context, receiverId: String, content: String, isAccepted: Boolean) {
        val myId = getMyUserId()
        val mFirestore = firestore
        
        // Write to local Room database instantly for offline confidence
        val repository = ChatRepository(context)
        repository.sendMessage(receiverId, content, isAccepted)

        // Write to Firestore if available
        if (myId != "local_me" && mFirestore != null) {
            try {
                val timestamp = System.currentTimeMillis()
                val messageData = hashMapOf(
                    "senderId" to myId,
                    "receiverId" to receiverId,
                    "content" to content,
                    "timestamp" to timestamp,
                    "isRead" to false,
                    "isAccepted" to isAccepted
                )
                mFirestore.collection("messages").add(messageData).await()
                Log.d(TAG, "Real-time message uploaded successfully to Firestore")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload real-time message to Firestore", e)
            }
        }
    }

    // --- REALTIME CALL SIGNALING SENDER ACTIONS ---
    fun startRealtimeCall(context: Context, calleeId: String, calleeName: String, callType: CallType) {
        val myId = getMyUserId()
        val mFirestore = firestore
        if (myId == "local_me" || mFirestore == null) {
            Log.e(TAG, "Cannot start real-time call. Offline or unauthenticated.")
            return
        }

        scope.launch {
            try {
                val callId = "${myId}_${calleeId}_${System.currentTimeMillis()}"
                currentCallDocId = callId

                // Fetch self profile details
                val repository = ChatRepository(context)
                val settings = repository.getSettingsDirectly()
                val callerName = settings.currentUserName

                val callData = hashMapOf(
                    "id" to callId,
                    "callerId" to myId,
                    "callerName" to callerName,
                    "callerAvatarHexColor" to 0xFF6366F1, // Indigo default
                    "calleeId" to calleeId,
                    "callType" to callType.name,
                    "status" to "ringing",
                    "timestamp" to System.currentTimeMillis()
                )

                mFirestore.collection("calls").document(callId).set(callData).await()
                Log.d(TAG, "Real-time outgoing call initialized. ID: $callId")

                // Start local signaling log & simulation for Alice
                CallManager.triggerIncomingCall(
                    context,
                    CallData(
                        callerName = calleeName,
                        callerSubtitle = "Kicsörög...",
                        callerAvatarHexColor = 0xFFEC4899,
                        callType = callType
                    )
                )

                // Alice listens to changes on the call document to see when Bob answers or declines
                activeCallListener?.remove()
                activeCallListener = mFirestore.collection("calls").document(callId)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            Log.e(TAG, "Alice call listener failed", e)
                            return@addSnapshotListener
                        }
                        if (snapshot != null && snapshot.exists()) {
                            val status = snapshot.getString("status")
                            Log.d(TAG, "ALICE: Call status changed to: $status")
                            if (status == "answered") {
                                Log.d(TAG, "ALICE: Bob answered! Standardizing WebRTC stream.")
                                CallManager.answerCall(context)
                            } else if (status == "declined" || status == "ended") {
                                Log.d(TAG, "ALICE: Bob declined or hung up.")
                                CallManager.endCall(context)
                                activeCallListener?.remove()
                                activeCallListener = null
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed initiating real-time call in Firestore", e)
            }
        }
    }

    fun acceptRealtimeCall(context: Context) {
        val docId = currentCallDocId ?: return
        val mFirestore = firestore ?: return
        Log.d(TAG, "Accepting real-time call in Firestore document: $docId")
        scope.launch {
            try {
                mFirestore.collection("calls").document(docId).update("status", "answered").await()
                CallManager.answerCall(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update call status to answered", e)
            }
        }
    }

    fun declineRealtimeCall(context: Context) {
        val docId = currentCallDocId ?: return
        val mFirestore = firestore ?: return
        Log.d(TAG, "Declining real-time call in Firestore document: $docId")
        scope.launch {
            try {
                mFirestore.collection("calls").document(docId).update("status", "declined").await()
                CallManager.declineCall(context)
                currentCallDocId = null
                activeCallListener?.remove()
                activeCallListener = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update call status to declined", e)
            }
        }
    }

    fun endRealtimeCall(context: Context) {
        val docId = currentCallDocId ?: return
        val mFirestore = firestore ?: return
        Log.d(TAG, "Ending real-time call in Firestore document: $docId")
        scope.launch {
            try {
                mFirestore.collection("calls").document(docId).update("status", "ended").await()
                CallManager.endCall(context)
                currentCallDocId = null
                activeCallListener?.remove()
                activeCallListener = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update call status to ended", e)
            }
        }
    }
}
