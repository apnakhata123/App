package com.example.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.ai.UploadedPaperData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContentUriReader {

    private const val MAX_INLINE_BYTES = 18 * 1024 * 1024 // Stay safely under Gemini's 20MB cap

    suspend fun readPaperAttachment(context: Context, uri: Uri): Result<UploadedPaperData> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri) ?: inferMimeFromName(queryDisplayName(context, uri))
                val name = queryDisplayName(context, uri)

                if (mimeType.startsWith("image/")) {
                    val bitmap = decodeScaledBitmap(context, uri)
                        ?: return@withContext Result.failure(IllegalStateException("Could not decode selected image."))
                    return@withContext Result.success(
                        UploadedPaperData(
                            displayName = name,
                            mimeType = mimeType,
                            bitmap = bitmap,
                        )
                    )
                }

                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Unable to read selected document."))

                if (bytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("The selected file is empty."))
                }
                if (bytes.size > MAX_INLINE_BYTES) {
                    return@withContext Result.failure(
                        IllegalStateException("File exceeds 18 MB limit (${bytes.size / (1024 * 1024)} MB). Please select a smaller document.")
                    )
                }

                Result.success(
                    UploadedPaperData(
                        displayName = name,
                        mimeType = normalizeDocumentMime(mimeType, name),
                        bytes = bytes,
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun readVideoBytes(context: Context, uri: Uri): Result<Triple<String, String, ByteArray>> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val name = queryDisplayName(context, uri)
                val mimeType = resolver.getType(uri) ?: "video/mp4"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Unable to read selected video."))
                if (bytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Selected video is empty."))
                }
                if (bytes.size > MAX_INLINE_BYTES) {
                    return@withContext Result.failure(
                        IllegalStateException("Video is ${bytes.size / (1024 * 1024)} MB. Please pick a short clip under 18 MB.")
                    )
                }
                Result.success(Triple(name, mimeType, bytes))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "QuestionPaper"
    }

    private fun inferMimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain"
            lower.endsWith(".csv") -> "text/csv"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/pdf"
        }
    }

    private fun normalizeDocumentMime(mime: String, name: String): String {
        return when {
            mime == "application/octet-stream" -> inferMimeFromName(name)
            mime == "text/markdown" -> "text/plain"
            else -> mime
        }
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxDim: Int = 1800): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxDim || boundsOpts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }
}
