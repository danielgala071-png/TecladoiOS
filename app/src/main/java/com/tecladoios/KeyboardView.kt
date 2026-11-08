package com.tecladoios

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator

enum class Tipo { LETRA, SHIFT, BORRAR, A_NUMEROS, A_SIMBOLOS, A_LETRAS, EMOJI, ESPACIO, ENTER, MIC, UNA_MANO, VACIO }

/** PIN: teclado de solo números (contraseñas numéricas, códigos, teléfonos) */
enum class Modo { LETRAS, NUMEROS, SIMBOLOS, PIN, TELEFONO_SIMBOLOS }

class Tecla(val tipo: Tipo, val texto: String = "", val ancho: Float = 1f, val sub: String = "") {
    /** La letra en mayúscula ya calculada (no se crea un texto nuevo en cada dibujo) */
    val textoMayus: String = texto.uppercase()
    /** Índice de la letra para la predicción de toques (a–z = 0..25, ñ = 26, -1 = no es letra) */
    val letra: Int = if (texto.length == 1) {
        val c = texto[0]
        if (c == 'ñ') 26 else if (c in 'a'..'z') c - 'a' else -1
    } else -1
    /** Esquinas de abajo más curvas (123 a la izquierda, intro a la derecha, siguiendo la pantalla) */
    var curvaIzq = 0f
    var curvaDer = 0f
    val rect = RectF()
    /** Forma con esquinas suaves (se calcula una vez) */
    val forma = Path()
    /** Filo de color de la app (se calcula una vez) */
    var shaderBorde: Shader? = null
    /** Reflejo de vidrio translúcido (se calcula una vez) */
    var shaderBrillo: Shader? = null
    /** Sombra interior abajo: volumen del vidrio (se calcula una vez) */
    var shaderFondo: Shader? = null
    /** Línea de luz en el filo de arriba (se calcula una vez) */
    var shaderReflejo: Shader? = null
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
        /** Mantener presionada la tecla de emojis: abre los ajustes del teclado */
        fun abrirAjustes()
        fun retro(tipo: Tipo)
        fun moverCursor(pasos: Int)
        /** Cursor con la barra espaciadora hacia arriba o abajo (solo en textos de varias líneas) */
        fun moverCursorVertical(pasos: Int)
        /** Deslizar para escribir: el recorrido del dedo, en teclas */
        fun deslizar(puntos: List<FloatArray>, proporcion: Float, mayus: Boolean)
        /**
         * El dedo acaba de tocar la letra [s] (todavía no la suelta): el servicio empieza a
         * calcular las sugerencias de ya, así al soltarla la barra está lista al instante.
         */
        fun anticipar(s: String, x: Float, y: Float)
        /** Deslizaste la barra espaciadora hacia arriba: se pone la sugerencia del centro */
        fun aceptarSugerencia()
    }

    private val d = Medidas.escala(context)
    private fun dp(v: Float) = v * d

    /** Repinta solo el área de una tecla (más un margen para la sombra), no todo el teclado */
    private fun invalidarTecla(t: Tecla) {
        val r = t.rect
        if (r.isEmpty) { invalidate(); return }
        val m = dp(2f)
        invalidate((r.left - m).toInt(), (r.top - m).toInt(),
            (r.right + m).toInt() + 1, (r.bottom + m).toInt() + 1)
    }

    /** La primera tecla de ese tipo en el teclado actual (para repintar la shift, p. ej.) */
    private fun teclaDe(tipo: Tipo): Tecla? {
        for (fila in filas) for (t in fila) if (t.tipo == tipo) return t
        return null
    }

    private val horizontal = Medidas.horizontal(context)
    /** Tamaño elegido en Ajustes (1 = normal). Cambia el alto de las teclas y el tamaño de las letras. */
    private val factor = Ajustes.tamano(context) / 100f
    /** Medida de letras e íconos: crece y se achica junto con el teclado */
    private fun tx(v: Float) = v * d * factor
    /** Alto de una fila normal de letras */
    private val altoBase = (if (horizontal) dp(40f) else dp(54f)) * factor
    /** Fila de números arriba de las letras (ajuste): el teclado tiene una fila más */
    private val filaNumeros = Ajustes.filaNumeros(context)
    private val filasTotal = if (filaNumeros) 5 else 4
    /**
     * Alto de cada fila del teclado actual. El teclado mide siempre lo mismo: con la fila de
     * números, los teclados de 4 filas (123, #+=, PIN) usan teclas un poco más altas.
     */
    private var altoFila = altoBase
    private val margenArriba = dp(8f)
    private val margenAbajo = dp(6f)
    // Teclas un poco más anchas y altas (huecos más finos) y esquinas más suaves, como iOS 26
    private val sepH = dp(2.7f)
    private val sepV = dp(5.2f)
    // Esquinas un poco más redondas, como las teclas de iOS 26
    private val radio = dp(8.5f)
    private val formaPulso = Path()
    private val rectPulso = RectF()

    // Al presionar: la tecla se tiñe de vidrio del color de la app (sin brillo)
    private val pulsos = HashMap<Tecla, Float>()
    private val animPulsos = HashMap<Tecla, ValueAnimator>()

    // ---------- Opciones que pone el servicio ----------

    /**
     * Qué tan probable es cada letra como la siguiente (lo calcula el servicio con el
     * diccionario). Si tocas justo en el borde entre dos teclas, gana la letra que tiene
     * sentido, como en el iPhone. null = solo cuenta dónde cayó el dedo.
     */
    var probLetras: FloatArray? = null

    /** Tu manera de tocar: cuánto se corre tu dedo en cada letra (se aprende solo) */
    private val modelo = ModeloToque(context)

    // Mantener presionada la tecla de emojis abre los ajustes
    private var punteroEmoji = -1
    private var emojiLargo = false
    private val abrirAjustesTarea = Runnable {
        if (punteroEmoji != -1) {
            emojiLargo = true
            acciones.retro(Tipo.EMOJI)
            acciones.abrirAjustes()
        }
    }

    // Para descartar toques involuntarios: el último toque de letra (rebote del dedo)
    private var ultimoToqueMs = 0L
    private var ultimoToqueX = 0f
    private var ultimoToqueY = 0f
    private var ultimaTeclaToque: Tecla? = null

    /** Radio de las esquinas de la pantalla y alto de la barra de navegación (para la curva de abajo) */
    private var radioPantalla = 0f
    private var navAbajo = 0f

    var soloNumeros = false
    var vistaPrevia = true
    var bloqMayusPermitido = true
    /** "pin", "telefono", "decimal" o "accion": qué va abajo a la izquierda del teclado numérico */
    var pinTipo = "pin"
    private var unaMano = "no"
    private var deslizarActivo = true

    /** Ícono de la app donde escribes, muy tenue en la barra espaciadora (null = sin ícono) */
    var iconoApp: Bitmap? = null
        set(value) { if (field !== value) { field = value; invalidate() } }
    private val pinturaIconoApp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // En gris y transparente: se nota sin distraer
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.15f) })
    }
    private val rectIcono = RectF()

    private var filas: List<List<Tecla>> = crearFilas(Modo.LETRAS)

    var modo = Modo.LETRAS
        set(value) {
            val cambia = field != value
            val antes = field
            field = value
            filas = crearFilas(value)
            acomodar()
            // Al pasar de letras a 123 (y al revés) las letras nuevas entran deslizándose
            // apenas de costado: hacia 123 desde la derecha, de vuelta a ABC desde la izquierda
            if (cambia) animarEtiquetas(if (value == Modo.LETRAS || (antes == Modo.SIMBOLOS && value == Modo.NUMEROS)) -1 else 1)
            else invalidate()
        }

    /** Escritura rápida apaga los acentos al mantener presionado (lo pone el servicio) */
    var acentosAlMantener = true

    // ---------- Transición suave de las letras (como iOS) ----------

    /** 0 = letras invisibles, 1 = a la vista. Solo cambia por un instante al cambiar de teclado. */
    private var aparicion = 1f
    private var animAparicion: ValueAnimator? = null
    /** Hacia dónde se deslizan las letras al aparecer (1 = desde la derecha, -1 = izquierda, 0 = sin moverse) */
    private var dirAparicion = 0

    /** Corrimiento de las letras mientras aparecen */
    private fun corrimientoEtiqueta(): Float =
        if (aparicion >= 1f || dirAparicion == 0) 0f else (1f - aparicion) / 0.75f * dp(16f) * dirAparicion

    private fun animarEtiquetas(direccion: Int = 0) {
        dirAparicion = direccion
        animAparicion?.cancel()
        // Sin animación si el teclado no se ve (al abrirlo, por ejemplo): no gasta nada
        if (!isShown || width == 0) {
            aparicion = 1f
            invalidate()
            return
        }
        animAparicion = ValueAnimator.ofFloat(0.25f, 1f).apply {
            duration = if (direccion == 0) 110 else 170
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                aparicion = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** El color con la transparencia de la transición aplicada */
    private fun conAparicion(color: Int): Int {
        if (aparicion >= 1f) return color
        val a = (Color.alpha(color) * aparicion).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private val esPad get() = modo == Modo.PIN || modo == Modo.TELEFONO_SIMBOLOS

    fun configurar(mano: String, deslizar: Boolean) {
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
    private val variantes = VariantesPopup(this).apply {
        // Un toquecito al pasar de un acento a otro (se siente qué estás eligiendo)
        alCambiarSeleccion = { acciones.retro(Tipo.VACIO) }
    }

    // Acentos al mantener presionado
    private var punteroLargo = -1
    private var teclaLarga: Tecla? = null
    private var punteroVariantes = -1

    // Cursor con la barra espaciadora (como el trackpad del iPhone: a los lados y arriba/abajo)
    private var punteroEspacio = -1
    private var modoCursor = false
    private var xCursor = 0f
    private var yCursor = 0f
    /** Cuándo se apoyó el dedo en el espacio: un toque rápido que "barre" no mueve el cursor */
    private var espacioDesdeMs = 0L
    /** Este toque del espacio ya aceptó la sugerencia (al soltarlo no escribe espacio) */
    private var espacioAcepto = false
    /** Deslizar el espacio hacia arriba acepta la sugerencia del centro (lo pone el servicio) */
    var aceptarDeslizando = true

    // Deslizar desde 123 (o ABC, #+=): eliges un número o signo sin soltar el dedo,
    // se escribe y vuelve solo al teclado de antes, como en iPhone
    private var punteroCambio = -1
    private var cambioDeslizado = false
    private var modoAntesCambio = Modo.LETRAS
    private var x0Cambio = 0f
    private var y0Cambio = 0f

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
        strokeWidth = maxOf(1f, dp(0.6f))
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
    /** Línea de luz finita en el filo de arriba de cada tecla */
    private val pinturaReflejo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = maxOf(1f, dp(0.75f))
    }
    /** Para tapar una tecla de la capa fija antes de dibujarla presionada (el fondo es negro) */
    private val pinturaTapar = Paint().apply { color = tema.fondo or (0xFF shl 24) }

    // ---------- Capa fija (rendimiento) ----------
    /**
     * Todas las teclas en reposo se graban una sola vez en una capa de la GPU. En cada toque
     * solo se dibuja encima la tecla que cambia: el teclado ya no recalcula 33 teclas con sus
     * degradados por cada letra, así responde más rápido y gasta menos batería.
     * La capa se vuelve a grabar solo si cambia algo de lo que muestra (mayúsculas, modo...).
     */
    private val capaFija = RenderNode("teclas")
    private var versionDistribucion = 0
    private var capaVersion = -1
    private var capaModo: Modo? = null
    private var capaShift = -1
    private var capaEnter = ""
    private var capaAccion = false
    private var capaCursor = false
    private var capaAparicion = -1f
    private var capaAncho = -1
    private var capaAlto = -1
    private var capaIcono: Bitmap? = null

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
                manejador.postDelayed(this, 160)
            } else {
                acciones.borrar()
                // Empieza más tranquilo y va acelerando (más control para borrar pocas letras)
                manejador.postDelayed(this, if (repeticiones < 4) 85L else 60L)
            }
            acciones.retro(Tipo.BORRAR)
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

    fun alto(): Int = (margenArriba + altoBase * filasTotal + margenAbajo).toInt()

    /** Filas que hay arriba de las letras (la de números) para medir los toques como siempre */
    private val desfaseFilas get() = if (filaNumeros && modo == Modo.LETRAS) 1 else 0

    // ---------- Distribución ----------

    private fun letras(s: String) = s.map { Tecla(Tipo.LETRA, it.toString()) }
    private fun simbolos(s: String, ancho: Float = 1f) = s.split(" ").map { Tecla(Tipo.LETRA, it, ancho) }

    /** Fila de abajo: 123 · emoji · espacio · intro (sin micrófono) */
    private fun filaInferior(tipo: Tipo, texto: String): List<Tecla> = listOf(
        Tecla(tipo, texto, 1.3f),
        Tecla(Tipo.EMOJI, ancho = 1.2f),
        Tecla(Tipo.ESPACIO, "espacio", 5.2f),
        Tecla(Tipo.ENTER, ancho = 2.3f),
    )

    private fun digito(n: String, sub: String) = Tecla(Tipo.LETRA, n, 1f, sub)

    private fun izquierdaPin(): Tecla = when (pinTipo) {
        "telefono" -> Tecla(Tipo.A_SIMBOLOS, "+*#")
        "decimal" -> Tecla(Tipo.LETRA, ".")
        "accion" -> Tecla(Tipo.ENTER)
        else -> Tecla(Tipo.VACIO)
    }

    private fun crearFilas(m: Modo): List<List<Tecla>> = when (m) {
        Modo.LETRAS -> (if (filaNumeros) listOf(letras("1234567890")) else emptyList()) + listOf(
            letras("qwertyuiop"),
            letras("asdfghjklñ"),
            listOf(Tecla(Tipo.SHIFT, ancho = 1.4f)) + letras("zxcvbnm") + Tecla(Tipo.BORRAR, ancho = 1.4f),
            filaInferior(Tipo.A_NUMEROS, "123"),
        )
        Modo.NUMEROS -> listOf(
            letras("1234567890"),
            simbolos("- / : ; ( ) S/ & @ \""),
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

    /** El mismo color con la opacidad multiplicada por [f] */
    private fun conAlfa(color: Int, f: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * f).toInt().coerceIn(0, 255) shl 24)

    private fun colocar(t: Tecla, x: Float, w: Float, top: Float, sep: Float) {
        t.rect.set(x + sep, top + sepV, x + w - sep, top + altoFila - sepV)
        Formas.suave(t.forma, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, radio, 0f, t.curvaDer, t.curvaIzq)
        val r = t.rect
        // Filo del color de la app como luz que entra en diagonal: intenso arriba a la izquierda,
        // tenue al medio y un poco más vivo abajo (como el borde de un vidrio grueso)
        // Filo parejo en toda la tecla: un poco más vivo arriba (donde "pega la luz") y más
        // tenue abajo. Antes iba en diagonal y la esquina de arriba a la izquierda se veía manchada.
        t.shaderBorde = LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(tema.borde, conAlfa(tema.borde, 0.5f), conAlfa(tema.borde, 0.32f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        // Reflejo arriba de la tecla: le da el efecto translúcido de vidrio
        t.shaderBrillo = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.55f,
            tema.brillo, tema.brillo and 0x00FFFFFF, Shader.TileMode.CLAMP)
        // Sombra interior abajo: el vidrio se ve con volumen, no plano
        t.shaderFondo = LinearGradient(0f, r.top + r.height() * 0.45f, 0f, r.bottom,
            tema.profundidad and 0x00FFFFFF, tema.profundidad, Shader.TileMode.CLAMP)
        // Línea de luz en el filo de arriba: brilla al centro y se apaga hacia las esquinas
        t.shaderReflejo = LinearGradient(r.left, 0f, r.right, 0f,
            intArrayOf(tema.reflejo and 0x00FFFFFF, tema.reflejo, tema.reflejo and 0x00FFFFFF),
            null, Shader.TileMode.CLAMP)
    }

    /**
     * 123 (abajo a la izquierda) e intro (abajo a la derecha) terminan en curva: su esquina
     * de afuera sigue la curva de la pantalla, como en el iPhone.
     */
    private fun curvaExterior(t: Tecla, izquierda: Boolean): Float {
        val alto = t.rect.height()
        // Distancia de la tecla a la esquina de la pantalla
        val dx = if (izquierda) t.rect.left else width - t.rect.right
        val dy = (alto() - t.rect.bottom) + navAbajo
        val concentrica = radioPantalla - maxOf(dx, dy)
        // Curva suave: sigue la pantalla pero sin que la tecla parezca una pestaña
        return maxOf(concentrica, alto * 0.26f).coerceAtMost(alto * 0.34f)
    }

    private fun acomodar() {
        // Cambió la distribución: la capa fija se vuelve a grabar en el próximo dibujo
        versionDistribucion++
        if (filas.isNotEmpty()) altoFila = altoBase * filasTotal / filas.size
        if (width == 0) return
        val pad = esPad
        for (fila in filas) for (t in fila) { t.curvaIzq = 0f; t.curvaDer = 0f }
        if (!pad) {
            val abajo = filas.last()
            val primera = abajo.first()
            val ultima = abajo.last()
            if (primera.tipo == Tipo.A_NUMEROS || primera.tipo == Tipo.A_LETRAS) primera.curvaIzq = -1f
            if (ultima.tipo == Tipo.ENTER) ultima.curvaDer = -1f
        }
        val mano = !pad && !horizontal && unaMano != "no"
        areaAncho = if (mano) width * 0.86f else width.toFloat()
        areaX = if (mano && unaMano == "der") width - areaAncho else 0f
        unidad = if (pad) areaAncho / 3f else areaAncho / 10f
        val sep = if (pad) dp(3.5f) else sepH
        filas.forEachIndexed { i, fila ->
            val top = margenArriba + i * altoFila
            val ladoIzq = fila.first()
            val ladoDer = fila.last()
            if (!pad && i == filas.size - 2 && fila.size >= 3 && ladoIzq.tipo != Tipo.LETRA && ladoDer.tipo == Tipo.BORRAR) {
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
        // Con la posición ya puesta, se calcula la curva de afuera y se rehace su forma
        for (t in filas.last()) {
            if (t.curvaIzq < 0f || t.curvaDer < 0f) {
                if (t.curvaIzq < 0f) t.curvaIzq = curvaExterior(t, true)
                if (t.curvaDer < 0f) t.curvaDer = curvaExterior(t, false)
                Formas.suave(t.forma, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, radio, 0f, t.curvaDer, t.curvaIzq)
            }
        }
        if (mano) {
            val izq = if (unaMano == "izq") areaAncho else 0f
            val der = if (unaMano == "izq") width.toFloat() else areaX
            teclaUnaMano.rect.set(izq, margenArriba, der, margenArriba + filas.size * altoFila)
        } else teclaUnaMano.rect.setEmpty()
        burbujas.altoTecla = altoFila - sepV * 2
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), alto())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        leerPantalla()
        acomodar()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        modelo.revisar()
        if (leerPantalla()) acomodar()
    }

    /** Lee la curva de las esquinas de la pantalla y la barra de navegación. true si cambió. */
    private fun leerPantalla(): Boolean {
        val ins = rootWindowInsets ?: return false
        var r = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val a = ins.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            val b = ins.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
            r = maxOf(a, b).toFloat()
        }
        val nav = ins.getInsets(WindowInsets.Type.navigationBars()).bottom.toFloat()
        if (r == radioPantalla && nav == navAbajo) return false
        radioPantalla = r
        navAbajo = nav
        return true
    }

    /** Posición del dedo medida en teclas: x en anchos de tecla, y en filas (0 = primera fila) */
    private fun enTeclas(x: Float, y: Float): FloatArray =
        floatArrayOf((x - areaX) / unidad, (y - margenArriba) / altoFila - 0.5f - desfaseFilas)

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
        manejador.removeCallbacks(abrirAjustesTarea)
        punteroEmoji = -1
        burbujas.limpiar()
        variantes.cerrar()
        punteroVariantes = -1
        punteroLargo = -1
        teclaLarga = null
        punteroEspacio = -1
        espacioAcepto = false
        modoCursor = false
        punteroCambio = -1
        cambioDeslizado = false
        punteroTrazo = -1
        deslizando = false
        trazo.clear()
        invalidate()
    }

    private val tareaPrepararPreview = Runnable {
        if (vistaPrevia && isAttachedToWindow && width > 0) burbujas.preparar()
    }

    /** Deja lista la capa de la vista previa antes del primer toque */
    fun prepararVistaPrevia() {
        removeCallbacks(tareaPrepararPreview)
        postDelayed(tareaPrepararPreview, 90)
    }

    fun ocultarPreview() {
        removeCallbacks(tareaPrepararPreview)
        reiniciar()
        burbujas.cerrar()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        modelo.guardar()
        // Libera la memoria de la GPU mientras el teclado no se ve
        capaFija.discardDisplayList()
        capaVersion = -1
        animAparicion?.cancel()
        aparicion = 1f
        ocultarPreview()
    }

    // ---------- Dibujo ----------

    private fun etiqueta(t: Tecla) =
        if (modo == Modo.LETRAS && shift > 0) t.textoMayus else t.texto

    /** ¿La tecla se ve presionada ahora? */
    private fun estaPresionada(t: Tecla) = !modoCursor && !deslizando && presionadas.containsValue(t)

    /** Graba las teclas en reposo en la capa fija, solo si cambió algo de lo que se ve */
    private fun asegurarCapa() {
        val w = width
        val h = height
        if (capaVersion == versionDistribucion && capaModo == modo && capaShift == shift &&
            capaEnter == etiquetaEnter && capaAccion == enterAccion && capaCursor == modoCursor &&
            capaAparicion == aparicion && capaAncho == w && capaAlto == h && capaIcono === iconoApp &&
            capaFija.hasDisplayList()
        ) return
        capaFija.setPosition(0, 0, w, h)
        val rc = capaFija.beginRecording(w, h)
        try {
            for (fila in filas) for (t in fila) dibujar(rc, t, false, 0f)
        } finally {
            capaFija.endRecording()
        }
        capaVersion = versionDistribucion
        capaModo = modo
        capaShift = shift
        capaEnter = etiquetaEnter
        capaAccion = enterAccion
        capaCursor = modoCursor
        capaAparicion = aparicion
        capaAncho = w
        capaAlto = h
        capaIcono = iconoApp
    }

    override fun onDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated && width > 0 && height > 0) {
            asegurarCapa()
            canvas.drawRenderNode(capaFija)
            // Encima, solo las teclas que están cambiando (presionadas o terminando su animación)
            if (!modoCursor) {
                val m = dp(1.5f)
                for (fila in filas) for (t in fila) {
                    if (t.tipo == Tipo.VACIO) continue
                    val presionada = estaPresionada(t)
                    val f = pulsos[t] ?: 0f
                    if (!presionada && f <= 0f) continue
                    val r = t.rect
                    canvas.drawRect(r.left - m, r.top - m, r.right + m, r.bottom + m, pinturaTapar)
                    dibujar(canvas, t, presionada, f)
                }
            }
        } else {
            for (fila in filas) for (t in fila) {
                dibujar(canvas, t, estaPresionada(t), if (!modoCursor) pulsos[t] ?: 0f else 0f)
            }
        }
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
        if (hasta > 0f) {
            // Al presionar la tecla se marca AL INSTANTE (sin animación de entrada): se siente
            // más directa, como en iPhone. Solo la salida es suave.
            pulsos[t] = hasta
            invalidarTecla(t)
            return
        }
        val desde = pulsos[t] ?: return
        val anim = ValueAnimator.ofFloat(desde, 0f).apply {
            duration = 150
            interpolator = FRENO_PULSO
            addUpdateListener {
                val v = it.animatedValue as Float
                if (v <= 0f) pulsos.remove(t) else pulsos[t] = v
                invalidarTecla(t)
            }
        }
        animPulsos[t] = anim
        anim.start()
    }

    /** Mezcla dos colores incluida su transparencia (así la tecla se apaga sin saltos) */
    private fun mezclarConAlfa(a: Int, b: Int, f: Float): Int {
        fun c(x: Int, y: Int) = (x + (y - x) * f).toInt().coerceIn(0, 255)
        return Color.argb(c(Color.alpha(a), Color.alpha(b)), c(Color.red(a), Color.red(b)),
            c(Color.green(a), Color.green(b)), c(Color.blue(a), Color.blue(b)))
    }

    /** Apaga (suave) las teclas marcadas que ya no tiene ningún dedo encima */
    private fun apagarSueltas() {
        if (pulsos.isEmpty()) return
        for (t in pulsos.keys.toList()) {
            if (presionadas.values.none { it === t } && animPulsos[t]?.isRunning != true) animarPulso(t, 0f)
        }
    }

    /** Dibuja una tecla. [presionada] y [f] (animación del toque) vienen de afuera: en la capa fija van en reposo. */
    private fun dibujar(c: Canvas, t: Tecla, presionada: Boolean, f: Float) {
        if (t.tipo == Tipo.VACIO) return
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
            f > 0f -> mezclarConAlfa(base, tema.teclaPresionada, f)
            else -> base
        }
        // Cuánto está "encendida" la tecla ahora (para agrandar apenas su letra al tocarla)
        val toque = if (presionada) 1f else f
        // Al presionar, la tecla apenas se hunde (no crece ni brilla)
        val r = if (f > 0f) rectPulso.apply { set(t.rect); inset(dp(0.6f) * f, dp(0.6f) * f) } else t.rect
        val forma = if (f > 0f) {
            Formas.suave(formaPulso, r.left, r.top, r.right, r.bottom, radio, 0f, t.curvaDer, t.curvaIzq)
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
            // Vidrio (no en mayúsculas activadas ni en el enter de color): reflejo arriba y
            // volumen abajo. La pintura va opaca para que el degradado tenga su propia intensidad.
            // El intro de color (enviar, buscar) también lleva vidrio: reflejo y volumen
            val vidrio = !modoCursor && (Color.alpha(color) < 0xC0 || (t.tipo == Tipo.ENTER && enterAccion))
            if (vidrio) {
                pintura.color = Color.BLACK
                pintura.shader = t.shaderBrillo
                c.drawPath(forma, pintura)
                pintura.shader = t.shaderFondo
                c.drawPath(forma, pintura)
                pintura.shader = null
            }
            // Filo de vidrio con el color de la app
            if (!modoCursor) {
                pinturaBorde.shader = t.shaderBorde
                c.drawPath(forma, pinturaBorde)
                pinturaBorde.shader = null
                // Al presionar, el filo se enciende con el color de la app y se apaga suave
                // (la línea de luz de arriba se quitó: se veía como un rayón en cada tecla)
                val brilloFilo = if (presionada && t.tipo != Tipo.LETRA) 1f else f
                if (brilloFilo > 0f) {
                    pinturaBorde.color = conAlfa(tema.azul, 0.9f * brilloFilo)
                    c.drawPath(forma, pinturaBorde)
                    pinturaBorde.color = Color.BLACK
                }
            }
        }

        // Moviendo el cursor: las teclas quedan en blanco, como en iPhone
        if (modoCursor) return
        // La letra presionada la muestra la vista previa de arriba, no la tecla
        if (t.tipo == Tipo.LETRA && presionada && vistaPrevia && !pad) return

        val cx = t.rect.centerX()
        val cy = t.rect.centerY()
        val contenido = conAparicion(when {
            t.tipo == Tipo.ENTER && enterAccion && !presionada -> tema.textoAcento
            t.tipo == Tipo.SHIFT && shift > 0 -> tema.textoActivo
            else -> tema.texto
        })
        pinturaIcono.color = contenido

        when (t.tipo) {
            Tipo.SHIFT -> Iconos.shift(c, cx, cy, tx(20f), pinturaIcono, shift > 0, shift == 2)
            // Como en iPhone: al presionar borrar, el ícono se rellena (la X queda calada)
            Tipo.BORRAR -> Iconos.borrar(c, cx, cy, tx(if (pad) 26f else 22f), pinturaIcono,
                lleno = toque > 0.5f, hueco = Tema.sobreNegro(color) or (0xFF shl 24))
            Tipo.EMOJI -> {
                // Carita de línea fina (como iOS): antes era una bola blanca rellena que
                // llamaba más la atención que las letras
                Iconos.emoji(c, cx, cy, tx(23f), pinturaIcono)
            }
            Tipo.MIC -> {}
            Tipo.ENTER -> if (pad) Iconos.visto(c, cx, cy, tx(22f), pinturaIcono)
                else texto(c, etiquetaEnter, cx, cy, tx(16f), contenido)
            Tipo.ESPACIO -> {
                val icono = iconoApp
                if (icono == null || presionada || t.rect.width() < tx(120f)) {
                    texto(c, t.texto, cx, cy, tx(16f), contenido)
                } else {
                    // Ícono de la app a la izquierda de "espacio", todo centrado
                    pinturaTexto.textSize = tx(16f)
                    val ancho = pinturaTexto.measureText(t.texto)
                    val lado = tx(15f)
                    val hueco = tx(7f)
                    val izq = cx - (lado + hueco + ancho) / 2f
                    rectIcono.set(izq, cy - lado / 2f, izq + lado, cy + lado / 2f)
                    pinturaIconoApp.alpha = (120 * aparicion).toInt().coerceIn(0, 255)
                    c.drawBitmap(icono, null, rectIcono, pinturaIconoApp)
                    texto(c, t.texto, izq + lado + hueco + ancho / 2f, cy, tx(16f), contenido)
                }
            }
            Tipo.LETRA -> {
                if (pad) {
                    val conSub = t.sub.isNotEmpty()
                    texto(c, t.texto, cx, if (conSub) cy - tx(5f) else cy, tx(26f) * (1f + 0.08f * toque), contenido)
                    if (conSub) {
                        pinturaSub.textSize = tx(9.5f)
                        pinturaSub.color = contenido
                        c.drawText(t.sub, cx, cy + tx(15f), pinturaSub)
                    }
                } else {
                    val minuscula = modo == Modo.LETRAS && shift == 0
                    // La letra crece con el teclado, pero sin salirse del ancho de la tecla
                    // Sin burbuja, la letra tocada crece un poquito: se siente el toque
                    val crece = if (!vistaPrevia) 1f + 0.12f * toque else 1f
                    val tam = minOf(tx(23f), t.rect.width() * 0.78f) * crece
                    texto(c, etiqueta(t), cx, if (minuscula) cy - tx(1.5f) else cy, tam, contenido)
                }
            }
            else -> texto(c, t.texto, cx, cy, minOf(tx(16f), t.rect.width() * 0.42f), contenido)
        }
    }

    /** Medidas de la letra reutilizadas (pedirlas crea un objeto nuevo cada vez) */
    private val medidasLetra = Paint.FontMetrics()
    private var tamMedido = -1f

    private fun texto(c: Canvas, s: String, cx: Float, cy: Float, tam: Float, color: Int) {
        pinturaTexto.textSize = tam
        pinturaTexto.color = color
        if (tam != tamMedido) {
            pinturaTexto.getFontMetrics(medidasLetra)
            tamMedido = tam
        }
        c.drawText(s, cx + corrimientoEtiqueta(), cy - (medidasLetra.ascent + medidasLetra.descent) / 2f, pinturaTexto)
    }

    // ---------- Acentos ----------

    private fun programarVariantes(t: Tecla, id: Int) {
        manejador.removeCallbacks(abrirVariantes)
        if (acentosAlMantener && t.tipo == Tipo.LETRA && !esPad && VARIANTES.containsKey(t.texto)) {
            teclaLarga = t
            punteroLargo = id
            manejador.postDelayed(abrirVariantes, 430)
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
        // Si sueles tocar un poquito más abajo (o arriba) de las teclas, la fila se elige con eso
        val yAjustada = if (modo == Modo.LETRAS) y - modelo.dyGeneral * altoFila else y
        val i = ((yAjustada - margenArriba) / altoFila).toInt().coerceIn(0, filas.size - 1)
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
        if (mejor != null && mejor.letra >= 0 && modo == Modo.LETRAS) mejor = ajustarATuToque(x, mejor, i)
        val p = probLetras
        if (p != null && mejor != null && mejor.letra >= 0 && modo == Modo.LETRAS) {
            return conPrediccion(x, y, mejor, i, p)
        }
        return mejor
    }

    /**
     * Entre letras vecinas de la misma fila gana la que tenga su centro (corrido a tu
     * manera de tocar) más cerca del dedo. Solo cambia cerca del borde: nunca elige una
     * tecla que esté a más de un cuarto de tecla del dedo.
     */
    private fun ajustarATuToque(x: Float, base: Tecla, fila: Int): Tecla {
        var mejor = base
        var menor = Math.abs(x - (base.rect.centerX() + modelo.offX(base.letra) * unidad))
        for (t in filas[fila]) {
            if (t === base || t.letra < 0) continue
            val r = t.rect
            val fuera = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            if (fuera > unidad * 0.25f) continue
            val d = Math.abs(x - (r.centerX() + modelo.offX(t.letra) * unidad))
            if (d < menor) { menor = d; mejor = t }
        }
        return mejor
    }

    /**
     * Toque inteligente (como el iPhone): cerca del borde entre dos letras, gana la que
     * tiene más sentido para la palabra que estás escribiendo. En el centro de la tecla
     * manda siempre el dedo: solo cambia en una franja angosta junto al borde.
     */
    private fun conPrediccion(x: Float, y: Float, base: Tecla, fila: Int, p: FloatArray): Tecla {
        var mejor = base
        var mejorPuntaje = puntajeToque(base, x, y, p)
        // Zona donde la letra probable "crece" sin que se vea (como en iPhone)
        val zonaX = unidad * 0.28f
        val zonaY = altoFila * 0.24f
        for (f in maxOf(0, fila - 1)..minOf(filas.size - 1, fila + 1)) {
            for (t in filas[f]) {
                if (t === base || t.letra < 0) continue
                val r = t.rect
                if (x < r.left - zonaX || x > r.right + zonaX || y < r.top - zonaY || y > r.bottom + zonaY) continue
                val s = puntajeToque(t, x, y, p)
                if (s > mejorPuntaje) { mejorPuntaje = s; mejor = t }
            }
        }
        return mejor
    }

    private fun puntajeToque(t: Tecla, x: Float, y: Float, p: FloatArray): Float {
        val prob = p.getOrElse(t.letra) { 0f }
        // Distancia al centro de la tecla corrido a tu manera de tocar
        val dx = (x - t.rect.centerX() - modelo.offX(t.letra) * unidad) / unidad
        val dy = (y - t.rect.centerY() - modelo.offY(t.letra) * altoFila) / unidad
        val d2 = dx * dx + dy * dy * 0.8f
        // Distancia al centro (en anchos de tecla) contra lo probable de la letra
        return Math.log(prob + 0.02).toFloat() * 0.45f - d2 / (2f * 0.33f * 0.33f)
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
        apagarSueltas()
        if (puntos.size >= 2) acciones.deslizar(puntos, altoFila / unidad, mayus)
        if (shift == 1 && modo == Modo.LETRAS) shift = 0
        invalidate()
    }

    // ---------- Accesibilidad (TalkBack) ----------

    private val accesibilidad = context.getSystemService(AccessibilityManager::class.java)
    private var teclaExplorada: Tecla? = null

    /** Cómo lee TalkBack cada tecla */
    private fun nombreAccesible(t: Tecla): String = when (t.tipo) {
        Tipo.LETRA -> etiqueta(t)
        Tipo.SHIFT -> when (shift) { 2 -> "bloqueo de mayúsculas"; 1 -> "mayúsculas activadas"; else -> "mayúsculas" }
        Tipo.BORRAR -> "borrar"
        Tipo.ESPACIO -> "espacio"
        Tipo.ENTER -> etiquetaEnter
        Tipo.A_NUMEROS -> "números"
        Tipo.A_SIMBOLOS -> "más símbolos"
        Tipo.A_LETRAS -> "letras"
        Tipo.EMOJI -> "emojis"
        Tipo.UNA_MANO -> "teclado completo"
        Tipo.MIC, Tipo.VACIO -> ""
    }

    /**
     * Con TalkBack: deslizas el dedo y te dice cada tecla; al levantarlo sobre una tecla, se
     * escribe ("levantar para escribir", como Gboard). Sin TalkBack no hace nada.
     */
    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (accesibilidad?.isTouchExplorationEnabled != true) return super.onHoverEvent(e)
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val t = teclaEn(x, y)
                if (t !== teclaExplorada) {
                    teclaExplorada = t
                    if (t != null) {
                        val nombre = nombreAccesible(t)
                        if (nombre.isNotEmpty()) announceForAccessibility(nombre)
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val t = teclaExplorada
                teclaExplorada = null
                if (t != null && x >= 0f && y >= 0f && x <= width && y <= height && teclaEn(x, y) === t) activarAccesible(t)
            }
        }
        return true
    }

    /** Escribe o usa la tecla elegida con TalkBack (lo mismo que tocarla y soltarla) */
    private fun activarAccesible(t: Tecla) {
        when (t.tipo) {
            Tipo.VACIO, Tipo.MIC -> return
            Tipo.BORRAR -> { acciones.retro(Tipo.BORRAR); acciones.borrar() }
            Tipo.SHIFT -> {
                presionar(t, -1, t.rect.centerX(), t.rect.centerY())
                animarPulso(t, 0f)
            }
            else -> { acciones.retro(t.tipo); soltar(t, -1) }
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Entrega los toques apenas llegan (sin agruparlos por cuadro): menos latencia
                // al escribir rápido, así se siente menos "pegajoso".
                requestUnbufferedDispatch(e)
                // Primer dedo: si quedó algo colgado de antes (un toque perdido), se limpia
                if (e.actionMasked == MotionEvent.ACTION_DOWN && presionadas.isNotEmpty()) reiniciar()
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                // Toque involuntario: la palma o el costado de la mano (contacto enorme)
                val contacto = e.getTouchMajor(i)
                if (contacto > maxOf(dp(75f), unidad * 2.6f) && contacto < width) return true
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
                // Otro dedo mientras deslizabas desde 123: se deja de deslizar y se sigue
                // escribiendo normal (el teclado nuevo se queda)
                if (punteroCambio != -1) {
                    punteroCambio = -1
                    cambioDeslizado = false
                }
                val t = teclaEn(x, y) ?: return true
                // Rebote: la misma letra otra vez casi en el mismo punto en menos de 45 ms
                // (el dedo "rebota" o roza dos veces). Nadie escribe así de rápido a propósito.
                if (t.tipo == Tipo.LETRA) {
                    if (t === ultimaTeclaToque && e.eventTime - ultimoToqueMs < 45 &&
                        Math.hypot((x - ultimoToqueX).toDouble(), (y - ultimoToqueY).toDouble()) < unidad * 0.35f
                    ) return true
                    ultimaTeclaToque = t
                    ultimoToqueMs = e.eventTime
                    ultimoToqueX = x
                    ultimoToqueY = y
                }
                // Escritura con dos dedos: la letra del otro dedo se escribe ya (así no se
                // pierde el orden), pero su animación sigue hasta que ese dedo se levante
                for ((k, previa) in presionadas.toList()) {
                    if (k in escritas) continue
                    // El espacio que ya aceptó una sugerencia no escribe nada más
                    if (k == punteroEspacio && espacioAcepto) {
                        escritas.add(k)
                        punteroEspacio = -1
                        continue
                    }
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
                        if (espacioAcepto) continue
                        // Deslizar el espacio hacia arriba: se pone la sugerencia del centro
                        // (más rápido que estirar el pulgar hasta la barra)
                        if (aceptarDeslizando && !modoCursor && yCursor - y > altoFila * 0.7f &&
                            Math.abs(x - xCursor) < unidad * 1.6f) {
                            espacioAcepto = true
                            manejador.removeCallbacks(activarCursor)
                            acciones.retro(Tipo.ENTER)
                            acciones.aceptarSugerencia()
                            invalidate()
                            continue
                        }
                        if (modoCursor) {
                            val paso = dp(8f)
                            val n = ((x - xCursor) / paso).toInt()
                            if (n != 0) {
                                acciones.moverCursor(n)
                                xCursor += n * paso
                            }
                            // Arriba y abajo: una línea por cada fila de teclas que subes o bajas
                            val pasoY = altoFila * 0.85f
                            val m = ((y - yCursor) / pasoY).toInt()
                            if (m != 0) {
                                acciones.moverCursorVertical(m)
                                yCursor += m * pasoY
                            }
                        } else if (Math.abs(x - xCursor) > dp(18f) &&
                            SystemClock.uptimeMillis() - espacioDesdeMs > 140) {
                            // Escribiendo rápido con los pulgares el dedo a veces barre el espacio:
                            // solo se activa el cursor si el dedo se quedó un instante apoyado
                            manejador.removeCallbacks(activarCursor)
                            modoCursor = true
                            xCursor = x
                            yCursor = y
                            acciones.retro(Tipo.ESPACIO)
                            invalidate()
                        }
                        continue
                    }
                    if (id == punteroCambio) {
                        moverDesdeCambio(id, x, y)
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
                            presionadas[id]?.let { tk -> animPulsos.remove(tk)?.cancel(); pulsos.remove(tk) }
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
                        // Un temblor del dedo no cambia la tecla
                        val p0 = puntosToque[id]
                        if (p0 != null && Math.hypot((x - p0[0]).toDouble(), (y - p0[1]).toDouble()) < dp(9f)) continue
                        // Solo cambia de tecla si el dedo entró de verdad en la otra (no por rozar
                        // el borde al escribir rápido): así no salen letras vecinas por error
                        val m = dp(5f)
                        if (x < t.rect.left + m || x > t.rect.right - m ||
                            y < t.rect.top + dp(3f) || y > t.rect.bottom - dp(3f)) continue
                        presionadas[id] = t
                        // Sin vista previa: la tecla anterior se apaga y se marca la nueva
                        if (!vistaPrevia) { apagarSueltas(); animarPulso(t, 1f) }
                        puntosToque[id] = floatArrayOf(x, y)
                        anticiparLetra(t, x, y)
                        programarVariantes(t, id)
                        if (vistaPrevia) burbujas.mostrar(id, t.rect, etiqueta(t), tema, deslizando = true)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == punteroCambio) {
                    punteroCambio = -1
                    if (cambioDeslizado) {
                        cambioDeslizado = false
                        soltarDesdeCambio(id)
                        return true
                    }
                }
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
                        else if (espacioAcepto) espacioAcepto = false
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

    /** A qué teclado lleva cada tecla de cambio (lo mismo que al tocarla y soltarla) */
    private fun destinoDe(t: Tecla): Modo = when (t.tipo) {
        Tipo.A_NUMEROS -> Modo.NUMEROS
        Tipo.A_SIMBOLOS -> Modo.SIMBOLOS
        else -> Modo.LETRAS
    }

    /** Deslizando desde 123: al salir de la tecla cambia el teclado y se marca la tecla bajo el dedo */
    private fun moverDesdeCambio(id: Int, x: Float, y: Float) {
        if (!cambioDeslizado) {
            val actual = presionadas[id] ?: return
            if (Math.hypot((x - x0Cambio).toDouble(), (y - y0Cambio).toDouble()) < unidad * 0.6f) return
            // Salió de la tecla: el teclado cambia ya, sin soltar
            cambioDeslizado = true
            modoAntesCambio = modo
            animPulsos.remove(actual)?.cancel()
            pulsos.remove(actual)
            modo = destinoDe(actual)
            presionadas.remove(id)
        }
        val t = teclaEn(x, y)
        if (t === presionadas[id]) return
        if (t == null || t.tipo == Tipo.VACIO || t.tipo == Tipo.UNA_MANO) {
            presionadas.remove(id)
            burbujas.quitar(id)
        } else {
            presionadas[id] = t
            if (t.tipo == Tipo.LETRA && vistaPrevia) burbujas.mostrar(id, t.rect, etiqueta(t), tema, deslizando = true)
            else burbujas.quitar(id)
        }
        invalidate()
    }

    /** Soltaste después de deslizar desde 123: se escribe lo que quedó bajo el dedo */
    private fun soltarDesdeCambio(id: Int) {
        val t = presionadas.remove(id)
        escritas.remove(id)
        puntosToque.remove(id)
        burbujas.soltar(id)
        if (t == null) {
            invalidate()
            return
        }
        when (t.tipo) {
            Tipo.LETRA -> {
                acciones.retro(Tipo.LETRA)
                acciones.texto(etiqueta(t), -1f, -1f)
                if (shift == 1 && modo == Modo.LETRAS) shift = 0
                // Vuelve solo al teclado de antes
                if (!soloNumeros) modo = modoAntesCambio
            }
            Tipo.BORRAR -> { acciones.retro(Tipo.BORRAR); acciones.borrar() }
            Tipo.VACIO, Tipo.UNA_MANO, Tipo.SHIFT -> {}
            else -> { acciones.retro(t.tipo); soltar(t, id) }
        }
        invalidate()
    }

    private fun presionar(t: Tecla, id: Int, x: Float, y: Float) {
        if (t.tipo == Tipo.VACIO) return
        acciones.retro(t.tipo)
        // Las letras con vista previa no se marcan (la burbuja ya muestra el toque). Sin vista
        // previa, y en el teclado numérico, la tecla se marca y al soltarla se apaga suave.
        if (t.tipo != Tipo.UNA_MANO && (t.tipo != Tipo.LETRA || !vistaPrevia || esPad)) animarPulso(t, 1f)
        when (t.tipo) {
            Tipo.LETRA -> {
                puntosToque[id] = floatArrayOf(x, y)
                anticiparLetra(t, x, y)
                if (!esPad) {
                    if (vistaPrevia) burbujas.mostrar(id, t.rect, etiqueta(t), tema)
                    programarVariantes(t, id)
                    // Deslizar para escribir: solo con un dedo y en las letras
                    if (deslizarActivo && modo == Modo.LETRAS && presionadas.size == 1 && t.letra >= 0) {
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
                espacioAcepto = false
                espacioDesdeMs = SystemClock.uptimeMillis()
                xCursor = x
                yCursor = y
                manejador.postDelayed(activarCursor, 450)
            }
            Tipo.A_NUMEROS, Tipo.A_SIMBOLOS, Tipo.A_LETRAS -> {
                // Puede que deslices hasta un signo sin soltar (se ve en ACTION_MOVE)
                if (!esPad && presionadas.size == 1) {
                    punteroCambio = id
                    cambioDeslizado = false
                    x0Cambio = x
                    y0Cambio = y
                }
            }
            Tipo.EMOJI -> {
                punteroEmoji = id
                emojiLargo = false
                manejador.postDelayed(abrirAjustesTarea, 500)
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
                // Mayúsculas ↔ minúsculas con una transición corta, como en iPhone
                if (modo == Modo.LETRAS) animarEtiquetas()
            }
            else -> {}
        }
        // Al presionar una letra solo cambia esa tecla: se repinta sola (no todo el teclado)
        if (t.tipo == Tipo.LETRA) invalidarTecla(t) else invalidate()
    }

    /** Avisa al servicio qué letra se va a escribir (solo letras: a–z y ñ) */
    private fun anticiparLetra(t: Tecla, x: Float, y: Float) {
        if (t.letra < 0 || modo != Modo.LETRAS || esPad) return
        val k = enTeclas(x, y)
        acciones.anticipar(etiqueta(t), k[0], k[1])
    }

    private fun soltar(t: Tecla, id: Int) {
        when (t.tipo) {
            Tipo.LETRA -> {
                val p = puntosToque.remove(id)
                val k = if (p != null && modo == Modo.LETRAS) enTeclas(p[0], p[1]) else null
                // Aprender tu manera de tocar (solo con toques que cayeron en esa misma tecla)
                if (p != null && modo == Modo.LETRAS && t.letra >= 0) {
                    val r = t.rect
                    if (p[0] >= r.left - sepH && p[0] <= r.right + sepH && p[1] >= r.top - sepV && p[1] <= r.bottom + sepV) {
                        modelo.aprender(t.letra, (p[0] - r.centerX()) / unidad, (p[1] - r.centerY()) / altoFila)
                    }
                }
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
            Tipo.EMOJI -> {
                manejador.removeCallbacks(abrirAjustesTarea)
                val largo = emojiLargo
                emojiLargo = false
                punteroEmoji = -1
                // Si se mantuvo presionada ya se abrieron los ajustes: no se abren los emojis
                if (!largo) acciones.abrirEmojis()
            }
            Tipo.MIC -> {}
            Tipo.UNA_MANO -> {
                // Volver al teclado completo, como la flecha del iPhone
                unaMano = "no"
                Ajustes.prefs(context).edit().putString("una_mano", "no").apply()
                acomodar()
            }
            Tipo.SHIFT, Tipo.VACIO -> {}
        }
        // Al soltar una letra solo cambia esa tecla (y quizá la shift, si se apagó la mayúscula):
        // se repintan esas, no todo el teclado. Si cambió de modo, el cambio de 'modo' ya repintó todo.
        if (t.tipo == Tipo.LETRA) {
            invalidarTecla(t)
            if (modo == Modo.LETRAS) teclaDe(Tipo.SHIFT)?.let { invalidarTecla(it) }
        } else invalidate()
    }

    companion object {
        private val FRENO_PULSO = DecelerateInterpolator(1.5f)

        /** Lo que aparece al mantener presionada cada tecla */
        private val VARIANTES = mapOf(
            // Lo del español primero; al final el número (como en Gboard: mantén la tecla)
            "q" to listOf("1"),
            "w" to listOf("2"),
            "e" to listOf("é", "è", "ë", "ê", "3"),
            "r" to listOf("4"),
            "t" to listOf("5"),
            "y" to listOf("6"),
            "u" to listOf("ú", "ü", "ù", "û", "7"),
            "i" to listOf("í", "ï", "ì", "î", "8"),
            "o" to listOf("ó", "ö", "ò", "ô", "º", "9"),
            "p" to listOf("0"),
            "a" to listOf("á", "à", "ä", "â", "ª"),
            "n" to listOf("ñ"),
            "c" to listOf("ç"),
            "s" to listOf("ß"),
            "z" to listOf("ž"),
            "0" to listOf("°"),
            "-" to listOf("–", "—", "•"),
            "/" to listOf("\\"),
            // Soles primero (Perú) y las otras monedas al mantener presionado
            "S/" to listOf("$", "US$", "€", "£", "¥", "¢"),
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
