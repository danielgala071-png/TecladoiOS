package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

/** Las letras con acento que salen al mantener presionada una tecla (á, é, ñ…). */
class VariantesView(context: Context) : View(context) {

    var opciones: List<String> = emptyList()
    var seleccion = 0
        set(value) {
            if (value == field) return
            field = value
            // La cápsula se desliza hasta el acento nuevo (no salta)
            animMarca?.cancel()
            animMarca = android.animation.ValueAnimator.ofFloat(posMarca, value.toFloat()).apply {
                duration = 170
                interpolator = Movimiento.DESLIZAR
                addUpdateListener { posMarca = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    /** Dónde está la cápsula ahora (en celdas; se mueve suave entre una y otra) */
    private var posMarca = 0f
    private var animMarca: android.animation.ValueAnimator? = null

    /** Pone la selección sin animar (al abrir el menú) */
    fun fijarSeleccion(i: Int) {
        animMarca?.cancel()
        seleccion = i
        animMarca?.cancel()
        posMarca = i.toFloat()
        invalidate()
    }
    var celda = 0f
    var altoCelda = 0f
    var oscuro = false
    /** Color de la app: la opción elegida se marca con ese color */
    var acento = 0xFF007AFF.toInt()

    private val d = resources.displayMetrics.density
    val pad get() = 10f * d
    val margen get() = 4f * d

    fun anchoTotal() = (celda * opciones.size + (pad + margen) * 2).toInt()
    fun altoTotal() = (altoCelda + (pad + margen) * 2).toInt()

    private val rect = RectF()
    private val celdaRect = RectF()
    private val cuerpo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, 0.7f * d)
    }
    /** Los degradados se arman una vez (no en cada cuadro) */
    private var fondoAlto = -1
    private var fondoOscuro = false
    private val fmTmp = Paint.FontMetrics()
    private val marca = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }.also { Fuentes.aplicar(it, 400) }


    override fun onDraw(c: Canvas) {
        if (opciones.isEmpty()) return
        rect.set(pad, pad, width - pad, height - pad)
        val radio = altoCelda * 0.32f
        // Grafito como la burbuja de las teclas: un tono más claro arriba, sombra suave y un
        // filo de luz finito (el mismo lenguaje de todo el teclado, sin brillos metálicos)
        if (fondoAlto != height || fondoOscuro != oscuro) {
            fondoAlto = height
            fondoOscuro = oscuro
            val arriba = if (oscuro) 0xFF333337.toInt() else 0xF5FBFBFD.toInt()
            val abajo = if (oscuro) 0xFF2A2A2D.toInt() else 0xF0E8EAEF.toInt()
            cuerpo.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, arriba, abajo, Shader.TileMode.CLAMP)
            cuerpo.setShadowLayer(14f * d, 0f, 4f * d, 0x66000000)
            borde.shader = if (oscuro) LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(0x40FFFFFF, 0x14FFFFFF, 0x0DFFFFFF), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP) else null
            if (!oscuro) borde.color = 0xB3FFFFFF.toInt()
        }
        c.drawRoundRect(rect, radio, radio, cuerpo)
        c.drawRoundRect(rect, radio, radio, borde)

        texto.textSize = altoCelda * 0.5f
        val fm = fmTmp
        texto.getFontMetrics(fm)
        val izq = pad + margen
        val cy = pad + margen + altoCelda / 2f
        // La cápsula del color de la app, en su posición animada
        val xm = izq + posMarca * celda
        celdaRect.set(xm + 1.5f * d, pad + margen, xm + celda - 1.5f * d, pad + margen + altoCelda)
        marca.color = acento
        c.drawRoundRect(celdaRect, radio * 0.75f, radio * 0.75f, marca)
        opciones.forEachIndexed { i, op ->
            val x = izq + i * celda
            val elegida = Math.abs(i - posMarca) < 0.5f
            texto.color = when {
                elegida -> if ((0.299 * Color.red(acento) + 0.587 * Color.green(acento) + 0.114 * Color.blue(acento)) > 175) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                oscuro -> 0xFFFFFFFF.toInt()
                else -> 0xFF000000.toInt()
            }
            c.drawText(op, x + celda / 2f, cy - (fm.ascent + fm.descent) / 2f, texto)
        }
    }
}

class VariantesPopup(private val anchor: View) {

    private val vista = VariantesView(anchor.context)
    private val popup = PopupWindow(vista).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        setBackgroundDrawable(null)
        animationStyle = 0
    }
    private var izquierda = 0f

    val abierto get() = popup.isShowing
    /** Avisa cuando el dedo pasa a otra opción (para vibrar) */
    var alCambiarSeleccion: (() -> Unit)? = null

    fun mostrar(r: RectF, opciones: List<String>, anchoTeclado: Float, oscuro: Boolean, acento: Int) {
        if (anchor.windowToken == null || opciones.isEmpty()) return
        val d = anchor.resources.displayMetrics.density
        vista.opciones = opciones
        vista.oscuro = oscuro
        vista.acento = acento
        vista.celda = r.width() + 4f * d
        vista.altoCelda = r.height() * 1.08f

        val ancho = opciones.size * vista.celda
        var izq = r.left - 2f * d
        if (izq + ancho > anchoTeclado - 4f * d) izq = anchoTeclado - 4f * d - ancho
        if (izq < 4f * d) izq = 4f * d
        izquierda = izq
        vista.fijarSeleccion(((r.centerX() - izq) / vista.celda).toInt().coerceIn(0, opciones.size - 1))

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val w = vista.anchoTotal()
        val h = vista.altoTotal()
        val x = loc[0] + (izq - vista.pad - vista.margen).toInt()
        val y = loc[1] + (r.top - vista.altoCelda - vista.pad - vista.margen - 6f * d).toInt()

        if (popup.isShowing) popup.update(x, y, w, h) else {
            popup.width = w
            popup.height = h
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        vista.pivotX = (r.centerX() - izq) + vista.pad + vista.margen
        vista.pivotY = h.toFloat()
        vista.alpha = 0f
        vista.scaleX = 0.78f
        vista.scaleY = 0.78f
        vista.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200).setInterpolator(Movimiento.POP).start()
    }

    /** x en coordenadas del teclado */
    fun mover(x: Float) {
        if (!popup.isShowing || vista.celda <= 0f) return
        val i = ((x - izquierda) / vista.celda).toInt().coerceIn(0, vista.opciones.size - 1)
        if (i != vista.seleccion) {
            vista.seleccion = i
            alCambiarSeleccion?.invoke()
        }
    }

    fun elegido(): String? = vista.opciones.getOrNull(vista.seleccion)

    fun cerrar() {
        vista.animate().cancel()
        if (popup.isShowing) popup.dismiss()
    }
}
