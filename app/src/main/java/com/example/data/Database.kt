package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
data class User(
    @PrimaryKey val id: String,
    val name: String,
    val status: String,
    val avatarColor: Long,
    val isFriend: Boolean,
    val isRequestSent: Boolean,
    val isRequestReceived: Boolean,
    val isSelf: Boolean = false,
    val avatarUrl: String? = null,
    val customRingtone: String = "Alapértelmezett",
    val customVibration: String = "Alapértelmezett"
)

@Entity(tableName = "messages")
data class DbMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val isAccepted: Boolean = true
)

@Entity(tableName = "settings")
data class UserSettings(
    @PrimaryKey val id: Int = 1,
    val isStealthMode: Boolean = false,
    val currentUserName: String = "Te (Én)",
    val avatarUrl: String? = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=150&q=80"
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM users")
    fun getAllUsersFlow(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserById(id: String): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<User>)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUserById(id: String)

    @Update
    suspend fun updateUser(user: User)

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessagesFlow(): Flow<List<DbMessage>>

    @Query("SELECT * FROM messages WHERE (senderId = :u1 AND receiverId = :u2) OR (senderId = :u2 AND receiverId = :u1) ORDER BY timestamp ASC")
    fun getChatMessagesFlow(u1: String, u2: String): Flow<List<DbMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: DbMessage)

    @Query("UPDATE messages SET isRead = 1 WHERE senderId = :senderId AND receiverId = :receiverId")
    suspend fun markMessagesAsRead(senderId: String, receiverId: String)

    @Query("UPDATE messages SET isAccepted = 1 WHERE (senderId = :u1 AND receiverId = :u2) OR (senderId = :u2 AND receiverId = :u1)")
    suspend fun acceptMessageRequest(u1: String, u2: String)

    @Query("DELETE FROM messages WHERE (senderId = :u1 AND receiverId = :u2) OR (senderId = :u2 AND receiverId = :u1)")
    suspend fun deleteMessagesBetween(u1: String, u2: String)

    @Query("SELECT * FROM settings WHERE id = 1")
    fun getSettingsFlow(): Flow<UserSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: UserSettings)

    @Query("UPDATE users SET customRingtone = :ringtone, customVibration = :vibration WHERE id = :userId")
    suspend fun updateCustomCallSettings(userId: String, ringtone: String, vibration: String)
}

@Database(entities = [User::class, DbMessage::class, UserSettings::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
