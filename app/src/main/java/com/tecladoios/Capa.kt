package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Capa transparente encima de la barra y del teclado. Dibuja el globo de la tecla que tocas
 * (como en el iPhone, sale hacia arriba y puede pasar por encima de la barra) y las opciones
 * al mantener presionada una tecla (tildes y símbolos).
 */
class Capa(context: Context) : View(context) {

    var tema = Tema(Tema.AZUL)
    private val d = resources.displayMetrics.density

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letraP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 400) }
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val pintaGlobo = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Margen alrededor del globo guardado (para el aura y la sombra), en dp */
    private val MARGEN = 18f
    /** Globos ya armados, por tecla y letra (los más usados se quedan) */
    private val globos = object : LinkedHashMap<String, android.graphics.Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 48
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        globos.clear()
    }

    private fun imagenGlobo(): android.graphics.Bitmap? {
        val clave = "${limites.left.toInt()},${limites.top.toInt()},${limites.right.toInt()},${tecla.bottom.toInt()},$globoTexto"
        globos[clave]?.let { return it }
        return try {
            val m = MARGEN * d
            val w = (limites.width() + 2 * m).toInt().coerceAtLeast(1)
            val h = (tecla.bottom - limites.top + 2 * m).toInt().coerceAtLeast(1)
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.translate(m - limites.left, m - limites.top)
            sombra.alpha = 0x8C
            c.save(); c.translate(0f, 3f * d); c.drawPath(forma, sombra); c.restore()
            aura.alpha = 0xBF
            c.drawPath(forma, aura)
            pintura.shader = android.graphics.LinearGradient(0f, limites.top, 0f, tecla.bottom,
                0xFF3A3C4E.toInt(), 0xFF262844.toInt(), android.graphics.Shader.TileMode.CLAMP)
            pintura.alpha = 255
            c.drawPath(forma, pintura)
            pintura.shader = null
            brilloG.shader = android.graphics.LinearGradient(0f, limites.top, 0f, tecla.bottom,
                intArrayOf(0x8CFFFFFF.toInt(), 0x337A8CFF, 0x997A8CFF.toInt()), floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            brilloG.alpha = 255
            c.drawPath(forma, brilloG)
            brilloG.shader = null
            letraP.color = tema.texto; letraP.alpha = 255
            letraP.textSize = tecla.height() * 0.78f
            letraP.setShadowLayer(9f * d, 0f, 0f, 0xD9A0AAFF.toInt())
            val cy = limites.top + (tecla.top - limites.top) * 0.5f
            c.drawText(globoTexto, limites.centerX(), cy + letraP.textSize * 0.35f, letraP)
            letraP.clearShadowLayer()
            globos[clave] = b
            b
        } catch (e: Throwable) { Registro.anotar("globo", e); null }
    }
    // Globo retroiluminado: aura violeta difuminada alrededor
    private val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBF5E5CE6.toInt()
        maskFilter = android.graphics.BlurMaskFilter(14f * resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.OUTER)
    }
    private val brilloG = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * resources.displayMetrics.density }

    // Globo
    private var globo = false
    private val tecla = RectF()
    private var globoTexto = ""
    private val forma = Path()
    private val limites = RectF()

    // Opciones
    private var opciones: List<String> = emptyList()
    private var seleccion = 0
    private val caja = RectF()
    private var anchoOpcion = 0f

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    // Animación del globo: aparece creciendo un poco y se va desvaneciendo (muy corta)
    private var apareceMs = 0L
    private var seVaMs = 0L
    // Como el iPhone: el globo aparece al instante y se va en un suspiro
    private val APARECE = 80f
    private val SE_VA = 90f

    fun mostrarGlobo(r: RectF, texto: String) {
        val ahora = android.os.SystemClock.uptimeMillis()
        // Escribiendo rápido el globo salta de tecla en tecla sin volver a crecer
        if (!globo && ahora - seVaMs > SE_VA) apareceMs = ahora
        tecla.set(r); globoTexto = texto; globo = true
        armarForma()
        invalidate()
    }

    fun ocultarGlobo() {
        if (globo) { globo = false; seVaMs = android.os.SystemClock.uptimeMillis(); invalidate() }
    }

    private fun armarForma() {
        val w = tecla.width()
        val ext = w * 0.32f
        val alto = tecla.height() * 1.12f
        var izq = tecla.left - ext
        var der = tecla.right + ext
        // Que no se salga por los costados
        if (izq < 2 * d) { der += 2 * d - izq; izq = 2 * d }
        if (der > width - 2 * d) { izq -= der - (width - 2 * d); der = width - 2 * d }
        val arriba = (tecla.top - alto).coerceAtLeast(1 * d)
        val cuello = tecla.top - tecla.height() * 0.12f
        val rr = 8 * d
        val rk = minOf(5.5f * d, tecla.height() * 0.12f)
        forma.reset()
        forma.moveTo(tecla.left + rk, tecla.bottom)
        forma.quadTo(tecla.left, tecla.bottom, tecla.left, tecla.bottom - rk)
        forma.lineTo(tecla.left, tecla.top + 4 * d)
        forma.cubicTo(tecla.left, cuello, izq, cuello + 2 * d, izq, cuello - tecla.height() * 0.25f)
        forma.lineTo(izq, arriba + rr)
        forma.quadTo(izq, arriba, izq + rr, arriba)
        forma.lineTo(der - rr, arriba)
        forma.quadTo(der, arriba, der, arriba + rr)
        forma.lineTo(der, cuello - tecla.height() * 0.25f)
        forma.cubicTo(der, cuello + 2 * d, tecla.right, cuello, tecla.right, tecla.top + 4 * d)
        forma.lineTo(tecla.right, tecla.bottom - rk)
        forma.quadTo(tecla.right, tecla.bottom, tecla.right - rk, tecla.bottom)
        forma.close()
        forma.computeBounds(limites, true)
    }

    /** Muestra las opciones arriba de la tecla [r]; devuelve false si no hay espacio */
    fun mostrarOpciones(r: RectF, lista: List<String>, inicial: Int) {
        opciones = lista
        seleccion = inicial.coerceIn(0, lista.size - 1)
        anchoOpcion = maxOf(r.width() * 0.95f, 34 * d)
        val w = anchoOpcion * lista.size + 8 * d
        val h = r.height() * 1.05f
        var izq = r.centerX() - anchoOpcion / 2 - 4 * d - anchoOpcion * seleccion
        if (izq < 3 * d) izq = 3 * d
        if (izq + w > width - 3 * d) izq = width - 3 * d - w
        val arriba = (r.top - h - 6 * d).coerceAtLeast(2 * d)
        caja.set(izq, arriba, izq + w, arriba + h)
        tecla.set(r)
        opcionesMs = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    fun hayOpciones() = opciones.isNotEmpty()

    /** Las tildes y símbolos se despliegan desde la tecla (crecen desde abajo con un resorte) */
    private var opcionesMs = 0L
    private val DESPLIEGUE = 130f

    /** Elige la opción bajo el dedo (x en coordenadas de la capa) */
    fun moverSeleccion(x: Float, y: Float): Int {
        if (opciones.isEmpty()) return -1
        val i = ((x - caja.left - 4 * d) / anchoOpcion).toInt().coerceIn(0, opciones.size - 1)
        // Si el dedo baja mucho, no se elige ninguna (soltar ahí cancela)
        val nueva = if (y > tecla.bottom + tecla.height() * 0.6f) -1 else i
        if (nueva != seleccion) { seleccion = nueva; invalidate(); return nueva }
        return seleccion
    }

    fun elegida(): String? = if (seleccion in opciones.indices) opciones[seleccion] else null

    fun ocultarOpciones() { if (opciones.isNotEmpty()) { opciones = emptyList(); invalidate() } }

    // Un error al dibujar se anota y no cierra el teclado
    override fun onDraw(c: Canvas) {
        val n = c.save()
        try { dibujarTodo(c) } catch (e: Throwable) { Registro.anotar("dibujo capa", e) } finally { c.restoreToCount(n) }
    }

    private fun dibujarTodo(c: Canvas) {
        val ahora = android.os.SystemClock.uptimeMillis()
        val visible = globo || ahora - seVaMs < SE_VA
        if (visible && opciones.isEmpty() && !forma.isEmpty) {
            val alfa: Float
            val escala: Float
            val subeLetra: Float
            if (globo) {
                // Aparece rapidísimo creciendo apenas desde la tecla
                // Brota de la tecla con un pequeño rebote (como el globo del iPhone)
                val t = ((ahora - apareceMs) / APARECE).coerceIn(0f, 1f)
                val u = t - 1f
                val e = 1f + 2.0f * u * u * u + 1.0f * u * u
                alfa = (0.4f + 1.2f * t).coerceAtMost(1f); escala = 0.86f + 0.14f * e; subeLetra = (1f - e) * 4f * d
                if (t < 1f) postInvalidateOnAnimation()
            } else {
                // Y se va desvaneciendo en un suspiro
                val t = ((ahora - seVaMs) / SE_VA).coerceIn(0f, 1f)
                alfa = 1f - t; escala = 1f - 0.04f * t; subeLetra = 0f
                postInvalidateOnAnimation()
            }
            // El globo (sombra, aura difuminada, vidrio, filo y letra brillante) se arma UNA vez
            // por tecla y se guarda: después cada cuadro solo lo copia. El aura difuminada era lo
            // más caro de dibujar y se repetía en cada cuadro mientras la tecla estaba apretada.
            val img = imagenGlobo()
            c.save()
            c.scale(escala, escala, tecla.centerX(), tecla.bottom)
            if (img != null) {
                pintaGlobo.alpha = (alfa * 255).toInt().coerceIn(0, 255)
                c.drawBitmap(img, limites.left - MARGEN * d, limites.top - MARGEN * d + subeLetra * 0.3f, pintaGlobo)
            }
            c.restore()
        }
        if (opciones.isNotEmpty()) {
            val tA = ((ahora - opcionesMs) / DESPLIEGUE).coerceIn(0f, 1f)
            val uA = tA - 1f
            val eA = 1f + 2.2f * uA * uA * uA + 1.2f * uA * uA
            if (tA < 1f) postInvalidateOnAnimation()
            c.save()
            c.scale(0.7f + 0.3f * eA, 0.55f + 0.45f * eA, tecla.centerX().coerceIn(caja.left, caja.right), caja.bottom)
            c.saveLayerAlpha(caja.left - 8 * d, caja.top - 8 * d, caja.right + 8 * d, caja.bottom + 8 * d, (255 * minOf(1f, tA * 2f)).toInt())
            c.save(); c.translate(0f, 2f * d)
            sombra.alpha = 0x66
            c.drawRoundRect(caja, 8 * d, 8 * d, sombra); c.restore()
            pintura.color = tema.globo
            c.drawRoundRect(caja, 8 * d, 8 * d, pintura)
            // Filo de luz del vidrio
            borde.shader = android.graphics.LinearGradient(0f, caja.top, 0f, caja.bottom,
                intArrayOf(0x66FFFFFF, 0x10FFFFFF, 0x1AFFFFFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            borde.strokeWidth = 0.9f * d
            c.drawRoundRect(caja, 8 * d, 8 * d, borde)
            borde.shader = null
            letraP.textSize = minOf(caja.height() * 0.5f, anchoOpcion * 0.62f)
            for ((i, o) in opciones.withIndex()) {
                val x0 = caja.left + 4 * d + anchoOpcion * i
                if (i == seleccion) {
                    // Opción elegida: pastilla con el degradado azul → violeta del teclado
                    pintura.shader = android.graphics.LinearGradient(x0, caja.top, x0 + anchoOpcion, caja.bottom,
                        Tema.AZUL_ACENTO, Tema.ROJO, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRoundRect(x0 + 1 * d, caja.top + 5 * d, x0 + anchoOpcion - 1 * d, caja.bottom - 5 * d, 6 * d, 6 * d, pintura)
                    pintura.shader = null
                }
                letraP.color = tema.texto
                val tam = if (o.length > 2) letraP.textSize * 0.62f else letraP.textSize
                val previo = letraP.textSize
                letraP.textSize = tam
                c.drawText(o, x0 + anchoOpcion / 2, caja.centerY() + tam * 0.36f, letraP)
                letraP.textSize = previo
            }
            c.restore(); c.restore()
        }
    }
}
