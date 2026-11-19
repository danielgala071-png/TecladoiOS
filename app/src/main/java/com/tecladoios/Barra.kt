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
        /** Mantener presionada la captura: se quita de las sugerencias (no se borra la foto) */
        fun descartarCaptura() {}
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
        val antes = destacada
        destacada = if (autocorrige && lista.isNotEmpty()) 0 else -1
        // Aparece una corrección: la línea entre la barra y las teclas se ilumina un instante
        if (antes < 0 && destacada >= 0 && !Ahorro.activo) lineaMs = android.os.SystemClock.uptimeMillis()
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

    /** Al abrir el teclado la barra aparece con un fundido corto (y sube apenas) */
    fun aparecer() {
        animInicio = android.os.SystemClock.uptimeMillis(); animDura = 220f; animSube = true
        invalidate()
    }

    private fun animar(duracion: Float, subir: Boolean) {
        // Fundido cortito sin movimiento (en la GPU, casi no gasta)
        animInicio = android.os.SystemClock.uptimeMillis(); animDura = if (Ahorro.activo) 0f else minOf(duracion, 140f); animSube = false
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

    private val HERRAMIENTAS = intArrayOf(H_EMOJI, H_EDITAR, H_TAMANO, H_AJUSTES)

    // Un error al dibujar se anota y no cierra el teclado
    override fun onDraw(c: Canvas) {
        val n = c.save()
        try { dibujarTodo(c) } catch (e: Throwable) { Registro.anotar("dibujo barra", e) } finally { c.restoreToCount(n) }
    }

    // ---------- Palabra elegida que salta al texto ----------
    private var saltoTexto: String? = null
    private var saltoX = 0f
    private var saltoMs = 0L
    private val SALTO = 190f
    private val pinturaSalto = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER; Fuentes.aplicar(this, 600)
    }

    /** Se dibuja encima de todo: la palabra se encoge, sube un poquito y se apaga (confirmación) */
    private fun dibujarSalto(c: Canvas, h: Float) {
        val txt = saltoTexto ?: return
        val t = ((android.os.SystemClock.uptimeMillis() - saltoMs) / SALTO).coerceIn(0f, 1f)
        if (t >= 1f) { saltoTexto = null; return }
        val e = 1f - (1f - t) * (1f - t)
        val escala = 1f - 0.22f * e
        pinturaSalto.textSize = texto.textSize
        pinturaSalto.color = tema.texto
        pinturaSalto.alpha = (255 * (1f - e)).toInt().coerceIn(0, 255)
        val y = h / 2 - h * 0.32f * e
        c.save()
        c.scale(escala, escala, saltoX, y)
        c.drawText(txt, saltoX, y + pinturaSalto.textSize * 0.36f, pinturaSalto)
        c.restore()
        postInvalidateOnAnimation()
    }

    /** Matiz muy sutil según la app (verde en WhatsApp, azul en Messenger…); 0 = ninguno */
    var tinte = 0; set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * Palabra mal escrita que se está revisando: va a la izquierda en rojo suave y en las
     * correcciones se resalta solo la parte que cambia (así ves si era una sola letra).
     */
    var revision: String? = null; set(v) { if (field != v) { field = v; invalidate() } }

    /** Cuántas letras iguales hay al inicio y al final entre [a] y [b] (sin mayúsculas) */
    private fun iguales(a: String, b: String): Pair<Int, Int> {
        val x = a.lowercase(); val y = b.lowercase()
        var ini = 0
        while (ini < x.length && ini < y.length && x[ini] == y[ini]) ini++
        var fin = 0
        while (fin < x.length - ini && fin < y.length - ini && x[x.length - 1 - fin] == y[y.length - 1 - fin]) fin++
        return ini to fin
    }

    /** Dibuja [s] centrada con la parte distinta a [original] en blanco y subrayada */
    private fun textoConCambio(c: Canvas, s: String, original: String, cx: Float, y: Float, p: Paint) {
        val (ini, fin) = iguales(original, s)
        val corte = s.length - fin
        if (ini >= corte) {
            // Solo había una letra de más (la corrección la quita): se ve tal cual
            c.drawText(s, cx, y, p)
            return
        }
        val alin = p.textAlign; val color = p.color; val alfa = p.alpha
        p.textAlign = Paint.Align.LEFT
        val a = s.substring(0, ini); val m = s.substring(ini, corte); val z = s.substring(corte)
        val wa = p.measureText(a); val wm = p.measureText(m)
        var x = cx - p.measureText(s) / 2
        // Lo que no cambia va en gris; lo que cambia, en blanco y subrayado
        val gris = 0xFF9A9A9E.toInt()
        p.color = gris; p.alpha = alfa
        c.drawText(a, x, y, p); x += wa
        p.color = 0xFFFFFFFF.toInt(); p.alpha = alfa
        c.drawText(m, x, y, p)
        relleno.style = Paint.Style.FILL
        relleno.color = 0xFFFFFFFF.toInt(); relleno.alpha = alfa
        c.drawRoundRect(x, y + dp(4f), x + wm, y + dp(5.6f), dp(0.8f), dp(0.8f), relleno)
        relleno.alpha = 255
        x += wm
        p.color = gris; p.alpha = alfa
        c.drawText(z, x, y, p)
        p.color = color; p.alpha = alfa
        p.textAlign = alin
    }
    private val pinturaFondo = Paint()
    private val pinturaCapsula = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dibujarTodo(c: Canvas) {
        zonas.clear(); zonasLargas.clear()
        val w = width.toFloat(); val h = height.toFloat()
        // Fondo de la barra: un tono sobre el negro del teclado, con el matiz de la app
        if (!tema.claro) {
            // Diseño C: el mismo negro del teclado, sin matiz de la app (una sola pieza)
            pinturaFondo.color = tema.fondoBarra
            c.drawRect(0f, 0f, w, h, pinturaFondo)
            // Diseño 4 «Tecla elegida»: barra en negro puro, sin cápsula ni líneas;
            // solo la palabra que se pondrá va sobre una mini tecla carmesí (ver dibujarTeclaElegida)
        }
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
        dibujarSalto(c, h)
        dibujarLineaCorreccion(c, w, h)
    }

    // ---------- Línea de luz al aparecer una corrección ----------
    private var lineaMs = 0L
    private val LINEA_DURA = 420f
    private val pinturaLineaC = Paint(Paint.ANTI_ALIAS_FLAG)
    private var degradadoLineaC: android.graphics.Shader? = null
    private var degradadoAncho = -1f

    private fun dibujarLineaCorreccion(c: Canvas, w: Float, h: Float) {
        if (lineaMs == 0L) return
        val t = (android.os.SystemClock.uptimeMillis() - lineaMs) / LINEA_DURA
        if (t >= 1f) { lineaMs = 0L; return }
        if (degradadoAncho != w) {
            // Más brillante al centro (debajo de la palabra que se pondrá) y se pierde a los lados
            degradadoLineaC = android.graphics.LinearGradient(0f, 0f, w, 0f,
                intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0.15f, 0.5f, 0.85f),
                android.graphics.Shader.TileMode.CLAMP)
            degradadoAncho = w
        }
        val f = kotlin.math.sin(Math.PI.toFloat() * t)
        pinturaLineaC.shader = degradadoLineaC
        pinturaLineaC.alpha = (255 * f * (if (tema.claro) 0.5f else 0.75f)).toInt().coerceIn(0, 255)
        // Dentro de la cápsula, en su borde de abajo
        c.drawRect(dp(5f) + h / 2f, h - dp(5.6f), w - dp(5f) - h / 2f, h - dp(4.8f), pinturaLineaC)
        postInvalidateOnAnimation()
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
    // Rodillo: mientras escribes, la palabra vieja sube y se apaga y la nueva sube desde abajo
    private val celdaSaliente = arrayOfNulls<String>(3)
    private val celdaSubeY = FloatArray(3)
    private val RETRASO_CELDA = longArrayOf(20L, 0L, 40L)

    // Destello de la palabra que elegiste
    private var elegidaMs = 0L
    private var elegidaCelda = -1
    private var chipDesliza = -1
    private var chipMs = 0L
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
                // Mientras escribes: efecto rodillo (la vieja sale hacia arriba, la nueva entra
                // desde abajo con un resorte cortito). Desde vacío: entra deslizándose.
                celdaDura[i] = if (Ahorro.activo) 0f else if (desdeVacio) 240f else 170f
                celdaSube[i] = if (desdeVacio) dp(8f) else 0f
                celdaSubeY[i] = if (desdeVacio) 0f else dp(6f)
                celdaSaliente[i] = if (desdeVacio || Ahorro.activo) null else celdaPrevia[i]
                celdaPrevia[i] = celdas[i]
            }
        }
        // Mini tecla que viaja del centro a la palabra tocada a un costado (180 ms)
        if (chipDesliza >= 0 && !tema.claro) {
            val tch = ((ahora - chipMs) / 180f).coerceIn(0f, 1f)
            if (tch < 1f) {
                val e = 1f - (1f - tch) * (1f - tch) * (1f - tch)
                val cx = (1f + (chipDesliza - 1) * e) * ancho + ancho / 2
                dibujarTeclaElegida(c, cx, h, ancho * 0.45f, ancho, 1f - tch * tch)
                seguir = true
            } else chipDesliza = -1
        }
        // Como el iPhone: líneas finas grises entre las sugerencias
        if (!tema.claro) {
            relleno.style = Paint.Style.FILL; relleno.color = 0xFF2A2A2C.toInt()
            for (j in 1..2) if (celdas[j - 1] != null && celdas[j] != null) c.drawRect(j * ancho - dp(0.5f), h * 0.28f, j * ancho + dp(0.5f), h * 0.72f, relleno)
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
            // Entrada con resorte: llega, se pasa un pelo y se asienta (suave a 90/120 Hz)
            val res = 1f - kotlin.math.exp(-5.5f * tc) * kotlin.math.cos(8f * tc) * uc
            // La palabra que se va: sube y se apaga
            val sal = celdaSaliente[i]
            if (sal != null && tc < 1f) {
                val col = texto.color; val al = texto.alpha
                texto.color = if (tema.claro) tema.velo(0x99) else 0xFF9A9A9E.toInt()
                texto.alpha = (200 * (1f - ec)).toInt().coerceIn(0, 255)
                c.drawText(recortar(sal, texto, ancho - dp(22f)), x0 + ancho / 2, h / 2 + texto.textSize * 0.36f - celdaSubeY[i] * ec, texto)
                texto.color = col; texto.alpha = al
            }
            c.save()
            c.translate(celdaSube[i] * (1f - res), celdaSubeY[i] * (1f - res))
            // La que se pondrá sola brilla fuerte; las demás quedan tenues y lo que escribiste
            // (entre comillas) va aún más apagado, para que se distinga de las sugerencias
            val esCorregida = false
            val elegidaAqui = i == 1 && destacada >= 0
            // Tres tonos: lo que escribes en gris azulado, las sugerencias en gris claro y la
            // que se pondrá sola en blanco con un halo azul hielo
            p.color = when {
                elegidaAqui -> tema.texto
                esLiteral[i] && revision != null -> 0xFFFF7A70.toInt()   // la mal escrita, en rojo suave
                esLiteral[i] -> if (tema.claro) 0xFF5B6B85.toInt() else 0xFF8C9BB5.toInt()
                tema.claro -> tema.velo(0x99)
                else -> 0xFF9A9A9E.toInt()
            }
            if (i == celdaTocada || (i == elegidaCelda && brilloElegida > 0f)) {
                relleno.style = Paint.Style.FILL
                relleno.color = if (tema.claro) 0x24000000 else tema.velo(0x29)
                c.drawRoundRect(x0 + dp(3f), h * 0.12f, x0 + ancho - dp(3f), h * 0.88f, dp(7f), dp(7f), relleno)
            }
            val mostrado = if (esLiteral[i]) "“$s”" else s
            val corto = recortar(mostrado, p, ancho - dp(22f))
            // Como el iPhone: la palabra elegida solo va en blanco (sin mini tecla)
            val alfaBase = p.alpha
            p.alpha = (alfaBase * (0.2f + 0.8f * ec)).toInt()
            val rev = revision
            if (rev != null && !esLiteral[i] && corto == mostrado) textoConCambio(c, corto, rev, x0 + ancho / 2, h / 2 + p.textSize * 0.36f, p)
            else c.drawText(corto, x0 + ancho / 2, h / 2 + p.textSize * 0.36f, p)
            if (elegidaAqui && rev == null && tema.claro) {
                // Tema claro: la palabra que se pondrá sola lleva una rayita debajo
                val mitad = p.measureText(corto) / 2
                val yL = h / 2 + p.textSize * 0.36f + dp(6f)
                relleno.style = Paint.Style.FILL
                relleno.color = 0xFF8A8A90.toInt(); relleno.alpha = p.alpha
                c.drawRoundRect(x0 + ancho / 2 - mitad, yL, x0 + ancho / 2 + mitad, yL + dp(2f), dp(1f), dp(1f), relleno)
                relleno.alpha = 255
            }
            p.alpha = 255
            c.restore()
            val rr = RectF(desplazamiento + x0, 0f, desplazamiento + x0 + ancho, h)
            val lit = esLiteral[i]
            val celda = i
            if (esCorregida) { zonas.add(rr to { corregida = null; oyente?.deshacerCorreccion() }); continue }
            val xCentro = desplazamiento + x0 + ancho / 2
            zonas.add(rr to {
                // Ahorro: sin destello, sin mini tecla que se desliza y sin salto de la palabra
                if (!Ahorro.activo) {
                    elegidaCelda = celda; elegidaMs = android.os.SystemClock.uptimeMillis()
                    // La mini tecla carmesí se desliza hasta la palabra tocada (no retrasa nada)
                    if (celda != 1 && destacada >= 0) { chipDesliza = celda; chipMs = elegidaMs }
                    // La palabra elegida "salta" al texto: se encoge y sube mientras se desvanece
                    saltoTexto = corto; saltoX = xCentro; saltoMs = elegidaMs
                }
                oyente?.elegir(s, lit)
            })
            if (!lit) zonasLargas.add(rr to { porEliminar = s; invalidate() })
        }
        // Separadores: no se dibujan junto a una casilla vacía
        // Tres lugares fijos: las dos líneas siempre en su sitio, aunque falte alguna palabra
        // Diseño C: sin líneas separadoras (más limpio)
        c.restore()
        if (seguir) postInvalidateOnAnimation()
    }

    private val lineaLuz = Paint(Paint.ANTI_ALIAS_FLAG)
    private val degradadoLinea = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0xFFB0B0B5.toInt(), 0x00B0B0B5), floatArrayOf(0f, 0.3f, 0.7f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    /** En chats (WhatsApp, Telegram…) la línea es más cálida: ámbar → coral → violeta */
    private val degradadoCalido = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x00FFB340, 0xFFFFA24A.toInt(), 0xFFFF6B7A.toInt(), 0x00B06CF0), floatArrayOf(0f, 0.3f, 0.7f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    private val matrizLinea = android.graphics.Matrix()

    /** Candado pequeño con un punto de luz violeta (modo privado) */
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private fun candado(c: Canvas, cx: Float, cy: Float) {
        relleno.color = 0x33C4122E
        c.drawCircle(cx, cy, dp(14f), relleno)
        trazo.color = 0xFFFFC2C8.toInt(); trazo.strokeWidth = dp(1.6f)
        c.drawArc(cx - dp(4f), cy - dp(8.5f), cx + dp(4f), cy - dp(0.5f), 180f, 180f, false, trazo)
        c.drawLine(cx - dp(4f), cy - dp(4.5f), cx - dp(4f), cy - dp(2f), trazo)
        c.drawLine(cx + dp(4f), cy - dp(4.5f), cx + dp(4f), cy - dp(2f), trazo)
        relleno.color = 0xFFFFC2C8.toInt()
        c.drawRoundRect(cx - dp(6f), cy - dp(2f), cx + dp(6f), cy + dp(7f), dp(2f), dp(2f), relleno)
        relleno.color = 0xFF8E8E93.toInt()
        c.drawCircle(cx, cy + dp(2.5f), dp(1.4f), relleno)
    }

    /** Cápsula de vidrio: relleno translúcido y filo de luz arriba */
    /**
     * Mini tecla de obsidiana carmesí detrás de la palabra que se pondrá sola: base roja
     * asomando abajo, cara en rojo metálico y un reflejo finito arriba (como las teclas).
     */
    private val pinturaTecla = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflejoTecla = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FF8A96; strokeCap = Paint.Cap.ROUND }
    private var caraTecla: android.graphics.LinearGradient? = null
    private var caraAlto = -1f
    private val rectTecla = RectF()
    private fun dibujarTeclaElegida(c: Canvas, cx: Float, h: Float, anchoTexto: Float, ancho: Float, entrada: Float) {
        val alto = h * 0.70f
        val anchoT = (anchoTexto + dp(28f)).coerceIn(ancho * 0.55f, ancho - dp(6f))
        val arriba = (h - alto) / 2f - dp(1f)
        val radio = dp(8f)
        val alfa = (255 * (0.2f + 0.8f * entrada)).toInt().coerceIn(0, 255)
        rectTecla.set(cx - anchoT / 2, arriba, cx + anchoT / 2, arriba + alto)
        // Base carmesí 2 dp más abajo
        pinturaTecla.shader = null; pinturaTecla.color = 0xFF0E0E10.toInt(); pinturaTecla.alpha = alfa
        c.drawRoundRect(rectTecla.left, rectTecla.top + dp(2f), rectTecla.right, rectTecla.bottom + dp(2f), radio, radio, pinturaTecla)
        // Cara en rojo metálico
        if (caraTecla == null || caraAlto != alto) {
            caraTecla = android.graphics.LinearGradient(0f, 0f, 0f, alto,
                intArrayOf(0xFF2E4372.toInt(), 0xFF223258.toInt(), 0xFF1A2440.toInt()), floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            caraAlto = alto
        }
        c.save(); c.translate(0f, arriba)
        pinturaTecla.color = 0xFFFFFFFF.toInt(); pinturaTecla.alpha = alfa; pinturaTecla.shader = caraTecla
        c.drawRoundRect(rectTecla.left, 0f, rectTecla.right, alto, radio, radio, pinturaTecla)
        pinturaTecla.shader = null
        reflejoTecla.strokeWidth = dp(0.8f); reflejoTecla.alpha = (0x40 * alfa) / 255
        c.drawLine(rectTecla.left + radio, dp(0.7f), rectTecla.right - radio, dp(0.7f), reflejoTecla)
        c.restore()
    }

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
            if (apretado) { relleno.color = tema.velo(0x30); c.drawCircle(cx, cy, dp(15f), relleno) }
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
            zonasLargas.add(RectF(r) to { oyente?.descartarCaptura() })
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
            relleno.color = 0xFF2C2C2E.toInt()
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
        val s = if (tema.claro) dp(30f) else dp(24f)
        for (hId in herrs) {
            val cx = x + anchoHerr / 2; val cy = h / 2
            // Cada herramienta en un botón redondo oscuro
            // Solo el ícono dentro de la cápsula (más limpio); el círculo aparece al tocarlo
            val apretado = toqueX in x..(x + anchoHerr) && tocando
            if (tema.claro) {
                if (apretado) { relleno.color = tema.velo(0x30); c.drawCircle(cx, cy, dp(16f), relleno) }
            } else {
                // Como el iPhone: solo el ícono gris; al tocarlo, un círculo tenue
                relleno.style = Paint.Style.FILL
                if (apretado) { relleno.color = 0x33FFFFFF; c.drawCircle(cx, cy, dp(16f), relleno) }
                icono.color = 0xFFD1D1D6.toInt()
            }
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
                    // Al tocar una palabra: su propia vibración (distinta a la de las teclas)
                    if (z != null) { oyente?.respuesta(Respuesta.Tipo.PALABRA); z.second() }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(larga); tocando = false; invalidate() }
        }
        return true
    }
}
