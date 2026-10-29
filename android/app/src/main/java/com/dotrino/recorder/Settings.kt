package com.dotrino.recorder

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

/** Cómo se graba. Se guarda en las preferencias de la app: es configuración del aparato, no del usuario. */
data class Settings(
    val format: Format = Format.WAV24,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    /** `MediaRecorder.AudioSource.*`. */
    val source: Int = MediaRecorder.AudioSource.MIC,
    /** El id del micrófono elegido; 0 = el que use el sistema. */
    val deviceId: Int = 0,
    val gainDb: Int = 0,
) {
    enum class Format(val ext: String, val mime: String, val bits: Int) {
        WAV24("wav", "audio/wav", 24),
        WAV16("wav", "audio/wav", 16),
        AAC("m4a", "audio/mp4", 0),
    }

    /** Bytes por segundo en disco (AAC: la tasa fija del codificador). */
    val bytesPerSecond: Long get() = when (format) {
        Format.AAC -> AAC_BITRATE / 8L
        else -> sampleRate.toLong() * channels * (format.bits / 8)
    }

    companion object {
        const val AAC_BITRATE = 256_000
        val RATES = listOf(44100, 48000)
        const val GAIN_MIN = -12
        const val GAIN_MAX = 24

        /** El micrófono sin filtros (ni supresión de ruido ni control de ganancia), si el aparato lo ofrece. */
        fun unprocessedSupported(ctx: Context): Boolean =
            ctx.getSystemService(AudioManager::class.java)
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences("recorder", Context.MODE_PRIVATE)
            // Sin nada guardado: el micrófono sin procesar cuando existe, que es el fiel.
            val defSource = if (unprocessedSupported(ctx)) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            val fmt = p.getString("format", null)
            return Settings(
                format = if (fmt == null) Format.WAV24 else Format.valueOf(fmt),
                sampleRate = p.getInt("rate", 48000),
                channels = p.getInt("channels", 2),
                source = p.getInt("source", defSource),
                deviceId = p.getInt("device", 0),
                gainDb = p.getInt("gain", 0),
            )
        }

        fun save(ctx: Context, s: Settings) = ctx.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit()
            .putString("format", s.format.name)
            .putInt("rate", s.sampleRate)
            .putInt("channels", s.channels)
            .putInt("source", s.source)
            .putInt("device", s.deviceId)
            .putInt("gain", s.gainDb)
            .apply()
    }
}
