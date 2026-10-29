package com.dotrino.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * Mantiene viva la grabación con la app en segundo plano o la pantalla apagada: un servicio en
 * primer plano de tipo micrófono, con su aviso fijo (Pausa / Detener). La captura es de [Engine].
 */
class RecorderService : Service() {
    companion object {
        private const val CHANNEL = "recording"
        private const val NOTE = 1
        const val START = "com.dotrino.recorder.START"
        const val PAUSE = "com.dotrino.recorder.PAUSE"
        const val RESUME = "com.dotrino.recorder.RESUME"
        const val STOP = "com.dotrino.recorder.STOP"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RecorderService::class.java).setAction(START))
        fun send(ctx: Context, action: String) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(action))
    }

    private var off: (() -> Unit)? = null
    private var lastMode: Engine.Mode? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.load(this)
        when (intent?.action) {
            START -> begin()
            PAUSE -> Engine.pause()
            RESUME -> Engine.resume()
            STOP -> Thread({ Engine.stop() }, "recorder-stop").start()
            else -> { Log.e("recorder", "service started without an action: ${intent?.action}"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("noteChannel"), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTE, note(Engine.Mode.RECORDING), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val lib = Library(this)
        val s = Settings.load(this)
        val file: File = Library.newFile(this, s)
        Engine.onDone = { d -> lib.add(d) }
        // Primero arranca, después escucha: `listen` avisa del estado actual al momento, y antes
        // de arrancar ese estado es IDLE (cerraría el servicio sin haber grabado).
        Engine.record(this, s, file)
        off = Engine.listen { st ->
            if (st.mode == lastMode) return@listen
            lastMode = st.mode
            when (st.mode) {
                Engine.Mode.RECORDING, Engine.Mode.PAUSED -> nm.notify(NOTE, note(st.mode))
                Engine.Mode.IDLE -> finish()
                Engine.Mode.MONITOR -> {}
            }
        }
    }

    private fun finish() {
        off?.invoke(); off = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun note(mode: Engine.Mode): Notification {
        fun action(label: String, a: String) = Notification.Action.Builder(null, label,
            PendingIntent.getService(this, a.hashCode(), Intent(this, RecorderService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = mode == Engine.Mode.PAUSED
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(t(if (paused) "notePaused" else "noteRecording"))
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - Engine.state.elapsedMs)
            .addAction(if (paused) action(t("resume"), RESUME) else action(t("pause"), PAUSE))
            .addAction(action(t("stop"), STOP))
            .build()
    }

    override fun onDestroy() {
        off?.invoke()
        super.onDestroy()
    }
}
