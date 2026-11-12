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
    // Un poco más largas y con el mismo frenado suave: todo se siente más fluido, sin
    // hacer esperar al dedo (al presionar la tecla se marca al instante; solo la salida dura)
    const val TOQUE_SALIDA = 210L
    /**
     * Escribiendo rápido la tecla se apaga más ligero: con dos pulgares, varias teclas
     * encendidas a la vez se veían como una mancha. Así cada toque se ve limpio y separado.
     */
    const val TOQUE_SALIDA_RAPIDA = 125L
    /** Onda de luz al tocar: lo que tarda en expandirse y desvanecerse */
    const val ONDA = 430L
    /** Latido de borrar al mantenerlo (uno por cada letra que se va) */
    const val LATIDO = 150L
    /** Enviar con despegue: la etiqueta del intro sale volando y vuelve */
    const val DESPEGUE = 460L
    /** Destello que pasa sobre las teclas al abrir el teclado */
    const val DESTELLO = 620L
    /** Palabra aprendida: lo que se queda a la vista en la barra */
    const val APRENDIDA = 1700L
    /** Destello de la cápsula al autocorregir */
    const val DESTELLO_CORRECCION = 380L
    /** Rebote de un emoji al tocarlo */
    const val REBOTE_EMOJI = 320L
    /** Luz que recorre el borde de arriba al abrir el teclado */
    const val LUZ_BORDE = 900L
    /** La cápsula de la sugerencia que elegiste brilla con el color de la app y se apaga */
    const val ELEGIDA = 320L
    const val LETRAS = 190L
    const val LETRAS_DESLIZAR = 320L
    const val BARRA_APARECE = 240L
    const val BARRA_SE_VA = 180L
    const val CAMBIO = 280L
}
