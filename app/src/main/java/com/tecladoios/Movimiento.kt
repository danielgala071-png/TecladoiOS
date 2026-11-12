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

    /**
     * Resorte fino, como los de iOS: llega, se pasa un pelito y se asienta. Para la burbuja de
     * la tecla: nace con cuerpo, sin parecer de goma.
     */
    val POP = PathInterpolator(0.25f, 1.22f, 0.45f, 1f)

    // Duraciones en milisegundos
    // Un poco más largas y con el mismo frenado suave: todo se siente más fluido, sin
    // hacer esperar al dedo (al presionar la tecla se marca al instante; solo la salida dura)
    const val TOQUE_SALIDA = 170L
    /**
     * Escribiendo rápido la tecla se apaga más ligero: con dos pulgares, varias teclas
     * encendidas a la vez se veían como una mancha. Así cada toque se ve limpio y separado.
     */
    const val TOQUE_SALIDA_RAPIDA = 85L
    /** Latido de borrar al mantenerlo (uno por cada letra que se va) */
    const val LATIDO = 150L
    /** Enviar con despegue: la etiqueta del intro sale volando y vuelve */
    const val DESPEGUE = 460L
    /** Rebote de un emoji al tocarlo */
    const val REBOTE_EMOJI = 320L

    /** La cápsula de la sugerencia que elegiste brilla con el color de la app y se apaga */
    const val ELEGIDA = 320L
    const val LETRAS = 190L
    /** Cambio de teclado (letras ↔ números): las filas caen en cascada */
    const val LETRAS_DESLIZAR = 340L
    const val BARRA_APARECE = 240L
    const val BARRA_SE_VA = 180L
    const val CAMBIO = 280L

    /** Burbuja de la tecla: nace con un resorte cortito y se recoge en su tecla */
    const val BURBUJA_ENTRA = 125L
    const val BURBUJA_SALE = 130L
    /** Burbuja escribiendo rápido a dos dedos: aparece en casi nada */
    const val BURBUJA_RAPIDA = 70L
    /** La cápsula de la corrección se desliza de una casilla a otra (no salta) */
    const val CAPSULA = 230L
    /** Mayúsculas ↔ minúsculas: las letras se transforman con un fundido y un leve crecer */
    const val MAYUS = 160L
    /** Destello del filo al enviar */
    const val DESTELLO = 520L
}
