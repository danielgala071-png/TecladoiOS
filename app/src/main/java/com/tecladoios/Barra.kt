package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Barra de arriba del teclado. Mientras escribes muestra tres sugerencias (la del centro es la
 * mejor, y se pinta del color de la app si se pondrá sola al tocar espacio; a la izquierda,
 * entre comillas, lo que escribiste tal cual). Sin escribir muestra las herramientas:
 * portapapeles, edición de texto, una mano, escanear texto y ajustes, más lo último que
 * copiaste o tu captura reciente para enviarlos rápido.
 */
class Barra(context: Context) : View(context) {

    interface Oyente {
        fun elegir(texto: String, esLiteral: Boolean)
        fun olvidar(texto: String)
        fun herramienta(cual: Int)
        fun pegarChip()
        fun enviarCaptura()
        fun emojiBuscado(e: String)
        fun cerrarBusqueda()
        fun respuesta(tipo: Respuesta.Tipo)
    }

    companion object {
        const val H_PORTAPAPELES = 1
        const val H_EDITAR = 2
        const val H_UNA_MANO = 3
        const val H_ESCANEAR = 4
        const val H_AJUSTES = 5
        const val H_EMOJI = 6
        const val H_TAMANO = 7
    }

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }
    var herramientasActivas = true

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // Contenido
    private var sugerencias: List<String> = emptyList()
    private var literal: String? = null
    private var destacada = -1   // índice que se pondrá solo
    private var chipTexto: String? = null
    private var captura: Bitmap? = null
    private var buscando = false
    private var consulta = ""
    private var resultados: List<String> = emptyList()
    private var corrimiento = 0f

    fun mostrarSugerencias(lista: List<String>, literalEntreComillas: String?, autocorrige: Boolean) {
        val nuevas = lista.take(3)
        if (nuevas == sugerencias && literalEntreComillas == literal && (autocorrige && lista.isNotEmpty()) == (destacada >= 0)) return
        val cambiaModo = sugerencias.isEmpty() && literal == null
        sugerencias = nuevas; literal = literalEntreComillas
        destacada = if (autocorrige && lista.isNotEmpty()) 0 else -1
        animar(if (cambiaModo) 230f else 150f, cambiaModo)
    }

    fun limpiar() {
        if (sugerencias.isEmpty() && literal == null) return
        sugerencias = emptyList(); literal = null; destacada = -1
        animar(230f, true)
    }

    // ---------- Animación (las palabras aparecen suave; herramientas y sugerencias se cruzan) ----------

    private var animInicio = 0L
    private var animDura = 0f
    private var animSube = false

    private fun animar(duracion: Float, subir: Boolean) {
        animInicio = android.os.SystemClock.uptimeMillis(); animDura = duracion; animSube = subir
        invalidate()
    }

    fun hayContenido() = sugerencias.isNotEmpty() || literal != null

    fun chip(texto: String?) { chipTexto = texto; invalidate() }

    fun captura(b: Bitmap?) { captura = b; invalidate() }

    fun busqueda(activa: Boolean, q: String, res: List<String>) {
        buscando = activa; consulta = q; resultados = res; corrimiento = 0f; invalidate()
    }

    // ---------- Dibujo ----------

    private val texto = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 400) }
    private val fuerte = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 600) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linea = Paint(Paint.ANTI_ALIAS_FLAG)
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private val zonas = ArrayList<Pair<RectF, () -> Any?>>()
    private val zonasLargas = ArrayList<Pair<RectF, () -> Any?>>()

    private val HERRAMIENTAS = intArrayOf(H_EMOJI, H_PORTAPAPELES, H_EDITAR, H_UNA_MANO, H_TAMANO, H_ESCANEAR, H_AJUSTES)

    override fun onDraw(c: Canvas) {
        zonas.clear(); zonasLargas.clear()
        val w = width.toFloat(); val h = height.toFloat()
        // Línea finísima que separa la barra de las teclas (detalle premium, casi invisible)
        linea.color = 0x14FFFFFF; linea.strokeWidth = d * 0.6f
        c.drawLine(dp(18f), h - d * 0.6f, w - dp(18f), h - d * 0.6f, linea)
        val t = if (animDura > 0f) ((android.os.SystemClock.uptimeMillis() - animInicio) / animDura).coerceIn(0f, 1f) else 1f
        if (t < 1f) {
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            c.saveLayerAlpha(0f, 0f, w, h, (90 + 165 * e).toInt())
            c.translate(0f, if (animSube) (1f - e) * dp(7f) else (1f - e) * dp(2.5f))
            dibujarContenido(c, w, h)
            c.restore()
            postInvalidateOnAnimation()
        } else dibujarContenido(c, w, h)
    }

    private fun dibujarContenido(c: Canvas, w: Float, h: Float) {
        texto.textSize = dp(16.5f); fuerte.textSize = dp(16.5f)
        linea.color = tema.separador; linea.strokeWidth = dp(1f)
        if (buscando) { dibujarBusqueda(c, w, h); return }
        if (sugerencias.isNotEmpty() || literal != null) { dibujarSugerencias(c, w, h); return }
        dibujarHerramientas(c, w, h)
    }

    private fun dibujarSugerencias(c: Canvas, w: Float, h: Float) {
        // Orden: [izquierda, centro (la mejor), derecha]
        val celdas = arrayOfNulls<String>(3)
        val esLiteral = BooleanArray(3)
        val mejor = sugerencias.getOrNull(0)
        celdas[1] = mejor
        if (literal != null) { celdas[0] = literal; esLiteral[0] = true; celdas[2] = sugerencias.getOrNull(1) }
        else { celdas[0] = sugerencias.getOrNull(1); celdas[2] = sugerencias.getOrNull(2) }
        val ancho = w / 3
        for (i in 0 until 3) {
            val s = celdas[i] ?: continue
            val x0 = i * ancho
            val p = if (i == 1 && destacada >= 0) fuerte else texto
            p.color = if (i == 1 && destacada >= 0) tema.acento else tema.texto
            val mostrado = if (esLiteral[i]) "«$s»" else s
            val corto = TextUtils.ellipsize(mostrado, p, ancho - dp(22f), TextUtils.TruncateAt.MIDDLE).toString()
            if (i == 1 && destacada >= 0) {
                // La palabra que se pondrá sola va en una cápsula suave del color de la app
                val pw = minOf(p.measureText(corto) + dp(26f), ancho - dp(8f))
                relleno.color = Tema.mezclar(tema.acento, 0xFF000000.toInt(), 0.2f)
                c.drawRoundRect(x0 + ancho / 2 - pw / 2, h / 2 - dp(15f), x0 + ancho / 2 + pw / 2, h / 2 + dp(15f), dp(15f), dp(15f), relleno)
            }
            c.drawText(corto, x0 + ancho / 2, h / 2 + p.textSize * 0.36f, p)
            val rr = RectF(x0, 0f, x0 + ancho, h)
            val lit = esLiteral[i]
            zonas.add(rr to { oyente?.elegir(s, lit) })
            if (!lit) zonasLargas.add(rr to { oyente?.olvidar(s) })
        }
        for (i in 1..2) c.drawLine(i * ancho, h * 0.28f, i * ancho, h * 0.72f, linea)
    }

    private fun dibujarHerramientas(c: Canvas, w: Float, h: Float) {
        var izq = dp(8f)
        // Chips: captura reciente y lo último copiado
        val cap = captura
        if (cap != null) {
            val lado = h - dp(12f)
            r.set(izq, dp(6f), izq + lado, dp(6f) + lado)
            c.save()
            val camino = android.graphics.Path().apply { addRoundRect(r, dp(6f), dp(6f), android.graphics.Path.Direction.CW) }
            c.clipPath(camino)
            c.drawBitmap(cap, null, r, null)
            c.restore()
            relleno.style = Paint.Style.STROKE; relleno.strokeWidth = dp(1f); relleno.color = tema.acento
            c.drawRoundRect(r, dp(6f), dp(6f), relleno)
            relleno.style = Paint.Style.FILL
            zonas.add(RectF(r) to { oyente?.enviarCaptura() })
            izq = r.right + dp(8f)
        }
        val chip = chipTexto
        val anchoHerr = dp(42f)
        val herrs = if (herramientasActivas) HERRAMIENTAS else intArrayOf(H_AJUSTES)
        val espacioHerr = herrs.size * anchoHerr
        if (chip != null) {
            texto.textSize = dp(14f)
            val maxChip = (w - izq - espacioHerr - dp(16f)).coerceAtLeast(dp(60f))
            val corto = TextUtils.ellipsize(chip.replace('\n', ' '), texto, maxChip - dp(36f), TextUtils.TruncateAt.END).toString()
            val anchoChip = texto.measureText(corto) + dp(36f)
            r.set(izq, dp(7f), izq + anchoChip, h - dp(7f))
            relleno.color = tema.teclaFuncion
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
            relleno.style = Paint.Style.STROKE; relleno.strokeWidth = dp(1f); relleno.color = Tema.mezclar(tema.acento, 0, 0.7f)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
            relleno.style = Paint.Style.FILL
            icono.color = tema.textoSuave
            Iconos.portapapeles(c, r.left + dp(16f), r.centerY(), dp(22f), icono)
            texto.color = tema.texto
            texto.textAlign = Paint.Align.LEFT
            c.drawText(corto, r.left + dp(28f), r.centerY() + texto.textSize * 0.36f, texto)
            texto.textAlign = Paint.Align.CENTER
            zonas.add(RectF(r) to { oyente?.pegarChip() })
            texto.textSize = dp(16.5f)
        }
        // Herramientas a la derecha
        var x = w - dp(4f) - espacioHerr
        if (chip == null && cap == null) x = (w - espacioHerr) / 2f
        icono.color = tema.textoSuave
        val s = dp(30f)
        for (hId in herrs) {
            val cx = x + anchoHerr / 2; val cy = h / 2
            // Cada herramienta en un botón redondo oscuro
            val apretado = toqueX in x..(x + anchoHerr) && tocando
            relleno.color = if (apretado) 0xFF3A3A3D.toInt() else 0xFF1C1C1E.toInt()
            c.drawCircle(cx, cy, dp(17f), relleno)
            // Aro de luz finísimo: el botón se ve con relieve
            linea.color = 0x1AFFFFFF; linea.strokeWidth = d * 0.7f
            linea.style = Paint.Style.STROKE
            c.drawCircle(cx, cy, dp(17f), linea)
            linea.style = Paint.Style.FILL_AND_STROKE
            when (hId) {
                H_EMOJI -> Iconos.cara(c, cx, cy, s, icono)
                H_PORTAPAPELES -> Iconos.portapapeles(c, cx, cy, s, icono)
                H_EDITAR -> Iconos.editar(c, cx, cy, s, icono)
                H_UNA_MANO -> Iconos.unaMano(c, cx, cy, s, icono)
                H_ESCANEAR -> Iconos.escanear(c, cx, cy, s, icono)
                H_TAMANO -> Iconos.tamano(c, cx, cy, s, icono)
                H_AJUSTES -> Iconos.engranaje(c, cx, cy, s, icono)
            }
            val id = hId
            zonas.add(RectF(x, 0f, x + anchoHerr, h) to { oyente?.herramienta(id) })
            x += anchoHerr
        }
    }

    private fun dibujarBusqueda(c: Canvas, w: Float, h: Float) {
        icono.color = tema.textoSuave
        Iconos.lupa(c, dp(20f), h / 2, dp(30f), icono)
        texto.textAlign = Paint.Align.LEFT
        texto.textSize = dp(15f)
        texto.color = if (consulta.isEmpty()) tema.textoSuave else tema.texto
        val q = if (consulta.isEmpty()) "Buscar emoji" else consulta
        val anchoQ = minOf(texto.measureText(q), w * 0.3f)
        c.drawText(TextUtils.ellipsize(q, texto, w * 0.3f, TextUtils.TruncateAt.START).toString(), dp(36f), h / 2 + texto.textSize * 0.36f, texto)
        texto.textAlign = Paint.Align.CENTER
        // Cursor
        relleno.color = tema.acento
        c.drawRect(dp(37f) + anchoQ, h * 0.28f, dp(39f) + anchoQ, h * 0.72f, relleno)
        val inicio = dp(46f) + anchoQ
        val fin = w - dp(40f)
        icono.color = tema.textoSuave
        Iconos.cerrar(c, w - dp(20f), h / 2, dp(32f), icono)
        zonas.add(RectF(fin, 0f, w, h) to { oyente?.cerrarBusqueda() })
        c.save()
        c.clipRect(inicio, 0f, fin, h)
        texto.textSize = dp(26f)
        val celda = dp(42f)
        var x = inicio - corrimiento
        for (e in resultados) {
            if (x + celda > inicio && x < fin) {
                c.drawText(e, x + celda / 2, h / 2 + texto.textSize * 0.36f, texto)
                zonas.add(RectF(x, 0f, x + celda, h) to { oyente?.emojiBuscado(e) })
            }
            x += celda
        }
        maxCorrimiento = (resultados.size * celda - (fin - inicio)).coerceAtLeast(0f)
        c.restore()
        texto.textSize = dp(16.5f)
    }

    private var maxCorrimiento = 0f

    // ---------- Toques ----------

    private var toqueX = -1f
    private var tocando = false
    private var x0 = 0f
    private var ultX = 0f
    private var arrastrando = false
    private var largaHecha = false
    private val larga = Runnable {
        val z = zonasLargas.firstOrNull { it.first.contains(x0, height / 2f) }
        if (z != null) { largaHecha = true; oyente?.respuesta(Respuesta.Tipo.FUNCION); z.second() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; ultX = e.x; arrastrando = false; largaHecha = false
                toqueX = e.x; tocando = true; invalidate()
                postDelayed(larga, 550L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - x0) > dp(10f)) { arrastrando = true; removeCallbacks(larga) }
                if (arrastrando && buscando) {
                    corrimiento = (corrimiento - (e.x - ultX)).coerceIn(0f, maxCorrimiento)
                    invalidate()
                }
                ultX = e.x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(larga)
                tocando = false; invalidate()
                if (!arrastrando && !largaHecha) {
                    val z = zonas.firstOrNull { it.first.contains(e.x, e.y) }
                    if (z != null) { oyente?.respuesta(Respuesta.Tipo.LETRA); z.second() }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(larga); tocando = false; invalidate() }
        }
        return true
    }
}
