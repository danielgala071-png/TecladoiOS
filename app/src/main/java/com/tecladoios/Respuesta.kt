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

    private fun vibrar(t: Tipo, fuerza: Int) {
        val v = vibrador ?: return
        try {
            val escala = (0.45f + fuerza * 0.11f).coerceAtMost(1f)
            val efecto = if (conPrimitivas) {
                val comp = VibrationEffect.startComposition()
                when (t) {
                    Tipo.SUAVE -> comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, escala * 0.7f)
                    Tipo.LETRA -> comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, escala)
                    else -> {
                        comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                        if (fuerza >= 3) comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, escala, 8)
                    }
                }
                comp.compose()
            } else {
                val ms = when (t) { Tipo.SUAVE -> 6L; Tipo.LETRA -> 8L + fuerza * 3; else -> 12L + fuerza * 4 }
                val amp = when (t) { Tipo.SUAVE -> 90; Tipo.LETRA -> 120 + fuerza * 27; else -> 255 }.coerceAtMost(255)
                VibrationEffect.createOneShot(ms, amp)
            }
            v.vibrate(efecto)
        } catch (_: Throwable) { }
    }

    fun soltar() {
        sonidos?.release(); sonidos = null
    }
}
