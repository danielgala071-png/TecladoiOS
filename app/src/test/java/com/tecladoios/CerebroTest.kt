package com.tecladoios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pruebas del cerebro: corren solas en GitHub antes de compilar el APK.
 * Si algo de lo aprendido deja de funcionar, la compilación avisa en vez de instalarse roto.
 */
class CerebroTest {

    private fun archivoNuevo(): File = File(Files.createTempDirectory("cerebro").toFile(), Cerebro.ARCHIVO)

    private fun nuevo(archivo: File = archivoNuevo()): Cerebro = Cerebro(archivo).apply { cargar() }

    @Test
    fun `predice la palabra que sueles escribir despues`() {
        val c = nuevo()
        repeat(3) { c.aprender("la", "casa", "a", "") }
        val sinLetras = c.candidatos("la", "a", "", "", 3)
        assertEquals("casa", sinLetras.first().first)
        val conLetras = c.candidatos("la", "a", "", "ca", 3)
        assertEquals("casa", conLetras.first().first)
        assertTrue(c.candidatos("la", "a", "", "zz", 3).isEmpty())
    }

    @Test
    fun `aprende tu error de siempre despues de reescribirlo dos veces`() {
        val c = nuevo()
        c.aprenderError("tqrjeta", "tarjeta", 1.5f)
        assertNull("con una sola vez todavía no corrige", c.errorTipico("tqrjeta"))
        c.aprenderError("tqrjeta", "tarjeta", 1.5f)
        assertEquals("tarjeta", c.errorTipico("tqrjeta"))
    }

    @Test
    fun `deshacer la correccion la olvida`() {
        val c = nuevo()
        repeat(2) { c.aprenderError("tqrjeta", "tarjeta", 1.5f) }
        c.rechazarError("tqrjeta", "tarjeta")
        assertNull(c.errorTipico("tqrjeta"))
    }

    @Test
    fun `memoria de la conversacion`() {
        val c = nuevo()
        c.aprender("", "Ocucaje", "", "")
        val recientes = c.recientesCon("ocu", 5)
        assertEquals("ocucaje", recientes.first().first)
        assertTrue(c.reciente("Ocucaje") > 0.4f)
        assertEquals(0f, c.reciente("lima"), 0.0001f)
        // Las palabras cortitas no se guardan (no son el tema de la conversación)
        c.aprender("", "sol", "", "")
        assertTrue(c.recientesCon("so", 5).isEmpty())
        // Si la borras enseguida, se olvida
        c.desaprender("", "Ocucaje", "", "")
        assertEquals(0f, c.reciente("ocucaje"), 0.0001f)
    }

    @Test
    fun `la autocorreccion se calibra sola`() {
        assertEquals(1f, nuevo().audacia(), 0.0001f)

        val prudente = nuevo()
        repeat(60) {
            prudente.correccionAplicada()
            prudente.correccionDeshecha()
        }
        assertTrue("si la deshaces seguido se vuelve prudente", prudente.audacia() < 0.95f)

        val segura = nuevo()
        repeat(60) { segura.correccionAplicada() }
        assertTrue("si nunca la deshaces se atreve un poco más", segura.audacia() > 1.05f)
    }

    @Test
    fun `guarda y vuelve a leer todo lo aprendido`() {
        val archivo = archivoNuevo()
        val c = nuevo(archivo)
        repeat(3) { c.aprender("la", "casa", "a", "") }
        repeat(2) { c.aprenderError("qje", "que", 1.5f) }
        repeat(60) { c.correccionAplicada() }
        c.escribir(c.instantanea())

        val leido = nuevo(archivo)
        assertEquals("que", leido.errorTipico("qje"))
        assertEquals("casa", leido.candidatos("la", "a", "", "", 3).first().first)
        assertEquals(c.audacia(), leido.audacia(), 0.001f)
    }

    @Test
    fun `un archivo danado no lo rompe`() {
        val archivo = archivoNuevo()
        archivo.writeText("tecladoios-cerebro-1\n#c\nbasura sin tabuladores\n#e\nmal\n#a\ntasa\tno-es-numero\n")
        val c = nuevo(archivo)
        assertTrue(c.listo)
        assertNull(c.errorTipico("mal"))
        assertEquals(1f, c.audacia(), 0.0001f)
    }

    @Test
    fun `olvidar una palabra la saca de todo`() {
        val c = nuevo()
        repeat(3) { c.aprender("la", "casa", "a", "") }
        c.olvidar("casa")
        assertTrue(c.candidatos("la", "a", "", "", 3).isEmpty())
    }

    @Test
    fun `resumen para ajustes`() {
        val c = nuevo()
        repeat(2) { c.aprenderError("qje", "que", 1.5f) }
        val r = Cerebro.resumen(c.instantanea())
        assertNotNull(r)
        assertTrue(r!!.contains("errores tuyos"))
        assertNull(Cerebro.resumen(null))
        assertNull(Cerebro.resumen("otra cosa"))
    }

    @Test
    fun `reconoce palabras parecidas para la reescritura`() {
        assertTrue(Cerebro.parecidas("tqrjeta", "tarjeta"))
        assertTrue(Cerebro.parecidas("qje", "que"))
        assertTrue(Cerebro.parecidas("nesesito", "necesito"))
        assertFalse(Cerebro.parecidas("casa", "perro"))
        assertFalse(Cerebro.parecidas("hola", "holaaaaa"))
    }

    @Test
    fun `sin tildes`() {
        assertEquals("cancion", Cerebro.sinTildes("canción"))
        assertEquals("ano", Cerebro.sinTildes("año"))
    }
}
