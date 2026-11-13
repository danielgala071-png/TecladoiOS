package com.tecladoios

import java.io.File

/**
 * Lo que el teclado aprende de ti, solo en el celular: tus palabras nuevas, cuánto usas cada
 * palabra, qué palabra sueles escribir después de otra y las correcciones que deshiciste.
 * Se guarda en un archivo de texto privado de la app.
 */
class Aprendizaje(private val archivo: File) {

    class Propia(val texto: String, var veces: Int, var fija: Boolean)

    /** Palabras que no están en el diccionario (clave sin tildes → palabra) */
    val propias = HashMap<String, Propia>()
    /** Cuántas veces usaste cada palabra del diccionario */
    val uso = HashMap<String, Int>()
    /** "anterior" o "ante anterior" → (siguiente → veces) */
    val siguientes = HashMap<String, HashMap<String, Int>>()
    /** Correcciones que deshiciste: "escrito>corregido" */
    val rechazadas = HashSet<String>()

    @Volatile var cambios = 0
        private set

    @Synchronized
    fun usar(palabra: String, enDiccionario: Boolean) {
        val k = Texto.normal(palabra)
        if (k.isEmpty()) return
        if (enDiccionario) {
            uso[k] = (uso[k] ?: 0) + 1
        } else {
            val p = propias[k]
            if (p == null) propias[k] = Propia(palabra, 1, false)
            else { p.veces++; if (p.texto != palabra && palabra[0].isLowerCase()) propias[k] = Propia(palabra, p.veces, p.fija) }
        }
        cambios++
        recortar()
    }

    /** Palabra propia agregada a mano o al deshacer una corrección: cuenta de inmediato */
    @Synchronized
    fun agregar(palabra: String, fija: Boolean = false) {
        val k = Texto.normal(palabra)
        if (k.isEmpty()) return
        val p = propias[k]
        if (p == null) propias[k] = Propia(palabra, 3, fija)
        else { p.veces = maxOf(p.veces, 3); if (fija) p.fija = true }
        cambios++
    }

    @Synchronized
    fun quitar(palabra: String) {
        val k = Texto.normal(palabra)
        propias.remove(k); uso.remove(k)
        for (m in siguientes.values) m.remove(palabra)
        cambios++
    }

    /** ¿Ya la usaste lo suficiente como para sugerirla y no corregirla? */
    @Synchronized
    fun conocida(clave: String): Propia? = propias[clave]?.takeIf { it.veces >= 2 || it.fija }

    @Synchronized
    fun veces(clave: String): Int = uso[clave] ?: propias[clave]?.veces ?: 0

    @Synchronized
    fun siguiente(contexto: List<String>, palabra: String) {
        if (contexto.isEmpty()) return
        val a = Texto.normal(contexto.last())
        sumarSig(a, palabra)
        if (contexto.size >= 2) sumarSig(Texto.normal(contexto[contexto.size - 2]) + " " + a, palabra)
        cambios++
    }

    private fun sumarSig(clave: String, palabra: String) {
        val m = siguientes.getOrPut(clave) { HashMap(4) }
        m[palabra] = (m[palabra] ?: 0) + 1
        if (m.size > 24) {
            val menor = m.minByOrNull { it.value }?.key
            if (menor != null) m.remove(menor)
        }
    }

    @Synchronized
    fun predecir(contexto: List<String>): List<Pair<String, Int>> {
        val res = HashMap<String, Int>()
        if (contexto.size >= 2) {
            val k = Texto.normal(contexto[contexto.size - 2]) + " " + Texto.normal(contexto.last())
            siguientes[k]?.forEach { (w, v) -> res[w] = (res[w] ?: 0) + v * 3 }
        }
        if (contexto.isNotEmpty()) siguientes[Texto.normal(contexto.last())]?.forEach { (w, v) -> res[w] = (res[w] ?: 0) + v }
        return res.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
    }

    @Synchronized
    fun rechazar(escrito: String, corregido: String) {
        rechazadas.add(Texto.normal(escrito) + ">" + Texto.normal(corregido))
        cambios++
    }

    @Synchronized
    fun rechazada(escrito: String, corregido: String) =
        rechazadas.contains(Texto.normal(escrito) + ">" + Texto.normal(corregido))

    private fun recortar() {
        if (propias.size > 12000) {
            val fuera = propias.entries.filter { !it.value.fija && it.value.veces < 2 }.take(propias.size - 10000)
            for (e in fuera) propias.remove(e.key)
        }
        if (siguientes.size > 30000) {
            val fuera = siguientes.keys.take(siguientes.size - 25000)
            for (k in fuera) siguientes.remove(k)
        }
        if (uso.size > 40000) {
            val fuera = uso.entries.filter { it.value < 2 }.take(uso.size - 35000)
            for (e in fuera) uso.remove(e.key)
        }
    }

    @Synchronized
    fun borrarTodo() {
        propias.clear(); uso.clear(); siguientes.clear(); rechazadas.clear()
        cambios++
        try { archivo.delete() } catch (_: Throwable) { }
    }

    @Synchronized
    fun guardar() {
        try {
            val tmp = File(archivo.parentFile, archivo.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("#TPA2\n")
                for (p in propias.values) w.write("P\t${p.texto}\t${p.veces}\t${if (p.fija) 1 else 0}\n")
                for ((k, v) in uso) w.write("U\t$k\t$v\n")
                for ((k, m) in siguientes) for ((s, v) in m) w.write("S\t$k\t$s\t$v\n")
                for (r in rechazadas) w.write("R\t$r\n")
            }
            if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
            cambios = 0
        } catch (e: Throwable) { Registro.anotar("guardar aprendizaje", e) }
    }

    @Synchronized
    fun cargar() {
        if (!archivo.exists()) return
        try {
            archivo.bufferedReader(Charsets.UTF_8).useLines { ls ->
                for (l in ls) {
                    val p = l.split('\t')
                    when (p[0]) {
                        "P" -> if (p.size >= 4) propias[Texto.normal(p[1])] = Propia(p[1], p[2].toIntOrNull() ?: 1, p[3] == "1")
                        "U" -> if (p.size >= 3) uso[p[1]] = p[2].toIntOrNull() ?: 1
                        "S" -> if (p.size >= 4) siguientes.getOrPut(p[1]) { HashMap(4) }[p[2]] = p[3].toIntOrNull() ?: 1
                        "R" -> if (p.size >= 2) rechazadas.add(p[1])
                    }
                }
            }
        } catch (e: Throwable) { Registro.anotar("leer aprendizaje", e) }
    }
}
