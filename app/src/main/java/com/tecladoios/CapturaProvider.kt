package com.tecladoios

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Entrega a la app donde escribes (WhatsApp, Messenger…) la captura que tocaste en la barra.
 * No es pública: solo la puede leer la app a la que el teclado le da permiso en ese momento,
 * y solo las copias de capturas que hizo el teclado.
 */
class CapturaProvider : ContentProvider() {

    companion object {
        const val AUTORIDAD = "com.tecladoios.capturas"
        fun uriDe(nombre: String): Uri = Uri.parse("content://$AUTORIDAD/$nombre")
    }

    override fun onCreate() = true

    private fun tipo(nombre: String) = when (nombre.substringAfterLast('.', "")) {
        "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    override fun getType(uri: Uri): String = tipo(uri.lastPathSegment ?: "")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val c = context ?: throw FileNotFoundException()
        val f = Capturas.archivo(c, uri.lastPathSegment ?: "") ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = context ?: throw FileNotFoundException()
        val nombre = uri.lastPathSegment ?: ""
        val f = Capturas.archivo(c, nombre) ?: throw FileNotFoundException()
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val fila = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> nombre
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray()
        return MatrixCursor(cols, 1).apply { addRow(fila) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
