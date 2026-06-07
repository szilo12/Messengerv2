package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private const val TAG = "FirebaseManager"

    val isFirebaseAvailable: Boolean
        get() = try {
            FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase configuration is not fully active or initialized. Falling back to secure simulator mode.")
            false
        }

    val auth: FirebaseAuth?
        get() = if (isFirebaseAvailable) FirebaseAuth.getInstance() else null

    val firestore: FirebaseFirestore?
        get() = if (isFirebaseAvailable) FirebaseFirestore.getInstance() else null

    fun getCurrentUserId(): String {
        return auth?.currentUser?.uid ?: "me"
    }

    fun getCurrentUserEmail(): String? {
        return auth?.currentUser?.email
    }

    suspend fun registerUser(email: String, password: String, name: String): Boolean {
        val authInstance = auth ?: return false
        val firestoreInstance = firestore ?: return false

        return try {
            val result = authInstance.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                // Save user profile in Firestore
                val userProfile = hashMapOf(
                    "uid" to user.uid,
                    "name" to name,
                    "email" to email,
                    "status" to "Aktív most",
                    "avatarColor" to 0xFF3B82F6, // Bright blue
                    "avatarUrl" to null,
                    "isFriend" to true,
                    "isSelf" to false
                )
                firestoreInstance.collection("users").document(user.uid).set(userProfile).await()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering user: ", e)
            throw e
        }
    }

    suspend fun loginUser(email: String, password: String): Boolean {
        val authInstance = auth ?: return false
        return try {
            authInstance.signInWithEmailAndPassword(email, password).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error logging in: ", e)
            throw e
        }
    }

    fun logout() {
        auth?.signOut()
    }

    // Observe other users real-time from Firestore
    fun observeUsers(): Flow<List<User>> = callbackFlow {
        val firestoreInstance = firestore
        if (firestoreInstance == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = firestoreInstance.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to users in Firestore", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val usersList = snapshot.documents.mapNotNull { doc ->
                        val uid = doc.getString("uid") ?: return@mapNotNull null
                        // Don't include self
                        if (uid == getCurrentUserId()) return@mapNotNull null

                        val name = doc.getString("name") ?: "Felhasználó"
                        val status = doc.getString("status") ?: "Offline"
                        val avatarColor = doc.getLong("avatarColor") ?: 0xFF3B82F6
                        val avatarUrl = doc.getString("avatarUrl")
                        
                        User(
                            id = uid,
                            name = name,
                            status = status,
                            avatarColor = avatarColor,
                            isFriend = true,
                            isRequestSent = false,
                            isRequestReceived = false,
                            isSelf = false,
                            avatarUrl = avatarUrl
                        )
                    }
                    trySend(usersList)
                }
            }

        awaitClose { listener.remove() }
    }

    // Observe real-time messages for a specific user from Firestore
    fun observeMessages(chatPartnerId: String): Flow<List<DbMessage>> = callbackFlow {
        val firestoreInstance = firestore
        val myUid = getCurrentUserId()
        if (firestoreInstance == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = firestoreInstance.collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to messages in Firestore", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        val senderId = doc.getString("senderId") ?: ""
                        val receiverId = doc.getString("receiverId") ?: ""
                        val content = doc.getString("content") ?: ""
                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        val isRead = doc.getBoolean("isRead") ?: false
                        val isAccepted = doc.getBoolean("isAccepted") ?: true

                        val isBetweenUs = (senderId == myUid && receiverId == chatPartnerId) ||
                                          (senderId == chatPartnerId && receiverId == myUid)

                        if (isBetweenUs) {
                            DbMessage(
                                id = doc.id.hashCode(),
                                senderId = if (senderId == myUid) "me" else senderId,
                                receiverId = if (receiverId == myUid) "me" else receiverId,
                                content = content,
                                timestamp = timestamp,
                                isRead = isRead,
                                isAccepted = isAccepted
                            )
                        } else null
                    }
                    trySend(messages)
                }
            }

        awaitClose { listener.remove() }
    }

    // Send message to Firestore real-time
    suspend fun sendMessage(receiverId: String, content: String) {
        val firestoreInstance = firestore ?: return
        val myUid = getCurrentUserId()

        val messageData = hashMapOf(
            "senderId" to myUid,
            "receiverId" to receiverId,
            "content" to content,
            "timestamp" to System.currentTimeMillis(),
            "isRead" to false,
            "isAccepted" to true
        )
        try {
            firestoreInstance.collection("messages").add(messageData).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to Firestore: ", e)
        }
    }
}
