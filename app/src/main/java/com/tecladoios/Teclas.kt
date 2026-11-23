package com.tecladoios

/**
 * Las teclas y sus distribuciones, iguales a las del teclado del iPhone en español.
 * Cada tecla tiene un tipo, lo que escribe, su ancho (en "unidades": una letra = 1) y lo que
 * aparece al mantenerla presionada.
 */
object K {
    const val TEXTO = 0
    const val SHIFT = 1
    const val BORRAR = 2
    const val NUM = 3        // pasar a 123
    const val ABC = 4        // volver a letras
    const val SIMB2 = 5      // #+=
    const val EMOJI = 6
    const val ESPACIO = 7
    const val INTRO = 8
    const val VACIA = 9      // hueco (sin tecla)
}

class Tecla(
    val tipo: Int,
    val texto: String = "",
    val ancho: Float = 1f,
    val variantes: List<String> = emptyList(),
    /** Teclas de función (mayúscula, borrar, 123, emoji, espacio, intro) */
    val funcion: Boolean = tipo != K.TEXTO,
) {
    val esLetra = tipo == K.TEXTO && texto.length == 1 && texto[0].isLetter()
}

class Fila(val teclas: List<Tecla>) {
    val unidades: Float = teclas.sumOf { it.ancho.toDouble() }.toFloat()
}

class Distribucion(val filas: List<Fila>, val modo: Int) {
    val unidades: Float = filas.maxOf { it.unidades }
    val numerico get() = modo == Distribuciones.NUMEROS || modo == Distribuciones.TELEFONO
}

object Distribuciones {

    const val LETRAS = 0
    const val SIMBOLOS = 1
    const val SIMBOLOS2 = 2
    const val NUMEROS = 3
    const val TELEFONO = 4

    // Lo que sale al mantener presionada cada letra (como en el iPhone en español)
    private val ACENTOS = mapOf(
        'a' to listOf("á", "à", "ä", "â", "ã", "å", "ą", "æ", "ā", "ª"),
        'e' to listOf("é", "è", "ë", "ê", "ę", "ė", "ē"),
        'i' to listOf("í", "ï", "ì", "î", "į", "ī"),
        'o' to listOf("ó", "ò", "ö", "ô", "õ", "ø", "œ", "ō", "º"),
        'u' to listOf("ú", "ü", "ù", "û", "ū"),
        'n' to listOf("ñ", "ń"),
        'c' to listOf("ç", "ć", "č"),
        's' to listOf("ß", "ś", "š"),
        'y' to listOf("ÿ"),
        'z' to listOf("ž", "ź", "ż"),
        'l' to listOf("ł"),
    )

    private fun letra(c: Char) = Tecla(K.TEXTO, c.toString(), 1f, ACENTOS[c] ?: emptyList())
    private fun letraAngosta(c: Char) = Tecla(K.TEXTO, c.toString(), 6.5f / 7f, ACENTOS[c] ?: emptyList())

    private fun t(s: String, ancho: Float = 1f, variantes: List<String> = emptyList()) =
        Tecla(K.TEXTO, s, ancho, variantes)

    private fun hueco(ancho: Float) = Tecla(K.VACIA, "", ancho, funcion = false)

    private fun filaAbajo(modo: Int) = Fila(listOf(
        // 123, emoji e intro más grandes (la barra espaciadora cede un poco)
        if (modo == LETRAS) Tecla(K.NUM, "123", 1.85f) else Tecla(K.ABC, "ABC", 1.85f),
        Tecla(K.EMOJI, "", 1.85f),
        Tecla(K.ESPACIO, "espacio", 3.6f),
        Tecla(K.INTRO, "", 2.7f),
    ))

    fun crear(modo: Int, decimal: Boolean = false): Distribucion = when (modo) {
        SIMBOLOS -> Distribucion(listOf(
            Fila("1234567890".map {
                when (it) {
                    '0' -> t("0", 1f, listOf("°"))
                    else -> t(it.toString())
                }
            }),
            Fila(listOf(
                t("-", 1f, listOf("–", "—", "•")), t("/", 1f, listOf("\\")), t(":"), t(";"), t("("), t(")"),
                t("S/", 1f, listOf("$", "€", "£", "¥", "₩", "¢")), t("&", 1f, listOf("§")), t("@"),
                t("\"", 1f, listOf("„", "“", "”", "«", "»")),
            )),
            Fila(listOf(
                Tecla(K.SIMB2, "#+=", 1.5f),
                t(".", 1.4f, listOf("…")), t(",", 1.4f), t("?", 1.4f, listOf("¿")), t("!", 1.4f, listOf("¡")),
                t("'", 1.4f, listOf("‘", "’", "`")),
                Tecla(K.BORRAR, "", 1.5f),
            )),
            filaAbajo(modo),
        ), SIMBOLOS)
        SIMBOLOS2 -> Distribucion(listOf(
            Fila(listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map {
                if (it == "%") t(it, 1f, listOf("‰")) else t(it)
            }),
            Fila(listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { t(it) }),
            Fila(listOf(
                Tecla(K.NUM, "123", 1.5f),
                t(".", 1.4f, listOf("…")), t(",", 1.4f), t("?", 1.4f, listOf("¿")), t("!", 1.4f, listOf("¡")),
                t("'", 1.4f, listOf("‘", "’", "`")),
                Tecla(K.BORRAR, "", 1.5f),
            )),
            filaAbajo(modo),
        ), SIMBOLOS2)
        // Teclado numérico del iPhone: 3 columnas
        NUMEROS -> Distribucion(listOf(
            Fila(listOf(t("1", 2f), t("2", 2f), t("3", 2f))),
            Fila(listOf(t("4", 2f), t("5", 2f), t("6", 2f))),
            Fila(listOf(t("7", 2f), t("8", 2f), t("9", 2f))),
            if (decimal) {
                val sep = java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString()
                Fila(listOf(Tecla(K.TEXTO, sep, 2f, funcion = true), t("0", 2f), Tecla(K.BORRAR, "", 2f)))
            } else Fila(listOf(Tecla(K.INTRO, "", 2f), t("0", 2f), Tecla(K.BORRAR, "", 2f))),
        ), NUMEROS)
        TELEFONO -> Distribucion(listOf(
            Fila(listOf(t("1", 2f), t("2", 2f), t("3", 2f))),
            Fila(listOf(t("4", 2f), t("5", 2f), t("6", 2f))),
            Fila(listOf(t("7", 2f), t("8", 2f), t("9", 2f))),
            Fila(listOf(Tecla(K.TEXTO, "+", 2f, listOf("*", "#"), funcion = true), t("0", 2f), Tecla(K.BORRAR, "", 2f))),
        ), TELEFONO)
        else -> Distribucion(listOf(
            Fila("qwertyuiop".map { letra(it) }),
            Fila("asdfghjklñ".map { letra(it) }),
            // Mayúscula y borrar más cortas (1.4 teclas); z–m con el ancho completo de una letra
            Fila(listOf(Tecla(K.SHIFT, "", 1.4f)) + "zxcvbnm".map { letra(it) } + listOf(Tecla(K.BORRAR, "", 1.4f))),
            filaAbajo(LETRAS),
        ), LETRAS)
    }
}
