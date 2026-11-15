package com.tecladoios

/**
 * Arma el diccionario a partir de las listas de palabras. No usa nada de Android: así se
 * puede armar también al compilar (en GitHub) y meterlo ya listo dentro del APK.
 * [lineas] devuelve las líneas útiles de un archivo de assets (sin vacías ni comentarios).
 */
object ArmarDiccionario {

    const val ARCHIVO_LISTO = "diccionario_listo.bin"

    private fun palabrasDeFrases(lineas: (String) -> List<String>): List<String> {
        val res = LinkedHashSet<String>()
        for (l in lineas("bigramas.txt")) {
            val i = l.indexOf(':')
            if (i <= 0) continue
            for (parte in l.substring(0, i).split(' ') + l.substring(i + 1).split(',')) {
                val w = parte.trim()
                if (w.isNotEmpty() && w.all { it.isLetter() }) res.add(w)
            }
        }
        return res.toList()
    }

    fun armar(lineas: (String) -> List<String>): Diccionario {
        val frecuentes = lineas("frecuentes.txt")
        val peru = lineas("peruano.txt")
        val corte = peru.indexOf("@medio")
        val fijas = if (corte >= 0) peru.subList(0, corte) else peru
        val medio = if (corte >= 0) peru.subList(corte + 1, peru.size) else emptyList()
        val principal = ArrayList<String>(300_000)
        principal.addAll(frecuentes)
        var conFijas = false
        var conMedio = false
        for (w in lineas("diccionario.txt")) {
            if (w == "@fijas") { principal.addAll(fijas); conFijas = true; continue }
            principal.add(w)
            if (!conMedio && principal.size >= 30_000) { principal.addAll(medio); conMedio = true }
        }
        if (!conFijas) principal.addAll(fijas)
        if (!conMedio) principal.addAll(medio)
        // Toda palabra que el teclado puede predecir es válida (nunca se toma como error). Van
        // al final: si ya estaban, no cambian su frecuencia; si faltaban, solo dejan de corregirse
        principal.addAll(palabrasDeFrases(lineas))
        val ingles = lineas("ingles.txt")
        val portugues = lineas("portugues.txt")
        return Diccionario.armar(principal, listOf(ingles to 1.4f, portugues to 1.9f))
    }

    /** Líneas útiles de un texto (sin vacías ni comentarios #) */
    fun utiles(ls: Sequence<String>): List<String> =
        ls.map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }.toList()
}
