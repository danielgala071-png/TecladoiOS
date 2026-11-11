package com.tecladoios

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.LinearGradient
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
    /** Oración completa que predijo el cerebro ("vemos mañana"): se escribe entera */
    val frase: Boolean = false,
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
        /** Buscando emojis: tocaste la búsqueda (vuelves al panel de emojis) */
        fun salirBusqueda()
        /** Buscando emojis: elegiste uno */
        fun emojiBuscado(e: String)
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
    /** Dónde empezó el toque (para saber si deslizaste hacia abajo) */
    private var y0Toque = 0f
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
        // Filo fino (igual que el del teclado: son una sola pieza)
        strokeWidth = maxOf(1f, 0.7f * d)
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
    /**
     * Avisa apenas cambia si la barra se puede tocar o no (antes de la animación), para que
     * la app de atrás sepa hasta dónde llega el teclado.
     */
    var alCambiarEstado: (() -> Unit)? = null

    /**
     * true = las sugerencias están a la vista (y se pueden tocar).
     *
     * La barra NUNCA cambia de tamaño ni se quita del teclado (antes pasaba de GONE a VISIBLE
     * con la primera letra): eso hacía que toda la ventana del teclado cambiara de alto justo
     * mientras escribías, y la segunda tecla se "pegaba". Ahora siempre ocupa su lugar y solo
     * se dibuja (o no) encima; mientras está oculta es transparente y los toques pasan a la app.
     */
    val aLaVista: Boolean get() = mostrando

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

    // ---------- Buscar emojis ----------
    /** Lo que estás buscando (null = la barra muestra sugerencias normales) */
    private var busqueda: String? = null
    private var resultados: List<String> = emptyList()
    private val pinturaEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pinturaCursor = Paint(Paint.ANTI_ALIAS_FLAG)

    fun mostrarBusqueda(consulta: String?, lista: List<String>) {
        if (consulta == busqueda && lista == resultados) return
        busqueda = consulta
        resultados = lista
        presionado = -1
        invalidate()
    }

    private val anchoBusqueda get() = width * 0.36f

    /** Qué hay bajo el dedo en la búsqueda: [ZONA_BUSQUEDA], un emoji (0..5) o -1 */
    private fun zonaBusqueda(x: Float): Int {
        if (x < anchoBusqueda) return ZONA_BUSQUEDA
        val celda = (width - anchoBusqueda) / CELDAS_EMOJI
        val i = ((x - anchoBusqueda) / celda).toInt().coerceIn(0, CELDAS_EMOJI - 1)
        return if (i < resultados.size) i else -1
    }

    private fun dibujarBusqueda(c: Canvas, consulta: String) {
        val h = height.toFloat()
        val arriba = 6 * d
        val abajo = h - 10 * d
        val rc = (abajo - arriba) / 2f
        // Cápsula de vidrio con lo que escribes y un cursor del color de la app
        pintura.color = if (presionado == ZONA_BUSQUEDA) tema.teclaPresionada else tema.tecla
        c.drawRoundRect(6 * d, arriba, anchoBusqueda - 4 * d, abajo, rc, rc, pintura)
        val previo = pinturaBorde.color
        pinturaBorde.color = (tema.borde and 0x00FFFFFF) or 0x8C000000.toInt()
        c.drawRoundRect(6 * d, arriba, anchoBusqueda - 4 * d, abajo, rc, rc, pinturaBorde)
        pinturaBorde.color = previo
        grosor(400)
        pinturaTexto.textSize = 15f * d
        val alin = pinturaTexto.textAlign
        pinturaTexto.textAlign = Paint.Align.LEFT
        val izq = 16 * d
        val disponible = anchoBusqueda - izq - 14 * d
        val cy = (arriba + abajo) / 2f - (pinturaTexto.fontMetrics.ascent + pinturaTexto.fontMetrics.descent) / 2f
        val vacia = consulta.isEmpty()
        pinturaTexto.color = if (vacia) tema.textoSecundario else tema.texto
        val mostrar = if (vacia) "Buscar emoji" else
            TextUtils.ellipsize(consulta, pinturaTexto, disponible, TextUtils.TruncateAt.START).toString()
        c.drawText(mostrar, izq, cy, pinturaTexto)
        if (!vacia) {
            pinturaCursor.color = tema.azul
            val xc = izq + pinturaTexto.measureText(mostrar) + 2 * d
            c.drawRect(xc, arriba + 9 * d, xc + 2 * d, abajo - 9 * d, pinturaCursor)
        }
        pinturaTexto.textAlign = alin
        pinturaTexto.textSize = 17f * d
        // Los emojis que coinciden
        val celda = (width - anchoBusqueda) / CELDAS_EMOJI
        if (resultados.isEmpty() && !vacia) {
            pinturaTexto.color = tema.textoSecundario
            c.drawText("Sin resultados", anchoBusqueda + (width - anchoBusqueda) / 2f, centroY(), pinturaTexto)
            return
        }
        pinturaEmoji.textSize = 24f * d
        val fm = pinturaEmoji.fontMetrics
        for ((i, e) in resultados.take(CELDAS_EMOJI).withIndex()) {
            val cx = anchoBusqueda + celda * i + celda / 2f
            if (i == presionado) {
                pintura.color = tema.teclaPresionada
                c.drawRoundRect(cx - celda / 2f + 2 * d, arriba, cx + celda / 2f - 2 * d, abajo, rc, rc, pintura)
            }
            c.drawText(e, cx, (arriba + abajo) / 2f - (fm.ascent + fm.descent) / 2f, pinturaEmoji)
        }
    }

    // Fundido de las casillas que cambian
    private var cambio = 1f
    private val cambiadas = BooleanArray(3)
    private var animCambio: ValueAnimator? = null

    private fun animarCambio(antes: List<Sugerencia?>, ahora: List<Sugerencia?>) {
        if (!mostrando || opacidad < 1f || !isShown) return
        var alguna = false
        for (i in 0 until 3) {
            // La casilla de la izquierda (lo que escribes) cambia con cada letra: esa no se funde
            val cambia = i > 0 && ahora[i] != null && ahora[i]?.texto != antes[i]?.texto
            cambiadas[i] = cambia
            if (cambia) alguna = true
        }
        if (!alguna) return
        animCambio?.cancel()
        animCambio = ValueAnimator.ofFloat(0.25f, 1f).apply {
            duration = 190
            interpolator = FRENO_BARRA
            addUpdateListener {
                cambio = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * La sugerencia que se pone al deslizar la barra espaciadora hacia arriba: la del centro
     * (la corrección o la más probable). Si solo hay lo copiado, eso. null si no hay nada útil.
     */
    fun sugerenciaCentral(): Sugerencia? {
        if (!mostrando || busqueda != null || confirmar != null) return null
        val primera = items[0]
        if (primera != null && primera.portapapeles) return primera
        val centro = items[1]
        if (centro != null && centro.accion == 0) return centro
        return items[2]?.takeIf { it.accion == 0 && !it.emoji && !it.frase }
    }

    fun mostrar(nuevos: List<Sugerencia?>) {
        val lista = List(3) { nuevos.getOrNull(it) }
        // Si no cambió nada, no se vuelve a dibujar (menos trabajo en cada letra)
        if (lista == items) return
        animarCambio(items, lista)
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
        val cambia = si != mostrando
        mostrando = si
        animador?.cancel()
        animador = null
        presionado = -1
        if (si) {
            // Al volver a aparecer no se ven las sugerencias de la palabra anterior
            items = listOf(null, null, null)
            if (cambia) alCambiar?.invoke(true)
        }
        if (cambia) alCambiarEstado?.invoke()
        if (!animar || !isShown || width == 0) {
            opacidad = destino
            if (!si) alCambiar?.invoke(false)
            invalidate()
            return
        }
        // Aparece subiendo apenas desde el teclado y se apaga suave (como en iOS)
        animador = ValueAnimator.ofFloat(opacidad, destino).apply {
            duration = if (si) Movimiento.BARRA_APARECE else Movimiento.BARRA_SE_VA
            interpolator = FRENO_BARRA
            addUpdateListener {
                opacidad = it.animatedValue as Float
                invalidate()
            }
            if (!si) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!mostrando) alCambiar?.invoke(false)
                }
            })
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
        // Oculta: no se dibuja nada (es transparente y se ve la app de atrás)
        if (opacidad <= 0f && confirmar == null) return
        val o = if (confirmar != null) 1f else opacidad.coerceIn(0f, 1f)
        // Toda la barra (fondo, filo y sugerencias) se enciende y apaga junta, subiendo apenas
        val capa = if (o < 1f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (o * 255).toInt()) else -1
        if (o < 1f) c.translate(0f, (1f - o) * 6f * d)
        // Fondo negro como el teclado (curvo arriba) y el filo del color de la app
        pinturaFondo.color = tema.fondo
        c.drawPath(forma, pinturaFondo)
        // Halo suave del color de la app por dentro del filo (igual que en el teclado)
        val grosorFilo = pinturaBorde.strokeWidth
        pinturaBorde.color = tema.resplandor
        pinturaBorde.strokeWidth = grosorFilo * 8f
        c.drawPath(filo, pinturaBorde)
        pinturaBorde.strokeWidth = grosorFilo * 4f
        c.drawPath(filo, pinturaBorde)
        pinturaBorde.strokeWidth = grosorFilo
        pinturaBorde.color = tema.bordePanel
        c.drawPath(filo, pinturaBorde)

        val conf = confirmar
        if (conf != null) dibujarConfirmar(c, conf) else dibujarSugerencias(c)
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
        busqueda?.let { dibujarBusqueda(c, it); return }
        items[0]?.let { if (it.portapapeles) { dibujarCopiado(c, it); return } }
        val ancho = width / 3f
        for (i in 0 until 3) {
            val s = items[i] ?: continue
            val x0 = ancho * i
            // Las casillas que acaban de cambiar aparecen con un fundido cortito (como en iPhone)
            val animando = cambio < 1f && cambiadas[i]
            val capa = if (animando)
                c.saveLayerAlpha(x0, 0f, x0 + ancho, height.toFloat(), (cambio * 255).toInt()) else -1
            if (animando) {
                // La palabra nueva sube apenas mientras aparece (como en iPhone); la corrección
                // resaltada además brota desde un poquito más chica
                c.translate(0f, (1f - cambio) * 5f * d)
                if (s.destacada) {
                    val k = 0.9f + 0.1f * cambio
                    c.scale(k, k, x0 + ancho / 2f, height / 2f)
                }
            }
            dibujarCasilla(c, i, s, x0, ancho)
            if (capa >= 0) c.restoreToCount(capa)
        }
        // Separadores finos entre las casillas, como en iPhone: se desvanecen en las puntas
        // (no son una raya dura) y no se dibujan junto a una cápsula (la corrección resaltada
        // o la que estás tocando), así la cápsula se ve limpia. Con la barra vacía quedan
        // apenas marcados: la barra nunca se ve como un hueco negro.
        val vacia = items.all { it == null }
        fun conCapsula(k: Int) = k == presionado || (k == soltado && brilloSoltado > 0f) || items[k]?.destacada == true
        val arriba = height * 0.3f
        val abajo = height * 0.62f
        if (separador == null || separadorAlto != height) {
            val col = tema.textoSecundario and 0x00FFFFFF
            separador = LinearGradient(0f, arriba, 0f, abajo,
                intArrayOf(col, col or 0x55000000, col), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            separadorAlto = height
        }
        pintura.shader = separador
        pintura.strokeWidth = maxOf(1f, d * 0.5f)
        pintura.alpha = if (vacia) 110 else 255
        for (i in 1 until 3) {
            val ambos = items[i - 1] != null && items[i] != null
            if ((ambos || vacia) && !conCapsula(i - 1) && !conCapsula(i)) {
                c.drawLine(ancho * i, arriba, ancho * i, abajo, pintura)
            }
        }
        pintura.shader = null
        pintura.alpha = 255
    }

    /** Degradado de los separadores (se arma una vez por alto de la barra) */
    private var separador: LinearGradient? = null
    private var separadorAlto = -1

    // La cápsula de la sugerencia que soltaste se apaga suave
    private var soltado = -1
    private var brilloSoltado = 0f
    private var animSoltado: ValueAnimator? = null

    private fun apagarPresion(i: Int) {
        animSoltado?.cancel()
        if (i < 0 || !isShown) { soltado = -1; brilloSoltado = 0f; return }
        soltado = i
        animSoltado = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = Movimiento.TOQUE_SALIDA
            interpolator = FRENO_BARRA
            addUpdateListener {
                brilloSoltado = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Dibuja una casilla de la barra (sugerencia, corrección, emoji o predicción en línea) */
    private fun dibujarCasilla(c: Canvas, i: Int, s: Sugerencia, x0: Float, ancho: Float) {
        if (i == presionado || (i == soltado && brilloSoltado > 0f)) {
            // Al tocarla: cápsula de vidrio (misma forma que la destacada). Al soltar no
            // desaparece de golpe: se apaga suave, como en iPhone
            val rp = (height - 16 * d) / 2f
            val f = if (i == presionado) 1f else brilloSoltado
            pintura.color = (tema.teclaPresionada and 0x00FFFFFF) or
                ((Color.alpha(tema.teclaPresionada) * f).toInt().coerceIn(0, 255) shl 24)
            c.drawRoundRect(x0 + 6 * d, 6 * d, x0 + ancho - 6 * d, height - 10 * d, rp, rp, pintura)
        }
        if (i != presionado && s.destacada) {
            // La corrección que se pondrá sola va en una cápsula de vidrio con el filo de la app
            val arriba = 6 * d
            val abajo = height - 10 * d
            val rc = (abajo - arriba) / 2f
            // Cápsula clara como en iOS 26: gris luminoso con un toque de la app. Así se ve de
            // un vistazo cuál palabra se pondrá sola al tocar espacio
            pintura.color = (Tema.mezclar(0xFFFFFFFF.toInt(), tema.azul, 0.18f) and 0x00FFFFFF) or 0x33000000
            c.drawRoundRect(x0 + 6 * d, arriba, x0 + ancho - 6 * d, abajo, rc, rc, pintura)
            val previo = pinturaBorde.color
            pinturaBorde.color = (tema.borde and 0x00FFFFFF) or 0x99000000.toInt()
            c.drawRoundRect(x0 + 6 * d, arriba, x0 + ancho - 6 * d, abajo, rc, rc, pinturaBorde)
            pinturaBorde.color = previo
        }
        // Emoji sugerido: se dibuja grande y nítido (antes salía del tamaño de las letras)
        if (s.emoji) {
            pinturaEmoji.textSize = 24f * d
            val fe = pinturaEmoji.fontMetrics
            val cyE = (6 * d + height - 10 * d) / 2f - (fe.ascent + fe.descent) / 2f
            c.drawText(s.texto, x0 + ancho / 2f, cyE, pinturaEmoji)
            return
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
                return
            }
        }
        pinturaTexto.color = tema.texto
        val crudo = if (s.literal) "\u201C${s.texto}\u201D" else s.texto
        // Palabras largas: primero se achica un poco la letra (hasta 13) y solo si aun así
        // no cabe se corta con "…" (como en iPhone, casi nunca se ve cortada)
        val disponible = ancho - 16 * d
        val medida = pinturaTexto.measureText(crudo)
        if (medida > disponible) {
            pinturaTexto.textSize = maxOf(13f * d, 17f * d * disponible / medida)
        }
        val txt = TextUtils.ellipsize(crudo, pinturaTexto, disponible, TextUtils.TruncateAt.END).toString()
        c.drawText(txt, x0 + ancho / 2f, cy, pinturaTexto)
        pinturaTexto.textSize = 17f * d
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
        if (busqueda != null) {
            val zona = zonaBusqueda(e.x)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { presionado = zona; invalidate() }
                MotionEvent.ACTION_MOVE -> if (zona != presionado && presionado != -1) { presionado = -1; invalidate() }
                MotionEvent.ACTION_UP -> {
                    val z = presionado
                    presionado = -1
                    invalidate()
                    if (z == zona && z != -1) {
                        if (z == ZONA_BUSQUEDA) acciones.salirBusqueda()
                        else resultados.getOrNull(z)?.let { acciones.emojiBuscado(it) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { presionado = -1; invalidate() }
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
                y0Toque = e.y
                presionado = if (items[i] != null) i else -1
                if (presionado != -1) manejador.postDelayed(alMantener, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignorarGesto) return true
                // Deslizar una sugerencia hacia abajo: se descarta (y no se vuelve a sugerir)
                val bajo = presionado
                val sb = if (bajo >= 0) items[bajo] else null
                if (sb != null && !mantenido && e.y - y0Toque > height * 0.55f &&
                    (sb.portapapeles || sePuedeOlvidar(sb))) {
                    manejador.removeCallbacks(alMantener)
                    mantenido = true
                    presionado = -1
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    items = items.map { if (it === sb) null else it }
                    acciones.olvidar(sb)
                    invalidate()
                    return true
                }
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
                if (s != null) apagarPresion(presionado)
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
        val FRENO_BARRA = Movimiento.SUAVE
        /** Debajo de este tiempo tras tocar una letra, un toque en la barra se ignora */
        const val GUARDA_MS = 130L
        /** Buscando emojis: la cápsula de búsqueda y cuántos emojis caben al lado */
        const val ZONA_BUSQUEDA = 100
        const val CELDAS_EMOJI = 6
    }
}
