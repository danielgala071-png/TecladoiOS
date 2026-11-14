package com.tecladoios

import android.content.SharedPreferences

/**
 * Atajos de texto: escribes la clave ("bd") y al tocar espacio se pone la frase completa
 * ("Buenos días"). Si borras justo después, vuelve lo que escribiste.
 */
object Atajos {
    const val CLAVE = "atajos_texto"

    val PREDETERMINADOS = linkedMapOf(
        "bd" to "Buenos días",
        "bt" to "Buenas tardes",
        "bn" to "Buenas noches",
        "salu" to "Le saludo cordialmente",
        "tqm" to "Te quiero mucho",
        "ntp" to "No te preocupes",
    )

    fun leer(p: SharedPreferences): LinkedHashMap<String, String> {
        val s = p.getString(CLAVE, null) ?: return LinkedHashMap(PREDETERMINADOS)
        val m = LinkedHashMap<String, String>()
        for (l in s.split('\n')) {
            val i = l.indexOf('\t')
            if (i <= 0) continue
            val k = l.substring(0, i).trim().lowercase()
            val v = l.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
        }
        return m
    }

    fun guardar(p: SharedPreferences, m: Map<String, String>) {
        p.edit().putString(CLAVE, m.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    /** Clave válida: una sola "palabra" sin espacios */
    fun claveValida(k: String) = k.isNotEmpty() && k.length <= 20 && k.none { it.isWhitespace() }

    /** La frase para lo escrito, respetando la mayúscula inicial ("Bd" → "Buenos días") */
    fun expandir(m: Map<String, String>, escrito: String): String? {
        if (escrito.isEmpty() || m.isEmpty()) return null
        val v = m[escrito.lowercase()] ?: return null
        return if (escrito[0].isUpperCase() && v.isNotEmpty()) v[0].uppercaseChar() + v.substring(1) else v
    }
}
