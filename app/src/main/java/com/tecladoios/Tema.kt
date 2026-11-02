package com.tecladoios

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * Un solo diseño, como el teclado del iPhone (la imagen de referencia), hecho de
 * Liquid Glass teñido con el color de la app que estás usando:
 * verde en WhatsApp, rosado en Instagram, rojo en YouTube…
 *
 * Claro u oscuro sigue al sistema. Ya no hay temas para elegir.
 */
data class Tema(
    val oscuro: Boolean,
    /** Fondo del teclado (vidrio teñido) */
    val fondo: Int,
    /** Parte de arriba del fondo: el tono de la app se difumina de arriba hacia abajo */
    val fondoArriba: Int,
    val tecla: Int,
    val teclaFuncion: Int,
    /** Tecla presionada: vidrio del color de la app (no se aclara ni brilla) */
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
    /** Vidrio de la barra de sugerencias */
    val barra: Int,
    /** Enter de acción (enviar, buscar…) con el color de la app */
    val enterAccion: Int,
    val textoAcento: Int,
    /** Vista previa de las teclas: vidrio del color de la app */
    val burbuja: Int,
    val bordeBurbuja: Int,
    val desenfoque: Int,
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

        private fun textoSobre(acento: Int): Int {
            val lum = (0.299 * Color.red(acento) + 0.587 * Color.green(acento) + 0.114 * Color.blue(acento)) / 255.0
            return if (lum > 0.68) NEGRO else BLANCO
        }

        /** Color de acento del sistema (Material You), para cuando la app no tiene color */
        private fun acentoSistema(context: Context, oscuro: Boolean): Int = try {
            context.getColor(if (oscuro) android.R.color.system_accent1_300 else android.R.color.system_accent1_500)
        } catch (_: Exception) {
            if (oscuro) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
        }

        /** [conDesenfoque]: si el sistema puede desenfocar lo que hay detrás. [acento]: color de la app */
        fun desde(context: Context, conDesenfoque: Boolean, acento: Int?): Tema {
            val oscuro = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val color = (acento ?: acentoSistema(context, oscuro)) or (0xFF shl 24)
            val blur = if (conDesenfoque) (Medidas.escala(context) * 24).toInt() else 0

            return if (oscuro) {
                val base = 0xFF232325.toInt()
                Tema(
                    oscuro = true,
                    fondo = a(if (conDesenfoque) 0xB0 else 0xF2, mezclar(base, color, 0.16f)),
                    fondoArriba = a(if (conDesenfoque) 0xC8 else 0xF5, mezclar(base, color, 0.30f)),
                    // Gris de las teclas del iPhone, apenas teñido: vidrio casi opaco
                    tecla = a(0xD6, mezclar(0xFF6A6A6D.toInt(), color, 0.08f)),
                    teclaFuncion = a(0xD6, mezclar(0xFF58585C.toInt(), color, 0.12f)),
                    teclaPresionada = a(0xE6, mezclar(0xFF48484C.toInt(), color, 0.45f)),
                    teclaActiva = 0xFFEBEBF0.toInt(),
                    textoActivo = NEGRO,
                    sombra = a(0x59, 0x000000),
                    texto = BLANCO,
                    textoSecundario = 0xFFA8A8AE.toInt(),
                    azul = color,
                    borde = a(0x8C, mezclar(color, BLANCO, 0.25f)),
                    bordePanel = a(0xD9, mezclar(color, BLANCO, 0.15f)),
                    barra = a(0x24, mezclar(BLANCO, color, 0.45f)),
                    enterAccion = a(0xE0, color),
                    textoAcento = textoSobre(color),
                    burbuja = a(0xF5, mezclar(0xFF5E5E62.toInt(), color, 0.32f)),
                    bordeBurbuja = a(0xCC, mezclar(color, BLANCO, 0.2f)),
                    desenfoque = blur,
                )
            } else {
                val base = 0xFFD1D3D9.toInt()
                Tema(
                    oscuro = false,
                    fondo = a(if (conDesenfoque) 0xB8 else 0xF2, mezclar(base, color, 0.14f)),
                    fondoArriba = a(if (conDesenfoque) 0xD0 else 0xF5, mezclar(base, color, 0.26f)),
                    tecla = a(0xF2, mezclar(BLANCO, color, 0.04f)),
                    teclaFuncion = a(0xE6, mezclar(0xFFA9AEB8.toInt(), color, 0.18f)),
                    teclaPresionada = a(0xF2, mezclar(0xFFC4C8D0.toInt(), color, 0.42f)),
                    teclaActiva = BLANCO,
                    textoActivo = NEGRO,
                    sombra = a(0x40, 0x5A5B5E),
                    texto = NEGRO,
                    textoSecundario = 0xFF5E5E64.toInt(),
                    azul = color,
                    borde = a(0x9E, color),
                    bordePanel = a(0xC0, color),
                    barra = a(0x80, mezclar(BLANCO, color, 0.12f)),
                    enterAccion = a(0xE6, color),
                    textoAcento = textoSobre(color),
                    burbuja = a(0xFA, mezclar(BLANCO, color, 0.18f)),
                    bordeBurbuja = a(0xC0, color),
                    desenfoque = blur,
                )
            }
        }
    }
}
