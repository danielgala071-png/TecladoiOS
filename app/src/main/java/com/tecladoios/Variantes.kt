package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow

/** Las letras con acento que salen al mantener presionada una tecla (á, é, ñ…). */
class VariantesView(context: Context) : View(context) {

    var opciones: List<String> = emptyList()
    var seleccion = 0
        set(value) { field = value; invalidate() }
    var celda = 0f
    var altoCelda = 0f
    var oscuro = false

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
        strokeWidth = 1f * d
    }
    private val marca = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 400, false)
    }


    override fun onDraw(c: Canvas) {
        if (opciones.isEmpty()) return
        rect.set(pad, pad, width - pad, height - pad)
        val radio = altoCelda * 0.32f
        val arriba = if (oscuro) 0xEE6C6C72.toInt() else 0xF5FBFBFD.toInt()
        val abajo = if (oscuro) 0xEE55555B.toInt() else 0xF0E8EAEF.toInt()
        cuerpo.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, arriba, abajo, Shader.TileMode.CLAMP)
        cuerpo.setShadowLayer(12f * d, 0f, 3f * d, 0x38000000)
        c.drawRoundRect(rect, radio, radio, cuerpo)
        borde.color = if (oscuro) 0x40FFFFFF else 0xB3FFFFFF.toInt()
        c.drawRoundRect(rect, radio, radio, borde)

        texto.textSize = altoCelda * 0.5f
        val fm = texto.fontMetrics
        val izq = pad + margen
        val cy = pad + margen + altoCelda / 2f
        opciones.forEachIndexed { i, op ->
            val x = izq + i * celda
            val elegida = i == seleccion
            if (elegida) {
                celdaRect.set(x + 1.5f * d, pad + margen, x + celda - 1.5f * d, pad + margen + altoCelda)
                marca.color = 0xFF007AFF.toInt()
                c.drawRoundRect(celdaRect, radio * 0.75f, radio * 0.75f, marca)
            }
            texto.color = when {
                elegida -> 0xFFFFFFFF.toInt()
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

    fun mostrar(r: RectF, opciones: List<String>, anchoTeclado: Float, oscuro: Boolean) {
        if (anchor.windowToken == null || opciones.isEmpty()) return
        val d = anchor.resources.displayMetrics.density
        vista.opciones = opciones
        vista.oscuro = oscuro
        vista.celda = r.width() + 4f * d
        vista.altoCelda = r.height() * 1.08f

        val ancho = opciones.size * vista.celda
        var izq = r.left - 2f * d
        if (izq + ancho > anchoTeclado - 4f * d) izq = anchoTeclado - 4f * d - ancho
        if (izq < 4f * d) izq = 4f * d
        izquierda = izq
        vista.seleccion = ((r.centerX() - izq) / vista.celda).toInt().coerceIn(0, opciones.size - 1)

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
        vista.scaleX = 0.7f
        vista.scaleY = 0.7f
        vista.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160).setInterpolator(OvershootInterpolator(1.3f)).start()
    }

    /** x en coordenadas del teclado */
    fun mover(x: Float) {
        if (!popup.isShowing || vista.celda <= 0f) return
        val i = ((x - izquierda) / vista.celda).toInt().coerceIn(0, vista.opciones.size - 1)
        if (i != vista.seleccion) vista.seleccion = i
    }

    fun elegido(): String? = vista.opciones.getOrNull(vista.seleccion)

    fun cerrar() {
        vista.animate().cancel()
        if (popup.isShowing) popup.dismiss()
    }
}
