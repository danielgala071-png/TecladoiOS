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
                "sutil" -> 0x40
                "vivo" -> 0xC0
                else -> 0x62
            }
            return Tema(
                oscuro = true,
                fondo = NEGRO,
                fondoArriba = NEGRO,
                // Grafito neutro, apenas teñido: las letras se distinguen claramente de las
                // teclas de función (más oscuras), como en el teclado oscuro del iPhone
                tecla = a(0x3A, mezclar(BLANCO, color, 0.05f)),
                teclaFuncion = a(0x1C, mezclar(BLANCO, color, 0.10f)),
                // Tecla presionada: el vidrio se ilumina en gris claro con un toque de la app
                // (antes era un bloque verde oscuro y pesado)
                teclaPresionada = a(0x60, mezclar(BLANCO, color, 0.2f)),
                // Mayúsculas activadas: blanco suave, no encandila sobre el negro
                teclaActiva = 0xFFE3E3E8.toInt(),
                textoActivo = 0xFF0A0A0B.toInt(),
                sombra = 0,
                texto = 0xFFF5F5F7.toInt(),
                textoSecundario = 0xFF8E8E93.toInt(),
                azul = color,
                // Filo fino y sedoso: el color de la app con algo de luz, sin que "grite"
                borde = a(alfaBorde, mezclar(color, BLANCO, 0.22f)),
                bordePanel = a(0x8C, color),
                barra = a(0xF0, 0x0B0B0D),
                enterAccion = a(0xE6, color),
                textoAcento = textoSobre(color),
                burbuja = a(0xFA, mezclar(0xFF2A2A2D.toInt(), color, 0.12f)),
                bordeBurbuja = a(0xB0, mezclar(color, BLANCO, 0.15f)),
                desenfoque = 0,
                // Se dibujan con opacidad propia (no dependen de la transparencia de la tecla)
                brillo = a(0x1A, BLANCO),
                profundidad = a(0x3A, NEGRO),
                reflejo = a(0x24, BLANCO),
                resplandor = a(0x18, color),
            )
        }
    }
}
