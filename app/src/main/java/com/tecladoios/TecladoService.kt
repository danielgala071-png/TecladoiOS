package com.tecladoios

import android.Manifest
import android.content.ClipDescription
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.view.animation.DecelerateInterpolator
import android.content.Intent
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethod
import android.widget.FrameLayout
import android.widget.LinearLayout

class TecladoService : InputMethodService(),
    KeyboardView.Acciones, EmojiPanel.Acciones, SuggestionBar.Acciones {

    companion object {
        /** Cuánto texto antes del cursor se guarda en la copia local */
        private const val MAX_ESPEJO = 300
        /** Lo copiado se ofrece en la barra durante este tiempo (como en iPhone) */
        private const val VIGENCIA_COPIADO = 3 * 60 * 1000L
        // Botones de la barra de edición
        private const val ED_TODO = 1
        private const val ED_SELECCIONAR = 2
        private const val ED_CORTAR = 3
        private const val ED_COPIAR = 4
        private const val ED_PEGAR = 5
        /** Dónde se guarda el historial del portapapeles */
        private const val CLAVE_HISTORIAL = "historial_copiado"
        /** Lo aprendido se guarda como mucho cada tantos milisegundos (menos escrituras = menos batería) */
        private const val ESPERA_GUARDADO = 20_000L
        /** Cuánto se queda la barra a la vista después de enviar o dejar de escribir */
        private const val SOSTENER_MS = 2600L
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
    private val coloresApps = LinkedHashMap<String, Int?>()
    private var appActual: String? = null
    private lateinit var diccionario: Diccionario
    private var vibrador: Vibrator? = null
    private var audio: AudioManager? = null

    private var vibrar = true
    private var sonido = false
    /** Fuerza de la háptica elegida en Ajustes (suave / media / fuerte) */
    @Volatile private var fuerzaVibracion = "media"
    /** La barra se queda unos segundos después de enviar o de dejar de escribir */
    private var barraSostenida = true
    /** Con el campo vacío, la barra muestra los emojis que más usas */
    private var emojisFrecuentesActivo = true
    /** Esconde la barra cuando pasó el tiempo de "barra sostenida" */
    private val tareaOcultarBarra = Runnable { actualizar() }

    // Sonidos propios estilo iPhone (letra, borrar, espacio, intro): cortos y limpios
    private var sonidos: SoundPool? = null
    private val idSonido = IntArray(4)
    private val sonidoListo = BooleanArray(4)
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
    /** ¿ y ¡ al inicio de la oración cuando la cierras con ? o ! */
    private var aperturaActiva = true
    /** Emojis en la barra según la palabra ("jaja" → 😂) */
    private var emojisBarraActivo = true
    /** El campo acepta varias líneas (el cursor puede subir y bajar con la barra espaciadora) */
    private var multilinea = false
    private var atajos: Map<String, String> = emptyMap()
    /** Texto de las sustituciones ya leído (solo se vuelve a leer si cambiaste algo en Ajustes) */
    private var atajosCrudo: String? = null

    // ---------- Lo copiado (como en iPhone: aparece en la barra para pegarlo con un toque) ----------
    private var portapapeles: ClipboardManager? = null
    private var copiadoActivo = true
    /** En este campo se puede ofrecer lo copiado (no en el teclado de PIN) */
    private var copiadoPermitido = false
    /** Campo de contraseña: lo copiado se muestra con puntos */
    private var campoClave = false
    /** Hora (del portapapeles) de lo último que pegaste o descartaste: no se vuelve a ofrecer */
    private var copiadoUsado = 0L
    private var copiadoHora = -1L
    private var copiadoTexto: String? = null
    private var copiadoSensible = false
    /** Las contraseñas copiadas se borran solas del portapapeles */
    private var limpiarClaves = true
    /** Hora (del portapapeles) de la contraseña copiada que se va a borrar */
    private var horaALimpiar = -1L
    private val tareaLimpiar = Runnable {
        try {
            val cm = portapapeles ?: return@Runnable
            val hora = cm.primaryClipDescription?.timestamp ?: return@Runnable
            // Solo si sigue siendo la misma (si copiaste otra cosa, no se toca)
            if (hora == horaALimpiar) {
                cm.clearPrimaryClip()
                copiadoTexto = null
                copiadoHora = -1L
            }
        } catch (_: Exception) { }
        horaALimpiar = -1L
    }
    private val alCambiarCopiado = ClipboardManager.OnPrimaryClipChangedListener {
        guardarEnHistorial()
        copiadoHora = -1L
        if (isInputViewShown) actualizar()
    }
    private val tareaGuardar = Runnable { try { diccionario.guardar() } catch (e: Throwable) { Registro.anotar("guardar", e) } }

    private var toqueFino: Boolean? = null
    private var ultimaCorreccion: Pair<String, String>? = null
    /** La misma de arriba cuando fue una autocorrección (para que el cerebro se calibre al deshacerla) */
    private var ultimaCorreccionAuto: Pair<String, String>? = null

    /**
     * La última palabra que cerraste y dónde empezaba. Si la borras y escribes otra parecida
     * en el mismo lugar, el cerebro aprende solo que la primera era un error tuyo ("tqrjeta"
     * borrada y reescrita como "tarjeta"), sin que tengas que tocar nada.
     */
    private class Cerrada(
        val palabra: String, val previa: String, val previa2: String, val inicio: Int, val hora: Long,
    ) {
        /** Lo que quedó escrito (puede ser la autocorrección) */
        var aprendida: String = palabra
        /** Ya se deshizo lo aprendido (borraste el espacio enseguida) */
        var desaprendida = false
    }
    private var ultimaCerrada: Cerrada? = null
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
     * Corrección pendiente: diste espacio antes de que el motor terminara de revisar la palabra.
     * El espacio se escribe al instante (no se traba la escritura rápida) y la corrección se
     * pone unos milisegundos después, aunque ya hayas seguido escribiendo.
     */
    private class Diferida(
        val palabra: String, val sufijo: String, val previa: String, val previa2: String,
        /** Posición del cursor justo después de palabra + sufijo */
        var fin: Int,
    )
    private val diferidas = ArrayList<Diferida>()

    // ---------- Calcular antes de soltar ----------

    /** Opciones del momento (se copian para que el hilo del motor no lea cosas a medio cambiar) */
    private class Opciones(
        val revisar: Boolean, val aplicar: Boolean, val linea: Boolean,
        val enLetras: Boolean, val emojisBarra: Boolean,
    )

    /** Lo que calculó el motor para una palabra a medio escribir */
    private class Resultado(
        val p: String, val previa: String, val previa2: String, val probs: FloatArray?,
        val corr: String?, val otras: List<String>, val completa: String?,
    )

    /**
     * Cálculo adelantado: apenas el dedo toca una letra (antes de soltarla) el motor ya calcula
     * las sugerencias como si estuviera escrita. Al soltar, si es la misma letra, la barra y la
     * autocorrección quedan listas al instante, sin esperar al motor.
     */
    private class Anticipo(val clave: String, val turno: Int, val marca: Long, val hora: Long, val op: Opciones) {
        @Volatile var resultado: Resultado? = null
        /** La letra ya se escribió: mostrar el resultado apenas esté */
        @Volatile var mostrar = false
    }
    private var anticipo: Anticipo? = null
    /** Orden de los toques y de las letras escritas (para saber qué pasó primero) */
    private var marca = 0L
    private var marcaTexto = 0L

    // ---------- Buscar emojis escribiendo ----------
    private var buscandoEmoji = false
    private var consultaEmoji = ""

    // ---------- Contactos e íconos ----------
    /** Cuándo se leyeron los contactos por última vez (0 = no se leyeron o están apagados) */
    private var contactosLeidos = 0L
    /** Ícono de cada app (para la barra espaciadora) */
    private val iconos = LinkedHashMap<String, Bitmap?>()

    // ---------- Barra de edición ----------
    /** Estás moviendo el cursor con la barra espaciadora: la barra muestra Cortar, Copiar, Pegar... */
    private var editando = false
    /** Modo seleccionar: mover el cursor con la barra espaciadora va marcando el texto */
    private var seleccionando = false

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
        Registro.iniciar(this)
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
        cargarSonidos()
        portapapeles = getSystemService(ClipboardManager::class.java)
        try { portapapeles?.addPrimaryClipChangedListener(alCambiarCopiado) } catch (_: Exception) { }
    }

    override fun onCreateInputView(): View = construir()

    /** Carga los 4 sonidos una sola vez (se reproducen sin demora en cada toque) */
    private fun cargarSonidos() {
        try {
            val sp = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
            sp.setOnLoadCompleteListener { _, id, estado ->
                if (estado == 0) for (i in 0 until 4) if (idSonido[i] == id) sonidoListo[i] = true
            }
            idSonido[0] = sp.load(this, R.raw.tecla, 1)
            idSonido[1] = sp.load(this, R.raw.espacio, 1)
            idSonido[2] = sp.load(this, R.raw.borrar, 1)
            idSonido[3] = sp.load(this, R.raw.intro, 1)
            sonidos = sp
        } catch (e: Throwable) {
            Registro.anotar("cargar sonidos", e)
            sonidos = null
        }
    }

    /**
     * Al entrar a una conversación, muchas apps piden abrir el teclado solas. Eso llega sin la
     * marca de "lo pidió el usuario" (SHOW_EXPLICIT): en ese caso no se abre. Se abre cuando
     * tocas donde escribir.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (!configChange && (flags and InputMethod.SHOW_EXPLICIT) == 0 &&
            (flags and InputMethod.SHOW_FORCED) == 0 && !esBuscador(currentInputEditorInfo)) return false
        return super.onShowInputRequested(flags, configChange)
    }

    /**
     * Buscadores (Google, Chrome, Play Store, contactos...): ahí sí se abre solo, porque
     * entraste justamente para escribir. En los chats sigue esperando a que toques.
     */
    private fun esBuscador(info: EditorInfo?): Boolean {
        if (info == null) return false
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val variacion = info.inputType and InputType.TYPE_MASK_VARIATION
        val clase = info.inputType and InputType.TYPE_MASK_CLASS
        return accion == EditorInfo.IME_ACTION_SEARCH ||
            (clase == InputType.TYPE_CLASS_TEXT && variacion == InputType.TYPE_TEXT_VARIATION_FILTER)
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
    private fun firma() = "${Medidas.horizontal(this)}-${Ajustes.tamano(this)}-${Ajustes.filaNumeros(this)}-${Ajustes.filasEmoji(this)}"

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
            resplandor = t.resplandor
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
        // La barra ya no cambia el alto del teclado: solo se avisa a Android hasta dónde llega
        // el teclado (para que la caja de texto de la app quede justo encima de la barra)
        b.alCambiarEstado = { b.requestLayout() }

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
        val arriba = if (b.aLaVista && b.height > 0) {
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
        // El cerebro aprende cómo escribes en cada app (WhatsApp, Instagram, correo...)
        diccionario.app = (info.packageName ?: "").filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        if (Tema.desde(this, false, colorDeApp(appActual)) != tema || firma() != firmaTamano) {
            setInputView(construir())
        }
        configurarVentana()
        aplicarVentanaTransparente()

        vibrar = Ajustes.vibrar(this)
        sonido = Ajustes.sonido(this)
        val fuerza = Ajustes.vibracion(this)
        if (fuerza != fuerzaVibracion) {
            fuerzaVibracion = fuerza
            // Los efectos guardados se vuelven a armar con la fuerza nueva
            retroManejador.post { efectos.fill(null) }
        }
        barraSostenida = Ajustes.barraSostenida(this)
        emojisFrecuentesActivo = Ajustes.emojisFrecuentes(this)
        manejador.removeCallbacks(tareaOcultarBarra)
        puntoDoble = Ajustes.puntoDoble(this)
        atajosActivos = Ajustes.atajosActivos(this)
        val crudo = Ajustes.prefs(this).getString("atajos", null)
        if (crudo == null || crudo != atajosCrudo) {
            atajos = Ajustes.atajos(this).associate { it.first.lowercase() to it.second }
            atajosCrudo = Ajustes.prefs(this).getString("atajos", null)
        }
        copiadoActivo = Ajustes.portapapeles(this)
        limpiarClaves = Ajustes.limpiarClaves(this)
        aperturaActiva = Ajustes.apertura(this)
        emojisBarraActivo = Ajustes.emojisBarra(this)
        multilinea = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
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
        campoClave = esClave

        // Solo números: PIN, códigos, teléfonos y campos numéricos (como el teclado numérico del iPhone)
        val esPin = clase == InputType.TYPE_CLASS_NUMBER || clase == InputType.TYPE_CLASS_PHONE
        copiadoPermitido = copiadoActivo && !esPin && clase != InputType.TYPE_NULL
        val esFecha = clase == InputType.TYPE_CLASS_DATETIME
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val conAccion = !sinAccion && accion in setOf(
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT,
        )

        teclado?.apply {
            probLetras = null
            val rapida = Ajustes.escrituraRapida(this@TecladoService)
            // Escritura rápida: sin burbuja ni acentos al mantener (nada se interpone al escribir)
            vistaPrevia = Ajustes.vistaPrevia(this@TecladoService) && !rapida
            acentosAlMantener = !rapida
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
        ultimaCorreccionAuto = null
        ultimaCerrada = null
        anticipo = null
        buscandoEmoji = false
        consultaEmoji = ""
        teclado?.iconoApp = if (Ajustes.iconoEspacio(this)) iconoDe(appActual) else null
        cargarContactosSiHaceFalta()
        espacioAutomatico = false
        escribiendo = false
        barra?.visible(false, animar = false)
        volverABC()
        actualizar()
        // La vista previa de las teclas se prepara antes del primer toque (así la primera
        // y la segunda letra no esperan a que Android cree su capa)
        teclado?.prepararVistaPrevia()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        barra?.visible(false, animar = false)
        teclado?.ocultarPreview()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        manejador.removeCallbacks(tareaActualizar)
        manejador.removeCallbacks(tareaOcultarBarra)
        anticipo = null
        buscandoEmoji = false
        consultaEmoji = ""
        diferidas.clear()
        editando = false
        seleccionando = false
        teclado?.ocultarPreview()
        emojis?.cerrarTonos()
        teclado?.probLetras = null
        programarGuardado()
        espejoValido = false
        toques.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        try { portapapeles?.removePrimaryClipChangedListener(alCambiarCopiado) } catch (_: Exception) { }
        motor.removeCallbacks(tareaGuardar)
        diccionario.interrumpir()
        motor.post { try { diccionario.guardar() } catch (_: Throwable) { } }
        hiloMotor.quitSafely()
        hiloRetro.quitSafely()
        try { sonidos?.release() } catch (_: Throwable) { }
        sonidos = null
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
        anticipo = null
        actualizar()
    }

    // ---------- Copia local del texto ----------

    private fun enMotor(tarea: () -> Unit) {
        motor.post { try { tarea() } catch (e: Throwable) { Registro.anotar("sugerencias", e) } }
    }

    /**
     * Guardar lo aprendido un ratito después de cerrar el teclado (si lo vuelves a abrir y
     * cerrar varias veces seguidas, se guarda una sola vez): menos escrituras, menos batería.
     */
    private fun programarGuardado() {
        motor.removeCallbacks(tareaGuardar)
        motor.postDelayed(tareaGuardar, ESPERA_GUARDADO)
    }

    // ---------- Lo copiado ----------

    /** Lo que copiaste hace poco (y que todavía no pegaste ni descartaste), o null */
    private fun copiadoReciente(): String? {
        if (!copiadoPermitido) return null
        val cm = portapapeles ?: return null
        val desc = try { cm.primaryClipDescription } catch (_: Exception) { null } ?: return null
        val hora = desc.timestamp
        if (hora <= 0L || hora <= copiadoUsado) return null
        if (System.currentTimeMillis() - hora > VIGENCIA_COPIADO) return null
        if (hora == copiadoHora) return copiadoTexto
        // Solo se lee el contenido cuando cambió (leer el portapapeles es una consulta al sistema)
        copiadoHora = hora
        copiadoTexto = null
        copiadoSensible = false
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return null
        val texto = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        if (texto.isNullOrBlank() || texto.length > 5000) return null
        copiadoSensible = try { desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true } catch (_: Exception) { false }
        copiadoTexto = texto
        if (copiadoSensible) programarLimpieza(hora)
        return texto
    }

    /** En un minuto se borra la contraseña del portapapeles (para que no quede ahí olvidada) */
    private fun programarLimpieza(hora: Long) {
        if (!limpiarClaves || hora <= 0L) return
        horaALimpiar = hora
        manejador.removeCallbacks(tareaLimpiar)
        manejador.postDelayed(tareaLimpiar, 60_000L)
    }

    private fun sugerenciaCopiado(): Sugerencia? {
        val t = copiadoReciente() ?: return null
        val mostrar = if (copiadoSensible || campoClave) "•".repeat(minOf(t.length, 10)) else t
        return Sugerencia(mostrar, portapapeles = true, pegar = t)
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
        if (!coloresLeidos) leerColores()
        if (coloresApps.containsKey(paquete)) return coloresApps[paquete]
        val color = try { calcularColor(paquete) } catch (_: Exception) { null }
        coloresApps[paquete] = color
        guardarColores()
        return color
    }

    /**
     * El color de cada app se recuerda entre reinicios del teclado: sacarlo del ícono cuesta
     * (hay que dibujarlo), así que se calcula una sola vez por app. "-" = la app no tiene color.
     */
    private var coloresLeidos = false

    private fun leerColores() {
        coloresLeidos = true
        val crudo = Ajustes.prefs(this).getString("colores_apps", null) ?: return
        for (par in crudo.split('|')) {
            val i = par.lastIndexOf('=')
            if (i <= 0) continue
            val v = par.substring(i + 1)
            coloresApps[par.substring(0, i)] = if (v == "-") null else (v.toLongOrNull(16)?.toInt() ?: continue)
        }
    }

    private fun guardarColores() {
        // Como mucho 120 apps (las más nuevas reemplazan a las viejas)
        val entradas = coloresApps.entries.toList().takeLast(120)
        val texto = entradas.joinToString("|") { (k, v) ->
            k + "=" + (v?.let { java.lang.Long.toHexString(it.toLong() and 0xFFFFFFFFL) } ?: "-")
        }
        Ajustes.prefs(this).edit().putString("colores_apps", texto).apply()
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
        // Todos los puntos de una sola vez (mucho más rápido que uno por uno)
        val pixeles = IntArray(lado * lado)
        bmp.getPixels(pixeles, 0, lado, 0, 0, lado, lado)
        for (p in pixeles) {
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
        if (!mayusPermitida || buscandoEmoji) { k.mayusAuto(false); return }
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
        // Buscando un emoji: la barra muestra lo que buscas y los emojis que coinciden
        if (buscandoEmoji) {
            b.visible(true)
            val lista = if (consultaEmoji.isBlank()) EmojiPanel.recientes(this).take(6)
                else EmojiPalabras.buscar(consultaEmoji, 6)
            b.mostrarBusqueda(consultaEmoji, lista)
            return
        }
        b.mostrarBusqueda(null, emptyList())
        val enEmojis = emojis?.visibility == View.VISIBLE
        val modo = teclado?.modo
        val enPad = modo == Modo.PIN || modo == Modo.TELEFONO_SIMBOLOS
        // Barra de edición: con texto marcado, o mientras mueves el cursor con la barra espaciadora
        val conSeleccion = selIni >= 0 && selIni != selFin
        if (!enEmojis && !enPad && !campoClave && (conSeleccion || editando || seleccionando)) {
            b.visible(true)
            b.mostrar(barraEdicion(conSeleccion))
            return
        }
        // Lo que copiaste hace poco aparece en la barra para pegarlo con un toque (como en iPhone):
        // al abrir el teclado o con el campo vacío
        if (!enEmojis && !enPad && (!escribiendo || antes.isBlank())) {
            val clip = sugerenciaCopiado()
            if (clip != null) {
                b.visible(true)
                b.mostrar(listOf(clip, null, null))
                return
            }
        }
        // La barra aparece cuando empiezas a escribir (no al abrir el teclado) y se esconde
        // al enviar, al mover el cursor o al cerrar el teclado. Mientras escribes funciona
        // completa: sugerencias, predicción de la siguiente palabra y deshacer la autocorrección.
        // Barra sostenida: al enviar o dejar de escribir, la barra se queda unos segundos (con la
        // predicción de lo que sigue) en vez de desaparecer de golpe. Así no "salta" tanto.
        val desdeEscritura = SystemClock.uptimeMillis() - ultimaEscritura
        val sostener = !escribiendo && barraSostenida && b.aLaVista && desdeEscritura < SOSTENER_MS
        if (!sugerenciasActivas || enEmojis || enPad || (!escribiendo && !sostener)) {
            manejador.removeCallbacks(tareaOcultarBarra)
            b.visible(false)
            return
        }
        if (sostener) {
            manejador.removeCallbacks(tareaOcultarBarra)
            manejador.postDelayed(tareaOcultarBarra, SOSTENER_MS - desdeEscritura + 20)
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

        // Calculado mientras tenías el dedo en la tecla: se muestra ya, sin esperar al motor
        if (p.isNotEmpty()) {
            val a = anticipo
            if (a != null && diccionario.vigente(a.turno) && SystemClock.uptimeMillis() - a.hora < 800) {
                if (a.clave == claveDe(previa2De(antes), previa, p)) {
                    b.mostrarEscrito(Sugerencia(p, literal = true))
                    // Primero se marca y después se mira el resultado (así nunca se pierde)
                    a.mostrar = true
                    a.resultado?.let { mostrarResultado(it, a.op) }
                    return
                }
                // Ya tocaste la letra siguiente (escribes rápido): esta palabra a medias no se
                // calcula, el motor ya está con la que viene
                if (a.marca > marcaTexto) {
                    b.mostrarEscrito(Sugerencia(p, literal = true))
                    return
                }
            }
        }
        anticipo = null

        // El cálculo anterior (si aún corre) ya no sirve: se corta y se pide uno nuevo
        diccionario.interrumpir()
        val t = diccionario.turno()

        // Campo vacío: tus 3 emojis más usados, a un toque
        if (p.isEmpty() && emojisFrecuentesActivo && antes.isBlank()) {
            val frecuentes = EmojiPanel.recientes(this).take(3)
            if (frecuentes.size == 3) {
                anticipo = null
                b.mostrar(frecuentes.map { Sugerencia(it, emoji = true) })
                return
            }
        }

        if (p.isEmpty()) {
            // Predicción de la siguiente palabra, como en iPhone
            val prev = if (inicio) "" else previa
            val prev2 = if (inicio) "" else previa2De(antes)
            val uc = ultimaCorreccion
            val deshacer = if (uc != null && antes.endsWith(uc.second)) Sugerencia(uc.first.trim(), literal = true, revertir = true) else null
            // La palabra que acabas de escribir pide un emoji ("jaja " → 😂), como en iPhone
            val emo = if (emojisBarraActivo && antes.endsWith(" ")) EmojiPalabras.para(previa) else null
            motor.post {
                if (!diccionario.vigente(t)) return@post
                val sig = try {
                    diccionario.siguientes(prev, 3, prev2).map { if (inicio) it.replaceFirstChar { c -> c.uppercase() } else it }
                } catch (e: Throwable) { Registro.anotar("predicción", e); emptyList() }
                manejador.post {
                    if (!diccionario.vigente(t)) return@post
                    val derecha = (if (deshacer != null) sig.getOrNull(1) else sig.getOrNull(2))?.let { Sugerencia(it) }
                    b.mostrar(listOf(
                        deshacer ?: sig.getOrNull(1)?.let { Sugerencia(it) },
                        sig.getOrNull(0)?.let { Sugerencia(it) },
                        emo?.let { Sugerencia(it, emoji = true) } ?: derecha,
                    ))
                }
            }
            return
        }

        // Mientras se calcula, la barra ya muestra lo que escribiste (respuesta inmediata)
        b.mostrarEscrito(Sugerencia(p, literal = true))
        val previa2 = previa2De(antes)
        val toq = toquesPara(p)
        val op = opcionesActuales()
        motor.post {
            val r = try {
                calcularPalabra(p, previa, previa2, t, toq, op) { probs ->
                    // Las letras probables primero: el teclado las usa en el siguiente toque
                    manejador.post { if (diccionario.vigente(t)) teclado?.probLetras = probs }
                }
            } catch (e: Throwable) { Registro.anotar("sugerencias", e); null } ?: return@post
            manejador.post { if (diccionario.vigente(t)) mostrarResultado(r, op) }
        }
    }

    private fun opcionesActuales() = Opciones(
        revisar = revisarActiva, aplicar = autocorreccionActiva, linea = enLineaActiva,
        enLetras = teclado?.modo == Modo.LETRAS, emojisBarra = emojisBarraActivo,
    )

    private fun claveDe(previa2: String, previa: String, p: String) = "$previa2\u0001$previa\u0001$p"

    /** En el hilo del motor: corrección, sugerencias y letras probables de [p] (null si se cortó) */
    private fun calcularPalabra(
        p: String, previa: String, previa2: String, t: Int, toq: Array<FloatArray>?, op: Opciones,
        alProbs: ((FloatArray) -> Unit)? = null,
    ): Resultado? {
        if (!diccionario.vigente(t)) return null
        val probs = if (op.enLetras) {
            try { diccionario.letrasProbables(p, t) } catch (_: Throwable) { null }
        } else null
        if (!diccionario.vigente(t)) return null
        if (probs != null) alProbs?.invoke(probs)
        val corr = if (op.revisar) diccionario.corregir(p, previa, previa2, t, toq) else null
        if (!diccionario.vigente(t)) return null
        val otras = diccionario.sugerir(p, 4, previa, previa2, t, toq).filter { it != corr && it != p }
        // Predicción en línea: la palabra que más probablemente estás escribiendo
        var completa: String? = null
        if (op.linea && corr == null && p.length >= 2) {
            val top = otras.firstOrNull()
            if (top != null && top.length >= p.length + 2 &&
                top.substring(0, p.length).equals(p, ignoreCase = true) && !diccionario.conocida(p)
            ) completa = top
        }
        if (!diccionario.vigente(t)) return null
        return Resultado(p, previa, previa2, probs, corr, otras, completa)
    }

    /** En el hilo principal: pone en la barra lo calculado y deja lista la corrección del espacio */
    private fun mostrarResultado(r: Resultado, op: Opciones) {
        val b = barra ?: return
        if (op.enLetras && r.probs != null) teclado?.probLetras = r.probs
        val p = r.p
        val c = r.corr
        val comp = r.completa
        val lista = r.otras
        calculada = Calculada(p, r.previa, r.previa2, if (op.aplicar) c else null, comp)
        if (comp != null) {
            b.mostrar(listOf(
                Sugerencia(p, literal = true, resto = comp.substring(p.length)),
                lista.getOrNull(1)?.let { Sugerencia(it) },
                lista.getOrNull(2)?.let { Sugerencia(it) },
            ))
            return
        }
        // Con Autocorrección la corrección va resaltada al centro (se pone sola al dar espacio);
        // solo con Revisar ortografía aparece como sugerencia para tocarla
        val centro = c?.let { Sugerencia(it, destacada = op.aplicar) } ?: lista.getOrNull(0)?.let { Sugerencia(it) }
        // Emoji de la palabra (o de su corrección) a la derecha: "corazon" → ❤️
        val emo = if (op.emojisBarra) EmojiPalabras.para(p) ?: EmojiPalabras.para(c) else null
        val derecha = emo?.let { Sugerencia(it, emoji = true) }
            ?: (if (c != null) lista.getOrNull(0) else lista.getOrNull(1))?.let { Sugerencia(it) }
        b.mostrar(listOf(Sugerencia(p, literal = true), centro, derecha))
    }

    /** El dedo tocó una letra: se calcula ya, como si estuviera escrita */
    override fun anticipar(s: String, x: Float, y: Float) {
        if (buscandoEmoji || !sugerenciasActivas || editando || seleccionando || campoClave) return
        if (currentInputConnection == null || s.length != 1 || !s[0].isLetter()) return
        if (emojis?.visibility == View.VISIBLE) return
        val texto = antes(120) + s
        val (previa, p) = palabrasDe(texto)
        if (p.isEmpty()) return
        // Las sustituciones de texto las resuelve el camino de siempre
        if (buscarAtajo(tokenDe(texto)) != null) return
        val previa2 = previa2De(texto)
        val clave = claveDe(previa2, previa, p)
        if (anticipo?.clave == clave) return
        val lista = ArrayList(toques)
        lista.add(floatArrayOf(x, y, s[0].lowercaseChar().code.toFloat()))
        val toq = toquesPara(p, lista)
        diccionario.interrumpir()
        val t = diccionario.turno()
        val op = opcionesActuales()
        val a = Anticipo(clave, t, ++marca, SystemClock.uptimeMillis(), op)
        anticipo = a
        motor.post {
            val r = try {
                calcularPalabra(p, previa, previa2, t, toq, op)
            } catch (e: Throwable) { Registro.anotar("anticipar", e); null } ?: return@post
            // Primero se guarda y después se mira si ya hay que mostrarlo (así nunca se pierde)
            a.resultado = r
            if (a.mostrar) manejador.post { mostrarAnticipo(a, r) }
        }
    }

    private fun mostrarAnticipo(a: Anticipo, r: Resultado) {
        if (anticipo !== a || !diccionario.vigente(a.turno) || buscandoEmoji) return
        // Solo si en pantalla sigue la misma palabra
        val (previa, p) = palabrasDe(antes(120))
        if (p != r.p || previa != r.previa) return
        mostrarResultado(r, a.op)
    }

    /** Calcula en el motor la corrección de una palabra ya cerrada y la pone apenas esté */
    private fun corregirDespues(d: Diferida, toq: Array<FloatArray>?) {
        diferidas.add(d)
        diccionario.interrumpir()
        // Adelante de todo: es lo primero que hace el motor (y no se corta aunque sigas escribiendo)
        motor.postAtFrontOfQueue {
            val corr = try {
                diccionario.corregir(d.palabra, d.previa, d.previa2, diccionario.turno(), toq, cortable = false)
            } catch (e: Throwable) { Registro.anotar("autocorrección", e); null }
            manejador.post { aplicarDiferida(d, corr) }
        }
    }

    private fun aplicarDiferida(d: Diferida, corr: String?) {
        if (!diferidas.remove(d)) return
        val orig = d.palabra + d.sufijo
        // Lo que escribiste después del espacio (se vuelve a poner igual detrás de la corrección)
        val extra = if (selIni >= 0 && selIni == selFin && espejoValido) selIni - d.fin else -1
        var final = d.palabra
        if (corr != null && corr != d.palabra && autocorreccionActiva && currentInputConnection != null &&
            extra in 0..80
        ) {
            val tramo = antes(extra + orig.length)
            if (tramo.length == extra + orig.length && tramo.startsWith(orig)) {
                val resto = tramo.substring(orig.length)
                val nuevo = corr + d.sufijo
                val guardados = ArrayList(toques)
                val calc = calculada
                reemplazar(tramo.length, nuevo + resto)
                // La palabra que estás escribiendo ahora no cambió: se conservan sus toques
                toques.clear()
                toques.addAll(guardados)
                if (extra > 0) calculada = calc
                // Las otras pendientes que están más adelante se corren
                val delta = nuevo.length - orig.length
                for (o in diferidas) if (o.fin > d.fin - orig.length) o.fin += delta
                if (extra == 0) {
                    ultimaCorreccion = Pair(d.palabra, nuevo)
                    ultimaCorreccionAuto = ultimaCorreccion
                }
                final = corr
                val escrito = d.palabra
                ultimaCerrada?.let { if (it.palabra == escrito) it.aprendida = corr }
                enMotor {
                    diccionario.notarCorreccion(escrito, corr)
                    diccionario.correccionAplicada()
                }
            }
        }
        aprenderCerrada(final, d.previa, d.previa2, d.sufijo, alFinal = extra == 0)
    }

    /** Aprende la palabra que se acaba de cerrar (en segundo plano) */
    private fun aprenderCerrada(palabra: String, previa: String, previa2: String, sufijo: String, alFinal: Boolean) {
        if (!sugerenciasActivas && !autocorreccionActiva) return
        val aMitad = previa.isNotEmpty()
        enMotor {
            diccionario.aprender(palabra)
            diccionario.aprenderPar(previa, palabra, previa2)
            diccionario.notarMayuscula(palabra, aMitad)
        }
        // Si borras el espacio enseguida, se deshace lo aprendido (era un error)
        if (alFinal && sufijo == " ") ultimaAprendida = Aprendida(previa2, previa, palabra, palabra + sufijo)
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

        // ¿Borraste la palabra anterior y escribiste esta en su lugar? El cerebro aprende solo
        val inicio = if (selIni >= 0 && selIni == selFin) selIni - palabra.length else -1
        notarReescritura(palabra, previa, previa2, inicio)

        // 2) Autocorrección de la palabra (con la posición exacta de cada toque, como Gboard).
        //    Si el motor ya la calculó se usa al instante; si no (escribes muy rápido), el
        //    espacio se escribe ya y la corrección llega enseguida: el toque nunca espera.
        var nuevaPalabra = palabra
        val hecha = calculada
        val yaCalculada = hecha != null && hecha.palabra == palabra && hecha.previa == previa && hecha.previa2 == previa2
        val corr = if (autocorreccionActiva && yaCalculada) hecha!!.correccion else null
        var diferir = autocorreccionActiva && !yaCalculada
        if (corr != null) {
            nuevaPalabra = corr
            // El cerebro anota este error tuyo (si lo deshaces, se resta) y se calibra
            enMotor {
                diccionario.notarCorreccion(palabra, corr)
                diccionario.correccionAplicada()
            }
        }
        else if (enLineaActiva && sufijo == " ") completadaDe(palabra, previa)?.let { nuevaPalabra = it; diferir = false }

        // 3) Tildes que dependen de la palabra anterior ("que tal" → "qué tal")
        var nuevaPrevia = previa
        if (autocorreccionActiva && previa.isNotEmpty()) {
            diccionario.interrumpir()
            diccionario.corregirPar(previa, nuevaPalabra)?.let { (a, b) ->
                nuevaPrevia = a
                nuevaPalabra = b
            }
        }

        // Las tildes por la palabra anterior ya la cambiaron: es una palabra conocida
        if (nuevaPalabra != palabra || nuevaPrevia != previa) diferir = false

        if (diferir) {
            // Se escribe tal cual ya; lo aprendido y el deshacer se anotan cuando llegue la corrección
            reemplazar(0, sufijo)
            ultimaCorreccion = null
            if (selIni >= 0 && selIni == selFin && espejoValido) {
                corregirDespues(Diferida(palabra, sufijo, previa, previa2, selIni), toquesPara(palabra, misToques))
            } else {
                aprenderCerrada(palabra, previa, previa2, sufijo, alFinal = true)
            }
            return
        }

        ultimaCerrada?.let { if (it.palabra == palabra) it.aprendida = nuevaPalabra }
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
        ultimaCorreccionAuto = if (corr != null) ultimaCorreccion else null
    }

    /**
     * Autoaprendizaje: si la palabra que acabas de cerrar empieza donde empezaba la anterior,
     * es que borraste la anterior y la reescribiste. Si se parecen ("tqrjeta" → "tarjeta"),
     * la primera era un error tuyo: se deja de aprender y la próxima vez se corrige sola.
     */
    private fun notarReescritura(palabra: String, previa: String, previa2: String, inicio: Int) {
        val ahora = SystemClock.uptimeMillis()
        val u = ultimaCerrada
        ultimaCerrada = if (inicio >= 0) Cerrada(palabra, previa, previa2, inicio, ahora) else null
        if (u == null || inicio < 0 || u.inicio != inicio || ahora - u.hora > 25_000L) return
        if (!sugerenciasActivas && !autocorreccionActiva) return
        if (!u.previa.equals(previa, ignoreCase = true)) return
        val a = u.palabra
        if (a.equals(palabra, ignoreCase = true) || a.length < 3 || palabra.length < 3) return
        if (!Cerebro.parecidas(Cerebro.sinTildes(a.lowercase()), Cerebro.sinTildes(palabra.lowercase()))) return
        val quitar = !u.desaprendida
        val aprendida = u.aprendida
        enMotor {
            if (diccionario.pausado) return@enMotor
            // Lo que se había aprendido de la palabra borrada no cuenta
            if (quitar) diccionario.desaprender(aprendida, u.previa, u.previa2)
            // Solo si lo nuevo es una palabra de verdad y lo borrado no (si no, solo cambiaste de idea)
            if (diccionario.conocida(palabra) && !diccionario.conocida(a)) {
                diccionario.notarCorreccion(a, palabra, 1.5f)
            }
        }
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

    /**
     * Signos de apertura, como se escribe en español: al cerrar con ? o ! se pone ¿ o ¡
     * donde empieza la pregunta. "que haces?" → "¿qué haces?" y si la pregunta va después
     * de una coma: "Hola, como estas?" → "Hola, ¿cómo estás?".
     */
    private fun abrirSigno(cierre: Char) {
        if (currentInputConnection == null) return
        val apertura = if (cierre == '?') '¿' else '¡'
        val antes = antes(200)
        if (antes.isEmpty()) return
        // "??" o "!!": es énfasis, ya se abrió con el primero
        if (antes.last() == '?' || antes.last() == '!') return
        var i = antes.length
        while (i > 0 && antes[i - 1] !in ".!?¿¡\n…") i--
        // Ya tiene su signo de apertura
        if (i > 0 && (antes[i - 1] == '¿' || antes[i - 1] == '¡')) return
        // Si no se ve dónde empieza la oración (texto muy largo), mejor no tocar nada
        if (i == 0 && !espejoCompleto) return
        val oracion = antes.substring(i)
        if (oracion.none { it.isLetter() }) return
        // Enlaces, correos y usuarios: no
        if (oracion.contains("://") || oracion.contains('@') || oracion.contains("www.")) return
        var a = 0
        while (a < oracion.length && (oracion[a].isWhitespace() || oracion[a] in "\"'“‘«(")) a++
        if (a >= oracion.length) return

        // ¿La pregunta empieza después de una coma? ("Oye, cuándo vienes?")
        var desde = a
        val coma = oracion.lastIndexOf(',')
        if (coma > a) {
            var b = coma + 1
            while (b < oracion.length && oracion[b].isWhitespace()) b++
            var e = b
            while (e < oracion.length && oracion[e].isLetter()) e++
            if (e > b) {
                val w = sinMarcas(oracion.substring(b, e))
                // "por" solo cuenta si sigue "que": "Oye, por qué no vienes?"
                val porQue = w == "por" && sinMarcas(oracion.substring(e).trimStart().takeWhile { it.isLetter() }) == "que"
                if (w in PREGUNTAS || porQue) desde = b
            }
        }
        if (desde >= oracion.length) return
        var resto = oracion.substring(desde)
        // La palabra de pregunta después de la coma también lleva tilde: "como" → "cómo"
        // (al inicio de una pregunta ya la puso acentuarPregunta)
        if (autocorreccionActiva && (desde != a || cierre == '!')) {
            var b = 0
            // "por que" → la tilde va en "que"
            if (sinMarcas(resto.takeWhile { it.isLetter() }) == "por") {
                b = resto.indexOfFirst { !it.isLetter() }.let { if (it < 0) resto.length else it }
                while (b < resto.length && resto[b].isWhitespace()) b++
            }
            var e = b
            while (e < resto.length && resto[e].isLetter()) e++
            // En las exclamaciones solo "qué" y "cuánto": "¡qué bueno!", "¡cuánto tiempo!"
            val vale = cierre == '?' || sinMarcas(resto.substring(b, e)) in EXCLAMATIVAS
            if (e > b && vale) {
                diccionario.interrumpir()
                val conTilde = try { diccionario.interrogativa(resto.substring(b, e)) } catch (_: Throwable) { null }
                if (conTilde != null) resto = resto.substring(0, b) + conTilde + resto.substring(e)
            }
        }
        reemplazar(oracion.length - desde, apertura + resto)
    }

    private fun sinMarcas(w: String): String =
        java.text.Normalizer.normalize(w.lowercase(), java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private val EXCLAMATIVAS = setOf("que", "cuanto", "cuanta", "cuantos", "cuantas")

    private val PREGUNTAS = setOf(
        "que", "como", "cuando", "donde", "adonde", "quien", "quienes", "cual", "cuales",
        "cuanto", "cuanta", "cuantos", "cuantas", "acaso", "verdad", "no",
    )

    /** Elegiste el emoji de la barra: se escribe después de la palabra ("jaja 😂 ") */
    private fun elegirEmoji(e: String) {
        val p = palabrasDe(antes(60)).second
        // Si estabas a mitad de palabra, primero se cierra (con su autocorrección)
        if (p.isNotEmpty()) cerrarPalabra(" ")
        else if (antes(1).let { it.isNotEmpty() && !it[0].isWhitespace() }) reemplazar(0, " ")
        reemplazar(0, "$e ")
        ultimaCorreccion = null
        ultimaAprendida = null
        ultimoDeslizado = null
        espacioAutomatico = true
        escribiendo = true
        ultimaEscritura = SystemClock.uptimeMillis()
        EmojiPanel.sumarReciente(this, e)
        retro(Tipo.LETRA)
    }

    // ---------- Acciones de las teclas ----------

    /** Comilla de apertura o de cierre según lo que hay antes (Puntuación inteligente) */
    private fun abreComilla(): Boolean {
        val a = antes(1)
        return a.isEmpty() || a[0].isWhitespace() || a[0] in "([{¿¡“‘—-"
    }

    override fun texto(s: String, x: Float, y: Float) {
        if (buscandoEmoji) {
            if (consultaEmoji.length < 30 && s.length == 1 && s[0].isLetterOrDigit()) consultaEmoji += s.lowercase()
            actualizar()
            return
        }
        marcaTexto = ++marca
        salirDeEdicion()
        teclado?.probLetras = null
        escribiendo = true
        ultimaEscritura = SystemClock.uptimeMillis()
        barra?.ultimaTeclaMs = ultimaEscritura
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
        if ((c == "?" || c == "!") && puntuacionActiva && aperturaActiva) abrirSigno(c[0])
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
        if (buscandoEmoji) {
            if (consultaEmoji.isNotEmpty() && !consultaEmoji.endsWith(" ")) consultaEmoji += " "
            actualizar()
            return
        }
        salirDeEdicion()
        teclado?.probLetras = null
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
        if (buscandoEmoji) {
            consultaEmoji = consultaEmoji.dropLast(1)
            actualizar()
            return
        }
        salirDeEdicion()
        teclado?.probLetras = null
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
        val fueAuto = uc != null && uc === ultimaCorreccionAuto
        ultimaCorreccionAuto = null
        val ua = ultimaAprendida
        ultimaAprendida = null
        if (uc != null) {
            // Deshacer la autocorrección como en iPhone
            if (antes(uc.second.length) == uc.second) {
                toques.clear()
                reemplazar(uc.second.length, uc.first)
                // Volviste a tu palabra: no fue una reescritura
                ultimaCerrada = null
                // La corrección no era lo que querías: se olvida y se aprende lo tuyo
                enMotor {
                    if (fueAuto) diccionario.correccionDeshecha()
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
            ultimaCerrada?.let { if (it.aprendida.equals(ua.palabra, ignoreCase = true)) it.desaprendida = true }
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
        // Si se sabe exactamente qué hay antes del cursor, se borra directo (una sola llamada a la
        // app, como Gboard): más rápido que simular la tecla, sobre todo al mantener presionado
        val clase = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        if (n > 0 && clase != InputType.TYPE_NULL) ic.deleteSurroundingText(n, 0)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
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
        if (buscandoEmoji) {
            consultaEmoji = ""
            actualizar()
            return
        }
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
        moverConTecla(tecla, Math.abs(pasos))
        olvidarEspejo()
        if (!editando) {
            editando = true
            actualizarBarra()
        }
    }

    /** Mueve el cursor; en modo Seleccionar va marcando el texto (Mayús + flecha) */
    private fun moverConTecla(tecla: Int, veces: Int) {
        if (!seleccionando) {
            repeat(veces) { sendDownUpKeyEvents(tecla) }
            return
        }
        val ic = currentInputConnection ?: return
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        repeat(veces) {
            val t = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, tecla, 0, meta))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, tecla, 0, meta))
        }
    }

    override fun moverCursorVertical(pasos: Int) {
        // En un campo de una sola línea, arriba/abajo movería el foco a otra parte de la app
        if (!multilinea || pasos == 0) return
        espacioAutomatico = false
        ultimaAprendida = null
        ultimaCorreccion = null
        ultimoDeslizado = null
        calculada = null
        toques.clear()
        val tecla = if (pasos > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        moverConTecla(tecla, Math.abs(pasos))
        olvidarEspejo()
        if (!editando) {
            editando = true
            actualizarBarra()
        }
    }

    override fun enter() {
        if (buscandoEmoji) {
            salirBusqueda()
            return
        }
        salirDeEdicion()
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        ultimaCorreccion = null
        espacioAutomatico = false
        ultimoDeslizado = null
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!sinAccion && accion != EditorInfo.IME_ACTION_NONE && accion != EditorInfo.IME_ACTION_UNSPECIFIED) {
            toques.clear()
            ultimaCerrada = null
            // Enviaste o buscaste: dejaste de escribir. La barra se queda un momento (con tus
            // emojis frecuentes o la predicción) y luego se esconde sola
            escribiendo = false
            ultimaEscritura = SystemClock.uptimeMillis()
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

    // ---------- Buscar emojis escribiendo (como iOS) ----------

    /** Tocaste "Buscar emoji": vuelven las letras y lo que escribes busca en vez de escribir */
    override fun buscarEmoji() {
        emojis?.cerrarTonos()
        emojis?.animate()?.cancel()
        emojis?.visibility = View.GONE
        buscandoEmoji = true
        consultaEmoji = ""
        teclado?.let {
            it.modo = Modo.LETRAS
            it.mayusAuto(false)
            aparecer(it, desdeDerecha = false)
        }
        actualizarBarra()
    }

    /** Tocaste la búsqueda en la barra (o intro): vuelves al panel de emojis */
    override fun salirBusqueda() {
        buscandoEmoji = false
        consultaEmoji = ""
        retro(Tipo.A_LETRAS)
        abrirEmojis()
    }

    /** Elegiste un emoji de la búsqueda: se escribe y puedes seguir buscando */
    override fun emojiBuscado(e: String) {
        ultimaCorreccion = null
        ultimaAprendida = null
        ultimoDeslizado = null
        espacioAutomatico = false
        toques.clear()
        reemplazar(0, e)
        EmojiPanel.sumarReciente(this, e)
        retro(Tipo.LETRA)
    }

    // ---------- Historial del portapapeles ----------

    private fun historial() = Portapapeles(Ajustes.prefs(this).getString(CLAVE_HISTORIAL, null))

    private fun guardarHistorial(h: Portapapeles) {
        Ajustes.prefs(this).edit().putString(CLAVE_HISTORIAL, h.comoTexto()).apply()
    }

    /** Copiaste algo (en cualquier app): se suma al historial, salvo contraseñas y cosas privadas */
    private fun guardarEnHistorial() {
        try {
            if (!Ajustes.historialPortapapeles(this)) return
            val cm = portapapeles ?: return
            val desc = cm.primaryClipDescription ?: return
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            // Copiado desde un campo de contraseña donde está el teclado: no se guarda
            if (campoClave && isInputViewShown) return
            if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
                !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return
            val texto = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (texto.isNullOrBlank()) return
            val h = historial()
            if (h.agregar(texto)) guardarHistorial(h)
        } catch (_: Exception) { }
    }

    override fun historialCopiado(): List<Portapapeles.Elemento> =
        if (Ajustes.historialPortapapeles(this)) historial().elementos() else emptyList()

    override fun fijarCopiado(texto: String): Boolean {
        val h = historial()
        val fijado = h.alternarFijado(texto)
        guardarHistorial(h)
        return fijado
    }

    override fun quitarCopiado(texto: String) {
        val h = historial()
        h.quitar(texto)
        guardarHistorial(h)
    }

    /** Tocaste algo del historial: se pega y vuelven las letras */
    override fun pegar(texto: String) {
        if (currentInputConnection == null) return
        ultimaCorreccion = null
        ultimaAprendida = null
        ultimoDeslizado = null
        espacioAutomatico = false
        toques.clear()
        escribiendo = true
        ultimaEscritura = SystemClock.uptimeMillis()
        reemplazar(0, texto)
        retro(Tipo.LETRA)
        volverABC()
    }

    // ---------- Contactos e ícono de la app ----------

    /**
     * Lee los nombres de tus contactos (si lo activaste y diste permiso) para no corregirlos y
     * escribirlos con mayúscula. Se lee como mucho cada 6 horas, en el hilo del motor.
     */
    private fun cargarContactosSiHaceFalta() {
        val activo = Ajustes.nombresContactos(this) &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!activo) {
            if (contactosLeidos != 0L) {
                contactosLeidos = 0L
                enMotor { diccionario.ponerContactos(emptyList()) }
            }
            return
        }
        val ahora = SystemClock.elapsedRealtime()
        if (contactosLeidos != 0L && ahora - contactosLeidos < 6 * 60 * 60 * 1000L) return
        contactosLeidos = ahora
        enMotor {
            val nombres = ArrayList<String>()
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
            )?.use { c ->
                while (c.moveToNext() && nombres.size < 5000) c.getString(0)?.let { nombres.add(it) }
            }
            diccionario.ponerContactos(nombres)
        }
    }

    /** El ícono de la app (se guarda para no volver a dibujarlo) */
    private fun iconoDe(paquete: String?): Bitmap? {
        if (paquete == null || paquete == packageName) return null
        if (iconos.containsKey(paquete)) return iconos[paquete]
        val bmp = try {
            val d = packageManager.getApplicationIcon(paquete)
            val lado = 64
            Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888).also { b ->
                d.setBounds(0, 0, lado, lado)
                d.draw(Canvas(b))
            }
        } catch (_: Exception) { null }
        if (iconos.size >= 12) iconos.remove(iconos.keys.first())
        iconos[paquete] = bmp
        return bmp
    }

    override fun abrirEmojis() {
        buscandoEmoji = false
        consultaEmoji = ""
        teclado?.ocultarPreview()
        emojis?.refrescar()
        teclado?.visibility = View.INVISIBLE
        emojis?.let { aparecer(it, desdeDerecha = true) }
        actualizarBarra()
    }

    /** Mantener presionada la tecla de emojis: se abren los ajustes del teclado */
    override fun abrirAjustes() {
        try {
            requestHideSelf(0)
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        } catch (e: Exception) { Registro.anotar("abrir los ajustes", e) }
    }

    private fun puedePegar(): Boolean = try { portapapeles?.hasPrimaryClip() == true } catch (_: Exception) { false }

    /** Los tres botones de la barra de edición, según haya texto marcado o no */
    private fun barraEdicion(conSeleccion: Boolean): List<Sugerencia?> {
        val pegar = if (puedePegar()) Sugerencia("Pegar", accion = ED_PEGAR) else null
        return if (conSeleccion) listOf(
            Sugerencia("Cortar", accion = ED_CORTAR),
            Sugerencia("Copiar", accion = ED_COPIAR),
            pegar,
        ) else listOf(
            Sugerencia("Seleccionar todo", accion = ED_TODO),
            // Encendido: mover el cursor marca el texto (se ve resaltado, como un interruptor)
            Sugerencia(if (seleccionando) "Marcando…" else "Seleccionar", accion = ED_SELECCIONAR, destacada = seleccionando),
            pegar,
        )
    }

    private fun editar(accion: Int) {
        val ic = currentInputConnection ?: return
        toques.clear()
        calculada = null
        ultimaCorreccion = null
        ultimaAprendida = null
        ultimoDeslizado = null
        espacioAutomatico = false
        when (accion) {
            ED_TODO -> { ic.performContextMenuAction(android.R.id.selectAll); editando = true }
            ED_SELECCIONAR -> { seleccionando = !seleccionando; editando = true }
            ED_CORTAR -> { ic.performContextMenuAction(android.R.id.cut); seleccionando = false }
            ED_COPIAR -> {
                ic.performContextMenuAction(android.R.id.copy)
                seleccionando = false
                // Lo que acabas de copiar no se vuelve a ofrecer en la barra
                copiadoUsado = maxOf(copiadoUsado, System.currentTimeMillis() + 3000L)
            }
            ED_PEGAR -> {
                ic.performContextMenuAction(android.R.id.paste)
                seleccionando = false
                copiadoUsado = maxOf(copiadoUsado, copiadoHora)
            }
        }
        olvidarEspejo()
        retro(Tipo.LETRA)
        actualizarBarra()
    }

    /** Al escribir de nuevo se cierra la barra de edición */
    private fun salirDeEdicion() {
        if (!editando && !seleccionando) return
        editando = false
        seleccionando = false
    }

    override fun volverABC() {
        emojis?.cerrarTonos()
        val estabaEnEmojis = emojis?.visibility == View.VISIBLE
        emojis?.animate()?.cancel()
        emojis?.visibility = View.GONE
        teclado?.let { if (estabaEnEmojis) aparecer(it, desdeDerecha = false) else { it.animate().cancel(); it.alpha = 1f; it.translationX = 0f; it.visibility = View.VISIBLE } }
        actualizarBarra()
    }

    /**
     * Cambio entre letras y emojis: la vista nueva entra deslizándose de costado con un
     * fundido, como en iOS (los emojis desde la derecha, las letras desde la izquierda).
     */
    private fun aparecer(v: View, desdeDerecha: Boolean) {
        v.animate().cancel()
        val d = resources.displayMetrics.density
        v.alpha = 0f
        v.translationY = 0f
        v.translationX = (if (desdeDerecha) 28f else -28f) * d
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).translationX(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
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
        if (currentInputConnection == null) return
        if (s.accion != 0) {
            editar(s.accion)
            return
        }
        toques.clear()
        if (s.portapapeles) {
            // Pegar lo copiado, como al tocarlo en la barra del iPhone
            copiadoUsado = maxOf(copiadoUsado, copiadoHora)
            val texto = s.pegar ?: return
            ultimaCorreccion = null
            ultimaAprendida = null
            ultimoDeslizado = null
            espacioAutomatico = false
            escribiendo = true
            ultimaEscritura = SystemClock.uptimeMillis()
            reemplazar(0, texto)
            // Pegaste una contraseña: se borra del portapapeles al minuto
            if (campoClave || copiadoSensible) programarLimpieza(copiadoHora)
            retro(Tipo.LETRA)
            return
        }
        if (s.emoji) {
            elegirEmoji(s.texto)
            return
        }
        if (s.revertir) {
            // Volver a lo que escribiste y aprenderlo para no corregirlo otra vez
            val uc = ultimaCorreccion ?: return
            val fueAuto = uc === ultimaCorreccionAuto
            ultimaCorreccionAuto = null
            ultimaCerrada = null
            val sufijo = uc.second.takeLastWhile { !it.isLetterOrDigit() }
            reemplazar(uc.second.length, uc.first + sufijo)
            val ua = ultimaAprendida
            enMotor {
                if (fueAuto) diccionario.correccionDeshecha()
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
        } else if (s.reemplaza < 0 && !s.texto.contains(' ')) {
            // Elegiste la palabra justo donde borraste otra: también cuenta como reescritura
            val ini = if (selIni >= 0 && selIni == selFin) selIni - palabra.length else -1
            notarReescritura(s.texto, previa, previa2, ini)
        }
        reemplazar(borrarN, s.texto + " ")
        // Elegir una sugerencia a propósito enseña más que escribirla (sobre todo la que está "entre comillas")
        val pv = previa
        val pv2 = previa2
        // Tocaste una palabra distinta a lo que escribías (no solo la completaste): es un error tuyo
        val escrito = palabra
        val esCorreccion = !s.literal && escrito.length >= 2 && s.reemplaza < 0 && !s.texto.contains(' ') &&
            !Cerebro.sinTildes(s.texto.lowercase()).startsWith(Cerebro.sinTildes(escrito.lowercase()))
        if (esCorreccion) enMotor { diccionario.notarCorreccion(escrito, s.texto, 2f) }
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
        if (s.portapapeles) {
            // Mantener presionado lo copiado: se quita de la barra
            copiadoUsado = maxOf(copiadoUsado, copiadoHora)
            retro(Tipo.BORRAR)
            actualizarBarra()
            return
        }
        enMotor { diccionario.olvidar(s.texto) }
        retro(Tipo.BORRAR)
        actualizarBarra()
    }

    // ---------- Deslizar para escribir ----------

    override fun deslizar(puntos: List<FloatArray>, proporcion: Float, mayus: Boolean) {
        if (buscandoEmoji) return
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

    // ---------- Respuesta del teclado (vibración y sonido) ----------

    /** Los efectos de vibración se arman una sola vez: letra, espacio/intro, borrar y teclas de función */
    private val efectos = arrayOfNulls<VibrationEffect>(4)

    private fun claseRetro(tipo: Tipo) = when (tipo) {
        Tipo.LETRA, Tipo.VACIO -> 0
        Tipo.ESPACIO, Tipo.ENTER -> 1
        Tipo.BORRAR -> 2
        else -> 3
    }

    /** Como en iPhone: el espacio y el intro se sienten un poco más firmes, las de función más suaves */
    private fun crearEfecto(clase: Int): VibrationEffect {
        val nivel = fuerzaVibracion
        return if (toqueFino == true) {
            val base = when (clase) { 1 -> 0.72f; 2 -> 0.6f; 3 -> 0.38f; else -> 0.5f }
            val por = when (nivel) { "suave" -> 0.55f; "fuerte" -> 1.5f; else -> 1f }
            val fuerza = (base * por).coerceIn(0.08f, 1f)
            // En "fuerte" el toque usa CLICK (más firme) en lugar de TICK
            val primitivo = if (nivel == "fuerte") VibrationEffect.Composition.PRIMITIVE_CLICK
                else VibrationEffect.Composition.PRIMITIVE_TICK
            VibrationEffect.startComposition().addPrimitive(primitivo, fuerza).compose()
        } else {
            val efecto = when (nivel) {
                "suave" -> VibrationEffect.EFFECT_TICK
                "fuerte" -> if (clase == 1) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                else -> if (clase == 1) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
            }
            VibrationEffect.createPredefined(efecto)
        }
    }

    override fun retro(tipo: Tipo) {
        val conVibrar = vibrar
        val conSonido = sonido
        if (!conVibrar && !conSonido) return
        // En su propio hilo: la tecla se dibuja y se escribe sin esperar a la vibración
        retroManejador.post {
            if (conVibrar) vibrarAhora(tipo)
            if (conSonido) sonar(tipo)
        }
    }

    /** Sonido de la tecla: los propios (estilo iPhone) o, si no cargaron, los del sistema */
    private fun sonar(tipo: Tipo) {
        val a = audio
        // Como en iPhone: en silencio o vibración, el teclado no suena
        if (a != null && a.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val i = when (tipo) {
            Tipo.ESPACIO -> 1
            Tipo.BORRAR -> 2
            Tipo.ENTER -> 3
            else -> 0
        }
        val sp = sonidos
        if (sp != null && sonidoListo[i]) {
            try {
                val vol = if (i == 0) 0.5f else 0.58f
                sp.play(idSonido[i], vol, vol, 1, 0, 1f)
                return
            } catch (_: Throwable) { }
        }
        val efecto = when (tipo) {
            Tipo.BORRAR -> AudioManager.FX_KEYPRESS_DELETE
            Tipo.ESPACIO -> AudioManager.FX_KEYPRESS_SPACEBAR
            Tipo.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        try { a?.playSoundEffect(efecto, -1f) } catch (_: Exception) { }
    }

    private fun vibrarAhora(tipo: Tipo) {
        try {
            val v = vibrador ?: return
            // Toque corto y seco, como el Taptic Engine del iPhone (si el celular lo soporta)
            if (toqueFino == null) {
                toqueFino = v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            }
            val clase = claseRetro(tipo)
            val efecto = efectos[clase] ?: crearEfecto(clase).also { efectos[clase] = it }
            v.vibrate(efecto)
        } catch (_: Exception) { }
    }
}
