package com.tecladoios

import android.content.Context
import java.io.File
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sugerencias, predicción de la siguiente palabra y autocorrección en español
 * (general, formal y peruano).
 *
 * Autocorrección: compara lo que escribiste con cada palabra conocida usando la
 * distribución del teclado (equivocarte con una tecla vecina "cuesta" poco, como
 * Tqrjeta → Tarjeta), dónde cayó exactamente cada dedo, letras invertidas, dobles,
 * faltantes o sobrantes, y palabras pegadas ("estoybien" → "estoy bien").
 * Entre los candidatos gana el que mejor combina parecido, frecuencia real y contexto.
 *
 * Rendimiento: el diccionario (más de 100 mil palabras, ordenadas de la más común a la
 * menos común) se arma una sola vez en segundo plano. Buscar por prefijo es una búsqueda
 * binaria; la autocorrección solo revisa las palabras que empiezan con la misma letra o
 * una vecina y que tienen un largo parecido. Así cada tecla responde al instante y casi
 * no gasta batería.
 *
 * Aprendizaje: cuenta las palabras que usas y el orden en que las usas (de a dos y de a
 * tres), recuerda cuándo usaste cada una (lo reciente pesa más y lo que escribiste una
 * sola vez hace mucho se olvida solo), recuerda los nombres con mayúscula, "desaprende"
 * lo que borras justo después de escribirlo, no repite una corrección que deshiciste y
 * no aprende nada en modo incógnito. Todo se guarda en un archivo propio, pocas veces y
 * solo si cambió algo.
 */
class Diccionario(context: Context) {

    private val prefs = Ajustes.prefs(context)
    private val archivo = File(context.applicationContext.filesDir, ARCHIVO)
    private val normales = HashMap<String, String>()
    private val aprendidas = HashMap<String, Int>()
    /**
     * Índice ordenado de lo aprendido ("sin tildes\u0000palabra"): para buscar por cómo empieza
     * sin recorrer todas tus palabras en cada letra. Con meses de uso son miles y así no se
     * pone lento. Se mantiene al día en cada lugar donde cambia [aprendidas].
     */
    private val indiceAprendidas = java.util.TreeSet<String>()
    /**
     * Palabras de los nombres de tus contactos (minúscula → sin tildes). Solo cuentan las que no
     * están en el diccionario ("Yesenia" sí, "Rosa" no, para no ponerle mayúscula a la flor).
     */
    @Volatile private var contactos: Map<String, String> = emptyMap()
    private val pares = HashMap<String, HashMap<String, Int>>()
    /** Palabras que pediste olvidar: no se sugieren ni se usan para corregir */
    private val bloqueadas = HashSet<String>()
    /** Cuántas veces escribiste la palabra con mayúscula a mitad de oración (nombres) */
    private val nombres = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Último día (desde 1970) en que usaste cada palabra aprendida */
    private val usadas = HashMap<String, Int>()
    /** Tres palabras seguidas: "nos vemos" → {"mañana": 4, "luego": 2} */
    private val trios = HashMap<String, HashMap<String, Int>>()
    /** Correcciones que deshiciste: "chamba>chamba" no se vuelve a corregir */
    private val rechazos = HashSet<String>()
    /**
     * Correcciones deshechas una sola vez. Recién la segunda vez que deshaces la misma
     * corrección se deja de hacer (una vez puede ser que borraste por otra cosa).
     */
    private val rechazosPendientes = HashSet<String>()
    /**
     * Qué es cada palabra aprendida respecto al diccionario (se calcula una vez por palabra):
     * [EN_DICCIONARIO], [SIN_TILDE] ("sabado" existe como "sábado") u [OTRA].
     */
    private val clases = HashMap<String, Int>()
    /** Modo incógnito: no se aprende nada (lo pide la app, como Chrome incógnito) */
    @Volatile var pausado = false

    /** El cerebro: tu modelo de lenguaje personal (frases, apps, errores de siempre) */
    private val cerebro = Cerebro(File(context.applicationContext.filesDir, Cerebro.ARCHIVO))
    /** La app donde estás escribiendo (el cerebro aprende cómo escribes en cada una) */
    @Volatile var app = ""
    /** Las últimas predicciones que se mostraron (para saber cuáles ignoras) */
    private var ultimasPredichas: Pair<String, List<String>>? = null

    /**
     * Turnos: el teclado calcula las sugerencias en un hilo aparte. Cuando llega una letra
     * nueva, el cálculo anterior ya no sirve y se corta al instante (así nunca se acumula
     * trabajo ni se traba el teclado al escribir rápido con dos dedos).
     */
    private val turnoGlobal = AtomicInteger()
    private var miTurno = 0
    fun interrumpir() { turnoGlobal.incrementAndGet() }
    fun turno() = turnoGlobal.get()
    fun vigente(t: Int) = turnoGlobal.get() == t
    private fun cortado() = !sinCorte && turnoGlobal.get() != miTurno
    /** La corrección al dar espacio no se corta aunque sigas escribiendo (ya se necesita sí o sí) */
    private var sinCorte = false
    private var revision = -1
    @Volatile private var cambios = false
    private var sinGuardar = 0
    /** Lo aprendido estaba en el formato viejo (dentro de los ajustes): se pasa al archivo */
    @Volatile private var migrar = false
    /** Para escribir el archivo sin bloquear las sugerencias (y sin que una copia vieja pise a una nueva) */
    private val escritura = Any()
    private var copiaNumero = 0L
    private var ultimaEscrita = 0L

    /**
     * Palabras de una letra inicial agrupadas por largo: la autocorrección solo mira las
     * de largo parecido al de lo que escribiste (muchas menos comparaciones por tecla).
     */
    private class Grupo(val idx: IntArray, val desde: IntArray)

    /** Diccionario base ya ordenado. Se arma en segundo plano para que el teclado abra al instante. */
    private class Indice(
        val palabras: Array<String>,
        val normal: Array<String>,
        /** Posiciones ordenadas por la palabra sin tildes (para buscar por prefijo) */
        val orden: IntArray,
        /** Lo mismo, pero solo con las palabras más comunes (prefijos de una o dos letras) */
        val ordenTop: IntArray,
        val grupos: HashMap<Char, Grupo>,
        /** Predicción: después de cada palabra, las que más se escriben (código + archivo) */
        val pares: HashMap<String, List<String>>,
        /** Predicción con las dos palabras anteriores: "lo antes" → "posible" */
        val trios: HashMap<String, List<String>>,
    ) {
        val total = palabras.size
    }

    @Volatile
    private var indice: Indice? = null

    /**
     * Dónde cayó cada dedo en la palabra que estás escribiendo ([x, fila, letra tocada]).
     * Con esto la corrección sabe si tocaste justo en el borde entre dos teclas
     * (y entonces cambiar de letra "cuesta" casi nada) o en el centro de la tecla.
     */
    private var toq: Array<FloatArray>? = null
    /** Alto de una fila medido en anchos de tecla (lo manda el teclado) */
    @Volatile var proporcion = 1.37f

    // Memoria reutilizable para comparar palabras sin crear basura en cada tecla
    private var fila0 = FloatArray(48)
    private var fila1 = FloatArray(48)
    private var fila2 = FloatArray(48)

    init {
        cargar()
        activo = WeakReference(this)
        val app = context.applicationContext
        Thread({
            try { cerebro.cargar() } catch (e: Throwable) { Registro.anotar("cargar el cerebro", e) }
            try { indice = construir(app) } catch (e: Throwable) { Registro.anotar("armar el diccionario", e) }
        }, "diccionario").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    /** La parte pesada del índice (las palabras ya ordenadas), que se guarda armada en un archivo */
    private class Base(
        val palabras: Array<String>,
        val normal: Array<String>,
        val orden: IntArray,
        val ordenTop: IntArray,
        val grupos: HashMap<Char, Grupo>,
    )

    /**
     * Arma el índice. Las palabras ordenadas se leen ya listas de un archivo guardado la
     * primera vez (así, cuando Android reinicia el teclado, las sugerencias están listas casi
     * al instante y se gasta mucha menos batería). Solo se vuelve a armar al actualizar la app.
     */
    private fun construir(c: Context): Indice {
        val archivoIndice = File(c.applicationContext.filesDir, CACHE)
        val version = versionApp(c)
        val base = leerIndice(archivoIndice, version) ?: armarBase(c).also {
            if (it.palabras.size > 1000) escribirIndice(archivoIndice, version, it)
        }
        val (pares, trios) = armarPredicciones(c)
        return Indice(base.palabras, base.normal, base.orden, base.ordenTop, base.grupos, pares, trios)
    }

    /** Cambia con cada instalación: así el índice guardado nunca queda viejo */
    private fun versionApp(c: Context): Long = try {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        info.longVersionCode * 1_000_003L + info.lastUpdateTime
    } catch (_: Exception) { -1L }

    private fun escribirIndice(f: File, version: Long, b: Base) {
        if (version == -1L) return
        try {
            val tmp = File(f.parentFile, "$CACHE.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)).use { o ->
                o.writeInt(MAGIA_INDICE)
                o.writeLong(version)
                o.writeInt(b.palabras.size)
                for (i in b.palabras.indices) {
                    o.writeUTF(b.palabras[i])
                    // Si la forma sin tildes es igual, no se repite (archivo más chico)
                    o.writeUTF(if (b.normal[i] == b.palabras[i]) "" else b.normal[i])
                }
                fun enteros(a: IntArray) { o.writeInt(a.size); for (v in a) o.writeInt(v) }
                enteros(b.orden)
                enteros(b.ordenTop)
                o.writeInt(b.grupos.size)
                for ((k, g) in b.grupos) {
                    o.writeChar(k.code)
                    enteros(g.idx)
                    enteros(g.desde)
                }
                o.writeInt(MAGIA_INDICE)
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Throwable) {
            try { f.delete() } catch (_: Exception) { }
        }
    }

    private fun leerIndice(f: File, version: Long): Base? {
        if (version == -1L || !f.exists()) return null
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f), 1 shl 16)).use { e ->
                if (e.readInt() != MAGIA_INDICE || e.readLong() != version) return null
                val n = e.readInt()
                if (n <= 0 || n > 1_000_000) return null
                val palabras = arrayOfNulls<String>(n)
                val normal = arrayOfNulls<String>(n)
                for (i in 0 until n) {
                    val p = e.readUTF()
                    val q = e.readUTF()
                    palabras[i] = p
                    normal[i] = if (q.isEmpty()) p else q
                }
                fun enteros(): IntArray {
                    val m = e.readInt()
                    if (m < 0 || m > 2_000_000) throw java.io.IOException("índice dañado")
                    return IntArray(m) { e.readInt() }
                }
                val orden = enteros()
                val ordenTop = enteros()
                val nGrupos = e.readInt()
                val grupos = HashMap<Char, Grupo>(nGrupos * 2)
                repeat(nGrupos) {
                    val k = e.readChar()
                    val idx = enteros()
                    val desde = enteros()
                    grupos[k] = Grupo(idx, desde)
                }
                if (e.readInt() != MAGIA_INDICE) return null
                Base(palabras.requireNoNulls(), normal.requireNoNulls(), orden, ordenTop, grupos)
            }
        } catch (_: Throwable) { null }
    }

    private fun armarBase(c: Context): Base {
        val vistos = HashSet<String>(140_000)
        val lista = ArrayList<String>(130_000)
        fun sumar(p: String) { if (p.isNotEmpty() && vistos.add(p)) lista.add(p) }
        // 1) Lo más usado en el chat
        for (p in FRECUENCIA.trim().split(ESPACIOS)) sumar(p)
        // 2) El diccionario grande, de la palabra más común a la menos común.
        //    En "@fijas" entran las listas del código (chat peruano, tildes, jerga).
        var conFijas = false
        try {
            c.assets.open("diccionario.txt").bufferedReader().useLines { lineas ->
                for (linea in lineas) {
                    val p = linea.trim()
                    if (p.isEmpty() || p[0] == '#') continue
                    if (p == "@fijas") {
                        conFijas = true
                        for (w in (BASE + " " + BASE_TILDES + " " + BASE_EXTRA + " " + JERGA).trim().split(ESPACIOS)) sumar(w)
                        continue
                    }
                    sumar(p)
                }
            }
        } catch (_: Exception) { }
        if (!conFijas) for (w in (BASE + " " + BASE_TILDES + " " + BASE_EXTRA + " " + JERGA).trim().split(ESPACIOS)) sumar(w)
        vistos.clear()

        val palabras = lista.toTypedArray()
        lista.clear()
        // La forma sin tildes: si es igual a la palabra se usa el mismo texto (menos memoria)
        val normal = Array(palabras.size) {
            val p = palabras[it]
            val n = normal(p)
            if (n == p) p else n
        }
        val orden = palabras.indices.sortedWith { a, b -> normal[a].compareTo(normal[b]) }.toIntArray()
        val ordenTop = orden.filter { it < TOP }.toIntArray()

        // Grupos por letra inicial, ordenados por largo (y dentro, de más a menos común)
        val porLetra = HashMap<Char, ArrayList<Int>>()
        for (i in palabras.indices) {
            val n = normal[i]
            if (n.isEmpty() || n.length > MAX_LARGO) continue
            porLetra.getOrPut(n[0]) { ArrayList() }.add(i)
        }
        val grupos = HashMap<Char, Grupo>()
        for ((k, v) in porLetra) {
            val idx = v.sortedWith(compareBy<Int>({ normal[it].length }, { it })).toIntArray()
            val desde = IntArray(MAX_LARGO + 2)
            var pos = 0
            for (largo in 0..MAX_LARGO + 1) {
                while (pos < idx.size && normal[idx[pos]].length < largo) pos++
                desde[largo] = pos
            }
            grupos[k] = Grupo(idx, desde)
        }
        return Base(palabras, normal, orden, ordenTop, grupos)
    }

    /** Predicciones: las del código y las del archivo bigramas.txt (es poco, se arma siempre) */
    private fun armarPredicciones(c: Context): Pair<HashMap<String, List<String>>, HashMap<String, List<String>>> {
        val paresFijos = HashMap<String, List<String>>(PARES_BASE.size * 2)
        for ((k, v) in PARES_BASE) paresFijos[k] = v
        val triosFijos = HashMap<String, List<String>>()
        try {
            c.assets.open("bigramas.txt").bufferedReader().useLines { lineas ->
                for (linea in lineas) {
                    if (linea.isBlank() || linea.trimStart().startsWith("#")) continue
                    val i = linea.indexOf(':')
                    if (i <= 0) continue
                    val clave = linea.substring(0, i).trim().lowercase()
                    // Como en iPhone: se predice una palabra a la vez (de "fin de semana" va "fin")
                    val sig = linea.substring(i + 1).split(",")
                        .mapNotNull { s -> s.trim().split(' ').firstOrNull()?.takeIf { it.isNotEmpty() } }
                        .distinct()
                    if (sig.isEmpty()) continue
                    val partes = clave.split(' ')
                    when (partes.size) {
                        1 -> paresFijos[clave] = ((paresFijos[clave] ?: emptyList()) + sig).distinct()
                        2 -> triosFijos[clave] = ((triosFijos[clave] ?: emptyList()) + sig).distinct()
                    }
                }
            }
        } catch (_: Exception) { }
        // También sin tildes: "como estas" encuentra lo de "cómo estás" (escribir sin tildes es lo normal en el chat)
        for (mapa in listOf(paresFijos, triosFijos)) {
            for (k in mapa.keys.toList()) {
                val n = normal(k)
                if (n != k && !mapa.containsKey(n)) mapa[n] = mapa[k] ?: continue
            }
        }
        return paresFijos to triosFijos
    }

    private fun normal(s: String) = sinTildes(s)

    /** Solo para palabras aprendidas (hilo principal) */
    private fun normalDe(p: String) = normales.getOrPut(p) { normal(p) }

    private fun claveIndice(p: String) = normal(p) + '\u0000' + p

    /** Pone o suma una palabra aprendida y la anota en el índice si es nueva */
    private fun ponerAprendida(p: String, n: Int) {
        if (aprendidas.put(p, n) == null) indiceAprendidas.add(claveIndice(p))
    }

    private fun quitarAprendida(p: String) {
        if (aprendidas.remove(p) != null) indiceAprendidas.remove(claveIndice(p))
    }

    /** Claves del índice cuyas palabras (sin tildes) empiezan con [np] */
    private fun aprendidasDesde(np: String): Collection<String> =
        if (np.isEmpty()) indiceAprendidas else indiceAprendidas.subSet(np, true, np + '\uFFFF', false)

    private fun palabraDe(clave: String) = clave.substring(clave.indexOf('\u0000') + 1)

    private fun bloqueada(p: String) = bloqueadas.isNotEmpty() && p.lowercase() in bloqueadas

    /** Más alto = más común (escala logarítmica, como la frecuencia real de las palabras) */
    private fun frec(i: Int): Int = (1000.0 - 85.0 * Math.log(i + 1.0)).toInt().coerceAtLeast(1)

    /** Posición de la palabra exacta (sin importar mayúsculas) en el diccionario, o -1 */
    private fun rango(ix: Indice, p: String): Int {
        val n = normal(p)
        if (n.isEmpty()) return -1
        var k = desde(ix, n)
        var mejor = -1
        while (k < ix.orden.size) {
            val i = ix.orden[k++]
            if (ix.normal[i] != n) break
            if (ix.palabras[i].equals(p, ignoreCase = true) && (mejor < 0 || i < mejor)) mejor = i
        }
        return mejor
    }

    /** La palabra más común que se escribe así sin tildes ("tambien" → "también"), o -1 */
    private fun mejorPorNormal(ix: Indice, n: String): Int {
        var k = desde(ix, n)
        var mejor = -1
        while (k < ix.orden.size) {
            val i = ix.orden[k++]
            if (ix.normal[i] != n) break
            if (mejor < 0 || i < mejor) mejor = i
        }
        return mejor
    }

    private fun conoce(ix: Indice, p: String): Boolean =
        rango(ix, p) >= 0 || propia(p.lowercase(), aprendidas[p.lowercase()] ?: 0) ||
            esContacto(p.lowercase()) || p.lowercase() in INGLES

    /**
     * ¿Una palabra aprendida ya es "tuya" (se sugiere con fuerza y no se corrige)?
     * Antes bastaba con deshacer una corrección una sola vez, y así se colaban errores de
     * tipeo ("qje", "comnel"). Ahora una palabra que no está en el diccionario tiene que
     * aparecer varias veces, y las cortitas de dos letras muchas más.
     */
    private fun propia(l: String, n: Int): Boolean {
        if (n < MIN_PROPIA) return false
        val ix = indice ?: return true
        return when (clase(ix, l)) {
            EN_DICCIONARIO -> true
            // Una palabra del diccionario escrita sin tilde ("sabado", "codigo") se sigue
            // corrigiendo, salvo que la uses así muchísimas veces a propósito
            SIN_TILDE -> n >= MIN_VARIANTE
            else -> if (l.length <= 2) n >= MIN_CORTA && l.any { it in "aeiouáéíóú" } else true
        }
    }

    private fun clase(ix: Indice, l: String): Int = clases.getOrPut(l) {
        val n = normal(l)
        when {
            rango(ix, l) >= 0 -> EN_DICCIONARIO
            n == l && mejorPorNormal(ix, n) >= 0 -> SIN_TILDE
            else -> OTRA
        }
    }

    /** ¿[l] es una palabra del diccionario sin su tilde? ("sabado" existe como "sábado") */
    private fun esVariante(ix: Indice, l: String): Boolean = clase(ix, l) == SIN_TILDE

    private fun igualarMayus(c: String, modelo: String): String = when {
        modelo.length > 1 && modelo.all { !it.isLetter() || it.isUpperCase() } -> c.uppercase()
        modelo.isNotEmpty() && modelo[0].isUpperCase() -> c.replaceFirstChar { it.uppercase() }
        esNombre(c) -> c.replaceFirstChar { it.uppercase() }
        else -> c
    }

    private fun esNombre(c: String) = (nombres[c.lowercase()] ?: 0) >= 2 || esContacto(c.lowercase())

    private fun esContacto(l: String): Boolean {
        if (contactos.isEmpty() || !contactos.containsKey(l)) return false
        val ix = indice ?: return true
        return rango(ix, l) < 0
    }

    /** Nombres de tus contactos (los lee el servicio, si lo activaste en Ajustes) */
    @Synchronized
    fun ponerContactos(lista: List<String>) {
        val m = HashMap<String, String>()
        for (nombre in lista) for (w in nombre.split(ESPACIOS)) {
            val l = w.trim().lowercase()
            if (l.length < 2 || l.length > 20 || !l.all { it.isLetter() }) continue
            m[l] = normal(l)
        }
        contactos = m
    }

    private fun hoy() = (System.currentTimeMillis() / 86_400_000L).toInt()

    /** Lo que usaste hace poco pesa más en las sugerencias */
    private fun recencia(p: String): Int {
        val dia = usadas[p] ?: return 0
        return when (hoy() - dia) {
            0 -> 60
            in 1..3 -> 35
            in 4..14 -> 15
            else -> 0
        }
    }

    /** "holaaaa", "siiii", "jajaja", "jsjsjs": se escriben así a propósito, no se corrigen */
    private fun expresiva(l: String): Boolean {
        var repetidas = 1
        for (i in 1 until l.length) {
            repetidas = if (l[i] == l[i - 1]) repetidas + 1 else 1
            if (repetidas >= 3) return true
        }
        return RISA.matches(l)
    }

    /** Primera posición (en el orden alfabético) cuya palabra es >= [np] */
    private fun desde(ix: Indice, np: String, orden: IntArray = ix.orden): Int {
        var a = 0
        var b = orden.size
        while (a < b) {
            val m = (a + b) ushr 1
            if (ix.normal[orden[m]] < np) a = m + 1 else b = m
        }
        return a
    }

    /** Letras con las que pudo empezar la palabra que quisiste escribir */
    private fun iniciales(np: String): Set<Char> {
        val s = LinkedHashSet<Char>()
        val c = np[0]
        s.add(c)
        VECINOS[c]?.let { s.addAll(it) }
        s.add('h') // "ola" → "hola"
        if (np.length > 1) s.add(np[1]) // "uqe" → "que"
        return s
    }

    /** Recorre las palabras de [ch] con un largo entre [min] y [max] */
    private inline fun recorrer(ix: Indice, ch: Char, min: Int, max: Int, accion: (Int) -> Unit) {
        val g = ix.grupos[ch] ?: return
        val a = g.desde[min.coerceIn(0, MAX_LARGO + 1)]
        val b = g.desde[(max + 1).coerceIn(0, MAX_LARGO + 1)]
        for (k in a until b) accion(g.idx[k])
    }

    // ---------- Distancia "de teclado" ----------

    /**
     * Distancia entre lo escrito [a] y los primeros [largoB] caracteres de [b].
     * Si en algún momento supera [limite] se rinde (para que sea rápido).
     */
    private fun distancia(a: String, b: String, largoB: Int, limite: Float): Float {
        val n = a.length
        val m = minOf(largoB, b.length)
        if (n == 0 || m == 0 || Math.abs(n - m) > 2 || n > 40 || m > 40) return 99f
        if (fila0.size < m + 1) { fila0 = FloatArray(m + 1); fila1 = FloatArray(m + 1); fila2 = FloatArray(m + 1) }
        var antes2 = fila0
        var antes = fila1
        var actual = fila2
        antes[0] = 0f
        for (j in 1..m) antes[j] = antes[j - 1] + if (b[j - 1] == 'h') 0.3f else 1f
        val tq = toq?.takeIf { it.size == n }
        for (i in 1..n) {
            actual[0] = i.toFloat()
            var minimo = actual[0]
            val ca = a[i - 1]
            // Letra de más: si repetiste la letra o es vecina de la anterior/siguiente, fue un
            // dedo que rozó dos teclas ("hokla" → "hola") y cuesta menos
            val extra = when {
                i >= 2 && a[i - 2] == ca -> 0.5f
                (i >= 2 && costoCambio(a[i - 2], ca) <= 0.4f) || (i < n && costoCambio(a[i], ca) <= 0.4f) -> 0.65f
                else -> 1f
            }
            for (j in 1..m) {
                val cb = b[j - 1]
                var v = antes[j - 1] + (if (tq != null) costoToque(tq[i - 1], ca, cb) else costoCambio(ca, cb))
                v = minOf(v, antes[j] + extra)
                // Letra que faltó (letra doble o "h" muda cuestan menos: "aroz" → "arroz", "ola" → "hola")
                v = minOf(v, actual[j - 1] + if (cb == 'h') 0.3f else if (j >= 2 && b[j - 2] == cb) 0.5f else 1f)
                // Dos letras invertidas: "qeu" → "que"
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = minOf(v, antes2[j - 2] + 0.6f)
                actual[j] = v
                if (v < minimo) minimo = v
            }
            // Margen extra: una inversión de letras se "cobra" una fila después
            if (minimo > limite + 1f) return 99f
            val t = antes2; antes2 = antes; antes = actual; actual = t
        }
        var r = antes[m]
        // Equivocarse en la primera letra es raro: se penaliza un poco (salvo la "h" muda)
        if (a[0] != b[0] && !(b[0] == 'h' && b.length > 1 && a[0] == b[1]) && !similares(a[0], b[0])) r += 0.3f
        return r
    }

    /**
     * Como Gboard: cuánto cuesta que quisieras [cb] si tu dedo cayó en [t].
     * Si el dedo cayó en el borde entre las dos teclas, casi nada; si cayó en el
     * centro de la tecla que tocaste, bastante más.
     */
    private fun costoToque(t: FloatArray, ca: Char, cb: Char): Float {
        if (ca == cb) return 0f
        val x = t[0]
        if (x < 0f) return costoCambio(ca, cb)
        val pb = POSICIONES[cb] ?: return costoCambio(ca, cb)
        val tocada = t[2].toInt().toChar()
        val pa = POSICIONES[tocada] ?: POSICIONES[ca] ?: return costoCambio(ca, cb)
        val ky = proporcion * 0.75f
        val dxb = x - pb[0]
        val dyb = (t[1] - pb[1]) * ky
        val dxa = x - pa[0]
        val dya = (t[1] - pa[1]) * ky
        var c = 0.5f * ((dxb * dxb + dyb * dyb) - (dxa * dxa + dya * dya))
        c = c.coerceIn(0.05f, 1.4f)
        if (similares(ca, cb)) c = minOf(c, 0.3f)
        return c
    }

    private fun limitePara(n: Int) = when {
        n <= 4 -> 0.65f     // palabras cortas: solo un error
        n <= 5 -> 1.05f
        n <= 8 -> 1.65f
        else -> 2.1f
    }

    /** Las palabras que suelen ir después de [l] (código + archivo) */
    private fun paresFijos(l: String): List<String>? {
        val ix = indice
        return if (ix != null) ix.pares[l] ?: ix.pares[normal(l)] else PARES_BASE[l]
    }

    /** Puntos extra si sueles escribir [w] después de [previa] (y de [previa2] antes de esa) */
    private fun contexto(previa: String, w: String, previa2: String = ""): Float {
        val base = contextoFijo(previa, w, previa2)
        // El cerebro sabe qué escribes tú después de esas palabras (hasta tres antes y en esta app)
        val c = cerebro.prob(previa, previa2, w, app)
        val conFrase = if (c < 0.03f) base else maxOf(base, minOf(0.95f, 0.3f + c))
        // Memoria de la conversación: si la usaste hace un rato (un nombre, un lugar), pesa más
        val r = cerebro.reciente(w)
        return if (r <= 0f) conFrase else maxOf(conFrase, r * 0.4f)
    }

    private fun contextoFijo(previa: String, w: String, previa2: String): Float {
        if (previa.isEmpty()) return 0f
        val l = previa.lowercase()
        val lw = w.lowercase()
        if (previa2.isNotEmpty()) {
            val clave = previa2.lowercase() + " " + l
            val trio = trios[clave]?.get(lw) ?: 0
            if (trio > 0) return 0.8f + minOf(trio, 10) * 0.05f
            if (indice?.trios?.get(clave)?.contains(lw) == true) return 0.55f
        }
        val propio = pares[l]?.get(lw) ?: 0
        if (propio > 0) return 0.5f + minOf(propio, 10) * 0.05f
        return if (paresFijos(l)?.contains(lw) == true) 0.35f else 0f
    }

    // ---------- Sugerencias ----------

    @Synchronized
    fun sugerir(
        prefijo: String, max: Int, previa: String = "", previa2: String = "", t: Int = turno(),
        toques: Array<FloatArray>? = null,
    ): List<String> {
        toq = toques
        try {
            return sugerirSinToques(prefijo, max, previa, previa2, t)
        } finally {
            toq = null
        }
    }

    private fun sugerirSinToques(prefijo: String, max: Int, previa: String, previa2: String, t: Int): List<String> {
        miTurno = t
        if (prefijo.isEmpty() || cortado()) return emptyList()
        val ix = indice
        val np = normal(prefijo)
        if (np.isEmpty()) return emptyList()
        val lp = prefijo.lowercase()
        val res = HashMap<String, Int>()
        if (ix != null) {
            // Todas las palabras que empiezan así (búsqueda binaria, sin recorrer todo).
            // Con una o dos letras se miran solo las más comunes (si no, serían miles).
            val orden = if (np.length <= 2) ix.ordenTop else ix.orden
            var k = desde(ix, np, orden)
            var revisadas = 0
            while (k < orden.size && revisadas < 6000) {
                val i = orden[k++]
                if (!ix.normal[i].startsWith(np)) break
                if ((++revisadas and 511) == 0 && cortado()) return emptyList()
                val p = ix.palabras[i]
                if (p.equals(lp, ignoreCase = true) || bloqueada(p)) continue
                val s = frec(i)
                if ((res[p] ?: 0) < s) res[p] = s
            }
        }
        // Contexto: lo que sueles escribir después de la palabra anterior pesa muchísimo
        if (previa.isNotEmpty()) {
            val lprev = previa.lowercase()
            pares[lprev]?.forEach { (w, c) ->
                if (w != lp && normalDe(w).startsWith(np) && !bloqueada(w)) res[w] = (res[w] ?: 0) + 400 + c * 120
            }
            if (previa2.isNotEmpty()) {
                val clave = previa2.lowercase() + " " + lprev
                trios[clave]?.forEach { (w, c) ->
                    if (w != lp && normalDe(w).startsWith(np) && !bloqueada(w)) res[w] = (res[w] ?: 0) + 600 + c * 150
                }
                ix?.trios?.get(clave)?.forEachIndexed { i, w ->
                    if (w != lp && normalDe(w).startsWith(np) && !bloqueada(w)) res[w] = (res[w] ?: 0) + 380 - i * 30
                }
            }
            paresFijos(lprev)?.forEachIndexed { i, w ->
                if (w != lp && normalDe(w).startsWith(np) && !bloqueada(w)) res[w] = (res[w] ?: 0) + 300 - minOf(i, 8) * 25
            }
        }
        for (k in aprendidasDesde(np)) {
            val p = palabraDe(k)
            val c = aprendidas[p] ?: continue
            if (p != lp && !bloqueada(p)) {
                val tuya = propia(p, c)
                // Sin tilde ("sabado") o de dos letras que aún no es tuya: no se sugiere (la buena sí)
                if (!tuya && (p.length <= 2 || (ix != null && esVariante(ix, p)))) continue
                // Tus palabras pesan más; cuando ya son tuyas, mucho más (y más si fue hace poco)
                res[p] = (res[p] ?: 0) + (if (tuya) minOf(c, 60) * 60 else 15) + recencia(p)
            }
        }
        // Si hay pocas, agregar palabras como si hubieras tocado una tecla vecina: "tqrj" → "tarjeta"
        if (ix != null && res.size < max + 2 && np.length >= 2) {
            val lim = when {
                np.length <= 3 -> 0.45f
                np.length <= 5 -> 0.7f
                else -> 1.1f
            }
            var vistas = 0
            for (ch in iniciales(np)) {
                if (cortado()) return emptyList()
                recorrer(ix, ch, np.length - 2, MAX_LARGO) { i ->
                    if ((++vistas and 255) == 0 && cortado()) return emptyList()
                    val p = ix.palabras[i]
                    if (!res.containsKey(p) && !p.equals(lp, ignoreCase = true) && !bloqueada(p)) {
                        val n = ix.normal[i]
                        val c = minOf(
                            distancia(np, n, np.length, lim),
                            distancia(np, n, n.length, lim),
                        )
                        if (c <= lim) res[p] = (frec(i) * 0.55f * (1f - c / (lim + 0.6f))).toInt()
                    }
                }
            }
            for (ch in iniciales(np)) for (k in aprendidasDesde(ch.toString())) {
                val p = palabraDe(k)
                val c = aprendidas[p] ?: continue
                if (res.containsKey(p) || p == lp || bloqueada(p) || !propia(p, c)) continue
                val n = normalDe(p)
                val d = minOf(distancia(np, n, np.length, lim), distancia(np, n, n.length, lim))
                if (d <= lim) res[p] = (minOf(c, 50) * 40 * (1f - d / (lim + 0.6f))).toInt()
            }
        }
        // El cerebro: lo que tú sueles escribir aquí (por la frase y la app) sube arriba
        if (!cortado()) {
            for ((w, pr) in cerebro.candidatos(previa, previa2, app, np, 12)) {
                // Solo palabras de verdad (del diccionario o ya tuyas), nunca un error que se coló
                if (w == lp || bloqueada(w) || (ix != null && !conoce(ix, w))) continue
                res[w] = (res[w] ?: 0) + (pr * 1600f).toInt()
            }
            // Memoria de la conversación: lo que escribiste hace unos minutos vuelve a aparecer
            for ((w, pr) in cerebro.recientesCon(np, 6)) {
                if (w == lp || bloqueada(w) || (ix != null && !conoce(ix, w))) continue
                res[w] = (res[w] ?: 0) + (pr * 700f).toInt()
            }
            // Nombres de tus contactos
            val agenda = contactos
            if (agenda.isNotEmpty()) for ((w, nw) in agenda) {
                if (w == lp || !nw.startsWith(np) || bloqueada(w) || !esContacto(w)) continue
                res[w] = (res[w] ?: 0) + 520
            }
        }
        return res.entries.sortedByDescending { it.value }
            .take(max)
            .map {
                val s = igualarMayus(it.key, prefijo)
                if (esContacto(it.key.lowercase())) s.replaceFirstChar { c -> c.uppercase() } else s
            }
    }

    /**
     * Qué tan probable es cada letra como la siguiente de [prefijo] (a–z y ñ al final).
     * El teclado lo usa para agrandar un poquito las teclas probables cuando tocas justo
     * en el borde, como hace el iPhone. null si no hay datos.
     */
    @Synchronized
    fun letrasProbables(prefijo: String, t: Int): FloatArray? {
        miTurno = t
        val ix = indice ?: return null
        if (prefijo.isEmpty() || prefijo.length > 20) return null
        val np = normal(prefijo)
        if (np.length != prefijo.length) return null
        val res = FloatArray(27)
        var total = 0f
        val largo = np.length
        var k = desde(ix, np, ix.ordenTop)
        var revisadas = 0
        while (k < ix.ordenTop.size && revisadas < 3000) {
            val i = ix.ordenTop[k++]
            val n = ix.normal[i]
            if (!n.startsWith(np)) break
            revisadas++
            if (n.length <= largo || ix.palabras[i].length <= largo) continue
            val ch = ix.palabras[i][largo].lowercaseChar()
            val tecla = if (ch == 'ñ') 26 else (SIN_ACENTO[ch] ?: ch) - 'a'
            if (tecla !in 0..26) continue
            val w = frec(i).toFloat()
            res[tecla] += w
            total += w
        }
        if (cortado()) return null
        val lp = prefijo.lowercase()
        for (k in aprendidasDesde(np)) {
            val p = palabraDe(k)
            val c = aprendidas[p] ?: continue
            // Se compara sin tildes: "cancio" también cuenta para "canción"
            if (c < 2 || p.length <= largo || normalDe(p).length != p.length) continue
            val ch = p[largo]
            val tecla = if (ch == 'ñ') 26 else (SIN_ACENTO[ch] ?: ch) - 'a'
            if (tecla !in 0..26) continue
            val w = minOf(c, 40) * 40f
            res[tecla] += w
            total += w
        }
        if (total <= 0f) return null
        for (i in res.indices) res[i] /= total
        return res
    }

    /** Predicción de la siguiente palabra (barra después de un espacio), como en iPhone */
    @Synchronized
    fun siguientes(previa: String, max: Int, previa2: String = ""): List<String> {
        val l = previa.lowercase()
        val res = LinkedHashMap<String, Int>()
        // Lo que sueles escribir después de esas dos palabras manda: "nos vemos" → "mañana"
        if (previa2.isNotEmpty() && l.isNotEmpty()) {
            val clave = previa2.lowercase() + " " + l
            trios[clave]?.forEach { (w, c) -> if (!bloqueada(w)) res[w] = c * 30 }
            val ixTrios = indice?.trios
            (ixTrios?.get(clave) ?: ixTrios?.get(normal(clave)))?.forEachIndexed { i, w ->
                if (!bloqueada(w)) res[w] = (res[w] ?: 0) + (12 - i).coerceAtLeast(2)
            }
        }
        pares[l]?.forEach { (w, c) -> if (!bloqueada(w)) res[w] = (res[w] ?: 0) + c * 10 + recencia(w) / 10 }
        val fijas = if (l.isEmpty()) INICIO else paresFijos(l)
        fijas?.forEachIndexed { i, w -> if (!bloqueada(w)) res[w] = (res[w] ?: 0) + (8 - i).coerceAtLeast(1) }
        // El cerebro: tu manera de seguir esta frase (hasta tres palabras atrás, y en esta app)
        val ixC = indice
        for ((w, pr) in cerebro.candidatos(previa, previa2, app, "", 8)) {
            if (bloqueada(w) || (ixC != null && !conoce(ixC, w))) continue
            res[w] = (res[w] ?: 0) + (pr * 160f).toInt()
        }
        // Las que siempre ignoras después de esta palabra bajan
        if (res.isNotEmpty()) {
            for (k in res.keys.toList()) {
                val f = cerebro.factorIgnorada(l, k)
                if (f < 1f) res[k] = ((res[k] ?: 0) * f).toInt()
            }
        }
        // Siempre 3 opciones, como en iPhone: si no hay nada para esa palabra, las más comunes
        if (res.size < max) {
            val generales = if (l.isEmpty()) INICIO else GENERALES
            for (w in generales) {
                if (res.size >= max) break
                if (!res.containsKey(w) && w != l && !bloqueada(w)) res[w] = 0
            }
        }
        val lista = res.entries.sortedByDescending { it.value }.take(max).map { it.key }
        ultimasPredichas = l to lista
        return lista.map { if (esNombre(it)) it.replaceFirstChar { c -> c.uppercase() } else it }
    }

    // ---------- Autocorrección ----------

    /** Devuelve la corrección de [p] o null si está bien escrita. */
    @Synchronized
    fun corregir(
        p: String, previa: String = "", previa2: String = "", t: Int = turno(),
        toques: Array<FloatArray>? = null, cortable: Boolean = true,
    ): String? {
        miTurno = t
        toq = toques
        sinCorte = !cortable
        val encontrada: String? = try { corregirSinFiltro(p, previa, previa2) } finally { toq = null; sinCorte = false }
        val r = encontrada ?: return null
        if (cortado()) return null
        // Una corrección que ya deshiciste no se vuelve a hacer
        if (rechazos.isNotEmpty() && (p.lowercase() + ">" + r.lowercase()) in rechazos) return null
        return r
    }

    /**
     * La palabra más parecida a [np] (sin tildes) del diccionario y de lo que aprendiste.
     * [maxRango]: solo palabras más comunes que esa posición (-1 = todas).
     */
    private fun masParecida(
        ix: Indice, np: String, lim: Float, previa: String, previa2: String, maxRango: Int = -1,
    ): String? {
        var mejor: String? = null
        var mejorTotal = Float.MAX_VALUE
        var vistas = 0
        for (ch in iniciales(np)) {
            if (cortado()) return null
            recorrer(ix, ch, np.length - 2, np.length + 2) { i ->
                // Si ya llegó otra letra, se deja de buscar al toque (no se hace esperar a nadie)
                if ((++vistas and 255) == 0 && cortado()) return null
                if (maxRango < 0 || i <= maxRango) {
                    val c = ix.palabras[i]
                    if (!bloqueada(c)) {
                        val n = ix.normal[i]
                        val d = distancia(np, n, n.length, lim)
                        if (d <= lim) {
                            val ctx = contexto(previa, c, previa2)
                            // Si no es un error "de tecla vecina", solo corrige hacia palabras comunes
                            if (!(d > 0.65f && i > 2500 && ctx == 0f)) {
                                val total = d * 2.5f + Math.log(i + 30.0).toFloat() * 0.3f - ctx
                                if (total < mejorTotal) { mejorTotal = total; mejor = c }
                            }
                        }
                    }
                }
            }
        }
        if (maxRango < 0) {
            for (ch in iniciales(np)) for (k in aprendidasDesde(ch.toString())) {
                val c = palabraDe(k)
                val n = aprendidas[c] ?: continue
                if (bloqueada(c)) continue
                val nc = normalDe(c)
                if (Math.abs(nc.length - np.length) > 2 || !propia(c, n)) continue
                val d = distancia(np, nc, nc.length, lim)
                if (d > lim || (d > 0.65f && n < 3)) continue
                val total = d * 2.5f + 1.1f - minOf(n, 30) * 0.04f - contexto(previa, c, previa2) - recencia(c) * 0.004f
                if (total < mejorTotal) { mejorTotal = total; mejor = c }
            }
        }
        return mejor
    }

    /**
     * Palabras pegadas por no tocar el espacio: "estoybien" → "estoy bien", "porfavor" → "por favor".
     * Las dos partes tienen que ser palabras comunes.
     */
    private fun separar(ix: Indice, p: String): String? {
        val l = p.lowercase()
        if (l.length < 5) return null
        var mejor: String? = null
        var mejorPuntaje = 0
        for (k in 1 until l.length - 1) {
            val a = l.substring(0, k)
            val b = l.substring(k)
            if (a.length == 1 && a[0] !in "ayoeu") continue
            if (b.length < 2) continue
            val ra = rango(ix, a)
            if (ra < 0 || ra > COMUN) continue
            val rb = rango(ix, b)
            if (rb < 0 || rb > COMUN) continue
            var s = minOf(frec(ra), frec(rb)) + (frec(ra) + frec(rb)) / 4
            if (paresFijos(a)?.contains(b) == true || pares[a]?.containsKey(b) == true) s += 250
            if (s > mejorPuntaje) { mejorPuntaje = s; mejor = ix.palabras[ra].lowercase() + " " + ix.palabras[rb] }
        }
        // Solo si ambas partes son bastante comunes (evita cortes raros)
        return if (mejorPuntaje >= 520) mejor else null
    }

    private fun conMayus(s: String, modelo: String) =
        if (modelo[0].isUpperCase()) s.replaceFirstChar { it.uppercase() } else s

    /**
     * El dedo cayó en la fila de abajo en vez de la barra espaciadora: en lugar del espacio
     * quedó una b, n, v, m o c. "quebhago" → "que hago", "ennla" → "en la",
     * "nonquiere" → "no quiere". Las dos partes tienen que ser palabras comunes.
     * Con [soloSeguro] solo si esas dos palabras suelen ir juntas (lo tuyo o lo del diccionario).
     */
    private fun separarEspacio(ix: Indice, p: String, soloSeguro: Boolean): String? {
        val l = p.lowercase()
        if (l.length < 4) return null
        var mejor: String? = null
        var mejorPuntaje = 0
        for (k in 1 until l.length - 2) {
            if (l[k] !in JUNTO_AL_ESPACIO) continue
            val a = l.substring(0, k)
            val b = l.substring(k + 1)
            if (a.length == 1 && a[0] !in "ayoeu") continue
            val ra = rango(ix, a)
            if (ra < 0 || ra > COMUN) continue
            val rb = rango(ix, b)
            if (rb < 0 || rb > COMUN) continue
            val pa = ix.palabras[ra].lowercase()
            val pb = ix.palabras[rb]
            val juntas = paresFijos(pa)?.contains(pb.lowercase()) == true ||
                pares[pa]?.containsKey(pb.lowercase()) == true
            if (soloSeguro && !juntas) continue
            // Partes cortitas ("te", "os", "pe"): solo si son palabras de enlace o van juntas
            // (así "acompañante" nunca se vuelve "acompaña te")
            if (!juntas) {
                if (a.length <= 2 && a !in ENLACE) continue
                if (b.length <= 2 && (b !in ENLACE || a.length > 2)) continue
            }
            var s = minOf(frec(ra), frec(rb)) + (frec(ra) + frec(rb)) / 4
            if (juntas) s += 250
            if (s > mejorPuntaje) { mejorPuntaje = s; mejor = "$pa $pb" }
        }
        return if (mejorPuntaje >= 520) mejor else null
    }

    private fun corregirSinFiltro(p: String, previa: String, previa2: String): String? {
        if (p.length < 2) return null
        val l = p.lowercase()
        // Palabras en inglés que se usan en el chat ("ok", "link", "story"): no se tocan
        if (l in INGLES) return null
        // Nombres de tus contactos: nunca se corrigen, solo se les pone mayúscula
        if (esContacto(l)) return if (p[0].isLowerCase()) p.replaceFirstChar { it.uppercase() } else null
        // Errores comunes al escribir rápido o de oído: "aser" → "hacer", "nesecito" → "necesito"
        if (!propia(l, aprendidas[l] ?: 0)) {
            ERRORES_COMUNES[l]?.let { if (!bloqueada(it)) return igualarMayus(it, p).takeIf { c -> c != p } }
        }
        if (expresiva(l)) return null
        val ix = indice ?: return null // aún cargando: mejor no tocar nada
        val np = normal(p)
        if (np.isEmpty()) return null
        val rp = rango(ix, p)
        val usada = aprendidas[l] ?: 0
        if (rp >= 0 || propia(l, usada)) {
            // Nombres que siempre escribes con mayúscula: "javier" → "Javier"
            if (p[0].isLowerCase() && esNombre(l) && !bloqueada(l)) return p.replaceFirstChar { it.uppercase() }
            // Una palabra rarísima que nunca usaste, tocada justo en el borde de una tecla,
            // cuando a un toque hay una palabra muy común: casi seguro fue un dedo desviado
            // ("cpmo" está en el diccionario como rara → "como"). Solo con la posición del dedo.
            if (rp > RARA && usada == 0 && np.length >= 3 && toq != null) {
                val alt = masParecida(ix, np, 0.28f, previa, previa2, maxRango = 4000)
                if (alt != null && !alt.equals(p, ignoreCase = true)) return igualarMayus(alt, p)
            }
            return null
        }

        // 0) Tu error de siempre (el cerebro lo vio varias veces): "qje" → "que"
        cerebro.errorTipico(l)?.let { e ->
            if (!bloqueada(e)) return igualarMayus(e, p).takeIf { c -> c != p }
        }

        // 1) Solo faltan tildes: "tambien" → "también"
        var mejor: String? = null
        var puntaje = -1
        val mi = mejorPorNormal(ix, np)
        if (mi >= 0) {
            val c = ix.palabras[mi]
            if (!bloqueada(c)) { mejor = c; puntaje = frec(mi) }
        }
        for (k in aprendidasDesde(np)) {
            // Solo las que sin tildes son exactamente lo escrito
            if (k.length <= np.length || k[np.length] != '\u0000') continue
            val c = palabraDe(k)
            val n = aprendidas[c] ?: continue
            if (propia(c, n) && n * 40 > puntaje && !bloqueada(c)) { mejor = c; puntaje = n * 40 }
        }

        // 2) Tocaste la b, la n o la v en vez del espacio: "quebhago" → "que hago".
        //    Si esas dos palabras suelen ir juntas, es casi seguro: va antes que lo demás.
        if (mejor == null && np.length >= 4) {
            separarEspacio(ix, p, soloSeguro = true)?.let { s -> return conMayus(s, p) }
        }
        // 3) Errores de tipeo con la distribución del teclado
        // (una palabra rara escrita una sola vez puede ser un error; cuando la repites ya es tuya)
        if (mejor == null && np.length >= 3) {
            // El límite se calibra solo según qué tan seguido deshaces la autocorrección
            mejor = masParecida(ix, np, limitePara(np.length) * cerebro.audacia(), previa, previa2)
        }
        // 4) Palabras pegadas: "estoybien" → "estoy bien", "yonsoy" → "yo soy"
        if (mejor == null && np.length >= 4) {
            (if (np.length >= 5) separar(ix, p) else null)?.let { s -> return conMayus(s, p) }
            separarEspacio(ix, p, soloSeguro = false)?.let { s -> return conMayus(s, p) }
        }
        val elegida = mejor ?: return null
        val resultado = igualarMayus(elegida, p)
        return if (resultado == p) null else resultado
    }

    /**
     * Tildes que dependen de la palabra anterior: "que tal" → "qué tal",
     * "como estas" → "cómo estás", "todo esta bien" → "todo está".
     * Devuelve (anterior, actual) corregidas, o null si no hay nada que cambiar.
     */
    // Sin @Synchronized: solo lee tablas fijas, así el espacio nunca espera a las sugerencias
    fun corregirPar(previa: String, palabra: String): Pair<String, String>? {
        if (previa.isEmpty() || palabra.isEmpty()) return null
        val par = PARES_TILDE[normal(previa) + " " + normal(palabra)] ?: return null
        val a = igualarMayus(par.first, previa)
        val b = igualarMayus(par.second, palabra)
        return if (a == previa && b == palabra) null else a to b
    }

    /** Palabra de pregunta con tilde: "que" → "qué", "como" → "cómo" */
    fun interrogativa(w: String): String? =
        INTERROGATIVAS[normal(w)]?.let { igualarMayus(it, w) }?.takeIf { it != w }

    /** ¿Es una palabra que el teclado conoce (del diccionario o que ya usaste)? */
    @Synchronized
    fun conocida(p: String): Boolean {
        val ix = indice ?: return true
        return conoce(ix, p)
    }

    // ---------- Deslizar para escribir ----------

    /** Letras de la palabra como teclas (á → a, la ñ se queda), sin repetir teclas seguidas */
    private fun teclasDe(w: String): CharArray? {
        val out = CharArray(w.length)
        var n = 0
        for (ch in w) {
            val l = ch.lowercaseChar()
            val k = if (POSICIONES.containsKey(l)) l else (SIN_ACENTO[l] ?: return null)
            if (n == 0 || out[n - 1] != k) out[n++] = k
        }
        return out.copyOf(n)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun largo(p: Array<FloatArray>): Float {
        var t = 0f
        for (i in 1 until p.size) t += dist(p[i - 1], p[i])
        return t
    }

    /** El recorrido repartido en [n] puntos a la misma distancia */
    private fun remuestrear(p: Array<FloatArray>, n: Int): Array<FloatArray> {
        val total = largo(p)
        if (total <= 0f) return Array(n) { p[0].copyOf() }
        val paso = total / (n - 1)
        val res = ArrayList<FloatArray>(n)
        res.add(p[0].copyOf())
        var acum = 0f
        var prev = p[0]
        var i = 1
        while (i < p.size && res.size < n) {
            val cur = p[i]
            val dd = dist(prev, cur)
            if (dd > 0f && acum + dd >= paso) {
                val f = (paso - acum) / dd
                val q = floatArrayOf(prev[0] + f * (cur[0] - prev[0]), prev[1] + f * (cur[1] - prev[1]))
                res.add(q)
                prev = q
                acum = 0f
            } else {
                acum += dd
                prev = cur
                i++
            }
        }
        while (res.size < n) res.add(p[p.size - 1].copyOf())
        return res.toTypedArray()
    }

    /**
     * Deslizar para escribir: compara la forma del recorrido del dedo con el camino
     * ideal de cada palabra (de centro a centro de sus teclas), revisa que el dedo haya
     * pasado cerca de cada letra y desempata con lo común que es la palabra y el contexto.
     * [puntos]: el recorrido en teclas; [prop]: alto de una fila en anchos de tecla.
     */
    @Synchronized
    fun deslizar(puntos: List<FloatArray>, prop: Float, previa: String, previa2: String, max: Int): List<String> {
        val ix = indice ?: return emptyList()
        if (puntos.size < 2) return emptyList()
        val pos = HashMap<Char, FloatArray>()
        for ((k, v) in POSICIONES) pos[k] = floatArrayOf(v[0], v[1] * prop)
        val usuario = Array(puntos.size) { floatArrayOf(puntos[it][0], puntos[it][1] * prop) }
        val muestras = 40
        val u = remuestrear(usuario, muestras)
        val largoU = largo(usuario)
        val inicio = usuario[0]
        val fin = usuario[usuario.size - 1]

        val mejores = HashMap<String, Float>()
        fun evaluar(w: String, extra: Float) {
            if (w.length < 2 || bloqueada(w)) return
            val seq = teclasDe(w) ?: return
            if (seq.size < 2) return
            val pf = pos[seq[seq.size - 1]] ?: return
            if (dist(pf, fin) > 1.3f) return
            val pi = pos[seq[0]] ?: return
            if (dist(pi, inicio) > 1.3f) return
            val ideal = Array(seq.size) { pos[seq[it]]!! }
            val largoI = largo(ideal)
            if (largoI > largoU * 1.9f + 1f || largoI < largoU * 0.45f - 1f) return
            val r = remuestrear(ideal, muestras)
            var suma = 0f
            for (k in 0 until muestras) suma += dist(u[k], r[k])
            val forma = suma / muestras
            if (forma > 1.15f) return
            // El dedo tiene que haber pasado cerca de cada letra de la palabra
            var peor = 0f
            for (c in seq) {
                val p = pos[c] ?: return
                var m = Float.MAX_VALUE
                for (q in u) { val dq = dist(p, q); if (dq < m) m = dq }
                if (m > peor) peor = m
                if (peor > 1.05f) return
            }
            val total = forma * 3f + peor * 1.5f + extra - contexto(previa, w, previa2) * 1.2f
            val l = w.lowercase()
            val antes = mejores[l]
            if (antes == null || total < antes) mejores[l] = total
        }

        // Solo se revisan las palabras que empiezan cerca de donde empezó el dedo
        val iniciales = pos.filter { dist(it.value, inicio) <= 1.3f }.keys
            .map { if (it == 'ñ') 'n' else it }.toSet()
        for (ch in iniciales) {
            recorrer(ix, ch, 2, MAX_LARGO) { i ->
                evaluar(ix.palabras[i], Math.log(i + 30.0).toFloat() * 0.22f)
            }
        }
        // Las palabras que tú usas
        for ((w, c) in aprendidas) {
            if (!propia(w, c)) continue
            evaluar(w, 1.0f - minOf(c, 30) * 0.03f - recencia(w) * 0.004f)
        }
        return mejores.entries.sortedBy { it.value }.take(max)
            .map { if (esNombre(it.key)) it.key.replaceFirstChar { c -> c.uppercase() } else it.key }
    }

    // ---------- Aprendizaje ----------

    private fun valida(p: String) = p.length >= 1 && p.all { it.isLetter() }

    /** peso 2 o más = lo elegiste a propósito (sugerencia entre comillas o deshacer corrección) */
    @Synchronized
    fun aprender(p: String, peso: Int = 1) {
        if (pausado || p.length < 2 || !valida(p)) return
        val l = p.lowercase()
        if (l in bloqueadas) {
            // Una palabra olvidada solo vuelve si la eliges a propósito
            if (peso < 2) return
            bloqueadas.remove(l)
        }
        ponerAprendida(l, (aprendidas[l] ?: 0) + peso)
        usadas[l] = hoy()
        cambios = true
        // Por si Android cierra el teclado de golpe: cada tanto se guarda igual
        if (++sinGuardar >= 150) guardar()
    }

    @Synchronized
    fun aprenderPar(previa: String, palabra: String, previa2: String = "") {
        if (pausado || !valida(palabra) || (previa.isNotEmpty() && !valida(previa))) return
        val p2 = if (previa2.isNotEmpty() && valida(previa2)) previa2 else ""
        cerebro.aprender(previa, palabra, p2, app)
        // ¿Escribiste una de las predicciones que se mostraron? (si no, se anotan como ignoradas)
        ultimasPredichas?.let { (ctx, lista) ->
            if (ctx == previa.lowercase()) cerebro.observarPredicciones(previa, lista, palabra)
        }
        ultimasPredichas = null
        val mapa = pares.getOrPut(previa.lowercase()) { HashMap() }
        val w = palabra.lowercase()
        mapa[w] = (mapa[w] ?: 0) + 1
        // Y de a tres palabras, para predecir frases que repites ("nos vemos mañana")
        if (previa.isNotEmpty() && previa2.isNotEmpty() && valida(previa2)) {
            val clave = previa2.lowercase() + " " + previa.lowercase()
            val m3 = trios.getOrPut(clave) { HashMap() }
            m3[w] = (m3[w] ?: 0) + 1
        }
        cambios = true
    }

    /** Deshace el último aprendizaje (borraste la palabra justo después de escribirla) */
    @Synchronized
    fun desaprender(palabra: String, previa: String, previa2: String = "") {
        val l = palabra.lowercase()
        cerebro.desaprender(previa, palabra, previa2, app)
        aprendidas[l]?.let { n -> if (n <= 1) { quitarAprendida(l); usadas.remove(l) } else aprendidas[l] = n - 1 }
        val mapa = pares[previa.lowercase()]
        mapa?.get(l)?.let { n -> if (n <= 1) mapa.remove(l) else mapa[l] = n - 1 }
        if (previa2.isNotEmpty()) {
            val m3 = trios[previa2.lowercase() + " " + previa.lowercase()]
            m3?.get(l)?.let { n -> if (n <= 1) m3.remove(l) else m3[l] = n - 1 }
        }
        cambios = true
    }

    /**
     * [escrito] terminó como [final] (la autocorrección o una sugerencia que tocaste).
     * El cerebro lo anota como uno de tus errores de siempre; si lo deshaces, se resta.
     */
    @Synchronized
    fun notarCorreccion(escrito: String, final: String, peso: Float = 1f) {
        if (pausado) return
        val f = final.trim().trimEnd { !it.isLetterOrDigit() }
        cerebro.aprenderError(escrito.trim(), f, peso)
        cambios = true
    }

    /** Se aplicó sola una autocorrección (para que el cerebro se calibre) */
    @Synchronized
    fun correccionAplicada() {
        if (!pausado) cerebro.correccionAplicada()
    }

    /** Deshiciste una autocorrección (el cerebro se vuelve un poco más prudente) */
    @Synchronized
    fun correccionDeshecha() {
        if (!pausado) cerebro.correccionDeshecha()
    }

    /** Deshiciste la corrección de [original] a [corregido]: a la segunda vez ya no se vuelve a hacer */
    @Synchronized
    fun rechazarCorreccion(original: String, corregido: String) {
        val o = original.trim().lowercase()
        val c = corregido.trim().trimEnd { !it.isLetterOrDigit() }.lowercase()
        if (pausado || o.isEmpty() || c.isEmpty() || o.contains(' ') || o == c) return
        cerebro.rechazarError(o, c)
        val clave = "$o>$c"
        // La primera vez solo se anota; la segunda vez ya no se vuelve a corregir
        if (clave in rechazos) return
        if (rechazosPendientes.remove(clave)) rechazos.add(clave) else rechazosPendientes.add(clave)
        cambios = true
    }

    /** Recuerda los nombres: mayúscula a mitad de oración suma, minúscula resta */
    @Synchronized
    fun notarMayuscula(p: String, aMitadDeOracion: Boolean) {
        if (pausado || !aMitadDeOracion || p.length < 2 || !valida(p)) return
        val l = p.lowercase()
        if (p[0].isUpperCase() && p.drop(1).all { it.isLowerCase() }) {
            nombres[l] = minOf((nombres[l] ?: 0) + 1, 20)
            cambios = true
        } else if (p.all { it.isLowerCase() } && nombres.containsKey(l)) {
            val n = (nombres[l] ?: 0) - 2
            if (n <= 0) nombres.remove(l) else nombres[l] = n
            cambios = true
        }
    }

    /** Olvidar una palabra (mantener presionada la sugerencia → Borrar) */
    @Synchronized
    fun olvidar(p: String) {
        val l = p.lowercase()
        cerebro.olvidar(l)
        quitarAprendida(l)
        usadas.remove(l)
        nombres.remove(l)
        pares.remove(l)
        for (mapa in pares.values) mapa.remove(l)
        trios.keys.removeAll { k -> k.split(' ').contains(l) }
        for (mapa in trios.values) mapa.remove(l)
        bloqueadas.add(l)
        cambios = true
        guardar()
    }

    /** ¿Hay algo aprendido sin guardar? (el teclado decide cuándo guardar para no gastar batería) */
    @Synchronized
    fun pendiente() = cambios

    /** Los pares (o tríos) más usados, hasta [max] (los que sobran y casi no se usan se descartan) */
    private fun mejoresDe(mapas: HashMap<String, HashMap<String, Int>>, max: Int, sb: StringBuilder) {
        var total = 0
        for (m in mapas.values) total += m.size
        val limite = if (total <= max) 0 else {
            // Umbral para quedarse con los ~max más usados sin ordenar todo
            val cuentas = IntArray(32)
            for (m in mapas.values) for (c in m.values) cuentas[minOf(c, 31)]++
            var acum = 0
            var u = 31
            while (u > 0 && acum + cuentas[u] <= max) { acum += cuentas[u]; u-- }
            u + 1
        }
        var n = 0
        for ((clave, mapa) in mapas) {
            for ((w, c) in mapa) {
                if (c < limite || n >= max) continue
                n++
                sb.append(clave).append('\t').append(w).append('\t').append(c).append('\n')
            }
        }
    }

    /**
     * Guarda lo aprendido. La copia del texto se arma con el diccionario bloqueado (es rápido),
     * pero el archivo se escribe después, sin bloquearlo: así guardar nunca frena una tecla.
     */
    fun guardar() {
        val texto: String
        val textoCerebro: String?
        val numero: Long
        val quitarViejo: Boolean
        synchronized(this) {
            if (!cambios) return
            // Si restableciste el diccionario desde los ajustes, no se vuelve a guardar lo viejo
            if (prefs.getInt("aprendidas_rev", 0) != revision) {
                recargarSiCambio()
                return
            }
            val dia = hoy()
            val sb = StringBuilder(128 * 1024)
            sb.append(VERSION_ARCHIVO).append('\n')
            sb.append("#a\n")
            val lista = if (aprendidas.size <= 8000) aprendidas.entries.toList()
            else aprendidas.entries.sortedByDescending { it.value * 10 + recencia(it.key) }.take(8000)
            for ((p, c) in lista) sb.append(p).append('\t').append(c).append('\t').append(usadas[p] ?: dia).append('\n')
            sb.append("#p\n")
            mejoresDe(pares, 12000, sb)
            sb.append("#t\n")
            mejoresDe(trios, 10000, sb)
            sb.append("#r\n")
            for (r in rechazos.take(2000)) sb.append(r).append('\n')
            for (r in rechazosPendientes.take(1000)) sb.append('?').append(r).append('\n')
            sb.append("#b\n")
            for (b in bloqueadas) sb.append(b).append('\n')
            sb.append("#n\n")
            for ((k, v) in nombres) sb.append(k).append('\t').append(v).append('\n')
            texto = sb.toString()
            textoCerebro = if (cerebro.listo && cerebro.hayCambios()) cerebro.instantanea() else null
            numero = ++copiaNumero
            quitarViejo = migrar
            cambios = false
            sinGuardar = 0
        }
        synchronized(escritura) {
            // Otra copia más nueva ya se escribió: esta no la pisa
            if (numero < ultimaEscrita) return
            try {
                // Primero a un archivo temporal y luego se reemplaza: nunca queda a medias
                val tmp = File(archivo.parentFile, "$ARCHIVO.tmp")
                tmp.writeText(texto, Charsets.UTF_8)
                if (!tmp.renameTo(archivo)) {
                    archivo.delete()
                    if (!tmp.renameTo(archivo)) throw java.io.IOException("no se pudo reemplazar $ARCHIVO")
                }
                ultimaEscrita = numero
                if (textoCerebro != null) {
                    try { cerebro.escribir(textoCerebro) } catch (e: Exception) {
                        cerebro.marcarCambios()
                        Registro.anotar("guardar el cerebro", e)
                    }
                }
                if (quitarViejo) {
                    migrar = false
                    prefs.edit().remove("aprendidas").remove("pares").remove("trios").remove("rechazos")
                        .remove("bloqueadas").remove("nombres").apply()
                }
            } catch (e: Exception) {
                // No se pudo: queda pendiente para el próximo guardado
                cambios = true
                Registro.anotar("guardar lo aprendido", e)
            }
        }
    }

    @Synchronized
    fun recargarSiCambio() {
        if (prefs.getInt("aprendidas_rev", 0) != revision) {
            aprendidas.clear()
            indiceAprendidas.clear()
            usadas.clear()
            pares.clear()
            trios.clear()
            rechazos.clear()
            rechazosPendientes.clear()
            bloqueadas.clear()
            nombres.clear()
            normales.clear()
            clases.clear()
            ultimasPredichas = null
            cargar()
            cerebro.cargar()
        }
    }

    private fun agregarAprendida(p: String, n: Int, usada: Int, dia: Int) {
        if (p.isEmpty()) return
        val dias = dia - usada
        // Limpieza: lo que escribiste una sola vez y no volviste a usar en 45 días era un error
        if (n <= 1 && dias > 45) return
        // Lo que dejaste de usar hace meses pierde fuerza poco a poco
        ponerAprendida(p, if (dias > 120 && n > 2) maxOf(2, n / 2) else n)
        usadas[p] = usada
    }

    private fun cargar() {
        revision = prefs.getInt("aprendidas_rev", 0)
        cambios = false
        if (archivo.exists()) {
            try { cargarArchivo(); return } catch (e: Exception) { Registro.anotar("leer lo aprendido", e) }
        }
        cargarViejo()
    }

    private fun cargarArchivo() {
        val dia = hoy()
        var seccion = ' '
        archivo.bufferedReader(Charsets.UTF_8).useLines { lineas ->
            for (linea in lineas) {
                if (linea.isEmpty()) continue
                if (linea[0] == '#' && linea.length == 2) { seccion = linea[1]; continue }
                val partes = linea.split('\t')
                when (seccion) {
                    'a' -> if (partes.size >= 2) {
                        val n = partes[1].toIntOrNull() ?: continue
                        agregarAprendida(partes[0], n, partes.getOrNull(2)?.toIntOrNull() ?: dia, dia)
                    }
                    'p' -> if (partes.size == 3) {
                        val c = partes[2].toIntOrNull() ?: continue
                        pares.getOrPut(partes[0]) { HashMap() }[partes[1]] = c
                    }
                    't' -> if (partes.size == 3) {
                        val c = partes[2].toIntOrNull() ?: continue
                        trios.getOrPut(partes[0]) { HashMap() }[partes[1]] = c
                    }
                    'r' -> if (linea.contains('>')) {
                        if (linea[0] == '?') rechazosPendientes.add(linea.substring(1)) else rechazos.add(linea)
                    }
                    'b' -> bloqueadas.add(linea)
                    'n' -> if (partes.size == 2) nombres[partes[0]] = partes[1].toIntOrNull() ?: continue
                }
            }
        }
    }

    /** Lo aprendido con las versiones anteriores (guardado dentro de los ajustes) */
    private fun cargarViejo() {
        val texto = prefs.getString("aprendidas", "") ?: ""
        val dia = hoy()
        for (par in texto.split("|")) {
            // "palabra:veces:día" (las versiones anteriores guardaban solo "palabra:veces")
            val partes = par.split(':')
            if (partes.size < 2 || partes[0].isEmpty()) continue
            val n = partes[1].toIntOrNull() ?: continue
            agregarAprendida(partes[0], n, partes.getOrNull(2)?.toIntOrNull() ?: dia, dia)
        }
        fun leerMapas(clave: String, destino: HashMap<String, HashMap<String, Int>>) {
            val t = prefs.getString(clave, "") ?: ""
            for (item in t.split(SEP2)) {
                val partes = item.split(SEP1)
                if (partes.size != 3) continue
                val c = partes[2].toIntOrNull() ?: continue
                destino.getOrPut(partes[0]) { HashMap() }[partes[1]] = c
            }
        }
        leerMapas("pares", pares)
        leerMapas("trios", trios)
        (prefs.getString("rechazos", "") ?: "").split("|").filter { it.contains('>') }.forEach { rechazos.add(it) }
        (prefs.getString("bloqueadas", "") ?: "").split("|").filter { it.isNotBlank() }.forEach { bloqueadas.add(it) }
        for (par in (prefs.getString("nombres", "") ?: "").split("|")) {
            val i = par.lastIndexOf(':')
            if (i <= 0) continue
            nombres[par.substring(0, i)] = par.substring(i + 1).toIntOrNull() ?: continue
        }
        if (aprendidas.isNotEmpty() || pares.isNotEmpty()) {
            // Se pasa al archivo nuevo en el próximo guardado
            migrar = true
            cambios = true
        }
    }

    companion object {
        /** Archivo con lo que el teclado aprendió de ti */
        private const val ARCHIVO = "aprendido.txt"
        /** El índice de palabras ya armado (se rehace solo al actualizar la app) */
        private const val CACHE = "indice.bin"
        private const val MAGIA_INDICE = 0x54454331
        private const val VERSION_ARCHIVO = "tecladoios-aprendido-2"
        /** Las palabras más comunes (para prefijos cortos y letras probables) */
        private const val TOP = 20000
        private const val MAX_LARGO = 40
        /** Desde esta posición la palabra es rarísima (formas poco usadas) */
        private const val RARA = 45000
        /** Hasta esta posición la palabra es común (para separar palabras pegadas) */
        private const val COMUN = 15000
        /** Veces que hay que usar una palabra que no está en el diccionario para que sea tuya */
        private const val MIN_PROPIA = 3
        /** Lo mismo para palabras de dos letras ("yq", "jj" casi siempre son errores) */
        private const val MIN_CORTA = 6
        /** Lo mismo para una palabra escrita sin su tilde ("sabado") */
        private const val MIN_VARIANTE = 12
        private const val EN_DICCIONARIO = 0
        private const val SIN_TILDE = 1
        private const val OTRA = 2
        /** Teclas de la fila de abajo que el dedo toca cuando quería el espacio */
        private const val JUNTO_AL_ESPACIO = "bnvmc"
        /** Palabras cortitas de enlace que pueden quedar pegadas por fallar el espacio */
        private val ENLACE = hashSetOf(
            "a", "y", "o", "e", "u", "ya", "yo", "no", "ni", "tu", "te", "me", "se", "mi", "ti",
            "la", "el", "lo", "le", "en", "de", "es", "un", "si", "al", "su", "ay", "va", "ve",
        )

        /** Borra todo lo aprendido (Restablecer diccionario en los ajustes) */
        fun borrarDatos(c: Context) {
            try { File(c.applicationContext.filesDir, ARCHIVO).delete() } catch (_: Exception) { }
            try { File(c.applicationContext.filesDir, Cerebro.ARCHIVO).delete() } catch (_: Exception) { }
        }

        /** Lo que aprendió el cerebro, tal cual está en el archivo (para la copia de seguridad) */
        fun leerCerebro(c: Context): String? {
            guardarAhora()
            return try {
                val f = File(c.applicationContext.filesDir, Cerebro.ARCHIVO)
                if (f.exists()) f.readText(Charsets.UTF_8) else null
            } catch (_: Exception) { null }
        }

        /** Pone el cerebro de una copia de seguridad (se lee de nuevo al abrir el teclado) */
        fun escribirCerebro(c: Context, texto: String) {
            if (!texto.startsWith("tecladoios-cerebro-")) return
            val dir = c.applicationContext.filesDir
            val tmp = File(dir, Cerebro.ARCHIVO + ".tmp")
            tmp.writeText(texto, Charsets.UTF_8)
            val f = File(dir, Cerebro.ARCHIVO)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }

        /** El diccionario que está usando el teclado ahora (si el teclado está abierto en memoria) */
        @Volatile private var activo: WeakReference<Diccionario>? = null

        /** Guarda ya lo aprendido que el teclado tenga pendiente (antes de exportar o listar) */
        fun guardarAhora() {
            try { activo?.get()?.guardar() } catch (_: Throwable) { }
        }

        /** Todo lo aprendido tal cual está en el archivo (para la copia de seguridad) */
        fun leerDatos(c: Context): String? {
            guardarAhora()
            return try {
                val f = File(c.applicationContext.filesDir, ARCHIVO)
                if (f.exists()) f.readText(Charsets.UTF_8) else null
            } catch (_: Exception) { null }
        }

        /** ¿Es un archivo de lo aprendido válido? (para no importar cualquier cosa) */
        fun datosValidos(texto: String) = texto.startsWith("tecladoios-aprendido-")

        /**
         * Reemplaza lo aprendido por [texto] (al importar una copia de seguridad) y avisa al
         * teclado para que lo vuelva a leer.
         */
        fun escribirDatos(c: Context, texto: String) {
            val dir = c.applicationContext.filesDir
            val tmp = File(dir, "$ARCHIVO.tmp")
            tmp.writeText(texto, Charsets.UTF_8)
            val f = File(dir, ARCHIVO)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            avisarCambio(c)
        }

        /** El teclado vuelve a leer el archivo la próxima vez que se abra */
        private fun avisarCambio(c: Context) {
            val p = Ajustes.prefs(c)
            p.edit()
                .remove("aprendidas").remove("pares").remove("trios")
                .remove("rechazos").remove("bloqueadas").remove("nombres")
                .putInt("aprendidas_rev", p.getInt("aprendidas_rev", 0) + 1)
                .commit()
        }

        /** Las palabras que aprendió de ti, de la más usada a la menos usada: (palabra, veces) */
        fun palabrasAprendidas(c: Context): List<Pair<String, Int>> {
            val texto = leerDatos(c) ?: return emptyList()
            val lista = ArrayList<Pair<String, Int>>()
            var seccion = ' '
            for (linea in texto.lineSequence()) {
                if (linea.isEmpty()) continue
                if (linea[0] == '#' && linea.length == 2) { seccion = linea[1]; continue }
                if (seccion != 'a') continue
                val partes = linea.split('\t')
                val n = partes.getOrNull(1)?.toIntOrNull() ?: continue
                if (partes[0].isNotEmpty()) lista.add(partes[0] to n)
            }
            return lista.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        }

        /** Olvidar una palabra desde los ajustes (igual que mantener presionada la sugerencia → Borrar) */
        fun olvidarPalabra(c: Context, palabra: String) {
            val l = palabra.lowercase()
            val vivo = activo?.get()
            if (vivo != null) {
                try {
                    // Por si importaste o restableciste algo antes: primero se pone al día
                    vivo.recargarSiCambio()
                    vivo.olvidar(l)
                    return
                } catch (_: Throwable) { }
            }
            // El teclado no está en memoria: se edita el archivo directamente
            val texto = leerDatos(c) ?: return
            val sb = StringBuilder(texto.length)
            var seccion = ' '
            var bloqueadaPuesta = false
            for (linea in texto.lineSequence()) {
                if (linea.isEmpty()) continue
                if (linea[0] == '#' && linea.length == 2) {
                    if (seccion == 'b' && !bloqueadaPuesta) { sb.append(l).append('\n'); bloqueadaPuesta = true }
                    seccion = linea[1]
                    sb.append(linea).append('\n')
                    continue
                }
                val partes = linea.split('\t')
                val quitar = when (seccion) {
                    'a', 'n' -> partes[0] == l
                    'p' -> partes[0] == l || partes.getOrNull(1) == l
                    't' -> partes[0].split(' ').contains(l) || partes.getOrNull(1) == l
                    'b' -> linea == l
                    else -> false
                }
                if (!quitar) sb.append(linea).append('\n')
            }
            if (!bloqueadaPuesta) {
                if (seccion != 'b') sb.append("#b\n")
                sb.append(l).append('\n')
            }
            try { escribirDatos(c, sb.toString()) } catch (_: Exception) { }
        }

        private val MARCAS = Regex("\\p{Mn}+")
        private val ESPACIOS = Regex("\\s+")

        /**
         * Inglés de todos los días en el chat: no se corrige al español. Se dejaron fuera las
         * que son errores típicos de una palabra en español ("hot" por "hoy", "team" por "tema").
         */
        private val INGLES = hashSetOf(
            "ok", "okay", "link", "links", "like", "likes", "love", "baby", "babe", "please", "sorry",
            "thanks", "thank", "you", "yes", "yeah", "nope", "wifi", "online", "offline", "story",
            "stories", "post", "posts", "selfie", "meme", "memes", "crush", "random", "cool", "nice",
            "bro", "happy", "birthday", "weekend", "show", "live", "delivery", "feedback", "meeting",
            "email", "mail", "password", "login", "app", "apps", "update", "reel", "reels", "tiktok",
            "stream", "streamer", "gamer", "full", "premium", "style", "look", "outfit", "match",
            "chill", "vibe", "vibes", "hater", "haters", "friend", "friends", "cute", "fashion",
            "fitness", "gym", "coach", "crack", "fail", "zoom", "sticker", "stickers", "fans", "fake",
            "check", "smart", "delete", "download", "upload", "playlist", "remix", "feat", "trend",
            "trending", "influencer", "follow", "followers", "unfollow", "spoiler", "girl", "boy",
            "bestie", "besties", "honey", "darling", "omg", "lol", "btw", "idk", "asap", "cringe",
            "flow", "swag", "drip", "flex", "ghosting", "dating", "single", "party", "brunch", "snack",
            "burger", "cheesecake", "brownie", "cupcake", "coffee", "bye", "hello", "hey", "guys",
        )

        private fun sinTildes(s: String) =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARCAS, "")
        private const val SEP1 = "\u0001"
        private const val SEP2 = "\u0002"

        /** Centro de cada tecla (en anchos de tecla), igual que en el teclado */
        private val POSICIONES: Map<Char, FloatArray> = HashMap<Char, FloatArray>().apply {
            "qwertyuiop".forEachIndexed { i, c -> put(c, floatArrayOf(i + 0.5f, 0f)) }
            "asdfghjklñ".forEachIndexed { i, c -> put(c, floatArrayOf(i + 0.5f, 1f)) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, floatArrayOf(i + 2f, 2f)) }
        }

        private fun similares(a: Char, b: Char): Boolean = when (a) {
            'b' -> b == 'v'
            'v' -> b == 'b'
            's' -> b == 'z' || b == 'c'
            'z' -> b == 's' || b == 'c'
            'c' -> b == 's' || b == 'z' || b == 'k'
            'i' -> b == 'y'
            'y' -> b == 'i'
            'g' -> b == 'j'
            'j' -> b == 'g'
            'k' -> b == 'q' || b == 'c'
            'q' -> b == 'k'
            else -> false
        }

        /** Cuánto cuesta haber tocado [a] queriendo tocar [b]: poco si son teclas vecinas */
        private fun costoCambio(a: Char, b: Char): Float {
            if (a == b) return 0f
            // Confusiones típicas de ortografía: b/v, s/c/z, i/y, g/j, k/q/c
            if (similares(a, b)) return 0.3f
            val pa = POSICIONES[a] ?: return 1f
            val pb = POSICIONES[b] ?: return 1f
            val dx = pa[0] - pb[0]
            val dy = pa[1] - pb[1]
            val d = Math.sqrt((dx * dx + dy * dy).toDouble())
            return when {
                d <= 1.05 -> 0.4f   // tecla de al lado o justo arriba/abajo
                d <= 1.6 -> 0.6f    // diagonal
                else -> 1.2f        // teclas lejanas: es raro equivocarse así
            }
        }

        /** Letras con tilde → su tecla (la ñ tiene su propia tecla) */
        private val SIN_ACENTO: Map<Char, Char> = mapOf(
            'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
            'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o',
            'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c',
        )

        /** Para cada letra: las teclas vecinas y las que se confunden al escribir */
        private val VECINOS: Map<Char, List<Char>> = HashMap<Char, List<Char>>().apply {
            val letras = POSICIONES.keys.toList()
            for (a in letras) put(a, letras.filter { b -> b != a && costoCambio(a, b) < 1f })
        }

        /** Al empezar una oración (como el iPhone: Hola, Yo, Ya...) */
        private val INICIO = listOf("hola", "yo", "ya", "gracias", "ok", "buenos", "qué", "no", "sí", "te", "buenas", "oye", "dale", "listo", "cómo", "me", "estoy", "el", "la", "en", "estimado", "quisiera", "por", "bueno", "amor", "voy", "muchas", "les", "le")

        /** Para cuando no hay predicción propia de la palabra anterior */
        private val GENERALES = listOf("y", "que", "de", "en", "a", "no", "con", "para", "el", "la", "por", "se")

        /** Risas que no se corrigen: jajaja, jsjsjs, jeje, xd */
        private val RISA = Regex("(?:j+[aeiouhs]+)+j*|(?:js|sj)+[js]?|x+d+")

        /**
         * Errores frecuentes del chat (de oído, por apuro o del corrector) → lo correcto.
         * Ganan a la autocorrección normal; si deshaces la corrección, el teclado aprende
         * que la querías así y no la vuelve a tocar.
         */
        private val ERRORES_COMUNES: Map<String, String> = """
            aser:hacer aserlo:hacerlo aserla:hacerla aserme:hacerme aserte:hacerte asiendo:haciendo
            ise:hice isiste:hiciste isimos:hicimos isieron:hicieron izo:hizo
            haci:así asi:así asií:así aora:ahora aorita:ahorita orita:ahorita horita:ahorita
            nesesito:necesito nesecito:necesito nesecitas:necesitas nesesitas:necesitas nesesita:necesita
            nesecita:necesita nesesario:necesario nesesaria:necesaria
            dijistes:dijiste fuistes:fuiste hicistes:hiciste tuvistes:tuviste estuvistes:estuviste
            pudistes:pudiste comistes:comiste llegastes:llegaste mandastes:mandaste salistes:saliste
            estubo:estuvo estube:estuve estubiste:estuviste estubieron:estuvieron tubiste:tuviste
            iva:iba ivas:ibas ivamos:íbamos ivan:iban bamos:vamos vien:bien vueno:bueno vuena:buena
            llendo:yendo hayga:haya haiga:haya naiden:nadie nadien:nadie dijieron:dijeron
            trajieron:trajeron trajistes:trajiste
            enserio:en_serio aveces:a_veces talvez:tal_vez porfavor:por_favor almenos:al_menos
            apesar:a_pesar deacuerdo:de_acuerdo encambio:en_cambio atravez:a_través através:a_través
            derrepente:de_repente envez:en_vez alfin:al_fin nisiquiera:ni_siquiera osea:o_sea
            sierto:cierto sierta:cierta desir:decir dises:dices dise:dice disen:dicen desirte:decirte
            hiba:iba hibas:ibas ubiera:hubiera ubieras:hubieras ubiese:hubiese abia:había avia:había
            habia:había abeces:a_veces asta:hasta ahy:ahí
            conosco:conozco conoses:conoces paresco:parezco paresca:parezca paresiera:pareciera
            veses:veces graxias:gracias grasias:gracias grasia:gracia
            pasao:pasado cansao:cansado terminao:terminado parao:parado salao:salado helao:helado
            kieres:quieres kiere:quiere kieren:quieren kisiera:quisiera keda:queda kedo:quedo
            kedamos:quedamos kedate:quédate porke:porque porqe:porque xke:por_qué
            tambien:también despues:después todavia:todavía
            cumpleanos:cumpleaños ano:año anos:años
            exelente:excelente esactamente:exactamente esacto:exacto
            eskina:esquina ecsamen:examen exsamen:examen exámen:examen
            haora:ahora aki:aquí alla:allá
            ojala:ojalá ojála:ojalá ademas:además quizas:quizás
            bacan:bacán chevere:chévere bakan:bacán bkn:bacán
            wasap:WhatsApp guasap:WhatsApp whatsap:WhatsApp watsap:WhatsApp
            istagram:Instagram instagran:Instagram facebok:Facebook feisbuk:Facebook
            yutub:YouTube youtuve:YouTube
            ubicacion:ubicación direccion:dirección cancion:canción informacion:información
            aber:a_ver hiba:iba ivamos:íbamos hechar:echar
            haci:así hací:así extranio:extraño estrañar:extrañar estraño:extraño estraña:extraña
            ecxelente:excelente escelente:excelente esepto:excepto exepto:excepto
            nececito:necesito nesesitamos:necesitamos nececitas:necesitas nececita:necesita
            ubiera:hubiera uviera:hubiera huviera:hubiera ubo:hubo uvo:hubo huvo:hubo
            preosupado:preocupado preocupao:preocupado emosionado:emocionado emosionada:emocionada
            entonses:entonces entoces:entonces enronces:entonces
            porfabor:por_favor porfavo:por_favor
            dispulpa:disculpa discupa:disculpa
            okei:okey aorra:ahora alguen:alguien alguein:alguien sige:sigue
            cuidate:cuídate quedate:quédate callate:cállate calmate:cálmate
            avisame:avísame llamame:llámame escribeme:escríbeme pasame:pásame mandame:mándame
            dimelo:dímelo esperame:espérame ayudame:ayúdame cuentame:cuéntame
            yapeame:yapéame plineame:plinéame
            haver:haber aver:a_ver  hechar:echar   bayan:vayan  
            exito:éxito  proposito:propósito analisis:análisis basicamente:básicamente
            practicamente:prácticamente rapidamente:rápidamente facilmente:fácilmente ultimamente:últimamente
            unicamente:únicamente especificamente:específicamente automaticamente:automáticamente
            economicamente:económicamente tecnicamente:técnicamente logicamente:lógicamente
              consiencia:conciencia conciente:consciente 
            inconciente:inconsciente desicion:decisión desiciones:decisiones decision:decisión ocacion:ocasión
            ocaciones:ocasiones ocasion:ocasión previlegio:privilegio privilejio:privilegio exepcion:excepción
            excepcion:excepción  espectativa:expectativa espectativas:expectativas
            exhorbitante:exorbitante extrictamente:estrictamente  sinembargo:sin_embargo
            noobstante:no_obstante porlotanto:por_lo_tanto osea:o_sea apartir:a_partir 
               inprescindible:imprescindible
            imprecindible:imprescindible inprimir:imprimir inportante:importante inposible:imposible
            inperdible:imperdible inbitacion:invitación invitacion:invitación nececidad:necesidad nesesidad:necesidad
            nesecidad:necesidad presisamente:precisamente  presicion:precisión
            precision:precisión  concecuencia:consecuencia conclucion:conclusión
            conclusion:conclusión revicion:revisión revision:revisión dirijir:dirigir corrijir:corregir
            elejir:elegir elijir:elegir escojer:escoger recojer:recoger protejer:proteger
            reciviste:recibiste recivir:recibir recivido:recibido recibi:recibí recivi:recibí
            enviame:envíame envialo:envíalo enviamelo:envíamelo pasamelo:pásamelo dimelo:dímelo
            explicame:explícame explicamelo:explícamelo mandamelo:mándamelo ayudame:ayúdame
            confirmame:confírmame indicame:indícame comunicame:comunícame informame:infórmame
            agradeceria:agradecería podria:podría podrias:podrías deberia:debería deberias:deberías
            tendria:tendría tendrias:tendrías  serian:serían habria:habría haria:haría
            gustaria:gustaría encantaria:encantaría queria:quería querias:querías  tenia:tenía
            tenias:tenías habian:habían habiamos:habíamos estabamos:estábamos ibamos:íbamos eramos:éramos
            reunion:reunión atencion:atención solucion:solución situacion:situación educacion:educación
            relacion:relación organizacion:organización administracion:administración comunicacion:comunicación
            investigacion:investigación presentacion:presentación evaluacion:evaluación aplicacion:aplicación
            confirmacion:confirmación cotizacion:cotización explicacion:explicación participacion:participación
            documentacion:documentación capacitacion:capacitación coordinacion:coordinación gestion:gestión
            opcion:opción  razon:razón corazon:corazón tambien:también despues:después
        """.trim().split(ESPACIOS).mapNotNull { par ->
            val i = par.indexOf(':')
            if (i <= 0) null else par.substring(0, i) to par.substring(i + 1).replace('_', ' ')
        }.toMap()

        private val INTERROGATIVAS = mapOf(
            "que" to "qué", "como" to "cómo", "donde" to "dónde", "adonde" to "adónde",
            "cuando" to "cuándo", "quien" to "quién", "quienes" to "quiénes", "cual" to "cuál",
            "cuales" to "cuáles", "cuanto" to "cuánto", "cuanta" to "cuánta",
            "cuantos" to "cuántos", "cuantas" to "cuántas",
        )

        /** "anterior actual" sin tildes → con tildes correctas */
        private val PARES_TILDE: Map<String, Pair<String, String>> = """
            que tal|qué tal; que haces|qué haces; que hacen|qué hacen; que paso|qué pasó; que pasa|qué pasa;
            que fue|qué fue; que onda|qué onda; que hora|qué hora; que dices|qué dices; que hiciste|qué hiciste;
            que miedo|qué miedo; que bueno|qué bueno; que rico|qué rico; que lindo|qué lindo; que linda|qué linda;
            que asco|qué asco; que pena|qué pena; que roche|qué roche; que palta|qué palta; que bien|qué bien;
            que bonito|qué bonito; que feo|qué feo; que raro|qué raro; por que|por qué;
            que cosa|qué cosa; que tienes|qué tienes; que tiene|qué tiene; que quieres|qué quieres; que quiere|qué quiere;
            que comiste|qué comiste; que vas|qué vas; que vamos|qué vamos; que dijo|qué dijo; que dijiste|qué dijiste;
            que hay|qué hay; que haria|qué haría; que harias|qué harías; que significa|qué significa; que hubo|qué hubo;
            que tan|qué tan; que tanto|qué tanto; que chevere|qué chévere; que bacan|qué bacán; que genial|qué genial;
            que alegria|qué alegría; que gracioso|qué gracioso; que graciosa|qué graciosa; que triste|qué triste;
            que lastima|qué lástima; que suerte|qué suerte; que flojera|qué flojera; que sueño|qué sueño; que hambre|qué hambre;
            que calor|qué calor; que frio|qué frío; que mal|qué mal; que horrible|qué horrible; que locura|qué locura;
            que te|qué te; que le|qué le; que me|qué me; que nos|qué nos;
            como estas|cómo estás; como esta|cómo está; como estan|cómo están; como fue|cómo fue;
            como asi|cómo así; como vas|cómo vas; como va|cómo va; como sigues|cómo sigues;
            como haces|cómo haces; como amaneciste|cómo amaneciste; como te|cómo te; como se|cómo se;
            como hago|cómo hago; como hiciste|cómo hiciste; como estuvo|cómo estuvo; como les|cómo les; como le|cómo le;
            como sabes|cómo sabes; como que|cómo que; como estamos|cómo estamos; como andas|cómo andas;
            donde estas|dónde estás; donde esta|dónde está; donde estan|dónde están; donde vives|dónde vives;
            donde queda|dónde queda; donde vas|dónde vas; de donde|de dónde; a donde|a dónde; por donde|por dónde;
            donde andas|dónde andas; donde nos|dónde nos; donde fuiste|dónde fuiste; donde compraste|dónde compraste;
            cuando vienes|cuándo vienes; cuando llegas|cuándo llegas; cuando sales|cuándo sales;
            cuando vas|cuándo vas; desde cuando|desde cuándo; hasta cuando|hasta cuándo; para cuando|para cuándo;
            cuando nos|cuándo nos; cuando regresas|cuándo regresas; cuando vuelves|cuándo vuelves; cuando es|cuándo es;
            quien es|quién es; quien eres|quién eres; quien sabe|quién sabe; quien fue|quién fue; quien te|quién te;
            con quien|con quién; de quien|de quién; para quien|para quién; quien dijo|quién dijo; quien era|quién era;
            cual es|cuál es; cual era|cuál era; cuales son|cuáles son; cuanto es|cuánto es; cuanto cuesta|cuánto cuesta;
            cuanto vale|cuánto vale; cuanto falta|cuánto falta; cuantos años|cuántos años; cuantos anos|cuántos años;
            cuanto tiempo|cuánto tiempo; cuanto te|cuánto te; cuanto me|cuánto me; cuantas veces|cuántas veces;
            esta bien|está bien; esta mal|está mal; esta aqui|está aquí; esta ahi|está ahí; esta lloviendo|está lloviendo;
            ya esta|ya está; no esta|no está; todo esta|todo está; esta bonito|está bonito; esta bonita|está bonita;
            esta rico|está rico; esta rica|está rica; esta feo|está feo; esta fea|está fea; esta loco|está loco; esta loca|está loca;
            esta en|está en; esta con|está con; esta durmiendo|está durmiendo; esta trabajando|está trabajando;
            estas bien|estás bien; estas ahi|estás ahí; estas ocupado|estás ocupado; estas ocupada|estás ocupada;
            estas loco|estás loco; estas loca|estás loca; estas en|estás en; estas con|estás con; estas haciendo|estás haciendo;
            estas despierto|estás despierto; estas despierta|estás despierta; estas molesto|estás molesto; estas molesta|estás molesta;
            el es|él es; el esta|él está; el dijo|él dijo; el no|él no; el me|él me; el tambien|él también; con el|con él;
            para el|para él; de el|de él;
            tu eres|tú eres; tu estas|tú estás; tu sabes|tú sabes; tu tambien|tú también; tu no|tú no; tu que|tú qué;
            tu tienes|tú tienes; tu dijiste|tú dijiste; y tu|y tú; tu crees|tú crees;
            si claro|sí claro; si si|sí sí; claro si|claro sí; mas o|más o; mas tarde|más tarde; aun no|aún no; mas que|más que;
            mi mama|mi mamá; mi papa|mi papá; tu mama|tu mamá; tu papa|tu papá; su mama|su mamá; su papa|su papá;
            a mi|a mí; para mi|para mí; de mi|de mí; por mi|por mí; sin mi|sin mí;
            se que|sé que; no se|no sé; yo se|yo sé; ya se|ya sé; solo se|solo sé;
            tambien te|también te; tambien me|también me; tambien yo|también yo; yo tambien|yo también;
            despues te|después te; despues de|después de
        """.trim().split(";").mapNotNull { linea ->
            val partes = linea.trim().split("|")
            if (partes.size != 2) null else {
                val a = partes[0].trim().split(" ")
                val b = partes[1].trim().split(" ")
                if (a.size == 2 && b.size == 2) "${sinTildes(a[0])} ${sinTildes(a[1])}" to (b[0] to b[1]) else null
            }
        }.toMap()

        /** Predicción: después de cada palabra, las que más se escriben (chat peruano) */
        private val PARES_BASE: Map<String, List<String>> = """
            que: tal, es, no, te, haces, pasó, me, se, hay, bueno
            qué: tal, haces, pasó, es, bien, fue, hora, dices, onda, rico, roche, palta
            de: la, que, los, nada, verdad, mi, tu, las, una, ahí
            te: quiero, amo, extraño, llamo, aviso, escribo, digo, mando, paso, espero
            buenos: días
            buenas: noches, tardes
            muchas: gracias, veces, cosas
            muchos: saludos, besos, años
            por: favor, qué, eso, fin, la, el, mi, aquí, ahí, si
            estoy: bien, en, yendo, llegando, cansado, cansada, saliendo, trabajando, en casa, aquí
            hola: cómo, qué, amor, causa, buenos, buenas, bebé, mi
            como: estás, está, te, así, que, se, vas, va
            cómo: estás, está, te, así, que, se, vas, va
            nos: vemos, vamos, hablamos, vemos mañana, avisas
            a: la, las, qué, ver, mí, el, los, que, tu
            en: la, el, casa, serio, un, una, mi, la noche, el trabajo
            no: sé, te, hay, puedo, pasa, me, es, tengo, quiero, importa
            yo: también, te, no, estoy, sé, tampoco, voy, creo, quiero, igual
            ya: voy, llegué, está, pe, nos, salgo, estoy, sé, me, fue
            voy: a, en, para, saliendo, llegando, con
            vamos: a, al, a la, mañana, pe, juntos
            gracias: por, amor, causa, a ti, igualmente, de verdad
            todo: bien, el, lo, está, tranquilo, ok
            está: bien, en, con, muy, rico, aquí
            estás: bien, en, haciendo, ahí, con, ocupado, ocupada
            dónde: estás, está, vives, queda, nos
            donde: estás, está, vives, queda
            mi: amor, mamá, papá, casa, vida, hermano, hermana, celular, cielo, bebé
            tu: casa, mamá, papá, celular, número, hermano, vida
            tal: vez, cual
            lo: siento, que, sé, mismo, voy, hago, tengo, vi
            un: momento, rato, poco, ratito, día, beso, abrazo, toque
            una: vez, pregunta, cosa, foto, semana, hora
            me: avisas, gusta, voy, parece, encanta, dices, pasas, llamas, mandas, escribes
            le: dije, digo, voy, aviso, mando, pregunto
            se: me, te, fue, va, puede, lo, que, siente
            es: que, un, una, el, la, muy, mi, verdad
            muy: bien, bueno, buena, rico, lindo, linda, tarde, temprano, cansado
            bien: y tú, gracias, y, pe, ahí
            y: tú, tu, que, yo, el, la, me, ya, no, ahora
            pero: no, ya, si, que, bueno, igual
            para: que, ti, mí, el, la, mañana, ir, qué
            con: mi, el, la, los, mis, quién, tu, todo
            el: lunes, sábado, domingo, viernes, fin, otro, día, que
            la: verdad, próxima, otra, casa, noche, misma
            los: chicos, demás, quiero, amo
            tengo: que, una, un, sueño, hambre, frío, miedo, clases, que ir
            tienes: que, razón, algo, tiempo
            quiero: que, verte, ir, saber, comer, dormir, decirte
            puedo: ir, llamarte, pasar, hablar
            puedes: venir, llamarme, pasarme, mandarme, ayudarme
            hay: que, algo, clases, chamba, tiempo
            hoy: no, es, día, en la noche, voy, tengo
            mañana: te, nos, es, voy, temprano, en la mañana, hablamos
            ahorita: te, voy, no, vuelvo, salgo, llego, regreso
            después: te, de, hablamos, nos, vemos
            luego: te, hablamos, nos, vemos, regreso
            feliz: cumpleaños, día, año, navidad, fin
            hasta: mañana, luego, pronto, la, el
            dale: pe, ya, gracias, nos vemos
            ok: gracias, ya, perfecto, dale, nos vemos
            okey: gracias, ya, dale
            sí: claro, pe, ya, te, me, mañana
            claro: que, pe, ya, sí, no hay problema
            disculpa: la, por, que
            perdón: por, la, amor
            cuídate: mucho, bastante, amor
            besos: y, abrazos
            amor: te, mío, cómo, ya, mi
            causa: qué, ya, vamos, cómo
            bro: qué, ya, vamos, cómo
            oe: causa, qué, ya, bro, mano
            pucha: qué, ya, no, mare
            jaja: ya, no, sí, qué, pe
            jajaja: ya, no, sí, qué
            cuánto: es, cuesta, te, falta, tiempo
            cuándo: vienes, nos, es, llegas, sales
            quién: es, eres, te, sabe, fue
            cuál: es, te, era
            porque: no, te, me, yo, ya, es, estoy
            entonces: ya, nos, qué, vamos, mañana
            igual: te, no, ya, vamos
            también: te, me, yo, está
            tampoco: yo, me, te
            nada: más, que, pe, mal
            algo: así, más, para, de
            todos: los, ustedes, están
            espero: que, estés, verte
            ojalá: que, puedas, mañana
            avísame: cuando, si, para
            llámame: cuando, cuando puedas, mañana
            escríbeme: cuando, mañana
            pásame: la, el, tu, la foto, el número
            mándame: la, el, foto, tu, audio
            dime: qué, si, cuando, dónde
            sabes: qué, que, si, dónde
            sé: que, si, dónde
            creo: que, no, sí
            parece: que, bien, bonito
            ya llegué: a, amor
            estamos: en, yendo, llegando, bien
            están: en, bien, todos
            vas: a, al, a la, mañana
            va: a, al, a la, bien
            tenemos: que, clases, examen
            fue: muy, un, una, a, el
            era: un, una, el, la, para, que
            sale: el, la, a, mañana
            salgo: a, del, de, ahorita
            llego: a, en, tarde
            llegué: a, bien, tarde, a casa
            pasa: algo, que, nada, por
            pasó: algo, nada, con, que
            vengo: de, del, a, mañana
            ven: a, mañana, rápido, pe
            mira: lo, la, esto, eso
            oye: causa, amor, qué, una
            hola amor: cómo, buenos
            buen: día, provecho, viaje, fin
            mucho: gusto, trabajo, tiempo, más, floro, roche
            poco: a, más, tiempo
            más: tarde, o, rato, que, bien, de
            otra: vez, cosa
            otro: día, lado
            ahí: está, te, nos, vamos, voy
            aquí: estoy, en, te, está
            acá: en, estoy, te
            tan: bonito, linda, lindo, rico, temprano, tarde
            así: es, que, nomás, como
            gran: día, idea
            al: toque, final, menos, fin, rato, costado
            del: trabajo, colegio, día
            les: aviso, escribo, mando, digo
            nunca: más, te, me
            siempre: te, me, que
            primero: que, voy
            vale: la, pe, gracias
            chévere: pe, causa, gracias
            bacán: pe, causa
            listo: ya, pe, nos vemos
            habla: causa, bro, mano, pe, ps
            firme: pe, causa, nomás, que
            ponte: mosca, las, pilas, trucha
            asu: mare, qué, causa
            mare: qué, mía
            manyas: causa, pe, que
            tremendo: roche, floro, jale, tono
            full: chamba, roche, tráfico, gente
            pura: paja, floro, vida
            puro: floro, cuento, roche
            nomás: pe, causa, ya, es
            yara: con, que, causa
            ni: hablar, modo, idea, siquiera
            sin: roche, palta, problema, miedo
yapéame: porfa, cuando, la, el, ahorita
            yapea: al, a, nomás, la
            yapeé: a, ya, la, el
            plinéame: porfa, cuando, ahorita
            cuánta: plata, gente
            estuvo: bien, bacán, buenazo, rico, chévere, mal
            estaba: en, durmiendo, trabajando, viendo, pensando
            tenía: que, un, una, razón
            puede: ser, que, ir, venir
            podemos: ir, vernos, hablar, salir, quedar
            quieres: ir, que, algo, salir, comer, venir
            vienes: a, mañana, hoy, o
            vengan: a, mañana, temprano
            salimos: a, mañana, hoy, el
            vemos: mañana, luego, el, en
            hablamos: mañana, luego, más tarde, después
            avisa: cuando, si, para
            espérame: un, ahí, en, que
            ayúdame: con, a, porfa
            manda: la, el, foto, audio
            llama: a, cuando, me
            escribe: cuando, me
            ojo: que, con, pe
            normal: nomás, pe, no, ya
            seguro: que, pe, sí, no
            obvio: que, sí, no, pe
            exacto: eso, pe, así
            demasiado: bueno, rico, tarde, temprano, caro
            bastante: bien, tarde, tiempo, gente
            súper: bien, bueno, rico, lindo, linda, tarde, temprano
            recién: llego, estoy, salgo, me, termino, acabo
            acabo: de, que
            acaba: de
            ando: en, con, bien, cansado, cansada
            andas: en, con, bien, haciendo
            necesito: que, un, una, tu, hablar, ayuda
            felicidades: por, amor, causa
            bendiciones: para, a, amor
            saludos: a, para, cordiales
            atentamente: el, la
            estimado: señor, cliente
            estimada: señora, clienta
            hace: un, una, rato, frío, calor, tiempo, días
            hice: un, una, la, el
            hizo: un, una, la, el
            dijo: que, a, la
            dije: que, a
            sabías: que
            cualquier: cosa, día, momento
            cada: vez, día, uno
            ningún: problema
            fin: de
            pase: lo, a, por
            pasé: por, a, la
            pasaste: la, el, por
            entré: a, al, en
            muchísimas: gracias
            jajajaja: ya, no, sí, qué
            xd: ya, no
            lunes: a, en, por
            martes: a, en
            miércoles: a, en
            jueves: a, en
            viernes: a, en, por
            sábado: a, en, por
            domingo: a, en, por
        """.trim().lines().mapNotNull { linea ->
            val i = linea.indexOf(':')
            val clave = if (i <= 0) "" else linea.substring(0, i).trim()
            // Como en iPhone: se predice una sola palabra a la vez
            if (clave.isEmpty() || clave.contains(' ')) null
            else clave to linea.substring(i + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }
        }.toMap()

        /** Palabras más usadas al escribir en el chat, de más a menos (manda el puntaje) */
        private const val FRECUENCIA = """
            que de no a la el y en lo es un por me una te los se con para mi pero las su al le si ya
            qué bien sí hola gracias ok jaja jajaja yo tú tu él ella todo como cómo muy más está estoy
            estás bueno buena buenos buenas días noches tardes amor causa pe pues oe bro ya
            voy vamos va vas quiero quieres puedo puedes tengo tienes hay hacer hace haces hago hice
            ser soy eres es son fue era sé sabes sabe saber ver veo vi visto dime dile dijo dije
            también tampoco nada algo nadie alguien siempre nunca ahora ahorita hoy mañana ayer después luego
            aquí acá ahí allá así porque entonces aunque cuando donde dónde cuándo quién cuál cuánto
            casa trabajo chamba colegio clases universidad celular mensaje foto fotos video audio llamada
            mamá papá hermano hermana amigo amiga amigos amigas familia novio novia hijo hija bebé
            tiempo día hora horas rato momento semana mes año años vez veces noche tarde temprano
            mucho mucha muchos muchas poco poca otro otra otros mismo cada todos todas
            bonito bonita lindo linda rico rica feo fea grande pequeño nuevo nueva mejor peor
            claro dale listo okey vale chévere bacán genial perfecto tranquilo tranquila igual
            llegué llego llegas llega salgo sale salir llegar ir venir vengo ven viene vienes
            nos les sus mis tus del cual cuanto quien
            avísame llámame escríbeme pásame mándame espérame ayúdame cuéntame
            te quiero amo extraño extrañas cariño corazón besos beso abrazo abrazos
            comer comida almuerzo cena desayuno hambre sueño dormir duermo cansado cansada
            plata soles pagar comprar tienda mercado pasaje carro taxi combi
            fiesta reunión cumpleaños feliz felicidades saludos suerte bendiciones
            verdad mentira obvio exacto seguro segura
            creo crees parece gusta gustó encanta necesito necesitas
            bueno ya pe pucha mare asu jajajaja xd
        """

        // Palabras con tilde o ñ para corregir cuando las escribes sin ella
        private const val BASE_TILDES = """
            también después además todavía quizás jamás aquí allá acá ahí así atrás según algún ningún
            común fácil difícil útil árbol cámara música número página rápido rápida teléfono último última
            público pública práctica práctico lápiz inglés francés japonés alemán canción camión avión razón
            corazón opción acción atención información relación situación educación dirección estación
            habitación televisión pasión emoción ilusión decisión reunión región millón jabón botón ratón
            limón melón salón sillón balón montón café bebé sofá menú papá mamá tío tía río frío día días
            mía tía película políticos política médico médica clínica química física matemática gráfico
            pasó llegó salió comió terminó empezó habló llamó quedó dejó mandó compró pagó ganó perdió
            vivió murió subió bajó entró pidió escribió recibió sintió durmió pensó esperó trabajó estudió
            viajó regresó volvió jugó cambió encontró llevó tomó sacó dio
            llegué pagué jugué busqué toqué saqué empecé terminé compré hablé llamé mandé dejé quedé pensé
            esperé entré trabajé estudié viajé regresé volví comí salí escribí viví perdí subí recibí sentí
            pedí dormí leí oí reí vi
            será estará tendrá hará irá podrá vendrá saldrá llegará haré iré podré tendré estaré seré
            pasaré llamaré escribiré avisaré veré diré sabré querrá
            sería podría tendría haría iría debería querría gustaría estaría
            tenía había quería sabía podía decía hacía vivía creía sentía salía venía
            estábamos íbamos éramos teníamos hacíamos
            dímelo avísame llámame escríbeme mándame pásame dámelo cuéntame explícame ayúdame espérame
            búscame díselo mírala míralo enséñame acompáñame tráeme cómpralo
            lógico básico económico eléctrico único típico clásico fantástico romántico simpático
            sábado miércoles jóvenes exámenes imágenes órdenes
            año años niño niña niños niñas señor señora baño mañana pequeño pequeña sueño otoño
            cumpleaños español española compañero compañera compañía montaña araña enseñar diseño dueño
            dueña cariño piña uña uñas ñaño ñaña engaño extraño extraña
        """

        // Más palabras frecuentes: verbos conjugados, sustantivos y expresiones del día a día
        private const val BASE_EXTRA = """
            ser soy eres somos son sido siendo estar estaba estaban estuve estuvo estado estando
            tener tenía tenías tuve tuvo tienen tenido teniendo hacer haces hacemos hacen hice hiciste
            hecho haciendo ir vas va van fui fuiste fuimos ido yendo iba ibas íbamos poder puede pueden
            pude pudo podía podría podrías querer quiere queremos quieren quise quería querías decir dices
            dice decimos dicen dije dijo dicho diciendo saber sabe sabemos saben supe sabía sabías veo ves
            vemos ven vi vio visto viendo dar doy das da damos dan di dio dado venir vienes vienen vine
            vino viniste venido llegar llegas llega llegamos llegan llegó llegaste pasar pasas pasamos
            pasan pasé pasado poner pongo pones pone ponemos ponen puse puso puesto quedar quedo quedas
            queda quedamos quedan quedé quedó creer creo crees cree creemos creen creí hablar hablo hablas
            habla hablamos hablan hablé habló hablando llevar llevo llevas lleva llevamos llevé dejar dejo
            dejas deja dejé dejó seguir sigo sigues sigue seguimos siguen encontrar encuentro encontré
            llamo llamas llama llamamos llamé llamó pensar pienso piensas piensa pensé pensando conocer
            conozco conoces conoce conocí vivir vivo vives vive vivimos sentir siento sientes siente
            sentí mirar miro miras miré contar cuento cuentas cuenta conté empezar empiezo empieza empezó
            esperar espero esperas espera esperé esperando buscar busco buscas busca busqué buscando
            entrar entro entras entra entré trabajar trabajo trabajas trabaja trabajé trabajando escribo
            escribes escribí escribiendo perder pierdo pierdes pierde perdiste entender entiendo entiendes
            entiende entendí pedir pido pides pide pedí recibir recibo recibí recordar recuerdo recuerdas
            terminar termino terminé terminó terminamos conseguir consigo conseguí leer leo lees leí caer
            caí cayó cambiar cambio cambié crear abrir abro abres abre abrí abierto cerrar cierro cierra
            cerré cerrado acabar acabo acabé acabó ganar gano ganas gana traer traigo traes trae traje
            morir muero aceptar acepto lograr logré explicar explico explícame preguntar pregunto pregunté
            pregúntale tocar toco tocó estudias estudia estudiamos estudiando correr corro corrí utilizar
            pago pagas paga pagué ayudar ayudo ayudas ayuda ayúdame ayudé gustan gustaba escuchar escucho
            escuchas escucha escuché cumplir ofrecer descubrir levantar levanto intentar intento intenté
            usar uso usas usa usé decidir decidí olvidar olvido olvidé olvidaste comer como comes come
            comemos comí comiste comiendo mostrar muestra mover muevo continuar suceder sucede preparar
            preparo enviar envío envías envía envié enviaste mandar mando mandas manda mandé mándame
            dormir duermo duermes duerme dormí durmiendo despertar despierto desperté bañar baño bañé
            cocinar cocino limpiar limpio manejar manejo viajar viajo regresar regreso regresé volver
            vuelvo vuelves vuelve volví subir subo subí bajar bajo bajé caminar camino caminé bailar
            bailo cantar canto reír río llorar lloro extrañar extraño extrañas extrañé amar amo amas
            odio molestar molesta preocupar preocupa preocupes importar importa importó faltar falta
            faltan doler duele avisar aviso avisas avisa avisé confirmar confirmo responder respondo
            respondes respondí contestas contesté grabar descargar instalar actualizar cargar prender
            apagar conectar recoger recojo paso pasas pasa pásame dime díselo cuéntame mírame espera
            oye escúchame tranquilo dale vale chévere genial bacán increíble horrible feo bonito bonita
            lindo linda hermoso hermosa rico rica bueno malo mala grande gran pequeña nuevo viejo vieja
            joven mayor menor primero primera segundo segunda tercero último siguiente anterior mismo
            igual diferente posible imposible importante necesario seguro segura listo lista libre
            ocupado ocupada cansada enfermo enferma contento contenta triste alegre aburrido aburrida
            molesto molesta preocupado preocupada emocionado emocionada nervioso nerviosa bravo brava
            gente persona personas hombre mujer chico chica chicos chicas niños hijo hija hijos padre
            madre papás tío tía primo prima abuelo abuela familia novio novia esposo esposa vecino
            vecina jefe compañeros grupo mundo país ciudad pueblo barrio lugar sitio parte lado centro
            casa departamento cuarto sala puerta ventana cama mesa silla ropa zapatillas polo casaca
            mochila llaves billetera tarjeta cuenta pasaje carro bus moto tren avión aeropuerto terminal
            calle avenida esquina parque playa mar río cerro campo chacra plaza iglesia hospital clínica
            farmacia banco tienda bodega mercado restaurante pollería chifa cevichería cine fiesta
            reunión cumple matrimonio bautizo velorio partido concierto evento cita trabajo oficina
            colegio cole universidad instituto academia curso clase tarea examen práctica nota notas
            libro cuaderno lapicero celular cel teléfono laptop computadora compu tablet audífonos
            cargador internet wifi señal batería saldo recarga datos megas mensaje audio llamada
            videollamada foto fotos video videos estado historia historias publicación comentario like
            grupo chat contacto número correo cuenta clave contraseña aplicación app juego juegos
            partida nivel equipo cosa cosas algo nada todo todos todas mucho muchos poco pocos varios
            varias alguno alguna ningún ninguno ninguna cualquier otro otra mismo tanto tanta cuánto
            cuánta cuántos cuántas cuál cuáles quién quiénes dónde adónde cuándo cómo qué porque pues
            entonces luego después antes ahora ahorita hoy mañana ayer anoche tarde temprano siempre
            nunca jamás casi solo sólo también tampoco además aunque sino mientras hasta desde durante
            según contra sin sobre bajo entre hacia mediante dentro fuera cerca lejos arriba abajo
            adelante atrás encima debajo delante detrás aquí acá ahí allí allá sí no quizá quizás
            claro obvio exacto cierto verdad mentira bueno bien mal mejor peor rápido lento despacio
            fuerte suave mucho demasiado bastante apenas recién justo tal cual hora horas minuto minutos
            segundo segundos día días semana semanas mes meses año años momento rato vez veces tiempo
            mañanita tardecita nochecita fin finde feriado vacaciones navidad año nuevo cumpleaños
            hambre sed sueño frío calor miedo ganas suerte prisa razón culpa problema problemas
            solución pregunta respuesta idea plan opinión verdad noticia noticias historia caso forma
            manera modo tipo clase número cantidad precio plata sencillo vuelto soles dólares cuenta
            deuda pago regalo sorpresa comida almuerzo desayuno cena lonche menú pan leche café té
            jugo agua gaseosa cerveza chela vino pollo carne pescado arroz frijoles lentejas papa
            camote yuca choclo huevo queso fruta manzana plátano naranja mango palta tomate cebolla
            ají limón azúcar sal salchipapa hamburguesa pizza tallarines anticucho causa rocoto
            jaja jajaja jajajaja jeje jsjs xd ok okey okay oki ya yap yaa sip nop nel simón uff ufff
            wow guau ay ayy ah oh eh mmm bueno chau chao adiós hola holi holis buenas saludos gracias
            graciass porfavor perdón disculpa lo siento felicidades feliz suerte bendiciones besos
            abrazos cuídate descansa duerme bien que te vaya bien nos vemos hablamos luego te llamo
            te escribo avísame cuando llegues ya llegué estoy llegando voy saliendo en camino
            gil sapo tombo pituco pituca huachafo huachafa achorado achorada jijuna conchudo conchuda
            floro florear jugador lenteja cabro chancón chancona tono tonazo chupar hablar firme fresh
        """

        // Jerga peruana (de la calle, del barrio, de la chamba y del chat), con sus formas más usadas
        private const val JERGA = """
            causa causita causón causas causitas pata patita patazo patas patitas broder bróder brother
            compadre compa cumpa cumpita choche chochera chocheras yunta yuntas pana panas mancha manchita
            jato jatos jatear jateo jateas jatea jateamos jatean jateé jateó jateando jatearse
            jerma jermita jermas flaca flaco flaquita flaquito chibolo chibola chibolos chibolas chibolito
            chibolita churre churres churrito chiquillo chiquilla chiquillos chiquillas tío tía tíos
            viejo vieja viejos viejita viejito cucha cucho taita mamita papito
            chamba chambas chambear chambeo chambeas chambea chambeamos chambean chambeé chambeó chambeando
            chambeador chambeadora chambita cachuelo cachuelos cachuelito cachuelear cachueleando
            misio misia misios misias luca lucas lukas sencillo china chinita gamba
            manyar manyo manyas manya manyamos manyan manyé manyaste manyó manyando manyado
            jalar jalo jalas jala jalamos jalan jalé jalaste jaló jalado jalada jalados jalando jalador
            jaladora jale jalón jalones
            chancar chanco chancas chanca chancamos chancan chanqué chancó chancando chancón chancona
            chancones chanconas
            floro floros florear floreo floreas florea florean floreé floreó floreando florero florera
            roche roches rochoso rochosa rochear rochearse roché palta paltas paltearse palteado palteada
            palteo paltazo piña piñas piñazo piñata ampay ampayar ampayado ampayada ampayaron ampayé
            chotear choteo choteado choteada choteó choteaste chotearon
            achorado achorada achorados achoradas achorao choro chora choros chorear choreo choreó
            choreado choreada faite faites pastrulo pastrula fumón tronchito pirañita pirañitas rata ratas
            tombo tombos tomba cana canazo cachaco cachacos serenazgo
            gil gila giles gilazo sapo sapa sapos sapear sapeando sapeó lorna lornaza lornas monse monses
            huachafo huachafa huachafos huachafas huachafería pituco pituca pitucos pitucas pituquería
            lenteja lentejas mosca mosquita trucha pilas firme firmeza fresh tranqui tranquis
            bacán bacanazo bacancito bacanes chévere cheverazo mostro mostrazo mostra pulenta
            bravazo bravaza buenazo buenaza paja pajaza pajita pajas cuadro yuca yucazo yara
            jama jamear jameo jameas jamea jameamos jamean jameé jameó jameando bitute bitutear
            lonche lonchear lonchecito lonchera huarique huariques chifa chifita pollería polladas pollada
            chela chelas chelita chelear chelear chupar chupe chupa chupo chupamos chupando chupódromo
            trago tragos tragito juerga juerguear juergueando tono tonos tonazo tonear toneo
            perreo perrear perreando quinceañero quinceañera fulbito pichanga pichangas pichanguita
            pichanguear pelota pata cancha canchita
            telo telos calato calata calatos calatear calatearse
            tirar tira tiras tiré tiraste tiró tirando chape chapar chapo chapas chapé chapó chapando
            chaparse chapa chapita encamotado encamotada encamotarse camote camotito pisado pisada
            pisados gileo gilear gileando gileaste vacilar vacilo vacilas vacila vacilé vacilando
            vacilón vacilona vacile jugador jugadora jugadores brichero brichera
            arroz cachina cachinero cachinera bamba bambas bambear bambero bambera
            caleta caletas caletita caletazo yapa yapita yapear zampar zampado zampada zampón zampona
            zamparse gorrear gorrero gorrera gorreo
            maletear maleteo maletero maletera mermelero mermelada otorongo otorongos pulpín pulpines
            cachimbo cachimba cachimbos paporreta pericote pericotes chancho chancha chanchita
            colecta chompa chompita casaca polo polito zapatillas taba tabas jean jeans chalina
            combi combis cobrador micro paradero colectivo mototaxi mototaxista mototaxis chofer
            bodega bodeguero bodeguera bodegas pasaje jirón galería galerías
            chilcano pisco piscos emoliente emolientero emolientera picarones tequeños anticuchos
            anticuchera anticuchero ceviche cevichito cevichería salchipapa salchipapas chaufa aeropuerto
            rocoto ají cancha choclo chicha chicharrón chicharronero sanguchito sánguche
            sánguches sanguchería
            asu asumare mare maree pucha puchamare alucina alucinas alucinante habla hablas
            oe oye ps pes pe nomás yaa yap yapo sipo nones nica chau chaufa
            ñaño ñaña ñaños ñañas ñoba ñaupa charapa charapita chalaco chalaca chalacos limeño limeña
            limeños provinciano provinciana serrano serrana costeño costeña
            jijuna conchudo conchuda conchudez conchudos frescura fresco fresca patero patera
            chicoteo chacra chacrita cholito cholita trome tromes tromazo crack capo capaza
            grone grones crema cremas ídolo maestro maestra maestrazo jefe jefazo jefecito jefa
            brutal asado asada asados asar
            quemar quemado quemada quema quemé quemó quemaste
            cocho cocha cochos cuchucho cuchi cuchito cuchita
            jeta jetón jetona mitra mitras coco coquito
            tutear
             
        """

        // Ordenadas de más a menos usadas
        private const val BASE = """
            que de no a la el y en lo es un por me una te los se con para mi pero las su al le si ya
            todo como bien esta este eso esto esa ese muy hay también sí qué cómo cuándo dónde quién cuál
            porque hola gracias bueno buena buenos buenas días noches tardes jaja jajaja jajajaja xd
            causa pata chamba chévere bacán pe pues oe asu mare habla tal vez mañana hoy ayer ahorita
            después luego nada nadie algo alguien siempre nunca casa trabajo colegio universidad celular
            mamá papá hermano hermana hermanito hermanita amigo amiga amigos amigas quiero puedo tengo voy
            vamos estoy estás está estamos están era fue hacer hago hace hizo dime dile sé sabes saber ver
            veo vez cosa cosas tiempo año años día hora horas momento ahora aquí ahí allá así más menos
            mucho mucha muchos muchas poco poca nuevo nueva grande pequeño pequeña mejor peor otro otra
            otros mismo misma cada sin sobre entre hasta desde hacia durante según yo tú él ella nosotros
            ustedes ellos ellas usted conmigo contigo nos les sus tu del cuando donde quien cual cuanto
            claro okey ok listo dale chau adiós vemos quiere quieres amor cariño beso besos abrazo feliz
            cumpleaños porfa favor disculpa perdón siento salud comida almuerzo cena desayuno pollo ceviche
            chaufa lomo saltado arroz papa papas agua gaseosa chela plata luca lucas soles dinero pagar
            comprar vender tienda mercado calle carro combi micro taxi cuarto baño cocina tele televisor
            juego juegos jugar video videos foto fotos música canción película serie mensaje mensajes
            llamar llamada llámame escribir escribe responde contesta número teléfono internet wifi datos
            batería cargador tarde noche semana mes lunes martes miércoles jueves viernes sábado domingo
            enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre Perú
            Lima peruano verdad mentira sería será fueron vino viene venir ir irme salir sale salimos
            llegar llego llegué estudiar estudio examen tarea clase profe profesor profesora curso nota
            notas difícil fácil rápido lento temprano todavía aún recién quizás tampoco además entonces
            aunque mientras sino solo sola acá allí oye mira ojo pucha bro mano manyas jato jerma flaco
            flaca chibolo chibola roche palta piña misio alucina yapa paja bravazo buenazo chambear jalar
            aprobar aprobé ganar perder gané perdí partido fútbol pelota equipo gol mal tranqui tranquilo
            tranquila cansado cansada sueño hambre frío calor lluvia sol playa paseo viaje moto bici
            corazón razón información atención opinión compañero compañera niño niña señor señora español
            inglés último última próximo próxima jamás árbol lápiz también qué tal pasó pasa pasar dijo
            dijiste digo creo crees parece gusta gustó encanta necesito necesitas podemos puedes tienes
            tiene tenemos hay había habías avísame avísale espérame ven vente vengo
        """
    }
}
