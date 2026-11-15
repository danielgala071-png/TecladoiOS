package com.tecladoios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Arma el diccionario al compilar y lo deja listo dentro del APK (assets): el teclado ya no
 * tiene que armarlo la primera vez después de instalar o actualizar. Solo corre cuando el
 * workflow lo pide (GENERAR_DICCIONARIO=1); en las pruebas normales no hace nada.
 */
class GenerarDiccionarioTest {
    @Test fun generar() {
        if (System.getenv("GENERAR_DICCIONARIO") != "1") return
        val assets = File("src/main/assets")
        val dic = ArmarDiccionario.armar { nombre ->
            val f = File(assets, nombre)
            if (f.exists()) f.bufferedReader(Charsets.UTF_8).useLines { ArmarDiccionario.utiles(it) } else emptyList()
        }
        val salida = File(assets, ArmarDiccionario.ARCHIVO_LISTO)
        dic.guardar(salida, Diccionario.PRECOMPILADO)
        // Se comprueba que se lee bien antes de meterlo al APK
        val leido = Diccionario.leer(salida, Diccionario.PRECOMPILADO)
        assertNotNull(leido)
        assertEquals(dic.total, leido!!.total)
        println("Diccionario listo: ${dic.total} palabras, ${salida.length() / 1024} KB")
    }
}
