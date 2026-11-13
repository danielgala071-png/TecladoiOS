package com.tecladoios

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Escribir deslizando el dedo por las letras. Compara el trazo con el camino \"ideal\" de cada
 * palabra posible (de su primera a su última letra, pasando por todas en orden) y elige las
 * que más se parecen, pesando también qué tan común es cada una.
 */
class Deslizar(private val motor: Motor) {

    private val dic get() = motor.dic
    private val geo get() = motor.geo

    /** Palabras agrupadas por primera y última letra (solo las más usadas, para ir rápido) */
    private val grupos: Array<IntArray> by lazy { armarGrupos() }

    private fun armarGrupos(): Array<IntArray> {
        val listas = Array(27 * 27) { ArrayList<Int>() }
        val lim = minOf(dic.total, 90000)
        for (id in 0 until lim) {
            val k = dic.claves[id]
            if (k.length < 2) continue
            val a = Texto.indice(k[0]); val b = Texto.indice(k[k.length - 1])
            if (a < 0 || b < 0) continue
            if (!k.all { Texto.indice(it) >= 0 }) continue
            listas[a * 27 + b].add(id)
        }
        return Array(listas.size) { listas[it].toIntArray() }
    }

    fun preparar() { grupos.size }

    private val N = 48

    /** Reparte el trazo en [n] puntos a la misma distancia */
    private fun remuestrear(xs: FloatArray, ys: FloatArray, cuantos: Int, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        var largo = 0f
        for (i in 1 until cuantos) largo += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (largo <= 0f || cuantos < 2) {
            for (i in 0 until n) { out[2 * i] = xs[0]; out[2 * i + 1] = ys[0] }
            return out
        }
        val paso = largo / (n - 1)
        var acum = 0f
        var k = 0
        out[0] = xs[0]; out[1] = ys[0]; k = 1
        var px = xs[0]; var py = ys[0]
        var i = 1
        while (i < cuantos && k < n) {
            val dx = xs[i] - px; val dy = ys[i] - py
            val d = hypot(dx, dy)
            if (acum + d >= paso && d > 0f) {
                val t = (paso - acum) / d
                px += dx * t; py += dy * t
                out[2 * k] = px; out[2 * k + 1] = py
                k++
                acum = 0f
            } else {
                acum += d
                px = xs[i]; py = ys[i]
                i++
            }
        }
        while (k < n) { out[2 * k] = xs[cuantos - 1]; out[2 * k + 1] = ys[cuantos - 1]; k++ }
        return out
    }

    private fun letrasCerca(x: Float, y: Float, radio: Float): List<Int> {
        val r = ArrayList<Int>(4)
        for (i in 0 until 27) if (geo.hay[i] && hypot((x - geo.x[i]) / geo.ancho, (y - geo.y[i]) / geo.alto) <= radio) r.add(i)
        return r
    }

    /**
     * Las mejores palabras para el trazo ([xs], [ys], [cuantos] puntos en píxeles),
     * de la más probable a la menos.
     */
    fun decodificar(xs: FloatArray, ys: FloatArray, cuantos: Int, contexto: List<String>): List<String> {
        if (cuantos < 2) return emptyList()
        val tr = remuestrear(xs, ys, cuantos, N)
        val denso = remuestrear(xs, ys, cuantos, 96)
        val inicios = letrasCerca(xs[0], ys[0], 1.05f)
        val finales = letrasCerca(xs[cuantos - 1], ys[cuantos - 1], 1.05f)
        if (inicios.isEmpty() || finales.isEmpty()) return emptyList()

        val mejores = ArrayList<Pair<String, Float>>(32)
        val ideal = FloatArray(N * 2)
        val cx = FloatArray(64); val cy = FloatArray(64)

        fun evaluar(clave: String, palabra: String, frecuencia: Float) {
            // Letras seguidas iguales cuentan una vez ("llamar" → l-a-m-a-r)
            var m = 0
            for (ch in clave) {
                val i = Texto.indice(ch)
                if (i < 0 || !geo.hay[i]) return
                if (m > 0 && cx[m - 1] == geo.x[i] && cy[m - 1] == geo.y[i]) continue
                if (m >= 63) return
                cx[m] = geo.x[i]; cy[m] = geo.y[i]; m++
            }
            // Cada letra tiene que quedar cerca del trazo, en orden
            var p = 0
            for (j in 0 until m) {
                var ok = false
                while (p < 96) {
                    val d = hypot((denso[2 * p] - cx[j]) / geo.ancho, (denso[2 * p + 1] - cy[j]) / geo.alto)
                    if (d <= 0.95f) { ok = true; break }
                    p++
                }
                if (!ok) return
            }
            // Camino ideal remuestreado y distancia punto a punto
            if (m == 1) { for (i in 0 until N) { ideal[2 * i] = cx[0]; ideal[2 * i + 1] = cy[0] } }
            else remuestrearEn(cx, cy, m, ideal)
            var suma = 0f
            for (i in 0 until N) {
                val dx = (tr[2 * i] - ideal[2 * i]) / geo.ancho
                val dy = (tr[2 * i + 1] - ideal[2 * i + 1]) / geo.alto
                suma += sqrt(dx * dx + dy * dy)
            }
            val forma = suma / N
            val puntaje = forma * 4.2f + frecuencia * 0.33f
            mejores.add(palabra to puntaje)
        }

        for (a in inicios) for (b in finales) for (id in grupos[a * 27 + b]) {
            evaluar(dic.claves[id], dic.palabras[id], motor.confianzaDe(dic.palabras[id]))
        }
        // Tus palabras propias también se pueden deslizar
        val propias = synchronized(motor.aprendido) { motor.aprendido.propias.values.filter { it.veces >= 2 || it.fija }.toList() }
        for (pp in propias) {
            val k = Texto.normal(pp.texto)
            if (k.length < 2) continue
            val a = Texto.indice(k[0]); val b = Texto.indice(k[k.length - 1])
            if (a in inicios && b in finales) evaluar(k, pp.texto, 2.5f)
        }
        if (mejores.isEmpty()) return emptyList()
        // Contexto: la palabra que suele ir después pesa un poco más
        val pred = motor.predecir(contexto, false).map { it.lowercase() }
        val ordenadas = mejores.map { (w, p) ->
            val i = pred.indexOf(w.lowercase())
            w to (if (i >= 0) p - 0.5f + i * 0.1f else p)
        }.sortedBy { it.second }
        val vistos = HashSet<String>()
        return ordenadas.map { it.first }.filter { vistos.add(it.lowercase()) }.take(5)
    }

    private fun remuestrearEn(xs: FloatArray, ys: FloatArray, cuantos: Int, out: FloatArray) {
        val r = remuestrear(xs, ys, cuantos, N)
        System.arraycopy(r, 0, out, 0, N * 2)
    }
}
