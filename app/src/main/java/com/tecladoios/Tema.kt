package com.tecladoios

import android.content.Context
import android.graphics.Color

/**
 * Diseño negro AMOLED, simple al estilo iOS 27: fondo negro puro (ahorra batería en
 * pantallas OLED), todas las teclas iguales en grafito con letras blancas y una línea de luz
 * muy fina. El color de la app que estás usando (verde en WhatsApp, rosado en Instagram,
 * rojo en YouTube…) va en el borde del teclado y en el intro de enviar o buscar.
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
    /**
     * Color compañero del de la app (su vecino en el círculo de colores: el verde de WhatsApp
     * va con un turquesa, el rosado de Instagram con un naranja coral). Se funde con el
     * principal de un lado al otro del teclado; el principal siempre manda.
     */
    val azul2: Int,
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
    /** Cápsula de la corrección en la barra de sugerencias (gris suave, como iOS) */
    val capsula: Int,
    /** Sombra de lo que flota encima del teclado (vista previa, acentos) */
    val sombraFlotante: Int,
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

        /**
         * El compañero de [color]: el tono vecino (unos 40° más allá en el círculo de colores),
         * igual de vivo. Los grises se quedan como están.
         */
        fun companero(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            if (hsv[1] < 0.12f) return color or (0xFF shl 24)
            hsv[0] = (hsv[0] + 40f) % 360f
            hsv[2] = maxOf(hsv[2], 0.95f)
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
                "sutil" -> 0x12
                "vivo" -> 0x40
                else -> 0x1E
            }
            // Estilo iOS 27, simple: teclas grafito uniformes (letras y funciones iguales),
            // letras blancas, sin brillos de color. El color de la app queda donde importa: el
            // borde del teclado, el intro de enviar/buscar y lo que se elige (acentos, emojis).
            // Como el iPhone: las letras en un gris claro y las teclas de función (mayúscula,
            // borrar, 123, emojis, intro) un tono más oscuro, así se distinguen de un vistazo
            val grafito = a(0x42, BLANCO)
            val grafitoFuncion = a(0x29, BLANCO)
            return Tema(
                oscuro = true,
                fondo = NEGRO,
                fondoArriba = NEGRO,
                // Línea de luz arriba de cada tecla: un pelito más presente (pieza pulida)
                filoLuz = a(0x30, BLANCO),
                tinteInterior = 0,
                halo = 0,
                plata = 0xFFDDE1E8.toInt(),
                textoFuncion = 0xFFF5F5F7.toInt(),
                tecla = grafito,
                teclaFuncion = grafitoFuncion,
                // Presionada: un gris más claro, como en el iPhone
                teclaPresionada = a(0x74, BLANCO),
                teclaActiva = 0xFFE9E9EE.toInt(),
                textoActivo = 0xFF0A0A0B.toInt(),
                // Base finita debajo de cada tecla (asoma 1-2 px): la tecla parece una pieza
                // real y al tocarla BAJA sobre su base. Va grabada en la capa fija: no gasta.
                sombra = 0xFF161618.toInt(),
                texto = 0xFFF5F5F7.toInt(),
                textoSecundario = 0xFF8E8E93.toInt(),
                azul = color,
                azul2 = color,
                // Filo de las teclas: una línea de luz neutra muy fina (no de color)
                borde = a(alfaBorde, BLANCO),
                bordePanel = a(0xD0, color),
                barra = a(0xF0, 0x0B0B0D),
                enterAccion = color,
                textoAcento = textoSobre(color),
                // Burbuja un tono más clara que las teclas: se ve flotando encima, no pegada
                burbuja = 0xFF4B4B50.toInt(),
                bordeBurbuja = a(0x33, BLANCO),
                desenfoque = 0,
                // Volumen de la tecla: apenas más clara arriba y más honda abajo
                brillo = a(0x13, BLANCO),
                profundidad = a(0x24, NEGRO),
                reflejo = a(0x20, BLANCO),
                resplandor = 0,
                capsula = a(0x24, BLANCO),
                sombraFlotante = a(0x80, NEGRO),
            )
        }
    }
}
