package com.tecladoios

import android.content.Context
import android.content.SharedPreferences

/**
 * Todos los ajustes del teclado en un solo lugar. El servicio lee una "foto" (Config) y la
 * vuelve a leer solo cuando algo cambia, así cada tecla no consulta las preferencias.
 */
object Ajustes {
    const val ARCHIVO = "tecladoios_v8"

    // Corrección
    const val AUTOCORRECCION = "autocorreccion"
    const val MAYUS_AUTO = "mayus_auto"
    const val PUNTO_DOBLE_ESPACIO = "punto_doble_espacio"
    const val SUGERENCIAS = "sugerencias"
    const val PREDICCION = "prediccion"
    const val APRENDER = "aprender"
    const val CONTACTOS = "contactos"
    const val SEPARAR_PEGADAS = "separar_pegadas"
    const val ESPACIO_AUTO = "espacio_auto"

    // Gestos
    const val DESLIZAR = "deslizar_escribir"
    const val ESTELA = "estela"
    const val BORRAR_DESLIZANDO = "borrar_deslizando"
    const val CURSOR_ESPACIO = "cursor_espacio"

    // Teclado
    const val FILA_NUMEROS = "fila_numeros"
    const val PISTAS = "pistas_simbolos"
    const val VISTA_PREVIA = "vista_previa"
    const val UNA_MANO = "una_mano"          // 0 apagado, 1 izquierda, 2 derecha
    const val ALTURA = "altura"              // 80..130 (%)
    const val MARGEN_ABAJO = "margen_abajo"  // dp de espacio debajo del teclado
    const val RETARDO_LARGA = "retardo_larga"// ms
    const val BORDES_APP = "bordes_app"
    const val BARRA_HERRAMIENTAS = "barra_herramientas"

    // Sonido y vibración
    const val SONIDO = "sonido"
    const val VOLUMEN = "volumen"            // 0..100
    const val VIBRACION = "vibracion"
    const val FUERZA = "fuerza_vibracion"    // 1..5

    // Portapapeles y otros
    const val PORTAPAPELES = "portapapeles"
    const val CAPTURAS = "capturas"
    const val ICONO_OCULTO = "icono_oculto"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
}

/** Foto de los ajustes, de solo lectura */
class Config(p: SharedPreferences) {
    val autocorreccion = p.getBoolean(Ajustes.AUTOCORRECCION, true)
    val mayusAuto = p.getBoolean(Ajustes.MAYUS_AUTO, true)
    val puntoDobleEspacio = p.getBoolean(Ajustes.PUNTO_DOBLE_ESPACIO, true)
    val sugerencias = p.getBoolean(Ajustes.SUGERENCIAS, true)
    val prediccion = p.getBoolean(Ajustes.PREDICCION, true)
    val aprender = p.getBoolean(Ajustes.APRENDER, true)
    val contactos = p.getBoolean(Ajustes.CONTACTOS, false)
    val separarPegadas = p.getBoolean(Ajustes.SEPARAR_PEGADAS, true)
    val espacioAuto = p.getBoolean(Ajustes.ESPACIO_AUTO, true)

    val deslizar = p.getBoolean(Ajustes.DESLIZAR, true)
    val estela = p.getBoolean(Ajustes.ESTELA, true)
    val borrarDeslizando = p.getBoolean(Ajustes.BORRAR_DESLIZANDO, true)
    val cursorEspacio = p.getBoolean(Ajustes.CURSOR_ESPACIO, true)

    val filaNumeros = p.getBoolean(Ajustes.FILA_NUMEROS, false)
    val pistas = p.getBoolean(Ajustes.PISTAS, true)
    val vistaPrevia = p.getBoolean(Ajustes.VISTA_PREVIA, true)
    val unaMano = p.getInt(Ajustes.UNA_MANO, 0)
    val altura = p.getInt(Ajustes.ALTURA, 100).coerceIn(75, 135)
    val margenAbajo = p.getInt(Ajustes.MARGEN_ABAJO, 0).coerceIn(0, 120)
    val retardoLarga = p.getInt(Ajustes.RETARDO_LARGA, 320).coerceIn(150, 800)
    val bordesApp = p.getBoolean(Ajustes.BORDES_APP, true)
    val barraHerramientas = p.getBoolean(Ajustes.BARRA_HERRAMIENTAS, true)

    val sonido = p.getBoolean(Ajustes.SONIDO, false)
    val volumen = p.getInt(Ajustes.VOLUMEN, 40).coerceIn(0, 100)
    val vibracion = p.getBoolean(Ajustes.VIBRACION, true)
    val fuerza = p.getInt(Ajustes.FUERZA, 5).coerceIn(1, 5)

    val portapapeles = p.getBoolean(Ajustes.PORTAPAPELES, true)
    val capturas = p.getBoolean(Ajustes.CAPTURAS, false)
}
