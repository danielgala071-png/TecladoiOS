package com.tecladoios

import android.graphics.Path

/**
 * Esquinas "continuas" como las del iPhone: la curva empieza antes y termina
 * más suave que un redondeo normal, por eso se ve más elegante.
 */
object Formas {
    /**
     * Rectángulo con esquinas suaves de radio [radio]. Las esquinas de abajo pueden ser más
     * curvas ([abajoDer], [abajoIzq]; 0 = como las demás), como el 123 y el intro del iPhone.
     */
    fun suave(
        p: Path, l: Float, t: Float, r: Float, b: Float, radio: Float,
        arriba: Float = 0f, abajoDer: Float = 0f, abajoIzq: Float = 0f,
    ) {
        p.reset()
        val maxK = minOf((r - l) / 2f, (b - t) / 2f)
        fun k(v: Float) = minOf((if (v > 0f) v else radio) * 1.35f, maxK) // dónde empieza la curva
        val kTI = k(arriba)
        val kTD = k(arriba)
        val kBD = k(abajoDer)
        val kBI = k(abajoIzq)
        // Qué tan pegada a la esquina va la curva (más lejos de la esquina = más redonda)
        fun q(kk: Float, v: Float) = kk * (if (v > radio) 0.42f else 0.30f)
        val qTI = q(kTI, arriba)
        val qTD = q(kTD, arriba)
        val qBD = q(kBD, abajoDer)
        val qBI = q(kBI, abajoIzq)
        p.moveTo(l + kTI, t)
        p.lineTo(r - kTD, t)
        p.cubicTo(r - qTD, t, r, t + qTD, r, t + kTD)
        p.lineTo(r, b - kBD)
        p.cubicTo(r, b - qBD, r - qBD, b, r - kBD, b)
        p.lineTo(l + kBI, b)
        p.cubicTo(l + qBI, b, l, b - qBI, l, b - kBI)
        p.lineTo(l, t + kTI)
        p.cubicTo(l, t + qTI, l + qTI, t, l + kTI, t)
        p.close()
    }
}
