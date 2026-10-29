package com.dotrino.recorder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import com.dotrino.sdk.DotrinoStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Las grabaciones (CONVENCIONES §4): los BYTES son archivos en la carpeta privada de la app; el
 * ÍNDICE (nombre, formato, duración) es del usuario y va al almacén, hilo `recorder.recordings`.
 * Un archivo sin ficha (la app se cerró a mitad de una grabación) se recupera al abrir.
 */
class Library(private val ctx: Context) {
    @Serializable
    data class Recording(
        val id: String,
        val name: String,
        val file: String,
        val format: String,
        val sampleRate: Int,
        val channels: Int,
        /** -1 = no se pudo leer (un .m4a cortado a medias). */
        val durationMs: Long,
        val bytes: Long,
        val createdAt: Long,
        val recovered: Boolean = false,
    )

    companion object {
        const val THREAD = "recorder.recordings"
        fun dir(ctx: Context): File =
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: error("no app storage"), "recordings").apply { mkdirs() }

        fun newFile(ctx: Context, s: Settings): File =
            File(dir(ctx), "rec-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + "." + s.format.ext)
    }

    private val store = DotrinoStore(ctx, "recorder")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun file(r: Recording) = File(dir(ctx), r.file)

    fun list(): List<Recording> = store.listThread(THREAD).map { e ->
        val doc = e["doc"] ?: throw IllegalStateException("entry ${e["id"]} in $THREAD has no document")
        json.decodeFromJsonElement<Recording>(doc)
    }.sortedByDescending { it.createdAt }

    fun save(r: Recording) = store.appendMessage(THREAD, JsonObject(mapOf(
        "id" to JsonPrimitive(r.id),
        "ts" to JsonPrimitive(System.currentTimeMillis()),
        "doc" to json.encodeToJsonElement(r).jsonObject,
    )))

    fun add(d: Engine.Done) = save(Recording(
        id = d.file.nameWithoutExtension,
        name = defaultName(d.startedAt),
        file = d.file.name,
        format = d.settings.format.name,
        sampleRate = d.settings.sampleRate,
        channels = d.settings.channels,
        durationMs = d.durationMs,
        bytes = d.bytes,
        createdAt = d.startedAt,
    ))

    fun rename(r: Recording, name: String) = save(r.copy(name = name))

    fun delete(r: Recording) {
        val f = file(r)
        if (f.exists() && !f.delete()) throw java.io.IOException("could not delete ${f.name}")
        store.removeMessage(THREAD, r.id)
    }

    /** Los archivos de la carpeta que no tienen ficha: se apuntan como recuperados. */
    fun recoverOrphans(skip: File?) {
        val known = list().map { it.file }.toSet()
        for (f in dir(ctx).listFiles().orEmpty()) {
            if (f.name in known || f == skip || !f.isFile) continue
            if (f.length() <= 44) { f.delete(); continue }
            val fmt = when (f.extension) { "wav" -> wavFormat(f); "m4a" -> "AAC"; else -> continue }
            var rate = 0; var ch = 0; var dur = -1L
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.path)
                dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
                rate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                Log.e("recorder", "could not read ${f.name} while recovering it", e)
            } finally { mmr.release() }
            if (fmt != "AAC") wavHeader(f)?.let { (r, c) -> rate = r; ch = c }
            save(Recording(f.nameWithoutExtension, defaultName(f.lastModified()), f.name, fmt, rate, ch, dur, f.length(), f.lastModified(), recovered = true))
        }
    }

    private fun wavHeader(f: File): Pair<Int, Int>? = try {
        f.inputStream().use { i ->
            val h = ByteArray(36); if (i.read(h) < 36) return null
            val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.getInt(24) to b.getShort(22).toInt()
        }
    } catch (e: java.io.IOException) { Log.e("recorder", "could not read the header of ${f.name}", e); null }

    private fun wavFormat(f: File): String = try {
        f.inputStream().use { i ->
            val h = ByteArray(36); i.read(h)
            if (java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(34).toInt() == 24) "WAV24" else "WAV16"
        }
    } catch (e: java.io.IOException) { "WAV16" }

    private fun defaultName(ts: Long) = t("defaultName", "date" to SimpleDateFormat("d MMM yyyy, HH:mm", Locale(I18n.code)).format(Date(ts)))
}
