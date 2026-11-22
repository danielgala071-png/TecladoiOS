package com.tecladoios

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Pone una imagen como fondo de pantalla, sin menús (HyperOS no acepta imágenes enviadas desde
 * fuera). Se usa desde ADB:
 *   am start -n com.tecladoios/.PonerFondo -d file:///sdcard/Pictures/fondo.png
 * (opcional: --es donde inicio | bloqueo | ambos; por defecto ambos)
 * o desde «Compartir» / «Establecer como» de cualquier galería.
 * No tiene pantalla: pone el fondo y se cierra.
 */
class PonerFondo : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        @Suppress("DEPRECATION")
        val uri: Uri? = i.data ?: if (i.action == Intent.ACTION_SEND) i.getParcelableExtra(Intent.EXTRA_STREAM) else null
        if (uri == null) { avisar("No llegó ninguna imagen"); finish(); return }
        val donde = when (i.getStringExtra("donde")) {
            "inicio" -> WallpaperManager.FLAG_SYSTEM
            "bloqueo" -> WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        val app = applicationContext
        Thread {
            val r = try {
                app.contentResolver.openInputStream(uri).use { ent ->
                    if (ent == null) "No se pudo abrir la imagen"
                    else { WallpaperManager.getInstance(app).setStream(ent, null, true, donde); null }
                }
            } catch (e: SecurityException) {
                "Falta permiso para leer la imagen"
            } catch (e: Throwable) {
                "No se pudo poner el fondo: ${e.message}"
            }
            runOnUiThread { avisar(r ?: "Fondo de pantalla aplicado"); finish() }
        }.start()
    }

    private fun avisar(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
}
