package com.tecladoios

import kotlin.math.ln

/**
 * Dónde está cada letra en el teclado (en píxeles). Lo llena el teclado cada vez que cambia
 * de tamaño; el motor lo usa para saber qué letra estaba \"cerca\" de cada toque.
 */
class Geometria {
    val x = FloatArray(27)
    val y = FloatArray(27)
    val hay = BooleanArray(27)
    @Volatile var ancho = 1f
    @Volatile var alto = 1f

    fun poner(ch: Char, cx: Float, cy: Float) {
        val i = Texto.indice(ch)
        if (i >= 0) { x[i] = cx; y[i] = cy; hay[i] = true }
    }

    /** Distancia (en anchos de tecla) entre un toque y el centro de una letra */
    fun distancia(px: Float, py: Float, ch: Char): Float {
        val i = Texto.indice(ch)
        if (i < 0 || !hay[i]) return 3f
        val dx = (px - x[i]) / ancho
        val dy = (py - y[i]) / alto * 0.85f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun centroX(ch: Char) = Texto.indice(ch).let { if (it >= 0) x[it] else Float.NaN }
    fun centroY(ch: Char) = Texto.indice(ch).let { if (it >= 0) y[it] else Float.NaN }
}

/** Una sugerencia: la palabra y qué tan buena es (menos es mejor) */
class Opcion(val texto: String, val puntaje: Float, val esCorreccion: Boolean, val completa: Boolean)

/** Lo que el motor propone para la palabra que estás escribiendo */
class Propuesta(
    val opciones: List<Opcion>,
    /** Si no es null, al tocar espacio o puntuación se cambia lo escrito por esta */
    val autocorregir: String?,
    /** La palabra escrita existe tal cual (o la aprendiste) */
    val valida: Boolean,
)

/**
 * El cerebro del teclado: busca palabras parecidas a lo que escribiste teniendo en cuenta
 * DÓNDE tocaste (una tecla vecina cuesta poco, una lejana mucho), completa palabras, predice
 * la siguiente y decide cuándo corregir solo.
 */
class Motor(
    val dic: Diccionario,
    val errores: Errores,
    val aprendido: Aprendizaje,
    private val bigramas: Map<String, Array<String>>,
    val geo: Geometria,
) {
    @Volatile var contactos: Map<String, String> = emptyMap()
    @Volatile var separarPegadas = true

    // Filas de costo para la búsqueda en el árbol (una por profundidad)
    private val filas = Array(64) { FloatArray(40) }
    private val letrasNodo = CharArray(64)

    private class Cand(val id: Int, var costo: Float, var completa: Boolean)

    // ---------- Costos por toque ----------

    private var entrada = CharArray(0)
    private var tx = FloatArray(0)
    private var ty = FloatArray(0)
    private var conToque = BooleanArray(0)

    /** Costos ya calculados: [posición * 27 + letra] (se arma una vez por palabra, no en cada nodo) */
    private var tabla = FloatArray(0)

    private fun armarTabla(n: Int) {
        if (tabla.size < n * 27) tabla = FloatArray(maxOf(n * 27, 27 * 40))
        for (i in 0 until n) for (k in 0 until 27) tabla[i * 27 + k] = costoCalculado(i, LETRAS[k])
    }

    private fun costoCambio(i: Int, c: Char): Float {
        val k = Texto.indice(c)
        return if (k >= 0) tabla[i * 27 + k] else costoCalculado(i, c)
    }

    private fun costoCalculado(i: Int, c: Char): Float {
        val e = entrada[i]
        if (e == c) {
            // La letra escrita: casi gratis (un poco más si el toque fue muy al borde)
            return if (conToque[i]) (geo.distancia(tx[i], ty[i], c) - 0.45f).coerceIn(0f, 0.25f) else 0f
        }
        val d = if (conToque[i]) geo.distancia(tx[i], ty[i], c) else {
            val ex = geo.centroX(e); val ey = geo.centroY(e)
            if (ex.isNaN()) 2f else geo.distancia(ex, ey, c)
        }
        return ((d - 0.35f) * 0.75f).coerceIn(0.15f, 1.0f)
    }

    private fun costoSobra(i: Int): Float {
        // Letra de más: si es la misma que la anterior (rebote) cuesta poco
        if (i > 0 && entrada[i] == entrada[i - 1]) return 0.35f
        return 0.8f
    }

    private val COSTO_FALTA = 0.85f
    private val COSTO_CRUCE = 0.55f

    private fun presupuesto(n: Int) = when {
        n <= 1 -> 0.3f
        n == 2 -> 0.9f
        n <= 4 -> 1.3f
        n <= 7 -> 1.9f
        else -> 2.5f
    }

    // ---------- Búsqueda con errores ----------

    @Synchronized
    private fun buscar(clave: String, xs: FloatArray?, ys: FloatArray?, conCompletar: Boolean): HashMap<Int, Cand> {
        val n = minOf(clave.length, 38)
        entrada = clave.toCharArray(0, n)
        tx = FloatArray(n); ty = FloatArray(n); conToque = BooleanArray(n)
        if (xs != null && ys != null) for (i in 0 until n) if (i < xs.size && !xs[i].isNaN()) {
            tx[i] = xs[i]; ty[i] = ys[i]; conToque[i] = true
        }
        armarTabla(n)
        val res = HashMap<Int, Cand>(64)
        val tope = presupuesto(n)
        val f0 = filas[0]
        f0[0] = 0f
        for (j in 1..n) f0[j] = f0[j - 1] + costoSobra(j - 1)
        recorrer(0, 0, n, tope, res, conCompletar)
        return res
    }

    private fun recorrer(nodo: Int, prof: Int, n: Int, tope: Float, res: HashMap<Int, Cand>, conCompletar: Boolean) {
        var h = dic.hijo[nodo]
        while (h >= 0) {
            val c = dic.letra[h]
            val d = prof + 1
            if (d >= 63) { h = dic.hermano[h]; continue }
            letrasNodo[d] = c
            val prev = filas[prof]
            val fila = filas[d]
            fila[0] = prev[0] + COSTO_FALTA
            var minimo = fila[0]
            for (j in 1..n) {
                var v = prev[j - 1] + costoCambio(j - 1, c)
                val falta = prev[j] + COSTO_FALTA
                if (falta < v) v = falta
                val sobra = fila[j - 1] + costoSobra(j - 1)
                if (sobra < v) v = sobra
                // Letras cruzadas ("porqeu" → "porque")
                if (j >= 2 && d >= 2 && entrada[j - 1] == letrasNodo[d - 1] && entrada[j - 2] == c) {
                    val cruce = filas[d - 2][j - 2] + COSTO_CRUCE
                    if (cruce < v) v = cruce
                }
                fila[j] = v
                if (v < minimo) minimo = v
            }
            if (minimo <= tope) {
                val total = fila[n]
                val t = dic.terminal[h]
                if (t >= 0 && total <= tope) {
                    val previo = res[t]
                    if (previo == null) res[t] = Cand(t, total, false)
                    else if (total < previo.costo || previo.completa) { previo.costo = total; previo.completa = false }
                }
                // Completar: ya se \"gastó\" toda la entrada y sigue la palabra
                if (conCompletar && n >= 1 && total <= minOf(tope, 1.0f) && d >= n) {
                    val m = dic.mejor[h]
                    if (m != Int.MAX_VALUE && m != t) {
                        val extra = dic.claves[m].length - d
                        val costo = total + 0.55f + 0.08f * extra
                        val previo = res[m]
                        if (previo == null) res[m] = Cand(m, costo, true)
                        else if (previo.completa && costo < previo.costo) previo.costo = costo
                    }
                }
                recorrer(h, d, n, tope, res, conCompletar)
            }
            h = dic.hermano[h]
        }
    }

    // ---------- Puntaje ----------

    /** Bono de cada palabra que suele ir después de [contexto] (se calcula una vez por palabra escrita) */
    private fun mapaContexto(contexto: List<String>): Map<String, Float> {
        if (contexto.isEmpty()) return emptyMap()
        val m = HashMap<String, Float>()
        for ((w, v) in aprendido.predecir(contexto)) m[w] = 0.6f + 0.25f * ln(1f + v)
        bigramas[Texto.normal(contexto.last())]?.forEachIndexed { i, w -> m[w] = (m[w] ?: 0f) + 0.9f - i * 0.06f }
        return m
    }

    private fun bonoUso(clave: String): Float {
        val v = aprendido.veces(clave)
        return if (v <= 0) 0f else 0.35f * ln(1f + v)
    }

    private fun puntaje(costo: Float, penal: Float, contexto: Float, uso: Float) =
        costo * 2.4f + penal * 0.42f - contexto - uso

    // ---------- Lo principal ----------

    /**
     * Sugerencias para [escrito] (como se ve en pantalla) con los toques de cada letra
     * ([xs], [ys] en píxeles, NaN si no se sabe) y las palabras de antes ([contexto]).
     */
    fun proponer(escrito: String, xs: FloatArray?, ys: FloatArray?, contexto: List<String>, corregir: Boolean): Propuesta {
        if (escrito.isEmpty()) return Propuesta(emptyList(), null, true)
        val clave = Texto.normal(escrito)
        val soloLetras = clave.all { it.isLetter() || it == '\'' }
        val formasExactas = dic.formas(clave)
        val propia = aprendido.conocida(clave)
        val contacto = contactos[clave]
        val valida = formasExactas.isNotEmpty() || propia != null || contacto != null

        val ctxBono = mapaContexto(contexto)
        val lista = ArrayList<Opcion>(12)
        val vistos = HashSet<String>()
        fun meter(t: String, p: Float, corr: Boolean, comp: Boolean) {
            val texto = if (dic.id(t).let { it >= 0 && dic.palabras[it][0].isUpperCase() }) t else Texto.conMayusculas(t, escrito)
            if (vistos.add(texto.lowercase())) lista.add(Opcion(texto, p, corr, comp))
        }

        if (soloLetras) {
            val cands = buscar(clave, xs, ys, true)
            for (c in cands.values) {
                val w = dic.palabras[c.id]
                val k = dic.claves[c.id]
                val p = puntaje(c.costo, dic.penal(c.id), (ctxBono[w.lowercase()] ?: 0f), bonoUso(k))
                meter(w, p, k != clave, c.completa)
            }
            // Las más usadas que empiezan exactamente así ("ho" → hola, hoy, hora)
            if (clave.length >= 2) {
                val nodo = dic.nodo(clave)
                dic.completar(nodo, 4) { id ->
                    val k = dic.claves[id]
                    if (k != clave) {
                        val costo = 0.55f + 0.08f * (k.length - clave.length)
                        meter(dic.palabras[id], puntaje(costo, dic.penal(id), (ctxBono[dic.palabras[id].lowercase()] ?: 0f), bonoUso(k)), false, true)
                    }
                }
            }
            // Palabras propias y contactos (pocas: se revisan una por una)
            val propias = synchronized(aprendido) { aprendido.propias.values.filter { it.veces >= 2 || it.fija }.toList() }
            for (pp in propias) {
                val k = Texto.normal(pp.texto)
                if (!candidataRapida(clave, k)) continue
                val costo = parecido(clave, k, xs, ys) ?: continue
                meter(pp.texto, puntaje(costo, 1.5f, (ctxBono[pp.texto.lowercase()] ?: 0f), 0.35f * ln(1f + pp.veces)), k != clave, k.length > clave.length && k.startsWith(clave))
            }
            for ((k, nombre) in contactos) {
                if (!candidataRapida(clave, k)) continue
                val costo = parecido(clave, k, xs, ys) ?: continue
                meter(nombre, puntaje(costo, 2.0f, 0f, 0f), k != clave, k.length > clave.length)
            }
        }

        // Error conocido (tablas): manda sobre todo lo demás
        val conocido = if (!valida || formasExactas.isEmpty()) errores.buscar(clave) ?: errores.buscar(escrito.lowercase()) else null
        if (conocido != null && !aprendido.rechazada(escrito, conocido)) meter(conocido, -100f, true, false)

        lista.sortBy { it.puntaje }

        // ¿Corregir solo?
        var auto: String? = null
        if (corregir && soloLetras && clave.length >= 1) {
            val mejor = lista.firstOrNull { !it.completa }
            if (conocido != null && !aprendido.rechazada(escrito, conocido)) {
                auto = Texto.conMayusculas(conocido, escrito)
            } else if (!valida && mejor != null && mejor.esCorreccion) {
                val costoMax = when { clave.length <= 2 -> 0.9f; clave.length <= 4 -> 1.3f; else -> 1.9f }
                val id = dic.id(mejor.texto)
                val penal = if (id >= 0) dic.penal(id) else 1.5f
                // Puntaje de lo escrito si fuera una palabra rara: hay que superarlo
                val umbral = 3.2f + 0.3f * clave.length
                if (mejor.puntaje < umbral && penal < 9.5f && costoDe(mejor.texto, clave, xs, ys) <= costoMax &&
                    !aprendido.rechazada(escrito, mejor.texto)) auto = mejor.texto
            } else if (valida && formasExactas.isNotEmpty()) {
                // Existe, pero quizás solo con tilde ("cancion" → "canción")
                val forma = dic.palabras[formasExactas[0]]
                val sinTilde = formasExactas.any { dic.palabras[it].lowercase() == escrito.lowercase() }
                if (!sinTilde && forma.lowercase() != escrito.lowercase() && !aprendido.rechazada(escrito, forma))
                    auto = Texto.conMayusculas(forma, escrito)
            }
            // Palabras pegadas: "Yanpodemos" → "Ya podemos"
            if (auto == null && separarPegadas && !valida && clave.length >= 4) separar(clave, escrito)?.let { auto = it; meter(it, -1f, true, false); lista.sortBy { o -> o.puntaje } }
        }

        // Las formas con tilde de lo escrito también se ofrecen (esta / está)
        for (i in formasExactas) meter(dic.palabras[i], lista.firstOrNull()?.puntaje ?: 0f, false, false)
        lista.sortBy { it.puntaje }
        return Propuesta(lista.take(8), auto, valida)
    }

    /** Descarte barato antes de la comparación completa: largo parecido y mismo comienzo */
    private fun candidataRapida(clave: String, k: String): Boolean {
        if (k.isEmpty() || kotlin.math.abs(k.length - clave.length) > 3 && !(k.length > clave.length && k.startsWith(clave))) return false
        return k[0] == clave[0] || (k.length > 1 && clave.length > 1 && (k[1] == clave[1] || k[0] == clave[1]))
    }

    private fun costoDe(palabra: String, clave: String, xs: FloatArray?, ys: FloatArray?): Float =
        parecido(clave, Texto.normal(palabra), xs, ys) ?: 9f

    /** Costo de escribir [clave] queriendo [objetivo] (o null si son muy distintas) */
    @Synchronized
    fun parecido(clave: String, objetivo: String, xs: FloatArray?, ys: FloatArray?): Float? {
        val n = minOf(clave.length, 38)
        if (kotlin.math.abs(objetivo.length - n) > 3 || objetivo.length > 60) return null
        entrada = clave.toCharArray(0, n)
        tx = FloatArray(n); ty = FloatArray(n); conToque = BooleanArray(n)
        if (xs != null && ys != null) for (i in 0 until n) if (i < xs.size && !xs[i].isNaN()) { tx[i] = xs[i]; ty[i] = ys[i]; conToque[i] = true }
        armarTabla(n)
        val m = objetivo.length
        var prev2: FloatArray? = null
        var prev = FloatArray(n + 1)
        for (j in 1..n) prev[j] = prev[j - 1] + costoSobra(j - 1)
        var completo = prev[n]
        for (i in 1..m) {
            val c = objetivo[i - 1]
            val fila = FloatArray(n + 1)
            fila[0] = prev[0] + COSTO_FALTA
            for (j in 1..n) {
                var v = prev[j - 1] + costoCambio(j - 1, c)
                v = minOf(v, prev[j] + COSTO_FALTA, fila[j - 1] + costoSobra(j - 1))
                if (prev2 != null && j >= 2 && i >= 2 && entrada[j - 1] == objetivo[i - 2] && entrada[j - 2] == c)
                    v = minOf(v, prev2[j - 2] + COSTO_CRUCE)
                fila[j] = v
            }
            // Como prefijo (para completar)
            if (i >= n && fila[n] + 0.55f + 0.08f * (m - i) < completo) completo = fila[n] + 0.55f + 0.08f * (m - i)
            prev2 = prev; prev = fila
        }
        val costo = minOf(prev[n], completo)
        return if (costo <= presupuesto(n)) costo else null
    }

    /** Dos palabras pegadas, o con una letra junto al espacio (b, n, m, v, c) en vez del espacio */
    fun separar(clave: String, escrito: String): String? {
        var mejor: String? = null
        var mejorP = Float.MAX_VALUE
        fun probar(a: String, b: String, extra: Float) {
            if (a.isEmpty() || b.isEmpty()) return
            val ia = dic.formas(a).firstOrNull() ?: return
            val ib = dic.formas(b).firstOrNull() ?: return
            // Las piezas de una letra solo si son palabras comunes (a, y, o, e, u)
            if (a.length == 1 && a !in setOf("a", "y", "o", "e", "u")) return
            if (b.length == 1 && b !in setOf("a", "y", "o", "e", "u")) return
            val p = dic.penal(ia) + dic.penal(ib) + extra
            if (p < mejorP) { mejorP = p; mejor = dic.palabras[ia] + " " + dic.palabras[ib] }
        }
        for (i in 1 until clave.length) {
            probar(clave.substring(0, i), clave.substring(i), 0f)
            if (clave[i] in "bnmvc" && i + 1 < clave.length) probar(clave.substring(0, i), clave.substring(i + 1), 0.8f)
        }
        val m = mejor ?: return null
        if (mejorP > 11f) return null
        return Texto.conMayusculas(m, escrito)
    }

    /** Siguiente palabra probable después de [contexto] */
    fun predecir(contexto: List<String>, inicioDeFrase: Boolean): List<String> {
        val res = LinkedHashMap<String, Float>()
        for ((w, v) in aprendido.predecir(contexto)) res[w] = 2f + ln(1f + v)
        if (contexto.isNotEmpty()) bigramas[Texto.normal(contexto.last())]?.forEachIndexed { i, w ->
            res[w] = (res[w] ?: 0f) + 1.6f - i * 0.12f
        }
        if (res.size < 3) {
            val base = if (inicioDeFrase || contexto.isEmpty()) INICIO else GENERALES
            base.forEachIndexed { i, w -> if (!res.containsKey(w)) res[w] = 0.5f - i * 0.02f }
        }
        val orden = res.entries.sortedByDescending { it.value }.map { it.key }
        return orden.map { if (inicioDeFrase) Texto.capitalizar(it) else it }.distinct().take(3)
    }

    /** Igual que [confianzaDe] pero con el número de la palabra (más rápido al deslizar) */
    fun confianzaId(id: Int): Float = dic.penal(id) - bonoUso(dic.claves[id])

    /** Palabras para deslizar: lo usa el decodificador de gestos */
    fun confianzaDe(palabra: String): Float {
        val id = dic.id(palabra)
        val k = Texto.normal(palabra)
        return (if (id >= 0) dic.penal(id) else if (aprendido.conocida(k) != null) 2f else 12f) - bonoUso(k)
    }

    companion object {
        private val LETRAS = CharArray(27) { i -> if (i < 14) 'a' + i else if (i == 14) 'ñ' else 'o' + (i - 15) }
        val INICIO = listOf("hola", "ya", "gracias", "buenos", "sí", "no", "estimado", "ok", "qué", "te", "yo", "bueno")
        val GENERALES = listOf("de", "que", "y", "la", "el", "en", "a", "no", "con", "para", "por", "me")

        fun leerBigramas(lineas: Sequence<String>, en: HashMap<String, Array<String>>) {
            for (l in lineas) {
                if (l.isEmpty() || l[0] == '#') continue
                val i = l.indexOf(':')
                if (i <= 0) continue
                val k = Texto.normal(l.substring(0, i).trim())
                if (k.contains(' ')) continue
                val nuevos = l.substring(i + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }
                val previos = en[k]
                en[k] = if (previos == null) nuevos.toTypedArray() else (previos.toList() + nuevos).distinct().toTypedArray()
            }
        }
    }
}
