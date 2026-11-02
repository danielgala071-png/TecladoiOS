package com.tecladoios

import android.graphics.Outline
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Panel como el de iOS 26/27: esquinas de arriba curvas (donde empieza la barra
 * de sugerencias) y la parte de abajo recta, cubriendo todo hasta el borde.
 */
object KeyboardCorners {

    fun apply(root: View, alLeerRadio: (Float) -> Unit = {}, fallbackDp: Float = 42f, radioArribaDp: Float = 30f) {
        val density = root.resources.displayMetrics.density
        var radio = fallbackDp * density
        val radioArriba = radioArribaDp * Medidas.escala(root.context)

        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Solo se redondea arriba: el rectángulo baja más allá del borde,
                // así las esquinas de abajo quedan fuera y el fondo llega recto hasta el final
                val extra = radioArriba.toInt() + 2
                outline.setRoundRect(0, 0, view.width, view.height + extra, radioArriba)
            }
        }
        root.clipToOutline = true

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post {
                    radio = leerRadio(v) ?: (fallbackDp * density)
                    v.invalidateOutline()
                    alLeerRadio(radio)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun leerRadio(v: View): Float? {
        val insets = v.rootWindowInsets ?: return null
        val izq = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        val der = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
        val r = maxOf(izq, der)
        return if (r > 0) r.toFloat() else null
    }
}
