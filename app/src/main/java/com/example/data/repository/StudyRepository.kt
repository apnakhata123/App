package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.StudySession
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class StudyRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeStudySessions(userId: String): Flow<List<StudySession>> = flow {
        val path = "users/$userId/studySessions"
        emitAll(
            db.collection("users").document(userId).collection("studySessions")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        StudySession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && Firebase.auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getStudySession(userId: String, sessionId: String): Result<StudySession?> {
        val docRef = db.collection("users").document(userId)
            .collection("studySessions").document(sessionId)
        return try {
            val snap = docRef.get().await()
            val item = snap.toObject(
                StudySession::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun saveStudySession(session: StudySession): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
            .collection("studySessions").document()
        val safeTitle = session.title.trim().ifBlank { "Study Note" }.take(140)
        val safePrompt = session.prompt.trim().ifBlank { "Uploaded study material" }.take(10000)
        val safeAnswer = session.answer.take(50000)
        val payload = buildMap<String, Any> {
            put("userId", uid)
            put("title", safeTitle)
            put("category", session.category)
            put("prompt", safePrompt)
            put("answer", safeAnswer)
            if (session.attachmentName.isNotBlank()) {
                put("attachmentName", session.attachmentName.take(200))
            }
            if (session.attachmentMimeType.isNotBlank()) {
                put("attachmentMimeType", session.attachmentMimeType.take(100))
            }
            put("modelUsed", session.modelUsed.ifBlank { "gemini-4-hydrogen" }.take(80))
            put("isDeepThinking", session.isDeepThinking)
            put("isBookmarked", session.isBookmarked)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleBookmark(session: StudySession): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
            .collection("studySessions").document(session.id)
        return try {
            docRef.update(
                mapOf(
                    "isBookmarked" to !session.isBookmarked,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteStudySession(sessionId: String): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
            .collection("studySessions").document(sessionId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeChatMessages(userId: String): Flow<List<ChatMessage>> = flow {
        val path = "users/$userId/chatMessages"
        emitAll(
            db.collection("users").document(userId).collection("chatMessages")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && Firebase.auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun addChatMessage(message: ChatMessage): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
            .collection("chatMessages").document()
        val payload = buildMap<String, Any> {
            put("userId", uid)
            put("role", message.role)
            put("text", message.text.take(50000).ifBlank { " " })
            if (message.attachmentLabel.isNotBlank()) {
                put("attachmentLabel", message.attachmentLabel.take(200))
            }
            put("modelUsed", message.modelUsed.take(80))
            put("isDeepThinking", message.isDeepThinking)
            put("createdAt", FieldValue.serverTimestamp())
        }
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun clearChatHistory(messageIds: List<String>): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val collectionRef = db.collection("users").document(uid).collection("chatMessages")
        return try {
            val batch = db.batch()
            messageIds.forEach { id ->
                if (id.isNotBlank()) {
                    batch.delete(collectionRef.document(id))
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, collectionRef.path)
            Result.failure(e)
        }
    }
}
