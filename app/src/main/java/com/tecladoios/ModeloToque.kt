package com.tecladoios

import android.content.Context

/**
 * Tu manera de tocar (como el iPhone y Gboard): cada persona toca las teclas un poquito
 * corrida del centro (más abajo, más a la derecha con el pulgar derecho...). El teclado
 * aprende cuánto se corre tu dedo en cada letra y mueve un poquito el "centro" de esa
 * tecla hacia donde tú tocas, así se confunden menos las letras vecinas.
 *
 * Solo aprende de toques que cayeron dentro de la tecla (nunca de los que decidió la
 * predicción), y el ajuste tiene un tope: la tecla nunca se corre más de un cuarto.
 */
class ModeloToque(context: Context) {
    private val prefs = Ajustes.prefs(context)
    private val dx = FloatArray(LETRAS)
    private val dy = FloatArray(LETRAS)
    private val n = IntArray(LETRAS)
    private var revision = prefs.getInt(CLAVE_REV, 0)
    private var sinGuardar = 0
    /** Cuánto se corre el dedo hacia arriba o abajo en general (para elegir la fila) */
    var dyGeneral = 0f
        private set

    init {
        leer(prefs.getString(CLAVE, null))
    }

    /** Cuánto creerle a lo aprendido de esa letra (con pocos toques, casi nada) */
    private fun peso(l: Int) = minOf(1f, n[l] / 25f)

    /** Corrimiento de tu dedo en esa letra, en anchos de tecla (0 si aún no se sabe) */
    fun offX(l: Int): Float = if (l in 0 until LETRAS) dx[l] * peso(l) else 0f

    /** Corrimiento hacia arriba/abajo, en alturas de fila */
    fun offY(l: Int): Float = if (l in 0 until LETRAS) dy[l] * peso(l) else 0f

    /** Anota un toque en la letra [l]: [fx] y [fy] desde el centro de la tecla (en teclas y filas) */
    fun aprender(l: Int, fx: Float, fy: Float) {
        if (l !in 0 until LETRAS || Math.abs(fx) > 0.75f || Math.abs(fy) > 0.75f) return
        if (n[l] < 100_000) n[l]++
        // Promedio que se mueve: rápido al principio, después cambia de a poquito
        val k = minOf(n[l], 150).toFloat()
        dx[l] = (dx[l] + (fx - dx[l]) / k).coerceIn(-MAX, MAX)
        dy[l] = (dy[l] + (fy - dy[l]) / k).coerceIn(-MAX, MAX)
        recalcular()
        if (++sinGuardar >= 120) guardar()
    }

    private fun recalcular() {
        var suma = 0f
        var total = 0f
        for (l in 0 until LETRAS) {
            val p = peso(l)
            suma += dy[l] * p
            total += p
        }
        dyGeneral = if (total > 0f) suma / total * minOf(1f, total / 8f) else 0f
    }

    /** Si lo restableciste en Ajustes mientras el teclado estaba abierto, se empieza de cero */
    fun revisar() {
        val rev = prefs.getInt(CLAVE_REV, 0)
        if (rev != revision) {
            revision = rev
            dx.fill(0f); dy.fill(0f); n.fill(0)
            recalcular()
        }
    }

    fun guardar() {
        sinGuardar = 0
        // Si lo restableciste en Ajustes, se empieza de cero (no se pisa con lo viejo)
        if (prefs.getInt(CLAVE_REV, 0) != revision) { revisar(); return }
        val sb = StringBuilder(LETRAS * 16)
        for (l in 0 until LETRAS) {
            if (l > 0) sb.append(';')
            sb.append(dx[l]).append(',').append(dy[l]).append(',').append(n[l])
        }
        prefs.edit().putString(CLAVE, sb.toString()).apply()
    }

    private fun leer(texto: String?) {
        if (texto.isNullOrEmpty()) return
        val partes = texto.split(';')
        for (l in 0 until minOf(LETRAS, partes.size)) {
            val v = partes[l].split(',')
            if (v.size != 3) continue
            dx[l] = (v[0].toFloatOrNull() ?: 0f).coerceIn(-MAX, MAX)
            dy[l] = (v[1].toFloatOrNull() ?: 0f).coerceIn(-MAX, MAX)
            n[l] = v[2].toIntOrNull() ?: 0
        }
        recalcular()
    }

    companion object {
        private const val LETRAS = 27
        private const val MAX = 0.25f
        private const val CLAVE = "modelo_toque"
        private const val CLAVE_REV = "modelo_toque_rev"

        /** Ajustes → Restablecer: se olvida tu manera de tocar */
        fun restablecer(c: Context) {
            val p = Ajustes.prefs(c)
            p.edit().remove(CLAVE).putInt(CLAVE_REV, p.getInt(CLAVE_REV, 0) + 1).apply()
        }
    }
}
