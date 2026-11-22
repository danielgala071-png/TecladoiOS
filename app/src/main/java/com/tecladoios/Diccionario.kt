package com.tecladoios

import android.content.Context
import kotlin.math.ln

/**
 * Diccionario de español latinoamericano (~158 000 palabras ordenadas de la más usada a la
 * menos usada) y el corrector, pensado como el del iPhone:
 * - Corrige solo palabras que NO existen y solo cuando está seguro.
 * - Pone las tildes que faltan (tambien → también).
 * - Tiene en cuenta las teclas vecinas (es más fácil tocar la de al lado).
 * - Aprende las palabras que rechazas al corregir (las que eliges entre comillas).
 *
 * Todo vive en un bloque de bytes y dos listas de números (sin un texto por palabra),
 * así ocupa unos 3 MB.
 */
class Diccionario private constructor(
    /** Palabras por frecuencia (el índice es el puesto: 0 = la más usada) */
    private val palabras: Lista,
    /** Las mismas palabras vistas sin tildes, en orden alfabético (sin copiarlas: solo el orden) */
    private val claves: Claves,
    /** Carpeta donde se guarda lo que aprende de ti */
    private val dir: java.io.File,
) {

    /**
     * Las palabras tal como vienen en el archivo, en un solo bloque de bytes (una por línea):
     * no se crea ni un texto por palabra, así ocupa lo mínimo.
     */
    class Lista(val datos: ByteArray, val ini: IntArray) {
        val size get() = ini.size - 1
        fun fin(i: Int) = ini[i + 1] - 1            // sin el salto de línea
        operator fun get(i: Int) = String(datos, ini[i], fin(i) - ini[i], Charsets.UTF_8)
    }

    /**
     * Las palabras ordenadas sin tildes, para buscarlas. No guarda una segunda copia: compara
     * leyendo los bytes de [palabras] y tratando á, é, í, ó, ú, ü como a, e, i, o, u al vuelo.
     */
    class Claves(private val p: Lista, private val puestos: IntArray) {
        val size get() = puestos.size
        fun puesto(i: Int) = puestos[i]

        private fun vocal(c: Int) = when (c) {
            0xA0, 0xA1, 0xA2, 0xA4 -> 'a'.code; 0xA8, 0xA9, 0xAA, 0xAB -> 'e'.code
            0xAC, 0xAD, 0xAE, 0xAF -> 'i'.code; 0xB2, 0xB3, 0xB4, 0xB6 -> 'o'.code
            0xB9, 0xBA, 0xBB, 0xBC -> 'u'.code; else -> 0
        }

        /** Compara la palabra [i] (sin tildes) con [k]; con [prefijo], 0 si empieza con [k] */
        fun comparar(i: Int, k: ByteArray, prefijo: Boolean = false): Int {
            val pp = puestos[i]; val d = p.datos
            var j = p.ini[pp]; val fin = p.fin(pp); var q = 0
            while (j < fin && q < k.size) {
                var b = d[j].toInt() and 0xFF; var paso = 1
                if (b == 0xC3 && j + 1 < fin) { val v = vocal(d[j + 1].toInt() and 0xFF); if (v != 0) { b = v; paso = 2 } }
                val y = k[q].toInt() and 0xFF
                if (b != y) return b - y
                j += paso; q++
            }
            if (q == k.size) return if (j >= fin || prefijo) 0 else 1
            return -1
        }
        fun empieza(i: Int, k: ByteArray) = comparar(i, k, prefijo = true) == 0

        /** La palabra [i] sin tildes copiada en [buf]; devuelve cuántos bytes ocupa */
        fun normal(i: Int, buf: ByteArray): Int {
            val pp = puestos[i]; val d = p.datos
            var j = p.ini[pp]; val fin = p.fin(pp); var n = 0
            while (j < fin && n < buf.size) {
                var b = d[j].toInt() and 0xFF; var paso = 1
                if (b == 0xC3 && j + 1 < fin) { val v = vocal(d[j + 1].toInt() and 0xFF); if (v != 0) { b = v; paso = 2 } }
                buf[n++] = b.toByte(); j += paso
            }
            return n
        }
        fun largo(i: Int, buf: ByteArray) = normal(i, buf)
    }

    /** Lo que el teclado propone para la palabra que escribes */
    class Propuesta(
        val escrito: String,
        /** Corrección que se pondrá sola al tocar espacio (null = ninguna) */
        val correccion: String?,
        /** Otras opciones para la barra */
        val opciones: List<String>,
    )

    companion object {
        const val PROPIAS = "palabras_propias"
        const val USOS = "palabras_usos"
        const val PARES = "palabras_pares"
        const val TRIOS = "palabras_trios"
        const val NOMBRES = "palabras_nombres"
        const val OLVIDADAS = "palabras_olvidadas"
        /** Índice de una letra en [ALFABETO] (para los costos según dónde tocaste) */
        fun indiceLetra(c: Char) = ALFABETO.indexOf(c)

        /** Lo que suele seguir a las palabras más comunes (cuando aún no aprendió tu forma de escribir) */
        private val COMUNES = mapOf(
            "buenos" to listOf("días"), "buenas" to listOf("noches", "tardes"), "muchas" to listOf("gracias"),
            "por" to listOf("favor", "qué", "eso"), "hola" to listOf("cómo", "qué", "buenos"),
            "cómo" to listOf("estás", "te", "así"), "qué" to listOf("tal", "haces", "pasó"), "estoy" to listOf("bien", "en", "llegando"),
            "te" to listOf("quiero", "amo", "extraño"), "no" to listOf("sé", "hay", "puedo"), "lo" to listOf("que", "siento", "sé"),
            "nos" to listOf("vemos", "vamos"), "hasta" to listOf("luego", "mañana", "pronto"), "a" to listOf("la", "ver", "qué"),
            "ya" to listOf("voy", "llegué", "está"), "voy" to listOf("a", "en", "llegando"), "tengo" to listOf("que", "un", "una"),
            "me" to listOf("voy", "gusta", "dijo"), "un" to listOf("momento", "poco", "rato"), "feliz" to listOf("cumpleaños", "día", "año"),
            "gracias" to listOf("por", "a", "amor"), "de" to listOf("la", "nada", "verdad"), "que" to listOf("no", "te", "es"),
            "en" to listOf("la", "casa", "el"), "para" to listOf("que", "el", "la"), "con" to listOf("el", "la", "mi"),
            "todo" to listOf("bien", "el", "lo"), "está" to listOf("bien", "en", "muy"), "es" to listOf("que", "un", "muy"),
            "mi" to listOf("amor", "casa", "mamá"), "dónde" to listOf("estás", "está", "vives"), "cuándo" to listOf("vienes", "llegas", "nos"),
            "bien" to listOf("y", "gracias", "y tú"), "y" to listOf("tú", "que", "el"), "ok" to listOf("gracias", "listo", "dale"),
            "sí" to listOf("claro", "ya", "señor"), "claro" to listOf("que", "sí", "no"), "vamos" to listOf("a", "al", "pues"),
            "dale" to listOf("gracias", "nos", "ya"), "listo" to listOf("gracias", "ya", "nos"),
        )
        const val ALFABETO = "abcdefghijklmnñopqrstuvwxyz"

        fun cargar(c: Context): Diccionario {
            // El archivo entero queda en un solo bloque; solo se anota dónde empieza cada palabra
            val datos = c.assets.open("palabras.txt").use { it.readBytes() }
            var n = 0
            for (b in datos) if (b == '\n'.code.toByte()) n++
            val ini = IntArray(n + 1)
            var k = 0
            ini[0] = 0
            for (i in datos.indices) if (datos[i] == '\n'.code.toByte()) { k++; ini[k] = i + 1 }
            val palabras = Lista(datos, ini)
            // El orden alfabético viene ya calculado (orden.bin): el teclado arranca sin ordenar nada
            val puestos = leerOrden(c, datos, n) ?: ordenar(palabras)
            val dir = java.io.File(c.filesDir, "aprendido").apply { mkdirs() }
            migrarDeAjustes(c, dir)
            return Diccionario(palabras, Claves(palabras, puestos), dir)
        }

        private fun leerOrden(c: Context, datos: ByteArray, n: Int): IntArray? = try {
            java.io.DataInputStream(java.io.BufferedInputStream(c.assets.open("orden.bin"))).use { f ->
                if (f.readInt() != 0x4F524431) return null           // "ORD1"
                if (f.readInt() != n) return null
                val crc = java.util.zip.CRC32().apply { update(datos) }.value.toInt()
                if (f.readInt() != crc) return null                   // la lista cambió: se ordena aquí
                IntArray(n) { f.readInt() }
            }
        } catch (_: Throwable) { null }

        /** Solo si orden.bin no corresponde a la lista: se ordena al cargar (más lento) */
        private fun ordenar(p: Lista): IntArray {
            val norm = Array(p.size) { normal(p[it]).toByteArray(Charsets.UTF_8) }
            val idx = (0 until p.size).sortedWith { a, b ->
                val x = norm[a]; val y = norm[b]; val m = minOf(x.size, y.size)
                var r = 0
                for (j in 0 until m) { val d = (x[j].toInt() and 0xFF) - (y[j].toInt() and 0xFF); if (d != 0) { r = d; break } }
                if (r == 0) r = x.size - y.size
                if (r != 0) r else a - b
            }
            return idx.toIntArray()
        }

        // ---------- Lo aprendido, en archivos (no en los ajustes: así no queda copiado en memoria) ----------
        fun leer(dir: java.io.File, nombre: String): List<String> = try {
            java.io.File(dir, "$nombre.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (_: Throwable) { emptyList() }

        fun escribir(dir: java.io.File, nombre: String, lineas: Collection<String>) {
            try {
                val tmp = java.io.File(dir, "$nombre.tmp")
                tmp.writeText(lineas.joinToString("\n"))
                tmp.renameTo(java.io.File(dir, "$nombre.txt"))
            } catch (_: Throwable) { }
        }

        fun carpeta(c: Context) = java.io.File(c.filesDir, "aprendido").apply { mkdirs() }

        /** Versiones anteriores lo guardaban en los ajustes: se pasa a archivos una sola vez */
        private fun migrarDeAjustes(c: Context, dir: java.io.File) {
            val p = Ajustes.prefs(c)
            val e = p.edit(); var hay = false
            for (k in listOf(PROPIAS, USOS, PARES, TRIOS, NOMBRES)) {
                val v = p.getString(k, null) ?: continue
                escribir(dir, k, v.split('\n').filter { it.isNotEmpty() }); e.remove(k); hay = true
            }
            if (hay) e.apply()
        }

        /** Minúsculas y sin tildes (la ñ se queda) */
        fun normal(s: CharSequence): String {
            val sb = StringBuilder(s.length)
            for (ch in s) sb.append(when (val c = ch.lowercaseChar()) {
                'á', 'à', 'â', 'ä' -> 'a'; 'é', 'è', 'ê', 'ë' -> 'e'; 'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'ô', 'ö' -> 'o'; 'ú', 'ù', 'û', 'ü' -> 'u'; else -> c
            })
            return sb.toString()
        }

        // Posición de cada letra en el teclado (para saber cuáles son vecinas)
        private val POS: Map<Char, Pair<Float, Float>> = HashMap<Char, Pair<Float, Float>>().apply {
            "qwertyuiop".forEachIndexed { i, c -> put(c, i.toFloat() to 0f) }
            "asdfghjklñ".forEachIndexed { i, c -> put(c, i + 0.5f to 1f) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1.5f to 2f) }
        }

        private fun vecinas(a: Char, b: Char): Boolean {
            val p = POS[a] ?: return false; val q = POS[b] ?: return false
            return kotlin.math.abs(p.first - q.first) <= 1.1f && kotlin.math.abs(p.second - q.second) <= 1f
        }

        // Letras que suenan igual y se confunden al escribir (s/c/z, b/v, g/j, y/i)
        private val SUENAN = setOf("sc", "cs", "sz", "zs", "cz", "zc", "bv", "vb", "gj", "jg", "yi", "iy")

        private fun costoCambio(a: Char, b: Char): Float = when {
            (a == 'n' && b == 'ñ') || (a == 'ñ' && b == 'n') -> 0.3f
            "$a$b" in SUENAN -> 0.5f
            vecinas(a, b) -> 0.55f
            else -> 1f
        }

        /** Abreviaturas de chat: la palabra completa aparece primero en la barra (no se cambia sola) */
        private val ABREVIATURAS = mapOf(
            "q" to "que", "k" to "que", "xq" to "porque", "pq" to "porque", "porq" to "porque",
            "tb" to "también", "tmb" to "también", "tbn" to "también", "x" to "por", "xfa" to "por favor",
            "pls" to "por favor", "dnd" to "dónde", "ntp" to "no te preocupes", "bn" to "bien", "msj" to "mensaje",
            "mñn" to "mañana", "mñna" to "mañana", "tqm" to "te quiero mucho", "ps" to "pues", "d" to "de",
            "toy" to "estoy", "tas" to "estás", "ta" to "está", "nd" to "nada", "dps" to "después",
            "grax" to "gracias", "salu" to "saludos", "cel" to "celular",
            "aprox" to "aproximadamente", "porfa" to "por favor", "hno" to "hermano", "hna" to "hermana", "qtal" to "qué tal", "qhaces" to "qué haces",
        )

        /** Palabras que se escriben juntas pero van separadas (como corrige el iPhone) */
        private val JUNTAS = mapOf(
            "aver" to "a ver", "osea" to "o sea", "talvez" to "tal vez", "enserio" to "en serio",
            "porfavor" to "por favor", "atravez" to "a través", "atraves" to "a través", "nose" to "no sé",
            "almenos" to "al menos", "sinembargo" to "sin embargo", "envez" to "en vez", "derepente" to "de repente",
            "aveces" to "a veces", "apesar" to "a pesar", "enrealidad" to "en realidad", "deverdad" to "de verdad",
            "porsupuesto" to "por supuesto", "alrato" to "al rato", "enfin" to "en fin", "deveras" to "de veras",
            "porlomenos" to "por lo menos", "loque" to "lo que", "esque" to "es que",
            "yaque" to "ya que", "asique" to "así que", "almismo" to "al mismo", "derrepente" to "de repente",
            "atiempo" to "a tiempo", "alfinal" to "al final", "porahora" to "por ahora",
        )
    }

    // ---------- Palabras propias (las que aprendes) ----------
    private val propias = java.util.concurrent.ConcurrentHashMap<String, String>()   // clave → palabra
    init {
        leer(dir, PROPIAS).forEach { propias[normal(it)] = it }
    }

    // ---------- Lo que aprende de cómo escribes (solo en el celular) ----------
    /** Cuántas veces usaste cada palabra (las 3000 más usadas) */
    private val usos = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Qué palabra sueles poner después de cada palabra */
    private val conteoPares = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Tres palabras seguidas que escribes ("nos vemos mañana") */
    private val conteoTrios = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Nombres que escribiste con mayúscula (maría → María) */
    private val nombres = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var sinGuardar = 0
    /** Palabras que pediste olvidar: no se sugieren ni se ponen solas */
    private val olvidadas: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        leer(dir, USOS).forEach { l ->
            val i = l.lastIndexOf(':'); if (i > 0) l.substring(i + 1).toIntOrNull()?.let { usos[l.substring(0, i)] = it }
        }
        leer(dir, PARES).forEach { l ->
            val i = l.lastIndexOf(':'); if (i > 0) l.substring(i + 1).toIntOrNull()?.let { conteoPares[l.substring(0, i)] = it }
        }
        leer(dir, TRIOS).forEach { l ->
            val i = l.lastIndexOf(':'); if (i > 0) l.substring(i + 1).toIntOrNull()?.let { conteoTrios[l.substring(0, i)] = it }
        }
        leer(dir, NOMBRES).forEach { if (it.length >= 2) nombres[it.lowercase()] = it }
        olvidadas.addAll(leer(dir, OLVIDADAS))
    }

    /** Olvidar una palabra (mantener presionada en la barra): sale de lo aprendido y no se vuelve a sugerir */
    fun olvidar(palabra: String) {
        val w = palabra.lowercase().trim()
        if (w.isEmpty()) return
        olvidadas.add(w)
        if (olvidadas.size > 500) olvidadas.remove(olvidadas.first())
        propias.remove(normal(w))
        usos.remove(w)
        nombres.remove(w)
        conteoPares.keys.removeAll { it.endsWith(" $w") || it == w }
        conteoTrios.keys.removeAll { it.endsWith(" $w") }
        escribir(dir, OLVIDADAS, olvidadas)
        sinGuardar = maxOf(sinGuardar, 1)
        guardar()
    }

    fun olvidada(w: String) = w.lowercase() in olvidadas

    /** Escribiste un nombre con mayúscula en medio de una oración: se recuerda */
    fun aprenderNombre(w: String) {
        if (w.length < 3 || !w.all { it.isLetter() } || !w[0].isUpperCase() || w.drop(1).any { it.isUpperCase() }) return
        val l = w.lowercase()
        // Palabras comunes (Rosa, Luz, Paz) no se vuelven nombres
        var puesto = Int.MAX_VALUE
        conClave(normal(l)) { if (palabras[it] == l) puesto = it }
        if (puesto < 20000) return
        nombres[l] = w
        if (nombres.size > 600) nombres.keys.take(nombres.size - 500).forEach { nombres.remove(it) }
        sinGuardar += 5
    }

    /** Terminaste una palabra (después de [anterior]): se cuenta para sugerir mejor */
    fun registrar(anterior2: String?, anterior: String?, palabra: String) {
        val w = palabra.lowercase()
        if (w.isEmpty() || !w.all { it.isLetter() }) return
        usos[w] = (usos[w] ?: 0) + 1
        if (anterior != null) {
            val k = "${anterior.lowercase()} $w"
            conteoPares[k] = (conteoPares[k] ?: 0) + 1
            if (anterior2 != null) {
                val k3 = "${anterior2.lowercase()} $k"
                conteoTrios[k3] = (conteoTrios[k3] ?: 0) + 1
            }
        }
        if (usos.size > 2500) recortar(usos, 2000)
        if (conteoPares.size > 3500) recortar(conteoPares, 3000)
        if (conteoTrios.size > 3500) recortar(conteoTrios, 3000)
        if (++sinGuardar >= 15) guardar()
    }

    private fun recortar(m: MutableMap<String, Int>, n: Int) {
        val quedan = m.entries.sortedByDescending { it.value }.take(n).map { it.key }.toHashSet()
        m.keys.retainAll(quedan)
    }

    fun guardar() {
        if (sinGuardar == 0) return
        sinGuardar = 0
        escribir(dir, USOS, usos.entries.map { "${it.key}:${it.value}" })
        escribir(dir, PARES, conteoPares.entries.map { "${it.key}:${it.value}" })
        escribir(dir, TRIOS, conteoTrios.entries.map { "${it.key}:${it.value}" })
        escribir(dir, NOMBRES, nombres.values)
        escribir(dir, PROPIAS, propias.values)
    }

    /**
     * Lo que suele ir después, mirando las dos palabras anteriores (como SwiftKey):
     * primero tus frases de 3 palabras, luego tus pares, luego las combinaciones comunes.
     * Si siempre escribes lo mismo («nos vemos mañana»), la primera opción es la frase entera.
     */
    fun predecir(anterior2: String?, anterior: String?, n: Int = 3): List<String> {
        val a = anterior?.lowercase() ?: return emptyList()
        val r = ArrayList<String>(n)
        fun meter(w: String) { if (r.size < n && w !in r && w.lowercase() !in olvidadas) r.add(w) }
        if (anterior2 != null) {
            val pref3 = "${anterior2.lowercase()} $a "
            conteoTrios.entries.filter { it.key.startsWith(pref3) && it.value >= 2 }
                .sortedByDescending { it.value }.forEach { meter(it.key.substring(pref3.length)) }
        }
        val pref = "$a "
        conteoPares.entries.filter { it.key.startsWith(pref) && it.value >= 2 }
            .sortedByDescending { it.value }.forEach { meter(it.key.substring(pref.length)) }
        COMUNES[a]?.forEach { meter(it) }
        // Frase tuya: si después de la primera opción casi siempre va la misma palabra, se ofrece junta
        val primera = r.firstOrNull()
        if (primera != null && !primera.contains(' ')) {
            val pref2 = "$a $primera "
            conteoTrios.entries.filter { it.key.startsWith(pref2) && it.value >= 3 }.maxByOrNull { it.value }?.let {
                r.add(0, "$primera ${it.key.substring(pref2.length)}")
                if (r.size > n) r.removeAt(r.size - 1)
            }
        }
        return r
    }

    fun aprender(palabra: String) {
        val w = palabra.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() } || existe(w)) return
        propias[normal(w)] = w
        if (propias.size > 1000) propias.keys.take(propias.size - 1000).forEach { propias.remove(it) }
        sinGuardar = maxOf(sinGuardar, 1)
        guardar()
    }

    // ---------- Búsquedas ----------
    private fun inferior(k: ByteArray): Int {
        var lo = 0; var hi = claves.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (claves.comparar(m, k) < 0) lo = m + 1 else hi = m }
        return lo
    }

    private inline fun conClave(k: String, f: (Int) -> Unit) {
        val kb = k.toByteArray(Charsets.UTF_8)
        var i = inferior(kb)
        while (i < claves.size && claves.comparar(i, kb) == 0) { f(claves.puesto(i)); i++ }
    }

    fun existe(w: String): Boolean {
        val lw = w.lowercase()
        var si = false
        conClave(normal(lw)) { if (palabras[it] == lw) si = true }
        return si || propias[normal(lw)] == lw
    }

    // ---------- Propuesta ----------
    private class Cand(val palabra: String, val costo: Float, val puesto: Int, val soloTilde: Boolean = false) {
        val puntaje = costo * 2.2f + ln(1f + puesto) * 0.25f
    }

    fun proponer(escrito: String, anterior: String? = null, nombrePropio: Boolean = false,
                 toques: List<FloatArray?>? = null, rapido: Boolean = false): Propuesta {
        val ant = anterior?.lowercase()
        if (escrito.isEmpty()) return Propuesta(escrito, null, emptyList())
        val t = escrito.lowercase()
        val k = normal(t)
        if (propias[k] != t) JUNTAS[k]?.takeIf { it != t }?.let { j ->
            val j2 = conForma(j, escrito)
            return Propuesta(escrito, j2, if (rapido) emptyList() else completar(k, 2, null).filter { it != t })
        }
        // Las palabras que ya usaste antes no se corrigen
        val valida = existe(t) || usos.containsKey(t)
        // Risas y onomatopeyas (jajaja, jsjsjs, jejeje) se dejan tal cual
        val suf = sufijos(ant)
        val risa = t.length >= 4 && t.count { it == 'j' } >= 2 && t.all { it in "jaeiosxdhk" }
        val cands = HashMap<String, Cand>()
        fun agregar(w: String, costo: Float, puesto0: Int, soloTilde: Boolean = false) {
            if (w == t) return
            // Tus palabras y las que sueles poner después de la anterior pesan más
            var puesto = puesto0
            val u = usos[w] ?: 0
            if (u > 0) puesto /= (1 + 4 * u)
            if (ant != null && (conteoPares["$ant $w"] ?: 0) > 0) puesto /= 10
            if (concuerda(suf, normal(w))) puesto /= 3
            val c = Cand(w, costo, puesto, soloTilde)
            val prev = cands[w]
            if (prev == null || prev.puntaje > c.puntaje) cands[w] = c
        }
        // 1. Tildes (misma palabra sin tilde)
        conClave(k) { agregar(palabras[it], if (valida) 0.6f else 0.1f, it, soloTilde = true) }
        propias[k]?.let { agregar(it, 0.1f, 500) }
        // 2. Un error (letra vecina, de más, de menos, cambiada de lugar)
        if (k.length in 2..24 && k.all { it in ALFABETO }) {
            val primeras = ArrayList<Pair<String, Float>>()
            ediciones(k, toques = toques) { e, costo ->
                conClave(e) { agregar(palabras[it], costo, it) }
                propias[e]?.let { agregar(it, costo, 800) }
                if (costo <= 0.8f) primeras.add(e to costo)
            }
            // 3. Dos errores (solo en palabras de 5 letras o más, y solo errores típicos)
            if (!rapido && !valida && k.length >= 5 && cands.values.none { it.costo <= 0.8f }) {
                for ((e, c1) in primeras) ediciones(e, soloBaratas = true) { e2, c2 ->
                    if (e2 != k) conClave(e2) { agregar(palabras[it], c1 + c2, it) }
                }
            }
        }
        val orden = cands.values.sortedBy { it.puntaje }
        // ¿Se corrige sola? Solo si la palabra no existe y la corrección es segura
        var correccion: String? = null
        val mejor = orden.firstOrNull()
        if (!valida && mejor != null && t.length >= 2 && t.all { it.isLetter() }) {
            val limite = when { t.length <= 4 -> 0.6f; t.length <= 5 -> 1.0f; else -> 1.65f }
            val segunda = orden.getOrNull(1)
            // Si dos opciones empatan (y no es solo la tilde), no adivina
            // Si otra opción está casi igual de cerca (y no es la misma palabra con o sin tilde),
            // no adivina: deja la palabra y muestra las opciones en la barra
            val dudosa = segunda != null && mejor.costo > 0.45f && segunda.costo <= limite &&
                segunda.puntaje - mejor.puntaje < 0.3f && normal(segunda.palabra) != normal(mejor.palabra)
            // Una palabra muy rara solo se pone si el error es clarísimo
            val rara = mejor.puesto > 40000 && mejor.costo > 0.5f
            // La palabra escrita sin tilde no se cambia sola: la versión con tilde queda en la barra
            // Bien escrita pero sin tilde (hagamoslo, haria): se deja pasar, nunca se cambia por otra
            val sinTilde = orden.any { it.soloTilde }
            val abreviatura = t in ABREVIATURAS
            if (mejor.costo <= limite && !dudosa && !rara && !sinTilde && !risa && !nombrePropio && !abreviatura)
                correccion = conForma(mejor.palabra, escrito)
        }
        // Palabras pegadas: holacomo → hola como (también con la n en lugar del espacio)
        if (!valida && correccion == null && !risa && !nombrePropio && k.length >= 5 && orden.none { it.soloTilde })
            separar(k)?.let { correccion = conForma(it, escrito) }
        // Nombres que escribiste antes con mayúscula: maría → María
        val nombre = nombres[t]
        if (nombre != null && escrito == t && correccion == null) correccion = nombre
        // La cuenta rápida solo decide la corrección (la barra no la necesita)
        if (correccion != null && correccion!!.lowercase() in olvidadas) correccion = null
        if (rapido) return Propuesta(escrito, correccion, emptyList())
        // Opciones de la barra: completar la palabra y las mejores correcciones
        val opciones = ArrayList<String>(3)
        val usadas = HashSet<String>().apply { add(t); correccion?.let { add(it.lowercase()) } }
        fun poner(w: String) { if (opciones.size < 2 && w.lowercase() !in olvidadas && usadas.add(w.lowercase())) opciones.add(conForma(w, escrito)) }
        // Abreviatura de chat (q, xq, tb, ntp…): la palabra completa va primero en la barra
        ABREVIATURAS[t]?.let { poner(it) }
        // La misma palabra con tilde va primero en la barra
        orden.firstOrNull { it.soloTilde }?.let { poner(it.palabra) }
        if (correccion != null) orden.drop(1).take(2).forEach { if (it.costo <= 1.2f) poner(it.palabra) }
        completar(k, 3, suf).forEach { poner(it) }
        if (correccion == null) orden.take(3).forEach { if (it.costo <= 0.8f) poner(it.palabra) }
        return Propuesta(escrito, correccion, opciones)
    }

    /** Letras con las que puede seguir lo que escribes (para acertar la tecla cuando el toque queda justo entre dos) */
    fun siguientes(escrito: String): Set<Char> {
        val kb = normal(escrito).toByteArray(Charsets.UTF_8)
        val r = HashSet<Char>()
        val buf = ByteArray(64)
        var i = inferior(kb); var vistos = 0
        while (i < claves.size && claves.empieza(i, kb) && vistos < 8000) {
            if (claves.puesto(i) < 60000) {
                val n = claves.normal(i, buf)
                if (n > kb.size) {
                    val b = buf[kb.size].toInt() and 0xFF
                    val ch = if (b == 0xC3 && n > kb.size + 1 && (buf[kb.size + 1].toInt() and 0xFF) == 0xB1) 'ñ' else b.toChar()
                    if (ch in 'a'..'z' || ch == 'ñ') r.add(ch)
                }
            }
            i++; vistos++
        }
        for ((clave, _) in propias) if (clave.length > escrito.length && clave.startsWith(normal(escrito))) r.add(clave[escrito.length])
        return r
    }

    private fun termina(b: ByteArray, n: Int, suf: ByteArray): Boolean {
        if (n <= suf.size) return false
        for (j in suf.indices) if (b[n - suf.size + j] != suf[j]) return false
        return true
    }

    /** Palabras que empiezan con [k]: primero las que tú usas, luego las más usadas en general */
    private fun completar(k: String, n: Int, suf: Array<ByteArray>?): List<String> {
        val kb = k.toByteArray(Charsets.UTF_8)
        val puntos = FloatArray(n) { Float.MAX_VALUE }
        val mejores = arrayOfNulls<String>(n)
        fun meter(w: String, pt: Float) {
            if (pt >= puntos[n - 1] || mejores.any { it == w }) return
            var j = n - 1
            while (j > 0 && puntos[j - 1] > pt) { puntos[j] = puntos[j - 1]; mejores[j] = mejores[j - 1]; j-- }
            puntos[j] = pt; mejores[j] = w
        }
        // Las más usadas del diccionario (sin armar textos: se comparan los bytes); las que
        // concuerdan con la palabra anterior (la casa, los perros, yo tengo) suben
        val top = IntArray(n) { -1 }
        val topP = FloatArray(n) { Float.MAX_VALUE }
        val buf = ByteArray(64)
        var i = inferior(kb); var vistos = 0
        while (i < claves.size && claves.empieza(i, kb) && vistos < 6000) {
            val largo = claves.normal(i, buf)
            if (largo > kb.size) {
                var p = claves.puesto(i).toFloat()
                if (suf != null && suf.any { termina(buf, largo, it) }) p /= 3f
                if (p < topP[n - 1]) {
                    var j = n - 1
                    while (j > 0 && topP[j - 1] > p) { topP[j] = topP[j - 1]; top[j] = top[j - 1]; j-- }
                    topP[j] = p; top[j] = claves.puesto(i)
                }
            }
            i++; vistos++
        }
        for (j in 0 until n) if (top[j] >= 0) meter(palabras[top[j]], topP[j])
        // Tus palabras más usadas que empiezan igual suben al frente
        for ((w, u) in usos) {
            val nw = normal(w)
            if (nw.length > k.length && nw.startsWith(k)) {
                var p = 60000
                conClave(nw) { if (palabras[it] == w) p = it }
                meter(w, p.toFloat() / (1 + 4 * u))
            }
        }
        val r = mejores.filterNotNull().toMutableList()
        for ((clave, w) in propias) if (r.size < n && clave.startsWith(k) && clave.length > k.length && w !in r) r.add(w)
        return r
    }

    /**
     * Concordancia simple: qué terminaciones van bien después de la palabra anterior
     * (la → casa, los → perros, yo → tengo, nosotros → vamos, ellos → saben)
     */
    private fun sufijos(ant: String?): Array<ByteArray>? {
        val a = ant?.let { normal(it) } ?: return null
        val s = when (a) {
            "la", "una", "esta", "esa", "aquella", "nuestra", "toda", "otra", "alguna", "ninguna", "mucha", "poca",
            "cierta", "tanta", "cuanta", "misma", "primera", "ultima", "buena", "mala", "nueva", "vieja" ->
                arrayOf("a", "ion", "dad", "tad", "tud", "umbre")
            "el", "un", "este", "ese", "aquel", "nuestro", "todo", "otro", "algun", "ningun", "mucho", "poco",
            "cierto", "tanto", "mismo", "primer", "ultimo", "buen", "mal", "nuevo", "viejo", "del", "al" ->
                arrayOf("o", "or", "on", "en", "an", "el", "il", "al", "ar", "es")
            "las", "unas", "estas", "esas", "nuestras", "todas", "otras", "algunas", "muchas", "pocas", "tantas", "mismas" ->
                arrayOf("as", "es")
            "los", "unos", "estos", "esos", "nuestros", "todos", "otros", "algunos", "muchos", "pocos", "tantos", "mismos" ->
                arrayOf("os", "es")
            "mis", "tus", "sus" -> arrayOf("s")
            "yo" -> arrayOf("o", "e", "i", "ia", "aba")
            "nosotros", "nosotras" -> arrayOf("mos")
            "ellos", "ellas", "ustedes" -> arrayOf("n")
            else -> return null
        }
        return Array(s.size) { s[it].toByteArray(Charsets.UTF_8) }
    }

    private fun concuerda(suf: Array<ByteArray>?, w: String): Boolean {
        if (suf == null) return false
        val b = w.toByteArray(Charsets.UTF_8)
        return suf.any { s -> b.size > s.size && (0 until s.size).all { b[b.size - s.size + it] == s[it] } }
    }

    /** Mayúsculas como las escribiste: «Hola», «HOLA» */
    private fun conForma(w: String, escrito: String): String = when {
        escrito.length > 1 && escrito.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        escrito.first().isUpperCase() -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    /** Todas las palabras a un error de [k], con su costo */
    /** Divide una palabra pegada en dos palabras comunes; null si no hay una división clara */
    private fun separar(k: String): String? {
        var mejor: String? = null; var mejorP = Float.MAX_VALUE
        fun puesto(w: String): Int { var p = Int.MAX_VALUE; conClave(w) { if (it < p) p = it }; return p }
        val sueltas = setOf("a", "y", "o", "e", "u")
        for (j in 1 until k.length) {
            for (saltaN in listOf(false, true)) {
                if (saltaN && k[j] != 'n') continue
                val izq = k.substring(0, j)
                val der = k.substring(if (saltaN) j + 1 else j)
                if ((izq.length < 2 && izq !in sueltas) || der.length < 2) continue
                val pi = puesto(izq); if (pi > 30000) continue
                val pd = puesto(der); if (pd > 30000) continue
                val pt = ln(1f + pi) + ln(1f + pd) + (if (saltaN) 1.5f else 0f)
                if (pt < mejorP) { mejorP = pt; mejor = "${palabras[pi]} ${palabras[pd]}" }
            }
        }
        // Solo si ambas son palabras bastante comunes
        return if (mejorP < 13f) mejor else null
    }

    private inline fun ediciones(k: String, soloBaratas: Boolean = false, toques: List<FloatArray?>? = null,
                                 f: (String, Float) -> Unit) {
        val n = k.length
        val sb = StringBuilder(n + 1)
        // Letra de más (la doble cuesta poco: "holaa")
        for (i in 0 until n) {
            // La h de más cuesta poco; la primera letra casi siempre está bien (cuesta más)
            var costo = if (i > 0 && k[i] == k[i - 1]) 0.4f else if (k[i] == 'h') 0.5f else 0.75f
            if (i == 0 && k[i] != 'h') costo += 0.25f
            if (soloBaratas && costo > 0.8f) continue
            sb.setLength(0); sb.append(k, 0, i).append(k, i + 1, n); f(sb.toString(), costo)
        }
        // k en lugar de qu o c (kiero → quiero, kasa → casa)
        if (!soloBaratas) for (i in 0 until n) if (k[i] == 'k') {
            sb.setLength(0); sb.append(k, 0, i).append("qu").append(k, i + 1, n); f(sb.toString(), 0.5f)
            sb.setLength(0); sb.append(k, 0, i).append('c').append(k, i + 1, n); f(sb.toString(), 0.5f)
        }
        // Dos letras cambiadas de lugar
        for (i in 0 until n - 1) {
            if (k[i] == k[i + 1]) continue
            sb.setLength(0); sb.append(k, 0, i).append(k[i + 1]).append(k[i]).append(k, i + 2, n); f(sb.toString(), 0.6f)
        }
        // Una letra equivocada (la vecina cuesta menos; si se sabe dónde tocaste, cuesta según
        // qué tan cerca de esa otra tecla cayó el dedo, como Gboard)
        for (i in 0 until n) {
            val tq = toques?.getOrNull(i)
            for (c in ALFABETO) {
            if (c == k[i]) continue
            var costo = costoCambio(k[i], c)
            if (tq != null) {
                val ci = ALFABETO.indexOf(c)
                if (ci >= 0) costo = minOf(if (costo <= 0.5f) costo else 1.2f, tq[ci])
            }
            if (i == 0 && (tq == null || costo > 0.35f)) costo += 0.25f
            if (soloBaratas && costo > 0.8f) continue
            sb.setLength(0); sb.append(k, 0, i).append(c).append(k, i + 1, n); f(sb.toString(), costo)
            }
        }
        // Una letra que faltó
        for (i in 0..n) for (c in ALFABETO) {
            val doble = (i > 0 && k[i - 1] == c) || (i < n && k[i] == c)
            // La h que falta cuesta poco (ablar → hablar)
            var costo = if (doble) 0.45f else if (c == 'h') 0.5f else 0.8f
            if (i == 0 && c != 'h') costo += 0.25f
            if (soloBaratas && c != 'h' && !doble && !(i > 0 && vecinas(k[i - 1], c)) && !(i < n && vecinas(k[i], c))) continue
            sb.setLength(0); sb.append(k, 0, i).append(c).append(k, i, n); f(sb.toString(), costo)
        }
    }
}
