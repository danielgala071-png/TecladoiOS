package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Ajustar el tamaño del teclado, como en Gboard: se oscurece el teclado y aparece un marco con
 * cuatro esquinas para arrastrar.
 *
 * - Esquinas de arriba: hacia arriba o abajo, el teclado se hace más alto o más bajo.
 * - Esquinas de abajo: hacia arriba, el borde de abajo sube (deja espacio debajo).
 * - Cualquier esquina hacia los lados: más ancho o más angosto (queda centrado).
 * - Las manijas del centro de arriba y abajo hacen lo mismo que sus esquinas, solo en alto.
 * - El botón del medio mueve todo el teclado sin cambiar su tamaño.
 *
 * Todo cambia en vivo mientras arrastras; «Listo» lo guarda y «Restablecer» vuelve al tamaño
 * de fábrica.
 */
class AjusteTamano(context: Context) : View(context) {

    interface Oyente {
        /** [altura] y [ancho] en %, [margen] en dp; [fin] = se tocó Listo (se guarda) */
        fun tamano(altura: Int, margen: Int, ancho: Int, fin: Boolean)
        fun cerrarTamano()
        fun respuesta(tipo: Respuesta.Tipo)
    }

    companion object {
        const val ALTURA_MIN = 75; const val ALTURA_MAX = 135
        const val ANCHO_MIN = 70; const val ANCHO_MAX = 100
        const val MARGEN_MAX = 120
    }

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }
    var altura = 100
    var margen = 0
    var ancho = 100
    /** Alto del teclado (sin barra) cuando la altura es 100 %, en px */
    var altoBase = 1f
    /** Ancho de la pantalla en px (el 100 % del ancho) */
    var anchoPantalla = 1f

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val velo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val esquina = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val manija = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pastilla = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 600) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camino = Path()

    private val manijaArriba = RectF()
    private val manijaAbajo = RectF()
    private val botonRestablecer = RectF()
    private val botonListo = RectF()
    private val botonMover = RectF()
    private val caja = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Velo encima del teclado
        velo.color = 0xB30B0B0E.toInt()
        c.drawRoundRect(0f, 0f, w, h, dp(16f), dp(16f), velo)
        // Marco del teclado
        val m = dp(4f)
        caja.set(m, m, w - m, h - m)
        marco.color = 0x66FF2D3A; marco.strokeWidth = dp(1.2f)
        c.drawRoundRect(caja, dp(12f), dp(12f), marco)
        // Cuatro esquinas para arrastrar (en L, gruesas)
        val activo = arrastre
        val largo = dp(26f)
        esquina.strokeWidth = dp(4.5f)
        fun dibujarEsquina(x: Float, y: Float, sx: Float, sy: Float, cual: Int) {
            esquina.color = if (activo == cual) 0xFFFFFFFF.toInt() else tema.rojo
            camino.reset()
            camino.moveTo(x, y + sy * largo)
            camino.lineTo(x, y + sy * dp(6f))
            camino.quadTo(x, y, x + sx * dp(6f), y)
            camino.lineTo(x + sx * largo, y)
            c.drawPath(camino, esquina)
        }
        dibujarEsquina(caja.left, caja.top, 1f, 1f, Que.SUP_IZQ)
        dibujarEsquina(caja.right, caja.top, -1f, 1f, Que.SUP_DER)
        dibujarEsquina(caja.left, caja.bottom, 1f, -1f, Que.INF_IZQ)
        dibujarEsquina(caja.right, caja.bottom, -1f, -1f, Que.INF_DER)
        // Manijas del centro, arriba y abajo
        val mw = dp(56f); val mh = dp(5f)
        manijaArriba.set(w / 2 - mw / 2, dp(10f), w / 2 + mw / 2, dp(10f) + mh)
        manijaAbajo.set(w / 2 - mw / 2, h - dp(15f), w / 2 + mw / 2, h - dp(15f) + mh)
        manija.color = if (activo == Que.ARRIBA) 0xFFFFFFFF.toInt() else Tema.mezclar(tema.rojo, 0xFFFFFFFF.toInt(), 0.7f)
        c.drawRoundRect(manijaArriba, mh / 2, mh / 2, manija)
        manija.color = if (activo == Que.ABAJO) 0xFFFFFFFF.toInt() else Tema.mezclar(tema.rojo, 0xFFFFFFFF.toInt(), 0.7f)
        c.drawRoundRect(manijaAbajo, mh / 2, mh / 2, manija)
        // Botones al centro
        val cy = h / 2
        val bh = dp(44f)
        val bw = minOf(dp(130f), (w - bh - dp(48f)) / 2)
        botonMover.set(w / 2 - bh / 2, cy - bh / 2, w / 2 + bh / 2, cy + bh / 2)
        botonRestablecer.set(botonMover.left - dp(12f) - bw, cy - bh / 2, botonMover.left - dp(12f), cy + bh / 2)
        botonListo.set(botonMover.right + dp(12f), cy - bh / 2, botonMover.right + dp(12f) + bw, cy + bh / 2)
        pastilla.color = 0xFF2A2124.toInt()
        c.drawRoundRect(botonRestablecer, dp(10f), dp(10f), pastilla)
        pastilla.color = tema.rojo
        c.drawRoundRect(botonListo, dp(10f), dp(10f), pastilla)
        pastilla.color = if (activo == Que.MOVER) 0xFF4A1E24.toInt() else 0xFF1C1E24.toInt()
        c.drawOval(botonMover, pastilla)
        texto.textSize = dp(15f); texto.color = 0xFFFFFFFF.toInt()
        c.drawText("Restablecer", botonRestablecer.centerX(), cy + texto.textSize * 0.36f, texto)
        c.drawText("Listo", botonListo.centerX(), cy + texto.textSize * 0.36f, texto)
        icono.color = 0xFFFFFFFF.toInt()
        Iconos.flecha(c, botonMover.centerX(), botonMover.centerY() - dp(7f), dp(28f), icono, 90f)
        Iconos.flecha(c, botonMover.centerX(), botonMover.centerY() + dp(7f), dp(28f), icono, 270f)
        // Valores actuales
        texto.textSize = dp(12.5f); texto.color = 0xCCFFFFFF.toInt()
        c.drawText("Alto $altura %   ·   Ancho $ancho %   ·   Abajo $margen", w / 2, cy + bh / 2 + dp(24f), texto)
        texto.textSize = dp(11.5f); texto.color = 0x80FFFFFF.toInt()
        c.drawText("Arrastra las esquinas", w / 2, cy - bh / 2 - dp(14f), texto)
    }

    // ---------- Toques ----------

    private object Que {
        const val NADA = 0; const val ARRIBA = 1; const val ABAJO = 2; const val MOVER = 3; const val BOTON = 4
        const val SUP_IZQ = 5; const val SUP_DER = 6; const val INF_IZQ = 7; const val INF_DER = 8
    }
    private var arrastre = Que.NADA
    private var x0 = 0f
    private var y0 = 0f
    private var alturaInicial = 100
    private var margenInicial = 0
    private var anchoInicial = 100

    private fun cerca(r: RectF, x: Float, y: Float) =
        x > r.left - dp(40f) && x < r.right + dp(40f) && y > r.top - dp(22f) && y < r.bottom + dp(22f)

    private fun cercaDe(px: Float, py: Float, x: Float, y: Float): Boolean {
        val r = dp(46f)
        return (x - px) * (x - px) + (y - py) * (y - py) < r * r
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY
                alturaInicial = altura; margenInicial = margen; anchoInicial = ancho
                arrastre = when {
                    cercaDe(caja.left, caja.top, e.x, e.y) -> Que.SUP_IZQ
                    cercaDe(caja.right, caja.top, e.x, e.y) -> Que.SUP_DER
                    cercaDe(caja.left, caja.bottom, e.x, e.y) -> Que.INF_IZQ
                    cercaDe(caja.right, caja.bottom, e.x, e.y) -> Que.INF_DER
                    cerca(manijaArriba, e.x, e.y) -> Que.ARRIBA
                    cerca(manijaAbajo, e.x, e.y) -> Que.ABAJO
                    botonMover.contains(e.x, e.y) -> Que.MOVER
                    botonRestablecer.contains(e.x, e.y) || botonListo.contains(e.x, e.y) -> Que.BOTON
                    else -> Que.NADA
                }
                if (arrastre != Que.NADA) { oyente?.respuesta(Respuesta.Tipo.SUAVE); invalidate() }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - x0
                val dy = e.rawY - y0
                // Hacia afuera = más ancho (se cuenta doble: el teclado crece por los dos lados)
                val anchoIzq = (anchoInicial - dx * 2f / anchoPantalla * 100f).toInt()
                val anchoDer = (anchoInicial + dx * 2f / anchoPantalla * 100f).toInt()
                when (arrastre) {
                    // Arriba: subir = teclado más alto
                    Que.ARRIBA -> cambiar(alturaArriba(dy), margen, ancho)
                    Que.SUP_IZQ -> cambiar(alturaArriba(dy), margen, anchoIzq)
                    Que.SUP_DER -> cambiar(alturaArriba(dy), margen, anchoDer)
                    // Abajo: subir = el borde de abajo sube (más espacio debajo, teclas más bajas)
                    Que.ABAJO -> abajo(dy, ancho)
                    Que.INF_IZQ -> abajo(dy, anchoIzq)
                    Que.INF_DER -> abajo(dy, anchoDer)
                    // Mover: todo el teclado sube o baja sin cambiar su tamaño
                    Que.MOVER -> cambiar(altura, (margenInicial - dy / d).toInt(), ancho)
                }
            }
            MotionEvent.ACTION_UP -> {
                when (arrastre) {
                    Que.BOTON -> {
                        oyente?.respuesta(Respuesta.Tipo.FUNCION)
                        if (botonRestablecer.contains(e.x, e.y)) {
                            altura = 100; margen = 0; ancho = 100
                            oyente?.tamano(100, 0, 100, false); invalidate()
                        } else if (botonListo.contains(e.x, e.y)) {
                            oyente?.tamano(altura, margen, ancho, true); oyente?.cerrarTamano()
                        }
                    }
                    Que.NADA -> {}
                    else -> oyente?.tamano(altura, margen, ancho, false)
                }
                arrastre = Que.NADA
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { arrastre = Que.NADA; invalidate() }
        }
        return true
    }

    private fun alturaArriba(dy: Float) = (alturaInicial - dy / altoBase * 100f).toInt()

    private fun abajo(dy: Float, nuevoAncho: Int) {
        val nuevoMargen = (margenInicial - dy / d).toInt().coerceIn(0, MARGEN_MAX)
        val usado = (nuevoMargen - margenInicial) * d
        cambiar((alturaInicial - usado / altoBase * 100f).toInt(), nuevoMargen, nuevoAncho)
    }

    private fun cambiar(a: Int, m: Int, an: Int) {
        val na = a.coerceIn(ALTURA_MIN, ALTURA_MAX)
        val nm = m.coerceIn(0, MARGEN_MAX)
        val nan = an.coerceIn(ANCHO_MIN, ANCHO_MAX)
        if (na != altura || nm != margen || nan != ancho) {
            // Un toquecito al llegar a un tope
            val tope = (na != altura && (na == ALTURA_MIN || na == ALTURA_MAX)) ||
                (nan != ancho && (nan == ANCHO_MIN || nan == ANCHO_MAX))
            if (tope) oyente?.respuesta(Respuesta.Tipo.SUAVE)
            altura = na; margen = nm; ancho = nan
            oyente?.tamano(altura, margen, ancho, false)
            invalidate()
        }
    }
}
