package org.polycare.app.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the PCM samples out of a 16-bit PCM WAV file and returns them at mono/16kHz — the one
 * format `WhisperEngine` accepts. Development/testing only (`--es whisper_wav_path`, and the
 * on-device Hindi TTS fixture in [HindiCheck]); the real input path is `AudioRecord` capturing at
 * that same rate directly, which needs no file parsing or resampling at all. Any other sample
 * rate found in the file (Android's TTS engines commonly write 22050Hz or 24000Hz) is linearly
 * resampled to 16kHz rather than rejected — good enough for a speech test fixture, not a general
 * audio resampler.
 */
object WavFile {
    class UnsupportedWavException(message: String) : Exception(message)

    fun readPcm16Mono16k(file: File): ShortArray {
        val (samples, sampleRate) = readPcm16Mono(file)
        return if (sampleRate == 16_000) samples else resampleLinear(samples, sampleRate, 16_000)
    }

    /** Returns the mono samples at whatever sample rate the file actually has. */
    fun readPcm16Mono(file: File): Pair<ShortArray, Int> {
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
        if (sampleRate <= 0) throw UnsupportedWavException("no fmt chunk / invalid sample rate")
        val mono = if (channels == 1) samples else downmixToMono(samples, channels)
        return mono to sampleRate
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray =
        ShortArray(samples.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += samples[i * channels + c]
            (sum / channels).toShort()
        }

    private fun resampleLinear(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (samples.isEmpty()) return samples
        val outLength = (samples.size.toLong() * toRate / fromRate).toInt()
        return ShortArray(outLength) { i ->
            val srcPos = i.toDouble() * fromRate / toRate
            val i0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = srcPos - i0
            (samples[i0] * (1 - frac) + samples[i1] * frac).toInt().toShort()
        }
    }

    private const val RIFF = 0x46464952 // "RIFF" little-endian
    private const val WAVE = 0x45564157 // "WAVE"
    private const val FMT = 0x20746d66 // "fmt "
    private const val DATA = 0x61746164 // "data"
}
