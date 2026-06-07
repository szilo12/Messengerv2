package com.example.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application)

    val isFirebaseLoggedIn = MutableStateFlow(FirebaseManager.auth?.currentUser != null)
    val firebaseUserEmail = MutableStateFlow(FirebaseManager.auth?.currentUser?.email)

    @OptIn(ExperimentalCoroutinesApi::class)
    val allUsers: StateFlow<List<User>> = isFirebaseLoggedIn.flatMapLatest { loggedIn ->
        if (loggedIn) {
            combine(
                repository.allUsersFlow,
                FirebaseManager.observeUsers()
            ) { local, firebase ->
                val combined = local.toMutableList()
                firebase.forEach { fbUser ->
                    val existingIdx = combined.indexOfFirst { it.id == fbUser.id }
                    if (existingIdx != -1) {
                        combined[existingIdx] = fbUser
                    } else {
                        combined.add(fbUser)
                    }
                }
                combined
            }
        } else {
            repository.allUsersFlow
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessages: StateFlow<List<DbMessage>> = repository.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _selectedUser = MutableStateFlow<User?>(null)
    val selectedUser: StateFlow<User?> = _selectedUser.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeChatMessages: StateFlow<List<DbMessage>> = combine(
        _selectedUser,
        isFirebaseLoggedIn
    ) { user, loggedIn ->
        Pair(user, loggedIn)
    }.flatMapLatest { (user, loggedIn) ->
        if (user == null) {
            flowOf(emptyList())
        } else if (loggedIn && user.id != "me" && user.id != "olyna" && user.id != "anyuka" && user.id != "jhaymark" && user.id != "szilard" && user.id != "kovacs" && user.id != "toth") {
            FirebaseManager.observeMessages(user.id)
        } else {
            repository.getChatMessages(user.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.initializeDataIfEmpty()
        }
    }

    fun selectUser(user: User?) {
        _selectedUser.value = user
        if (user != null) {
            viewModelScope.launch {
                repository.markAsRead(user.id)
            }
        }
    }

    fun sendMessage(content: String) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value && user.id != "me" && user.id != "olyna" && user.id != "anyuka" && user.id != "jhaymark" && user.id != "szilard" && user.id != "kovacs" && user.id != "toth") {
                FirebaseManager.sendMessage(user.id, content)
            } else {
                val isAccepted = user.isFriend
                repository.sendMessage(user.id, content, isAccepted = isAccepted)
            }
        }
    }

    fun sendAndReceiveMockReply(content: String) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value && user.id != "me" && user.id != "olyna" && user.id != "anyuka" && user.id != "jhaymark" && user.id != "szilard" && user.id != "kovacs" && user.id != "toth") {
                FirebaseManager.sendMessage(user.id, content)
            } else {
                val isAccepted = user.isFriend
                repository.sendMessage(user.id, content, isAccepted = isAccepted)
                
                if (isAccepted) {
                    kotlinx.coroutines.delay(1500)
                    val replyContent = "Szia! Erre most nem tudok válaszolni de megkaptam: \"$content\""
                    repository.sendMessage(user.id, replyContent, isAccepted = true)
                }
            }
        }
    }

    suspend fun registerUser(email: String, password: String, name: String): String? {
        return try {
            val success = FirebaseManager.registerUser(email, password, name)
            if (success) {
                isFirebaseLoggedIn.value = true
                firebaseUserEmail.value = email
                repository.updateUserName(name)
                null
            } else {
                "Sikertelen regisztráció a Firebase-szel."
            }
        } catch (e: Exception) {
            e.localizedMessage ?: "Hiba történt a regisztráció során."
        }
    }

    suspend fun loginUser(email: String, password: String): String? {
        return try {
            val success = FirebaseManager.loginUser(email, password)
            if (success) {
                isFirebaseLoggedIn.value = true
                firebaseUserEmail.value = email
                val profileName = FirebaseManager.getCurrentUserEmail()?.split("@")?.firstOrNull() ?: "Bejelentkezett"
                repository.updateUserName(profileName)
                null
            } else {
                "Sikertelen bejelentkezés."
            }
        } catch (e: Exception) {
            e.localizedMessage ?: "Hiba történt a bejelentkezés során."
        }
    }

    fun logoutUser() {
        FirebaseManager.logout()
        isFirebaseLoggedIn.value = false
        firebaseUserEmail.value = null
        viewModelScope.launch {
            repository.updateUserName("Vendég (Kijelentkezve)")
        }
    }

    fun sendFriendRequest(userId: String) {
        viewModelScope.launch {
            repository.sendFriendRequest(userId)
        }
    }

    fun acceptFriendRequest(userId: String) {
        viewModelScope.launch {
            repository.acceptFriendRequest(userId)
        }
    }

    fun rejectFriendRequest(userId: String) {
        viewModelScope.launch {
            repository.rejectFriendRequest(userId)
        }
    }

    fun acceptMessageRequest(userId: String) {
        viewModelScope.launch {
            repository.acceptMessageRequest(userId)
            val users = repository.allUsersFlow.firstOrNull() ?: emptyList()
            val u = users.find { it.id == userId }
            if (u != null) {
                _selectedUser.value = u
            }
        }
    }

    fun rejectMessageRequest(userId: String) {
        viewModelScope.launch {
            repository.rejectMessageRequest(userId)
            if (_selectedUser.value?.id == userId) {
                _selectedUser.value = null
            }
        }
    }

    fun updateStealthMode(isStealth: Boolean) {
        viewModelScope.launch {
            repository.updateStealthMode(isStealth)
        }
    }

    fun updateUserName(name: String) {
        viewModelScope.launch {
            repository.updateUserName(name)
        }
    }

    fun updateUserProfile(name: String, avatarUrl: String?) {
        viewModelScope.launch {
            repository.updateUserProfile(name, avatarUrl)
        }
    }

    fun updateCustomCallSettings(userId: String, ringtone: String, vibration: String) {
        viewModelScope.launch {
            repository.updateCustomCallSettings(userId, ringtone, vibration)
        }
    }
}
