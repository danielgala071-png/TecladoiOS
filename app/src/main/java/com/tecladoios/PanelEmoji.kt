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

    /** Categoría con sus grupos ([grupos] = cuántos emojis tiene cada grupo, en orden) */
    class Categoria(val nombre: String, val emojis: List<String>, val grupos: IntArray = intArrayOf(emojis.size))

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
                val emojis = ArrayList<String>()
                val grupos = ArrayList<Int>()
                fun cerrar() {
                    val n = nombre ?: return
                    val g = grupos.filter { it > 0 }
                    if (emojis.isNotEmpty()) lista.add(Categoria(n, emojis.toList(), g.toIntArray()))
                    emojis.clear(); grupos.clear()
                }
                c.assets.open("emojis.txt").bufferedReader().useLines { ls ->
                    for (l in ls) {
                        if (l.isEmpty() || l[0] == '#') continue
                        if (l[0] == '@') { cerrar(); nombre = l.substring(1).trim(); continue }
                        if (l[0] == '%') { grupos.add(0); continue }
                        if (nombre == null) continue
                        if (grupos.isEmpty()) grupos.add(0)
                        val nuevos = l.trim().split(' ').filter { it.isNotEmpty() && p.hasGlyph(it) }
                        emojis.addAll(nuevos)
                        grupos[grupos.size - 1] = grupos[grupos.size - 1] + nuevos.size
                    }
                }
                cerrar()
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
        // Como el iPhone: tus usados recientemente y luego cada categoría con su título, todo
        // seguido al deslizar (sin cortes dentro de cada categoría)
        val base = cargar(context)
        // Para animar: cómo estaban los recientes la vez anterior y cuándo se abrió el panel
        recientesAntes = categorias.firstOrNull()?.emojis ?: emptyList()
        abiertoMs = android.os.SystemClock.uptimeMillis()
        // Mientras el panel sube queda quieto (sin redibujar): así la subida va fluida
        congelado = true
        atajos = base.map { it.emojis.firstOrNull() ?: "•" }
        inicioCat = IntArray(0)
        categorias = listOf(Categoria("Usados recientemente", recientes())) +
            base.map { Categoria(nombreBonito(it.nombre), it.emojis) }
        celdaArmada = -1f
        corrimiento = 0f
        indicadorX = -1f
        scroller.forceFinished(true)
        invalidate()
    }

    // ---------- Animaciones del panel ----------
    private var recientesAntes: List<String> = emptyList()
    private var animandoGrilla = false
    private var abiertoMs = 0L
    /**
     * true mientras el panel sube o baja: no se anima nada por dentro, así Android solo mueve
     * una imagen ya hecha (eso era lo que se veía entrecortado).
     */
    private var congelado = false

    /** Termina la subida: recién ahí los recientes se acomodan con su animación */
    fun soltar() {
        if (!congelado) return
        congelado = false
        // Ahorro: los recientes quedan en su lugar sin animarse
        abiertoMs = android.os.SystemClock.uptimeMillis() - if (Ahorro.activo) 100_000L else 60L
        invalidate()
    }

    /** Congela el panel antes de bajarlo */
    fun congelar() { congelado = true }

    /**
     * Deja dibujados los emojis antes de abrir el panel (en segundo plano), así el primer
     * cuadro de la subida no tiene que dibujar nada nuevo. [alto] = alto del teclado.
     */
    fun preparar(alto: Int) {
        if (alto <= 0) return
        if (categorias.isEmpty()) { refrescar(); congelado = false }
        val cel = (alto - altoAbajo() - arribaGrilla() - dp(4f)) / FILAS.toFloat()
        if (cel <= 0f) return
        precalentar((cel * 0.68f).toInt(), 0)
    }
    /** Recientes: el que usaste entra primero y empuja a los demás */
    private val EMPUJE_DURA = 320f
    // Emoji tocado que salta hacia arriba
    private var saltoEmoji: String? = null
    private var saltoX = 0f
    private var saltoY = 0f
    private var saltoMs = 0L
    private val SALTO_DURA = 230f

    private fun suaveP(t: Float): Float { val u = 1f - t; return 1f - u * u * u }

    /** Dónde empieza cada grupo dentro de la lista de todos (para los atajos de abajo) */
    private var inicioCat = IntArray(0)
    private var atajos: List<String> = emptyList()
    /** Posición de cada atajo: 0 = recientes, luego cada grupo */
    private var paradasX = FloatArray(0)

    /** «EMOTICONOS Y PERSONAS» → «Emoticonos y personas» (los títulos del iPhone) */
    private fun nombreBonito(n: String): String =
        n.lowercase(java.util.Locale("es")).replaceFirstChar { it.uppercaseChar() }

    // Cada grupo de una categoría es un bloque aparte: empieza en su propia columna y se llena
    // por filas (de izquierda a derecha), así los emojis parecidos quedan juntos y en orden.
    private var bloqueX = Array(0) { FloatArray(0) }     // inicio de cada bloque (relativo a la sección)
    private var bloqueIni = Array(0) { IntArray(0) }     // primer emoji del bloque
    private var bloqueN = Array(0) { IntArray(0) }       // cuántos emojis tiene
    private var bloqueCols = Array(0) { IntArray(0) }    // cuántas columnas ocupa
    private val SEPARACION_GRUPO_DP = 16f

    private fun armarSecciones(cel: Float) {
        if (cel == celdaArmada) return
        celdaArmada = cel
        val n = categorias.size
        columnas = IntArray(n); inicioX = FloatArray(n)
        bloqueX = Array(n) { FloatArray(0) }; bloqueIni = Array(n) { IntArray(0) }
        bloqueN = Array(n) { IntArray(0) }; bloqueCols = Array(n) { IntArray(0) }
        val colW = anchoCol(cel)
        var x = dp(10f)
        for ((k, cat) in categorias.withIndex()) {
            inicioX[k] = x
            val tamanos = if (k == 0 || cat.grupos.isEmpty()) intArrayOf(cat.emojis.size) else cat.grupos
            val m = tamanos.size
            val bx = FloatArray(m); val bi = IntArray(m); val bn = IntArray(m); val bc = IntArray(m)
            var dentro = 0f; var ini = 0; var cols = 0
            for (g in 0 until m) {
                val cuantos = tamanos[g]
                bx[g] = dentro; bi[g] = ini; bn[g] = cuantos
                bc[g] = if (cuantos == 0) 0 else (cuantos + FILAS - 1) / FILAS
                dentro += bc[g] * colW + (if (g < m - 1) SEPARACION_GRUPO_DP * d else 0f)
                ini += cuantos; cols += bc[g]
            }
            bloqueX[k] = bx; bloqueIni[k] = bi; bloqueN[k] = bn; bloqueCols[k] = bc
            // Recientes: siempre su casilla (al menos 6 columnas), aunque esté vacía
            columnas[k] = cols
            x += anchoSeccion(k, cel) + SEPARACION_DP * d
        }
        anchoTotal = x
        // Atajos de abajo: el inicio de cada sección
        paradasX = inicioX.copyOf()
    }

    /** Emoji del bloque [g] de la categoría [k] en (columna, fila); null si ese lugar está vacío */
    private fun emojiBloque(k: Int, g: Int, col: Int, fila: Int): String? {
        val cols = bloqueCols[k][g]
        if (col < 0 || col >= cols) return null
        // Por columnas (de arriba abajo y luego a la derecha), como el iPhone: el orden se lee
        // de corrido al deslizar
        val i = col * FILAS + fila
        if (i >= bloqueN[k][g]) return null
        return categorias[k].emojis.getOrNull(bloqueIni[k][g] + i)
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
        if (k == 0) return maxOf(columnas[k], 6) * anchoCol(cel)
        val bx = bloqueX.getOrNull(k) ?: return columnas[k] * anchoCol(cel)
        if (bx.isEmpty()) return 0f
        val ult = bx.size - 1
        return bx[ult] + bloqueCols[k][ult] * anchoCol(cel)
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
        for (k in paradasX.indices) if (paradasX[k] <= x) i = k
        return if (corrimiento >= maxCorrimiento() - 1f && maxCorrimiento() > 0f) (paradasX.size - 1).coerceAtLeast(0) else i
    }

    private fun emojiEn(x: Float, y: Float): String? {
        if (y < arribaGrilla() || y > height - altoAbajo()) return null
        if (columnas.size != categorias.size) return null
        val cel = celda()
        val ax = x + corrimiento
        val fila = ((y - arribaGrilla()) / cel).toInt().coerceIn(0, FILAS - 1)
        val colW = anchoCol(cel)
        for (k in categorias.indices) {
            val rel = ax - inicioX[k]
            if (rel < 0 || rel >= anchoSeccion(k, cel)) continue
            val bx = bloqueX[k]
            for (g in bx.indices) {
                val desde = bx[g]
                if (rel >= desde && rel < desde + bloqueCols[k][g] * colW)
                    return emojiBloque(k, g, ((rel - desde) / colW).toInt(), fila)
            }
            return null
        }
        return null
    }

    // ---------- Dibujo ----------

    private val emojiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // ---------- Emojis ya dibujados (para deslizar fluido) ----------
    //
    // Dibujar un emoji a color es caro; al deslizar se repetía con cada emoji en cada cuadro y
    // por eso se sentía lento. Ahora cada emoji se dibuja UNA vez en una imagen chiquita y luego
    // solo se copia (muy rápido). Se guardan los más recientes, con un tope de memoria.
    // Imágenes de los emojis ya dibujados: con espacio para casi todos, así cambiar de
    // categoría no tiene que dibujar decenas de emojis de golpe (eso era el tironcito)
    private val imagenes = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt().coerceIn(16 * 1024, 72 * 1024)) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount / 1024
    }
    private val pintaImagen = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emojiDibujo = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun imagenEmoji(e: String, tam: Int): android.graphics.Bitmap? {
        if (tam <= 0) return null
        val clave = "$tam:$e"
        imagenes.get(clave)?.let { return it }
        return try {
            val lado = (tam * 1.18f).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(lado, lado, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            emojiDibujo.textSize = tam.toFloat()
            cv.drawText(e, lado / 2f, lado / 2f + tam * 0.35f, emojiDibujo)
            imagenes.put(clave, bmp)
            bmp
        } catch (_: Throwable) { null }
    }

    @Volatile private var tamPrecalentado = -1
    /** Categoría que conviene preparar primero (la que acabas de tocar) */
    @Volatile private var prioridad = 0
    @Volatile private var vueltaPrecalentar = 0

    // Hilo propio y de baja prioridad: prepara los emojis sin frenar al cerebro ni la pantalla
    private val hiloEmojis = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "emojis").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }

    /**
     * Deja listos en segundo plano los emojis, empezando por la categoría [desde] y siguiendo
     * por las de al lado: al deslizar o tocar otra categoría ya están dibujados.
     */
    private fun precalentar(tam: Int, desde: Int = 0) {
        if (tam <= 0) return
        if (tam == tamPrecalentado && desde == prioridad) return
        tamPrecalentado = tam; prioridad = desde
        val vuelta = ++vueltaPrecalentar
        val cats = categorias
        if (cats.isEmpty()) return
        // Orden: la elegida, luego alternando derecha e izquierda
        val orden = ArrayList<Int>(cats.size)
        orden.add(desde.coerceIn(0, cats.size - 1))
        for (k in 1 until cats.size) {
            if (desde + k < cats.size) orden.add(desde + k)
            if (desde - k >= 0) orden.add(desde - k)
        }
        hiloEmojis.execute {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = tam.toFloat() }
            val lado = (tam * 1.18f).toInt()
            for (k in orden) for (e in cats[k].emojis) {
                if (vuelta != vueltaPrecalentar || tamPrecalentado != tam) return@execute
                if (imagenes.get("$tam:$e") != null) continue
                try {
                    val bmp = android.graphics.Bitmap.createBitmap(lado, lado, android.graphics.Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawText(e, lado / 2f, lado / 2f + tam * 0.35f, p)
                    imagenes.put("$tam:$e", bmp)
                } catch (_: Throwable) { return@execute }
                // Si el caché ya está lleno, no tiene sentido seguir (se pisarían entre sí)
                if (imagenes.size() >= imagenes.maxSize() - lado * lado * 4 / 1024) return@execute
            }
        }
    }

    /** Dibuja el emoji centrado en (cx, cy) con alto de letra [tam], usando la imagen guardada */
    private fun dibujarEmoji(c: Canvas, e: String, cx: Float, cy: Float, tam: Float, alfa: Int = 255) {
        val bmp = imagenEmoji(e, tam.toInt())
        if (bmp == null) { emojiP.textSize = tam; emojiP.alpha = alfa; c.drawText(e, cx, cy + tam * 0.35f, emojiP); emojiP.alpha = 255; return }
        pintaImagen.alpha = alfa
        c.drawBitmap(bmp, cx - bmp.width / 2f, cy - bmp.height / 2f, pintaImagen)
    }
    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { Fuentes.aplicar(this, 600); letterSpacing = 0.03f }
    private val grises = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
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

    // Un error al dibujar se anota y no cierra el teclado
    override fun onDraw(c: Canvas) {
        val n = c.save()
        try { dibujarTodo(c) } catch (e: Throwable) { Registro.anotar("dibujo emojis", e) } finally { c.restoreToCount(n) }
    }

    private fun dibujarTodo(c: Canvas) {
        if (categorias.isEmpty()) { refrescar(); congelado = false }
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

        // Emojis (solo las columnas visibles). Si alguno está animándose se pide UN solo cuadro nuevo
        animandoGrilla = false
        emojiP.textSize = cel * 0.68f
        val colW = anchoCol(cel)
        precalentar(emojiP.textSize.toInt(), prioridad)
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
                    relleno.color = tema.velo(0x24)
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
            val ancho = anchoSeccion(k, cel)
            if (x0 > w || x0 + ancho < 0) continue
            val bx = bloqueX[k]
            for (g in bx.indices) {
                val gx = x0 + bx[g]
                val cols = bloqueCols[k][g]
                if (gx > w || gx + cols * colW < 0) continue
                // Línea finita entre grupos (separa sin recargar)
                if (g > 0) {
                    relleno.color = tema.velo(0x1A)
                    val lx = gx - SEPARACION_GRUPO_DP * d / 2
                    c.drawRect(lx - d * 0.35f, arribaGrilla() + cel * 0.6f, lx + d * 0.35f, arribaGrilla() + cel * (FILAS - 0.6f), relleno)
                }
                val primera = ((-gx) / colW).toInt().coerceAtLeast(0)
                val ultima = minOf(cols - 1, ((w - gx) / colW).toInt())
                val ahoraP = android.os.SystemClock.uptimeMillis()
                val tAbierto = if (congelado) 0f else (ahoraP - abiertoMs).toFloat()
                for (col in primera..ultima) for (fila in 0 until FILAS) {
                    val e = emojiBloque(k, g, col, fila) ?: continue
                    var cx = gx + col * colW + colW / 2
                    var cy = arribaGrilla() + fila * cel + cel / 2
                    var alfa = 255
                    var escalaE = 1f
                    // Recientes: cada uno viaja de su lugar anterior al nuevo; el nuevo crece
                    if (k == 0 && recientesAntes.isNotEmpty()) {
                        val te = ((tAbierto - 60f) / EMPUJE_DURA).coerceIn(0f, 1f)
                        if (te < 1f) {
                            val ee = suaveP(te)
                            val j = recientesAntes.indexOf(e)
                            val i = col * FILAS + fila
                            if (j < 0) { escalaE = 0.4f + 0.6f * ee; alfa = (alfa * ee).toInt() }
                            else if (j != i) {
                                val cxA = gx + (j / FILAS) * colW + colW / 2
                                val cyA = arribaGrilla() + (j % FILAS) * cel + cel / 2
                                cx = cxA + (cx - cxA) * ee; cy = cyA + (cy - cyA) * ee
                            }
                            if (!congelado) animandoGrilla = true
                        }
                    }
                    if (e == presionado) {
                        // Gris suave bajo el emoji que tocas (como en iOS)
                        relleno.color = tema.velo(0x29)
                        c.drawRoundRect(cx - cel * 0.46f, cy - cel * 0.46f, cx + cel * 0.46f, cy + cel * 0.46f, dp(10f), dp(10f), relleno)
                    }
                    if (escalaE != 1f) { c.save(); c.scale(escalaE, escalaE, cx, cy) }
                    dibujarEmoji(c, e, cx, cy, emojiP.textSize, alfa.coerceIn(0, 255))
                    if (escalaE != 1f) c.restore()
                }
            }
        }
        c.restore()

        if (animandoGrilla) postInvalidateOnAnimation()

        // Emoji tocado: crece un instante y vuela hacia arriba mientras se desvanece
        saltoEmoji?.let { se ->
            val t = ((android.os.SystemClock.uptimeMillis() - saltoMs) / SALTO_DURA).coerceIn(0f, 1f)
            if (t >= 1f) saltoEmoji = null
            else {
                val e = suaveP(t)
                val esc = 1f + 0.45f * kotlin.math.sin(Math.PI.toFloat() * minOf(1f, t * 1.6f)) * (1f - t * 0.5f)
                val y = saltoY - dp(46f) * e
                c.save(); c.scale(esc, esc, saltoX, y)
                dibujarEmoji(c, se, saltoX, y, emojiP.textSize, (255 * (1f - e * e)).toInt().coerceIn(0, 255))
                c.restore()
                postInvalidateOnAnimation()
            }
        }

        relleno.color = tema.velo(0x1F)
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
        val paradas = paradasX.size.coerceAtLeast(1)
        val paso = (der - izq) / paradas
        val actual = categoriaActual()
        emojiP.textSize = minOf(paso * 0.58f, dp(19f))
        // Indicador de vidrio que se desliza hasta la categoría actual
        run {
            val meta = izq + paso * actual + paso / 2
            if (indicadorX < 0f) indicadorX = meta
            val falta = meta - indicadorX
            indicadorX += if (Ahorro.activo) falta else falta * 0.35f
            if (kotlin.math.abs(falta) > 0.5f) postInvalidateOnAnimation() else indicadorX = meta
            val cy = yAbajo + altoAbajo() / 2
            val rad = minOf(paso * 0.48f, dp(17f))
            if (tema.claro) {
                relleno.color = tema.velo(if (deslizandoCat) 0x3D else 0x29)
                c.drawCircle(indicadorX, cy, rad, relleno)
            } else {
                // Obsidiana carmesí: la categoría actual va sobre la misma mini tecla de la barra
                // (base roja abajo, cara en rojo metálico) y se desliza al cambiar
                val mx = rad * 1.05f; val my = rad * 0.92f; val rr = dp(8f)
                relleno.color = 0xFF0E0E10.toInt()
                c.drawRoundRect(indicadorX - mx, cy - my + dp(2f), indicadorX + mx, cy + my + dp(2f), rr, rr, relleno)
                relleno.color = if (deslizandoCat) 0xFF2A3A58.toInt() else 0xFF1A2230.toInt()
                c.drawRoundRect(indicadorX - mx, cy - my, indicadorX + mx, cy + my, rr, rr, relleno)
                relleno.color = 0x40FF8A96
                c.drawRect(indicadorX - mx + rr, cy - my + dp(0.4f), indicadorX + mx - rr, cy - my + dp(1.2f), relleno)
            }
        }
        for (k in 0 until paradas) {
            val cx = izq + paso * k + paso / 2
            val cy = yAbajo + altoAbajo() / 2
            val ic = when (k) { 0 -> "\uD83D\uDD52"; else -> atajos.getOrElse(k - 1) { "•" } }
            // Íconos de categoría en gris como el iPhone; la actual, a color
            pintaImagen.colorFilter = if (k == actual) null else grises
            emojiP.alpha = if (k == actual) 255 else 165
            dibujarEmoji(c, ic, cx, cy, emojiP.textSize, emojiP.alpha)
            pintaImagen.colorFilter = null
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
                    relleno.color = tema.velo(0x33)
                    c.drawRoundRect(x0 + dp(3f), cajaTonos.top + dp(5f), x0 + anch - dp(3f), cajaTonos.bottom - dp(5f), dp(8f), dp(8f), relleno)
                }
                c.drawText(e, x0 + anch / 2, cajaTonos.centerY() + emojiP.textSize * 0.35f, emojiP)
            }
        }
    }

    // ---------- Toques ----------

    private var x0 = 0f; private var y0 = 0f; private var ultX = 0f

    // Deslizar el dedo por la barra de categorías (como el iPhone): salta de una a otra
    private var deslizandoCat = false
    private var movioCat = false
    private var ultimaCat = -1
    private var indicadorX = -1f
    private var vidrioCache: android.graphics.Shader? = null
    private var vidrioDe = Float.NaN
    private fun vidrioAbajo(y0: Float, y1: Float): android.graphics.Shader {
        val v = vidrioCache
        if (v != null && vidrioDe == y0 + y1 * 10000f) return v
        val nuevo = android.graphics.LinearGradient(0f, y0, 0f, y1,
            intArrayOf(0x1AFFFFFF, 0x0DFFFFFF, 0x08C4122E), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        vidrioCache = nuevo; vidrioDe = y0 + y1 * 10000f
        return nuevo
    }
    private val filoIndicador = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun categoriaEnBarra(x: Float): Int {
        val izq = dp(100f); val der = width - dp(60f)
        val n = paradasX.size.coerceAtLeast(1)
        return ((x - izq) / ((der - izq) / n)).toInt().coerceIn(0, n - 1)
    }

    private fun saltarA(k: Int) {
        // Primero se preparan los emojis de esa categoría (y las vecinas)
        if (tamPrecalentado > 0) precalentar(tamPrecalentado, k)
        scroller.forceFinished(true)
        corrimiento = (paradasX.getOrElse(k) { 0f } - dp(10f)).coerceIn(0f, maxCorrimiento())
        invalidate()
    }
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
                deslizandoCat = false; movioCat = false
                if (e.y > h - altoAbajo() && e.x >= dp(100f) && e.x <= width - dp(60f) && categorias.isNotEmpty()) {
                    deslizandoCat = true; ultimaCat = categoriaEnBarra(e.x); invalidate()
                } else if (e.y > h - altoAbajo() && e.x > width - dp(60f)) {
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
                if (deslizandoCat) {
                    if (kotlin.math.abs(e.x - x0) > dp(6f)) movioCat = true
                    if (movioCat) {
                        val k = categoriaEnBarra(e.x)
                        if (k != ultimaCat) { ultimaCat = k; saltarA(k); oyente?.respuesta(Respuesta.Tipo.SUAVE) }
                    }
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
                if (deslizandoCat) {
                    val movio = movioCat
                    deslizandoCat = false; movioCat = false
                    velocidad?.recycle(); velocidad = null
                    if (!movio) tocar(e.x, e.y) else invalidate()
                    return true
                }
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
                deslizandoCat = false; movioCat = false
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
                    val k = categoriaEnBarra(x)
                    // Se preparan primero los emojis de ese grupo, así el deslizamiento va fluido
                    if (tamPrecalentado > 0) precalentar(tamPrecalentado, k)
                    // Desliza suave hasta ese grupo
                    val destino = (paradasX.getOrElse(k) { 0f } - dp(10f)).coerceIn(0f, maxCorrimiento())
                    scroller.forceFinished(true)
                    scroller.startScroll(corrimiento.toInt(), 0, (destino - corrimiento).toInt(), 0, 380)
                    oyente?.respuesta(Respuesta.Tipo.SUAVE)
                    postInvalidateOnAnimation()
                }
            }
            return
        }
        emojiEn(x, y)?.let { e ->
            // Centro de la celda tocada, para que el salto nazca justo ahí
            val cel = celda()
            val fila = ((y - arribaGrilla()) / cel).toInt().coerceIn(0, FILAS - 1)
            saltoEmoji = e; saltoX = x; saltoY = arribaGrilla() + fila * cel + cel / 2
            if (!Ahorro.activo) saltoMs = android.os.SystemClock.uptimeMillis()
            invalidate()
            elegir(e)
        }
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
        // Teclado escondido: se sueltan las imágenes para no ocupar memoria
        imagenes.evictAll(); tamPrecalentado = -1
    }
}
