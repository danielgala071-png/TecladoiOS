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
    /** Texto que copiaste: al tocarlo se pega tal cual */
    val portapapeles: Boolean = false,
    /** Etiqueta chiquita arriba del texto copiado (Copiado, Solo texto, Solo número) */
    val etiqueta: String? = null,
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
        !s.portapapeles && !s.revertir && s.reemplaza < 0 && !s.texto.contains(' ') && s.texto.length >= 2 &&
                s.texto.all { it.isLetter() }
    // Una pintura por grosor de letra: cambiar el grosor en cada dibujo es lento
    private val pinturaNormal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * d
    }.also { Fuentes.aplicar(it, 400) }
    private val pinturaMedia = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * d
    }.also { Fuentes.aplicar(it, 500) }
    private var pinturaTexto: TextPaint = pinturaNormal
    private fun grosor(peso: Int, tam: Float = 17f) {
        pinturaTexto = if (peso >= 500) pinturaMedia else pinturaNormal
        pinturaTexto.textSize = tam * d
    }
    private val espacios = Regex("\\s+")
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinturaEtiqueta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * d
    }.also { Fuentes.aplicar(it, 500) }

    fun mostrar(nuevos: List<Sugerencia?>) {
        val lista = List(3) { nuevos.getOrNull(it) }
        // Si no cambió nada, no se vuelve a dibujar (menos trabajo en cada letra)
        if (lista == items) return
        items = lista
        if (confirmar == null) presionado = -1
        invalidate()
    }

    /** Al escribir una letra: lo escrito aparece al instante, mientras se calculan las demás */
    fun mostrarEscrito(s: Sugerencia) {
        if (items[0] == s || items.any { it?.portapapeles == true }) return
        items = listOf(s, items[1], items[2])
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
        grosor(400)
        val fm = pinturaTexto.fontMetrics
        val cy = height / 2f - (fm.ascent + fm.descent) / 2f
        // Cancelar a la izquierda
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
        grosor(500)
        pinturaTexto.color = rojo
        val txt = TextUtils.ellipsize("Borrar \u201C${s.texto}\u201D", pinturaTexto,
            tercio * 2f - 24 * d, TextUtils.TruncateAt.END).toString()
        c.drawText(txt, tercio * 2f, cy, pinturaTexto)
    }

    /** Texto copiado: cápsula teñida con el color de la app, etiqueta arriba y el texto abajo */
    private fun dibujarCopiado(c: Canvas, s: Sugerencia, x0: Float, ancho: Float, presionada: Boolean) {
        val alfa = if (presionada) 0x55 else if (tema.oscuro) 0x33 else 0x26
        pintura.color = (tema.azul and 0x00FFFFFF) or (alfa shl 24)
        c.drawRoundRect(x0 + 4 * d, 4 * d, x0 + ancho - 4 * d, height - 4 * d, 11 * d, 11 * d, pintura)
        val etiqueta = s.etiqueta
        val centroTexto: Float
        if (etiqueta != null) {
            pinturaEtiqueta.color = tema.textoSecundario
            val fe = pinturaEtiqueta.fontMetrics
            c.drawText(etiqueta, x0 + ancho / 2f, height * 0.33f - (fe.ascent + fe.descent) / 2f, pinturaEtiqueta)
            centroTexto = height * 0.66f
        } else centroTexto = height / 2f
        grosor(500, if (etiqueta != null) 14.5f else 16f)
        pinturaTexto.color = tema.texto
        // Una sola línea: los saltos de línea se ven como espacios (al pegar se respetan)
        val plano = s.texto.replace(espacios, " ")
        val txt = TextUtils.ellipsize(plano, pinturaTexto, ancho - 22 * d, TextUtils.TruncateAt.END).toString()
        val fm = pinturaTexto.fontMetrics
        c.drawText(txt, x0 + ancho / 2f, centroTexto - (fm.ascent + fm.descent) / 2f, pinturaTexto)
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
            if (s.portapapeles) { dibujarCopiado(c, s, x0, ancho, i == presionado); continue }
            grosor(if (s.destacada) 500 else 400)
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
            val a = items[i - 1]
            val b = items[i]
            if (a != null && b != null && !a.portapapeles && !b.portapapeles) {
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
