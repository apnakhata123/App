package com.example.ui.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.audio.PcmAudioPlayer
import com.example.audio.VoiceRecorder
import com.example.data.ai.AiVoiceOption
import com.example.data.ai.GeminiStudyService
import com.example.data.ai.StudyAspectRatio
import com.example.data.ai.StudyModelMode
import com.example.data.ai.UploadedPaperData
import com.example.data.model.ChatMessage
import com.example.data.model.StudySession
import com.example.data.repository.StudyRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SavedSessionsUiState {
    data object Loading : SavedSessionsUiState
    data class Success(val sessions: List<StudySession>) : SavedSessionsUiState
    data class Error(val message: String) : SavedSessionsUiState
}

data class AskSolveUiState(
    val questionInput: String = "",
    val selectedMode: StudyModelMode = StudyModelMode.STANDARD,
    val attachment: UploadedPaperData? = null,
    val streamingAnswer: String = "",
    val lastSolvedSessionId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class ChatUiState(
    val inputText: String = "",
    val selectedMode: StudyModelMode = StudyModelMode.STANDARD,
    val attachment: UploadedPaperData? = null,
    val streamingReply: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

data class VideoAnalysisUiState(
    val videoName: String = "",
    val videoMimeType: String = "",
    val videoBytes: ByteArray? = null,
    val questionInput: String = "",
    val analysisText: String = "",
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null,
)

data class DiagramUiState(
    val promptInput: String = "",
    val selectedRatio: StudyAspectRatio = StudyAspectRatio.RATIO_16_9,
    val studioQuality: Boolean = false,
    val generatedBitmap: Bitmap? = null,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
)

data class VoiceUiState(
    val selectedVoice: AiVoiceOption = AiVoiceOption.KORE,
    val isRecordingForAsk: Boolean = false,
    val isRecordingForChat: Boolean = false,
    val isTranscribing: Boolean = false,
    val isSynthesizingVoice: Boolean = false,
    val activeSpeechTag: String? = null,
    val voiceError: String? = null,
)

class AskPaperViewModel(
    private val repository: StudyRepository,
    private val currentUserId: String,
    private val geminiService: GeminiStudyService = GeminiStudyService(),
    private val voiceRecorder: VoiceRecorder = VoiceRecorder(),
    private val audioPlayer: PcmAudioPlayer = PcmAudioPlayer(),
) : ViewModel() {

    val savedSessionsState: StateFlow<SavedSessionsUiState> =
        repository.observeStudySessions(currentUserId)
            .map<List<StudySession>, SavedSessionsUiState> { SavedSessionsUiState.Success(it) }
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                val root = fsErr ?: error
                Log.w(TAG, "Error observing study sessions", root)
                emit(SavedSessionsUiState.Error(root.message ?: "Failed to load saved sessions."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = SavedSessionsUiState.Loading,
            )

    val chatMessages: StateFlow<List<ChatMessage>> =
        repository.observeChatMessages(currentUserId)
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                Log.w(TAG, "Error observing chat messages", fsErr ?: error)
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

    private val _askState = MutableStateFlow(AskSolveUiState())
    val askState: StateFlow<AskSolveUiState> = _askState.asStateFlow()

    private val _chatState = MutableStateFlow(ChatUiState())
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private val _videoState = MutableStateFlow(VideoAnalysisUiState())
    val videoState: StateFlow<VideoAnalysisUiState> = _videoState.asStateFlow()

    private val _diagramState = MutableStateFlow(DiagramUiState())
    val diagramState: StateFlow<DiagramUiState> = _diagramState.asStateFlow()

    private val _voiceState = MutableStateFlow(VoiceUiState())
    val voiceState: StateFlow<VoiceUiState> = _voiceState.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = audioPlayer.isPlaying
    val recordingAmplitude: StateFlow<Float> = voiceRecorder.amplitude

    private var speechJob: Job? = null

    // --- Ask & Question Paper Solver ---

    fun updateQuestionInput(value: String) {
        _askState.update { it.copy(questionInput = value, errorMessage = null) }
    }

    fun selectAskMode(mode: StudyModelMode) {
        _askState.update { it.copy(selectedMode = mode) }
    }

    fun attachPaperForAsk(paper: UploadedPaperData) {
        _askState.update { it.copy(attachment = paper, errorMessage = null) }
    }

    fun clearAskAttachment() {
        _askState.update { it.copy(attachment = null) }
    }

    fun setAskError(message: String) {
        _askState.update { it.copy(errorMessage = message) }
    }

    fun submitQuestionOrPaper() {
        val state = _askState.value
        val prompt = state.questionInput.trim()
        val attachment = state.attachment
        if (prompt.isBlank() && attachment == null) {
            _askState.update { it.copy(errorMessage = "Please enter a question or upload a question paper / doc.") }
            return
        }

        viewModelScope.launch {
            _askState.update {
                it.copy(
                    isLoading = true,
                    streamingAnswer = "",
                    errorMessage = null,
                    lastSolvedSessionId = null,
                )
            }
            var finalAnswer = ""
            try {
                geminiService.askOrSolveStream(prompt, attachment, state.selectedMode)
                    .collect { partial ->
                        finalAnswer = partial
                        _askState.update { it.copy(streamingAnswer = partial) }
                    }

                val category = if (attachment != null) "paper" else "question"
                val title = when {
                    attachment != null && prompt.isNotBlank() ->
                        "${attachment.displayName.substringBeforeLast('.')} — ${prompt.take(50)}"
                    attachment != null -> attachment.displayName
                    else -> prompt.take(80)
                }
                val savedId = repository.saveStudySession(
                    StudySession(
                        title = title,
                        category = category,
                        prompt = prompt.ifBlank { "Solve uploaded paper: ${attachment?.displayName.orEmpty()}" },
                        answer = finalAnswer,
                        attachmentName = attachment?.displayName.orEmpty(),
                        attachmentMimeType = attachment?.mimeType.orEmpty(),
                        modelUsed = state.selectedMode.modelName,
                        isDeepThinking = state.selectedMode.isDeepThinking,
                        isBookmarked = false,
                    )
                ).getOrNull()

                _askState.update {
                    it.copy(
                        isLoading = false,
                        lastSolvedSessionId = savedId,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Ask/Solve timed out", e)
                _askState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Request timed out after 60 seconds. Please try a shorter prompt or smaller file.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Ask/Solve failed", e)
                _askState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to generate answer.",
                    )
                }
            }
        }
    }

    // --- Multi-turn Tutor Chat ---

    fun updateChatInput(value: String) {
        _chatState.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun selectChatMode(mode: StudyModelMode) {
        _chatState.update { it.copy(selectedMode = mode) }
    }

    fun attachPaperForChat(paper: UploadedPaperData) {
        _chatState.update { it.copy(attachment = paper, errorMessage = null) }
    }

    fun clearChatAttachment() {
        _chatState.update { it.copy(attachment = null) }
    }

    fun sendChatMessage() {
        val state = _chatState.value
        val text = state.inputText.trim()
        val attachment = state.attachment
        if (text.isBlank() && attachment == null) return

        val priorHistory = chatMessages.value.takeLast(14).map { msg ->
            msg.role to msg.text
        }
        val outgoingText = text.ifBlank { "Please help me understand and solve ${attachment?.displayName ?: "this paper"}." }

        viewModelScope.launch {
            _chatState.update {
                it.copy(
                    inputText = "",
                    attachment = null,
                    isSending = true,
                    streamingReply = "",
                    errorMessage = null,
                )
            }
            repository.addChatMessage(
                ChatMessage(
                    role = "user",
                    text = outgoingText,
                    attachmentLabel = attachment?.displayName.orEmpty(),
                    modelUsed = state.selectedMode.modelName,
                    isDeepThinking = state.selectedMode.isDeepThinking,
                )
            )

            var fullReply = ""
            try {
                geminiService.sendChatTurnStream(
                    historyPairs = priorHistory,
                    userMessage = outgoingText,
                    attachment = attachment,
                    mode = state.selectedMode,
                ).collect { partial ->
                    fullReply = partial
                    _chatState.update { it.copy(streamingReply = partial) }
                }

                repository.addChatMessage(
                    ChatMessage(
                        role = "model",
                        text = fullReply,
                        modelUsed = state.selectedMode.modelName,
                        isDeepThinking = state.selectedMode.isDeepThinking,
                    )
                )
                _chatState.update { it.copy(isSending = false, streamingReply = "") }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Chat timed out", e)
                _chatState.update {
                    it.copy(
                        isSending = false,
                        streamingReply = "",
                        errorMessage = "Tutor timed out after 60 seconds. Please try again.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat failed", e)
                _chatState.update {
                    it.copy(
                        isSending = false,
                        streamingReply = "",
                        errorMessage = e.localizedMessage ?: "Tutor failed to reply.",
                    )
                }
            }
        }
    }

    fun clearChatHistory() {
        val ids = chatMessages.value.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.clearChatHistory(ids)
        }
    }

    // --- Microphone Audio Transcription (gemini-3.5-transcribe) ---

    fun startVoiceRecording(forChat: Boolean) {
        val started = voiceRecorder.startRecording(viewModelScope)
        if (!started) {
            _voiceState.update { it.copy(voiceError = "Unable to initialize microphone recorder.") }
            return
        }
        _voiceState.update {
            it.copy(
                isRecordingForAsk = !forChat,
                isRecordingForChat = forChat,
                voiceError = null,
            )
        }
    }

    fun stopAndTranscribeRecording(forChat: Boolean) {
        val wavBytes = voiceRecorder.stopAndGetWav()
        _voiceState.update {
            it.copy(isRecordingForAsk = false, isRecordingForChat = false)
        }
        if (wavBytes == null || wavBytes.size < 1024) {
            _voiceState.update { it.copy(voiceError = "Recording was too short. Please speak clearly and try again.") }
            return
        }

        viewModelScope.launch {
            _voiceState.update { it.copy(isTranscribing = true, voiceError = null) }
            try {
                val transcript = geminiService.transcribeAudio(wavBytes)
                if (forChat) {
                    _chatState.update { current ->
                        val joined = if (current.inputText.isBlank()) transcript else "${current.inputText} $transcript"
                        current.copy(inputText = joined)
                    }
                } else {
                    _askState.update { current ->
                        val joined = if (current.questionInput.isBlank()) transcript else "${current.questionInput} $transcript"
                        current.copy(questionInput = joined)
                    }
                }
                _voiceState.update { it.copy(isTranscribing = false) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Audio transcription timed out", e)
                _voiceState.update {
                    it.copy(isTranscribing = false, voiceError = "Audio transcription timed out.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio transcription failed", e)
                _voiceState.update {
                    it.copy(
                        isTranscribing = false,
                        voiceError = e.localizedMessage ?: "Audio transcription failed.",
                    )
                }
            }
        }
    }

    // --- AI Voice Playback (gemini-3.1-flash-tts-preview) ---

    fun selectAiVoice(voice: AiVoiceOption) {
        _voiceState.update { it.copy(selectedVoice = voice) }
    }

    fun clearVoiceError() {
        _voiceState.update { it.copy(voiceError = null) }
    }

    fun toggleSpeakAnswer(text: String, tag: String) {
        val currentState = _voiceState.value
        if ((isSpeaking.value || currentState.isSynthesizingVoice) && currentState.activeSpeechTag == tag) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        if (text.isBlank()) return

        speechJob = viewModelScope.launch {
            _voiceState.update {
                it.copy(
                    isSynthesizingVoice = true,
                    activeSpeechTag = tag,
                    voiceError = null,
                )
            }
            try {
                val pcmBytes = geminiService.synthesizeSpeech(text, _voiceState.value.selectedVoice)
                _voiceState.update { it.copy(isSynthesizingVoice = false) }
                audioPlayer.playPcm24kMono(pcmBytes)
                _voiceState.update { it.copy(activeSpeechTag = null) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI Voice synthesis timed out", e)
                _voiceState.update {
                    it.copy(
                        isSynthesizingVoice = false,
                        activeSpeechTag = null,
                        voiceError = "AI Voice synthesis timed out.",
                    )
                }
            } catch (e: CancellationException) {
                _voiceState.update { it.copy(isSynthesizingVoice = false, activeSpeechTag = null) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI Voice synthesis failed", e)
                _voiceState.update {
                    it.copy(
                        isSynthesizingVoice = false,
                        activeSpeechTag = null,
                        voiceError = e.localizedMessage ?: "AI Voice playback failed.",
                    )
                }
            }
        }
    }

    fun stopSpeaking() {
        speechJob?.cancel()
        speechJob = null
        audioPlayer.stop()
        _voiceState.update { it.copy(isSynthesizingVoice = false, activeSpeechTag = null) }
    }

    // --- Video Understanding (gemini-4-argon) ---

    fun setSelectedVideo(name: String, mimeType: String, bytes: ByteArray) {
        _videoState.update {
            it.copy(
                videoName = name,
                videoMimeType = mimeType,
                videoBytes = bytes,
                errorMessage = null,
            )
        }
    }

    fun setVideoError(message: String) {
        _videoState.update { it.copy(errorMessage = message) }
    }

    fun updateVideoQuestion(value: String) {
        _videoState.update { it.copy(questionInput = value, errorMessage = null) }
    }

    fun analyzeSelectedVideo() {
        val state = _videoState.value
        val bytes = state.videoBytes
        if (bytes == null) {
            _videoState.update { it.copy(errorMessage = "Please pick a study video first.") }
            return
        }

        viewModelScope.launch {
            _videoState.update { it.copy(isAnalyzing = true, analysisText = "", errorMessage = null) }
            var finalAnalysis = ""
            try {
                geminiService.analyzeVideoStream(
                    videoBytes = bytes,
                    mimeType = state.videoMimeType,
                    question = state.questionInput.trim(),
                ).collect { partial ->
                    finalAnalysis = partial
                    _videoState.update { it.copy(analysisText = partial) }
                }

                repository.saveStudySession(
                    StudySession(
                        title = "Video: ${state.videoName}",
                        category = "video",
                        prompt = state.questionInput.trim().ifBlank { "Analyze lecture video ${state.videoName}" },
                        answer = finalAnalysis,
                        attachmentName = state.videoName,
                        attachmentMimeType = state.videoMimeType,
                        modelUsed = "gemini-4-argon",
                        isDeepThinking = false,
                        isBookmarked = false,
                    )
                )
                _videoState.update { it.copy(isAnalyzing = false) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Video analysis timed out", e)
                _videoState.update {
                    it.copy(isAnalyzing = false, errorMessage = "Video analysis timed out after 60 seconds.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Video analysis failed", e)
                _videoState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = e.localizedMessage ?: "Failed to analyze video.",
                    )
                }
            }
        }
    }

    // --- Image / Study Visual Generation with Aspect Ratios ---

    fun updateDiagramPrompt(value: String) {
        _diagramState.update { it.copy(promptInput = value, errorMessage = null) }
    }

    fun selectDiagramRatio(ratio: StudyAspectRatio) {
        _diagramState.update { it.copy(selectedRatio = ratio) }
    }

    fun toggleStudioQuality(enabled: Boolean) {
        _diagramState.update { it.copy(studioQuality = enabled) }
    }

    fun generateStudyVisual() {
        val state = _diagramState.value
        val prompt = state.promptInput.trim()
        if (prompt.isBlank()) {
            _diagramState.update { it.copy(errorMessage = "Describe the diagram or visual you want to create.") }
            return
        }

        viewModelScope.launch {
            _diagramState.update { it.copy(isGenerating = true, errorMessage = null) }
            try {
                val bitmap = geminiService.generateStudyDiagram(
                    prompt = prompt,
                    aspectRatio = state.selectedRatio,
                    studioQuality = state.studioQuality,
                )
                _diagramState.update { it.copy(isGenerating = false, generatedBitmap = bitmap) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Image generation timed out", e)
                _diagramState.update {
                    it.copy(isGenerating = false, errorMessage = "Image generation timed out after 60 seconds.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image generation failed", e)
                _diagramState.update {
                    it.copy(
                        isGenerating = false,
                        errorMessage = e.localizedMessage ?: "Image generation failed.",
                    )
                }
            }
        }
    }

    // --- Saved Library Actions ---

    fun toggleBookmark(session: StudySession) {
        viewModelScope.launch {
            repository.toggleBookmark(session)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteStudySession(sessionId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecorder.stopAndDiscard()
        audioPlayer.stop()
    }

    companion object {
        private const val TAG = "AskPaperViewModel"

        fun provideFactory(
            repository: StudyRepository,
            currentUserId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AskPaperViewModel(
                    repository = repository,
                    currentUserId = currentUserId,
                )
            }
        }
    }
}
