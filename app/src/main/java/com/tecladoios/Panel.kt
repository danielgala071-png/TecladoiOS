package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.LinearLayout

/**
 * Contenedor del teclado: dibuja el borde con el color de la app que estás usando.
 * Con [radio] = 0 el teclado es recto arriba. Abajo queda recto y sin borde.
 */
class Panel(context: Context) : LinearLayout(context) {

    var colorBorde = 0
        set(value) { field = value; invalidate() }
    /** Halo suave del color de la app por dentro del filo (0 = sin halo) */
    var resplandor = 0
        set(value) { field = value; invalidate() }
    var radio = 0f
        set(value) { field = value; armar(width, height) }
    var grosor = 1f
        set(value) { field = value; armar(width, height) }
    /** El filo de arriba se esconde mientras la barra de sugerencias está a la vista */
    var lineaArriba = true
        set(value) { if (field != value) { field = value; armar(width, height) } }

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val borde = Path()
    private val esquina = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        armar(w, h)
    }

    private fun armar(w: Int, h: Int) {
        borde.reset()
        if (w <= 0 || h <= 0) return
        val g = grosor / 2f
        if (!lineaArriba) {
            // Solo los costados (siguen a los de la barra de sugerencias)
            borde.moveTo(g, 0f)
            borde.lineTo(g, h.toFloat())
            borde.moveTo(w - g, 0f)
            borde.lineTo(w - g, h.toFloat())
        } else if (radio <= 0f) {
            borde.moveTo(g, h.toFloat())
            borde.lineTo(g, g)
            borde.lineTo(w - g, g)
            borde.lineTo(w - g, h.toFloat())
        } else {
            val r = radio.coerceAtMost(w / 2f)
            borde.moveTo(g, h.toFloat())
            borde.lineTo(g, g + r)
            esquina.set(g, g, g + r * 2f, g + r * 2f)
            borde.arcTo(esquina, 180f, 90f, false)
            borde.lineTo(w - g - r, g)
            esquina.set(w - g - r * 2f, g, w - g, g + r * 2f)
            borde.arcTo(esquina, 270f, 90f, false)
            borde.lineTo(w - g, h.toFloat())
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (Color.alpha(colorBorde) == 0) return
        // Primero el halo (ancho y tenue: la mitad de afuera queda recortada, se ve solo por dentro)
        if (Color.alpha(resplandor) > 0) {
            pintura.color = resplandor
            pintura.strokeWidth = grosor * 8f
            canvas.drawPath(borde, pintura)
            pintura.strokeWidth = grosor * 4f
            canvas.drawPath(borde, pintura)
        }
        pintura.color = colorBorde
        pintura.strokeWidth = grosor
        canvas.drawPath(borde, pintura)
    }
}
