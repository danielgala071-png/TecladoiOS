package com.tecladoios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

/**
 * Vista previa de las teclas como la del iPhone, dibujada en UNA sola capa fija
 * encima del teclado (antes era una ventanita por toque que se movía de tecla en tecla).
 *
 * - Cada toque tiene su propia burbuja, que nace, se queda y se recoge en SU tecla.
 *   Al tocar la "i" y luego la "z", la burbuja de la "i" se recoge en la "i" y la de
 *   la "z" nace en la "z": ya no se ve un desplazamiento de una tecla a otra.
 * - La capa nunca se mueve ni cambia de tamaño mientras escribes, así Android no tiene
 *   que reacomodar ventanas en cada letra: las animaciones van fluidas y el teclado
 *   responde al instante aunque escribas rápido con dos dedos.
 */
class KeyPreviewOverlay(private val anchor: View) {

    private val vista = Capa(anchor.context)
    private val popup = PopupWindow(vista).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        isOutsideTouchable = false
        setBackgroundDrawable(null)
        animationStyle = 0
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    private var x = Int.MIN_VALUE
    private var y = Int.MIN_VALUE
    private var w = 0
    private var h = 0

    /** Alto de la tecla más alta: decide cuánto sobresale la capa por arriba del teclado */
    var altoTecla = 0f
    var radioTecla = 6f

    /** Prepara (o reubica) la capa. Solo hace algo si el teclado cambió de lugar o tamaño. */
    private fun asegurar(): Boolean {
        if (anchor.windowToken == null || anchor.width == 0 || anchor.height == 0) return false
        val d = anchor.resources.displayMetrics.density
        val extra = (altoTecla * 1.3f + 22f * d).toInt()
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val nx = loc[0]
        val ny = loc[1] - extra
        val nw = anchor.width
        val nh = anchor.height + extra
        vista.extraArriba = extra.toFloat()
        if (popup.isShowing) {
            if (nx != x || ny != y || nw != w || nh != h) popup.update(nx, ny, nw, nh)
        } else {
            popup.width = nw
            popup.height = nh
            try {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, nx, ny)
            } catch (_: Exception) {
                return false
            }
        }
        x = nx; y = ny; w = nw; h = nh
        return true
    }

    /**
     * Muestra la burbuja del dedo [id] sobre la tecla [rect].
     * [deslizando]: el mismo dedo pasó a otra tecla (aparece casi completa, sin viajar).
     */
    fun mostrar(id: Int, rect: RectF, etiqueta: String, tema: Tema, deslizando: Boolean = false) {
        if (rect.width() <= 0f || !asegurar()) return
        vista.tema = tema
        vista.radioTecla = radioTecla
        vista.mostrar(id, rect, etiqueta, deslizando)
    }

    /**
     * Crea la capa de la vista previa por adelantado (vacía e invisible), apenas se abre el
     * teclado. Antes se creaba con el primer toque: esa primera vez Android tarda en armar
     * la ventanita y la siguiente tecla se sentía pegada.
     */
    fun preparar() { asegurar() }

    /** El dedo [id] se levantó: su burbuja se recoge en su propia tecla */
    fun soltar(id: Int) = vista.soltar(id)

    /** Quita la burbuja del dedo [id] al instante (por ejemplo, al abrir los acentos) */
    fun quitar(id: Int) = vista.quitar(id)

    /** Quita todas las burbujas al instante */
    fun limpiar() = vista.limpiar()

    fun cerrar() {
        vista.limpiar()
        if (popup.isShowing) try { popup.dismiss() } catch (_: Exception) { }
        x = Int.MIN_VALUE
    }

    // ---------------------------------------------------------------------------------

    private class Burbuja(val tecla: RectF, var etiqueta: String) {
        var desde = 0f
        var hasta = 1f
        var inicio = 0L
        var duracion = 170L
        var rebote = true

        fun animar(a: Float, b: Float, ms: Long, conRebote: Boolean, ahora: Long) {
            desde = a; hasta = b; duracion = ms; rebote = conRebote; inicio = ahora
        }

        fun valor(ahora: Long): Float {
            val f = ((ahora - inicio).toFloat() / duracion).coerceIn(0f, 1f)
            val i = if (rebote) REBOTE_BURBUJA.getInterpolation(f) else FRENO.getInterpolation(f)
            return desde + (hasta - desde) * i
        }

        fun termino(ahora: Long) = ahora - inicio >= duracion
    }

    private class Capa(context: Context) : View(context) {

        var tema: Tema? = null
        var radioTecla = 6f
        var extraArriba = 0f

        /** Burbujas de los dedos que están tocando */
        private val activas = HashMap<Int, Burbuja>()
        /** Burbujas de dedos ya levantados, recogiéndose en su tecla */
        private val saliendo = ArrayList<Burbuja>()

        private val d = resources.displayMetrics.density
        private val path = Path()
        private val cuerpo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sombra = Paint(Paint.ANTI_ALIAS_FLAG)
        /** Sombra ancha y tenue (un trazo grueso hace de desenfoque sin costo) */
        private val sombraAncha = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(0.8f, 0.6f * d)
        }

        /** Colores calculados una sola vez por tema (antes se armaban degradados en cada cuadro) */
        private var temaColores: Tema? = null
        private var colorCuerpo = 0
        private var colorBorde = 0
        private var colorSombra = 0
        private var colorLetra = 0

        private fun prepararColores(tm: Tema) {
            if (temaColores === tm) return
            temaColores = tm
            // Vidrio del color de la app (no una burbuja brillante): verde en WhatsApp, rosado en Instagram…
            colorCuerpo = tm.burbuja
            colorBorde = tm.bordeBurbuja
            colorSombra = tm.sombraFlotante
            colorLetra = tm.texto
        }
        private val texto = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }.also { Fuentes.aplicar(it, 400) }
        /**
         * Reflejo de vidrio arriba de la burbuja (igual que en las teclas). El degradado se arma
         * una sola vez y en cada cuadro solo se estira a la medida: no crea nada nuevo.
         */
        private val degradadoBrillo = LinearGradient(0f, 0f, 0f, 1f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        private val matrizBrillo = Matrix()
        private val brillo = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = degradadoBrillo }

        private fun ahora() = SystemClock.uptimeMillis()

        fun mostrar(id: Int, rect: RectF, etiqueta: String, deslizando: Boolean) {
            val t = ahora()
            activas.remove(id) // si el mismo dedo cambió de tecla, la anterior desaparece en su sitio
            val b = Burbuja(RectF(rect), etiqueta)
            // Aparece casi al instante (como en iPhone): nace ya grande y termina con un rebote corto
            // Rebote marcado: la burbuja salta, se pasa un poquito de su tamaño y se acomoda
            // Nace con un resorte fino: se pasa un pelito de su tamaño y se asienta (premium,
            // sin parecer de goma). Si el dedo se deslizó a otra tecla, aparece casi completa.
            if (deslizando) b.animar(0.9f, 1f, 80, true, t) else b.animar(0.55f, 1f, Movimiento.BURBUJA_ENTRA, true, t)
            activas[id] = b
            postInvalidateOnAnimation()
        }

        fun soltar(id: Int) {
            val b = activas.remove(id) ?: return
            val t = ahora()
            // Escribiendo rápido el dedo se levanta en 30-40 ms y la burbuja casi no se veía
            // (parecía que la tecla no se había presionado). Ahora se queda a la vista un mínimo
            // y recién ahí se recoge, un poco más despacio.
            val visto = t - b.inicio
            val espera = (VISIBLE_MINIMO - visto).coerceAtLeast(0L)
            b.animar(if (espera > 0L) 1f else b.valor(t).coerceAtMost(1f), 0f, Movimiento.BURBUJA_SALE, false, t + espera)
            saliendo.add(b)
            postInvalidateOnAnimation()
        }

        fun quitar(id: Int) {
            if (activas.remove(id) != null) invalidate()
        }

        fun limpiar() {
            if (activas.isEmpty() && saliendo.isEmpty()) return
            activas.clear()
            saliendo.clear()
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val tm = tema ?: return
            val t = ahora()
            var animando = false
            c.save()
            c.translate(0f, extraArriba)
            val iter = saliendo.iterator()
            while (iter.hasNext()) {
                val b = iter.next()
                if (b.termino(t)) { iter.remove(); continue }
                dibujar(c, b, b.valor(t), tm)
                animando = true
            }
            for (b in activas.values) {
                dibujar(c, b, b.valor(t), tm)
                if (!b.termino(t)) animando = true
            }
            c.restore()
            if (animando) postInvalidateOnAnimation()
        }

        private fun dibujar(c: Canvas, b: Burbuja, progreso: Float, tm: Tema) {
            val p = progreso.coerceIn(0f, 1.12f)
            if (p <= 0.01f) return
            val r0 = b.tecla
            val kw = r0.width()
            val kh = r0.height()
            val kl = r0.left
            val kr = r0.right
            val fondo = r0.bottom
            val extra = kw * 0.25f            // cuánto sobresale el cuadro a cada lado
            val altoCuadro = kh * 1.12f       // alto del cuadro donde va la letra
            val e = extra * p
            val bh = altoCuadro * p
            val bb = r0.top                   // parte de abajo del cuadro = arriba de la tecla
            val top = bb - bh
            var izq = kl - e
            var der = kr + e
            // En los bordes (q, p, a, ñ) el cuadro se corre hacia adentro, como en iPhone
            val margen = 2f * d
            var dx = 0f
            if (izq < margen) dx = margen - izq
            else if (der > width - margen) dx = (width - margen) - der
            dx = dx.coerceIn(-e, e)
            izq += dx
            der += dx
            val r = minOf(bh * 0.34f, (der - izq) * 0.28f)
            val rk = minOf(radioTecla * 1.35f, kw * 0.3f)
            val n = kh * 0.34f * p            // largo del cuello

            path.reset()
            path.moveTo(izq + r, top)
            path.lineTo(der - r, top)
            path.quadTo(der, top, der, top + r)
            path.lineTo(der, maxOf(bb - r, top + r))
            path.cubicTo(der, bb, kr, bb, kr, bb + n)
            path.lineTo(kr, fondo - rk)
            path.quadTo(kr, fondo, kr - rk, fondo)
            path.lineTo(kl + rk, fondo)
            path.quadTo(kl, fondo, kl, fondo - rk)
            path.lineTo(kl, bb + n)
            path.cubicTo(kl, bb, izq, bb, izq, maxOf(bb - r, top + r))
            path.lineTo(izq, top + r)
            path.quadTo(izq, top, izq + r, top)
            path.close()

            prepararColores(tm)
            // Al recogerse, la burbuja además se desvanece (no solo se achica): se va limpia
            val opaca = (p / 0.45f).coerceIn(0f, 1f)
            // Sombra en dos capas (ancha y tenue + corta y firme): la burbuja flota sobre el
            // teclado. Sin desenfoque real, así cada cuadro se dibuja rapidísimo.
            sombraAncha.color = colorSombra
            sombraAncha.alpha = (Color.alpha(colorSombra) * 0.28f * opaca).toInt()
            sombraAncha.strokeWidth = 5f * d
            c.save()
            c.translate(0f, 3f * d)
            c.drawPath(path, sombraAncha)
            c.restore()
            sombra.color = colorSombra
            sombra.alpha = (Color.alpha(colorSombra) * 0.7f * opaca).toInt()
            c.save()
            c.translate(0f, 1.2f * d)
            c.drawPath(path, sombra)
            c.restore()
            cuerpo.color = colorCuerpo
            cuerpo.alpha = (255 * opaca).toInt()
            c.drawPath(path, cuerpo)
            matrizBrillo.setScale(1f, maxOf(1f, bh * 0.6f))
            matrizBrillo.postTranslate(0f, top)
            degradadoBrillo.setLocalMatrix(matrizBrillo)
            brillo.alpha = (255 * opaca).toInt()
            c.drawPath(path, brillo)
            borde.color = colorBorde
            borde.alpha = (Color.alpha(colorBorde) * opaca).toInt()
            c.drawPath(path, borde)

            // Letra grande en el cuadro de arriba
            val alfaLetra = ((p - 0.3f) / 0.55f).coerceIn(0f, 1f)
            if (alfaLetra <= 0f) return
            texto.color = colorLetra
            texto.alpha = (255 * alfaLetra * opaca).toInt()
            texto.textSize = kh * 0.74f
            val fm = texto.fontMetrics
            val cx = (izq + der) / 2f
            val cy = top + bh * 0.5f
            c.drawText(b.etiqueta, cx, cy - (fm.ascent + fm.descent) / 2f, texto)
        }
    }

    private companion object {
        /** Lo mínimo que se ve la burbuja de una tecla, aunque el toque sea cortísimo (ms) */
        const val VISIBLE_MINIMO = 90L
        val REBOTE = Movimiento.REBOTE
        /** Aparece rápido y frena suave, sin rebote (como en iOS) */
        val REBOTE_BURBUJA = Movimiento.POP
        val FRENO = Movimiento.SUAVE

        /** Color [a] (con transparencia) puesto encima de [b] (opaco) */
        fun sobre(a: Int, b: Int): Int {
            val al = Color.alpha(a) / 255f
            fun canal(x: Int, y: Int) = (x * al + y * (1f - al)).toInt().coerceIn(0, 255)
            return Color.rgb(canal(Color.red(a), Color.red(b)), canal(Color.green(a), Color.green(b)),
                canal(Color.blue(a), Color.blue(b)))
        }

        fun aclarar(c: Int, f: Float): Int {
            fun canal(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (canal((c shr 16) and 0xFF) shl 16) or
                    (canal((c shr 8) and 0xFF) shl 8) or canal(c and 0xFF)
        }
    }
}
