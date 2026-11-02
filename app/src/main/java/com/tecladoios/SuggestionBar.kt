package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
    /** Cuántas letras borrar al elegirla (-1 = la palabra actual) */
    val reemplaza: Int = -1,
    /** Deshace la última autocorrección (vuelve a lo que escribiste) */
    val revertir: Boolean = false,
    /** Predicción en línea: lo que falta de la palabra, en gris (se acepta con espacio) */
    val resto: String? = null,
)

/**
 * Barra de sugerencias de 3 casillas, como en iOS, pegada encima del teclado.
 * Es negra y su filo del color de la app termina en curva en la parte de abajo.
 * Aparece cuando empiezas a escribir y se esconde cuando dejas de escribir.
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
    private fun grosor(peso: Int) {
        pinturaTexto = if (peso >= 500) pinturaMedia else pinturaNormal
        pinturaTexto.textSize = 17f * d
    }
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinturaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, 1.1f * d)
    }

    // Filo del color de la app: recto arriba y con curva abajo
    private val filo = Path()
    private val esquina = RectF()
    private var mostrando = false

    /** Avisa cuando la barra aparece (true) o termina de esconderse (false) */
    var alCambiarVisible: ((Boolean) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        armarForma(w.toFloat(), h.toFloat())
    }

    private fun armarForma(w: Float, h: Float) {
        filo.reset()
        if (w <= 0f || h <= 0f) return
        val g = pinturaBorde.strokeWidth / 2f
        val r = minOf(20f * d, h / 2f)
        val abajo = h - g
        // Arriba: borde recto de lado a lado (es el borde de arriba del teclado)
        filo.moveTo(g, h)
        filo.lineTo(g, g)
        filo.lineTo(w - g, g)
        filo.lineTo(w - g, h)
        // Abajo: la curva que separa la barra de las teclas
        filo.moveTo(g, abajo - r * 1.6f)
        esquina.set(g, abajo - r * 2f, g + r * 2f, abajo)
        filo.arcTo(esquina, 180f, -90f, false)
        filo.lineTo(w - g - r, abajo)
        esquina.set(w - g - r * 2f, abajo - r * 2f, w - g, abajo)
        filo.arcTo(esquina, 90f, -90f, false)
        filo.lineTo(w - g, abajo - r * 1.6f)
    }

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
        if (items[0] == s) return
        items = listOf(s, items[1], items[2])
        invalidate()
    }

    /**
     * Mostrar u ocultar la barra con una animación suave. El espacio de la barra queda
     * reservado (transparente), así la app de atrás no salta al aparecer o desaparecer.
     */
    fun visible(si: Boolean, animar: Boolean = true) {
        if (!si) cancelar()
        if (!si && !animar) {
            // Al cerrar el teclado: se esconde al instante (sin animación pendiente)
            mostrando = false
            animate().cancel()
            alpha = 0f
            visibility = INVISIBLE
            alCambiarVisible?.invoke(false)
            avisarCambio()
            return
        }
        if (si == mostrando) return
        mostrando = si
        animate().cancel()
        if (si) {
            // Al volver a aparecer no se ven las sugerencias de la palabra anterior
            items = listOf(null, null, null)
            presionado = -1
            if (visibility != VISIBLE) {
                alpha = 0f
                visibility = VISIBLE
            }
            alCambiarVisible?.invoke(true)
            animate().alpha(1f).setDuration(130).start()
        } else {
            animate().alpha(0f).setDuration(120)
                .withEndAction {
                    if (!mostrando) {
                        visibility = INVISIBLE
                        alCambiarVisible?.invoke(false)
                        avisarCambio()
                    }
                }
                .start()
        }
        avisarCambio()
    }

    /** Avisa al teclado que cambió la zona tocable (para que los toques pasen a la app) */
    private fun avisarCambio() {
        (parent as? View)?.requestLayout()
        requestLayout()
    }

    private fun cancelar() {
        manejador.removeCallbacks(alMantener)
        if (confirmar != null) {
            confirmar = null
            invalidate()
        }
    }

    private fun centroY(): Float {
        val fm = pinturaTexto.fontMetrics
        return height / 2f - 1.5f * d - (fm.ascent + fm.descent) / 2f
    }

    private fun dibujarConfirmar(c: Canvas, s: Sugerencia) {
        val tercio = width / 3f
        grosor(400)
        val cy = centroY()
        // Cancelar a la izquierda
        pinturaTexto.color = tema.textoSecundario
        if (tocoEn == 0) {
            pintura.color = tema.teclaPresionada
            c.drawRoundRect(4 * d, 5 * d, tercio - 4 * d, height - 9 * d, 10 * d, 10 * d, pintura)
        }
        c.drawText("Cancelar", tercio / 2f, cy, pinturaTexto)
        // Borrar la palabra en rojo, en los otros dos tercios
        val rojo = if (tema.oscuro) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
        pintura.color = (rojo and 0x00FFFFFF) or (if (tocoEn == 1) 0x55000000 else 0x26000000)
        c.drawRoundRect(tercio + 4 * d, 5 * d, width - 4 * d, height - 9 * d, 10 * d, 10 * d, pintura)
        grosor(500)
        pinturaTexto.color = rojo
        val txt = TextUtils.ellipsize("Borrar \u201C${s.texto}\u201D", pinturaTexto,
            tercio * 2f - 24 * d, TextUtils.TruncateAt.END).toString()
        c.drawText(txt, tercio * 2f, cy, pinturaTexto)
    }

    override fun onDraw(c: Canvas) {
        // Fondo negro como el teclado y el filo del color de la app con la curva abajo
        c.drawColor(tema.fondo)
        pinturaBorde.color = tema.bordePanel
        c.drawPath(filo, pinturaBorde)

        confirmar?.let { dibujarConfirmar(c, it); return }
        val ancho = width / 3f
        for (i in 0 until 3) {
            val s = items[i] ?: continue
            val x0 = ancho * i
            if (i == presionado) {
                pintura.color = tema.teclaPresionada
                c.drawRoundRect(x0 + 4 * d, 5 * d, x0 + ancho - 4 * d, height - 9 * d, 10 * d, 10 * d, pintura)
            }
            grosor(if (s.destacada) 500 else 400)
            val cy = centroY()
            val resto = s.resto
            if (resto != null) {
                // Predicción en línea: lo escrito normal y lo que falta en gris
                val total = pinturaTexto.measureText(s.texto + resto)
                if (total <= ancho - 16 * d) {
                    val izq = x0 + (ancho - total) / 2f
                    val alin = pinturaTexto.textAlign
                    pinturaTexto.textAlign = Paint.Align.LEFT
                    pinturaTexto.color = tema.texto
                    c.drawText(s.texto, izq, cy, pinturaTexto)
                    pinturaTexto.color = tema.textoSecundario
                    c.drawText(resto, izq + pinturaTexto.measureText(s.texto), cy, pinturaTexto)
                    pinturaTexto.textAlign = alin
                    continue
                }
            }
            pinturaTexto.color = tema.texto
            val crudo = if (s.literal) "\u201C${s.texto}\u201D" else s.texto
            val txt = TextUtils.ellipsize(crudo, pinturaTexto, ancho - 16 * d, TextUtils.TruncateAt.END).toString()
            c.drawText(txt, x0 + ancho / 2f, cy, pinturaTexto)
        }
        // Separadores finos entre las casillas, como en la imagen
        pintura.color = (tema.textoSecundario and 0x00FFFFFF) or 0x55000000
        pintura.strokeWidth = maxOf(1f, d * 0.7f)
        for (i in 1 until 3) {
            if (items[i - 1] != null && items[i] != null) {
                c.drawLine(ancho * i, height * 0.26f, ancho * i, height * 0.66f, pintura)
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
