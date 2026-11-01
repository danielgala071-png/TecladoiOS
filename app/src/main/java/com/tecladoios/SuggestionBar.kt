package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

data class Sugerencia(val texto: String, val literal: Boolean = false, val destacada: Boolean = false)

/** Barra de sugerencias de 3 casillas, como en iOS. */
@SuppressLint("ViewConstructor")
class SuggestionBar(
    context: Context,
    private val tema: Tema,
    private val acciones: Acciones,
) : View(context) {

    interface Acciones { fun elegir(s: Sugerencia) }

    private val d = Medidas.escala(context)
    private var items: List<Sugerencia?> = listOf(null, null, null)
    private var presionado = -1

    private val normal = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 400, false)
    private val media = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 500, false)
    private val pinturaTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * d
    }
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)

    fun mostrar(nuevos: List<Sugerencia?>) {
        items = List(3) { nuevos.getOrNull(it) }
        presionado = -1
        invalidate()
    }

    fun visible(si: Boolean) {
        val nueva = if (si) VISIBLE else GONE
        if (visibility != nueva) visibility = nueva
    }

    override fun onDraw(c: Canvas) {
        val ancho = width / 3f
        for (i in 0 until 3) {
            val s = items[i] ?: continue
            val x0 = ancho * i
            if (i == presionado) {
                pintura.color = tema.teclaPresionada
                c.drawRoundRect(x0 + 4 * d, 6 * d, x0 + ancho - 4 * d, height - 6 * d, 10 * d, 10 * d, pintura)
            }
            pinturaTexto.typeface = if (s.destacada) media else normal
            pinturaTexto.color = tema.texto
            val crudo = if (s.literal) "\u201C${s.texto}\u201D" else s.texto
            val txt = TextUtils.ellipsize(crudo, pinturaTexto, ancho - 16 * d, TextUtils.TruncateAt.END).toString()
            val fm = pinturaTexto.fontMetrics
            c.drawText(txt, x0 + ancho / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, pinturaTexto)
        }
        pintura.color = (tema.textoSecundario and 0x00FFFFFF) or 0x55000000
        pintura.strokeWidth = d
        for (i in 1 until 3) {
            if (items[i - 1] != null && items[i] != null) {
                c.drawLine(ancho * i, height * 0.3f, ancho * i, height * 0.7f, pintura)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width == 0) return true
        val i = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                presionado = if (items[i] != null) i else -1
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val s = if (presionado == i) items[i] else null
                presionado = -1
                invalidate()
                if (s != null) acciones.elegir(s)
            }
            MotionEvent.ACTION_CANCEL -> {
                presionado = -1
                invalidate()
            }
        }
        return true
    }
}
