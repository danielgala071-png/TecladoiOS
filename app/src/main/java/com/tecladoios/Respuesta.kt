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
     * Vibración sutil pero con diferencia clara entre teclas (cortita: no se encima al
     * escribir rápido y casi no gasta):
     * - Letras: un golpe de 80 ms a fuerza máxima (se siente bien marcado).
     * - Palabra de la barra: un «tic» un poco más firme (18 ms).
     * - Espacio: doble tic («tic-tic»).
     * - Borrar: tic con un eco muy suave.
     * - Intro: doble tic más marcado.
     * - Mayúscula, 123, emoji: crece de suave a firme.
     */
    private var efPalabra: VibrationEffect? = null

    private fun armar() {
        if (efLetra != null) return
        try {
            val max = if (conAmplitud) 255 else VibrationEffect.DEFAULT_AMPLITUDE
            fun f(x: Int) = if (conAmplitud) x else max
            // Letras con la misma fuerza que espacio, intro y borrar (un solo golpe firme)
            efLetra = VibrationEffect.createOneShot(80L, f(255))   // 80 ms a fuerza máxima
            efPalabra = VibrationEffect.createOneShot(18L, f(190))
            efFuerte = onda(longArrayOf(0, 10, 18), intArrayOf(0, 90, 200))
            efSuave = VibrationEffect.createOneShot(10L, f(120))
            efEspacio = onda(longArrayOf(0, 12, 30, 12), intArrayOf(0, 180, 0, 160))
            efBorrar = onda(longArrayOf(0, 14, 22, 10), intArrayOf(0, 190, 0, 90))
            efIntro = onda(longArrayOf(0, 20, 32, 24), intArrayOf(0, 220, 0, 220))
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
                Tipo.PALABRA -> efPalabra
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
