package com.tecladoios

/** Ayudas de texto rápidas (sin expresiones regulares en el camino de cada tecla) */
object Texto {

    /** Minúsculas y sin tildes, pero la ñ se queda (es otra letra) */
    fun normal(s: CharSequence): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(normal(ch))
        return sb.toString()
    }

    fun normal(ch: Char): Char = when (ch) {
        'á', 'à', 'â', 'ä', 'ã', 'å', 'ā', 'Á', 'À', 'Â', 'Ä', 'Ã', 'Å' -> 'a'
        'é', 'è', 'ê', 'ë', 'ē', 'ę', 'É', 'È', 'Ê', 'Ë' -> 'e'
        'í', 'ì', 'î', 'ï', 'ī', 'Í', 'Ì', 'Î', 'Ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ', 'ø', 'ō', 'Ó', 'Ò', 'Ô', 'Ö', 'Õ' -> 'o'
        'ú', 'ù', 'û', 'ü', 'ū', 'Ú', 'Ù', 'Û', 'Ü' -> 'u'
        'Ñ' -> 'ñ'
        'ç', 'Ç' -> 'c'
        else -> ch.lowercaseChar()
    }

    fun esLetraPalabra(ch: Char) = ch.isLetterOrDigit() || ch == '\'' || ch == '-' || ch == '_'

    /** Pone a [palabra] las mayúsculas de lo que se escribió */
    fun conMayusculas(palabra: String, comoSeEscribio: String): String {
        if (comoSeEscribio.isEmpty() || palabra.isEmpty()) return palabra
        val todas = comoSeEscribio.length > 1 && comoSeEscribio.all { !it.isLetter() || it.isUpperCase() }
        return when {
            todas -> palabra.uppercase()
            comoSeEscribio[0].isUpperCase() -> palabra.replaceFirstChar { it.uppercaseChar() }
            else -> palabra
        }
    }

    fun capitalizar(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    /** Índice del abecedario (a..z más ñ) o -1 */
    fun indice(ch: Char): Int = when (ch) {
        in 'a'..'n' -> ch - 'a'
        'ñ' -> 14
        in 'o'..'z' -> ch - 'o' + 15
        else -> -1
    }
}
