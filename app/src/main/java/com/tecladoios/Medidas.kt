package com.tecladoios

import android.content.Context
import android.content.res.Configuration

/**
 * Tamaños proporcionales a la pantalla, como en iPhone (pantalla de 393 pt de ancho).
 * Así el teclado se ve igual sin importar el DPI que tenga configurado el celular.
 */
object Medidas {
    fun escala(c: Context): Float {
        val m = c.resources.displayMetrics
        return minOf(m.widthPixels, m.heightPixels) / 393f
    }

    fun horizontal(c: Context) =
        c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}
