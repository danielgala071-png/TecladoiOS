package com.tecladoios

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * Colores del teclado.
 * - "Sólido (iPhone)": igual al teclado de iOS 26/27 (gris oscuro o claro según el sistema).
 * - Transparente / Predeterminado: Liquid Glass con desenfoque.
 * - AMOLED: negro puro.
 * - Liquid Glass de color: vidrio teñido con el color de la app que estás usando,
 *   con un difuminado que es más intenso arriba y se aclara hacia abajo.
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
    /** Color de la parte de arriba del fondo (0 = fondo de un solo color) */
    val fondoArriba: Int = 0,
    /** Mayúsculas, borrar y 123 teñidas (translúcidas) con el color de la app (0 = sin teñir) */
    val teclaApp: Int = 0,
    /** Enter de acción (enviar, buscar…) translúcido con el color de la app (0 = el azul normal) */
    val enterApp: Int = 0,
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
            val fondoArmonizado = if (t.fondo == 0xFF000000.toInt()) t.fondo else mezclar(t.fondo, acento, 0.05f)
            return t.copy(
                azul = acento,
                textoAcento = textoSobre(acento),
                fondo = fondoArmonizado,
            )
        }

        private fun textoSobre(acento: Int): Int {
            val lum = (0.299 * Color.red(acento) + 0.587 * Color.green(acento) + 0.114 * Color.blue(acento)) / 255.0
            return if (lum > 0.68) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        /** Liquid Glass teñido con el color de la app (verde en WhatsApp, rosado en Instagram…) */
        private fun vidrioDeColor(context: Context, oscuro: Boolean, conDesenfoque: Boolean, color: Int): Tema {
            val base = if (oscuro) 0xFF121214.toInt() else 0xFFF4F5F8.toInt()
            val blanco = 0xFFFFFFFF.toInt()
            val tinte = mezclar(base, color, if (oscuro) 0.26f else 0.20f)
            val tinteArriba = mezclar(base, color, if (oscuro) 0.48f else 0.38f)
            val teclaRgb = mezclar(blanco, color, if (oscuro) 0.20f else 0.07f)
            val radioBlur = if (conDesenfoque) (Medidas.escala(context) * 26).toInt() else 0
            return Tema(
                oscuro = oscuro,
                fondo = a(if (conDesenfoque) 0x78 else 0xDC, tinte),
                fondoArriba = a(if (conDesenfoque) 0x9E else 0xEA, tinteArriba),
                tecla = a(if (oscuro) 0x3A else 0xB8, teclaRgb),
                teclaFuncion = a(if (oscuro) 0x22 else 0x6E, teclaRgb),
                teclaPresionada = a(if (oscuro) 0x80 else 0xE6, mezclar(blanco, color, 0.15f)),
                sombra = 0,
                texto = if (oscuro) blanco else 0xFF000000.toInt(),
                textoSecundario = if (oscuro) 0xFFB4B4BA.toInt() else 0xFF5E5E64.toInt(),
                azul = color,
                borde = a(if (oscuro) 0x50 else 0xB3, mezclar(blanco, color, 0.30f)),
                brillo = a(if (oscuro) 0x30 else 0x70, blanco),
                desenfoque = radioBlur,
                textoAcento = textoSobre(color),
            )
        }

        /** Color de acento del sistema (Material You), para cuando la app no tiene color */
        private fun acentoSistema(context: Context, oscuro: Boolean): Int = try {
            context.getColor(if (oscuro) android.R.color.system_accent1_300 else android.R.color.system_accent1_500)
        } catch (_: Exception) {
            if (oscuro) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
        }

        /**
         * Teclas de función (mayúsculas, borrar, 123 y enter) con un vidrio translúcido del color
         * de la app: verde en WhatsApp, rosado en Instagram, rojo en YouTube…
         */
        private fun conTeclasApp(t: Tema, acento: Int): Tema {
            val blanco = 0xFFFFFFFF.toInt()
            val negro = 0xFF000000.toInt()
            // En oscuro se aclara un poco el color para que no se pierda; en claro se oscurece apenas
            val base = if (t.oscuro) mezclar(acento, blanco, 0.12f) else mezclar(acento, negro, 0.04f)
            val tecla = a(if (t.oscuro) 0x5C else 0x63, base)
            val enter = a(if (t.oscuro) 0xB8 else 0xC4, acento)
            return t.copy(teclaApp = tecla, enterApp = enter, textoAcento = textoSobre(acento))
        }

        /** conDesenfoque: si el sistema puede desenfocar lo que hay detrás. acento: color de la app */
        fun desde(context: Context, conDesenfoque: Boolean, acento: Int? = null): Tema {
            val t = base(context, conDesenfoque, acento)
            return if (acento != null && Ajustes.teclasColor(context)) conTeclasApp(t, acento) else t
        }

        private fun base(context: Context, conDesenfoque: Boolean, acento: Int?): Tema {
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
            if (Ajustes.vidrio(context) == "color") {
                return vidrioDeColor(context, oscuro, conDesenfoque, acento ?: acentoSistema(context, oscuro))
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
