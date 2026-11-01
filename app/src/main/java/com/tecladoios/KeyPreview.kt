package com.tecladoios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow

/**
 * Vista previa estilo iOS 26: una cápsula de vidrio que nace de la tecla
 * y se estira hacia arriba como líquido, con la letra grande arriba.
 */
class KeyPreviewView(context: Context) : View(context) {

    var label: String = ""
        set(value) { field = value; invalidate() }
    var keyWidth = 0f
    var keyHeight = 0f
    var darkMode = false

    /** 0 = del tamaño de la tecla, 1 = cápsula completa */
    var progreso = 0f
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    val pad get() = 12f * d
    private val anchoPrev get() = keyWidth * 1.14f
    private val altoPrev get() = keyHeight * 2.3f

    fun totalWidth() = (anchoPrev + pad * 2).toInt()
    fun totalHeight() = (altoPrev + pad * 2).toInt()
    fun offsetX() = ((anchoPrev - keyWidth) / 2f + pad).toInt()
    fun offsetY() = (altoPrev - keyHeight + pad).toInt()

    private val rect = RectF()
    private val cuerpo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 400, false)
    }


    override fun onDraw(canvas: Canvas) {
        if (keyWidth <= 0f || keyHeight <= 0f) return
        val p = progreso.coerceIn(0f, 1.15f)

        // La base queda fija en la tecla; lo que crece es la parte de arriba
        val fondo = pad + altoPrev
        val alto = keyHeight + (altoPrev - keyHeight) * p
        val ancho = keyWidth + (anchoPrev - keyWidth) * p.coerceAtMost(1f)
        val cx = pad + anchoPrev / 2f
        rect.set(cx - ancho / 2f, fondo - alto, cx + ancho / 2f, fondo)
        val radio = ancho * 0.3f

        // Vidrio gris azulado translúcido, como en la imagen
        val arriba: Int; val abajo: Int
        if (darkMode) {
            arriba = 0xFAA7B0BE.toInt(); abajo = 0xF78C96A6.toInt()
        } else {
            arriba = 0xFCF6F7FA.toInt(); abajo = 0xF8DFE2E8.toInt()
        }
        cuerpo.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, arriba, abajo, Shader.TileMode.CLAMP)
        cuerpo.setShadowLayer(10f * d, 0f, 3f * d, 0x30000000)
        canvas.drawRoundRect(rect, radio, radio, cuerpo)

        // Reflejo de luz arriba (Liquid Glass)
        brillo.shader = LinearGradient(0f, rect.top, 0f, rect.top + alto * 0.4f,
            0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radio, radio, brillo)

        borde.color = 0x80FFFFFF.toInt()
        canvas.drawRoundRect(rect, radio, radio, borde)

        // La letra aparece y sube junto con la cápsula
        val alfaLetra = ((p - 0.25f) / 0.6f).coerceIn(0f, 1f)
        textPaint.color = if (darkMode) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
        textPaint.setShadowLayer(if (darkMode) 3f * d else 0f, 0f, 1f * d, 0x40000000)
        textPaint.alpha = (255 * alfaLetra).toInt()
        textPaint.textSize = keyHeight * 0.6f
        val fm = textPaint.fontMetrics
        val cy = rect.top + (alto - keyHeight) * 0.55f + keyHeight * 0.1f
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }
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
    fun show(rect: RectF, label: String, oscuro: Boolean) {
        if (anchor.windowToken == null || rect.width() <= 0f) return

        vista.darkMode = oscuro
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
