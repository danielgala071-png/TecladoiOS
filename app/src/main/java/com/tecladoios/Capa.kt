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
    private val APARECE = 100f
    private val SE_VA = 140f

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
        val rr = 10 * d
        val rk = minOf(7.5f * d, tecla.height() * 0.17f)
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
        invalidate()
    }

    fun hayOpciones() = opciones.isNotEmpty()

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

    override fun onDraw(c: Canvas) {
        val ahora = android.os.SystemClock.uptimeMillis()
        val visible = globo || ahora - seVaMs < SE_VA
        if (visible && opciones.isEmpty() && !forma.isEmpty) {
            val alfa: Float
            val escala: Float
            if (globo) {
                val t = ((ahora - apareceMs) / APARECE).coerceIn(0f, 1f)
                // Aparece suave desde la tecla, sin rebote (salida cúbica: frena delicado al final)
                val u = 1f - t
                val e = 1f - u * u * u
                alfa = 0.25f + 0.75f * e; escala = 0.94f + 0.06f * e
                if (t < 1f) postInvalidateOnAnimation()
            } else {
                val t = ((ahora - seVaMs) / SE_VA).coerceIn(0f, 1f)
                // Se desvanece despacio, encogiéndose apenas
                val u = 1f - t
                alfa = u * u; escala = 1f - 0.03f * t
                postInvalidateOnAnimation()
            }
            val a = (alfa * 255).toInt()
            c.save()
            c.scale(escala, escala, tecla.centerX(), tecla.bottom)
            sombra.alpha = (a * 0.55f).toInt()
            c.save(); c.translate(0f, 3f * d); c.drawPath(forma, sombra); c.restore()
            // Globo con un degradado suave (más claro arriba) y filo de luz
            pintura.shader = android.graphics.LinearGradient(0f, limites.top, 0f, tecla.bottom,
                Tema.mezclar(0xFFFFFFFF.toInt(), tema.globo, 0.08f), tema.globo, android.graphics.Shader.TileMode.CLAMP)
            pintura.alpha = a
            c.drawPath(forma, pintura)
            pintura.shader = null
            brilloG.color = 0x24FFFFFF; brilloG.alpha = (0x24 * alfa).toInt()
            c.drawPath(forma, brilloG)
            letraP.color = tema.texto; letraP.alpha = a
            letraP.textSize = tecla.height() * 0.78f
            val cy = limites.top + (tecla.top - limites.top) * 0.5f
            c.drawText(globoTexto, limites.centerX(), cy + letraP.textSize * 0.35f, letraP)
            c.restore()
            pintura.alpha = 255; letraP.alpha = 255
        }
        if (opciones.isNotEmpty()) {
            c.save(); c.translate(0f, 2f * d)
            sombra.alpha = 0x66
            c.drawRoundRect(caja, 11 * d, 11 * d, sombra); c.restore()
            pintura.color = tema.globo
            c.drawRoundRect(caja, 11 * d, 11 * d, pintura)
            borde.color = tema.bordeTecla; borde.strokeWidth = 1f * d
            c.drawRoundRect(caja, 11 * d, 11 * d, borde)
            letraP.textSize = minOf(caja.height() * 0.5f, anchoOpcion * 0.62f)
            for ((i, o) in opciones.withIndex()) {
                val x0 = caja.left + 4 * d + anchoOpcion * i
                if (i == seleccion) {
                    // Opción elegida: pastilla blanca con letra negra (sin color de app)
                    pintura.color = 0xFFF5F5F7.toInt()
                    c.drawRoundRect(x0 + 1 * d, caja.top + 5 * d, x0 + anchoOpcion - 1 * d, caja.bottom - 5 * d, 7 * d, 7 * d, pintura)
                }
                letraP.color = if (i == seleccion) 0xFF000000.toInt() else tema.texto
                val tam = if (o.length > 2) letraP.textSize * 0.62f else letraP.textSize
                val previo = letraP.textSize
                letraP.textSize = tam
                c.drawText(o, x0 + anchoOpcion / 2, caja.centerY() + tam * 0.36f, letraP)
                letraP.textSize = previo
            }
        }
    }
}
