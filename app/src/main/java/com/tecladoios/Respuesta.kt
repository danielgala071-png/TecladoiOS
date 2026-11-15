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
            // Vibración con textura: letras un «tic» seco; mayúscula, 123, emoji, espacio,
            // intro y borrar cada una con su propio golpe
            if (vib) vibrar(t)
            if (son) {
                cargarSonidos()
                val v = vol / 100f
                val id = when (t) { Tipo.ESPACIO -> ids[1]; Tipo.BORRAR -> ids[2]; Tipo.INTRO -> ids[3]; else -> ids[0] }
                sonidos?.play(id, v, v, 1, 0, 1f)
            }
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

    private var efEspacio: VibrationEffect? = null
    private var efBorrar: VibrationEffect? = null
    private var efIntro: VibrationEffect? = null

    /**
     * Vibración con textura: cada tipo de tecla se siente claramente distinto.
     * - Letras: «tic» seco, fuerte y corto (32 ms): rápido a dos pulgares.
     * - Espacio: «tac-tac», doble golpe.
     * - Borrar: golpe fuerte y un eco más suave (se siente como «quitar»).
     * - Intro: golpe largo y pesado, doble.
     * - Mayúscula, 123, emoji y demás: crece de suave a fuerte («bum»).
     */
    private fun armar() {
        if (efLetra != null) return
        try {
            val max = if (conAmplitud) 255 else VibrationEffect.DEFAULT_AMPLITUDE
            // Letras: golpe fuerte pero corto. Antes duraba 32 ms y, a dos pulgares, una
            // vibración se encimaba con la siguiente (se sentía como si la tecla se pegara).
            efLetra = if (conPrimitivas) VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f).compose()
                else VibrationEffect.createOneShot(18L, max)
            efFuerte = onda(longArrayOf(0, 18, 40), intArrayOf(0, 110, 255))
            efSuave = VibrationEffect.createOneShot(14L, max)
            efEspacio = onda(longArrayOf(0, 20, 34, 18), intArrayOf(0, 255, 0, 220))
            efBorrar = onda(longArrayOf(0, 20, 28, 14), intArrayOf(0, 255, 0, 120))
            efIntro = onda(longArrayOf(0, 34, 36, 40), intArrayOf(0, 255, 0, 255))
        } catch (_: Throwable) { }
    }

    /** Patrón con fuerzas; si el motor no regula la fuerza, solo los tiempos (prendido/apagado) */
    private fun onda(tiempos: LongArray, fuerzas: IntArray): VibrationEffect =
        if (conAmplitud) VibrationEffect.createWaveform(tiempos, fuerzas, -1)
        else VibrationEffect.createWaveform(tiempos, -1)

    private fun vibrar(t: Tipo) {
        val v = vibrador ?: return
        try {
            armar()
            val ef = when (t) {
                Tipo.ESPACIO -> efEspacio
                Tipo.BORRAR -> efBorrar
                Tipo.INTRO -> efIntro
                Tipo.MAYUS, Tipo.FUNCION -> efFuerte
                Tipo.SUAVE -> efSuave
                else -> efLetra
            } ?: return
            v.vibrate(ef)
        } catch (_: Throwable) { }
    }

    fun soltar() {
        manejador.post { sonidos?.release(); sonidos = null }
        hilo.quitSafely()
    }
}
