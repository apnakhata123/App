package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

enum class StudyModelMode(
    val label: String,
    val modelName: String,
    val isDeepThinking: Boolean,
) {
    STANDARD("Balanced", "gemini-4-hydrogen", false),
    FAST("Fast", "gemini-3.1-flash-lite", false),
    COMPLEX("Complex", "gemini-4-argon", false),
    DEEP_THINK("Deep Think", "gemini-4-carbon", true),
}

enum class StudyAspectRatio(
    val label: String,
    val sdkRatio: AspectRatio,
) {
    RATIO_1_1("1:1", AspectRatio.SQUARE_1x1),
    RATIO_2_3("2:3", AspectRatio.PORTRAIT_2x3),
    RATIO_3_2("3:2", AspectRatio.LANDSCAPE_3x2),
    RATIO_3_4("3:4", AspectRatio.PORTRAIT_3x4),
    RATIO_4_3("4:3", AspectRatio.LANDSCAPE_4x3),
    RATIO_9_16("9:16", AspectRatio.PORTRAIT_9x16),
    RATIO_16_9("16:9", AspectRatio.LANDSCAPE_16x9),
    RATIO_21_9("21:9", AspectRatio.LANDSCAPE_21x9),
}

enum class AiVoiceOption(val voiceName: String, val label: String) {
    KORE("Kore", "Kore (Clear)"),
    AOEDE("Aoede", "Aoede (Warm)"),
    PUCK("Puck", "Puck (Energetic)"),
    CHARON("Charon", "Charon (Deep)"),
}

data class UploadedPaperData(
    val displayName: String,
    val mimeType: String,
    val bytes: ByteArray? = null,
    val bitmap: Bitmap? = null,
)

class GeminiStudyService {

    private val studySystemInstruction: Content by lazy {
        content {
            text(
                "You are AskPaper, an expert academic tutor and question-paper solver. " +
                    "Provide clear, accurate, step-by-step answers formatted cleanly with headings, " +
                    "bullet points, and formulas. If a question paper or document (PDF, doc, or image) " +
                    "is uploaded, detect every question and solve each one clearly in order. " +
                    "Respond in the same language the user asks in (English, Hindi, or Hinglish)."
            )
        }
    }

    private val tutorChatSystemInstruction: Content by lazy {
        content {
            text(
                "You are AskPaper Tutor, a patient, encouraging interactive study companion. " +
                    "Explain concepts intuitively, check understanding, solve doubts step by step, " +
                    "and reply in the user's language (English, Hindi, or Hinglish)."
            )
        }
    }

    private val standardModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = StudyModelMode.STANDARD.modelName,
            systemInstruction = studySystemInstruction
        )
    }

    private val fastModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = StudyModelMode.FAST.modelName,
            systemInstruction = studySystemInstruction
        )
    }

    private val complexModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = StudyModelMode.COMPLEX.modelName,
            systemInstruction = studySystemInstruction
        )
    }

    // Do NOT set maxOutputTokens when ThinkingLevel.HIGH is configured.
    private val deepThinkModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = StudyModelMode.DEEP_THINK.modelName,
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            },
            systemInstruction = studySystemInstruction
        )
    }

    private val videoModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-4-argon",
            systemInstruction = studySystemInstruction
        )
    }

    private val transcribeModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-transcribe"
        )
    }

    private fun modelFor(mode: StudyModelMode): GenerativeModel = when (mode) {
        StudyModelMode.STANDARD -> standardModel
        StudyModelMode.FAST -> fastModel
        StudyModelMode.COMPLEX -> complexModel
        StudyModelMode.DEEP_THINK -> deepThinkModel
    }

    fun askOrSolveStream(
        question: String,
        attachment: UploadedPaperData?,
        mode: StudyModelMode,
    ): Flow<String> = flow {
        val model = modelFor(mode)
        val promptContent = content {
            if (attachment?.bitmap != null) {
                image(attachment.bitmap)
            } else if (attachment?.bytes != null) {
                inlineData(attachment.bytes, attachment.mimeType)
            }
            val finalPrompt = when {
                question.isNotBlank() && attachment != null ->
                    "Uploaded document/paper: ${attachment.displayName}\n\nUser request: $question"
                question.isBlank() && attachment != null ->
                    "Analyze this uploaded question paper/document (${attachment.displayName}). " +
                        "Extract all questions and provide complete, step-by-step answers for every question."
                else -> question
            }
            text(finalPrompt)
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            model.generateContentStream(promptContent).collect { chunk ->
                val chunkText = chunk.text.orEmpty()
                if (chunkText.isNotEmpty()) {
                    accumulated.append(chunkText)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("The AI returned an empty response. Please try again.")
        }
    }

    fun sendChatTurnStream(
        historyPairs: List<Pair<String, String>>,
        userMessage: String,
        attachment: UploadedPaperData?,
        mode: StudyModelMode,
    ): Flow<String> = flow {
        val chatModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = mode.modelName,
            generationConfig = if (mode.isDeepThinking) {
                generationConfig {
                    thinkingConfig = thinkingConfig {
                        thinkingLevel = ThinkingLevel.HIGH
                    }
                }
            } else {
                null
            },
            systemInstruction = tutorChatSystemInstruction
        )

        val history = historyPairs.map { (role, text) ->
            content(role) { text(text) }
        }
        val chat = chatModel.startChat(history = history)
        val outgoing = content {
            if (attachment?.bitmap != null) {
                image(attachment.bitmap)
            } else if (attachment?.bytes != null) {
                inlineData(attachment.bytes, attachment.mimeType)
            }
            text(
                userMessage.ifBlank {
                    "Please explain and solve the attached material (${attachment?.displayName ?: "file"})."
                }
            )
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            chat.sendMessageStream(outgoing).collect { chunk ->
                val chunkText = chunk.text.orEmpty()
                if (chunkText.isNotEmpty()) {
                    accumulated.append(chunkText)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("Tutor returned an empty reply.")
        }
    }

    suspend fun transcribeAudio(wavBytes: ByteArray): String {
        val requestContent = content {
            inlineData(wavBytes, "audio/wav")
            text("Transcribe this spoken audio accurately into text. Preserve Hindi, Hinglish, or English words naturally. Output only the transcription.")
        }
        val response = withTimeout(60_000L) {
            transcribeModel.generateContent(requestContent)
        }
        val text = response.text?.trim()
        if (text.isNullOrBlank()) {
            throw IllegalStateException("Could not transcribe any speech from the recording.")
        }
        return text
    }

    fun analyzeVideoStream(
        videoBytes: ByteArray,
        mimeType: String,
        question: String,
    ): Flow<String> = flow {
        val requestContent = content {
            inlineData(videoBytes, mimeType)
            text(
                question.ifBlank {
                    "Analyze this study video. Summarize key concepts, list important formulas or questions shown, and highlight timestamps."
                }
            )
        }
        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            videoModel.generateContentStream(requestContent).collect { chunk ->
                val chunkText = chunk.text.orEmpty()
                if (chunkText.isNotEmpty()) {
                    accumulated.append(chunkText)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("Video analysis returned an empty response.")
        }
    }

    suspend fun generateStudyDiagram(
        prompt: String,
        aspectRatio: StudyAspectRatio,
        studioQuality: Boolean,
    ): Bitmap {
        val modelName = if (studioQuality) "gemini-3-pro-image-preview" else "gemini-nano-banana-2.1"
        val config = generationConfig {
            responseModalities = listOf(ResponseModality.IMAGE)
            imageConfig = ImageConfig.builder()
                .setAspectRatio(aspectRatio.sdkRatio)
                .setImageSize(ImageSize.SIZE_1K)
                .build()
        }
        val imageModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = config
        )
        val response = withTimeout(60_000L) {
            imageModel.generateContent(prompt)
        }
        val imagePart = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<ImagePart>()
            ?.firstOrNull()
        return imagePart?.image
            ?: throw IllegalStateException("No image was returned by $modelName.")
    }

    @OptIn(PublicPreviewAPI::class)
    suspend fun synthesizeSpeech(
        text: String,
        voice: AiVoiceOption,
    ): ByteArray {
        // Keep TTS input concise and clean so synthesis stays crisp and fast
        val cleanText = text
            .replace(Regex("[#*`]+"), "")
            .trim()
            .take(1800)

        val ttsConfig = generationConfig {
            responseModalities = listOf(ResponseModality.AUDIO)
            speechConfig = SpeechConfig(
                voice = Voice(voice.voiceName),
                languageCode = "en-US"
            )
        }
        val ttsModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.1-flash-tts-preview",
            generationConfig = ttsConfig
        )
        val response = withTimeout(60_000L) {
            ttsModel.generateContent("Read the following answer clearly and naturally as a helpful teacher:\n\n$cleanText")
        }
        val audioPart = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<InlineDataPart>()
            ?.firstOrNull()
        val pcmData = audioPart?.inlineData
        if (pcmData == null || pcmData.isEmpty()) {
            Log.e(TAG, "TTS response contained no audio inlineData")
            throw IllegalStateException("AI Voice did not return audio data.")
        }
        return pcmData
    }

    companion object {
        private const val TAG = "GeminiStudyService"
    }
}
