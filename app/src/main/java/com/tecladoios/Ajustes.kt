package com.tecladoios

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Todas las opciones del teclado, con los mismos nombres que en iPhone. */
object Ajustes {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("tecladoios", Context.MODE_PRIVATE)

    fun vibrar(c: Context) = prefs(c).getBoolean("vibrar", true)
    fun sonido(c: Context) = prefs(c).getBoolean("sonido", false)
    fun vistaPrevia(c: Context) = prefs(c).getBoolean("vista_previa", true)
    fun sugerencias(c: Context) = prefs(c).getBoolean("sugerencias", true)
    fun autocorregir(c: Context) = prefs(c).getBoolean("autocorregir", true)
    fun mayusculas(c: Context) = prefs(c).getBoolean("mayusculas", true)
    fun puntoDoble(c: Context) = prefs(c).getBoolean("punto_doble", true)
    fun bloqMayus(c: Context) = prefs(c).getBoolean("bloq_mayus", true)
    fun atajosActivos(c: Context) = prefs(c).getBoolean("atajos_activos", true)
    /** "auto", "claro", "oscuro" o "amoled" (negro puro) */
    fun tema(c: Context) = prefs(c).getString("tema", "auto") ?: "auto"
    /** Combinar el color del teclado con la app que estás usando */
    fun adaptar(c: Context) = prefs(c).getBoolean("adaptar", true)
    /** Mayúsculas, borrar, 123 y enter con el color translúcido de la app */
    fun teclasColor(c: Context) = prefs(c).getBoolean("teclas_color", true)
    /** Mostrar lo que copias en la barra de sugerencias para pegarlo */
    fun portapapeles(c: Context) = prefs(c).getBoolean("portapapeles", true)
    fun iconoOculto(c: Context) = prefs(c).getBoolean("ocultar_icono", false)

    /** Una sola vez: pasar al nuevo diseño igual al del iPhone */
    fun migrarDiseno(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("diseno_v3", false)) {
            p.edit().putString("tema", "auto").putString("vidrio", "tintado").putBoolean("diseno_v3", true).apply()
        }
    }
    /** Altura de las teclas en % (80 a 130) */
    fun altura(c: Context) = prefs(c).getInt("altura", 100).coerceIn(80, 130)
    /** Tamaño de las letras en % (80 a 130) */
    fun letra(c: Context) = prefs(c).getInt("letra", 100).coerceIn(80, 130)
    /** "transparente", "normal" o "tintado" (como el control de Liquid Glass de iOS 27) */
    fun vidrio(c: Context) = prefs(c).getString("vidrio", "tintado") ?: "tintado"
    fun desenfoque(c: Context) = prefs(c).getBoolean("desenfoque", true)

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
        return lista
    }

    fun guardarAtajos(c: Context, lista: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((atajo, frase) in lista.sortedBy { it.first.lowercase() }) arr.put(JSONArray().put(atajo).put(frase))
        prefs(c).edit().putString("atajos", arr.toString()).apply()
    }

    /** Sustituciones comunes del chat peruano */
    val ATAJOS_SUGERIDOS = listOf(
        "pq" to "porque", "xq" to "porque", "tmb" to "también", "tb" to "también",
        "tqm" to "te quiero mucho", "tkm" to "te quiero mucho", "ntp" to "no te preocupes",
        "dnd" to "de nada", "xfa" to "por favor", "cdt" to "cuídate", "bn" to "bien",
        "msj" to "mensaje", "salu2" to "saludos", "nd" to "nada", "dsp" to "después",
        "aki" to "aquí", "vdd" to "verdad", "grax" to "gracias",
        "q" to "que", "k" to "que", "xk" to "por qué", "bno" to "bueno", "tons" to "entonces",
        "ps" to "pues", "tbn" to "también", "dnd" to "de nada", "kiero" to "quiero", "ke" to "que",
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
        "kien" to "quién", "kmo" to "cómo", "ksa" to "casa", "kasa" to "casa", "aver" to "a ver",
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
        "vrdd" to "verdad", "vdad" to "verdad", "toy" to "estoy", "tas" to "estás", "sta" to "está",
        "stas" to "estás", "stamos" to "estamos", "ksi" to "casi", "kpo" to "capo", "gnial" to "genial",
        "dle" to "dale", "chvr" to "chévere", "chvre" to "chévere", "bkn" to "bacán", "slds" to "saludos",
        "atte" to "atentamente", "nro" to "número", "wsp" to "WhatsApp", "wasa" to "WhatsApp",
        "ig" to "Instagram", "fb" to "Facebook", "yt" to "YouTube", "nsc" to "no sé",
        "ntp" to "no te preocupes", "mñ" to "mañana", "hy" to "hoy", "ayr" to "ayer", "ahi" to "ahí",
        "aorita" to "ahorita", "orita" to "ahorita", "fds" to "fin de semana",
        "hbd" to "¡Feliz cumpleaños!", "qepd" to "que en paz descanse", "bnd" to "bendiciones",
        "dtb" to "Dios te bendiga",
    ).distinctBy { it.first.lowercase() }
}
