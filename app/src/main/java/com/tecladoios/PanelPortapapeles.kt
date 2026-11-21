package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max

/**
 * Portapapeles en fichas: lo último que copiaste, en dos columnas.
 * Tocar una ficha la pega; mantenerla presionada la quita. Negro AMOLED como el teclado.
 */
class PanelPortapapeles(context: Context) : View(context) {

    interface Oyente {
        fun pegar(t: String)
        fun quitarRecorte(t: String)
        fun borrarPortapapeles()
        fun borrar(palabra: Boolean)
        fun abc()
        fun respuesta(t: Respuesta.Tipo)
    }

    var oyente: Oyente? = null
    var abajoSistema = 0; set(v) { field = v; requestLayout() }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var textos: List<String> = emptyList()
    private var formas: Array<StaticLayout?> = emptyArray()

    fun abrir(lista: List<String>) {
        textos = lista
        formas = arrayOfNulls(lista.size)
        scroller.forceFinished(true)
        desplazado = 0f
        invalidate()
    }

    // ---------- Medidas ----------
    private val ALTO_TITULO get() = dp(34f)
    private val ALTO_BARRA get() = dp(46f)
    private val MARGEN get() = dp(6f)
    private val ALTO_FICHA get() = dp(74f)
    private val arribaGrilla get() = ALTO_TITULO
    private val abajoGrilla get() = height - abajoSistema - ALTO_BARRA.toInt()
    private val anchoFicha get() = (width - MARGEN * 3) / 2

    private fun fichaRect(i: Int, r: RectF) {
        val col = i % 2; val fila = i / 2
        val x = MARGEN + col * (anchoFicha + MARGEN)
        val y = arribaGrilla + MARGEN + fila * (ALTO_FICHA + MARGEN) - desplazado
        r.set(x, y, x + anchoFicha, y + ALTO_FICHA)
    }

    private val maxDesplazado get() = run {
        val filas = (textos.size + 1) / 2
        val total = MARGEN + filas * (ALTO_FICHA + MARGEN)
        max(0f, total - (abajoGrilla - arribaGrilla))
    }

    // ---------- Toques ----------
    private val scroller = OverScroller(context)
    private var desplazado = 0f
    private var velocidad: VelocityTracker? = null
    private var y0 = 0f; private var ultY = 0f
    private var arrastrando = false
    private var tocado = -1
    private var hecho = false
    private var borrando = false
    private var repeticiones = 0
    private val botonAbc = RectF()
    private val botonBorrar = RectF()
    private val botonVaciar = RectF()
    private val tmp = RectF()

    private val larga = Runnable {
        val i = tocado
        if (i in textos.indices && !arrastrando) {
            hecho = true
            oyente?.respuesta(Respuesta.Tipo.FUNCION)
            val t = textos[i]
            oyente?.quitarRecorte(t)
            textos = textos.filterIndexed { k, _ -> k != i }
            formas = arrayOfNulls(textos.size)
            desplazado = desplazado.coerceIn(0f, maxDesplazado)
            tocado = -1
            invalidate()
        }
    }
    private val repetir = object : Runnable {
        override fun run() {
            if (!borrando) return
            repeticiones++
            oyente?.respuesta(Respuesta.Tipo.BORRAR)
            oyente?.borrar(repeticiones > 18)
            postDelayed(this, if (repeticiones > 18) 200L else 90L)
        }
    }

    private fun fichaEn(x: Float, y: Float): Int {
        if (y < arribaGrilla || y >= abajoGrilla) return -1
        for (i in textos.indices) { fichaRect(i, tmp); if (tmp.contains(x, y)) return i }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocidad?.recycle(); velocidad = VelocityTracker.obtain().also { it.addMovement(e) }
                y0 = y; ultY = y; arrastrando = false; hecho = false; tocado = -1
                when {
                    y >= abajoGrilla && botonBorrar.contains(x, y) -> {
                        borrando = true; repeticiones = 0
                        oyente?.respuesta(Respuesta.Tipo.BORRAR); oyente?.borrar(false)
                        postDelayed(repetir, 450L)
                    }
                    y >= abajoGrilla && botonAbc.contains(x, y) -> oyente?.respuesta(Respuesta.Tipo.FUNCION)
                    y < arribaGrilla && botonVaciar.contains(x, y) && textos.isNotEmpty() -> oyente?.respuesta(Respuesta.Tipo.FUNCION)
                    else -> {
                        tocado = fichaEn(x, y)
                        if (tocado >= 0) { oyente?.respuesta(Respuesta.Tipo.LETRA); postDelayed(larga, 550L) }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocidad?.addMovement(e)
                if (borrando || hecho) return true
                if (!arrastrando && abs(y - y0) > dp(8f) && y0 >= arribaGrilla && y0 < abajoGrilla) {
                    arrastrando = true; tocado = -1; removeCallbacks(larga)
                }
                if (arrastrando) {
                    desplazado = (desplazado - (y - ultY)).coerceIn(0f, maxDesplazado)
                    invalidate()
                }
                ultY = y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repetir); removeCallbacks(larga)
                when {
                    borrando -> borrando = false
                    hecho -> {}
                    arrastrando -> velocidad?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(0, desplazado.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxDesplazado.toInt())
                        postInvalidateOnAnimation()
                    }
                    y0 >= abajoGrilla && botonAbc.contains(x, y) -> oyente?.abc()
                    y0 < arribaGrilla && botonVaciar.contains(x, y) && textos.isNotEmpty() -> {
                        oyente?.borrarPortapapeles()
                        abrir(emptyList())
                    }
                    tocado >= 0 && fichaEn(x, y) == tocado -> oyente?.pegar(textos[tocado])
                }
                tocado = -1; arrastrando = false; hecho = false
                velocidad?.recycle(); velocidad = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repetir); removeCallbacks(larga)
                borrando = false; tocado = -1; arrastrando = false; hecho = false
                invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            desplazado = scroller.currY.toFloat().coerceIn(0f, maxDesplazado)
            postInvalidateOnAnimation()
        }
    }

    // ---------- Dibujo ----------
    private val fondo = Paint().apply { color = Color.BLACK }
    private val ficha = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
    private val fichaApretada = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF48484A.toInt() }
    private val textoFicha = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * d }
    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt(); textSize = 12f * d; isFakeBoldText = true }
    private val aviso = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt(); textSize = 15f * d; textAlign = Paint.Align.CENTER }
    private val etiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 16f * d }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val camino = Path()

    private fun forma(i: Int): StaticLayout {
        formas[i]?.let { return it }
        val t = textos[i].trim().replace(Regex("\\s*\n\\s*"), " ⏎ ")
        val ancho = (anchoFicha - dp(20f)).toInt().coerceAtLeast(1)
        val s = StaticLayout.Builder.obtain(t, 0, minOf(t.length, 300), textoFicha, ancho)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        formas[i] = s
        return s
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { formas = arrayOfNulls(textos.size) }

    override fun onDraw(cv: Canvas) {
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fondo)
        val ty = ALTO_TITULO - dp(12f)
        cv.drawText("PORTAPAPELES", dp(12f), ty, titulo)
        if (textos.isNotEmpty()) {
            titulo.textAlign = Paint.Align.RIGHT
            cv.drawText("BORRAR TODO", width - dp(12f), ty, titulo)
            titulo.textAlign = Paint.Align.LEFT
            botonVaciar.set(width - dp(120f), 0f, width.toFloat(), ALTO_TITULO)
        } else botonVaciar.setEmpty()

        if (textos.isEmpty()) {
            val cy = (arribaGrilla + abajoGrilla) / 2f
            cv.drawText("Lo que copies aparecerá aquí", width / 2f, cy - dp(6f), aviso)
            cv.drawText("Mantén presionada una ficha para quitarla", width / 2f, cy + dp(16f), titulo.also { it.textAlign = Paint.Align.CENTER })
            titulo.textAlign = Paint.Align.LEFT
        } else {
            cv.save()
            cv.clipRect(0f, arribaGrilla, width.toFloat(), abajoGrilla.toFloat())
            for (i in textos.indices) {
                fichaRect(i, tmp)
                if (tmp.bottom < arribaGrilla || tmp.top > abajoGrilla) continue
                cv.drawRoundRect(tmp, dp(8f), dp(8f), if (i == tocado) fichaApretada else ficha)
                val f = forma(i)
                cv.save()
                cv.translate(tmp.left + dp(10f), tmp.centerY() - f.height / 2f)
                f.draw(cv)
                cv.restore()
            }
            cv.restore()
        }
        dibujarBarra(cv)
    }

    private fun dibujarBarra(cv: Canvas) {
        val y0 = abajoGrilla.toFloat()
        val cy = y0 + ALTO_BARRA / 2
        val lado = dp(52f)
        botonAbc.set(0f, y0, lado, y0 + ALTO_BARRA)
        botonBorrar.set(width - lado, y0, width.toFloat(), y0 + ALTO_BARRA)
        cv.drawText("ABC", botonAbc.centerX(), cy - (etiqueta.ascent() + etiqueta.descent()) / 2, etiqueta)
        val bx = botonBorrar.centerX(); val w = dp(11f); val h = dp(8f)
        camino.reset()
        camino.moveTo(bx - w - dp(6f), cy); camino.lineTo(bx - w, cy - h); camino.lineTo(bx + w, cy - h)
        camino.lineTo(bx + w, cy + h); camino.lineTo(bx - w, cy + h); camino.close()
        cv.drawPath(camino, trazo)
        val x = dp(3.2f); val ox = bx + dp(1.5f)
        cv.drawLine(ox - x, cy - x, ox + x, cy + x, trazo); cv.drawLine(ox - x, cy + x, ox + x, cy - x, trazo)
    }
}
