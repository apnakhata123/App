package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.ChatMessage
import com.example.data.model.StudySession
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun saveAndObserveStudySession_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = StudyRepository(firestore)

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveStudySession(
                StudySession(
                    title = "Calculus Paper 2025",
                    category = "paper",
                    prompt = "Solve Question 1 to 3",
                    answer = "Step-by-step solution...",
                    attachmentName = "calc_paper.pdf",
                    attachmentMimeType = "application/pdf",
                    modelUsed = "gemini-4-carbon",
                    isDeepThinking = true,
                    isBookmarked = false,
                )
            )
        }
        assertTrue(saveResult.isSuccess)
        val sessionId = saveResult.getOrThrow()

        val sessions = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeStudySessions(uid).first { list -> list.any { it.id == sessionId } }
        }
        assertTrue(sessions.any { it.id == sessionId && it.title == "Calculus Paper 2025" })
    }

    @Test
    fun getStudySession_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = StudyRepository(firestore)
        val sessionId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.saveStudySession(
                StudySession(
                    title = "Alice Private Notes",
                    category = "question",
                    prompt = "Explain mitosis",
                    answer = "Mitosis is cell division...",
                    modelUsed = "gemini-4-hydrogen",
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = StudyRepository(firestore)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getStudySession(aliceUid, sessionId)
        }
        assertTrue(result.isFailure)
        val fsEx = result.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeStudySessions_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = StudyRepository(firestore)

        val exception = try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeStudySessions("some_uid").first()
            }
            null
        } catch (e: Exception) {
            e
        }
        val fsEx = checkNotNull(exception?.unwrapFirestoreException()) {
            "Expected FirebaseFirestoreException, got $exception"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx.code)
    }

    @Test
    fun addAndObserveChatMessages_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = StudyRepository(firestore)

        val addResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addChatMessage(
                ChatMessage(
                    role = "user",
                    text = "Explain Ohm's Law",
                    modelUsed = "gemini-4-hydrogen",
                    isDeepThinking = false,
                )
            )
        }
        assertTrue(addResult.isSuccess)
        val msgId = addResult.getOrThrow()

        val msgs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeChatMessages(uid).first { list -> list.any { it.id == msgId } }
        }
        assertTrue(msgs.any { it.id == msgId && it.text == "Explain Ohm's Law" })
    }

    private companion object {
        const val ALICE_EMAIL = "alice_study@test.com"
        const val BOB_EMAIL = "bob_study@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
