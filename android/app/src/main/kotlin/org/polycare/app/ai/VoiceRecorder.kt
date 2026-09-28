package org.polycare.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Live microphone capture at the exact PCM format [org.polycare.whisper.WhisperEngine] expects
 * (mono, 16-bit, 16 kHz) — no file, no intermediate WAV, straight into a [ShortArray]. Mirrors
 * [WavFile]'s format assumptions so a live question and a debug `whisper_wav_path` file produce
 * identical input to the model.
 *
 * One call ([recordUntilStopped]) owns the whole AudioRecord lifecycle (open → read loop →
 * stop → release); [requestStop] only flips a flag the loop polls, so there is no path where a
 * second caller can release the object while the read loop is still using it.
 */
class VoiceRecorder(private val context: Context) {
    @Volatile private var stopRequested = false

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Refuse to record forever if [requestStop] is never called — a question this long
         * would exceed the LLM's context window anyway. */
        const val MAX_DURATION_MS = 30_000

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Records until [requestStop] is called (or [MAX_DURATION_MS] elapses) and returns the
     * captured audio; an empty array means the mic couldn't be opened (no permission, or the
     * device reports no valid buffer size) — the caller falls back to typed input, never crashes.
     */
    suspend fun recordUntilStopped(): ShortArray = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext ShortArray(0)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext ShortArray(0)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext ShortArray(0)
        }

        stopRequested = false
        val out = ByteArrayOutputStream()
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100ms per read
        try {
            record.startRecording()
            val deadline = System.nanoTime() + MAX_DURATION_MS * 1_000_000L
            while (!stopRequested && System.nanoTime() < deadline) {
                val n = record.read(chunk, 0, chunk.size)
                for (i in 0 until n) {
                    out.write(chunk[i].toInt() and 0xFF)
                    out.write((chunk[i].toInt() shr 8) and 0xFF)
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        val bytes = out.toByteArray()
        ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** Ends an in-progress [recordUntilStopped] — e.g. the user tapped "stop" or navigated away. */
    fun requestStop() {
        stopRequested = true
    }
}
