package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
        /** Tocaste la palabra recién corregida: vuelve lo que escribiste */
        fun deshacerCorreccion()
    }

    companion object {
        const val CORREGIDA_DURA = 2600L
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

    /** Estilo según la app: 0 normal, 1 chat (línea cálida), 2 navegador (más compacta) */
    var estilo = 0; set(v) { if (field != v) { field = v; invalidate() } }

    /** Campo de contraseña o modo incógnito: no aprende ni guarda nada (candado en la barra) */
    var privado = false; set(v) { if (field != v) { field = v; invalidate() } }

    // Palabra que el teclado acaba de corregir: se ilumina un momento y al tocarla se deshace
    private var corregida: String? = null
    private var corregidaMs = 0L

    fun avisarCorreccion(puesta: String?) {
        if (puesta == null && corregida == null) return
        corregida = puesta; corregidaMs = android.os.SystemClock.uptimeMillis()
        invalidate()
        if (puesta != null) postInvalidateDelayed(CORREGIDA_DURA + 20)
    }

    private fun corregidaVisible(): String? {
        val c = corregida ?: return null
        if (android.os.SystemClock.uptimeMillis() - corregidaMs > CORREGIDA_DURA) { corregida = null; return null }
        return c
    }

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

    /** Palabra que mantuviste presionada: la barra pregunta si eliminarla */
    private var porEliminar: String? = null

    fun mostrarSugerencias(lista: List<String>, literalEntreComillas: String?, autocorrige: Boolean) {
        val nuevas = lista.take(3)
        if (porEliminar != null && (nuevas != sugerencias || literalEntreComillas != literal)) porEliminar = null
        // Empezaste otra palabra: la corrección anterior ya no se ofrece
        if (literalEntreComillas != null || autocorrige) corregida = null
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
        // Fundido cortito sin movimiento (en la GPU, casi no gasta)
        animInicio = android.os.SystemClock.uptimeMillis(); animDura = minOf(duracion, 140f); animSube = false
        invalidate()
        invalidate()
    }

    fun hayContenido() = sugerencias.isNotEmpty() || literal != null

    /** [etiqueta]: "De Mensajes", "Tu correo"… (sin etiqueta es lo copiado) */
    fun chip(texto: String?, etiqueta: String? = null) {
        if (chipTexto == texto && chipEtiqueta == etiqueta) return
        chipTexto = texto; chipEtiqueta = etiqueta; invalidate()
    }
    private var chipEtiqueta: String? = null

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

    // Las palabras ya recortadas se guardan: durante las animaciones no se mide el texto en cada cuadro
    private val recortes = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }
    private fun recortar(t: String, p: android.text.TextPaint, max: Float): String {
        val clave = "$t|${max.toInt()}|${p.textSize.toInt()}"
        return recortes.getOrPut(clave) { TextUtils.ellipsize(t, p, max, TextUtils.TruncateAt.MIDDLE).toString() }
    }
    private val pequeno = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 500) }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private val zonas = ArrayList<Pair<RectF, () -> Any?>>()
    private val zonasLargas = ArrayList<Pair<RectF, () -> Any?>>()

    private val HERRAMIENTAS = intArrayOf(H_EMOJI, H_PORTAPAPELES, H_EDITAR, H_UNA_MANO, H_TAMANO, H_AJUSTES)

    // Un error al dibujar se anota y no cierra el teclado
    override fun onDraw(c: Canvas) {
        val n = c.save()
        try { dibujarTodo(c) } catch (e: Throwable) { Registro.anotar("dibujo barra", e) } finally { c.restoreToCount(n) }
    }

    private fun dibujarTodo(c: Canvas) {
        zonas.clear(); zonasLargas.clear()
        val w = width.toFloat(); val h = height.toFloat()
        // Línea finísima que separa la barra de las teclas (detalle premium, casi invisible)
        // Sin línea de borde: la barra y las teclas son una sola pieza de vidrio
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
        // En el navegador la barra es más compacta (letra algo menor)
        val tam = if (estilo == 2) dp(15.5f) else dp(17.5f)
        texto.textSize = tam; fuerte.textSize = tam
        linea.color = tema.separador; linea.strokeWidth = dp(0.8f); linea.strokeCap = Paint.Cap.ROUND
        if (buscando) { dibujarBusqueda(c, w, h); return }
        porEliminar?.let { dibujarEliminar(c, w, h, it); return }
        if ((sugerencias.isNotEmpty() || literal != null) && !verHerramientas) { dibujarSugerencias(c, w, h); return }
        dibujarHerramientas(c, w, h)
    }

    /** «¿Eliminar "palabra"?» con Cancelar y Eliminar (como Gboard) */
    private fun dibujarEliminar(c: Canvas, w: Float, h: Float, palabra: String) {
        val bh = h - dp(12f)
        val anchoBoton = dp(92f)
        val eliminar = RectF(w - dp(8f) - anchoBoton, (h - bh) / 2, w - dp(8f), (h + bh) / 2)
        val cancelar = RectF(eliminar.left - dp(8f) - anchoBoton, eliminar.top, eliminar.left - dp(8f), eliminar.bottom)
        texto.textAlign = Paint.Align.LEFT
        texto.textSize = dp(14.5f); texto.color = tema.texto
        val pregunta = TextUtils.ellipsize("¿Eliminar «$palabra»?", texto, cancelar.left - dp(22f), TextUtils.TruncateAt.MIDDLE).toString()
        c.drawText(pregunta, dp(14f), h / 2 + texto.textSize * 0.36f, texto)
        texto.textAlign = Paint.Align.CENTER
        val apretado = { r: RectF -> tocando && toqueX >= r.left && toqueX <= r.right }
        relleno.color = if (apretado(cancelar)) tema.velo(0x42) else tema.velo(0x1F)
        c.drawRoundRect(cancelar, bh / 2, bh / 2, relleno)
        relleno.color = if (apretado(eliminar)) 0xFFC81E2B.toInt() else 0xFFFF3B30.toInt()
        c.drawRoundRect(eliminar, bh / 2, bh / 2, relleno)
        fuerte.textSize = dp(14.5f); fuerte.color = tema.texto
        c.drawText("Cancelar", cancelar.centerX(), h / 2 + fuerte.textSize * 0.36f, fuerte)
        fuerte.color = 0xFFFFFFFF.toInt()
        c.drawText("Eliminar", eliminar.centerX(), h / 2 + fuerte.textSize * 0.36f, fuerte)
        texto.textSize = dp(16.5f); fuerte.textSize = dp(16.5f)
        zonas.add(RectF(cancelar.left - dp(4f), 0f, cancelar.right + dp(4f), h) to { porEliminar = null; invalidate() })
        zonas.add(RectF(eliminar.left - dp(4f), 0f, w, h) to { porEliminar = null; oyente?.olvidar(palabra); invalidate() })
        zonas.add(RectF(0f, 0f, cancelar.left - dp(4f), h) to { porEliminar = null; invalidate() })
    }

    // Cada casilla anima su palabra al cambiar: la del centro primero y luego los costados
    private val celdaPrevia = arrayOfNulls<String>(3)
    private val celdaMs = LongArray(3)
    private val celdaDura = FloatArray(3)
    private val celdaSube = FloatArray(3)
    private val RETRASO_CELDA = longArrayOf(20L, 0L, 40L)

    // Destello de la palabra que elegiste
    private var elegidaMs = 0L
    private var elegidaCelda = -1
    private val DESTELLO = 150f

    private fun dibujarSugerencias(c: Canvas, w: Float, h: Float) {
        // Orden: [izquierda, centro (la mejor), derecha]
        val celdas = arrayOfNulls<String>(3)
        val esLiteral = BooleanArray(3)
        val mejor = sugerencias.getOrNull(0)
        celdas[1] = mejor
        if (literal != null) { celdas[0] = literal; esLiteral[0] = true; celdas[2] = sugerencias.getOrNull(1) }
        else { celdas[0] = sugerencias.getOrNull(1); celdas[2] = sugerencias.getOrNull(2) }
        // La palabra recién corregida ocupa la izquierda y brilla en violeta
        // Botón de herramientas a la izquierda (cuatro puntitos, como Gboard)
        val boton = dp(40f)
        val ahora = android.os.SystemClock.uptimeMillis()
        run {
            val cx = boton / 2 + dp(2f); val cy = h / 2
            val apretado = tocando && toqueX in 0f..boton
            if (apretado) { relleno.color = 0x24FFFFFF; c.drawCircle(cx, cy, dp(15f), relleno) }
            if (privado) candado(c, cx, cy) else {
                relleno.color = tema.textoSuave
                val sep = dp(4.2f); val lado = dp(2.6f)
                for (a in -1..1 step 2) for (b in -1..1 step 2)
                    c.drawRoundRect(cx + a * sep - lado, cy + b * sep - lado, cx + a * sep + lado, cy + b * sep + lado, dp(1.2f), dp(1.2f), relleno)
            }
            zonas.add(RectF(0f, 0f, boton + dp(4f), h) to { verHerramientas = true; animar(200f, true) })
        }
        c.save()
        c.translate(boton + dp(4f), 0f)
        val anchoUtil = w - boton - dp(4f)
        val ancho = anchoUtil / 3
        val desplazamiento = boton + dp(4f)
        var seguir = false
        // Sin cápsulas: la palabra destacada, la que tocas y la que eliges solo brillan más
        val celdaTocada = if (tocando && !arrastrando && toqueX >= desplazamiento)
            ((toqueX - desplazamiento) / ancho).toInt().coerceIn(0, 2) else -1
        var brilloElegida = 0f
        if (elegidaCelda in 0..2) {
            val t = ((ahora - elegidaMs) / DESTELLO).coerceIn(0f, 1f)
            if (t < 1f) { brilloElegida = (1f - t) * (1f - t); seguir = true } else elegidaCelda = -1
        }
        for (i in 0 until 3) {
            val cambio = celdas[i] != celdaPrevia[i]
            if (cambio) {
                // Entrar desde vacío se nota más; cambiar mientras escribes es más corto y sutil
                val desdeVacio = celdaPrevia[i] == null
                celdaMs[i] = ahora + if (desdeVacio) RETRASO_CELDA[i] else RETRASO_CELDA[i] / 2
                // Fundido cortito como el iPhone (al aparecer, sube apenas)
                // Mientras escribes, las palabras cambian al instante (sin esperar ningún fundido)
                // Barra sin saltos: mientras escribes, las palabras cambian con un fundido
                // rapidísimo en su lugar (nada se mueve). Solo al aparecer desde vacío entran
                // con un deslizamiento cortito.
                celdaDura[i] = if (desdeVacio) 140f else 70f
                celdaSube[i] = if (desdeVacio) dp(8f) else 0f
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
            c.translate(celdaSube[i] * (1f - ec), 0f)
            // La que se pondrá sola brilla fuerte; las demás quedan tenues y lo que escribiste
            // (entre comillas) va aún más apagado, para que se distinga de las sugerencias
            val esCorregida = false
            val elegidaAqui = i == 1 && destacada >= 0
            p.color = when {
                elegidaAqui -> tema.texto
                esLiteral[i] -> tema.velo(0x6B)
                i == 1 -> tema.velo(0xD0)
                else -> tema.velo(0x8F)
            }
            if (elegidaAqui) p.setShadowLayer(dp(7f), 0f, 0f, if (tema.claro) 0x40000000 else 0xB3FFFFFF.toInt())
            if (i == celdaTocada || (i == elegidaCelda && brilloElegida > 0f)) {
                relleno.style = Paint.Style.FILL
                relleno.color = if (tema.claro) 0x24000000 else tema.velo(0x29)
                c.drawRoundRect(x0 + dp(3f), h * 0.12f, x0 + ancho - dp(3f), h * 0.88f, dp(7f), dp(7f), relleno)
            }
            val mostrado = if (esLiteral[i]) "“$s”" else s
            val corto = recortar(mostrado, p, ancho - dp(22f))
            val alfaBase = p.alpha
            p.alpha = (alfaBase * (0.2f + 0.8f * ec)).toInt()
            c.drawText(corto, x0 + ancho / 2, h / 2 + p.textSize * 0.36f, p)
            p.alpha = 255
            if (elegidaAqui) p.clearShadowLayer()
            c.restore()
            val rr = RectF(desplazamiento + x0, 0f, desplazamiento + x0 + ancho, h)
            val lit = esLiteral[i]
            val celda = i
            if (esCorregida) { zonas.add(rr to { corregida = null; oyente?.deshacerCorreccion() }); continue }
            zonas.add(rr to { elegidaCelda = celda; elegidaMs = android.os.SystemClock.uptimeMillis(); oyente?.elegir(s, lit) })
            if (!lit) zonasLargas.add(rr to { porEliminar = s; invalidate() })
        }
        // Separadores: no se dibujan junto a una casilla vacía
        // Tres lugares fijos: las dos líneas siempre en su sitio, aunque falte alguna palabra
        for (i in 1..2) c.drawLine(i * ancho, h * 0.3f, i * ancho, h * 0.7f, linea)
        c.restore()
        if (seguir) postInvalidateOnAnimation()
    }

    private val lineaLuz = Paint(Paint.ANTI_ALIAS_FLAG)
    private val degradadoLinea = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x000A84FF, 0xFF7A8CFF.toInt(), 0xFF5E5CE6.toInt(), 0x005E5CE6), floatArrayOf(0f, 0.3f, 0.7f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    /** En chats (WhatsApp, Telegram…) la línea es más cálida: ámbar → coral → violeta */
    private val degradadoCalido = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x00FFB340, 0xFFFFA24A.toInt(), 0xFFFF6B7A.toInt(), 0x00B06CF0), floatArrayOf(0f, 0.3f, 0.7f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    private val matrizLinea = android.graphics.Matrix()

    /** Candado pequeño con un punto de luz violeta (modo privado) */
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private fun candado(c: Canvas, cx: Float, cy: Float) {
        relleno.color = 0x335E5CE6
        c.drawCircle(cx, cy, dp(14f), relleno)
        trazo.color = 0xFFC9C8FF.toInt(); trazo.strokeWidth = dp(1.6f)
        c.drawArc(cx - dp(4f), cy - dp(8.5f), cx + dp(4f), cy - dp(0.5f), 180f, 180f, false, trazo)
        c.drawLine(cx - dp(4f), cy - dp(4.5f), cx - dp(4f), cy - dp(2f), trazo)
        c.drawLine(cx + dp(4f), cy - dp(4.5f), cx + dp(4f), cy - dp(2f), trazo)
        relleno.color = 0xFFC9C8FF.toInt()
        c.drawRoundRect(cx - dp(6f), cy - dp(2f), cx + dp(6f), cy + dp(7f), dp(2f), dp(2f), relleno)
        relleno.color = 0xFF5E5CE6.toInt()
        c.drawCircle(cx, cy + dp(2.5f), dp(1.4f), relleno)
    }

    /** Cápsula de vidrio: relleno translúcido y filo de luz arriba */
    private val filoCapsula = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private fun capsula(c: Canvas, rect: RectF, color: Int) {
        val rad = rect.height() / 2
        relleno.color = color
        c.drawRoundRect(rect, rad, rad, relleno)
        val a = Color.alpha(color)
        filoCapsula.strokeWidth = dp(0.8f)
        filoCapsula.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(((a * 1.8f).toInt().coerceAtMost(255) shl 24) or 0xFFFFFF, 0x00FFFFFF, ((a / 3) shl 24) or 0xFFFFFF),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(rect.left + dp(0.4f), rect.top + dp(0.4f), rect.right - dp(0.4f), rect.bottom - dp(0.4f), rad, rad, filoCapsula)
        filoCapsula.shader = null
    }

    private fun dibujarHerramientas(c: Canvas, w: Float, h: Float) {
        var izq = dp(8f)
        if (verHerramientas) {
            // Volver a las palabras
            val cx = dp(22f); val cy = h / 2
            val apretado = tocando && toqueX in 0f..dp(42f)
            relleno.color = if (apretado) tema.velo(0x42) else tema.velo(0x17)
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
            val etq = chipEtiqueta
            val anchoEtq = if (etq != null) { pequeno.textSize = dp(11.5f); pequeno.measureText(etq) + dp(8f) } else 0f
            val sangria = if (etq != null) dp(14f) + anchoEtq else dp(28f)
            val corto = TextUtils.ellipsize(chip.replace('\n', ' '), texto, maxChip - sangria - dp(8f), TextUtils.TruncateAt.END).toString()
            val anchoChip = texto.measureText(corto) + sangria + dp(14f)
            r.set(izq, dp(7f), izq + anchoChip, h - dp(7f))
            relleno.color = tema.teclaFuncion
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, relleno)
            if (etq != null) {
                pequeno.color = tema.textoSuave; pequeno.textAlign = Paint.Align.LEFT
                c.drawText(etq, r.left + dp(14f), r.centerY() + pequeno.textSize * 0.36f, pequeno)
                pequeno.textAlign = Paint.Align.CENTER
            } else {
                icono.color = tema.textoSuave
                Iconos.portapapeles(c, r.left + dp(16f), r.centerY(), dp(22f), icono)
            }
            texto.color = tema.texto
            texto.textAlign = Paint.Align.LEFT
            c.drawText(corto, r.left + sangria, r.centerY() + texto.textSize * 0.36f, texto)
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
            relleno.color = if (apretado) tema.velo(0x42) else tema.velo(0x17)
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
                relleno.color = tema.velo(0x33)
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
