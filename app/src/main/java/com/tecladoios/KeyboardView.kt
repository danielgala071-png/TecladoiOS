package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * El teclado en sí: dibuja las teclas con el aspecto del iPhone y maneja los toques.
 *
 * Cada dedo se sigue por separado (escribir rápido con dos pulgares nunca mezcla teclas: si
 * bajas un dedo mientras el otro sigue apoyado, la tecla del primero se escribe al instante).
 * Gestos: deslizar por las letras para escribir, deslizar el espacio para mover el cursor,
 * mantener el espacio para usarlo como trackpad, deslizar desde borrar a la izquierda para
 * borrar palabras enteras, mantener una tecla para tildes y símbolos y deslizar desde 123
 * hasta un símbolo para escribirlo y volver a las letras.
 */
class KeyboardView(context: Context, private val capa: Capa) : View(context) {

    interface Oyente {
        fun tecla(texto: String, x: Float, y: Float, esLetra: Boolean)
        fun codigo(tipo: Int)
        fun borrarRepetido(paso: Int)
        fun cursor(pasos: Int)
        fun cursorVertical(pasos: Int)
        fun seleccionBorrar(palabras: Int)
        fun terminarSeleccionBorrar(borrar: Boolean)
        fun deslizado(xs: FloatArray, ys: FloatArray, n: Int)
        fun volverLetras()
        fun unaMano(accion: Int)
        fun respuesta(tipo: Respuesta.Tipo)
        /** Quita la última letra escrita (para cambiarla por una tilde al mantener presionado) */
        fun deshacerLetra()
        /** Trackpad: con otro dedo se empieza (o se termina) a seleccionar texto, como en iOS */
        fun seleccionTrackpad(activa: Boolean)
        /** Opción elegida en el menú que sale al mantener la tecla emoji */
        fun menuTeclado(opcion: String)
    }

    private var seleccionTrackpad = false

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; ensuciar() }
    var config: Config? = null; set(v) { field = v; ensuciar() }
    val geo = Cerebro.geo

    var distribucion: Distribucion = Distribuciones.crear(Distribuciones.LETRAS, 0, false)
        set(v) {
            // Transición abc ↔ 123: se guarda la imagen de la distribución que se va para que
            // sus letras salgan rodando mientras entran las nuevas, fila por fila
            if (field.nombre != v.nombre && cacheFondo != null && cacheLetras != null && isShown) {
                guardarLibre(anteriorFondo); guardarLibre(anteriorLetras)
                anteriorFondo = cacheFondo; anteriorLetras = cacheLetras
                cacheFondo = null; cacheLetras = null
                cambioMs = SystemClock.uptimeMillis()
                // abc → 123: las letras salen a la izquierda; al volver, a la derecha
                cambioHaciaIzq = v.nombre != "abc"
            }
            field = v; reacomodar(); ensuciar()
        }

    /** 0 minúsculas, 1 mayúscula, 2 bloqueo */
    // La mayúscula solo cambia letras y el color de la tecla shift: no se redibuja todo
    var shift = 0; set(v) {
        if (field != v) {
            // La tecla mayúscula pasa de gris a blanco (o al revés) con un fundido
            shiftAntes = field; shiftCambioMs = SystemClock.uptimeMillis()
            // Minúsculas ↔ mayúsculas con un fundido cortito (se guarda cómo se veían)
            val l = cacheLetras
            if (l != null && !l.isRecycled && isShown && distribucion.nombre == "abc" && !modoTrackpad) {
                letrasViejas = try {
                    // Se reutiliza la misma imagen (sin crear una nueva en cada cambio de mayúscula)
                    val b = bufferMayus?.takeIf { !it.isRecycled && it.width == l.width && it.height == l.height }
                        ?: Bitmap.createBitmap(l.width, l.height, Bitmap.Config.ALPHA_8).also { bufferMayus = it }
                    b.eraseColor(0)
                    Canvas(b).drawBitmap(l, 0f, 0f, null)
                    b
                } catch (e: Throwable) { null }
                letrasViejasMs = SystemClock.uptimeMillis()
            }
            field = v; letrasSucias = true; shiftSucia = true; invalidate()
        }
    }
    private var letrasViejas: Bitmap? = null
    private var bufferMayus: Bitmap? = null
    private var letrasViejasMs = 0L
    private val FUNDIDO_MAYUS = 90f
    /** Texto de la tecla intro ("buscar", "enviar"...) o null para la flecha */
    var textoIntro: String? = null; set(v) { if (field != v) { field = v; ensuciar() } }
    var introDestacado = false; set(v) { if (field != v) { field = v; ensuciar() } }
    /** Altura en % mientras se ajusta el tamaño (null = la de los ajustes) */
    var alturaForzada: Int? = null; set(v) { if (field != v) { field = v; requestLayout() } }
    var unaMano = 0; set(v) { if (field != v) { field = v; reacomodar(); requestLayout(); ensuciar() } }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ---------- Medidas ----------

    private class Colocada(val tecla: Tecla, val toque: RectF, val dibujo: RectF, val fila: Int)

    private var colocadas: Array<Colocada> = emptyArray()
    private var anchoTecla = 1f
    private var altoFila = 1f
    private val ARRIBA get() = dp(3f)
    private val panelMano = RectF()
    private val botonExpandir = RectF()
    private val botonCambiar = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val filas = distribucion.filas.size
        val escala = (alturaForzada ?: config?.altura ?: 100) / 100f
        var base = if (filas >= 5) 57f else 62f   // teclas más grandes (más fáciles a dos dedos)
        // En horizontal: altura cómoda según la pantalla (ni aplastadas ni tapando todo)
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val altoPantalla = resources.configuration.screenHeightDp.toFloat()
            base = (altoPantalla * (if (filas >= 5) 0.12f else 0.14f)).coerceIn(44f, base)
        }
        // (+3 dp que antes eran margen debajo del teclado: ahora son de la fila de abajo)
        val h = (dp(base) * filas * escala + dp(8f) + dp(6f) + dp(3f)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reacomodar()
    }

    private fun reacomodar() {
        globosPedidos = false
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // Menos margen a los costados y abajo: ese espacio se lo lleva la fila de abajo
        // (123, emoji, espacio e intro), que queda más alta
        // Menos espacio entre la barra y la primera fila (3 dp): ese alto se lo llevan las teclas
        val arriba = ARRIBA; val abajo = dp(2f)
        val lado = dp(1.5f)
        var izq = lado; var der = w - lado
        panelMano.setEmpty()
        if (unaMano != 0 && distribucion.nombre != "num" && distribucion.nombre != "tel") {
            val ancho = w * 0.82f
            if (unaMano == 1) { der = ancho; panelMano.set(ancho, arriba, w, h - abajo) }
            else { izq = w - ancho; panelMano.set(0f, arriba, izq, h - abajo) }
            val cx = panelMano.centerX(); val s = dp(44f)
            botonExpandir.set(cx - s / 2, panelMano.centerY() - s * 1.2f, cx + s / 2, panelMano.centerY() - s * 0.2f)
            botonCambiar.set(cx - s / 2, panelMano.centerY() + s * 0.2f, cx + s / 2, panelMano.centerY() + s * 1.2f)
        }
        val filas = distribucion.filas
        // Los 7 dp que eran margen (abajo y debajo del teclado) van enteros a la fila de abajo;
        // las demás filas quedan del mismo alto que antes
        // La fila de abajo (123, emoji, espacio, intro) es más alta: se lleva 12 dp extra
        val extraAbajo = if (filas.size >= 2 && filas.last().teclas.any { it.tipo == K.ESPACIO }) dp(12f) else 0f
        altoFila = (h - arriba - abajo - extraAbajo) / filas.size
        // Teclado dividido (celular echado): las letras se separan a los lados para los pulgares,
        // como en el iPad. La fila de abajo (espacio) queda entera.
        val dividido = config?.dividido != false && unaMano == 0 &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            distribucion.nombre != "num" && distribucion.nombre != "tel"
        val hueco = if (dividido) (der - izq) * 0.24f else 0f
        val unidadEntera = (der - izq) / distribucion.unidades
        val unidad = (der - izq - hueco) / distribucion.unidades
        anchoTecla = unidad
        // Divisiones finas entre teclas: teclas más grandes y un aspecto más limpio
        // Más aire entre teclas. Solo cambia el dibujo: la zona que recibe el toque sigue siendo
        // la celda completa, así escribir rápido a dos dedos no pierde precisión.
        val sepX = dp(6f) * if (unaMano != 0) 0.85f else 1f
        // Menos aire entre filas (antes hasta 11 dp): las teclas quedan más altas y el
        // teclado mide lo mismo. Toda la celda sigue recibiendo el toque.
        val sepY = minOf(dp(6f), altoFila * 0.1f)
        val lista = ArrayList<Colocada>(48)
        for ((fi, fila) in filas.withIndex()) {
            val partir = dividido && fila.teclas.none { it.tipo == K.ESPACIO }
            val u = if (partir || !dividido) unidad else unidadEntera
            var x = izq + (distribucion.unidades - fila.unidades) / 2 * u + fila.sangria * u
            val y0 = arriba + fi * altoFila
            var enUnidades = fila.sangria
            val mitad = fila.unidades / 2f - 0.01f
            var saltado = false
            for (t in fila.teclas) {
                // Al cruzar la mitad de la fila se salta el hueco del centro
                if (partir && !saltado && enUnidades >= mitad) { x += hueco; saltado = true }
                enUnidades += t.ancho
                val ancho = t.ancho * u
                val altoEsta = if (fi == filas.size - 1) altoFila + extraAbajo else altoFila
                val toque = RectF(x, y0, x + ancho, y0 + altoEsta)
                // Mayúscula, borrar y la fila de abajo se ven más grandes: menos margen a los lados
                val grande = t.tipo == K.SHIFT || t.tipo == K.BORRAR || fi == filas.size - 1
                // Letras más compactas: un poco más de aire alrededor (el toque sigue siendo la
                // celda completa, así no se pierde precisión)
                val sx = if (grande) sepX * 0.6f else sepX * 1.45f
                val sy = if (grande) sepY else sepY + dp(2.5f)
                val dib = RectF(x + sx / 2, y0 + sy / 2, x + ancho - sx / 2, y0 + altoEsta - sy / 2)
                if (t.tipo != K.VACIA) lista.add(Colocada(t, toque, dib, fi))
                if (t.esLetra) geo.poner(t.texto[0], dib.centerX(), dib.centerY())
                x += ancho
            }
        }
        // Las primeras y últimas teclas de cada fila llegan hasta el borde (más fácil de acertar)
        for (c in lista) {
            if (c.toque.left - izq < dp(4f)) c.toque.left = 0f
            if (der - c.toque.right < dp(4f)) c.toque.right = w
        }
        colocadas = lista.toTypedArray()
        pulsos.clear()   // las teclas se volvieron a armar: las animaciones viejas ya no sirven
        haloCache = null
        sepYActual = sepY
        geo.ancho = unidad
        geo.alto = altoFila
        sucio = true
    }

    // ---------- Ajuste a tu manera de tocar ----------

    private val desvio = HashMap<Char, FloatArray>()

    private fun aprenderDesvio(ch: Char, x: Float, y: Float, centro: RectF) {
        val v = desvio.getOrPut(ch) { FloatArray(2) }
        // Aprende dónde sueles tocar cada tecla (como el iPhone, sin que se vea distinto)
        val lim = anchoTecla * 0.3f
        v[0] = (v[0] * 0.96f + (x - centro.centerX()) * 0.04f).coerceIn(-lim, lim)
        v[1] = (v[1] * 0.96f + (y - centro.centerY()) * 0.04f).coerceIn(-lim, lim)
    }

    fun desviosTexto(): String = desvio.entries.joinToString(";") { "${it.key},${it.value[0] / anchoTecla},${it.value[1] / anchoTecla}" }

    fun leerDesvios(s: String?) {
        if (s.isNullOrEmpty()) return
        for (p in s.split(';')) {
            val q = p.split(',')
            if (q.size != 3 || q[0].length != 1) continue
            val a = q[1].toFloatOrNull() ?: continue; val b = q[2].toFloatOrNull() ?: continue
            pendientes[q[0][0]] = floatArrayOf(a, b)
        }
    }
    private val pendientes = HashMap<Char, FloatArray>()

    private fun buscar(x: Float, y: Float): Colocada? {
        if (pendientes.isNotEmpty() && anchoTecla > 1f) {
            for ((k, v) in pendientes) desvio[k] = floatArrayOf(v[0] * anchoTecla, v[1] * anchoTecla)
            pendientes.clear()
        }
        var dentro: Colocada? = null
        for (c in colocadas) if (c.toque.contains(x, y)) { dentro = c; break }
        if (dentro == null) {
            // Fuera de todas (borde de abajo o de arriba): la más cercana
            var mejor: Colocada? = null; var md = Float.MAX_VALUE
            for (c in colocadas) {
                val dx = maxOf(c.toque.left - x, 0f, x - c.toque.right)
                val dy = maxOf(c.toque.top - y, 0f, y - c.toque.bottom)
                val dd = dx * dx + dy * dy
                if (dd < md) { md = dd; mejor = c }
            }
            return if (md < (altoFila * altoFila)) mejor else null
        }
        if (!dentro.tecla.esLetra) return dentro
        // Entre letras: la más cercana a tu forma de tocar cada una
        var mejor = dentro
        var md = Float.MAX_VALUE
        for (c in colocadas) {
            if (!c.tecla.esLetra || abs(c.fila - dentro.fila) > 0) continue
            if (abs(c.dibujo.centerX() - x) > anchoTecla * 1.5f) continue
            val dv = desvio[c.tecla.texto[0]]
            val cx = c.dibujo.centerX() + (dv?.get(0) ?: 0f)
            val cy = c.dibujo.centerY() + (dv?.get(1) ?: 0f)
            val ex = x - cx; val ey = (y - cy) * 0.6f
            // Zona caliente: la letra que el cerebro espera atrae el toque un poco más
            // (hasta 30 % de ventaja), como hace el iPhone con su zona invisible
            val dd = (ex * ex + ey * ey) * (1f - 0.3f * (probables[c.tecla.texto[0].lowercaseChar()] ?: 0f))
            if (dd < md) { md = dd; mejor = c }
        }
        return mejor
    }

    /**
     * Letras que probablemente vienen (0..1), según lo que llevas escrito. Atraen un poco el
     * toque, sin verse (como la zona invisible del iPhone). Vacío fuera de una palabra.
     */
    var probables: Map<Char, Float> = emptyMap()

    // ---------- Dedos ----------

    private object Modo {
        const val NORMAL = 0; const val DESLIZAR = 1; const val CURSOR = 2; const val TRACKPAD = 3
        const val BORRAR_SEL = 4; const val OPCIONES = 5; const val CAMBIO_123 = 6; const val HECHO = 7
        const val PANEL = 8
    }

    private inner class Dedo(val id: Int) {
        var tecla: Colocada? = null
        /** La letra ya se escribió al tocar (no se vuelve a escribir al soltar) */
        var emitida = false
        /** Estaba en mayúscula al tocar (para la tilde, si se cambia) */
        var mayus = false
        /** Mantuvo la tecla emoji: el menú de teclado está abierto */
        var menu = false
        var x0 = 0f; var y0 = 0f; var x = 0f; var y = 0f
        var modo = Modo.NORMAL
        var acumX = 0f; var acumY = 0f
        var borrados = 0
        var palabras = 0
        val larga = Runnable { pulsacionLarga(this) }
        val repetir = object : Runnable {
            override fun run() {
                if (modo != Modo.NORMAL || tecla?.tecla?.tipo != K.BORRAR) return
                borrados++
                destelloBorrar(borrados)
                oyente?.borrarRepetido(borrados)
                oyente?.respuesta(Respuesta.Tipo.SUAVE)
                postDelayed(this, when { borrados < 4 -> 85L; borrados < 15 -> 60L; else -> 45L })
            }
        }
        // Trazo para deslizar
        var tx = FloatArray(256); var ty = FloatArray(256); var n = 0
        fun punto(px: Float, py: Float) {
            if (n == tx.size) { tx = tx.copyOf(n * 2); ty = ty.copyOf(n * 2) }
            tx[n] = px; ty[n] = py; n++
        }
    }

    private val dedos = HashMap<Int, Dedo>()
    private var presionadas = HashSet<Colocada>()
    // Al mover el cursor, las letras se desvanecen (y vuelven al soltar) en vez de desaparecer de golpe
    private var modoTrackpad = false; set(v) { if (field != v) { field = v; trackpadMs = SystemClock.uptimeMillis(); invalidate() } }
    private var trackpadMs = 0L
    private val FUNDIDO_CURSOR = 160f

    // Arreglos reutilizados (sin crear objetos nuevos en cada movimiento del dedo)
    private val posA = IntArray(2)
    private val posB = IntArray(2)

    private fun desplazamientoCapa(): Float {
        getLocationInWindow(posA); capa.getLocationInWindow(posB)
        return (posA[1] - posB[1]).toFloat()
    }

    private fun desplazamientoCapaX(): Float {
        getLocationInWindow(posA); capa.getLocationInWindow(posB)
        return (posA[0] - posB[0]).toFloat()
    }

    private val rectCapa = RectF()

    /** Rectángulo de la tecla en coordenadas de la capa (se reutiliza: la capa copia sus valores) */
    private fun rectEnCapa(r: RectF): RectF {
        getLocationInWindow(posA); capa.getLocationInWindow(posB)
        val dx = (posA[0] - posB[0]).toFloat(); val dy = (posA[1] - posB[1]).toFloat()
        rectCapa.set(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
        return rectCapa
    }

    private fun textoVisible(t: Tecla): String {
        if (!t.esLetra) return t.texto
        return if (shift > 0) t.texto.uppercase() else t.texto
    }

    private fun mostrarGlobo(c: Colocada) {
        val t = c.tecla
        if (config?.vistaPrevia == false || t.tipo != K.TEXTO || t.texto.length > 2) return
        if (distribucion.nombre == "num" || distribucion.nombre == "tel") return   // iOS: solo se aclara
        capa.mostrarGlobo(rectEnCapa(c.dibujo), textoVisible(t))
    }

    private fun tipoRespuesta(t: Tecla) = when (t.tipo) {
        K.ESPACIO -> Respuesta.Tipo.ESPACIO
        K.BORRAR -> Respuesta.Tipo.BORRAR
        K.SHIFT -> Respuesta.Tipo.MAYUS
        K.INTRO -> Respuesta.Tipo.INTRO
        K.TEXTO -> Respuesta.Tipo.LETRA
        else -> Respuesta.Tipo.FUNCION
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                bajar(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val dd = dedos[e.getPointerId(i)] ?: continue
                    // Puntos intermedios (el trazo queda fiel aunque el dedo vaya rápido)
                    if (dd.modo == Modo.DESLIZAR || dd.modo == Modo.NORMAL) {
                        for (h in 0 until e.historySize) {
                            mover(dd, e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.pointerCount)
                        }
                    }
                    mover(dd, e.getX(i), e.getY(i), e.pointerCount)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val dd = dedos.remove(e.getPointerId(i))
                if (dd != null) soltar(dd, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelarTodo()
        }
        return true
    }

    private fun bajar(id: Int, x: Float, y: Float) {
        // Moviendo el cursor con el espacio: tocar con otro dedo empieza a seleccionar
        // (y otro toque lo deja), como el trackpad del iPhone
        if (dedos.values.any { it.modo == Modo.CURSOR || it.modo == Modo.TRACKPAD }) {
            seleccionTrackpad = !seleccionTrackpad
            oyente?.seleccionTrackpad(seleccionTrackpad)
            oyente?.respuesta(Respuesta.Tipo.FUNCION)
            val otro = Dedo(id); otro.modo = Modo.HECHO
            dedos[id] = otro
            return
        }
        // Si otro dedo sigue en una tecla, esa tecla se escribe ya (dos pulgares rápidos). Antes
        // solo pasaba con letras: si el pulgar del ESPACIO se soltaba después de tocar la
        // siguiente letra, la letra salía antes que el espacio ("holaq ue"). Ahora el espacio
        // también se escribe en el momento en que el otro dedo baja, en el orden real.
        for (o in dedos.values) if (o.modo == Modo.NORMAL) {
            val tipo = o.tecla?.tecla?.tipo
            if (tipo == K.TEXTO) {
                removeCallbacks(o.larga)
                if (o.emitida) { o.tecla?.let { liberar(it) } } else emitir(o)
                o.modo = Modo.HECHO
            } else if (tipo == K.ESPACIO) {
                removeCallbacks(o.larga)
                oyente?.codigo(K.ESPACIO)
                o.tecla?.let { liberar(it) }
                o.modo = Modo.HECHO
            }
        }
        val dd = Dedo(id)
        dd.x0 = x; dd.y0 = y; dd.x = x; dd.y = y
        dedos[id] = dd
        if (!panelMano.isEmpty && panelMano.contains(x, y)) { dd.modo = Modo.PANEL; return }
        val c = buscar(x, y) ?: run { dd.modo = Modo.HECHO; return }
        // La vibración va primero: se siente justo al apoyar el dedo, antes de dibujar nada
        oyente?.respuesta(tipoRespuesta(c.tecla))
        dd.tecla = c
        presionar(c, x, y)
        val t = c.tecla
        val retardo = (config?.retardoLarga ?: 320).toLong()
        when (t.tipo) {
            K.TEXTO -> {
                mostrarGlobo(c)
                if (t.variantes.isNotEmpty()) postDelayed(dd.larga, retardo)
                if (t.esLetra && dd.n == 0) dd.punto(x, y)
                // Las letras se escriben en el instante en que tocas (no al levantar el dedo):
                // a dos pulgares se siente inmediato, como un teclado físico
                if (t.esLetra && config?.alTocar != false) {
                    dd.mayus = shift > 0
                    aprenderDesvio(t.texto[0], x, y, c.dibujo)
                    oyente?.tecla(t.texto, x, y, true)
                    dd.emitida = true
                }
            }
            K.BORRAR -> {
                oyente?.codigo(K.BORRAR)
                postDelayed(dd.repetir, 420L)
            }
            K.SHIFT -> oyente?.codigo(K.SHIFT)
            K.NUM, K.ABC, K.SIMB2 -> {
                oyente?.codigo(t.tipo)
                dd.modo = Modo.CAMBIO_123
                dd.tecla = null
                presionadas.clear()
                // La tecla del teclado nuevo bajo el dedo hace su pulso: de ahí nace la ola
                buscar(x, y)?.let { presionar(it, x, y); liberar(it); pulsos[it]?.nivel = 0.85f }
            }
            K.ESPACIO -> postDelayed(dd.larga, retardo + 180L)
            K.EMOJI -> postDelayed(dd.larga, retardo + 120L)
        }
        invalidate()
    }

    private fun mover(dd: Dedo, x: Float, y: Float, cuantos: Int) {
        val cfg = config
        val dx = x - dd.x0; val dy = y - dd.y0
        when (dd.modo) {
            Modo.NORMAL -> {
                val t = dd.tecla?.tecla ?: return
                when (t.tipo) {
                    K.TEXTO -> {
                        if (dd.emitida) return   // ya se escribió al tocar
                        if (t.esLetra) dd.punto(x, y)
                        val lejos = dx * dx + dy * dy > anchoTecla * anchoTecla * 0.5625f
                        if (lejos && t.esLetra && cuantos == 1 && cfg?.deslizar != false && distribucion.nombre == "abc") {
                            dd.modo = Modo.DESLIZAR
                            removeCallbacks(dd.larga)
                            capa.ocultarGlobo()
                            for (k in presionadas.toList()) liberar(k)
                            invalidate()
                        } else if (lejos && (cfg?.deslizar == false || !t.esLetra)) {
                            // Sin deslizar: la tecla que vale es la que está bajo el dedo
                            val c = buscar(x, y)
                            if (c != null && c != dd.tecla && c.tecla.tipo == K.TEXTO) {
                                dd.tecla?.let { liberar(it) }; dd.tecla = c; presionar(c, x, y)
                                removeCallbacks(dd.larga)
                                if (c.tecla.variantes.isNotEmpty()) postDelayed(dd.larga, (cfg?.retardoLarga ?: 320).toLong())
                                mostrarGlobo(c); invalidate()
                            }
                        }
                    }
                    K.ESPACIO -> if (cfg?.cursorEspacio != false && abs(dx) > anchoTecla * 0.45f) {
                        removeCallbacks(dd.larga)
                        dd.modo = Modo.CURSOR
                        dd.acumX = x
                        modoTrackpad = true
                        invalidate()
                    }
                    K.BORRAR -> if (cfg?.borrarDeslizando != false && dx < -anchoTecla * 0.55f && dd.borrados < 3) {
                        removeCallbacks(dd.repetir)
                        dd.modo = Modo.BORRAR_SEL
                        dd.palabras = 0
                    }
                }
            }
            Modo.DESLIZAR -> { dd.punto(x, y); dd.x = x; dd.y = y; invalidate() }
            Modo.CURSOR, Modo.TRACKPAD -> {
                val paso = anchoTecla * if (dd.modo == Modo.TRACKPAD) 0.38f else 0.5f
                var mov = ((x - dd.acumX) / paso).toInt()
                if (mov != 0) {
                    dd.acumX += mov * paso
                    oyente?.cursor(mov)
                    oyente?.respuesta(Respuesta.Tipo.SUAVE)
                }
                if (dd.modo == Modo.TRACKPAD) {
                    val pasoY = altoFila * 0.75f
                    mov = ((y - dd.acumY) / pasoY).toInt()
                    if (mov != 0) { dd.acumY += mov * pasoY; oyente?.cursorVertical(mov); oyente?.respuesta(Respuesta.Tipo.SUAVE) }
                }
                invalidate()   // el punto de luz sigue al dedo
            }
            Modo.BORRAR_SEL -> {
                val n = ((dd.x0 - x) / (anchoTecla * 0.9f)).toInt().coerceAtLeast(0)
                if (n != dd.palabras) {
                    dd.palabras = n
                    oyente?.seleccionBorrar(n)
                    oyente?.respuesta(Respuesta.Tipo.SUAVE)
                }
            }
            Modo.OPCIONES -> {
                val antes = capa.elegida()
                capa.moverSeleccion(x + desplazamientoCapaX(), y + desplazamientoCapa())
                if (capa.elegida() != antes) oyente?.respuesta(Respuesta.Tipo.SUAVE)
            }
            Modo.CAMBIO_123 -> {
                val c = buscar(x, y)
                if (c != dd.tecla && dx * dx + dy * dy > anchoTecla * anchoTecla * 0.16f) {
                    dd.tecla?.let { liberar(it) }
                    dd.tecla = c
                    if (c != null && c.tecla.tipo == K.TEXTO) { presionar(c, x, y); mostrarGlobo(c) } else capa.ocultarGlobo()
                    invalidate()
                }
            }
        }
        dd.x = x; dd.y = y
    }

    private fun pulsacionLarga(dd: Dedo) {
        val c = dd.tecla ?: return
        if (dd.modo != Modo.NORMAL) return
        val t = c.tecla
        when (t.tipo) {
            K.TEXTO -> if (t.variantes.isNotEmpty()) {
                dd.modo = Modo.OPCIONES
                capa.ocultarGlobo()
                // Si la letra ya se escribió al tocar, se quita: la reemplaza la que elijas
                if (dd.emitida) oyente?.deshacerLetra()
                val mayus = if (dd.emitida) dd.mayus else shift > 0
                val lista = if (mayus) t.variantes.map { if (it.length == 1) it.uppercase() else it } else t.variantes
                capa.mostrarOpciones(rectEnCapa(c.dibujo), lista, 0)
                oyente?.respuesta(Respuesta.Tipo.FUNCION)
            }
            // Como el globo terráqueo del iPhone: mantener emoji abre el menú del teclado
            K.EMOJI -> {
                dd.modo = Modo.OPCIONES
                dd.menu = true
                capa.mostrarOpciones(rectEnCapa(c.dibujo), MENU_TECLADO, 0, minOf(dp(76f), (width - dp(20f)) / MENU_TECLADO.size))
                oyente?.respuesta(Respuesta.Tipo.FUNCION)
            }
            K.ESPACIO -> {
                dd.modo = Modo.TRACKPAD
                dd.acumX = dd.x; dd.acumY = dd.y
                modoTrackpad = true
                oyente?.respuesta(Respuesta.Tipo.FUNCION)
                invalidate()
            }
        }
    }

    private fun emitir(dd: Dedo) {
        val c = dd.tecla ?: return
        val t = c.tecla
        if (t.tipo != K.TEXTO) return
        if (t.esLetra) aprenderDesvio(t.texto[0], dd.x0, dd.y0, c.dibujo)
        oyente?.tecla(t.texto, dd.x0, dd.y0, t.esLetra)
        liberar(c)
        capa.ocultarGlobo()
    }

    private fun soltar(dd: Dedo, x: Float, y: Float) {
        removeCallbacks(dd.larga); removeCallbacks(dd.repetir)
        val c = dd.tecla
        when (dd.modo) {
            Modo.NORMAL -> if (c != null) {
                when (c.tecla.tipo) {
                    K.TEXTO -> if (dd.emitida) capa.ocultarGlobo() else emitir(dd)
                    K.ESPACIO, K.INTRO, K.EMOJI -> oyente?.codigo(c.tecla.tipo)
                }
            }
            Modo.DESLIZAR -> {
                dd.punto(x, y)
                oyente?.deslizado(dd.tx, dd.ty, dd.n)
            }
            Modo.OPCIONES -> {
                val elegida = capa.elegida()
                capa.ocultarOpciones()
                if (dd.menu) { if (elegida != null) oyente?.menuTeclado(elegida) }
                else if (elegida != null) oyente?.tecla(elegida, dd.x0, dd.y0, false)
                else if (dd.emitida) dd.tecla?.tecla?.let { t ->
                    // No eligió ninguna: vuelve la letra que había tocado
                    oyente?.tecla(if (dd.mayus) t.texto.uppercase() else t.texto, dd.x0, dd.y0, true)
                }
            }
            Modo.BORRAR_SEL -> oyente?.terminarSeleccionBorrar(dd.palabras > 0)
            Modo.CAMBIO_123 -> if (c != null && c.tecla.tipo == K.TEXTO) {
                oyente?.tecla(c.tecla.texto, x, y, false)
                oyente?.volverLetras()
            }
            Modo.PANEL -> {
                if (botonExpandir.contains(x, y)) { oyente?.respuesta(Respuesta.Tipo.FUNCION); oyente?.unaMano(0) }
                else if (botonCambiar.contains(x, y)) { oyente?.respuesta(Respuesta.Tipo.FUNCION); oyente?.unaMano(1) }
            }
        }
        if (c != null) liberar(c)
        if (dd.modo == Modo.CURSOR || dd.modo == Modo.TRACKPAD) {
            modoTrackpad = dedos.values.any { it.modo == Modo.CURSOR || it.modo == Modo.TRACKPAD }
            if (!modoTrackpad && seleccionTrackpad) { seleccionTrackpad = false; oyente?.seleccionTrackpad(false) }
        }
        if (dedos.values.none { it.modo == Modo.NORMAL && it.tecla?.tecla?.tipo == K.TEXTO }) capa.ocultarGlobo()
        invalidate()
    }

    fun cancelarTodo() {
        for (dd in dedos.values) {
            removeCallbacks(dd.larga); removeCallbacks(dd.repetir)
            if (dd.modo == Modo.BORRAR_SEL) oyente?.terminarSeleccionBorrar(false)
        }
        dedos.clear(); presionadas.clear(); pulsos.clear(); modoTrackpad = false
        capa.ocultarGlobo(); capa.ocultarOpciones()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelarTodo()
        // La imagen guardada se suelta: el teclado escondido no ocupa memoria
        // Las imágenes NO se liberan a mano: Android todavía puede estar usándolas para
        // dibujar la pantalla (liberarlas antes dejaba el teclado en negro). Se sueltan solas.
        cacheFondo = null; cacheLetras = null; sucio = true
        anteriorFondo = null; anteriorLetras = null
        libres.clear()
    }

    // ---------- Dibujo ----------
    //
    // Para gastar lo mínimo de batería, las teclas en reposo se dibujan UNA vez en dos imágenes
    // guardadas: los cuerpos de las teclas y, aparte, sus letras e íconos. Separadas permiten
    // animar las letras sin redibujar el teclado (cambio abc ↔ 123) y hundir una tecla
    // entera copiando solo su pedacito.

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sombra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 400) }
    private val chica = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    private val pista = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val estela = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val camino = Path()

    private var cacheFondo: Bitmap? = null
    /** Las letras van en una imagen de solo transparencia (4 veces más liviana): el color se pone al dibujarla */
    private var cacheLetras: Bitmap? = null
    private var sucio = true
    private var letrasSucias = false
    private var shiftSucia = false
    private val borrador = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
    private val pinturaLetras = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F7.toInt() }
    private var sepYActual = 0f

    // Imágenes de la distribución que se va (abc ↔ 123) y las que quedan libres para reusar
    private var anteriorFondo: Bitmap? = null
    private var anteriorLetras: Bitmap? = null
    private val libres = ArrayList<Bitmap>(2)
    private var cambioMs = 0L
    private val pinturaAnterior = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0xFFF5F5F7.toInt() }
    private val pinturaNueva = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0xFFF5F5F7.toInt() }
    private val pinturaDestello = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(Tema.ROJO, android.graphics.PorterDuff.Mode.SRC_IN)
    }
    /** Cada fila tarda esto en cambiar... */
    private val DURACION_FILA = 200f
    private var cambioHaciaIzq = true
    /** ...y arranca este tanto después de la de abajo: una ola que nace en la tecla 123 */
    private val ESCALON = 12f   // de abajo hacia arriba, casi a la vez

    // ---------- Pulsación: la tecla se hunde, se enciende desde el dedo y vuelve con resorte ----------

    private fun tamanoPorUso(ch: Char): Float = when (ch.lowercaseChar()) {
        'e', 'a', 'o', 's', 'r', 'n', 'i' -> 1.035f
        'k', 'w', 'x', 'q' -> 0.97f
        else -> 1f
    }

    private companion object {
        val MENU_TECLADO = listOf("Ajustes", "Teclados", "◀ Mano", "Completo", "Mano ▶")
        val LETRAS_TEL = mapOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
            "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")
        // Mayúscula activa más sutil, como el iPhone oscuro: grafito claro con flecha blanca
        const val SHIFT_BLANCO = 0xFF56565B.toInt()
        const val SHIFT_BLANCO_APRETADO = 0xFF6A6A70.toInt()
        const val FLECHA_NEGRA = 0xFFFFFFFF.toInt()
    }

    private class Pulso(var t0: Long, var x: Float, var y: Float) {
        /** Cuándo se soltó (0 = sigue apretada) */
        var suelta = 0L
        /** Cuánto se había hundido al soltarla (para volver desde ahí, sin saltos) */
        var nivel = 0f
    }

    /** Teclas apretadas y las que están volviendo a su lugar */
    private val pulsos = HashMap<Colocada, Pulso>()
    // Como el iPhone: la tecla cambia de color al instante al tocarla y vuelve en un suspiro
    // Efecto 6 «botón elástico»: la tecla se encoge al tocarla y al soltar rebota como goma
    // Reacción instantánea: ya en el primer cuadro del toque la tecla se ve hundida
    private val HUNDIR = 40f     // se hunde en un instante
    private val VOLVER = 280f    // al soltar vuelve con un rebote elástico
    private val ENCOGE = 0.04f   // cuánto se achica al hundirse
    private val EXPANDE = 120f   // la almohadilla de color crece desde el centro
    private val APAGAR = 110f   // al soltar sube con un rebote cortito y el brillo se apaga
    private val BAJAR = 55f     // se hunde en un instante (se siente, pero no retrasa nada)
    private val EXPANDIR = 1f
    private val HUNDIMIENTO = 0f

    private val pinturaBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tapa = Paint().apply { color = 0xFF000000.toInt() }
    // Brillo blanco muy tenue que nace del dedo (sin teñir la tecla de rojo)
    private val luzShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(0x2EFFFFFF, 0x10FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    /** Línea de luz roja finita en la base de la tecla apretada: el único toque de color */
    private val filoRojo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val luz = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = luzShader }
    private val matrizLuz = android.graphics.Matrix()
    private val src = android.graphics.Rect()
    private val dst = RectF()
    private val rr = RectF()

    private fun presionar(c: Colocada, x: Float, y: Float) {
        presionadas.add(c)
        val ahora = SystemClock.uptimeMillis()
        val previo = pulsos[c]
        if (previo == null || previo.suelta != 0L) {
            val nuevo = Pulso(ahora, x, y)
            // Si todavía volvía de un toque anterior, se hunde desde donde iba
            if (previo != null) nuevo.t0 -= (HUNDIR * hundimiento(previo, ahora).coerceIn(0f, 1f)).toLong()
            pulsos[c] = nuevo
        }
        invalidate()
    }

    private fun liberar(c: Colocada) {
        presionadas.remove(c)
        val p = pulsos[c] ?: return
        if (p.suelta == 0L) {
            val ahora = SystemClock.uptimeMillis()
            // Desde lo que alcanzó a hundirse (un toque rapidísimo no salta)
            p.nivel = suave(((ahora - p.t0) / HUNDIR).coerceIn(0f, 1f)).coerceAtLeast(0.85f)
            p.suelta = ahora
        }
        invalidate()
    }

    /** 0 = en reposo, 1 = hundida del todo (al volver puede pasar un pelito a negativo: resorte) */
    private fun hundimiento(p: Pulso, ahora: Long): Float {
        if (p.suelta == 0L) return suave(((ahora - p.t0) / HUNDIR).coerceIn(0f, 1f))
        val r = ((ahora - p.suelta) / VOLVER).coerceIn(0f, 1f)
        // Resorte de goma más fluido: vuelve, se pasa una sola vez y se asienta suave
        return p.nivel * kotlin.math.exp(-4f * r) * kotlin.math.cos(r * 8.2f) * (1f - r)
    }

    /** Cuánta luz tiene la tecla (0..1) */
    private fun luzDe(p: Pulso, ahora: Long): Float {
        if (p.suelta == 0L) return 1f   // apretada: color de tecla apretada al instante
        val r = ((ahora - p.suelta) / APAGAR).coerceIn(0f, 1f)
        val u = 1f - r
        return p.nivel.coerceIn(0f, 1f) * u * u
    }

    private fun terminado(p: Pulso, ahora: Long) =
        p.suelta != 0L && ahora - p.suelta >= maxOf(VOLVER, APAGAR)

    private fun ensuciar() { sucio = true; invalidate() }

    private fun prepararPinturas() {
        borde.strokeWidth = dp(0.6f)
        borde.color = tema.bordeTecla
        brillo.strokeWidth = dp(0.8f)
        letra.textSize = altoFila * 0.46f
        chica.textSize = minOf(altoFila * 0.29f, dp(16f))
        pista.textSize = altoFila * 0.19f
        pista.color = tema.pista
    }

    private fun colorTecla(t: Tecla, apretada: Boolean): Int = when {
        // Como iOS: "buscar", "ir" y "enviar" van en una tecla azul con letras blancas
        // Diseño C: el intro va en grafito como las demás principales (sin azul)
        t.tipo == K.INTRO && introDestacado -> if (apretada) tema.funcionPresionada else tema.teclaFuncion
        // Como el iPhone: el enter es una tecla gris más y la mayúscula activa se pone blanca
        t.tipo == K.SHIFT && shift > 0 -> if (apretada) SHIFT_BLANCO_APRETADO else SHIFT_BLANCO
        t.funcion -> if (apretada) tema.funcionPresionada else tema.teclaFuncion
        apretada -> tema.teclaPresionada
        else -> tema.tecla
    }

    /**
     * Cuerpo de la tecla con un filo de luz finísimo arriba (como un reflejo): en negro AMOLED
     * le da relieve sin recargar. En el tema claro y en la mayúscula blanca va lisa.
     */
    private fun cuerpoConRelieve(c: Canvas, r: RectF, radio: Float, color: Int, blanca: Boolean) {
        relleno.shader = null
        if (!tema.claro && !blanca) {
            relleno.color = Tema.mezclar(color, 0xFFFFFFFF.toInt(), 0.82f)
            c.drawRoundRect(r, radio, radio, relleno)
            // Cuerpo con degradado vertical: un poco más clara arriba, como luz desde arriba
            relleno.shader = degradadoTecla(color, r.top + dp(0.9f), r.bottom)
            c.drawRoundRect(r.left, r.top + dp(0.9f), r.right, r.bottom, radio, radio, relleno)
            relleno.shader = null
        } else {
            relleno.color = color
            c.drawRoundRect(r, radio, radio, relleno)
        }
    }

    // Un degradado por color (se estira a cada tecla con una matriz: nada nuevo por cuadro)
    private val degradadosTecla = HashMap<Int, android.graphics.LinearGradient>()
    private val matrizTecla = android.graphics.Matrix()
    private fun degradadoTecla(color: Int, arriba: Float, abajo: Float): android.graphics.Shader {
        val g = degradadosTecla.getOrPut(color) {
            android.graphics.LinearGradient(0f, 0f, 0f, 1f, Tema.mezclar(color, 0xFFFFFFFF.toInt(), 0.9f), color,
                android.graphics.Shader.TileMode.CLAMP)
        }
        matrizTecla.setScale(1f, (abajo - arriba).coerceAtLeast(1f))
        matrizTecla.postTranslate(0f, arriba)
        g.setLocalMatrix(matrizTecla)
        return g
    }

    /** ¿Esta tecla va solo con contorno? (las letras y símbolos en el diseño C) */
    private fun deContorno(t: Tecla, blanca: Boolean) =
        tema.contorno && (!t.funcion || t.tipo == K.ESPACIO) && !blanca

    /** Cuerpo según el diseño: contorno fino para letras, relleno plano para las principales */
    private fun cuerpoTecla(c: Canvas, r: RectF, radio: Float, t: Tecla, blanca: Boolean) {
        if (deContorno(t, blanca)) {
            borde.color = tema.lineaTecla; borde.strokeWidth = dp(1f)
            val m = dp(0.5f)
            c.drawRoundRect(r.left + m, r.top + m, r.right - m, r.bottom - m, radio, radio, borde)
            borde.strokeWidth = dp(0.6f); borde.color = tema.bordeTecla
        } else if (tema.contorno) {
            relleno.shader = null; relleno.color = colorTecla(t, false)
            c.drawRoundRect(r, radio, radio, relleno)
        } else cuerpoConRelieve(c, r, radio, colorTecla(t, false), blanca)
    }

    /** Esquinas como las teclas de iOS: poco curvas */
    private fun radioDe(r: RectF) = minOf(dp(5.5f), r.height() * 0.13f)

    /**
     * Tecla plana como en iOS: el color de la tecla y una línea de sombra de 1 dp abajo.
     * Se dibuja una sola vez en la imagen guardada.
     */
    private fun dibujarCuerpo(c: Canvas, k: Colocada) {
        val r = k.dibujo
        val radio = radioDe(r)
        val blanca = k.tecla.tipo == K.SHIFT && shift > 0
        relleno.shader = null
        if (!tema.contorno) {
            relleno.color = tema.sombra
            c.drawRoundRect(r.left, r.top + dp(1.5f), r.right, r.bottom + dp(1.5f), radio, radio, relleno)
        }
        cuerpoTecla(c, r, radio, k.tecla, blanca)
        if (blanca) flechaNegra(c, k)
        if (k.tecla.tipo == K.INTRO && introDestacado) introBlanco(c, k)
    }

    /** Texto blanco de la tecla intro azul (va en la imagen de cuerpos, no en la de letras) */
    private fun introBlanco(c: Canvas, k: Colocada) {
        if (modoTrackpad) return
        val r = k.dibujo
        val txt = textoIntro
        if (txt == null) {
            // Sin texto: la flecha ↵ en blanco sobre el azul
            iconoVivo.color = 0xFFFFFFFF.toInt(); iconoVivo.alpha = 255
            Iconos.intro(c, r.centerX(), r.centerY(), minOf(r.height(), r.width()), iconoVivo)
            return
        }
        val previo = chica.color
        chica.color = 0xFFFFFFFF.toInt()
        c.drawText(txt, r.centerX(), r.centerY() + chica.textSize * 0.36f, chica)
        chica.color = previo
    }

    /** Flecha rellena negra de la mayúscula activa (con rayita debajo si está fija) */
    private fun flechaNegra(c: Canvas, k: Colocada) {
        if (modoTrackpad) return
        val r = k.dibujo
        icono.color = FLECHA_NEGRA
        Iconos.shift(c, r.centerX(), r.centerY(), minOf(r.height(), r.width()), icono, true, shift == 2)
        icono.style = Paint.Style.STROKE
        icono.color = tema.texto
    }

    /** Letra o ícono de la tecla (va a la imagen guardada de letras) */
    private fun dibujarEtiqueta(c: Canvas, k: Colocada) {
        val t = k.tecla
        val r = k.dibujo
        val cx = r.centerX(); val cy = r.centerY()
        val s = minOf(r.height(), r.width())
        icono.color = tema.texto
        when (t.tipo) {
            K.TEXTO -> {
                val txt = textoVisible(t)
                val p = if (txt.length > 1) chica else letra
                p.color = tema.texto
                val ajuste = if (t.esLetra && shift == 0) -p.textSize * 0.05f else 0f
                val letrasTel = if (distribucion.nombre == "num" && txt == "0") "" else if (distribucion.nombre == "tel" || distribucion.nombre == "num") LETRAS_TEL[txt] else null
                if (letrasTel != null || ((distribucion.nombre == "tel" || distribucion.nombre == "num") && txt.length == 1 && txt[0].isDigit())) {
                    // Teclado de teléfono como iOS: número grande y letras chiquitas debajo
                    val tam = letra.textSize
                    letra.textSize = minOf(r.height() * 0.5f, dp(28f))
                    val subeN = if (letrasTel != null && letrasTel.isNotEmpty()) r.height() * 0.1f else 0f
                    c.drawText(txt, cx, cy + letra.textSize * 0.36f - subeN, letra)
                    letra.textSize = tam
                    if (!letrasTel.isNullOrEmpty()) {
                        val tp = pista.textSize
                        pista.textSize = minOf(r.height() * 0.2f, dp(10.5f))
                        pista.letterSpacing = 0.18f
                        c.drawText(letrasTel, cx, cy + r.height() * 0.3f, pista)
                        pista.letterSpacing = 0f
                        pista.textSize = tp
                    }
                } else if (t.esLetra) {
                    // Las letras más usadas en español, apenas más grandes; las raras, apenas menores
                    val tam = p.textSize
                    p.textSize = tam * tamanoPorUso(t.texto[0])
                    c.drawText(txt, cx, cy + p.textSize * 0.36f + ajuste, p)
                    p.textSize = tam
                } else c.drawText(txt, cx, cy + p.textSize * 0.36f + ajuste, p)
                if (config?.pistas != false && distribucion.nombre == "abc" && t.pista != null)
                    c.drawText(t.pista, r.right - r.width() * 0.2f, r.top + pista.textSize * 1.05f, pista)
            }
            // Activa: la flecha negra va pintada sobre la tecla blanca (en la imagen de cuerpos)
            // Íconos de trazo más fino (estilo "luz")
            K.SHIFT -> if (shift == 0) Iconos.fino { Iconos.shift(c, cx, cy, s, icono, false, false) }
            K.BORRAR -> Iconos.fino { Iconos.borrar(c, cx, cy, s, icono, false) }
            K.EMOJI -> Iconos.fino { Iconos.cara(c, cx, cy, s, icono) }
            K.INTRO -> if (!introDestacado) {
                val txt = textoIntro
                if (txt != null) { chica.color = tema.texto; c.drawText(txt, cx, cy + chica.textSize * 0.36f, chica) }
                else Iconos.intro(c, cx, cy, s, icono)
            }
            K.ESPACIO -> {
                // Como en iOS: el espacio dice "espacio" (en el teclado numérico va limpio)
                if (distribucion.nombre != "tel" && distribucion.nombre != "num") {
                    chica.color = tema.texto
                    c.drawText(if (mostrarIdioma) "Español" else "espacio", cx, cy + chica.textSize * 0.36f, chica)
                }
            }
            else -> { chica.color = tema.texto; c.drawText(t.texto, cx, cy + chica.textSize * 0.36f, chica) }
        }
    }

    /**
     * Tecla apretada: se tapa la tecla quieta y se copia su pedacito de las imágenes guardadas,
     * achicado (se hunde). Encima, la tecla se aclara y una luz roja se abre desde la yema
     * del dedo; al soltar vuelve con un resorte corto y la luz se apaga.
     */
    // Profundidad al presionar: sombra interior arriba (la tecla se hunde en el vidrio) y la
    // letra baja un poquito. Todo con un degradado ya armado: casi no gasta.
    private val sombraHundida = android.graphics.LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0x73000000, 0x26000000, 0x00000000), floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP)
    private val pinturaHundida = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sombraHundida }
    // Onda de luz que nace del dedo (azul → violeta, se abre y se apaga en un cuarto de segundo)
    private val ondaShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(0x8C7AA8FF.toInt(), 0x4D5E5CE6, 0x005E5CE6), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    private val pinturaOnda = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = ondaShader }
    private val matrizOnda = android.graphics.Matrix()
    private val ONDA = 260f
    private val filoBajo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Tecla apretada, «retroiluminada»: una luz violeta se enciende por debajo de la tecla y
     * sube por dentro de ella; el vidrio se aclara. No se mueve: es rápida y elegante. La luz
     * es una imagen difuminada hecha una sola vez, así que casi no gasta.
     */
    // El degradado del fondo (el mismo del marco) visto desde esta vista
    private var degradado: android.graphics.Shader? = null
    private var degradadoDe = ""
    private fun degradadoFondo(): android.graphics.Shader? {
        if (tema.fondo == tema.fondoAbajo) return null
        var v: View = this
        var y = 0f
        while (v.tag != "marco") {
            y += v.top + v.translationY
            v = (v.parent as? View) ?: return null
        }
        val alto = v.height.toFloat()
        val clave = "${y.toInt()}:${alto.toInt()}:${tema.fondo}:${tema.fondoAbajo}"
        if (clave != degradadoDe) {
            degradado = android.graphics.LinearGradient(0f, -y, 0f, alto - y, tema.fondo, tema.fondoAbajo, android.graphics.Shader.TileMode.CLAMP)
            degradadoDe = clave
        }
        return degradado
    }

    /**
     * Tecla presionada con profundidad: se hunde sobre su borde de abajo (como un botón real),
     * una almohadilla de color crece desde el centro hasta llenarla y, al soltar, sube con un
     * resorte suave mientras el color se apaga. Todo con rectángulos: nada pesado por cuadro.
     */
    private fun dibujarPulso(c: Canvas, k: Colocada, p: Pulso, ahora: Long, fondo: Bitmap, letras: Bitmap) {
        val h = hundimiento(p, ahora)
        val l = luzDe(p, ahora)
        if (kotlin.math.abs(h) <= 0.01f && l <= 0.01f) return
        val t = k.tecla
        val r = k.dibujo
        val radio = radioDe(r)
        val blanca = t.tipo == K.SHIFT && shift > 0
        val prof = dp(1.5f)

        // 1. Se borra la tecla en su lugar (con su borde) con el mismo degradado del fondo
        //    y se deja el borde fijo abajo
        relleno.shader = degradadoFondo()
        relleno.color = tema.fondo
        c.drawRect(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + prof + 1f, relleno)
        relleno.shader = null
        if (!tema.contorno) {
            relleno.color = tema.sombra
            c.drawRoundRect(r.left, r.top + prof, r.right, r.bottom + prof, radio, radio, relleno)
        }

        c.save()
        // 2. Profundidad: la tecla se hunde derecho (baja 3 dp y se achica apenas, sin
        //    inclinarse); al soltar sube y rebota como goma
        c.translate(0f, HUNDE * h)
        val esc = 1f - ENCOGE * h
        c.scale(esc, esc, r.centerX(), r.centerY())
        cuerpoTecla(c, r, radio, t, blanca)
        // 3. La almohadilla: la tecla se llena al instante y el relleno se apaga al soltar
        if (l > 0.01f) {
            relleno.shader = null
            relleno.color = colorTecla(t, true)
            relleno.alpha = (255 * l).toInt().coerceIn(0, 255)
            c.drawRoundRect(r, radio, radio, relleno)
            relleno.alpha = 255
        }
        // 3b. Sombra interior arriba (se ve hundida, como un hueco) y un filo de luz abajo
        val hondo = maxOf(h, 0f).coerceAtMost(1f)
        if (hondo > 0.02f && !blanca) {
            matrizSombra.setScale(1f, r.height()); matrizSombra.postTranslate(0f, r.top)
            sombraInterior.setLocalMatrix(matrizSombra)
            pinturaSombraInt.alpha = (255 * hondo).toInt().coerceIn(0, 255)
            c.drawRoundRect(r, radio, radio, pinturaSombraInt)
            filoLuz.alpha = (70 * hondo).toInt().coerceIn(0, 255)
            c.drawLine(r.left + radio, r.bottom - dp(0.8f), r.right - radio, r.bottom - dp(0.8f), filoLuz)
        }
        // 4. La letra o el ícono encima, también hundidos (no mientras mueves el cursor)
        if (modoTrackpad) { }
        else if (blanca) flechaNegra(c, k)
        else if ((t.tipo == K.BORRAR || t.tipo == K.EMOJI) && p.suelta == 0L) {
            // Mientras la mantienes, el ícono se ve relleno (como los íconos del iPhone al elegirlos)
            val sz = minOf(r.height(), r.width())
            iconoVivo.color = tema.texto; iconoVivo.alpha = 255
            if (t.tipo == K.BORRAR) Iconos.borrar(c, r.centerX(), r.centerY(), sz, iconoVivo, true)
            else Iconos.caraLlena(c, r.centerX(), r.centerY(), sz, iconoVivo, colorTecla(t, false))
            iconoVivo.style = Paint.Style.STROKE
        }
        else if (t.tipo == K.INTRO && introDestacado) introBlanco(c, k)
        else {
            src.set(r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0),
                (r.right + 1).toInt().coerceAtMost(letras.width), (r.bottom + 1).toInt().coerceAtMost(letras.height))
            dst.set(src)
            pinturaLetras.color = tema.texto
            c.drawBitmap(letras, src, dst, pinturaLetras)
        }
        c.restore()
    }

    // Profundidad al presionar: cuánto baja la tecla
    private val HUNDE by lazy { dp(3f) }
    private val sombraInterior = android.graphics.LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0xB0000000.toInt(), 0x40000000, 0x00000000), floatArrayOf(0f, 0.35f, 0.7f), android.graphics.Shader.TileMode.CLAMP)
    private val matrizSombra = android.graphics.Matrix()
    private val pinturaSombraInt = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sombraInterior }
    private val filoLuz by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1f); strokeCap = Paint.Cap.ROUND } }

    // Luz violeta por dentro, desde la base de la tecla hacia arriba
    private val luzInterior = android.graphics.LinearGradient(0f, 1f, 0f, 0f,
        intArrayOf(0x995E5CE6.toInt(), 0x407A8CFF, 0x007A8CFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
    private val pinturaInterior = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = luzInterior }
    private val pinturaHalo = Paint(Paint.FILTER_BITMAP_FLAG)
    private var haloCache: Bitmap? = null

    /** El halo violeta difuminado, hecho una sola vez (con el tamaño de una tecla) */
    private fun luzDebajo(): Bitmap? {
        haloCache?.let { if (!it.isRecycled) return it }
        val tw = colocadas.firstOrNull { it.tecla.esLetra }?.dibujo ?: return null
        val ex = dp(12f)
        val w = (tw.width() + ex * 2).toInt().coerceAtLeast(8); val h = (tw.height() + ex * 2).toInt().coerceAtLeast(8)
        return try {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xD95E5CE6.toInt()
                maskFilter = android.graphics.BlurMaskFilter(dp(9f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            val rad = radioDe(tw)
            cv.drawRoundRect(ex, ex, w - ex, h - ex, rad, rad, p)
            haloCache = b; b
        } catch (e: Throwable) { Registro.anotar("halo", e); null }
    }

    // Cursor de vidrio
    private val veloCursor = Paint().apply { color = 0x66000000 }
    private val puntoShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0xE69DB8FF.toInt(), 0x995E5CE6.toInt(), 0x005E5CE6), floatArrayOf(0f, 0.22f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
    private val puntoCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = puntoShader }

    // ---------- Luces: borrado, espacio que respira y bloqueo de mayúsculas ----------

    // Un solo degradado radial de radio 1; se estira con una matriz (sin crear objetos al dibujar)
    private val violetaShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(0xE69A98FF.toInt(), 0x805E5CE6.toInt(), 0x005E5CE6), floatArrayOf(0f, 0.5f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    private val violetaPintura = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = violetaShader }
    private val matrizVioleta = android.graphics.Matrix()
    private val aroMayus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var borrarMs = 0L
    private var borrarDura = 0f
    private var borrarFuerza = 0f
    private var espacioMs = 0L
    private val RESPIRA = 520f

    /** Cada letra que se va al mantener borrar deja un destello; más rápido, más intenso */
    private fun destelloBorrar(n: Int) {
        borrarMs = SystemClock.uptimeMillis()
        borrarDura = (80f - minOf(n, 20) * 1.7f)
        borrarFuerza = 0.85f
        invalidate()
    }

    /** La barra espaciadora hace un pulso violeta suave (punto automático o mayúscula sola) */
    // ---------- La tecla borrar "se come" la letra ----------
    private var comida: String? = null
    private var comidaMs = 0L
    private val COMER = 200f
    private val pinturaComida = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** La letra borrada aparece arriba de la tecla borrar y se encoge hacia ella */
    fun comerLetra(ch: String) {
        if (ch.isBlank() || modoTrackpad) return
        comida = ch; comidaMs = SystemClock.uptimeMillis(); invalidate()
    }

    private fun dibujarComida(c: Canvas, ahora: Long) {
        val ch = comida ?: return
        val t = (ahora - comidaMs) / COMER
        if (t >= 1f) { comida = null; return }
        val k = teclaDe(K.BORRAR) ?: return
        val r = k.dibujo
        val e = suave(t)
        // Sale de arriba a la izquierda de la tecla y entra en ella encogiéndose
        val x0 = r.centerX() - r.width() * 0.35f; val y0 = r.top - r.height() * 0.55f
        val x = x0 + (r.centerX() - x0) * e; val y = y0 + (r.centerY() - y0) * e
        pinturaComida.textSize = letra.textSize * (1f - 0.75f * e)
        pinturaComida.color = tema.texto
        pinturaComida.alpha = (255 * (1f - e * e)).toInt().coerceIn(0, 255)
        c.drawText(ch, x, y + pinturaComida.textSize * 0.36f, pinturaComida)
        postInvalidateOnAnimation()
    }

    /** Al terminar una frase, la fila de abajo hace un pulso de luz muy tenue */
    fun respirarEspacio() { respiroMs = SystemClock.uptimeMillis(); invalidate() }
    private var respiroMs = 0L
    private val RESPIRO = 460f

    private var shiftAntes = 0
    private var shiftCambioMs = 0L
    private val FUNDIDO_SHIFT = 190f
    private val iconoVivo = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Dibuja encima la tecla mayúscula como estaba antes, desvaneciéndose */
    private fun dibujarFundidoShift(c: Canvas, ahora: Long) {
        if (shiftCambioMs == 0L || modoTrackpad) return
        val t = (ahora - shiftCambioMs) / FUNDIDO_SHIFT
        if (t >= 1f) { shiftCambioMs = 0L; return }
        val k = teclaDe(K.SHIFT) ?: return
        val r = k.dibujo
        val radio = radioDe(r)
        val a = ((1f - suave(t)) * 255).toInt().coerceIn(0, 255)
        relleno.shader = null
        relleno.color = if (shiftAntes > 0) SHIFT_BLANCO else tema.teclaFuncion
        relleno.alpha = a
        c.drawRoundRect(r, radio, radio, relleno)
        relleno.alpha = 255
        iconoVivo.color = if (shiftAntes > 0) FLECHA_NEGRA else tema.texto
        iconoVivo.alpha = a
        iconoVivo.style = Paint.Style.STROKE
        Iconos.shift(c, r.centerX(), r.centerY(), minOf(r.height(), r.width()), iconoVivo, shiftAntes > 0, shiftAntes == 2)
        postInvalidateOnAnimation()
    }

    /** Pulso tenue en la fila de abajo (punto final o mayúscula de frase nueva) */
    private fun dibujarRespiro(c: Canvas, ahora: Long) {
        if (respiroMs == 0L || modoTrackpad) return
        val t = (ahora - respiroMs) / RESPIRO
        if (t >= 1f) { respiroMs = 0L; return }
        val f = kotlin.math.sin(Math.PI.toFloat() * t)
        val ultima = colocadas.maxOfOrNull { it.fila } ?: return
        relleno.shader = null
        relleno.color = tema.velo((0x1C * f).toInt())
        for (k in colocadas) if (k.fila == ultima) {
            val radio = radioDe(k.dibujo)
            if (deContorno(k.tecla, false)) {
                // Diseño C: en las teclas de contorno solo se ilumina el borde
                borde.color = tema.velo((0x55 * f).toInt()); borde.strokeWidth = dp(1f)
                val m = dp(0.5f)
                c.drawRoundRect(k.dibujo.left + m, k.dibujo.top + m, k.dibujo.right - m, k.dibujo.bottom - m, radio, radio, borde)
                borde.strokeWidth = dp(0.6f); borde.color = tema.bordeTecla
            } else c.drawRoundRect(k.dibujo, radio, radio, relleno)
        }
        postInvalidateOnAnimation()
    }

    private fun teclaDe(tipo: Int): Colocada? { for (k in colocadas) if (k.tecla.tipo == tipo) return k; return null }

    /** Centro de la tecla de ese tipo (para que el panel de emojis nazca desde su botón) */
    fun centroDe(tipo: Int): android.graphics.PointF? = teclaDe(tipo)?.dibujo?.let { android.graphics.PointF(it.centerX(), it.centerY()) }

    private fun luzVioleta(c: Canvas, r: RectF, fuerza: Float, ancho: Float, alto: Float) {
        if (fuerza <= 0.01f) return
        matrizVioleta.setScale(ancho, alto)
        matrizVioleta.postTranslate(r.centerX(), r.centerY())
        violetaShader.setLocalMatrix(matrizVioleta)
        violetaPintura.alpha = (255 * fuerza).toInt().coerceIn(0, 255)
        c.drawRect(r.centerX() - ancho, r.centerY() - alto, r.centerX() + ancho, r.centerY() + alto, violetaPintura)
    }

    /**
     * Borrar con ritmo: manteniéndola, la tecla parpadea (vuelve a su gris un instante) con
     * cada letra que se va, y el parpadeo se acorta a medida que el borrado acelera.
     */
    private fun dibujarLuces(c: Canvas, ahora: Long) {
        if (modoTrackpad || borrarMs == 0L) return
        val t = (ahora - borrarMs) / borrarDura
        if (t >= 1f) { borrarMs = 0L; return }
        val k = teclaDe(K.BORRAR) ?: return
        val r = k.dibujo
        val radio = radioDe(r)
        val u = 1f - t
        relleno.shader = null
        relleno.color = tema.teclaFuncion
        relleno.alpha = (255 * borrarFuerza * u).toInt().coerceIn(0, 255)
        c.drawRoundRect(r, radio, radio, relleno)
        relleno.alpha = 255
        // El ícono encima (copiado de la imagen de letras)
        val letras = cacheLetras
        if (letras != null && !letras.isRecycled) {
            src.set(r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0),
                (r.right + 1).toInt().coerceAtMost(letras.width), (r.bottom + 1).toInt().coerceAtMost(letras.height))
            dst.set(src)
            pinturaLetras.color = tema.texto
            c.drawBitmap(letras, src, dst, pinturaLetras)
        }
        postInvalidateOnAnimation()
    }

    // ---------- Rebote del cursor al llegar al inicio o al final del texto ----------
    private var reboteMs = 0L
    private var reboteLado = 0f
    private val REBOTE = 260f

    /** [lado] -1 = chocó con el inicio, 1 = con el final */
    fun rebotar(lado: Int) { reboteMs = SystemClock.uptimeMillis(); reboteLado = lado.toFloat(); invalidate() }

    private fun desplazamientoRebote(ahora: Long): Float {
        if (reboteMs == 0L) return 0f
        val t = (ahora - reboteMs) / REBOTE
        if (t >= 1f) { reboteMs = 0L; return 0f }
        postInvalidateOnAnimation()
        // Choca, se pasa un poquito y vuelve (resorte amortiguado)
        return reboteLado * dp(7f) * kotlin.math.sin(Math.PI.toFloat() * t * 1.5f) * (1f - t) * (1f - t)
    }

    // Entrada en cascada al abrir el teclado
    private var entradaMs = 0L
    private val ENTRADA_FILA = 170f
    private val ENTRADA_ESCALON = 35f

    /** En iOS el teclado solo sube con la ventana: sin cascada */
    fun animarEntrada() {
        // Como el iPhone: al abrir, el espacio dice el idioma un momento y luego "espacio"
        mostrarIdioma = true; letrasSucias = true; invalidate()
        removeCallbacks(quitarIdioma)
        postDelayed(quitarIdioma, 1300L)
    }
    private var mostrarIdioma = false
    private val quitarIdioma = Runnable { mostrarIdioma = false; letrasSucias = true; invalidate() }

    /** Las filas aparecen una tras otra, subiendo un poquito */
    private fun dibujarEntrada(c: Canvas, t: Float, filas: Int, fondo: Bitmap, letras: Bitmap) {
        val w = width.toFloat()
        val arriba = ARRIBA
        val sube = dp(18f)
        pinturaLetras.color = tema.texto
        for (k in 0 until filas) {
            val y0 = arriba + k * altoFila
            val top = if (k == 0) 0f else y0
            val bot = if (k == filas - 1) height.toFloat() else y0 + altoFila
            val f = ((t - k * ENTRADA_ESCALON) / ENTRADA_FILA).coerceIn(0f, 1f)
            if (f <= 0f) continue
            val e = suave(f)
            // Sin capas extra: cada fila se copia con su transparencia (mucho más barato)
            val a = (255 * e).toInt().coerceIn(0, 255)
            c.save()
            c.translate(0f, sube * (1f - e))
            c.clipRect(0f, top, w, bot)
            pinturaNueva.alpha = a
            c.drawBitmap(fondo, 0f, 0f, pinturaNueva)
            pinturaLetras.alpha = a
            c.drawBitmap(letras, 0f, 0f, pinturaLetras)
            pinturaLetras.alpha = 255
            c.restore()
        }
    }

    /** Suavizado: rápido al principio y lento al final */
    private fun suave(t: Float): Float { val u = 1f - t; return 1f - u * u * u }

    /** Llega, se pasa apenitas y se asienta (para las letras nuevas al cambiar de teclado) */
    private fun asentar(t: Float): Float {
        val c1 = 1.15f; val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private fun guardarLibre(b: Bitmap?) {
        if (b != null && !b.isRecycled && libres.size < 4 && libres.none { it === b }) libres.add(b)
    }

    private fun imagen(alto: Int, tipo: Bitmap.Config): Bitmap {
        val it = libres.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.config != tipo) continue
            it.remove()
            if (!b.isRecycled && b.width == width && b.height >= height) { b.eraseColor(0); return b }
        }
        return Bitmap.createBitmap(width, alto, tipo)
    }

    /** Dibuja todas las letras e íconos en su imagen */
    private fun dibujarLetras(letras: Bitmap) {
        letras.eraseColor(0)
        val cl = Canvas(letras)
        for (k in colocadas) dibujarEtiqueta(cl, k)
        if (!panelMano.isEmpty) {
            icono.color = tema.textoSuave
            Iconos.expandir(cl, botonExpandir.centerX(), botonExpandir.centerY(), botonExpandir.width(), icono)
            Iconos.flecha(cl, botonCambiar.centerX(), botonCambiar.centerY(), botonCambiar.width(), icono, if (unaMano == 1) 180f else 0f)
        }
    }

    // Un error al dibujar se anota y no cierra el teclado
    override fun onDraw(c: Canvas) {
        val n = c.save()
        try { dibujarTodo(c) } catch (e: Throwable) { Registro.anotar("dibujo teclado", e) } finally { c.restoreToCount(n) }
    }

    /** Ya se pidió preparar los globos para esta distribución */
    private var globosPedidos = false

    /**
     * Prepara en ratos libres los globos de todas las letras (minúscula y mayúscula): así el
     * primer toque de cada tecla no tiene que dibujar la sombra difuminada (ese era un tironcito).
     */
    private fun pedirGlobos() {
        if (globosPedidos || config?.vistaPrevia == false) return
        globosPedidos = true
        post {
            val lista = ArrayList<Pair<RectF, String>>()
            for (k in colocadas) {
                val t = k.tecla
                if (t.tipo != K.TEXTO || t.texto.length > 2) continue
                val r = rectEnCapa(k.dibujo)
                lista.add(r to t.texto)
            }
            capa.precalentarGlobos(lista)
        }
    }

    private fun dibujarTodo(c: Canvas) {
        if (width <= 0 || height <= 0) return
        pedirGlobos()
        val f0 = cacheFondo
        val l0 = cacheLetras
        if (f0 == null || f0.isRecycled || f0.width != width || f0.height < height ||
            l0 == null || l0.isRecycled || l0.width != width || l0.height < height) {
            // Con margen de alto: al ajustar el tamaño no se crea una imagen nueva en cada
            // movimiento del dedo (eso llenaba la memoria y podía dejar el teclado en negro)
            val alto = (height * 1.3f).toInt()
            cacheFondo = imagen(alto, Bitmap.Config.ARGB_8888); cacheLetras = imagen(alto, Bitmap.Config.ALPHA_8)
            sucio = true
        }
        val fondo: Bitmap = cacheFondo ?: return
        val letras: Bitmap = cacheLetras ?: return
        if (sucio) {
            fondo.eraseColor(0)
            val cf = Canvas(fondo)
            prepararPinturas()
            for (k in colocadas) dibujarCuerpo(cf, k)
            dibujarLetras(letras)
            sucio = false; letrasSucias = false; shiftSucia = false
        } else {
            if (shiftSucia) {
                // Solo la tecla shift cambia de color: se borra su lugar y se vuelve a pintar
                val cf = Canvas(fondo)
                prepararPinturas()
                for (k in colocadas) if (k.tecla.tipo == K.SHIFT) {
                    val r = k.dibujo
                    cf.drawRect(r.left - dp(2f), r.top - dp(2f), r.right + dp(2f), r.bottom + dp(3f), borrador)
                    dibujarCuerpo(cf, k)
                }
                shiftSucia = false
            }
            if (letrasSucias) { prepararPinturas(); dibujarLetras(letras); letrasSucias = false }
        }

        val ahora = SystemClock.uptimeMillis()
        // Rebote del cursor: todo el teclado se mueve apenas hacia el lado del choque
        val rebote = desplazamientoRebote(ahora)
        if (rebote != 0f) c.translate(rebote, 0f)
        val viejoF = anteriorFondo; val viejoL = anteriorLetras
        val filas = distribucion.filas.size
        var cambiando = false
        if (viejoF != null && viejoL != null) {
            val fin = DURACION_FILA + ESCALON * (filas - 1)
            if (ahora - cambioMs >= fin || viejoF.isRecycled || viejoL.isRecycled || viejoF.width != width) {
                anteriorFondo = null; anteriorLetras = null
                guardarLibre(viejoF); guardarLibre(viejoL)
            } else cambiando = true
        }
        val tEntrada = (ahora - entradaMs).toFloat()
        if (tEntrada < ENTRADA_FILA + ENTRADA_ESCALON * (filas - 1)) {
            dibujarEntrada(c, tEntrada, filas, fondo, letras)
            postInvalidateOnAnimation()
        } else if (cambiando && viejoF != null && viejoL != null) {
            dibujarCambio(c, (ahora - cambioMs).toFloat(), filas, fondo, letras, viejoF, viejoL)
            postInvalidateOnAnimation()
        } else {
            c.drawBitmap(fondo, 0f, 0f, null)
            pinturaLetras.color = tema.texto
            // Cuánto se ven las letras: se apagan al mover el cursor y vuelven al soltar
            val tc = ((ahora - trackpadMs) / FUNDIDO_CURSOR).coerceIn(0f, 1f)
            val verLetras = if (modoTrackpad) 1f - suave(tc) else suave(tc)
            if (tc < 1f) postInvalidateOnAnimation()
            val viejas = letrasViejas
            val tm = (ahora - letrasViejasMs) / FUNDIDO_MAYUS
            if (viejas != null && tm < 1f && !viejas.isRecycled && viejas.width == letras.width) {
                // Fundido minúsculas ↔ mayúsculas
                val e = suave(tm.coerceIn(0f, 1f))
                pinturaLetras.alpha = (255 * (1f - e) * verLetras).toInt().coerceIn(0, 255)
                c.drawBitmap(viejas, 0f, 0f, pinturaLetras)
                pinturaLetras.alpha = (255 * e * verLetras).toInt().coerceIn(0, 255)
                c.drawBitmap(letras, 0f, 0f, pinturaLetras)
                pinturaLetras.alpha = 255
                postInvalidateOnAnimation()
            } else {
                if (viejas != null) letrasViejas = null
                pinturaLetras.alpha = (255 * verLetras).toInt().coerceIn(0, 255)
                if (verLetras > 0.01f) c.drawBitmap(letras, 0f, 0f, pinturaLetras)
                pinturaLetras.alpha = 255
            }
        }

        // Teclas apretadas y las que vuelven a su lugar
        if (pulsos.isNotEmpty()) {
            val it = pulsos.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (terminado(e.value, ahora)) { it.remove(); continue }
                dibujarPulso(c, e.key, e.value, ahora, fondo, letras)
            }
            if (pulsos.values.any { it.suelta != 0L || ahora - it.t0 < maxOf(HUNDIR, EXPANDE) }) postInvalidateOnAnimation()
        }


        dibujarLuces(c, ahora)
        dibujarFundidoShift(c, ahora)
        dibujarRespiro(c, ahora)
        dibujarComida(c, ahora)

        // Cursor de vidrio: al mover el cursor con el espacio, el teclado queda como un panel
        // de vidrio vacío y un punto de luz sigue tu dedo
        if (modoTrackpad) {
            val dd: Dedo? = null   // iOS: las letras se ocultan y nada más
            if (dd != null) {
                matrizOnda.setScale(dp(22f), dp(22f))
                matrizOnda.postTranslate(dd.x, dd.y)
                puntoShader.setLocalMatrix(matrizOnda)
                c.drawCircle(dd.x, dd.y, dp(22f), puntoCursor)
            }
        }

        // Estela al deslizar (se afina hacia el final, como una pincelada)
        if (config?.estela != false) for (dd in dedos.values) if (dd.modo == Modo.DESLIZAR && dd.n > 1) {
            val desde = maxOf(0, dd.n - 60)
            val tramos = 4
            val largo = dd.n - desde
            for (tramo in 0 until tramos) {
                val a = desde + largo * tramo / tramos
                val b = minOf(dd.n - 1, desde + largo * (tramo + 1) / tramos)
                if (b <= a) continue
                camino.reset()
                camino.moveTo(dd.tx[a], dd.ty[a])
                for (i in a + 1..b) camino.lineTo(dd.tx[i], dd.ty[i])
                val f = (tramo + 1f) / tramos
                estela.color = Tema.mezclar(tema.estela, 0x00000000, 0.35f + 0.55f * f)
                estela.strokeWidth = dp(2f + 3.5f * f)
                c.drawPath(camino, estela)
            }
        }
    }

    /**
     * Cambio abc ↔ 123 fila por fila, como una ola que nace en la tecla 123 (abajo) y sube:
     * en cada tecla la letra vieja sube y se apaga mientras la nueva entra desde abajo y se
     * asienta (como un rodillo), y un destello rojo muy tenue recorre las teclas al pasar.
     */
    private fun dibujarCambio(c: Canvas, t: Float, filas: Int, fondo: Bitmap, letras: Bitmap, viejoF: Bitmap, viejoL: Bitmap) {
        val w = width.toFloat()
        val arriba = ARRIBA
        val sube = altoFila * 0.08f   // las letras nuevas suben apenas mientras aparecen
        val medio = sepYActual / 2f
        for (k in 0 until filas) {
            val y0 = arriba + k * altoFila
            val top = if (k == 0) 0f else y0
            val bot = if (k == filas - 1) height.toFloat() else y0 + altoFila
            val retardo = (filas - 1 - k) * ESCALON
            val f = ((t - retardo) / DURACION_FILA).coerceIn(0f, 1f)
            val e = suave(f)
            c.save()
            c.clipRect(0f, top, w, bot)
            // Cuerpos: la forma nueva aparece sobre la vieja (si cambian de ancho, se funden)
            // Las teclas no se cruzan (dos capas de vidrio translúcido juntas se veían más
            // claras un instante): quedan fijas y solo cambian las letras
            c.drawBitmap(fondo, 0f, 0f, null)
            // Destello que recorre las filas
            val destello = kotlin.math.sin(Math.PI * f).toFloat()
            if (false && destello > 0.02f) {
                pinturaDestello.alpha = (destello * 46).toInt()
                c.drawBitmap(fondo, 0f, 0f, pinturaDestello)
            }
            // Letras: pasan como una página (las viejas salen por un lado y las nuevas entran
            // por el otro), recortadas al alto de las teclas de la fila
            c.clipRect(0f, y0 + medio, w, y0 + altoFila - medio)
            // Fundido suave y rápido en su lugar (nada se mueve: no estorba si sigues escribiendo)
            pinturaAnterior.color = tema.texto; pinturaNueva.color = tema.texto
            if (f < 1f) {
                pinturaAnterior.alpha = (255 * (1f - e)).toInt().coerceIn(0, 255)
                c.drawBitmap(viejoL, 0f, 0f, pinturaAnterior)
            }
            if (f > 0f) {
                // Las nuevas llegan con un resorte: suben apenas, se pasan un pelo y se asientan
                val res = 1f - kotlin.math.exp(-5f * f) * kotlin.math.cos(7f * f) * (1f - f)
                pinturaNueva.alpha = (255 * e).toInt().coerceIn(0, 255)
                c.drawBitmap(letras, 0f, sube * (1f - res), pinturaNueva)
            }
            c.restore()
        }
    }

}
