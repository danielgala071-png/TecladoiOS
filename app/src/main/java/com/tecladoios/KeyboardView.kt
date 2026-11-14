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
    }

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
            }
            field = v; reacomodar(); ensuciar()
        }

    /** 0 minúsculas, 1 mayúscula, 2 bloqueo */
    // La mayúscula solo cambia letras y el color de la tecla shift: no se redibuja todo
    var shift = 0; set(v) { if (field != v) { field = v; letrasSucias = true; shiftSucia = true; invalidate() } }
    /** Texto de la tecla intro ("buscar", "enviar"...) o null para la flecha */
    var textoIntro: String? = null; set(v) { if (field != v) { field = v; letrasSucias = true; invalidate() } }
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
        val base = if (filas >= 5) 57f else 62f   // teclas más grandes (más fáciles a dos dedos)
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
        // Divisiones finas entre teclas: teclas más grandes y un aspecto más limpio
        // Más aire entre teclas. Solo cambia el dibujo: la zona que recibe el toque sigue siendo
        // la celda completa, así escribir rápido a dos dedos no pierde precisión.
        val sepX = dp(5.4f) * if (unaMano != 0) 0.85f else 1f
        val sepY = minOf(dp(9.5f), altoFila * 0.17f)
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
            val ex = x - cx; val ey = (y - cy) * 0.6f
            val dd = ex * ex + ey * ey
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
        /** La letra ya se escribió al tocar (no se vuelve a escribir al soltar) */
        var emitida = false
        /** Estaba en mayúscula al tocar (para la tilde, si se cambia) */
        var mayus = false
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
        dd.tecla = c
        presionar(c, x, y)
        val t = c.tecla
        oyente?.respuesta(tipoRespuesta(t))
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
                if (elegida != null) oyente?.tecla(elegida, dd.x0, dd.y0, false)
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
        if (dd.modo == Modo.CURSOR || dd.modo == Modo.TRACKPAD) modoTrackpad = dedos.values.any { it.modo == Modo.CURSOR || it.modo == Modo.TRACKPAD }
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
    private val DURACION_FILA = 170f
    /** ...y arranca este tanto después de la de abajo: una ola que nace en la tecla 123 */
    private val ESCALON = 0f

    // ---------- Pulsación: la tecla se hunde, se enciende desde el dedo y vuelve con resorte ----------

    private companion object {
        const val SHIFT_BLANCO = 0xFFF5F5F7.toInt()
        const val SHIFT_BLANCO_APRETADO = 0xFFC7C7CC.toInt()
        const val FLECHA_NEGRA = 0xFF1C1C1E.toInt()
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
    private val HUNDIR = 1f
    private val VOLVER = 1f
    private val APAGAR = 200f   // al soltar sube con un rebote cortito y el brillo se apaga
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
            p.nivel = suave(((ahora - p.t0) / BAJAR).coerceIn(0f, 1f)).coerceAtLeast(0.6f)
            p.suelta = ahora
        }
        invalidate()
    }

    /** 0 = en reposo, 1 = hundida del todo (al volver puede pasar un pelito a negativo: resorte) */
    private fun hundimiento(p: Pulso, ahora: Long): Float {
        if (p.suelta == 0L) return suave(((ahora - p.t0) / HUNDIR).coerceIn(0f, 1f))
        val r = ((ahora - p.suelta) / VOLVER).coerceIn(0f, 1f)
        // Resorte amortiguado: vuelve, se pasa apenas y se asienta
        return p.nivel * kotlin.math.exp(-4.2f * r) * kotlin.math.cos(r * 6.6f) * (1f - r)
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
        // Como el iPhone: el enter es una tecla gris más y la mayúscula activa se pone blanca
        t.tipo == K.SHIFT && shift > 0 -> if (apretada) SHIFT_BLANCO_APRETADO else SHIFT_BLANCO
        t.funcion -> if (apretada) tema.funcionPresionada else tema.teclaFuncion
        // Letras: con el globo encima basta un brillo leve (como el iPhone); sin globo, más marcado
        apretada && t.tipo == K.TEXTO && config?.vistaPrevia != false && t.texto.length <= 2 ->
            Tema.mezclar(tema.teclaPresionada, tema.tecla, 0.45f)
        apretada -> tema.teclaPresionada
        else -> tema.tecla
    }

    /** Esquinas de vidrio: curva suave como el iPhone */
    private fun radioDe(r: RectF) = minOf(dp(8.5f), r.height() * 0.18f)

    /**
     * Tecla de vidrio líquido con volumen. Se dibuja UNA vez en la imagen guardada, así que
     * todo esto no gasta nada al escribir:
     * - doble sombra suave solo por fuera (la tecla flota sobre el fondo),
     * - cuerpo translúcido,
     * - cúpula de luz arriba (como una gota de vidrio) y un brillo de refracción en la base,
     * - filo luminoso: fuerte en el canto de arriba, tenue a los lados, un poco abajo.
     */
    private fun dibujarCuerpo(c: Canvas, k: Colocada) {
        val r = k.dibujo
        val radio = radioDe(r)
        val color = colorTecla(k.tecla, false)
        val blanca = k.tecla.tipo == K.SHIFT && shift > 0
        camino.reset(); camino.addRoundRect(r, radio, radio, Path.Direction.CW)
        // Sombras por fuera
        c.save()
        c.clipOutPath(camino)
        sombra.color = 0x33000000
        c.drawRoundRect(r.left - dp(0.5f), r.top + dp(2.5f), r.right + dp(0.5f), r.bottom + dp(3.5f), radio + dp(1f), radio + dp(1f), sombra)
        sombra.color = 0x59000000
        c.drawRoundRect(r.left + dp(0.3f), r.top + dp(1.2f), r.right - dp(0.3f), r.bottom + dp(1.4f), radio, radio, sombra)
        c.restore()
        // Cuerpo
        relleno.shader = null
        relleno.color = color
        c.drawRoundRect(r, radio, radio, relleno)
        // Enter con el degradado azul → violeta (modelo Fusión)
        if (k.tecla.tipo == K.INTRO) {
            relleno.shader = android.graphics.LinearGradient(r.left, r.top, r.right, r.bottom,
                Tema.AZUL_ACENTO, Tema.ROJO, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radio, radio, relleno)
            relleno.shader = null
        }
        if (!blanca) {
            c.save()
            c.clipPath(camino)
            // Cúpula de luz arriba
            relleno.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.55f,
                0x24FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.55f, relleno)
            // Refracción en la base
            relleno.shader = android.graphics.LinearGradient(0f, r.bottom - r.height() * 0.28f, 0f, r.bottom,
                0x00FFFFFF, 0x12FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.bottom - r.height() * 0.28f, r.right, r.bottom, relleno)
            relleno.shader = null
            c.restore()
            // Filo luminoso
            brillo.strokeWidth = dp(0.9f)
            brillo.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom,
                intArrayOf(0x73FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x1FFFFFFF), floatArrayOf(0f, 0.3f, 0.7f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r.left + dp(0.45f), r.top + dp(0.45f), r.right - dp(0.45f), r.bottom - dp(0.45f), radio, radio, brillo)
            brillo.shader = null
        }
        if (blanca) flechaNegra(c, k)
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
        if (modoTrackpad) return
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
                c.drawText(txt, cx, cy + p.textSize * 0.36f + ajuste, p)
                if (config?.pistas != false && distribucion.nombre == "abc" && t.pista != null)
                    c.drawText(t.pista, r.right - r.width() * 0.2f, r.top + pista.textSize * 1.05f, pista)
            }
            // Activa: la flecha negra va pintada sobre la tecla blanca (en la imagen de cuerpos)
            K.SHIFT -> if (shift == 0) Iconos.shift(c, cx, cy, s, icono, false, false)
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
    private fun dibujarPulso(c: Canvas, k: Colocada, p: Pulso, ahora: Long, fondo: Bitmap, letras: Bitmap) {
        val l = luzDe(p, ahora)
        if (l <= 0.01f) return
        val r = k.dibujo
        val radio = radioDe(r)
        val blanca = k.tecla.tipo == K.SHIFT && shift > 0
        // Luz por debajo (halo violeta, un poco más abajo que la tecla)
        val luzB = luzDebajo()
        if (luzB != null) {
            val ex = dp(12f)
            dst.set(r.left - ex, r.top - ex + dp(5f), r.right + ex, r.bottom + ex + dp(5f))
            pinturaHalo.alpha = (255 * l).toInt().coerceIn(0, 255)
            c.drawBitmap(luzB, null, dst, pinturaHalo)
        }
        // Vuelve a pintar la tecla encima (el halo queda solo alrededor y por debajo)
        src.set((r.left - 1).toInt().coerceAtLeast(0), (r.top - 1).toInt().coerceAtLeast(0),
            (r.right + 1).toInt().coerceAtMost(fondo.width), (r.bottom + 1).toInt().coerceAtMost(fondo.height))
        dst.set(src)
        c.drawBitmap(fondo, src, dst, null)
        // El vidrio se aclara y la luz violeta sube por dentro desde abajo
        relleno.shader = null
        relleno.color = colorTecla(k.tecla, true)
        relleno.alpha = (Color.alpha(relleno.color) * l * (if (blanca) 1f else 0.75f)).toInt().coerceIn(0, 255)
        c.drawRoundRect(r, radio, radio, relleno)
        relleno.alpha = 255
        if (!blanca) {
            // Sin recortes (que son caros): la luz se pinta con la misma forma redondeada
            matrizLuz.setScale(1f, r.height() * 0.6f)
            matrizLuz.postTranslate(0f, r.bottom - r.height() * 0.6f)
            luzInterior.setLocalMatrix(matrizLuz)
            pinturaInterior.alpha = (255 * l).toInt().coerceIn(0, 255)
            c.drawRoundRect(r, radio, radio, pinturaInterior)
            filoBajo.strokeWidth = dp(1f)
            filoBajo.color = 0x73FFFFFF
            filoBajo.alpha = (0x73 * l).toInt()
            c.drawLine(r.left + radio, r.top + dp(0.6f), r.right - radio, r.top + dp(0.6f), filoBajo)
        }
        if (blanca) { flechaNegra(c, k); return }
        // La letra encima
        src.set(r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0),
            (r.right + 1).toInt().coerceAtMost(letras.width), (r.bottom + 1).toInt().coerceAtMost(letras.height))
        dst.set(src)
        pinturaLetras.color = tema.texto
        c.drawBitmap(letras, src, dst, pinturaLetras)
    }

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

    // Entrada en cascada al abrir el teclado
    private var entradaMs = 0L
    private val ENTRADA_FILA = 170f
    private val ENTRADA_ESCALON = 35f

    fun animarEntrada() { entradaMs = SystemClock.uptimeMillis(); invalidate() }

    /** Las filas aparecen una tras otra, subiendo un poquito */
    private fun dibujarEntrada(c: Canvas, t: Float, filas: Int, fondo: Bitmap, letras: Bitmap) {
        val w = width.toFloat()
        val arriba = dp(8f)
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

    private fun dibujarTodo(c: Canvas) {
        if (width <= 0 || height <= 0) return
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
            c.drawBitmap(letras, 0f, 0f, pinturaLetras)
        }

        // Teclas apretadas y las que vuelven a su lugar
        if (pulsos.isNotEmpty()) {
            val it = pulsos.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (terminado(e.value, ahora)) { it.remove(); continue }
                dibujarPulso(c, e.key, e.value, ahora, fondo, letras)
            }
            if (pulsos.values.any { it.suelta != 0L || ahora - it.t0 < BAJAR }) postInvalidateOnAnimation()
        }

        // Cursor de vidrio: al mover el cursor con el espacio, el teclado queda como un panel
        // de vidrio vacío y un punto de luz sigue tu dedo
        if (modoTrackpad) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veloCursor)
            val dd = dedos.values.firstOrNull { it.modo == Modo.CURSOR || it.modo == Modo.TRACKPAD }
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
        val arriba = dp(8f)
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
            // Letras: ruedan dentro de cada tecla (recortadas al alto de la tecla)
            c.clipRect(0f, y0 + medio, w, y0 + altoFila - medio)
            if (f < 1f) {
                val u = 1f - e
                pinturaAnterior.alpha = (255 * u * u).toInt().coerceIn(0, 255)
                c.save(); c.translate(0f, -sube * e)
                c.drawBitmap(viejoL, 0f, 0f, pinturaAnterior)
                c.restore()
            }
            if (f > 0f) {
                pinturaNueva.alpha = (255 * minOf(1f, f * 1.6f)).toInt().coerceIn(0, 255)
                c.save(); c.translate(0f, sube * (1f - asentar(f)))
                c.drawBitmap(letras, 0f, 0f, pinturaNueva)
                c.restore()
            }
            c.restore()
        }
    }

}
