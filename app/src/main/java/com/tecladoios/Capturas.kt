package com.tecladoios

import android.Manifest
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.inputmethod.EditorInfo
import java.io.File

/**
 * Capturas de pantalla recientes para la barra de sugerencias.
 *
 * Solo mira las imágenes de la carpeta de capturas de los últimos minutos (nunca recorre tu
 * galería) y solo si diste permiso en Ajustes. La miniatura vive en memoria; para enviarla,
 * la captura se copia a la carpeta privada del teclado y se entrega a la app con un permiso
 * de lectura temporal.
 */
object Capturas {

    class Captura(val uri: Uri, val mime: String, val ms: Long)

    /** Cuánto tiempo se ofrece una captura en la barra */
    const val VIGENCIA_MS = 3 * 60 * 1000L
    private const val CARPETA = "capturas"
    private const val GUARDAR_MAX = 4

    fun permiso(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun tienePermiso(c: Context) =
        c.checkSelfPermission(permiso()) == PackageManager.PERMISSION_GRANTED

    /** La captura más reciente de los últimos minutos, o null */
    fun buscar(c: Context): Captura? {
        if (!tienePermiso(c)) return null
        val desde = (System.currentTimeMillis() - VIGENCIA_MS) / 1000L
        val proy = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val sel = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND (" +
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf(desde.toString(), "%screenshot%", "screenshot%")
        return try {
            c.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proy, sel, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cur ->
                if (!cur.moveToFirst()) return null
                val id = cur.getLong(0)
                val ms = cur.getLong(1) * 1000L
                val mime = cur.getString(2) ?: "image/png"
                Captura(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), mime, ms)
            }
        } catch (e: Exception) {
            Registro.anotar("buscar captura", e)
            null
        }
    }

    /** Miniatura chiquita para la barra (el sistema ya la tiene hecha: es rápida) */
    fun miniatura(c: Context, uri: Uri): Bitmap? = try {
        c.contentResolver.loadThumbnail(uri, Size(240, 240), null)
    } catch (e: Exception) {
        Registro.anotar("miniatura de captura", e)
        null
    }

    /** ¿La app donde escribes acepta imágenes pegadas desde el teclado? */
    fun aceptaImagen(info: EditorInfo?, mime: String): Boolean {
        val tipos = info?.contentMimeTypes ?: return false
        return tipos.any { ClipDescription.compareMimeTypes(mime, it) }
    }

    /**
     * Copia la captura a la carpeta privada del teclado y devuelve su dirección para
     * entregarla. Se guardan solo las últimas (la app que la recibe puede leerla un rato
     * después, al enviar); las más viejas se borran.
     */
    fun copiarParaEnviar(c: Context, cap: Captura): Uri? = try {
        val dir = File(c.cacheDir, CARPETA).apply { mkdirs() }
        val ext = when (cap.mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        val archivo = File(dir, "captura_${cap.ms}.$ext")
        if (!archivo.exists()) {
            c.contentResolver.openInputStream(cap.uri)?.use { ent ->
                archivo.outputStream().use { sal -> ent.copyTo(sal) }
            } ?: throw IllegalStateException("no se pudo abrir")
        }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(GUARDAR_MAX)?.forEach { it.delete() }
        CapturaProvider.uriDe(archivo.name)
    } catch (e: Exception) {
        Registro.anotar("copiar captura", e)
        null
    }

    fun archivo(c: Context, nombre: String): File? {
        val dir = File(c.cacheDir, CARPETA)
        val f = File(dir, nombre)
        // Solo archivos de esa carpeta (nada de "../")
        return if (f.parentFile?.canonicalPath == dir.canonicalPath && f.exists()) f else null
    }
}
