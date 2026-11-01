package com.tecladoios

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Íconos dibujados a mano para que se vean como los de iOS. */
object Iconos {
    private val path = Path()

    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, lleno: Boolean, bloqueado: Boolean) {
        val sube = if (bloqueado) s * 0.08f else 0f
        path.reset()
        path.moveTo(cx, cy - s * 0.5f - sube)
        path.lineTo(cx + s * 0.5f, cy + s * 0.02f - sube)
        path.lineTo(cx + s * 0.22f, cy + s * 0.02f - sube)
        path.lineTo(cx + s * 0.22f, cy + s * 0.36f - sube)
        path.lineTo(cx - s * 0.22f, cy + s * 0.36f - sube)
        path.lineTo(cx - s * 0.22f, cy + s * 0.02f - sube)
        path.lineTo(cx - s * 0.5f, cy + s * 0.02f - sube)
        path.close()
        p.style = if (lleno) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawPath(path, p)
        if (bloqueado) {
            p.style = Paint.Style.FILL
            c.drawRect(cx - s * 0.22f, cy + s * 0.44f, cx + s * 0.22f, cy + s * 0.54f, p)
        }
    }

    fun borrar(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s * 0.36f
        path.reset()
        path.moveTo(cx - s * 0.55f, cy)
        path.lineTo(cx - s * 0.22f, cy - h)
        path.lineTo(cx + s * 0.5f, cy - h)
        path.lineTo(cx + s * 0.5f, cy + h)
        path.lineTo(cx - s * 0.22f, cy + h)
        path.close()
        p.style = Paint.Style.STROKE
        c.drawPath(path, p)
        val xc = cx + s * 0.13f
        val k = s * 0.13f
        c.drawLine(xc - k, cy - k, xc + k, cy + k, p)
        c.drawLine(xc - k, cy + k, xc + k, cy - k, p)
    }

    /** Flecha de "intro" (⏎) como en iOS 26/27 */
    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        p.style = Paint.Style.STROKE
        val der = cx + s * 0.42f
        val izq = cx - s * 0.42f
        val base = cy + s * 0.14f
        c.drawLine(der, cy - s * 0.32f, der, base, p)
        c.drawLine(der, base, izq, base, p)
        c.drawLine(izq, base, izq + s * 0.2f, base - s * 0.2f, p)
        c.drawLine(izq, base, izq + s * 0.2f, base + s * 0.2f, p)
    }

    fun microfono(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        p.style = Paint.Style.STROKE
        val w = s * 0.32f
        val arriba = cy - s * 0.5f
        c.drawRoundRect(cx - w / 2f, arriba, cx + w / 2f, arriba + s * 0.58f, w / 2f, w / 2f, p)
        c.drawArc(cx - s * 0.33f, cy - s * 0.32f, cx + s * 0.33f, cy + s * 0.26f, 0f, 180f, false, p)
        c.drawLine(cx, cy + s * 0.26f, cx, cy + s * 0.46f, p)
    }

    fun emoji(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        p.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s * 0.45f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx - s * 0.15f, cy - s * 0.1f, s * 0.055f, p)
        c.drawCircle(cx + s * 0.15f, cy - s * 0.1f, s * 0.055f, p)
        p.style = Paint.Style.STROKE
        c.drawArc(cx - s * 0.24f, cy - s * 0.14f, cx + s * 0.24f, cy + s * 0.25f, 25f, 130f, false, p)
    }
}
