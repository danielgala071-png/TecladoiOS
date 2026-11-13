package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

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
    }

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; ensuciar() }
    var config: Config? = null; set(v) { field = v; ensuciar() }
    val geo = Cerebro.geo

    var distribucion: Distribucion = Distribuciones.crear(Distribuciones.LETRAS, 0, false)
        set(v) { field = v; reacomodar(); ensuciar() }

    /** 0 minúsculas, 1 mayúscula, 2 bloqueo */
    var shift = 0; set(v) { if (field != v) { field = v; ensuciar() } }
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
    private val panelMano = RectF()
    private val botonExpandir = RectF()
    private val botonCambiar = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val filas = distribucion.filas.size
        val escala = (alturaForzada ?: config?.altura ?: 100) / 100f
        val base = if (filas >= 5) 50f else 54f
        val h = (dp(base) * filas * escala + dp(8f) + dp(6f)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reacomodar()
    }

    private fun reacomodar() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val arriba = dp(8f); val abajo = dp(6f)
        val lado = dp(3f)
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
        altoFila = (h - arriba - abajo) / filas.size
        val unidad = (der - izq) / distribucion.unidades
        anchoTecla = unidad
        val sepX = dp(5.5f) * if (unaMano != 0) 0.8f else 1f
        val sepY = minOf(dp(11f), altoFila * 0.2f)
        val lista = ArrayList<Colocada>(48)
        for ((fi, fila) in filas.withIndex()) {
            var x = izq + (distribucion.unidades - fila.unidades) / 2 * unidad + fila.sangria * unidad
            val y0 = arriba + fi * altoFila
            for (t in fila.teclas) {
                val ancho = t.ancho * unidad
                val toque = RectF(x, y0, x + ancho, y0 + altoFila)
                val dib = RectF(x + sepX / 2, y0 + sepY / 2, x + ancho - sepX / 2, y0 + altoFila - sepY / 2)
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
        geo.ancho = unidad
        geo.alto = altoFila
        sucio = true
    }

    // ---------- Ajuste a tu manera de tocar ----------

    private val desvio = HashMap<Char, FloatArray>()

    private fun aprenderDesvio(ch: Char, x: Float, y: Float, centro: RectF) {
        val v = desvio.getOrPut(ch) { FloatArray(2) }
        val lim = anchoTecla * 0.22f
        v[0] = (v[0] * 0.97f + (x - centro.centerX()) * 0.03f).coerceIn(-lim, lim)
        v[1] = (v[1] * 0.97f + (y - centro.centerY()) * 0.03f).coerceIn(-lim, lim)
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
            val dd = hypot(x - cx, (y - cy) * 0.6f)
            if (dd < md) { md = dd; mejor = c }
        }
        return mejor
    }

    // ---------- Dedos ----------

    private object Modo {
        const val NORMAL = 0; const val DESLIZAR = 1; const val CURSOR = 2; const val TRACKPAD = 3
        const val BORRAR_SEL = 4; const val OPCIONES = 5; const val CAMBIO_123 = 6; const val HECHO = 7
        const val PANEL = 8
    }

    private inner class Dedo(val id: Int) {
        var tecla: Colocada? = null
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
    private var modoTrackpad = false; set(v) { if (field != v) { field = v; ensuciar() } }

    private fun desplazamientoCapa(): Float {
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); capa.getLocationInWindow(b)
        return (a[1] - b[1]).toFloat()
    }

    private fun desplazamientoCapaX(): Float {
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); capa.getLocationInWindow(b)
        return (a[0] - b[0]).toFloat()
    }

    private fun rectEnCapa(r: RectF): RectF {
        val dy = desplazamientoCapa(); val dx = desplazamientoCapaX()
        return RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
    }

    private fun textoVisible(t: Tecla): String {
        if (!t.esLetra) return t.texto
        return if (shift > 0) t.texto.uppercase() else t.texto
    }

    private fun mostrarGlobo(c: Colocada) {
        val t = c.tecla
        if (config?.vistaPrevia == false || t.tipo != K.TEXTO || t.texto.length > 2) return
        capa.mostrarGlobo(rectEnCapa(c.dibujo), textoVisible(t))
    }

    private fun tipoRespuesta(t: Tecla) = when (t.tipo) {
        K.ESPACIO -> Respuesta.Tipo.ESPACIO
        K.BORRAR -> Respuesta.Tipo.BORRAR
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
        // Si otro dedo sigue en una tecla, esa tecla se escribe ya (dos pulgares rápidos)
        for (o in dedos.values) if (o.modo == Modo.NORMAL && o.tecla?.tecla?.tipo == K.TEXTO) {
            removeCallbacks(o.larga)
            emitir(o)
            o.modo = Modo.HECHO
        }
        val dd = Dedo(id)
        dd.x0 = x; dd.y0 = y; dd.x = x; dd.y = y
        dedos[id] = dd
        if (!panelMano.isEmpty && panelMano.contains(x, y)) { dd.modo = Modo.PANEL; return }
        val c = buscar(x, y) ?: run { dd.modo = Modo.HECHO; return }
        dd.tecla = c
        presionadas.add(c)
        val t = c.tecla
        oyente?.respuesta(tipoRespuesta(t))
        val retardo = (config?.retardoLarga ?: 320).toLong()
        when (t.tipo) {
            K.TEXTO -> {
                mostrarGlobo(c)
                if (t.variantes.isNotEmpty()) postDelayed(dd.larga, retardo)
                if (t.esLetra && dd.n == 0) dd.punto(x, y)
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
            }
            K.ESPACIO -> postDelayed(dd.larga, retardo + 180L)
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
                        if (t.esLetra) dd.punto(x, y)
                        val lejos = hypot(dx, dy) > anchoTecla * 0.75f
                        if (lejos && t.esLetra && cuantos == 1 && cfg?.deslizar != false && distribucion.nombre == "abc") {
                            dd.modo = Modo.DESLIZAR
                            removeCallbacks(dd.larga)
                            capa.ocultarGlobo()
                            presionadas.clear()
                            invalidate()
                        } else if (lejos && (cfg?.deslizar == false || !t.esLetra)) {
                            // Sin deslizar: la tecla que vale es la que está bajo el dedo
                            val c = buscar(x, y)
                            if (c != null && c != dd.tecla && c.tecla.tipo == K.TEXTO) {
                                dd.tecla?.let { presionadas.remove(it) }; dd.tecla = c; presionadas.add(c)
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
                if (c != dd.tecla && hypot(dx, dy) > anchoTecla * 0.4f) {
                    dd.tecla?.let { presionadas.remove(it) }
                    dd.tecla = c
                    if (c != null && c.tecla.tipo == K.TEXTO) { presionadas.add(c); mostrarGlobo(c) } else capa.ocultarGlobo()
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
                val lista = if (shift > 0) t.variantes.map { if (it.length == 1) it.uppercase() else it } else t.variantes
                capa.mostrarOpciones(rectEnCapa(c.dibujo), lista, 0)
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
        presionadas.remove(c)
        apagando[c] = SystemClock.uptimeMillis()
        capa.ocultarGlobo()
    }

    private fun soltar(dd: Dedo, x: Float, y: Float) {
        removeCallbacks(dd.larga); removeCallbacks(dd.repetir)
        val c = dd.tecla
        when (dd.modo) {
            Modo.NORMAL -> if (c != null) {
                when (c.tecla.tipo) {
                    K.TEXTO -> emitir(dd)
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
                if (elegida != null) oyente?.tecla(elegida, dd.x0, dd.y0, false)
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
        if (c != null) { presionadas.remove(c); apagando[c] = SystemClock.uptimeMillis() }
        if (dd.modo == Modo.CURSOR || dd.modo == Modo.TRACKPAD) modoTrackpad = dedos.values.any { it.modo == Modo.CURSOR || it.modo == Modo.TRACKPAD }
        if (dedos.values.none { it.modo == Modo.NORMAL && it.tecla?.tecla?.tipo == K.TEXTO }) capa.ocultarGlobo()
        invalidate()
    }

    fun cancelarTodo() {
        for (dd in dedos.values) {
            removeCallbacks(dd.larga); removeCallbacks(dd.repetir)
            if (dd.modo == Modo.BORRAR_SEL) oyente?.terminarSeleccionBorrar(false)
        }
        dedos.clear(); presionadas.clear(); apagando.clear(); modoTrackpad = false
        capa.ocultarGlobo(); capa.ocultarOpciones()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelarTodo()
        // La imagen guardada se suelta: el teclado escondido no ocupa memoria
        cache?.recycle(); cache = null; sucio = true
    }

    // ---------- Dibujo ----------
    //
    // Para gastar lo mínimo de batería, las teclas en reposo se dibujan UNA vez en una imagen
    // guardada; en cada toque solo se dibuja encima la tecla presionada (y se apaga suave).

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sombra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 400) }
    private val chica = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    private val pista = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val estela = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val camino = Path()

    private var cache: Bitmap? = null
    private var sucio = true
    private val apagando = HashMap<Colocada, Long>()
    private val DURACION_APAGADO = 140f

    private fun ensuciar() { sucio = true; invalidate() }

    private fun prepararPinturas() {
        borde.strokeWidth = dp(0.6f)
        borde.color = tema.bordeTecla
        brillo.strokeWidth = dp(1f)
        sombra.color = 0x40000000
        letra.textSize = altoFila * 0.46f
        chica.textSize = minOf(altoFila * 0.29f, dp(16f))
        pista.textSize = altoFila * 0.19f
        pista.color = tema.pista
    }

    private fun colorTecla(t: Tecla, apretada: Boolean): Int = when {
        t.tipo == K.INTRO && introDestacado -> if (apretada) Tema.mezclar(tema.acento, 0xFF000000.toInt(), 0.72f) else tema.introActivo
        t.tipo == K.SHIFT && shift > 0 -> if (apretada) 0xFFD1D1D6.toInt() else 0xFFF2F2F7.toInt()
        t.funcion -> if (apretada) tema.funcionPresionada else tema.teclaFuncion
        apretada -> tema.teclaPresionada
        else -> tema.tecla
    }

    /**
     * Dibuja una tecla. [fondo] (0..1) es cuánto se ve el fondo de \"presionada\" encima de la
     * imagen guardada; en reposo es 1 con el color normal.
     */
    private fun dibujarTecla(c: Canvas, k: Colocada, apretada: Boolean, fondo: Float) {
        val t = k.tecla
        val r = k.dibujo
        // Esquinas bien redondeadas, como las teclas de vidrio
        val radio = minOf(dp(12.5f), r.height() * 0.27f)
        if (!apretada) c.drawRoundRect(r.left, r.top + dp(0.7f), r.right, r.bottom + dp(0.7f), radio, radio, sombra)
        relleno.color = colorTecla(t, apretada)
        relleno.alpha = (relleno.alpha * fondo).toInt().coerceIn(0, 255)
        c.drawRoundRect(r, radio, radio, relleno)
        // Reflejo fino en el borde de arriba (efecto vidrio)
        if (!(t.tipo == K.SHIFT && shift > 0) && !(t.tipo == K.INTRO && introDestacado)) {
            brillo.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.5f,
                tema.brillo, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            brillo.alpha = (255 * fondo).toInt().coerceIn(0, 255)
            c.drawRoundRect(r.left + dp(0.5f), r.top + dp(0.5f), r.right - dp(0.5f), r.bottom - dp(0.5f), radio, radio, brillo)
        }
        if (modoTrackpad) return

        val cx = r.centerX(); val cy = r.centerY()
        val s = minOf(r.height(), r.width())
        icono.color = tema.texto
        when (t.tipo) {
            K.TEXTO -> {
                val txt = textoVisible(t)
                val p = if (txt.length > 1) chica else letra
                p.color = tema.texto
                val ajuste = if (t.esLetra && shift == 0) -p.textSize * 0.05f else 0f
                c.drawText(txt, cx, cy + p.textSize * 0.36f + ajuste, p)
                if (config?.pistas != false && distribucion.nombre == "abc" && t.pista != null)
                    c.drawText(t.pista, r.right - r.width() * 0.2f, r.top + pista.textSize * 1.05f, pista)
            }
            K.SHIFT -> {
                icono.color = if (shift > 0) 0xFF000000.toInt() else tema.texto
                Iconos.shift(c, cx, cy, s, icono, shift > 0, shift == 2)
            }
            K.BORRAR -> Iconos.borrar(c, cx, cy, s, icono, false)
            K.EMOJI -> Iconos.cara(c, cx, cy, s, icono)
            K.INTRO -> {
                val txt = textoIntro
                if (txt != null) { chica.color = tema.texto; c.drawText(txt, cx, cy + chica.textSize * 0.36f, chica) }
                else Iconos.intro(c, cx, cy, s, icono)
            }
            K.ESPACIO -> {
                // Como en el iPhone: el espacio va limpio, solo el idioma chiquito en la esquina
                pista.textAlign = Paint.Align.RIGHT
                c.drawText("ES", r.right - dp(10f), r.bottom - dp(8f), pista)
                pista.textAlign = Paint.Align.CENTER
            }
            else -> { chica.color = tema.texto; c.drawText(t.texto, cx, cy + chica.textSize * 0.36f, chica) }
        }
    }

    /** Suavizado: rápido al principio y lento al final */
    private fun suave(t: Float) = 1f - (1f - t) * (1f - t)

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        var bmp = cache
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cache = bmp
            sucio = true
        }
        if (sucio) {
            bmp.eraseColor(0)
            val cc = Canvas(bmp)
            prepararPinturas()
            for (k in colocadas) dibujarTecla(cc, k, false, 1f)
            if (!panelMano.isEmpty) {
                icono.color = tema.textoSuave
                Iconos.expandir(cc, botonExpandir.centerX(), botonExpandir.centerY(), botonExpandir.width(), icono)
                Iconos.flecha(cc, botonCambiar.centerX(), botonCambiar.centerY(), botonCambiar.width(), icono, if (unaMano == 1) 180f else 0f)
            }
            sucio = false
        }
        c.drawBitmap(bmp, 0f, 0f, null)

        // Teclas presionadas (encima) y las que se están soltando, apagándose suave
        for (k in presionadas) dibujarTecla(c, k, true, 1f)
        if (apagando.isNotEmpty()) {
            val ahora = SystemClock.uptimeMillis()
            val it = apagando.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key in presionadas) { it.remove(); continue }
                val t = (ahora - e.value) / DURACION_APAGADO
                if (t >= 1f) { it.remove(); continue }
                dibujarTecla(c, e.key, true, 1f - suave(t))
            }
            if (apagando.isNotEmpty()) postInvalidateOnAnimation()
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

}
