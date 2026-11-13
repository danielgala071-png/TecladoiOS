package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.util.concurrent.Executors

/**
 * Colores del teclado, estilo vidrio oscuro: fondo pizarra azulado con un leve degradado y
 * teclas grises claras y redondeadas, todas del mismo tono. El color de la app queda solo en
 * el filo del teclado y en los detalles.
 */
class Tema(val acento: Int) {
    val fondo = 0xFF252932.toInt()          // arriba del degradado
    val fondoAbajo = 0xFF181B22.toInt()     // abajo del degradado
    val tecla = 0xFF4B4F58.toInt()          // todas las teclas
    val teclaFuncion = 0xFF40444D.toInt()   // shift, borrar, 123 (apenas más oscuras)
    val teclaPresionada = 0xFF6C717B.toInt()
    val funcionPresionada = 0xFF62666F.toInt()
    val brillo = 0x2EFFFFFF                 // reflejo fino arriba de cada tecla
    val texto = 0xFFFFFFFF.toInt()
    val textoSuave = 0x99FFFFFF.toInt()
    val pista = 0x8CFFFFFF.toInt()
    val separador = 0x26FFFFFF
    val barra = 0x00000000
    val globo = 0xFF5E636D.toInt()
    val bordeTecla = 0x1FFFFFFF
    val bordePanel = acento
    val estela = acento
    val introActivo = acento

    companion object {
        const val AZUL = 0xFF0A84FF.toInt()

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
