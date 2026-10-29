package com.dotrino.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * El micrófono, para todo el proceso: UN hilo que lee de `AudioRecord`, aplica la ganancia, mide
 * el nivel y, si se está grabando, escribe. Lo mismo sirve para PROBAR el nivel (lee y mide, no
 * escribe) y para GRABAR, así que lo que marca el medidor al probar es lo que quedará grabado.
 *
 * La pantalla lo observa con [listen]; el servicio en primer plano lo mantiene vivo mientras graba.
 */
object Engine {
    enum class Mode { IDLE, MONITOR, RECORDING, PAUSED }

    class State(
        val mode: Mode,
        val elapsedMs: Long,
        /** Pico por canal desde la última lectura, en dBFS. */
        val peakDb: FloatArray,
        /** Algún pico llegó al tope desde que se empezó (o desde [clearClip]). */
        val clipped: Boolean,
        val bytes: Long,
        val error: String?,
    )

    /** Lo que se grabó, al terminar. */
    class Done(val file: File, val settings: Settings, val durationMs: Long, val bytes: Long, val startedAt: Long)

    private const val TAG = "recorder"
    private const val SILENCE = -90f

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(State) -> Unit>()

    @Volatile var state = State(Mode.IDLE, 0, floatArrayOf(SILENCE, SILENCE), false, 0, null); private set
    @Volatile private var gain = 1f
    @Volatile private var paused = false
    @Volatile private var stopping = false
    @Volatile private var clip = false
    private var worker: Thread? = null

    /** Lo terminado se le entrega a quien lo pidió (el servicio, que lo apunta en la biblioteca). */
    var onDone: ((Done) -> Unit)? = null

    fun listen(l: (State) -> Unit): () -> Unit {
        listeners += l; l(state)
        return { listeners -= l }
    }

    private fun publish(s: State) {
        state = s
        main.post { for (l in listeners.toList()) l(s) }
    }

    fun setGain(db: Int) { gain = 10f.pow(db / 20f) }
    fun clearClip() { clip = false }

    fun pause() { if (state.mode == Mode.RECORDING) paused = true }
    fun resume() { if (state.mode == Mode.PAUSED) paused = false }

    /** Probar el nivel: el micrófono abierto, sin escribir nada. */
    fun monitor(ctx: Context, s: Settings) = start(ctx, s, null)

    fun record(ctx: Context, s: Settings, file: File) = start(ctx, s, file)

    /** Para lo que esté corriendo y espera a que el archivo quede cerrado. */
    fun stop() {
        val w = worker ?: return
        stopping = true
        w.join()
        worker = null
    }

    @Synchronized
    private fun start(ctx: Context, s: Settings, file: File?) {
        stop()
        stopping = false; paused = false; clip = false
        setGain(s.gainDb)
        val app = ctx.applicationContext
        publish(State(if (file == null) Mode.MONITOR else Mode.RECORDING, 0, floatArrayOf(SILENCE, SILENCE), false, 0, null))
        worker = Thread({ run(app, s, file) }, "recorder-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")   // la pantalla pide el permiso antes de llegar aquí
    private fun run(ctx: Context, s: Settings, file: File?) {
        val float = s.format == Settings.Format.WAV24
        val enc = if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val mask = if (s.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val ch = s.channels
        val startedAt = System.currentTimeMillis()
        var rec: AudioRecord? = null
        var sink: Sink? = null
        var frames = 0L
        var error: String? = null
        try {
            val min = AudioRecord.getMinBufferSize(s.sampleRate, mask, enc)
            if (min <= 0) throw RecorderError("unsupported-format")
            val bytesPerFrame = ch * (if (float) 4 else 2)
            rec = AudioRecord.Builder()
                .setAudioSource(s.source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(s.sampleRate).setChannelMask(mask).build())
                .setBufferSizeInBytes(max(min * 4, s.sampleRate * bytesPerFrame / 2))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw RecorderError("mic-unavailable")
            if (s.deviceId != 0) {
                val dev = inputs(ctx).firstOrNull { it.id == s.deviceId } ?: throw RecorderError("device-gone")
                if (!rec.setPreferredDevice(dev)) throw RecorderError("device-refused")
            }
            if (file != null) sink = when (s.format) {
                Settings.Format.AAC -> AacSink(file, s.sampleRate, ch, Settings.AAC_BITRATE)
                else -> WavSink(file, s.sampleRate, ch, s.format.bits)
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw RecorderError("mic-busy")

            val chunk = 1024
            val fbuf = FloatArray(chunk * ch)
            val sbuf = if (float) null else ShortArray(chunk * ch)
            val peaks = FloatArray(2)
            var lastPublish = 0L
            while (!stopping) {
                val n = if (float) rec.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                        else rec.read(sbuf!!, 0, sbuf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw RecorderError("read-failed:$n")
                if (n == 0) continue
                if (!float) for (i in 0 until n) fbuf[i] = sbuf!![i] / 32768f
                // Ganancia, recorte y picos, en una pasada.
                val g = gain
                for (i in 0 until n) {
                    var v = fbuf[i] * g
                    if (v >= 1f) { v = 1f; clip = true } else if (v <= -1f) { v = -1f; clip = true }
                    fbuf[i] = v
                    val c = if (ch == 2) i and 1 else 0
                    val a = abs(v)
                    if (a > peaks[c]) peaks[c] = a
                }
                val f = n / ch
                if (sink != null && !paused) {
                    sink.write(fbuf, f)
                    frames += f
                    if (sink is WavSink && sink.full) throw RecorderError("wav-full")
                }
                val now = System.currentTimeMillis()
                if (now - lastPublish >= 50) {
                    val db = FloatArray(2) { c -> toDb(peaks[if (ch == 2) c else 0]) }
                    peaks[0] = 0f; peaks[1] = 0f
                    val mode = when {
                        sink == null -> Mode.MONITOR
                        paused -> Mode.PAUSED
                        else -> Mode.RECORDING
                    }
                    publish(State(mode, frames * 1000 / s.sampleRate, db, clip, sink?.bytes ?: 0, null))
                    lastPublish = now
                }
            }
        } catch (e: RecorderError) {
            Log.e(TAG, "capture stopped: ${e.code}")
            error = e.code
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            error = "io:" + (e.message ?: e.javaClass.simpleName)
        } finally {
            try { rec?.stop() } catch (e: IllegalStateException) { Log.e(TAG, "could not stop AudioRecord", e) }
            rec?.release()
            try {
                sink?.close()
            } catch (e: Exception) {
                Log.e(TAG, "could not close the recording", e)
                if (error == null) error = "close-failed"
            }
        }
        // Lo que se alcanzó a escribir se entrega aunque haya habido un error: es del usuario.
        if (file != null && sink != null && frames > 0) {
            onDone?.invoke(Done(file, s, frames * 1000 / s.sampleRate, file.length(), startedAt))
        } else if (file != null && file.exists() && frames == 0L) {
            file.delete()
        }
        publish(State(Mode.IDLE, 0, floatArrayOf(SILENCE, SILENCE), false, 0, error))
    }

    private fun toDb(a: Float) = if (a <= 0f) SILENCE else max(SILENCE, 20f * log10(a))

    /** Los micrófonos que ve el sistema (el del teléfono, uno USB, unos auriculares…). */
    fun inputs(ctx: Context): List<AudioDeviceInfo> =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX && it.type != AudioDeviceInfo.TYPE_FM_TUNER }
            .toList()
}

/** Un fallo con un código estable: la pantalla lo traduce (`err.<code>`). */
class RecorderError(val code: String) : Exception(code)
