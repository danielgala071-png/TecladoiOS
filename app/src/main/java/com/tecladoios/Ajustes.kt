package com.tecladoios

import android.content.Context
import android.content.SharedPreferences

/** Los mismos ajustes del teclado del iPhone (Ajustes → General → Teclado) */
object Ajustes {
    const val ARCHIVO = "teclado"

    const val MAYUS_AUTO = "mayus_auto"
    const val AUTOCORRECCION = "autocorreccion"
    const val PREDICTIVO = "predictivo"
    const val BLOQ_MAYUS = "bloq_mayus"
    const val VISTA_PREVIA = "vista_previa"
    const val ATAJO_PUNTO = "atajo_punto"
    const val VIBRACION = "vibracion"
    const val SONIDO = "sonido"
    /** Tamaño del teclado en % (alto de las teclas) */
    const val TAMANO = "tamano"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
}

class Config(p: SharedPreferences) {
    val mayusAuto = p.getBoolean(Ajustes.MAYUS_AUTO, true)
    val autocorreccion = p.getBoolean(Ajustes.AUTOCORRECCION, true)
    val predictivo = p.getBoolean(Ajustes.PREDICTIVO, true)
    val bloqMayus = p.getBoolean(Ajustes.BLOQ_MAYUS, true)
    val vistaPrevia = p.getBoolean(Ajustes.VISTA_PREVIA, true)
    val atajoPunto = p.getBoolean(Ajustes.ATAJO_PUNTO, true)
    val vibracion = p.getBoolean(Ajustes.VIBRACION, true)
    val sonido = p.getBoolean(Ajustes.SONIDO, false)
    val tamano = p.getInt(Ajustes.TAMANO, 100).coerceIn(80, 130)
}
