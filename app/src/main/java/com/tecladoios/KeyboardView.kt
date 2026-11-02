package com.tecladoios

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

enum class Tipo { LETRA, SHIFT, BORRAR, A_NUMEROS, A_SIMBOLOS, A_LETRAS, EMOJI, ESPACIO, ENTER, MIC, UNA_MANO, VACIO }

/** PIN: teclado de solo números (contraseñas numéricas, códigos, teléfonos) */
enum class Modo { LETRAS, NUMEROS, SIMBOLOS, PIN, TELEFONO_SIMBOLOS }

class Tecla(val tipo: Tipo, val texto: String = "", val ancho: Float = 1f, val sub: String = "") {
    val rect = RectF()
    /** Forma con esquinas suaves (se calcula una vez) */
    val forma = Path()
    /** Filo de color de la app (se calcula una vez) */
    var shaderBorde: Shader? = null
}

/** Las teclas dibujadas directamente en pantalla, con el diseño del teclado del iPhone. */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val tema: Tema,
    private val acciones: Acciones,
) : View(context) {

    interface Acciones {
        /** [x], [y]: dónde cayó el dedo, medido en teclas (-1 si no aplica). Sirve para corregir con precisión. */
        fun texto(s: String, x: Float, y: Float)
        fun borrar()
        fun borrarPalabra()
        fun espacio()
        fun enter()
        fun abrirEmojis()
        fun retro(tipo: Tipo)
        fun moverCursor(pasos: Int)
        fun dictar()
        /** Deslizar para escribir: el recorrido del dedo, en teclas */
        fun deslizar(puntos: List<FloatArray>, proporcion: Float, mayus: Boolean)
    }

    private val d = Medidas.escala(context)
    private fun dp(v: Float) = v * d

    private val horizontal = Medidas.horizontal(context)
    private val altoFila = if (horizontal) dp(40f) else dp(54f)
    private val margenArriba = dp(8f)
    private val margenAbajo = dp(6f)
    private val sepH = dp(3f)
    private val sepV = dp(5.5f)
    private val radio = dp(5.6f)
    private val formaPulso = Path()
    private val rectPulso = RectF()

    var dictando = false
        set(value) { field = value; invalidate() }

    // Al presionar: la tecla se tiñe de vidrio del color de la app (sin brillo)
    private val pulsos = HashMap<Tecla, Float>()
    private val animPulsos = HashMap<Tecla, ValueAnimator>()

    // ---------- Opciones que pone el servicio ----------

    var soloNumeros = false
    var vistaPrevia = true
    var bloqMayusPermitido = true
    /** "pin", "telefono", "decimal" o "accion": qué va abajo a la izquierda del teclado numérico */
    var pinTipo = "pin"
    private var conMic = true
    private var unaMano = "no"
    private var deslizarActivo = true

    private var filas: List<List<Tecla>> = crearFilas(Modo.LETRAS)

    var modo = Modo.LETRAS
        set(value) {
            field = value
            filas = crearFilas(value)
            acomodar()
            invalidate()
        }

    private val esPad get() = modo == Modo.PIN || modo == Modo.TELEFONO_SIMBOLOS

    fun configurar(mic: Boolean, mano: String, deslizar: Boolean) {
        conMic = mic
        unaMano = mano
        deslizarActivo = deslizar
        modo = modo
    }

    private var shift = 0 // 0 apagado, 1 una letra, 2 bloqueado
    private var ultimoShift = 0L
    private var etiquetaEnter = "intro"
    private var enterAccion = false

    private val presionadas = HashMap<Int, Tecla>()
    /** Dedos cuya letra ya se escribió (porque otro dedo tocó después) pero siguen apoyados */
    private val escritas = HashSet<Int>()
    /** Dónde cayó cada dedo (para la autocorrección por posición, como Gboard) */
    private val puntosToque = HashMap<Int, FloatArray>()
    private val burbujas = KeyPreviewOverlay(this).apply { radioTecla = radio }
    private val variantes = VariantesPopup(this)

    // Acentos al mantener presionado
    private var punteroLargo = -1
    private var teclaLarga: Tecla? = null
    private var punteroVariantes = -1

    // Cursor con la barra espaciadora
    private var punteroEspacio = -1
    private var modoCursor = false
    private var xCursor = 0f

    // Deslizar para escribir
    private var punteroTrazo = -1
    private var deslizando = false
    private var trazoX0 = 0f
    private var trazoY0 = 0f
    private val trazo = ArrayList<FloatArray>()
    private val pathTrazo = Path()

    // Teclado con una mano
    private var areaX = 0f
    private var areaAncho = 0f
    private var unidad = 1f
    private val teclaUnaMano = Tecla(Tipo.UNA_MANO)

    private var repeticiones = 0

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinturaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, dp(0.7f))
    }
    private val pinturaTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }.also { Fuentes.aplicar(it, 400) }
    private val pinturaSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }.also { Fuentes.aplicar(it, 600) }
    private val pinturaIcono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pinturaTrazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(6f)
    }

    private val manejador = Handler(Looper.getMainLooper())

    /** Borrar mantenido: primero letra por letra, luego palabra por palabra (como iPhone) */
    private val repetirBorrar = object : Runnable {
        override fun run() {
            repeticiones++
            if (repeticiones > 14 && !esPad) {
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

    /** Fila de abajo como en la imagen: 123 · emoji · espacio · micrófono · intro */
    private fun filaInferior(tipo: Tipo, texto: String): List<Tecla> {
        val fila = ArrayList<Tecla>()
        fila.add(Tecla(tipo, texto, 1.3f))
        fila.add(Tecla(Tipo.EMOJI, ancho = 1.2f))
        fila.add(Tecla(Tipo.ESPACIO, "espacio", if (conMic) 4.0f else 5.2f))
        if (conMic) fila.add(Tecla(Tipo.MIC, ancho = 1.2f))
        fila.add(Tecla(Tipo.ENTER, ancho = 2.3f))
        return fila
    }

    private fun digito(n: String, sub: String) = Tecla(Tipo.LETRA, n, 1f, sub)

    private fun izquierdaPin(): Tecla = when (pinTipo) {
        "telefono" -> Tecla(Tipo.A_SIMBOLOS, "+*#")
        "decimal" -> Tecla(Tipo.LETRA, ".")
        "accion" -> Tecla(Tipo.ENTER)
        else -> Tecla(Tipo.VACIO)
    }

    private fun crearFilas(m: Modo): List<List<Tecla>> = when (m) {
        Modo.LETRAS -> listOf(
            letras("qwertyuiop"),
            letras("asdfghjklñ"),
            listOf(Tecla(Tipo.SHIFT, ancho = 1.4f)) + letras("zxcvbnm") + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tipo.A_NUMEROS, "123"),
        )
        Modo.NUMEROS -> listOf(
            letras("1234567890"),
            simbolos("- / : ; ( ) $ & @ \""),
            listOf(Tecla(Tipo.A_SIMBOLOS, "#+=", 1.4f)) + simbolos(". , ? ! '", 1.36f) + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tipo.A_LETRAS, "ABC"),
        )
        Modo.SIMBOLOS -> listOf(
            simbolos("[ ] { } # % ^ * + ="),
            simbolos("_ \\ | ~ < > € £ ¥ •"),
            listOf(Tecla(Tipo.A_NUMEROS, "123", 1.4f)) + simbolos(". , ? ! '", 1.36f) + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tipo.A_LETRAS, "ABC"),
        )
        // Como el teclado numérico del iPhone (para PIN, códigos y números)
        Modo.PIN -> listOf(
            listOf(digito("1", ""), digito("2", "ABC"), digito("3", "DEF")),
            listOf(digito("4", "GHI"), digito("5", "JKL"), digito("6", "MNO")),
            listOf(digito("7", "PQRS"), digito("8", "TUV"), digito("9", "WXYZ")),
            listOf(izquierdaPin(), digito("0", if (pinTipo == "telefono") "+" else ""), Tecla(Tipo.BORRAR)),
        )
        Modo.TELEFONO_SIMBOLOS -> listOf(
            listOf(digito("+", ""), digito("*", ""), digito("#", "")),
            listOf(digito("(", ""), digito(")", ""), digito("-", "")),
            listOf(digito(",", ""), digito(";", ""), digito("/", "")),
            listOf(Tecla(Tipo.A_NUMEROS, "123"), digito(".", ""), Tecla(Tipo.BORRAR)),
        )
    }

    private fun colocar(t: Tecla, x: Float, w: Float, top: Float, sep: Float) {
        t.rect.set(x + sep, top + sepV, x + w - sep, top + altoFila - sepV)
        Formas.suave(t.forma, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, radio)
        t.shaderBorde = LinearGradient(0f, t.rect.top, 0f, t.rect.bottom,
            tema.borde, (tema.borde and 0x00FFFFFF) or ((Color.alpha(tema.borde) / 3) shl 24), Shader.TileMode.CLAMP)
    }

    private fun acomodar() {
        if (width == 0) return
        val pad = esPad
        val mano = !pad && !horizontal && unaMano != "no"
        areaAncho = if (mano) width * 0.86f else width.toFloat()
        areaX = if (mano && unaMano == "der") width - areaAncho else 0f
        unidad = if (pad) areaAncho / 3f else areaAncho / 10f
        val sep = if (pad) dp(3.5f) else sepH
        filas.forEachIndexed { i, fila ->
            val top = margenArriba + i * altoFila
            val ladoIzq = fila.first()
            val ladoDer = fila.last()
            if (!pad && i == 2 && fila.size >= 3 && ladoIzq.tipo != Tipo.LETRA && ladoDer.tipo == Tipo.BORRAR) {
                // Tercera fila como en iPhone: mayúsculas y borrar pegados a los bordes,
                // las letras centradas debajo de las de arriba y un espacio libre entre ellas
                val lado = 1.3f * unidad
                colocar(ladoIzq, areaX, lado, top, sep)
                colocar(ladoDer, areaX + areaAncho - lado, lado, top, sep)
                val medio = fila.subList(1, fila.size - 1)
                val total = medio.sumOf { it.ancho.toDouble() }.toFloat() * unidad
                var x = areaX + (areaAncho - total) / 2f
                for (t in medio) {
                    val w = t.ancho * unidad
                    colocar(t, x, w, top, sep)
                    x += w
                }
                return@forEachIndexed
            }
            val total = fila.sumOf { it.ancho.toDouble() }.toFloat()
            var x = areaX + (areaAncho - total * unidad) / 2f
            for (t in fila) {
                val w = t.ancho * unidad
                colocar(t, x, w, top, sep)
                x += w
            }
        }
        if (mano) {
            val izq = if (unaMano == "izq") areaAncho else 0f
            val der = if (unaMano == "izq") width.toFloat() else areaX
            teclaUnaMano.rect.set(izq, margenArriba, der, margenArriba + 4 * altoFila)
        } else teclaUnaMano.rect.setEmpty()
        burbujas.altoTecla = altoFila - sepV * 2
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), alto())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        acomodar()
    }

    /** Posición del dedo medida en teclas: x en anchos de tecla, y en filas (0 = primera fila) */
    private fun enTeclas(x: Float, y: Float): FloatArray =
        floatArrayOf((x - areaX) / unidad, (y - margenArriba) / altoFila - 0.5f)

    // ---------- Lo que controla el servicio ----------

    fun configurarEnter(etiqueta: String, accion: Boolean) {
        etiquetaEnter = etiqueta
        enterAccion = accion
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
        animPulsos.values.forEach { it.cancel() }
        animPulsos.clear()
        pulsos.clear()
        presionadas.clear()
        escritas.clear()
        puntosToque.clear()
        manejador.removeCallbacks(repetirBorrar)
        manejador.removeCallbacks(abrirVariantes)
        manejador.removeCallbacks(activarCursor)
        burbujas.limpiar()
        variantes.cerrar()
        punteroVariantes = -1
        punteroLargo = -1
        teclaLarga = null
        punteroEspacio = -1
        modoCursor = false
        punteroTrazo = -1
        deslizando = false
        trazo.clear()
        invalidate()
    }

    fun ocultarPreview() {
        reiniciar()
        burbujas.cerrar()
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
        if (!teclaUnaMano.rect.isEmpty && !modoCursor) {
            pinturaIcono.color = tema.textoSecundario
            Iconos.chevron(canvas, teclaUnaMano.rect.centerX(), teclaUnaMano.rect.centerY(), dp(22f),
                pinturaIcono, haciaIzquierda = unaMano == "der")
        }
        if (deslizando && trazo.size >= 2) {
            pathTrazo.reset()
            pathTrazo.moveTo(trazo[0][0], trazo[0][1])
            for (k in 1 until trazo.size) pathTrazo.lineTo(trazo[k][0], trazo[k][1])
            pinturaTrazo.color = (tema.azul and 0x00FFFFFF) or (0xB4 shl 24)
            canvas.drawPath(pathTrazo, pinturaTrazo)
        }
    }

    private fun animarPulso(t: Tecla, hasta: Float) {
        animPulsos.remove(t)?.cancel()
        val anim = ValueAnimator.ofFloat(pulsos[t] ?: 0f, hasta).apply {
            duration = if (hasta > 0f) 70 else 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                if (hasta == 0f && v <= 0f) pulsos.remove(t) else pulsos[t] = v
                invalidate()
            }
        }
        animPulsos[t] = anim
        anim.start()
    }

    private fun dibujar(c: Canvas, t: Tecla) {
        if (t.tipo == Tipo.VACIO) return
        val presionada = presionadas.containsValue(t) && !modoCursor && !deslizando
        val f = if (!modoCursor) pulsos[t] ?: 0f else 0f
        val pad = esPad
        // En el teclado numérico, borrar no tiene fondo (como en iPhone)
        val sinFondo = pad && t.tipo == Tipo.BORRAR

        val base = when {
            modoCursor -> tema.teclaFuncion
            t.tipo == Tipo.ENTER -> if (enterAccion) tema.enterAccion else tema.teclaFuncion
            t.tipo == Tipo.LETRA || t.tipo == Tipo.ESPACIO -> tema.tecla
            t.tipo == Tipo.SHIFT -> if (shift > 0) tema.teclaActiva else tema.teclaFuncion
            else -> tema.teclaFuncion
        }
        val color = when {
            sinFondo -> if (presionada || f > 0f) tema.teclaPresionada else 0
            t.tipo == Tipo.LETRA && presionada && (!vistaPrevia || pad) -> tema.teclaPresionada
            t.tipo == Tipo.ESPACIO && presionada -> tema.teclaPresionada
            f > 0f -> Tema.mezclar(base, tema.teclaPresionada, f)
            else -> base
        }
        // Al presionar, la tecla apenas se hunde (no crece ni brilla)
        val r = if (f > 0f) rectPulso.apply { set(t.rect); inset(dp(0.6f) * f, dp(0.6f) * f) } else t.rect
        val forma = if (f > 0f) {
            Formas.suave(formaPulso, r.left, r.top, r.right, r.bottom, radio)
            formaPulso
        } else t.forma

        if (Color.alpha(color) > 0) {
            if (Color.alpha(tema.sombra) > 0) {
                pintura.color = tema.sombra
                c.save()
                c.translate(0f, dp(1f))
                c.drawPath(forma, pintura)
                c.restore()
            }
            pintura.color = color
            c.drawPath(forma, pintura)
            // Filo de vidrio con el color de la app
            if (!modoCursor) {
                pinturaBorde.shader = t.shaderBorde
                c.drawPath(forma, pinturaBorde)
                pinturaBorde.shader = null
            }
        }

        // Moviendo el cursor: las teclas quedan en blanco, como en iPhone
        if (modoCursor) return
        // La letra presionada la muestra la vista previa de arriba, no la tecla
        if (t.tipo == Tipo.LETRA && presionada && vistaPrevia && !pad) return

        val cx = t.rect.centerX()
        val cy = t.rect.centerY()
        val contenido = when {
            t.tipo == Tipo.ENTER && enterAccion && !presionada -> tema.textoAcento
            t.tipo == Tipo.SHIFT && shift > 0 -> tema.textoActivo
            else -> tema.texto
        }
        pinturaIcono.color = contenido

        when (t.tipo) {
            Tipo.SHIFT -> Iconos.shift(c, cx, cy, dp(20f), pinturaIcono, shift > 0, shift == 2)
            Tipo.BORRAR -> Iconos.borrar(c, cx, cy, dp(if (pad) 26f else 22f), pinturaIcono)
            Tipo.EMOJI -> {
                val fondoOpaco = color or (0xFF shl 24)
                Iconos.emojiLleno(c, cx, cy, dp(23f), pinturaIcono, fondoOpaco)
            }
            Tipo.MIC -> {
                pinturaIcono.color = if (dictando) tema.azul else contenido
                Iconos.microfonoLleno(c, cx, cy + dp(1f), dp(22f), pinturaIcono)
            }
            Tipo.ENTER -> if (pad) Iconos.visto(c, cx, cy, dp(22f), pinturaIcono)
                else texto(c, etiquetaEnter, cx, cy, dp(16f), contenido)
            Tipo.ESPACIO -> texto(c, t.texto, cx, cy, dp(16f), contenido)
            Tipo.LETRA -> {
                if (pad) {
                    val conSub = t.sub.isNotEmpty()
                    texto(c, t.texto, cx, if (conSub) cy - dp(5f) else cy, dp(26f), contenido)
                    if (conSub) {
                        pinturaSub.textSize = dp(9.5f)
                        pinturaSub.color = contenido
                        c.drawText(t.sub, cx, cy + dp(15f), pinturaSub)
                    }
                } else {
                    val minuscula = modo == Modo.LETRAS && shift == 0
                    texto(c, etiqueta(t), cx, if (minuscula) cy - dp(1.5f) else cy, dp(23f), contenido)
                }
            }
            else -> texto(c, t.texto, cx, cy, dp(16f), contenido)
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
        if (t.tipo == Tipo.LETRA && !esPad && VARIANTES.containsKey(t.texto)) {
            teclaLarga = t
            punteroLargo = id
            manejador.postDelayed(abrirVariantes, 480)
        } else {
            teclaLarga = null
            punteroLargo = -1
        }
    }

    private fun mostrarVariantes() {
        if (deslizando) return
        val t = teclaLarga ?: return
        val ops = VARIANTES[t.texto] ?: return
        val lista = if (modo == Modo.LETRAS && shift > 0) {
            ops.map { v -> v.uppercase().let { u -> if (u.length == 1) u else v } }
        } else ops
        burbujas.quitar(punteroLargo)
        if (punteroLargo == punteroTrazo) {
            punteroTrazo = -1
            trazo.clear()
        }
        variantes.mostrar(t.rect, lista, width.toFloat(), tema.oscuro, tema.azul)
        punteroVariantes = punteroLargo
        acciones.retro(Tipo.LETRA)
    }

    // ---------- Toques ----------

    private fun teclaEn(x: Float, y: Float): Tecla? {
        if (filas.isEmpty()) return null
        if (!teclaUnaMano.rect.isEmpty && x >= teclaUnaMano.rect.left && x <= teclaUnaMano.rect.right) {
            return teclaUnaMano
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

    private fun agregarPunto(x: Float, y: Float) {
        val u = trazo.lastOrNull()
        if (u != null && Math.hypot((x - u[0]).toDouble(), (y - u[1]).toDouble()) < dp(2.5f)) return
        trazo.add(floatArrayOf(x, y))
    }

    /** Termina el gesto de deslizar y manda el recorrido para buscar la palabra */
    private fun terminarDeslizado() {
        val id = punteroTrazo
        val puntos = trazo.map { enTeclas(it[0], it[1]) }
        val mayus = shift > 0
        punteroTrazo = -1
        deslizando = false
        trazo.clear()
        presionadas.remove(id)
        escritas.remove(id)
        puntosToque.remove(id)
        burbujas.quitar(id)
        if (puntos.size >= 2) acciones.deslizar(puntos, altoFila / unidad, mayus)
        if (shift == 1 && modo == Modo.LETRAS) shift = 0
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Primer dedo: si quedó algo colgado de antes (un toque perdido), se limpia
                if (e.actionMasked == MotionEvent.ACTION_DOWN && presionadas.isNotEmpty()) reiniciar()
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                // Otro dedo mientras deslizas: la palabra deslizada se escribe ya
                if (deslizando) terminarDeslizado()
                else if (punteroTrazo != -1) { punteroTrazo = -1; trazo.clear() }
                // Escritura rápida: si otro dedo se quedó apoyado y abrió los acentos
                // o el cursor, se cierra y se sigue escribiendo sin perder letras
                if (punteroVariantes != -1) {
                    val baseTecla = teclaLarga
                    variantes.cerrar()
                    presionadas.remove(punteroVariantes)
                    escritas.remove(punteroVariantes)
                    burbujas.soltar(punteroVariantes)
                    puntosToque.remove(punteroVariantes)
                    punteroVariantes = -1
                    punteroLargo = -1
                    teclaLarga = null
                    if (baseTecla != null) {
                        acciones.texto(etiqueta(baseTecla), -1f, -1f)
                        if (shift == 1 && modo == Modo.LETRAS) shift = 0
                    }
                }
                if (modoCursor) {
                    presionadas.remove(punteroEspacio)
                    escritas.remove(punteroEspacio)
                    punteroEspacio = -1
                    modoCursor = false
                }
                val t = teclaEn(x, y) ?: return true
                // Escritura con dos dedos: la letra del otro dedo se escribe ya (así no se
                // pierde el orden), pero su animación sigue hasta que ese dedo se levante
                for ((k, previa) in presionadas.toList()) {
                    if (k in escritas) continue
                    if (previa.tipo != Tipo.LETRA && previa.tipo != Tipo.ESPACIO) continue
                    if (k == punteroLargo) {
                        manejador.removeCallbacks(abrirVariantes)
                        punteroLargo = -1
                        teclaLarga = null
                    }
                    if (k == punteroEspacio) {
                        manejador.removeCallbacks(activarCursor)
                        punteroEspacio = -1
                    }
                    escritas.add(k)
                    soltar(previa, k)
                }
                presionadas[id] = t
                presionar(t, id, x, y)
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
                    if (id == punteroTrazo) {
                        for (h in 0 until e.historySize) agregarPunto(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        agregarPunto(x, y)
                        if (!deslizando && presionadas.size == 1 &&
                            Math.hypot((x - trazoX0).toDouble(), (y - trazoY0).toDouble()) > unidad * 0.9f
                        ) {
                            // Empezó a deslizar: ya no es un toque, es una palabra
                            deslizando = true
                            manejador.removeCallbacks(abrirVariantes)
                            punteroLargo = -1
                            teclaLarga = null
                            burbujas.quitar(id)
                        }
                        if (deslizando) {
                            invalidate()
                            continue
                        }
                    }
                    // Un dedo cuya letra ya se escribió no cambia de tecla al moverse
                    if (id in escritas) continue
                    val actual = presionadas[id] ?: continue
                    if (actual.tipo != Tipo.LETRA || esPad) continue
                    val t = teclaEn(x, y) ?: continue
                    if (t !== actual && t.tipo == Tipo.LETRA) {
                        // Solo cambia de tecla si el dedo entró de verdad en la otra (no por rozar
                        // el borde al escribir rápido): así no salen letras vecinas por error
                        val m = dp(5f)
                        if (x < t.rect.left + m || x > t.rect.right - m ||
                            y < t.rect.top + dp(3f) || y > t.rect.bottom - dp(3f)) continue
                        presionadas[id] = t
                        puntosToque[id] = floatArrayOf(x, y)
                        programarVariantes(t, id)
                        if (vistaPrevia) burbujas.mostrar(id, t.rect, etiqueta(t), tema, deslizando = true)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == punteroTrazo && deslizando) {
                    terminarDeslizado()
                    return true
                }
                if (id == punteroTrazo) {
                    punteroTrazo = -1
                    trazo.clear()
                }
                val t = presionadas.remove(id)
                val yaEscrita = escritas.remove(id)
                if (t != null && pulsos.containsKey(t) && presionadas.values.none { it === t }) animarPulso(t, 0f)
                // Cada dedo cierra solo su propia vista previa
                burbujas.soltar(id)
                when (id) {
                    punteroVariantes -> {
                        val elegido = variantes.elegido()
                        variantes.cerrar()
                        punteroVariantes = -1
                        punteroLargo = -1
                        teclaLarga = null
                        puntosToque.remove(id)
                        if (elegido != null) {
                            acciones.texto(elegido, -1f, -1f)
                            if (shift == 1 && modo == Modo.LETRAS) shift = 0
                        }
                    }
                    punteroEspacio -> {
                        manejador.removeCallbacks(activarCursor)
                        punteroEspacio = -1
                        if (modoCursor) modoCursor = false
                        else if (t != null && !yaEscrita) soltar(t, id)
                    }
                    else -> {
                        if (id == punteroLargo) {
                            manejador.removeCallbacks(abrirVariantes)
                            punteroLargo = -1
                            teclaLarga = null
                        }
                        if (t != null && !yaEscrita) soltar(t, id)
                    }
                }
                puntosToque.remove(id)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> reiniciar()
        }
        return true
    }

    private fun presionar(t: Tecla, id: Int, x: Float, y: Float) {
        if (t.tipo == Tipo.VACIO) return
        acciones.retro(t.tipo)
        if (t.tipo != Tipo.LETRA && t.tipo != Tipo.UNA_MANO) animarPulso(t, 1f)
        when (t.tipo) {
            Tipo.LETRA -> {
                puntosToque[id] = floatArrayOf(x, y)
                if (!esPad) {
                    if (vistaPrevia) burbujas.mostrar(id, t.rect, etiqueta(t), tema)
                    programarVariantes(t, id)
                    // Deslizar para escribir: solo con un dedo y en las letras
                    if (deslizarActivo && modo == Modo.LETRAS && presionadas.size == 1) {
                        punteroTrazo = id
                        trazo.clear()
                        trazo.add(floatArrayOf(x, y))
                        trazoX0 = x
                        trazoY0 = y
                    }
                }
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

    private fun soltar(t: Tecla, id: Int) {
        when (t.tipo) {
            Tipo.LETRA -> {
                val p = puntosToque.remove(id)
                val k = if (p != null && modo == Modo.LETRAS) enTeclas(p[0], p[1]) else null
                acciones.texto(etiqueta(t), k?.get(0) ?: -1f, k?.get(1) ?: -1f)
                if (shift == 1 && modo == Modo.LETRAS) shift = 0
                if ((modo == Modo.NUMEROS || modo == Modo.SIMBOLOS) && t.texto == "'" && !soloNumeros) modo = Modo.LETRAS
            }
            Tipo.BORRAR -> manejador.removeCallbacks(repetirBorrar)
            Tipo.ESPACIO -> {
                acciones.espacio()
                if ((modo == Modo.NUMEROS || modo == Modo.SIMBOLOS) && !soloNumeros) modo = Modo.LETRAS
            }
            Tipo.ENTER -> acciones.enter()
            Tipo.A_NUMEROS -> modo = if (modo == Modo.TELEFONO_SIMBOLOS) Modo.PIN else Modo.NUMEROS
            Tipo.A_SIMBOLOS -> modo = if (modo == Modo.PIN) Modo.TELEFONO_SIMBOLOS else Modo.SIMBOLOS
            Tipo.A_LETRAS -> modo = Modo.LETRAS
            Tipo.EMOJI -> acciones.abrirEmojis()
            Tipo.MIC -> acciones.dictar()
            Tipo.UNA_MANO -> {
                // Volver al teclado completo, como la flecha del iPhone
                unaMano = "no"
                Ajustes.prefs(context).edit().putString("una_mano", "no").apply()
                acomodar()
            }
            Tipo.SHIFT, Tipo.VACIO -> {}
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
