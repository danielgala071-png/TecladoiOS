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
 *  - Tu perfil de errores: cada corrección se desarma letra por letra y se anota QUÉ TIPO de
 *    dedazo fue: tecla vecina ("tenfo" → tengo), letra que no salió ("porqe" → porque), letra
 *    de más ("hokla" → hola), letras cruzadas entre los dos pulgares ("qeu" → que), tildes
 *    o un espacio fallado. Si tú sueles comerte la r, a la autocorrección le cuesta menos
 *    ponerla; si sueles cruzar letras, las inversiones le cuestan menos.
 *  - Los errores difíciles de ver: palabras que SÍ existen pero no son las que querías
 *    ("tubo" por "tuvo", "valla" por "vaya", "casa" por "cada"). Si las cambias seguido
 *    después de cierta palabra ("no tubo" → "no tuvo"), aprende a cambiarlas solo en ese
 *    contexto; si las cambias siempre, en cualquier lugar.
 *  - Cuándo va un espacio y cuándo es una sola palabra mal escrita: aprende de lo que dejas
 *    y de lo que deshaces. Si deshaces seguido las separaciones con la n ("ya n podemos"),
 *    deja de separar con la n; si casi siempre las dejas, se anima más. Lo mismo con las
 *    palabras pegadas sin letra en medio y con el espacio cruzado ("hol aamigo" → "hola amigo").
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
    /**
     * Palabras reales que cambias por otra: "no|tubo" → {"tuvo": 3} (después de esa palabra)
     * y "*|tubo" → {"tuvo": 5} (en cualquier lugar)
     */
    private val reemplazos = HashMap<String, HashMap<String, Float>>()
    /** Predicciones que ignoras: "la>casa" → veces */
    private val ignoradas = HashMap<String, Float>()
    /** Las últimas palabras que escribiste (para saber las tres anteriores) */
    private val historial = ArrayDeque<String>()

    /** Memoria de la conversación: palabra → cuántas veces (se apaga en minutos) y cuándo */
    private class Reciente(var veces: Float, var ms: Long, val n: String)
    private val recientes = HashMap<String, Reciente>()

    /** Teclas que confundes: [escrita * LETRAS + querida] → cuántas veces (de tus correcciones) */
    private val confusiones = FloatArray(LETRAS * LETRAS)
    /** Letras que no salieron (te las comiste): letra querida → cuántas veces */
    private val faltas = FloatArray(LETRAS)
    /** Letras de más (rozaste otra tecla o rebotó): letra que sobró → cuántas veces */
    private val sobras = FloatArray(LETRAS)
    /** Cuántas veces cruzaste dos letras al escribir con los dos pulgares ("qeu" → "que") */
    private var cruces = 0f
    /** Cuántas veces hiciste cada tipo de error (para el perfil y el resumen de Ajustes) */
    private val tipos = FloatArray(TIPOS)
    /**
     * Espacios: [letra que cayó en lugar del espacio] (b, n, v, m, c...; la posición [LETRAS]
     * es "sin letra", palabras pegadas) → cuántas veces separar ahí estuvo bien (lo dejaste)
     * y cuántas estuvo mal (lo deshiciste). La posición [CRUCE] es el espacio cruzado.
     */
    private val espacioSi = FloatArray(LETRAS + 2)
    private val espacioNo = FloatArray(LETRAS + 2)

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
        if (f.contains(' ')) anotarEspacio(e, f, peso) else anotarDedazo(e, f, peso)
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
        val f = normal(final)
        // Un espacio que no iba: se anota aunque sea la primera vez (así aprende rápido
        // que con esa letra no tiene que separar)
        if (f.contains(' ') && !e.contains(' ')) anotarEspacio(e, f, -1f)
        val m = errores[e] ?: return
        val v = (m[f] ?: return) - 3f
        if (v <= 0f) m.remove(f) else m[f] = v
        if (!f.contains(' ')) anotarDedazo(e, f, -2f)
        if (m.isEmpty()) errores.remove(e)
        cambios = true
    }

    // ---------- Teclas corregidas mientras escribes ----------

    /**
     * Borraste la letra [borrada] y en su lugar escribiste [nueva] ("tenf" ← borrar → "teng"):
     * es la señal más directa de qué teclas confundes. Pasa decenas de veces al día, así que
     * cada una suma poquito; con el tiempo el cerebro sabe exactamente cuáles son tus dedazos.
     */
    @Synchronized
    fun aprenderTecla(borrada: Char, nueva: Char) {
        if (!listo) return
        val a = letra(borrada)
        val b = letra(nueva)
        if (a < 0 || b < 0 || a == b) return
        val k = a * LETRAS + b
        confusiones[k] = (confusiones[k] + PESO_TECLA).coerceIn(0f, 40f)
        sumarTipo(TIPO_VECINA, PESO_TECLA)
        cambios = true
    }

    // ---------- Errores difíciles: una palabra real por otra ----------

    private fun claveCambio(previa: String, escrita: String) = normal(previa).ifEmpty { INICIO } + "|" + normal(escrita)

    /**
     * Escribiste [escrita] (una palabra que existe) después de [previa] y la cambiaste por
     * [querida] (otra que existe y se le parece): "no tubo" → "no tuvo".
     */
    @Synchronized
    fun aprenderCambio(previa: String, escrita: String, querida: String, peso: Float = 1f) {
        if (!listo) return
        val e = normal(escrita)
        val q = normal(querida)
        if (e.length < 2 || q.length < 2 || e == q || e.contains(' ') || q.contains(' ')) return
        for (clave in listOf(claveCambio(previa, e), "*|$e")) {
            val m = reemplazos.getOrPut(clave) { HashMap() }
            val p = if (clave[0] == '*') peso * 0.6f else peso
            m[q] = minOf(30f, (m[q] ?: 0f) + p)
        }
        if (reemplazos.size > MAX_CAMBIOS) {
            reemplazos.entries.sortedBy { it.value.values.maxOrNull() ?: 0f }
                .take(reemplazos.size - MAX_CAMBIOS * 9 / 10).map { it.key }.forEach { reemplazos.remove(it) }
        }
        cambios = true
    }

    /** Deshiciste el cambio de [escrita] por [querida]: era la palabra que querías */
    @Synchronized
    fun rechazarCambio(escrita: String, querida: String) {
        if (!listo || reemplazos.isEmpty()) return
        val e = normal(escrita)
        val q = normal(querida)
        val sufijo = "|$e"
        val vacias = ArrayList<String>()
        for ((clave, m) in reemplazos) {
            if (!clave.endsWith(sufijo)) continue
            val v = (m[q] ?: continue) - 3f
            if (v <= 0f) m.remove(q) else m[q] = v
            if (m.isEmpty()) vacias.add(clave)
        }
        for (c in vacias) reemplazos.remove(c)
        cambios = true
    }

    /**
     * La palabra que de verdad querías cuando escribes [escrita] después de [previa], si ya lo
     * aprendió seguro: en ese contexto (2 veces o más, casi siempre la misma) o en cualquier
     * lugar (más veces). null si no está seguro.
     */
    @Synchronized
    fun cambioEnContexto(previa: String, escrita: String): String? {
        if (!listo || reemplazos.isEmpty()) return null
        val e = normal(escrita)
        dominante(reemplazos[claveCambio(previa, e)], 2.5f, 0.8f)?.let { return it }
        return dominante(reemplazos["*|$e"], 3.5f, 0.85f)
    }

    private fun dominante(m: HashMap<String, Float>?, minimo: Float, parte: Float): String? {
        if (m.isNullOrEmpty()) return null
        var mejor: String? = null
        var max = 0f
        var total = 0f
        for ((w, v) in m) {
            total += v
            if (v > max) { max = v; mejor = w }
        }
        return if (max >= minimo && max >= total * parte) mejor else null
    }

    // ---------- Perfil de errores ----------

    /**
     * Desarma el dedazo [escrito] → [final] letra por letra y anota cada parte según su tipo:
     * tecla cambiada, letra que no salió, letra de más, letras cruzadas o solo tildes.
     * Con [peso] negativo resta (deshiciste la corrección). Si hay más de 3 diferencias no
     * es un dedazo sino otra palabra: no se anota nada.
     */
    private fun anotarDedazo(escrito: String, final: String, peso: Float) {
        if (sinTildes(escrito) == sinTildes(final) && escrito.length == final.length) {
            sumarTipo(TIPO_TILDE, peso)
            cambios = true
            return
        }
        val ops = alinear(escrito, final) ?: return
        if (ops.isEmpty() || ops.size > 3) return
        for (op in ops) {
            when (op.tipo) {
                TIPO_VECINA -> {
                    val k = op.a * LETRAS + op.b
                    confusiones[k] = (confusiones[k] + peso).coerceIn(0f, 40f)
                }
                TIPO_FALTA -> faltas[op.b] = (faltas[op.b] + peso).coerceIn(0f, 40f)
                TIPO_SOBRA -> sobras[op.a] = (sobras[op.a] + peso).coerceIn(0f, 40f)
                TIPO_CRUCE -> cruces = (cruces + peso).coerceIn(0f, 60f)
            }
            sumarTipo(op.tipo, peso)
        }
        cambios = true
    }

    private fun sumarTipo(t: Int, peso: Float) {
        tipos[t] = (tipos[t] + peso).coerceIn(0f, 100_000f)
    }

    /**
     * Un espacio: [escrito] ("quebhago", "estoybien") quedó como [separado] ("que hago",
     * "estoy bien"). Se anota en qué letra cayó el dedo en lugar del espacio (o que no hubo
     * letra) y si estuvo bien ([peso] positivo) o mal (negativo: lo deshiciste).
     */
    private fun anotarEspacio(escrito: String, separado: String, peso: Float) {
        val i = letraDelEspacio(escrito, separado)
        if (i < 0) return
        if (peso >= 0f) {
            espacioSi[i] = minOf(60f, espacioSi[i] + peso)
            sumarTipo(TIPO_ESPACIO, peso)
        } else {
            espacioNo[i] = minOf(60f, espacioNo[i] - peso * 1.5f)
            espacioSi[i] = maxOf(0f, espacioSi[i] + peso)
            sumarTipo(TIPO_ESPACIO, peso)
        }
        cambios = true
    }

    /** Separar mientras escribías: [original] ("yanpode") quedó como [separado] ("ya pode") */
    @Synchronized
    fun aprenderEspacio(original: String, separado: String, bien: Boolean) {
        if (!listo) return
        anotarEspacio(normal(original), normal(separado), if (bien) 1f else -1f)
    }

    /** El espacio cruzado con una letra ("hol aamigo" → "hola amigo") se dejó o se deshizo */
    @Synchronized
    fun aprenderCruce(bien: Boolean) {
        if (!listo) return
        if (bien) {
            espacioSi[CRUCE] = minOf(60f, espacioSi[CRUCE] + 1f)
            sumarTipo(TIPO_CRUCE_ESPACIO, 1f)
        } else {
            espacioNo[CRUCE] = minOf(60f, espacioNo[CRUCE] + 1.5f)
            espacioSi[CRUCE] = maxOf(0f, espacioSi[CRUCE] - 1f)
            sumarTipo(TIPO_CRUCE_ESPACIO, -1f)
        }
        cambios = true
    }

    /**
     * Qué tanto confiar en poner un espacio en lugar de la letra [c] (null = palabras pegadas
     * sin letra en medio): 1 = sin datos (lo normal), menos de 1 = deshaces seguido esas
     * separaciones (más prudente), más de 1 = casi siempre las dejas (se anima más).
     */
    @Synchronized
    fun confianzaEspacio(c: Char?): Float {
        if (!listo) return 1f
        val i = if (c == null) LETRAS else letra(c)
        return if (i < 0) 1f else confianza(i)
    }

    /** Lo mismo, mirando en qué letra cayó el dedo: [escrito] ("quebhago") → [separado] ("que hago") */
    @Synchronized
    fun confianzaSeparar(escrito: String, separado: String): Float {
        if (!listo) return 1f
        val i = letraDelEspacio(normal(escrito), normal(separado))
        return if (i < 0) 1f else confianza(i)
    }

    /** Lo mismo para el espacio cruzado ("hol aamigo" → "hola amigo") */
    @Synchronized
    fun confianzaCruce(): Float = if (!listo) 1f else confianza(CRUCE)

    private fun confianza(i: Int): Float {
        val si = espacioSi[i]
        val no = espacioNo[i]
        if (si + no < 0.5f) return 1f
        // Se parte de "casi siempre está bien" (4 de 5) y lo tuyo lo va moviendo
        val p = (si + PREVIA_ESPACIO * 3f) / (si + no + 3f)
        return (p / PREVIA_ESPACIO).coerceIn(0.35f, 1.25f)
    }

    /**
     * Cuánto le cuesta a la autocorrección cada tipo de dedazo según los tuyos (1 = normal,
     * más chico = es un error tuyo de siempre). null si todavía no aprendió nada.
     */
    class Perfil(
        /** [escrita * LETRAS + querida] */
        val cambio: FloatArray?,
        /** letra que no salió */
        val falta: FloatArray?,
        /** letra de más */
        val sobra: FloatArray?,
        /** letras cruzadas */
        val cruce: Float,
    )

    @Synchronized
    fun perfil(): Perfil? {
        if (!listo) return null
        val cambio = factoresConfusion()
        val falta = factores(faltas)
        val sobra = factores(sobras)
        val cruce = if (cruces < 3f) 1f else (1f / (1f + (cruces - 2f) * 0.06f)).coerceAtLeast(0.55f)
        if (cambio == null && falta == null && sobra == null && cruce >= 1f) return null
        return Perfil(cambio, falta, sobra, cruce)
    }

    private fun factores(a: FloatArray): FloatArray? {
        var r: FloatArray? = null
        for (i in a.indices) {
            val v = a[i]
            if (v < 2f) continue
            if (r == null) r = FloatArray(a.size) { 1f }
            r[i] = (1f / (1f + (v - 1f) * 0.12f)).coerceAtLeast(0.5f)
        }
        return r
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
        // Perfil de errores y espacios (las versiones viejas ignoran estas secciones)
        sb.append("#f\n")
        for (i in faltas.indices) if (faltas[i] >= 0.5f) sb.append(i).append('\t').append(Math.round(faltas[i] * 100f) / 100f).append('\n')
        sb.append("#s\n")
        for (i in sobras.indices) if (sobras[i] >= 0.5f) sb.append(i).append('\t').append(Math.round(sobras[i] * 100f) / 100f).append('\n')
        sb.append("#t\n")
        sb.append("x\t").append(Math.round(cruces * 100f) / 100f).append('\n')
        for (i in tipos.indices) if (tipos[i] >= 0.5f) sb.append(i).append('\t').append(Math.round(tipos[i] * 100f) / 100f).append('\n')
        // Palabras reales que cambias por otra (las versiones viejas ignoran esta sección)
        sb.append("#r\n")
        for ((k, m) in reemplazos) for ((w, v) in m) {
            if (v >= 0.1f) sb.append(k).append('\t').append(w).append('\t').append(Math.round(v * 100f) / 100f).append('\n')
        }
        sb.append("#p\n")
        for (i in espacioSi.indices) {
            if (espacioSi[i] < 0.05f && espacioNo[i] < 0.05f) continue
            sb.append(i).append('\t').append(Math.round(espacioSi[i] * 100f) / 100f).append('\t')
                .append(Math.round(espacioNo[i] * 100f) / 100f).append('\n')
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
        borrarPerfil()
        entradas = 0
        try {
            if (archivo.exists()) leer(archivo.readText(Charsets.UTF_8))
        } catch (e: Throwable) {
            contextos.clear()
            errores.clear()
            ignoradas.clear()
            confusiones.fill(0f)
            borrarPerfil()
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
                'f', 's' -> if (p.size == 2) {
                    val i = p[0].toIntOrNull() ?: continue
                    val v = p[1].toFloatOrNull() ?: continue
                    val destino = if (seccion == 'f') faltas else sobras
                    if (i in 0 until LETRAS) destino[i] = v.coerceIn(0f, 40f)
                }
                't' -> if (p.size == 2) {
                    val v = p[1].toFloatOrNull() ?: continue
                    if (p[0] == "x") cruces = v.coerceIn(0f, 60f)
                    else {
                        val i = p[0].toIntOrNull() ?: continue
                        if (i in 0 until TIPOS) tipos[i] = v.coerceIn(0f, 100_000f)
                    }
                }
                'r' -> if (p.size == 3) {
                    val v = p[2].toFloatOrNull() ?: continue
                    if (v > 0f && p[0].contains('|') && p[1].isNotEmpty()) reemplazos.getOrPut(p[0]) { HashMap() }[p[1]] = v.coerceAtMost(30f)
                }
                'p' -> if (p.size == 3) {
                    val i = p[0].toIntOrNull() ?: continue
                    val si = p[1].toFloatOrNull() ?: continue
                    val no = p[2].toFloatOrNull() ?: continue
                    if (i in espacioSi.indices) {
                        espacioSi[i] = si.coerceIn(0f, 60f)
                        espacioNo[i] = no.coerceIn(0f, 60f)
                    }
                }
            }
        }
    }

    private fun borrarPerfil() {
        reemplazos.clear()
        faltas.fill(0f)
        sobras.fill(0f)
        tipos.fill(0f)
        espacioSi.fill(0f)
        espacioNo.fill(0f)
        cruces = 0f
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
        borrarPerfil()
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
        for (m in reemplazos.values) m.remove(w)
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
        /** Tipos de error del perfil */
        const val TIPO_VECINA = 0
        const val TIPO_FALTA = 1
        const val TIPO_SOBRA = 2
        const val TIPO_CRUCE = 3
        const val TIPO_TILDE = 4
        const val TIPO_ESPACIO = 5
        const val TIPO_CRUCE_ESPACIO = 6
        const val TIPOS = 7
        private val NOMBRES_TIPO = arrayOf(
            "tecla vecina", "letra que no salió", "letra de más", "letras cruzadas",
            "tildes", "espacio que faltó", "espacio cruzado",
        )
        /** Lo que suma cada letra corregida mientras escribes (poquito: pasa muy seguido) */
        private const val PESO_TECLA = 0.35f
        /** Posición del espacio cruzado en las cuentas de espacios */
        private const val CRUCE = LETRAS + 1
        /** Lo normal: 4 de cada 5 separaciones están bien */
        private const val PREVIA_ESPACIO = 0.8f

        /** Una parte de un dedazo: [tipo] con la letra escrita [a] y la querida [b] (-1 = ninguna) */
        class Op(val tipo: Int, val a: Int, val b: Int)

        /**
         * Desarma [escrito] → [final] en las operaciones mínimas (como se hace al revisar un
         * dedazo a mano): letra cambiada, letra que faltó, letra de más o dos letras cruzadas.
         * Las vocales con tilde cuentan como la misma letra. null si no son solo letras.
         */
        fun alinear(escrito: String, final: String): List<Op>? {
            val n = escrito.length
            val m = final.length
            if (n == 0 || m == 0 || n > 32 || m > 32 || Math.abs(n - m) > 3) return null
            val x = IntArray(n) { letra(escrito[it]) }
            val y = IntArray(m) { letra(final[it]) }
            for (v in x) if (v < 0) return null
            for (v in y) if (v < 0) return null
            val d = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) d[i][0] = i
            for (j in 0..m) d[0][j] = j
            for (i in 1..n) for (j in 1..m) {
                var v = d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1
                v = minOf(v, d[i - 1][j] + 1, d[i][j - 1] + 1)
                if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1] && x[i - 1] != x[i - 2]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
            }
            val ops = ArrayList<Op>()
            var i = n
            var j = m
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && x[i - 1] == y[j - 1] && d[i][j] == d[i - 1][j - 1]) { i--; j--; continue }
                if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1] && x[i - 1] != x[i - 2] &&
                    d[i][j] == d[i - 2][j - 2] + 1
                ) { ops.add(Op(TIPO_CRUCE, x[i - 2], x[i - 1])); i -= 2; j -= 2; continue }
                if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1) { ops.add(Op(TIPO_VECINA, x[i - 1], y[j - 1])); i--; j--; continue }
                if (i > 0 && d[i][j] == d[i - 1][j] + 1) { ops.add(Op(TIPO_SOBRA, x[i - 1], -1)); i--; continue }
                if (j > 0 && d[i][j] == d[i][j - 1] + 1) { ops.add(Op(TIPO_FALTA, -1, y[j - 1])); j--; continue }
                return null
            }
            ops.reverse()
            return ops
        }

        /**
         * En qué letra cayó el dedo en lugar del espacio: "quebhago" → "que hago" es la b,
         * "estoybien" → "estoy bien" no tuvo letra ([LETRAS]). -1 si no se entiende.
         */
        fun letraDelEspacio(escrito: String, separado: String): Int {
            var i = 0
            var tipo = -1
            for (j in separado.indices) {
                val c = separado[j]
                if (c == ' ') {
                    if (i >= escrito.length) return -1
                    val sig = if (j + 1 < separado.length) letra(separado[j + 1]) else -1
                    val e = letra(escrito[i])
                    if (e < 0) return -1
                    val t = if (e == sig) LETRAS else { i++; e }
                    if (tipo < 0) tipo = t
                    continue
                }
                if (i >= escrito.length || letra(escrito[i]) != letra(c)) return -1
                i++
            }
            return if (i == escrito.length) tipo else -1
        }

        private const val VERSION = "tecladoios-cerebro-1"
        private const val INICIO = "^"
        /** Días en que algo que ya no usas pierde la mitad de su peso */
        private const val VIDA_MEDIA = 180.0
        private const val MAX_ENTRADAS = 150_000
        private const val MAX_ERRORES = 6000
        /** Palabras reales que cambias por otra (contextos guardados como mucho) */
        private const val MAX_CAMBIOS = 4000
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
            var cambiosReales = 0
            val cuentaTipos = FloatArray(TIPOS)
            val prudentes = StringBuilder()
            val animadas = StringBuilder()
            for (linea in texto.lineSequence()) {
                if (linea.isEmpty()) continue
                if (linea.length == 2 && linea[0] == '#') { seccion = linea[1]; continue }
                when (seccion) {
                    'c' -> frases++
                    'e' -> errores++
                    'r' -> if (linea.startsWith("*|")) cambiosReales++
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
                    't' -> {
                        val p = linea.split('\t')
                        val i = p[0].toIntOrNull()
                        if (p.size == 2 && i != null && i in 0 until TIPOS) cuentaTipos[i] = p[1].toFloatOrNull() ?: 0f
                    }
                    'p' -> {
                        val p = linea.split('\t')
                        val i = p[0].toIntOrNull() ?: -1
                        val si = p.getOrNull(1)?.toFloatOrNull() ?: 0f
                        val no = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                        if (p.size == 3 && si + no >= 2f) {
                            val f = (si + PREVIA_ESPACIO * 3f) / (si + no + 3f) / PREVIA_ESPACIO
                            val nombre = when (i) {
                                in 0 until 26 -> ('a' + i).toString()
                                26 -> "ñ"
                                LETRAS -> "sin letra"
                                CRUCE -> "cruzado"
                                else -> null
                            }
                            if (nombre != null) {
                                val destino = if (f < 0.75f) prudentes else if (f > 1.1f) animadas else null
                                if (destino != null) { if (destino.isNotEmpty()) destino.append(", "); destino.append(nombre) }
                            }
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
            val conTeclas = (if (teclas > 0) " · $teclas teclas que sueles confundir" else "") +
                (if (cambiosReales > 0) " · $cambiosReales palabras que sueles cambiar por otra" else "")
            // Tus dedazos más comunes, de más a menos (solo si ya vio unos cuantos)
            val total = cuentaTipos.sum()
            val perfil = if (total < 8f) "" else {
                val orden = (0 until TIPOS).filter { cuentaTipos[it] >= 1f }.sortedByDescending { cuentaTipos[it] }.take(3)
                "\nTus errores más comunes: " + orden.joinToString(", ") {
                    NOMBRES_TIPO[it] + " " + Math.round(cuentaTipos[it] * 100f / total) + "%"
                }
            }
            val espacios = buildString {
                if (prudentes.isNotEmpty()) append("\nEspacios: más prudente con ").append(prudentes)
                if (animadas.isNotEmpty()) append(if (prudentes.isEmpty()) "\nEspacios: " else " · ").append("se anima con ").append(animadas)
            }
            return "$frases patrones de frases · $errores errores tuyos que ya reconoce$conTeclas\n" +
                "Autocorrección: $calibrada$perfil$espacios"
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
