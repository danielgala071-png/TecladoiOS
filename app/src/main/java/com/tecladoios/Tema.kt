package com.tecladoios

import android.content.Context
import android.graphics.Color

/**
 * Diseño negro AMOLED premium: fondo negro puro (ahorra batería en pantallas OLED) y teclas
 * de grafito profundo, como piezas pulidas. Cada tecla tiene un filo de luz blanca finita
 * arriba, el filo del color de la app que estás usando (verde en WhatsApp, rosado en
 * Instagram, rojo en YouTube…) y ese mismo color brillando apenas por dentro, abajo.
 * Al tocarla, la tecla se enciende y suelta un halo del color de la app.
 *
 * Siempre es oscuro, aunque el celular esté en modo claro.
 */
data class Tema(
    val oscuro: Boolean,
    /** Fondo del teclado: negro puro */
    val fondo: Int,
    val fondoArriba: Int,
    /** Filo de luz blanca en el borde de arriba de cada tecla (brillo de pieza pulida) */
    val filoLuz: Int,
    /** Color de la app que brilla apenas por dentro, abajo de cada tecla */
    val tinteInterior: Int,
    /** Halo del color de la app que rodea la tecla al tocarla (se apaga con la tecla) */
    val halo: Int,
    /** Brillo plateado del filo metálico, en las esquinas de arriba de cada tecla */
    val plata: Int,
    /** Íconos y texto de las teclas de función (obsidiana): el color de la app con luz */
    val textoFuncion: Int,
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

        /**
         * El color de la app más vivo e intenso: más saturado y más luminoso, para que brille
         * sobre el negro (el verde de WhatsApp, el rosado de Instagram, el rojo de YouTube…
         * se ven a todo color). Los grises casi sin color se dejan como están.
         */
        fun intenso(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            if (hsv[1] < 0.12f) return color or (0xFF shl 24)
            hsv[1] = (hsv[1] * 1.25f + 0.12f).coerceAtMost(1f)
            hsv[2] = maxOf(hsv[2], 0.92f)
            return Color.HSVToColor(0xFF, hsv)
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
            val color = intenso((acento ?: acentoSistema(context)) or (0xFF shl 24))
            // Filo de las teclas elegido en Ajustes (apagado, sutil, normal o vivo)
            val alfaBorde = when (Ajustes.bordeTeclas(context)) {
                "apagado" -> 0x00
                "sutil" -> 0x55
                "vivo" -> 0xF0
                else -> 0x96
            }
            return Tema(
                oscuro = true,
                fondo = NEGRO,
                fondoArriba = NEGRO,
                filoLuz = a(0x58, BLANCO),
                tinteInterior = a(0x2C, color),
                halo = a(0x8C, color),
                // Plata fría, apenas teñida con la app (así combina con cualquier color)
                plata = mezclar(0xFFDDE1E8.toInt(), color, 0.12f),
                // Íconos de función a todo color (solo un toque de luz si el color es oscuro)
                textoFuncion = mezclar(color, BLANCO, 0.10f),
                // Grafito profundo, apenas teñido: las letras se distinguen claramente de las
                // teclas de función (más oscuras), como en el teclado oscuro del iPhone
                tecla = a(0x2E, mezclar(BLANCO, color, 0.06f)),
                // Obsidiana: más oscura y mate que las letras
                teclaFuncion = a(0x12, mezclar(BLANCO, color, 0.10f)),
                // Tecla presionada: se enciende en gris claro con un toque del color de la app
                teclaPresionada = a(0x66, mezclar(BLANCO, color, 0.45f)),
                // Mayúsculas activadas: blanco suave, no encandila sobre el negro
                teclaActiva = 0xFFE6E6EB.toInt(),
                textoActivo = 0xFF0A0A0B.toInt(),
                // Sin canto: sobre el negro puro no se vería; el peso lo da el hundido al tocar
                sombra = 0,
                texto = 0xFFF5F5F7.toInt(),
                textoSecundario = 0xFF8E8E93.toInt(),
                azul = color,
                // Filo del color de la app con algo de luz: se nota sin "gritar"
                // Filo a todo color (antes iba mezclado con blanco y se veía lavado)
                borde = a(alfaBorde, mezclar(color, BLANCO, 0.06f)),
                bordePanel = a(0xC0, color),
                barra = a(0xF0, 0x0B0B0D),
                enterAccion = color,
                textoAcento = textoSobre(color),
                burbuja = a(0xFA, mezclar(0xFF27272B.toInt(), color, 0.12f)),
                bordeBurbuja = a(0xE6, color),
                desenfoque = 0,
                // Se dibujan con opacidad propia (no dependen de la transparencia de la tecla)
                brillo = a(0x18, BLANCO),
                profundidad = a(0x20, NEGRO),
                reflejo = a(0x24, BLANCO),
                resplandor = a(0x38, color),
            )
        }
    }
}
