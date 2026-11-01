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
        val guardado = prefs(c).getString("atajos", null)
            ?: return ATAJOS_SUGERIDOS.toMutableList()
        val lista = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(guardado)
            for (i in 0 until arr.length()) {
                val par = arr.getJSONArray(i)
                lista.add(par.getString(0) to par.getString(1))
            }
        } catch (_: Exception) { }
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
    ).distinctBy { it.first.lowercase() }
}
