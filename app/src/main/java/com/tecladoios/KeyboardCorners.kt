package com.tecladoios

import android.graphics.Outline
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Arriba recto y solo las esquinas inferiores curvas, con el mismo radio
 * que las esquinas de la pantalla del celular.
 */
object KeyboardCorners {

    fun apply(root: View, alLeerRadio: (Float) -> Unit = {}, fallbackDp: Float = 42f) {
        val density = root.resources.displayMetrics.density
        var radio = fallbackDp * density

        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // El rectángulo empieza más arriba: solo se redondean las de abajo
                val r = radio.toInt()
                outline.setRoundRect(0, -r, view.width, view.height, radio)
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
