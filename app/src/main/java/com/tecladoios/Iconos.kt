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

    private var grosor = 1f

    /** Dibuja con trazo más fino (teclas de función con ícono de luz) */
    inline fun fino(f: () -> Unit) { f() }   // estilo iOS: mismo trazo en todos
    fun ponerGrosor(g: Float) { grosor = g }

    private fun trazo(p: Paint, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.08f * grosor
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    // Esquinas suavizadas como los íconos de iOS (se crean según el tamaño)
    private var esquina: android.graphics.CornerPathEffect? = null
    private var esquinaDe = -1f
    private fun suaves(p: Paint, radio: Float) {
        if (esquinaDe != radio) { esquina = android.graphics.CornerPathEffect(radio); esquinaDe = radio }
        p.pathEffect = esquina
    }

    /** Flecha de mayúscula como la de iOS; [lleno] = activa, [bloqueo] = raya debajo */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, lleno: Boolean, bloqueo: Boolean) {
        val ancho = s * 0.44f            // ancho de la punta
        val tallo = s * 0.19f            // ancho del tallo
        val alto = s * 0.42f
        val top = cy - alto * (if (bloqueo) 0.62f else 0.52f)
        val medio = top + alto * 0.5f
        val base = top + alto
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx + ancho / 2, medio)
        path.lineTo(cx + tallo / 2, medio)
        path.lineTo(cx + tallo / 2, base)
        path.lineTo(cx - tallo / 2, base)
        path.lineTo(cx - tallo / 2, medio)
        path.lineTo(cx - ancho / 2, medio)
        path.close()
        trazo(p, s * 0.8f)
        suaves(p, s * 0.035f)
        if (lleno) p.style = Paint.Style.FILL_AND_STROKE
        c.drawPath(path, p)
        p.pathEffect = null
        if (bloqueo) {
            p.style = Paint.Style.FILL
            val y = base + s * 0.07f
            r.set(cx - tallo / 2 - s * 0.01f, y, cx + tallo / 2 + s * 0.01f, y + s * 0.055f)
            c.drawRoundRect(r, s * 0.015f, s * 0.015f, p)
            p.style = Paint.Style.STROKE
        }
    }

    /** Borrar como el de iOS: etiqueta con punta a la izquierda y una × adentro */
    fun borrar(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, lleno: Boolean) {
        val w = s * 0.56f; val h = s * 0.38f
        val l = cx - w / 2; val t = cy - h / 2
        path.reset()
        path.moveTo(l + w * 0.3f, t)
        path.lineTo(l + w, t)
        path.lineTo(l + w, t + h)
        path.lineTo(l + w * 0.3f, t + h)
        path.lineTo(l, cy)
        path.close()
        trazo(p, s * 0.8f)
        suaves(p, s * 0.04f)
        if (lleno) { p.style = Paint.Style.FILL_AND_STROKE; c.drawPath(path, p); p.color = 0xFF1C1C1F.toInt() }
        else c.drawPath(path, p)
        p.pathEffect = null
        p.style = Paint.Style.STROKE
        val x0 = l + w * 0.62f; val d = h * 0.19f
        c.drawLine(x0 - d, cy - d, x0 + d, cy + d, p)
        c.drawLine(x0 + d, cy - d, x0 - d, cy + d, p)
    }

    /** Intro ↵ como el de iOS: baja desde arriba a la derecha y gira a la izquierda con punta */
    fun intro(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.42f; val h = s * 0.26f
        trazo(p, s * 0.8f)
        val der = cx + w / 2; val izq = cx - w / 2
        val abajo = cy + h * 0.45f
        path.reset()
        path.moveTo(der, cy - h)
        path.lineTo(der, abajo)
        path.lineTo(izq, abajo)
        suaves(p, s * 0.04f)
        c.drawPath(path, p)
        p.pathEffect = null
        val punta = h * 0.62f
        path.reset()
        path.moveTo(izq + punta, abajo - punta)
        path.lineTo(izq, abajo)
        path.lineTo(izq + punta, abajo + punta)
        c.drawPath(path, p)
    }

    /** Carita rellena (tecla emoji mantenida): círculo lleno con ojos y sonrisa del color de la tecla */
    fun caraLlena(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, fondo: Int) {
        val previo = p.color
        p.style = Paint.Style.FILL
        val rad = s * 0.25f + s * 0.02f
        c.drawCircle(cx, cy, rad, p)
        p.color = fondo
        val ojoW = s * 0.022f; val ojoH = s * 0.036f
        val r0 = s * 0.25f
        r.set(cx - r0 * 0.36f - ojoW, cy - r0 * 0.26f - ojoH, cx - r0 * 0.36f + ojoW, cy - r0 * 0.26f + ojoH)
        c.drawOval(r, p)
        r.set(cx + r0 * 0.36f - ojoW, cy - r0 * 0.26f - ojoH, cx + r0 * 0.36f + ojoW, cy - r0 * 0.26f + ojoH)
        c.drawOval(r, p)
        trazo(p, s * 0.8f)
        r.set(cx - r0 * 0.52f, cy - r0 * 0.3f, cx + r0 * 0.52f, cy + r0 * 0.56f)
        c.drawArc(r, 22f, 136f, false, p)
        p.color = previo
    }

    /** Carita (emojis), como la de iOS: círculo, ojos ovalados y sonrisa */
    fun cara(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val rad = s * 0.25f
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.FILL
        val ojoW = s * 0.022f; val ojoH = s * 0.036f
        r.set(cx - rad * 0.36f - ojoW, cy - rad * 0.26f - ojoH, cx - rad * 0.36f + ojoW, cy - rad * 0.26f + ojoH)
        c.drawOval(r, p)
        r.set(cx + rad * 0.36f - ojoW, cy - rad * 0.26f - ojoH, cx + rad * 0.36f + ojoW, cy - rad * 0.26f + ojoH)
        c.drawOval(r, p)
        p.style = Paint.Style.STROKE
        r.set(cx - rad * 0.52f, cy - rad * 0.3f, cx + rad * 0.52f, cy + rad * 0.56f)
        c.drawArc(r, 22f, 136f, false, p)
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

    /** Rectángulo con flechas arriba y abajo (ajustar tamaño) */
    fun tamano(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        trazo(p, s * 0.8f)
        val w = s * 0.5f; val h = s * 0.26f
        r.set(cx - w / 2, cy - h / 2 + s * 0.04f, cx + w / 2, cy + h / 2 + s * 0.04f)
        c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
        val a = s * 0.07f
        c.drawLine(cx, cy - s * 0.3f, cx, cy - s * 0.14f, p)
        c.drawLine(cx - a, cy - s * 0.3f + a, cx, cy - s * 0.3f, p); c.drawLine(cx + a, cy - s * 0.3f + a, cx, cy - s * 0.3f, p)
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
