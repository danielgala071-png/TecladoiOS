package com.tecladoios

import android.graphics.Path

/**
 * Esquinas "continuas" como las del iPhone: la curva empieza antes y termina
 * más suave que un redondeo normal, por eso se ve más elegante.
 */
object Formas {
    fun suave(p: Path, l: Float, t: Float, r: Float, b: Float, radio: Float) {
        p.reset()
        val k = minOf(radio * 1.35f, (r - l) / 2f, (b - t) / 2f) // dónde empieza la curva
        val q = k * 0.30f                                         // qué tan pegada a la esquina
        p.moveTo(l + k, t)
        p.lineTo(r - k, t)
        p.cubicTo(r - q, t, r, t + q, r, t + k)
        p.lineTo(r, b - k)
        p.cubicTo(r, b - q, r - q, b, r - k, b)
        p.lineTo(l + k, b)
        p.cubicTo(l + q, b, l, b - q, l, b - k)
        p.lineTo(l, t + k)
        p.cubicTo(l, t + q, l + q, t, l + k, t)
        p.close()
    }
}
