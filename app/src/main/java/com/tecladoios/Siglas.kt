package com.tecladoios

/**
 * Siglas que se escriben solas en mayúscula: escribes «dni» y queda «DNI». Incluye las del
 * Estado peruano y del sector público; además, cualquier palabra que hayas escrito tú en
 * mayúsculas (ej. «PNP», «UGEL») el cerebro la aprende como sigla tuya.
 */
object Siglas {
    private val FIJAS: Set<String> = listOf(
        // Documentos, trámites y tributos
        "dni", "ruc", "cts", "igv", "isc", "uit", "rmv", "afp", "onp", "sctr", "pdf", "usb",
        // Instituciones del Estado peruano
        "sunat", "reniec", "sunarp", "sunafil", "sunass", "sunedu", "mef", "minsa", "minedu",
        "mininter", "mimp", "midis", "minam", "minem", "mincetur", "mtpe", "mtc", "mvcs", "pcm",
        "pnp", "jne", "onpe", "inei", "bcr", "bcrp", "sbs", "smv", "osce", "oece", "cgr", "oci",
        "indecopi", "osinergmin", "osiptel", "ositran", "senamhi", "sernanp", "sis", "ugel",
        "gore", "inpe", "sucamec", "devida", "ceplan", "conadis", "sedapal",
        // Gestión pública
        "tupa", "rof", "mof", "pim", "siaf", "seace", "tdr", "poi", "pei",
        "mype", "mypes", "ong", "onu", "oea", "ocde", "fmi", "bid", "eeuu",
    ).toSet()

    /** Formas especiales que no son todo mayúsculas */
    private val ESPECIALES = mapOf("eeuu" to "EE. UU.", "mypes" to "MYPEs", "essalud" to "EsSalud")

    /** La sigla para lo escrito, o null si no es una sigla */
    fun de(escrito: String, aprendido: Aprendizaje?): String? {
        if (escrito.length < 2 || escrito.length > 12 || !escrito.all { it.isLetter() }) return null
        val k = Texto.normal(escrito)
        ESPECIALES[k]?.let { return it }
        if (k in FIJAS) return k.uppercase()
        // Tuyas: palabras nuevas que escribiste en mayúsculas (2 a 6 letras)
        val propia = aprendido?.conocida(k)?.texto ?: return null
        return propia.takeIf { it.length in 2..6 && it.all { ch -> ch.isUpperCase() } }
    }

    /** Pone la sigla primero en la propuesta y hace que el espacio la escriba sola */
    fun aplicar(escrito: String, p: Propuesta, aprendido: Aprendizaje?, corregir: Boolean): Propuesta {
        val s = de(escrito, aprendido) ?: return p
        if (s == escrito) return p
        val resto = p.opciones.filter { !it.texto.equals(s, ignoreCase = true) && Texto.normal(it.texto) != Texto.normal(s) }
        return Propuesta(listOf(Opcion(s, 0f, true, false)) + resto, if (corregir) s else p.autocorregir, true)
    }
}
