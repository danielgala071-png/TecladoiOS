package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.util.Locale

/**
 * Emojis grandes que se desplazan de lado, como en el iPhone, con sus categorías abajo,
 * los usados recientemente primero, tonos de piel (mantén presionado) y búsqueda.
 */
class PanelEmoji(context: Context) : View(context) {

    interface Oyente {
        fun emoji(e: String)
        fun borrar()
        fun abc()
        fun buscar()
        fun respuesta(tipo: Respuesta.Tipo)
    }

    class Categoria(val nombre: String, val emojis: List<String>)

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val prefs = Ajustes.prefs(context)

    companion object {
        private var cache: List<Categoria>? = null
        private const val FILAS = 5
        private val TONOS = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

        fun cargar(c: Context): List<Categoria> {
            cache?.let { return it }
            val p = Paint()
            val lista = ArrayList<Categoria>()
            try {
                var nombre: String? = null
                c.assets.open("emojis.txt").bufferedReader().useLines { ls ->
                    for (l in ls) {
                        if (l.isEmpty() || l[0] == '#') continue
                        if (l[0] == '@') { nombre = l.substring(1).trim(); continue }
                        val n = nombre ?: continue
                        lista.add(Categoria(n, l.trim().split(' ').filter { it.isNotEmpty() && p.hasGlyph(it) }))
                    }
                }
            } catch (e: Throwable) { Registro.anotar("emojis", e) }
            // Banderas: Perú primero
            val paises = listOf("PE") + Locale.getISOCountries().filter { it != "PE" }
            val banderas = paises.map { cc -> cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") }
                .filter { p.hasGlyph(it) }
            lista.add(Categoria("BANDERAS", listOf("\uD83C\uDFC1", "\uD83D\uDEA9", "\uD83C\uDFF4", "\uD83C\uDFF3\uFE0F") + banderas))
            cache = lista
            return lista
        }

        fun admiteTono(e: String): Boolean = try {
            val cp = e.codePointAt(0)
            UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_MODIFIER_BASE) && !e.contains("\u200D")
        } catch (_: Throwable) { false }

        fun conTono(e: String, tono: String): String {
            val cp = e.codePointAt(0)
            val base = String(Character.toChars(cp))
            val resto = e.substring(base.length).removePrefix("\uFE0F")
            return base + tono + resto
        }
    }

    private var categorias: List<Categoria> = emptyList()
    private var inicioCol = IntArray(0)
    private var totalCols = 0

    fun refrescar() {
        val base = cargar(context)
        val rec = recientes()
        categorias = listOf(Categoria("USADOS RECIENTEMENTE", rec)) + base
        inicioCol = IntArray(categorias.size)
        var col = 0
        for ((i, c) in categorias.withIndex()) {
            inicioCol[i] = col
            col += maxOf(1, (c.emojis.size + FILAS - 1) / FILAS)
        }
        totalCols = col
        corrimiento = 0f
        saltarRecientes = rec.isEmpty()
        scroller.forceFinished(true)
        invalidate()
    }

    private fun recientes(): List<String> =
        prefs.getString("emojis_recientes", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun sumarReciente(e: String) {
        val l = recientes().toMutableList()
        l.remove(e); l.add(0, e)
        prefs.edit().putString("emojis_recientes", l.take(36).joinToString("\n")).apply()
    }

    // ---------- Medidas ----------

    private fun altoAbajo() = dp(44f)
    private fun arribaGrilla() = dp(26f)
    private fun celda() = (height - altoAbajo() - arribaGrilla()) / FILAS.toFloat()

    private var corrimiento = 0f
    private var saltarRecientes = false
    private val scroller = OverScroller(context)

    private fun maxCorrimiento() = (totalCols * celda() - width + dp(8f)).coerceAtLeast(0f)

    private fun categoriaActual(): Int {
        val col = ((corrimiento + celda() * 0.5f) / celda()).toInt()
        var i = 0
        for (k in inicioCol.indices) if (inicioCol[k] <= col) i = k
        return i
    }

    private fun emojiEn(x: Float, y: Float): String? {
        if (y < arribaGrilla() || y > height - altoAbajo()) return null
        val col = ((x + corrimiento - dp(4f)) / celda()).toInt()
        val fila = ((y - arribaGrilla()) / celda()).toInt().coerceIn(0, FILAS - 1)
        for (k in categorias.indices.reversed()) {
            if (col >= inicioCol[k]) {
                val idx = (col - inicioCol[k]) * FILAS + fila
                return categorias[k].emojis.getOrNull(idx)
            }
        }
        return null
    }

    // ---------- Dibujo ----------

    private val emojiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { Fuentes.aplicar(this, 600) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    // Tonos de piel
    private var tonosDe: String? = null
    private val cajaTonos = RectF()
    private var tonoElegido = -1

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            corrimiento = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(c: Canvas) {
        if (categorias.isEmpty()) refrescar()
        val w = width.toFloat(); val h = height.toFloat()
        val cel = celda()
        if (cel <= 0f) return
        if (saltarRecientes) { saltarRecientes = false; corrimiento = (inicioCol.getOrElse(1) { 0 } * cel).coerceAtMost(maxCorrimiento()) }
        // Título de la categoría
        titulo.textSize = dp(12f); titulo.color = tema.textoSuave
        val actual = categoriaActual()
        c.drawText(categorias.getOrNull(actual)?.nombre ?: "", dp(14f), dp(18f), titulo)
        // Grilla
        emojiP.textSize = cel * 0.74f
        c.save()
        c.clipRect(0f, arribaGrilla(), w, h - altoAbajo())
        val primeraCol = (corrimiento / cel).toInt()
        val ultimaCol = ((corrimiento + w) / cel).toInt() + 1
        for ((k, cat) in categorias.withIndex()) {
            val c0 = inicioCol[k]
            val c1 = c0 + (cat.emojis.size + FILAS - 1) / FILAS
            if (c1 < primeraCol || c0 > ultimaCol) continue
            for ((i, e) in cat.emojis.withIndex()) {
                val col = c0 + i / FILAS
                if (col < primeraCol || col > ultimaCol) continue
                val fila = i % FILAS
                val x = dp(4f) + col * cel - corrimiento + cel / 2
                val y = arribaGrilla() + fila * cel + cel / 2 + emojiP.textSize * 0.36f
                c.drawText(e, x, y, emojiP)
            }
        }
        if (categorias.firstOrNull()?.emojis?.isEmpty() == true && corrimiento < cel * 2) {
            titulo.color = tema.textoSuave; titulo.textSize = dp(13f)
            c.drawText("Aquí aparecerán tus emojis", dp(14f) - corrimiento, arribaGrilla() + cel, titulo)
        }
        c.restore()
        // Barra de abajo: ABC, buscar, categorías, borrar
        val yb = h - altoAbajo()
        val chico = Paint(titulo).apply { textAlign = Paint.Align.CENTER; textSize = dp(15f); color = tema.texto }
        r.set(dp(6f), yb + dp(6f), dp(58f), h - dp(6f))
        relleno.color = tema.teclaFuncion
        c.drawRoundRect(r, dp(7f), dp(7f), relleno)
        c.drawText("ABC", r.centerX(), r.centerY() + chico.textSize * 0.36f, chico)
        icono.color = tema.textoSuave
        Iconos.lupa(c, dp(80f), yb + altoAbajo() / 2, dp(30f), icono)
        val izq = dp(100f); val der = w - dp(60f)
        val paso = (der - izq) / categorias.size
        emojiP.textSize = minOf(paso * 0.62f, dp(20f))
        for ((k, cat) in categorias.withIndex()) {
            val cx = izq + paso * k + paso / 2
            if (k == actual) {
                relleno.color = Tema.mezclar(tema.acento, 0, 0.35f)
                c.drawCircle(cx, yb + altoAbajo() / 2, minOf(paso * 0.48f, dp(16f)), relleno)
            }
            val ic = if (k == 0) "\uD83D\uDD52" else cat.emojis.firstOrNull() ?: "•"
            c.drawText(ic, cx, yb + altoAbajo() / 2 + emojiP.textSize * 0.36f, emojiP)
        }
        icono.color = tema.texto
        Iconos.borrar(c, w - dp(30f), yb + altoAbajo() / 2, dp(40f), icono, false)
        // Tonos
        val base = tonosDe
        if (base != null) {
            relleno.color = tema.globo
            c.drawRoundRect(cajaTonos, dp(10f), dp(10f), relleno)
            val anch = cajaTonos.width() / 6
            emojiP.textSize = anch * 0.7f
            val opciones = listOf(base) + TONOS.map { conTono(base, it) }
            for ((i, e) in opciones.withIndex()) {
                val x0 = cajaTonos.left + anch * i
                if (i == tonoElegido) {
                    relleno.color = tema.acento
                    c.drawRoundRect(x0 + dp(2f), cajaTonos.top + dp(4f), x0 + anch - dp(2f), cajaTonos.bottom - dp(4f), dp(7f), dp(7f), relleno)
                }
                c.drawText(e, x0 + anch / 2, cajaTonos.centerY() + emojiP.textSize * 0.36f, emojiP)
            }
        }
    }

    // ---------- Toques ----------

    private var x0 = 0f; private var y0 = 0f; private var ultX = 0f
    private var arrastrando = false
    private var velocidad: android.view.VelocityTracker? = null
    private var borrando = false
    private val repetirBorrar = object : Runnable {
        override fun run() { if (borrando) { oyente?.borrar(); postDelayed(this, 70L) } }
    }
    private val larga = Runnable {
        val e = emojiEn(x0, y0) ?: return@Runnable
        if (!admiteTono(e)) return@Runnable
        tonosDe = e
        val anch = celda() * 0.95f
        var izq = x0 - anch * 3
        izq = izq.coerceIn(dp(4f), width - anch * 6 - dp(4f))
        val arriba = (y0 - celda() * 1.6f).coerceAtLeast(dp(2f))
        cajaTonos.set(izq, arriba, izq + anch * 6, arriba + celda() * 1.05f)
        tonoElegido = 0
        oyente?.respuesta(Respuesta.Tipo.FUNCION)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                x0 = e.x; y0 = e.y; ultX = e.x; arrastrando = false
                velocidad?.recycle(); velocidad = android.view.VelocityTracker.obtain(); velocidad?.addMovement(e)
                if (e.y > h - altoAbajo() && e.x > width - dp(60f)) {
                    borrando = true; oyente?.borrar(); oyente?.respuesta(Respuesta.Tipo.BORRAR)
                    postDelayed(repetirBorrar, 420L)
                } else if (e.y < h - altoAbajo()) postDelayed(larga, 380L)
            }
            MotionEvent.ACTION_MOVE -> {
                velocidad?.addMovement(e)
                if (tonosDe != null) {
                    val anch = cajaTonos.width() / 6
                    val i = ((e.x - cajaTonos.left) / anch).toInt().coerceIn(0, 5)
                    if (i != tonoElegido) { tonoElegido = i; invalidate() }
                    return true
                }
                if (!arrastrando && kotlin.math.abs(e.x - x0) > dp(8f) && e.y < h - altoAbajo()) {
                    arrastrando = true; removeCallbacks(larga)
                }
                if (arrastrando) {
                    corrimiento = (corrimiento - (e.x - ultX)).coerceIn(0f, maxCorrimiento())
                    invalidate()
                }
                ultX = e.x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(larga); borrando = false; removeCallbacks(repetirBorrar)
                val base = tonosDe
                if (base != null) {
                    val elegido = if (tonoElegido <= 0) base else conTono(base, TONOS[tonoElegido - 1])
                    tonosDe = null
                    elegir(elegido)
                    invalidate()
                    return true
                }
                if (arrastrando) {
                    velocidad?.computeCurrentVelocity(1000)
                    val vx = velocidad?.xVelocity ?: 0f
                    scroller.fling(corrimiento.toInt(), 0, -vx.toInt(), 0, 0, maxCorrimiento().toInt(), 0, 0)
                    postInvalidateOnAnimation()
                } else tocar(e.x, e.y)
                velocidad?.recycle(); velocidad = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(larga); borrando = false; removeCallbacks(repetirBorrar); tonosDe = null
                velocidad?.recycle(); velocidad = null; invalidate()
            }
        }
        return true
    }

    private fun tocar(x: Float, y: Float) {
        val h = height.toFloat()
        if (y > h - altoAbajo()) {
            when {
                x < dp(62f) -> { oyente?.respuesta(Respuesta.Tipo.FUNCION); oyente?.abc() }
                x < dp(98f) -> { oyente?.respuesta(Respuesta.Tipo.FUNCION); oyente?.buscar() }
                x < width - dp(60f) -> {
                    val izq = dp(100f); val der = width - dp(60f)
                    val k = ((x - izq) / ((der - izq) / categorias.size)).toInt().coerceIn(0, categorias.size - 1)
                    scroller.forceFinished(true)
                    corrimiento = (inicioCol[k] * celda()).coerceAtMost(maxCorrimiento())
                    oyente?.respuesta(Respuesta.Tipo.SUAVE)
                    invalidate()
                }
            }
            return
        }
        emojiEn(x, y)?.let { elegir(it) }
    }

    private fun elegir(e: String) {
        oyente?.respuesta(Respuesta.Tipo.LETRA)
        oyente?.emoji(e)
        sumarReciente(e)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        borrando = false; removeCallbacks(repetirBorrar); removeCallbacks(larga)
    }
}
