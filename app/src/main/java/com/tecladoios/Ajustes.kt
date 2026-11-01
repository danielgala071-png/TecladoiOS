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
    fun tema(c: Context) = prefs(c).getString("tema", "auto") ?: "auto"
    /** "transparente", "normal" o "tintado" (como el control de Liquid Glass de iOS 27) */
    fun vidrio(c: Context) = prefs(c).getString("vidrio", "normal") ?: "normal"
    fun desenfoque(c: Context) = prefs(c).getBoolean("desenfoque", true)

    /** Sustitución de texto: (función rápida, frase) */
    fun atajos(c: Context): MutableList<Pair<String, String>> {
        val guardado = prefs(c).getString("atajos", null)
            ?: return mutableListOf("pq" to "porque", "tmb" to "también")
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
        for ((atajo, frase) in lista) arr.put(JSONArray().put(atajo).put(frase))
        prefs(c).edit().putString("atajos", arr.toString()).apply()
    }
}
