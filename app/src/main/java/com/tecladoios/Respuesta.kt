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
     * Una sola vibración, siempre fuerte y un poco larga (no un toquecito corto):
     * - Letras y demás teclas: golpe fuerte (100) de ~26 ms.
     * - Espacio, mayúscula y borrar: más fuerte (120) y algo más largo, ~34 ms.
     * Si el motor permite elegir la fuerza, se usa un pulso propio con largo y fuerza exactos;
     * si no, el golpe pesado del fabricante.
     */
    private fun armar() {
        if (efLetra != null) return
        try {
            if (conAmplitud) {
                efLetra = VibrationEffect.createOneShot(26L, 213)
                efFuerte = VibrationEffect.createOneShot(34L, 255)
                efSuave = VibrationEffect.createOneShot(10L, 140)
            } else if (conPrimitivas) {
                efLetra = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 6).compose()
                efFuerte = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 8).compose()
                efSuave = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f).compose()
            } else if (conEfectos) {
                efLetra = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                efFuerte = VibrationEffect.createOneShot(34L, VibrationEffect.DEFAULT_AMPLITUDE)
                efSuave = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                efLetra = VibrationEffect.createOneShot(26L, VibrationEffect.DEFAULT_AMPLITUDE)
                efFuerte = VibrationEffect.createOneShot(34L, VibrationEffect.DEFAULT_AMPLITUDE)
                efSuave = VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
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
