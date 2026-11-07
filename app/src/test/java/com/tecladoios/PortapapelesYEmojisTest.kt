package com.tecladoios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas del historial del portapapeles y de la búsqueda de emojis */
class PortapapelesYEmojisTest {

    private val hora = 1_000_000_000L

    @Test
    fun `lo ultimo copiado va primero y no se repite`() {
        val h = Portapapeles(null)
        h.agregar("hola", hora)
        h.agregar("chau", hora + 1)
        assertEquals(listOf("chau", "hola"), h.elementos(hora + 2).map { it.texto })
        h.agregar("hola", hora + 3)
        assertEquals(listOf("hola", "chau"), h.elementos(hora + 4).map { it.texto })
    }

    @Test
    fun `lo fijado se queda arriba y no se borra`() {
        val h = Portapapeles(null)
        h.agregar("mi correo", hora)
        assertTrue(h.alternarFijado("mi correo"))
        repeat(15) { h.agregar("cosa $it", hora + 1 + it) }
        val lista = h.elementos(hora + 100)
        assertEquals("mi correo", lista.first().texto)
        assertTrue(lista.first().fijado)
        // 10 recientes + el fijado
        assertEquals(Portapapeles.MAX_RECIENTES + 1, lista.size)
        // Lo fijado no vence con el tiempo; lo demás sí
        val manana = hora + Portapapeles.VIGENCIA + 1000
        assertEquals(listOf("mi correo"), h.elementos(manana).map { it.texto })
        assertFalse(h.alternarFijado("mi correo"))
    }

    @Test
    fun `se guarda y se lee igual`() {
        val h = Portapapeles(null)
        h.agregar("línea uno\nlínea dos", hora)
        h.agregar("otra", hora + 1)
        h.alternarFijado("otra")
        val leido = Portapapeles(h.comoTexto())
        val lista = leido.elementos(hora + 2)
        assertEquals(listOf("otra", "línea uno\nlínea dos"), lista.map { it.texto })
        assertTrue(lista[0].fijado)
        assertTrue(Portapapeles("basura sin formato").elementos(hora).isEmpty())
    }

    @Test
    fun `no guarda cosas vacias ni gigantes`() {
        val h = Portapapeles(null)
        assertFalse(h.agregar("   ", hora))
        assertFalse(h.agregar("x".repeat(Portapapeles.MAX_LARGO + 1), hora))
        h.agregar("algo", hora)
        h.quitar("algo")
        assertTrue(h.elementos(hora).isEmpty())
    }

    @Test
    fun `buscar emojis escribiendo`() {
        assertEquals("❤️", EmojiPalabras.buscar("corazon", 6).first())
        assertTrue(EmojiPalabras.buscar("cora", 6).contains("❤️"))
        assertTrue(EmojiPalabras.buscar("Perr", 6).contains("🐶"))
        assertEquals("😂", EmojiPalabras.buscar("jajaja", 6).first())
        assertTrue(EmojiPalabras.buscar("", 6).isEmpty())
        assertTrue(EmojiPalabras.buscar("qxzw", 6).isEmpty())
        assertTrue(EmojiPalabras.buscar("a", 6).size <= 6)
    }
}
