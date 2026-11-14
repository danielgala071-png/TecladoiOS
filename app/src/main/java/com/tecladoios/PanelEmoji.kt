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
            val especiales = listOf(
                "\uD83C\uDFC1", "\uD83D\uDEA9", "\uD83C\uDF8C", "\uD83C\uDFF4", "\uD83C\uDFF3\uFE0F",
                "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08", "\uD83C\uDFF3\uFE0F\u200D\u26A7\uFE0F", "\uD83C\uDFF4\u200D\u2620\uFE0F",
                "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
                "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F",
                "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F",
            ).filter { p.hasGlyph(it) }
            lista.add(Categoria("BANDERAS", especiales + banderas))
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
    private var columnas = IntArray(0)
    private var inicioX = FloatArray(0)
    private var anchoTotal = 0f
    private var celdaArmada = -1f

    /** Vuelve a leer los recientes y arma las secciones (cada categoría con su título) */
    fun refrescar() {
        val base = cargar(context)
        categorias = listOf(Categoria("USADOS RECIENTEMENTE", recientes())) + base
        celdaArmada = -1f
        corrimiento = 0f
        scroller.forceFinished(true)
        invalidate()
    }

    private fun armarSecciones(cel: Float) {
        if (cel == celdaArmada) return
        celdaArmada = cel
        val n = categorias.size
        columnas = IntArray(n); inicioX = FloatArray(n)
        var x = dp(10f)
        for ((k, cat) in categorias.withIndex()) {
            inicioX[k] = x
            // Recientes vacío: un espacio para el aviso, sin pisar la sección que sigue
            columnas[k] = if (cat.emojis.isEmpty()) 0 else (cat.emojis.size + FILAS - 1) / FILAS
            x += anchoSeccion(k, cel) + SEPARACION_DP * d
        }
        anchoTotal = x
    }

    private fun recientes(): List<String> =
        prefs.getString("emojis_recientes", null)?.split('\n')?.filter { it.isNotEmpty() }?.take(30) ?: emptyList()

    private fun sumarReciente(e: String) {
        val l = (prefs.getString("emojis_recientes", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()).toMutableList()
        l.remove(e); l.add(0, e)
        prefs.edit().putString("emojis_recientes", l.take(30).joinToString("\n")).apply()
    }

    // ---------- Medidas ----------

    private val SEPARACION_DP = 30f

    /** Columna un poco más ancha que alta: los emojis respiran como en el iPhone */
    private fun anchoCol(cel: Float) = cel * 1.12f

    /** Ancho de cada sección; la de recientes siempre deja lugar para al menos 6 columnas */
    private fun anchoSeccion(k: Int, cel: Float): Float {
        val cols = if (k == 0) maxOf(columnas[k], 6) else columnas[k]
        return cols * anchoCol(cel)
    }
    private fun altoAbajo() = dp(46f)
    private fun arribaGrilla() = dp(30f)
    private fun celda() = (height - altoAbajo() - arribaGrilla() - dp(4f)) / FILAS.toFloat()

    private var corrimiento = 0f
    private val scroller = OverScroller(context)

    private fun maxCorrimiento() = (anchoTotal - width).coerceAtLeast(0f)

    private fun categoriaActual(): Int {
        val x = corrimiento + width * 0.3f
        var i = 0
        for (k in inicioX.indices) if (inicioX[k] <= x) i = k
        return if (corrimiento >= maxCorrimiento() - 1f && maxCorrimiento() > 0f) inicioX.size - 1 else i
    }

    private fun emojiEn(x: Float, y: Float): String? {
        if (y < arribaGrilla() || y > height - altoAbajo()) return null
        if (columnas.size != categorias.size) return null
        val cel = celda()
        val ax = x + corrimiento
        val fila = ((y - arribaGrilla()) / cel).toInt().coerceIn(0, FILAS - 1)
        for (k in categorias.indices) {
            val ancho = columnas[k] * anchoCol(cel)
            if (ax >= inicioX[k] && ax < inicioX[k] + ancho) {
                val col = ((ax - inicioX[k]) / anchoCol(cel)).toInt()
                return categorias[k].emojis.getOrNull(col * FILAS + fila)
            }
        }
        return null
    }

    // ---------- Dibujo ----------

    private val emojiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { Fuentes.aplicar(this, 600); letterSpacing = 0.03f }
    private val aviso = Paint(Paint.ANTI_ALIAS_FLAG).apply { Fuentes.aplicar(this, 400) }
    private val boton = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    // Tonos de piel
    private var tonosDe: String? = null
    private val cajaTonos = RectF()
    private var tonoElegido = -1

    // Emoji presionado (se achica un poco, como en el iPhone)
    private var presionado: String? = null

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
        armarSecciones(cel)
        val yAbajo = h - altoAbajo()

        // Títulos: cada sección tiene el suyo; el de la sección actual se queda pegado a la izquierda
        titulo.textSize = dp(12f); titulo.color = tema.textoSuave
        for (k in categorias.indices) {
            val ancho = anchoSeccion(k, cel)
            val x0 = inicioX[k] - corrimiento
            if (x0 > w || x0 + ancho < 0) continue
            val nombre = categorias[k].nombre
            val tw = titulo.measureText(nombre)
            var tx = maxOf(x0 + dp(4f), dp(14f))
            tx = minOf(tx, x0 + ancho - tw)            // no se sale de su sección
            tx = maxOf(tx, x0 + dp(4f))
            c.drawText(nombre, tx, dp(21f), titulo)
        }

        // Emojis (solo las columnas visibles)
        emojiP.textSize = cel * 0.68f
        val colW = anchoCol(cel)
        c.save()
        c.clipRect(0f, arribaGrilla() - dp(2f), w, yAbajo)
        for ((k, cat) in categorias.withIndex()) {
            val x0 = inicioX[k] - corrimiento
            if (cat.emojis.isEmpty()) {
                val anchoR = anchoSeccion(k, cel)
                if (x0 < w && x0 + anchoR > 0) {
                    // Aviso centrado en el espacio reservado, con un marco punteado tenue
                    r.set(x0 + dp(2f), arribaGrilla() + dp(4f), x0 + anchoR - dp(2f), arribaGrilla() + cel * FILAS - dp(6f))
                    relleno.style = Paint.Style.STROKE; relleno.strokeWidth = d
                    relleno.color = 0x24FFFFFF
                    relleno.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
                    c.drawRoundRect(r, dp(14f), dp(14f), relleno)
                    relleno.pathEffect = null; relleno.style = Paint.Style.FILL
                    aviso.textSize = dp(13.5f); aviso.color = tema.textoSuave; aviso.textAlign = Paint.Align.CENTER
                    c.drawText("Aquí aparecerán", r.centerX(), r.centerY() - dp(4f), aviso)
                    c.drawText("tus emojis", r.centerX(), r.centerY() + dp(14f), aviso)
                    aviso.textAlign = Paint.Align.LEFT
                }
                continue
            }
            val ancho = columnas[k] * colW
            if (x0 > w || x0 + ancho < 0) continue
            val primera = ((-x0) / colW).toInt().coerceAtLeast(0)
            val ultima = minOf(columnas[k] - 1, ((w - x0) / colW).toInt())
            for (col in primera..ultima) for (fila in 0 until FILAS) {
                val e = cat.emojis.getOrNull(col * FILAS + fila) ?: break
                val cx = x0 + col * colW + colW / 2
                val cy = arribaGrilla() + fila * cel + cel / 2
                if (e == presionado) {
                    relleno.color = 0x22FFFFFF
                    c.drawRoundRect(cx - cel * 0.46f, cy - cel * 0.46f, cx + cel * 0.46f, cy + cel * 0.46f, dp(10f), dp(10f), relleno)
                }
                c.drawText(e, cx, cy + emojiP.textSize * 0.35f, emojiP)
            }
        }
        c.restore()

        // Línea fina que separa la barra de categorías
        relleno.color = tema.separador
        c.drawRect(0f, yAbajo, w, yAbajo + d * 0.6f, relleno)

        // Abajo: ABC, buscar, categorías, borrar
        boton.textSize = dp(15f); boton.color = tema.texto
        r.set(dp(6f), yAbajo + dp(7f), dp(58f), h - dp(6f))
        relleno.color = tema.teclaFuncion
        c.drawRoundRect(r, dp(8f), dp(8f), relleno)
        c.drawText("ABC", r.centerX(), r.centerY() + boton.textSize * 0.36f, boton)
        icono.color = tema.textoSuave
        Iconos.lupa(c, dp(80f), yAbajo + altoAbajo() / 2, dp(30f), icono)
        val izq = dp(100f); val der = w - dp(60f)
        val paso = (der - izq) / categorias.size
        val actual = categoriaActual()
        emojiP.textSize = minOf(paso * 0.58f, dp(19f))
        for (k in categorias.indices) {
            val cx = izq + paso * k + paso / 2
            val cy = yAbajo + altoAbajo() / 2
            if (k == actual) {
                relleno.color = 0x2EFFFFFF
                c.drawCircle(cx, cy, minOf(paso * 0.46f, dp(16f)), relleno)
            }
            val ic = when (k) { 0 -> "\uD83D\uDD52"; else -> categorias[k].emojis.firstOrNull() ?: "•" }
            emojiP.alpha = if (k == actual) 255 else 150
            c.drawText(ic, cx, cy + emojiP.textSize * 0.35f, emojiP)
        }
        emojiP.alpha = 255
        icono.color = tema.texto
        Iconos.borrar(c, w - dp(30f), yAbajo + altoAbajo() / 2, dp(40f), icono, false)

        // Tonos de piel
        val base = tonosDe
        if (base != null) {
            relleno.color = 0x66000000
            c.drawRoundRect(cajaTonos.left, cajaTonos.top + dp(2f), cajaTonos.right, cajaTonos.bottom + dp(2f), dp(12f), dp(12f), relleno)
            relleno.color = tema.globo
            c.drawRoundRect(cajaTonos, dp(12f), dp(12f), relleno)
            val anch = cajaTonos.width() / 6
            emojiP.textSize = anch * 0.68f
            val opciones = listOf(base) + TONOS.map { conTono(base, it) }
            for ((i, e) in opciones.withIndex()) {
                val x0 = cajaTonos.left + anch * i
                if (i == tonoElegido) {
                    relleno.color = tema.acento
                    c.drawRoundRect(x0 + dp(3f), cajaTonos.top + dp(5f), x0 + anch - dp(3f), cajaTonos.bottom - dp(5f), dp(8f), dp(8f), relleno)
                }
                c.drawText(e, x0 + anch / 2, cajaTonos.centerY() + emojiP.textSize * 0.35f, emojiP)
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
        presionado = null
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
                } else if (e.y < h - altoAbajo()) {
                    presionado = emojiEn(e.x, e.y)
                    if (presionado != null) invalidate()
                    postDelayed(larga, 380L)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocidad?.addMovement(e)
                if (tonosDe != null) {
                    val anch = cajaTonos.width() / 6
                    val i = ((e.x - cajaTonos.left) / anch).toInt().coerceIn(0, 5)
                    if (i != tonoElegido) { tonoElegido = i; oyente?.respuesta(Respuesta.Tipo.SUAVE); invalidate() }
                    return true
                }
                if (!arrastrando && kotlin.math.abs(e.x - x0) > dp(8f) && e.y < h - altoAbajo()) {
                    arrastrando = true; removeCallbacks(larga); presionado = null
                }
                if (arrastrando) {
                    corrimiento = (corrimiento - (e.x - ultX)).coerceIn(0f, maxCorrimiento())
                    invalidate()
                }
                ultX = e.x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(larga); borrando = false; removeCallbacks(repetirBorrar)
                presionado = null
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
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(larga); borrando = false; removeCallbacks(repetirBorrar); tonosDe = null; presionado = null
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
                    // Desliza suave hasta la categoría
                    val destino = (inicioX.getOrElse(k) { 0f } - dp(10f)).coerceIn(0f, maxCorrimiento())
                    scroller.forceFinished(true)
                    scroller.startScroll(corrimiento.toInt(), 0, (destino - corrimiento).toInt(), 0, 320)
                    oyente?.respuesta(Respuesta.Tipo.SUAVE)
                    postInvalidateOnAnimation()
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
        velocidad?.recycle(); velocidad = null
    }
}
