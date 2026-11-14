package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class StudySession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val category: String = "question",
    val prompt: String = "",
    val answer: String = "",
    val attachmentName: String = "",
    val attachmentMimeType: String = "",
    val modelUsed: String = "gemini-4-hydrogen",
    val isDeepThinking: Boolean = false,
    val isBookmarked: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "user",
    val text: String = "",
    val attachmentLabel: String = "",
    val modelUsed: String = "gemini-4-hydrogen",
    val isDeepThinking: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
)
