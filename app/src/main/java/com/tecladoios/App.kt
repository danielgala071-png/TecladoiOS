package com.tecladoios

import android.app.Application
import android.util.Log

/** Guarda el último error para poder verlo en la app y arreglarlo. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Fuentes.iniciar(this)
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            try {
                getSharedPreferences("tecladoios_error", MODE_PRIVATE).edit()
                    .putString("ultimo", Log.getStackTraceString(error))
                    .commit()
            } catch (_: Throwable) { }
            previo?.uncaughtException(hilo, error)
        }
    }
}
