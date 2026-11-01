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
