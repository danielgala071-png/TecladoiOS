package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Emojis como en el iPhone: se desplazan de lado, con «Usados frecuentemente» primero y las
 * categorías abajo, junto a ABC y borrar. Negro AMOLED.
 */
class PanelEmoji(context: Context) : View(context) {

    interface Oyente {
        fun emoji(e: String)
        fun borrar(palabra: Boolean)
        fun abc()
        fun respuesta(t: Respuesta.Tipo)
    }

    var oyente: Oyente? = null

    private class Categoria(val nombre: String, val icono: String, val emojis: List<String>)

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val prefs = Ajustes.prefs(context)

    private var categorias: List<Categoria> = emptyList()
    /** Columna donde empieza cada categoría */
    private var inicio = IntArray(0)
    private var columnas = 0
    private var filas = 5
    private var celdaW = 1f
    private var celdaH = 1f

    /** Abajo del panel: espacio de la barra de navegación del sistema */
    var abajoSistema = 0; set(v) { field = v; requestLayout() }

    companion object {
        private const val RECIENTES = "emojis_recientes"
        private var base: List<Categoria>? = null
        fun soltarCache() { base = null }
        private val NOMBRES = mapOf(
            "CARAS" to "EMOTICONOS Y PERSONAS", "CORAZONES" to "EMOTICONOS Y PERSONAS",
            "MANOS Y CUERPO" to "EMOTICONOS Y PERSONAS", "PERSONAS" to "EMOTICONOS Y PERSONAS",
            "ROPA Y ACCESORIOS" to "EMOTICONOS Y PERSONAS",
            "ANIMALES" to "ANIMALES Y NATURALEZA", "PLANTAS Y CLIMA" to "ANIMALES Y NATURALEZA",
            "COMIDA Y BEBIDA" to "COMIDA Y BEBIDA", "ACTIVIDADES" to "ACTIVIDAD",
            "VIAJES Y LUGARES" to "VIAJES Y LUGARES", "OBJETOS" to "OBJETOS",
            "SEÑALES Y SÍMBOLOS" to "SÍMBOLOS",
        )
        private val ICONOS = mapOf(
            "EMOTICONOS Y PERSONAS" to "😀", "ANIMALES Y NATURALEZA" to "🐻", "COMIDA Y BEBIDA" to "🍔",
            "ACTIVIDAD" to "⚽", "VIAJES Y LUGARES" to "🚗", "OBJETOS" to "💡", "SÍMBOLOS" to "🔣",
        )
    }

    /** Lee las categorías del archivo y las junta como las del iPhone */
    private fun leerBase(): List<Categoria> {
        base?.let { return it }
        val grupos = LinkedHashMap<String, ArrayList<String>>()
        try {
            var actual: ArrayList<String>? = null
            context.assets.open("emojis.txt").bufferedReader().useLines { lineas ->
                for (l in lineas) {
                    when {
                        l.startsWith("#") || l.isBlank() || l.startsWith("%") -> {}
                        l.startsWith("@") -> {
                            val nombre = l.substring(1).split('|')[0]
                            val junto = NOMBRES[nombre] ?: nombre
                            actual = grupos.getOrPut(junto) { ArrayList() }
                        }
                        else -> actual?.addAll(l.trim().split(' ').filter { it.isNotBlank() })
                    }
                }
            }
        } catch (_: Throwable) { }
        val r = grupos.map { (n, l) -> Categoria(n, ICONOS[n] ?: l.firstOrNull() ?: "🙂", l) }
        base = r
        return r
    }

    private fun recientes(): List<String> =
        prefs.getString(RECIENTES, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun usar(e: String) {
        val l = ArrayList(recientes()); l.remove(e); l.add(0, e)
        prefs.edit().putString(RECIENTES, l.take(30).joinToString("\n")).apply()
    }

    /** Al abrir: arma la lista (los usados se reordenan solo al volver a abrir, como en iOS) */
    fun abrir() {
        val rec = recientes()
        val lista = ArrayList<Categoria>()
        lista.add(Categoria("USADOS FRECUENTEMENTE", "🕘", rec))
        lista.addAll(leerBase())
        categorias = lista
        rearmar()
        scroller.forceFinished(true)
        desplazado = if (rec.isEmpty() && inicio.size > 1) inicio[1] * celdaW else 0f
        invalidate()
    }

    // ---------- Medidas ----------
    private val ALTO_TITULO get() = dp(30f)
    private val ALTO_BARRA get() = dp(46f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rearmar() }

    private fun rearmar() {
        val altoGrilla = height - abajoSistema - ALTO_TITULO - ALTO_BARRA
        if (altoGrilla <= 0 || width <= 0) return
        filas = if (altoGrilla / 5 >= dp(40f)) 5 else 4
        celdaH = altoGrilla / filas
        // Columnas que entran enteras en la pantalla (como el iPhone, ~9 en vertical)
        val porPantalla = max(6, (width / (celdaH * 1.05f)).toInt())
        celdaW = (width - dp(8f)) / porPantalla
        inicio = IntArray(categorias.size)
        var col = 0
        for ((i, c) in categorias.withIndex()) {
            inicio[i] = col
            col += ceil(c.emojis.size / filas.toFloat()).toInt().coerceAtLeast(if (i == 0) 1 else 0)
        }
        columnas = col
        emoji.textSize = celdaH * 0.74f
    }

    private val maxDesplazado get() = max(0f, columnas * celdaW + dp(8f) - width)

    // ---------- Toques ----------
    private val scroller = OverScroller(context)
    private var desplazado = 0f
    private var velocidad: VelocityTracker? = null
    private var x0 = 0f; private var ultX = 0f
    private var arrastrando = false
    private var tocado = -1      // índice global del emoji bajo el dedo
    private var tocadoCat = -1
    private var borrando = false
    private var repeticiones = 0
    private val repetir = object : Runnable {
        override fun run() {
            if (!borrando) return
            repeticiones++
            oyente?.respuesta(Respuesta.Tipo.BORRAR)
            oyente?.borrar(repeticiones > 18)
            postDelayed(this, if (repeticiones > 18) 200L else 90L)
        }
    }

    private val botonAbc = RectF()
    private val botonBorrar = RectF()
    private val botonesCat = ArrayList<RectF>()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocidad?.recycle(); velocidad = VelocityTracker.obtain().also { it.addMovement(e) }
                x0 = x; ultX = x; arrastrando = false; tocado = -1; tocadoCat = -1
                val barraY = height - abajoSistema - ALTO_BARRA
                if (y >= barraY) {
                    when {
                        botonBorrar.contains(x, y) -> {
                            borrando = true; repeticiones = 0
                            oyente?.respuesta(Respuesta.Tipo.BORRAR); oyente?.borrar(false)
                            postDelayed(repetir, 450L)
                        }
                        botonAbc.contains(x, y) -> oyente?.respuesta(Respuesta.Tipo.FUNCION)
                        else -> {
                            tocadoCat = botonesCat.indexOfFirst { it.contains(x, y) }
                            if (tocadoCat >= 0) oyente?.respuesta(Respuesta.Tipo.FUNCION)
                        }
                    }
                } else tocado = emojiEn(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocidad?.addMovement(e)
                if (borrando) return true
                if (!arrastrando && abs(x - x0) > dp(8f) && y < height - abajoSistema - ALTO_BARRA) { arrastrando = true; tocado = -1 }
                if (arrastrando) {
                    desplazado = (desplazado - (x - ultX)).coerceIn(0f, maxDesplazado)
                    invalidate()
                }
                ultX = x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repetir)
                if (borrando) { borrando = false }
                else if (arrastrando) {
                    velocidad?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(desplazado.toInt(), 0, -it.xVelocity.toInt(), 0, 0, maxDesplazado.toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                } else if (botonAbc.contains(x, y) && y >= height - abajoSistema - ALTO_BARRA) {
                    oyente?.abc()
                } else if (tocadoCat >= 0 && botonesCat.getOrNull(tocadoCat)?.contains(x, y) == true) {
                    irA(tocadoCat)
                } else if (tocado >= 0) {
                    textoDe(tocado)?.let { em ->
                        oyente?.respuesta(Respuesta.Tipo.LETRA)
                        oyente?.emoji(em)
                        usar(em)
                    }
                }
                tocado = -1; tocadoCat = -1; arrastrando = false
                velocidad?.recycle(); velocidad = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repetir); borrando = false; tocado = -1; tocadoCat = -1; arrastrando = false
                invalidate()
            }
        }
        return true
    }

    private fun irA(cat: Int) {
        scroller.forceFinished(true)
        val destino = (inicio.getOrElse(cat) { 0 } * celdaW).coerceIn(0f, maxDesplazado)
        scroller.startScroll(desplazado.toInt(), 0, (destino - desplazado).toInt(), 0, 250)
        postInvalidateOnAnimation()
    }

    /** Índice global (categoría, posición) codificado como cat * 10000 + pos */
    private fun emojiEn(x: Float, y: Float): Int {
        val gy = y - ALTO_TITULO
        if (gy < 0) return -1
        val fila = (gy / celdaH).toInt()
        if (fila !in 0 until filas) return -1
        val col = ((x - dp(4f) + desplazado) / celdaW).toInt()
        for (i in categorias.indices.reversed()) {
            if (col >= inicio[i]) {
                val pos = (col - inicio[i]) * filas + fila
                return if (pos < categorias[i].emojis.size) i * 10000 + pos else -1
            }
        }
        return -1
    }

    private fun textoDe(codigo: Int): String? = categorias.getOrNull(codigo / 10000)?.emojis?.getOrNull(codigo % 10000)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            desplazado = scroller.currX.toFloat().coerceIn(0f, maxDesplazado)
            postInvalidateOnAnimation()
        }
    }

    // ---------- Dibujo ----------
    private val fondo = Paint().apply { color = Color.BLACK }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Íconos de categoría en gris, como en el iPhone
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = 150
    }
    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt(); textSize = 12f * d; isFakeBoldText = true }
    private val etiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 16f * d }
    private val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val camino = Path()

    private fun categoriaActual(): Int {
        val col = ((desplazado + celdaW * 0.5f) / celdaW).toInt()
        var r = 0
        for (i in inicio.indices) if (col >= inicio[i] && categorias[i].emojis.isNotEmpty()) r = i
        if (desplazado >= maxDesplazado - 1f && maxDesplazado > 0f) r = categorias.size - 1
        return r
    }

    override fun onDraw(cv: Canvas) {
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fondo)
        if (categorias.isEmpty()) return
        val actual = categoriaActual()
        cv.drawText(categorias[actual].nombre, dp(12f), ALTO_TITULO - dp(9f), titulo)
        // Emojis visibles
        val primera = max(0, (desplazado / celdaW).toInt() - 1)
        val ultima = ((desplazado + width) / celdaW).toInt() + 1
        val ajusteY = -(emoji.ascent() + emoji.descent()) / 2
        for ((i, c) in categorias.withIndex()) {
            val col0 = inicio[i]
            val cols = ceil(c.emojis.size / filas.toFloat()).toInt()
            if (col0 + cols < primera || col0 > ultima) continue
            for ((k, em) in c.emojis.withIndex()) {
                val col = col0 + k / filas
                if (col < primera || col > ultima) continue
                val fila = k % filas
                val cx = dp(4f) + col * celdaW - desplazado + celdaW / 2
                val cy = ALTO_TITULO + fila * celdaH + celdaH / 2
                if (tocado == i * 10000 + k) cv.drawRoundRect(cx - celdaW / 2 + dp(2f), cy - celdaH / 2 + dp(2f), cx + celdaW / 2 - dp(2f), cy + celdaH / 2 - dp(2f), dp(8f), dp(8f), marca)
                cv.drawText(em, cx, cy + ajusteY, emoji)
            }
        }
        if (categorias[0].emojis.isEmpty() && actual == 0) {
            titulo.textAlign = Paint.Align.CENTER
            cv.drawText("Los emojis que uses aparecerán aquí", width / 2f, ALTO_TITULO + celdaH * filas / 2, titulo)
            titulo.textAlign = Paint.Align.LEFT
        }
        dibujarBarra(cv, actual)
    }

    private fun dibujarBarra(cv: Canvas, actual: Int) {
        val y0 = height - abajoSistema - ALTO_BARRA
        val cy = y0 + ALTO_BARRA / 2
        val lado = dp(52f)
        botonAbc.set(0f, y0, lado, y0 + ALTO_BARRA)
        botonBorrar.set(width - lado, y0, width.toFloat(), y0 + ALTO_BARRA)
        cv.drawText("ABC", botonAbc.centerX(), cy - (etiqueta.ascent() + etiqueta.descent()) / 2, etiqueta)
        // Borrar
        val bx = botonBorrar.centerX(); val w = dp(11f); val h = dp(8f)
        camino.reset()
        camino.moveTo(bx - w - dp(6f), cy); camino.lineTo(bx - w, cy - h); camino.lineTo(bx + w, cy - h)
        camino.lineTo(bx + w, cy + h); camino.lineTo(bx - w, cy + h); camino.close()
        cv.drawPath(camino, trazo)
        val x = dp(3.2f); val ox = bx + dp(1.5f)
        cv.drawLine(ox - x, cy - x, ox + x, cy + x, trazo); cv.drawLine(ox - x, cy + x, ox + x, cy - x, trazo)
        // Categorías
        val ancho = (width - 2 * lado) / categorias.size
        icono.textSize = dp(19f)
        if (botonesCat.size != categorias.size) { botonesCat.clear(); repeat(categorias.size) { botonesCat.add(RectF()) } }
        for ((i, c) in categorias.withIndex()) {
            val cx = lado + ancho * i + ancho / 2
            botonesCat[i].set(cx - ancho / 2, y0, cx + ancho / 2, y0 + ALTO_BARRA)
            if (i == actual) cv.drawCircle(cx, cy, dp(15f), marca)
            cv.drawText(c.icono, cx, cy - (icono.ascent() + icono.descent()) / 2, icono)
        }
    }
}
