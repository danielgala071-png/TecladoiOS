package com.tecladoios

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    /** Lo que copiaste (se pega al tocarlo, como en iPhone). [texto] es lo que se ve */
    val portapapeles: Boolean = false,
    /** Lo que se pega de verdad (puede ser distinto a lo que se ve, p. ej. una clave con puntos) */
    val pegar: String? = null,
    /** Emoji sugerido por la palabra ("jaja" → 😂): se agrega después de la palabra */
    val emoji: Boolean = false,
    /** Botón de la barra de edición (Cortar, Copiar, Pegar...); 0 = es una palabra */
    val accion: Int = 0,
)

/**
 * Barra de sugerencias de 3 casillas, como en iOS. Es la parte de arriba del teclado:
 * una sola pieza negra con el teclado, con las esquinas de arriba curvas y la parte de
 * abajo unida a las teclas (sin línea). Está oculta y solo aparece mientras escribes;
 * al dejar de escribir desaparece entera y el teclado queda con su borde recto arriba.
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

    /**
     * Momento (uptime) en que tocaste la última letra. Lo pone el servicio. Un toque en la
     * barra que llega pegadito a una letra casi siempre es un dedo despistado escribiendo
     * rápido (no un toque a propósito), así que se ignora.
     */
    @Volatile var ultimaTeclaMs = 0L
    /** Este gesto empezó mientras escribías: se ignora completo (no elige sugerencia) */
    private var ignorarGesto = false

    /** Sugerencia que se está por borrar (la barra muestra Cancelar / Borrar) */
    private var confirmar: Sugerencia? = null
    private var mantenido = false
    private var tocoEn = -1
    private val manejador = Handler(Looper.getMainLooper())
    private val alMantener = Runnable {
        val s = items.getOrNull(presionado) ?: return@Runnable
        if (s.portapapeles) {
            // Mantener presionado lo copiado: se quita de la barra
            mantenido = true
            presionado = -1
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            acciones.olvidar(s)
            invalidate()
            return@Runnable
        }
        if (!sePuedeOlvidar(s)) return@Runnable
        mantenido = true
        tocoEn = -1
        confirmar = s
        presionado = -1
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun sePuedeOlvidar(s: Sugerencia) =
        s.accion == 0 && !s.portapapeles && !s.revertir && s.reemplaza < 0 && !s.texto.contains(' ') && s.texto.length >= 2 &&
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

    // Forma de la barra: esquinas curvas arriba, recta abajo (pegada al teclado)
    private val forma = Path()
    private val filo = Path()
    private val esquina = RectF()
    private val pinturaFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var mostrando = false
    /** Qué tan visibles están las sugerencias (0 = apagadas, 1 = a la vista) */
    private var opacidad = 0f
    private var animador: ValueAnimator? = null

    /** Avisa cuando la barra aparece o desaparece (para que el teclado ponga su filo de arriba) */
    var alCambiar: ((Boolean) -> Unit)? = null

    init {
        // Empieza oculta: aparece recién al escribir
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        armarForma(w.toFloat(), h.toFloat())
    }

    private fun armarForma(w: Float, h: Float) {
        forma.reset()
        filo.reset()
        if (w <= 0f || h <= 0f) return
        val r = minOf(18f * d, h / 2f)
        // Relleno negro: curvo arriba y recto abajo, hasta el borde del teclado
        forma.moveTo(0f, h)
        forma.lineTo(0f, r)
        esquina.set(0f, 0f, r * 2f, r * 2f)
        forma.arcTo(esquina, 180f, 90f, false)
        forma.lineTo(w - r, 0f)
        esquina.set(w - r * 2f, 0f, w, r * 2f)
        forma.arcTo(esquina, 270f, 90f, false)
        forma.lineTo(w, h)
        forma.close()
        // Filo del color de la app: sube por los costados, hace la curva arriba
        // y abajo queda abierto (sigue por los costados del teclado)
        val g = pinturaBorde.strokeWidth / 2f
        val rf = r - g
        filo.moveTo(g, h)
        filo.lineTo(g, g + rf)
        esquina.set(g, g, g + rf * 2f, g + rf * 2f)
        filo.arcTo(esquina, 180f, 90f, false)
        filo.lineTo(w - g - rf, g)
        esquina.set(w - g - rf * 2f, g, w - g, g + rf * 2f)
        filo.arcTo(esquina, 270f, 90f, false)
        filo.lineTo(w - g, h)
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
     * Mostrar u ocultar la barra. Al escribir aparece y las sugerencias se encienden suavemente;
     * al dejar de escribir la barra entera desaparece (no queda el espacio negro vacío).
     */
    fun visible(si: Boolean, animar: Boolean = true) {
        if (!si) cancelar()
        val destino = if (si) 1f else 0f
        if (si == mostrando && (animar || opacidad == destino)) return
        mostrando = si
        animador?.cancel()
        val vis = if (si) VISIBLE else GONE
        if (visibility != vis) {
            visibility = vis
            alCambiar?.invoke(si)
        }
        if (!si) {
            opacidad = 0f
            invalidate()
            return
        }
        // Al volver a aparecer no se ven las sugerencias de la palabra anterior
        items = listOf(null, null, null)
        presionado = -1
        if (!animar) {
            opacidad = destino
            invalidate()
            return
        }
        animador = ValueAnimator.ofFloat(opacidad, destino).apply {
            duration = if (si) 130 else 120
            addUpdateListener {
                opacidad = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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
        // Fondo negro como el teclado (curvo arriba) y el filo del color de la app
        pinturaFondo.color = tema.fondo
        c.drawPath(forma, pinturaFondo)
        pinturaBorde.color = tema.bordePanel
        c.drawPath(filo, pinturaBorde)

        confirmar?.let { dibujarConfirmar(c, it); return }
        if (opacidad <= 0f) return
        // Las sugerencias se encienden y apagan suavemente
        val capa = if (opacidad < 1f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacidad * 255).toInt()) else -1
        dibujarSugerencias(c)
        if (capa >= 0) c.restoreToCount(capa)
    }

    // Cápsula de lo copiado
    private val capsula = RectF()
    private val pinturaClip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.5f * d
    }
    private val pinturaChica = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 15f * d
    }.also { Fuentes.aplicar(it, 400) }
    private var clipCrudo: String? = null
    private var clipLinea = ""
    private var clipAncho = -1f

    /** Lo copiado: una cápsula al centro con el ícono del portapapeles y el texto en una línea */
    private fun dibujarCopiado(c: Canvas, s: Sugerencia) {
        val h = height.toFloat()
        val maxAncho = width * 0.78f
        val icono = 14f * d
        val hueco = 8f * d
        val lados = 14f * d
        val disponible = maxAncho - lados * 2 - icono - hueco
        // El texto ya recortado se guarda: no se vuelve a medir en cada cuadro
        if (s.texto != clipCrudo || disponible != clipAncho) {
            clipCrudo = s.texto
            clipAncho = disponible
            val unaLinea = s.texto.replace(Regex("\\s+"), " ").trim()
            clipLinea = TextUtils.ellipsize(unaLinea, pinturaChica, disponible, TextUtils.TruncateAt.END).toString()
        }
        val anchoTexto = pinturaChica.measureText(clipLinea)
        val ancho = lados * 2 + icono + hueco + anchoTexto
        val izq = (width - ancho) / 2f
        capsula.set(izq, 6f * d, izq + ancho, h - 10f * d)
        val radioC = capsula.height() / 2f
        pintura.color = if (presionado == 0) tema.teclaPresionada else tema.tecla
        c.drawRoundRect(capsula, radioC, radioC, pintura)
        pinturaBorde.color = tema.borde
        c.drawRoundRect(capsula, radioC, radioC, pinturaBorde)
        // Ícono de portapapeles (una tablilla con su gancho)
        pinturaClip.color = tema.texto
        val cx = izq + lados + icono / 2f
        val cy = capsula.centerY()
        val w2 = icono * 0.38f
        val h2 = icono * 0.5f
        c.drawRoundRect(cx - w2, cy - h2 + 1.5f * d, cx + w2, cy + h2, 2.5f * d, 2.5f * d, pinturaClip)
        c.drawRoundRect(cx - w2 * 0.5f, cy - h2, cx + w2 * 0.5f, cy - h2 + 3f * d, 1.5f * d, 1.5f * d, pinturaClip)
        // El texto
        pinturaChica.color = tema.texto
        val fm = pinturaChica.fontMetrics
        c.drawText(clipLinea, izq + lados + icono + hueco, cy - (fm.ascent + fm.descent) / 2f, pinturaChica)
    }

    private fun dibujarSugerencias(c: Canvas) {
        items[0]?.let { if (it.portapapeles) { dibujarCopiado(c, it); return } }
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
        // Sin sugerencias a la vista, tocar la barra no hace nada
        if (!mostrando && confirmar == null) return true
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
        val esCopiado = items[0]?.portapapeles == true
        val i = if (esCopiado) 0 else (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Toque accidental al escribir rápido: si acabas de tocar una letra hace muy
                // poco, este toque en la barra se ignora. El del portapapeles no se ignora
                // porque solo aparece cuando no estás escribiendo.
                ignorarGesto = !esCopiado && SystemClock.uptimeMillis() - ultimaTeclaMs < GUARDA_MS
                if (ignorarGesto) return true
                mantenido = false
                presionado = if (items[i] != null) i else -1
                if (presionado != -1) manejador.postDelayed(alMantener, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignorarGesto) return true
                if (presionado != -1 && i != presionado) {
                    manejador.removeCallbacks(alMantener)
                    presionado = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (ignorarGesto) { ignorarGesto = false; return true }
                manejador.removeCallbacks(alMantener)
                val s = if (presionado == i && !mantenido) items[i] else null
                mantenido = false
                presionado = -1
                invalidate()
                if (s != null) acciones.elegir(s)
            }
            MotionEvent.ACTION_CANCEL -> {
                ignorarGesto = false
                manejador.removeCallbacks(alMantener)
                presionado = -1
                invalidate()
            }
        }
        return true
    }

    private companion object {
        /** Debajo de este tiempo tras tocar una letra, un toque en la barra se ignora */
        const val GUARDA_MS = 130L
    }
}
