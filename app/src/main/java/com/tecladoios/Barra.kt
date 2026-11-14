package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Barra de arriba del teclado. Mientras escribes muestra tres sugerencias (la del centro es la
 * mejor, y se pinta del color de la app si se pondrá sola al tocar espacio; a la izquierda,
 * entre comillas, lo que escribiste tal cual). Sin escribir muestra las herramientas:
 * portapapeles, edición de texto, una mano, tamaño y ajustes, más lo último que
 * copiaste o tu captura reciente para enviarlos rápido.
 */
class Barra(context: Context) : View(context) {

    interface Oyente {
        fun elegir(texto: String, esLiteral: Boolean)
        fun olvidar(texto: String)
        fun herramienta(cual: Int)
        fun pegarChip()
        fun enviarCaptura()
        fun emojiBuscado(e: String)
        fun cerrarBusqueda()
        fun respuesta(tipo: Respuesta.Tipo)
    }

    companion object {
        const val H_PORTAPAPELES = 1
        const val H_EDITAR = 2
        const val H_UNA_MANO = 3
        const val H_ESCANEAR = 4
        const val H_AJUSTES = 5
        const val H_EMOJI = 6
        const val H_TAMANO = 7
    }

    var oyente: Oyente? = null
    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }
    var herramientasActivas = true

    /**
     * La barra nunca se esconde: siempre muestra palabras. Las herramientas se abren con el
     * botón de la izquierda (como en Gboard) y se cierran solas al seguir escribiendo.
     */
    private var verHerramientas = false

    fun cerrarHerramientas() { if (verHerramientas) { verHerramientas = false; animar(180f, true) } }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // Contenido
    private var sugerencias: List<String> = emptyList()
    private var literal: String? = null
    private var destacada = -1   // índice que se pondrá solo
    private var chipTexto: String? = null
    private var captura: Bitmap? = null
    private var buscando = false
    private var consulta = ""
    private var resultados: List<String> = emptyList()
    private var corrimiento = 0f

    fun mostrarSugerencias(lista: List<String>, literalEntreComillas: String?, autocorrige: Boolean) {
        val nuevas = lista.take(3)
        if (nuevas == sugerencias && literalEntreComillas == literal && (autocorrige && lista.isNotEmpty()) == (destacada >= 0)) return
        val cambiaModo = sugerencias.isEmpty() && literal == null
        sugerencias = nuevas; literal = literalEntreComillas
        destacada = if (autocorrige && lista.isNotEmpty()) 0 else -1
        // Al pasar de herramientas a sugerencias se cruza toda la barra; mientras escribes,
        // cada palabra entra por su cuenta (ver dibujarSugerencias)
        if (cambiaModo) animar(200f, true) else invalidate()
    }

    fun limpiar() {
        if (sugerencias.isEmpty() && literal == null) return
        sugerencias = emptyList(); literal = null; destacada = -1
        for (i in 0 until 3) celdaPrevia[i] = null
        animar(230f, true)
    }

    // ---------- Animación (las palabras aparecen suave; herramientas y sugerencias se cruzan) ----------

    private var animInicio = 0L
    private var animDura = 0f
    private var animSube = false

    private fun animar(duracion: Float, subir: Boolean) {
        animInicio = android.os.SystemClock.uptimeMillis(); animDura = duracion; animSube = subir
        invalidate()
    }

    fun hayContenido() = sugerencias.isNotEmpty() || literal != null

    fun chip(texto: String?) { chipTexto = texto; invalidate() }

    fun captura(b: Bitmap?) { captura = b; invalidate() }

    /** [recientes] = cuántos de [res] (los primeros) son tus usados recientemente */
    fun busqueda(activa: Boolean, q: String, res: List<String>, recientes: Int = 0) {
        buscando = activa; consulta = q; resultados = res; cuantosRecientes = recientes; corrimiento = 0f; invalidate()
    }
    private var cuantosRecientes = 0

    // ---------- Dibujo ----------

    private val texto = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 400) }
    private val fuerte = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 600) }
    private val icono = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linea = Paint(Paint.ANTI_ALIAS_FLAG)
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private val zonas = ArrayList<Pair<RectF, () -> Any?>>()
    private val zonasLargas = ArrayList<Pair<RectF, () -> Any?>>()

    private val HERRAMIENTAS = intArrayOf(H_EMOJI, H_PORTAPAPELES, H_EDITAR, H_UNA_MANO, H_TAMANO, H_AJUSTES)

    override fun onDraw(c: Canvas) {
        zonas.clear(); zonasLargas.clear()
        val w = width.toFloat(); val h = height.toFloat()
        // Línea finísima que separa la barra de las teclas (detalle premium, casi invisible)
        linea.color = 0x14FFFFFF; linea.strokeWidth = d * 0.6f
        c.drawLine(dp(18f), h - d * 0.6f, w - dp(18f), h - d * 0.6f, linea)
        val t = if (animDura > 0f) ((android.os.SystemClock.uptimeMillis() - animInicio) / animDura).coerceIn(0f, 1f) else 1f
        if (t < 1f) {
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            c.saveLayerAlpha(0f, 0f, w, h, (90 + 165 * e).toInt())
            c.translate(0f, if (animSube) (1f - e) * dp(7f) else (1f - e) * dp(2.5f))
            dibujarContenido(c, w, h)
            c.restore()
            postInvalidateOnAnimation()
        } else dibujarContenido(c, w, h)
    }

    private fun dibujarContenido(c: Canvas, w: Float, h: Float) {
        texto.textSize = dp(16.5f); fuerte.textSize = dp(16.5f)
        linea.color = tema.separador; linea.strokeWidth = dp(1f)
        if (buscando) { dibujarBusqueda(c, w, h); return }
        if ((sugerencias.isNotEmpty() || literal != null) && !verHerramientas) { dibujarSugerencias(c, w, h); return }
        dibujarHerramientas(c, w, h)
    }

    // Cada casilla anima su palabra al cambiar: la del centro primero y luego los costados
    private val celdaPrevia = arrayOfNulls<String>(3)
    private val celdaMs = LongArray(3)
    private val celdaDura = FloatArray(3)
    private val celdaSube = FloatArray(3)
    private val RETRASO_CELDA = longArrayOf(30L, 0L, 60L)

    // Destello de la palabra que elegiste
    private var elegidaMs = 0L
    private var elegidaCelda = -1
    private val DESTELLO = 340f

    private fun dibujarSugerencias(c: Canvas, w: Float, h: Float) {
        // Orden: [izquierda, centro (la mejor), derecha]
        val celdas = arrayOfNulls<String>(3)
        val esLiteral = BooleanArray(3)
        val mejor = sugerencias.getOrNull(0)
        celdas[1] = mejor
        if (literal != null) { celdas[0] = literal; esLiteral[0] = true; celdas[2] = sugerencias.getOrNull(1) }
        else { celdas[0] = sugerencias.getOrNull(1); celdas[2] = sugerencias.getOrNull(2) }
        // Botón de herramientas a la izquierda (cuatro puntitos, como Gboard)
        val boton = dp(40f)
        val ahora = android.os.SystemClock.uptimeMillis()
        run {
            val cx = boton / 2 + dp(2f); val cy = h / 2
            val apretado = tocando && toqueX in 0f..boton
            relleno.color = if (apretado) 0xFF4A1E24.toInt() else 0xFF1A1618.toInt()
            c.drawCircle(cx, cy, dp(15f), relleno)
            relleno.color = tema.textoSuave
            val sep = dp(4.2f); val lado = dp(2.6f)
            for (a in -1..1 step 2) for (b in -1..1 step 2)
                c.drawRoundRect(cx + a * sep - lado, cy + b * sep - lado, cx + a * sep + lado, cy + b * sep + lado, dp(1.2f), dp(1.2f), relleno)
            zonas.add(RectF(0f, 0f, boton + dp(4f), h) to { verHerramientas = true; animar(200f, true) })
        }
        c.save()
        c.translate(boton + dp(4f), 0f)
        val anchoUtil = w - boton - dp(4f)
        val ancho = anchoUtil / 3
        val desplazamiento = boton + dp(4f)
        var seguir = false
        // Casilla bajo el dedo: una cápsula tenue mientras lo mantienes
        if (tocando && !arrastrando && toqueX >= desplazamiento) {
            val i = ((toqueX - desplazamiento) / ancho).toInt().coerceIn(0, 2)
            if (celdas[i] != null) {
                relleno.color = 0x1FFFFFFF
                r.set(i * ancho + dp(5f), dp(5f), (i + 1) * ancho - dp(5f), h - dp(5f))
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
            }
        }
        // Destello rojo detrás de la palabra elegida (se abre y se apaga)
        if (elegidaCelda in 0..2) {
            val t = ((ahora - elegidaMs) / DESTELLO).coerceIn(0f, 1f)
            if (t < 1f) {
                val u = 1f - t
                val e = 1f - u * u * u
                relleno.color = 0xFFFF2D3A.toInt(); relleno.alpha = (0x55 * u * u).toInt()
                val cxD = elegidaCelda * ancho + ancho / 2
                val medio = (ancho / 2 - dp(6f)) * (0.7f + 0.3f * e)
                r.set(cxD - medio, dp(5f) + dp(2f) * (1f - e), cxD + medio, h - dp(5f) - dp(2f) * (1f - e))
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
                relleno.alpha = 255
                seguir = true
            } else elegidaCelda = -1
        }
        for (i in 0 until 3) {
            val cambio = celdas[i] != celdaPrevia[i]
            if (cambio) {
                // Entrar desde vacío se nota más; cambiar mientras escribes es más corto y sutil
                val desdeVacio = celdaPrevia[i] == null
                celdaMs[i] = ahora + if (desdeVacio) RETRASO_CELDA[i] else RETRASO_CELDA[i] / 2
                celdaDura[i] = if (desdeVacio) 230f else 150f
                celdaSube[i] = if (desdeVacio) dp(7f) else dp(3.5f)
                celdaPrevia[i] = celdas[i]
            }
        }
        for (i in 0 until 3) {
            val s = celdas[i] ?: continue
            val x0 = i * ancho
            val p = if (i == 1 && destacada >= 0) fuerte else texto
            val destacadaAqui = i == 1 && destacada >= 0
            val tc = if (celdaDura[i] > 0f) ((ahora - celdaMs[i]) / celdaDura[i]).coerceIn(0f, 1f) else 1f
            if (tc < 1f) seguir = true
            val uc = 1f - tc
            val ec = 1f - uc * uc * uc
            c.save()
            c.translate(0f, celdaSube[i] * (1f - ec))
            val escalaC = 0.94f + 0.06f * ec
            c.scale(escalaC, escalaC, x0 + ancho / 2, h / 2)
            // Sin cápsula ni color: la palabra que se pondrá sola brilla (blanco intenso con un
            // halo suave) y las demás quedan en un blanco más tenue
            p.color = if (destacadaAqui) 0xFFFFFFFF.toInt() else 0xBFEBEBF5.toInt()
            val mostrado = if (esLiteral[i]) "«$s»" else s
            val corto = TextUtils.ellipsize(mostrado, p, ancho - dp(22f), TextUtils.TruncateAt.MIDDLE).toString()
            if (destacadaAqui) p.setShadowLayer(dp(8f), 0f, 0f, 0xB3FF2D3A.toInt())
            val alfaBase = p.alpha
            p.alpha = (alfaBase * (0.15f + 0.85f * ec)).toInt()
            c.drawText(corto, x0 + ancho / 2, h / 2 + p.textSize * 0.36f, p)
            p.alpha = alfaBase
            if (destacadaAqui) p.clearShadowLayer()
            c.restore()
            val rr = RectF(desplazamiento + x0, 0f, desplazamiento + x0 + ancho, h)
            val lit = esLiteral[i]
            val celda = i
            zonas.add(rr to { elegidaCelda = celda; elegidaMs = android.os.SystemClock.uptimeMillis(); oyente?.elegir(s, lit) })
            if (!lit) zonasLargas.add(rr to { oyente?.olvidar(s) })
        }
        // Separadores: no se dibujan junto a una casilla vacía
        for (i in 1..2) if (celdas[i - 1] != null && celdas[i] != null) c.drawLine(i * ancho, h * 0.3f, i * ancho, h * 0.7f, linea)
        c.restore()
        if (seguir) postInvalidateOnAnimation()
    }

    private fun dibujarHerramientas(c: Canvas, w: Float, h: Float) {
        var izq = dp(8f)
        if (verHerramientas) {
            // Volver a las palabras
            val cx = dp(22f); val cy = h / 2
            val apretado = tocando && toqueX in 0f..dp(42f)
            relleno.color = if (apretado) 0xFF4A1E24.toInt() else 0xFF1A1618.toInt()
            c.drawCircle(cx, cy, dp(15f), relleno)
            icono.color = tema.textoSuave
            Iconos.flecha(c, cx, cy, dp(30f), icono, 0f)
            zonas.add(RectF(0f, 0f, dp(44f), h) to { verHerramientas = false; animar(200f, true) })
            izq = dp(48f)
        }
        // Chips: captura reciente y lo último copiado
        val cap = captura
        if (cap != null) {
            val lado = h - dp(12f)
            r.set(izq, dp(6f), izq + lado, dp(6f) + lado)
            c.save()
            val camino = android.graphics.Path().apply { addRoundRect(r, dp(6f), dp(6f), android.graphics.Path.Direction.CW) }
            c.clipPath(camino)
            c.drawBitmap(cap, null, r, null)
            c.restore()
            relleno.style = Paint.Style.STROKE; relleno.strokeWidth = dp(1f); relleno.color = tema.acento
            c.drawRoundRect(r, dp(6f), dp(6f), relleno)
            relleno.style = Paint.Style.FILL
            zonas.add(RectF(r) to { oyente?.enviarCaptura() })
            izq = r.right + dp(8f)
        }
        val chip = chipTexto
        val anchoHerr = dp(42f)
        val herrs = if (herramientasActivas) HERRAMIENTAS else intArrayOf(H_AJUSTES)
        val espacioHerr = herrs.size * anchoHerr
        if (chip != null) {
            texto.textSize = dp(14f)
            val maxChip = (w - izq - espacioHerr - dp(16f)).coerceAtLeast(dp(60f))
            val corto = TextUtils.ellipsize(chip.replace('\n', ' '), texto, maxChip - dp(36f), TextUtils.TruncateAt.END).toString()
            val anchoChip = texto.measureText(corto) + dp(36f)
            r.set(izq, dp(7f), izq + anchoChip, h - dp(7f))
            relleno.color = tema.teclaFuncion
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
            relleno.style = Paint.Style.STROKE; relleno.strokeWidth = dp(1f); relleno.color = Tema.mezclar(tema.acento, 0, 0.7f)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
            relleno.style = Paint.Style.FILL
            icono.color = tema.textoSuave
            Iconos.portapapeles(c, r.left + dp(16f), r.centerY(), dp(22f), icono)
            texto.color = tema.texto
            texto.textAlign = Paint.Align.LEFT
            c.drawText(corto, r.left + dp(28f), r.centerY() + texto.textSize * 0.36f, texto)
            texto.textAlign = Paint.Align.CENTER
            zonas.add(RectF(r) to { oyente?.pegarChip() })
            texto.textSize = dp(16.5f)
        }
        // Herramientas a la derecha
        var x = w - dp(4f) - espacioHerr
        if (chip == null && cap == null) x = maxOf(izq, (w - espacioHerr) / 2f)
        icono.color = tema.textoSuave
        val s = dp(30f)
        for (hId in herrs) {
            val cx = x + anchoHerr / 2; val cy = h / 2
            // Cada herramienta en un botón redondo oscuro
            val apretado = toqueX in x..(x + anchoHerr) && tocando
            relleno.color = if (apretado) 0xFF4A1E24.toInt() else 0xFF1A1618.toInt()
            c.drawCircle(cx, cy, dp(17f), relleno)
            // Aro de luz finísimo: el botón se ve con relieve
            linea.color = 0x1AFFFFFF; linea.strokeWidth = d * 0.7f
            linea.style = Paint.Style.STROKE
            c.drawCircle(cx, cy, dp(17f), linea)
            linea.style = Paint.Style.FILL_AND_STROKE
            when (hId) {
                H_EMOJI -> Iconos.cara(c, cx, cy, s, icono)
                H_PORTAPAPELES -> Iconos.portapapeles(c, cx, cy, s, icono)
                H_EDITAR -> Iconos.editar(c, cx, cy, s, icono)
                H_UNA_MANO -> Iconos.unaMano(c, cx, cy, s, icono)
                H_TAMANO -> Iconos.tamano(c, cx, cy, s, icono)
                H_AJUSTES -> Iconos.engranaje(c, cx, cy, s, icono)
            }
            val id = hId
            zonas.add(RectF(x, 0f, x + anchoHerr, h) to { oyente?.herramienta(id) })
            x += anchoHerr
        }
    }

    private fun dibujarBusqueda(c: Canvas, w: Float, h: Float) {
        icono.color = tema.textoSuave
        Iconos.lupa(c, dp(20f), h / 2, dp(30f), icono)
        texto.textAlign = Paint.Align.LEFT
        texto.textSize = dp(15f)
        texto.color = if (consulta.isEmpty()) tema.textoSuave else tema.texto
        val q = if (consulta.isEmpty()) "Buscar emoji" else consulta
        val anchoQ = minOf(texto.measureText(q), w * 0.3f)
        c.drawText(TextUtils.ellipsize(q, texto, w * 0.3f, TextUtils.TruncateAt.START).toString(), dp(36f), h / 2 + texto.textSize * 0.36f, texto)
        texto.textAlign = Paint.Align.CENTER
        // Cursor
        relleno.color = tema.acento
        c.drawRect(dp(37f) + anchoQ, h * 0.28f, dp(39f) + anchoQ, h * 0.72f, relleno)
        val inicio = dp(46f) + anchoQ
        val fin = w - dp(40f)
        icono.color = tema.textoSuave
        Iconos.cerrar(c, w - dp(20f), h / 2, dp(32f), icono)
        zonas.add(RectF(fin, 0f, w, h) to { oyente?.cerrarBusqueda() })
        c.save()
        c.clipRect(inicio, 0f, fin, h)
        texto.textSize = dp(26f)
        val celda = dp(42f)
        val hueco = dp(22f)   // espacio entre «recientes» y los demás
        val recientes = cuantosRecientes.coerceIn(0, resultados.size)
        var x = inicio - corrimiento
        if (recientes > 0) {
            // Relojito que marca el apartado de usados recientemente (como en el panel)
            val chico = texto.textSize
            texto.textSize = dp(13f); texto.alpha = 150
            c.drawText("\uD83D\uDD52", x + dp(10f), h / 2 + texto.textSize * 0.36f, texto)
            texto.alpha = 255; texto.textSize = chico
            x += dp(22f)
        }
        for ((i, e) in resultados.withIndex()) {
            if (i == recientes && recientes > 0) {
                // Separador finito: después de tus recientes vienen los emojis normales
                relleno.color = 0x33FFFFFF
                val lx = x + hueco / 2
                c.drawRect(lx - d * 0.5f, h * 0.25f, lx + d * 0.5f, h * 0.75f, relleno)
                x += hueco
            }
            if (x + celda > inicio && x < fin) {
                c.drawText(e, x + celda / 2, h / 2 + texto.textSize * 0.36f, texto)
                zonas.add(RectF(x, 0f, x + celda, h) to { oyente?.emojiBuscado(e) })
            }
            x += celda
        }
        val extra = if (recientes > 0) dp(22f) + hueco else 0f
        maxCorrimiento = (resultados.size * celda + extra - (fin - inicio)).coerceAtLeast(0f)
        c.restore()
        texto.textSize = dp(16.5f)
    }

    private var maxCorrimiento = 0f

    // ---------- Toques ----------

    private var toqueX = -1f
    private var tocando = false
    private var x0 = 0f
    private var ultX = 0f
    private var arrastrando = false
    private var largaHecha = false
    private val larga = Runnable {
        val z = zonasLargas.firstOrNull { it.first.contains(x0, height / 2f) }
        if (z != null) { largaHecha = true; oyente?.respuesta(Respuesta.Tipo.FUNCION); z.second() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; ultX = e.x; arrastrando = false; largaHecha = false
                toqueX = e.x; tocando = true; invalidate()
                postDelayed(larga, 550L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - x0) > dp(10f)) { arrastrando = true; removeCallbacks(larga) }
                if (arrastrando && buscando) {
                    corrimiento = (corrimiento - (e.x - ultX)).coerceIn(0f, maxCorrimiento)
                    invalidate()
                }
                ultX = e.x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(larga)
                tocando = false; invalidate()
                if (!arrastrando && !largaHecha) {
                    val z = zonas.firstOrNull { it.first.contains(e.x, e.y) }
                    if (z != null) { oyente?.respuesta(Respuesta.Tipo.LETRA); z.second() }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(larga); tocando = false; invalidate() }
        }
        return true
    }
}
