package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * El teclado, como el nativo del iPhone, en negro AMOLED: letras en grafito y teclas de
 * función en negro con contorno. Al tocar una letra sale su globo hacia arriba; al
 * mantenerla salen sus tildes. Nada más: sin predicciones ni ajustes al toque.
 *
 * Arriba del teclado hay una franja transparente (no recibe toques) donde se dibujan los
 * globos de la primera fila.
 */
class KeyboardView(context: Context) : View(context) {

    interface Oyente {
        fun texto(s: String)
        fun borrar(palabra: Boolean)
        fun espacio()
        fun intro()
        fun emojis()
        fun cursor(dx: Int, dy: Int)
        fun respuesta(t: Respuesta.Tipo)
        fun sugerencia(i: Int)
    }

    /** Una casilla de la barra: [tipo] 0 = normal, 1 = lo escrito (entre comillas), 2 = corrección */
    class Sug(val texto: String, val tipo: Int)

    var oyente: Oyente? = null
    var config: Config? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ---------- Colores (negro AMOLED) ----------
    private val FONDO = Color.BLACK
    private val LETRA = 0xFF2C2C2E.toInt()          // teclas de letras: grafito
    private val LETRA_PRESIONADA = 0xFF48484A.toInt()
    private val FUNCION = Color.BLACK               // mayúscula, borrar, 123, emoji, espacio, intro
    private val FUNCION_PRESIONADA = 0xFF2C2C2E.toInt()
    private val CONTORNO = 0xFF3A3A3C.toInt()
    private val TEXTO = Color.WHITE
    private val ELEGIDA = 0xFF636366.toInt()        // opción elegida al mantener una tecla

    // ---------- Distribución y estado ----------
    var distribucion: Distribucion = Distribuciones.crear(Distribuciones.LETRAS); private set
    /** 0 = minúsculas, 1 = mayúscula para una letra, 2 = bloqueo de mayúsculas */
    var shift = 0; private set
    private var shiftAuto = false
    private var ultimoShiftMs = 0L
    /** Lo que dice la tecla intro (null = flecha) */
    var etiquetaIntro: String? = null; set(v) { field = v; invalidate() }

    fun ponerModo(modo: Int, decimal: Boolean = false) {
        terminarTodo()
        distribucion = Distribuciones.crear(modo, decimal)
        if (modo != Distribuciones.LETRAS) { shift = 0; shiftAuto = false }
        requestLayout()
        acomodar()
        invalidate()
    }

    /** El editor pide (o no) mayúscula al inicio de oración */
    fun mayusculaAutomatica(si: Boolean) {
        if (distribucion.modo != Distribuciones.LETRAS || shift == 2) return
        if (si && shift == 0) { shift = 1; shiftAuto = true; invalidate() }
        else if (!si && shift == 1 && shiftAuto) { shift = 0; shiftAuto = false; invalidate() }
    }

    // ---------- Medidas ----------
    /** Barra de sugerencias arriba de las teclas (solo al escribir texto) */
    var conBarra = false; set(v) { if (field != v) { field = v; requestLayout(); acomodar(); invalidate() } }
    private val BARRA get() = if (conBarra) dp(44f) else 0f
    private val sugerencias = arrayOfNulls<Sug>(3)
    fun ponerSugerencias(a: Sug?, b: Sug?, c: Sug?) {
        if (sugerencias[0] === a && sugerencias[1] === b && sugerencias[2] === c) return
        sugerencias[0] = a; sugerencias[1] = b; sugerencias[2] = c
        invalidate()
    }
    private var casillaApretada = -1

    /** Franja transparente de arriba para los globos (con la barra, el globo pasa por encima de ella) */
    val zona get() = if (conBarra) dp(14f) else dp(58f)
    private val ARRIBA get() = dp(6f)
    private val ABAJO get() = dp(4f)
    private val ALTO_FILA get() = dp(57f) * (config?.tamano ?: 100) / 100f
    private val SEP_X get() = dp(6f)
    private val SEP_Y get() = dp(11f)
    private val LADO get() = dp(3f)
    private val RADIO get() = dp(6f)
    /** Espacio de la barra de navegación del sistema (se pinta negro) */
    var abajoSistema = 0; set(v) { if (field != v) { field = v; requestLayout() } }

    private class Colocada(val tecla: Tecla, val dibujo: RectF, val toque: RectF)
    private var colocadas: Array<Colocada> = emptyArray()
    private var anchoUnidad = 1f

    /** Alto del teclado sin la franja de arriba (lo que se ve) */
    /** La fila de abajo (123, emoji, espacio, intro) es más alta que las de letras */
    private val EXTRA_ABAJO get() = if (distribucion.filas.last().teclas.any { it.tipo == K.ESPACIO }) dp(9f) else 0f

    fun altoVisible(): Int = (BARRA + ARRIBA + ALTO_FILA * max(4, distribucion.filas.size) + EXTRA_ABAJO + ABAJO + abajoSistema).toInt()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // El alto no cambia entre letras, símbolos y números: el de 4 filas de letras
        val filas = max(4, distribucion.filas.size)
        val h = zona + BARRA + ARRIBA + ALTO_FILA * filas + EXTRA_ABAJO + ABAJO + abajoSistema
        setMeasuredDimension(MeasureSpec.getSize(wSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { acomodar() }

    private fun acomodar() {
        val w = width.toFloat()
        if (w <= 0f) return
        val filas = distribucion.filas
        val extra = EXTRA_ABAJO
        val altoFila = (height - zona - BARRA - ARRIBA - ABAJO - abajoSistema - extra) / filas.size
        val u = (w - 2 * LADO) / distribucion.unidades
        anchoUnidad = u
        val lista = ArrayList<Colocada>(40)
        for ((fi, fila) in filas.withIndex()) {
            var x = LADO + (distribucion.unidades - fila.unidades) / 2 * u
            val y0 = zona + BARRA + ARRIBA + fi * altoFila
            for (t in fila.teclas) {
                val ancho = t.ancho * u
                if (t.tipo != K.VACIA) {
                    // Mayúscula, borrar, 123, emoji, espacio e intro: menos separación a los
                    // lados (se ven más anchas); la fila de abajo, además, más alta
                    val alto = if (fi == filas.size - 1) altoFila + extra else altoFila
                    val sx = if (t.funcion) SEP_X * 0.25f else SEP_X / 2
                    val dib = RectF(x + sx, y0 + SEP_Y / 2, x + ancho - sx, y0 + alto - SEP_Y / 2)
                    val toque = RectF(x, y0, x + ancho, y0 + alto)
                    if (fi == 0) toque.top = zona + BARRA
                    if (fi == filas.size - 1) toque.bottom = height.toFloat()
                    lista.add(Colocada(t, dib, toque))
                }
                x += ancho
            }
        }
        // Las teclas de los extremos reciben el toque hasta el borde
        for (c in lista) {
            if (c.toque.left - LADO < dp(4f)) c.toque.left = 0f
            if (w - LADO - c.toque.right < dp(4f)) c.toque.right = w
        }
        colocadas = lista.toTypedArray()
    }

    private fun buscar(x: Float, y: Float): Colocada? {
        if (y < zona + BARRA) return null
        for (c in colocadas) if (c.toque.contains(x, y)) return c
        // En un hueco (al lado de mayúscula o borrar): la tecla más cercana de esa fila
        var mejor: Colocada? = null; var md = Float.MAX_VALUE
        for (c in colocadas) {
            if (y < c.toque.top || y > c.toque.bottom) continue
            val dx = max(max(c.toque.left - x, 0f), x - c.toque.right)
            if (dx < md) { md = dx; mejor = c }
        }
        return mejor
    }

    // ---------- Dedos ----------
    private object Modo { const val NORMAL = 0; const val OPCIONES = 1; const val CURSOR = 2; const val HECHO = 3; const val BARRA = 4 }

    private inner class Dedo(val id: Int) {
        var c: Colocada? = null
        var modo = Modo.NORMAL
        var x0 = 0f; var y0 = 0f
        var acumX = 0f; var acumY = 0f
        var repeticiones = 0
        val larga = Runnable { mantener(this) }
        val repetir = object : Runnable {
            override fun run() {
                if (c?.tecla?.tipo != K.BORRAR || modo != Modo.NORMAL) return
                repeticiones++
                oyente?.respuesta(Respuesta.Tipo.BORRAR)
                // Como el iPhone: después de un rato borra palabras enteras
                oyente?.borrar(repeticiones > 18)
                postDelayed(this, if (repeticiones > 18) 200L else 90L)
            }
        }
    }

    private val dedos = HashMap<Int, Dedo>()
    private var presionada: Colocada? = null      // la que muestra su globo
    private val presionadas = HashSet<Colocada>()

    // Opciones al mantener una tecla (tildes, símbolos)
    private var opciones: List<String> = emptyList()
    private val opcionesRect = RectF()
    private var opcionElegida = -1
    private var dedoOpciones: Dedo? = null
    private var mayusOpciones = false

    private var trackpad = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> bajar(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) {
                dedos[e.getPointerId(k)]?.let { mover(it, e.getX(k), e.getY(k)) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> dedos.remove(e.getPointerId(i))?.let { subir(it) }
            MotionEvent.ACTION_CANCEL -> terminarTodo()
        }
        return true
    }

    private fun bajar(id: Int, x: Float, y: Float) {
        // Como en el iPhone, al apoyar otro dedo la letra del dedo anterior se escribe ya
        for (otro in dedos.values) {
            if (otro.modo == Modo.NORMAL && otro.c?.tecla?.tipo == K.TEXTO) {
                escribir(otro)
                otro.modo = Modo.HECHO
            }
        }
        // Barra de sugerencias
        if (conBarra && y >= zona && y < zona + BARRA) {
            val k = (x / (width / 3f)).toInt().coerceIn(0, 2)
            if (sugerencias[k] == null) return
            dedos[id] = Dedo(id).apply { modo = Modo.BARRA; x0 = x; y0 = y; repeticiones = k }
            casillaApretada = k
            oyente?.respuesta(Respuesta.Tipo.LETRA)
            invalidate()
            return
        }
        val c = buscar(x, y) ?: return
        val dd = Dedo(id).apply { this.c = c; x0 = x; y0 = y }
        dedos[id] = dd
        val t = c.tecla
        oyente?.respuesta(when (t.tipo) {
            K.TEXTO -> Respuesta.Tipo.LETRA
            K.ESPACIO -> Respuesta.Tipo.ESPACIO
            K.BORRAR -> Respuesta.Tipo.BORRAR
            K.INTRO -> Respuesta.Tipo.INTRO
            else -> Respuesta.Tipo.FUNCION
        })
        presionadas.add(c)
        when (t.tipo) {
            K.TEXTO -> {
                presionada = c
                if (t.variantes.isNotEmpty()) postDelayed(dd.larga, 380L)
            }
            K.BORRAR -> {
                oyente?.borrar(false)
                postDelayed(dd.repetir, 450L)
            }
            K.SHIFT -> tocarShift()
            K.ESPACIO -> postDelayed(dd.larga, 450L)
        }
        invalidate()
    }

    private fun mover(dd: Dedo, x: Float, y: Float) {
        when (dd.modo) {
            Modo.BARRA -> {
                val k = (x / (width / 3f)).toInt()
                val dentro = k == dd.repeticiones && y >= zona - dp(30f) && y < zona + BARRA + dp(30f)
                val nueva = if (dentro) dd.repeticiones else -1
                if (nueva != casillaApretada) { casillaApretada = nueva; invalidate() }
            }
            Modo.OPCIONES -> {
                if (opciones.isEmpty()) return
                val anchoOp = opcionesRect.width() / opciones.size
                val k = ((x - opcionesRect.left) / anchoOp).toInt().coerceIn(0, opciones.size - 1)
                val dentro = y > opcionesRect.top - dp(60f) && y < opcionesRect.bottom + dp(120f)
                val nueva = if (dentro) k else -1
                if (nueva != opcionElegida) { opcionElegida = nueva; invalidate() }
            }
            Modo.CURSOR -> {
                dd.acumX += x - dd.x0; dd.acumY += y - dd.y0
                dd.x0 = x; dd.y0 = y
                val pasoX = dp(9f); val pasoY = dp(28f)
                val nx = (dd.acumX / pasoX).toInt(); val ny = (dd.acumY / pasoY).toInt()
                if (nx != 0 || ny != 0) {
                    dd.acumX -= nx * pasoX; dd.acumY -= ny * pasoY
                    oyente?.cursor(nx, ny)
                }
            }
            Modo.NORMAL -> {
                val c = dd.c ?: return
                if (c.tecla.tipo == K.ESPACIO) {
                    // Mover el dedo sobre el espacio también mueve el cursor (como el iPhone)
                    if (abs(x - dd.x0) > dp(14f)) { removeCallbacks(dd.larga); empezarCursor(dd, x, y) }
                    return
                }
                // El globo sigue al dedo si se desliza a otra letra
                if (c.tecla.tipo == K.TEXTO && !c.toque.contains(x, y)) {
                    val n = buscar(x, y) ?: return
                    if (n.tecla.tipo != K.TEXTO || n === c) return
                    removeCallbacks(dd.larga)
                    presionadas.remove(c); presionadas.add(n)
                    dd.c = n; presionada = n
                    if (n.tecla.variantes.isNotEmpty()) postDelayed(dd.larga, 380L)
                    invalidate()
                }
            }
        }
    }

    private fun subir(dd: Dedo) {
        removeCallbacks(dd.larga); removeCallbacks(dd.repetir)
        val c = dd.c
        when (dd.modo) {
            Modo.NORMAL -> if (c != null) when (c.tecla.tipo) {
                K.TEXTO -> escribir(dd)
                K.ESPACIO -> {
                    oyente?.espacio()
                    if (distribucion.modo == Distribuciones.SIMBOLOS || distribucion.modo == Distribuciones.SIMBOLOS2)
                        ponerModo(Distribuciones.LETRAS)
                }
                K.INTRO -> oyente?.intro()
                K.NUM -> ponerModo(Distribuciones.SIMBOLOS)
                K.ABC -> ponerModo(Distribuciones.LETRAS)
                K.SIMB2 -> ponerModo(Distribuciones.SIMBOLOS2)
                K.EMOJI -> oyente?.emojis()
            }
            Modo.OPCIONES -> {
                val k = opcionElegida
                if (k in opciones.indices) {
                    oyente?.texto(opciones[k])
                    despuesDeLetra()
                }
                opciones = emptyList(); opcionElegida = -1; dedoOpciones = null
            }
            Modo.CURSOR -> trackpad = false
            Modo.BARRA -> {
                if (casillaApretada == dd.repeticiones) oyente?.sugerencia(dd.repeticiones)
                casillaApretada = -1
            }
        }
        if (c != null) presionadas.remove(c)
        if (presionada === c) presionada = null
        invalidate()
    }

    private fun escribir(dd: Dedo) {
        val c = dd.c ?: return
        removeCallbacks(dd.larga)
        val t = c.tecla
        val s = if (shift > 0 && t.esLetra) t.texto.uppercase() else t.texto
        oyente?.texto(s)
        presionadas.remove(c)
        if (presionada === c) presionada = null
        if (t.esLetra) despuesDeLetra()
        // Como en el iPhone: después del apóstrofo vuelve a las letras
        if (t.texto == "'" && distribucion.modo != Distribuciones.LETRAS) ponerModo(Distribuciones.LETRAS)
        invalidate()
    }

    private fun despuesDeLetra() {
        if (shift == 1) { shift = 0; shiftAuto = false; invalidate() }
    }

    private fun tocarShift() {
        val ahora = SystemClock.uptimeMillis()
        val doble = ahora - ultimoShiftMs < 300L
        ultimoShiftMs = ahora
        shift = when {
            shift == 2 -> 0
            doble && config?.bloqMayus != false -> 2
            shift == 1 -> 0
            else -> 1
        }
        shiftAuto = false
        invalidate()
    }

    private fun mantener(dd: Dedo) {
        val c = dd.c ?: return
        if (dd.modo != Modo.NORMAL) return
        when (c.tecla.tipo) {
            K.TEXTO -> {
                val mayus = shift > 0
                opciones = if (mayus) c.tecla.variantes.map { it.uppercase() } else c.tecla.variantes
                mayusOpciones = mayus
                if (opciones.isEmpty()) return
                dd.modo = Modo.OPCIONES
                dedoOpciones = dd
                presionada = null
                val anchoOp = c.dibujo.width()
                val total = anchoOp * opciones.size
                var izq = c.dibujo.centerX() - anchoOp / 2
                if (izq + total > width - dp(3f)) izq = c.dibujo.centerX() + anchoOp / 2 - total
                izq = izq.coerceIn(dp(3f), max(dp(3f), width - dp(3f) - total))
                val alto = c.dibujo.height()
                val arriba = c.dibujo.top - alto - dp(10f)
                opcionesRect.set(izq, arriba, izq + total, arriba + alto)
                opcionElegida = ((c.dibujo.centerX() - izq) / anchoOp).toInt().coerceIn(0, opciones.size - 1)
                oyente?.respuesta(Respuesta.Tipo.FUNCION)
                invalidate()
            }
            K.ESPACIO -> empezarCursor(dd, dd.x0, dd.y0)
        }
    }

    private fun empezarCursor(dd: Dedo, x: Float, y: Float) {
        dd.modo = Modo.CURSOR
        dd.x0 = x; dd.y0 = y; dd.acumX = 0f; dd.acumY = 0f
        trackpad = true
        oyente?.respuesta(Respuesta.Tipo.FUNCION)
        invalidate()
    }

    fun terminarTodo() {
        for (dd in dedos.values) { removeCallbacks(dd.larga); removeCallbacks(dd.repetir) }
        dedos.clear(); presionadas.clear(); presionada = null
        opciones = emptyList(); opcionElegida = -1; dedoOpciones = null
        trackpad = false
        casillaApretada = -1
        invalidate()
    }

    // ---------- Dibujo ----------
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contorno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = CONTORNO }
    private val letras = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val etiquetas = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; style = Paint.Style.STROKE; strokeWidth = dp(1.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val lleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; style = Paint.Style.FILL }
    private val globoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LETRA }
    // Borde fino en vez de sombra difuminada (la sombra se recalcula en cada cuadro y gasta)
    private val bordeGlobo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFF48484A.toInt() }
    private val camino = Path()
    private val ovalo = RectF()

    override fun onDraw(cv: Canvas) {
        relleno.color = FONDO
        cv.drawRect(0f, zona, width.toFloat(), height.toFloat(), relleno)
        letras.textSize = dp(25f)
        etiquetas.textSize = dp(16f)
        if (conBarra) dibujarBarra(cv)
        for (c in colocadas) dibujarTecla(cv, c)
        if (!trackpad) presionada?.let { if (config?.vistaPrevia != false && !distribucion.numerico) dibujarGlobo(cv, it) }
        if (opciones.isNotEmpty()) dibujarOpciones(cv)
    }

    private fun dibujarTecla(cv: Canvas, c: Colocada) {
        val t = c.tecla
        val r = c.dibujo
        val apretada = c in presionadas
        val conGlobo = apretada && t.tipo == K.TEXTO && config?.vistaPrevia != false && !distribucion.numerico
        if (t.funcion) {
            relleno.color = if (apretada) FUNCION_PRESIONADA else FUNCION
            cv.drawRoundRect(r, RADIO, RADIO, relleno)
            cv.drawRoundRect(r, RADIO, RADIO, contorno)
        } else {
            relleno.color = if (apretada && !conGlobo) LETRA_PRESIONADA else LETRA
            cv.drawRoundRect(r, RADIO, RADIO, relleno)
        }
        if (trackpad && t.tipo != K.ESPACIO) return   // con el cursor, las teclas quedan en blanco
        val cx = r.centerX(); val cy = r.centerY()
        when (t.tipo) {
            K.TEXTO -> {
                val s = if (shift > 0 && t.esLetra) t.texto.uppercase() else t.texto
                letras.textSize = if (distribucion.numerico) dp(27f) else if (s.length > 1) dp(20f) else dp(25f)
                cv.drawText(s, cx, cy - (letras.ascent() + letras.descent()) / 2, letras)
                letras.textSize = dp(25f)
            }
            K.NUM, K.ABC, K.SIMB2 -> cv.drawText(t.texto, cx, cy - (etiquetas.ascent() + etiquetas.descent()) / 2, etiquetas)
            K.ESPACIO -> if (!trackpad) cv.drawText(t.texto, cx, cy - (etiquetas.ascent() + etiquetas.descent()) / 2, etiquetas)
            K.INTRO -> {
                val e = etiquetaIntro
                if (e != null) cv.drawText(e, cx, cy - (etiquetas.ascent() + etiquetas.descent()) / 2, etiquetas)
                else dibujarIntro(cv, cx, cy)
            }
            K.SHIFT -> dibujarShift(cv, cx, cy)
            K.BORRAR -> dibujarBorrar(cv, cx, cy)
            K.EMOJI -> dibujarEmoji(cv, cx, cy)
        }
    }

    private val textoBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val divisor = Paint().apply { color = CONTORNO }
    private val casilla = RectF()

    /** Barra como la del iPhone: tres casillas; la corrección que se pondrá va resaltada */
    private fun dibujarBarra(cv: Canvas) {
        val y0 = zona; val y1 = zona + BARRA
        val ancho = width / 3f
        textoBarra.textSize = dp(17f)
        for (k in 0 until 3) {
            val sg = sugerencias[k]
            val x0 = k * ancho
            if (k > 0 && sugerencias[k] != null && sugerencias[k - 1] != null &&
                sg?.tipo != 2 && sugerencias[k - 1]?.tipo != 2 && casillaApretada != k && casillaApretada != k - 1)
                cv.drawRect(x0, y0 + BARRA * 0.28f, x0 + dp(1f), y1 - BARRA * 0.28f, divisor)
            if (sg == null) continue
            casilla.set(x0 + dp(4f), y0 + dp(5f), x0 + ancho - dp(4f), y1 - dp(5f))
            if (sg.tipo == 2 || casillaApretada == k) {
                relleno.color = if (casillaApretada == k) LETRA_PRESIONADA else LETRA
                cv.drawRoundRect(casilla, dp(7f), dp(7f), relleno)
            }
            var s = if (sg.tipo == 1) "“${sg.texto}”" else sg.texto
            val max = ancho - dp(14f)
            if (textoBarra.measureText(s) > max) {
                val n = textoBarra.breakText(s, true, max - textoBarra.measureText("…"), null)
                s = s.substring(0, n.coerceAtLeast(1)) + "…"
            }
            cv.drawText(s, x0 + ancho / 2, (y0 + y1) / 2 - (textoBarra.ascent() + textoBarra.descent()) / 2, textoBarra)
        }
    }

    /** Globo del iPhone: la tecla crece hacia arriba con la letra grande */
    private fun dibujarGlobo(cv: Canvas, c: Colocada) {
        val k = c.dibujo
        val kw = k.width(); val kh = k.height()
        val bw = max(kw * 1.5f, kw + dp(20f))
        var izq = k.centerX() - bw / 2
        izq = izq.coerceIn(dp(2f), width - dp(2f) - bw)
        val der = izq + bw
        val arriba = k.top - kh * 1.15f
        val finAncho = k.top - kh * 0.12f
        val inicioTallo = k.top + kh * 0.28f
        val medio = (finAncho + inicioTallo) / 2
        val rg = dp(10f); val r = RADIO
        camino.reset()
        camino.moveTo(izq, arriba + rg)
        camino.quadTo(izq, arriba, izq + rg, arriba)
        camino.lineTo(der - rg, arriba)
        camino.quadTo(der, arriba, der, arriba + rg)
        camino.lineTo(der, finAncho)
        camino.cubicTo(der, medio, k.right, medio, k.right, inicioTallo)
        camino.lineTo(k.right, k.bottom - r)
        camino.quadTo(k.right, k.bottom, k.right - r, k.bottom)
        camino.lineTo(k.left + r, k.bottom)
        camino.quadTo(k.left, k.bottom, k.left, k.bottom - r)
        camino.lineTo(k.left, inicioTallo)
        camino.cubicTo(k.left, medio, izq, medio, izq, finAncho)
        camino.close()
        cv.drawPath(camino, globoP)
        cv.drawPath(camino, bordeGlobo)
        val t = c.tecla
        val s = if (shift > 0 && t.esLetra) t.texto.uppercase() else t.texto
        letras.textSize = if (s.length > 1) dp(30f) else dp(40f)
        val cy = (arriba + finAncho) / 2
        cv.drawText(s, (izq + der) / 2, cy - (letras.ascent() + letras.descent()) / 2, letras)
        letras.textSize = dp(25f)
    }

    private fun dibujarOpciones(cv: Canvas) {
        val r = opcionesRect
        relleno.color = LETRA
        cv.drawRoundRect(r, dp(9f), dp(9f), relleno)
        cv.drawRoundRect(r, dp(9f), dp(9f), bordeGlobo)
        val ancho = r.width() / opciones.size
        letras.textSize = dp(26f)
        for ((k, s) in opciones.withIndex()) {
            val x0 = r.left + k * ancho
            if (k == opcionElegida) {
                relleno.color = ELEGIDA
                cv.drawRoundRect(x0 + dp(2f), r.top + dp(3f), x0 + ancho - dp(2f), r.bottom - dp(3f), dp(6f), dp(6f), relleno)
            }
            cv.drawText(s, x0 + ancho / 2, r.centerY() - (letras.ascent() + letras.descent()) / 2, letras)
        }
        letras.textSize = dp(25f)
    }

    // Íconos dibujados (sin imágenes)
    private fun dibujarShift(cv: Canvas, cx: Float, cy: Float) {
        val s = dp(10f)
        camino.reset()
        camino.moveTo(cx, cy - s * 1.05f)
        camino.lineTo(cx + s, cy - s * 0.05f)
        camino.lineTo(cx + s * 0.45f, cy - s * 0.05f)
        camino.lineTo(cx + s * 0.45f, cy + s * 0.8f)
        camino.lineTo(cx - s * 0.45f, cy + s * 0.8f)
        camino.lineTo(cx - s * 0.45f, cy - s * 0.05f)
        camino.lineTo(cx - s, cy - s * 0.05f)
        camino.close()
        cv.drawPath(camino, if (shift > 0) lleno else trazo)
        if (shift == 2) cv.drawRect(cx - s * 0.45f, cy + s * 1.05f, cx + s * 0.45f, cy + s * 1.25f, lleno)
    }

    private fun dibujarBorrar(cv: Canvas, cx: Float, cy: Float) {
        val w = dp(12f); val h = dp(8.5f)
        camino.reset()
        camino.moveTo(cx - w - dp(6f), cy)
        camino.lineTo(cx - w, cy - h)
        camino.lineTo(cx + w, cy - h)
        camino.lineTo(cx + w, cy + h)
        camino.lineTo(cx - w, cy + h)
        camino.close()
        cv.drawPath(camino, trazo)
        val x = dp(3.5f); val ox = cx + dp(1.5f)
        cv.drawLine(ox - x, cy - x, ox + x, cy + x, trazo)
        cv.drawLine(ox - x, cy + x, ox + x, cy - x, trazo)
    }

    private fun dibujarEmoji(cv: Canvas, cx: Float, cy: Float) {
        val r = dp(11f)
        cv.drawCircle(cx, cy, r, trazo)
        cv.drawCircle(cx - r * 0.35f, cy - r * 0.25f, dp(1.4f), lleno)
        cv.drawCircle(cx + r * 0.35f, cy - r * 0.25f, dp(1.4f), lleno)
        ovalo.set(cx - r * 0.5f, cy - r * 0.15f, cx + r * 0.5f, cy + r * 0.55f)
        cv.drawArc(ovalo, 20f, 140f, false, trazo)
    }

    private fun dibujarIntro(cv: Canvas, cx: Float, cy: Float) {
        val w = dp(10f); val h = dp(6f)
        camino.reset()
        camino.moveTo(cx + w, cy - h - dp(2f))
        camino.lineTo(cx + w, cy + dp(2f))
        camino.lineTo(cx - w, cy + dp(2f))
        cv.drawPath(camino, trazo)
        val p = dp(4f)
        cv.drawLine(cx - w, cy + dp(2f), cx - w + p, cy + dp(2f) - p, trazo)
        cv.drawLine(cx - w, cy + dp(2f), cx - w + p, cy + dp(2f) + p, trazo)
    }
}
