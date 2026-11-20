package com.tecladoios

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibración como la del iPhone (un tic seco igual en todas las teclas) y sonido al tocar */
class Respuesta(c: Context) {

    enum class Tipo { LETRA, ESPACIO, BORRAR, MAYUS, INTRO, FUNCION, SUAVE, PALABRA }

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

    /**
     * Vibración y sonido van en un hilo aparte: cada vibración es una llamada al sistema y,
     * hecha en el hilo de las teclas, retrasaba un poquito cada letra (se sentía «pegado»).
     */
    private val hilo = android.os.HandlerThread("respuesta", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val manejador = android.os.Handler(hilo.looper)

    fun tocar(t: Tipo) {
        val cfg = config ?: return
        // Sin sonido (gasta batería); la vibración sí, con su textura por tecla
        val vib = cfg.vibracion; val son = cfg.sonido && !Ahorro.activo; val vol = cfg.volumen
        if (!vib && !son) return
        manejador.post {
            // Vibración como el iPhone: el mismo tic seco en todas las teclas
            if (vib) vibrar(t)
            if (son) {
                cargarSonidos()
                val v = vol / 100f
                val id = when (t) { Tipo.ESPACIO -> ids[1]; Tipo.BORRAR -> ids[2]; Tipo.INTRO -> ids[3]; else -> ids[0] }
                sonidos?.play(id, v, v, 1, 0, 1f)
            }
        }
    }

    /*
     * Vibración como la del iPhone (iOS 16 en adelante): un «tic» seco, cortísimo y nítido,
     * IGUAL en todas las teclas (letras, espacio, borrar, mayúscula, 123, intro). No zumba ni
     * se alarga: es un golpecito del motor lineal, como un clic físico muy pequeño.
     *
     * Orden de preferencia (lo más parecido al Taptic Engine que tenga el celular):
     * 1. Primitiva CLICK del motor (Xiaomi 15T Pro la tiene): el golpe más limpio.
     * 2. Efecto CLICK ya hecho por el fabricante.
     * 3. Un pulso de 8 ms (celulares sin motor lineal).
     * Las palabras de la barra y el cursor con el espacio usan un «tic» aún más leve.
     */
    private val conTick = try {
        vibrador?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) == true
    } catch (_: Throwable) { false }
    private val conEfectos = try {
        vibrador?.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK) ==
            Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    } catch (_: Throwable) { false }
    private val conAmplitud = try { vibrador?.hasAmplitudeControl() == true } catch (_: Throwable) { false }

    // Se arman una sola vez (nada de objetos nuevos en cada tecla)
    private var efTecla: VibrationEffect? = null
    private var efLeve: VibrationEffect? = null

    private fun armar() {
        if (efTecla != null) return
        try {
            when {
                conPrimitivas -> {
                    efTecla = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f).compose()
                    efLeve = if (conTick) VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f).compose()
                    else VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.4f).compose()
                }
                conEfectos -> {
                    efTecla = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    efLeve = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                }
                else -> {
                    efTecla = VibrationEffect.createOneShot(8L, if (conAmplitud) 170 else VibrationEffect.DEFAULT_AMPLITUDE)
                    efLeve = VibrationEffect.createOneShot(5L, if (conAmplitud) 110 else VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
        } catch (_: Throwable) {
            efTecla = VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE)
            efLeve = efTecla
        }
    }

    private fun vibrar(t: Tipo) {
        val v = vibrador ?: return
        try {
            armar()
            val ef = when (t) {
                Tipo.SUAVE, Tipo.PALABRA -> efLeve
                else -> efTecla   // todas las teclas iguales, como el iPhone
            } ?: return
            v.vibrate(ef)
        } catch (_: Throwable) { }
    }

    fun soltar() {
        manejador.post { sonidos?.release(); sonidos = null }
        hilo.quitSafely()
    }
}
