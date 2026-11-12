package com.tecladoios

import android.content.Context
import android.graphics.Color

/**
 * Diseño "vidrio líquido" (como iOS): el teclado es una lámina de vidrio oscuro teñida con
 * el color de la app que estás usando (verde en WhatsApp, rosado en Instagram, rojo en
 * YouTube…), con una luz suave de ese color arriba. Cada tecla es una pieza de vidrio:
 * translúcida, con un filo de luz arriba, el filo del color de la app y el color de la app
 * que se asoma por dentro, abajo, como luz que atraviesa el vidrio.
 *
 * Todo son degradados que se arman una sola vez (sin desenfoque en vivo): se ve lujoso
 * sin calentar el celular ni hacerlo lento.
 */
data class Tema(
    val oscuro: Boolean,
    /** Vidrio del fondo, arriba (también es el color de la barra de sugerencias) */
    val fondo: Int,
    val fondoArriba: Int,
    /** Vidrio del fondo, abajo (más profundo) */
    val fondoAbajo: Int,
    /** Luz del color de la app que se refleja en la lámina de vidrio, arriba */
    val luzAmbiente: Int,
    /** Filo de luz blanca en el borde de arriba de cada tecla (el brillo del vidrio) */
    val filoLuz: Int,
    /** Color de la app que se asoma por dentro del vidrio, abajo de cada tecla */
    val tinteInterior: Int,
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

        /** El color [color] (translúcido) tal como se ve encima de [base] (opaco) */
        fun sobre(color: Int, base: Int): Int {
            val f = Color.alpha(color) / 255f
            fun c(p: Int, q: Int) = (q + (p - q) * f).toInt().coerceIn(0, 255)
            return Color.rgb(c(Color.red(color), Color.red(base)), c(Color.green(color), Color.green(base)),
                c(Color.blue(color), Color.blue(base)))
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
                "sutil" -> 0x22
                "vivo" -> 0xB0
                else -> 0x36
            }
            // Vidrio oscuro teñido con la app: arriba un grafito con un toque de color, abajo
            // más profundo. Nunca negro puro: así el vidrio de las teclas se nota.
            val vidrioArriba = mezclar(0xFF1D1E23.toInt(), color, 0.16f)
            val vidrioAbajo = mezclar(0xFF0F1013.toInt(), color, 0.07f)
            return Tema(
                oscuro = true,
                fondo = vidrioArriba,
                fondoArriba = vidrioArriba,
                fondoAbajo = vidrioAbajo,
                luzAmbiente = a(0x2E, mezclar(color, BLANCO, 0.10f)),
                filoLuz = a(0x8C, BLANCO),
                tinteInterior = a(0x30, color),
                // Vidrio: blanco translúcido apenas teñido. Las letras más claras que las de
                // función (como en iPhone)
                tecla = a(0x30, mezclar(BLANCO, color, 0.10f)),
                teclaFuncion = a(0x17, mezclar(BLANCO, color, 0.14f)),
                // Presionada: el vidrio se llena de luz con el color de la app
                teclaPresionada = a(0x5C, mezclar(BLANCO, color, 0.28f)),
                // Mayúsculas activadas: vidrio blanco lechoso, no encandila
                teclaActiva = 0xFFE8E8ED.toInt(),
                textoActivo = 0xFF0A0A0B.toInt(),
                // Sombra suave debajo de cada pieza de vidrio (le da peso sobre la lámina)
                sombra = a(0x4A, mezclar(NEGRO, color, 0.08f)),
                texto = 0xFFF7F7F9.toInt(),
                textoSecundario = 0xFF9A9AA2.toInt(),
                azul = color,
                // Filo con el color de la app y algo de luz
                borde = a(alfaBorde, mezclar(color, BLANCO, 0.30f)),
                bordePanel = a(0x66, mezclar(color, BLANCO, 0.15f)),
                barra = a(0xF0, vidrioArriba),
                enterAccion = a(0xE6, color),
                textoAcento = textoSobre(color),
                burbuja = a(0xF5, mezclar(0xFF2E2F36.toInt(), color, 0.16f)),
                bordeBurbuja = a(0xC0, mezclar(color, BLANCO, 0.25f)),
                desenfoque = 0,
                // Se dibujan con opacidad propia (no dependen de la transparencia de la tecla)
                brillo = a(0x22, BLANCO),
                profundidad = a(0x18, NEGRO),
                reflejo = a(0x24, BLANCO),
                resplandor = a(0x1E, color),
            )
        }
    }
}
