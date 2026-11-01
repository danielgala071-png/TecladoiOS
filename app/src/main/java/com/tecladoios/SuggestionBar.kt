package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

data class Sugerencia(
    val texto: String,
    val literal: Boolean = false,
    val destacada: Boolean = false,
    val ia: Boolean = false,
    /** Cuántas letras borrar al elegirla (-1 = la palabra actual) */
    val reemplaza: Int = -1,
    /** Deshace la última autocorrección (vuelve a lo que escribiste) */
    val revertir: Boolean = false,
)

/**
 * Barra de sugerencias de 3 casillas, como en iOS.
 * Mantener presionada una sugerencia muestra la opción de borrar esa palabra.
 */
@SuppressLint("ViewConstructor")
class SuggestionBar(
    context: Context,
    private val tema: Tema,
    private val acciones: Acciones,
) : View(context) {

    interface Acciones {
        fun elegir(s: Sugerencia)
        /** Olvidar la palabra: no volver a sugerirla ni a corregir hacia ella */
        fun olvidar(s: Sugerencia)
    }

    private val d = Medidas.escala(context)
    private var items: List<Sugerencia?> = listOf(null, null, null)
    private var presionado = -1

    /** Sugerencia que se está por borrar (la barra muestra Cancelar / Borrar) */
    private var confirmar: Sugerencia? = null
    private var mantenido = false
    private var tocoEn = -1
    private val manejador = Handler(Looper.getMainLooper())
    private val alMantener = Runnable {
        val s = items.getOrNull(presionado) ?: return@Runnable
        if (!sePuedeOlvidar(s)) return@Runnable
        mantenido = true
        tocoEn = -1
        confirmar = s
        presionado = -1
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun sePuedeOlvidar(s: Sugerencia) =
        !s.revertir && s.reemplaza < 0 && !s.texto.contains(' ') && s.texto.length >= 2 &&
                s.texto.all { it.isLetter() }
    private val pinturaTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * d
    }.also { Fuentes.aplicar(it, 400) }
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)

    fun mostrar(nuevos: List<Sugerencia?>) {
        items = List(3) { nuevos.getOrNull(it) }
        presionado = -1
        invalidate()
    }

    fun visible(si: Boolean) {
        if (!si) cancelar()
        val nueva = if (si) VISIBLE else GONE
        if (visibility != nueva) visibility = nueva
    }

    private fun cancelar() {
        manejador.removeCallbacks(alMantener)
        if (confirmar != null) {
            confirmar = null
            invalidate()
        }
    }

    private fun dibujarConfirmar(c: Canvas, s: Sugerencia) {
        val tercio = width / 3f
        val fm = pinturaTexto.fontMetrics
        val cy = height / 2f - (fm.ascent + fm.descent) / 2f
        // Cancelar a la izquierda
        Fuentes.aplicar(pinturaTexto, 400)
        pinturaTexto.color = tema.textoSecundario
        if (tocoEn == 0) {
            pintura.color = tema.teclaPresionada
            c.drawRoundRect(4 * d, 6 * d, tercio - 4 * d, height - 6 * d, 10 * d, 10 * d, pintura)
        }
        c.drawText("Cancelar", tercio / 2f, cy, pinturaTexto)
        // Borrar la palabra en rojo, en los otros dos tercios
        val rojo = if (tema.oscuro) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
        pintura.color = (rojo and 0x00FFFFFF) or (if (tocoEn == 1) 0x55000000 else 0x26000000)
        c.drawRoundRect(tercio + 4 * d, 6 * d, width - 4 * d, height - 6 * d, 10 * d, 10 * d, pintura)
        Fuentes.aplicar(pinturaTexto, 500)
        pinturaTexto.color = rojo
        val txt = TextUtils.ellipsize("Borrar \u201C${s.texto}\u201D", pinturaTexto,
            tercio * 2f - 24 * d, TextUtils.TruncateAt.END).toString()
        c.drawText(txt, tercio * 2f, cy, pinturaTexto)
    }

    override fun onDraw(c: Canvas) {
        confirmar?.let { dibujarConfirmar(c, it); return }
        val ancho = width / 3f
        for (i in 0 until 3) {
            val s = items[i] ?: continue
            val x0 = ancho * i
            if (i == presionado) {
                pintura.color = tema.teclaPresionada
                c.drawRoundRect(x0 + 4 * d, 6 * d, x0 + ancho - 4 * d, height - 6 * d, 10 * d, 10 * d, pintura)
            }
            Fuentes.aplicar(pinturaTexto, if (s.destacada) 500 else 400)
            pinturaTexto.color = tema.texto
            val crudo = when {
                s.literal -> "\u201C${s.texto}\u201D"
                s.ia -> "\u2726 ${s.texto}"
                else -> s.texto
            }
            val txt = TextUtils.ellipsize(crudo, pinturaTexto, ancho - 16 * d, TextUtils.TruncateAt.END).toString()
            val fm = pinturaTexto.fontMetrics
            c.drawText(txt, x0 + ancho / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, pinturaTexto)
        }
        pintura.color = (tema.textoSecundario and 0x00FFFFFF) or 0x40000000
        pintura.strokeWidth = maxOf(1f, d * 0.6f)
        for (i in 1 until 3) {
            if (items[i - 1] != null && items[i] != null) {
                c.drawLine(ancho * i, height * 0.34f, ancho * i, height * 0.66f, pintura)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width == 0) return true
        val conf = confirmar
        if (conf != null) {
            // Modo borrar: izquierda = Cancelar, el resto = Borrar palabra
            val lado = if (e.x < width / 3f) 0 else 1
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tocoEn = lado; invalidate() }
                MotionEvent.ACTION_MOVE -> if (lado != tocoEn) { tocoEn = -1; invalidate() }
                MotionEvent.ACTION_UP -> {
                    val elegido = tocoEn
                    tocoEn = -1
                    if (mantenido) { mantenido = false; invalidate(); return true }
                    if (elegido == lado) {
                        confirmar = null
                        if (lado == 1) acciones.olvidar(conf)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { tocoEn = -1; mantenido = false; invalidate() }
            }
            return true
        }
        val i = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mantenido = false
                presionado = if (items[i] != null) i else -1
                if (presionado != -1) manejador.postDelayed(alMantener, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (presionado != -1 && i != presionado) {
                    manejador.removeCallbacks(alMantener)
                    presionado = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                manejador.removeCallbacks(alMantener)
                val s = if (presionado == i) items[i] else null
                presionado = -1
                invalidate()
                if (s != null) acciones.elegir(s)
            }
            MotionEvent.ACTION_CANCEL -> {
                manejador.removeCallbacks(alMantener)
                presionado = -1
                invalidate()
            }
        }
        return true
    }
}
