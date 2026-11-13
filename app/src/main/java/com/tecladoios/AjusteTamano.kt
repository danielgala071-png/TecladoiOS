package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Ajustar el tamaño del teclado: se oscurece el teclado y aparecen una manija arriba (más alto
 * o más bajo), una manija abajo y un botón para moverlo (subirlo o bajarlo), además de
 * «Restablecer» y «Listo». Todo cambia en vivo mientras arrastras.
 */
class AjusteTamano(context: Context) : View(context) {

    interface Oyente {
        /** [altura] en %, [margen] en dp; [fin] = se tocó Listo (se guarda) */
        fun tamano(altura: Int, margen: Int, fin: Boolean)
        fun cerrarTamano()
        fun respuesta(tipo: Respuesta.Tipo)
    }

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }
    var altura = 100
    var margen = 0
    /** Alto del teclado (sin barra) cuando la altura es 100 %, en px */
    var altoBase = 1f

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val velo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val manija = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pastilla = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 600) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)

    private val manijaArriba = RectF()
    private val manijaAbajo = RectF()
    private val botonRestablecer = RectF()
    private val botonListo = RectF()
    private val botonMover = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Velo encima del teclado
        velo.color = 0xA6101216.toInt()
        c.drawRoundRect(0f, 0f, w, h, dp(24f), dp(24f), velo)
        // Manijas
        manija.color = Tema.mezclar(tema.acento, 0xFFFFFFFF.toInt(), 0.65f)
        val mw = dp(56f); val mh = dp(5f)
        manijaArriba.set(w / 2 - mw / 2, dp(10f), w / 2 + mw / 2, dp(10f) + mh)
        manijaAbajo.set(w / 2 - mw / 2, h - dp(14f), w / 2 + mw / 2, h - dp(14f) + mh)
        c.drawRoundRect(manijaArriba, mh / 2, mh / 2, manija)
        c.drawRoundRect(manijaAbajo, mh / 2, mh / 2, manija)
        // Botones al centro
        val cy = h / 2
        val bh = dp(46f)
        botonMover.set(w / 2 - bh / 2, cy - bh / 2, w / 2 + bh / 2, cy + bh / 2)
        botonRestablecer.set(w / 2 - bh / 2 - dp(12f) - dp(140f), cy - bh / 2, w / 2 - bh / 2 - dp(12f), cy + bh / 2)
        botonListo.set(w / 2 + bh / 2 + dp(12f), cy - bh / 2, w / 2 + bh / 2 + dp(12f) + dp(120f), cy + bh / 2)
        pastilla.color = Tema.mezclar(tema.acento, 0xFF000000.toInt(), 0.55f)
        c.drawRoundRect(botonRestablecer, bh / 2, bh / 2, pastilla)
        c.drawRoundRect(botonListo, bh / 2, bh / 2, pastilla)
        pastilla.color = 0xFF1C1E24.toInt()
        c.drawOval(botonMover, pastilla)
        texto.textSize = dp(15.5f); texto.color = 0xFFFFFFFF.toInt()
        c.drawText("Restablecer", botonRestablecer.centerX(), cy + texto.textSize * 0.36f, texto)
        c.drawText("Listo", botonListo.centerX(), cy + texto.textSize * 0.36f, texto)
        icono.color = 0xFFFFFFFF.toInt()
        Iconos.flecha(c, botonMover.centerX(), botonMover.centerY() - dp(8f), dp(30f), icono, 90f)
        Iconos.flecha(c, botonMover.centerX(), botonMover.centerY() + dp(8f), dp(30f), icono, 270f)
        // Valor actual
        texto.textSize = dp(12.5f); texto.color = 0xCCFFFFFF.toInt()
        c.drawText("Altura $altura %   ·   Espacio abajo $margen", w / 2, cy + bh / 2 + dp(26f), texto)
    }

    // ---------- Toques ----------

    private object Que { const val NADA = 0; const val ARRIBA = 1; const val ABAJO = 2; const val MOVER = 3; const val BOTON = 4 }
    private var arrastre = Que.NADA
    private var y0 = 0f
    private var alturaInicial = 100
    private var margenInicial = 0

    private fun cerca(r: RectF, x: Float, y: Float) =
        x > r.left - dp(40f) && x < r.right + dp(40f) && y > r.top - dp(22f) && y < r.bottom + dp(22f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                y0 = e.rawY; alturaInicial = altura; margenInicial = margen
                arrastre = when {
                    cerca(manijaArriba, e.x, e.y) -> Que.ARRIBA
                    cerca(manijaAbajo, e.x, e.y) -> Que.ABAJO
                    botonMover.contains(e.x, e.y) -> Que.MOVER
                    botonRestablecer.contains(e.x, e.y) || botonListo.contains(e.x, e.y) -> Que.BOTON
                    else -> Que.NADA
                }
                if (arrastre != Que.NADA) oyente?.respuesta(Respuesta.Tipo.SUAVE)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - y0
                when (arrastre) {
                    // Subir la manija de arriba = teclado más alto
                    Que.ARRIBA -> cambiar((alturaInicial - dy / altoBase * 100f).toInt(), margen)
                    // Subir la manija de abajo = más espacio abajo y teclas más bajas
                    Que.ABAJO -> {
                        val nuevoMargen = (margenInicial - dy / d).toInt()
                        val usado = (nuevoMargen - margenInicial) * d
                        cambiar((alturaInicial - usado / altoBase * 100f).toInt(), nuevoMargen)
                    }
                    // Mover: todo el teclado sube o baja sin cambiar su tamaño
                    Que.MOVER -> cambiar(altura, (margenInicial - dy / d).toInt())
                }
            }
            MotionEvent.ACTION_UP -> {
                when (arrastre) {
                    Que.BOTON -> {
                        oyente?.respuesta(Respuesta.Tipo.FUNCION)
                        if (botonRestablecer.contains(e.x, e.y)) { altura = 100; margen = 0; oyente?.tamano(100, 0, false); invalidate() }
                        else if (botonListo.contains(e.x, e.y)) { oyente?.tamano(altura, margen, true); oyente?.cerrarTamano() }
                    }
                    Que.ARRIBA, Que.ABAJO, Que.MOVER -> oyente?.tamano(altura, margen, false)
                }
                arrastre = Que.NADA
            }
            MotionEvent.ACTION_CANCEL -> arrastre = Que.NADA
        }
        return true
    }

    private fun cambiar(a: Int, m: Int) {
        val na = a.coerceIn(75, 135)
        val nm = m.coerceIn(0, 120)
        if (na != altura || nm != margen) {
            if ((na == 75 || na == 135) && na != altura) oyente?.respuesta(Respuesta.Tipo.SUAVE)
            altura = na; margen = nm
            oyente?.tamano(altura, margen, false)
            invalidate()
        }
    }
}
