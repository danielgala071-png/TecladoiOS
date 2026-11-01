package com.tecladoios

import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView

/**
 * Letra fina como la de iPhone. HyperOS aplica su propio grosor (negrita) a la letra
 * del sistema; aquí se fuerza el grosor real con la variación 'wght' de la fuente.
 */
object Fuentes {
    fun tipo(peso: Int = 400): Typeface =
        Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), peso, false)

    fun aplicar(p: Paint, peso: Int = 400) {
        p.typeface = tipo(peso)
        try { p.fontVariationSettings = "'wght' $peso" } catch (_: Exception) { }
    }

    fun aplicar(t: TextView, peso: Int = 400) {
        t.typeface = tipo(peso)
        try { t.fontVariationSettings = "'wght' $peso" } catch (_: Exception) { }
    }
}
