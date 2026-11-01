package com.tecladoios

import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Panel como el de iOS 26/27: esquinas de arriba curvas (donde empieza la barra
 * de sugerencias) y esquinas de abajo con el mismo radio que la pantalla.
 */
object KeyboardCorners {

    fun apply(root: View, alLeerRadio: (Float) -> Unit = {}, fallbackDp: Float = 42f) {
        val density = root.resources.displayMetrics.density
        var radio = fallbackDp * density
        val radioArriba = 26f * Medidas.escala(root.context)

        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                if (Build.VERSION.SDK_INT >= 33) {
                    val path = Path()
                    path.addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(
                        radioArriba, radioArriba, radioArriba, radioArriba,
                        radio, radio, radio, radio,
                    ), Path.Direction.CW)
                    outline.setPath(path)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radio)
                }
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
