package com.tecladoios

import java.io.BufferedReader

/**
 * Diccionario en un árbol de letras compacto (solo arreglos de números, sin objetos por
 * letra): buscar con errores, completar y sugerir es rápido y gasta poca memoria.
 *
 * Cada palabra tiene un número: 0 es la más usada. El costo de frecuencia sale de ese número.
 * Las claves del árbol van sin tildes y en minúsculas; la palabra guardada sí las tiene.
 */
class Diccionario private constructor(
    val palabras: Array<String>,
    val claves: Array<String>,
    private val penal: FloatArray,
    // Árbol
    val letra: CharArray,
    val hijo: IntArray,
    val hermano: IntArray,
    val terminal: IntArray,
    val mejor: IntArray,
    private val otras: HashMap<String, IntArray>,
) {
    val total get() = palabras.size

    /** Costo por ser poco común (0 = de las más usadas) */
    fun penal(id: Int): Float = penal[id]

    /** Nodo del árbol para una clave exacta o -1 */
    fun nodo(clave: CharSequence): Int {
        var n = 0
        for (ch in clave) {
            var h = hijo[n]
            while (h >= 0 && letra[h] != ch) h = hermano[h]
            if (h < 0) return -1
            n = h
        }
        return n
    }

    /** Todas las palabras con esa clave (\"esta\" → está, esta, ésta), de más a menos usada */
    fun formas(clave: String): IntArray {
        otras[clave]?.let { return it }
        val n = nodo(clave)
        if (n < 0 || terminal[n] < 0) return IntArray(0)
        return intArrayOf(terminal[n])
    }

    fun existe(clave: String) = formas(clave).isNotEmpty()

    fun id(palabra: String): Int {
        val f = formas(Texto.normal(palabra))
        for (i in f) if (palabras[i] == palabra) return i
        for (i in f) if (palabras[i].equals(palabra, ignoreCase = true)) return i
        return -1
    }

    /** Las [k] palabras más usadas que empiezan en el nodo [desde] */
    fun completar(desde: Int, k: Int, salida: (Int) -> Unit) {
        if (desde < 0) return
        // Cola por número de palabra: nodos (positivos) y palabras (codificadas como -1 - id)
        val cola = java.util.PriorityQueue<Long>(32)
        fun meter(clave: Int, valor: Int) { cola.add((clave.toLong() shl 32) or (valor.toLong() and 0xFFFFFFFFL)) }
        meter(mejor[desde], desde)
        var dados = 0
        while (cola.isNotEmpty() && dados < k) {
            val x = cola.poll()!!
            val v = (x and 0xFFFFFFFFL).toInt()
            if (v < 0) { salida(-1 - v); dados++; continue }
            if (terminal[v] >= 0) meter(terminal[v], -1 - terminal[v])
            var h = hijo[v]
            while (h >= 0) { if (mejor[h] < Int.MAX_VALUE) meter(mejor[h], h); h = hermano[h] }
        }
    }

    companion object {
        private val RAIZ_BASE = Math.log(30.0).toFloat()

        class Fuente(val lector: () -> BufferedReader?, val extraPenal: Float, val multiplica: Int)

        /**
         * Arma el diccionario. [principal] da las palabras en orden de uso (la primera es la
         * más común); [otras] son otros idiomas, que pesan menos.
         */
        fun armar(principal: List<String>, otrosIdiomas: List<Pair<List<String>, Float>>): Diccionario {
            // 1) Palabras con su costo, sin repetir
            val costo = HashMap<String, Float>(principal.size + 60000)
            principal.forEachIndexed { r, w ->
                if (w.isNotEmpty() && !costo.containsKey(w))
                    costo[w] = (Math.log(r + 30.0).toFloat() - RAIZ_BASE)
            }
            for ((lista, extra) in otrosIdiomas) {
                lista.forEachIndexed { r, w ->
                    if (w.isEmpty()) return@forEachIndexed
                    val c = Math.log(r * 3.0 + 30.0).toFloat() - RAIZ_BASE + extra
                    val previo = costo[w]
                    if (previo == null || c < previo) costo[w] = c
                }
            }
            // 2) Número de cada palabra = su lugar por costo
            val ordenadas = costo.entries.sortedBy { it.value }
            val n = ordenadas.size
            val palabras = Array(n) { ordenadas[it].key }
            val penal = FloatArray(n) { ordenadas[it].value }
            val claves = Array(n) { val p = palabras[it]; val c = Texto.normal(p); if (c == p) p else c }

            // 3) Árbol: se meten las claves en orden alfabético
            val porClave = (0 until n).sortedWith { a, b -> claves[a].compareTo(claves[b]) }
            var cap = 1 shl 16
            var letra = CharArray(cap)
            var hijo = IntArray(cap)
            var hermano = IntArray(cap)
            var terminal = IntArray(cap)
            var padre = IntArray(cap)
            var ultimo = IntArray(cap)
            var nodos = 1
            hijo[0] = -1; hermano[0] = -1; terminal[0] = -1; padre[0] = -1; ultimo[0] = -1
            val otras = HashMap<String, IntArray>()
            val camino = IntArray(64)
            var anterior = ""
            for (id in porClave) {
                val k = claves[id]
                if (k.isEmpty() || k.length >= 60) continue
                var comun = 0
                val lim = minOf(k.length, anterior.length)
                while (comun < lim && k[comun] == anterior[comun]) comun++
                if (comun == k.length && comun == anterior.length) {
                    // Misma clave: otra forma de la misma palabra (tildes o mayúsculas)
                    val nodo = camino[k.length]
                    val previo = terminal[nodo]
                    val lista = otras[k]?.toMutableList() ?: mutableListOf(previo)
                    lista.add(id)
                    lista.sort()
                    otras[k] = lista.toIntArray()
                    if (id < previo) terminal[nodo] = id
                    continue
                }
                camino[0] = 0
                var actual = camino[comun]
                for (i in comun until k.length) {
                    if (nodos == cap) {
                        cap *= 2
                        letra = letra.copyOf(cap); hijo = hijo.copyOf(cap); hermano = hermano.copyOf(cap)
                        terminal = terminal.copyOf(cap); padre = padre.copyOf(cap); ultimo = ultimo.copyOf(cap)
                    }
                    val nuevo = nodos++
                    letra[nuevo] = k[i]; hijo[nuevo] = -1; hermano[nuevo] = -1; terminal[nuevo] = -1
                    ultimo[nuevo] = -1; padre[nuevo] = actual
                    if (hijo[actual] < 0) hijo[actual] = nuevo else hermano[ultimo[actual]] = nuevo
                    ultimo[actual] = nuevo
                    actual = nuevo
                    camino[i + 1] = nuevo
                }
                terminal[actual] = id
                anterior = k
            }
            // 4) La palabra más usada debajo de cada nodo (para completar rápido)
            val mejor = IntArray(nodos) { if (terminal[it] >= 0) terminal[it] else Int.MAX_VALUE }
            for (i in nodos - 1 downTo 1) {
                val p = padre[i]
                if (mejor[i] < mejor[p]) mejor[p] = mejor[i]
            }
            return Diccionario(
                palabras, claves, penal,
                letra.copyOf(nodos), hijo.copyOf(nodos), hermano.copyOf(nodos), terminal.copyOf(nodos),
                mejor, otras
            )
        }
    }
}
