package com.dotrino.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** A dónde van las muestras ya con la ganancia aplicada: floats intercalados en [-1, 1]. */
interface Sink {
    fun write(buf: FloatArray, frames: Int)
    fun close()
    val bytes: Long
}

/**
 * WAV PCM entero (16 o 24 bit). La cabecera se reescribe cada segundo con los tamaños reales,
 * así que si el proceso muere a mitad, lo grabado hasta ese segundo se puede reproducir.
 */
class WavSink(file: File, private val rate: Int, private val channels: Int, private val bits: Int) : Sink {
    init { require(bits == 16 || bits == 24) { "unsupported bit depth: $bits" } }

    companion object {
        /** El tope del formato: los tamaños de la cabecera son de 32 bits. */
        const val MAX_DATA = 0xFFFF_FFFFL - 36 - 64 * 1024
    }

    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val bytesPerSample = bits / 8
    private var scratch = ByteArray(0)
    private var data = 0L
    private var lastHeader = 0L
    override val bytes get() = 44 + data

    init { header() }

    private fun header() {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + data).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16)
        h.putShort(1)                                   // PCM
        h.putShort(channels.toShort())
        h.putInt(rate)
        h.putInt(rate * channels * bytesPerSample)
        h.putShort((channels * bytesPerSample).toShort())
        h.putShort(bits.toShort())
        h.put("data".toByteArray()); h.putInt(data.toInt())
        val pos = raf.filePointer
        raf.seek(0); raf.write(h.array())
        raf.seek(maxOf(pos, 44L))
    }

    override fun write(buf: FloatArray, frames: Int) {
        val n = frames * channels
        val len = n * bytesPerSample
        if (scratch.size < len) scratch = ByteArray(len)
        var o = 0
        if (bits == 16) {
            for (i in 0 until n) {
                val v = (buf[i] * 32767f).roundToInt()
                scratch[o++] = v.toByte(); scratch[o++] = (v shr 8).toByte()
            }
        } else {
            for (i in 0 until n) {
                val v = (buf[i] * 8388607f).roundToInt()
                scratch[o++] = v.toByte(); scratch[o++] = (v shr 8).toByte(); scratch[o++] = (v shr 16).toByte()
            }
        }
        raf.write(scratch, 0, len)
        data += len
        val now = System.currentTimeMillis()
        if (now - lastHeader >= 1000) { header(); lastHeader = now }
    }

    val full get() = data >= MAX_DATA

    override fun close() {
        header()
        raf.fd.sync()
        raf.close()
    }
}

/** AAC-LC en un .m4a, con el codificador del aparato (MediaCodec) y su contenedor (MediaMuxer). */
class AacSink(file: File, private val rate: Int, private val channels: Int, bitrate: Int) : Sink {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var framesIn = 0L
    private var scratch = ByteArray(0)
    private var out = 0L
    override val bytes get() = out

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private fun pts() = framesIn * 1_000_000L / rate

    override fun write(buf: FloatArray, frames: Int) {
        val n = frames * channels
        val len = n * 2
        if (scratch.size < len) scratch = ByteArray(len)
        var o = 0
        for (i in 0 until n) {
            val v = (buf[i] * 32767f).roundToInt()
            scratch[o++] = v.toByte(); scratch[o++] = (v shr 8).toByte()
        }
        var off = 0
        while (off < len) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val ib = codec.getInputBuffer(i) ?: error("encoder gave no input buffer $i")
                ib.clear()
                val k = minOf(ib.remaining() - ib.remaining() % (2 * channels), len - off)
                ib.put(scratch, off, k)
                codec.queueInputBuffer(i, 0, k, pts(), 0)
                framesIn += k / (2 * channels)
                off += k
            }
            drain(eos = false)
        }
    }

    private fun drain(eos: Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    check(System.currentTimeMillis() < deadline) { "aac encoder did not finish" }
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "aac output format changed twice" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                o >= 0 -> {
                    val ob = codec.getOutputBuffer(o) ?: error("encoder gave no output buffer $o")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(started) { "aac data before its format" }
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                        out += info.size
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        try {
            val i = codec.dequeueInputBuffer(1_000_000)
            check(i >= 0) { "aac encoder took no end of stream" }
            codec.queueInputBuffer(i, 0, 0, pts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(eos = true)
        } finally {
            codec.stop(); codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
