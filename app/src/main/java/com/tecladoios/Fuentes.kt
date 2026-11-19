package com.tecladoios

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView

/**
 * Letra propia del teclado: Inter (muy parecida a la San Francisco del iPhone), incluida en
 * la app en 4 grosores (assets/fuentes). Así el teclado se ve igual en cualquier celular y no
 * usa la letra en negrita de HyperOS. Si por algo falta un archivo, usa la del sistema.
 */
object Fuentes {
    private var app: Context? = null
    private val cache = HashMap<Int, Typeface>()
    // Solo 2 grosores (menos memoria): 500 usa el normal y 700 el semi-negrita
    private val PESOS = intArrayOf(400, 600)

    fun iniciar(c: Context) {
        if (app == null) app = c.applicationContext
    }

    /** El grosor incluido más cercano al pedido */
    private fun cercano(peso: Int): Int = PESOS.minByOrNull { kotlin.math.abs(it - peso) } ?: 400

    fun tipo(peso: Int = 400): Typeface {
        val p = cercano(peso)
        return cache.getOrPut(p) {
            // Ahorro de RAM: la letra del sistema (ya está cargada en memoria por Android);
            // sin archivos de fuente propios dentro de la app
            Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), p, false)
        }
    }

    fun aplicar(p: Paint, peso: Int = 400) {
        p.typeface = tipo(peso)
        // Letras más nítidas: posición por subpíxel (los bordes de cada letra quedan finos y
        // parejos en vez de saltar al píxel más cercano) y sin el ajuste grueso del sistema
        p.isSubpixelText = true
        p.isLinearText = false
        p.hinting = Paint.HINTING_OFF
    }

    fun aplicar(t: TextView, peso: Int = 400) {
        t.typeface = tipo(peso)
    }
}
