package com.tecladoios

/**
 * Las teclas y sus distribuciones. Cada tecla tiene un tipo, lo que escribe, su ancho (en
 * "unidades": una letra = 1), la pista de símbolo que se ve en la esquina y lo que aparece al
 * mantenerla presionada.
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
    val pista: String? = null,
    val variantes: List<String> = emptyList(),
    /** Teclas grises (función): se dibujan con otro tono, como en el iPhone */
    val funcion: Boolean = tipo != K.TEXTO && tipo != K.ESPACIO,
) {
    val esLetra = tipo == K.TEXTO && texto.length == 1 && texto[0].isLetter()
}

class Fila(val teclas: List<Tecla>, val sangria: Float = 0f) {
    val unidades: Float = teclas.sumOf { it.ancho.toDouble() }.toFloat() + sangria * 2
}

class Distribucion(val filas: List<Fila>, val nombre: String) {
    val unidades: Float = filas.maxOf { it.unidades }
}

object Distribuciones {

    const val LETRAS = 0
    const val SIMBOLOS = 1
    const val SIMBOLOS2 = 2
    const val NUMEROS = 3
    const val TELEFONO = 4

    /** Tipo de campo: cambia la fila de abajo (correo, web) */
    const val CAMPO_TEXTO = 0
    const val CAMPO_CORREO = 1
    const val CAMPO_WEB = 2

    private val PISTAS = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5", 'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")", 'ñ' to "/",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
    )

    private val ACENTOS = mapOf(
        'a' to listOf("á", "à", "â", "ä", "ã", "å", "ā", "ª"),
        'e' to listOf("é", "è", "ê", "ë", "ē", "ę", "€"),
        'i' to listOf("í", "ì", "î", "ï", "ī"),
        'o' to listOf("ó", "ò", "ô", "ö", "õ", "ø", "ō", "º"),
        'u' to listOf("ú", "ü", "ù", "û", "ū"),
        'n' to listOf("ñ", "ń"),
        'c' to listOf("ç", "ć", "č"),
        's' to listOf("ß", "š", "ś"),
        'y' to listOf("ý", "ÿ"),
        'z' to listOf("ž", "ź", "ż"),
        'l' to listOf("ł"),
    )

    private fun letra(c: Char): Tecla {
        val pista = PISTAS[c]
        val acentos = ACENTOS[c] ?: emptyList()
        // Las vocales abren primero con la tilde; las demás, con su símbolo
        val variantes = if (acentos.isNotEmpty() && c in "aeiou") acentos + listOfNotNull(pista)
            else listOfNotNull(pista) + acentos
        return Tecla(K.TEXTO, c.toString(), 1f, pista, variantes)
    }

    private fun texto(s: String, ancho: Float = 1f, variantes: List<String> = emptyList()) =
        Tecla(K.TEXTO, s, ancho, null, variantes, funcion = false)

    private val PUNTUACION = listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "…")

    private fun filaNumeros() = Fila("1234567890".map {
        val v = when (it) {
            '1' -> listOf("¹", "½", "⅓", "¼"); '2' -> listOf("²", "⅔"); '3' -> listOf("³", "¾")
            '0' -> listOf("°", "∅"); else -> emptyList()
        }
        texto(it.toString(), 1f, v)
    })

    private fun filaAbajo(modo: Int, campo: Int): Fila {
        val izquierda = if (modo == LETRAS) Tecla(K.NUM, "123", 1.3f) else Tecla(K.ABC, "ABC", 1.3f)
        val emoji = Tecla(K.EMOJI, "", 1.1f)
        val intro = Tecla(K.INTRO, "", 2.0f)
        return when (campo) {
            CAMPO_CORREO -> Fila(listOf(izquierda, emoji, texto("@", 1f), Tecla(K.ESPACIO, "", 3.6f),
                texto(".", 1f, listOf(",", "-", "_", ".com", ".pe", ".gob.pe")), intro))
            CAMPO_WEB -> Fila(listOf(izquierda, emoji, texto("/", 1f), Tecla(K.ESPACIO, "", 3.6f),
                texto(".", 1f, listOf(".com", ".pe", ".gob.pe", ".org", "-", "_")), intro))
            else -> Fila(listOf(izquierda, emoji, Tecla(K.ESPACIO, "", 4.6f),
                texto(".", 1f, PUNTUACION.drop(1)), intro))
        }
    }

    fun crear(modo: Int, campo: Int, filaNum: Boolean): Distribucion = when (modo) {
        SIMBOLOS -> Distribucion(listOf(
            filaNumeros(),
            Fila(listOf("-", "/", ":", ";", "(", ")", "S/", "&", "@", "\"").map {
                when (it) {
                    "S/" -> texto(it, 1f, listOf("$", "€", "£", "¥", "₩", "¢"))
                    "-" -> texto(it, 1f, listOf("–", "—", "•"))
                    "\"" -> texto(it, 1f, listOf("“", "”", "«", "»", "„"))
                    else -> texto(it)
                }
            }),
            // Misma forma que la fila de letras (1,5 + 7 teclas + 1,5): el teclado no "salta" al cambiar
            Fila(listOf(Tecla(K.SIMB2, "#+=", 1.5f), texto("."), texto(","), texto("?", 1f, listOf("¿")),
                texto("!", 1f, listOf("¡")), texto("'", 1f, listOf("‘", "’", "`")), texto("%", 1f, listOf("‰")),
                texto("*", 1f, listOf("•", "†")), Tecla(K.BORRAR, "", 1.5f))),
            filaAbajo(modo, campo),
        ), "123")
        SIMBOLOS2 -> Distribucion(listOf(
            Fila(listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { texto(it) }),
            Fila(listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { texto(it) }),
            Fila(listOf(Tecla(K.NUM, "123", 1.5f), texto("."), texto(","), texto("¿"), texto("¡"),
                texto("°"), texto("§"), texto("…"), Tecla(K.BORRAR, "", 1.5f))),
            filaAbajo(modo, campo),
        ), "#+=")
        NUMEROS -> Distribucion(listOf(
            Fila(listOf(texto("1", 2f), texto("2", 2f), texto("3", 2f), texto("-", 1.4f))),
            Fila(listOf(texto("4", 2f), texto("5", 2f), texto("6", 2f), texto(",", 1.4f))),
            Fila(listOf(texto("7", 2f), texto("8", 2f), texto("9", 2f), Tecla(K.BORRAR, "", 1.4f))),
            Fila(listOf(texto(".", 2f), texto("0", 2f), Tecla(K.ESPACIO, "", 2f), Tecla(K.INTRO, "", 1.4f))),
        ), "num")
        TELEFONO -> Distribucion(listOf(
            Fila(listOf(texto("1", 2f), texto("2", 2f), texto("3", 2f), texto("+", 1.4f))),
            Fila(listOf(texto("4", 2f), texto("5", 2f), texto("6", 2f), texto("*", 1.4f))),
            Fila(listOf(texto("7", 2f), texto("8", 2f), texto("9", 2f), Tecla(K.BORRAR, "", 1.4f))),
            Fila(listOf(texto("#", 2f), texto("0", 2f), Tecla(K.ESPACIO, "", 2f), Tecla(K.INTRO, "", 1.4f))),
        ), "tel")
        else -> {
            val filas = ArrayList<Fila>(5)
            if (filaNum) filas.add(filaNumeros())
            filas.add(Fila("qwertyuiop".map { letra(it) }))
            filas.add(Fila("asdfghjklñ".map { letra(it) }))
            filas.add(Fila(listOf(Tecla(K.SHIFT, "", 1.5f)) + "zxcvbnm".map { letra(it) } + Tecla(K.BORRAR, "", 1.5f)))
            filas.add(filaAbajo(LETRAS, campo))
            Distribucion(filas, "abc")
        }
    }
}
