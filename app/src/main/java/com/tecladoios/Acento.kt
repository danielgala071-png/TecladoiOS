package com.tecladoios

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color

/**
 * El color que combina con tu fondo de pantalla. Se calcula al abrir el teclado o al cambiar el
 * fondo (no se revisa a cada rato) y lo usan el teclado, los emojis, el portapapeles y los Ajustes.
 */
object Acento {
    const val DEFECTO = 0xFFE53935.toInt()   // carmesí del eclipse

    @Volatile var color: Int = DEFECTO
        private set

    /** Negro AMOLED total: sin leer el fondo; todo en gris neutro */
    const val NEUTRO = 0xFF8E8E93.toInt()
    private const val SIN_COLOR = true

    fun leer(c: Context): Int {
        if (SIN_COLOR) { color = NEUTRO; return color }
        val wc = try {
            WallpaperManager.getInstance(c).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) { null }
        color = elegir(wc)
        return color
    }

    /** El color más vivo del fondo (si el fondo es casi todo negro, el carmesí) */
    fun elegir(c: WallpaperColors?): Int {
        if (c == null) return DEFECTO
        val hsv = FloatArray(3)
        var mejor = DEFECTO; var puntaje = 0f
        for (col in listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor)) {
            Color.colorToHSV(col.toArgb(), hsv)
            val p = hsv[1] * hsv[2]
            if (hsv[1] >= 0.3f && hsv[2] >= 0.3f && p > puntaje) { puntaje = p; mejor = col.toArgb() }
        }
        if (puntaje == 0f) return DEFECTO
        // Que se lea bien sobre negro: ni muy oscuro ni pastel
        Color.colorToHSV(mejor, hsv)
        hsv[1] = hsv[1].coerceIn(0.55f, 0.85f); hsv[2] = hsv[2].coerceIn(0.75f, 0.92f)
        return Color.HSVToColor(hsv)
    }

    /** Mezcla [a] con un poco de [b] (f = cuánto de b): para teñir el grafito con tu color */
    fun mezclar(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(),
        (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(),
        (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt())

    /** El color con transparencia (0–255) */
    fun conAlfa(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    /** El color oscurecido hacia el negro (0 = negro, 1 = igual) */
    fun oscuro(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
}
