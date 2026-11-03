package com.tecladoios

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethod
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

class TecladoService : InputMethodService(),
    KeyboardView.Acciones, EmojiPanel.Acciones, SuggestionBar.Acciones {

    companion object {
        /** Cuánto texto antes del cursor se guarda en la copia local */
        private const val MAX_ESPEJO = 300
    }

    private var barra: SuggestionBar? = null
    /** true cuando ya escribiste algo: la barra no aparece solo por abrir el teclado o mover el cursor */
    private var escribiendo = false
    private var ultimaEscritura = 0L
    /** El teclado negro (sin la barra flotante de arriba) */
    private var panel: View? = null
    private var teclado: KeyboardView? = null
    private var emojis: EmojiPanel? = null
    private var tema: Tema? = null
    private var firmaTamano = ""

    // Color de acento de cada app (sale de su ícono), para teñir el teclado con la app
    private val coloresApps = HashMap<String, Int?>()
    private var appActual: String? = null
    private lateinit var diccionario: Diccionario
    private var vibrador: Vibrator? = null
    private var audio: AudioManager? = null

    private var vibrar = true
    private var sonido = false
    private var sugerenciasActivas = true
    private var autocorreccionActiva = true
    /** Revisar ortografía: calcular correcciones (se aplican solas solo con Autocorrección) */
    private var revisarActiva = true
    private var enLineaActiva = false
    private var puntuacionActiva = true
    private var borrarDeslizadoActivo = true
    private var mayusPermitida = true
    private var puntoDoble = true
    private var atajosActivos = true
    private var atajos: Map<String, String> = emptyMap()

    private var toqueFino: Boolean? = null
    private var ultimaCorreccion: Pair<String, String>? = null
    private var ultimoEspacio = 0L
    /** Lo último que se aprendió (para deshacerlo si borras enseguida) */
    private data class Aprendida(val previa2: String, val previa: String, val palabra: String, val texto: String)
    private var ultimaAprendida: Aprendida? = null
    /** true justo después de elegir una sugerencia (el espacio lo puso el teclado) */
    private var espacioAutomatico = false

    /**
     * Dónde cayó el dedo en cada letra de la palabra actual: [x, fila, letra].
     * Con esto la autocorrección y las sugerencias son precisas como en Gboard,
     * sobre todo al escribir rápido con dos dedos.
     */
    private val toques = ArrayList<FloatArray>()

    /** La última palabra escrita deslizando el dedo (y sus alternativas para la barra) */
    private class Deslizado(val insertado: String, val alternativas: List<Sugerencia?>)
    private var ultimoDeslizado: Deslizado? = null

    // Dictado por voz (botón de micrófono)
    private var reconocedor: SpeechRecognizer? = null
    private var dictando = false

    /**
     * La barra y las mayúsculas se actualizan una sola vez por cuadro, aunque lleguen
     * varias letras juntas (escribir rápido con dos dedos). Así el teclado no se traba.
     */
    private val manejador = Handler(Looper.getMainLooper())
    private val tareaActualizar = Runnable { actualizarAhora() }

    /**
     * Las sugerencias y la autocorrección se calculan en un hilo aparte ("motor").
     * El hilo principal solo dibuja y escribe: así cada toque responde al instante,
     * aunque escribas muy rápido con dos dedos.
     */
    private val hiloMotor = HandlerThread("motor-sugerencias", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val motor = Handler(hiloMotor.looper)

    /** La vibración y el sonido van en su propio hilo: el toque nunca espera al motor de vibración */
    private val hiloRetro = HandlerThread("retro", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val retroManejador = Handler(hiloRetro.looper)

    /** Corrección ya calculada para la palabra que estás escribiendo (lista para el espacio) */
    private class Calculada(
        val palabra: String, val previa: String, val previa2: String,
        val correccion: String?, val completa: String?,
    )
    @Volatile private var calculada: Calculada? = null

    /**
     * Copia local del texto antes del cursor. El teclado lleva la cuenta por su cuenta y
     * solo vuelve a leer si tú mueves el cursor o la app cambia el texto.
     */
    private val espejo = StringBuilder()
    private var espejoValido = false
    /** true si la copia tiene todo el texto desde el inicio del campo */
    private var espejoCompleto = false
    private var selIni = -1
    private var selFin = -1
    /** Posiciones del cursor que esperamos que la app nos confirme (por lo que escribimos) */
    private val pendientes = ArrayDeque<Int>()

    override fun onCreate() {
        super.onCreate()
        Fuentes.iniciar(this)
        diccionario = Diccionario(this)
        // Revisa en segundo plano qué emojis puede dibujar el celular (el panel abre al instante)
        Thread({ try { CATEGORIAS.forEach { it.emojis } } catch (_: Throwable) { } }, "emojis").apply {
            isDaemon = true
            start()
        }
        vibrador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onCreateInputView(): View = construir()

    /**
     * Al entrar a una conversación, muchas apps piden abrir el teclado solas. Eso llega sin la
     * marca de "lo pidió el usuario" (SHOW_EXPLICIT): en ese caso no se abre. Se abre cuando
     * tocas donde escribir.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (!configChange && (flags and InputMethod.SHOW_EXPLICIT) == 0 &&
            (flags and InputMethod.SHOW_FORCED) == 0) return false
        return super.onShowInputRequested(flags, configChange)
    }

    private fun dp(v: Float) = (v * Medidas.escala(this)).toInt()

    private fun puedeDesenfocar(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val wm = getSystemService(WindowManager::class.java) ?: return false
        if (!wm.isCrossWindowBlurEnabled) return false
        val w = window?.window ?: return false
        return w.attributes.height == WindowManager.LayoutParams.WRAP_CONTENT
    }

    /** Si cambia la orientación o el tamaño elegido en Ajustes, el teclado se vuelve a armar */
    private fun firma() = "${Medidas.horizontal(this)}-${Ajustes.tamano(this)}"

    private fun construir(): View {
        val t = Tema.desde(this, false, colorDeApp(appActual))
        tema = t
        firmaTamano = firma()

        val b = SuggestionBar(this, t, this)
        val k = KeyboardView(this, t, this)
        val e = EmojiPanel(this, t, this).apply { visibility = View.GONE }
        barra = b; teclado = k; emojis = e

        val zona = FrameLayout(this)
        zona.addView(k, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        zona.addView(e, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, k.alto()))

        // El teclado: negro AMOLED, recto (sin esquinas curvas) con el filo del color de la app
        val p = Panel(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.fondo)
            radio = 0f
            grosor = maxOf(1f, resources.displayMetrics.density * 1.1f)
            colorBorde = t.bordePanel
            addView(zona, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        p.setOnApplyWindowInsetsListener { v, insets ->
            val abajo = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, abajo)
            insets
        }
        p.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { aplicarVentanaTransparente() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        panel = p
        // Con la barra a la vista, su filo curvo es el de arriba y el teclado solo dibuja los
        // costados. Con la barra oculta, el teclado dibuja su propio filo recto arriba.
        p.lineaArriba = true
        b.alCambiar = { mostrando -> p.lineaArriba = !mostrando }

        // La barra y el teclado son una sola pieza: la barra arriba (curva) y el teclado abajo (recto).
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)))
            addView(p, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return raiz
    }

    /** La ventana del teclado es transparente: así se ve la app detrás de la barra flotante */
    private fun aplicarVentanaTransparente() {
        val w = window?.window ?: return
        w.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) w.setBackgroundBlurRadius(0)
    }

    /**
     * La app de atrás se acomoda desde el borde de arriba de la barra de sugerencias (o del
     * teclado, si la barra está oculta). Así la caja de texto de la app (WhatsApp, etc.)
     * queda siempre encima y nunca debajo de la barra.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val p = panel ?: return
        val b = barra ?: return
        if (isFullscreenMode || !p.isAttachedToWindow || !b.isAttachedToWindow || p.height == 0) return
        val pos = IntArray(2)
        p.getLocationInWindow(pos)
        val arribaTeclado = pos[1]
        val abajo = pos[1] + p.height
        // Si la barra está oculta, la app llega hasta el borde de arriba del teclado
        val arriba = if (b.visibility == View.VISIBLE && b.height > 0) {
            b.getLocationInWindow(pos)
            pos[1]
        } else arribaTeclado
        outInsets.contentTopInsets = arriba
        outInsets.visibleTopInsets = arriba
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, arriba, p.width, abajo)
    }

    private fun configurarVentana() {
        window?.window?.let { w ->
            w.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        appActual = info.packageName
        if (Tema.desde(this, false, colorDeApp(appActual)) != tema || firma() != firmaTamano) {
            setInputView(construir())
        }
        configurarVentana()
        aplicarVentanaTransparente()

        vibrar = Ajustes.vibrar(this)
        sonido = Ajustes.sonido(this)
        puntoDoble = Ajustes.puntoDoble(this)
        atajosActivos = Ajustes.atajosActivos(this)
        atajos = Ajustes.atajos(this).associate { it.first.lowercase() to it.second }
        enMotor { diccionario.recargarSiCambio() }

        // Copia local del texto: se lee una sola vez al abrir el teclado
        selIni = info.initialSelStart
        selFin = info.initialSelEnd
        pendientes.clear()
        espejoValido = false
        calculada = null
        toques.clear()
        ultimoDeslizado = null

        val clase = info.inputType and InputType.TYPE_MASK_CLASS
        val variacion = info.inputType and InputType.TYPE_MASK_VARIATION
        val esTexto = clase == InputType.TYPE_CLASS_TEXT
        val esClave = (esTexto && variacion in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (clase == InputType.TYPE_CLASS_NUMBER && variacion == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val esCorreoOWeb = esTexto && variacion in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        val sinSugerencias = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        sugerenciasActivas = Ajustes.sugerencias(this) && esTexto && !esClave && !esCorreoOWeb && !sinSugerencias
        // Modo incógnito (Chrome, etc.) y contraseñas: no se aprende nada de lo que escribes
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        diccionario.pausado = incognito || esClave
        val escribeTexto = esTexto && !esClave && !esCorreoOWeb
        autocorreccionActiva = Ajustes.autocorregir(this) && escribeTexto && !sinSugerencias
        revisarActiva = (Ajustes.revisarOrtografia(this) || autocorreccionActiva) && escribeTexto && !sinSugerencias
        enLineaActiva = Ajustes.enLinea(this) && sugerenciasActivas
        puntuacionActiva = Ajustes.puntuacionInteligente(this) && escribeTexto
        borrarDeslizadoActivo = Ajustes.borrarDeslizado(this)
        mayusPermitida = Ajustes.mayusculas(this) && escribeTexto

        // Solo números: PIN, códigos, teléfonos y campos numéricos (como el teclado numérico del iPhone)
        val esPin = clase == InputType.TYPE_CLASS_NUMBER || clase == InputType.TYPE_CLASS_PHONE
        val esFecha = clase == InputType.TYPE_CLASS_DATETIME
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val conAccion = !sinAccion && accion in setOf(
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT,
        )

        teclado?.apply {
            vistaPrevia = Ajustes.vistaPrevia(this@TecladoService)
            bloqMayusPermitido = Ajustes.bloqMayus(this@TecladoService)
            soloNumeros = esFecha
            configurar(
                mano = Ajustes.unaMano(this@TecladoService),
                deslizar = Ajustes.deslizar(this@TecladoService) && escribeTexto,
            )
            pinTipo = when {
                clase == InputType.TYPE_CLASS_PHONE -> "telefono"
                clase == InputType.TYPE_CLASS_NUMBER &&
                        (info.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 -> "decimal"
                conAccion -> "accion"
                else -> "pin"
            }
            modo = when {
                esPin -> Modo.PIN
                esFecha -> Modo.NUMEROS
                else -> Modo.LETRAS
            }
            when {
                sinAccion -> configurarEnter("intro", false)
                accion == EditorInfo.IME_ACTION_GO -> configurarEnter("ir", true)
                accion == EditorInfo.IME_ACTION_SEARCH -> configurarEnter("buscar", true)
                accion == EditorInfo.IME_ACTION_SEND -> configurarEnter("enviar", true)
                accion == EditorInfo.IME_ACTION_DONE -> configurarEnter("OK", true)
                accion == EditorInfo.IME_ACTION_NEXT -> configurarEnter("siguiente", false)
                else -> configurarEnter("intro", false)
            }
        }
        ultimaCorreccion = null
        espacioAutomatico = false
        escribiendo = false
        barra?.visible(false, animar = false)
        volverABC()
        actualizar()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        barra?.visible(false, animar = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        manejador.removeCallbacks(tareaActualizar)
        if (dictando) detenerDictado()
        teclado?.ocultarPreview()
        enMotor { diccionario.guardar() }
        espejoValido = false
        toques.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        reconocedor?.destroy()
        reconocedor = null
        diccionario.interrumpir()
        motor.post { try { diccionario.guardar() } catch (_: Throwable) { } }
        hiloMotor.quitSafely()
        hiloRetro.quitSafely()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart == newSelEnd && pendientes.contains(newSelStart)) {
            // Es el resultado de algo que escribió el teclado: la copia local ya está al día
            while (pendientes.isNotEmpty()) if (pendientes.removeFirst() == newSelStart) break
            return
        }
        if (pendientes.isEmpty() && newSelStart == selIni && newSelEnd == selFin) return
        // Moviste el cursor o la app cambió el texto: se vuelve a leer cuando haga falta
        // (la barra se esconde hasta que vuelvas a escribir)
        if (SystemClock.uptimeMillis() - ultimaEscritura > 500) escribiendo = false
        pendientes.clear()
        selIni = newSelStart
        selFin = newSelEnd
        espejoValido = false
        calculada = null
        toques.clear()
        actualizar()
    }

    // ---------- Copia local del texto ----------

    private fun enMotor(tarea: () -> Unit) {
        motor.post { try { tarea() } catch (_: Throwable) { } }
    }

    private fun leerEspejo() {
        val t = try { currentInputConnection?.getTextBeforeCursor(MAX_ESPEJO, 0)?.toString() } catch (_: Exception) { null }
        espejo.setLength(0)
        if (t != null) {
            espejo.append(t)
            espejoValido = true
            espejoCompleto = t.length < MAX_ESPEJO
        } else {
            espejoValido = false
            espejoCompleto = false
        }
    }

    /** Los últimos [n] caracteres antes del cursor (de la copia local, sin preguntarle a la app) */
    private fun antes(n: Int = MAX_ESPEJO): String {
        if (!espejoValido) leerEspejo()
        val largo = espejo.length
        return if (n >= largo) espejo.toString() else espejo.substring(largo - n)
    }

    private fun recortarEspejo() {
        if (espejo.length > MAX_ESPEJO * 2) {
            espejo.delete(0, espejo.length - MAX_ESPEJO)
            espejoCompleto = false
        }
    }

    private fun esperarCursor(c: Int) {
        selIni = c
        selFin = c
        pendientes.addLast(c)
        while (pendientes.size > 64) pendientes.removeFirst()
    }

    private fun olvidarEspejo() {
        espejoValido = false
        pendientes.clear()
        selIni = -1
        selFin = -1
    }

    /**
     * Borra [borrarN] caracteres antes del cursor y escribe [nuevo], todo de una sola vez,
     * y actualiza la copia local sin preguntarle nada a la app.
     */
    private fun reemplazar(borrarN: Int, nuevo: String) {
        val ic = currentInputConnection ?: return
        if (!espejoValido) leerEspejo()
        if (borrarN > 0) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(borrarN, 0)
            ic.commitText(nuevo, 1)
            ic.endBatchEdit()
        } else {
            ic.commitText(nuevo, 1)
        }
        calculada = null
        val conSeleccion = selIni != selFin
        if (selIni < 0 || conSeleccion || !espejoValido || (borrarN > espejo.length && !espejoCompleto)) {
            olvidarEspejo()
        } else {
            val quitar = minOf(borrarN, espejo.length)
            espejo.setLength(espejo.length - quitar)
            espejo.append(nuevo)
            recortarEspejo()
            esperarCursor(selIni - quitar + nuevo.length)
        }
        actualizar()
    }

    /** Cuántos caracteres borra la tecla borrar al final de la copia (-1 = no se sabe, p. ej. un emoji) */
    private fun largoUltimo(): Int {
        val n = espejo.length
        if (n == 0) return 0
        val c = espejo[n - 1]
        if (Character.isSurrogate(c) || c == '\u200D' || c == '\uFE0F' || c == '\u20E3') return -1
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) return -1
        if (n >= 2 && espejo[n - 2] == '\u200D') return -1
        if (c == '\n' && n >= 2 && espejo[n - 2] == '\r') return 2
        return 1
    }

    // ---------- Estado ----------

    /** Color principal del ícono de la app (verde en WhatsApp, rosado en Instagram, etc.) */
    private fun colorDeApp(paquete: String?): Int? {
        if (paquete == null || paquete == packageName) return null
        if (coloresApps.containsKey(paquete)) return coloresApps[paquete]
        val color = try { calcularColor(paquete) } catch (_: Exception) { null }
        coloresApps[paquete] = color
        return color
    }

    private fun calcularColor(paquete: String): Int? {
        val icono = packageManager.getApplicationIcon(paquete)
        val lado = 48
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        icono.setBounds(0, 0, lado, lado)
        icono.draw(Canvas(bmp))
        val pesos = FloatArray(12)
        val rojos = FloatArray(12)
        val verdes = FloatArray(12)
        val azules = FloatArray(12)
        val hsv = FloatArray(3)
        for (x in 0 until lado) for (y in 0 until lado) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
            val i = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            val w = hsv[1] * hsv[2]
            pesos[i] += w
            rojos[i] += Color.red(p) * w
            verdes[i] += Color.green(p) * w
            azules[i] += Color.blue(p) * w
        }
        bmp.recycle()
        val mejor = pesos.indices.maxByOrNull { pesos[it] } ?: return null
        val w = pesos[mejor]
        if (w < 25f) return null // ícono casi sin color: se queda el azul de iOS
        val color = Color.rgb((rojos[mejor] / w).toInt(), (verdes[mejor] / w).toInt(), (azules[mejor] / w).toInt())
        // Ajustar para que se vea bien como tecla (ni muy apagado ni chillón)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.6f, 0.92f)
        return Color.HSVToColor(hsv)
    }

    private fun palabrasDe(antes: String): Pair<String, String> {
        var i = antes.length
        while (i > 0 && antes[i - 1].isLetter()) i--
        val actual = antes.substring(i)
        var j = i
        while (j > 0 && antes[j - 1] == ' ') j--
        var k = j
        while (k > 0 && antes[k - 1].isLetter()) k--
        val previa = if (j < i && k < j) antes.substring(k, j) else ""
        return previa to actual
    }

    /** La palabra antes de la anterior: "nos vemos " → "nos" (para predecir con dos palabras) */
    private fun previa2De(antes: String): String {
        fun palabraAntes(fin: Int): Pair<String, Int> {
            var j = fin
            while (j > 0 && antes[j - 1] == ' ') j--
            var k = j
            while (k > 0 && antes[k - 1].isLetter()) k--
            return if (j < fin && k < j) antes.substring(k, j) to k else "" to fin
        }
        var i = antes.length
        while (i > 0 && antes[i - 1].isLetter()) i--
        val (previa, k) = palabraAntes(i)
        return if (previa.isEmpty()) "" else palabraAntes(k).first
    }

    private fun inicioDeOracion(): Boolean = inicioDeOracion(antes(60))

    /** ¿Lo siguiente va con mayúscula? (inicio del campo, después de . ! ? o un salto de línea, o tras ¿ ¡) */
    private fun inicioDeOracion(antes: String): Boolean {
        var i = antes.length
        if (i == 0) return espejoCompleto || !espejoValido
        val ultimo = antes[i - 1]
        if (ultimo == '\n') return true
        // "¿Qué" y "¡Hola": justo después del signo de apertura
        if (ultimo == '¿' || ultimo == '¡') return inicioDeOracion(antes.substring(0, i - 1))
        if (!ultimo.isWhitespace()) return false
        while (i > 0 && antes[i - 1] == ' ') i--
        if (i == 0) return espejoCompleto
        var j = i
        // Se saltan comillas y paréntesis de cierre: «Hola.» Sigue…
        while (j > 0 && antes[j - 1] in "\"'”’»)") j--
        if (j == 0) return espejoCompleto
        val p = antes[j - 1]
        return p == '.' || p == '!' || p == '?' || p == '…' || p == '\n'
    }

    /** Lo escrito desde el último espacio (para atajos con números o símbolos) */
    private fun tokenActual(): String = tokenDe(antes(40))

    private fun tokenDe(antes: String): String {
        var i = antes.length
        while (i > 0 && !antes[i - 1].isWhitespace()) i--
        return antes.substring(i)
    }

    private fun buscarAtajo(token: String): String? {
        if (!atajosActivos || !sugerenciasActivas || token.isEmpty()) return null
        val frase = atajos[token.lowercase()] ?: return null
        // "Tqm" al inicio de oración → "Te quiero mucho"
        return if (token[0].isUpperCase()) frase.replaceFirstChar { it.uppercase() } else frase
    }

    /** Pide actualizar (se junta con otros pedidos del mismo cuadro) */
    private fun actualizar() {
        manejador.removeCallbacks(tareaActualizar)
        manejador.post(tareaActualizar)
    }

    private fun actualizarAhora() {
        // Todo sale de la copia local: no se le pregunta nada a la app
        val antes = antes(120)
        val inicio = inicioDeOracion(antes)
        actualizarMayus(inicio)
        actualizarBarra(antes, inicio)
    }

    private fun actualizarMayus(inicio: Boolean) {
        val k = teclado ?: return
        if (k.modo != Modo.LETRAS) return
        if (!mayusPermitida) { k.mayusAuto(false); return }
        k.mayusAuto(inicio)
    }

    private fun actualizarBarra() {
        manejador.removeCallbacks(tareaActualizar)
        actualizarAhora()
    }

    /** Los toques de la palabra [p] (si coinciden letra por letra), para la corrección por posición */
    private fun toquesPara(p: String, lista: List<FloatArray> = toques): Array<FloatArray>? {
        if (p.isEmpty() || lista.size < p.length) return null
        val desde = lista.size - p.length
        for (i in p.indices) {
            if (lista[desde + i][2].toInt().toChar() != p[i].lowercaseChar()) return null
        }
        return Array(p.length) { lista[desde + it].copyOf() }
    }

    private fun actualizarBarra(antes: String, inicio: Boolean) {
        val b = barra ?: return
        if (dictando) return
        val enEmojis = emojis?.visibility == View.VISIBLE
        val modo = teclado?.modo
        val enPad = modo == Modo.PIN || modo == Modo.TELEFONO_SIMBOLOS
        // La barra aparece cuando empiezas a escribir (no al abrir el teclado) y se esconde
        // al enviar, al mover el cursor o al cerrar el teclado. Mientras escribes funciona
        // completa: sugerencias, predicción de la siguiente palabra y deshacer la autocorrección.
        if (!sugerenciasActivas || enEmojis || enPad || !escribiendo) {
            b.visible(false)
            return
        }
        b.visible(true)
        // Recién deslizaste una palabra: la barra muestra las otras opciones
        val ud = ultimoDeslizado
        if (ud != null) {
            if (antes.endsWith(ud.insertado)) {
                b.mostrar(ud.alternativas)
                return
            }
            ultimoDeslizado = null
        }

        val (previa, p) = palabrasDe(antes)
        val token = tokenDe(antes)

        // Si lo escrito es una sustitución, la frase aparece al centro (como en iPhone)
        val frase = buscarAtajo(token)
        if (frase != null) {
            b.mostrar(listOf(
                Sugerencia(token, literal = true, reemplaza = token.length),
                Sugerencia(frase, destacada = true, reemplaza = token.length),
                null,
            ))
            return
        }

        // El cálculo anterior (si aún corre) ya no sirve: se corta y se pide uno nuevo
        diccionario.interrumpir()
        val t = diccionario.turno()

        if (p.isEmpty()) {
            // Predicción de la siguiente palabra, como en iPhone
            val prev = if (inicio) "" else previa
            val prev2 = if (inicio) "" else previa2De(antes)
            val uc = ultimaCorreccion
            val deshacer = if (uc != null && antes.endsWith(uc.second)) Sugerencia(uc.first.trim(), literal = true, revertir = true) else null
            motor.post {
                if (!diccionario.vigente(t)) return@post
                val sig = try {
                    diccionario.siguientes(prev, 3, prev2).map { if (inicio) it.replaceFirstChar { c -> c.uppercase() } else it }
                } catch (_: Throwable) { emptyList() }
                manejador.post {
                    if (!diccionario.vigente(t)) return@post
                    b.mostrar(listOf(
                        deshacer ?: sig.getOrNull(1)?.let { Sugerencia(it) },
                        sig.getOrNull(0)?.let { Sugerencia(it) },
                        (if (deshacer != null) sig.getOrNull(1) else sig.getOrNull(2))?.let { Sugerencia(it) },
                    ))
                }
            }
            return
        }

        // Mientras se calcula, la barra ya muestra lo que escribiste (respuesta inmediata)
        b.mostrarEscrito(Sugerencia(p, literal = true))
        val previa2 = previa2De(antes)
        val revisar = revisarActiva
        val aplicar = autocorreccionActiva
        val linea = enLineaActiva
        val toq = toquesPara(p)
        motor.post {
            if (!diccionario.vigente(t)) return@post
            var corr: String? = null
            var otras: List<String> = emptyList()
            var completa: String? = null
            try {
                corr = if (revisar) diccionario.corregir(p, previa, previa2, t, toq) else null
                if (!diccionario.vigente(t)) return@post
                otras = diccionario.sugerir(p, 4, previa, previa2, t, toq).filter { it != corr && it != p }
                // Predicción en línea: la palabra que más probablemente estás escribiendo
                if (linea && corr == null && p.length >= 2) {
                    val top = otras.firstOrNull()
                    if (top != null && top.length >= p.length + 2 &&
                        top.substring(0, p.length).equals(p, ignoreCase = true) && !diccionario.conocida(p)
                    ) completa = top
                }
            } catch (_: Throwable) { return@post }
            if (!diccionario.vigente(t)) return@post
            val c = corr
            val comp = completa
            val lista = otras
            manejador.post {
                if (!diccionario.vigente(t)) return@post
                calculada = Calculada(p, previa, previa2, if (aplicar) c else null, comp)
                if (comp != null) {
                    b.mostrar(listOf(
                        Sugerencia(p, literal = true, resto = comp.substring(p.length)),
                        lista.getOrNull(1)?.let { Sugerencia(it) },
                        lista.getOrNull(2)?.let { Sugerencia(it) },
                    ))
                    return@post
                }
                // Con Autocorrección la corrección va resaltada al centro (se pone sola al dar espacio);
                // solo con Revisar ortografía aparece como sugerencia para tocarla
                val centro = c?.let { Sugerencia(it, destacada = aplicar) } ?: lista.getOrNull(0)?.let { Sugerencia(it) }
                val derecha = (if (c != null) lista.getOrNull(0) else lista.getOrNull(1))?.let { Sugerencia(it) }
                b.mostrar(listOf(Sugerencia(p, literal = true), centro, derecha))
            }
        }
    }

    /** La corrección de [palabra]: la ya calculada en segundo plano, o se calcula ahora (pocas veces) */
    private fun correccionDe(palabra: String, previa: String, previa2: String, toq: Array<FloatArray>?): String? {
        val c = calculada
        if (c != null && c.palabra == palabra && c.previa == previa && c.previa2 == previa2) return c.correccion
        diccionario.interrumpir()
        return try { diccionario.corregir(palabra, previa, previa2, diccionario.turno(), toq) } catch (_: Throwable) { null }
    }

    /** Predicción en línea aceptada con espacio */
    private fun completadaDe(palabra: String, previa: String): String? {
        val c = calculada ?: return null
        return if (c.palabra == palabra && c.previa == previa) c.completa else null
    }

    /**
     * Cierra la palabra actual y agrega el sufijo (espacio, signo o salto de línea):
     * 1) sustitución de texto, 2) autocorrección, 3) tildes según la palabra anterior.
     */
    private fun cerrarPalabra(sufijo: String) {
        if (currentInputConnection == null) return
        espacioAutomatico = false
        ultimaAprendida = null
        ultimoDeslizado = null
        val misToques = ArrayList(toques)
        toques.clear()

        // 1) Sustitución de texto
        val token = tokenActual()
        val frase = buscarAtajo(token)
        if (frase != null) {
            reemplazar(token.length, frase + sufijo)
            ultimaCorreccion = Pair(token, frase + sufijo)
            return
        }

        val antes = antes(120)
        var i = antes.length
        while (i > 0 && antes[i - 1].isLetter()) i--
        val palabra = antes.substring(i)
        var j = i
        while (j > 0 && antes[j - 1] == ' ') j--
        var k = j
        while (k > 0 && antes[k - 1].isLetter()) k--
        val previa = if (j < i && k < j) antes.substring(k, j) else ""
        val espacios = antes.substring(j, i)
        val previa2 = previa2De(antes)

        if (palabra.isEmpty()) {
            reemplazar(0, sufijo)
            ultimaCorreccion = null
            return
        }

        // 2) Autocorrección de la palabra (con la posición exacta de cada toque, como Gboard)
        var nuevaPalabra = palabra
        val corr = if (autocorreccionActiva) correccionDe(palabra, previa, previa2, toquesPara(palabra, misToques)) else null
        if (corr != null) nuevaPalabra = corr
        else if (enLineaActiva && sufijo == " ") completadaDe(palabra, previa)?.let { nuevaPalabra = it }

        // 3) Tildes que dependen de la palabra anterior ("que tal" → "qué tal")
        var nuevaPrevia = previa
        if (autocorreccionActiva && previa.isNotEmpty()) {
            diccionario.interrumpir()
            diccionario.corregirPar(previa, nuevaPalabra)?.let { (a, b) ->
                nuevaPrevia = a
                nuevaPalabra = b
            }
        }

        if (sugerenciasActivas || autocorreccionActiva) {
            // Aprender va en segundo plano: no frena la escritura
            val np = nuevaPalabra
            val npr = nuevaPrevia
            val aMitad = previa.isNotEmpty()
            enMotor {
                diccionario.aprender(np)
                diccionario.aprenderPar(npr, np, previa2)
                // Si hay palabra anterior pegada, estás a mitad de oración (sirve para recordar nombres)
                diccionario.notarMayuscula(np, aMitad)
            }
            // Si borras el espacio enseguida, se deshace lo aprendido (era un error)
            ultimaAprendida = if (sufijo == " ") Aprendida(previa2, nuevaPrevia, nuevaPalabra, nuevaPalabra + sufijo) else null
        }

        if (nuevaPrevia == previa && nuevaPalabra == palabra) {
            reemplazar(0, sufijo)
            ultimaCorreccion = null
            return
        }
        val cambiaPrevia = nuevaPrevia != previa
        val original = if (cambiaPrevia) previa + espacios + palabra else palabra
        val nuevo = (if (cambiaPrevia) nuevaPrevia + espacios else "") + nuevaPalabra
        reemplazar(original.length, nuevo + sufijo)
        ultimaCorreccion = Pair(original, nuevo + sufijo)
    }

    /** Al poner "?", la palabra de pregunta del inicio lleva tilde: "que hora es?" → "qué hora es?" */
    private fun acentuarPregunta() {
        if (!autocorreccionActiva) return
        if (currentInputConnection == null) return
        val antes = antes(200)
        var i = antes.length
        while (i > 0 && antes[i - 1] !in ".!?¿¡\n") i--
        val oracion = antes.substring(i)
        var a = 0
        while (a < oracion.length && oracion[a].isWhitespace()) a++
        var b = a
        while (b < oracion.length && oracion[b].isLetter()) b++
        if (b == a) return
        val primera = oracion.substring(a, b)
        // "por que ...?" → "por qué ...?"
        var inicio = a
        var objetivo = primera
        if (primera.equals("por", ignoreCase = true)) {
            var c = b
            while (c < oracion.length && oracion[c] == ' ') c++
            var e = c
            while (e < oracion.length && oracion[e].isLetter()) e++
            if (e > c && oracion.substring(c, e).equals("que", ignoreCase = true)) {
                inicio = c
                objetivo = oracion.substring(c, e)
            } else return
        }
        diccionario.interrumpir()
        val conTilde = diccionario.interrogativa(objetivo) ?: return
        val desde = i + inicio
        val resto = antes.substring(desde + objetivo.length)
        reemplazar(antes.length - desde, conTilde + resto)
    }

    // ---------- Acciones de las teclas ----------

    /** Comilla de apertura o de cierre según lo que hay antes (Puntuación inteligente) */
    private fun abreComilla(): Boolean {
        val a = antes(1)
        return a.isEmpty() || a[0].isWhitespace() || a[0] in "([{¿¡“‘—-"
    }

    override fun texto(s: String, x: Float, y: Float) {
        escribiendo = true
        ultimaEscritura = SystemClock.uptimeMillis()
        if (currentInputConnection == null) return
        ultimaAprendida = null
        var c = s
        // Recordar dónde cayó el dedo en cada letra (o que no se sabe)
        if (c.length == 1 && c[0].isLetter()) {
            toques.add(floatArrayOf(x, y, c[0].lowercaseChar().code.toFloat()))
            if (toques.size > 48) toques.removeAt(0)
        } else toques.clear()

        // Puntuación inteligente: comillas tipográficas y raya, como en iPhone
        if (puntuacionActiva) {
            when (c) {
                "\"" -> c = if (abreComilla()) "“" else "”"
                "'" -> c = if (abreComilla()) "‘" else "’"
                "-" -> if (antes(1) == "-") {
                    reemplazar(1, "—")
                    espacioAutomatico = false
                    ultimaCorreccion = null
                    return
                }
            }
        }
        if (c == "?") acentuarPregunta()
        if (c.length == 1 && c[0] in ".,;:!?") {
            // Después de elegir una sugerencia, la puntuación se pega a la palabra: "hola, "
            if (espacioAutomatico && antes(1) == " ") {
                reemplazar(1, "$c ")
                espacioAutomatico = false
                ultimaCorreccion = null
                return
            }
            cerrarPalabra(c)
            return
        }
        espacioAutomatico = false
        ultimaCorreccion = null
        reemplazar(0, c)
    }

    override fun espacio() {
        if (currentInputConnection == null) return
        val ahora = SystemClock.uptimeMillis()
        // Después de elegir una sugerencia o deslizar, el espacio ya está puesto (como Gboard)
        if (espacioAutomatico && antes(1) == " ") {
            espacioAutomatico = false
            ultimoEspacio = ahora
            toques.clear()
            return
        }
        val antes = antes(2)
        if (puntoDoble && antes.length == 2 && antes[1] == ' ' && antes[0].isLetterOrDigit() &&
            ahora - ultimoEspacio < 450
        ) {
            reemplazar(1, ". ")
            ultimoEspacio = 0
            ultimaCorreccion = null
            espacioAutomatico = false
            toques.clear()
            return
        }
        ultimoEspacio = ahora
        cerrarPalabra(" ")
    }

    override fun borrar() {
        escribiendo = true
        ultimaEscritura = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        espacioAutomatico = false
        // Borrar con Deslizar para escribir por palabra: se va la palabra entera
        val ud = ultimoDeslizado
        ultimoDeslizado = null
        if (ud != null && borrarDeslizadoActivo && antes(ud.insertado.length) == ud.insertado) {
            toques.clear()
            ultimaCorreccion = null
            val ua = ultimaAprendida
            ultimaAprendida = null
            if (ua != null) enMotor { diccionario.desaprender(ua.palabra, ua.previa, ua.previa2) }
            reemplazar(ud.insertado.length, "")
            return
        }
        val uc = ultimaCorreccion
        ultimaCorreccion = null
        val ua = ultimaAprendida
        ultimaAprendida = null
        if (uc != null) {
            // Deshacer la autocorrección como en iPhone
            if (antes(uc.second.length) == uc.second) {
                toques.clear()
                reemplazar(uc.second.length, uc.first)
                // La corrección no era lo que querías: se olvida y se aprende lo tuyo
                enMotor {
                    if (ua != null) diccionario.desaprender(ua.palabra, ua.previa, ua.previa2)
                    diccionario.rechazarCorreccion(uc.first.trim(), uc.second.trim())
                    uc.first.split(" ").filter { it.isNotBlank() }.forEach {
                        diccionario.aprender(it, 2)
                        if (it.all { c -> c.isLowerCase() }) diccionario.notarMayuscula(it, true)
                    }
                }
                return
            }
        }
        // Borraste el espacio justo después de una palabra: no se cuenta como aprendida
        if (ua != null && antes(ua.texto.length) == ua.texto) {
            enMotor { diccionario.desaprender(ua.palabra, ua.previa, ua.previa2) }
        }
        calculada = null
        if (toques.isNotEmpty()) toques.removeAt(toques.size - 1)
        // Hay texto seleccionado: se borra la selección (sin preguntarle a la app si se sabe)
        val haySeleccion = if (selIni >= 0) selIni != selFin else !ic.getSelectedText(0).isNullOrEmpty()
        if (haySeleccion) {
            ic.commitText("", 1)
            olvidarEspejo()
            toques.clear()
            actualizar()
            return
        }
        if (!espejoValido) leerEspejo()
        val n = if (espejoValido && selIni >= 0) largoUltimo() else -1
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        when {
            n > 0 -> {
                espejo.setLength(espejo.length - n)
                esperarCursor(selIni - n)
            }
            n == 0 && espejoCompleto -> { /* no había nada que borrar */ }
            else -> olvidarEspejo()
        }
        actualizar()
    }

    override fun borrarPalabra() {
        if (currentInputConnection == null) return
        espacioAutomatico = false
        ultimaCorreccion = null
        toques.clear()
        val antes = antes(60)
        if (antes.isEmpty()) { if (!espejoCompleto) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); olvidarEspejo() }; return }
        var i = antes.length
        while (i > 0 && antes[i - 1].isWhitespace()) i--
        while (i > 0 && antes[i - 1].isLetterOrDigit()) i--
        val n = antes.length - i
        if (n <= 1) borrar()
        else reemplazar(n, "")
    }

    override fun moverCursor(pasos: Int) {
        espacioAutomatico = false
        ultimaAprendida = null
        ultimaCorreccion = null
        ultimoDeslizado = null
        calculada = null
        toques.clear()
        val tecla = if (pasos > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(Math.abs(pasos)) { sendDownUpKeyEvents(tecla) }
        olvidarEspejo()
    }

    override fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        ultimaCorreccion = null
        espacioAutomatico = false
        ultimoDeslizado = null
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!sinAccion && accion != EditorInfo.IME_ACTION_NONE && accion != EditorInfo.IME_ACTION_UNSPECIFIED) {
            toques.clear()
            // Enviaste o buscaste: dejaste de escribir, la barra se esconde
            escribiendo = false
            ic.performEditorAction(accion)
            // Enviar/buscar suele limpiar el campo: se vuelve a leer cuando haga falta
            olvidarEspejo()
            return
        }
        val multilinea = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (multilinea) cerrarPalabra("\n") else {
            toques.clear()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            olvidarEspejo()
        }
    }

    override fun abrirEmojis() {
        teclado?.ocultarPreview()
        emojis?.refrescar()
        teclado?.visibility = View.INVISIBLE
        emojis?.visibility = View.VISIBLE
        actualizarBarra()
    }

    override fun volverABC() {
        emojis?.visibility = View.GONE
        teclado?.visibility = View.VISIBLE
        actualizarBarra()
    }

    override fun emoji(s: String) {
        ultimaCorreccion = null
        ultimaAprendida = null
        ultimoDeslizado = null
        espacioAutomatico = false
        toques.clear()
        reemplazar(0, s)
    }

    override fun elegir(s: Sugerencia) {
        if (dictando) return
        if (currentInputConnection == null) return
        toques.clear()
        if (s.revertir) {
            // Volver a lo que escribiste y aprenderlo para no corregirlo otra vez
            val uc = ultimaCorreccion ?: return
            val sufijo = uc.second.takeLastWhile { !it.isLetterOrDigit() }
            reemplazar(uc.second.length, uc.first + sufijo)
            val ua = ultimaAprendida
            enMotor {
                ua?.let { diccionario.desaprender(it.palabra, it.previa, it.previa2) }
                diccionario.rechazarCorreccion(uc.first.trim(), uc.second.trim())
                uc.first.split(" ").filter { it.isNotBlank() }.forEach {
                    diccionario.aprender(it, 2)
                    if (it.all { c -> c.isLowerCase() }) diccionario.notarMayuscula(it, true)
                }
            }
            ultimaAprendida = null
            ultimaCorreccion = null
            retro(Tipo.LETRA)
            return
        }
        val ud = ultimoDeslizado
        val antesTexto = antes(120)
        val (previa0, palabra) = palabrasDe(antesTexto)
        var previa = previa0
        var previa2 = previa2De(antesTexto)
        val borrarN = if (s.reemplaza >= 0) s.reemplaza else palabra.length
        if (ud != null && s.reemplaza == ud.insertado.length && antesTexto.endsWith(ud.insertado)) {
            // Cambiar la palabra deslizada por otra opción
            val sin = antesTexto.substring(0, antesTexto.length - ud.insertado.length)
            val (pv, act) = palabrasDe(sin)
            previa = act.ifEmpty { pv }
            previa2 = if (act.isNotEmpty()) pv else previa2De(sin)
        }
        reemplazar(borrarN, s.texto + " ")
        // Elegir una sugerencia a propósito enseña más que escribirla (sobre todo la que está "entre comillas")
        val pv = previa
        val pv2 = previa2
        enMotor {
            diccionario.aprender(s.texto, if (s.literal) 3 else 2)
            diccionario.aprenderPar(pv, s.texto, pv2)
        }
        ultimaAprendida = Aprendida(pv2, pv, s.texto, s.texto + " ")
        ultimaCorreccion = null
        espacioAutomatico = true
        if (ud != null && s.reemplaza == ud.insertado.length) {
            // Las otras opciones siguen en la barra (y borrar quita la palabra entera)
            val nuevo = s.texto + " "
            ultimoDeslizado = Deslizado(nuevo, ud.alternativas.map { a ->
                a?.let { Sugerencia(it.texto, destacada = it.texto == s.texto, reemplaza = nuevo.length) }
            })
            actualizarBarra()
        } else ultimoDeslizado = null
        retro(Tipo.LETRA)
    }

    /** Mantener presionada una sugerencia → Borrar: el teclado olvida esa palabra */
    override fun olvidar(s: Sugerencia) {
        if (dictando) return
        enMotor { diccionario.olvidar(s.texto) }
        retro(Tipo.BORRAR)
        actualizarBarra()
    }

    // ---------- Deslizar para escribir ----------

    override fun deslizar(puntos: List<FloatArray>, proporcion: Float, mayus: Boolean) {
        escribiendo = true
        ultimaEscritura = SystemClock.uptimeMillis()
        if (currentInputConnection == null) return
        toques.clear()
        ultimaCorreccion = null
        ultimaAprendida = null
        val antes = antes(120)
        // Si la palabra anterior no terminó en espacio, se pone uno (como en iPhone)
        val espacioAntes = antes.isNotEmpty() && !antes.last().isWhitespace() && antes.last() !in "¿¡([{“‘\"'@#/-"
        val conEspacio = if (espacioAntes) "$antes " else antes
        val inicio = inicioDeOracion(conEspacio) && mayusPermitida
        val (pv, act) = palabrasDe(conEspacio)
        val previa = pv.ifEmpty { act }
        val previa2 = previa2De(conEspacio)
        diccionario.proporcion = proporcion
        motor.post {
            val lista = try {
                diccionario.deslizar(puntos, proporcion, previa, previa2, 3)
            } catch (_: Throwable) { emptyList() }
            manejador.post { escribirDeslizado(lista, espacioAntes, mayus || inicio, previa, previa2) }
        }
    }

    private fun escribirDeslizado(lista: List<String>, espacioAntes: Boolean, mayus: Boolean, previa: String, previa2: String) {
        if (lista.isEmpty() || currentInputConnection == null) return
        val palabras = lista.map { if (mayus) it.replaceFirstChar { c -> c.uppercase() } else it }
        val mejor = palabras[0]
        val insertado = "$mejor "
        reemplazar(0, (if (espacioAntes) " " else "") + insertado)
        espacioAutomatico = true
        ultimaCorreccion = null
        enMotor {
            diccionario.aprender(mejor)
            diccionario.aprenderPar(previa, mejor, previa2)
        }
        ultimaAprendida = Aprendida(previa2, previa, mejor, insertado)
        val alternativas = listOf(palabras.getOrNull(1), mejor, palabras.getOrNull(2)).map { w ->
            w?.let { Sugerencia(it, destacada = it == mejor, reemplaza = insertado.length) }
        }
        ultimoDeslizado = Deslizado(insertado, alternativas)
        actualizarBarra()
    }

    // ---------- Dictado ----------

    override fun dictar() {
        if (dictando) { detenerDictado(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Abre la app para pedir permiso del micrófono
            startActivity(Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("pedir_microfono", true))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Este celular no tiene reconocimiento de voz", Toast.LENGTH_SHORT).show()
            return
        }
        val r = reconocedor ?: SpeechRecognizer.createSpeechRecognizer(this).also { reconocedor = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                barra?.visible(true)
                barra?.mostrar(listOf(null, Sugerencia("Escuchando…", destacada = true), null))
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { terminarDictado() }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) {
                    barra?.visible(true)
                    barra?.mostrar(listOf(null, Sugerencia(t, destacada = true), null))
                }
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) escribirDictado(t)
                terminarDictado()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Ajustes.idiomaDictado(this@TecladoService))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Puntuación automática (Android 13 o más nuevo pone comas, puntos y signos)
            if (Ajustes.puntuacionDictado(this@TecladoService) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        try {
            r.startListening(intent)
            dictando = true
            teclado?.dictando = true
        } catch (_: Exception) {
            terminarDictado()
        }
    }

    private fun escribirDictado(texto: String) {
        if (currentInputConnection == null) return
        toques.clear()
        ultimoDeslizado = null
        val antes = antes(1)
        val t = if (inicioDeOracion()) texto.replaceFirstChar { it.uppercase() } else texto
        val prefijo = if (antes.isNotEmpty() && !antes.last().isWhitespace()) " " else ""
        reemplazar(0, "$prefijo$t ")
        ultimaCorreccion = null
        espacioAutomatico = false
    }

    private fun detenerDictado() {
        try { reconocedor?.stopListening() } catch (_: Exception) { }
        terminarDictado()
    }

    private fun terminarDictado() {
        dictando = false
        teclado?.dictando = false
        actualizarBarra()
    }

    // ---------- Respuesta del teclado (vibración y sonido) ----------

    /** El efecto de vibración se arma una sola vez */
    private var efectoToque: VibrationEffect? = null

    override fun retro(tipo: Tipo) {
        val conVibrar = vibrar
        val conSonido = sonido
        if (!conVibrar && !conSonido) return
        // En su propio hilo: la tecla se dibuja y se escribe sin esperar a la vibración
        retroManejador.post {
            if (conVibrar) vibrarAhora()
            if (conSonido) {
                val efecto = when (tipo) {
                    Tipo.BORRAR -> AudioManager.FX_KEYPRESS_DELETE
                    Tipo.ESPACIO -> AudioManager.FX_KEYPRESS_SPACEBAR
                    Tipo.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }
                try { audio?.playSoundEffect(efecto, -1f) } catch (_: Exception) { }
            }
        }
    }

    private fun vibrarAhora() {
        try {
            val v = vibrador ?: return
            // Toque corto y seco, como el Taptic Engine del iPhone (si el celular lo soporta)
            if (toqueFino == null) {
                toqueFino = v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            }
            val efecto = efectoToque ?: (if (toqueFino == true) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }).also { efectoToque = it }
            v.vibrate(efecto)
        } catch (_: Exception) { }
    }
}
