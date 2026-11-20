package com.tecladoios

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import java.io.File

/**
 * Arranque de la app. La primera vez que corre esta versión borra todo lo que guardaban las
 * versiones anteriores (palabras aprendidas, errores, desvíos de toque, ajustes viejos): el
 * teclado empieza de cero, como recién instalado.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val p = Ajustes.prefs(this)
        if (!p.getBoolean(LIMPIO, false)) {
            try {
                File(dataDir, "shared_prefs").listFiles()?.forEach { f ->
                    val nombre = f.name.removeSuffix(".xml")
                    if (nombre != Ajustes.ARCHIVO) { deleteSharedPreferences(nombre); f.delete() }
                }
                filesDir.listFiles()?.forEach { it.deleteRecursively() }
                noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                // Si antes se había ocultado el ícono, vuelve a aparecer
                packageManager.setComponentEnabledSetting(
                    ComponentName(this, "com.tecladoios.Lanzador"),
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            } catch (_: Throwable) { }
            p.edit().clear().putBoolean(LIMPIO, true).commit()
        }
    }

    companion object { private const val LIMPIO = "limpio_desde_cero" }
}
