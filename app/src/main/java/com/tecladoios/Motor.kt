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
        // Ajustado con el banco de pruebas (toques simulados a dos dedos): un toque que cae
        // entre dos teclas cuesta un poco menos, como en el modelo de toques de Gboard
        val base = ((d - 0.45f) * 0.75f).coerceIn(0.15f, 1.0f)
        // Mis confusiones: si sueles poner esta letra en vez de la otra, cambiarla cuesta menos
        val ie = Texto.indice(e); val ic = Texto.indice(c)
        if (ie >= 0 && ic >= 0) {
            val desc = aprendido.descuento[ie * 27 + ic]
            if (desc > 0f) return (base * (1f - desc)).coerceAtLeast(0.06f)
        }
        return base
    }

    private fun costoSobra(i: Int): Float {
        // Letra de más: si es la misma que la anterior (rebote) cuesta poco
        if (i > 0 && entrada[i] == entrada[i - 1]) return 0.35f
        return 0.8f
    }

    // Letra que faltó ("naa" → "nada"): más barata. Medido: 93.0 → 93.6 % solas, 98.9 % en la barra
    private val COSTO_FALTA = 0.65f
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
        tresPalabras(contexto)?.forEachIndexed { i, w -> m[w] = (m[w] ?: 0f) + 1.5f - i * 0.07f }
        dosPalabras(contexto)?.forEachIndexed { i, w -> m[w] = (m[w] ?: 0f) + 1.2f - i * 0.07f }
        bigramas[Texto.normal(contexto.last())]?.forEachIndexed { i, w -> m[w] = (m[w] ?: 0f) + 0.9f - i * 0.06f }
        return m
    }

    private fun bonoUso(clave: String): Float {
        val v = aprendido.veces(clave)
        return if (v <= 0) 0f else 0.35f * ln(1f + v)
    }

    private fun puntaje(costo: Float, penal: Float, contexto: Float, uso: Float) =
        // El contexto (las palabras de antes) pesa el doble: si la frase lo pide, la corrección
        // elige la palabra que encaja (medido en el banco: 92.9 % → 93.3 %, 0 palabras buenas cambiadas)
        costo * 2.4f + penal * 0.42f - contexto * 2.0f - uso

    // ---------- Lo principal ----------

    /**
     * Sugerencias para [escrito] (como se ve en pantalla) con los toques de cada letra
     * ([xs], [ys] en píxeles, NaN si no se sabe) y las palabras de antes ([contexto]).
     */
    private val RISA = Regex("(?:j[aeiou]|ja|js|sj|jk|ks|sk|xd|ha|he|ji)+[jhsk]?|x+d+|j+a+j*|a*j+a+j+[aj]*")

    fun proponer(escrito: String, xs: FloatArray?, ys: FloatArray?, contexto: List<String>, corregir: Boolean): Propuesta {
        if (escrito.isEmpty()) return Propuesta(emptyList(), null, true)
        val clave = Texto.normal(escrito)
        // Risas (jaja, jajaja, jsjs, jeje, xd, ksks…) de cualquier largo: nunca se corrigen
        if ((clave.length >= 4 || clave.startsWith("xd")) && RISA.matches(clave)) return Propuesta(listOf(Opcion(escrito, 0f, false, false)), null, true)
        val soloLetras = clave.all { it.isLetter() || it == '\'' }
        val formasExactas = dic.formas(clave)
        val propia = aprendido.conocida(clave)
        val contacto = contactos[clave]
        var valida = formasExactas.isNotEmpty() || propia != null || contacto != null

        val ctxBono = mapaContexto(contexto)
        // Otros idiomas (como Gboard multilingüe): una palabra en inglés o portugués solo
        // compite de igual a igual si la frase ya va en ese idioma o si tú la usas
        val ctxFuera = contexto.lastOrNull()?.let { u -> dic.id(u).let { it >= 0 && dic.esExtranjera(it) } } == true
        fun extraFuera(id: Int, k: String): Float =
            if (!ctxFuera && dic.esExtranjera(id) && aprendido.veces(k) <= 0) FUERA else 0f
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
                val p = puntaje(c.costo, dic.penal(c.id), (ctxBono[w.lowercase()] ?: 0f), bonoUso(k)) + extraFuera(c.id, k)
                meter(w, p, k != clave, c.completa)
            }
            // Las más usadas que empiezan exactamente así ("ho" → hola, hoy, hora)
            if (clave.length >= 2) {
                val nodo = dic.nodo(clave)
                dic.completar(nodo, 4) { id ->
                    val k = dic.claves[id]
                    if (k != clave) {
                        val costo = 0.55f + 0.08f * (k.length - clave.length)
                        meter(dic.palabras[id], puntaje(costo, dic.penal(id), (ctxBono[dic.palabras[id].lowercase()] ?: 0f), bonoUso(k)) + extraFuera(id, k), false, true)
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

        // Error de dedo que el cerebro "aprendió" por repetirlo (se escapó dos o tres veces sin
        // corregir): si está a un toque de una palabra común, no cuenta como palabra tuya y se
        // corrige. Las que fijaste (o deshiciste una corrección) y las que usas mucho se respetan.
        if (valida && formasExactas.isEmpty() && contacto == null && propia != null && !propia.fija && propia.veces < DUDOSA) {
            val rival = lista.filter { it.esCorreccion && !it.completa }.minByOrNull { it.puntaje }
            if (rival != null) {
                val id = dic.id(rival.texto)
                val costo = costoDe(rival.texto, clave, xs, ys)
                if (id >= 0 && dic.penal(id) < 7.5f && costo <= 1.05f && !aprendido.rechazada(escrito, rival.texto)) {
                    valida = false
                    lista.removeAll { Texto.normal(it.texto) == clave }
                }
            }
        }

        // Error conocido (tablas): manda sobre todo lo demás
        val conocido = if (!valida || formasExactas.isEmpty()) errores.buscar(clave) ?: errores.buscar(escrito.lowercase()) else null
        if (conocido != null && !aprendido.rechazada(escrito, conocido)) meter(conocido, -100f, true, false)

        // Las que eliminaste no se sugieren ni corrigen (salvo que sea justo lo que escribiste)
        if (aprendido.eliminadas.isNotEmpty()) lista.removeAll { val k = Texto.normal(it.texto); k != clave && aprendido.eliminada(k) }
        lista.sortBy { it.puntaje }

        // ¿Corregir solo?
        var auto: String? = null
        var porVentaja: String? = null
        // Nunca se corrige sola hacia una palabra de otro idioma que no usas
        fun bloqueada(o: Opcion): Boolean {
            if (ctxFuera) return false
            val id = dic.id(o.texto)
            return id >= 0 && dic.esExtranjera(id) && aprendido.veces(Texto.normal(o.texto)) <= 0
        }
        if (corregir && soloLetras && clave.length >= 1) {
            val mejor = lista.firstOrNull { !it.completa && !bloqueada(it) }
            if (conocido != null && !aprendido.rechazada(escrito, conocido)) {
                auto = Texto.conMayusculas(conocido, escrito)
            } else if (!valida && mejor != null && mejor.esCorreccion) {
                // Más eficaz: acepta un poco más de error de dedo antes de corregir
                val costoMax = when { clave.length <= 2 -> 0.9f; clave.length <= 4 -> 1.45f; else -> 2.15f }
                val id = dic.id(mejor.texto)
                val penal = if (id >= 0) dic.penal(id) else 1.5f
                // Puntaje de lo escrito si fuera una palabra rara: hay que superarlo
                val umbral = 3.2f + 0.3f * clave.length
                // Lo que el contexto apoya ("buenos d..." → días) se puede corregir con más confianza
                val apoyo = (ctxBono[mejor.texto.lowercase()] ?: 0f).coerceAtMost(1.2f)
                val costo = costoDe(mejor.texto, clave, xs, ys)
                if (mejor.puntaje < umbral + apoyo && penal < 9.5f && costo <= costoMax + apoyo * 0.4f &&
                    !aprendido.rechazada(escrito, mejor.texto)) auto = mejor.texto
                // Palabra larga muy mal tecleada (dos dedos rápido): si una opción le saca mucha
                // ventaja a todas las demás, es esa (así decide Gboard: la más probable por lejos)
                if (auto == null && clave.length >= 5 && penal < 9f && !aprendido.rechazada(escrito, mejor.texto)) {
                    val segunda = lista.firstOrNull { it !== mejor && !it.completa && !bloqueada(it) }
                    val ventaja = (segunda?.puntaje ?: (mejor.puntaje + 9f)) - mejor.puntaje
                    if (ventaja >= 1.4f && mejor.puntaje < umbral + 2.5f && costo <= 2.4f + 0.12f * (clave.length - 6)) porVentaja = mejor.texto
                }
            } else if (valida && formasExactas.isNotEmpty()) {
                // Existe, pero quizás solo con tilde ("cancion" → "canción")
                val forma = dic.palabras[formasExactas[0]]
                val sinTilde = formasExactas.any { dic.palabras[it].lowercase() == escrito.lowercase() }
                if (!sinTilde && forma.lowercase() != escrito.lowercase() && !aprendido.rechazada(escrito, forma))
                    auto = Texto.conMayusculas(forma, escrito)
            }
            // Nombres con mayúscula: si esa palabra solo existe como nombre propio (en el
            // diccionario, en tus contactos o como tú la escribes), va con mayúscula: daniel → Daniel
            if (auto == null && valida && escrito.none { it.isUpperCase() }) {
                val nombre = when {
                    formasExactas.isNotEmpty() && formasExactas.all { dic.palabras[it][0].isUpperCase() } -> dic.palabras[formasExactas[0]]
                    formasExactas.isEmpty() && contacto != null && contacto.firstOrNull()?.isUpperCase() == true -> contacto
                    formasExactas.isEmpty() && propia != null && propia.texto.firstOrNull()?.isUpperCase() == true -> propia.texto
                    else -> null
                }
                if (nombre != null && nombre != escrito && Texto.normal(nombre) == clave && !aprendido.rechazada(escrito, nombre)) auto = nombre
            }
            // Palabras pegadas: "Yanpodemos" → "Ya podemos"
            if (auto == null && separarPegadas && !valida && clave.length >= 4) separar(clave, escrito)?.let { auto = it; meter(it, -1f, true, false); lista.sortBy { o -> o.puntaje } }
            // Solo si no eran palabras pegadas: la opción que les saca mucha ventaja a las demás
            if (auto == null) auto = porVentaja
        }

        // Las formas con tilde de lo escrito también se ofrecen (esta / está)
        for (i in formasExactas) meter(dic.palabras[i], lista.firstOrNull()?.puntaje ?: 0f, false, false)
        lista.sortBy { it.puntaje }
        // Siglas: «dni» → «DNI», «sunat» → «SUNAT» (y las tuyas en mayúsculas)
        return Siglas.aplicar(escrito, Propuesta(lista.take(8), auto, valida), aprendido, corregir)
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
    /** Lo que suele seguir a las DOS últimas palabras ("buenos días" → a, para…) */
    private fun dosPalabras(contexto: List<String>): Array<String>? =
        if (contexto.size >= 2) bigramas[Texto.normal(contexto[contexto.size - 2]) + " " + Texto.normal(contexto.last())] else null

    /**
     * Zona caliente: qué letra viene después de lo escrito, con su probabilidad (0..1, la
     * más probable = 1). Sale del diccionario: cada rama pesa según su palabra más usada.
     */
    fun letrasProbables(escrito: CharSequence): Map<Char, Float> {
        if (escrito.isEmpty() || escrito.length > 20) return emptyMap()
        val n = dic.nodo(Texto.normal(escrito.toString()))
        if (n < 0) return emptyMap()
        val pesos = HashMap<Char, Float>(8)
        var h = dic.hijo[n]
        while (h >= 0) {
            val id = dic.mejor[h]
            if (id in 0 until dic.total && !dic.esExtranjera(id)) {
                val p = kotlin.math.exp(-0.45f * dic.penal(id))
                pesos[dic.letra[h]] = maxOf(pesos[dic.letra[h]] ?: 0f, p)
            }
            h = dic.hermano[h]
        }
        val max = pesos.values.maxOrNull() ?: return emptyMap()
        if (max <= 0f) return emptyMap()
        // Solo las 3 más probables: el resto no cambia nada
        return pesos.entries.sortedByDescending { it.value }.take(3).associate { it.key to it.value / max }
    }

    /** Lo que suele seguir a las TRES últimas palabras ("me voy a" → dormir, casa…) */
    private fun tresPalabras(contexto: List<String>): Array<String>? =
        if (contexto.size >= 3) bigramas[contexto.takeLast(3).joinToString(" ") { Texto.normal(it) }] else null

    /**
     * Siguiente palabra, como el iPhone: primero lo que TÚ sueles escribir después de esas
     * palabras, luego las frases hechas de dos palabras, luego lo que suele seguir a la
     * última, y si falta, palabras de inicio o generales. Nunca repite la palabra anterior.
     */
    fun predecir(contexto: List<String>, inicioDeFrase: Boolean): List<String> =
        puntuar(contexto, inicioDeFrase).map { it.first }

    /**
     * Frase adelantada: si el contexto apoya con fuerza la siguiente palabra Y la que viene
     * después, devuelve las dos juntas ("nos vemos" → "mañana temprano"). Null si no hay
     * suficiente seguridad (nunca rellena con palabras genéricas).
     */
    fun frase(contexto: List<String>, inicioDeFrase: Boolean): String? {
        if (contexto.isEmpty()) return null
        val p1 = puntuar(contexto, inicioDeFrase).firstOrNull() ?: return null
        if (p1.second < 2.0f) return null
        val p2 = puntuar(contexto + p1.first, false).firstOrNull() ?: return null
        if (p2.second < 2.0f || Texto.normal(p2.first) == Texto.normal(p1.first)) return null
        return p1.first + " " + p2.first
    }

    /** Siguientes palabras con su puntaje (2 o más = apoyadas por el contexto, no genéricas) */
    private fun puntuar(contexto: List<String>, inicioDeFrase: Boolean): List<Pair<String, Float>> {
        val res = LinkedHashMap<String, Float>()
        for ((w, v) in aprendido.predecir(contexto)) res[w] = 2.2f + ln(1f + v)
        tresPalabras(contexto)?.forEachIndexed { i, w -> res[w] = (res[w] ?: 0f) + 2.4f - i * 0.12f }
        dosPalabras(contexto)?.forEachIndexed { i, w -> res[w] = (res[w] ?: 0f) + 2.0f - i * 0.12f }
        if (contexto.isNotEmpty()) bigramas[Texto.normal(contexto.last())]?.forEachIndexed { i, w ->
            res[w] = (res[w] ?: 0f) + 1.6f - i * 0.12f
        }
        // Lo que más usas en general desempata (y sube lo tuyo por encima de lo genérico)
        for (w in res.keys.toList()) res[w] = res[w]!! + 0.15f * bonoUso(Texto.normal(w))
        val anterior = contexto.lastOrNull()?.let { Texto.normal(it) }
        if (anterior != null) res.keys.removeAll { Texto.normal(it) == anterior }
        if (aprendido.eliminadas.isNotEmpty()) res.keys.removeAll { aprendido.eliminada(Texto.normal(it)) }
        if (res.size < 3) {
            val base = if (inicioDeFrase || contexto.isEmpty()) INICIO else GENERALES
            base.forEachIndexed { i, w -> if (!res.containsKey(w) && Texto.normal(w) != anterior && !aprendido.eliminada(Texto.normal(w))) res[w] = 0.5f - i * 0.02f }
        }
        val orden = res.entries.sortedByDescending { it.value }
        val vistos = HashSet<String>()
        return orden.map { (if (inicioDeFrase) Texto.capitalizar(it.key) else it.key) to it.value }
            .filter { vistos.add(Texto.normal(it.first)) }.take(3)
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
        /** Lo que pesa una palabra de otro idioma que no usas (frente a una del español) */
        private const val FUERA = 2.4f
        /** Una palabra nueva tuya cuenta como segura recién después de usarla tantas veces */
        private const val DUDOSA = 5
        private val LETRAS = CharArray(27) { i -> if (i < 14) 'a' + i else if (i == 14) 'ñ' else 'o' + (i - 15) }
        val INICIO = listOf("hola", "ya", "gracias", "buenos", "sí", "no", "estimado", "ok", "qué", "te", "yo", "bueno")
        val GENERALES = listOf("de", "que", "y", "la", "el", "en", "a", "no", "con", "para", "por", "me")

        fun leerBigramas(lineas: Sequence<String>, en: HashMap<String, Array<String>>) {
            for (l in lineas) {
                if (l.isEmpty() || l[0] == '#') continue
                val i = l.indexOf(':')
                if (i <= 0) continue
                // También claves de dos palabras ("buenos días: ..."): predicen mejor que una sola
                val k = Texto.normal(l.substring(0, i).trim()).split(' ').filter { it.isNotEmpty() }.joinToString(" ")
                // Hasta tres palabras de contexto ("me voy a: dormir, casa…")
                if (k.isEmpty() || k.count { it == ' ' } > 2) continue
                val nuevos = l.substring(i + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }
                val previos = en[k]
                en[k] = if (previos == null) nuevos.toTypedArray() else (previos.toList() + nuevos).distinct().toTypedArray()
            }
        }
    }
}
