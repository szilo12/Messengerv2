package com.example.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

class ChatRepository(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "olyna_messenger_db"
    ).fallbackToDestructiveMigration().build()

    private val dao = db.chatDao()

    val allUsersFlow: Flow<List<User>> = dao.getAllUsersFlow()
    val allMessagesFlow: Flow<List<DbMessage>> = dao.getAllMessagesFlow()
    val settingsFlow: Flow<UserSettings> = dao.getSettingsFlow().map { it ?: UserSettings() }

    suspend fun initializeDataIfEmpty() {
        val currentUsers = dao.getAllUsersFlow().map { list -> list.filter { !it.isSelf } }.firstOrNull()
        if (currentUsers.isNullOrEmpty()) {
            val defaultUsers = listOf(
                User("me", "Saját Profil", "Online", 0xFF6366F1, isFriend = false, isRequestSent = false, isRequestReceived = false, isSelf = true, avatarUrl = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=150&q=80"),
                User("olyna", "Olyna", "Aktív most", 0xFFEC4899, isFriend = true, isRequestSent = false, isRequestReceived = false, avatarUrl = "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=150&q=80"),
                User("szilard", "Szilárd Cseke", "Aktív most", 0xFF3B82F6, isFriend = true, isRequestSent = false, isRequestReceived = false, avatarUrl = "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?auto=format&fit=crop&w=150&q=80"),
                User("anyuka", "Család / Anya", "Nemrég volt aktív", 0xFFF59E0B, isFriend = true, isRequestSent = false, isRequestReceived = false, avatarUrl = "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=150&q=80"),
                User("jhaymark", "Jhaymark Sudaria", "Aktív 5p. h.", 0xFF10B981, isFriend = false, isRequestSent = false, isRequestReceived = false, avatarUrl = null),
                User("kovacs", "Kovács Péter", "Aktív 1 ó. h.", 0xFF8B5CF6, isFriend = false, isRequestSent = false, isRequestReceived = false, avatarUrl = null),
                User("toth", "Tóth Gábor", "Online", 0xFFEF4444, isFriend = false, isRequestSent = false, isRequestReceived = true, avatarUrl = null)
            )
            dao.insertUsers(defaultUsers)

            val defaultMessages = listOf(
                DbMessage(senderId = "olyna", receiverId = "me", content = "Szia! Készen állsz a hívásra? 😊", isRead = false),
                DbMessage(senderId = "szilard", receiverId = "me", content = "Megírtam a Kotlin híváskezelőt!", isRead = true),
                DbMessage(senderId = "anyuka", receiverId = "me", content = "Vedd meg a tejet kérlek", isRead = true),
                DbMessage(senderId = "jhaymark", receiverId = "me", content = "Szia! Messenger call demo works perfectly, check it out!", isRead = false, isAccepted = false)
            )
            for (msg in defaultMessages) {
                dao.insertMessage(msg)
            }
            dao.insertSettings(UserSettings(1, false, "Szilárd", "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=150&q=80"))
        }
    }

    fun getChatMessages(userId: String): Flow<List<DbMessage>> {
        return dao.getChatMessagesFlow("me", userId)
    }

    suspend fun sendMessage(receiverId: String, content: String, isAccepted: Boolean = true) {
        val newMessage = DbMessage(
            senderId = "me",
            receiverId = receiverId,
            content = content,
            timestamp = System.currentTimeMillis(),
            isRead = true,
            isAccepted = isAccepted
        )
        dao.insertMessage(newMessage)
    }

    suspend fun sendFriendRequest(userId: String) {
        val user = dao.getUserById(userId)
        if (user != null) {
            dao.updateUser(user.copy(isRequestSent = true))
        }
    }

    suspend fun acceptFriendRequest(userId: String) {
        val user = dao.getUserById(userId)
        if (user != null) {
            dao.updateUser(user.copy(isFriend = true, isRequestReceived = false))
            // Auto accept any message requests from this user
            dao.acceptMessageRequest("me", userId)
        }
    }

    suspend fun rejectFriendRequest(userId: String) {
        val user = dao.getUserById(userId)
        if (user != null) {
            dao.updateUser(user.copy(isRequestReceived = false))
            dao.deleteMessagesBetween("me", userId)
        }
    }

    suspend fun acceptMessageRequest(userId: String) {
        // Find existing user and make them friends
        val user = dao.getUserById(userId)
        if (user != null) {
            dao.updateUser(user.copy(isFriend = true))
        }
        dao.acceptMessageRequest("me", userId)
    }

    suspend fun rejectMessageRequest(userId: String) {
        dao.deleteMessagesBetween("me", userId)
    }

    suspend fun updateStealthMode(isStealth: Boolean) {
        val currentSettings = dao.getSettingsFlow().map { it ?: UserSettings() }.firstOrNull() ?: UserSettings()
        dao.insertSettings(currentSettings.copy(isStealthMode = isStealth))
    }

    suspend fun updateUserName(name: String) {
        val currentSettings = dao.getSettingsFlow().map { it ?: UserSettings() }.firstOrNull() ?: UserSettings()
        dao.insertSettings(currentSettings.copy(currentUserName = name))
        // Also update 'me' user profile in users table
        val me = dao.getUserById("me")
        if (me != null) {
            dao.updateUser(me.copy(name = name))
        }
    }

    suspend fun updateUserProfile(name: String, avatarUrl: String?) {
        val currentSettings = dao.getSettingsFlow().map { it ?: UserSettings() }.firstOrNull() ?: UserSettings()
        dao.insertSettings(currentSettings.copy(currentUserName = name, avatarUrl = avatarUrl))
        // Also update 'me' user profile in users table
        val me = dao.getUserById("me")
        if (me != null) {
            dao.updateUser(me.copy(name = name, avatarUrl = avatarUrl))
        }
    }

    suspend fun markAsRead(senderId: String) {
        dao.markMessagesAsRead(senderId, "me")
    }

    suspend fun updateCustomCallSettings(userId: String, ringtone: String, vibration: String) {
        dao.updateCustomCallSettings(userId, ringtone, vibration)
    }

    suspend fun insertOrUpdateUserDirectly(user: User) {
        val existing = dao.getUserById(user.id)
        if (existing != null) {
            dao.updateUser(user.copy(
                customRingtone = existing.customRingtone,
                customVibration = existing.customVibration
            ))
        } else {
            dao.insertUser(user)
        }
    }

    suspend fun insertMessageDirectlyIfNotExist(msg: DbMessage) {
        val list = dao.getAllMessagesFlow().firstOrNull() ?: emptyList()
        val exists = list.any { 
            it.senderId == msg.senderId && 
            it.receiverId == msg.receiverId && 
            it.content == msg.content && 
            Math.abs(it.timestamp - msg.timestamp) < 2000
        }
        if (!exists) {
            dao.insertMessage(msg)
        }
    }

    suspend fun getSettingsDirectly(): UserSettings {
        return settingsFlow.firstOrNull() ?: UserSettings()
    }
}
