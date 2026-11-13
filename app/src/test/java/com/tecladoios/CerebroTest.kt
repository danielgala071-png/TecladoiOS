package com.tecladoios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CerebroTest {

    private val palabras = listOf(
        "de", "que", "hola", "ya", "podemos", "casa", "canción", "esta", "está", "holanda",
        "hora", "hoy", "gracias", "mañana", "porque", "vamos", "quiero", "amor", "nos", "vemos",
    )

    private fun geoQwerty(): Geometria {
        val g = Geometria()
        val filas = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        for ((f, fila) in filas.withIndex()) for ((i, ch) in fila.withIndex()) {
            val x = 50f + i * 100f + (if (f == 2) 150f else 0f)
            g.poner(ch, x, 60f + f * 120f)
        }
        g.ancho = 100f; g.alto = 120f
        return g
    }

    private fun motor(geo: Geometria = Geometria()): Motor {
        val dic = Diccionario.armar(palabras, emptyList())
        val apr = Aprendizaje(File.createTempFile("aprendido", ".tsv").apply { delete() })
        val big = HashMap<String, Array<String>>()
        Motor.leerBigramas(sequenceOf("nos: vemos", "te: quiero"), big)
        return Motor(dic, Errores.vacio(), apr, big, geo)
    }

    @Test fun normaliza() {
        assertEquals("cancion", Texto.normal("Canción"))
        assertEquals("ñandu", Texto.normal("Ñandú"))
        assertEquals("Hola", Texto.conMayusculas("hola", "Hila"))
        assertEquals("HOLA", Texto.conMayusculas("hola", "HILA"))
    }

    @Test fun diccionario() {
        val d = Diccionario.armar(palabras, emptyList())
        assertTrue(d.existe("cancion"))
        assertEquals(2, d.formas("esta").size)
        val salida = ArrayList<String>()
        d.completar(d.nodo("ho"), 3) { salida.add(d.palabras[it]) }
        assertEquals("hola", salida.first())
        assertTrue(salida.contains("hora"))
    }

    @Test fun corrigeTeclaVecina() {
        val p = motor().proponer("hila", null, null, emptyList(), true)
        assertEquals("hola", p.autocorregir)
    }

    @Test fun poneTilde() {
        val p = motor().proponer("cancion", null, null, emptyList(), true)
        assertEquals("canción", p.autocorregir)
    }

    @Test fun noTocaPalabrasValidas() {
        val p = motor().proponer("casa", null, null, emptyList(), true)
        assertNull(p.autocorregir)
        assertTrue(p.valida)
    }

    @Test fun separaPalabrasPegadas() {
        val p = motor().proponer("Yanpodemos", null, null, emptyList(), true)
        assertEquals("Ya podemos", p.autocorregir)
    }

    @Test fun completaYPredice() {
        val m = motor()
        val p = m.proponer("gra", null, null, emptyList(), false)
        assertTrue(p.opciones.any { it.texto == "gracias" })
        assertEquals("vemos", m.predecir(listOf("nos"), false).first())
    }

    @Test fun aprendeLoQueEscribes() {
        val m = motor()
        m.aprendido.agregar("chamba")
        val p = m.proponer("chamba", null, null, emptyList(), true)
        assertNull(p.autocorregir)
        assertTrue(p.valida)
    }

    @Test fun deslizar() {
        val geo = geoQwerty()
        val m = motor(geo)
        val d = Deslizar(m)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val letras = "hola"
        for (k in 0 until letras.length - 1) {
            val a = letras[k]; val b = letras[k + 1]
            for (s in 0 until 10) {
                val t = s / 10f
                xs.add(geo.centroX(a) + (geo.centroX(b) - geo.centroX(a)) * t)
                ys.add(geo.centroY(a) + (geo.centroY(b) - geo.centroY(a)) * t)
            }
        }
        xs.add(geo.centroX('a')); ys.add(geo.centroY('a'))
        val res = d.decodificar(xs.toFloatArray(), ys.toFloatArray(), xs.size, emptyList())
        assertNotNull(res.firstOrNull())
        assertEquals("hola", res.first())
    }
}
