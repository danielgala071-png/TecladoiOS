package com.tecladoios

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibración y sonido al tocar, como el iPhone: un «tic» seco y corto, igual en todas las
 * teclas, a fuerza máxima. Va en un hilo aparte para no retrasar las teclas.
 */
class Respuesta(c: Context) {

    enum class Tipo { LETRA, ESPACIO, BORRAR, INTRO, FUNCION }

    private val app = c.applicationContext

    private val vibrador: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else c.getSystemService(Vibrator::class.java)
    } catch (_: Throwable) { null }

    var config: Config? = null

    private val hilo = HandlerThread("respuesta", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val manejador = Handler(hilo.looper)

    private var efecto: VibrationEffect? = null
    private var sonidos: SoundPool? = null
    private val ids = IntArray(4)

    private fun armar(): VibrationEffect? {
        efecto?.let { return it }
        val v = vibrador ?: return null
        val e = try {
            when {
                // TICK: el golpe más corto y seco del motor, sin cola
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) ->
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f).compose()
                v.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES ->
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                else -> VibrationEffect.createOneShot(6L, if (v.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE)
            }
        } catch (_: Throwable) {
            VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        efecto = e
        return e
    }

    private fun cargarSonidos(): SoundPool {
        sonidos?.let { return it }
        val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        ids[0] = sp.load(app, R.raw.tecla, 1)
        ids[1] = sp.load(app, R.raw.espacio, 1)
        ids[2] = sp.load(app, R.raw.borrar, 1)
        ids[3] = sp.load(app, R.raw.intro, 1)
        sonidos = sp
        return sp
    }

    fun tocar(t: Tipo, vista: android.view.View? = null) {
        val cfg = config ?: return
        if (!cfg.vibracion && !cfg.sonido) return
        // Toque firme: nuestro propio «clic» del motor a toda fuerza (el del sistema en Xiaomi es
        // muy suave). Letras: clic seco; espacio, borrar, intro y demás: clic pesado.
        manejador.post {
            if (cfg.vibracion) try {
                val e = if (t == Tipo.LETRA) firmeLetra() else firmeFuncion()
                e?.let { vibrador?.vibrate(it) }
            } catch (_: Throwable) { }
            if (cfg.sonido) try {
                val sp = cargarSonidos()
                val id = when (t) { Tipo.ESPACIO -> ids[1]; Tipo.BORRAR -> ids[2]; Tipo.INTRO -> ids[3]; else -> ids[0] }
                sp.play(id, 0.5f, 0.5f, 1, 0, 1f)
            } catch (_: Throwable) { }
        }
    }

    private var efLetra: VibrationEffect? = null
    private var efFuncion: VibrationEffect? = null

    /** Clic seco a fuerza completa (sin cola), o el de siempre si el motor no lo tiene */
    private fun firmeLetra(): VibrationEffect? {
        efLetra?.let { return it }
        val v = vibrador ?: return null
        val e = try {
            if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK))
                VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f).compose()
            else armar()
        } catch (_: Throwable) { armar() }
        efLetra = e
        return e
    }

    /** Clic pesado para las teclas grandes (espacio, borrar, intro, mayúscula, 123, emoji) */
    private fun firmeFuncion(): VibrationEffect? {
        efFuncion?.let { return it }
        val v = vibrador ?: return null
        val e = try {
            when {
                v.areAllEffectsSupported(VibrationEffect.EFFECT_HEAVY_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES ->
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                else -> firmeLetra()
            }
        } catch (_: Throwable) { firmeLetra() }
        efFuncion = e
        return e
    }

    private var efectoSuave: VibrationEffect? = null

    /** Autocorrección: un segundo «tic» muy suave justo después del espacio, para notarla sin mirar */
    fun correccion() {
        val cfg = config ?: return
        if (!cfg.vibracion) return
        manejador.postDelayed({
            try {
                val v = vibrador ?: return@postDelayed
                val e = efectoSuave ?: (if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK))
                    VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f).compose()
                else armar()).also { efectoSuave = it }
                e?.let { v.vibrate(it) }
            } catch (_: Throwable) { }
        }, 75L)
    }

    /** Libera los sonidos (si están apagados no ocupan memoria) */
    fun soltarSonidos() { manejador.post { sonidos?.release(); sonidos = null } }

    fun soltar() {
        manejador.post { sonidos?.release(); sonidos = null }
        hilo.quitSafely()
    }
}
