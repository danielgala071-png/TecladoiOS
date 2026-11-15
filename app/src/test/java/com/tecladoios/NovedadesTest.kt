package com.tecladoios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pruebas de lo agregado: siglas, apps, confusiones, zona caliente, contexto y frases */
class NovedadesTest {

    private val palabras = listOf(
        "de", "que", "hola", "ya", "casa", "cosa", "nos", "vemos", "mañana", "temprano",
        "me", "voy", "a", "dormir", "trabajar", "hora", "hoy",
    )

    private fun motor(lineas: List<String> = emptyList()): Motor {
        val dic = Diccionario.armar(palabras + NombresApps.LISTA, emptyList())
        val apr = Aprendizaje(File.createTempFile("aprendido", ".tsv").apply { delete() })
        val big = HashMap<String, Array<String>>()
        Motor.leerBigramas(lineas.asSequence(), big)
        return Motor(dic, Errores.vacio(), apr, big, Geometria())
    }

    @Test fun siglasYApps() {
        assertEquals("DNI", Siglas.de("dni", null))
        assertEquals("SUNAT", Siglas.de("Sunat", null))
        assertEquals("WhatsApp", Siglas.de("whatsapp", null))
        assertEquals("EE. UU.", Siglas.de("eeuu", null))
        assertNull(Siglas.de("casa", null))
        val p = motor().proponer("dni", null, null, emptyList(), true)
        assertEquals("DNI", p.autocorregir)
    }

    @Test fun confusionesBajanElCosto() {
        val apr = Aprendizaje(File.createTempFile("aprendido", ".tsv").apply { delete() })
        apr.confundir("cosa", "casa")
        val i = Texto.indice('o') * 27 + Texto.indice('a')
        assertTrue(apr.descuento[i] > 0f)
        // Letras cambiadas de lugar no cuentan
        apr.confundir("csoa", "cosa")
        assertEquals(1, apr.confusiones.size)
    }

    @Test fun zonaCaliente() {
        val p = motor().letrasProbables("ho")
        assertTrue(p.isNotEmpty())
        assertTrue(p.keys.any { it == 'l' || it == 'r' || it == 'y' })
        assertTrue(motor().letrasProbables("").isEmpty())
    }

    @Test fun contextoDeTresPalabrasYFrase() {
        val m = motor(listOf("me voy a: dormir, trabajar", "nos: vemos", "nos vemos: mañana", "vemos mañana: temprano"))
        assertEquals("dormir", m.predecir(listOf("me", "voy", "a"), false).first())
        // Con dos palabras de contexto el cerebro está seguro de las dos siguientes
        assertEquals("mañana temprano", m.frase(listOf("nos", "vemos"), false))
        assertNull(m.frase(emptyList(), false))
    }
}
