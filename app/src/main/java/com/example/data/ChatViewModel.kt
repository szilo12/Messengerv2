package com.example.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application)

    val allUsers: StateFlow<List<User>> = repository.allUsersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessages: StateFlow<List<DbMessage>> = repository.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _selectedUser = MutableStateFlow<User?>(null)
    val selectedUser: StateFlow<User?> = _selectedUser.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeChatMessages: StateFlow<List<DbMessage>> = _selectedUser
        .flatMapLatest { user ->
            if (user == null) flowOf(emptyList())
            else repository.getChatMessages(user.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            // If they are a friend, it's already accepted. Otherwise, we send a request.
            val isAccepted = user.isFriend
            repository.sendMessage(user.id, content, isAccepted = isAccepted)
        }
    }

    fun sendAndReceiveMockReply(content: String) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch {
            val isAccepted = user.isFriend
            repository.sendMessage(user.id, content, isAccepted = isAccepted)
            
            // Mock a typing status reply if friendship is active
            if (isAccepted) {
                kotlinx.coroutines.delay(1500)
                // Insert auto-reply
                val replyContent = "Szia! Erre most nem tudok válaszolni de megkaptam: \"$content\""
                val replyMessage = DbMessage(
                    senderId = user.id,
                    receiverId = "me",
                    content = replyContent,
                    timestamp = System.currentTimeMillis()
                )
                // We directly insert it by invoking repository DB mappings or a quick wrapper inside repository
                repository.sendMessage(user.id, replyContent, isAccepted = true)
                // Swapping back sender receiver attributes
                val list = repository.allMessagesFlow.firstOrNull() ?: emptyList()
                val lastId = list.lastOrNull()?.id ?: 0
                // We can't access database directly from viewModel, so we let repository do it.
            }
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
            // Retrieve updated user to refresh UI
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
