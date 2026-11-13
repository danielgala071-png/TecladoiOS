package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Lo común de los paneles: dibujar botones redondeados y repetir los que se mantienen */
abstract class PanelBase(context: Context) : View(context) {
    /** Botón de un panel: zona, texto o ícono y qué hace */
    private class Boton(val r: RectF, val texto: String?, val icono: Int, val accion: () -> Unit, val repite: Boolean = false)

    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }
    protected val d = resources.displayMetrics.density
    protected fun dp(v: Float) = v * d
    protected val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val texto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    protected val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val botones = ArrayList<Boton>()
    private var activo: Boton? = null
    var alResponder: ((Respuesta.Tipo) -> Unit)? = null

    protected fun limpiarBotones() = botones.clear()

    protected fun boton(c: Canvas, r: RectF, txt: String?, ic: Int, funcion: Boolean, repite: Boolean = false, accion: () -> Unit) {
        val apretado = activo?.r == r
        relleno.color = if (apretado) tema.teclaPresionada else if (funcion) tema.teclaFuncion else tema.tecla
        c.drawRoundRect(r, dp(8f), dp(8f), relleno)
        borde.color = tema.bordeTecla; borde.strokeWidth = dp(0.8f)
        c.drawRoundRect(r, dp(8f), dp(8f), borde)
        if (txt != null) {
            texto.color = tema.texto; texto.textSize = dp(15f)
            c.drawText(txt, r.centerX(), r.centerY() + texto.textSize * 0.36f, texto)
        } else dibujarIcono(c, ic, r)
        botones.add(Boton(RectF(r), txt, ic, accion, repite))
    }

    protected open fun dibujarIcono(c: Canvas, ic: Int, r: RectF) {}

    private val repetir = object : Runnable {
        override fun run() { val b = activo ?: return; if (b.repite) { b.accion(); postDelayed(this, 70L) } }
    }

    protected open fun toqueLibre(e: MotionEvent): Boolean = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (toqueLibre(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activo = botones.firstOrNull { it.r.contains(e.x, e.y) }
                activo?.let {
                    alResponder?.invoke(Respuesta.Tipo.FUNCION)
                    if (it.repite) { it.accion(); postDelayed(repetir, 420L) }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repetir)
                val b = activo
                activo = null
                if (b != null && !b.repite && b.r.contains(e.x, e.y)) b.accion()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(repetir); activo = null; invalidate() }
        }
        return true
    }
}

/** Edición de texto: flechas, seleccionar, copiar, cortar, pegar, inicio y fin */
class PanelEdicion(context: Context) : PanelBase(context) {

    interface Oyente {
        fun mover(tecla: Int)        // KeyEvent.KEYCODE_DPAD_*, MOVE_HOME, MOVE_END
        fun seleccionar(activo: Boolean)
        fun accionTexto(id: Int)     // android.R.id.selectAll / copy / cut / paste
        fun borrar()
        fun abc()
    }

    var oyente: Oyente? = null
    var seleccionando = false; set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        limpiarBotones()
        val w = width.toFloat(); val h = height.toFloat()
        val m = dp(6f)
        val colIzq = w * 0.62f
        val filas = 4
        val altoF = (h - m) / filas
        fun celda(x0: Float, x1: Float, f: Int) = RectF(x0 + m / 2, f * altoF + m, x1 - m / 2, (f + 1) * altoF)
        val a = colIzq / 3
        boton(c, celda(a, 2 * a, 0), null, 1, false, true) { oyente?.mover(android.view.KeyEvent.KEYCODE_DPAD_UP) }
        boton(c, celda(0f, a, 1), null, 2, false, true) { oyente?.mover(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
        boton(c, celda(a, 2 * a, 1), if (seleccionando) "Selec. ✓" else "Selec.", 0, !seleccionando) {
            seleccionando = !seleccionando; oyente?.seleccionar(seleccionando)
        }
        boton(c, celda(2 * a, 3 * a, 1), null, 3, false, true) { oyente?.mover(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
        boton(c, celda(a, 2 * a, 2), null, 4, false, true) { oyente?.mover(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        boton(c, celda(0f, a, 3), "⇤ Inicio", 0, true) { oyente?.mover(android.view.KeyEvent.KEYCODE_MOVE_HOME) }
        boton(c, celda(2 * a, 3 * a, 3), "Fin ⇥", 0, true) { oyente?.mover(android.view.KeyEvent.KEYCODE_MOVE_END) }
        boton(c, celda(a, 2 * a, 3), "ABC", 0, true) { oyente?.abc() }
        val x0 = colIzq; val x1 = w - m / 2
        boton(c, celda(x0, x1, 0), "Seleccionar todo", 0, true) { oyente?.accionTexto(android.R.id.selectAll) }
        boton(c, celda(x0, (x0 + x1) / 2, 1), "Copiar", 0, true) { oyente?.accionTexto(android.R.id.copy) }
        boton(c, celda((x0 + x1) / 2, x1, 1), "Cortar", 0, true) { oyente?.accionTexto(android.R.id.cut) }
        boton(c, celda(x0, x1, 2), "Pegar", 0, true) { oyente?.accionTexto(android.R.id.paste) }
        boton(c, celda(x0, x1, 3), null, 5, true, true) { oyente?.borrar() }
    }

    override fun dibujarIcono(c: Canvas, ic: Int, r: RectF) {
        icono.color = tema.texto
        val s = minOf(r.height(), r.width()) * 1.1f
        when (ic) {
            1 -> Iconos.flecha(c, r.centerX(), r.centerY(), s, icono, 90f)
            2 -> Iconos.flecha(c, r.centerX(), r.centerY(), s, icono, 0f)
            3 -> Iconos.flecha(c, r.centerX(), r.centerY(), s, icono, 180f)
            4 -> Iconos.flecha(c, r.centerX(), r.centerY(), s, icono, 270f)
            5 -> Iconos.borrar(c, r.centerX(), r.centerY(), s * 0.8f, icono, false)
        }
    }
}

/** Historial del portapapeles en tarjetas: tocar pega, mantener fija o borra */
class PanelPortapapeles(context: Context) : PanelBase(context) {

    interface Oyente {
        fun pegar(texto: String)
        fun fijar(texto: String, si: Boolean)
        fun quitar(texto: String)
        fun borrarTodo()
        fun abc()
        fun activar()
    }

    var oyente: Oyente? = null
    var elementos: List<Portapapeles.Elemento> = emptyList(); set(v) { field = v; corrimiento = 0f; marcado = null; invalidate() }
    var activo = true; set(v) { field = v; invalidate() }

    private var corrimiento = 0f
    private var maxCorrimiento = 0f
    private var marcado: Portapapeles.Elemento? = null
    private val tarjetas = ArrayList<Pair<RectF, Portapapeles.Elemento>>()
    private val chico = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { Fuentes.aplicar(this, 400) }

    private fun altoAbajo() = dp(48f)

    override fun onDraw(c: Canvas) {
        limpiarBotones()
        tarjetas.clear()
        val w = width.toFloat(); val h = height.toFloat()
        val m = dp(6f)
        val fondoAbajo = h - altoAbajo()
        if (!activo) {
            texto.color = tema.textoSuave; texto.textSize = dp(14f)
            c.drawText("El historial del portapapeles está apagado", w / 2, h * 0.35f, texto)
            boton(c, RectF(w / 2 - dp(80f), h * 0.42f, w / 2 + dp(80f), h * 0.42f + dp(40f)), "Activar", 0, false) { oyente?.activar() }
        } else if (elementos.isEmpty()) {
            texto.color = tema.textoSuave; texto.textSize = dp(14f)
            c.drawText("Lo que copies aparecerá aquí", w / 2, h * 0.38f, texto)
            c.drawText("(se guarda una hora; fija lo que quieras conservar)", w / 2, h * 0.38f + dp(20f), texto)
        } else {
            c.save()
            c.clipRect(0f, 0f, w, fondoAbajo)
            val anchoT = (w - m * 3) / 2
            val altoT = dp(74f)
            chico.textSize = dp(13.5f); chico.color = tema.texto
            for ((i, e) in elementos.withIndex()) {
                val col = i % 2; val fila = i / 2
                val x0 = m + col * (anchoT + m)
                val y0 = m + fila * (altoT + m) - corrimiento
                val r = RectF(x0, y0, x0 + anchoT, y0 + altoT)
                tarjetas.add(r to e)
                if (r.bottom < 0 || r.top > fondoAbajo) continue
                relleno.color = if (marcado == e) tema.teclaPresionada else tema.tecla
                c.drawRoundRect(r, dp(10f), dp(10f), relleno)
                borde.color = if (e.fijo) tema.acento else tema.bordeTecla; borde.strokeWidth = dp(if (e.fijo) 1.4f else 0.8f)
                c.drawRoundRect(r, dp(10f), dp(10f), borde)
                val lay = StaticLayout.Builder.obtain(e.texto, 0, minOf(e.texto.length, 300), chico, (anchoT - dp(20f)).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                c.save(); c.translate(r.left + dp(10f), r.top + dp(9f)); lay.draw(c); c.restore()
                if (e.fijo) { icono.color = tema.acento; Iconos.chincheta(c, r.right - dp(12f), r.top + dp(14f), dp(18f), icono, true) }
            }
            val filas = (elementos.size + 1) / 2
            maxCorrimiento = (filas * (altoT + m) + m - fondoAbajo).coerceAtLeast(0f)
            c.restore()
        }
        // Abajo: ABC y acciones (o las acciones de la tarjeta marcada)
        val y0 = fondoAbajo + m / 2; val y1 = h - m
        boton(c, RectF(m, y0, dp(70f), y1), "ABC", 0, true) { oyente?.abc() }
        val e = marcado
        if (e != null) {
            val x0 = dp(76f); val paso = (w - x0 - m) / 3
            boton(c, RectF(x0, y0, x0 + paso - m, y1), if (e.fijo) "Desfijar" else "Fijar", 0, false) { oyente?.fijar(e.texto, !e.fijo); marcado = null }
            boton(c, RectF(x0 + paso, y0, x0 + 2 * paso - m, y1), "Borrar", 0, false) { oyente?.quitar(e.texto); marcado = null }
            boton(c, RectF(x0 + 2 * paso, y0, w - m, y1), "Listo", 0, true) { marcado = null; invalidate() }
        } else if (activo && elementos.any { !it.fijo }) {
            boton(c, RectF(w - dp(150f), y0, w - m, y1), "Borrar no fijados", 0, true) { oyente?.borrarTodo() }
        }
    }

    // Desplazar las tarjetas, tocar para pegar y mantener para marcar
    private var y0t = 0f; private var x0t = 0f; private var ultY = 0f
    private var enTarjetas = false; private var arrastrando = false; private var largaHecha = false
    private val larga = Runnable {
        val t = tarjetas.firstOrNull { it.first.contains(x0t, y0t) } ?: return@Runnable
        largaHecha = true; marcado = t.second
        alResponder?.invoke(Respuesta.Tipo.FUNCION)
        invalidate()
    }

    override fun toqueLibre(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            enTarjetas = e.y < height - altoAbajo() && activo && elementos.isNotEmpty()
            if (!enTarjetas) return false
            y0t = e.y; x0t = e.x; ultY = e.y; arrastrando = false; largaHecha = false
            postDelayed(larga, 500L)
            return true
        }
        if (!enTarjetas) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - y0t) > dp(8f)) { arrastrando = true; removeCallbacks(larga) }
                if (arrastrando) { corrimiento = (corrimiento - (e.y - ultY)).coerceIn(0f, maxCorrimiento); invalidate() }
                ultY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(larga)
                if (!arrastrando && !largaHecha) {
                    tarjetas.firstOrNull { it.first.contains(e.x, e.y) }?.let {
                        alResponder?.invoke(Respuesta.Tipo.LETRA)
                        oyente?.pegar(it.second.texto)
                    }
                }
                enTarjetas = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(larga); enTarjetas = false }
        }
        return true
    }
}
