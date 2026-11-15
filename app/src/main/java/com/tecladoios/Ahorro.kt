package com.tecladoios

/**
 * Teclado sin gasto de batería (fijo, sin interruptor): se quitaron todas las animaciones y
 * efectos que hacían trabajar la pantalla y el procesador al escribir.
 * - Teclas: se marcan al tocar y vuelven al soltar, sin hundirse ni rebotar.
 * - Globo: aparece ya formado y se va al instante.
 * - Barra: las palabras cambian directo (sin rodillo, destellos ni saltos).
 * - Paneles y emojis: cambian directo, sin transiciones.
 * - Sin pulso al enviar, sin brillos, sin animación de 123 ni de mayúsculas.
 * - Sin sonido; vibración: un toque corto igual para todas las teclas.
 */
object Ahorro {
    const val activo = true
}
