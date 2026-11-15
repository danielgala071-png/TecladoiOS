package com.tecladoios

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Al prender el celular (o al actualizar la app) deja el cerebro y el diccionario cargados en
 * segundo plano: la primera vez que abres el teclado ya está todo listo, sin espera.
 */
class AlEncender : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val fin = goAsync()
        val hecho = AtomicBoolean(false)
        val terminar = { if (hecho.compareAndSet(false, true)) try { fin.finish() } catch (_: Throwable) { } }
        // Android da unos segundos; si tarda más, se suelta y la carga sigue sola
        Handler(Looper.getMainLooper()).postDelayed({ terminar() }, 8000L)
        try {
            Cerebro.iniciar(c) { terminar() }
        } catch (e: Throwable) {
            Registro.anotar("al encender", e)
            terminar()
        }
    }
}
