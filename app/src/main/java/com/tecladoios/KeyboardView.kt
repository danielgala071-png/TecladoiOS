package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.LinearGradient
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
        /** [costos]: para cada letra, qué tan lejos cayó el toque (0 = justo encima, 1 = lejos) */
        fun texto(s: String, costos: FloatArray? = null)
        fun borrar(palabra: Boolean)
        fun espacio()
        fun intro()
        fun emojis()
        fun cursor(dx: Int, dy: Int)
        fun respuesta(t: Respuesta.Tipo)
        fun sugerencia(i: Int)
        /** Mantener la tecla emoji: abre los Ajustes */
        fun ajustes()
        /** Guarda lo aprendido sobre dónde tocas (cada 50 letras) */
        fun guardarAjuste(s: String)
        /** Mantener presionada una sugerencia: olvidarla */
        fun olvidarSugerencia(i: Int)
        /** Mantener la tecla Enviar: salto de línea en vez de enviar */
        fun saltoLinea()
    }

    /** Una casilla de la barra: [tipo] 0 = normal, 1 = lo escrito (entre comillas), 2 = corrección, 3 = lo copiado (pegar) */
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
    private val FUNCION_PRESIONADA = 0xFF3A3A3C.toInt()   // se aclara al presionar (más notorio)
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

    /** Color que combina con tu fondo de pantalla: tecla de enviar/buscar, borde de la barra y bloqueo de mayúsculas */
    var acento: Int = 0xFFE53935.toInt()
        set(v) {
            field = v
            acentoP.color = v
            acentoApretado.color = Color.rgb((Color.red(v) * 0.78f).toInt(), (Color.green(v) * 0.78f).toInt(), (Color.blue(v) * 0.78f).toInt())
            bordeTarjeta.color = Acento.conAlfa(v, 0x73)
            chipP.color = Acento.oscuro(v, 0.42f)
            chipApretado.color = Acento.oscuro(v, 0.55f)
            bordeGlobo.color = Acento.conAlfa(v, 0x99)
            invalidate()
        }
    private val acentoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val acentoApretado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB32D2A.toInt() }

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
    val zona get() = run {
        // Espacio justo para que el globo de la primera fila no se corte (crece con el tamaño)
        val globo = (ALTO_FILA - SEP_Y) * 1.2f + dp(4f)
        if (conBarra) max(dp(14f), globo - BARRA - ARRIBA - SEP_Y / 2) else max(dp(58f), globo - ARRIBA - SEP_Y / 2)
    }
    private val ARRIBA get() = dp(6f)
    private val ABAJO get() = dp(4f)
    // Teclas más altas (antes 57 dp): más cómodas para escribir
    private val ALTO_FILA get() = dp(66f) * (config?.tamano ?: 100) / 100f
    // Separación entre letras: 2.5 dp (letras más anchas)
    private val SEP_X get() = dp(2.5f)
    // Menos espacio entre filas: cada tecla queda más alta (56 dp en vez de 53)
    private val SEP_Y get() = dp(9f)
    private val LADO get() = dp(1.5f)
    private val RADIO get() = dp(8f)          // esquinas como el teclado de iOS 26
    /** Espacio de la barra de navegación del sistema (se pinta negro) */
    var abajoSistema = 0; set(v) { if (field != v) { field = v; requestLayout() } }

    private class Colocada(val tecla: Tecla, val dibujo: RectF, val toque: RectF, val fila: Int = 0)
    private var colocadas: Array<Colocada> = emptyArray()
    private var anchoUnidad = 1f

    /** Alto del teclado sin la franja de arriba (lo que se ve) */
    /** La fila de abajo (123, emoji, espacio, intro) es más alta que las de letras */
    private val EXTRA_ABAJO get() = if (distribucion.filas.last().teclas.any { it.tipo == K.ESPACIO }) dp(14f) else 0f

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
                    // Las teclas de función (mayúscula, 123, emoji, espacio, intro, borrar) quedan igual
                    val sx = if (t.funcion) dp(1.05f) else SEP_X / 2
                    val dib = RectF(x + sx, y0 + SEP_Y / 2, x + ancho - sx, y0 + alto - SEP_Y / 2)
                    val toque = RectF(x, y0, x + ancho, y0 + alto)
                    if (fi == 0) toque.top = zona + BARRA
                    if (fi == filas.size - 1) toque.bottom = height.toFloat()
                    lista.add(Colocada(t, dib, toque, fi))
                }
                x += ancho
            }
        }
        // Las teclas de los extremos reciben el toque hasta el borde
        for (c in lista) {
            if (c.toque.left - LADO < dp(4f)) c.toque.left = 0f
            if (w - LADO - c.toque.right < dp(4f)) c.toque.right = w
        }
        // Zonas de toque ajustadas a cómo escribes con dos dedos: la línea entre filas se corre
        // según dónde tocas de verdad cada fila, y cada mitad (pulgar izquierdo / derecho) se
        // corre a su lado. No cambia lo que se ve, solo dónde responde cada tecla.
        if (distribucion.modo == Distribuciones.LETRAS && filas.size >= 4) {
            val auto = config?.ajusteAuto != false
            val dy = if (auto) desvY else DESV_INICIAL
            val alto = altoFila - SEP_Y
            val lim = altoFila * 0.3f
            val s01 = ((dy[0] + dy[1]) / 2f * alto).coerceIn(-lim, lim)
            val s12 = ((dy[1] + dy[2]) / 2f * alto).coerceIn(-lim, lim)
            // Entre la última fila de letras y el espacio, un poco más a favor del espacio
            val s2e = (dy[2] / 2f * alto - altoFila * 0.04f).coerceIn(-lim, lim)
            val ultimaLetras = filas.size - 2
            for (c in lista) {
                when (c.fila) {
                    0 -> c.toque.bottom += s01
                    1 -> { c.toque.top += s01; c.toque.bottom += s12 }
                }
                if (c.fila == ultimaLetras) { c.toque.top += s12; c.toque.bottom += s2e }
                if (c.fila == filas.size - 1) c.toque.top += s2e
                if (auto && c.tecla.esLetra) {
                    val mano = if (c.dibujo.centerX() < w / 2f) 0 else 1
                    val sx = (desvX[mano] * c.dibujo.width()).coerceIn(-c.dibujo.width() * 0.25f, c.dibujo.width() * 0.25f)
                    if (c.toque.left > 1f) c.toque.left += sx
                    if (c.toque.right < w - 1f) c.toque.right += sx
                }
            }
        }
        colocadas = lista.toTypedArray()
    }

    // ---------- Ajuste a tu forma de escribir con dos dedos ----------
    private val DESV_INICIAL = floatArrayOf(0.18f, 0f, -0.06f)
    /** Cuánto más abajo (+) o arriba (−) de su centro tocas cada fila de letras (en altos de tecla) */
    private val desvY = DESV_INICIAL.copyOf()
    /** Cuánto a la derecha (+) o izquierda (−) tocas con cada pulgar (en anchos de tecla) */
    private val desvX = floatArrayOf(0f, 0f)
    /** Qué tan disperso tocas en vertical (para recomendar el tamaño) */
    private var dispersion = 0.2f
    private var muestras = 0

    // Lo aprendido de cada letra (como Gboard): dónde la tocas de verdad, en anchos/altos de tecla
    private val N_LETRAS = Diccionario.ALFABETO.length
    private val letraX = FloatArray(N_LETRAS)
    private val letraY = FloatArray(N_LETRAS)
    private val letraN = IntArray(N_LETRAS)
    /** Dispersión a lo ancho (también cuenta para el tamaño recomendado) */
    private var dispersionX = 0.2f

    /** Letras con las que puede seguir la palabra que escribes (las pone el corrector) */
    var probables: Set<Char>? = null

    fun cargarAjuste(s: String?) {
        val v = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return
        if (v.size < 7) return
        desvY[0] = v[0]; desvY[1] = v[1]; desvY[2] = v[2]
        desvX[0] = v[3]; desvX[1] = v[4]; dispersion = v[5]; muestras = v[6].toInt()
        if (v.size >= 8) dispersionX = v[7]
        if (v.size >= 8 + 3 * N_LETRAS) for (i in 0 until N_LETRAS) {
            letraX[i] = v[8 + 3 * i]; letraY[i] = v[9 + 3 * i]; letraN[i] = v[10 + 3 * i].toInt()
        }
        acomodar()
    }

    fun reiniciarAjuste() {
        DESV_INICIAL.copyInto(desvY); desvX[0] = 0f; desvX[1] = 0f; dispersion = 0.2f; dispersionX = 0.2f; muestras = 0
        letraX.fill(0f); letraY.fill(0f); letraN.fill(0)
        porConfirmar = -1
        acomodar()
    }

    private fun ajusteTexto(): String {
        val sb = StringBuilder()
        for (v in listOf(desvY[0], desvY[1], desvY[2], desvX[0], desvX[1], dispersion, muestras.toFloat(), dispersionX))
            sb.append(String.format(java.util.Locale.US, "%.4f", v)).append(',')
        for (i in 0 until N_LETRAS)
            sb.append(String.format(java.util.Locale.US, "%.4f,%.4f,%d,", letraX[i], letraY[i], letraN[i]))
        return sb.toString().trimEnd(',')
    }

    /** Dónde sueles tocar la letra [c] (desvío respecto al centro): suya si ya la aprendió, si no de su fila y mano */
    private fun desvioDe(c: Colocada): Pair<Float, Float> {
        val i = Diccionario.indiceLetra(c.tecla.texto[0])
        val mano = if (c.dibujo.centerX() < width / 2f) 0 else 1
        val fx = desvX[mano]; val fy = desvY.getOrElse(c.fila) { 0f }
        if (i < 0 || letraN[i] == 0) return fx to fy
        val p = (letraN[i] / 25f).coerceAtMost(1f)          // con 25 toques ya confía en la letra
        return (fx * (1 - p) + letraX[i] * p) to (fy * (1 - p) + letraY[i] * p)
    }

    // El toque se aprende recién cuando escribes la letra siguiente: si la borraste enseguida
    // (era un error), no se aprende. Así un toque equivocado no corre la tecla al lado malo.
    private var porConfirmar = -1
    private var pcFila = 0; private var pcMano = 0; private var pcDx = 0f; private var pcDy = 0f

    private fun aprenderToque(c: Colocada, x: Float, y: Float) {
        confirmarToque()
        if (distribucion.modo != Distribuciones.LETRAS || !c.tecla.esLetra || c.fila > 2) return
        val dy = (y - c.dibujo.centerY()) / c.dibujo.height()
        val dx = (x - c.dibujo.centerX()) / c.dibujo.width()
        if (abs(dy) > 0.9f || abs(dx) > 0.9f) return        // muy lejos: seguramente era otra tecla
        porConfirmar = Diccionario.indiceLetra(c.tecla.texto[0])
        pcFila = c.fila; pcMano = if (c.dibujo.centerX() < width / 2f) 0 else 1; pcDx = dx; pcDy = dy
    }

    /** Borraste: el último toque no se aprende */
    private fun descartarToque() { porConfirmar = -1 }

    private fun confirmarToque() {
        val i = porConfirmar
        if (i < 0) return
        porConfirmar = -1
        val a = 0.02f
        desvY[pcFila] = desvY[pcFila] * (1 - a) + pcDy * a
        desvX[pcMano] = desvX[pcMano] * (1 - a) + pcDx * a
        dispersion = dispersion * (1 - a) + abs(pcDy - desvY[pcFila]) * a
        dispersionX = dispersionX * (1 - a) + abs(pcDx - desvX[pcMano]) * a
        val b = if (letraN[i] < 10) 0.15f else 0.05f            // al principio aprende más rápido
        letraX[i] = letraX[i] * (1 - b) + pcDx * b
        letraY[i] = letraY[i] * (1 - b) + pcDy * b
        if (letraN[i] < 1000) letraN[i]++
        muestras++
        if (muestras % 50 == 0) {
            if (config?.ajusteAuto != false) acomodar()
            oyente?.guardarAjuste(ajusteTexto())
        }
    }

    private fun buscar(x: Float, y: Float): Colocada? {
        if (y < zona + BARRA) return null
        var dentro: Colocada? = null
        for (c in colocadas) if (c.toque.contains(x, y)) { dentro = c; break }
        if (dentro == null) {
            // En un hueco (al lado de mayúscula o borrar): la tecla más cercana de esa fila
            var md = Float.MAX_VALUE
            for (c in colocadas) {
                if (y < c.toque.top || y > c.toque.bottom) continue
                val dx = max(max(c.toque.left - x, 0f), x - c.toque.right)
                if (dx < md) { md = dx; dentro = c }
            }
        }
        val d = dentro ?: return null
        if (!d.tecla.esLetra || distribucion.modo != Distribuciones.LETRAS || config?.ajusteAuto == false) return d
        // Entre las letras vecinas gana la más cercana a donde TÚ sueles tocar cada una;
        // si dos quedan casi igual, gana la que sigue formando una palabra (como Gboard)
        var mejor = d; var mejorP = Float.MAX_VALUE; var segunda: Colocada? = null; var segundaP = Float.MAX_VALUE
        for (c in colocadas) {
            if (!c.tecla.esLetra || abs(c.fila - d.fila) > 1) continue
            if (abs(c.dibujo.centerX() - d.dibujo.centerX()) > c.dibujo.width() * 1.6f) continue
            val (ox, oy) = desvioDe(c)
            val dx = (x - (c.dibujo.centerX() + ox * c.dibujo.width())) / c.dibujo.width()
            val dy = (y - (c.dibujo.centerY() + oy * c.dibujo.height())) / c.dibujo.height()
            val p = dx * dx + dy * dy * 1.3f
            if (p < mejorP) { segunda = mejor; segundaP = mejorP; mejor = c; mejorP = p }
            else if (p < segundaP) { segunda = c; segundaP = p }
        }
        val prob = probables
        val seg = segunda
        if (prob != null && seg != null && seg !== mejor && segundaP - mejorP < 0.12f &&
            mejor.tecla.texto[0] !in prob && seg.tecla.texto[0] in prob) return seg
        return mejor
    }

    // ---------- Dedos ----------
    private object Modo { const val NORMAL = 0; const val OPCIONES = 1; const val CURSOR = 2; const val HECHO = 3; const val BARRA = 4 }

    private inner class Dedo(val id: Int) {
        var c: Colocada? = null
        var modo = Modo.NORMAL
        var x0 = 0f; var y0 = 0f
        /** Dónde está el dedo ahora (para saber dónde se soltó la letra) */
        var xu = 0f; var yu = 0f
        var acumX = 0f; var acumY = 0f
        var repeticiones = 0
        /** El dedo apoyó «123» y sigue apoyado: si se desliza a un símbolo y lo suelta, se escribe y vuelve a las letras */
        var desde123 = false
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Los toques llegan al instante, sin esperar al siguiente cuadro de la pantalla
                if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
                bajar(e.getPointerId(i), e.getX(i), e.getY(i))
            }
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
            val bd = Dedo(id).apply { modo = Modo.BARRA; x0 = x; y0 = y; repeticiones = k }
            dedos[id] = bd
            postDelayed(bd.larga, 550L)
            casillaApretada = k
            oyente?.respuesta(Respuesta.Tipo.LETRA)
            invalidate()
            return
        }
        val c = buscar(x, y) ?: return
        val dd = Dedo(id).apply { this.c = c; x0 = x; y0 = y; xu = x; yu = y }
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
                descartarToque()
                oyente?.borrar(false)
                postDelayed(dd.repetir, 450L)
            }
            K.SHIFT -> tocarShift()
            K.NUM -> {
                // Como el iPhone: los símbolos salen al apoyar el dedo, sin esperar a soltarlo
                cambiarModoConDedo(Distribuciones.SIMBOLOS, dd)
                dd.desde123 = true
            }
            K.ESPACIO -> postDelayed(dd.larga, 450L)
            // Enviar/Buscar/Ir: mantenerla pone un salto de línea (escribir en varias líneas en el chat)
            K.INTRO -> if (etiquetaIntro != null) postDelayed(dd.larga, 450L)
            K.EMOJI -> postDelayed(dd.larga, 500L)
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
                if (dd.desde123) {
                    // Deslizando desde «123»: se ilumina el símbolo que queda bajo el dedo
                    dd.xu = x; dd.yu = y
                    val n = buscar(x, y)?.takeIf { it.tecla.tipo == K.TEXTO }
                    if (n !== dd.c) {
                        dd.c?.let { presionadas.remove(it) }
                        dd.c = n; presionada = n
                        n?.let { presionadas.add(it) }
                        invalidate()
                    }
                    return
                }
                val c = dd.c ?: return
                dd.xu = x; dd.yu = y
                if (c.tecla.tipo == K.ESPACIO) {
                    // Mover el dedo sobre el espacio también mueve el cursor (como el iPhone)
                    if (abs(x - dd.x0) > dp(14f)) { removeCallbacks(dd.larga); empezarCursor(dd, x, y) }
                    return
                }
                // El globo sigue al dedo si se desliza a otra letra
                // Con dos dedos el pulgar se desliza un poco al soltar: solo cambia de tecla si el
                // dedo salió claramente de ella (8 dp de margen)
                val m = dp(8f)
                if (c.tecla.tipo == K.TEXTO && (x < c.toque.left - m || x > c.toque.right + m || y < c.toque.top - m || y > c.toque.bottom + m)) {
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
        if (dd.desde123 && dd.modo == Modo.NORMAL) {
            // Soltó sobre un símbolo: se escribe y vuelve a las letras; si no, se queda en símbolos
            if (c != null && c.tecla.tipo == K.TEXTO) {
                escribir(dd)
                if (distribucion.modo != Distribuciones.LETRAS) ponerModo(Distribuciones.LETRAS)
            }
            invalidate()
            return
        }
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
                if (casillaApretada == dd.repeticiones) {
                    oyente?.sugerencia(dd.repeticiones)
                }
                casillaApretada = -1
            }
        }
        if (c != null) presionadas.remove(c)
        if (presionada === c) presionada = null
        invalidate()
    }

    /**
     * Qué tan cerca cayó el toque de cada letra: el corrector lo usa para saber que si tocaste
     * entre la o y la p, cualquiera de las dos pudo ser (como Gboard)
     */
    private fun costosToque(x: Float, y: Float): FloatArray? {
        if (distribucion.modo != Distribuciones.LETRAS) return null
        val r = FloatArray(Diccionario.ALFABETO.length) { 1.2f }
        for (c in colocadas) {
            if (!c.tecla.esLetra) continue
            val i = Diccionario.indiceLetra(c.tecla.texto[0])
            if (i < 0) continue
            val dx = (x - c.dibujo.centerX()) / c.dibujo.width()
            val dy = (y - c.dibujo.centerY()) / c.dibujo.height()
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            r[i] = (0.12f + 0.6f * max(0f, dist - 0.45f)).coerceAtMost(1.2f)
        }
        return r
    }

    private fun escribir(dd: Dedo) {
        val c = dd.c ?: return
        removeCallbacks(dd.larga)
        val t = c.tecla
        val s = if (shift > 0 && t.esLetra) t.texto.uppercase() else t.texto
        oyente?.texto(s, if (t.esLetra) costosToque(dd.xu, dd.yu) else null)
        aprenderToque(c, dd.xu, dd.yu)
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
        if (dd.modo == Modo.BARRA) {
            // Mantener una sugerencia: se olvida (o se quita lo copiado de la barra)
            if (casillaApretada == dd.repeticiones) {
                oyente?.respuesta(Respuesta.Tipo.FUNCION)
                oyente?.olvidarSugerencia(dd.repeticiones)
            }
            casillaApretada = -1
            dd.modo = Modo.HECHO
            invalidate()
            return
        }
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
            K.INTRO -> {
                dd.modo = Modo.HECHO
                presionadas.remove(c)
                oyente?.respuesta(Respuesta.Tipo.INTRO)
                oyente?.saltoLinea()
                invalidate()
            }
            K.EMOJI -> {
                // Al soltar ya no abre los emojis
                dd.modo = Modo.HECHO
                presionadas.remove(c)
                oyente?.respuesta(Respuesta.Tipo.FUNCION)
                invalidate()
                oyente?.ajustes()
            }
        }
    }

    private fun empezarCursor(dd: Dedo, x: Float, y: Float) {
        dd.modo = Modo.CURSOR
        dd.x0 = x; dd.y0 = y; dd.acumX = 0f; dd.acumY = 0f
        trackpad = true
        oyente?.respuesta(Respuesta.Tipo.FUNCION)
        invalidate()
    }

    /** Cambia de distribución sin soltar el dedo que la pidió (para deslizar desde «123») */
    private fun cambiarModoConDedo(modo: Int, dd: Dedo) {
        for (otro in dedos.values) if (otro !== dd) { removeCallbacks(otro.larga); removeCallbacks(otro.repetir) }
        dedos.entries.removeAll { it.value !== dd }
        presionadas.clear(); presionada = null
        distribucion = Distribuciones.crear(modo, false)
        shift = 0; shiftAuto = false
        dd.c = null
        requestLayout()
        acomodar()
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
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; style = Paint.Style.STROKE; strokeWidth = dp(1.25f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }   // íconos finos estilo SF
    private val lleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; style = Paint.Style.FILL }
    private val globoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LETRA }
    private val LETRA_ARRIBA = 0xFF353538.toInt()
    private val LETRA_ABAJO = 0xFF29292B.toInt()
    private val baseLetra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161618.toInt() }
    private val letraDegradado = Paint(Paint.ANTI_ALIAS_FLAG)
    private var degradadoAlto = -1f
    private val chipP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Acento.oscuro(Acento.DEFECTO, 0.42f) }
    private val chipApretado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Acento.oscuro(Acento.DEFECTO, 0.55f) }
    private val puntoSep = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF636366.toInt() }
    // Borde fino en vez de sombra difuminada (la sombra se recalcula en cada cuadro y gasta)
    private val bordeGlobo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Acento.conAlfa(Acento.DEFECTO, 0x99) }
    private val camino = Path()
    private val ovalo = RectF()

    override fun onDraw(cv: Canvas) {
        relleno.color = FONDO
        cv.drawRect(0f, zona, width.toFloat(), height.toFloat(), relleno)
        letras.textSize = dp(28f)
        etiquetas.textSize = dp(17f)
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
        if (t.tipo == K.INTRO && etiquetaIntro != null) {
            // Enviar, Buscar, Ir…: la tecla toma el color de tu fondo, como el azul del iPhone
            cv.drawRoundRect(r, RADIO, RADIO, if (apretada) acentoApretado else acentoP)
        } else if (t.funcion) {
            relleno.color = if (apretada) FUNCION_PRESIONADA else FUNCION
            cv.drawRoundRect(r, RADIO, RADIO, relleno)
            cv.drawRoundRect(r, RADIO, RADIO, contorno)
        } else {
            // Base más oscura asomando 1,5 dp abajo: relieve fino como el iPhone
            cv.drawRoundRect(r.left, r.top + dp(1.5f), r.right, r.bottom + dp(1.5f), RADIO, RADIO, baseLetra)
            if (apretada && !conGlobo) {
                relleno.color = LETRA_PRESIONADA
                cv.drawRoundRect(r, RADIO, RADIO, relleno)
            } else {
                // Grafito con un degradado muy suave: más claro arriba, más oscuro abajo
                if (degradadoAlto != r.height()) {
                    degradadoAlto = r.height()
                    letraDegradado.shader = LinearGradient(0f, 0f, 0f, r.height(), LETRA_ARRIBA, LETRA_ABAJO, Shader.TileMode.CLAMP)
                }
                cv.save(); cv.translate(r.left, r.top)
                cv.drawRoundRect(0f, 0f, r.width(), r.height(), RADIO, RADIO, letraDegradado)
                cv.restore()
            }
        }
        if (trackpad && t.tipo != K.ESPACIO) return   // con el cursor, las teclas quedan en blanco
        val cx = r.centerX(); val cy = r.centerY()
        when (t.tipo) {
            K.TEXTO -> {
                val s = if (shift > 0 && t.esLetra) t.texto.uppercase() else t.texto
                letras.textSize = if (distribucion.numerico) dp(29f) else if (s.length > 1) dp(22f) else dp(28f)
                cv.drawText(s, cx, cy - (letras.ascent() + letras.descent()) / 2, letras)
                letras.textSize = dp(28f)
            }
            K.NUM, K.ABC, K.SIMB2 -> cv.drawText(t.texto, cx, cy - (etiquetas.ascent() + etiquetas.descent()) / 2, etiquetas)
            K.ESPACIO -> if (!trackpad) {
                // «español» en gris tenue, como el nombre del idioma en la barra espaciadora
                etiquetas.color = 0xFF8E8E93.toInt()
                cv.drawText("español", cx, cy - (etiquetas.ascent() + etiquetas.descent()) / 2, etiquetas)
                etiquetas.color = TEXTO
            }
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
    private val textoBarraFuerte = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTO; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val TEXTO_SUAVE = 0xFFD1D1D6.toInt()
    private val fondoTarjeta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1E.toInt() }
    private val bordeTarjeta = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = CONTORNO }
    private val divisorTarjeta = Paint().apply { color = 0xFF48484A.toInt() }
    private val tarjeta = RectF()
    private val casilla = RectF()
    private val espacios = Regex("\\s+")
    private val enlace = Regex("^(?:https?://)?(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)+)(?:[/?#:]\\S*)?$", RegexOption.IGNORE_CASE)

    private var dominioDe: String? = null
    private var dominioVal: String? = null

    /** Si lo copiado es un enlace, su dominio (youtube.com); si no, null. Se calcula una sola vez */
    private fun dominio(t: String): String? {
        if (t === dominioDe) return dominioVal
        dominioDe = t
        dominioVal = calcularDominio(t)
        return dominioVal
    }

    private fun calcularDominio(t: String): String? {
        val s = t.trim()
        if (s.length > 2000 || s.contains(' ') || s.contains('\n')) return null
        if (!s.startsWith("http", true) && !s.startsWith("www.", true)) return null
        return enlace.matchEntire(s)?.groupValues?.get(1)?.lowercase()
    }

    /** Barra como la del iPhone: tres casillas; la corrección que se pondrá va resaltada */
    private fun dibujarBarra(cv: Canvas) {
        val y0 = zona; val y1 = zona + BARRA
        // Estilo «Tarjeta»: una sola pieza grafito con borde fino y las 3 palabras separadas
        // Estilo «Cápsula»: una cápsula redonda completa con filo del color de tu fondo
        tarjeta.set(dp(6f), y0 + dp(3f), width - dp(6f), y1 - dp(3f))
        val rc = tarjeta.height() / 2
        cv.drawRoundRect(tarjeta, rc, rc, fondoTarjeta)
        cv.drawRoundRect(tarjeta, rc, rc, bordeTarjeta)

        val ancho = width / 3f
        textoBarra.textSize = dp(17f)
        textoBarraFuerte.textSize = dp(17f)
        // La palabra principal va en negrita y blanca: la que se pondrá sola o, si no hay, la del medio
        val principal = sugerencias.indexOfFirst { it?.tipo == 2 }.takeIf { it >= 0 } ?: 1
        for (k in 0 until 3) {
            val sg = sugerencias[k]
            val x0 = k * ancho
            // Puntitos tenues entre palabras (no junto al chip de color)
            if (k > 0 && k != principal && k - 1 != principal && casillaApretada != k && casillaApretada != k - 1)
                cv.drawCircle(x0, (y0 + y1) / 2, dp(1.8f), puntoSep)
            if (sg == null) continue
            if (k == principal || casillaApretada == k) {
                // La palabra que se pondrá sola va sobre un chip del color de tu fondo
                casilla.set(x0 + dp(if (k == 0) 10f else 4f), y0 + dp(8f), x0 + ancho - dp(if (k == 2) 10f else 4f), y1 - dp(8f))
                val rr = casilla.height() / 2
                if (k == principal) cv.drawRoundRect(casilla, rr, rr, if (casillaApretada == k) chipApretado else chipP)
                else { relleno.color = LETRA_PRESIONADA; cv.drawRoundRect(casilla, rr, rr, relleno) }
            }
            val p = if (k == principal) textoBarraFuerte else textoBarra
            p.color = if (k == principal) TEXTO else TEXTO_SUAVE
            var s = when (sg.tipo) {
                1 -> "“${sg.texto}”"
                3 -> dominio(sg.texto)?.let { "🔗 $it" } ?: sg.texto.trim().take(80).replace(espacios, " ")
                else -> sg.texto
            }
            // Lo copiado lleva el ícono del portapapeles a la izquierda
            val icono = if (sg.tipo == 3 && dominio(sg.texto) == null) dp(20f) else 0f
            val max = ancho - dp(18f) - icono
            if (p.measureText(s) > max) {
                val n = p.breakText(s, true, max - p.measureText("…"), null)
                s = s.substring(0, n.coerceAtLeast(1)) + "…"
            }
            val ty = (y0 + y1) / 2 - (p.ascent() + p.descent()) / 2
            if (sg.tipo == 3 && icono > 0f) {
                val tx = x0 + ancho / 2 - (p.measureText(s) + icono) / 2
                val px = tx + dp(6f); val cy = (y0 + y1) / 2
                trazo.strokeWidth = dp(1.3f)
                cv.drawRoundRect(px - dp(5.5f), cy - dp(6.5f), px + dp(5.5f), cy + dp(7.5f), dp(2f), dp(2f), trazo)
                cv.drawRoundRect(px - dp(2.5f), cy - dp(8.5f), px + dp(2.5f), cy - dp(5f), dp(1f), dp(1f), trazo)
                trazo.strokeWidth = dp(1.5f)
                p.textAlign = Paint.Align.LEFT
                cv.drawText(s, tx + icono, ty, p)
                p.textAlign = Paint.Align.CENTER
            } else cv.drawText(s, x0 + ancho / 2, ty, p)
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
        letras.textSize = if (s.length > 1) dp(32f) else dp(43f)
        val cy = (arriba + finAncho) / 2
        cv.drawText(s, (izq + der) / 2, cy - (letras.ascent() + letras.descent()) / 2, letras)
        letras.textSize = dp(28f)
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
        letras.textSize = dp(28f)
    }

    // Íconos dibujados (sin imágenes)
    private fun dibujarShift(cv: Canvas, cx: Float, cy: Float) {
        val s = dp(12.5f)
        camino.reset()
        camino.moveTo(cx, cy - s * 1.05f)
        camino.lineTo(cx + s, cy - s * 0.05f)
        camino.lineTo(cx + s * 0.45f, cy - s * 0.05f)
        camino.lineTo(cx + s * 0.45f, cy + s * 0.8f)
        camino.lineTo(cx - s * 0.45f, cy + s * 0.8f)
        camino.lineTo(cx - s * 0.45f, cy - s * 0.05f)
        camino.lineTo(cx - s, cy - s * 0.05f)
        camino.close()
        // Mayúscula activa: flecha rellena del color de tu fondo
        cv.drawPath(camino, if (shift > 0) acentoP else trazo)
        if (shift == 2) cv.drawRoundRect(cx - s * 0.55f, cy + s * 1.05f, cx + s * 0.55f, cy + s * 1.3f, s * 0.12f, s * 0.12f, acentoP)
    }

    private fun dibujarBorrar(cv: Canvas, cx: Float, cy: Float) {
        val w = dp(14f); val h = dp(10f)
        camino.reset()
        camino.moveTo(cx - w - dp(7f), cy)
        camino.lineTo(cx - w, cy - h)
        camino.lineTo(cx + w, cy - h)
        camino.lineTo(cx + w, cy + h)
        camino.lineTo(cx - w, cy + h)
        camino.close()
        cv.drawPath(camino, trazo)
        val x = dp(4.2f); val ox = cx + dp(1.5f)
        cv.drawLine(ox - x, cy - x, ox + x, cy + x, trazo)
        cv.drawLine(ox - x, cy + x, ox + x, cy - x, trazo)
    }

    private fun dibujarEmoji(cv: Canvas, cx: Float, cy: Float) {
        val r = dp(13.5f)
        cv.drawCircle(cx, cy, r, trazo)
        cv.drawCircle(cx - r * 0.35f, cy - r * 0.25f, dp(1.4f), lleno)
        cv.drawCircle(cx + r * 0.35f, cy - r * 0.25f, dp(1.4f), lleno)
        ovalo.set(cx - r * 0.5f, cy - r * 0.15f, cx + r * 0.5f, cy + r * 0.55f)
        cv.drawArc(ovalo, 20f, 140f, false, trazo)
    }

    private fun dibujarIntro(cv: Canvas, cx: Float, cy: Float) {
        val w = dp(12.5f); val h = dp(7.5f)
        camino.reset()
        camino.moveTo(cx + w, cy - h - dp(2f))
        camino.lineTo(cx + w, cy + dp(2f))
        camino.lineTo(cx - w, cy + dp(2f))
        cv.drawPath(camino, trazo)
        val p = dp(5f)
        cv.drawLine(cx - w, cy + dp(2f), cx - w + p, cy + dp(2f) - p, trazo)
        cv.drawLine(cx - w, cy + dp(2f), cx - w + p, cy + dp(2f) + p, trazo)
    }
}
