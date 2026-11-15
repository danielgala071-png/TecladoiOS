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
    /** Palabras que eliminaste de las sugerencias: no se sugieren ni se usan para corregir */
    val eliminadas = HashSet<String>()
    /** Palabras nuevas usadas una sola vez: si en la limpieza siguiente siguen igual, se olvidan */
    val enEspera = HashSet<String>()

    // ---------- Mis confusiones ----------
    /**
     * Letras que confundes: "ab" → cuántas veces pusiste «a» donde iba «b» (se cuenta cuando
     * eliges la corrección en la barra o al tocar una palabra mal escrita).
     */
    val confusiones = HashMap<String, Int>()

    /**
     * Descuento listo para el cerebro (sin candado): [escrita * 27 + correcta] = 0..0.6.
     * Cuantas más veces confundiste ese par, más barato le sale corregirlo.
     */
    @Volatile var descuento = FloatArray(27 * 27)
        private set

    private fun rehacerDescuento() {
        val d = FloatArray(27 * 27)
        for ((par, v) in confusiones) {
            if (par.length != 2) continue
            val a = Texto.indice(par[0]); val b = Texto.indice(par[1])
            if (a < 0 || b < 0) continue
            // 1 vez: 15 %, 2: 27 %, 3: 37 %… hasta 60 %
            d[a * 27 + b] = (0.6f * (1f - Math.pow(0.75, v.toDouble()).toFloat())).coerceIn(0f, 0.6f)
        }
        descuento = d
    }

    /**
     * Compara lo que escribiste con la corrección que elegiste: si son del mismo largo y
     * difieren en 1 o 2 letras, aprende esos pares («cosa» → «casa»: pones o por a).
     */
    @Synchronized
    fun confundir(escrito: String, correcto: String) {
        val a = Texto.normal(escrito); val b = Texto.normal(correcto)
        if (a.length != b.length || a == b || a.length < 2) return
        val pares = ArrayList<String>(2)
        for (i in a.indices) if (a[i] != b[i]) {
            pares.add("${a[i]}${b[i]}")
            if (pares.size > 2) return
        }
        // Dos letras cambiadas de lugar («cosa» ↔ «csoa») no es confusión de letras
        if (pares.size == 2 && pares[0][0] == pares[1][1] && pares[0][1] == pares[1][0]) return
        for (p in pares) confusiones[p] = minOf((confusiones[p] ?: 0) + 1, 50)
        if (confusiones.size > 400) confusiones.entries.minByOrNull { it.value }?.let { confusiones.remove(it.key) }
        rehacerDescuento()
        cambios++
    }

    /**
     * Limpieza semanal: olvida las palabras nuevas que aprendió una sola vez y no volviste a
     * usar en toda una semana (casi siempre son errores de dedo). Las que fijaste o usaste más
     * de una vez se quedan. Devuelve cuántas olvidó.
     */
    @Synchronized
    fun limpiar(): Int {
        var olvidadas = 0
        val sigue = HashSet<String>()
        for ((k, p) in propias.entries.toList()) {
            if (p.fija || p.veces > 1) continue
            if (k in enEspera) { propias.remove(k); uso.remove(k); olvidadas++ } else sigue.add(k)
        }
        enEspera.clear(); enEspera.addAll(sigue)
        cambios++
        return olvidadas
    }

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
        eliminadas.remove(k)   // si la agregas tú, vuelve a sugerirse
        val p = propias[k]
        if (p == null) propias[k] = Propia(palabra, 3, fija)
        else { p.veces = maxOf(p.veces, 3); if (fija) p.fija = true }
        cambios++
    }

    @Synchronized
    fun quitar(palabra: String) {
        val k = Texto.normal(palabra)
        propias.remove(k); uso.remove(k)
        for (m in siguientes.values) m.keys.removeAll { Texto.normal(it) == k }
        cambios++
    }

    /** Eliminar de las sugerencias y del cerebro (también palabras del diccionario) */
    @Synchronized
    fun eliminar(palabra: String) {
        quitar(palabra)
        eliminadas.add(Texto.normal(palabra))
        cambios++
    }

    @Synchronized
    fun restaurar(clave: String) { eliminadas.remove(Texto.normal(clave)); cambios++ }

    @Synchronized
    fun eliminada(clave: String) = eliminadas.isNotEmpty() && eliminadas.contains(clave)

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
        propias.clear(); uso.clear(); siguientes.clear(); rechazadas.clear(); eliminadas.clear()
        confusiones.clear(); rehacerDescuento()
        cambios++
        try { archivo.delete() } catch (_: Throwable) { }
    }

    /**
     * Guarda en el archivo. Solo se arma el texto con el candado puesto (rapidísimo, en memoria)
     * y el archivo se escribe ya sin candado: así escribir en el teclado nunca espera a que
     * termine de guardarse (antes podía dar un tironcito cada cierto número de palabras).
     */
    fun guardar() {
        try {
            val texto: String
            synchronized(this) {
                val sw = java.io.StringWriter(16 * 1024)
                sw.write("#TPA2\n"); escribir(sw)
                texto = sw.toString()
                cambios = 0
            }
            val tmp = File(archivo.parentFile, archivo.name + ".tmp")
            tmp.writeText(texto, Charsets.UTF_8)
            if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        } catch (e: Throwable) { Registro.anotar("guardar aprendizaje", e) }
    }

    /** Todo lo aprendido, en el mismo formato del archivo (para la copia de seguridad) */
    @Synchronized
    fun escribir(w: java.io.Writer) {
        for (p in propias.values) w.write("P\t${p.texto}\t${p.veces}\t${if (p.fija) 1 else 0}\n")
        for ((k, v) in uso) w.write("U\t$k\t$v\n")
        for ((k, m) in siguientes) for ((s, v) in m) w.write("S\t$k\t$s\t$v\n")
        for (r in rechazadas) w.write("R\t$r\n")
        for (x in eliminadas) w.write("X\t$x\n")
        for (x in enEspera) w.write("W\t$x\n")
        for ((k, v) in confusiones) w.write("C\t$k\t$v\n")
    }

    /** Suma una línea de una copia de seguridad a lo que ya sabe (gana el número mayor) */
    @Synchronized
    fun mezclar(l: String): Boolean {
        val p = l.split('\t')
        when (p[0]) {
            "P" -> if (p.size >= 4) {
                val k = Texto.normal(p[1]); val v = p[2].toIntOrNull() ?: 1
                val ya = propias[k]
                if (ya == null) propias[k] = Propia(p[1], v, p[3] == "1")
                else { ya.veces = maxOf(ya.veces, v); if (p[3] == "1") ya.fija = true }
            } else return false
            "U" -> if (p.size >= 3) uso[p[1]] = maxOf(uso[p[1]] ?: 0, p[2].toIntOrNull() ?: 1) else return false
            "S" -> if (p.size >= 4) {
                val m = siguientes.getOrPut(p[1]) { HashMap(4) }
                m[p[2]] = maxOf(m[p[2]] ?: 0, p[3].toIntOrNull() ?: 1)
            } else return false
            "R" -> if (p.size >= 2) rechazadas.add(p[1]) else return false
            "X" -> if (p.size >= 2) eliminadas.add(p[1]) else return false
            "C" -> if (p.size >= 3) { confusiones[p[1]] = maxOf(confusiones[p[1]] ?: 0, p[2].toIntOrNull() ?: 1); rehacerDescuento() } else return false
            else -> return false
        }
        cambios++
        return true
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
                        "X" -> if (p.size >= 2) eliminadas.add(p[1])
                        "W" -> if (p.size >= 2) enEspera.add(p[1])
                        "C" -> if (p.size >= 3) confusiones[p[1]] = p[2].toIntOrNull() ?: 1
                    }
                }
            }
            rehacerDescuento()
        } catch (e: Throwable) { Registro.anotar("leer aprendizaje", e) }
    }
}
