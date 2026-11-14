package com.tecladoios

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.util.concurrent.Executors

/**
 * Colores del teclado: negro AMOLED puro (los píxeles negros se apagan y no gastan batería)
 * con teclas como las del iPhone en modo oscuro: letras gris oscuro, teclas de función más
 * oscuras y texto blanco. El color de la app queda solo en el filo y los detalles.
 */
class Tema(val acento: Int = ROJO) {
    val fondo = 0xFF000000.toInt()
    val fondoAbajo = 0xFF000000.toInt()
    // Grafito neutro para las letras; las teclas de función llevan un leve tinte vino
    val tecla = 0xFF38383E.toInt()
    val teclaFuncion = 0xFF2A2124.toInt()
    val teclaPresionada = 0xFF5A4348.toInt()     // al presionar, el gris toma un toque rojo
    val funcionPresionada = 0xFF4A2E33.toInt()
    val brillo = 0x1CFFFFFF
    val texto = 0xFFF5F5F7.toInt()
    val textoSuave = 0x99EBEBF5.toInt()
    val pista = 0x80EBEBF5.toInt()
    val separador = 0x24FFFFFF
    val barra = 0x00000000
    val globo = 0xFF46464D.toInt()
    val tarjeta = 0xFF000000.toInt()
    val bordeTecla = 0x14FFFFFF
    val bordePanel = acento
    val rojo = ROJO
    val rojoOscuro = 0xFFB3121F.toInt()
    val brilloRojo = 0x66FF2D3A                  // resplandor rojo semitransparente
    val estela = ROJO
    val introActivo = ROJO

    companion object {
        const val AZUL = 0xFF0A84FF.toInt()
        /** Rojo vivo del teclado (combina con el negro AMOLED) */
        const val ROJO = 0xFFFF2D3A.toInt()
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
