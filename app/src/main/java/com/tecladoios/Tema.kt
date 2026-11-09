package com.tecladoios

import android.content.Context
import android.graphics.Color

/**
 * Diseño negro AMOLED: fondo negro puro (ahorra batería en pantallas OLED) y teclas de
 * vidrio translúcido. El filo de cada tecla toma el color de la app que estás usando:
 * verde en WhatsApp, rosado en Instagram, rojo en YouTube…
 *
 * Siempre es oscuro, aunque el celular esté en modo claro.
 */
data class Tema(
    val oscuro: Boolean,
    /** Fondo del teclado: negro puro */
    val fondo: Int,
    val fondoArriba: Int,
    /** Teclas de letras: vidrio translúcido apenas teñido con el color de la app */
    val tecla: Int,
    val teclaFuncion: Int,
    /** Tecla presionada: vidrio del color de la app */
    val teclaPresionada: Int,
    /** Mayúsculas activadas: tecla clara con la flecha oscura, como en el iPhone */
    val teclaActiva: Int,
    val textoActivo: Int,
    val sombra: Int,
    val texto: Int,
    val textoSecundario: Int,
    /** Color de la app */
    val azul: Int,
    /** Filo de cada tecla con el color de la app */
    val borde: Int,
    /** Borde de todo el teclado con el color de la app */
    val bordePanel: Int,
    /** Fondo de la barra de sugerencias (flotante) */
    val barra: Int,
    /** Enter de acción (enviar, buscar…) con el color de la app */
    val enterAccion: Int,
    val textoAcento: Int,
    /** Vista previa de las teclas */
    val burbuja: Int,
    val bordeBurbuja: Int,
    val desenfoque: Int,
    /** Reflejo de vidrio en la parte de arriba de cada tecla (efecto translúcido) */
    val brillo: Int,
    /** Sombra interior en la parte de abajo de cada tecla: le da volumen al vidrio */
    val profundidad: Int,
    /** Línea de luz finita en el filo de arriba de cada tecla (como el vidrio de iOS 26) */
    val reflejo: Int,
    /** Halo suave del color de la app alrededor del filo del teclado y de la barra */
    val resplandor: Int,
) {
    companion object {
        private val BLANCO = 0xFFFFFFFF.toInt()
        private val NEGRO = 0xFF000000.toInt()

        private fun a(alfa: Int, rgb: Int) = (alfa shl 24) or (rgb and 0xFFFFFF)

        fun mezclar(x: Int, y: Int, f: Float): Int {
            fun c(p: Int, q: Int) = (p + (q - p) * f).toInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(x), c(Color.red(x), Color.red(y)),
                c(Color.green(x), Color.green(y)), c(Color.blue(x), Color.blue(y)))
        }

        /** El color tal como se ve encima del negro (sirve para dibujar los huecos de los íconos) */
        fun sobreNegro(color: Int): Int {
            val f = Color.alpha(color) / 255f
            return Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(),
                (Color.blue(color) * f).toInt())
        }

        private fun textoSobre(acento: Int): Int {
            val lum = (0.299 * Color.red(acento) + 0.587 * Color.green(acento) + 0.114 * Color.blue(acento)) / 255.0
            return if (lum > 0.68) NEGRO else BLANCO
        }

        /** Color de acento del sistema (Material You), para cuando la app no tiene color */
        private fun acentoSistema(context: Context): Int = try {
            context.getColor(android.R.color.system_accent1_300)
        } catch (_: Exception) {
            0xFF0A84FF.toInt()
        }

        /** [acento]: color de la app. [conDesenfoque] ya no se usa (el fondo es negro sólido). */
        @Suppress("UNUSED_PARAMETER")
        fun desde(context: Context, conDesenfoque: Boolean, acento: Int?): Tema {
            val color = (acento ?: acentoSistema(context)) or (0xFF shl 24)
            // Filo de las teclas elegido en Ajustes (apagado, sutil, normal o vivo)
            val alfaBorde = when (Ajustes.bordeTeclas(context)) {
                "apagado" -> 0x00
                "sutil" -> 0x55
                "vivo" -> 0xD0
                else -> 0x74
            }
            return Tema(
                oscuro = true,
                fondo = NEGRO,
                fondoArriba = NEGRO,
                // Vidrio translúcido: blanco muy tenue con un toque del color de la app
                // Grafito neutro, apenas teñido: se lee mejor y se ve más fino
                // Letras un poco más claras que las teclas de función (se distinguen mejor, como en iOS)
                tecla = a(0x2E, mezclar(BLANCO, color, 0.10f)),
                teclaFuncion = a(0x19, mezclar(BLANCO, color, 0.16f)),
                // Tecla presionada: el vidrio se ilumina (más claro, apenas teñido de la app),
                // como en iPhone. Antes se volvía un bloque verde oscuro y pesado.
                // Más suave: se ilumina sin encandilar
                teclaPresionada = a(0x5E, mezclar(BLANCO, color, 0.34f)),
                teclaActiva = 0xFFEBEBF0.toInt(),
                textoActivo = NEGRO,
                sombra = 0,
                texto = BLANCO,
                textoSecundario = 0xFF8E8E93.toInt(),
                azul = color,
                // Filo sutil: se nota el color de la app sin que "grite"
                // Filo más fino y sedoso: el color de la app mezclado con un poco de luz
                borde = a(alfaBorde, mezclar(color, BLANCO, 0.30f)),
                bordePanel = a(0x80, color),
                barra = a(0xF0, 0x0B0B0D),
                enterAccion = a(0xD9, color),
                textoAcento = textoSobre(color),
                burbuja = a(0xF7, mezclar(0xFF1C1C1E.toInt(), color, 0.22f)),
                bordeBurbuja = a(0xE6, color),
                desenfoque = 0,
                // Se dibujan con opacidad propia (no dependen de la transparencia de la tecla)
                brillo = a(0x17, BLANCO),
                profundidad = a(0x38, NEGRO),
                reflejo = a(0x2E, mezclar(BLANCO, color, 0.15f)),
                resplandor = a(0x1C, color),
            )
        }
    }
}
