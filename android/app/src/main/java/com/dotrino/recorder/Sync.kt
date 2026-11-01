package com.dotrino.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.dotrino.sdk.ContentNodeClient
import com.dotrino.sdk.ContentNodeClient.ContentError
import com.dotrino.sdk.Crypto
import com.dotrino.sdk.PhoneIdentity
import com.dotrino.sdk.Profile
import com.dotrino.sdk.ProxyConnection
import com.dotrino.sdk.SegmentCipher
import com.dotrino.sdk.webrtc.WebRtcDirect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sube las grabaciones al content node de la cuenta, que es un aparato más del perfil.
 *
 * El camino decide (regla del dueño, 2026-11-01), y lo dice `ContentNodeClient.bytesAllowed`:
 *  - WebRTC directo: solo, sin preguntar.
 *  - TURN o proxio PROPIOS de la cuenta: queda a la vista y sube al pulsar, tras preguntar.
 *  - TURN o proxio de Dotrino: no sube. Esa red es para el contenido de Dotrino.
 *
 * El archivo se cifra por tramos con una llave suya, y la ficha (nombre, formato, esa llave) se
 * cierra con la llave de la cuenta: en el node solo hay cifrado, y lo abren los aparatos del acta.
 */
object Sync {
    const val TAG = "library"
    private const val DEFAULT_PROXY = "wss://proxy.dotrino.com"

    /** Lo que se enseña. `waiting` y `ask` son estados normales, no errores. */
    sealed class Status {
        object Idle : Status()
        object Checking : Status()
        /** No hay ningún node encendido, o el perfil no está en una bóveda: no se dice nada por grabación. */
        object NoNode : Status()
        /** Hay node, pero sin conexión directa y la red es de Dotrino. */
        object WaitingDirect : Status()
        /** Hay node por una red propia: decide el dueño. */
        object Ask : Status()
        data class Uploading(val id: String, val percent: Int) : Status()
        data class Failed(val code: String) : Status()
    }

    @Volatile var status: Status = Status.Idle
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    fun listen(l: () -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }

    private fun set(s: Status) { status = s; ui.post { listeners.forEach { it() } } }

    /** Hay algo por subir: una grabación sin copia, o una copia con otro nombre. */
    private fun pending(lib: Library) = lib.list().filter { !it.upToDate() && lib.file(it).exists() }

    /** Lo intenta si hace falta. [allowOwnNetwork]: el dueño acaba de decir que sí a su red. */
    fun kick(ctx: Context, allowOwnNetwork: Boolean = false) {
        if (running || Engine.state.mode != Engine.Mode.IDLE) return
        if (pending(Library(ctx)).isEmpty()) return
        ctx.startForegroundService(Intent(ctx, SyncService::class.java).putExtra(SyncService.ALLOW, allowOwnNetwork))
    }

    internal fun run(ctx: Context, allowOwnNetwork: Boolean, done: () -> Unit) {
        if (running) return done()
        running = true
        scope.launch {
            try {
                set(Status.Checking)
                set(attempt(ctx, allowOwnNetwork))
            } catch (e: ContentError) {
                Log.e("recorder", "sync failed: ${e.code}: ${e.message}")
                set(if (e.code == "needs-direct") Status.WaitingDirect else Status.Failed(e.code))
            } catch (e: Profile.ProfileError) {
                Log.i("recorder", "sync: no profile to speak as (${e.code})")
                set(Status.NoNode)
            } catch (e: Exception) {
                Log.e("recorder", "sync failed", e)
                set(Status.Failed("failed"))
            } finally {
                running = false
                done()
            }
        }
    }

    private suspend fun attempt(ctx: Context, allowOwnNetwork: Boolean): Status {
        val lib = Library(ctx)
        val identity = PhoneIdentity(ctx.applicationContext)
        var profile = identity.profile()
        val proxy = profile.vault?.proxy ?: return Status.NoNode
        val conn = ProxyConnection(proxy, "recorder")
        conn.useDirect(WebRtcDirect(ctx.applicationContext))
        try {
            conn.connect()
            conn.identifyAs(profile.publickey) { profile.signData(it) }
            // El TURN de Dotrino no se pide: por ahí estos bytes no van a pasar.
            if (!ContentNodeClient.isDotrinoNetwork(proxy)) conn.enableTurn(profile.publickey) { profile.signData(it) }
            val nodes = ContentNodeClient.find(profile, conn)
            if (nodes.isEmpty()) return Status.NoNode
            val node = ContentNodeClient.open(profile, conn, proxy, nodes.first(), catchUp = { identity.catchUp(profile, conn).also { profile = it } })
            try {
                node.settle()
                when (node.allowed()) {
                    ContentNodeClient.Path.YES -> {}
                    ContentNodeClient.Path.NO -> return Status.WaitingDirect
                    ContentNodeClient.Path.ASK -> if (!allowOwnNetwork) return Status.Ask
                }
                val (gen, cek) = profile.contentKey() ?: throw ContentError("this device does not hold the account key yet", "no-content-key")
                for (r in pending(lib)) push(ctx, lib, node, r, cek, gen)
                return Status.Idle
            } finally { node.close() }
        } finally { conn.close() }
    }

    private suspend fun push(ctx: Context, lib: Library, node: ContentNodeClient, first: Library.Recording, cek: String, gen: Int) {
        var r = first
        val cipher = File(ctx.cacheDir, "up-${r.id}.enc")
        // Ya en el node, y solo cambió el nombre: basta una ficha nueva. Si no, el archivo cifrado
        // se rehace si hace falta: la llave se apunta primero, y con ella salen los mismos bytes
        // y el mismo cid, así que el node sigue donde iba.
        if (r.syncedCid == null) {
            if (r.syncKey == null || r.syncCid == null || !cipher.exists()) {
                val key = r.syncKey?.let { Crypto.fromB64(it) } ?: Crypto.randomBytes(32)
                val made = SegmentCipher.encryptFile(lib.file(r), cipher, key)
                r = r.copy(syncKey = Crypto.b64(key), syncCid = made)
                lib.save(r)
            }
            var shown = -1
            node.upload(cipher, r.syncCid!!, "application/octet-stream") { at, total ->
                val percent = if (total == 0L) 100 else (at * 100 / total).toInt()
                if (percent != shown) { shown = percent; set(Status.Uploading(r.id, percent)) }
            }
        }
        val cid = r.syncCid ?: throw IllegalStateException("${r.id} has no cid")
        val mime = Settings.Format.valueOf(r.format).mime
        val card = buildJsonObject {
            put("v", 1); put("id", r.id); put("app", "recorder"); put("kind", "audio")
            put("name", r.name); put("file", r.file); put("mime", mime); put("format", r.format)
            put("sampleRate", r.sampleRate); put("channels", r.channels); put("durationMs", r.durationMs)
            put("createdAt", r.createdAt); put("updatedAt", System.currentTimeMillis())
            put("cid", cid); put("key", JsonPrimitive(r.syncKey ?: throw IllegalStateException("${r.id} has no key")))
        }
        val env = Crypto.sealAccount(cek, gen, card.toString())
        node.put(env.toString().toByteArray(Charsets.UTF_8), "application/json", TAG)
        lib.save(r.copy(syncedCid = cid, syncedName = r.name))
        cipher.delete()
    }
}

/** Una subida larga sigue con la pantalla apagada: servicio en primer plano, con su aviso. */
class SyncService : Service() {
    companion object {
        const val ALLOW = "allow-own-network"
        private const val CHANNEL = "sync"
        private const val NOTE = 2
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, t("syncChannel"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note).setContentTitle(t("syncNote")).setContentIntent(open).setOngoing(true).build()
        startForeground(NOTE, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Sync.run(this, intent?.getBooleanExtra(ALLOW, false) == true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
