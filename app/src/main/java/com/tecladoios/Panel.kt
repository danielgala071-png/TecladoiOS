package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.widget.LinearLayout

/**
 * Contenedor del teclado: la lámina de vidrio líquido del fondo (degradado teñido con el
 * color de la app y una luz suave de ese color arriba) y el borde del color de la app.
 * Los degradados se arman solo cuando cambia el tamaño o el color: dibujarlos no cuesta nada.
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

    /** Vidrio del fondo arriba y abajo, y la luz del color de la app (0 = sin vidrio) */
    var vidrioArriba = 0
        set(value) { field = value; armarVidrio() }
    var vidrioAbajo = 0
        set(value) { field = value; armarVidrio() }
    var luz = 0
        set(value) { field = value; armarVidrio() }

    private val pinturaVidrio = Paint()
    private val pinturaLuz = Paint()
    private val pinturaLuz2 = Paint()

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val borde = Path()
    private val esquina = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        armar(w, h)
        armarVidrio()
    }

    private fun armarVidrio() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        pinturaVidrio.shader = if (Color.alpha(vidrioArriba) == 0) null else
            LinearGradient(0f, 0f, 0f, h, vidrioArriba, vidrioAbajo, Shader.TileMode.CLAMP)
        val transparente = luz and 0x00FFFFFF
        // Luz del color de la app que baja desde arriba al centro (como el reflejo del cielo
        // en un vidrio) y otra, más tenue, que sube desde abajo a la izquierda
        pinturaLuz.shader = if (Color.alpha(luz) == 0) null else luzArriba(w, -w * LUZ_ARRIBA, luz)
        pinturaLuz2.shader = if (Color.alpha(luz) == 0) null else
            RadialGradient(w * 0.12f, h * 1.05f, maxOf(w * 0.45f, 1f),
                (luz and 0x00FFFFFF) or ((Color.alpha(luz) / 2) shl 24), transparente, Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pinturaVidrio.shader == null) return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, pinturaVidrio)
        if (pinturaLuz.shader != null) {
            canvas.drawRect(0f, 0f, w, h, pinturaLuz)
            canvas.drawRect(0f, 0f, w, h, pinturaLuz2)
        }
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

    companion object {
        /**
         * Dónde está la luz de arriba y qué tan grande es (en proporción al ancho). La barra
         * de sugerencias dibuja la misma luz en el mismo lugar: así barra y teclado son una
         * sola pieza de vidrio, sin corte entre las dos.
         */
        const val LUZ_ARRIBA = 0.03f
        const val LUZ_RADIO = 0.62f

        /** La luz de arriba con el centro en [cy] (en coordenadas de quien la dibuja) */
        fun luzArriba(w: Float, cy: Float, luz: Int): Shader = RadialGradient(
            w * 0.5f, cy, maxOf(w * LUZ_RADIO, 1f),
            intArrayOf(luz, (luz and 0x00FFFFFF) or ((Color.alpha(luz) / 3) shl 24), luz and 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
}
