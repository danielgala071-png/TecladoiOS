package com.tecladoios

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Íconos propios, dibujados con líneas (sin imágenes): se ven nítidos en cualquier tamaño.
 * Todos se dibujan centrados en (cx, cy) dentro de un cuadrado de lado [s].
 */
object Iconos {
    private val path = Path()
    private val r = RectF()

    private fun trazo(p: Paint, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.085f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    /** Flecha de mayúscula; [lleno] = mayúscula activa, [bloqueo] = raya debajo */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, lleno: Boolean, bloqueo: Boolean) {
        val h = s * 0.5f; val w = s * 0.5f
        val top = cy - h * 0.62f
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx + w / 2, top + h * 0.5f)
        path.lineTo(cx + w * 0.23f, top + h * 0.5f)
        path.lineTo(cx + w * 0.23f, top + h * 0.95f)
        path.lineTo(cx - w * 0.23f, top + h * 0.95f)
        path.lineTo(cx - w * 0.23f, top + h * 0.5f)
        path.lineTo(cx - w / 2, top + h * 0.5f)
        path.close()
        trazo(p, s * 0.8f)
        if (lleno) { p.style = Paint.Style.FILL_AND_STROKE }
        c.drawPath(path, p)
        if (bloqueo) {
            p.style = Paint.Style.STROKE
            c.drawLine(cx - w * 0.28f, top + h * 1.18f, cx + w * 0.28f, top + h * 1.18f, p)
        }
    }

    fun borrar(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, lleno: Boolean) {
        val w = s * 0.62f; val h = s * 0.42f
        val l = cx - w / 2; val t = cy - h / 2
        path.reset()
        path.moveTo(l + w * 0.28f, t)
        path.lineTo(l + w, t)
        path.lineTo(l + w, t + h)
        path.lineTo(l + w * 0.28f, t + h)
        path.lineTo(l, cy)
        path.close()
        trazo(p, s * 0.8f)
        if (lleno) { p.style = Paint.Style.FILL_AND_STROKE; c.drawPath(path, p); p.color = 0xFF1C1C1F.toInt() }
        else c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        val x0 = l + w * 0.5f; val d = h * 0.2f
        c.drawLine(x0 - d + w * 0.06f, cy - d, x0 + d + w * 0.06f, cy + d, p)
        c.drawLine(x0 + d + w * 0.06f, cy - d, x0 - d + w * 0.06f, cy + d, p)
    }

    fun intro(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.5f; val h = s * 0.32f
        trazo(p, s * 0.8f)
        path.reset()
        path.moveTo(cx + w / 2, cy - h)
        path.lineTo(cx + w / 2, cy + h * 0.25f)
        path.lineTo(cx - w / 2, cy + h * 0.25f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(cx - w / 2 + h * 0.55f, cy + h * 0.25f - h * 0.55f)
        path.lineTo(cx - w / 2, cy + h * 0.25f)
        path.lineTo(cx - w / 2 + h * 0.55f, cy + h * 0.25f + h * 0.55f)
        c.drawPath(path, p)
    }

    /** Carita (emojis) */
    fun cara(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val rad = s * 0.27f
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx - rad * 0.36f, cy - rad * 0.22f, s * 0.03f, p)
        c.drawCircle(cx + rad * 0.36f, cy - rad * 0.22f, s * 0.03f, p)
        p.style = Paint.Style.STROKE
        r.set(cx - rad * 0.5f, cy - rad * 0.35f, cx + rad * 0.5f, cy + rad * 0.55f)
        c.drawArc(r, 25f, 130f, false, p)
    }

    /** Tablilla de portapapeles */
    fun portapapeles(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val w = s * 0.42f; val h = s * 0.52f
        r.set(cx - w / 2, cy - h / 2 + s * 0.04f, cx + w / 2, cy + h / 2 + s * 0.04f)
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p)
        r.set(cx - w * 0.25f, cy - h / 2 - s * 0.03f, cx + w * 0.25f, cy - h / 2 + s * 0.08f)
        c.drawRoundRect(r, s * 0.03f, s * 0.03f, p)
        c.drawLine(cx - w * 0.22f, cy + s * 0.04f, cx + w * 0.22f, cy + s * 0.04f, p)
        c.drawLine(cx - w * 0.22f, cy + s * 0.14f, cx + w * 0.1f, cy + s * 0.14f, p)
    }

    /** Cursor de texto con flechas (editar) */
    fun editar(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val h = s * 0.26f
        c.drawLine(cx, cy - h, cx, cy + h, p)
        c.drawLine(cx - s * 0.06f, cy - h, cx + s * 0.06f, cy - h, p)
        c.drawLine(cx - s * 0.06f, cy + h, cx + s * 0.06f, cy + h, p)
        val a = s * 0.3f; val b = s * 0.08f
        c.drawLine(cx - a, cy, cx - s * 0.13f, cy, p)
        c.drawLine(cx - a, cy, cx - a + b, cy - b, p); c.drawLine(cx - a, cy, cx - a + b, cy + b, p)
        c.drawLine(cx + a, cy, cx + s * 0.13f, cy, p)
        c.drawLine(cx + a, cy, cx + a - b, cy - b, p); c.drawLine(cx + a, cy, cx + a - b, cy + b, p)
    }

    /** Mano / teclado a un lado */
    fun unaMano(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val w = s * 0.5f; val h = s * 0.4f
        r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
        c.drawLine(cx - w * 0.1f, cy - h / 2, cx - w * 0.1f, cy + h / 2, p)
        p.style = Paint.Style.FILL
        for (i in 0..1) for (j in 0..2) c.drawCircle(cx + w * (0.06f + 0.13f * j), cy - h * 0.18f + i * h * 0.36f, s * 0.022f, p)
    }

    fun engranaje(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val ro = s * 0.25f; val ri = s * 0.19f
        path.reset()
        for (i in 0 until 16) {
            val ang = Math.PI * 2 * i / 16 - Math.PI / 2
            val rr = if (i % 2 == 0) ro else ri
            val x = cx + (rr * Math.cos(ang)).toFloat(); val y = cy + (rr * Math.sin(ang)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
        c.drawCircle(cx, cy, s * 0.08f, p)
    }

    /** Cámara con texto (escanear) */
    fun escanear(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val a = s * 0.27f; val b = s * 0.1f
        c.drawLine(cx - a, cy - a, cx - a + b, cy - a, p); c.drawLine(cx - a, cy - a, cx - a, cy - a + b, p)
        c.drawLine(cx + a, cy - a, cx + a - b, cy - a, p); c.drawLine(cx + a, cy - a, cx + a, cy - a + b, p)
        c.drawLine(cx - a, cy + a, cx - a + b, cy + a, p); c.drawLine(cx - a, cy + a, cx - a, cy + a - b, p)
        c.drawLine(cx + a, cy + a, cx + a - b, cy + a, p); c.drawLine(cx + a, cy + a, cx + a, cy + a - b, p)
        c.drawLine(cx - s * 0.13f, cy - s * 0.07f, cx + s * 0.13f, cy - s * 0.07f, p)
        c.drawLine(cx - s * 0.13f, cy + s * 0.07f, cx + s * 0.06f, cy + s * 0.07f, p)
    }

    fun lupa(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        c.drawCircle(cx - s * 0.05f, cy - s * 0.05f, s * 0.17f, p)
        c.drawLine(cx + s * 0.07f, cy + s * 0.07f, cx + s * 0.22f, cy + s * 0.22f, p)
    }

    fun flecha(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, grados: Float) {
        trazo(p, s * 0.8f)
        c.save()
        c.rotate(grados, cx, cy)
        val a = s * 0.16f
        c.drawLine(cx + a * 0.6f, cy - a, cx - a * 0.4f, cy, p)
        c.drawLine(cx - a * 0.4f, cy, cx + a * 0.6f, cy + a, p)
        c.restore()
    }

    fun expandir(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val a = s * 0.22f; val b = s * 0.1f
        c.drawLine(cx - a, cy - a, cx - a + b, cy - a, p); c.drawLine(cx - a, cy - a, cx - a, cy - a + b, p)
        c.drawLine(cx + a, cy + a, cx + a - b, cy + a, p); c.drawLine(cx + a, cy + a, cx + a, cy + a - b, p)
        c.drawLine(cx - a, cy - a, cx - a * 0.2f, cy - a * 0.2f, p)
        c.drawLine(cx + a, cy + a, cx + a * 0.2f, cy + a * 0.2f, p)
    }

    fun cerrar(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val a = s * 0.16f
        c.drawLine(cx - a, cy - a, cx + a, cy + a, p)
        c.drawLine(cx + a, cy - a, cx - a, cy + a, p)
    }

    fun chincheta(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, lleno: Boolean) {
        trazo(p, s * 0.8f)
        if (lleno) p.style = Paint.Style.FILL_AND_STROKE
        r.set(cx - s * 0.12f, cy - s * 0.24f, cx + s * 0.12f, cy + s * 0.02f)
        c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
        p.style = Paint.Style.STROKE
        c.drawLine(cx - s * 0.2f, cy + s * 0.04f, cx + s * 0.2f, cy + s * 0.04f, p)
        c.drawLine(cx, cy + s * 0.04f, cx, cy + s * 0.26f, p)
    }
}
