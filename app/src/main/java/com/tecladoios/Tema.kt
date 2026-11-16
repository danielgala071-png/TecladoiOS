package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.util.concurrent.Executors

/**
 * Colores del teclado como el de iOS: teclas grises planas con una sombrita abajo.
 * [claro] sigue el modo del celular (claro de día, oscuro si el sistema está en oscuro).
 */
class Tema(val acento: Int = AZUL, val claro: Boolean = false) {
    /** Fondo del teclado */
    val fondo = if (claro) 0xFFD1D4DA.toInt() else 0xFF000000.toInt()   // AMOLED: negro puro (teclas grises como el iPhone)
    /** Fondo con degradado muy suave: negro arriba → gris casi negro abajo (profundidad) */
    val fondoAbajo = if (claro) fondo else 0xFF000000.toInt()
    /** Teclas de letras */
    val tecla = if (claro) 0xFFFFFFFF.toInt() else 0xFF4A4A4E.toInt()
    /** Mayúscula, borrar, 123, emoji, intro */
    /** Las principales (mayúscula, borrar, 123, emoji, espacio, intro): grafito más oscuro */
    // AMOLED + azul profundo: las teclas principales (mayúscula, 123, emoji, espacio, intro,
    // borrar) en azul noche, las letras con contorno sobre negro puro
    val teclaFuncion = if (claro) 0xFFABB0BA.toInt() else 0xFF2A2A2D.toInt()
    /** Al tocar, las de función toman el color de las letras (como en iOS) */
    // Efecto Gboard: al tocar, la tecla se aclara
    val teclaPresionada = if (claro) 0xFFABB0BA.toInt() else 0xFF6E6E73.toInt()
    val funcionPresionada = if (claro) 0xFFFFFFFF.toInt() else 0xFF4A4A4E.toInt()
    /**
     * Diseño C «Contorno nocturno»: las letras son solo un contorno fino sobre negro puro
     * (en AMOLED casi no gasta) y las teclas principales van rellenas.
     */
    // Negro AMOLED (diseño C): letras con contorno fino sobre negro puro y las principales
    // (mayúscula, borrar, 123, emoji, intro) rellenas en grafito
    val contorno = false   // como el iPhone: teclas rellenas grises, sin contorno
    // Contorno más suave (menos contraste con el negro)
    val lineaTecla = 0xFF283040.toInt()
    val filoArriba = 0xFF3E4860.toInt()
    val filoAbajo = 0xFF161B24.toInt()
    /** Gris claro de detalle (antes rojo) */
    val rojoIntenso = 0xFF8E8E93.toInt()
    /** Línea de sombra bajo cada tecla */
    /** Borde de abajo de cada tecla (da la profundidad; en AMOLED es un gris muy oscuro) */
    val sombra = if (claro) 0xFF898A8D.toInt() else 0xFF0B0B0C.toInt()
    val brillo = 0x00FFFFFF
    /** Letras en blanco cálido (apenas crema): más descansado de noche */
    val texto = if (claro) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    /** Fondo de la barra de sugerencias: un tono sobre el negro, para separarla sin línea */
    val fondoBarra = if (claro) 0x00000000 else 0xFF000000.toInt()   // misma pieza que el teclado
    val textoSuave = if (claro) 0x993C3C43.toInt() else 0xB3EBEBF5.toInt()
    val pista = if (claro) 0x803C3C43.toInt() else 0x80EBEBF5.toInt()
    val separador = if (claro) 0x383C3C43 else 0x29FFFFFF
    val barra = 0x00000000
    val globo = tecla
    val tarjeta = fondo
    val tarjetaVidrio = fondo
    val bordeTecla = 0x00000000
    val bordePanel = fondo
    val rojo = AZUL
    val rojoOscuro = AZUL
    val brilloRojo = 0x00000000
    val estela = AZUL
    val introActivo = AZUL
    /** Velo para resaltar: blanco en oscuro, negro en claro (a = opacidad 0..255) */
    fun velo(a: Int): Int = (a shl 24) or (if (claro) 0x000000 else 0xFFFFFF)

    /** Mayúscula activa: tecla blanca con flecha negra */
    val shiftActivo = 0xFFFFFFFF.toInt()

    companion object {
        const val AZUL = 0xFF0A84FF.toInt()
        /**
         * Acento del teclado: violeta azulado (combina con el negro AMOLED y el vidrio). El
         * nombre quedó de antes; ya no es rojo.
         */
        const val ROJO = 0xFFC4122E.toInt()
        /** Acento del tema Obsidiana carmesí */
        const val CARMESI = 0xFFC4122E.toInt()
        /** Segundo tono del acento (degradado azul → violeta) */
        const val AZUL_ACENTO = 0xFF0A84FF.toInt()
        /** Detalles en plata (sin el color de cada app) */
        const val PLATA = 0xFFC7C7CF.toInt()

        fun mezclar(a: Int, b: Int, cuantoA: Float): Int {
            val t = cuantoA.coerceIn(0f, 1f)
            fun c(x: Int, y: Int) = (x * t + y * (1 - t)).toInt().coerceIn(0, 255)
            return Color.argb(
                c(Color.alpha(a), Color.alpha(b)), c(Color.red(a), Color.red(b)),
                c(Color.green(a), Color.green(b)), c(Color.blue(a), Color.blue(b))
            )
        }
    }
}

/**
 * Color de cada app sacado de su ícono (el tono más vivo y más presente). Se calcula una sola
 * vez por app, aparte del teclado, y queda guardado.
 */
object ColorApp {
    private val cache = HashMap<String, Int>()
    private val hilo = Executors.newSingleThreadExecutor()
    private const val PREFS = "colores_app"

    fun conocido(c: Context, paquete: String?): Int? {
        if (paquete.isNullOrEmpty()) return null
        synchronized(cache) { cache[paquete]?.let { return it } }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(paquete)) {
            val v = p.getInt(paquete, Tema.AZUL)
            synchronized(cache) { cache[paquete] = v }
            return v
        }
        return null
    }

    /** Calcula el color en segundo plano y avisa en el hilo principal */
    fun calcular(c: Context, paquete: String, listo: (Int) -> Unit) {
        val app = c.applicationContext
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        hilo.execute {
            val color = try { deIcono(app, paquete) } catch (e: Throwable) { Registro.anotar("color de app", e); Tema.AZUL }
            synchronized(cache) { cache[paquete] = color }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(paquete, color).apply()
            principal.post { listo(color) }
        }
    }

    private fun deIcono(c: Context, paquete: String): Int {
        val icono = c.packageManager.getApplicationIcon(paquete)
        val lado = 48
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        icono.setBounds(0, 0, lado, lado)
        icono.draw(cv)
        // Histograma de tonos (36 grupos), pesado por saturación y brillo
        val peso = FloatArray(36)
        val sumaR = FloatArray(36); val sumaG = FloatArray(36); val sumaB = FloatArray(36)
        val hsv = FloatArray(3)
        val px = IntArray(lado * lado)
        bmp.getPixels(px, 0, lado, 0, 0, lado, lado)
        bmp.recycle()
        for (p in px) {
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
            val g = ((hsv[0] / 10f).toInt()).coerceIn(0, 35)
            val w = hsv[1] * hsv[1] * hsv[2]
            peso[g] += w
            sumaR[g] += Color.red(p) * w; sumaG[g] += Color.green(p) * w; sumaB[g] += Color.blue(p) * w
        }
        var mejor = -1
        var mejorPeso = 0f
        for (g in 0 until 36) {
            val w = peso[g] + 0.5f * (peso[(g + 35) % 36] + peso[(g + 1) % 36])
            if (w > mejorPeso) { mejorPeso = w; mejor = g }
        }
        if (mejor < 0 || peso[mejor] < 4f) return Tema.AZUL
        val w = peso[mejor]
        val base = Color.rgb((sumaR[mejor] / w).toInt(), (sumaG[mejor] / w).toInt(), (sumaB[mejor] / w).toInt())
        // Bien intenso y visible sobre negro
        Color.colorToHSV(base, hsv)
        hsv[1] = maxOf(hsv[1], 0.75f)
        hsv[2] = maxOf(hsv[2], 0.88f)
        return Color.HSVToColor(hsv)
    }
}
