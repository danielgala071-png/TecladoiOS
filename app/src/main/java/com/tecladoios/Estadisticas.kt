package com.tecladoios

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Tus estadísticas de escritura (solo números, nada de lo que escribes): palabras, letras,
 * correcciones y el tiempo que pasas escribiendo, para sacar tu velocidad.
 */
object Estadisticas {
    private const val PALABRAS = "est_palabras"
    private const val LETRAS = "est_letras"
    private const val CORRECCIONES = "est_correcciones"
    private const val MS = "est_ms"
    private const val DESDE = "est_desde"

    private var palabras = 0L
    private var letras = 0L
    private var correcciones = 0L
    private var ms = 0L
    private var cargado = false
    private var ultimaTecla = 0L

    fun cargar(p: SharedPreferences) {
        if (cargado) return
        palabras = p.getLong(PALABRAS, 0); letras = p.getLong(LETRAS, 0)
        correcciones = p.getLong(CORRECCIONES, 0); ms = p.getLong(MS, 0)
        if (!p.contains(DESDE)) p.edit().putLong(DESDE, System.currentTimeMillis()).apply()
        cargado = true
    }

    /** Cada letra: suma el tiempo desde la anterior si fue hace poco (pausas largas no cuentan) */
    fun letra() {
        val ahora = SystemClock.uptimeMillis()
        val d = ahora - ultimaTecla
        if (d in 1..2500) ms += d
        ultimaTecla = ahora
        letras++
    }

    fun palabra(corregida: Boolean) { palabras++; if (corregida) correcciones++ }

    fun guardar(p: SharedPreferences) {
        if (!cargado) return
        p.edit().putLong(PALABRAS, palabras).putLong(LETRAS, letras)
            .putLong(CORRECCIONES, correcciones).putLong(MS, ms).apply()
    }

    fun reiniciar(p: SharedPreferences) {
        palabras = 0; letras = 0; correcciones = 0; ms = 0
        p.edit().remove(PALABRAS).remove(LETRAS).remove(CORRECCIONES).remove(MS)
            .putLong(DESDE, System.currentTimeMillis()).apply()
    }

    /** Texto para Ajustes (lee lo guardado: Ajustes puede abrirse sin el teclado) */
    fun resumen(p: SharedPreferences, masUsadas: List<Pair<String, Int>>): String {
        val pal = p.getLong(PALABRAS, 0); val let = p.getLong(LETRAS, 0)
        val cor = p.getLong(CORRECCIONES, 0); val t = p.getLong(MS, 0)
        val min = t / 60000.0
        val ppm = if (min > 0.5) (pal / min).toInt() else 0
        val desde = java.text.SimpleDateFormat("d/M/yyyy", java.util.Locale("es")).format(java.util.Date(p.getLong(DESDE, System.currentTimeMillis())))
        val sb = StringBuilder()
        sb.append("Desde el $desde\n\n")
        sb.append("Palabras escritas: ${"%,d".format(pal)}\n")
        sb.append("Letras tocadas: ${"%,d".format(let)}\n")
        sb.append("Velocidad: ${if (ppm > 0) "$ppm palabras por minuto" else "escribe un poco más"}\n")
        sb.append("Correcciones automáticas: ${"%,d".format(cor)}")
        if (pal > 0) sb.append(" (${(cor * 100 / pal)} % de las palabras)")
        sb.append("\nTiempo escribiendo: ${(t / 60000)} min\n")
        if (masUsadas.isNotEmpty()) {
            sb.append("\nTus palabras más usadas:\n")
            masUsadas.forEachIndexed { i, (w, n) -> sb.append("${i + 1}. $w ($n)\n") }
        }
        return sb.toString().trimEnd()
    }
}
