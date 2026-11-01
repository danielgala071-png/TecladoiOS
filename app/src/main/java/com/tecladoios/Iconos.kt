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

    /** Carita rellena como la de la franja inferior del iPhone */
    fun emojiLleno(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, fondo: Int) {
        val color = p.color
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, s * 0.46f, p)
        p.color = fondo
        c.drawOval(cx - s * 0.2f, cy - s * 0.2f, cx - s * 0.09f, cy - s * 0.02f, p)
        c.drawOval(cx + s * 0.09f, cy - s * 0.2f, cx + s * 0.2f, cy - s * 0.02f, p)
        c.drawArc(cx - s * 0.27f, cy - s * 0.12f, cx + s * 0.27f, cy + s * 0.3f, 0f, 180f, true, p)
        p.color = color
    }

    /** Micrófono relleno como el del iPhone */
    fun microfonoLleno(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.34f
        val arriba = cy - s * 0.52f
        p.style = Paint.Style.FILL
        c.drawRoundRect(cx - w / 2f, arriba, cx + w / 2f, arriba + s * 0.6f, w / 2f, w / 2f, p)
        p.style = Paint.Style.STROKE
        c.drawArc(cx - s * 0.33f, cy - s * 0.32f, cx + s * 0.33f, cy + s * 0.26f, 0f, 180f, false, p)
        c.drawLine(cx, cy + s * 0.26f, cx, cy + s * 0.44f, p)
        c.drawLine(cx - s * 0.16f, cy + s * 0.44f, cx + s * 0.16f, cy + s * 0.44f, p)
    }

    /** Flecha → para "ir", "buscar" y "enviar" (como en iOS) */
    fun flecha(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        p.style = Paint.Style.STROKE
        c.drawLine(cx - s * 0.4f, cy, cx + s * 0.4f, cy, p)
        c.drawLine(cx + s * 0.4f, cy, cx + s * 0.12f, cy - s * 0.28f, p)
        c.drawLine(cx + s * 0.4f, cy, cx + s * 0.12f, cy + s * 0.28f, p)
    }

    /** Visto ✓ para "listo" */
    fun visto(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        p.style = Paint.Style.STROKE
        c.drawLine(cx - s * 0.36f, cy + s * 0.02f, cx - s * 0.1f, cy + s * 0.28f, p)
        c.drawLine(cx - s * 0.1f, cy + s * 0.28f, cx + s * 0.4f, cy - s * 0.26f, p)
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
