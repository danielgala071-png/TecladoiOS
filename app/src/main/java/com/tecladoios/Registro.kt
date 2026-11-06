package com.tecladoios

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de errores que el teclado se "traga" para no cerrarse (no poder leer el
 * diccionario, no poder guardar lo aprendido...). Antes pasaban en silencio; ahora quedan
 * anotados y se pueden ver y copiar en Ajustes → Registro de errores.
 * Se guardan solo los últimos 40 y nunca el texto que escribes.
 */
object Registro {
    private const val ARCHIVO = "errores.txt"
    private const val MAX = 40

    @Volatile private var dir: File? = null
    private var ultimaClave = ""
    private var ultimaHora = 0L

    fun iniciar(c: Context) {
        dir = c.applicationContext.filesDir
    }

    fun anotar(donde: String, e: Throwable) {
        try {
            val d = dir ?: return
            val clave = donde + "|" + e.javaClass.name + "|" + e.message
            synchronized(this) {
                // El mismo error repetido muchas veces seguidas se anota una sola vez
                val ahora = System.currentTimeMillis()
                if (clave == ultimaClave && ahora - ultimaHora < 60_000L) return
                ultimaClave = clave
                ultimaHora = ahora
                val lugar = e.stackTrace.firstOrNull { it.className.startsWith("com.tecladoios") }
                val fecha = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(ahora))
                val linea = buildString {
                    append(fecha).append(" · ").append(donde).append(": ")
                    append(e.javaClass.simpleName)
                    e.message?.let { append(" — ").append(it.take(200)) }
                    lugar?.let { append(" (").append(it.fileName).append(':').append(it.lineNumber).append(')') }
                }.replace('\n', ' ')
                val f = File(d, ARCHIVO)
                val previas = if (f.exists()) f.readLines(Charsets.UTF_8).takeLast(MAX - 1) else emptyList()
                f.writeText((previas + linea).joinToString("\n"), Charsets.UTF_8)
            }
        } catch (_: Throwable) { }
    }

    fun leer(c: Context): String = try {
        val f = File(c.applicationContext.filesDir, ARCHIVO)
        if (f.exists()) f.readText(Charsets.UTF_8) else ""
    } catch (_: Throwable) { "" }

    fun borrar(c: Context) {
        try { File(c.applicationContext.filesDir, ARCHIVO).delete() } catch (_: Throwable) { }
    }
}
