package com.tecladoios

import android.content.Context
import java.text.Normalizer

/**
 * Otros idiomas a la vez que el español (inglés y portugués), sin cambiar nada a mano.
 *
 * Cómo decide, palabra por palabra:
 *  - Cada palabra que terminas cuenta como "evidencia" de su idioma: "the", "you", "você" empujan
 *    hacia ese idioma; "pero", "también" hacia el español; las que existen en los dos ("no",
 *    "me", "a") no empujan. Así el teclado sabe en qué idioma va la frase en ese momento.
 *  - Una palabra que existe en otro idioma activo NO se corrige si la frase va en ese idioma o
 *    si es muy común allá ("love", "please"). En una frase claramente en español solo se
 *    respetan las más comunes, para que un dedazo como "hate" (por "hace") aún pueda corregirse.
 *  - Si la frase va en inglés, los dedazos se corrigen en inglés ("teh" → "the", "dont" →
 *    "don't") y nunca se fuerza una palabra en español rara.
 *  - Las sugerencias mezclan los idiomas según cuánto pesa cada uno en la frase.
 *
 * Los léxicos son listas por frecuencia (assets/ingles.txt, assets/portugues.txt). Se cargan
 * fuera del hilo de la pantalla y solo los idiomas activados en Ajustes.
 */
class Idiomas(context: Context) {

    private val app = context.applicationContext

    /** Un idioma: sus palabras por frecuencia y un índice para buscar por prefijo */
    private class Lexico(
        val codigo: String,
        /** Palabras como se escriben, de la más común a la menos */
        val palabras: Array<String>,
        /** Sin tildes y en minúsculas → posición (frecuencia) */
        val rango: HashMap<String, Int>,
        /** Posiciones ordenadas por la forma sin tildes (para el prefijo) */
        val orden: IntArray,
        val normales: Array<String>,
    )

    @Volatile private var lexicos: List<Lexico> = emptyList()
    private var activosAhora: Set<String> = emptySet()

    /** Qué tanto va la frase en cada idioma (-1 = nada, 1 = todo). Arranca en español. */
    private val tendencia = HashMap<String, Float>()

    val activo: Boolean get() = lexicos.isNotEmpty()

    /** Activa los idiomas pedidos ("en", "pt"). Carga lo que falte; llamar fuera de la pantalla. */
    @Synchronized
    fun activar(codigos: Set<String>) {
        if (codigos == activosAhora) return
        val nuevos = ArrayList<Lexico>()
        for (c in codigos) {
            val ya = lexicos.firstOrNull { it.codigo == c }
            nuevos.add(ya ?: cargar(c) ?: continue)
        }
        lexicos = nuevos
        activosAhora = codigos
        for (c in codigos) if (c !in tendencia) tendencia[c] = INICIO
    }

    /** Android pide memoria: se sueltan los léxicos (se vuelven a cargar al abrir el teclado) */
    @Synchronized
    fun soltar() {
        lexicos = emptyList()
        activosAhora = emptySet()
    }

    private fun archivo(c: String) = when (c) { "en" -> "ingles.txt"; "pt" -> "portugues.txt"; else -> null }

    private fun cargar(c: String): Lexico? {
        val nombre = archivo(c) ?: return null
        return try {
            val lista = ArrayList<String>(40000)
            val vistos = HashMap<String, Int>(60000)
            app.assets.open(nombre).bufferedReader().useLines { lineas ->
                for (crudo in lineas) {
                    val w = crudo.trim()
                    if (w.isEmpty() || w[0] == '#') continue
                    val n = normal(w)
                    if (n.isEmpty() || n in vistos) continue
                    vistos[n] = lista.size
                    lista.add(w)
                }
            }
            val palabras = lista.toTypedArray()
            val normales = Array(palabras.size) { normal(palabras[it]) }
            val orden = (palabras.indices).sortedWith { a, b -> normales[a].compareTo(normales[b]) }.toIntArray()
            Lexico(c, palabras, vistos, orden, normales)
        } catch (e: Exception) {
            Registro.anotar("cargar idioma $c", e)
            null
        }
    }

    // ---------- Qué idioma lleva la frase ----------

    /**
     * Terminaste [w]. [rangoEspanol]: su posición en el diccionario español (-1 si no existe).
     * Mueve la tendencia de cada idioma según la evidencia de la palabra.
     */
    @Synchronized
    fun notar(w: String, rangoEspanol: Int) {
        val lex = lexicos
        if (lex.isEmpty() || w.length < 2) return
        val n = normal(w)
        for (l in lex) {
            val r = l.rango[n] ?: -1
            val ev = evidencia(r, rangoEspanol)
            val antes = tendencia[l.codigo] ?: INICIO
            tendencia[l.codigo] = (antes * (1f - PASO) + ev * PASO).coerceIn(-1f, 1f)
        }
    }

    /** +1 la palabra es de ese idioma, -1 es del español, 0 no dice nada */
    private fun evidencia(rOtro: Int, rEs: Int): Float = when {
        rOtro < 0 && rEs < 0 -> 0f
        rOtro < 0 -> -1f
        rEs < 0 -> 1f
        // Existe en los dos: la que es mucho más común en un idioma empuja un poquito hacia él
        rOtro < 400 && rEs > 4000 -> 0.6f
        rEs < 400 && rOtro > 4000 -> -0.6f
        else -> 0f
    }

    /** Un campo nuevo: la tendencia vuelve hacia el español a medias (no se olvida de golpe) */
    @Synchronized
    fun campoNuevo() {
        for (k in tendencia.keys.toList()) tendencia[k] = ((tendencia[k] ?: INICIO) + INICIO) / 2f
    }

    /** Qué tanto va la frase en [codigo], de 0 (nada) a 1 (todo) */
    @Synchronized
    fun peso(codigo: String): Float = ((tendencia[codigo] ?: INICIO) + 1f) / 2f

    /** El idioma extranjero que más pesa ahora y cuánto */
    @Synchronized
    fun dominante(): Pair<String, Float>? {
        val lex = lexicos
        if (lex.isEmpty()) return null
        var mejor: String? = null
        var p = -1f
        for (l in lex) {
            val q = peso(l.codigo)
            if (q > p) { p = q; mejor = l.codigo }
        }
        return mejor?.let { it to p }
    }

    // ---------- Consultas ----------

    /** La mejor posición de [w] entre los idiomas activos (-1 si no existe en ninguno) */
    fun rango(w: String): Int {
        val n = normal(w)
        var mejor = -1
        for (l in lexicos) {
            val r = l.rango[n] ?: continue
            if (mejor < 0 || r < mejor) mejor = r
        }
        return mejor
    }

    fun existe(w: String) = rango(w) >= 0

    /**
     * ¿Se respeta [w] tal cual (no se corrige)? Solo para palabras que NO son españolas.
     * Más común en su idioma o más peso de ese idioma en la frase → más fácil de respetar.
     */
    @Synchronized
    fun protege(w: String): Boolean {
        val n = normal(w)
        for (l in lexicos) {
            val r = l.rango[n] ?: continue
            val p = peso(l.codigo)
            val limite = when {
                p >= 0.55f -> MAX_RESPETAR
                p >= 0.35f -> 25000
                p >= 0.2f -> 2500
                else -> 300 // frase claramente en español: solo las comunísimas ("the", "you")
            }
            if (r < limite) return true
        }
        return false
    }

    /**
     * Dedazo en otro idioma, solo si la frase va en ese idioma: "teh" → "the", "dont" → "don't".
     * Devuelve [NO_TOCAR] si la frase va claramente en otro idioma pero no hay una corrección
     * segura (así no se impone una palabra española absurda). null = que decida el español.
     */
    @Synchronized
    fun corregir(w: String): String? {
        val (cod, p) = dominante() ?: return null
        if (p < 0.6f) return null
        val l = lexicos.firstOrNull { it.codigo == cod } ?: return null
        val n = normal(w)
        if (cod == "en") CONTRACCIONES[n]?.let { return it }
        if (n.length < 3) return NO_TOCAR
        var mejor = -1
        fun probar(c: String) {
            val r = l.rango[c] ?: return
            if (r < MAX_CORREGIR && (mejor < 0 || r < mejor)) mejor = r
        }
        val abc = "abcdefghijklmnopqrstuvwxyz"
        // Una letra de más, de menos, cambiada o dos letras al revés
        for (i in n.indices) probar(n.removeRange(i, i + 1))
        for (i in 0 until n.length - 1) {
            val ch = n.toCharArray(); val t = ch[i]; ch[i] = ch[i + 1]; ch[i + 1] = t
            probar(String(ch))
        }
        for (i in n.indices) for (c in abc) if (c != n[i]) probar(n.substring(0, i) + c + n.substring(i + 1))
        for (i in 0..n.length) for (c in abc) probar(n.substring(0, i) + c + n.substring(i))
        return if (mejor >= 0) l.palabras[mejor] else NO_TOCAR
    }

    /** Palabras de los idiomas activos que empiezan con [prefijo]: (palabra, posición) */
    fun completar(prefijo: String, max: Int): List<Pair<String, Int>> {
        val np = normal(prefijo)
        if (np.isEmpty()) return emptyList()
        val res = ArrayList<Pair<String, Int>>()
        for (l in lexicos) {
            // Búsqueda binaria del primer normal >= prefijo
            var lo = 0
            var hi = l.orden.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (l.normales[l.orden[m]] < np) lo = m + 1 else hi = m
            }
            val mejores = ArrayList<Int>()
            var k = lo
            var vistas = 0
            while (k < l.orden.size && vistas < 4000) {
                val i = l.orden[k++]
                if (!l.normales[i].startsWith(np)) break
                vistas++
                if (l.normales[i] == np || l.palabras[i].lowercase() in GROSERIAS) continue
                mejores.add(i)
            }
            mejores.sort()
            for (i in mejores.take(max)) res.add(l.palabras[i] to i)
        }
        return res
    }

    companion object {
        /** Dice "la frase va en otro idioma: no corregir con el español" */
        const val NO_TOCAR = "\u0000"
        private const val INICIO = -0.6f
        private const val PASO = 0.42f
        private const val MAX_RESPETAR = 60000
        private const val MAX_CORREGIR = 20000
        private val MARCAS = Regex("\\p{M}+")

        fun normal(w: String): String {
            val l = w.lowercase().replace('’', '\'')
            val sinMarcas = Normalizer.normalize(l, Normalizer.Form.NFD).replace(MARCAS, "")
            return sinMarcas.filter { it.isLetter() || it == '\'' }
        }

        /** Inglés escrito sin apóstrofo (como se escribe rápido en el celular) */
        private val CONTRACCIONES = mapOf(
            "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
            "doesnt" to "doesn't", "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
            "werent" to "weren't", "havent" to "haven't", "hasnt" to "hasn't", "couldnt" to "couldn't",
            "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "im" to "I'm", "ive" to "I've",
            "youre" to "you're", "youve" to "you've", "theyre" to "they're", "thats" to "that's",
            "whats" to "what's", "theres" to "there's", "shes" to "she's",
            "itll" to "it'll", "youll" to "you'll", "hes" to "he's", "theyll" to "they'll",
        )

        /** No se sugieren (si las escribes completas, se respetan igual) */
        private val GROSERIAS = hashSetOf(
            "fuck", "fucking", "fucked", "shit", "bitch", "bitches", "asshole", "dick", "pussy", "cunt",
            "whore", "slut", "nigga", "nigger", "faggot", "porra", "caralho", "puta", "merda", "foda",
        )
    }
}
