package com.tecladoios

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
 * Donde la app de cámara deja la foto del escáner. No es pública: solo la cámara que abre el
 * escáner puede escribir ahí, y solo ese archivo. La foto se borra apenas se lee el texto.
 */
class EscanerProvider : ContentProvider() {

    companion object {
        const val AUTORIDAD = "com.tecladoios.escaner"
        private const val NOMBRE = "foto.jpg"
        fun uri(): Uri = Uri.parse("content://$AUTORIDAD/$NOMBRE")
        fun archivo(c: Context): File = File(File(c.cacheDir, "escaner").apply { mkdirs() }, NOMBRE)
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val c = context ?: throw FileNotFoundException()
        if (uri.lastPathSegment != NOMBRE) throw FileNotFoundException()
        return ParcelFileDescriptor.open(archivo(c), ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = context ?: throw FileNotFoundException()
        val f = archivo(c)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val fila = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> NOMBRE
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
