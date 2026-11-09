package com.tecladoios

import java.io.File

/**
 * El cerebro del teclado: un modelo de lenguaje personal que aprende solo de lo que tú
 * escribes, sin internet y sin que nada salga del celular.
 *
 * Qué aprende:
 *  - Qué palabra sueles escribir después de una, dos o tres palabras ("ya llegué a la" → "casa").
 *    Mezcla lo que sabe de cada largo de frase: si la frase larga la conoce, manda ella; si no,
 *    se apoya en la más corta (como hacen los teclados de Google y Apple).
 *  - Cómo escribes en cada app: en WhatsApp no escribes igual que en un correo.
 *  - Con qué palabras empiezas tus mensajes.
 *  - Tus errores de siempre: si escribes "qje" y te quedas con "que", lo corrige seguro.
 *  - Las predicciones que siempre ignoras: dejan de aparecer.
 *  - Lo reciente pesa más: lo que dejas de usar se va apagando solo con los meses.
 *  - Memoria de la conversación: las palabras que usaste en los últimos minutos (un nombre,
 *    un lugar, el tema del chat) suben solas en las sugerencias y en la autocorrección.
 *  - Se calibra solo: si deshaces seguido la autocorrección se vuelve más prudente; si casi
 *    nunca la deshaces, se atreve un poco más. Nadie tiene que tocar ningún ajuste.
 *  - Tus teclas confundidas: si sueles tocar la f queriendo la g ("tenfo" → "tengo"), ese
 *    error tuyo le cuesta menos a la autocorrección (corrige mejor tus dedazos de siempre).
 *  - La hora: de mañana empiezas con "buenos días", de noche con "buenas noches"; el cerebro
 *    aprende con qué palabras empiezas tus mensajes según la hora del día.
 *
 * Es estable por diseño: todo va con candado (lo usa un solo hilo a la vez), tiene topes de
 * tamaño (nunca crece sin control) y si el archivo está dañado empieza de cero sin cerrarse.
 */
class Cerebro(private val archivo: File) {

    /**
     * Cuántas veces (que se van apagando con el tiempo), el último día en que se usó y la
     * palabra sin tildes (para buscar por lo que vas escribiendo sin recalcularla cada vez)
     */
    private class Cuenta(var c: Float, var dia: Int, val n: String = "")

    private fun sinTildesDe(w: String): String {
        val n = sinTildes(w)
        return if (n == w) w else n
    }

    /** Lo que sigue a un contexto ("ya llegué a la" → {casa: 5, chamba: 2}) y el total */
    private class Siguientes {
        val mapa = HashMap<String, Cuenta>()
        val total = Cuenta(0f, 0)
    }

    /**
     * Contextos. Claves: "1 la" (una palabra antes), "2 a la" (dos), "3 llegué a la" (tres),
     * "@whatsapp la" (una palabra antes, en esa app). "^" es el inicio del mensaje.
     */
    private val contextos = HashMap<String, Siguientes>()
    /** Tus errores: "qje" → {"que": 4.0} */
    private val errores = HashMap<String, HashMap<String, Float>>()
    /** Predicciones que ignoras: "la>casa" → veces */
    private val ignoradas = HashMap<String, Float>()
    /** Las últimas palabras que escribiste (para saber las tres anteriores) */
    private val historial = ArrayDeque<String>()

    /** Memoria de la conversación: palabra → cuántas veces (se apaga en minutos) y cuándo */
    private class Reciente(var veces: Float, var ms: Long, val n: String)
    private val recientes = HashMap<String, Reciente>()

    /** Teclas que confundes: [escrita * LETRAS + querida] → cuántas veces (de tus correcciones) */
    private val confusiones = FloatArray(LETRAS * LETRAS)

    /** Franja del día ya calculada (se recalcula como mucho una vez por minuto) */
    private var franjaGuardada = -1
    private var franjaHasta = 0L

    /** Qué tan seguido deshaces la autocorrección (promedio que se mueve, 0 a 1) */
    private var tasaDeshacer = TASA_BASE
    /** Cuántas autocorrecciones vio (para saber cuánto creerle a la tasa) */
    private var correccionesVistas = 0f

    private var cambios = false
    private var entradas = 0
    @Volatile var listo = false
        private set

    // ---------- Tiempo ----------

    private fun hoy() = (System.currentTimeMillis() / 86_400_000L).toInt()

    /** Lo que no usas pierde la mitad de su peso cada [VIDA_MEDIA] días */
    private fun vigente(k: Cuenta, dia: Int): Float {
        val dias = dia - k.dia
        if (dias <= 0) return k.c
        return (k.c * Math.pow(0.5, dias.toDouble() / VIDA_MEDIA)).toFloat()
    }

    private fun sumar(k: Cuenta, cuanto: Float, dia: Int) {
        k.c = vigente(k, dia) + cuanto
        k.dia = dia
    }

    private fun normal(w: String) = w.lowercase()

    private fun clave1(p1: String) = "1 " + p1.ifEmpty { INICIO }
    private fun clave2(p2: String, p1: String) = "2 $p2 $p1"
    private fun clave3(p3: String, p2: String, p1: String) = "3 $p3 $p2 $p1"
    private fun claveApp(app: String, p1: String) = "@" + app + " " + p1.ifEmpty { INICIO }
    /** Cómo empiezas los mensajes según la hora: "%h0 ^" (mañana), "%h1 ^" (tarde)... */
    private fun claveHora() = "%h" + franja() + " " + INICIO

    /** 0 = mañana (5 a 11), 1 = tarde (12 a 18), 2 = noche (19 a 23), 3 = madrugada */
    private fun franja(): Int {
        val ahora = System.currentTimeMillis()
        if (franjaGuardada >= 0 && ahora < franjaHasta) return franjaGuardada
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        franjaGuardada = when (h) {
            in 5..11 -> 0
            in 12..18 -> 1
            in 19..23 -> 2
            else -> 3
        }
        franjaHasta = ahora + 60_000L
        return franjaGuardada
    }

    // ---------- Aprender ----------

    /**
     * Escribiste [palabra] después de [previa] (y de [previa2] antes de esa). La tercera
     * palabra anterior la saca de su propio historial si la frase coincide.
     */
    @Synchronized
    fun aprender(previa: String, palabra: String, previa2: String, app: String) {
        if (!listo) return
        val w = normal(palabra)
        if (w.isEmpty() || w.length > 30) return
        val p1 = normal(previa)
        val p2 = if (p1.isEmpty()) "" else normal(previa2)
        val p3 = terceraDe(p1, p2)
        val dia = hoy()
        anotar(clave1(p1), w, dia)
        if (p2.isNotEmpty()) anotar(clave2(p2, p1), w, dia)
        if (p3.isNotEmpty()) anotar(clave3(p3, p2, p1), w, dia)
        if (app.isNotEmpty()) anotar(claveApp(app, p1), w, dia)
        // Al empezar un mensaje: con qué palabra empiezas a esta hora del día
        if (p1.isEmpty()) anotar(claveHora(), w, dia)
        // Usarla es lo contrario de ignorarla
        if (ignoradas.isNotEmpty()) ignoradas.remove("$p1>$w")
        // Historial: si la frase no sigue, empieza de nuevo
        if (p1.isEmpty() || historial.lastOrNull() != p1) {
            historial.clear()
            if (p2.isNotEmpty()) historial.addLast(p2)
            if (p1.isNotEmpty()) historial.addLast(p1)
        }
        historial.addLast(w)
        while (historial.size > 4) historial.removeFirst()
        notarReciente(w)
        cambios = true
    }

    /** Borraste la palabra justo después de escribirla: no se cuenta */
    @Synchronized
    fun desaprender(previa: String, palabra: String, previa2: String, app: String) {
        if (!listo) return
        val w = normal(palabra)
        val p1 = normal(previa)
        val p2 = if (p1.isEmpty()) "" else normal(previa2)
        if (historial.lastOrNull() == w) historial.removeLast()
        val p3 = terceraDe(p1, p2)
        quitar(clave1(p1), w)
        if (p2.isNotEmpty()) quitar(clave2(p2, p1), w)
        if (p3.isNotEmpty()) quitar(clave3(p3, p2, p1), w)
        if (app.isNotEmpty()) quitar(claveApp(app, p1), w)
        if (p1.isEmpty()) quitar(claveHora(), w)
        recientes[w]?.let { r ->
            r.veces -= 1f
            if (r.veces <= 0.1f) recientes.remove(w)
        }
        cambios = true
    }

    // ---------- Memoria de la conversación ----------

    private fun pesoReciente(r: Reciente, ahora: Long): Float {
        val minutos = (ahora - r.ms) / 60_000f
        if (minutos <= 0f) return r.veces
        return (r.veces * Math.exp((-minutos / VIDA_RECIENTE_MIN).toDouble())).toFloat()
    }

    private fun notarReciente(w: String) {
        if (w.length < 4) return
        val ahora = System.currentTimeMillis()
        val r = recientes[w]
        if (r != null) {
            r.veces = minOf(8f, pesoReciente(r, ahora) + 1f)
            r.ms = ahora
            return
        }
        if (recientes.size >= MAX_RECIENTES) {
            // Se olvida lo que ya se apagó (y si aún sobran, lo más flojo)
            val iter = recientes.entries.iterator()
            while (iter.hasNext()) if (pesoReciente(iter.next().value, ahora) < 0.15f) iter.remove()
            if (recientes.size >= MAX_RECIENTES) {
                recientes.entries.sortedBy { pesoReciente(it.value, ahora) }
                    .take(recientes.size - MAX_RECIENTES * 3 / 4).map { it.key }.forEach { recientes.remove(it) }
            }
        }
        recientes[w] = Reciente(1f, ahora, sinTildesDe(w))
    }

    /** Qué tan presente está [w] en lo que escribiste hace poco (0 = nada, 1 = muchísimo) */
    @Synchronized
    fun reciente(w: String): Float {
        if (!listo || recientes.isEmpty()) return 0f
        val r = recientes[normal(w)] ?: return 0f
        val p = pesoReciente(r, System.currentTimeMillis())
        return if (p < 0.15f) 0f else p / (p + 1f)
    }

    /** Palabras de la conversación que empiezan con [prefijo] (sin tildes), de más a menos presentes */
    @Synchronized
    fun recientesCon(prefijo: String, max: Int): List<Pair<String, Float>> {
        if (!listo || recientes.isEmpty() || prefijo.isEmpty()) return emptyList()
        val ahora = System.currentTimeMillis()
        val res = ArrayList<Pair<String, Float>>()
        for ((w, r) in recientes) {
            if (w.length <= prefijo.length || !r.n.startsWith(prefijo)) continue
            val p = pesoReciente(r, ahora)
            if (p < 0.2f) continue
            res.add(w to p / (p + 1f))
        }
        if (res.isEmpty()) return res
        res.sortByDescending { it.second }
        return if (res.size > max) res.subList(0, max).toList() else res
    }

    // ---------- Calibración de la autocorrección ----------

    /** Se aplicó una autocorrección */
    @Synchronized
    fun correccionAplicada() {
        if (!listo) return
        tasaDeshacer += (0f - tasaDeshacer) * PASO_TASA
        if (correccionesVistas < 5000f) correccionesVistas += 1f
        cambios = true
    }

    /** Deshiciste una autocorrección */
    @Synchronized
    fun correccionDeshecha() {
        if (!listo) return
        tasaDeshacer += (1f - tasaDeshacer) * PASO_TASA
        cambios = true
    }

    /**
     * Qué tanto se atreve a corregir: menos de 1 = más prudente (deshaces seguido),
     * más de 1 = corrige errores un poco más grandes (casi nunca la deshaces).
     * Con pocas correcciones vistas se queda cerca de 1.
     */
    @Synchronized
    fun audacia(): Float {
        if (!listo) return 1f
        val confianza = minOf(1f, correccionesVistas / 40f)
        val a = 1f + (TASA_BASE - tasaDeshacer) * 2.2f
        return (1f + (a - 1f) * confianza).coerceIn(0.85f, 1.12f)
    }

    /** La palabra antes de [p2] [p1], si el historial coincide con lo que hay en pantalla */
    private fun terceraDe(p1: String, p2: String): String {
        if (p1.isEmpty() || p2.isEmpty()) return ""
        val n = historial.size
        if (n >= 3 && historial[n - 1] == p1 && historial[n - 2] == p2) return historial[n - 3]
        return ""
    }

    private fun anotar(clave: String, w: String, dia: Int) {
        val s = contextos.getOrPut(clave) { Siguientes() }
        val k = s.mapa[w]
        if (k == null) {
            s.mapa[w] = Cuenta(1f, dia, sinTildesDe(w))
            entradas++
        } else sumar(k, 1f, dia)
        sumar(s.total, 1f, dia)
    }

    private fun quitar(clave: String, w: String) {
        val s = contextos[clave] ?: return
        val k = s.mapa[w] ?: return
        val dia = hoy()
        k.c = vigente(k, dia) - 1f
        k.dia = dia
        s.total.c = maxOf(0f, vigente(s.total, dia) - 1f)
        s.total.dia = dia
        if (k.c <= 0.05f) {
            s.mapa.remove(w)
            entradas--
            if (s.mapa.isEmpty()) contextos.remove(clave)
        }
    }

    /** Se corrigió [escrito] a [final] y lo dejaste así (o lo elegiste tú en la barra) */
    @Synchronized
    fun aprenderError(escrito: String, final: String, peso: Float = 1f) {
        if (!listo) return
        val e = normal(escrito)
        val f = normal(final)
        if (e.length < 2 || e == f || f.isEmpty() || e.contains(' ')) return
        val m = errores.getOrPut(e) { HashMap() }
        m[f] = minOf(30f, (m[f] ?: 0f) + peso)
        anotarConfusiones(e, f, peso)
        if (errores.size > MAX_ERRORES) {
            // Se olvidan los más flojos
            errores.entries.sortedBy { it.value.values.maxOrNull() ?: 0f }
                .take(errores.size - MAX_ERRORES * 9 / 10).map { it.key }.forEach { errores.remove(it) }
        }
        cambios = true
    }

    /** Deshiciste la corrección de [escrito] a [final]: era lo que querías escribir */
    @Synchronized
    fun rechazarError(escrito: String, final: String) {
        if (!listo) return
        val e = normal(escrito)
        val m = errores[e] ?: return
        val f = normal(final)
        val v = (m[f] ?: return) - 3f
        if (v <= 0f) m.remove(f) else m[f] = v
        anotarConfusiones(e, f, -2f)
        if (m.isEmpty()) errores.remove(e)
        cambios = true
    }

    /**
     * Teclas que confundes: si [escrito] y [final] tienen el mismo largo y difieren en una o
     * dos letras ("tenfo" → "tengo"), se anota que tocaste la f queriendo la g.
     */
    private fun anotarConfusiones(escrito: String, final: String, peso: Float) {
        if (escrito.length != final.length || escrito.length < 2) return
        var diferentes = 0
        for (i in escrito.indices) if (escrito[i] != final[i]) diferentes++
        if (diferentes == 0 || diferentes > 2) return
        for (i in escrito.indices) {
            val a = letra(escrito[i])
            val b = letra(final[i])
            if (a < 0 || b < 0 || a == b) continue
            val k = a * LETRAS + b
            confusiones[k] = (confusiones[k] + peso).coerceIn(0f, 40f)
        }
        cambios = true
    }

    /**
     * Cuánto le cuesta a la autocorrección cada cambio de tecla según tus confusiones:
     * [escrita * LETRAS + querida] → factor (1 = normal, más chico = es un error tuyo de siempre).
     * null si todavía no aprendió ninguna.
     */
    @Synchronized
    fun factoresConfusion(): FloatArray? {
        if (!listo) return null
        var r: FloatArray? = null
        for (i in confusiones.indices) {
            val v = confusiones[i]
            if (v < 2f) continue
            if (r == null) r = FloatArray(LETRAS * LETRAS) { 1f }
            r[i] = (1f / (1f + (v - 1f) * 0.12f)).coerceAtLeast(0.45f)
        }
        return r
    }

    /**
     * Tu error de siempre: lo que casi siempre terminas escribiendo cuando tecleas [escrito].
     * Solo si lo viste varias veces y casi siempre fue lo mismo (si no, null).
     */
    @Synchronized
    fun errorTipico(escrito: String): String? {
        if (!listo) return null
        val m = errores[normal(escrito)] ?: return null
        var mejor: String? = null
        var max = 0f
        var total = 0f
        for ((w, v) in m) {
            total += v
            if (v > max) { max = v; mejor = w }
        }
        // Desde 2.5: dos correcciones elegidas o reescritas bastan (antes hacían falta más)
        return if (max >= 2.5f && max >= total * 0.75f) mejor else null
    }

    /** Mostraste [mostradas] después de [previa] y escribiste [elegida] */
    @Synchronized
    fun observarPredicciones(previa: String, mostradas: List<String>, elegida: String) {
        if (!listo || mostradas.isEmpty()) return
        val p1 = normal(previa)
        val w = normal(elegida)
        if (mostradas.any { normal(it) == w }) return
        for (m in mostradas) {
            val k = "$p1>${normal(m)}"
            ignoradas[k] = minOf(20f, (ignoradas[k] ?: 0f) + 1f)
        }
        if (ignoradas.size > MAX_IGNORADAS) {
            ignoradas.entries.sortedBy { it.value }.take(ignoradas.size - MAX_IGNORADAS * 9 / 10)
                .map { it.key }.forEach { ignoradas.remove(it) }
        }
        cambios = true
    }

    /** No quieres que después de [previa] siga [w] (borraste esa frase de la barra) */
    @Synchronized
    fun ignorarSiguiente(previa: String, w: String) {
        if (!listo) return
        val k = normal(previa) + ">" + normal(w)
        ignoradas[k] = maxOf(ignoradas[k] ?: 0f, 12f)
        cambios = true
    }

    /** Para las predicciones que no vienen del cerebro (las del diccionario) */
    @Synchronized
    fun factorIgnorada(previa: String, w: String): Float = if (!listo) 1f else castigo(normal(previa), normal(w))

    @Synchronized
    fun marcarCambios() { cambios = true }

    /** Qué tanto bajar una predicción que ignoras (1 = nada, más chico = la ignoras mucho) */
    private fun castigo(p1: String, w: String): Float {
        val v = ignoradas["$p1>$w"] ?: return 1f
        // Las primeras veces casi no cuenta (pudo ser casualidad)
        return 1f / (1f + maxOf(0f, v - 2f) * 0.35f)
    }

    // ---------- Predecir ----------

    /**
     * Qué tan probable es [w] después de lo que escribiste (0 a 1). Mezcla la frase de
     * tres, dos y una palabra (y la app), dándole más voz a la que más ejemplos tiene.
     */
    @Synchronized
    fun prob(previa: String, previa2: String, w: String, app: String): Float {
        if (!listo) return 0f
        val p1 = normal(previa)
        val p2 = if (p1.isEmpty()) "" else normal(previa2)
        val p3 = terceraDe(p1, p2)
        return mezcla(p1, p2, p3, app, normal(w), hoy(), if (p1.isEmpty()) claveHora() else null)
    }

    private fun mezcla(p1: String, p2: String, p3: String, app: String, w: String, dia: Int, hora: String? = null): Float {
        var resto = 1f
        var p = 0f
        fun nivel(clave: String?, fuerza: Float) {
            if (clave == null || resto <= 0f) return
            val s = contextos[clave] ?: return
            val total = vigente(s.total, dia)
            if (total < 0.5f) return
            // Cuánto creerle: más ejemplos y menos variedad = más confianza (Witten-Bell)
            val confianza = total / (total + s.mapa.size * 0.8f) * fuerza
            val k = s.mapa[w]
            val pw = if (k == null) 0f else vigente(k, dia) / total
            p += resto * confianza * pw
            resto *= (1f - confianza)
        }
        nivel(if (p3.isNotEmpty()) clave3(p3, p2, p1) else null, 1f)
        nivel(if (p2.isNotEmpty()) clave2(p2, p1) else null, 0.95f)
        nivel(if (app.isNotEmpty()) claveApp(app, p1) else null, 0.85f)
        nivel(hora, 0.8f)
        nivel(clave1(p1), 0.9f)
        return (p * castigo(p1, w)).coerceIn(0f, 1f)
    }

    /**
     * Las palabras que probablemente siguen (con su probabilidad), solo las que empiezan
     * con [prefijo] (sin tildes; vacío = cualquiera). Ordenadas de más a menos probable.
     */
    @Synchronized
    fun candidatos(previa: String, previa2: String, app: String, prefijo: String, max: Int): List<Pair<String, Float>> {
        if (!listo) return emptyList()
        val p1 = normal(previa)
        val p2 = if (p1.isEmpty()) "" else normal(previa2)
        val p3 = terceraDe(p1, p2)
        val dia = hoy()
        val vistas = HashSet<String>()
        val hora = if (p1.isEmpty()) claveHora() else null
        val claves = ArrayList<String>(5)
        if (p3.isNotEmpty()) claves.add(clave3(p3, p2, p1))
        if (p2.isNotEmpty()) claves.add(clave2(p2, p1))
        if (app.isNotEmpty()) claves.add(claveApp(app, p1))
        if (hora != null) claves.add(hora)
        claves.add(clave1(p1))
        for (c in claves) {
            val s = contextos[c] ?: continue
            if (prefijo.isEmpty()) {
                // Sin letras todavía: solo las más usadas de cada frase (rápido aunque sean miles)
                if (s.mapa.size <= 40) vistas.addAll(s.mapa.keys)
                else s.mapa.entries.sortedByDescending { it.value.c }.take(40).forEach { vistas.add(it.key) }
            } else {
                for ((w, k) in s.mapa) if (k.n.startsWith(prefijo)) vistas.add(w)
            }
        }
        if (vistas.isEmpty()) return emptyList()
        val res = ArrayList<Pair<String, Float>>(vistas.size)
        for (w in vistas) {
            val pr = mezcla(p1, p2, p3, app, w, dia, hora)
            if (pr > 0.01f) res.add(w to pr)
        }
        res.sortByDescending { it.second }
        return if (res.size > max) res.subList(0, max).toList() else res
    }

    // ---------- Frase completa ----------

    /**
     * La frase que sigue, para ofrecerla entera en la barra: después de [previa2] [previa]
     * [primera], las palabras que casi siempre escribes a continuación ("nos" "vemos" →
     * "mañana", "a", "las"...). Piensa paso a paso como tú: con las tres palabras de antes
     * busca la siguiente más probable, la agrega y vuelve a mirar. Solo sigue mientras está
     * seguro (la palabra se lleva casi todo el peso y le gana clarito a la segunda) y para en
     * cuanto duda. Vacío si no hay nada seguro.
     */
    @Synchronized
    fun continuar(previa: String, previa2: String, primera: String, app: String, max: Int): List<String> {
        if (!listo || max <= 0 || contextos.isEmpty()) return emptyList()
        var p1 = normal(primera)
        if (p1.isEmpty()) return emptyList()
        var p2 = normal(previa)
        var p3 = if (p2.isEmpty()) "" else normal(previa2)
        val dia = hoy()
        val res = ArrayList<String>(max)
        val usadas = HashSet<String>()
        usadas.add(p1)
        val candidatas = HashSet<String>()
        while (res.size < max) {
            // Hace falta haber escrito esa palabra unas cuantas veces para animarse a seguir
            val s1 = contextos[clave1(p1)] ?: break
            if (vigente(s1.total, dia) < MIN_FRASE) break
            candidatas.clear()
            if (p3.isNotEmpty() && p2.isNotEmpty()) contextos[clave3(p3, p2, p1)]?.let { mejoresDe(it, 6, dia, candidatas) }
            if (p2.isNotEmpty()) contextos[clave2(p2, p1)]?.let { mejoresDe(it, 6, dia, candidatas) }
            if (app.isNotEmpty()) contextos[claveApp(app, p1)]?.let { mejoresDe(it, 4, dia, candidatas) }
            mejoresDe(s1, 4, dia, candidatas)
            var mejor: String? = null
            var pm = 0f
            var segunda = 0f
            for (w in candidatas) {
                val pr = mezcla(p1, p2, p3, app, w, dia)
                if (pr > pm) { segunda = pm; pm = pr; mejor = w } else if (pr > segunda) segunda = pr
            }
            val w = mejor ?: break
            if (pm < UMBRAL_FRASE || pm < segunda * 1.7f || !usadas.add(w)) break
            res.add(w)
            p3 = p2; p2 = p1; p1 = w
        }
        return res
    }

    /** Las [k] palabras con más peso de un contexto (sin ordenar todo el mapa: es rápido) */
    private fun mejoresDe(s: Siguientes, k: Int, dia: Int, destino: HashSet<String>) {
        if (s.mapa.size <= k) { destino.addAll(s.mapa.keys); return }
        val palabras = arrayOfNulls<String>(k)
        val pesos = FloatArray(k)
        for ((w, c) in s.mapa) {
            val v = vigente(c, dia)
            if (v <= pesos[k - 1]) continue
            var i = k - 1
            while (i > 0 && pesos[i - 1] < v) { pesos[i] = pesos[i - 1]; palabras[i] = palabras[i - 1]; i-- }
            pesos[i] = v
            palabras[i] = w
        }
        for (w in palabras) if (w != null) destino.add(w)
    }

    // ---------- Guardar y leer ----------

    @Synchronized
    fun hayCambios() = cambios

    /** Todo lo aprendido como texto (para guardarlo o para la copia de seguridad) */
    @Synchronized
    fun instantanea(): String {
        podar()
        val dia = hoy()
        val sb = StringBuilder(256 * 1024)
        sb.append(VERSION).append('\n')
        sb.append("#c\n")
        for ((clave, s) in contextos) {
            for ((w, k) in s.mapa) {
                val v = vigente(k, dia)
                if (v < 0.05f) continue
                sb.append(clave).append('\t').append(w).append('\t')
                    .append(Math.round(v * 100f) / 100f).append('\t').append(dia).append('\n')
            }
        }
        sb.append("#e\n")
        for ((e, m) in errores) for ((w, v) in m) sb.append(e).append('\t').append(w).append('\t').append(v).append('\n')
        sb.append("#i\n")
        for ((k, v) in ignoradas) sb.append(k).append('\t').append(v).append('\n')
        // Calibración de la autocorrección (las versiones viejas ignoran esta sección)
        sb.append("#a\n")
        sb.append("tasa\t").append(tasaDeshacer).append('\n')
        sb.append("vistas\t").append(correccionesVistas).append('\n')
        // Teclas que confundes (las versiones viejas ignoran esta sección)
        sb.append("#k\n")
        for (i in confusiones.indices) {
            val v = confusiones[i]
            if (v < 0.5f) continue
            sb.append(i / LETRAS).append('\t').append(i % LETRAS).append('\t')
                .append(Math.round(v * 100f) / 100f).append('\n')
        }
        cambios = false
        return sb.toString()
    }

    /** Si creció demasiado, se olvida lo más flojo y viejo (así nunca se pone lento) */
    private fun podar() {
        if (entradas <= MAX_ENTRADAS) return
        val dia = hoy()
        val todas = ArrayList<Triple<String, String, Float>>(entradas)
        for ((clave, s) in contextos) for ((w, k) in s.mapa) todas.add(Triple(clave, w, vigente(k, dia)))
        todas.sortBy { it.third }
        val sobran = todas.size - MAX_ENTRADAS * 8 / 10
        for (i in 0 until sobran) {
            val (clave, w, v) = todas[i]
            val s = contextos[clave] ?: continue
            if (s.mapa.remove(w) != null) {
                s.total.c = maxOf(0f, vigente(s.total, dia) - v)
                s.total.dia = dia
                if (s.mapa.isEmpty()) contextos.remove(clave)
            }
        }
        entradas = contextos.values.sumOf { it.mapa.size }
    }

    /** Escribe el archivo (sin dejarlo a medias nunca) */
    fun escribir(texto: String) {
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.writeText(texto, Charsets.UTF_8)
        if (!tmp.renameTo(archivo)) {
            archivo.delete()
            if (!tmp.renameTo(archivo)) throw java.io.IOException("no se pudo reemplazar ${archivo.name}")
        }
    }

    /** Lee lo aprendido del archivo (o empieza de cero si no hay o está dañado) */
    @Synchronized
    fun cargar() {
        contextos.clear()
        errores.clear()
        ignoradas.clear()
        historial.clear()
        recientes.clear()
        tasaDeshacer = TASA_BASE
        correccionesVistas = 0f
        confusiones.fill(0f)
        entradas = 0
        try {
            if (archivo.exists()) leer(archivo.readText(Charsets.UTF_8))
        } catch (e: Throwable) {
            contextos.clear()
            errores.clear()
            ignoradas.clear()
            entradas = 0
            Registro.anotar("leer el cerebro", e)
        }
        cambios = false
        listo = true
    }

    private fun leer(texto: String) {
        if (!texto.startsWith("tecladoios-cerebro-")) return
        var seccion = ' '
        for (linea in texto.lineSequence()) {
            if (linea.isEmpty()) continue
            if (linea.length == 2 && linea[0] == '#') { seccion = linea[1]; continue }
            val p = linea.split('\t')
            when (seccion) {
                'c' -> if (p.size == 4) {
                    val v = p[2].toFloatOrNull() ?: continue
                    val dia = p[3].toIntOrNull() ?: continue
                    if (v <= 0f || p[1].isEmpty()) continue
                    val s = contextos.getOrPut(p[0]) { Siguientes() }
                    if (s.mapa.put(p[1], Cuenta(v, dia, sinTildesDe(p[1]))) == null) entradas++
                    // El total se rehace con lo que se va leyendo
                    s.total.c = vigente(s.total, dia) + v
                    s.total.dia = maxOf(s.total.dia, dia)
                }
                'e' -> if (p.size == 3) {
                    val v = p[2].toFloatOrNull() ?: continue
                    errores.getOrPut(p[0]) { HashMap() }[p[1]] = v
                }
                'i' -> if (p.size == 2) {
                    ignoradas[p[0]] = p[1].toFloatOrNull() ?: continue
                }
                'a' -> if (p.size == 2) {
                    val v = p[1].toFloatOrNull() ?: continue
                    when (p[0]) {
                        "tasa" -> tasaDeshacer = v.coerceIn(0f, 1f)
                        "vistas" -> correccionesVistas = v.coerceIn(0f, 5000f)
                    }
                }
                'k' -> if (p.size == 3) {
                    val a = p[0].toIntOrNull() ?: continue
                    val b = p[1].toIntOrNull() ?: continue
                    val v = p[2].toFloatOrNull() ?: continue
                    if (a in 0 until LETRAS && b in 0 until LETRAS) confusiones[a * LETRAS + b] = v.coerceIn(0f, 40f)
                }
            }
        }
    }

    /** Olvida todo (Restablecer diccionario) */
    @Synchronized
    fun olvidarTodo() {
        contextos.clear()
        errores.clear()
        ignoradas.clear()
        historial.clear()
        recientes.clear()
        tasaDeshacer = TASA_BASE
        correccionesVistas = 0f
        confusiones.fill(0f)
        entradas = 0
        cambios = false
        try { archivo.delete() } catch (_: Exception) { }
    }

    /** Olvida una palabra en todo el cerebro (mantener presionada la sugerencia → Borrar) */
    @Synchronized
    fun olvidar(palabra: String) {
        val w = normal(palabra)
        val vacias = ArrayList<String>()
        for ((clave, s) in contextos) {
            if (s.mapa.remove(w) != null) entradas--
            if (s.mapa.isEmpty()) vacias.add(clave)
        }
        for (c in vacias) contextos.remove(c)
        errores.remove(w)
        for (m in errores.values) m.remove(w)
        recientes.remove(w)
        cambios = true
    }

    companion object {
        const val ARCHIVO = "cerebro.txt"
        /** Letras del teclado para las confusiones: a–z y la ñ */
        const val LETRAS = 27

        /** Posición de la letra (a = 0 ... z = 25, ñ = 26; las vocales con tilde cuentan sin ella) o -1 */
        fun letra(c: Char): Int {
            val l = c.lowercaseChar()
            return when (l) {
                in 'a'..'z' -> l - 'a'
                'ñ' -> 26
                'á', 'à', 'ä', 'â' -> 0
                'é', 'è', 'ë', 'ê' -> 4
                'í', 'ì', 'ï', 'î' -> 8
                'ó', 'ò', 'ö', 'ô' -> 14
                'ú', 'ù', 'ü', 'û' -> 20
                else -> -1
            }
        }
        private const val VERSION = "tecladoios-cerebro-1"
        private const val INICIO = "^"
        /** Días en que algo que ya no usas pierde la mitad de su peso */
        private const val VIDA_MEDIA = 120.0
        private const val MAX_ENTRADAS = 80_000
        private const val MAX_ERRORES = 3000
        private const val MAX_IGNORADAS = 3000
        /** Memoria de la conversación: cuántas palabras y en cuántos minutos se apagan */
        private const val MAX_RECIENTES = 400
        private const val VIDA_RECIENTE_MIN = 25.0
        /** Lo normal: se deshace más o menos 1 de cada 12 autocorrecciones */
        private const val TASA_BASE = 0.08f
        /** Frase completa: cuántas veces escribiste la palabra y qué tan seguro tiene que estar */
        private const val MIN_FRASE = 3f
        private const val UMBRAL_FRASE = 0.45f
        private const val PASO_TASA = 0.04f

        /** Resumen de lo aprendido para Ajustes (lee el archivo del cerebro tal cual) */
        fun resumen(texto: String?): String? {
            if (texto == null || !texto.startsWith("tecladoios-cerebro-")) return null
            var seccion = ' '
            var frases = 0
            var errores = 0
            var tasa = -1f
            var vistas = 0f
            var teclas = 0
            for (linea in texto.lineSequence()) {
                if (linea.isEmpty()) continue
                if (linea.length == 2 && linea[0] == '#') { seccion = linea[1]; continue }
                when (seccion) {
                    'c' -> frases++
                    'e' -> errores++
                    'k' -> {
                        val p = linea.split('\t')
                        if (p.size == 3 && (p[2].toFloatOrNull() ?: 0f) >= 2f) teclas++
                    }
                    'a' -> {
                        val p = linea.split('\t')
                        if (p.size == 2) when (p[0]) {
                            "tasa" -> tasa = p[1].toFloatOrNull() ?: -1f
                            "vistas" -> vistas = p[1].toFloatOrNull() ?: 0f
                        }
                    }
                }
            }
            val calibrada = when {
                tasa < 0f || vistas < 40f -> "todavía aprendiendo cómo escribes"
                tasa > 0.14f -> "más prudente (la deshaces seguido)"
                tasa < 0.05f -> "más segura (casi nunca la deshaces)"
                else -> "equilibrada"
            }
            val conTeclas = if (teclas > 0) " · $teclas teclas que sueles confundir" else ""
            return "$frases patrones de frases · $errores errores tuyos que ya reconoce$conTeclas\n" +
                "Autocorrección: $calibrada"
        }

        /** Se parecen lo suficiente como para ser la misma palabra mal escrita (hasta 2 cambios) */
        fun parecidas(a: String, b: String): Boolean {
            if (Math.abs(a.length - b.length) > 2) return false
            val fila = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var diag = fila[0]
                fila[0] = i
                var menor = fila[0]
                for (j in 1..b.length) {
                    val arriba = fila[j]
                    fila[j] = minOf(
                        fila[j] + 1,
                        fila[j - 1] + 1,
                        diag + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                    diag = arriba
                    if (fila[j] < menor) menor = fila[j]
                }
                if (menor > 2) return false
            }
            return fila[b.length] <= 2
        }

        fun sinTildes(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(MARCAS, "")

        private val MARCAS = Regex("\\p{Mn}+")
    }
}
