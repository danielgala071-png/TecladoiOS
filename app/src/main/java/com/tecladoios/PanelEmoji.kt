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
        fun portapapeles()
        fun respuesta(t: Respuesta.Tipo)
    }

    var oyente: Oyente? = null
    /** Botón del portapapeles junto a ABC (si el historial está activado) */
    var conPortapapeles = true

    private class Categoria(val nombre: String, val icono: String, val emojis: List<String>)

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val prefs = Ajustes.prefs(context)

    private var categorias: List<Categoria> = emptyList()
    /** Dónde empieza (en px) cada sección y cuántas columnas tiene */
    private var inicioX = FloatArray(0)
    private var cols = IntArray(0)
    private var anchoTotal = 0f
    private var columnas = 0
    private var filas = 5
    private var celdaW = 1f
    private var celdaH = 1f

    /** Abajo del panel: espacio de la barra de navegación del sistema */
    var abajoSistema = 0; set(v) { field = v; requestLayout() }

    companion object {
        private const val RECIENTES = "emojis_recientes"
        private const val TONOS = "emojis_tonos"
        private val MODIFICADORES = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")
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
        prefs.edit().putString(RECIENTES, l.take(32).joinToString("\n")).apply()
    }

    // ---------- Tono de piel (mantener un emoji de manos o personas) ----------
    /** emoji base → el tono que elegiste la última vez */
    private val tonos = HashMap<String, String>()

    private fun leerTonos() {
        tonos.clear()
        prefs.getString(TONOS, null)?.split('\n')?.forEach {
            val i = it.indexOf('\t'); if (i > 0) tonos[it.substring(0, i)] = it.substring(i + 1)
        }
    }

    private fun guardarTonos() {
        prefs.edit().putString(TONOS, tonos.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    private fun conTono(e: String, mod: String): String {
        val b = e.replace("\uFE0F", "")
        val n = Character.charCount(b.codePointAt(0))
        return b.substring(0, n) + mod + b.substring(n)
    }

    /** El emoji y sus 5 tonos, si el celular los tiene; si no, vacío */
    private fun variantesDe(e: String): List<String> {
        if (MODIFICADORES.any { e.contains(it) }) return emptyList()
        val t = conTono(e, MODIFICADORES[2])
        return try { if (emoji.hasGlyph(t)) listOf(e) + MODIFICADORES.map { conTono(e, it) } else emptyList() } catch (_: Throwable) { emptyList() }
    }

    private fun mostrado(e: String) = tonos[e] ?: e

    private var tonosVisibles: List<String> = emptyList()
    private var baseTono: String? = null
    private var tonoElegido = -1
    private val cajaTonos = RectF()
    private val mantenerEmoji = Runnable {
        val cod = tocado
        if (cod < 0 || arrastrando) return@Runnable
        val base = textoDe(cod) ?: return@Runnable
        val v = variantesDe(base)
        if (v.isEmpty()) return@Runnable
        oyente?.respuesta(Respuesta.Tipo.FUNCION)
        tonosVisibles = v; baseTono = base
        // La fila de tonos aparece arriba del emoji, centrada y dentro de la pantalla
        val i = cod / 10000; val k = cod % 10000
        val cx = inicioX[i] - desplazado + (k / filas) * celdaW + celdaW / 2
        val cy = ALTO_TITULO + (k % filas) * celdaH
        val w = celdaW * 1.05f * v.size + dp(12f); val h = celdaH * 0.95f
        val izq = (cx - w / 2).coerceIn(dp(4f), width - dp(4f) - w)
        val arriba = (cy - h - dp(4f)).coerceAtLeast(dp(2f))
        cajaTonos.set(izq, arriba, izq + w, arriba + h)
        tonoElegido = v.indexOf(mostrado(base)).coerceAtLeast(0)
        invalidate()
    }

    private fun tonoEn(x: Float): Int {
        val w = (cajaTonos.width() - dp(12f)) / tonosVisibles.size
        return ((x - cajaTonos.left - dp(6f)) / w).toInt().coerceIn(0, tonosVisibles.size - 1)
    }

    /** Al abrir: arma la lista (los usados se reordenan solo al volver a abrir, como en iOS) */
    fun abrir() {
        leerTonos()
        val rec = recientes()
        val lista = ArrayList<Categoria>()
        // Siempre primero: los recientes; después las demás secciones
        lista.add(Categoria("RECIENTES", "🕘", rec))
        lista.addAll(leerBase())
        categorias = lista
        rearmar()
        scroller.forceFinished(true)
        desplazado = 0f
        invalidate()
    }

    // ---------- Medidas ----------
    private val ALTO_TITULO get() = dp(30f)
    private val ALTO_BARRA get() = dp(46f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rearmar() }

    private val SEP_SECCION get() = dp(26f)

    private fun rearmar() {
        val altoGrilla = height - abajoSistema - ALTO_TITULO - ALTO_BARRA
        if (altoGrilla <= 0 || width <= 0) return
        filas = 4
        celdaH = altoGrilla / filas
        // Unas 8 columnas por pantalla, como el iPhone
        val porPantalla = max(9, (width / (celdaH * 0.7f)).toInt())
        celdaW = (width - dp(8f)) / porPantalla
        inicioX = FloatArray(categorias.size); cols = IntArray(categorias.size)
        var x = dp(4f)
        for ((i, c) in categorias.withIndex()) {
            inicioX[i] = x
            // Los recientes vacíos dejan lugar para su aviso
            // Cada sección mide al menos lo que su título (con pocos recientes, el título no se corta
            // ni se monta sobre el de al lado)
            val porTitulo = ceil((titulo.measureText(c.nombre) + dp(12f)) / celdaW).toInt()
            val porEmojis = if (c.emojis.isEmpty()) 3 else ceil(c.emojis.size / filas.toFloat()).toInt()
            cols[i] = max(porEmojis, porTitulo)
            x += cols[i] * celdaW + SEP_SECCION
        }
        anchoTotal = x
        emoji.textSize = celdaH * 0.48f
    }

    private val maxDesplazado get() = max(0f, anchoTotal - width)

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
    private val botonClip = RectF()
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
                        botonAbc.contains(x, y) || botonClip.contains(x, y) -> oyente?.respuesta(Respuesta.Tipo.FUNCION)
                        else -> {
                            tocadoCat = botonesCat.indexOfFirst { it.contains(x, y) }
                            if (tocadoCat >= 0) oyente?.respuesta(Respuesta.Tipo.FUNCION)
                        }
                    }
                } else {
                    tocado = emojiEn(x, y)
                    if (tocado >= 0) postDelayed(mantenerEmoji, 420L)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocidad?.addMovement(e)
                if (borrando) return true
                if (tonosVisibles.isNotEmpty()) {
                    val n = tonoEn(x)
                    if (n != tonoElegido) { tonoElegido = n; invalidate() }
                    return true
                }
                if (!arrastrando && abs(x - x0) > dp(8f) && y < height - abajoSistema - ALTO_BARRA) { arrastrando = true; tocado = -1; removeCallbacks(mantenerEmoji) }
                if (arrastrando) {
                    desplazado = (desplazado - (x - ultX)).coerceIn(0f, maxDesplazado)
                    invalidate()
                }
                ultX = x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repetir); removeCallbacks(mantenerEmoji)
                if (tonosVisibles.isNotEmpty()) {
                    // Elegiste un tono: se escribe y se recuerda para ese emoji
                    val base = baseTono
                    val elegido = tonosVisibles.getOrNull(tonoElegido)
                    if (base != null && elegido != null) {
                        if (elegido == base) tonos.remove(base) else tonos[base] = elegido
                        guardarTonos()
                        oyente?.respuesta(Respuesta.Tipo.LETRA)
                        oyente?.emoji(elegido)
                        usar(elegido)
                    }
                    tonosVisibles = emptyList(); baseTono = null; tonoElegido = -1
                    tocado = -1; tocadoCat = -1; arrastrando = false
                    velocidad?.recycle(); velocidad = null
                    invalidate()
                    return true
                }
                if (borrando) { borrando = false }
                else if (arrastrando) {
                    velocidad?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(desplazado.toInt(), 0, -it.xVelocity.toInt(), 0, 0, maxDesplazado.toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                } else if (botonAbc.contains(x, y) && y >= height - abajoSistema - ALTO_BARRA) {
                    oyente?.abc()
                } else if (botonClip.contains(x, y) && y >= height - abajoSistema - ALTO_BARRA) {
                    oyente?.portapapeles()
                } else if (tocadoCat >= 0 && botonesCat.getOrNull(tocadoCat)?.contains(x, y) == true) {
                    irA(tocadoCat)
                } else if (tocado >= 0) {
                    textoDe(tocado)?.let { mostrado(it) }?.let { em ->
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
                removeCallbacks(repetir); removeCallbacks(mantenerEmoji)
                tonosVisibles = emptyList(); baseTono = null
                borrando = false; tocado = -1; tocadoCat = -1; arrastrando = false
                invalidate()
            }
        }
        return true
    }

    private fun irA(cat: Int) {
        scroller.forceFinished(true)
        val destino = (inicioX.getOrElse(cat) { 0f } - dp(4f)).coerceIn(0f, maxDesplazado)
        scroller.startScroll(desplazado.toInt(), 0, (destino - desplazado).toInt(), 0, 250)
        postInvalidateOnAnimation()
    }

    /** Índice global (categoría, posición) codificado como cat * 10000 + pos */
    private fun emojiEn(x: Float, y: Float): Int {
        val gy = y - ALTO_TITULO
        if (gy < 0) return -1
        val fila = (gy / celdaH).toInt()
        if (fila !in 0 until filas) return -1
        val gx = x + desplazado
        for (i in categorias.indices.reversed()) {
            if (gx >= inicioX[i]) {
                val col = ((gx - inicioX[i]) / celdaW).toInt()
                if (col >= cols[i]) return -1
                val pos = col * filas + fila
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
    private val marcaCat = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val camino = Path()
    private val cajaTonoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
    private val bordeTonoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = 0xFF48484A.toInt() }
    private val marcaTono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF48484A.toInt() }

    private fun categoriaActual(): Int {
        var r = 0
        for (i in inicioX.indices) if (desplazado + celdaW >= inicioX[i]) r = i
        if (desplazado >= maxDesplazado - 1f && maxDesplazado > 0f) r = categorias.size - 1
        return r
    }

    override fun onDraw(cv: Canvas) {
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fondo)
        if (categorias.isEmpty()) return
        val actual = categoriaActual()
        val ajusteY = -(emoji.ascent() + emoji.descent()) / 2
        val tituloY = ALTO_TITULO - dp(9f)
        for ((i, c) in categorias.withIndex()) {
            val x0 = inicioX[i] - desplazado
            val x1 = x0 + cols[i] * celdaW
            if (x1 < -celdaW || x0 > width + celdaW) continue
            // Título de cada sección al comienzo; el de la sección actual se queda fijo a la izquierda
            val siguiente = if (i + 1 < inicioX.size) inicioX[i + 1] - desplazado else Float.MAX_VALUE
            val ancho = titulo.measureText(c.nombre)
            val tx = if (i == actual) minOf(max(x0, dp(12f)), siguiente - ancho - SEP_SECCION) else max(x0, dp(12f))
            cv.drawText(c.nombre, tx, tituloY, titulo)
            if (c.emojis.isEmpty()) {
                val cx = (x0 + x1) / 2
                titulo.textAlign = Paint.Align.CENTER
                cv.drawText("Los emojis que", cx, ALTO_TITULO + celdaH * 1.7f, titulo)
                cv.drawText("uses aparecerán", cx, ALTO_TITULO + celdaH * 1.7f + dp(16f), titulo)
                cv.drawText("aquí", cx, ALTO_TITULO + celdaH * 1.7f + dp(32f), titulo)
                titulo.textAlign = Paint.Align.LEFT
                continue
            }
            for ((k, em) in c.emojis.withIndex()) {
                val cx = x0 + (k / filas) * celdaW + celdaW / 2
                if (cx < -celdaW || cx > width + celdaW) continue
                val cy = ALTO_TITULO + (k % filas) * celdaH + celdaH / 2
                if (tocado == i * 10000 + k) cv.drawRoundRect(cx - celdaW / 2 + dp(2f), cy - celdaH / 2 + dp(2f), cx + celdaW / 2 - dp(2f), cy + celdaH / 2 - dp(2f), dp(8f), dp(8f), marca)
                cv.drawText(mostrado(em), cx, cy + ajusteY, emoji)
            }
        }
        dibujarBarra(cv, actual)
        if (tonosVisibles.isNotEmpty()) {
            cv.drawRoundRect(cajaTonos, dp(12f), dp(12f), cajaTonoP)
            cv.drawRoundRect(cajaTonos, dp(12f), dp(12f), bordeTonoP)
            val w = (cajaTonos.width() - dp(12f)) / tonosVisibles.size
            for ((k, t) in tonosVisibles.withIndex()) {
                val cx = cajaTonos.left + dp(6f) + w * k + w / 2
                if (k == tonoElegido) cv.drawRoundRect(cx - w / 2 + dp(1f), cajaTonos.top + dp(4f), cx + w / 2 - dp(1f), cajaTonos.bottom - dp(4f), dp(8f), dp(8f), marcaTono)
                cv.drawText(t, cx, cajaTonos.centerY() + ajusteY, emoji)
            }
        }
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
        // Portapapeles: una tablilla con su pinza, en el mismo trazo blanco
        val clip = if (conPortapapeles) dp(44f) else 0f
        botonClip.set(lado, y0, lado + clip, y0 + ALTO_BARRA)
        if (conPortapapeles) {
            val px = botonClip.centerX()
            cv.drawRoundRect(px - dp(7f), cy - dp(8.5f), px + dp(7f), cy + dp(9f), dp(2.5f), dp(2.5f), trazo)
            cv.drawRoundRect(px - dp(3.5f), cy - dp(11f), px + dp(3.5f), cy - dp(6.5f), dp(1.5f), dp(1.5f), trazo)
            cv.drawLine(px - dp(3.5f), cy - dp(1f), px + dp(3.5f), cy - dp(1f), trazo)
            cv.drawLine(px - dp(3.5f), cy + dp(3.5f), px + dp(1.5f), cy + dp(3.5f), trazo)
        }
        // Categorías
        val ancho = (width - 2 * lado - clip) / categorias.size
        icono.textSize = dp(19f)
        if (botonesCat.size != categorias.size) { botonesCat.clear(); repeat(categorias.size) { botonesCat.add(RectF()) } }
        for ((i, c) in categorias.withIndex()) {
            val cx = lado + clip + ancho * i + ancho / 2
            botonesCat[i].set(cx - ancho / 2, y0, cx + ancho / 2, y0 + ALTO_BARRA)
            if (i == actual) {
                // Categoría actual: chip del color de tu fondo
                marcaCat.color = Acento.oscuro(Acento.color, 0.5f)
                cv.drawRoundRect(cx - dp(17f), cy - dp(14f), cx + dp(17f), cy + dp(14f), dp(14f), dp(14f), marcaCat)
            }
            cv.drawText(c.icono, cx, cy - (icono.ascent() + icono.descent()) / 2, icono)
        }
    }
}
