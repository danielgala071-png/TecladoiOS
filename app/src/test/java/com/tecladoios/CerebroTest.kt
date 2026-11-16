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
        "la", "del", "no", "lo", "se", "sé", "sino", "si", "es", "en", "serio", "te", "dije", "los", "a",
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
        return Motor(dic, Errores.vacio(), apr, big, geo).also {
            it.juntas = Juntas.leer(sequenceOf("de la", "lo sé", "es que", "a los", "sino = si no"))
        }
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

    @Test fun errorRepetidoSeSigueCorrigiendo() {
        // "qeu" se escapó dos veces sin corregir: el cerebro no debe darlo por palabra buena
        val m = motor(geoQwerty())
        m.aprendido.usar("qeu", false); m.aprendido.usar("qeu", false)
        assertEquals("que", m.proponer("qeu", null, null, emptyList(), true).autocorregir)
    }

    @Test fun palabraFijadaSeRespeta() {
        // Si la fijaste (o deshiciste la corrección), se queda como la escribiste
        val m = motor(geoQwerty())
        m.aprendido.agregar("qeu", true)
        assertNull(m.proponer("qeu", null, null, emptyList(), true).autocorregir)
    }

    // ---------- Criterio: dejar, corregir o separar ----------

    @Test fun parejaConocidaGanaALaCorreccion() {
        // "dela" está a una letra de "del", pero "de la" va seguida: se separa
        assertEquals("de la", motor().proponer("dela", null, null, emptyList(), true).autocorregir)
        assertEquals("De la", motor().proponer("Dela", null, null, emptyList(), true).autocorregir)
    }

    @Test fun tresPalabrasConSuTilde() {
        assertEquals("no lo sé", motor().proponer("nolose", null, null, emptyList(), true).autocorregir)
    }

    @Test fun pegadaQueTambienExisteSeDeja() {
        // "sino" existe: no se separa sola, pero "si no" queda en la barra
        val p = motor().proponer("sino", null, null, emptyList(), true)
        assertNull(p.autocorregir)
        assertTrue(p.opciones.any { it.texto == "si no" })
    }

    @Test fun separacionDeshechaNoSeRepite() {
        val m = motor()
        m.aprendido.rechazar("dela", "de la")
        assertTrue(m.proponer("dela", null, null, emptyList(), true).autocorregir != "de la")
    }

    @Test fun pegadaRaraDelDiccionarioIgualSeSepara() {
        // Los subtítulos traen "esque" como palabra (rara): igual va "es que"
        val relleno = (0 until 22000).map { i -> "qz" + Integer.toString(i, 26).map { c -> 'a' + Character.digit(c, 26) }.joinToString("") }
        val dic = Diccionario.armar(palabras + relleno + listOf("esque"), emptyList())
        val apr = Aprendizaje(File.createTempFile("aprendido", ".tsv").apply { delete() })
        val m = Motor(dic, Errores.vacio(), apr, HashMap(), Geometria())
        m.juntas = Juntas.leer(sequenceOf("es que"))
        assertEquals("es que", m.proponer("esque", null, null, emptyList(), true).autocorregir)
        // Pero si tú la usas, se respeta
        m.aprendido.usar("esque", true); m.aprendido.usar("esque", true)
        assertNull(m.proponer("esque", null, null, emptyList(), true).autocorregir)
    }
}
