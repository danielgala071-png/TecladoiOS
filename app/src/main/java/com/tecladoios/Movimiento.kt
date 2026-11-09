package com.tecladoios

import android.view.animation.PathInterpolator

/**
 * Curvas de movimiento compartidas por todo el teclado, como las de iOS: arrancan rápido y
 * llegan frenando de a poco (nada se detiene en seco ni rebota de más). Usar las mismas
 * curvas en todas partes hace que el teclado se sienta como una sola pieza.
 */
object Movimiento {
    /** Para lo que aparece o se apaga: rápido al inicio, frenado larguito y suave al final */
    val SUAVE = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** Para lo que se mueve de un lado a otro (cambio de teclado): acelera y frena parejo */
    val DESLIZAR = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /** Rebote apenas perceptible (vista previa de la tecla, acentos): vivo pero sin exagerar */
    val REBOTE = PathInterpolator(0.34f, 1.36f, 0.64f, 1f)

    // Duraciones en milisegundos
    const val TOQUE_SALIDA = 210L
    const val LETRAS = 170L
    const val LETRAS_DESLIZAR = 260L
    const val BARRA_APARECE = 200L
    const val BARRA_SE_VA = 160L
    const val CAMBIO = 260L
}
