package com.tecladoios

import java.io.File

/**
 * Historial del portapapeles: lo que copias queda una hora; lo que fijas, para siempre.
 * Solo texto, guardado en la carpeta privada de la app.
 */
class Portapapeles(private val archivo: File) {

    class Elemento(val texto: String, val ms: Long, var fijo: Boolean)

    private val lista = ArrayList<Elemento>()

    companion object {
        const val DURACION_MS = 60 * 60 * 1000L
        const val MAX = 40
    }

    @Synchronized
    fun elementos(): List<Elemento> {
        limpiar()
        return lista.sortedWith(compareByDescending<Elemento> { it.fijo }.thenByDescending { it.ms })
    }

    @Synchronized
    fun ultimo(): Elemento? { limpiar(); return lista.maxByOrNull { it.ms } }

    @Synchronized
    fun agregar(texto: String) {
        val t = texto.take(5000)
        if (t.isBlank()) return
        val previo = lista.firstOrNull { it.texto == t }
        val fijo = previo?.fijo ?: false
        if (previo != null) lista.remove(previo)
        lista.add(Elemento(t, System.currentTimeMillis(), fijo))
        limpiar()
        guardar()
    }

    @Synchronized
    fun fijar(texto: String, si: Boolean) {
        lista.firstOrNull { it.texto == texto }?.fijo = si
        guardar()
    }

    @Synchronized
    fun quitar(texto: String) {
        lista.removeAll { it.texto == texto }
        guardar()
    }

    @Synchronized
    fun borrarNoFijos() {
        lista.removeAll { !it.fijo }
        guardar()
    }

    private fun limpiar() {
        val ahora = System.currentTimeMillis()
        lista.removeAll { !it.fijo && ahora - it.ms > DURACION_MS }
        while (lista.count { !it.fijo } > MAX) lista.remove(lista.filter { !it.fijo }.minByOrNull { it.ms })
    }

    private fun guardar() {
        try {
            archivo.bufferedWriter(Charsets.UTF_8).use { w ->
                for (e in lista) {
                    w.write("${e.ms}\t${if (e.fijo) 1 else 0}\t")
                    w.write(e.texto.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t"))
                    w.write("\n")
                }
            }
        } catch (e: Throwable) { Registro.anotar("guardar portapapeles", e) }
    }

    @Synchronized
    fun cargar() {
        if (!archivo.exists()) return
        try {
            archivo.bufferedReader(Charsets.UTF_8).useLines { ls ->
                for (l in ls) {
                    val p = l.split('\t', limit = 3)
                    if (p.size < 3) continue
                    val sb = StringBuilder()
                    var i = 0
                    val s = p[2]
                    while (i < s.length) {
                        val ch = s[i]
                        if (ch == '\\' && i + 1 < s.length) {
                            when (s[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(s[i + 1]) }
                            i += 2
                        } else { sb.append(ch); i++ }
                    }
                    lista.add(Elemento(sb.toString(), p[0].toLongOrNull() ?: 0L, p[1] == "1"))
                }
            }
            limpiar()
        } catch (e: Throwable) { Registro.anotar("leer portapapeles", e) }
    }
}
