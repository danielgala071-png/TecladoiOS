package com.tecladoios

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Todas las opciones del teclado, con los mismos nombres que en Ajustes > General > Teclado del iPhone. */
object Ajustes {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("tecladoios", Context.MODE_PRIVATE)

    private fun si(c: Context, clave: String, porDefecto: Boolean) = prefs(c).getBoolean(clave, porDefecto)

    // Todos los teclados
    fun mayusculas(c: Context) = si(c, "mayusculas", true)
    fun autocorregir(c: Context) = si(c, "autocorregir", true)
    fun revisarOrtografia(c: Context) = si(c, "ortografia", true)
    fun bloqMayus(c: Context) = si(c, "bloq_mayus", true)
    fun atajosActivos(c: Context) = si(c, "atajos_activos", true)
    fun sugerencias(c: Context) = si(c, "sugerencias", true)
    fun enLinea(c: Context) = si(c, "en_linea", false)
    fun puntuacionInteligente(c: Context) = si(c, "puntuacion", true)
    fun deslizar(c: Context) = si(c, "deslizar", true)
    fun borrarDeslizado(c: Context) = si(c, "borrar_deslizado", true)
    fun vistaPrevia(c: Context) = si(c, "vista_previa", true)
    fun puntoDoble(c: Context) = si(c, "punto_doble", true)
    /** Lo copiado aparece en la barra de sugerencias para pegarlo con un toque */
    fun portapapeles(c: Context) = si(c, "portapapeles", true)
    /** Las contraseñas que copias se borran solas del portapapeles al minuto */
    fun limpiarClaves(c: Context) = si(c, "limpiar_claves", true)
    /** Al cerrar con ? o ! se pone el signo de apertura (¿ ¡) al inicio, como se escribe en español */
    fun apertura(c: Context) = si(c, "apertura", true)
    /** Emojis en la barra de sugerencias según lo que escribes ("jaja" → 😂) */
    fun emojisBarra(c: Context) = si(c, "emojis_barra", true)

    // Teclado con una mano: "no", "izq" o "der"
    fun unaMano(c: Context) = prefs(c).getString("una_mano", "no") ?: "no"

    /** Tamaño del teclado en porcentaje (80 = más chico, 130 = más grande). Las letras se ajustan solas. */
    const val TAMANO_MIN = 80
    const val TAMANO_MAX = 130
    fun tamano(c: Context) = prefs(c).getInt("tamano", 100).coerceIn(TAMANO_MIN, TAMANO_MAX)

    // Respuesta del teclado
    fun vibrar(c: Context) = si(c, "vibrar", true)
    fun sonido(c: Context) = si(c, "sonido", false)

    /** Sustitución de texto: (función rápida, frase) */
    fun atajos(c: Context): MutableList<Pair<String, String>> {
        val p = prefs(c)
        val guardado = p.getString("atajos", null)
            ?: return ATAJOS_SUGERIDOS.toMutableList()
        val lista = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(guardado)
            for (i in 0 until arr.length()) {
                val par = arr.getJSONArray(i)
                lista.add(par.getString(0) to par.getString(1))
            }
        } catch (_: Exception) { }
        // Una sola vez: sumar las sustituciones nuevas sin tocar las tuyas
        if (!p.getBoolean("atajos_v5", false)) {
            val claves = lista.map { it.first.lowercase() }.toSet()
            lista += ATAJOS_SUGERIDOS.filter { it.first.lowercase() !in claves }
            guardarAtajos(c, lista)
            p.edit().putBoolean("atajos_v5", true).apply()
        }
        // Una sola vez: quitar las sustituciones que chocaban con palabras reales
        // (solo si siguen tal cual venían; si las cambiaste tú, se respetan)
        if (!p.getBoolean("atajos_v6", false)) {
            val antes = lista.size
            lista.removeAll { (atajo, frase) -> RETIRADOS[atajo.lowercase()] == frase }
            if (lista.size != antes) guardarAtajos(c, lista)
            p.edit().putBoolean("atajos_v6", true).apply()
        }
        return lista
    }

    fun guardarAtajos(c: Context, lista: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((atajo, frase) in lista.sortedBy { it.first.lowercase() }) arr.put(JSONArray().put(atajo).put(frase))
        prefs(c).edit().putString("atajos", arr.toString()).apply()
    }

    /**
     * Sustituciones que se quitaron porque cambiaban palabras que sí querías escribir:
     * "dnd" (también es "dónde"), "aver" (a veces es "haber"), "toy", "sta" e "ig".
     */
    private val RETIRADOS = mapOf(
        "dnd" to "de nada", "aver" to "a ver", "toy" to "estoy", "sta" to "está", "ig" to "Instagram",
    )

    /** Sustituciones comunes del chat peruano */
    val ATAJOS_SUGERIDOS = listOf(
        "pq" to "porque", "xq" to "porque", "tmb" to "también", "tb" to "también",
        "tqm" to "te quiero mucho", "tkm" to "te quiero mucho", "ntp" to "no te preocupes",
        "xfa" to "por favor", "cdt" to "cuídate", "bn" to "bien",
        "msj" to "mensaje", "salu2" to "saludos", "nd" to "nada", "dsp" to "después",
        "aki" to "aquí", "vdd" to "verdad", "grax" to "gracias",
        "q" to "que", "k" to "que", "xk" to "por qué", "bno" to "bueno", "tons" to "entonces",
        "ps" to "pues", "tbn" to "también", "kiero" to "quiero", "ke" to "que",
        // Más abreviaturas comunes del chat
        "pk" to "porque", "porq" to "porque", "pqe" to "porque", "tmbn" to "también", "tambn" to "también",
        "tmpc" to "tampoco", "tpc" to "tampoco", "dsps" to "después", "dps" to "después", "dspues" to "después",
        "mñn" to "mañana", "mñna" to "mañana", "hrs" to "horas", "sgte" to "siguiente",
        "aprox" to "aproximadamente", "tdo" to "todo", "tds" to "todos", "tdos" to "todos",
        "nc" to "no sé", "ns" to "no sé", "nse" to "no sé", "nose" to "no sé", "sbs" to "sabes",
        "stoy" to "estoy", "mxo" to "mucho",
        "msjs" to "mensajes", "tlf" to "teléfono", "qtal" to "qué tal", "ktal" to "qué tal",
        "hla" to "hola", "grs" to "gracias", "gcs" to "gracias", "grx" to "gracias", "pls" to "por favor",
        "plis" to "por favor", "bss" to "besos", "bdias" to "buenos días",
        "bnas" to "buenas", "bnoches" to "buenas noches", "bn8" to "buenas noches", "btardes" to "buenas tardes",
        "kien" to "quién", "kmo" to "cómo", "ksa" to "casa", "kasa" to "casa",
        "osea" to "o sea", "nomas" to "nomás", "hno" to "hermano", "hna" to "hermana", "nunk" to "nunca",
        "ntc" to "no te creas", "tqi" to "te quiero", "tq" to "te quiero",
        "cdte" to "cuídate",
        "cmo" to "cómo", "xfis" to "por favor", "xfavor" to "por favor",
        "sldos" to "saludos", "fds" to "fin de semana", "bday" to "cumpleaños",
        "felicidds" to "felicidades",
        // Chat peruano: más abreviaturas y formas rápidas
        "tqmm" to "te quiero mucho", "tmo" to "te amo", "lqm" to "lo quiero mucho", "porfa" to "por favor",
        "porfis" to "por favor", "xfas" to "por favor", "pf" to "por favor", "dnde" to "dónde",
        "dond" to "dónde", "cnd" to "cuando", "cdo" to "cuando", "kndo" to "cuando", "tdv" to "todavía",
        "tdvia" to "todavía", "xa" to "para", "pra" to "para", "kn" to "con", "cn" to "con", "sn" to "sin",
        "dsd" to "desde", "hta" to "hasta", "hst" to "hasta", "msm" to "mismo", "mjr" to "mejor",
        "vrdd" to "verdad", "vdad" to "verdad", "tas" to "estás",
        "stas" to "estás", "stamos" to "estamos", "ksi" to "casi", "kpo" to "capo", "gnial" to "genial",
        "dle" to "dale", "chvr" to "chévere", "chvre" to "chévere", "bkn" to "bacán", "slds" to "saludos",
        "atte" to "atentamente", "nro" to "número", "wsp" to "WhatsApp", "wasa" to "WhatsApp",
        "fb" to "Facebook", "yt" to "YouTube", "nsc" to "no sé",
        "ntp" to "no te preocupes", "mñ" to "mañana", "hy" to "hoy", "ayr" to "ayer", "ahi" to "ahí",
        "aorita" to "ahorita", "orita" to "ahorita", "fds" to "fin de semana",
        "hbd" to "¡Feliz cumpleaños!", "qepd" to "que en paz descanse", "bnd" to "bendiciones",
        "dtb" to "Dios te bendiga",
    ).distinctBy { it.first.lowercase() }
}
