package com.tecladoios

/**
 * Arma el diccionario a partir de las listas de palabras. No usa nada de Android: así se
 * puede armar también al compilar (en GitHub) y meterlo ya listo dentro del APK.
 * [lineas] devuelve las líneas útiles de un archivo de assets (sin vacías ni comentarios).
 */
object ArmarDiccionario {

    const val ARCHIVO_LISTO = "diccionario_listo.bin"

    /**
     * Ahorro de RAM: solo las palabras más usadas del diccionario general (antes entraban
     * ~260 000 más 100 000 de inglés y portugués). Las listas del Perú, los nombres de apps y
     * las palabras de las frases entran siempre, aparte de este tope.
     */
    const val MAX_PALABRAS = 50_000

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
        // Solo español latinoamericano general: sin la lista peruana (jerga y palabras del Perú)
        val peru = emptyList<String>()
        val corte = peru.indexOf("@medio")
        val fijas = if (corte >= 0) peru.subList(0, corte) else peru
        val medio = if (corte >= 0) peru.subList(corte + 1, peru.size) else emptyList()
        val principal = ArrayList<String>(300_000)
        principal.addAll(frecuentes)
        var conFijas = false
        var conMedio = false
        var conApps = false
        var generales = 0
        for (w in lineas("diccionario.txt")) {
            if (w == "@fijas") { principal.addAll(fijas); conFijas = true; continue }
            if (generales >= MAX_PALABRAS) break
            generales++
            principal.add(w)
            // Nombres de apps: entre las palabras de uso medio (se corrigen bien sin ganarle
            // a las palabras comunes)
            if (!conApps && principal.size >= 12_000) { principal.addAll(NombresApps.LISTA); conApps = true }
            if (!conMedio && principal.size >= 30_000) { principal.addAll(medio); conMedio = true }
        }
        if (!conApps) principal.addAll(NombresApps.LISTA)
        if (!conFijas) principal.addAll(fijas)
        if (!conMedio) principal.addAll(medio)
        // Toda palabra que el teclado puede predecir es válida (nunca se toma como error). Van
        // al final: si ya estaban, no cambian su frecuencia; si faltaban, solo dejan de corregirse
        principal.addAll(palabrasDeFrases(lineas))
        // Un solo idioma: español latinoamericano (sin inglés, portugués ni lista peruana)
        return Diccionario.armar(principal, emptyList())
    }

    /** Líneas útiles de un texto (sin vacías ni comentarios #) */
    fun utiles(ls: Sequence<String>): List<String> =
        ls.map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }.toList()
}
