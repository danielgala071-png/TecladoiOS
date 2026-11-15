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
    private val globos = object : LinkedHashMap<String, android.graphics.Bitmap>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 96
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        globos.clear()
    }

    private fun imagenGlobo(): android.graphics.Bitmap? {
        val clave = "${tema.claro},${limites.left.toInt()},${limites.top.toInt()},${limites.right.toInt()},${tecla.bottom.toInt()}"
        globos[clave]?.let { return it }
        return try {
            val m = MARGEN * d
            val w = (limites.width() + 2 * m).toInt().coerceAtLeast(1)
            val h = (tecla.bottom - limites.top + 2 * m).toInt().coerceAtLeast(1)
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.translate(m - limites.left, m - limites.top)
            // Como en iOS: el globo es la misma tecla que crece hacia arriba, del mismo color,
            // con una sombra suave y la letra grande
            // Sombra difusa alrededor (más marcada abajo), como en iOS
            sombraSuave.color = if (tema.claro) 0x38000000 else 0x8C000000.toInt()
            c.save(); c.translate(0f, 2f * d); c.drawPath(forma, sombraSuave); c.restore()
            pintura.shader = null
            pintura.color = tema.tecla
            c.drawPath(forma, pintura)
            // La letra no va en la imagen: se dibuja aparte para que haga su "pop"
            globos[clave] = b
            b
        } catch (e: Throwable) { Registro.anotar("globo", e); null }
    }
    // Sombra difuminada del globo (se dibuja una sola vez por globo guardado)
    private val sombraSuave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(7f * resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.NORMAL)
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
    private val APARECE = 85f
    private val POP = 150f
    private var popMs = 0L
    private val SE_VA = 45f

    fun mostrarGlobo(r: RectF, texto: String) {
        val ahora = android.os.SystemClock.uptimeMillis()
        // Escribiendo rápido el globo salta de tecla en tecla sin volver a crecer
        if (!globo && ahora - seVaMs > SE_VA) apareceMs = ahora
        popMs = ahora   // cada tecla nueva hace su "pop", aunque escribas rápido
        tecla.set(r); globoTexto = texto; globo = true
        armarForma()
        invalidate()
    }

    // ---------- Globos preparados de antemano (uno por cuadro, en ratos libres) ----------
    private val porPreparar = ArrayDeque<Pair<RectF, String>>()
    private val guardaTecla = RectF()

    fun precalentarGlobos(lista: List<Pair<RectF, String>>) {
        porPreparar.clear(); porPreparar.addAll(lista)
        removeCallbacks(preparar)
        postDelayed(preparar, 250L)
    }

    private val preparar = object : Runnable {
        override fun run() {
            if (porPreparar.isEmpty() || width <= 0) return
            val ahora = android.os.SystemClock.uptimeMillis()
            // Si estás escribiendo (globo a la vista o desvaneciéndose), espera un rato
            if (globo || opciones.isNotEmpty() || ahora - seVaMs < SE_VA + 40) { postDelayed(this, 150L); return }
            val (r, t) = porPreparar.removeFirst()
            guardaTecla.set(tecla); val txt = globoTexto
            try {
                tecla.set(r); globoTexto = t
                armarForma(); imagenGlobo()
            } catch (e: Throwable) { Registro.anotar("preparar globo", e) }
            tecla.set(guardaTecla); globoTexto = txt; armarForma()
            if (porPreparar.isNotEmpty()) postOnAnimation(this)
        }
    }

    fun ocultarGlobo() {
        if (globo) { globo = false; seVaMs = android.os.SystemClock.uptimeMillis(); invalidate() }
    }

    private fun armarForma() {
        val w = tecla.width()
        // Como el globo de iOS: más ancho arriba que la tecla y un poco más alto
        val ext = w * 0.4f
        val alto = tecla.height() * 1.18f
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
    fun mostrarOpciones(r: RectF, lista: List<String>, inicial: Int, anchoMin: Float = 0f) {
        opciones = lista
        seleccion = inicial.coerceIn(0, lista.size - 1)
        anchoOpcion = maxOf(r.width() * 0.95f, 34 * d, anchoMin)
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
                // Almohadilla que se expande: brota desde la tecla y crece hasta su tamaño
                // en un instante (sin rebote), suave y sin retrasar la escritura
                val t = ((ahora - apareceMs) / APARECE).coerceIn(0f, 1f)
                val e = 1f - (1f - t) * (1f - t) * (1f - t)
                alfa = (0.5f + 0.5f * e); escala = 0.72f + 0.28f * e; subeLetra = 0f
                if (t < 1f) postInvalidateOnAnimation()
            } else {
                // Y se va desvaneciendo en un suspiro
                val t = ((ahora - seVaMs) / SE_VA).coerceIn(0f, 1f)
                alfa = 1f - t; escala = 1f; subeLetra = 0f
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
            // La letra hace un "pop": crece un poquito más que el globo y se asienta
            val tl = ((ahora - popMs) / POP).coerceIn(0f, 1f)
            val pop = if (globo) 1f + 0.16f * kotlin.math.sin(Math.PI.toFloat() * tl) else 1f
            if (globo && tl < 1f) postInvalidateOnAnimation()
            letraP.color = tema.texto; letraP.alpha = (alfa * 255).toInt().coerceIn(0, 255)
            letraP.textSize = tecla.height() * 0.8f
            val cyL = limites.top + (tecla.top - limites.top) * 0.5f
            c.save(); c.scale(pop, pop, limites.centerX(), cyL)
            c.drawText(globoTexto, limites.centerX(), cyL + letraP.textSize * 0.35f, letraP)
            c.restore()
            letraP.alpha = 255
            c.restore()
        }
        if (opciones.isNotEmpty()) {
            val tA = ((ahora - opcionesMs) / DESPLIEGUE).coerceIn(0f, 1f)
            val uA = tA - 1f
            val eA = 1f + 2.2f * uA * uA * uA + 1.2f * uA * uA
            c.save()
            c.save()
            c.save(); c.translate(0f, 2f * d)
            sombraSuave.color = if (tema.claro) 0x40000000 else 0x80000000.toInt()
            c.drawRoundRect(caja, 8 * d, 8 * d, sombraSuave); c.restore()
            pintura.color = tema.globo
            c.drawRoundRect(caja, 8 * d, 8 * d, pintura)
            letraP.textSize = minOf(caja.height() * 0.5f, anchoOpcion * 0.62f)
            for ((i, o) in opciones.withIndex()) {
                val x0 = caja.left + 4 * d + anchoOpcion * i
                if (i == seleccion) {
                    // Opción elegida: azul con letra blanca, como en iOS
                    pintura.color = Tema.AZUL
                    c.drawRoundRect(x0 + 1 * d, caja.top + 5 * d, x0 + anchoOpcion - 1 * d, caja.bottom - 5 * d, 6 * d, 6 * d, pintura)
                }
                letraP.color = if (i == seleccion) 0xFFFFFFFF.toInt() else tema.texto
                val tam = if (o.length > 4) minOf(letraP.textSize * 0.62f, 15f * d) else if (o.length > 2) letraP.textSize * 0.62f else letraP.textSize
                val previo = letraP.textSize
                letraP.textSize = tam
                c.drawText(o, x0 + anchoOpcion / 2, caja.centerY() + tam * 0.36f, letraP)
                letraP.textSize = previo
            }
            c.restore(); c.restore()
        }
    }
}
