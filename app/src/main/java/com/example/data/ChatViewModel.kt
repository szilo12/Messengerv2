package com.example.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application)

    val isFirebaseLoggedIn: StateFlow<Boolean> = FirebaseService.isUserLoggedIn
    val firebaseUserEmail: StateFlow<String?> = isFirebaseLoggedIn.map { if (it) FirebaseService.getMyEmail() else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allUsers: StateFlow<List<User>> = repository.allUsersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessages: StateFlow<List<DbMessage>> = repository.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _selectedUser = MutableStateFlow<User?>(null)
    val selectedUser: StateFlow<User?> = _selectedUser.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeChatMessages: StateFlow<List<DbMessage>> = _selectedUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
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
            if (isFirebaseLoggedIn.value) {
                FirebaseService.sendRealtimeMessage(getApplication(), user.id, content, user.isFriend)
            } else {
                val isAccepted = user.isFriend
                repository.sendMessage(user.id, content, isAccepted = isAccepted)
            }
        }
    }

    fun sendAndReceiveMockReply(content: String) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value) {
                FirebaseService.sendRealtimeMessage(getApplication(), user.id, content, user.isFriend)
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
            val success = FirebaseService.registerUser(getApplication(), email, password, name)
            if (success) {
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
            val success = FirebaseService.loginUser(getApplication(), email, password)
            if (success) {
                null
            } else {
                "Sikertelen bejelentkezés."
            }
        } catch (e: Exception) {
            e.localizedMessage ?: "Hiba történt a bejelentkezés során."
        }
    }

    fun logoutUser() {
        FirebaseService.logout(getApplication())
    }

    fun sendFriendRequest(userId: String) {
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value) {
                repository.sendFriendRequest(userId)
                FirebaseService.sendFriendRequest(userId)
            } else {
                repository.sendFriendRequest(userId)
            }
        }
    }

    fun acceptFriendRequest(userId: String) {
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value) {
                repository.acceptFriendRequest(userId)
                FirebaseService.acceptFriendRequest(userId)
            } else {
                repository.acceptFriendRequest(userId)
            }
        }
    }

    fun rejectFriendRequest(userId: String) {
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value) {
                repository.rejectFriendRequest(userId)
                FirebaseService.rejectFriendRequest(userId)
            } else {
                repository.rejectFriendRequest(userId)
            }
        }
    }

    fun acceptMessageRequest(userId: String) {
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value) {
                FirebaseService.acceptRealtimeMessageRequest(getApplication(), userId)
            } else {
                repository.acceptMessageRequest(userId)
            }
            val users = repository.allUsersFlow.firstOrNull() ?: emptyList()
            val u = users.find { it.id == userId }
            if (u != null) {
                _selectedUser.value = u
            }
        }
    }

    fun rejectMessageRequest(userId: String) {
        viewModelScope.launch {
            if (isFirebaseLoggedIn.value) {
                FirebaseService.rejectRealtimeMessageRequest(getApplication(), userId)
            } else {
                repository.rejectMessageRequest(userId)
            }
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
