package com.tecladoios

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    /** ¿Palabra nueva a una letra de una muy común? (se calcula una vez por palabra) */
    private val cercanas = HashMap<String, Boolean>()
    /** Modo incógnito: no se aprende nada (lo pide la app, como Chrome incógnito) */
    @Volatile var pausado = false

    /** El cerebro: tu modelo de lenguaje personal (frases, apps, errores de siempre) */
    private val cerebro = Cerebro(File(context.applicationContext.filesDir, Cerebro.ARCHIVO))

    /** Otros idiomas que escribes a la vez (inglés, portugués): sin cambiar nada a mano */
    private val idiomas = Idiomas(context)

    /** Activa los idiomas extra ("en", "pt"). Cargar es lento: llamar fuera de la pantalla. */
    fun ponerIdiomas(codigos: Set<String>) = idiomas.activar(codigos)

    /** Campo nuevo: la frase anterior ya no manda tanto sobre el idioma */
    fun campoNuevo() = idiomas.campoNuevo()

    /**
     * Android pide memoria con el teclado cerrado: se sueltan las cosas que se rearman solas
     * (idiomas extra y cachés). El diccionario principal se queda: es lo que hace que el
     * teclado aparezca con sugerencias al instante.
     */
    @Synchronized
    fun soltarMemoria() {
        idiomas.soltar()
        clases.clear()
        cercanas.clear()
    }
    /**
     * Tus teclas confundidas (las aprende el cerebro): la autocorrección cobra menos por los
     * dedazos que tú sueles hacer. Es una copia para leerla rápido en cada comparación.
     */
    @Volatile private var confusion: FloatArray? = null
    /** Letras que sueles comerte y letras que sueles poner de más (perfil del cerebro) */
    @Volatile private var falta: FloatArray? = null
    @Volatile private var sobra: FloatArray? = null
    /** Qué tanto sueles cruzar letras con los dos pulgares (1 = normal, menos = seguido) */
    @Volatile private var factorCruce = 1f

    /** Copia el perfil de errores del cerebro para leerlo rápido en cada comparación */
    private fun refrescarPerfil() {
        val pf = cerebro.perfil()
        confusion = pf?.cambio
        falta = pf?.falta
        sobra = pf?.sobra
        factorCruce = pf?.cruce ?: 1f
    }

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
            try { refrescarPerfil() } catch (_: Throwable) { }
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
        tipeos = leerTipeos(c)
        return Indice(base.palabras, base.normal, base.orden, base.ordenTop, base.grupos, pares, trios)
    }

    /**
     * Errores de tipeo ya aprendidos de fábrica (assets/errores.txt): los dedazos más comunes
     * de las palabras más usadas ("tranajo" → "trabajo", "nafie" → "nadie"). Solo los que no
     * se pueden confundir con otra palabra; se corrigen al instante, sin calcular nada.
     */
    @Volatile private var tipeos: Tipeos = Tipeos.VACIO

    /**
     * Los errores de fábrica (720 mil) guardados de la forma más compacta: todos los errores
     * pegados en UN solo bloque de bytes, en orden, con la posición donde empieza cada uno, y
     * las correcciones como números que apuntan a una lista de palabras sin repetir. Antes
     * cada error era un objeto aparte (más de 30 MB de memoria): así ocupa menos de la mitad,
     * Android no tiene que cerrar el teclado por falta de memoria y se busca igual de rápido
     * (búsqueda binaria: unas 20 comparaciones por palabra).
     */
    /**
     * Los errores de fábrica (casi 800 mil) en el formato compacto de assets/errores.bin
     * (ver herramientas/compilar_errores.py). Normalmente se usan DIRECTO desde el APK
     * ("mapeados"): no ocupan memoria del teclado ni hay que leer nada al arrancar; Android trae
     * solo las partes que se consultan. Números de 3 bytes.
     */
    private class Tipeos(private val b: ByteBuffer) {
        private val n: Int
        private val oIni: Int
        private val oDest: Int
        private val oPalIni: Int
        private val oClaves: Int
        private val oPal: Int

        init {
            if (b.capacity() >= 20 && b.get(0) == 'T'.code.toByte() && b.get(1) == 'P'.code.toByte() &&
                b.get(2) == 'E'.code.toByte() && b.get(3) == '1'.code.toByte()
            ) {
                n = b.getInt(4)
                val m = b.getInt(8)
                val totalClaves = b.getInt(12)
                oIni = 20
                oDest = oIni + (n + 1) * 3
                oPalIni = oDest + n * 3
                oClaves = oPalIni + (m + 1) * 3
                oPal = oClaves + totalClaves
            } else {
                n = 0; oIni = 0; oDest = 0; oPalIni = 0; oClaves = 0; oPal = 0
            }
        }

        val size get() = n

        private fun u24(p: Int): Int =
            (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or
                ((b.get(p + 2).toInt() and 0xFF) shl 16)

        /** Compara el error [i] con [k] (bytes UTF-8), como el orden del archivo */
        private fun comparar(i: Int, k: ByteArray, soloPrefijo: Boolean = false): Int {
            val a = u24(oIni + i * 3)
            val largo = u24(oIni + (i + 1) * 3) - a
            for (x in 0 until minOf(largo, k.size)) {
                val c = (b.get(oClaves + a + x).toInt() and 0xFF) - (k[x].toInt() and 0xFF)
                if (c != 0) return c
            }
            if (soloPrefijo && largo >= k.size) return 0
            return largo - k.size
        }

        private fun buscar(k: String): Int {
            if (n == 0) return -1
            val kb = k.toByteArray(Charsets.UTF_8)
            var a = 0
            var z = n - 1
            while (a <= z) {
                val m = (a + z) ushr 1
                val c = comparar(m, kb)
                if (c == 0) return m
                if (c < 0) a = m + 1 else z = m - 1
            }
            return -1
        }

        private fun palabra(j: Int): String {
            val a = u24(oPalIni + j * 3)
            val z = u24(oPalIni + (j + 1) * 3)
            val arr = ByteArray(z - a)
            for (x in arr.indices) arr[x] = b.get(oPal + a + x)
            return String(arr, Charsets.UTF_8)
        }

        operator fun get(k: String): String? {
            val i = buscar(k)
            return if (i >= 0) palabra(u24(oDest + i * 3)) else null
        }

        fun containsKey(k: String) = buscar(k) >= 0

        /** ¿Algún error empieza con [np]? */
        fun hayConPrefijo(np: String): Boolean {
            if (n == 0) return false
            val kb = np.toByteArray(Charsets.UTF_8)
            var a = 0
            var z = n
            while (a < z) {
                val m = (a + z) ushr 1
                if (comparar(m, kb) < 0) a = m + 1 else z = m
            }
            return a < n && comparar(a, kb, soloPrefijo = true) == 0
        }

        companion object {
            val VACIO = Tipeos(ByteBuffer.allocate(0))
        }
    }

    private fun leerTipeos(c: Context): Tipeos {
        // 1) Lo normal: el binario sin comprimir dentro del APK, mapeado (cero copias)
        try {
            c.assets.openFd("errores.bin").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).channel.use { canal ->
                    val buf = canal.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val t = Tipeos(buf)
                    if (t.size > 0) return t
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            // Comprimido dentro del APK: no se puede mapear, se lee de una vez (abajo)
        } catch (e: Exception) { Registro.anotar("mapear errores de fábrica", e) }
        // 2) El binario comprimido: un solo bloque de bytes (sin procesar línea por línea)
        try {
            c.assets.open("errores.bin").use { ent ->
                val t = Tipeos(ByteBuffer.wrap(ent.readBytes()).order(ByteOrder.LITTLE_ENDIAN))
                if (t.size > 0) return t
            }
        } catch (_: java.io.FileNotFoundException) {
        } catch (e: Exception) { Registro.anotar("leer errores.bin", e) }
        // 3) Versión vieja (solo errores.txt): se arma el mismo formato en memoria
        return leerTipeosTexto(c)
    }

    private fun leerTipeosTexto(c: Context): Tipeos {
        val unicas = HashMap<String, Int>(60_000)
        val palabras = ArrayList<String>(60_000)
        val claves = ArrayList<String>(800_000)
        var destinos = IntArray(800_000)
        var cuantos = 0
        try {
            c.assets.open("errores.txt").bufferedReader().useLines { lineas ->
                for (linea in lineas) {
                    if (linea.isEmpty() || linea[0] == '#') continue
                    val i = linea.indexOf(':')
                    if (i <= 0 || i >= linea.length - 1) continue
                    val d = linea.substring(i + 1).trim().replace('_', ' ')
                    val k = unicas.getOrPut(d) { palabras.add(d); palabras.size - 1 }
                    claves.add(linea.substring(0, i))
                    if (cuantos == destinos.size) destinos = destinos.copyOf(destinos.size * 2)
                    destinos[cuantos++] = k
                }
            }
        } catch (e: Exception) {
            Registro.anotar("leer errores de fábrica", e)
            return Tipeos.VACIO
        }
        unicas.clear()
        val orden = claves.indices.sortedWith { x, y -> compararBytes(claves[x], claves[y]) }
        val n = claves.size
        val clavesB = Array(n) { claves[orden[it]].toByteArray(Charsets.UTF_8) }
        val palB = Array(palabras.size) { palabras[it].toByteArray(Charsets.UTF_8) }
        val totalClaves = clavesB.sumOf { it.size }
        val totalPal = palB.sumOf { it.size }
        val buf = ByteBuffer.allocate(20 + (n + 1) * 3 + n * 3 + (palB.size + 1) * 3 + totalClaves + totalPal)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put("TPE1".toByteArray(Charsets.US_ASCII))
        buf.putInt(n).putInt(palB.size).putInt(totalClaves).putInt(totalPal)
        fun put24(v: Int) { buf.put((v and 0xFF).toByte()); buf.put(((v shr 8) and 0xFF).toByte()); buf.put(((v shr 16) and 0xFF).toByte()) }
        var p = 0
        for (k in clavesB) { put24(p); p += k.size }
        put24(p)
        for (x in 0 until n) put24(destinos[orden[x]])
        p = 0
        for (w in palB) { put24(p); p += w.size }
        put24(p)
        for (k in clavesB) buf.put(k)
        for (w in palB) buf.put(w)
        claves.clear()
        return Tipeos(buf)
    }

    /** Orden por bytes UTF-8 (el mismo del archivo) */
    private fun compararBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    /** ¿[np] es el comienzo de un dedazo de fábrica? ("connsec" → "connsecuencia") */
    private fun comienzoDeTipeo(np: String): Boolean {
        if (np.length < 3) return false
        return tipeos.hayConPrefijo(np)
    }

    /**
     * El error de fábrica de [l], salvo que [l] sea una palabra que el diccionario conoce
     * (al compilar se agregan miles de palabras: una palabra de verdad nunca se "corrige").
     */
    private fun tipeoDeFabrica(l: String): String? {
        val t = tipeos[l] ?: return null
        val ix = indice ?: return t
        return if (rango(ix, l) >= 0) null else t
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
        var conMedio = false
        try {
            c.assets.open("diccionario.txt").bufferedReader().useLines { lineas ->
                for (linea in lineas) {
                    val p = linea.trim()
                    if (p.isEmpty() || p[0] == '#') continue
                    if (p == "@fijas") {
                        conFijas = true
                        for (w in (BASE + " " + BASE_TILDES + " " + BASE_EXTRA + " " + JERGA + " " + JERGA_MAS + " " + JERGA_MUNDO).trim().split(ESPACIOS)) sumar(w)
                        continue
                    }
                    sumar(p)
                    // Sector público, comida, lugares y marcas: entran con una frecuencia media
                    // (se conocen y se sugieren, pero no tapan a las palabras de todos los días)
                    if (!conMedio && lista.size >= POS_MEDIO) {
                        conMedio = true
                        for (w in MEDIO.split(ESPACIOS)) sumar(w)
                    }
                }
            }
        } catch (_: Exception) { }
        if (!conFijas) for (w in (BASE + " " + BASE_TILDES + " " + BASE_EXTRA + " " + JERGA + " " + JERGA_MAS + " " + JERGA_MUNDO).trim().split(ESPACIOS)) sumar(w)
        if (!conMedio) for (w in MEDIO.split(ESPACIOS)) sumar(w)
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

    /**
     * Modo formal (correo y apps de trabajo, lo pone el servicio): la jerga del chat baja mucho
     * en las sugerencias y al empezar se proponen saludos formales ("Estimado", "Buenos"...).
     */
    @Volatile var formal = false

    private fun esJerga(w: String): Boolean = sinTildes(w.lowercase()) in JERGA_CHAT

    /** ¿Es una lisura? ("huevón", "carajo"...) */
    private fun esLisura(w: String): Boolean {
        val l = w.lowercase()
        return l in LISURAS || sinTildes(l) in LISURAS
    }

    /**
     * Una lisura que todavía no usaste: el teclado la conoce (nunca te la cambia por otra
     * palabra), pero no te la sugiere ni corrige hacia ella hasta que tú la escribas.
     * Como el iPhone: no te aparece una grosería si tú no las usas.
     */
    private fun lisuraAjena(w: String): Boolean {
        if (w.contains(' ')) return w.split(' ').any { lisuraAjena(it) }
        if (!esLisura(w)) return false
        // Recién cuando la usaste un par de veces se te sugiere (una sola vez puede ser un descuido)
        return (aprendidas[w.lowercase()] ?: 0) < 2
    }

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
            esContacto(p.lowercase()) || p.lowercase() in INGLES || idiomas.existe(p)

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
            else -> when {
                // Golpes de teclado ("asdf", "kjjjk", "hgfd") nunca se vuelven palabras tuyas
                pareceBasura(l) -> false
                l.length <= 2 -> n >= MIN_CORTA && l.any { it in "aeiouáéíóú" }
                // Palabra nueva que está a una letra de una muy común: tiene que repetirse más
                // (así un dedazo que se te escapó dos veces no se vuelve "tuyo")
                n < MIN_CERCANA && cercaDeComun(ix, l) -> false
                else -> true
            }
        }
    }

    /**
     * ¿[l] parece un golpe de teclado y no una palabra? Cinco consonantes seguidas, una
     * consonante tres veces seguidas o una fila del teclado corrida ("asdf", "hjkl").
     */
    private fun pareceBasura(l: String): Boolean {
        if (l.length < 3) return false
        // (Sin vocales sí puede ser tuya: "tqm", "ntp", "xd", "pls" son abreviaturas a propósito)
        val vocales = "aeiouáéíóúü"
        var cons = 0
        var rep = 1
        for (i in l.indices) {
            cons = if (l[i] in vocales || l[i] == 'y') 0 else cons + 1
            if (cons >= 5) return true
            rep = if (i > 0 && l[i] == l[i - 1]) rep + 1 else 1
            if (rep >= 3 && l[i] !in vocales && l[i] !in "jsr") return true
        }
        return FILAS_TECLADO.any { l.contains(it) }
    }

    /** ¿Hay una palabra muy común (entre las 5000 primeras) a una sola letra de [l]? */
    private fun cercaDeComun(ix: Indice, l: String): Boolean = cercanas.getOrPut(l) {
        val np = normal(l)
        var hay = false
        for (ch in iniciales(np)) {
            recorrer(ix, ch, np.length - 1, np.length + 1) { i ->
                if (!hay && i < 5000 && distancia(np, ix.normal[i], ix.normal[i].length, 0.6f) <= 0.6f) hay = true
            }
            if (hay) break
        }
        hay
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

    /** Mayúsculas para una palabra de otro idioma ("I'm" se queda como está) */
    private fun igualarMayusOtro(c: String, modelo: String): String =
        if (c.any { it.isUpperCase() }) c else igualarMayus(c, modelo)

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
        val cf = confusion
        val fFalta = falta
        val fSobra = sobra
        val fCruce = factorCruce
        for (i in 1..n) {
            actual[0] = i.toFloat()
            var minimo = actual[0]
            val ca = a[i - 1]
            val ia = if (cf != null || fSobra != null) Cerebro.letra(ca) else -1
            // Letra de más: si repetiste la letra o es vecina de la anterior/siguiente, fue un
            // dedo que rozó dos teclas ("hokla" → "hola") y cuesta menos. Si es una letra que
            // tú sueles poner de más (lo aprendió el cerebro), cuesta todavía menos
            var extra = when {
                i >= 2 && a[i - 2] == ca -> 0.5f
                (i >= 2 && costoCambio(a[i - 2], ca) <= 0.4f) || (i < n && costoCambio(a[i], ca) <= 0.4f) -> 0.65f
                else -> 1f
            }
            if (fSobra != null && ia >= 0) extra *= fSobra[ia]
            for (j in 1..m) {
                val cb = b[j - 1]
                var cambio = if (tq != null) costoToque(tq[i - 1], ca, cb) else costoCambio(ca, cb)
                // Un dedazo tuyo de siempre (lo aprendió el cerebro) cuesta menos
                if (cf != null && ia >= 0 && cambio > 0f) {
                    val ib = Cerebro.letra(cb)
                    if (ib >= 0) cambio *= cf!![ia * Cerebro.LETRAS + ib]
                }
                var v = antes[j - 1] + cambio
                v = minOf(v, antes[j] + extra)
                // Letra que faltó (letra doble o "h" muda cuestan menos: "aroz" → "arroz", "ola" → "hola").
                // Las letras que tú sueles comerte (lo aprendió el cerebro) cuestan menos todavía
                var falto = if (cb == 'h') 0.3f else if (j >= 2 && b[j - 2] == cb) 0.5f else 1f
                if (fFalta != null) {
                    val ib = Cerebro.letra(cb)
                    if (ib >= 0) falto *= fFalta[ib]
                }
                v = minOf(v, actual[j - 1] + falto)
                // Dos letras invertidas: "qeu" → "que". Si los dos pulgares tocaron casi a la vez
                // (menos de 70 ms entre uno y otro), el orden se cruza muy fácil: cuesta la mitad
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    val t = tq?.get(i - 1)
                    val alaVez = t != null && t.size > 3 && t[3] >= 0f && t[3] < 70f
                    v = minOf(v, antes2[j - 2] + (if (alaVez) 0.3f else 0.6f) * fCruce)
                }
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
        // Otros idiomas: sus palabras entran según cuánto pesa ese idioma en la frase. Si en
        // español casi no hay palabras que empiecen así, también entran (así "beau..." ofrece
        // "beautiful" aunque vengas escribiendo en español).
        if (idiomas.activo && !cortado()) {
            val (_, pesoOtro) = idiomas.dominante() ?: ("" to 0f)
            if (pesoOtro >= 0.3f || res.size < 3) {
                val escala = if (pesoOtro >= 0.3f) 0.25f + pesoOtro else 0.3f
                for ((w, r) in idiomas.completar(prefijo, 6)) {
                    val lw = w.lowercase()
                    if (lw == lp || bloqueada(lw)) continue
                    val f = (1000.0 - 85.0 * Math.log(r + 1.0)).toInt().coerceAtLeast(1)
                    val v = (f * escala).toInt()
                    if ((res[w] ?: 0) < v) res[w] = v
                }
            }
        }
        val enFormal = formal
        return res.entries.asSequence()
            .filter { !lisuraAjena(it.key) && !(enFormal && esLisura(it.key)) }
            .sortedByDescending { if (enFormal && esJerga(it.key)) it.value / 4 else it.value }
            // Siglas y marcas con su forma correcta (SUNAT, EsSalud, WhatsApp) y sin repetir
            .map { FORMAS[sinTildes(it.key)] ?: it.key }
            .distinct()
            .take(max)
            .map { w ->
                val s = if (w.any { it.isUpperCase() }) w else igualarMayus(w, prefijo)
                if (esContacto(w.lowercase())) s.replaceFirstChar { c -> c.uppercase() } else s
            }
            .toList()
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
        val fijas = if (l.isEmpty()) inicioAhora() else paresFijos(l)
        fijas?.forEachIndexed { i, w -> if (!bloqueada(w)) res[w] = (res[w] ?: 0) + (8 - i).coerceAtLeast(1) }
        // El cerebro: tu manera de seguir esta frase (hasta tres palabras atrás, y en esta app)
        val ixC = indice
        for ((w, pr) in cerebro.candidatos(previa, previa2, app, "", 8)) {
            if (bloqueada(w) || (ixC != null && !conoce(ixC, w))) continue
            res[w] = (res[w] ?: 0) + (pr * 160f).toInt()
        }
        // Saludo según la hora: "buenas" → "tardes" de día y "noches" de noche
        if (l == "buenas" || l == "buenos") {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val toca = when {
                l == "buenos" -> "días"
                h in 12..18 -> "tardes"
                h >= 19 || h < 5 -> "noches"
                else -> null
            }
            if (toca != null) res[toca] = (res[toca] ?: 0) + 60
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
            val generales = if (l.isEmpty()) inicioAhora() else GENERALES
            for (w in generales) {
                if (res.size >= max) break
                if (!res.containsKey(w) && w != l && !bloqueada(w)) res[w] = 0
            }
        }
        val enFormal = formal
        val lista = res.entries.filter { !lisuraAjena(it.key) && !(enFormal && (esLisura(it.key) || esJerga(it.key))) }
            .sortedByDescending { it.value }.take(max).map { FORMAS[sinTildes(it.key)] ?: it.key }
        ultimasPredichas = l to lista
        return lista.map { if (esNombre(it)) it.replaceFirstChar { c -> c.uppercase() } else it }
    }

    /**
     * La oración completa para la barra: si después de [previa2] [previa] viene [primera], ¿qué
     * frase sigue? Primero lo que tú sueles escribir (el cerebro piensa palabra por palabra y
     * solo sigue mientras está seguro); si no sabe nada tuyo, las frases hechas de siempre
     * ("muchas" "gracias" → "gracias por todo"). Devuelve la frase entera empezando por
     * [primera] (al menos dos palabras) o null.
     */
    @Synchronized
    fun frase(previa: String, previa2: String, primera: String): String? {
        if (primera.isEmpty() || primera.contains(' ')) return null
        val ix = indice ?: return null
        val enFormal = formal
        fun sirve(w: String) = !bloqueada(w) && !lisuraAjena(w) && conoce(ix, w) &&
            !(enFormal && (esLisura(w) || esJerga(w)))
        val siguen = ArrayList<String>(4)
        for (w in cerebro.continuar(previa, previa2, primera, app, 5)) {
            if (!sirve(w)) break
            siguen.add(FORMAS[sinTildes(w)] ?: if (esNombre(w)) w.replaceFirstChar { it.uppercase() } else w)
        }
        if (siguen.isEmpty()) {
            val clave = sinTildes(previa) + " " + sinTildes(primera)
            FRASES[clave]?.let { siguen.addAll(it) }
        }
        if (siguen.isEmpty()) return null
        // Que quepa en la barra: se quitan palabras del final (siempre queda al menos una)
        val sb = StringBuilder(primera)
        for (w in siguen) {
            if (sb.length + 1 + w.length > MAX_FRASE && sb.length > primera.length) break
            sb.append(' ').append(w)
        }
        if (sb.length == primera.length) return null
        val r = sb.toString()
        return if (frasesIgnoradas.isNotEmpty() && sinTildes(r) in frasesIgnoradas) null else r
    }

    /** Frases que quitaste de la barra (mantener presionada → Borrar) */
    private val frasesIgnoradas = HashSet<String>()

    /** Mantuviste presionada la frase y la borraste: no se vuelve a ofrecer */
    @Synchronized
    fun ignorarFrase(texto: String) {
        val w = texto.trim().split(' ').filter { it.isNotEmpty() }
        if (w.size < 2) return
        if (frasesIgnoradas.size > 300) frasesIgnoradas.clear()
        frasesIgnoradas.add(sinTildes(w.joinToString(" ")))
        cerebro.ignorarSiguiente(w[w.size - 2], w[w.size - 1])
        cambios = true
    }

    /** Cómo empiezan los mensajes, según la hora: de mañana "buenos", de tarde y noche "buenas" */
    private fun inicioAhora(): List<String> {
        if (formal) return INICIO_FORMAL
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val saludo = if (h in 5..11) "buenos" else "buenas"
        return listOf("hola", saludo) + INICIO.filter { it != "hola" && it != saludo }
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
        // Nunca se corrige HACIA una lisura que no usas ("pura" mal tocado no se vuelve otra cosa)
        if (!esLisura(p) && lisuraAjena(r)) return null
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
        // La segunda mejor (otra palabra de verdad, no la misma con o sin tilde): si queda
        // casi empatada con la primera, no se adivina a ciegas (ver [ultimaDuda])
        var mejorN = ""
        var mejorD = 99f
        var segundoTotal = Float.MAX_VALUE
        var segunda: String? = null
        ultimaDuda = false
        ultimaSegunda = null
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
                                // Le faltó solo la última letra ("curs" → "curso"): es el dedazo
                                // más común al escribir rápido, gana sobre cambiar una letra ("cura")
                                val falto = if (n.length == np.length + 1 && n.startsWith(np)) FALTO_ULTIMA else 0f
                                val total = d * 2.5f + Math.log(i + 30.0).toFloat() * 0.3f - ctx - falto
                                if (total < mejorTotal) {
                                    if (mejor != null && n != mejorN) { segundoTotal = mejorTotal; segunda = mejor }
                                    mejorTotal = total; mejor = c; mejorN = n; mejorD = d
                                } else if (total < segundoTotal && n != mejorN) { segundoTotal = total; segunda = c }
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
                val falto = if (nc.length == np.length + 1 && nc.startsWith(np)) FALTO_ULTIMA else 0f
                val total = d * 2.5f + 1.1f - minOf(n, 30) * 0.04f - contexto(previa, c, previa2) - recencia(c) * 0.004f - falto
                if (total < mejorTotal) {
                    if (mejor != null && nc != mejorN) { segundoTotal = mejorTotal; segunda = mejor }
                    mejorTotal = total; mejor = c; mejorN = nc; mejorD = d
                } else if (total < segundoTotal && nc != mejorN) { segundoTotal = total; segunda = c }
            }
        }
        // Empate: dos palabras explican igual de bien lo escrito y ni tu historia ni la frase
        // deciden cuál es ("cssa": ¿casa o cosa?). Un dedazo chiquito en el borde de la tecla
        // sí se corrige igual (ahí el dedo ya dijo cuál era).
        ultimaDuda = mejor != null && segunda != null && segundoTotal - mejorTotal < MARGEN_DUDA && mejorD >= 0.5f
        ultimaSegunda = segunda
        return mejor
    }

    /**
     * La última búsqueda de [masParecida] terminó casi empatada entre dos palabras distintas.
     * Entonces la autocorrección no cambia nada al tocar espacio (como Gboard): las dos quedan
     * en la barra de sugerencias y eliges tú con un toque. Así no cambia una palabra bien
     * pensada por otra que no querías.
     */
    private var ultimaDuda = false
    /** La segunda mejor de la última búsqueda (la otra palabra del empate) */
    private var ultimaSegunda: String? = null

    /** La última palabra que quedó en duda (sin tildes, en minúsculas) y sus dos opciones */
    private var dudaEscrita = ""
    private var dudaOpciones: List<String> = emptyList()

    /**
     * Si [p] es la palabra que quedó en empate, las dos opciones (la más probable primero),
     * para ponerlas juntas en la barra y elegir de un toque. Vacío si no hubo empate.
     */
    @Synchronized
    fun opcionesDeDuda(p: String): List<String> =
        if (dudaOpciones.isNotEmpty() && dudaEscrita == p.lowercase()) dudaOpciones else emptyList()

    /**
     * Elegiste en la barra una de las dos opciones de un empate: el cerebro aprende cuál
     * querías DESPUÉS DE ESA PALABRA ("mi cssa" → casa, "una cssa" → cosa). Con dos veces en
     * la misma frase, el empate ya se resuelve solo; con varias, en cualquier lugar.
     */
    @Synchronized
    fun notarEleccionDuda(previa: String, escrito: String, elegida: String) {
        if (pausado || dudaOpciones.isEmpty() || dudaEscrita != escrito.trim().lowercase()) return
        if (dudaOpciones.none { it.equals(elegida.trim(), ignoreCase = true) }) return
        cerebro.aprenderCambio(previa, escrito.trim(), elegida.trim(), 1.5f)
        dudaOpciones = emptyList()
        cambios = true
    }

    /**
     * Palabras pegadas por no tocar el espacio: "estoybien" → "estoy bien", "porfavor" → "por favor".
     * Las dos partes tienen que ser palabras comunes.
     */
    private fun separar(ix: Indice, p: String): String? {
        val l = p.lowercase()
        if (l.length < 5) return null
        val confPegada = cerebro.confianzaEspacio(null)
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
            // Lo que aprendió de tus espacios: si deshaces seguido estas separaciones, se cuida más
            s += ((confPegada - 1f) * 300f).toInt()
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
            // Lo que aprendió de tus espacios con esa letra (deshaces seguido las separaciones
            // con la n → casi no separa con la n; las dejas siempre con la b → se anima más)
            val conf = cerebro.confianzaEspacio(l[k])
            if (conf < 0.5f && !juntas) continue
            // Partes cortitas ("te", "os", "pe"): solo si son palabras de enlace o van juntas
            // (así "acompañante" nunca se vuelve "acompaña te")
            if (!juntas) {
                if (a.length <= 2 && a !in ENLACE) continue
                if (b.length <= 2 && (b !in ENLACE || a.length > 2)) continue
            }
            var s = minOf(frec(ra), frec(rb)) + (frec(ra) + frec(rb)) / 4
            if (juntas) s += 250
            s += ((conf - 1f) * 300f).toInt()
            if (s > mejorPuntaje) { mejorPuntaje = s; mejor = "$pa $pb" }
        }
        return if (mejorPuntaje >= 520) mejor else null
    }

    // ---------- Separar mientras escribes ----------

    /**
     * Separa palabras pegadas MIENTRAS escribes, sin esperar el espacio:
     * "yanpode" → "ya pode" (la n cayó en vez del espacio), "quebhag" → "que hag",
     * "yapodem" → "ya podem" (no tocaste el espacio). Sigues escribiendo y queda "ya podemos".
     *
     * Es prudente a propósito (separar mal una palabra molesta mucho más que no separar):
     *  - Mientras lo escrito todavía pueda ser el comienzo de una palabra (del diccionario,
     *    tuya, de un contacto, en inglés o una marca), no se toca nada.
     *  - Si parece una palabra a medias con un error de tipeo ("tenfo" → tengo), tampoco:
     *    eso lo arregla la autocorrección al dar espacio.
     *  - Palabra + terminación ("normalazo", "infórmenos", "dímelo") nunca se corta.
     *  - La primera parte tiene que ser una palabra común o tuya, y lo que sigue tiene que
     *    empezar una palabra común (al menos 3 letras, 4 si la primera es cortita).
     *  - Lo aprendido manda: si el cerebro sabe que después de esa palabra sueles escribir
     *    una que empieza así, se anima más (y hasta pasa por encima de la duda del tipeo).
     *  - Si deshaces una separación (borrar justo después), no la vuelve a hacer.
     *
     * Devuelve lo que reemplaza a la palabra a medias ("ya pode") o null si no hay que separar.
     */
    @Synchronized
    fun separarEnVivo(
        p: String, previa: String, previa2: String, t: Int, toques: Array<FloatArray>? = null,
    ): String? {
        miTurno = t
        toq = toques
        sinCorte = false
        return try { separarSinFiltro(p, previa, previa2) } catch (e: Throwable) {
            Registro.anotar("separar", e); null
        } finally { toq = null }
    }

    private fun separarSinFiltro(p: String, previa: String, previa2: String): String? {
        val n = p.length
        if (n < 5 || n > 28 || cortado()) return null
        for (ch in p) if (!ch.isLetter()) return null
        // Mayúsculas en el medio ("WhatsApp", "PUCP"): es un nombre o una sigla, no se toca
        for (i in 1 until n) if (p[i].isUpperCase()) return null
        val ix = indice ?: return null
        val l = p.lowercase()
        val np = normal(p)
        if (np.length != n) return null
        if (expresiva(l) || l in INGLES || esLisura(l)) return null
        // Mientras pueda ser el comienzo de una palabra (aunque sea rara), no se toca
        if (esComienzo(ix, np)) return null
        if (separacionRechazada(np)) return null
        // Palabra + terminación: "normalazo", "infórmenos", "dímelo", "rapidísimo"
        if (conSufijo(ix, np)) return null
        if (cortado()) return null

        var mejor: String? = null
        var mejorS = 0f
        // Lo que aprendió de tus espacios: palabras pegadas sin letra en medio
        val confPegada = cerebro.confianzaEspacio(null)
        for (k in 2..n - 3) {
            for (conSep in 0..1) {
                // La letra que cayó en lugar del espacio: se mira la tecla de verdad (la ñ
                // tiene su propia tecla y nunca es un espacio, aunque sin tilde sea "n")
                if (conSep == 1 && l[k] !in JUNTO_AL_ESPACIO) continue
                // Con esa letra deshaces seguido las separaciones: mientras escribes ya no se
                // separa con ella (al dar espacio todavía puede, si esas palabras van juntas)
                val conf = if (conSep == 1) cerebro.confianzaEspacio(l[k]) else confPegada
                if (conf < 0.55f) continue
                val desdeB = k + conSep
                val largoB = n - desdeB
                // Lo que sigue tiene que tener sustancia: 3 letras (4 si la primera es cortita)
                if (largoB < 3 || (k <= 2 && largoB < 4)) continue
                val na = np.substring(0, k)
                if (k <= 2 && na !in ENLACE) continue
                val a = l.substring(0, k)

                // Primera parte: palabra común del diccionario o una palabra tuya
                var ra = rango(ix, a)
                if (ra < 0) ra = mejorPorNormal(ix, na)
                val fa: Int
                val formaA: String
                // Con una letra en lugar del espacio (sobre todo la n, que está en casi todas
                // las palabras) la primera parte tiene que ser una palabra bien común
                val tope = if (conSep == 1) COMUN_SEPARADOR else COMUN
                if (ra in 0..tope) {
                    fa = frec(ra)
                    formaA = if (ix.palabras[ra].equals(a, ignoreCase = true)) p.substring(0, k)
                    else igualarMayus(ix.palabras[ra], p.substring(0, k))
                } else {
                    // Una palabra tuya solo sirve como primera parte si la usas de verdad
                    // (y nunca con una letra en lugar del espacio)
                    val c = aprendidas[a] ?: 0
                    if (conSep == 1 || c < 4 || !propia(a, c)) continue
                    fa = 450 + minOf(c, 30) * 5
                    formaA = p.substring(0, k)
                }
                if (bloqueada(formaA) || lisuraAjena(formaA)) continue
                // Letra de más o rebote: sin esa letra todo junto sigue siendo el comienzo de una
                // palabra ("connsecu", "conmsecu" → "consecu…"). Es un dedazo dentro de UNA
                // palabra, no un espacio fallado (al dar espacio la autocorrección decide)
                if (conSep == 1 && esComienzo(ix, np.substring(0, k) + np.substring(k + 1))) continue
                // "ve" + n + "tamas": si la primera parte con esa letra sigue siendo el comienzo
                // de una palabra ("ven…" → "ventanas"), lo más probable es un error de tipeo
                // dentro de una sola palabra, no un espacio fallado
                if (conSep == 1 && esComienzoComun(ix, np.substring(0, k + 1))) continue

                // Segunda parte: tiene que empezar una palabra común (o una tuya)
                val nb = np.substring(desdeB)
                val rb = mejorComienzo(ix, nb)
                val fb = when {
                    rb >= 0 -> frec(rb)
                    aprendidasDesde(nb).any { k2 -> propia(palabraDe(k2), aprendidas[palabraDe(k2)] ?: 0) } -> 450
                    else -> continue
                }

                // Lo aprendido: ¿después de esa palabra sueles escribir una que empieza así?
                // Solo suma puntos (antes también saltaba la revisión del error de tipeo, y con
                // mucho aprendido terminaba separando casi cualquier palabra con n)
                val la = formaA.lowercase()
                var ctx = 0f
                for ((w, pr) in cerebro.candidatos(la, previa, app, nb, 1)) {
                    if (conoce(ix, w)) ctx = maxOf(ctx, minOf(1f, pr * 1.5f))
                }
                if (paresFijos(la)?.any { normal(it).startsWith(nb) } == true) ctx = maxOf(ctx, 0.35f)
                if (ctx < 0.2f && pares[la]?.keys?.any { normalDe(it).startsWith(nb) } == true) ctx = 0.2f

                // Las palabras largas casi nunca son un accidente: suman
                val faL = fa + minOf(250, maxOf(0, 60 * (k - 3)))
                var s = minOf(faL, fb) + (faL + fb) / 4f
                if (conSep == 0) s -= 40f
                s += ctx * 250f
                // "con", "en", "sin", "para", "sobre"... son comienzos de miles de palabras
                // (consecuencia, entonces, sintonía, paracaídas, sobretodo): con ellas solo se separa
                // si lo aprendido o el diccionario dicen que esas dos palabras suelen ir juntas
                if (na in PREFIJOS_PALABRA && ctx < 0.2f) s -= 170f
                if (previa.isNotEmpty()) s += contexto(previa, formaA, previa2) * 120f
                // Con una letra en lugar del espacio se pide más seguridad
                if (conSep == 1) s -= 60f
                // Lo aprendido de tus espacios mueve la vara para un lado o para el otro
                s += (conf - 1f) * 300f
                if (s > mejorS) {
                    mejorS = s
                    mejor = formaA + " " + p.substring(desdeB)
                }
            }
            if (cortado()) return null
        }
        val res = mejor ?: return null
        if (mejorS < 600f) return null
        // ¿Era una palabra a medias mal tocada ("entones", "conntrol", "encientra")?
        // Ante la duda, nunca se separa: separar mal molesta mucho más que no separar
        if (pareceErrorDeTipeo(ix, np)) return null
        return if (cortado()) null else res
    }

    /**
     * Tres o más palabras pegadas (sin espacio o con una b, n, v, m o c en su lugar):
     * "quebhagonhoy" → "que hago hoy", "estoybienngracias" → "estoy bien gracias".
     * Todas las partes tienen que ser palabras muy comunes (o tuyas) y gana la forma con
     * menos palabras; si empatan, la de palabras más comunes.
     */
    private fun segmentar(ix: Indice, p: String): String? {
        val np = normal(p)
        val n = np.length
        if (n < 7 || n > 30 || n != p.length) return null
        val l = p.lowercase()
        if (l.length != n) return null
        // Desde cada posición hasta el final: cuántas palabras como mínimo (-1 = imposible),
        // la menos común de ellas y dónde empieza la siguiente
        val partes = IntArray(n + 1) { -1 }
        val menor = IntArray(n + 1) { Int.MAX_VALUE }
        val sigue = IntArray(n + 1)
        val formas = arrayOfNulls<String>(n + 1)
        partes[n] = 0
        for (s in n - 1 downTo 0) {
            if (cortado()) return null
            for (e in n downTo s + 1) {
                val w = np.substring(s, e)
                if (w.length <= 2 && w !in ENLACE) continue
                val (forma, fr) = palabraParaSegmentar(ix, w) ?: continue
                for (opcion in 0..1) {
                    val nx = if (opcion == 0) e else e + 1
                    // Con una letra en lugar del espacio, tiene que haber algo después
                    // (la tecla de verdad: la ñ nunca hace de espacio)
                    if (opcion == 1 && (e >= n - 1 || l[e] !in JUNTO_AL_ESPACIO)) continue
                    if (nx > n || partes[nx] < 0) continue
                    val cuantas = partes[nx] + 1
                    val m = minOf(fr, menor[nx])
                    if (partes[s] < 0 || cuantas < partes[s] || (cuantas == partes[s] && m > menor[s])) {
                        partes[s] = cuantas
                        menor[s] = m
                        sigue[s] = nx
                        formas[s] = forma
                    }
                }
            }
        }
        // Dos palabras ya las resuelven separar() y separarEspacio()
        if (partes[0] < 3) return null
        val sb = StringBuilder(n + 4)
        var i = 0
        while (i < n) {
            val f = formas[i] ?: return null
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(f)
            if (sigue[i] <= i) return null
            i = sigue[i]
        }
        val r = sb.toString()
        return if (lisuraAjena(r)) null else r
    }

    /** Una parte de palabras pegadas: palabra muy común (sin jerga) o tuya, con su frecuencia */
    private fun palabraParaSegmentar(ix: Indice, w: String): Pair<String, Int>? {
        var r = rango(ix, w)
        if (r < 0) r = mejorPorNormal(ix, w)
        if (r in 0..MUY_COMUN) {
            val c = ix.palabras[r]
            if (bloqueada(c) || (esJerga(c) && !propia(c.lowercase(), aprendidas[c.lowercase()] ?: 0))) return null
            return c to frec(r)
        }
        val n = aprendidas[w] ?: 0
        return if (propia(w, n) && !bloqueada(w)) w to 450 else null
    }

    /** ¿Lo escrito todavía puede ser el comienzo de alguna palabra? */
    private fun esComienzo(ix: Indice, np: String): Boolean {
        val k = desde(ix, np)
        if (k < ix.orden.size && ix.normal[ix.orden[k]].startsWith(np)) return true
        if (aprendidasDesde(np).isNotEmpty()) return true
        val agenda = contactos
        if (agenda.isNotEmpty()) for (v in agenda.values) if (v.startsWith(np)) return true
        for (w in INGLES) if (w.startsWith(np)) return true
        for (f in FORMAS.keys) if (f.startsWith(np)) return true
        for (e in ERRORES_COMUNES.keys) if (e.startsWith(np)) return true
        // Lo que vas escribiendo es el comienzo de un dedazo conocido de una sola palabra
        if (comienzoDeTipeo(np)) return true
        return false
    }

    /** ¿[np] es el comienzo de una palabra muy común? ("ven" → venir, ventana) */
    private fun esComienzoComun(ix: Indice, np: String): Boolean {
        val orden = ix.ordenTop
        var k = desde(ix, np, orden)
        var vistas = 0
        while (k < orden.size && vistas < 3000) {
            val i = orden[k++]
            if (!ix.normal[i].startsWith(np)) return false
            if (i <= MUY_COMUN) return true
            vistas++
        }
        return false
    }

    /** La palabra más común (posición) que empieza con [nb], entre las más usadas, o -1 */
    private fun mejorComienzo(ix: Indice, nb: String): Int {
        val orden = ix.ordenTop
        var k = desde(ix, nb, orden)
        var mejor = -1
        var vistas = 0
        while (k < orden.size && vistas < 400) {
            val i = orden[k++]
            if (!ix.normal[i].startsWith(nb)) break
            if (mejor < 0 || i < mejor) mejor = i
            vistas++
        }
        return mejor
    }

    /** "normalazo", "infórmenos", "dímelo": una palabra con su terminación (no son dos palabras) */
    private fun conSufijo(ix: Indice, np: String): Boolean {
        for (k in 2 until np.length) {
            if (!esSufijo(np.substring(k))) continue
            if (mejorPorNormal(ix, np.substring(0, k)) >= 0) return true
        }
        return false
    }

    private fun esSufijo(r: String): Boolean {
        for (x in SUFIJOS) {
            if (x.startsWith(r)) return true
            if (r.length > x.length && r.startsWith(x)) {
                val r2 = r.substring(x.length)
                for (y in SUFIJOS) if (y.startsWith(r2)) return true
            }
        }
        return false
    }

    /**
     * ¿Parece una palabra a medias con un error de tipeo? ("tenfo" → tengo, "estanmo" → estamos)
     * Se compara con el comienzo de las palabras conocidas usando la distancia de teclado.
     */
    private fun pareceErrorDeTipeo(ix: Indice, np: String): Boolean {
        val n = np.length
        var vistas = 0
        // Se compara SIN la posición de los dedos: un toque en el centro de la tecla equivocada
        // "inflaba" la distancia, la palabra a medias ya no parecía un dedazo y se separaba mal
        // ("consecuencia" terminaba como "con secuencia")
        val guardado = toq
        toq = null
        try {
            for (ch in iniciales(np)) {
                // Si llegó otra letra, ante la duda se dice que sí (y no se separa)
                if (cortado()) return true
                recorrer(ix, ch, n - 1, MAX_LARGO) { i ->
                    if ((++vistas and 255) == 0 && cortado()) return true
                    if (i <= RARA) {
                        val w = ix.normal[i]
                        var d = distancia(np, w, n, 1.2f)
                        if (d > 0.8f) d = minOf(d, distancia(np, w, n - 1, 1.2f), distancia(np, w, n + 1, 1.2f))
                        if (d <= 0.8f || (d <= 1.1f && i <= COMUN)) return true
                    }
                }
            }
            return false
        } finally {
            toq = guardado
        }
    }

    /**
     * Al cerrar la palabra que quedó después de separar mientras escribías: ¿la separación fue
     * un error? Sí, si [segunda] no es una palabra y todo junto ([unida]) sí lo es, o se parece
     * a una palabra al menos tanto como [segunda] ("en tones" → "entones" ≈ "entonces").
     */
    @Synchronized
    fun reunirSeparacion(segunda: String, unida: String, primera: String = ""): Boolean {
        val ix = indice ?: return false
        if (segunda.length < 2 || unida.length > MAX_LARGO) return false
        toq = null
        sinCorte = true
        return try {
            when {
                // Todo junto es una palabra ("con secuencia" → "consecuencia"): manda la palabra,
                // salvo que esas dos palabras vayan juntas de verdad (lo tuyo o el diccionario)
                conoce(ix, unida) -> !(conoce(ix, segunda) && vanJuntas(primera, segunda))
                conoce(ix, segunda) -> {
                    // La segunda es palabra, pero todo junto es casi seguro UNA palabra común con
                    // un dedazo ("conn secuencia" → "consecuencia"): se junta y la corrige
                    if (primera.isNotEmpty() && vanJuntas(primera, segunda)) false
                    else {
                        val nu = normal(unida)
                        val cu = masParecida(ix, nu, minOf(0.75f, limitePara(nu.length)), "", "", maxRango = COMUN)
                        cu != null && distancia(nu, normal(cu), normal(cu).length, 3f) <= 0.75f
                    }
                }
                else -> {
                    val nu = normal(unida)
                    val ns = normal(segunda)
                    val cu = masParecida(ix, nu, limitePara(nu.length), "", "")
                    if (cu == null) false
                    else {
                        val du = distancia(nu, normal(cu), normal(cu).length, 3f)
                        val cs = masParecida(ix, ns, limitePara(ns.length), "", "")
                        val ds = if (cs == null) 99f else distancia(ns, normal(cs), normal(cs).length, 3f)
                        du <= ds
                    }
                }
            }
        } catch (e: Throwable) {
            Registro.anotar("reunir", e); false
        } finally { sinCorte = false }
    }

    /** ¿Sueles escribir [b] después de [a]? (lo tuyo, el cerebro o el diccionario) */
    private fun vanJuntas(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val la = a.lowercase()
        val lb = b.lowercase()
        if (paresFijos(la)?.contains(lb) == true) return true
        if ((pares[la]?.get(lb) ?: 0) >= 2) return true
        return cerebro.prob(la, "", lb, app) > 0.15f
    }

    // ---------- Palabra partida por un espacio ----------

    /**
     * Se te fue un espacio en medio de una palabra: "consecu encia" → "consecuencia",
     * "mi ercoles" → "miércoles". Solo si la segunda parte NO es una palabra (ni tuya), todo
     * junto es una palabra común y la segunda parte no es simplemente otra palabra con un dedazo.
     * [original]: cómo escribiste la primera antes de que la autocorrección la cambiara.
     * Devuelve la palabra unida o null.
     */
    @Synchronized
    fun unirPartida(previa: String, palabra: String, original: String? = null): String? {
        val ix = indice ?: return null
        if (palabra.length < 2 || palabra.length > 20 || !palabra.all { it.isLetter() }) return null
        if (palabra[0].isUpperCase()) return null
        val lp = palabra.lowercase()
        if (lp in INGLES || expresiva(lp) || conoce(ix, palabra)) return null
        // Si la segunda parte es un dedazo conocido de otra palabra, eso lo arregla la autocorrección
        if (ERRORES_COMUNES.containsKey(lp) || tipeos.containsKey(normal(lp)) || FORMAS.containsKey(normal(lp))) return null
        toq = null
        sinCorte = true
        try {
            val opciones = if (original != null && original != previa) listOf(original, previa) else listOf(previa)
            for (a in opciones) {
                if (a.length < 2 || a.length > 20 || !a.all { it.isLetter() }) continue
                val unida = a + lp
                if (unida.length < 6 || unida.length > MAX_LARGO) continue
                if (rechazos.isNotEmpty() && (a.lowercase() + " " + lp + ">+") in rechazos) continue
                var r = rango(ix, unida)
                if (r < 0) r = mejorPorNormal(ix, normal(unida))
                if (r < 0 || r > COMUN) continue
                val forma = ix.palabras[r]
                if (bloqueada(forma) || lisuraAjena(forma)) continue
                // ¿La segunda parte es otra palabra común con un dedazo? Entonces no se junta
                val ns = normal(lp)
                if (ns.length >= 3) {
                    val alt = masParecida(ix, ns, minOf(0.65f, limitePara(ns.length)), a, "", maxRango = 6000)
                    if (alt != null && distancia(ns, normal(alt), normal(alt).length, 3f) <= 0.45f) continue
                }
                return igualarMayus(forma, a)
            }
            return null
        } catch (e: Throwable) {
            Registro.anotar("unir", e)
            return null
        } finally {
            sinCorte = false
        }
    }

    /** Deshiciste una unión ("consecu encia" → "consecuencia"): esa no se vuelve a hacer */
    @Synchronized
    fun rechazarUnion(original: String) {
        val o = original.trim().lowercase()
        if (pausado || !o.contains(' ') || o.length > 60) return
        rechazos.add("$o>+")
        cambios = true
    }

    /** Separaciones que deshiciste: lo que empieza así ya no se separa */
    private fun separacionRechazada(np: String): Boolean {
        if (rechazos.isEmpty()) return false
        for (k in 3..np.length) if ((np.substring(0, k) + ">~") in rechazos) return true
        return false
    }

    /** Deshiciste la separación de [original] (borrar justo después): no se vuelve a hacer */
    @Synchronized
    fun rechazarSeparacion(original: String, separado: String? = null) {
        val n = normal(original)
        if (pausado || n.length < 3) return
        rechazos.add("$n>~")
        // El cerebro aprende que con esa letra (o sin letra) no tenía que separar
        if (separado != null) cerebro.aprenderEspacio(original, separado, false)
        cambios = true
    }

    private fun corregirSinFiltro(p: String, previa: String, previa2: String): String? {
        if (p.length < 2) return null
        val l = p.lowercase()
        // Siglas, instituciones, lugares y marcas con su forma correcta:
        // "sunat" → "SUNAT", "essalud" → "EsSalud", "whatsapp" → "WhatsApp", "cusco" → "Cusco"
        FORMAS[sinTildes(p)]?.let { f -> return if (f == p || bloqueada(f)) null else f }
        // Palabras en inglés que se usan en el chat ("ok", "link", "story"): no se tocan
        if (l in INGLES) return null
        // Otro idioma que escribes (inglés, portugués): si la palabra existe allá y la frase va en
        // ese idioma, o es muy común allá, se respeta tal cual. Si la frase va en ese idioma y la
        // palabra no existe, se corrige en ese idioma ("teh" → "the") o no se toca: nunca se le
        // impone una palabra española rara a una frase en inglés.
        if (idiomas.activo) {
            val ixE = indice
            val rEs = if (ixE != null) rango(ixE, p) else -1
            if (rEs < 0 && !propia(l, aprendidas[l] ?: 0) && !esContacto(l)) {
                if (idiomas.protege(l)) return null
                idiomas.corregir(l)?.let { c ->
                    return if (c == Idiomas.NO_TOCAR) null else igualarMayusOtro(c, p).takeIf { it != p }
                }
            }
        }
        // Nombres de tus contactos: nunca se corrigen, solo se les pone mayúscula
        if (esContacto(l)) return if (p[0].isLowerCase()) p.replaceFirstChar { it.uppercase() } else null
        // Errores comunes al escribir rápido o de oído: "aser" → "hacer", "nesecito" → "necesito"
        if (!propia(l, aprendidas[l] ?: 0)) {
            (ERRORES_COMUNES[l] ?: tipeoDeFabrica(l))?.let { if (!bloqueada(it)) return igualarMayus(it, p).takeIf { c -> c != p } }
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
            // Error difícil de ver: la palabra existe, pero después de [previa] tú siempre la
            // cambias por otra ("no tubo" → "no tuvo"). El cerebro lo aprendió de tus cambios
            cerebro.cambioEnContexto(previa, l)?.let { q ->
                if (!bloqueada(q) && !lisuraAjena(q)) return igualarMayus(q, p).takeIf { it != p }
            }
            // Otra palabra que suena igual encaja mucho mejor con lo que TÚ sueles escribir
            // ("no tubo" → "no tuvo"): si el cerebro está muy seguro, se corrige sola
            if (rp >= 0 && previa.isNotEmpty()) {
                alternativa(ix, l, previa, previa2)?.let { (v, fuerte) ->
                    if (fuerte) return igualarMayus(v, p).takeIf { it != p }
                }
            }
            // Una palabra rarísima que nunca usaste, tocada justo en el borde de una tecla,
            // cuando a un toque hay una palabra muy común: casi seguro fue un dedo desviado
            // ("cpmo" está en el diccionario como rara → "como"). Solo con la posición del dedo.
            if (rp > RARA && usada == 0 && np.length >= 3 && toq != null) {
                val alt = masParecida(ix, np, 0.28f, previa, previa2, maxRango = 4000)
                if (alt != null && !alt.equals(p, ignoreCase = true)) return igualarMayus(alt, p)
            }
            return null
        }

        // Siglas que escribiste en mayúsculas a propósito ("UPC", "SJL", "PUCP"): no se tocan
        if (p.length in 2..5 && p.all { it.isUpperCase() }) return null

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
        //    Solo si esas dos palabras suelen ir juntas (lo tuyo o lo del diccionario).
        val sepSegura = if (mejor == null && np.length >= 4) separarEspacio(ix, p, soloSeguro = true) else null
        // 3) Errores de tipeo con la distribución del teclado
        // (una palabra rara escrita una sola vez puede ser un error; cuando la repites ya es tuya)
        var duda = false
        if (mejor == null && np.length >= 3) {
            // El límite se calibra solo según qué tan seguido deshaces la autocorrección
            mejor = masParecida(ix, np, limitePara(np.length) * cerebro.audacia() * factorRitmo(), previa, previa2)
            // Empate entre dos palabras: no se adivina (las dos quedan en la barra). Tampoco se
            // separa en dos palabras: lo escrito es claramente UNA palabra mal tocada
            if (mejor != null && ultimaDuda) {
                val opciones = listOfNotNull(mejor, ultimaSegunda).filter { !bloqueada(it) && !lisuraAjena(it) }
                // ¿Ya elegiste antes una de las dos después de esta misma palabra? Eso decide
                cerebro.cambioEnContexto(previa, l)?.let { q ->
                    val gana = opciones.firstOrNull { normal(it) == normal(q) }
                    if (gana != null) return igualarMayus(gana, p).takeIf { it != p }
                }
                dudaEscrita = l
                dudaOpciones = opciones.map { igualarMayus(it, p) }
                mejor = null
                duda = true
            }
        }
        if (duda) return null
        // La separación gana solo si lo escrito no se parece mucho a UNA palabra: "entones"
        // es "entonces" (no "en tones") y "ventamas" es "ventanas" (no "ve tamas")
        if (sepSegura != null) {
            val m = mejor
            if (m == null) return conMayus(sepSegura, p)
            if (prefiereEspacio(np, m, sepSegura, p, previa, previa2)) return conMayus(sepSegura, p)
        }
        // 4) Palabras pegadas: "estoybien" → "estoy bien", "yonsoy" → "yo soy"
        if (mejor == null && np.length >= 4) {
            (if (np.length >= 5) separar(ix, p) else null)?.let { s -> return conMayus(s, p) }
            separarEspacio(ix, p, soloSeguro = false)?.let { s -> return conMayus(s, p) }
        }
        // 5) Tres o más palabras pegadas: "quebhagonhoy" → "que hago hoy"
        if (mejor == null && np.length >= 7) {
            segmentar(ix, p)?.let { s -> return conMayus(s, p) }
        }
        val elegida = mejor ?: return null
        val resultado = igualarMayus(elegida, p)
        return if (resultado == p) null else resultado
    }

    /**
     * Escribiendo muy rápido con dos pulgares salen más dedazos por palabra: la autocorrección
     * se anima un poquito más (hasta 12 %). Se mira el ritmo de las letras de esta palabra.
     */
    private fun factorRitmo(): Float {
        val t = toq ?: return 1f
        var suma = 0f
        var cuantas = 0
        for (k in 1 until t.size) {
            val r = t[k].getOrNull(3) ?: continue
            if (r <= 0f || r > 1500f) continue
            suma += r
            cuantas++
        }
        if (cuantas < 2) return 1f
        val prom = suma / cuantas
        return when {
            prom < 110f -> 1.12f
            prom < 150f -> 1.07f
            else -> 1f
        }
    }

    /**
     * ¿Espacio o UNA palabra? Lo escrito ([np]) puede ser dos palabras con una letra en lugar
     * del espacio ([separada]: "que hago") o una sola palabra con un dedazo ([palabra]:
     * "entonces"). Antes la vara era fija; ahora se mueve con:
     *  - Lo que aprendió de tus espacios con esa letra (si las deshaces seguido, gana la palabra).
     *  - Lo que sueles escribir: si después de [previa] casi siempre va [palabra], gana la
     *    palabra; si las dos partes van juntas en lo tuyo, gana el espacio.
     *  - Qué tanto se parece lo escrito a la palabra (un dedazo chiquito casi siempre es eso).
     */
    private fun prefiereEspacio(np: String, palabra: String, separada: String, p: String, previa: String, previa2: String): Boolean {
        val nm = normal(palabra)
        val d = distancia(np, nm, nm.length, 2.5f)
        val conf = cerebro.confianzaSeparar(p, separada)
        var vara = 0.9f / conf
        if (contexto(previa, palabra, previa2) >= 0.5f) vara += 0.3f
        val sp = separada.indexOf(' ')
        if (sp > 0) {
            val a = separada.substring(0, sp)
            val b = separada.substring(sp + 1)
            if (cerebro.prob(a, previa, b, app) > 0.2f) vara -= 0.2f
            if (previa.isNotEmpty() && contexto(previa, a, previa2) >= 0.5f) vara -= 0.1f
        }
        return d > vara.coerceIn(0.5f, 1.6f)
    }

    // ---------- Espacio cruzado (escribir con dos pulgares) ----------

    /**
     * Con dos pulgares, el espacio y una letra se cruzan muy seguido:
     *  - "hol aamigo" → "hola amigo" (la última letra de la primera salió después del espacio)
     *  - "holaa migo" → "hola amigo" (la primera letra de la segunda salió antes del espacio)
     * Solo si lo escrito NO son palabras y al mover esa letra las dos sí lo son (palabras
     * comunes o tuyas). [original]: cómo escribiste la primera antes de que la autocorrección
     * la cambiara. Si deshaces el arreglo, no se vuelve a hacer. Devuelve (primera, segunda) o null.
     */
    @Synchronized
    fun cruzarEspacio(previa: String, palabra: String, original: String? = null): Pair<String, String>? {
        val ix = indice ?: return null
        if (palabra.length < 2 || palabra.length > 20 || !palabra.all { it.isLetter() }) return null
        if (palabra.any { it.isUpperCase() }) return null
        val lp = palabra.lowercase()
        if (lp in INGLES || expresiva(lp) || conoce(ix, palabra)) return null
        if (cerebro.confianzaCruce() < 0.55f) return null
        toq = null
        sinCorte = true
        try {
            val opciones = if (original != null && original != previa) listOf(original, previa) else listOf(previa)
            for (a in opciones) {
                if (a.length < 2 || a.length > 20 || !a.all { it.isLetter() }) continue
                val la = a.lowercase()
                if (conoce(ix, a) || expresiva(la) || la in INGLES) continue
                if (rechazos.isNotEmpty() &&
                    (("$la $lp>x") in rechazos || (previa.lowercase() + " " + lp + ">x") in rechazos)
                ) continue
                // 1) La letra se fue después del espacio: "hol" + "aamigo"
                if (lp.length >= 3) {
                    val w1 = formaComun(ix, la + lp[0])
                    val w2 = if (w1 != null) formaComun(ix, lp.substring(1)) else null
                    if (w1 != null && w2 != null) return igualarMayus(w1, a) to w2
                }
                // 2) La letra salió antes del espacio: "holaa" + "migo"
                if (la.length >= 3) {
                    val w1 = formaComun(ix, la.dropLast(1))
                    val w2 = if (w1 != null) formaComun(ix, la.last().toString() + lp) else null
                    if (w1 != null && w2 != null) return igualarMayus(w1, a) to w2
                }
            }
            return null
        } catch (e: Throwable) {
            Registro.anotar("cruzar", e)
            return null
        } finally {
            sinCorte = false
        }
    }

    /** La forma del diccionario de [l] si es una palabra común ("tambien" → "también") o tuya; si no, null */
    private fun formaComun(ix: Indice, l: String): String? {
        if (l.length < 2) return if (l in ENLACE) l else null
        var r = rango(ix, l)
        if (r < 0) r = mejorPorNormal(ix, normal(l))
        if (r in 0..COMUN) {
            val f = ix.palabras[r]
            return if (bloqueada(f) || lisuraAjena(f)) null else f
        }
        val n = aprendidas[l] ?: 0
        return if (propia(l, n) && !bloqueada(l)) l else null
    }

    /** El espacio cruzado se arregló ([bien]) o lo deshiciste ([original] era lo que querías) */
    @Synchronized
    fun notarCruce(original: String, bien: Boolean) {
        if (pausado) return
        cerebro.aprenderCruce(bien)
        if (!bien) {
            val o = original.trim().lowercase()
            if (o.contains(' ') && o.length <= 60) rechazos.add("$o>x")
        }
        cambios = true
    }

    /** Separaste mientras escribías: [original] ("yanpode") quedó como [separado] ("ya pode") */
    @Synchronized
    fun notarSeparacion(original: String, separado: String) {
        if (!pausado) cerebro.aprenderEspacio(original, separado, true)
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
        if (idiomas.activo) idiomas.notar(palabra, indice?.let { rango(it, palabra) } ?: -1)
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
        refrescarPerfil()
        cambios = true
    }

    // ---------- Palabras que suenan igual (errores difíciles de ver) ----------

    /**
     * Las formas que suenan igual que [n] (sin tildes): b/v, s/c/z, y/ll, g/j y la h muda.
     * "tubo" → "tuvo", "baya" → "vaya", "aya" → "haya", "caso" → "cazo".
     */
    private fun suenanIgual(n: String): List<String> {
        val r = ArrayList<String>()
        fun poner(v: String) { if (v != n && v.length >= 2 && v !in r) r.add(v) }
        for (i in n.indices) {
            val c = n[i]
            val sig = if (i + 1 < n.length) n[i + 1] else ' '
            val suave = sig == 'e' || sig == 'i'
            when (c) {
                'b' -> poner(n.substring(0, i) + 'v' + n.substring(i + 1))
                'v' -> poner(n.substring(0, i) + 'b' + n.substring(i + 1))
                's' -> {
                    poner(n.substring(0, i) + 'z' + n.substring(i + 1))
                    if (suave) poner(n.substring(0, i) + 'c' + n.substring(i + 1))
                }
                'z' -> {
                    poner(n.substring(0, i) + 's' + n.substring(i + 1))
                    if (suave) poner(n.substring(0, i) + 'c' + n.substring(i + 1))
                }
                'c' -> if (suave) {
                    poner(n.substring(0, i) + 's' + n.substring(i + 1))
                    poner(n.substring(0, i) + 'z' + n.substring(i + 1))
                }
                'y' -> if (i + 1 < n.length) poner(n.substring(0, i) + "ll" + n.substring(i + 1))
                'l' -> if (sig == 'l') poner(n.substring(0, i) + 'y' + n.substring(i + 2))
                'g' -> if (suave) poner(n.substring(0, i) + 'j' + n.substring(i + 1))
                'j' -> if (suave) poner(n.substring(0, i) + 'g' + n.substring(i + 1))
                'h' -> if (i == 0) poner(n.substring(1))
            }
        }
        if (n[0] != 'h') poner("h$n")
        return r
    }

    /**
     * ¿[l] (una palabra que existe) es en realidad otra que suena igual? Solo si después de
     * [previa] la otra encaja claramente y [l] casi nunca va ahí. Devuelve (la otra, segura):
     * segura = lo dicen TUS frases (el cerebro), no solo el diccionario. null si no hay duda.
     */
    private fun alternativa(ix: Indice, l: String, previa: String, previa2: String): Pair<String, Boolean>? {
        val n = normal(l)
        if (n.length < 2 || n.length > 20 || previa.isEmpty()) return null
        if (l in INGLES || esContacto(l)) return null
        val rp = rango(ix, l)
        val cp = contexto(previa, l, previa2)
        if (cp >= 0.05f) return null
        var mejor: String? = null
        var mejorC = 0f
        for (v in suenanIgual(n)) {
            val r = mejorPorNormal(ix, v)
            if (r < 0 || r > COMUN) continue
            // La otra no puede ser muchísimo más rara que lo escrito
            if (rp >= 0 && r > rp * 4 + 2000) continue
            val w = ix.palabras[r]
            if (bloqueada(w) || lisuraAjena(w)) continue
            val cv = contexto(previa, w, previa2)
            if (cv > mejorC) { mejorC = cv; mejor = w }
        }
        val w = mejor ?: return null
        if (mejorC < 0.35f) return null
        val tuya = cerebro.prob(previa, previa2, w, app)
        val lo = cerebro.prob(previa, previa2, l, app)
        val segura = tuya >= 0.3f && lo < 0.02f && mejorC >= 0.5f
        return w to segura
    }

    /**
     * Para la barra: la palabra que suena igual y encaja mejor después de [previa]
     * ("no tubo" → "tuvo"). Se ofrece al centro para elegirla de un toque.
     */
    @Synchronized
    fun alternativaEnContexto(p: String, previa: String, previa2: String): String? {
        val ix = indice ?: return null
        if (p.length < 2 || !valida(p) || rango(ix, p) < 0) return null
        return try {
            alternativa(ix, p.lowercase(), previa, previa2)?.first?.let { igualarMayus(it, p) }
        } catch (e: Throwable) { Registro.anotar("alternativa", e); null }
    }

    /** Borraste la letra [borrada] y escribiste [nueva] en su lugar: si son vecinas, el cerebro lo anota */
    @Synchronized
    fun notarTecla(borrada: Char, nueva: Char) {
        if (pausado) return
        val a = borrada.lowercaseChar()
        val b = nueva.lowercaseChar()
        if (a == b || !a.isLetter() || !b.isLetter()) return
        val pa = sinTildes(a.toString()).firstOrNull() ?: return
        val pb = sinTildes(b.toString()).firstOrNull() ?: return
        if (VECINOS[pa]?.contains(pb) != true && !similares(pa, pb)) return
        cerebro.aprenderTecla(pa, pb)
        refrescarPerfil()
        cambios = true
    }

    /**
     * Cambiaste una palabra que existe ([escrita]) por otra que también existe y se le parece
     * ([querida]) después de [previa]: "tubo" → "tuvo". Es un error difícil de ver (la
     * autocorrección no lo nota porque ambas son palabras); el cerebro lo aprende por contexto.
     */
    @Synchronized
    fun notarCambio(previa: String, escrita: String, querida: String, peso: Float = 1f) {
        if (pausado) return
        val ix = indice ?: return
        val e = escrita.trim()
        val q = querida.trim()
        if (e.length < 2 || q.length < 2 || !valida(e) || !valida(q) || e.equals(q, ignoreCase = true)) return
        if (!conoce(ix, e) || !conoce(ix, q)) return
        if (!Cerebro.parecidas(normal(e.lowercase()), normal(q.lowercase()))) return
        cerebro.aprenderCambio(previa, e, q, peso)
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
        cerebro.rechazarCambio(o, c)
        refrescarPerfil()
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
        /** Hasta esta posición la palabra es muy común (para separar tres o más palabras pegadas) */
        private const val MUY_COMUN = 2600
        /** Hasta aquí puede ir la primera palabra si una letra (b, n, v, m, c) cayó en lugar del espacio */
        private const val COMUN_SEPARADOR = 6000
        /** Veces que hay que usar una palabra que no está en el diccionario para que sea tuya */
        private const val MIN_PROPIA = 3
        /** Una palabra nueva muy parecida a una común necesita más usos para ser tuya */
        private const val MIN_CERCANA = 6
        /** Letras seguidas de una fila del teclado: si una "palabra" las tiene, es un golpe */
        private val FILAS_TECLADO = listOf(
            "qwer", "wert", "erty", "rtyu", "tyui", "yuio", "uiop", "asdf", "sdfg", "dfgh", "fghj",
            "ghjk", "hjkl", "jklñ", "zxcv", "xcvb", "cvbn", "vbnm", "poiu", "lkjh", "mnbv", "fdsa",
        )
        /** Lo mismo para palabras de dos letras ("yq", "jj" casi siempre son errores) */
        private const val MIN_CORTA = 6
        /** Lo mismo para una palabra escrita sin su tilde ("sabado") */
        private const val MIN_VARIANTE = 12
        private const val EN_DICCIONARIO = 0
        private const val SIN_TILDE = 1
        private const val OTRA = 2
        /** Teclas de la fila de abajo que el dedo toca cuando quería el espacio */
        private const val JUNTO_AL_ESPACIO = "bnvmc"
        /**
         * Terminaciones que se pegan a una palabra sin ser otra palabra: pronombres ("dímelo",
         * "infórmenos"), diminutivos y aumentativos ("ahorita", "normalazo") y otras.
         */
        private val SUFIJOS: List<String> = run {
            val cl = listOf("me", "te", "se", "nos", "os", "le", "les", "lo", "los", "la", "las")
            val dobles = listOf("me", "te", "se", "nos").flatMap { a -> listOf("lo", "la", "los", "las", "le", "les").map { a + it } }
            cl + dobles + listOf(
                "azo", "aza", "azos", "azas", "ito", "ita", "itos", "itas", "cito", "cita", "citos", "citas",
                "illo", "illa", "illos", "illas", "ote", "ota", "otes", "otas", "isimo", "isima", "isimos",
                "isimas", "mente", "ucho", "ucha", "on", "ona", "ones", "onas",
            )
        }
        /**
         * Palabras que también son el comienzo de muchísimas otras ("con" → consecuencia,
         * "en" → entonces, "sin" → sintonía): separar después de ellas pide más seguridad
         */
        private val PREFIJOS_PALABRA = hashSetOf(
            "con", "en", "sin", "para", "sobre", "contra", "entre", "ante", "bien", "mal",
            "tras", "bajo", "medio", "media", "auto", "super", "pre", "des", "sub", "pro", "inter",
        )
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
            haigan:hayan nadies:nadie vistes:viste dentrar:entrar dentra:entra ecepto:excepto abía:había
            ablar:hablar ablamos:hablamos ablas:hablas abla:habla iendo:yendo travez:través decicion:decisión
            dicision:decisión precion:presión ancioso:ansioso anciosa:ansiosa ijo:hijo ija:hija ijos:hijos
            ermano:hermano ermana:hermana ermanos:hermanos orario:horario ospital:hospital ambre:hambre
            onesto:honesto acer:hacer mui:muy voi:voy estoi:estoy soi:soy doi:doy llo:yo yegue:llegué yego:llego
            yegar:llegar yamar:llamar yamame:llámame yave:llave yaves:llaves yuvia:lluvia cayate:cállate
            uste:usted esamen:examen sertificado:certificado sertificados:certificados resivo:recibo
            resivi:recibí resibi:recibí resivir:recibir recivo:recibo solisito:solicito solisitud:solicitud
            espediente:expediente expedente:expediente notificasion:notificación aprovasion:aprobación
            aprovacion:aprobación aprovado:aprobado aprovada:aprobada aprovar:aprobar provar:probar
            provando:probando berdad:verdad boi:voy nobio:novio nobia:novia ebento:evento llebar:llevar
            llebo:llevo lleba:lleva deverias:deberías devo:debo deves:debes deve:debe deveria:debería
            travajo:trabajo travajar:trabajar isquierda:izquierda ezo:eso conose:conoce paresido:parecido
            parese:parece diferiencia:diferencia intelijente:inteligente imajen:imagen imajenes:imágenes
            jente:gente jenial:genial jeneral:general felis:feliz felises:felices desirle:decirle asme:hazme
            aslo:hazlo asle:hazle hiso:hizo hise:hice hisiste:hiciste cojudes:cojudez talves:tal_vez
            porsupuesto:por_supuesto encerio:en_serio alomejor:a_lo_mejor
            abiamos:habíamos abian:habían abias:habías abida:habida abidas:habidas abido:habido abidos:habidos
            abiendo:habiendo abil:hábil abilidad:habilidad abilidades:habilidades abilitar:habilitar
            abitacion:habitación abitaciones:habitaciones abitante:habitante abitantes:habitantes
            abitar:habitar abito:hábito abitos:hábitos abitual:habitual abituar:habituar ablan:hablan
            ablando:hablando ablaste:hablaste able:hablé ablemos:hablemos abremos:habremos acabao:acabado
            acanpar:acampar ace:hace aceder:acceder acemos:hacemos acen:hacen acerlo:hacerlo aces:haces
            acesible:accesible aceso:acceso acesorio:accesorio acesos:accesos aciamos:hacíamos acian:hacían
            acias:hacías acidente:accidente acidentes:accidentes acienda:hacienda aciendo:haciendo
            acion:acción acionar:accionar aciones:acciones acionista:accionista aconpaña:acompaña
            aconpañame:acompáñame aconpañamiento:acompañamiento aconpañar:acompañar aconpaño:acompañó
            acostunbrar:acostumbrar acseder:acceder acsesible:accesible acseso:acceso acsesorio:accesorio
            acsesos:accesos acsidente:accidente acsidentes:accidentes acsion:acción acsionar:accionar
            acsiones:acciones acsionista:accionista adicsion:adicción adkirir:adquirir adkisicion:adquisición
            aflicion:aflicción aflicsion:aflicción ago:hago aka:acá akel:aquel akella:aquella akellas:aquellas
            akello:aquello akellos:aquellos alcantariya:alcantarilla alfonbra:alfombra alkilar:alquilar
            alkiler:alquiler allar:hallar allazgo:hallazgo aller:ayer alluda:ayuda alludame:ayúdame
            alludar:ayudar alludas:ayudas allude:ayudé alluno:ayuno alrato:al_rato altok:al_toque
            altoke:al_toque altoqe:al_toque alunbrado:alumbrado alunbrar:alumbrar amariya:amarilla
            amariyo:amarillo amburguesa:hamburguesa ametrayadora:ametralladora   ampallada:ampayada
            ampallado:ampayado ampallar:ampayar ampallaron:ampayaron anbicion:ambición anbiciones:ambiciones
            anbicioso:ambicioso anbientar:ambientar anbiente:ambiente anbientes:ambientes anbito:ámbito
            anbitos:ámbitos anbulancia:ambulancia anesar:anexar aniyo:anillo anparar:amparar anpay:ampay
            anpayada:ampayada anpayado:ampayado anpayar:ampayar anpayaron:ampayaron anpliacion:ampliación
            anpliar:ampliar anplio:amplio anplitud:amplitud antealler:anteayer apeyido:apellido
            apeyidos:apellidos apollar:apoyar apollo:apoyo apollos:apoyos aprosimacion:aproximación
            aqueya:aquella aqueyas:aquellas aqueyo:aquello aqueyos:aquellos arciya:arcilla ardiya:ardilla
            argoya:argolla   ariamos:haríamos arian:harían arias:harías arina:harina arkero:arquero
            arkitecto:arquitecto arkitectos:arquitectos arkitectura:arquitectura arkitecturas:arquitecturas
            arranke:arranque arreglao:arreglado arrollo:arroyo asanblea:asamblea asanbleas:asambleas
            asen:hacen asfisiar:asfixiar asila:axila asonbrar:asombrar asonbro:asombro asonbroso:asombroso
            asustao:asustado atake:ataque atracion:atracción atracsion:atracción aunke:aunque ausilio:auxilio
            autoalluda:autoayuda aveer:a_ver aveyana:avellana avisastes:avisaste   ayamos:hayamos
            ayan:hayan ayas:hayas ayi:allí azaña:hazaña bachiyer:bachiller bachiyerato:bachillerato
            banba:bamba banbas:bambas banbear:bambear banbera:bambera banbero:bambero banketa:banqueta
            barbiya:barbilla bas:vas bataya:batalla batayas:batallas baya:vaya bayena:ballena benga:venga
            bengan:vengan bengo:vengo benir:venir bente:vente bernos:vernos berte:verte bes:ves beyeza:belleza
            biene:viene bienen:vienen bienes:vienes bimos:vimos biste:viste biyete:billete biyetera:billetera
            biyetes:billetes biyeton:billetón biyon:billón blankiazul:blanquiazul blankirroja:blanquirroja
            bloke:bloque blokear:bloquear bocadiyo:bocadillo bolsiyo:bolsillo bolsiyos:bolsillos
            bombiya:bombilla bonbero:bombero bonbilla:bombilla boseo:boxeo boske:bosque boskejo:bosquejo
            boskes:bosques boteya:botella boteyas:botellas botikin:botiquín boy:voy briyante:brillante
            briyar:brillar briyo:brillo buhardiya:buhardilla buske:busqué buskeda:búsqueda buskedas:búsquedas
            cabayero:caballero cabayeros:caballeros cabeyo:cabello cabeyos:cabellos cachinba:cachimba
            cachinbo:cachimbo cachinbos:cachimbos calefacion:calefacción calefacsion:calefacción
            callera:cayera calleramos:cayéramos calleran:cayeran calleras:cayeras cameyo:camello
            camiya:camilla canbia:cambia canbiar:cambiar canbie:cambié canbios:cambios canpana:campana
            canpaña:campaña canpañas:campañas canpeon:campeón canpeonato:campeonato canpeonatos:campeonatos
            canpesino:campesino canpestre:campestre canpo:campo canpos:campos canpus:campus capiya:capilla
            casiya:casilla casteyano:castellano castiyo:castillo cayado:callado cayar:callar caye:calle
            cayejon:callejón cayes:calles ceboya:cebolla cepiyar:cepillar cepiyo:cepillo chaketa:chaqueta
            chanba:chamba chanbas:chambas chanbea:chambea chanbeador:chambeador chanbeadora:chambeadora
            chanbeamos:chambeamos chanbean:chambean chanbeando:chambeando chanbear:chambear chanbeas:chambeas
            chanbee:chambeé chanbera:chambera chanbero:chambero chanberos:chamberos chanbita:chambita
            chanbon:chambón chanbona:chambona chanke:chanqué chanpu:champú cheke:cheque chekear:chequear
            chikilla:chiquilla chikillas:chiquillas chikillo:chiquillo chikillos:chiquillos chiquiya:chiquilla
            chiquiyas:chiquillas chiquiyo:chiquillo chiquiyos:chiquillos choke:choque chonpa:chompa
            chonpita:chompita cokito:coquito colecion:colección colecionar:coleccionar coleciones:colecciones
            colecsion:colección colecsionar:coleccionar colecsiones:colecciones coliya:colilla
            colokio:coloquio colonbiano:colombiano colunpio:columpio complicao:complicado comprastes:compraste
            conbate:combate conbatir:combatir conbativo:combativo conbi:combi conbinacion:combinación
            conbinaciones:combinaciones conbinar:combinar conbis:combis conbustible:combustible
            conducion:conducción conducsion:conducción conesion:conexión conesiones:conexiones
            confecion:confección confecionar:confeccionar confecsion:confección confecsionar:confeccionar
            conke:conque conkista:conquista conkistar:conquistar conocistes:conociste conosemos:conocemos
            conoser:conocer conosi:conocí conpa:compa conpadre:compadre conparacion:comparación
            conparaciones:comparaciones conparar:comparar conpartir:compartir conpas:compás
            conpasion:compasión conpasivo:compasivo conpatibilidad:compatibilidad conpatible:compatible
            conpañera:compañera conpañeras:compañeras conpañerita:compañerita conpañerito:compañerito
            conpañero:compañero conpañeros:compañeros conpañia:compañía conpañias:compañías
            conpensacion:compensación conpensaciones:compensaciones conpensar:compensar
            conpetencia:competencia conpetencias:competencias conpetente:competente conpeticion:competición
            conpeticiones:competiciones conpetidor:competidor conpetir:competir conpetitivo:competitivo
            conplacer:complacer conplejidad:complejidad conplejidades:complejidades conplejo:complejo
            conplementar:complementar conplementario:complementario conplemento:complemento conpleta:completa
            conpletamente:completamente conpletar:completar conpleto:completo conplicado:complicado
            conplicar:complicar conplicidad:complicidad conponente:componente conponer:componer
            conportamiento:comportamiento conportamientos:comportamientos conportarse:comportarse
            conpositor:compositor conpostura:compostura conpra:compra conpralo:cómpralo conprar:comprar
            conpras:compras conpre:compré conprende:comprende conprender:comprender conprendio:comprendió
            conprensible:comprensible conprensivo:comprensivo conpresion:compresión conprimir:comprimir
            conpro:compró conprobar:comprobar conprometer:comprometer conprometido:comprometido
            conpromiso:compromiso conpromisos:compromisos conpu:compu conpulsivo:compulsivo
            conputacion:computación conputadora:computadora conputadoras:computadoras conputar:computar
            conputo:cómputo construcion:construcción construciones:construcciones construcsion:construcción
            construcsiones:construcciones construlle:construye construllo:construyó
            contenplacion:contemplación contenplar:contemplar contenporaneo:contemporáneo contestos:contextos
            contracion:contracción contracsion:contracción contradicion:contradicción
            contradicsion:contradicción contratienpo:contratiempo convicion:convicción
            conviciones:convicciones convicsion:convicción convicsiones:convicciones conyevar:conllevar
            cordiyera:cordillera correcion:corrección correciones:correcciones correcsion:corrección
            correcsiones:correcciones costiya:costilla costunbre:costumbre costunbres:costumbres
            cremayera:cremallera crioya:criolla crioyada:criollada crioyo:criollo cuadriya:cuadrilla
            cualkier:cualquier cualkiera:cualquiera cuchiyo:cuchillo cueyo:cuello culla:cuya cullas:cuyas
            cullo:cuyo cullos:cuyos cunbia:cumbia cunbion:cumbión cunbre:cumbre cunpa:cumpa cunpita:cumpita
            cunple:cumple cunpleaños:cumpleaños cunplido:cumplido cunplimiento:cumplimiento
            cunplimientos:cumplimientos cunplio:cumplió cunplir:cumplir deducion:deducción deducsion:deducción
            dejastes:dejaste derrunbe:derrumbe desalluno:desayuno desallunos:desayunos desanparo:desamparo
            desarroya:desarrolla desarroyar:desarrollar desarroyos:desarrollos desblokear:desbloquear
            desconponer:descomponer desconposicion:descomposición desenbarco:desembarco desenpacar:desempacar
            desenpeñar:desempeñar desenpeño:desempeño desenpeños:desempeños desenpleo:desempleo desia:decía
            desian:decían desias:decías desimos:decimos desteyo:destello destrucion:destrucción
            destrucsion:destrucción detayado:detallado detayar:detallar detaye:detalle detayes:detalles
            dicienbre:diciembre dicienbres:diciembres direcion:dirección direciones:direcciones
            direcsion:dirección direcsiones:direcciones distracion:distracción distracsion:distracción
            dormistes:dormiste echos:hechos ectarea:hectárea ejenplo:ejemplo ejenplos:ejemplos ekidad:equidad
            ekilibrado:equilibrado ekilibrar:equilibrar ekilibrio:equilibrio ekilibrios:equilibrios
            ekipaje:equipaje ekipamiento:equipamiento ekipar:equipar ekipo:equipo ekipos:equipos
            ekitativo:equitativo ekivalencia:equivalencia ekivocacion:equivocación ekivocado:equivocado
            ekivocarse:equivocarse elado:helado elecion:elección eleciones:elecciones elecsion:elección
            elecsiones:elecciones elicoptero:helicóptero emisferio:hemisferio emocionao:emocionado emos:hemos
            enamorao:enamorado enbajada:embajada enbajador:embajador enbarazo:embarazo enbarcacion:embarcación
            enbarcar:embarcar enbargo:embargo enblema:emblema enblematico:emblemático enboscada:emboscada
            enbrion:embrión enfoke:enfoque enfokes:enfoques enpacar:empacar enpanada:empanada enpatar:empatar
            enpate:empate enpatico:empático enpatizar:empatizar enpece:empecé enpeorar:empeorar
            enperador:emperador enpezar:empezar enpezo:empezó enpeño:empeño enpieza:empieza enpiezo:empiezo
            enpleado:empleado enpleador:empleador enpleados:empleados enplear:emplear enpleo:empleo
            enpleos:empleos enpoderar:empoderar enporio:emporio enprendedor:emprendedor enprender:emprender
            enprendimiento:emprendimiento enpresa:empresa enpresarial:empresarial enpresario:empresario
            enpresarios:empresarios enpresas:empresas enpujar:empujar enpuje:empuje enrikecer:enriquecer
            ensallar:ensayar ensallo:ensayo ensanblar:ensamblar entendistes:entendiste eredar:heredar
            eredero:heredero erencia:herencia erencias:herencias erida:herida eridas:heridas erir:herir
            ermandad:hermandad ermanita:hermanita ermanito:hermanito ermosa:hermosa ermoso:hermoso eroe:héroe
            eroico:heroico eroina:heroína erramienta:herramienta erramientas:herramientas errero:herrero
            ervir:hervir esactitud:exactitud esageracion:exageración esagerar:exagerar esamenes:exámenes
            esaminar:examinar escavacion:excavación escedente:excedente esceder:exceder escelencia:excelencia
            escelencias:excelencias escepcion:excepción escepcional:excepcional escepciones:excepciones
            escepto:excepto esceptuar:exceptuar escesivo:excesivo escluir:excluir esclusivo:exclusivo
            escribistes:escribiste escursion:excursión escusa:excusa eshaustivo:exhaustivo eshibir:exhibir
            eshortar:exhortar esigencia:exigencia esigencias:exigencias esigente:exigente esigir:exigir
            esiliar:exiliar esilio:exilio esiste:existe esisten:existen esistencia:existencia
            esistencias:existencias esistir:existir esito:éxito esitos:éxitos esitoso:exitoso
            eskeleto:esqueleto eskema:esquema eskemas:esquemas esotico:exótico espandir:expandir
            espansion:expansión espedicion:expedición espedientes:expedientes espedir:expedir
            esperiencia:experiencia esperiencias:experiencias esperimental:experimental
            esperimentar:experimentar esperimento:experimento esperto:experto espertos:expertos
            esplica:explica esplicacion:explicación esplicaciones:explicaciones esplicame:explícame
            esplicar:explicar esplicito:explícito esplorar:explorar esplosion:explosión esplosivo:explosivo
            esplotar:explotar esponente:exponente esponer:exponer esportacion:exportación
            esportaciones:exportaciones esportar:exportar esposicion:exposición esposiciones:exposiciones
            espositor:expositor espresar:expresar espresion:expresión espresiones:expresiones
            espulsar:expulsar esquisito:exquisito estanpa:estampa estava:estaba estavamos:estábamos
            estavan:estaban estavas:estabas estayar:estallar estayido:estallido estender:extender
            estension:extensión estensiones:extensiones estenso:extenso estenuante:extenuante
            esterior:exterior esterno:externo estincion:extinción estracto:extracto estraer:extraer
            estraida:extraída estraidas:extraídas estraidos:extraídos estranjero:extranjero
            estraordinario:extraordinario estrañas:extrañas estrañe:extrañé estremo:extremo estreya:estrella
            etiketa:etiqueta exkisito:exquisito eya:ella eyas:ellas eyo:ello eyos:ellos faya:falla
            fayar:fallar fayas:fallas fayecer:fallecer fayido:fallido ficion:ficción ficsion:ficción
            flakis:flaquis flakita:flaquita flakito:flaquito flesibilidad:flexibilidad
            flesibilidades:flexibilidades flesible:flexible foyeto:folleto fracion:fracción fracsion:fracción
            frankeza:franqueza gacias:gracias galasia:galaxia ganba:gamba gayeta:galleta gayina:gallina
            gayo:gallo gracas:gracias grcias:gracias hablastes:hablaste haigamos:hayamos haigas:hayas
            hanbre:hambre hanburguesa:hamburguesa hayar:hallar hayazgo:hallazgo honbre:hombre honbro:hombro
            huarike:huarique huarikes:huariques huaskeada:huasqueada huaskeado:huasqueado huaskiado:huasquiado
            hueya:huella hueyas:huellas humiyar:humillar icieramos:hiciéramos icieran:hicieran
            icieras:hicieras icieron:hicieron icimos:hicimos iciste:hiciste idratar:hidratar ielo:hielo
            ierba:hierba ierro:hierro igado:hígado imno:himno inbecil:imbécil inbeciles:imbéciles
            inborrable:imborrable incertidunbre:incertidumbre incha:hincha inchas:hinchas
            inconpleto:incompleto inesperto:inexperto infecion:infección infecsion:infección
            inflesible:inflexible influllente:influyente inkietar:inquietar inkieto:inquieto
            inkietud:inquietud inkietudes:inquietudes inkilino:inquilino inlleccion:inyección
            inpaciencia:impaciencia inpaciente:impaciente inpactante:impactante inpacto:impacto
            inpactos:impactos inparcial:imparcial inpartir:impartir inpecable:impecable
            inpedimento:impedimento inpedir:impedir inpensable:impensable inperfecto:imperfecto
            inperio:imperio inpermeable:impermeable inplacable:implacable inplementar:implementar
            inplicar:implicar inplicito:implícito inplorar:implorar inponer:imponer inporta:importa
            inportancia:importancia inportancias:importancias inportantes:importantes inportar:importar
            inporte:importe inportes:importes inporto:importó inpredecible:impredecible inprenta:imprenta
            inpresion:impresión inpresionante:impresionante inpresionar:impresionar inpresiones:impresiones
            inprimida:imprimida inprimidas:imprimidas inprimidos:imprimidos inproductivo:improductivo
            inprovisacion:improvisación inprovisar:improvisar inpuesto:impuesto inpuestos:impuestos
            inpulsar:impulsar inpulsivo:impulsivo inpulso:impulso inpulsos:impulsos inspecion:inspección
            inspecionar:inspeccionar inspecsion:inspección inspecsionar:inspeccionar instrucion:instrucción
            instrucsion:instrucción intenperie:intemperie interacion:interacción interaciones:interacciones
            interacsion:interacción interacsiones:interacciones intercanbiar:intercambiar
            intercanbio:intercambio intercanbios:intercambios interesao:interesado interrunpir:interrumpir
            introducion:introducción introduciones:introducciones introducsion:introducción
            introducsiones:introducciones inyecion:inyección inyecsion:inyección ipoteca:hipoteca
            ipotesis:hipótesis ipotetico:hipotético irrenplazable:irremplazable irrunpir:irrumpir
            istoria:historia istorias:historias istorico:histórico jerarkia:jerarquía jerarkizar:jerarquizar
            jolla:joya jollaza:joyaza jolleria:joyería jollita:joyita jurisdicion:jurisdicción
            jurisdicsion:jurisdicción kasa:casa kebrada:quebrada kebrantar:quebrantar kebrar:quebrar
            kedan:quedan kedar:quedar kedas:quedas kede:quedé keja:queja kejarse:quejarse kejas:quejas
            kema:quema kemada:quemada kemadazo:quemadazo kemado:quemado kemadura:quemadura kemar:quemar
            kemaste:quemaste keme:quemé kemo:quemó keremos:queremos kerer:querer keria:quería
            keriamos:queríamos kerian:querían kerias:querías kerida:querida keridas:queridas kerido:querido
            keridos:queridos kerra:querrá kerran:querrán kerras:querrás kerremos:querremos kerria:querría
            kerriamos:querríamos kerrian:querrían kerrias:querrías keso:queso kiebra:quiebra kien:quién
            kienes:quiénes kiera:quiera kiero:quiero kietud:quietud kimera:quimera kimica:química
            kimicas:químicas kince:quince kinceañera:quinceañera kinceañero:quinceañero kinientas:quinientas
            kinientos:quinientos kinta:quinta kinto:quinto kiosco:quiosco kirofano:quirófano kise:quise
            kisieramos:quisiéramos kisieran:quisieran kisieras:quisieras kiso:quiso kitar:quitar kiza:quizá
            kizas:quizás kuando:cuándo kuanto:cuánto ladriyo:ladrillo lanpara:lámpara lecion:lección
            leciones:lecciones lecsion:lección lecsiones:lecciones legunbre:legumbre lellenda:leyenda
            lelles:leyes lello:leyó likidar:liquidar likido:líquido linpiar:limpiar linpieza:limpieza
            linpiezas:limpiezas linpio:limpio llala:yala llalas:yalas llamastes:llamaste llapa:yapa llape:yapé
            llapea:yapea llapeamos:yapeamos llapean:yapean llapear:yapear llapeas:yapeas llapeaste:yapeaste
            llapee:yapeé llapita:yapita llapo:yapo llara:yara llegua:yegua llema:yema llerno:yerno lleso:yeso
            llogur:yogur lluca:yuca llucazo:yucazo llunta:yunta lluntas:yuntas lonbriz:lombriz
            makillar:maquillar makina:máquina makinaria:maquinaria mallo:mayo mallor:mayor mallores:mayores
            malloria:mayoría mallorias:mayorías mallos:mayos maniki:maniquí manlla:manya manllado:manyado
            manllamos:manyamos manllan:manyan manllando:manyando manllar:manyar manllas:manyas
            manllaste:manyaste mantekilla:mantequilla mantequiya:mantequilla maquiyar:maquillar
            maraviya:maravilla maraviyoso:maravilloso martiyo:martillo masimizar:maximizar masimo:máximo
            medaya:medalla mejiya:mejilla menbrana:membrana mercadiyo:mercadillo mesicano:mexicano
            mienbro:miembro mienbros:miembros miyon:millón mojao:mojado moskita:mosquita mototasi:mototaxi
            mototasis:mototaxis mototasista:mototaxista mueye:muelle nesecitamos:necesitamos
            nesecitan:necesitan nesesidades:necesidades nesesitan:necesitan nonbramiento:nombramiento
            nonbrar:nombrar nonbre:nombre nonbres:nombres nose:no_sé novienbre:noviembre novienbres:noviembres
            obsekiar:obsequiar obsekio:obsequio ocupao:ocupado ogar:hogar ogares:hogares oguera:hoguera
            oie:oye ojas:hojas olis:holis ollera:oyera olleramos:oyéramos olleran:oyeran olleras:oyeras
            olvidao:olvidado ombre:hombre ombres:hombres ombro:hombro omenaje:homenaje omenajear:homenajear
            omenajes:homenajes omicidio:homicidio omologar:homologar onbligo:ombligo onestidad:honestidad
            onestidades:honestidades onor:honor onorable:honorable onores:honores onradez:honradez
            onrado:honrado onrar:honrar orarios:horarios orguyo:orgullo orguyos:orgullos orguyoso:orgulloso
            oriya:orilla orizonte:horizonte orkesta:orquesta ormiga:hormiga ornear:hornear orno:horno
            orrible:horrible orror:horror osigenar:oxigenar osigeno:oxígeno ospedar:hospedar
            ospitalario:hospitalario ospitales:hospitales ospitalidad:hospitalidad
            ospitalizacion:hospitalización ospitalizada:hospitalizada ospitalizado:hospitalizado ostil:hostil
            otel:hotel oya:olla pabeyon:pabellón pagastes:pagaste pakete:paquete pallaso:payaso
            palomiya:palomilla palomiyada:palomillada palomiyas:palomillas pantaya:pantalla paresen:parecen
            paresia:parecía parke:parque parriya:parrilla pasatienpo:pasatiempo pasiyo:pasillo
            pastiya:pastilla patruya:patrulla pekeña:pequeña pekeñas:pequeñas pekeño:pequeño pekeños:pequeños
            pelukeria:peluquería pensastes:pensaste perdistes:perdiste perfecion:perfección
            perfecionar:perfeccionar perfecsion:perfección perfecsionar:perfeccionar pesadiya:pesadilla
            pidistes:pediste pitukeria:pituquería plalla:playa poliya:polilla poyada:pollada poyadas:polladas
            poyeria:pollería poyo:pollo predicion:predicción predicsion:predicción pretesto:pretexto
            producion:producción produciones:producciones producsion:producción producsiones:producciones
            prollectar:proyectar prollecto:proyecto prollectos:proyectos prononbre:pronombre prosima:próxima
            prosimidad:proximidad prosimo:próximo protecion:protección proteciones:protecciones
            protecsion:protección protecsiones:protecciones pusistes:pusiste quisistes:quisiste ralla:raya
            rallo:rayo reacion:reacción reacionar:reaccionar reaciones:reacciones reacsion:reacción
            reacsionar:reaccionar reacsiones:reacciones recolecion:recolección recolecsion:recolección
            reconpensa:recompensa redacion:redacción redacsion:redacción reducion:reducción
            reduciones:reducciones reducsion:reducción reducsiones:reducciones reenbolsar:reembolsar
            reenbolso:reembolso reenplazar:reemplazar reenplazo:reemplazo reenplazos:reemplazos
            reflesion:reflexión reflesionar:reflexionar reflesiones:reflexiones reflesivo:reflexivo
            rekerimiento:requerimiento rekerimientos:requerimientos rekerir:requerir rekisar:requisar
            rekisito:requisito rekisitos:requisitos relanpago:relámpago remolke:remolque repoyo:repollo
            restricion:restricción restriciones:restricciones restricsion:restricción
            restricsiones:restricciones resurrecion:resurrección resurrecsion:resurrección reyenar:rellenar
            reyeno:relleno rikeza:riqueza rodiya:rodilla ronpecabezas:rompecabezas ronper:romper runbo:rumbo
            runbos:rumbos   sakear:saquear saranpion:sarampión satisfacio:satisfizo
            satisfacion:satisfacción satisfaciones:satisfacciones satisfacsion:satisfacción
            satisfacsiones:satisfacciones   saver:saber saves:sabes secion:sección seciones:secciones
            secsion:sección secsiones:secciones sekia:sequía selecion:selección selecionar:seleccionar
            seleciones:selecciones selecsion:selección selecsionar:seleccionar selecsiones:selecciones
            semiya:semilla sena:cena senar:cenar senbrar:sembrar senciyito:sencillito senciyo:sencillo
            sentistes:sentiste sentro:centro septienbre:septiembre septienbres:septiembres serca:cerca
            sercana:cercana sercano:cercano serviyeta:servilleta sesta:sexta sesto:sexto sesual:sexual
            setienbre:setiembre setienbres:setiembres seyar:sellar sielo:cielo sienpre:siempre
            sinbolico:simbólico sinbolo:símbolo sinbolos:símbolos sinco:cinco sine:cine sinpatia:simpatía
            sinpatico:simpático sinple:simple sinplemente:simplemente sinples:simples sinplicidad:simplicidad
            sinplicidades:simplicidades sinplificar:simplificar siudad:ciudad siya:silla siyon:sillón
            sobrenonbre:sobrenombre sonbra:sombra sonbras:sombras sonbrero:sombrero sonbrio:sombrío
            subrallar:subrayar sucunbir:sucumbir sulla:suya sullas:suyas sullo:suyo sullos:suyos
            supistes:supiste takilla:taquilla tambn:también tanbien:también tanbor:tambor tanpoco:tampoco
            taquiya:taquilla tasi:taxi tasis:taxis tasista:taxista tayarines:tallarines tayer:taller
            tayo:tallo tekeños:tequeños tenblar:temblar tenblor:temblor tenperamental:temperamental
            tenperatura:temperatura tenperaturas:temperaturas tenplo:templo tenporada:temporada
            tenporadas:temporadas tenporal:temporal tenprano:temprano testo:texto testos:textos tienpo:tiempo
            tienpos:tiempos tinbrar:timbrar tinbre:timbre toaya:toalla tobiyo:tobillo toke:toqué tonba:tomba
            tonbo:tombo tonbos:tombos torniyo:tornillo tosico:tóxico trabajastes:trabajaste trallecto:trayecto
            trallectoria:trayectoria tranki:tranqui trankila:tranquila trankilazo:tranquilazo
            trankilidad:tranquilidad trankilizar:tranquilizar trankilo:tranquilo trankis:tranquis
            tranpa:trampa tranposa:tramposa tranposo:tramposo tronpeta:trompeta tube:tuve tubieron:tuvieron
            tubimos:tuvimos tulla:tuya tullas:tuyas tullo:tuyo tullos:tuyos tunba:tumba uachafa:huachafa
            uachafas:huachafas uachaferia:huachafería uachafo:huachafo uachafos:huachafos uachiman:huachimán
            uachimanes:huachimanes uarique:huarique uariques:huariques uasca:huasca uasqueada:huasqueada
            uasqueado:huasqueado uasquiado:huasquiado uaynito:huaynito uayno:huayno ubieramos:hubiéramos
            ubieran:hubieran ubieron:hubieron ubimos:hubimos ubiste:hubiste ueco:hueco uelga:huelga
            uella:huella uellas:huellas ueso:hueso uesped:huésped uevada:huevada uevear:huevear uevo:huevo
            uevos:huevos uida:huida umana:humana umanas:humanas umanidad:humanidad umanidades:humanidades
            umano:humano umanos:humanos umedad:humedad umedo:húmedo umildad:humildad umildades:humildades
            umilde:humilde umillar:humillar umor:humor umores:humores unbral:umbral vajiya:vajilla valla:vaya
            vaye:valle vendistes:vendiste ventaniya:ventanilla viajastes:viajaste videoyamada:videollamada
            vinistes:viniste vislunbrar:vislumbrar vivistes:viviste viya:villa yama:llama yamada:llamada
            yamadas:llamadas yamado:llamado yamamos:llamamos yamare:llamaré yamas:llamas yamativo:llamativo
            yame:llamé yanto:llanto yanura:llanura yavero:llavero yega:llega yegada:llegada yegadas:llegadas
            yegamos:llegamos yegan:llegan yegando:llegando yegara:llegará yegaron:llegaron yegas:llegas
            yegaste:llegaste yegues:llegues yenar:llenar yeno:lleno yeva:lleva yevamos:llevamos yevan:llevan
            yevar:llevar yevas:llevas yeve:llevé yorar:llorar yoro:lloro yover:llover yovizna:llovizna
            zanpada:zampada zanpado:zampado zanpar:zampar zanparse:zamparse zanpon:zampón zanpona:zampona
            zapatiyas:zapatillas
        """.plus(" ").plus(ERRORES_MAS).plus(" ").plus(ERRORES_V9).plus(" ").plus(ERRORES_V10).plus(" ").plus(ERRORES_V11).trim().split(ESPACIOS).mapNotNull { par ->
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
        """.let { leerPares(it) } + leerPares(CONFUSIONES) + leerPares(CONFUSIONES_MAS) + leerPares(CONFUSIONES_V9)

        /**
         * "a|b": la palabra anterior y la actual escritas como "a" se cambian por "b". Si "b"
         * no son dos palabras, el "+" marca dónde termina la anterior ("vamos+a ver").
         */
        private fun leerPares(texto: String): Map<String, Pair<String, String>> =
            texto.trim().split(";").mapNotNull { linea ->
                val partes = linea.trim().split("|")
                if (partes.size != 2) null else {
                    val a = partes[0].trim().split(" ")
                    val bTexto = partes[1].trim()
                    val mas = bTexto.indexOf('+')
                    val b = if (mas > 0) listOf(bTexto.substring(0, mas).trim(), bTexto.substring(mas + 1).trim())
                        else bTexto.split(" ")
                    if (a.size == 2 && b.size == 2 && b[0].isNotEmpty() && b[1].isNotEmpty())
                        "${sinTildes(a[0])} ${sinTildes(a[1])}" to (b[0] to b[1]) else null
                }
            }.toMap()

        /**
         * Confusiones de gramática típicas del celular, que solo se ven con la palabra anterior
         * (las dos palabras existen, por eso la autocorrección sola no las ve):
         * "vamos haber" → "vamos a ver", "ay que" → "hay que", "por hay" → "por ahí",
         * "he echo" → "he hecho", "a sido" → "ha sido", "otra ves" → "otra vez",
         * "voy hacer" → "voy a hacer", "que valla" → "que vaya". Si deshaces una, vuelve
         * a lo que escribiste.
         */
        private const val CONFUSIONES = """
            e sido|he sido; as sido|has sido; an sido|han sido; a sido|ha sido; e hecho|he hecho; as hecho|has hecho;
            an hecho|han hecho; a hecho|ha hecho; e dicho|he dicho; as dicho|has dicho; an dicho|han dicho;
            a dicho|ha dicho; e visto|he visto; as visto|has visto; an visto|han visto; a visto|ha visto; e ido|he ido;
            as ido|has ido; an ido|han ido; a ido|ha ido; e llegado|he llegado; as llegado|has llegado;
            an llegado|han llegado; a llegado|ha llegado; e salido|he salido; as salido|has salido; an salido|han salido;
            a salido|ha salido; e terminado|he terminado; as terminado|has terminado; an terminado|han terminado;
            a terminado|ha terminado; e comido|he comido; as comido|has comido; an comido|han comido; a comido|ha comido;
            e dormido|he dormido; as dormido|has dormido; an dormido|han dormido; a dormido|ha dormido;
            e llamado|he llamado; as llamado|has llamado; an llamado|han llamado; a llamado|ha llamado;
            e escrito|he escrito; as escrito|has escrito; an escrito|han escrito; a escrito|ha escrito;
            e enviado|he enviado; as enviado|has enviado; an enviado|han enviado; a enviado|ha enviado;
            e mandado|he mandado; as mandado|has mandado; an mandado|han mandado; a mandado|ha mandado;
            e pagado|he pagado; as pagado|has pagado; an pagado|han pagado; a pagado|ha pagado; e cambiado|he cambiado;
            as cambiado|has cambiado; an cambiado|han cambiado; a cambiado|ha cambiado; e venido|he venido;
            as venido|has venido; an venido|han venido; a venido|ha venido; e vuelto|he vuelto; as vuelto|has vuelto;
            an vuelto|han vuelto; a vuelto|ha vuelto; e tenido|he tenido; as tenido|has tenido; an tenido|han tenido;
            a tenido|ha tenido; e podido|he podido; as podido|has podido; an podido|han podido; a podido|ha podido;
            e dado|he dado; as dado|has dado; an dado|han dado; a dado|ha dado; e traido|he traído; as traido|has traído;
            an traido|han traído; a traido|ha traído; e leido|he leído; as leido|has leído; an leido|han leído;
            a leido|ha leído; e entendido|he entendido; as entendido|has entendido; an entendido|han entendido;
            a entendido|ha entendido; e conseguido|he conseguido; as conseguido|has conseguido;
            an conseguido|han conseguido; a conseguido|ha conseguido; e encontrado|he encontrado;
            as encontrado|has encontrado; an encontrado|han encontrado; a encontrado|ha encontrado; e perdido|he perdido;
            as perdido|has perdido; an perdido|han perdido; a perdido|ha perdido; e ganado|he ganado;
            as ganado|has ganado; an ganado|han ganado; e trabajado|he trabajado;
            as trabajado|has trabajado; an trabajado|han trabajado; a trabajado|ha trabajado; e estudiado|he estudiado;
            as estudiado|has estudiado; an estudiado|han estudiado; a estudiado|ha estudiado; e comprado|he comprado;
            as comprado|has comprado; an comprado|han comprado; a comprado|ha comprado; e vendido|he vendido;
            as vendido|has vendido; an vendido|han vendido; a vendido|ha vendido; e decidido|he decidido;
            as decidido|has decidido; an decidido|han decidido; a decidido|ha decidido; e pensado|he pensado;
            as pensado|has pensado; an pensado|han pensado; a pensado|ha pensado; e olvidado|he olvidado;
            as olvidado|has olvidado; an olvidado|han olvidado; a olvidado|ha olvidado; e recibido|he recibido;
            as recibido|has recibido; an recibido|han recibido; a recibido|ha recibido; e avisado|he avisado;
            as avisado|has avisado; an avisado|han avisado; a avisado|ha avisado; e preguntado|he preguntado;
            as preguntado|has preguntado; an preguntado|han preguntado; a preguntado|ha preguntado;
            e contestado|he contestado; as contestado|has contestado; an contestado|han contestado;
            a contestado|ha contestado; e respondido|he respondido; as respondido|has respondido;
            an respondido|han respondido; a respondido|ha respondido; e empezado|he empezado; as empezado|has empezado;
            an empezado|han empezado; a empezado|ha empezado; e acabado|he acabado; as acabado|has acabado;
            an acabado|han acabado; a acabado|ha acabado; e regresado|he regresado; as regresado|has regresado;
            an regresado|han regresado; a regresado|ha regresado; e vivido|he vivido; as vivido|has vivido;
            an vivido|han vivido; a vivido|ha vivido; e conocido|he conocido; as conocido|has conocido;
            an conocido|han conocido; a conocido|ha conocido; e sentido|he sentido; as sentido|has sentido;
            an sentido|han sentido; a echo|ha hecho; e echo|he hecho; yo e|yo he; lo e|lo he;
            te e|te he; le e|le he; no e|no he; me a|me ha; te a|te ha; le a|le ha; nos a|nos ha; lo a|lo ha;
            he echo|he hecho; has echo|has hecho; ha echo|ha hecho; han echo|han hecho; hemos echo|hemos hecho;
            habia echo|había hecho; bien echo|bien hecho; mal echo|mal hecho; esta echo|está hecho;
            vamos haber|vamos+a ver; haber si|a ver+si; haber que|a ver+qué; haber cuando|a ver+cuándo;
            haber como|a ver+cómo; haber donde|a ver+dónde; haber quien|a ver+quién; haber dime|a ver+dime;
            haber cuentame|a ver+cuéntame; ha ver|a ver; va haber|va+a haber; van haber|van+a haber;
            voy hacer|voy+a hacer; vas hacer|vas+a hacer; va hacer|va+a hacer; vamos hacer|vamos+a hacer;
            van hacer|van+a hacer; voy ir|voy+a ir; vas ir|vas+a ir; vamos ir|vamos+a ir; voy ver|voy+a ver;
            vamos ver|vamos+a ver; voy ha|voy a; vas ha|vas a; va ha|va a; vamos ha|vamos a; van ha|van a; ha la|a la;
            ha las|a las; ha los|a los; ay que|hay que; no ay|no hay; ya ay|ya hay; si ay|si hay; que ay|que hay;
            ay muchos|hay muchos; ay muchas|hay muchas; ay mucho|hay mucho; ay mucha|hay mucha; ay alguien|hay alguien;
            ay algo|hay algo; ay gente|hay gente; ay tiempo|hay tiempo; ay problema|hay problema; ay clases|hay clases;
            ay chamba|hay chamba; por ay|por ahí; por hay|por ahí; de hay|de ahí; hasta hay|hasta ahí;
            hay nomas|ahí nomás; ahi nomas|ahí nomás; hay esta|ahí está; hay estoy|ahí estoy; hay voy|ahí voy;
            hay vamos|ahí vamos; hay nos|ahí nos; ay nos|ahí nos; esta hay|está ahí; esta ay|está ahí;
            estoy hay|estoy ahí; estoy ay|estoy ahí; estas hay|estás ahí; estas ay|estás ahí; otra ves|otra vez;
            una ves|una vez; tal ves|tal vez; cada ves|cada vez; alguna ves|alguna vez; esta ves|esta vez;
            primera ves|primera vez; ultima ves|última vez; segunda ves|segunda vez; de ves|de vez; rara ves|rara vez;
            que valla|que vaya; no valla|no vaya; ojala valla|ojalá vaya; cuando valla|cuando vaya; que halla|que haya;
            no halla|no haya; ojala halla|ojalá haya; cuando halla|cuando haya; no tubo|no tuvo; que tubo|que tuvo;
            yo boy|yo voy; ya boy|ya voy; me boy|me voy; ahi boy|ahí voy; no boy|no voy; ola como|hola+cómo;
            ola amor|hola amor; ola buenos|hola buenos; ola buenas|hola buenas; ola que|hola+qué; lo mas|lo más;
            es mas|es más; mucho mas|mucho más; poco mas|poco más; nada mas|nada más; algo mas|algo más;
            mas bien|más bien; mas rapido|más rápido; mas temprano|más temprano; mas grande|más grande; vez mas|vez más;
            asta luego|hasta luego; asta mañana|hasta mañana; asta pronto|hasta pronto
        """

        /**
         * Más errores de oído y de apuro que son muy comunes (b/v, c/s/z, ll/y, g/j, la h,
         * palabras pegadas): "pasiensia" → "paciencia", "hize" → "hice", "alamejor" → "a lo mejor".
         */
        private const val ERRORES_MAS = """
            hechame:échame hechale:échale hechalo:échalo hechate:échate pasiensia:paciencia pasiencia:paciencia
            ecselente:excelente exelentes:excelentes exsito:éxito exitos:éxitos vuelbo:vuelvo vuelbe:vuelve
            vuelben:vuelven bolvi:volví bolver:volver volber:volver devemos:debemos deveriamos:deberíamos
            devido:debido deven:deben cresco:crezco ofresco:ofrezco agradesco:agradezco meresco:merezco
            conosca:conozca agradesca:agradezca hize:hice hizimos:hicimos hizieron:hicieron hiziste:hiciste
            sientate:siéntate preucupes:preocupes preucupa:preocupa preucupado:preocupado trabage:trabaje
            trabages:trabajes trabaga:trabaja trabagar:trabajar trabaho:trabajo desepcion:decepción
            desepcionado:decepcionado desepcionada:decepcionada embes:en_vez enves:en_vez hiendo:yendo
            creiendo:creyendo leiendo:leyendo caiendo:cayendo oiendo:oyendo huiendo:huyendo escusas:excusas
            espliqué:expliqué porsiacaso:por_si_acaso porciacaso:por_si_acaso alamejor:a_lo_mejor nosé:no_sé
            sobretodo:sobre_todo apartirde:a_partir_de entodo:en_todo derepente:de_repente denuevo:de_nuevo
            depronto:de_pronto devez:de_vez nomas:nomás ensima:encima atraz:atrás atras:atrás
            alrrededor:alrededor reciví:recibí resibir:recibir resibido:recibido desidir:decidir desidi:decidí
            desidio:decidió desision:decisión intereza:interesa interezante:interesante interezado:interesado
            empesar:empezar empeso:empezó empese:empecé empesamos:empezamos empesaron:empezaron canbio:cambio
            simpre:siempre selular:celular tamien:también tamben:también bacio:vacío bacia:vacía vasio:vacío
            vasia:vacía yueve:llueve yorando:llorando cojer:coger cojí:cogí eleji:elegí
            jeneralmente:generalmente injeniero:ingeniero ablo:hablo oy:hoy bez:vez beces:veces bida:vida
            bivir:vivir bivo:vivo biaje:viaje verda:verdad havia:había havian:habían haviamos:habíamos
            quiza:quizá desagradesido:desagradecido agradesido:agradecido agradesida:agradecida coraçon:corazón
        """

        /**
         * Errores difíciles de ver: las dos palabras existen, pero juntas no tienen sentido.
         * Solo se nota por la palabra de al lado: "tubo que" → "tuvo que", "halla sido" →
         * "haya sido", "haz visto" → "has visto", "te valla bien" → "te vaya bien",
         * "aver si" → "a ver si", "debe aver" → "debe haber", "hay dios" → "ay Dios".
         */
        /**
         * Más errores del día a día (v9): palabras pegadas ("asique", "porlomenos"), verbos mal
         * conjugados ("ponido", "detení", "quedría", "cabió"), escritura de chat ("ke", "ai",
         * "grax") y palabras peruanas mal escritas ("chebere", "guachafo", "chivolo", "aji").
         */
        /**
         * Más errores de oído (v10): b/v ("bibir", "nuebo"), c/s/z ("sebolla", "pareser",
         * "reconosco"), ll/y ("cabayo", "gayetas"), la h que sobra ("hechando", "hiban") y
         * frases pegadas ("atraves", "nadamas", "alafirme").
         */
        /**
         * Errores v11: abreviaturas del chat (xq, tmb, dsp, msj), adverbios en -mente y palabras en
         * -ente mal escritas (realmete, suficente, urjente, pendinte), y palabras de trámites y del
         * sector público (expedinte, presupueto, licitasion). Cada una con su forma correcta.
         */
        private const val ERRORES_V11 = """
            xq:porque xk:porque pq:porque pk:porque porq:porque prq:porque pork:porque tmb:también tb:también
            tmbn:también tbn:también tambie:también tambein:también xfa:por_favor xfavor:por_favor pfv:por_favor
            xfis:por_favor dnd:donde dnde:donde cdo:cuando cnd:cuando kndo:cuando qndo:cuando cuado:cuando
            salu2:saludos slds:saludos sldos:saludos grcs:gracias grs:gracias  bno:bueno bn:bien
            bnos:buenos bnas:buenas mñn:mañana mñna:mañana mñana:mañana msj:mensaje msje:mensaje msjs:mensajes
            mjs:mensajes ntp:no_te_preocupes dsp:después dps:después despue:después despuse:después
            depues:después hno:hermano hna:hermana hnos:hermanos tqm:te_quiero_mucho tkm:te_quiero_mucho
                aunq:aunque aunk:aunque nesesariamente:necesariamente
            necesariamnete:necesariamente rapidamnete:rápidamente rapidamete:rápidamente realmnete:realmente
            realmete:realmente realmentee:realmente solamnete:solamente solamete:solamente
            basicamnete:básicamente basicamete:básicamente literalmnete:literalmente literalmete:literalmente
            exactamnete:exactamente exactamete:exactamente totalmnete:totalmente totalmete:totalmente
            obviamnete:obviamente obviamete:obviamente finalmnete:finalmente finalmete:finalmente
            generalmnete:generalmente generalmete:generalmente normalmnete:normalmente normalmete:normalmente
            actualmnete:actualmente actualmete:actualmente simplemnete:simplemente simplemete:simplemente
            seguramnete:seguramente seguramete:seguramente claramnete:claramente claramete:claramente
            completamnete:completamente completamete:completamente sinceramnete:sinceramente
            sinceramete:sinceramente atentamnete:atentamente atentamete:atentamente personalmnete:personalmente
            personalmete:personalmente unicamnete:únicamente unicamete:únicamente igualmnete:igualmente
            igualmete:igualmente perfectamnete:perfectamente perfectamete:perfectamente
            practicamnete:prácticamente practicamete:prácticamente probablemnete:probablemente
            probablemete:probablemente inmediatamnete:inmediatamente inmediatamete:inmediatamente
            nuevamnete:nuevamente nuevamete:nuevamente directamnete:directamente directamete:directamente
            especialmnete:especialmente especialmete:especialmente efectivamnete:efectivamente
            efectivamete:efectivamente posiblemnete:posiblemente posiblemete:posiblemente
            anteriormnete:anteriormente anteriormete:anteriormente previamnete:previamente
            previamete:previamente oportunamnete:oportunamente oportunamete:oportunamente
            cordialmnete:cordialmente cordialmete:cordialmente felismente:felizmente felizmete:felizmente
            afortunadamnete:afortunadamente lamentablemnete:lamentablemente lamentablemete:lamentablemente
            esistente:existente esistentes:existentes exsistente:existente ecsistente:existente
            eficente:eficiente eficasmente:eficazmente suficente:suficiente suficentes:suficientes
            sufciente:suficiente diferiente:diferente diferientes:diferentes difernte:diferente
            diferntes:diferentes excelnte:excelente intelignte:inteligente intelijentes:inteligentes
            urjente:urgente urjentes:urgentes urgnte:urgente pacinte:paciente pasiente:paciente
            pasientes:pacientes paciensia:paciencia concientes:conscientes pendinte:pendiente pendente:pendiente
            pendientee:pendiente cliete:cliente clientess:clientes presidnte:presidente precidente:presidente
            gerentee:gerente jerente:gerente independiete:independiente independente:independiente
            recientemnete:recientemente resientemente:recientemente frecuentemnete:frecuentemente
            frecuentemete:frecuentemente frequente:frecuente frecuentementee:frecuentemente
            constantemnete:constantemente constantemete:constantemente evidentemnete:evidentemente
            evidentemete:evidentemente aparentemnete:aparentemente aparentemete:aparentemente siguinte:siguiente
            sigientes:siguientes concenso:consenso excento:exento excenta:exenta exhuberante:exuberante
            inflingir:infligir diabetis:diabetes mounstruo:monstruo mostruo:monstruo vertir:verter
            cocreta:croqueta sicologo:psicólogo sicologa:psicóloga ambulacia:ambulancia ambulansia:ambulancia
            aiga:haya ases:haces asemos:hacemos aremos:haremos aras:harás ara:hará expedinte:expediente
            resolusion:resolución licitasion:licitación contratasion:contratación adquisision:adquisición
            requerimento:requerimiento requerimentos:requerimientos presupueto:presupuesto
            presupesto:presupuesto certificasion:certificación certifcado:certificado
            documentasion:documentación ducumento:documento docuemnto:documento municipalidd:municipalidad
            munisipalidad:municipalidad memorandun:memorando memorandum:memorando dirijente:dirigente
            dirijentes:dirigentes contribullente:contribuyente contribullentes:contribuyentes
            correspondiete:correspondiente corespondiente:correspondiente corespondientes:correspondientes
            seguromente:seguramente rapidomente:rápidamente claromente:claramente obviomente:obviamente
            exactomente:exactamente completomente:completamente directomente:directamente nuevomente:nuevamente
            unicomente:únicamente lus:luz exceliente:excelente urgiente:urgente inteligiente:inteligente
            vinienos:vinimos fuinos:fuimos tuvinos:tuvimos hicinos:hicimos dijinos:dijimos pudinos:pudimos
            estuvinos:estuvimos quisinos:quisimos
        """

        private const val ERRORES_V10 = """
            hechando:echando hechó:echó atraves:a_través porsiaca:por_si_acaso hiban:iban beo:veo bibir:vivir
            nuebo:nuevo nueba:nueva llebas:llevas estubimos:estuvimos obio:obvio obia:obvia obiamente:obviamente
            ovbio:obvio nadamas:nada_más sertesa:certeza serteza:certeza sigiente:siguiente siguente:siguiente
            sigiendo:siguiendo pareser:parecer aparese:aparece aparesen:aparecen naser:nacer creser:crecer
            ofreser:ofrecer agradeser:agradecer agradesemos:agradecemos reconosco:reconozco alafirme:a_la_firme
            disque:dizque ezperar:esperar okupado:ocupado trabago:trabajo chamva:chamba comprame:cómprame
            vamonos:vámonos bamonos:vámonos yena:llena cabayo:caballo gayetas:galletas sebolla:cebolla
            senamos:cenamos sincuenta:cincuenta selu:celu senso:censo sigarro:cigarro sapato:zapato
            sapatos:zapatos sapatilla:zapatilla zerveza:cerveza serbeza:cerveza serveza:cerveza
        """

        private const val ERRORES_V9 = """
            porfa_vor:por_favor asique:así_que asiq:así_que porfin:por_fin osino:o_si_no enverdad:en_verdad alrrato:al_rato
            deveras:de_veras alfinal:al_final enfin:en_fin porlomenos:por_lo_menos porloque:por_lo_que porejemplo:por_ejemplo
            amenudo:a_menudo alavez:a_la_vez noma:nomás nomá:nomás ponido:puesto hacido:hecho volvido:vuelto morido:muerto
            veniste:viniste andé:anduve andó:anduvo andaron:anduvieron detení:detuve detenió:detuvo traducí:traduje
            tradució:tradujo producí:produje produció:produjo conducí:conduje condució:condujo satisfació:satisfizo
            preveer:prever preveyendo:previendo quedré:querré quedría:querría quedrías:querrías cabió:cupo cabí:cupe
            sabí:supe sabió:supo cocinastes:cocinaste pasastes:pasaste exselente:excelente ijas:hijas paresce:parece
            paresida:parecida bale:vale vuenas:buenas vuenos:buenos yamo:llamo ke:que ai:ahí alli:allí chebere:chévere
            guachafo:huachafo wachafo:huachafo guachafa:huachafa wachafa:huachafa guayno:huayno chivolo:chibolo
            chivola:chibola chivolos:chibolos pitucco:pituco picarrones:picarones aji:ají cauza:causa kausa:causa
            cachuello:cachuelo chamuyo:chamullo chancon:chancón grax:gracias graciaz:gracias muchisimas:muchísimas
            muchisimo:muchísimo muchisima:muchísima felisidades:felicidades cumpleaño:cumpleaños disculpame:discúlpame
            disculpeme:discúlpeme perdoname:perdóname perdon:perdón dejame:déjame
        """

        /**
         * Más errores de gramática (v9) que se ven con la palabra de al lado: "sino puedes" →
         * "si no puedes", "te baya bien" → "te vaya bien", "tu quieres" → "tú quieres",
         * "el sabe" → "él sabe", "a mi me" → "a mí me", "ojala que" → "ojalá que".
         */
        private const val CONFUSIONES_V9 = """
            sino puedo|si no+puedo; sino puedes|si no+puedes; sino puede|si no+puede; sino quieres|si no+quieres;
            sino quiere|si no+quiere; sino vienes|si no+vienes; sino viene|si no+viene; sino hay|si no+hay;
            sino tienes|si no+tienes; sino tiene|si no+tiene; sino sabes|si no+sabes; sino llegas|si no+llegas;
            sino contestas|si no+contestas; haz salido|has salido; te baya|te vaya; que baya|que vaya;
            me baya|me vaya; se baya|se vaya; no baya|no vaya; les baya|les vaya; le baya|le vaya;
            tu quieres|tú quieres; tu puedes|tú puedes; tu vas|tú vas; tu dices|tú dices; tu has|tú has;
            el tiene|él tiene; el quiere|él quiere; el sabe|él sabe; el fue|él fue; el estaba|él estaba;
            mi me|mí me; que bonita|qué bonita; que buena|qué buena; que gusto|qué gusto; que horror|qué horror;
            que lata|qué lata; yo valla|yo vaya; tu valla|tú vayas; ojala que|ojalá que; ojala te|ojalá te;
            ojala no|ojalá no; ojala se|ojalá se
        """

        private const val CONFUSIONES_MAS = """
            tubo que|tuvo que; tube que|tuve que; se callo|se cayó; halla sido|haya sido; halla hecho|haya hecho;
            halla llegado|haya llegado; halla pasado|haya pasado; halla venido|haya venido;
            halla terminado|haya terminado; halla salido|haya salido; halla visto|haya visto;
            halla dicho|haya dicho; aya sido|haya sido; aya hecho|haya hecho; aya llegado|haya llegado;
            aya pasado|haya pasado; aya venido|haya venido; aya terminado|haya terminado; aya salido|haya salido;
            aya visto|haya visto; aya dicho|haya dicho; te valla|te vaya; le valla|le vaya; les valla|les vaya;
            me valla|me vaya; nos valla|nos vaya; se valla|se vaya; valla bien|vaya bien; valla mal|vaya mal;
            vallas bien|vayas bien; vallan a|vayan a; as echo|has hecho; an echo|han hecho; de echo|de hecho;
            el echo|el hecho; un echo|un hecho; los echos|los hechos; echo realidad|hecho realidad;
            haz visto|has visto; haz hecho|has hecho; haz ido|has ido; haz llegado|has llegado;
            haz comido|has comido; haz dicho|has dicho; haz terminado|has terminado; haz pensado|has pensado;
            haz escuchado|has escuchado; haz estado|has estado; haz sido|has sido; haz tenido|has tenido;
            haz podido|has podido; haz leido|has leído; haz dormido|has dormido; haz trabajado|has trabajado;
            haz recibido|has recibido; haz enviado|has enviado; has caso|haz caso; ha ser|a ser;
            ha hacer|a hacer; ha dar|a dar; ha comer|a comer; ha llegar|a llegar; ha salir|a salir;
            ha decir|a decir; ha trabajar|a trabajar; ha estudiar|a estudiar; ha jugar|a jugar;
            ha dormir|a dormir; ha buscar|a buscar; ha mi|a mí; ha ti|a ti; ha usted|a usted;
            ha ustedes|a ustedes; ha veces|a veces; ha pesar|a pesar; ha partir|a partir; ha tiempo|a tiempo;
            debe aver|debe haber; debería aver|debería haber; deberia aver|debería haber; puede aver|puede haber;
            podría aver|podría haber; podria aver|podría haber; suele aver|suele haber; aver si|a ver+si;
            aver que|a ver+qué; aver cuando|a ver+cuándo; aver como|a ver+cómo; aver donde|a ver+dónde;
            aver quien|a ver+quién; aver dime|a ver+dime; aver muestra|a ver+muestra; aver pasa|a ver+pasa;
            a lado|al lado; si mismo|sí mismo; si misma|sí misma; hay dios|ay+Dios; hay no|ay no;
            ay esta|ahí está; ay estoy|ahí estoy; ay voy|ahí voy; hay te|ahí te; hay le|ahí le; ay te|ahí te;
            ay le|ahí le; ahí que|hay que; ahi que|hay que; se a|se ha; se an|se han; me e|me he; se e|se he;
            la e|la he; lo as|lo has; la as|la has; lo an|lo han; la an|la han; ya e|ya he; ya as|ya has;
            ya an|ya han; nunca e|nunca he; aún así|aun así; aun asi|aun así; otra bez|otra vez; tal bez|tal vez;
            una bez|una vez; voy aser|voy+a hacer; vas aser|vas+a hacer; va aser|va+a hacer;
            vamos aser|vamos+a hacer; van aser|van+a hacer; ahí boy|ahí voy; nos bemos|nos vemos;
            nos bamos|nos vamos; hechar de|echar de; hechar la|echar la; abía una|había una; abia una|había una;
            avía una|había una; havia una|había una; ola amiga|hola amiga; ola amigo|hola amigo;
            ola chicos|hola chicos
        """

        /** Largo máximo de una frase en la barra (para que se lea sin cortarse) */
        private const val MAX_FRASE = 26
        /** Diferencia mínima de puntaje entre la mejor y la segunda para corregir sola */
        private const val MARGEN_DUDA = 0.3f
        /** Ventaja de la palabra a la que solo le faltó la última letra */
        private const val FALTO_ULTIMA = 0.55f

        /**
         * Frases hechas para completar la oración. De cada frase sale, para cada par de
         * palabras seguidas, lo que viene después: "muchas gracias por todo" →
         * "muchas gracias" → [por, todo] y "gracias por" → [todo]. Gana la primera que lo define.
         */
        private val FRASES: Map<String, List<String>> = HashMap<String, List<String>>().apply {
            for (linea in """
            que tengas un buen día; que tengas una buena noche; que tengas un lindo día; que te vaya bien;
            que te mejores pronto; que descanses mucho; nos vemos mañana; te quiero mucho; te extraño mucho;
            te amo mucho; te llamo más tarde; te escribo más tarde; te aviso cualquier cosa; te paso la voz;
            te mando la ubicación; cualquier cosa me avisas; en cuanto pueda te aviso; en cuanto llegue te aviso;
            lo antes posible; lo más pronto posible; muchas gracias por todo; gracias por todo; gracias por avisar;
            gracias por tu tiempo; gracias por tu ayuda; feliz cumpleaños; buen provecho; estoy en camino;
            ya estoy llegando; ya estoy saliendo; ya voy para allá; ahorita no puedo; no te preocupes; no hay problema;
            no pasa nada; claro que sí; por supuesto que sí; quedo atento a su respuesta; quedo atenta a su respuesta;
            quedo a la espera de su respuesta; quedo a su disposición; reciba un cordial saludo;
            por medio del presente documento; tengo el agrado de dirigirme a usted; sin otro particular;
            agradezco de antemano su atención; en atención a lo solicitado; a la brevedad posible; hasta luego;
            cuídate mucho; un abrazo fuerte; un beso grande; dios te bendiga; cómo te fue; a qué hora llegas;
            a qué hora sales; estoy en la casa; estoy en el trabajo; de vez en cuando; al fin y al cabo; por lo menos;
            por si acaso; todo bien por aquí; llego en un rato; llego en unos minutos; en un momento te aviso;
            ahí nos vemos; ahí te aviso; avísame cuando llegues; avísame cuando puedas; llámame cuando puedas;
            escríbeme cuando puedas; me avisas cualquier cosa; buenas noches que descanses; hola buenos días;
            hola buenas tardes; hola buenas noches; buenos días cómo estás; mil gracias por todo; disculpa la demora;
            disculpa la hora; disculpe la demora en responder; perdón por la demora; me avisas cuando puedas;
            te espero aquí; ya estoy aquí; ya llegué a la casa; feliz día de la madre; feliz día del padre;
            feliz año nuevo; feliz navidad para todos; buen viaje; salgo en un rato;
            estoy ocupado ahorita; estoy ocupada ahorita; sigo en el trabajo; ya salí del trabajo; mañana te confirmo;
            luego te cuento; después te cuento; te cuento después;
            ya llegué a la casa; ya estoy en camino; ya estoy en la casa; ya estoy saliendo de la casa;
            voy saliendo en un rato; llego en diez minutos; estoy en una reunión; estoy en clases;
            te llamo en un rato; te llamo apenas salga; te escribo apenas llegue; te aviso apenas pueda;
            no te olvides de avisarme; no te preocupes por eso; no hay apuro; todo bien gracias;
            todo tranquilo por aquí; qué tal tu día; cómo te fue en el trabajo; cómo sigues de salud;
            ya comiste; qué vas a comer hoy; vamos a almorzar; nos vemos el fin de semana;
            que tengas buen fin de semana; buen fin de semana; feliz fin de semana; que te diviertas;
            mucho éxito en todo; te deseo lo mejor; cuenta conmigo; aquí estoy para lo que necesites;
            gracias por la información; gracias por la confirmación; gracias por la paciencia;
            disculpa no vi tu mensaje; recién veo tu mensaje; perdón recién veo; te leo en un rato;
            mándame la ubicación; mándame la foto; pásame el número; pásame el dato;
            sí claro no hay problema; claro que sí con gusto; con mucho gusto; de nada para eso estamos;
            se adjunta el documento para su revisión; adjunto el informe para su conocimiento;
            para su conocimiento y fines pertinentes; quedo atento a sus comentarios;
            agradeceré su pronta respuesta; en relación al documento de la referencia;
            se solicita su atención al presente; sin otro particular me despido;
            buenos días estimado colega; buenas tardes estimados colegas; saludos cordiales
            """.trim().split(";")) {
                val w = linea.trim().split(ESPACIOS).filter { it.isNotEmpty() }
                for (k in 2 until w.size) {
                    val clave = sinTildes(w[k - 2]) + " " + sinTildes(w[k - 1])
                    if (!containsKey(clave)) put(clave, w.subList(k, w.size).toList())
                }
            }
        }

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

        // ---------- Más Perú (versión 7) ----------

        /** Más jerga peruana del día a día: barrio, chat, familia y verbos bien nuestros */
        /**
         * Modismos de toda Latinoamérica y España, y el "spanglish" de redes: México (neta, chido,
         * órale), Colombia (parcero, bacano, vaina), Argentina (boludo, laburo, quilombo), Chile
         * (pololo, carrete, fome), España (guay, mola, flipar) y redes (stalkear, ghostear, outfit).
         * Se respetan al escribirlas (no se "corrigen"). Sin insultos ni palabras vulgares.
         */
        private const val JERGA_MUNDO = """
            guey netas chida chidos chidas padrísimo padrísima órale híjole chamaca chamacos morro morras morros
            cuates carnal carnala cheve cheves chafa chafas parcera parceros bacana bacanos berraca berracos
            guaro pola polas rumba rumbear rumbeando parche parchar parchado vaina vainas boluda boludos boludez
            pibes pibas laburando laburos quilombo quilombos copado copada copados bondi birra birras chabón
            chabona chamuyo chamuyar morfar morfi fiaca guita trucho bancar bancame fomes pololos pololear
            carrete carretear guaguas cuático cuática filete guay guays mola molan mogollón flipar flipo flipas
            flipante tronca curro currar currando mazo chaval chavala chavales majo maja crushes randoms
            influencers streamers gamers ghostear ghosteo ghosteó outfits looks matches tiktoker tiktokers
            youtuber youtubers instagramer trends viral hashtag hashtags xdd ksyo besti
        """

        private const val JERGA_MAS = """
            malogrado malograda malogrados malogradas malograr malogra malogran malogró malogré
            malograste malograron malogrando malograrse malogrón huachimán huachimanes guachimán
            yapear yapeo yapeas yapea yapeamos yapean yapeé yapeó yapeaste yapéame yapeame yapéale
            yapeale plinear plineo plinea plineé plineó plinéame plineame plinéale
            fono fonito celu celular germa germita chongo chongos chongazo chongón chongona
            mancar mancado mancada mancó mancaste mancaron pasumare pasumadre puchica puchamadre
            asumadre joya joyita joyaza monstruo monstruazo terrible terribles huasca huasquiado
            huasqueado huasqueada nave naves navecita cuete cuetazo chupetín chupetines tajador
            carpeta carpetas cachito cachitos ñañito ñañita cuñao cuñis cuñadito cuñadita sobri
            primito primita primazo suegri seño señito compañerito compañerita maridito esposita
            gordo gorda gordito gordita gordis flaquis bebito bebita amorcito amorcita mor
            corazoncito cielito princesa reina papi mami papis mamis viejita viejito
            achorarse achorao apanado apanada apanar apanaron apanado pechar pecha pechó pechada
            cuadrar cuadra cuadró cuadrado cuadrarse fregar friega friegas fregado fregada fregó
            piconear picón picona piconazo picona renegar reniega reniegas renegó renegón renegona
            atorrante atorrantes pavo pava paveo apurar apúrate apurate apura aguanta aguántame
            aguantame aguantar chévere cheverísimo bacanazo bacanaza ojito ojitos tranquilazo
            relajado relajada relajo relajito pendejeo palomilla palomillas palomillada travieso
            traviesa churro churra churrazo guapo guapa guapetón guapetona guapachoso pitazo
            pitear pitea pitearon pucho puchos fumar tono tonazo chupódromo tragos chelita
            jarana jaranero jaranera jaranear huayno huaynito cumbia cumbión chicha festejo
            marinera polka vals criollo criolla criollada cajón peña peñas
            chamba chambita chambón chambona chancón chamberos chambero chambera
            habla hablaste hablamos bróder broder hermanito causita mancha manchita pata patita
            yala yalas yapo yapa yapita ñatito plata platita billete billetes billetón
            sencillito tarjetita cuenta cuentita deposito depósito transferencia transferí
            transferir transfiéreme transfiereme depósitame depositame
            micro combi combis combi colectivo colectivero mototaxi mototaxista bici bicis
            paradero paraderos cobrador cobradora chofer choferes jirón cuadra cuadras esquina
            pasaje pasajes pasajero pasajera tráfico trafico tráfico atoro atorado atorada
            carro carros carrito taxi taxis taxista delivery
            chibolada chiboladas chiboleo jatearse jateando jateo
            bacilar vacilando vacilón quemado quemada quemadazo roche rochazo roches
            palta paltazo paltas paltear palteado palteada
            ampay misio misios chancar chanqué chancando floro florero
            bamba cachina cachinero mercadillo galería galerías emporio
            imbécil imbéciles idiota idiotas estúpido estúpida estúpidos estúpidas tarado tarada
            tarados baboso babosa babosos cachudo cachuda cachudos pezuñento pezuñenta sacavuelta
            sacavueltas sacavueltero sacavueltera mamarracho mamarrachos lacra lacras
            """

        /**
         * Lisuras peruanas. Se conocen (el teclado nunca te las cambia por otra palabra), pero no se
         * sugieren ni se corrige hacia ellas hasta que tú las uses (ver [lisuraAjena]).
         */
        private const val LISURAS_TXT = """
            huevón huevona huevones huevonas huevonazo huevonaza huevonada huevonadas huevada
            huevadas huevear hueveando huevea hueveas huevas ahuevado ahuevada ahuevados ahuevonado
            ahuevonada webón webona webones webonas webada webadas weón weona weones
            cojudo cojuda cojudos cojudas cojudazo cojudaza cojudez cojudeces cojudear cojudeando
            acojudado acojudada conchatumadre conchatumare conchasumadre conchesumare conchesumadre
            conchuda conchudazo conchudaza csm ctm ptm hdp ptmr mrd
            carajo carajos carajazo carajete mierda mierdas mierdita mierdoso mierdosa
            puta puto putas putos putamadre putamare putear puteada puteadas puteando puteó puteaste
            pendejo pendeja pendejos pendejas pendejada pendejadas pendejear pendejeando pendejazo
            chucha chuchasumare chuchatumare chuchasumadre rechucha
            cagada cagadas cagado cagados cagar cagué cagaste cagó cagón cagona cagones cagazo cagarla
            jodido jodida jodidos jodidas jode jodes jodiste jodió jodiendo jódete jodete
            """

        /** Palabras del Estado peruano: trámites, documentos, contrataciones y gestión pública */
        private const val SECTOR_PUBLICO = """
            expediente expedientes oficio oficios memorando memorandos memorándum informe informes
            resolución resoluciones directoral directorales ministerial ministeriales jefatural
            gerencial gerenciales ejecutiva ejecutivo administrativa administrativo administrativos
            decreto decretos supremo legislativo ordenanza ordenanzas acuerdo acuerdos trámite
            trámites tramitar tramitando tramitado tramitación locación locador locadora servicios
            requerimiento requerimientos referencia términos certificación presupuestal presupuesto
            presupuestos devengado devengados girado viáticos rendición conformidad conformidades
            usuaria logística abastecimiento tesorería contabilidad planilla planillas boleta boletas
            honorarios constancia constancias certificado certificados notificación notificaciones
            notificar notificado notificada apelación apelaciones reconsideración recurso recursos
            procedimiento procedimientos plazo plazos hábiles hábil subsanar subsanación subsanado
            subsanada observación observaciones observado observada proveído despacho gerencia
            gerencias subgerencia subgerencias jefatura jefaturas dirección direcciones oficina
            unidad orgánica órgano servidor servidora servidores funcionario funcionaria funcionarios
            autoridad autoridades alcalde alcaldesa alcaldía regidor regidora regidores gobernador
            gobernadora prefecto prefectura subprefecto congresista congresistas congreso fiscal
            fiscalía fiscales juzgado juzgados judicial denuncia denuncias comisaría licencia
            licencias funcionamiento arbitrios predial autovalúo impuesto impuestos tributo tributos
            tributario tributaria fraccionamiento multa multas papeleta papeletas brevete pasaporte
            migraciones padrón elecciones personero personera nombramiento nombrado nombrada
            contratado contratada docente docentes convocatoria convocatorias bases postor postores
            postulante postulantes adjudicación adjudicado contratación contrataciones licitación
            licitaciones concurso concursos adenda adendas penalidad penalidades garantía cotización
            cotizaciones proforma proformas proveedor proveedores entregable entregables cronograma
            valorización valorizaciones liquidación obra obras supervisor supervisora residente
            municipalidad municipalidades municipal municipales distrital distritales provincial
            provinciales regional regionales sectorial ministerio ministerios viceministerio
            viceministro viceministra ministra ministro gobierno estatal entidad entidades públicos
            públicas institución institucional dependencia dependencias sede sedes ventanilla
            ciudadano ciudadana ciudadanos administrado administrada administrados solicitante
            solicitud solicitudes solicito solicita formulario formato formatos anexo anexos folio
            folios foliado foliada sello sellos firmado firmada visado visación rubricado acta actas
            derivar derivado derivada derivó archivar archivado elevar elevado remitir remito remite
            remitido remitida adjunto adjunta adjuntos adjuntar dirigirme dirijo cordialmente
            atentamente pertinente pertinentes asimismo mediante cumplimiento disposición
            disposiciones normativa normativas reglamento reglamentos directiva directivas
            lineamientos protocolo protocolos competencia competente viabilidad sustento sustentar
            sustentado justificación vigente vigencia disponibilidad meta metas indicador indicadores
            pliego programación ejecución gasto gastos inversión inversiones proyecto proyectos
            componente actividad actividades capacitación capacitaciones taller talleres comisión
            comisiones comité comités sesión sesiones quórum votación agenda técnico técnica legal
            asesoría asesor asesora asesores procurador procuraduría auditoría auditor contraloría
            defensoría transparencia acceso inciso incisos numeral literal considerando
            considerandos resuelve regístrese comuníquese publíquese cúmplase archívese
            nombramiento ascenso escalafón legajo legajos vacaciones licencia permiso permisos
            descanso médico subsidio bonificación aguinaldo gratificación cts remuneración
            remuneraciones sueldo sueldos haber haberes descuento descuentos retención retenciones
            """

        /** Comida, lugares y cosas muy peruanas */
        private const val COMIDA_LUGARES = """
            huancaína ají gallina lomo saltado rocoto relleno tacu carapulcra pachamanca juane tamal
            tamales humita humitas morada mazamorra suspiro limeña turrón papa rellena cau seco
            cabrito tiradito leche tigre jalea butifarra salchipollo broaster brasa mostrito
            chicharrón chilcano pisco sour causa limeña olluquito ollucos sopa menestrón aguadito
            chupe chupe camarones arroz chaufa aeropuerto sanguche alfajor alfajores king kong
            picarones queque keke empanada empanadas pan chancay tostadas emoliente maca quinua
            kiwicha cancha choclo chocho cebiche ceviche leche tigre aguaymanto lúcuma chirimoya
            granadilla tuna pacay camu camu cocona aguaje
            serranía selva sierra costa provincia provincias distrito distritos asentamiento
            humano pueblo joven pueblos jóvenes urbanización urbanizaciones centro poblado caserío
            anexo comunidad campesina nativa
            """

        /**
         * Nombres con su forma exacta (siglas, instituciones, lugares y marcas). Al escribirlos en
         * minúsculas se corrigen solos: "sunat" → "SUNAT", "essalud" → "EsSalud".
         * (Solo nombres que no se confunden con una palabra común: por eso no están Lima, Surco o Tumbes.)
         */
        private const val FORMAS_TXT = """
            SUNAT RENIEC EsSalud DNI RUC SOAT AFP ONP PNP SUNARP SUNAFIL SUNEDU Indecopi Osinergmin
            Osiptel Minsa Minedu MTC MEF UGEL ONPE JNE SEACE OSCE SIAF TUPA TDR Pronabec SAT INPE INEI
            Senamhi Sedapal ITSE PCM Mininter MIMP Midis Sunass Digesa SIS Qali Warma
            Perú Arequipa Cusco Trujillo Chiclayo Piura Iquitos Huancayo Tacna Puno Callao Ayacucho
            Cajamarca Huánuco Pucallpa Moquegua Huaraz Tarapoto Juliaca Chimbote Chincha Miraflores
            Chorrillos Rímac Carabayllo Pachacámac Lurín Gamarra Larcomar Surquillo Ica Paracas
            Huacachina Máncora Titicaca Kuélap Chachapoyas Machu Picchu Nasca Ucayali Loreto Lambayeque
            Apurímac Huancavelica Amazonas
            WhatsApp Facebook Instagram YouTube TikTok Yape Plin BCP Interbank BBVA Scotiabank Netflix
            Google Gmail Rappi Uber inDrive Cabify Spotify Telegram Messenger Movistar Entel Bitel
            Falabella Ripley Oechsle Tottus Wong Promart Sodimac Inkafarma Mifarma
            """

        /** Dónde entran (en el orden de frecuencia) las palabras de [MEDIO] */
        private const val POS_MEDIO = 12_000
        private val MEDIO = (SECTOR_PUBLICO + " " + COMIDA_LUGARES + " " + FORMAS_TXT + " " + LISURAS_TXT).trim()

        private val LISURAS: HashSet<String> = HashSet<String>().apply {
            for (w in LISURAS_TXT.trim().split(ESPACIOS)) { add(w.lowercase()); add(sinTildes(w)) }
        }

        /** Cómo empiezan los mensajes formales (correo, trámites) */
        private val INICIO_FORMAL = listOf(
            "estimado", "estimada", "buenos", "buenas", "quisiera", "le", "les", "por", "mediante",
            "hola", "agradeceré", "solicito", "cordiales", "saludos", "adjunto", "en",
        )

        /** Jerga del chat que en modo formal casi no se sugiere */
        private val JERGA_CHAT: HashSet<String> = HashSet<String>().apply {
            for (w in """
                causa causita pe pes ps oe bro broder bróder brother pata patita patas yunta choche
                mano manito jato jerma flaco flaca chibolo chibola chamba chambear chambita misio luca
                lucas palta paltazo roche piña bacán bacanazo chévere mostro bravazo buenazo paja asu
                mare pucha puchamare alucina xd jaja jajaja jajajaja jeje jsjs chela chelas tono tonazo
                manyas manyar jalar jalado floro yapa ya_fue firme fresh tranqui gil sapo tombo pituco
                huachafo achorado mancha jama jamear huarique pichanga cachuelo gorrear zampar telo
                calato chape chapar encamotado vacilar vacilón jugador caleta pulenta cheverazo yala
                oki okey okis holi holis grax porfa porfis xfa ntp tqm tkm chau chao besos besito
            """.trim().split(ESPACIOS)) add(sinTildes(w))
        }

        private val FORMAS: Map<String, String> = FORMAS_TXT.trim().split(ESPACIOS).associateBy { sinTildes(it) }
    }
}
