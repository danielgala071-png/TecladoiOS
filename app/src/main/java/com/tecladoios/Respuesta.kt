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

    enum class Tipo { LETRA, ESPACIO, BORRAR, INTRO, FUNCION, SUAVE }

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
        if (cfg.vibracion) vibrar(t, cfg.fuerza)
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
    private var fuerzaArmada = -1
    private var efLetra: VibrationEffect? = null
    private var efFuerte: VibrationEffect? = null
    private var efSuave: VibrationEffect? = null

    private fun armar(fuerza: Int) {
        if (fuerza == fuerzaArmada) return
        fuerzaArmada = fuerza
        val escala = (0.62f + fuerza * 0.076f).coerceAtMost(1f)   // 1 → 0.70 · 5 → 1.0
        try {
            if (conPrimitivas) {
                efLetra = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, escala).compose()
                efFuerte = if (conEfectos) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    else VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f).compose()
                efSuave = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, escala * 0.6f).compose()
            } else if (conEfectos) {
                efLetra = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                efFuerte = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                efSuave = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                // Motor simple: pulso muy corto a toda fuerza (seco, sin zumbido)
                val amp = if (conAmplitud) (150 + fuerza * 21).coerceAtMost(255) else VibrationEffect.DEFAULT_AMPLITUDE
                efLetra = VibrationEffect.createOneShot(7L + fuerza, amp)
                efFuerte = VibrationEffect.createOneShot(11L + fuerza, if (conAmplitud) 255 else VibrationEffect.DEFAULT_AMPLITUDE)
                efSuave = VibrationEffect.createOneShot(5L, if (conAmplitud) 90 else VibrationEffect.DEFAULT_AMPLITUDE)
            }
        } catch (_: Throwable) { }
    }

    private fun vibrar(t: Tipo, fuerza: Int) {
        val v = vibrador ?: return
        try {
            armar(fuerza)
            val ef = when (t) {
                Tipo.LETRA -> efLetra
                Tipo.SUAVE -> efSuave
                else -> efFuerte
            } ?: return
            v.vibrate(ef)
        } catch (_: Throwable) { }
    }

    fun soltar() {
        sonidos?.release(); sonidos = null
    }
}
