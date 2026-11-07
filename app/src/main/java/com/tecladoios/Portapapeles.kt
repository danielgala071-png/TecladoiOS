package com.tecladoios

/**
 * Historial del portapapeles: lo último que copiaste (hasta [MAX_RECIENTES]) y lo que fijaste
 * (tu correo, tu número, tu dirección...), que se queda siempre. Todo vive solo en el celular.
 *
 * Es lógica pura (sin Android) para poder probarla sola. Se guarda como texto simple:
 * cada elemento es "f|hora|texto" (f = 1 si está fijado), separados por un carácter invisible.
 */
class Portapapeles(texto: String?) {

    class Elemento(val texto: String, val hora: Long, val fijado: Boolean)

    private val lista = ArrayList<Elemento>()

    init {
        if (!texto.isNullOrEmpty()) {
            for (crudo in texto.split(SEP_ELEMENTO)) {
                val partes = crudo.split(SEP_CAMPO, limit = 3)
                if (partes.size != 3 || partes[2].isEmpty()) continue
                val hora = partes[1].toLongOrNull() ?: continue
                lista.add(Elemento(partes[2], hora, partes[0] == "1"))
            }
        }
    }

    /** Fijados primero; después lo más reciente. Lo no fijado y viejo ya no aparece. */
    fun elementos(ahora: Long = System.currentTimeMillis()): List<Elemento> =
        lista.filter { it.fijado || ahora - it.hora <= VIGENCIA }
            .sortedWith(compareByDescending<Elemento> { it.fijado }.thenByDescending { it.hora })

    /** Copiaste [t]: va arriba (si ya estaba, se mueve sin perder si estaba fijado) */
    fun agregar(t: String, ahora: Long = System.currentTimeMillis()): Boolean {
        val limpio = limpiar(t)
        if (limpio.isBlank() || limpio.length > MAX_LARGO) return false
        val previo = lista.firstOrNull { it.texto == limpio }
        lista.removeAll { it.texto == limpio }
        lista.add(Elemento(limpio, ahora, previo?.fijado == true))
        recortar(ahora)
        return true
    }

    /** Fija o suelta [t]. Devuelve cómo quedó (true = fijado). */
    fun alternarFijado(t: String): Boolean {
        val i = lista.indexOfFirst { it.texto == t }
        if (i < 0) return false
        val e = lista[i]
        val nuevo = !e.fijado
        if (nuevo && lista.count { it.fijado } >= MAX_FIJADOS) return false
        lista[i] = Elemento(e.texto, e.hora, nuevo)
        return nuevo
    }

    fun quitar(t: String) {
        lista.removeAll { it.texto == t }
    }

    private fun recortar(ahora: Long) {
        lista.removeAll { !it.fijado && ahora - it.hora > VIGENCIA }
        val sueltos = lista.filter { !it.fijado }.sortedByDescending { it.hora }
        if (sueltos.size > MAX_RECIENTES) {
            val sobran = sueltos.drop(MAX_RECIENTES).toSet()
            lista.removeAll { it in sobran }
        }
    }

    fun comoTexto(): String = lista.joinToString(SEP_ELEMENTO) {
        (if (it.fijado) "1" else "0") + SEP_CAMPO + it.hora + SEP_CAMPO + it.texto
    }

    companion object {
        const val MAX_RECIENTES = 10
        const val MAX_FIJADOS = 10
        const val MAX_LARGO = 2000
        /** Lo que no fijaste se va solo después de un día */
        const val VIGENCIA = 24L * 60 * 60 * 1000
        private const val SEP_ELEMENTO = "\u0002"
        private const val SEP_CAMPO = "\u0001"

        private fun limpiar(t: String) = t.replace(SEP_ELEMENTO, " ").replace(SEP_CAMPO, " ").trim()
    }
}
