package com.tecladoios

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView

/**
 * Letra propia del teclado (Inter, muy parecida a la San Francisco del iPhone).
 * Así el teclado no usa la letra en negrita de HyperOS.
 * Si por algo no está la fuente, usa la del sistema.
 */
object Fuentes {
    private var app: Context? = null
    private val cache = HashMap<Int, Typeface>()
    private var hayInter: Boolean? = null

    fun iniciar(c: Context) {
        if (app == null) app = c.applicationContext
    }

    private fun interDisponible(c: Context): Boolean {
        hayInter?.let { return it }
        val ok = try { c.assets.open("inter.ttf").close(); true } catch (_: Exception) { false }
        hayInter = ok
        return ok
    }

    fun tipo(peso: Int = 400): Typeface = cache.getOrPut(peso) {
        val c = app
        val propia = if (c != null && interDisponible(c)) {
            try {
                Typeface.Builder(c.assets, "inter.ttf")
                    .setFontVariationSettings("'wght' $peso")
                    .setWeight(peso)
                    .setFallback("sans-serif")
                    .build()
            } catch (_: Exception) { null }
        } else null
        propia ?: Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), peso, false)
    }

    fun aplicar(p: Paint, peso: Int = 400) {
        p.typeface = tipo(peso)
        try { p.fontVariationSettings = "'wght' $peso" } catch (_: Exception) { }
    }

    fun aplicar(t: TextView, peso: Int = 400) {
        t.typeface = tipo(peso)
        try { t.fontVariationSettings = "'wght' $peso" } catch (_: Exception) { }
    }
}
