package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

enum class Tipo { LETRA, SHIFT, BORRAR, A_NUMEROS, A_SIMBOLOS, A_LETRAS, EMOJI, ESPACIO, ENTER }
enum class Modo { LETRAS, NUMEROS, SIMBOLOS }

class Tecla(val tipo: Tipo, val texto: String = "", val ancho: Float = 1f) {
    val rect = RectF()
}

/** Las teclas dibujadas directamente en pantalla, estilo iPhone. */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val tema: Tema,
    private val acciones: Acciones,
) : View(context) {

    interface Acciones {
        fun texto(s: String)
        fun borrar()
        fun borrarPalabra()
        fun espacio()
        fun enter()
        fun abrirEmojis()
        fun retro(tipo: Tipo)
        fun moverCursor(pasos: Int)
    }

    private val d = Medidas.escala(context)
    private fun dp(v: Float) = v * d

    private val altoFila = if (Medidas.horizontal(context)) dp(40f) else dp(54f)
    private val margenArriba = dp(4f)
    private val margenAbajo = dp(8f)
    private val sepH = dp(3f)
    private val sepV = dp(5.5f)
    private val radio = dp(10.5f)

    private var filas: List<List<Tecla>> = crearFilas(Modo.LETRAS)

    var modo = Modo.LETRAS
        set(value) {
            field = value
            filas = crearFilas(value)
            acomodar()
            invalidate()
        }

    var soloNumeros = false
    var vistaPrevia = true
    var bloqMayusPermitido = true

    private var shift = 0 // 0 apagado, 1 una letra, 2 bloqueado
    private var ultimoShift = 0L
    private var etiquetaEnter = "intro"
    private var enterAzul = false

    private val presionadas = HashMap<Int, Tecla>()
    private val preview = KeyPreviewManager(this)
    private val variantes = VariantesPopup(this)

    // Acentos al mantener presionado
    private var punteroLargo = -1
    private var teclaLarga: Tecla? = null
    private var punteroVariantes = -1

    // Cursor con la barra espaciadora
    private var punteroEspacio = -1
    private var modoCursor = false
    private var xCursor = 0f

    private var repeticiones = 0

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinturaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, dp(0.6f))
    }
    private val pinturaBrillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinturaTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val pinturaIcono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val manejador = Handler(Looper.getMainLooper())

    /** Borrar mantenido: primero letra por letra, luego palabra por palabra (como iPhone) */
    private val repetirBorrar = object : Runnable {
        override fun run() {
            repeticiones++
            if (repeticiones > 14) {
                acciones.borrarPalabra()
                manejador.postDelayed(this, 150)
            } else {
                acciones.borrar()
                manejador.postDelayed(this, 65)
            }
        }
    }
    private val abrirVariantes = Runnable { mostrarVariantes() }
    private val activarCursor = Runnable {
        if (punteroEspacio != -1) {
            modoCursor = true
            acciones.retro(Tipo.ESPACIO)
            invalidate()
        }
    }

    fun alto(): Int = (margenArriba + altoFila * 4 + margenAbajo).toInt()

    // ---------- Distribución ----------

    private fun letras(s: String) = s.map { Tecla(Tipo.LETRA, it.toString()) }
    private fun simbolos(s: String, ancho: Float = 1f) = s.split(" ").map { Tecla(Tipo.LETRA, it, ancho) }

    private fun filaInferior(cambio: Tecla) = listOf(
        cambio,
        Tecla(Tipo.EMOJI, ancho = 1.25f),
        Tecla(Tipo.ESPACIO, "espacio", 5f),
        Tecla(Tipo.ENTER, ancho = 2.5f),
    )

    private fun crearFilas(m: Modo): List<List<Tecla>> = when (m) {
        Modo.LETRAS -> listOf(
            letras("qwertyuiop"),
            letras("asdfghjklñ"),
            listOf(Tecla(Tipo.SHIFT, ancho = 1.4f)) + letras("zxcvbnm") + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tecla(Tipo.A_NUMEROS, "123", 1.25f)),
        )
        Modo.NUMEROS -> listOf(
            letras("1234567890"),
            simbolos("- / : ; ( ) $ & @ \""),
            listOf(Tecla(Tipo.A_SIMBOLOS, "#+=", 1.4f)) + simbolos(". , ? ! '", 1.4f) + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tecla(Tipo.A_LETRAS, "ABC", 1.25f)),
        )
        Modo.SIMBOLOS -> listOf(
            simbolos("[ ] { } # % ^ * + ="),
            simbolos("_ \\ | ~ < > € £ ¥ •"),
            listOf(Tecla(Tipo.A_NUMEROS, "123", 1.4f)) + simbolos(". , ? ! '", 1.4f) + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tecla(Tipo.A_LETRAS, "ABC", 1.25f)),
        )
    }

    private fun acomodar() {
        if (width == 0) return
        val unidad = width / 10f
        filas.forEachIndexed { i, fila ->
            val total = fila.sumOf { it.ancho.toDouble() }.toFloat()
            var x = (width - total * unidad) / 2f
            val top = margenArriba + i * altoFila
            for (t in fila) {
                val w = t.ancho * unidad
                t.rect.set(x + sepH, top + sepV, x + w - sepH, top + altoFila - sepV)
                x += w
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), alto())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        acomodar()
    }

    // ---------- Lo que controla el servicio ----------

    fun configurarEnter(etiqueta: String, azul: Boolean) {
        etiquetaEnter = etiqueta
        enterAzul = azul
        invalidate()
    }

    fun mayusAuto(activar: Boolean) {
        if (shift == 2) return
        val nuevo = if (activar) 1 else 0
        if (nuevo != shift) {
            shift = nuevo
            invalidate()
        }
    }

    private fun reiniciar() {
        presionadas.clear()
        manejador.removeCallbacks(repetirBorrar)
        manejador.removeCallbacks(abrirVariantes)
        manejador.removeCallbacks(activarCursor)
        preview.hide()
        variantes.cerrar()
        punteroVariantes = -1
        punteroLargo = -1
        teclaLarga = null
        punteroEspacio = -1
        modoCursor = false
        invalidate()
    }

    fun ocultarPreview() {
        reiniciar()
        preview.dismiss()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ocultarPreview()
    }

    // ---------- Dibujo ----------

    private fun etiqueta(t: Tecla) =
        if (modo == Modo.LETRAS && shift > 0) t.texto.uppercase() else t.texto

    override fun onDraw(canvas: Canvas) {
        for (fila in filas) for (t in fila) dibujar(canvas, t)
    }

    private fun dibujar(c: Canvas, t: Tecla) {
        val presionada = presionadas.containsValue(t) && !modoCursor
        val colorFondo = when {
            modoCursor -> tema.teclaFuncion
            t.tipo == Tipo.ENTER -> when {
                presionada -> tema.teclaPresionada
                enterAzul -> tema.azul
                else -> tema.teclaFuncion
            }
            t.tipo == Tipo.LETRA -> if (presionada && !vistaPrevia) tema.teclaPresionada else tema.tecla
            t.tipo == Tipo.ESPACIO -> if (presionada) tema.teclaPresionada else tema.tecla
            t.tipo == Tipo.SHIFT -> if (shift > 0) tema.tecla else tema.teclaFuncion
            else -> if (presionada) tema.tecla else tema.teclaFuncion
        }

        if (Color.alpha(tema.sombra) > 0) {
            pintura.color = tema.sombra
            c.drawRoundRect(t.rect.left, t.rect.top + dp(1f), t.rect.right, t.rect.bottom + dp(1f), radio, radio, pintura)
        }
        pintura.color = colorFondo
        c.drawRoundRect(t.rect, radio, radio, pintura)

        // Liquid Glass: reflejo arriba y borde brillante
        pinturaBrillo.shader = LinearGradient(0f, t.rect.top, 0f, t.rect.top + t.rect.height() * 0.5f,
            tema.brillo, tema.brillo and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(t.rect, radio, radio, pinturaBrillo)
        pinturaBorde.shader = LinearGradient(0f, t.rect.top, 0f, t.rect.bottom,
            tema.borde, tema.borde and 0x22FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(t.rect, radio, radio, pinturaBorde)

        // Moviendo el cursor: las teclas quedan en blanco, como en iPhone
        if (modoCursor) return

        val cx = t.rect.centerX()
        val cy = t.rect.centerY()
        val colorContenido = if (t.tipo == Tipo.ENTER && enterAzul && !presionada) Color.WHITE else tema.texto
        pinturaIcono.color = colorContenido

        when (t.tipo) {
            Tipo.SHIFT -> Iconos.shift(c, cx, cy, dp(20f), pinturaIcono, shift > 0, shift == 2)
            Tipo.BORRAR -> Iconos.borrar(c, cx, cy, dp(22f), pinturaIcono)
            Tipo.EMOJI -> Iconos.emoji(c, cx, cy, dp(22f), pinturaIcono)
            Tipo.ENTER -> texto(c, etiquetaEnter, cx, cy, dp(16f), colorContenido)
            Tipo.LETRA -> {
                val minuscula = modo == Modo.LETRAS && shift == 0
                texto(c, etiqueta(t), cx, if (minuscula) cy - dp(1.5f) else cy, dp(24f), colorContenido)
            }
            else -> texto(c, t.texto, cx, cy, dp(16f), colorContenido)
        }
    }

    private fun texto(c: Canvas, s: String, cx: Float, cy: Float, tam: Float, color: Int) {
        pinturaTexto.textSize = tam
        pinturaTexto.color = color
        val fm = pinturaTexto.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, pinturaTexto)
    }

    // ---------- Acentos ----------

    private fun programarVariantes(t: Tecla, id: Int) {
        manejador.removeCallbacks(abrirVariantes)
        if (t.tipo == Tipo.LETRA && VARIANTES.containsKey(t.texto)) {
            teclaLarga = t
            punteroLargo = id
            manejador.postDelayed(abrirVariantes, 380)
        } else {
            teclaLarga = null
            punteroLargo = -1
        }
    }

    private fun mostrarVariantes() {
        val t = teclaLarga ?: return
        val ops = VARIANTES[t.texto] ?: return
        val lista = if (modo == Modo.LETRAS && shift > 0) {
            ops.map { v -> v.uppercase().let { u -> if (u.length == 1) u else v } }
        } else ops
        preview.hide()
        variantes.mostrar(t.rect, lista, width.toFloat(), tema.oscuro)
        punteroVariantes = punteroLargo
        acciones.retro(Tipo.LETRA)
    }

    // ---------- Toques ----------

    private fun teclaEn(x: Float, y: Float): Tecla? {
        if (filas.isEmpty()) return null
        val i = ((y - margenArriba) / altoFila).toInt().coerceIn(0, filas.size - 1)
        var mejor: Tecla? = null
        var distancia = Float.MAX_VALUE
        for (t in filas[i]) {
            val dx = when {
                x < t.rect.left -> t.rect.left - x
                x > t.rect.right -> x - t.rect.right
                else -> 0f
            }
            if (dx < distancia) { distancia = dx; mejor = t }
        }
        return mejor
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (punteroVariantes != -1 || modoCursor) return true
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val t = teclaEn(e.getX(i), e.getY(i)) ?: return true
                // Escritura rápida con dos dedos: la letra anterior se escribe ya
                val anteriores = presionadas.filterValues { it.tipo == Tipo.LETRA }.keys.toList()
                for (k in anteriores) {
                    if (k == punteroLargo) {
                        manejador.removeCallbacks(abrirVariantes)
                        punteroLargo = -1
                        teclaLarga = null
                    }
                    presionadas.remove(k)?.let { soltar(it) }
                }
                presionadas[id] = t
                presionar(t, id, e.getX(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (id == punteroVariantes) {
                        variantes.mover(x)
                        continue
                    }
                    if (id == punteroEspacio) {
                        if (modoCursor) {
                            val paso = dp(8f)
                            val n = ((x - xCursor) / paso).toInt()
                            if (n != 0) {
                                acciones.moverCursor(n)
                                xCursor += n * paso
                            }
                        } else if (Math.abs(x - xCursor) > dp(16f)) {
                            manejador.removeCallbacks(activarCursor)
                            modoCursor = true
                            xCursor = x
                            acciones.retro(Tipo.ESPACIO)
                            invalidate()
                        }
                        continue
                    }
                    val actual = presionadas[id] ?: continue
                    if (actual.tipo != Tipo.LETRA) continue
                    val t = teclaEn(x, y) ?: continue
                    if (t !== actual && t.tipo == Tipo.LETRA) {
                        presionadas[id] = t
                        programarVariantes(t, id)
                        if (vistaPrevia) preview.show(t.rect, etiqueta(t), tema.oscuro)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val t = presionadas.remove(id)
                when (id) {
                    punteroVariantes -> {
                        val elegido = variantes.elegido()
                        variantes.cerrar()
                        punteroVariantes = -1
                        punteroLargo = -1
                        teclaLarga = null
                        if (elegido != null) {
                            acciones.texto(elegido)
                            if (shift == 1 && modo == Modo.LETRAS) shift = 0
                        }
                    }
                    punteroEspacio -> {
                        manejador.removeCallbacks(activarCursor)
                        punteroEspacio = -1
                        if (modoCursor) modoCursor = false
                        else if (t != null) soltar(t)
                    }
                    else -> {
                        if (id == punteroLargo) {
                            manejador.removeCallbacks(abrirVariantes)
                            punteroLargo = -1
                            teclaLarga = null
                        }
                        if (t != null) soltar(t)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> reiniciar()
        }
        return true
    }

    private fun presionar(t: Tecla, id: Int, x: Float) {
        acciones.retro(t.tipo)
        when (t.tipo) {
            Tipo.LETRA -> {
                if (vistaPrevia) preview.show(t.rect, etiqueta(t), tema.oscuro)
                programarVariantes(t, id)
            }
            Tipo.BORRAR -> {
                repeticiones = 0
                acciones.borrar()
                manejador.postDelayed(repetirBorrar, 400)
            }
            Tipo.ESPACIO -> {
                punteroEspacio = id
                xCursor = x
                manejador.postDelayed(activarCursor, 450)
            }
            Tipo.SHIFT -> {
                val ahora = SystemClock.uptimeMillis()
                shift = when {
                    shift == 2 -> 0
                    bloqMayusPermitido && ahora - ultimoShift < 300 -> 2 // doble toque = bloq mayús
                    shift == 1 -> 0
                    else -> 1
                }
                ultimoShift = ahora
            }
            else -> {}
        }
        invalidate()
    }

    private fun soltar(t: Tecla) {
        when (t.tipo) {
            Tipo.LETRA -> {
                preview.hide()
                acciones.texto(etiqueta(t))
                if (shift == 1 && modo == Modo.LETRAS) shift = 0
                if (modo != Modo.LETRAS && t.texto == "'" && !soloNumeros) modo = Modo.LETRAS
            }
            Tipo.BORRAR -> manejador.removeCallbacks(repetirBorrar)
            Tipo.ESPACIO -> {
                acciones.espacio()
                if (modo != Modo.LETRAS && !soloNumeros) modo = Modo.LETRAS
            }
            Tipo.ENTER -> acciones.enter()
            Tipo.A_NUMEROS -> modo = Modo.NUMEROS
            Tipo.A_SIMBOLOS -> modo = Modo.SIMBOLOS
            Tipo.A_LETRAS -> modo = Modo.LETRAS
            Tipo.EMOJI -> acciones.abrirEmojis()
            Tipo.SHIFT -> {}
        }
        invalidate()
    }

    companion object {
        /** Lo que aparece al mantener presionada cada tecla */
        private val VARIANTES = mapOf(
            "a" to listOf("á", "à", "â", "ä", "æ", "ã", "å", "ā", "ª"),
            "e" to listOf("é", "è", "ê", "ë", "ē", "ė", "ę"),
            "i" to listOf("í", "ì", "î", "ï", "į", "ī"),
            "o" to listOf("ó", "ò", "ô", "ö", "õ", "ø", "œ", "ō", "º"),
            "u" to listOf("ú", "ù", "û", "ü", "ū"),
            "n" to listOf("ñ", "ń"),
            "c" to listOf("ç", "ć", "č"),
            "s" to listOf("ß", "ś", "š"),
            "y" to listOf("ÿ"),
            "z" to listOf("ž", "ź", "ż"),
            "l" to listOf("ł"),
            "0" to listOf("°"),
            "-" to listOf("–", "—", "•"),
            "/" to listOf("\\"),
            "$" to listOf("S/", "€", "£", "¥", "₩", "₽"),
            "&" to listOf("§"),
            "\"" to listOf("“", "”", "„", "«", "»"),
            "." to listOf("…"),
            "?" to listOf("¿"),
            "!" to listOf("¡"),
            "'" to listOf("‘", "’", "`"),
            "%" to listOf("‰"),
        )
    }
}
