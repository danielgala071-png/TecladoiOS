package com.tecladoios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow

/**
 * Vista previa como la del iPhone: un cuadro de vidrio "sobreexpuesto" más ancho
 * que la tecla, con la letra grande, unido a la tecla por un cuello curvo.
 * Nace de la tecla y se infla hacia arriba.
 */
class KeyPreviewView(context: Context) : View(context) {

    var label: String = ""
        set(value) { field = value; invalidate() }
    var keyWidth = 0f
    var keyHeight = 0f
    var darkMode = false
    /** Color de las teclas: la vista previa es del mismo tono, mucho más clara */
    var colorTecla = 0xFF343437.toInt()

    /** 0 = del tamaño de la tecla, 1 = vista previa completa */
    var progreso = 0f
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    val pad get() = 14f * d
    /** Cuánto sobresale el cuadro a cada lado de la tecla */
    private val extra get() = keyWidth * 0.25f
    /** Alto del cuadro de arriba (donde va la letra) */
    private val altoCuadro get() = keyHeight * 1.12f

    fun totalWidth() = (keyWidth + extra * 2 + pad * 2).toInt()
    fun totalHeight() = (altoCuadro + keyHeight + pad * 2).toInt()
    fun offsetX() = (extra + pad).toInt()
    fun offsetY() = (altoCuadro + pad).toInt()

    private val path = Path()
    private val cuerpo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }.also { Fuentes.aplicar(it, 400) }

    private fun construir(p: Float) {
        val kw = keyWidth
        val kh = keyHeight
        val cx = pad + extra + kw / 2f
        val fondo = pad + altoCuadro + kh
        val e = extra * p
        val bh = altoCuadro * p
        val top = fondo - kh - bh
        val bb = top + bh                 // parte de abajo del cuadro
        val izq = cx - kw / 2f - e
        val der = cx + kw / 2f + e
        val kl = cx - kw / 2f
        val kr = cx + kw / 2f
        val r = minOf(bh * 0.34f, (der - izq) * 0.28f)
        val rk = minOf(9f * d, kw * 0.3f)
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
    }

    override fun onDraw(canvas: Canvas) {
        if (keyWidth <= 0f || keyHeight <= 0f) return
        val p = progreso.coerceIn(0f, 1.12f)
        construir(p)
        val top = pad + altoCuadro * (1f - p)
        val fondo = pad + altoCuadro + keyHeight

        val arriba: Int; val abajo: Int
        if (darkMode) {
            // "Sobreexpuesta": mucho más clara que las teclas, como la O del iPhone
            val base = aclarar(colorTecla, 0.52f)
            arriba = (aclarar(base, 0.12f) and 0x00FFFFFF) or (0xF7 shl 24)
            abajo = (base and 0x00FFFFFF) or (0xF2 shl 24)
        } else {
            arriba = 0xFFFFFFFF.toInt(); abajo = 0xFAF2F3F6.toInt()
        }

        // Resplandor alrededor (luz que se escapa del vidrio)
        cuerpo.shader = null
        cuerpo.color = abajo
        cuerpo.setShadowLayer(14f * d, 0f, 0f, if (darkMode) 0x55FFFFFF else 0x40000000)
        canvas.drawPath(path, cuerpo)
        cuerpo.clearShadowLayer()

        // Cuerpo de vidrio, más brillante en el cuadro de arriba
        cuerpo.shader = LinearGradient(0f, top, 0f, fondo, arriba, abajo, Shader.TileMode.CLAMP)
        canvas.drawPath(path, cuerpo)

        brillo.shader = LinearGradient(0f, top, 0f, top + altoCuadro * 0.5f,
            0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawPath(path, brillo)

        borde.color = if (darkMode) 0xB3FFFFFF.toInt() else 0x33000000
        canvas.drawPath(path, borde)

        // Letra grande centrada en el cuadro de arriba
        val alfaLetra = ((p - 0.3f) / 0.55f).coerceIn(0f, 1f)
        textPaint.color = if (darkMode) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        textPaint.alpha = (255 * alfaLetra).toInt()
        if (darkMode) textPaint.setShadowLayer(4f * d, 0f, 1f * d, 0x59000000) else textPaint.clearShadowLayer()
        textPaint.textSize = keyHeight * 0.68f
        val fm = textPaint.fontMetrics
        val cx = pad + extra + keyWidth / 2f
        val cy = top + altoCuadro * p * 0.5f
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}

private fun aclarar(c: Int, f: Float): Int {
    fun canal(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (canal((c shr 16) and 0xFF) shl 16) or
            (canal((c shr 8) and 0xFF) shl 8) or canal(c and 0xFF)
}

/** Muestra y anima la cápsula. Se reutiliza para que no parpadee. */
class KeyPreviewManager(private val anchor: View) {

    private val vista = KeyPreviewView(anchor.context)
    private val popup = PopupWindow(vista).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        setBackgroundDrawable(null)
        animationStyle = 0
    }
    private var animacion: ValueAnimator? = null
    private var visible = false

    private fun animar(hasta: Float, duracion: Long, rebote: Boolean, alTerminar: (() -> Unit)? = null) {
        animacion?.cancel()
        animacion = ValueAnimator.ofFloat(vista.progreso, hasta).apply {
            duration = duracion
            interpolator = if (rebote) OvershootInterpolator(1.4f) else DecelerateInterpolator()
            addUpdateListener { vista.progreso = it.animatedValue as Float }
            if (alTerminar != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelada = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelada = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelada) alTerminar() }
            })
            start()
        }
    }

    /** rect: posición de la tecla dentro de [anchor] */
    fun show(rect: RectF, label: String, tema: Tema) {
        if (anchor.windowToken == null || rect.width() <= 0f) return

        vista.darkMode = tema.oscuro
        vista.colorTecla = tema.tecla
        vista.keyWidth = rect.width()
        vista.keyHeight = rect.height()
        vista.label = label

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val w = vista.totalWidth()
        val h = vista.totalHeight()
        val x = loc[0] + rect.left.toInt() - vista.offsetX()
        val y = loc[1] + rect.top.toInt() - vista.offsetY()

        if (popup.isShowing) {
            popup.update(x, y, w, h)
        } else {
            popup.width = w
            popup.height = h
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        vista.alpha = 1f

        if (!visible) {
            // Nace de la tecla y se estira hacia arriba con un pequeño rebote
            vista.progreso = 0f
            animar(1f, 170, rebote = true)
        } else {
            // Al deslizar a otra tecla: un rebote corto
            vista.progreso = 0.85f
            animar(1f, 110, rebote = true)
        }
        visible = true
    }

    fun hide() {
        if (!visible) return
        visible = false
        // Se recoge hacia la tecla y desaparece
        animar(0f, 110, rebote = false) {
            vista.alpha = 0f
        }
    }

    fun dismiss() {
        visible = false
        animacion?.cancel()
        vista.progreso = 0f
        if (popup.isShowing) popup.dismiss()
    }
}
