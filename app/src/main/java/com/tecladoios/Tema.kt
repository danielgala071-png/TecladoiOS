package com.tecladoios

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * Colores del teclado.
 * - "Sólido (iPhone)": igual al teclado de iOS 26/27 (gris oscuro o claro según el sistema).
 * - Transparente / Predeterminado: Liquid Glass con desenfoque.
 * - AMOLED: negro puro.
 * Con "Combinar con la app", el color de acento sale del ícono de la app que estás usando.
 */
data class Tema(
    val oscuro: Boolean,
    val fondo: Int,
    val tecla: Int,
    val teclaFuncion: Int,
    val teclaPresionada: Int,
    val sombra: Int,
    val texto: Int,
    val textoSecundario: Int,
    val azul: Int,
    val borde: Int,
    val brillo: Int,
    val desenfoque: Int,
    /** Color del ícono/texto sobre la tecla de acción (blanco o negro según el acento) */
    val textoAcento: Int = 0xFFFFFFFF.toInt(),
) {
    companion object {
        private fun a(alfa: Int, rgb: Int) = (alfa shl 24) or (rgb and 0xFFFFFF)

        private fun mezclar(x: Int, y: Int, f: Float): Int {
            fun c(p: Int, q: Int) = (p + (q - p) * f).toInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(x), c(Color.red(x), Color.red(y)),
                c(Color.green(x), Color.green(y)), c(Color.blue(x), Color.blue(y)))
        }

        private fun conAcento(t: Tema, acento: Int?): Tema {
            if (acento == null) return t
            val lum = (0.299 * Color.red(acento) + 0.587 * Color.green(acento) + 0.114 * Color.blue(acento)) / 255.0
            val fondoArmonizado = if (t.fondo == 0xFF000000.toInt()) t.fondo else mezclar(t.fondo, acento, 0.05f)
            return t.copy(
                azul = acento,
                textoAcento = if (lum > 0.68) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
                fondo = fondoArmonizado,
            )
        }

        /** conDesenfoque: si el sistema puede desenfocar lo que hay detrás. acento: color de la app */
        fun desde(context: Context, conDesenfoque: Boolean, acento: Int? = null): Tema {
            val sistemaOscuro = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val oscuro = when (Ajustes.tema(context)) {
                "claro" -> false
                "oscuro", "amoled" -> true
                else -> sistemaOscuro
            }
            if (Ajustes.tema(context) == "amoled") {
                return conAcento(Tema(
                    oscuro = true,
                    fondo = 0xFF000000.toInt(),
                    tecla = 0xFF38383B.toInt(),
                    teclaFuncion = 0xFF26262A.toInt(),
                    teclaPresionada = 0xFF55555A.toInt(),
                    sombra = 0,
                    texto = 0xFFFFFFFF.toInt(),
                    textoSecundario = 0xFF8E8E93.toInt(),
                    azul = 0xFF0A84FF.toInt(),
                    borde = a(0x2E, 0xFFFFFF),
                    brillo = a(0x0F, 0xFFFFFF),
                    desenfoque = 0,
                ), acento)
            }
            val nivel = when (Ajustes.vidrio(context)) {
                "transparente" -> 0
                "normal" -> 1
                else -> 2
            }

            // Sólido como el teclado del iPhone (la imagen de referencia)
            if (nivel == 2) {
                val t = if (oscuro) Tema(
                    oscuro = true,
                    fondo = 0xFF1F1F21.toInt(),
                    tecla = 0xFF434346.toInt(),
                    teclaFuncion = 0xFF434346.toInt(),
                    teclaPresionada = 0xFF68686C.toInt(),
                    sombra = 0,
                    texto = 0xFFFFFFFF.toInt(),
                    textoSecundario = 0xFF98989F.toInt(),
                    azul = 0xFF2F7CF6.toInt(),
                    borde = a(0x1C, 0xFFFFFF),
                    brillo = a(0x09, 0xFFFFFF),
                    desenfoque = 0,
                ) else Tema(
                    oscuro = false,
                    fondo = 0xFFD3D5DB.toInt(),
                    tecla = 0xFFFFFFFF.toInt(),
                    teclaFuncion = 0xFFFFFFFF.toInt(),
                    teclaPresionada = 0xFFC3C7CE.toInt(),
                    sombra = a(0x2A, 0x000000),
                    texto = 0xFF000000.toInt(),
                    textoSecundario = 0xFF6E6E73.toInt(),
                    azul = 0xFF2F7CF6.toInt(),
                    borde = a(0x00, 0xFFFFFF),
                    brillo = a(0x00, 0xFFFFFF),
                    desenfoque = 0,
                )
                return conAcento(t, acento)
            }

            val alfaFondo = if (conDesenfoque) intArrayOf(0x30, 0x6E)[nivel] else intArrayOf(0xB8, 0xD6)[nivel]
            val radioBlur = if (conDesenfoque)
                (Medidas.escala(context) * intArrayOf(14, 26)[nivel]).toInt() else 0

            val t = if (oscuro) Tema(
                oscuro = true,
                fondo = a(alfaFondo, 0x141416),
                tecla = a(intArrayOf(0x33, 0x45)[nivel], 0xFFFFFF),
                teclaFuncion = a(intArrayOf(0x1A, 0x26)[nivel], 0xFFFFFF),
                teclaPresionada = a(0x80, 0xFFFFFF),
                sombra = 0,
                texto = 0xFFFFFFFF.toInt(),
                textoSecundario = 0xFF9A9AA0.toInt(),
                azul = 0xFF0A84FF.toInt(),
                borde = a(0x40, 0xFFFFFF),
                brillo = a(0x2E, 0xFFFFFF),
                desenfoque = radioBlur,
            ) else Tema(
                oscuro = false,
                fondo = a(alfaFondo, 0xF2F3F7),
                tecla = a(intArrayOf(0x8C, 0xB8)[nivel], 0xFFFFFF),
                teclaFuncion = a(intArrayOf(0x40, 0x6B)[nivel], 0xFFFFFF),
                teclaPresionada = a(0xE6, 0xDDE0E6),
                sombra = 0,
                texto = 0xFF000000.toInt(),
                textoSecundario = 0xFF6E6E73.toInt(),
                azul = 0xFF007AFF.toInt(),
                borde = a(0xA6, 0xFFFFFF),
                brillo = a(0x66, 0xFFFFFF),
                desenfoque = radioBlur,
            )
            return conAcento(t, acento)
        }
    }
}
