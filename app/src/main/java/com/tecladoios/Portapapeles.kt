package com.tecladoios

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Historial de lo que copias (solo en el celular, nunca sale de él).
 * Guarda los últimos [MAXIMO] textos; lo marcado como sensible (contraseñas) no se guarda.
 */
class Portapapeles(c: Context) {

    class Recorte(val texto: String, val ms: Long)

    companion object {
        const val MAXIMO = 25
        /** Textos más largos que esto no se guardan (para no gastar memoria) */
        private const val LARGO_MAX = 5000
        /** Lo recién copiado aparece en la barra durante este tiempo */
        const val EN_BARRA_MS = 3 * 60_000L
        private const val SENSIBLE = "android.content.extra.IS_SENSITIVE"
    }

    private val app = c.applicationContext
    private val archivo = File(app.noBackupFilesDir, "portapapeles.json")
    private var lista: ArrayList<Recorte>? = null

    fun recortes(): List<Recorte> = cargar()

    private fun cargar(): ArrayList<Recorte> {
        lista?.let { return it }
        val r = ArrayList<Recorte>()
        try {
            if (archivo.exists()) {
                val a = JSONArray(archivo.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    r.add(Recorte(o.getString("t"), o.optLong("ms")))
                }
            }
        } catch (_: Throwable) { }
        lista = r
        return r
    }

    private fun guardar() {
        val l = lista ?: return
        try {
            val a = JSONArray()
            for (x in l) a.put(JSONObject().put("t", x.texto).put("ms", x.ms))
            archivo.writeText(a.toString())
        } catch (_: Throwable) { }
    }

    /** Lee lo que hay copiado ahora; devuelve true si era nuevo y se agregó */
    fun leerSistema(): Boolean {
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = try { cm.primaryClip } catch (_: Throwable) { null } ?: return false
        val desc = clip.description
        if (desc.extras?.getBoolean(SENSIBLE, false) == true) return false
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return false
        if (clip.itemCount == 0) return false
        val t = try { clip.getItemAt(0).coerceToText(app)?.toString() } catch (_: Throwable) { null } ?: return false
        return agregar(t, desc.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis())
    }

    private fun agregar(t: String, ms: Long): Boolean {
        if (t.isBlank() || t.length > LARGO_MAX) return false
        val l = cargar()
        // Si ya estaba arriba, no es nuevo; si estaba más abajo, sube
        if (l.firstOrNull()?.texto == t) return false
        l.removeAll { it.texto == t }
        l.add(0, Recorte(t, ms))
        while (l.size > MAXIMO) l.removeAt(l.size - 1)
        guardar()
        return true
    }

    fun quitar(t: String) { if (cargar().removeAll { it.texto == t }) guardar() }

    fun borrarTodo() { lista = ArrayList(); archivo.delete() }

    /** Suelta la lista de la memoria (se vuelve a leer del archivo al necesitarla) */
    fun soltar() { lista = null }
}
