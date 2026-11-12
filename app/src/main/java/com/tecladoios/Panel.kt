package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.widget.LinearLayout

/**
 * Contenedor del teclado: dibuja el borde con el color de la app que estás usando.
 * Con [radio] = 0 el teclado es recto arriba. Abajo queda recto y sin borde.
 */
class Panel(context: Context) : LinearLayout(context) {

    var colorBorde = 0
        set(value) { field = value; invalidate() }
    /** Colores fundidos del filo: el de la app y su compañero (0 = un solo color) */
    var colorApp = 0
        set(value) { field = value; armar(width, height) }
    var colorCompanero = 0
        set(value) { field = value; armar(width, height) }
    private var shaderFilo: Shader? = null
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

    /**
     * Destello al enviar (el toque propio del teclado): el filo del color de la app se enciende
     * y se apaga suave, como si el mensaje saliera por el borde. Solo repinta el filo.
     */
    private var destello = 0f
    private var animDestello: android.animation.ValueAnimator? = null

    fun destellar() {
        if (!isShown || Color.alpha(colorBorde) == 0) return
        animDestello?.cancel()
        animDestello = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = Movimiento.DESTELLO
            interpolator = Movimiento.SUAVE
            addUpdateListener {
                destello = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
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
        shaderFilo = if (colorApp == 0 || colorCompanero == 0) null else degradadoFusion(w.toFloat(), colorApp, colorCompanero)
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
        val fundido = shaderFilo
        if (Color.alpha(resplandor) > 0) {
            if (fundido != null) {
                pintura.shader = fundido
                pintura.color = Color.BLACK
                pintura.alpha = Color.alpha(resplandor)
            } else pintura.color = resplandor
            pintura.strokeWidth = grosor * 8f
            canvas.drawPath(borde, pintura)
            pintura.strokeWidth = grosor * 4f
            canvas.drawPath(borde, pintura)
        }
        if (fundido != null) {
            pintura.shader = fundido
            pintura.color = Color.BLACK
            pintura.alpha = Color.alpha(colorBorde)
        } else pintura.color = colorBorde
        pintura.strokeWidth = grosor
        canvas.drawPath(borde, pintura)
        if (destello > 0f) {
            // Luz ancha y tenue por dentro del filo + el filo a toda intensidad
            pintura.shader = null
            pintura.color = (colorBorde and 0x00FFFFFF) or ((0x55 * destello).toInt() shl 24)
            pintura.strokeWidth = grosor * 7f
            canvas.drawPath(borde, pintura)
            pintura.color = colorBorde or (0xFF shl 24)
            pintura.alpha = (255 * destello).toInt().coerceIn(0, 255)
            pintura.strokeWidth = grosor * 1.8f
            canvas.drawPath(borde, pintura)
        }
        pintura.shader = null
    }

    companion object {
        /**
         * Colores fundidos de lado a lado: el color de la app a la izquierda que se va
         * mezclando con su compañero hacia la derecha (hasta un 60 %, igual que las teclas).
         */
        fun degradadoFusion(ancho: Float, app: Int, companero: Int): Shader {
            val a = app or (0xFF shl 24)
            return LinearGradient(0f, 0f, maxOf(ancho, 1f), 0f,
                intArrayOf(a, Tema.mezclar(a, companero, 0.3f), Tema.mezclar(a, companero, 0.6f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
    }
}
