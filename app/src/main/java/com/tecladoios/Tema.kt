package com.tecladoios

import android.content.Context
import android.content.res.Configuration

/**
 * Colores Liquid Glass estilo iOS 27.
 * Tres niveles como en iOS 27: transparente, predeterminado y tintado.
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
) {
    companion object {
        private fun a(alfa: Int, rgb: Int) = (alfa shl 24) or (rgb and 0xFFFFFF)

        /** conDesenfoque: si el sistema puede desenfocar de verdad lo que hay detrás */
        fun desde(context: Context, conDesenfoque: Boolean): Tema {
            val sistemaOscuro = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val oscuro = when (Ajustes.tema(context)) {
                "claro" -> false
                "oscuro" -> true
                else -> sistemaOscuro
            }
            val nivel = when (Ajustes.vidrio(context)) {
                "transparente" -> 0
                "tintado" -> 2
                else -> 1
            }
            // Sin desenfoque real el fondo debe ser más sólido para que se lea bien
            val alfaFondo = if (conDesenfoque) intArrayOf(0x30, 0x6E, 0xE6)[nivel]
                            else intArrayOf(0xB8, 0xD6, 0xF2)[nivel]
            val radioBlur = if (conDesenfoque)
                (Medidas.escala(context) * intArrayOf(14, 26, 40)[nivel]).toInt() else 0

            return if (oscuro) Tema(
                oscuro = true,
                fondo = a(alfaFondo, 0x141416),
                tecla = if (nivel == 2) a(0xFF, 0x56565B) else a(intArrayOf(0x33, 0x45)[nivel], 0xFFFFFF),
                teclaFuncion = if (nivel == 2) a(0xFF, 0x37373A) else a(intArrayOf(0x1A, 0x26)[nivel], 0xFFFFFF),
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
                tecla = if (nivel == 2) a(0xFF, 0xFFFFFF) else a(intArrayOf(0x8C, 0xB8)[nivel], 0xFFFFFF),
                teclaFuncion = if (nivel == 2) a(0xFF, 0xBFC4CE) else a(intArrayOf(0x40, 0x6B)[nivel], 0xFFFFFF),
                teclaPresionada = a(0xE6, 0xDDE0E6),
                sombra = 0,
                texto = 0xFF000000.toInt(),
                textoSecundario = 0xFF6E6E73.toInt(),
                azul = 0xFF007AFF.toInt(),
                borde = a(0xA6, 0xFFFFFF),
                brillo = a(0x66, 0xFFFFFF),
                desenfoque = radioBlur,
            )
        }
    }
}
