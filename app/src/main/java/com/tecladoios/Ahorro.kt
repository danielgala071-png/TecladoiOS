package com.tecladoios

/**
 * Ahorro de batería (activado de fábrica): quita todo lo que gasta de más.
 * - Sin animaciones: las teclas se marcan al tocar y vuelven al soltar, sin hundirse ni
 *   rebotar; el globo aparece y desaparece al instante; la barra cambia sin rodillo; los
 *   emojis y los paneles cambian directo; sin pulso al enviar ni brillos.
 * - Vibración: un toque corto igual para todas las teclas (el motor trabaja mucho menos).
 * - Sin sonido de teclas.
 * Se apaga en Ajustes → Otros → «Ahorro de batería» para volver a tener todo.
 */
object Ahorro {
    @Volatile var activo = true
}
