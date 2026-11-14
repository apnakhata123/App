package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class PcmAudioPlayer {
    private var activeTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    suspend fun playPcm24kMono(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        stop()

        withContext(Dispatchers.IO) {
            val sampleRate = 24000
            val channelOut = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
            val bufferSize = maxOf(minBuffer, 4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .setEncoding(encoding)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()

            activeTrack = track
            _isPlaying.value = true
            try {
                track.play()
                var offset = 0
                while (offset < pcmBytes.size && activeTrack === track) {
                    val toWrite = minOf(bufferSize, pcmBytes.size - offset)
                    val written = track.write(pcmBytes, offset, toWrite)
                    if (written <= 0) break
                    offset += written
                }
                if (activeTrack === track) {
                    // Allow remaining buffered audio to drain
                    track.stop()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PcmAudioPlayer", "Error playing PCM audio", e)
            } finally {
                try {
                    track.release()
                } catch (_: Exception) {
                }
                if (activeTrack === track) {
                    activeTrack = null
                }
                _isPlaying.value = false
            }
        }
    }

    fun stop() {
        val track = activeTrack
        activeTrack = null
        _isPlaying.value = false
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
