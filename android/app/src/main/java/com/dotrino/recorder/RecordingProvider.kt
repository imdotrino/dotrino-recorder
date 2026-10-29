package com.dotrino.recorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Entrega UNA grabación, de solo lectura, a la app que el usuario eligió al compartir. Solo
 * sirve archivos de la carpeta de grabaciones, y solo con el permiso que da el propio Intent.
 */
class RecordingProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "com.dotrino.recorder.files"
        fun uri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.name).build()
        fun mime(f: File) = if (f.extension == "m4a") "audio/mp4" else "audio/wav"
    }

    private fun resolve(uri: Uri): File {
        val ctx: Context = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no file in $uri")
        val dir = Library.dir(ctx).canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.isFile) throw FileNotFoundException("not a recording: $name")
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = mime(resolve(uri))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = resolve(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }

    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read only")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException("read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException("read only")
}
