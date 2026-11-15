package com.tecladoios

import android.content.Context

/**
 * Medidor de velocidad: cuánto tarda el teclado en procesar cada tecla y en mostrar las
 * sugerencias. Guarda las últimas 200 medidas de cada uno (solo tiempos, nunca lo que
 * escribes) y se ve en Ajustes → Medidor de velocidad.
 */
object Medidor {
    private const val ARCHIVO = "tecladoios_medidor"

    private class Ventana {
        val v = FloatArray(200); var n = 0; var i = 0
        fun meter(ms: Float) { v[i] = ms; i = (i + 1) % v.size; if (n < v.size) n++ }
        fun ordenadas() = v.copyOf(n).also { it.sort() }
        fun texto(): String {
            if (n == 0) return ""
            val o = ordenadas()
            return "%.1f|%.1f|%.1f|%d".format(java.util.Locale.US, o.average(), o[(n * 0.95f).toInt().coerceAtMost(n - 1)], o[n - 1], n)
        }
    }

    private val teclas = Ventana()
    private val sugerencias = Ventana()
    private var sinGuardar = 0

    /** Se llama en el hilo principal con lo que tardó una tecla (en nanosegundos) */
    fun tecla(ns: Long) { teclas.meter(ns / 1_000_000f); sinGuardar++ }

    /** Desde que se pide hasta que la barra muestra las sugerencias (en nanosegundos) */
    fun sugerencia(ns: Long) { sugerencias.meter(ns / 1_000_000f) }

    fun guardarSiToca(c: Context) { if (sinGuardar >= 40) guardar(c) }

    fun guardar(c: Context) {
        sinGuardar = 0
        try {
            c.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit()
                .putString("teclas", teclas.texto()).putString("sugerencias", sugerencias.texto()).apply()
        } catch (_: Throwable) { }
    }

    fun reiniciar(c: Context) {
        teclas.n = 0; teclas.i = 0; sugerencias.n = 0; sugerencias.i = 0
        c.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Resumen para leer en Ajustes */
    fun resumen(c: Context): String {
        val p = c.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        fun linea(nombre: String, s: String?, bueno: Float): String {
            val x = s?.split('|') ?: return "$nombre: aún sin medidas (escribe un rato)"
            if (x.size < 4) return "$nombre: aún sin medidas (escribe un rato)"
            val prom = x[0].toFloatOrNull() ?: 0f
            val nota = if (prom <= bueno) "rápido" else if (prom <= bueno * 2.5f) "normal" else "lento"
            return "$nombre ($nota)\n  Promedio: ${x[0]} ms\n  95 % de las veces: menos de ${x[1]} ms\n  La más lenta: ${x[2]} ms\n  Medidas: ${x[3]}"
        }
        return linea("Cada tecla", p.getString("teclas", null), 4f) + "\n\n" +
            linea("Sugerencias en la barra", p.getString("sugerencias", null), 16f) +
            "\n\nReferencia: una pantalla de 120 Hz dibuja un cuadro cada 8 ms. Por debajo de eso, la respuesta se siente instantánea."
    }
}
