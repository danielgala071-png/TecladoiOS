package com.tecladoios

import android.content.Context
import java.text.Normalizer

/**
 * Sugerencias, predicción de la siguiente palabra y autocorrección en español peruano.
 * Aprende las palabras que escribes y el orden en que las usas.
 */
class Diccionario(context: Context) {

    private val prefs = Ajustes.prefs(context)
    private val base = LinkedHashMap<String, Int>()
    private val baseMinuscula = HashSet<String>()
    private val normales = HashMap<String, String>()
    private val aprendidas = HashMap<String, Int>()
    private val pares = HashMap<String, HashMap<String, Int>>()
    private var revision = -1
    private var cambios = false

    init {
        val palabras = BASE.trim().split(Regex("\\s+"))
        palabras.forEachIndexed { i, p ->
            if (!base.containsKey(p)) {
                base[p] = palabras.size - i
                baseMinuscula.add(p.lowercase())
            }
        }
        cargar()
    }

    private fun normal(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARCAS, "")

    private fun normalDe(p: String) = normales.getOrPut(p) { normal(p) }

    private fun conoce(p: String): Boolean {
        val l = p.lowercase()
        return l in baseMinuscula || (aprendidas[l] ?: 0) >= 2
    }

    private fun igualarMayus(c: String, modelo: String): String = when {
        modelo.length > 1 && modelo.all { !it.isLetter() || it.isUpperCase() } -> c.uppercase()
        modelo.isNotEmpty() && modelo[0].isUpperCase() -> c.replaceFirstChar { it.uppercase() }
        else -> c
    }

    fun sugerir(prefijo: String, max: Int): List<String> {
        if (prefijo.isEmpty()) return emptyList()
        val np = normal(prefijo)
        val lp = prefijo.lowercase()
        val res = HashMap<String, Int>()
        for ((p, s) in base) {
            if (p.lowercase() != lp && normalDe(p).startsWith(np)) res[p] = s
        }
        for ((p, c) in aprendidas) {
            if (c >= 2 && p != lp && normalDe(p).startsWith(np)) res[p] = (res[p] ?: 0) + c * 40
        }
        return res.entries.sortedByDescending { it.value }
            .take(max)
            .map { igualarMayus(it.key, prefijo) }
    }

    /** Predicción de la siguiente palabra (barra después de un espacio) */
    fun siguientes(previa: String, max: Int): List<String> {
        val l = previa.lowercase()
        val res = LinkedHashMap<String, Int>()
        pares[l]?.forEach { (w, c) -> res[w] = c * 10 }
        val fijas = if (l.isEmpty()) INICIO else PARES_BASE[l]
        fijas?.forEachIndexed { i, w -> res[w] = (res[w] ?: 0) + (5 - i) }
        return res.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    /** Devuelve la corrección (por ahora: tildes que faltan) o null. */
    fun corregir(p: String): String? {
        if (p.length < 2 || conoce(p)) return null
        val np = normal(p)
        var mejor: String? = null
        var puntaje = -1
        for ((c, s) in base) {
            if (s > puntaje && normalDe(c) == np) { mejor = c; puntaje = s }
        }
        for ((c, n) in aprendidas) {
            if (n >= 2 && n * 40 > puntaje && normalDe(c) == np) { mejor = c; puntaje = n * 40 }
        }
        val r = mejor ?: return null
        val resultado = igualarMayus(r, p)
        return if (resultado == p) null else resultado
    }

    private fun valida(p: String) = p.length >= 1 && p.all { it.isLetter() }

    fun aprender(p: String, peso: Int = 1) {
        if (p.length < 2 || !valida(p)) return
        val l = p.lowercase()
        aprendidas[l] = (aprendidas[l] ?: 0) + peso
        cambios = true
    }

    fun aprenderPar(previa: String, palabra: String) {
        if (!valida(palabra) || (previa.isNotEmpty() && !valida(previa))) return
        val mapa = pares.getOrPut(previa.lowercase()) { HashMap() }
        val w = palabra.lowercase()
        mapa[w] = (mapa[w] ?: 0) + 1
        cambios = true
    }

    fun guardar() {
        if (!cambios) return
        val texto = aprendidas.entries
            .sortedByDescending { it.value }
            .take(5000)
            .joinToString("|") { "${it.key}:${it.value}" }
        val sb = StringBuilder()
        var n = 0
        loop@ for ((previa, mapa) in pares) {
            for ((w, c) in mapa) {
                if (n++ > 8000) break@loop
                sb.append(previa).append(SEP1).append(w).append(SEP1).append(c).append(SEP2)
            }
        }
        prefs.edit().putString("aprendidas", texto).putString("pares", sb.toString()).apply()
        cambios = false
    }

    fun recargarSiCambio() {
        if (prefs.getInt("aprendidas_rev", 0) != revision) {
            aprendidas.clear()
            pares.clear()
            cargar()
        }
    }

    private fun cargar() {
        revision = prefs.getInt("aprendidas_rev", 0)
        val texto = prefs.getString("aprendidas", "") ?: ""
        for (par in texto.split("|")) {
            val i = par.lastIndexOf(':')
            if (i <= 0) continue
            val n = par.substring(i + 1).toIntOrNull() ?: continue
            aprendidas[par.substring(0, i)] = n
        }
        val textoPares = prefs.getString("pares", "") ?: ""
        for (item in textoPares.split(SEP2)) {
            val partes = item.split(SEP1)
            if (partes.size != 3) continue
            val c = partes[2].toIntOrNull() ?: continue
            pares.getOrPut(partes[0]) { HashMap() }[partes[1]] = c
        }
        cambios = false
    }

    companion object {
        private val MARCAS = Regex("\\p{Mn}+")
        private const val SEP1 = "\u0001"
        private const val SEP2 = "\u0002"

        private val INICIO = listOf("hola", "yo", "gracias", "ya", "ok")

        private val PARES_BASE = mapOf(
            "que" to listOf("tal", "es", "no", "te", "haces"),
            "qué" to listOf("tal", "haces", "pasó", "es", "bien"),
            "de" to listOf("la", "que", "los", "nada", "verdad"),
            "te" to listOf("quiero", "amo", "extraño", "llamo", "aviso"),
            "buenos" to listOf("días"),
            "buenas" to listOf("noches", "tardes"),
            "muchas" to listOf("gracias"),
            "por" to listOf("favor", "qué", "eso", "fin", "la"),
            "estoy" to listOf("bien", "en", "yendo", "llegando", "cansado"),
            "hola" to listOf("cómo", "qué", "amor", "causa", "buenos"),
            "como" to listOf("estás", "está", "te", "así"),
            "cómo" to listOf("estás", "está", "te", "así"),
            "nos" to listOf("vemos", "vamos"),
            "a" to listOf("la", "las", "qué", "ver", "mí"),
            "en" to listOf("la", "el", "casa", "serio", "un"),
            "no" to listOf("sé", "te", "hay", "puedo", "pasa"),
            "yo" to listOf("también", "te", "no", "estoy", "sé"),
            "ya" to listOf("voy", "llegué", "está", "pe", "nos"),
            "voy" to listOf("a", "en", "para"),
            "gracias" to listOf("por", "amor", "causa"),
            "todo" to listOf("bien", "el"),
            "está" to listOf("bien", "en"),
            "estás" to listOf("bien", "en"),
            "dónde" to listOf("estás", "está"),
            "donde" to listOf("estás", "está"),
            "mi" to listOf("amor", "mamá", "papá", "casa"),
            "tal" to listOf("vez"),
            "lo" to listOf("siento", "que", "sé"),
            "un" to listOf("momento", "rato", "poco"),
            "me" to listOf("avisas", "gusta", "voy", "parece"),
        )

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
