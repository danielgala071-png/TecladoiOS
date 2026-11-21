package com.tecladoios

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.ln

/**
 * Diccionario de español latinoamericano (~90 000 palabras ordenadas de la más usada a la
 * menos usada) y el corrector, pensado como el del iPhone:
 * - Corrige solo palabras que NO existen y solo cuando está seguro.
 * - Pone las tildes que faltan (tambien → también).
 * - Tiene en cuenta las teclas vecinas (es más fácil tocar la de al lado).
 * - Aprende las palabras que rechazas al corregir (las que eliges entre comillas).
 *
 * Todo vive en tres arreglos (sin mapas grandes), así ocupa poca memoria.
 */
class Diccionario private constructor(
    /** Palabras por frecuencia (el índice es el puesto: 0 = la más usada) */
    private val palabras: Array<String>,
    /** Las mismas palabras sin tildes y en minúscula, ordenadas alfabéticamente */
    private val claves: Array<String>,
    /** Puesto de cada clave */
    private val puestos: IntArray,
    private val prefs: SharedPreferences,
) {

    /** Lo que el teclado propone para la palabra que escribes */
    class Propuesta(
        val escrito: String,
        /** Corrección que se pondrá sola al tocar espacio (null = ninguna) */
        val correccion: String?,
        /** Otras opciones para la barra */
        val opciones: List<String>,
    )

    companion object {
        private const val PROPIAS = "palabras_propias"
        private const val USOS = "palabras_usos"
        private const val PARES = "palabras_pares"

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
        private const val ALFABETO = "abcdefghijklmnñopqrstuvwxyz"

        fun cargar(c: Context): Diccionario {
            val lista = ArrayList<String>(90000)
            c.assets.open("palabras.txt").bufferedReader().useLines { ls -> ls.forEach { if (it.isNotBlank()) lista.add(it.trim()) } }
            val palabras = lista.toTypedArray()
            val norm = Array(palabras.size) { val w = palabras[it]; val n = normal(w); if (n == w) w else n }
            val orden = (palabras.indices).sortedWith { a, b -> val x = norm[a].compareTo(norm[b]); if (x != 0) x else a - b }
            val claves = Array(orden.size) { norm[orden[it]] }
            val puestos = IntArray(orden.size) { orden[it] }
            return Diccionario(palabras, claves, puestos, Ajustes.prefs(c))
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

        /** Palabras que se escriben juntas pero van separadas (como corrige el iPhone) */
        private val JUNTAS = mapOf(
            "aver" to "a ver", "osea" to "o sea", "talvez" to "tal vez", "enserio" to "en serio",
            "porfavor" to "por favor", "atravez" to "a través", "atraves" to "a través", "nose" to "no sé",
            "almenos" to "al menos", "sinembargo" to "sin embargo", "envez" to "en vez", "derepente" to "de repente",
        )
    }

    // ---------- Palabras propias (las que aprendes) ----------
    private val propias = java.util.concurrent.ConcurrentHashMap<String, String>()   // clave → palabra
    init {
        prefs.getString(PROPIAS, null)?.split('\n')?.forEach { if (it.isNotEmpty()) propias[normal(it)] = it }
    }

    // ---------- Lo que aprende de cómo escribes (solo en el celular) ----------
    /** Cuántas veces usaste cada palabra (las 3000 más usadas) */
    private val usos = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Qué palabra sueles poner después de cada palabra */
    private val conteoPares = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var sinGuardar = 0

    init {
        prefs.getString(USOS, null)?.split('\n')?.forEach { l ->
            val i = l.lastIndexOf(':'); if (i > 0) l.substring(i + 1).toIntOrNull()?.let { usos[l.substring(0, i)] = it }
        }
        prefs.getString(PARES, null)?.split('\n')?.forEach { l ->
            val i = l.lastIndexOf(':'); if (i > 0) l.substring(i + 1).toIntOrNull()?.let { conteoPares[l.substring(0, i)] = it }
        }
    }

    /** Terminaste una palabra (después de [anterior]): se cuenta para sugerir mejor */
    fun registrar(anterior: String?, palabra: String) {
        val w = palabra.lowercase()
        if (w.isEmpty() || !w.all { it.isLetter() }) return
        usos[w] = (usos[w] ?: 0) + 1
        if (anterior != null) {
            val k = "${anterior.lowercase()} $w"
            conteoPares[k] = (conteoPares[k] ?: 0) + 1
        }
        if (usos.size > 3500) recortar(usos, 3000)
        if (conteoPares.size > 4500) recortar(conteoPares, 4000)
        if (++sinGuardar >= 15) guardar()
    }

    private fun recortar(m: MutableMap<String, Int>, n: Int) {
        val quedan = m.entries.sortedByDescending { it.value }.take(n).map { it.key }.toHashSet()
        m.keys.retainAll(quedan)
    }

    fun guardar() {
        if (sinGuardar == 0) return
        sinGuardar = 0
        prefs.edit()
            .putString(USOS, usos.entries.joinToString("\n") { "${it.key}:${it.value}" })
            .putString(PARES, conteoPares.entries.joinToString("\n") { "${it.key}:${it.value}" })
            .apply()
    }

    /** Las palabras que suelen ir después de [anterior]: primero las tuyas, luego las comunes */
    fun predecir(anterior: String?, n: Int = 3): List<String> {
        val a = anterior?.lowercase() ?: return emptyList()
        val r = ArrayList<String>(n)
        val pref = "$a "
        conteoPares.entries.filter { it.key.startsWith(pref) && it.value >= 2 }
            .sortedByDescending { it.value }.forEach { if (r.size < n) r.add(it.key.substring(pref.length)) }
        COMUNES[a]?.forEach { if (r.size < n && it !in r) r.add(it) }
        return r
    }

    fun aprender(palabra: String) {
        val w = palabra.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() } || existe(w)) return
        propias[normal(w)] = w
        // Las 1000 más recientes
        val l = prefs.getString(PROPIAS, null)?.split('\n')?.filter { it.isNotEmpty() && it != w }?.toMutableList() ?: ArrayList()
        l.add(0, w)
        prefs.edit().putString(PROPIAS, l.take(1000).joinToString("\n")).apply()
    }

    // ---------- Búsquedas ----------
    private fun inferior(k: String): Int {
        var lo = 0; var hi = claves.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (claves[m] < k) lo = m + 1 else hi = m }
        return lo
    }

    private inline fun conClave(k: String, f: (Int) -> Unit) {
        var i = inferior(k)
        while (i < claves.size && claves[i] == k) { f(puestos[i]); i++ }
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

    fun proponer(escrito: String, anterior: String? = null): Propuesta {
        val ant = anterior?.lowercase()
        if (escrito.isEmpty()) return Propuesta(escrito, null, emptyList())
        val t = escrito.lowercase()
        val k = normal(t)
        if (propias[k] != t) JUNTAS[k]?.let { j ->
            val j2 = conForma(j, escrito)
            return Propuesta(escrito, j2, completar(k, 2).filter { it != t })
        }
        val valida = existe(t)
        val cands = HashMap<String, Cand>()
        fun agregar(w: String, costo: Float, puesto0: Int, soloTilde: Boolean = false) {
            if (w == t) return
            // Tus palabras y las que sueles poner después de la anterior pesan más
            var puesto = puesto0
            val u = usos[w] ?: 0
            if (u > 0) puesto /= (1 + 4 * u)
            if (ant != null && (conteoPares["$ant $w"] ?: 0) > 0) puesto /= 10
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
            ediciones(k) { e, costo ->
                conClave(e) { agregar(palabras[it], costo, it) }
                propias[e]?.let { agregar(it, costo, 800) }
                if (costo <= 0.8f) primeras.add(e to costo)
            }
            // 3. Dos errores (solo en palabras de 5 letras o más, y solo errores típicos)
            if (!valida && k.length >= 5 && cands.values.none { it.costo <= 0.8f }) {
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
            val dudosa = segunda != null && mejor.costo > 0.2f && segunda.puntaje - mejor.puntaje < 0.12f && segunda.costo <= mejor.costo
            // La palabra escrita sin tilde no se cambia sola: la versión con tilde queda en la barra
            if (mejor.costo <= limite && (mejor.puesto < 45000 || mejor.costo <= 0.2f) && !dudosa && !mejor.soloTilde)
                correccion = conForma(mejor.palabra, escrito)
        }
        // Opciones de la barra: completar la palabra y las mejores correcciones
        val opciones = ArrayList<String>(3)
        val usadas = HashSet<String>().apply { add(t); correccion?.let { add(it.lowercase()) } }
        fun poner(w: String) { if (opciones.size < 2 && usadas.add(w.lowercase())) opciones.add(conForma(w, escrito)) }
        // La misma palabra con tilde va primero en la barra
        orden.firstOrNull { it.soloTilde }?.let { poner(it.palabra) }
        if (correccion != null) orden.drop(1).take(2).forEach { if (it.costo <= 1.2f) poner(it.palabra) }
        completar(k, 3).forEach { poner(it) }
        if (correccion == null) orden.take(3).forEach { if (it.costo <= 0.8f) poner(it.palabra) }
        return Propuesta(escrito, correccion, opciones)
    }

    /** Palabras que empiezan con [k]: primero las que tú usas, luego las más usadas en general */
    private fun completar(k: String, n: Int): List<String> {
        val puntos = FloatArray(n) { Float.MAX_VALUE }
        val mejores = arrayOfNulls<String>(n)
        var i = inferior(k); var vistos = 0
        while (i < claves.size && claves[i].startsWith(k) && vistos < 6000) {
            if (claves[i].length > k.length) {
                val w = palabras[puestos[i]]
                val u = usos[w] ?: 0
                val pt = puestos[i].toFloat() / (1 + 4 * u)
                if (pt < puntos[n - 1]) {
                    var j = n - 1
                    while (j > 0 && puntos[j - 1] > pt) { puntos[j] = puntos[j - 1]; mejores[j] = mejores[j - 1]; j-- }
                    puntos[j] = pt; mejores[j] = w
                }
            }
            i++; vistos++
        }
        val r = mejores.filterNotNull().toMutableList()
        for ((clave, w) in propias) if (r.size < n && clave.startsWith(k) && clave.length > k.length && w !in r) r.add(w)
        return r
    }

    /** Mayúsculas como las escribiste: «Hola», «HOLA» */
    private fun conForma(w: String, escrito: String): String = when {
        escrito.length > 1 && escrito.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        escrito.first().isUpperCase() -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    /** Todas las palabras a un error de [k], con su costo */
    private inline fun ediciones(k: String, soloBaratas: Boolean = false, f: (String, Float) -> Unit) {
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
        // Dos letras cambiadas de lugar
        for (i in 0 until n - 1) {
            if (k[i] == k[i + 1]) continue
            sb.setLength(0); sb.append(k, 0, i).append(k[i + 1]).append(k[i]).append(k, i + 2, n); f(sb.toString(), 0.6f)
        }
        // Una letra equivocada (la vecina cuesta menos)
        for (i in 0 until n) for (c in ALFABETO) {
            if (c == k[i]) continue
            val costo = costoCambio(k[i], c) + if (i == 0) 0.25f else 0f
            if (soloBaratas && costo > 0.8f) continue
            sb.setLength(0); sb.append(k, 0, i).append(c).append(k, i + 1, n); f(sb.toString(), costo)
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
