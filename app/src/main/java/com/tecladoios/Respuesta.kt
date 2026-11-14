package com.tecladoios

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibración firme y sonido al tocar cada tecla */
class Respuesta(c: Context) {

    enum class Tipo { LETRA, ESPACIO, BORRAR, MAYUS, INTRO, FUNCION, SUAVE }

    private val vibrador: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else c.getSystemService(Vibrator::class.java)
    } catch (_: Throwable) { null }

    private val conPrimitivas = try {
        vibrador?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true
    } catch (_: Throwable) { false }

    private var sonidos: SoundPool? = null
    private val ids = IntArray(4)
    private val app = c.applicationContext

    var config: Config? = null

    private fun cargarSonidos() {
        if (sonidos != null) return
        val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        ids[0] = sp.load(app, R.raw.tecla, 1)
        ids[1] = sp.load(app, R.raw.espacio, 1)
        ids[2] = sp.load(app, R.raw.borrar, 1)
        ids[3] = sp.load(app, R.raw.intro, 1)
        sonidos = sp
    }

    fun tocar(t: Tipo) {
        val cfg = config ?: return
        if (cfg.vibracion) vibrar(t)
        if (cfg.sonido) {
            cargarSonidos()
            val v = cfg.volumen / 100f
            val id = when (t) { Tipo.ESPACIO -> ids[1]; Tipo.BORRAR -> ids[2]; Tipo.INTRO -> ids[3]; else -> ids[0] }
            sonidos?.play(id, v, v, 1, 0, 1f)
        }
    }

    // Efectos ya hechos por el fabricante (golpe seco y corto, como el motor del iPhone)
    private val conEfectos = try {
        vibrador?.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK) ==
            Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    } catch (_: Throwable) { false }
    private val conAmplitud = try { vibrador?.hasAmplitudeControl() == true } catch (_: Throwable) { false }

    // Los efectos se arman una sola vez (nada de objetos nuevos en cada tecla)
    private var efLetra: VibrationEffect? = null
    private var efFuerte: VibrationEffect? = null
    private var efSuave: VibrationEffect? = null

    /**
     * Vibración al máximo del motor del celular y larga en cada tecla:
     * - Letras y demás teclas: fuerza máxima, 50 ms (parecido al largo de Gboard).
     * - Espacio, mayúscula y borrar: fuerza máxima, 55 ms (apenas más largas).
     * Siempre es un pulso propio con largo exacto (los efectos del fabricante son muy cortos).
     */
    private fun armar() {
        if (efLetra != null) return
        try {
            val max = if (conAmplitud) 255 else VibrationEffect.DEFAULT_AMPLITUDE
            efLetra = VibrationEffect.createOneShot(50L, max)
            efFuerte = VibrationEffect.createOneShot(55L, max)
            efSuave = VibrationEffect.createOneShot(14L, max)
        } catch (_: Throwable) { }
    }

    private fun vibrar(t: Tipo) {
        val v = vibrador ?: return
        try {
            armar()
            val ef = when (t) {
                Tipo.ESPACIO, Tipo.BORRAR, Tipo.MAYUS -> efFuerte
                Tipo.SUAVE -> efSuave
                else -> efLetra
            } ?: return
            v.vibrate(ef)
        } catch (_: Throwable) { }
    }

    fun soltar() {
        sonidos?.release(); sonidos = null
    }
}
