package com.tecladoios

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator

enum class Tipo { LETRA, SHIFT, BORRAR, A_NUMEROS, A_SIMBOLOS, A_LETRAS, EMOJI, ESPACIO, ENTER, MIC }
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
        fun dictar()
    }

    private val d = Medidas.escala(context)
    private fun dp(v: Float) = v * d

    // Tamaño ajustable desde Ajustes → Tamaño
    private val escalaAlto = Ajustes.altura(context) / 100f
    private val escalaLetra = Ajustes.letra(context) / 100f
    private val altoFila = (if (Medidas.horizontal(context)) dp(40f) else dp(54f)) * escalaAlto
    private val margenArriba = dp(4f)
    private val margenAbajo = dp(8f)
    private val sepH = dp(3f)
    private val sepV = dp(5.5f)
    private val radio = dp(10.5f)

    // Franja inferior como en iPhone: emoji a la izquierda y micrófono a la derecha
    private val altoInferior = if (Medidas.horizontal(context)) 0f else dp(42f)
    private val conTira = altoInferior > 0f
    private val tira = listOf(Tecla(Tipo.EMOJI), Tecla(Tipo.MIC))

    var dictando = false
        set(value) { field = value; invalidate() }

    // Efecto líquido al presionar teclas de función (iOS 27)
    private var teclaPulso: Tecla? = null
    private var pulso = 0f
    private var animPulso: ValueAnimator? = null

    // Letra fina como la de iPhone, sin importar el grosor de letra del sistema
    private val fuente: Typeface = Fuentes.tipo(400)

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
        typeface = fuente
    }.also { Fuentes.aplicar(it, 400) }
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

    fun alto(): Int = (margenArriba + altoFila * 4 + margenAbajo + altoInferior).toInt()

    // ---------- Distribución ----------

    private fun letras(s: String) = s.map { Tecla(Tipo.LETRA, it.toString()) }
    private fun simbolos(s: String, ancho: Float = 1f) = s.split(" ").map { Tecla(Tipo.LETRA, it, ancho) }

    private fun filaInferior(cambio: Tecla) = if (conTira) listOf(
        Tecla(cambio.tipo, cambio.texto, 2.5f),
        Tecla(Tipo.ESPACIO, "", 5f),
        Tecla(Tipo.ENTER, ancho = 2.5f),
    ) else listOf(
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
        if (conTira) {
            val top = margenArriba + 4 * altoFila
            tira[0].rect.set(0f, top, unidad * 2f, top + altoInferior)
            tira[1].rect.set(width - unidad * 2f, top, width.toFloat(), top + altoInferior)
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
        animPulso?.cancel()
        teclaPulso = null
        pulso = 0f
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
        if (conTira && !modoCursor) dibujarTira(canvas)
    }

    private fun dibujarTira(c: Canvas) {
        for (t in tira) {
            val pres = presionadas.containsValue(t)
            pinturaIcono.color = if (t.tipo == Tipo.MIC && dictando) tema.azul else tema.texto
            pinturaIcono.alpha = if (pres) 110 else 255
            val cx = t.rect.centerX()
            val cy = t.rect.centerY() - dp(3f)
            if (t.tipo == Tipo.EMOJI) Iconos.emoji(c, cx, cy, dp(27f), pinturaIcono)
            else Iconos.microfono(c, cx, cy, dp(25f), pinturaIcono)
        }
        pinturaIcono.alpha = 255
    }

    private fun mezclar(a: Int, b: Int, f: Float): Int {
        fun canal(x: Int, y: Int) = (x + (y - x) * f).toInt().coerceIn(0, 255)
        return Color.argb(
            canal(Color.alpha(a), Color.alpha(b)), canal(Color.red(a), Color.red(b)),
            canal(Color.green(a), Color.green(b)), canal(Color.blue(a), Color.blue(b)),
        )
    }

    private fun animarPulso(t: Tecla?, hasta: Float) {
        if (t != null) teclaPulso = t
        animPulso?.cancel()
        animPulso = ValueAnimator.ofFloat(pulso, hasta).apply {
            duration = if (hasta > 0f) 110 else 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pulso = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dibujar(c: Canvas, t: Tecla) {
        val presionada = presionadas.containsValue(t) && !modoCursor
        val f = if (t === teclaPulso && !modoCursor) pulso else 0f
        val colorNormal = when {
            modoCursor -> tema.teclaFuncion
            t.tipo == Tipo.ENTER -> if (enterAzul) tema.azul else tema.teclaFuncion
            t.tipo == Tipo.LETRA || t.tipo == Tipo.ESPACIO -> tema.tecla
            t.tipo == Tipo.SHIFT -> if (shift > 0) tema.tecla else tema.teclaFuncion
            else -> tema.teclaFuncion
        }
        val colorPrevio = when {
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
        // Teclas de función: se iluminan y crecen un poquito, como gota de vidrio
        val colorFondo = if (f > 0f) mezclar(colorNormal, tema.teclaPresionada, f) else colorPrevio
        val r = if (f > 0f) RectF(t.rect).apply { inset(-dp(2.5f) * f, -dp(2.5f) * f) } else t.rect

        if (Color.alpha(tema.sombra) > 0) {
            pintura.color = tema.sombra
            c.drawRoundRect(r.left, r.top + dp(1f), r.right, r.bottom + dp(1f), radio, radio, pintura)
        }
        pintura.color = colorFondo
        c.drawRoundRect(r, radio, radio, pintura)

        // Liquid Glass: reflejo arriba y borde brillante
        pinturaBrillo.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.5f,
            tema.brillo, tema.brillo and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radio, radio, pinturaBrillo)
        pinturaBorde.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            tema.borde, tema.borde and 0x22FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radio, radio, pinturaBorde)

        // Moviendo el cursor: las teclas quedan en blanco, como en iPhone
        if (modoCursor) return
        // La letra presionada la muestra la cápsula de arriba, no la tecla
        if (t.tipo == Tipo.LETRA && presionada && vistaPrevia) return

        val cx = t.rect.centerX()
        val cy = t.rect.centerY()
        val colorContenido = if (t.tipo == Tipo.ENTER && enterAzul && !presionada) Color.WHITE else tema.texto
        pinturaIcono.color = colorContenido

        when (t.tipo) {
            Tipo.SHIFT -> Iconos.shift(c, cx, cy, dp(20f), pinturaIcono, shift > 0, shift == 2)
            Tipo.BORRAR -> Iconos.borrar(c, cx, cy, dp(22f), pinturaIcono)
            Tipo.EMOJI -> Iconos.emoji(c, cx, cy, dp(22f), pinturaIcono)
            Tipo.ENTER -> if (etiquetaEnter == "intro") Iconos.enter(c, cx, cy, dp(22f), pinturaIcono)
                          else texto(c, etiquetaEnter, cx, cy, dp(16f) * escalaLetra, colorContenido)
            Tipo.ESPACIO -> {
                if (t.texto.isNotEmpty()) texto(c, t.texto, cx, cy, dp(16f) * escalaLetra, colorContenido)
                else {
                    // Idioma en la esquina, como "EN ES" en iPhone
                    pinturaTexto.textSize = dp(9f)
                    pinturaTexto.color = tema.textoSecundario
                    c.drawText("ES", t.rect.right - dp(14f), t.rect.bottom - dp(6f), pinturaTexto)
                }
            }
            Tipo.LETRA -> {
                val minuscula = modo == Modo.LETRAS && shift == 0
                texto(c, etiqueta(t), cx, if (minuscula) cy - dp(1.5f) else cy, dp(23f) * escalaLetra, colorContenido)
            }
            else -> texto(c, t.texto, cx, cy, dp(16f) * escalaLetra, colorContenido)
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
            manejador.postDelayed(abrirVariantes, 480)
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
        if (conTira && y >= margenArriba + 4 * altoFila) {
            return tira.firstOrNull { x >= it.rect.left && x <= it.rect.right }
        }
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
                val i = e.actionIndex
                val id = e.getPointerId(i)
                // Escritura rápida: si otro dedo se quedó apoyado y abrió los acentos
                // o el cursor, se cierra y se sigue escribiendo sin perder letras
                if (punteroVariantes != -1) {
                    val base = teclaLarga
                    variantes.cerrar()
                    presionadas.remove(punteroVariantes)
                    punteroVariantes = -1
                    punteroLargo = -1
                    teclaLarga = null
                    if (base != null) {
                        acciones.texto(etiqueta(base))
                        if (shift == 1 && modo == Modo.LETRAS) shift = 0
                    }
                }
                if (modoCursor) {
                    presionadas.remove(punteroEspacio)
                    punteroEspacio = -1
                    modoCursor = false
                }
                val t = teclaEn(e.getX(i), e.getY(i)) ?: return true
                // Escritura con dos dedos: la tecla anterior (letra o espacio) se escribe ya
                val anteriores = presionadas.filterValues {
                    it.tipo == Tipo.LETRA || it.tipo == Tipo.ESPACIO
                }.keys.toList()
                for (k in anteriores) {
                    if (k == punteroLargo) {
                        manejador.removeCallbacks(abrirVariantes)
                        punteroLargo = -1
                        teclaLarga = null
                    }
                    if (k == punteroEspacio) {
                        manejador.removeCallbacks(activarCursor)
                        punteroEspacio = -1
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
                        if (vistaPrevia) preview.show(t.rect, etiqueta(t), tema)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val t = presionadas.remove(id)
                if (t != null && t === teclaPulso) animarPulso(null, 0f)
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
        if (t.tipo != Tipo.LETRA && t !in tira) animarPulso(t, 1f)
        when (t.tipo) {
            Tipo.LETRA -> {
                if (vistaPrevia) preview.show(t.rect, etiqueta(t), tema)
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
            Tipo.MIC -> acciones.dictar()
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
