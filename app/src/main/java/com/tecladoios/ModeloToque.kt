package com.tecladoios

import android.content.Context

/**
 * Tu manera de tocar (como el iPhone y Gboard): cada persona toca las teclas un poquito
 * corrida del centro (más abajo, más a la derecha con el pulgar derecho...). El teclado
 * aprende cuánto se corre tu dedo en cada letra y mueve un poquito el "centro" de esa
 * tecla hacia donde tú tocas, así se confunden menos las letras vecinas.
 *
 * Escribiendo rápido con dos pulgares el dedo cae distinto que escribiendo despacio (se
 * estira menos y queda más corto, sobre todo en las teclas lejos de cada pulgar). Por eso
 * aprende DOS maneras de tocar cada letra: la de siempre y la de cuando vas rápido, y usa
 * la que corresponde a cada toque.
 *
 * También aprende qué tan desparramados caen tus toques: si tus dedos caen siempre cerca
 * del centro, el toque manda más; si caen más sueltos (escribir rápido), la palabra que
 * estás escribiendo ayuda más a decidir la letra cerca del borde.
 *
 * Solo aprende de toques que cayeron dentro de la tecla (nunca de los que decidió la
 * predicción), y el ajuste tiene un tope: la tecla nunca se corre más de un cuarto.
 */
class ModeloToque(context: Context) {
    private val prefs = Ajustes.prefs(context)
    private val dx = FloatArray(LETRAS)
    private val dy = FloatArray(LETRAS)
    private val n = IntArray(LETRAS)
    /** Lo mismo, solo con los toques de cuando escribes rápido */
    private val rdx = FloatArray(LETRAS)
    private val rdy = FloatArray(LETRAS)
    private val rn = IntArray(LETRAS)
    /** Qué tan sueltos caen tus toques (promedio de la distancia² a tu centro), despacio y rápido */
    private var disp = 0f
    private var dispN = 0
    private var dispR = 0f
    private var dispRN = 0
    private var revision = prefs.getInt(CLAVE_REV, 0)
    private var sinGuardar = 0
    /** Cuánto se corre el dedo hacia arriba o abajo en general (para elegir la fila) */
    var dyGeneral = 0f
        private set

    init {
        leer(prefs.getString(CLAVE, null), dx, dy, n)
        leer(prefs.getString(CLAVE_RAPIDO, null), rdx, rdy, rn)
        leerDispersion(prefs.getString(CLAVE_DISP, null))
        recalcular()
    }

    /** Cuánto creerle a lo aprendido de esa letra (con pocos toques, casi nada) */
    private fun peso(l: Int) = minOf(1f, n[l] / 25f)

    /** Cuánto creerle a lo aprendido al ir rápido (hace falta un poco más de toques) */
    private fun pesoRapido(l: Int) = if (rn[l] < 12) 0f else minOf(1f, rn[l] / 40f)

    /** Corrimiento de tu dedo en esa letra, en anchos de tecla (0 si aún no se sabe) */
    fun offX(l: Int, rapido: Boolean = false): Float {
        if (l !in 0 until LETRAS) return 0f
        val base = dx[l] * peso(l)
        if (!rapido) return base
        val w = pesoRapido(l)
        return if (w <= 0f) base else base + (rdx[l] - base) * w
    }

    /** Corrimiento hacia arriba/abajo, en alturas de fila */
    fun offY(l: Int, rapido: Boolean = false): Float {
        if (l !in 0 until LETRAS) return 0f
        val base = dy[l] * peso(l)
        if (!rapido) return base
        val w = pesoRapido(l)
        return if (w <= 0f) base else base + (rdy[l] - base) * w
    }

    /**
     * Qué tan sueltos caen tus toques, en anchos de tecla (lo normal es 0.33). Con pocos
     * toques aprendidos se queda en lo normal.
     */
    fun dispersion(rapido: Boolean = false): Float {
        val (v, cuantos) = if (rapido && dispRN >= 60) dispR to dispRN else disp to dispN
        if (cuantos < 60) return NORMAL
        val propia = Math.sqrt(v.toDouble()).toFloat().coerceIn(0.26f, 0.42f)
        val confianza = minOf(1f, cuantos / 300f)
        return NORMAL + (propia - NORMAL) * confianza
    }

    /**
     * Anota un toque en la letra [l]: [fx] y [fy] desde el centro de la tecla (en teclas y filas).
     * [rapido]: el toque llegó muy pegado al anterior (escribiendo rápido con dos dedos).
     */
    fun aprender(l: Int, fx: Float, fy: Float, rapido: Boolean = false) {
        if (l !in 0 until LETRAS || Math.abs(fx) > 0.75f || Math.abs(fy) > 0.75f) return
        // Qué tan lejos de TU centro cayó (antes de mover el centro con este toque)
        val ex = fx - offX(l, rapido)
        val ey = (fy - offY(l, rapido)) * 0.8f
        val d2 = ex * ex + ey * ey
        if (n[l] < 100_000) n[l]++
        // Promedio que se mueve: rápido al principio, después cambia de a poquito
        val k = minOf(n[l], 150).toFloat()
        dx[l] = (dx[l] + (fx - dx[l]) / k).coerceIn(-MAX, MAX)
        dy[l] = (dy[l] + (fy - dy[l]) / k).coerceIn(-MAX, MAX)
        if (dispN < 100_000) dispN++
        disp += (d2 - disp) / minOf(dispN, 400)
        if (rapido) {
            if (rn[l] < 100_000) rn[l]++
            val kr = minOf(rn[l], 150).toFloat()
            rdx[l] = (rdx[l] + (fx - rdx[l]) / kr).coerceIn(-MAX, MAX)
            rdy[l] = (rdy[l] + (fy - rdy[l]) / kr).coerceIn(-MAX, MAX)
            if (dispRN < 100_000) dispRN++
            dispR += (d2 - dispR) / minOf(dispRN, 400)
        }
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

    private fun borrarTodo() {
        dx.fill(0f); dy.fill(0f); n.fill(0)
        rdx.fill(0f); rdy.fill(0f); rn.fill(0)
        disp = 0f; dispN = 0; dispR = 0f; dispRN = 0
        recalcular()
    }

    /** Si lo restableciste en Ajustes mientras el teclado estaba abierto, se empieza de cero */
    fun revisar() {
        val rev = prefs.getInt(CLAVE_REV, 0)
        if (rev != revision) {
            revision = rev
            borrarTodo()
        }
    }

    fun guardar() {
        sinGuardar = 0
        // Si lo restableciste en Ajustes, se empieza de cero (no se pisa con lo viejo)
        if (prefs.getInt(CLAVE_REV, 0) != revision) { revisar(); return }
        prefs.edit()
            .putString(CLAVE, texto(dx, dy, n))
            .putString(CLAVE_RAPIDO, texto(rdx, rdy, rn))
            .putString(CLAVE_DISP, "$disp,$dispN,$dispR,$dispRN")
            .apply()
    }

    private fun texto(x: FloatArray, y: FloatArray, c: IntArray): String {
        val sb = StringBuilder(LETRAS * 16)
        for (l in 0 until LETRAS) {
            if (l > 0) sb.append(';')
            sb.append(x[l]).append(',').append(y[l]).append(',').append(c[l])
        }
        return sb.toString()
    }

    private fun leer(texto: String?, x: FloatArray, y: FloatArray, c: IntArray) {
        if (texto.isNullOrEmpty()) return
        val partes = texto.split(';')
        for (l in 0 until minOf(LETRAS, partes.size)) {
            val v = partes[l].split(',')
            if (v.size != 3) continue
            x[l] = (v[0].toFloatOrNull() ?: 0f).coerceIn(-MAX, MAX)
            y[l] = (v[1].toFloatOrNull() ?: 0f).coerceIn(-MAX, MAX)
            c[l] = (v[2].toIntOrNull() ?: 0).coerceAtLeast(0)
        }
    }

    private fun leerDispersion(texto: String?) {
        if (texto.isNullOrEmpty()) return
        val v = texto.split(',')
        if (v.size != 4) return
        disp = (v[0].toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        dispN = (v[1].toIntOrNull() ?: 0).coerceAtLeast(0)
        dispR = (v[2].toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        dispRN = (v[3].toIntOrNull() ?: 0).coerceAtLeast(0)
    }

    companion object {
        private const val LETRAS = 27
        private const val MAX = 0.25f
        /** Qué tan sueltos caen los toques de una persona cualquiera (en anchos de tecla) */
        private const val NORMAL = 0.33f
        private const val CLAVE = "modelo_toque"
        private const val CLAVE_RAPIDO = "modelo_toque_rapido"
        private const val CLAVE_DISP = "modelo_toque_disp"
        private const val CLAVE_REV = "modelo_toque_rev"

        /** Ajustes → Restablecer: se olvida tu manera de tocar */
        fun restablecer(c: Context) {
            val p = Ajustes.prefs(c)
            p.edit().remove(CLAVE).remove(CLAVE_RAPIDO).remove(CLAVE_DISP)
                .putInt(CLAVE_REV, p.getInt(CLAVE_REV, 0) + 1).apply()
        }
    }
}
