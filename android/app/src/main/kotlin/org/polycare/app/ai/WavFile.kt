package org.polycare.app.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the PCM samples out of a mono, 16-bit, 16 kHz PCM WAV file — the one format
 * `WhisperEngine` accepts. Development/testing only (`--es whisper_wav_path`); the real input
 * path is `AudioRecord` capturing at that same rate, which needs no file parsing at all.
 */
object WavFile {
    class UnsupportedWavException(message: String) : Exception(message)

    fun readPcm16Mono16k(file: File): ShortArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.int == RIFF) { "not a RIFF file" }
        buf.int // chunk size
        require(buf.int == WAVE) { "not a WAVE file" }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var data: ShortArray? = null

        while (buf.remaining() >= 8) {
            val id = buf.int
            val size = buf.int
            when (id) {
                FMT -> {
                    val start = buf.position()
                    buf.short // audio format
                    channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    bitsPerSample = buf.short.toInt()
                    buf.position(start + size + (size and 1))
                }
                DATA -> {
                    if (bitsPerSample != 16) throw UnsupportedWavException("expected 16-bit PCM, got ${bitsPerSample}-bit")
                    val samples = ShortArray(size / 2)
                    for (i in samples.indices) samples[i] = buf.short
                    data = samples
                    buf.position(buf.position() + (size and 1))
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        val samples = data ?: throw UnsupportedWavException("no data chunk")
        if (sampleRate != 16_000) throw UnsupportedWavException("expected 16000 Hz, got $sampleRate Hz")
        return if (channels == 1) samples else downmixToMono(samples, channels)
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray =
        ShortArray(samples.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += samples[i * channels + c]
            (sum / channels).toShort()
        }

    private const val RIFF = 0x46464952 // "RIFF" little-endian
    private const val WAVE = 0x45564157 // "WAVE"
    private const val FMT = 0x20746d66 // "fmt "
    private const val DATA = 0x61746164 // "data"
}
