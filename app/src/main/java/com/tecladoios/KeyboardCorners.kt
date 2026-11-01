package com.tecladoios

import android.graphics.Outline
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Panel con las 4 esquinas curvas (como el teclado de iOS 27), usando el
 * mismo radio que las esquinas de la pantalla del celular.
 */
object KeyboardCorners {

    fun apply(root: View, alLeerRadio: (Float) -> Unit = {}, fallbackDp: Float = 42f) {
        val density = root.resources.displayMetrics.density
        var radio = fallbackDp * density

        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radio)
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
