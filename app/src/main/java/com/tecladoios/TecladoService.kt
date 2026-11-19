package com.tecladoios

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.text.BreakIterator
import java.util.concurrent.atomic.AtomicInteger

/**
 * El servicio del teclado: recibe cada tecla y gesto y decide qué escribir en la app.
 *
 * - La palabra que escribes queda \"en composición\" (subrayada) hasta el espacio o un signo:
 *   ahí se corrige sola si hace falta. Borrar justo después deshace la corrección.
 * - Las sugerencias se calculan aparte del teclado (nunca lo traban) y llegan al instante.
 * - Mayúscula automática al empezar una frase, punto con doble espacio, espacio inteligente
 *   antes de los signos, corrección de dos palabras seguidas (\"a sido\" → \"ha sido\") y
 *   palabras pegadas (\"Yanpodemos\" → \"Ya podemos\").
 */
class TecladoService : InputMethodService(), KeyboardView.Oyente, Barra.Oyente, PanelEmoji.Oyente,
    PanelEdicion.Oyente, PanelPortapapeles.Oyente, AjusteTamano.Oyente, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var cfg: Config
    private lateinit var respuesta: Respuesta
    private lateinit var portapapeles: Portapapeles
    private val principal = Handler(Looper.getMainLooper())
    /** Para las sugerencias: mensajes asíncronos que no esperan a que termine de armarse el cuadro */
    private val rapido = Handler.createAsync(Looper.getMainLooper())

    // Vistas
    private var marco: Marco? = null
    private var barra: Barra? = null
    private var teclado: KeyboardView? = null
    private var capa: Capa? = null
    private var panelEmoji: PanelEmoji? = null
    private var panelEdicion: PanelEdicion? = null
    private var panelCopiado: PanelPortapapeles? = null
    private var ajusteTamano: AjusteTamano? = null
    private var tema = Tema(Tema.AZUL)
    private var acentoApp = Tema.ROJO

    // Estado de la escritura
    private val palabra = StringBuilder()
    private var tx = FloatArray(48)
    private var ty = FloatArray(48)
    private var vinoDeslizado = false
    private var alternativasDeslizar: List<String> = emptyList()
    private var espacioAuto = false
    private var ultimoEspacioMs = 0L
    private var ultimaEdicionMs = 0L
    private var shift = 0
    private var ultimoShiftMs = 0L
    private var shiftUsadoMientrasApretado = false

    private class Correccion(val original: String, val puesta: String, val separador: String, val atajo: Boolean = false)
    private var ultimaCorreccion: Correccion? = null
    /** La palabra anterior: como la escribiste y como quedó (para arreglar el espacio corrido) */
    private var previa: Pair<String, String>? = null

    private var modo = Distribuciones.LETRAS
    private var campo = Distribuciones.CAMPO_TEXTO
    private var sugerir = true
    private var corregir = true
    private var aprender = true
    private var mayusCampo = true
    private var accionIntro = EditorInfo.IME_ACTION_NONE
    private var selIni = 0
    private var selFin = 0

    // Gesto de borrar palabras
    private var borrarFin = -1
    private var borrarAntes = ""

    // Sugerencias en segundo plano
    private val turno = AtomicInteger()
    @Volatile private var propuesta: Propuesta? = null
    @Volatile private var propuestaDe: String = ""

    // Búsqueda de emojis
    private var buscandoEmoji = false
    private val consultaEmoji = StringBuilder()

    // Portapapeles y capturas
    private var chipTexto: String? = null
    private var chipMs = 0L
    private var captura: Capturas.Captura? = null

    // Borrado: la palabra se vuelve a abrir solo cuando dejas de borrar (nunca a mitad)
    private val reabrirTrasBorrar = Runnable {
        if (palabra.isEmpty() && SystemClock.uptimeMillis() - ultimaEdicionMs >= 300) recapturar(false)
    }
    /** La palabra en composición vino de reabrir una ya escrita: no se autocorrige sola */
    private var reabierta = false

    /**
     * Palabra mal escrita que tocaste (con el cursor en medio de ella): la barra la muestra a la
     * izquierda y al lado sus correcciones, con la letra que cambia resaltada. Al elegir una,
     * se reemplaza la palabra entera sin tener que borrarla.
     */
    private class Revision(val ini: Int, val fin: Int, val original: String, val opciones: List<String>, val bienEscrita: Boolean = false)
    private var revision: Revision? = null

    private enum class Panel { TECLADO, EMOJI, EDICION, PORTAPAPELES }
    private var panel = Panel.TECLADO

    // ---------- Ciclo de vida ----------

    override fun onCreate() {
        super.onCreate()
        prefs = Ajustes.prefs(this)
        cfg = Config(prefs)
        respuesta = Respuesta(this).also { it.config = cfg }
        portapapeles = Portapapeles(java.io.File(filesDir, "portapapeles.tsv")).also { it.cargar() }
        prefs.registerOnSharedPreferenceChangeListener(this)
        Cerebro.iniciar(this) { Cerebro.motor?.separarPegadas = cfg.separarPegadas; actualizarBarra() }
        (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.addPrimaryClipChangedListener(alCopiar)
        // Un código de SMS que llega con el teclado abierto aparece al instante en la barra
        Codigos.alLlegar = { principal.post { if (palabra.isEmpty() && cfg.codigos) actualizarBarra() } }
        // Ahorro de batería: ya no se fuerza la pantalla a 90/120 Hz (Android sube la
        // frecuencia solo al tocar y la baja al quedar quieto) ni se despierta el cerebro en
        // cada desbloqueo; se prepara recién cuando abres el teclado.
        // Sube y baja con la curva del iPhone (en algunos Android la animación la pone el sistema)
        try { window?.window?.setWindowAnimations(R.style.AnimacionTeclado) } catch (e: Throwable) { Registro.anotar("animación ventana", e) }
    }

    // ---------- Ahorro de RAM ----------

    /** Teclado escondido: suelta imágenes de emojis, globos y teclas (se rehacen al abrir) */
    override fun onWindowHidden() {
        super.onWindowHidden()
        soltarMemoria(true)
    }

    /** Android pide memoria: se suelta lo que no se está usando */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) soltarMemoria(!isInputViewShown)
    }

    private fun soltarMemoria(todo: Boolean) {
        try {
            if (todo || panel != Panel.EMOJI) panelEmoji?.soltarMemoria()
            capa?.soltarMemoria()
            teclado?.soltarMemoria(todo)
        } catch (e: Throwable) { Registro.anotar("soltar memoria", e) }
    }

    override fun onDestroy() {
        Codigos.alLlegar = null
        if (android.os.Build.VERSION.SDK_INT >= 31) oyenteDesenfoque?.let {
            try { getSystemService(android.view.WindowManager::class.java)?.removeCrossWindowBlurEnabledListener(it) } catch (_: Throwable) { }
        }
        (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(alCopiar)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        teclado?.let { prefs.edit().putString("desvios", it.desviosTexto()).apply() }
        Cerebro.guardar()
        respuesta.soltar()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, clave: String?) {
        if (clave == "emojis_recientes" || clave == "desvios" || clave == "ultima_captura") return
        cfg = Config(prefs)
        respuesta.config = cfg
        teclado?.config = cfg
        teclado?.unaMano = cfg.unaMano
        barra?.herramientasActivas = cfg.barraHerramientas
        if (clave == Ajustes.CONTACTOS) Cerebro.cargarContactos(this)
        Cerebro.motor?.separarPegadas = cfg.separarPegadas
        if (clave == Ajustes.ALTURA || clave == Ajustes.FILA_NUMEROS) { ponerDistribucion(); teclado?.requestLayout() }
        if (clave == Ajustes.MARGEN_ABAJO) marco?.margenAbajo = (cfg.margenAbajo * resources.displayMetrics.density).toInt()
        if (clave == Ajustes.ANCHO) marco?.margenLado = ladoPara(cfg.ancho)
        if (clave == Ajustes.VIDRIO) aplicarVidrio()
        aplicarTema(acentoApp)
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val c = this
        val cp = Capa(c)
        val kb = KeyboardView(c, cp).apply {
            config = cfg; oyente = this@TecladoService; unaMano = cfg.unaMano
            leerDesvios(prefs.getString("desvios", null))
        }
        val br = Barra(c).apply { oyente = this@TecladoService; herramientasActivas = cfg.barraHerramientas }
        // INVISIBLE (no GONE): ya está medido y armado, abrirlo no obliga a rehacer el diseño
        val pe = PanelEmoji(c).apply { oyente = this@TecladoService; visibility = View.INVISIBLE }
        val ed = PanelEdicion(c).apply { oyente = this@TecladoService; visibility = View.GONE; alResponder = { respuesta.tocar(it) } }
        val pc = PanelPortapapeles(c).apply { oyente = this@TecladoService; visibility = View.GONE; alResponder = { respuesta.tocar(it) } }
        val aj = AjusteTamano(c).apply { oyente = this@TecladoService; visibility = View.GONE }
        val cuerpo = Cuerpo(c).apply {
            addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            for (v in listOf(pe, ed, pc)) addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val columna = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            // Barra en cápsula pegada al teclado (44 dp: las herramientas caben holgadas)
            addView(zonaBarra(c, br), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * resources.displayMetrics.density).toInt()))
            addView(cuerpo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val m = Marco(c).apply {
            tag = "marco"
            addView(columna, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(cp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(aj, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { _, ins ->
                abajoSistema = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                ins
            }
        }
        marco = m; barra = br; teclado = kb; capa = cp
        panelEmoji = pe; panelEdicion = ed; panelCopiado = pc; ajusteTamano = aj
        m.margenAbajo = (cfg.margenAbajo * resources.displayMetrics.density).toInt()
        m.margenLado = ladoPara(cfg.ancho)
        aplicarTema(acentoApp)
        return m
    }

    override fun onWindowShown() {
        super.onWindowShown()
        aplicarVidrio()
        // Deja listos los emojis en segundo plano para que el panel abra sin tirón
        teclado?.let { kb -> kb.post { panelEmoji?.preparar(kb.height) } }
    }

    // ---------- Vidrio: desenfoque real de lo que hay detrás ----------

    private var oyenteDesenfoque: java.util.function.Consumer<Boolean>? = null

    /**
     * Android 12+ puede desenfocar lo que hay detrás de la ventana del teclado (lo hace la
     * GPU del sistema, casi no gasta). Si el teléfono no lo permite, si el ahorro de batería
     * lo apaga o si la ventana fuera más alta que el teclado, queda el vidrio sólido.
     */
    private fun aplicarVidrio() {
        val ventana = window?.window ?: return
        val m = marco ?: return
        var activo = false
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val wm = getSystemService(android.view.WindowManager::class.java)
                if (oyenteDesenfoque == null && wm != null) {
                    val o = java.util.function.Consumer<Boolean> { principal.post { aplicarVidrio() } }
                    oyenteDesenfoque = o
                    wm.addCrossWindowBlurEnabledListener(mainExecutor, o)
                }
                val decor = ventana.decorView
                val sobra = decor.height - m.height
                activo = cfg.vidrio && wm?.isCrossWindowBlurEnabled == true &&
                    (decor.height == 0 || m.height == 0 || sobra < 40 * resources.displayMetrics.density)
                val esquinas = 22 * resources.displayMetrics.density
                ventana.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (activo) 0x01000000 else 0)
                    cornerRadii = floatArrayOf(esquinas, esquinas, esquinas, esquinas, 0f, 0f, 0f, 0f)
                })
                ventana.setBackgroundBlurRadius(if (activo) (34 * resources.displayMetrics.density).toInt() else 0)
            }
        } catch (e: Throwable) { Registro.anotar("vidrio", e); activo = false }
        m.vidrio = activo
        @Suppress("DEPRECATION")
        ventana.navigationBarColor = if (activo) android.graphics.Color.TRANSPARENT else tema.tarjeta
        // La ventana aún no medía: se revisa otra vez cuando ya tiene su tamaño
        if (m.height == 0) m.post { if (m.height > 0) aplicarVidrio() }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cfg = Config(prefs); respuesta.config = cfg
        teclado?.config = cfg
        teclado?.unaMano = cfg.unaMano
        barra?.herramientasActivas = cfg.barraHerramientas
        leerCampo(info)
        // Al abrirse, las filas entran en cascada (una décima y media de segundo)
        if (!restarting) { teclado?.animarEntrada(); barra?.aparecer() }
        barra?.tinte = tinteDe(info.packageName)
        Cerebro.motor?.aprendido?.app = info.packageName ?: ""
        // Algunas apps reinician el campo al enviar: si la palabra ya no está, se suelta
        if (restarting && palabra.isNotEmpty() &&
            (info.initialSelStart <= 0 || antes(palabra.length) != palabra.toString())) {
            reiniciarPalabra(); ultimaCorreccion = null; espacioAuto = false
        }
        if (!restarting) {
            reiniciarPalabra()
            ultimaCorreccion = null; espacioAuto = false; revision = null; previa = null
            // Al abrir el teclado en otro campo, las letras aparecen directo (sin que el panel
            // de emojis que quedó abierto baje animado mientras el teclado sube)
            mostrarPanel(Panel.TECLADO, conAnimacion = false)
            cerrarBusqueda()
        }
        selIni = info.initialSelStart; selFin = info.initialSelEnd
        ponerDistribucion()
        actualizarIntro(info)
        // Color de la app
        val pkg = info.packageName
        // Sin el color de cada app: el teclado siempre igual, negro AMOLED con detalles en plata
        aplicarTema(Tema.ROJO)
        actualizarShift()
        revisarCaptura(info)
        actualizarBarra()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        revision = null
        barra?.revision = null
        ocultarLlaves()
        teclado?.cancelarTodo()
        principal.removeCallbacks(reabrirTrasBorrar)
        if (ajusteTamano?.visibility == View.VISIBLE) cerrarTamano()
        if (palabra.isNotEmpty()) { currentInputConnection?.finishComposingText(); reiniciarPalabra() }
        cerrarBusqueda()
        teclado?.let { prefs.edit().putString("desvios", it.desviosTexto()).apply() }
        Cerebro.guardar()
    }

    private fun leerCampo(info: EditorInfo) {
        val t = info.inputType
        val clase = t and InputType.TYPE_MASK_CLASS
        val variacion = t and InputType.TYPE_MASK_VARIATION
        modo = Distribuciones.LETRAS; campo = Distribuciones.CAMPO_TEXTO
        sugerir = cfg.sugerencias; corregir = cfg.autocorreccion
        aprender = cfg.aprender && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        mayusCampo = false
        when (clase) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> {
                modo = Distribuciones.NUMEROS; sugerir = false; corregir = false
                if (clase == InputType.TYPE_CLASS_NUMBER && (t and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0) campo = Distribuciones.CAMPO_DECIMAL
            }
            InputType.TYPE_CLASS_PHONE -> { modo = Distribuciones.TELEFONO; sugerir = false; corregir = false }
            InputType.TYPE_CLASS_TEXT -> {
                when (variacion) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> { sugerir = false; corregir = false; aprender = false }
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> {
                        campo = Distribuciones.CAMPO_CORREO; corregir = false
                    }
                    InputType.TYPE_TEXT_VARIATION_URI -> { campo = Distribuciones.CAMPO_WEB; corregir = false }
                }
                if ((t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) corregir = false
                val caps = t and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
                mayusCampo = caps != 0 || variacion == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
                    variacion == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE || variacion == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
                    variacion == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            }
            else -> { sugerir = false; corregir = false }
        }
    }

    private var privado = false

    private fun esPrivado(info: EditorInfo): Boolean {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        val clase = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        return (clase == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (clase == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** La barra cambia según la app: chats más cálidos, navegadores más compactos */
    /** Matiz de la barra según la app (se mezcla al 7 %: apenas perceptible) */
    private fun tinteDe(paquete: String?): Int {
        val p = paquete ?: return 0
        return when {
            p.startsWith("com.whatsapp") -> 0xFF25D366.toInt()
            p == "com.facebook.orca" || p == "com.facebook.katana" -> 0xFF0A84FF.toInt()
            p.startsWith("org.telegram") -> 0xFF2AABEE.toInt()
            p == "com.instagram.android" -> 0xFFE1306C.toInt()
            p == "com.google.android.gm" -> 0xFFEA4335.toInt()
            p == "com.google.android.apps.messaging" || p == "com.samsung.android.messaging" -> 0xFF34C759.toInt()
            p == "com.zhiliaoapp.musically" || p == "com.ss.android.ugc.trill" -> 0xFFFE2C55.toInt()
            else -> 0
        }
    }

    private fun estiloDe(paquete: String?): Int {
        val p = paquete ?: return 0
        return when {
            p.startsWith("com.whatsapp") || p.startsWith("org.telegram") || p == "com.facebook.orca" ||
                p.startsWith("org.thoughtcrime.securesms") || p == "com.instagram.android" ||
                p == "com.google.android.apps.messaging" || p == "com.samsung.android.messaging" -> 1
            p == "com.android.chrome" || p.startsWith("org.mozilla.") || p == "com.opera.browser" ||
                p == "com.brave.browser" || p == "com.microsoft.emmx" || p == "com.sec.android.app.sbrowser" ||
                p.startsWith("com.duckduckgo") || p == "com.vivaldi.browser" -> 2
            else -> 0
        }
    }

    private fun esClave(): Boolean {
        val v = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        return v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun ponerDistribucion() {
        val filaNum = cfg.filaNumeros || (modo == Distribuciones.LETRAS && esClave())
        teclado?.distribucion = Distribuciones.crear(modo, campo, filaNum)
        teclado?.requestLayout()
    }

    private fun actualizarIntro(info: EditorInfo) {
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        accionIntro = if (sinAccion) EditorInfo.IME_ACTION_NONE else accion
        val (txt, destacado) = when (accionIntro) {
            EditorInfo.IME_ACTION_SEARCH -> "buscar" to true
            EditorInfo.IME_ACTION_GO -> "ir" to true
            EditorInfo.IME_ACTION_SEND -> "enviar" to true
            EditorInfo.IME_ACTION_NEXT -> "siguiente" to false
            EditorInfo.IME_ACTION_DONE -> "listo" to false
            else -> null to false
        }
        teclado?.textoIntro = txt
        // La tecla intro va siempre en azul (punto de color fijo del teclado)
        teclado?.introDestacado = true
    }

    private fun aplicarTema(acento: Int) {
        acentoApp = acento
        // Claro u oscuro según el modo del celular, como en iOS
        // AMOLED (negro puro) siempre, salvo que en Ajustes pidas seguir el modo del celular
        val claro = cfg.temaSistema && (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Sin color: el acento también es gris (como el iPhone en modo oscuro)
        val t = Tema(0xFFAEAEB2.toInt(), claro)
        tema = t
        marco?.tema = t; teclado?.tema = t; barra?.tema = t; capa?.tema = t
        panelEmoji?.tema = t; panelEdicion?.tema = t; panelCopiado?.tema = t; ajusteTamano?.tema = t
        marco?.conBorde = false   // negro puro: sin filo de color
    }

    // ---------- Autocompletar (códigos, mis datos y contraseñas) ----------

    private var ofertaEscribir: String? = null

    /** Lo que cierra a cada signo de apertura */
    private val CIERRES = mapOf('(' to ')', '«' to '»', '¿' to '?', '¡' to '!', '“' to '”', '[' to ']')

    /**
     * El signo que falta cerrar en la frase actual (solo si ya escribiste algo después de
     * abrirlo y estás entre palabras), o null.
     */
    private fun cierrePendiente(t: String): String? {
        if (t.isEmpty() || t.last() != ' ') return null
        var inicio = t.length - 1
        while (inicio >= 0 && t[inicio] != '.' && t[inicio] != '\n') inicio--
        val pila = ArrayList<Pair<Char, Int>>(4)
        for (i in inicio + 1 until t.length) {
            val ch = t[i]
            if (ch in CIERRES.keys) pila.add(ch to i)
            else if (pila.isNotEmpty() && CIERRES[pila.last().first] == ch) pila.removeAt(pila.size - 1)
        }
        val abierto = pila.lastOrNull() ?: return null
        val despues = t.substring(abierto.second + 1)
        if (despues.count { it.isLetterOrDigit() } < 2) return null
        return CIERRES[abierto.first]?.toString()
    }

    /** (etiqueta, texto) que la barra ofrece con el campo vacío, o null */
    private fun ofertaAutocompletar(leido: String?): Pair<String, String>? {
        if ((leido ?: antes(1)).isNotEmpty()) return null
        if (cfg.codigos) Codigos.actual()?.let { return "De Mensajes" to it }
        val info = currentInputEditorInfo ?: return null
        val clase = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val pista = (info.hintText?.toString() ?: "").lowercase()
        val texto = clase == InputType.TYPE_CLASS_TEXT
        val dato = when {
            texto && (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                "correo" in pista || "email" in pista || "e-mail" in pista) -> "Tu correo" to cfg.datoCorreo
            clase == InputType.TYPE_CLASS_PHONE || (texto && ("teléfono" in pista || "telefono" in pista || "celular" in pista)) -> "Tu teléfono" to cfg.datoTelefono
            texto && (v == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS || "dirección" in pista || "direccion" in pista) -> "Tu dirección" to cfg.datoDireccion
            texto && (v == InputType.TYPE_TEXT_VARIATION_PERSON_NAME || "nombre" in pista) -> "Tu nombre" to cfg.datoNombre
            else -> null
        }
        return dato?.takeIf { it.second.isNotEmpty() }
    }

    private var llaves: android.widget.HorizontalScrollView? = null
    private var filaLlaves: LinearLayout? = null

    /** La barra con, encima, la fila de contraseñas guardadas (aparece solo cuando las hay) */
    private fun zonaBarra(c: Context, br: View): View {
        val fila = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val desliza = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setBackgroundColor(tema.fondo)
            addView(fila, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        llaves = desliza; filaLlaves = fila
        return FrameLayout(c).apply {
            addView(br, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(desliza, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun ocultarLlaves() {
        filaLlaves?.removeAllViews()
        llaves?.visibility = View.GONE
    }

    /** Estilo de las sugerencias de contraseñas: chips del color de las teclas, como iOS */
    private fun estiloLlaves(): Bundle {
        val d = resources.displayMetrics.density
        val fondo = android.graphics.drawable.Icon.createWithResource(this, R.drawable.chip_llave).setTint(tema.teclaFuncion)
        val chip = androidx.autofill.inline.common.ViewStyle.Builder()
            .setBackground(fondo)
            .setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            .build()
        val titulo = androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(tema.texto).setTextSize(15f).build()
        val sub = androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(tema.textoSuave).setTextSize(12f).build()
        val estilo = androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(chip).setTitleStyle(titulo).setSubtitleStyle(sub).build()
        return androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(estilo).build()
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        if (!cfg.contrasenas) return null
        return try {
            val d = resources.displayMetrics.density
            val spec = android.widget.inline.InlinePresentationSpec.Builder(
                android.util.Size((60 * d).toInt(), (34 * d).toInt()),
                android.util.Size((280 * d).toInt(), (34 * d).toInt()),
            ).setStyle(estiloLlaves()).build()
            android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(6).build()
        } catch (e: Throwable) { Registro.anotar("pedir contraseñas", e); null }
    }

    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        val fila = filaLlaves ?: return false
        val lista = response.inlineSuggestions
        fila.removeAllViews()
        if (lista.isEmpty()) { llaves?.visibility = View.GONE; return true }
        val vistas = arrayOfNulls<View>(lista.size)
        var faltan = lista.size
        val d = resources.displayMetrics.density
        for ((i, sug) in lista.withIndex()) {
            try {
                sug.inflate(this, android.util.Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), mainExecutor) { v ->
                    vistas[i] = v
                    faltan--
                    if (faltan == 0) {
                        fila.removeAllViews()
                        for (x in vistas) if (x != null) {
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            lp.marginStart = (6 * d).toInt()
                            fila.addView(x, lp)
                        }
                        llaves?.setBackgroundColor(tema.fondo)
                        llaves?.visibility = if (fila.childCount > 0 && palabra.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            } catch (e: Throwable) { faltan--; Registro.anotar("contraseña", e) }
        }
        return true
    }

    // ---------- Ayudas de texto ----------

    private fun ic(): InputConnection? = currentInputConnection

    private fun marcarEdicion() { ultimaEdicionMs = SystemClock.uptimeMillis() }

    private fun antes(n: Int): String = ic()?.getTextBeforeCursor(n, 0)?.toString() ?: ""

    /**
     * Texto antes y después del cursor junto con la posición REAL del cursor en el campo, leído
     * de una sola vez. Antes se mezclaba el texto actual con la posición del último aviso de la
     * app (que puede venir atrasado): la palabra se marcaba corrida y, al tocarla o elegir una
     * corrección, se borraba en vez de cambiarse. Devuelve null si hay texto seleccionado.
     */
    private class Alrededor(val antes: String, val despues: String, val cursor: Int)
    private fun alrededor(nAntes: Int, nDespues: Int): Alrededor? {
        val ic = ic() ?: return null
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val st = try { ic.getSurroundingText(nAntes, nDespues, 0) } catch (e: Throwable) { null }
            if (st != null) {
                if (st.selectionStart != st.selectionEnd) return null
                val t = st.text?.toString() ?: return null
                val cur = st.selectionStart
                if (cur < 0 || cur > t.length) return null
                if (st.offset >= 0) return Alrededor(t.substring(0, cur), t.substring(cur), st.offset + cur)
                return Alrededor(t.substring(0, cur), t.substring(cur), selIni)
            }
        }
        if (selIni != selFin) return null
        return Alrededor(antes(nAntes), despues(nDespues), selIni)
    }
    private fun despues(n: Int): String = ic()?.getTextAfterCursor(n, 0)?.toString() ?: ""

    /** Contexto de la palabra actual: se lee una vez al empezarla (menos consultas a la app = menos batería) */
    private var ctxPalabra: List<String>? = null

    private fun contextoPalabra(): List<String> = ctxPalabra ?: contexto().also { ctxPalabra = it }

    private fun reiniciarPalabra() {
        teclado?.probables = emptyMap()
        teclado?.olvidarToques()
        ctxPalabra = null
        reabierta = false
        palabra.setLength(0)
        vinoDeslizado = false
        alternativasDeslizar = emptyList()
        propuesta = null; propuestaDe = ""
    }

    private fun ponerToque(i: Int, x: Float, y: Float) {
        if (i >= tx.size) { tx = tx.copyOf(i * 2 + 8); ty = ty.copyOf(i * 2 + 8) }
        tx[i] = x; ty[i] = y
    }

    /** Las dos palabras anteriores a la que se está escribiendo (para predecir) */
    private fun contexto(leido: String? = null): List<String> {
        val t = leido ?: antes(80)
        val sinActual = if (palabra.isNotEmpty() && t.endsWith(palabra)) t.dropLast(palabra.length) else t
        // Tres palabras de contexto: el cerebro adelanta mejor lo que vas a escribir
        val res = ArrayList<String>(3)
        var i = sinActual.length - 1
        while (res.size < 3 && i >= 0) {
            while (i >= 0 && sinActual[i] == ' ') i--
            if (i < 0) break
            val ch = sinActual[i]
            if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') break
            if (!Texto.esLetraPalabra(ch)) { i--; continue }
            val fin = i + 1
            while (i >= 0 && Texto.esLetraPalabra(sinActual[i])) i--
            res.add(0, sinActual.substring(i + 1, fin))
        }
        return res
    }

    private fun inicioDeFrase(leido: String? = null): Boolean {
        val t = (leido?.takeLast(6) ?: antes(6)).trimEnd(' ')
        return t.isEmpty() || t.last() in ".?!\n¡¿"
    }

    // ---------- Mayúsculas ----------

    /**
     * Después de cada edición: mayúscula automática y barra con UNA sola lectura del texto
     * (cada lectura es una consulta a la app; con varias seguidas el teclado se trababa,
     * sobre todo en WhatsApp con el chat ocupado)
     */
    private fun refrescar() {
        val t = if (palabra.isEmpty() && (sugerir || (cfg.mayusAuto && mayusCampo))) antes(80) else null
        actualizarShift(t)
        actualizarBarra(t)
    }

    private fun actualizarShift(leido: String? = null) {
        if (shift == 2) { teclado?.shift = 2; return }
        var finDeFrase = false
        val auto = cfg.mayusAuto && mayusCampo && modo == Distribuciones.LETRAS && palabra.isEmpty() && run {
            val t = leido?.takeLast(4) ?: antes(4)
            finDeFrase = t.isNotEmpty()
            if (t.isEmpty()) true
            else {
                val sinEsp = t.trimEnd(' ')
                (t.endsWith(" ") || t.endsWith("\n") || sinEsp.isEmpty()) &&
                    (sinEsp.isEmpty() || sinEsp.last() in ".?!\n")
            }
        }
        // Mayúscula automática al empezar otra frase: la barra espaciadora respira en violeta
        if (auto && shift == 0 && finDeFrase) teclado?.respirarEspacio()
        shift = if (auto) 1 else if (shift == 1 && palabra.isEmpty()) 0 else shift
        teclado?.shift = shift
    }

    // ---------- Teclas ----------

    // Un error al escribir se anota y no cierra el teclado
    override fun tecla(texto: String, x: Float, y: Float, esLetra: Boolean) {
        try {
            val inicio = System.nanoTime()
            teclaReal(texto, x, y, esLetra)
            if ((texto == "?" || texto == "!") && sugerir && cfg.signosInicio) signoDeApertura(texto[0])
            // Como en iOS: el apóstrofo en 123 te devuelve a las letras
            if ((texto == "'" || texto == "’") && (modo == Distribuciones.SIMBOLOS || modo == Distribuciones.SIMBOLOS2)) volverLetras()
            // Puntuación inteligente (iOS): dos guiones seguidos se vuelven una raya
            if (texto == "-" && cfg.puntuacion && campo == Distribuciones.CAMPO_TEXTO && antes(2) == "--") {
                ic()?.let { ic -> ic.beginBatchEdit(); ic.deleteSurroundingText(2, 0); ic.commitText("—", 1); ic.endBatchEdit() }
            }
        } catch (e: Throwable) { Registro.anotar("tecla", e) }
    }

    /**
     * ¿ y ¡ automáticos: al cerrar una frase con ? o !, si esa frase no tiene su signo de
     * apertura, se pone al inicio de la frase ("Ya llegaste?" → "¿Ya llegaste?").
     */
    private fun signoDeApertura(cierre: Char) {
        val apertura = if (cierre == '?') '¿' else '¡'
        val ic = ic() ?: return
        val t = antes(300)
        val fin = t.lastIndexOf(cierre)
        if (fin < 0 || t.length - fin > 2) return
        // Si ya va un ? o ! justo antes (como "??" o "?!"), la frase ya se cerró antes
        if (fin > 0 && (t[fin - 1] == '?' || t[fin - 1] == '!')) return
        var i = fin - 1
        while (i >= 0) {
            val ch = t[i]
            if (ch == apertura) return                       // ya lo tiene
            if (ch == '.' || ch == '?' || ch == '!' || ch == '\n' || ch == '¿' || ch == '¡') break
            i--
        }
        var inicio = i + 1
        while (inicio < fin && t[inicio].isWhitespace()) inicio++
        if (inicio >= fin || i < 0 && t.length >= 300) return  // frase demasiado larga para estar seguros
        val trozo = t.substring(inicio)
        if (trozo.length < 3 || trozo.none { it.isLetter() }) return
        marcarEdicion()
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(trozo.length, 0)
        ic.commitText(apertura + trozo, 1)
        ic.endBatchEdit()
    }

    private fun teclaReal(texto: String, x: Float, y: Float, esLetra: Boolean) {
        if (buscandoEmoji) { consultaEmoji.append(texto.lowercase()); buscarEmoji(); return }
        val ic = ic() ?: return
        var t = texto
        if (texto.length == 1 && texto[0].isLetter() && shift > 0) t = texto.uppercase()
        if (shift == 1 && texto.isNotEmpty() && texto[0].isLetter()) { shift = 0; teclado?.shift = 0 }
        val ch = t[0]
        val dePalabra = t.length == 1 && (ch.isLetterOrDigit() || ((ch == '\'' || ch == '-') && palabra.isNotEmpty()))
        marcarEdicion()
        principal.removeCallbacks(reabrirTrasBorrar)
        if (dePalabra) {
            barra?.cerrarHerramientas()
            reabierta = false
            ultimaCorreccion = null
            if (vinoDeslizado && palabra.isNotEmpty()) {
                // Después de deslizar, la siguiente letra empieza otra palabra
                aprenderPalabra(palabra.toString())
                ic.commitText("$palabra ", 1)
                reiniciarPalabra()
            }
            espacioAuto = false
            if (!sugerir) { ic.commitText(t, 1); actualizarShift(); return }
            if (palabra.isEmpty() && !ch.isLetter()) { ic.commitText(t, 1); actualizarBarra(); return }
            ponerToque(palabra.length, if (esLetra) x else Float.NaN, if (esLetra) y else Float.NaN)
            palabra.append(t)
            ic.setComposingText(palabra, 1)
            pedirSugerencias()
            return
        }
        // Signos y símbolos
        if (palabra.isNotEmpty()) {
            val sep = if (t in setOf(".", ",", "?", "!", ":", ";") && espacioDespuesDe(t)) "$t " else t
            terminarPalabra(sep)
            espacioAuto = sep.endsWith(" ")
        } else if (espacioAuto && t in setOf(".", ",", "?", "!", ":", ";", ")") && antes(1) == " ") {
            ultimaCorreccion = null
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(if (t != ")" && espacioDespuesDe(t)) "$t " else t, 1)
            ic.endBatchEdit()
            espacioAuto = t != ")"
        } else {
            ic.commitText(t, 1)
            espacioAuto = false
            ultimaCorreccion = null
        }
        if (modo == Distribuciones.SIMBOLOS && t == "'") volverLetras()
        refrescar()
    }

    private fun espacioDespuesDe(t: String) = cfg.espacioAuto && campo == Distribuciones.CAMPO_TEXTO && t != "-"

    override fun codigo(tipo: Int) {
        try { codigoReal(tipo) } catch (e: Throwable) { Registro.anotar("codigo", e) }
    }

    private fun codigoReal(tipo: Int) {
        when (tipo) {
            K.BORRAR -> borrarTexto()
            K.ESPACIO -> espacio()
            K.INTRO -> intro()
            K.SHIFT -> {
                val ahora = SystemClock.uptimeMillis()
                shift = when {
                    shift == 0 -> 1
                    shift == 1 && ahora - ultimoShiftMs < 350 && cfg.bloqueoMayus -> 2
                    else -> 0
                }
                ultimoShiftMs = ahora
                teclado?.shift = shift
            }
            K.NUM -> { modo = Distribuciones.SIMBOLOS; ponerDistribucion() }
            K.ABC -> volverLetras()
            K.SIMB2 -> { modo = Distribuciones.SIMBOLOS2; ponerDistribucion() }
            K.EMOJI -> { if (buscandoEmoji) cerrarBusqueda(); mostrarPanel(Panel.EMOJI) }
        }
    }

    override fun volverLetras() {
        if (modo == Distribuciones.SIMBOLOS || modo == Distribuciones.SIMBOLOS2) {
            modo = Distribuciones.LETRAS
            ponerDistribucion()
            actualizarShift()
        }
    }

    private fun espacio() {
        if (buscandoEmoji) { consultaEmoji.append(' '); buscarEmoji(); return }
        val ic = ic() ?: return
        marcarEdicion()
        val ahora = SystemClock.uptimeMillis()
        if (palabra.isNotEmpty()) {
            terminarPalabra(" ")
        } else if (cfg.puntoDobleEspacio && ahora - ultimoEspacioMs < 900 && campo == Distribuciones.CAMPO_TEXTO && run {
                val t = antes(2); t.length == 2 && t[1] == ' ' && (t[0].isLetterOrDigit() || t[0] in "\"')")
            }) {
            ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); ic.endBatchEdit()
            teclado?.respirarEspacio()
            ultimoEspacioMs = 0
            espacioAuto = false
            ultimaCorreccion = null
            refrescar()
            return
        } else if (espacioAuto && antes(1) == " ") {
            espacioAuto = false
        } else {
            ic.commitText(" ", 1)
            ultimaCorreccion = null
        }
        ultimoEspacioMs = ahora
        if (modo == Distribuciones.SIMBOLOS || modo == Distribuciones.SIMBOLOS2) volverLetras()
        refrescar()
    }

    private fun intro() {
        if (buscandoEmoji) { cerrarBusqueda(); return }
        val ic = ic() ?: return
        marcarEdicion()
        if (palabra.isNotEmpty()) terminarPalabra("")
        ultimaCorreccion = null; espacioAuto = false
        if (accionIntro != EditorInfo.IME_ACTION_NONE && accionIntro != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(accionIntro)
        } else {
            val multilinea = ((currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            if (multilinea || accionIntro == EditorInfo.IME_ACTION_NONE) ic.commitText("\n", 1)
            else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        refrescar()
    }

    /**
     * Cierra la palabra en composición con [separador] (espacio, signo o nada), corrigiéndola
     * si hace falta, y aprende de ella.
     */
    private fun terminarPalabra(separador: String) {
        val ic = ic() ?: return
        val escrito = palabra.toString()
        var resultado = escrito
        val ctx = contextoPalabra()
        // Una palabra que ya estaba escrita y solo reabriste al borrar se respeta tal cual
        val respetar = reabierta
        // Atajo de texto ("bd" → "Buenos días")
        val atajo = if (!vinoDeslizado && !respetar && campo == Distribuciones.CAMPO_TEXTO && sugerir) Atajos.expandir(cfg.atajos, escrito) else null
        if (atajo != null) resultado = atajo
        else if (!vinoDeslizado && sugerir && corregir && !respetar) {
            val p = propuestaPara(escrito, ctx)
            p?.autocorregir?.let { resultado = it }
        }
        // Dos palabras seguidas que se corrigen juntas ("a sido" → "ha sido")
        var anteriorNueva: String? = null
        var anteriorVieja: String? = null
        if (atajo == null && corregir && !respetar && ctx.isNotEmpty() && Cerebro.motor != null) {
            val conf = Cerebro.motor?.errores?.confusion(ctx.last(), resultado)
            if (conf != null) {
                val previo = antes(ctx.last().length + 1 + escrito.length)
                if (previo.startsWith(ctx.last() + " ")) {
                    anteriorVieja = ctx.last()
                    anteriorNueva = Texto.conMayusculas(conf.first, ctx.last())
                    resultado = conf.second
                }
            }
        }
        // Espacio corrido a dos pulgares: "ho laque" → "hola que", "holaq ue" → "hola que".
        // Se mira la anterior COMO LA ESCRIBISTE (aunque se haya corregido sola)
        val prev = previa
        if (anteriorNueva == null && atajo == null && corregir && sugerir && !respetar && !vinoDeslizado &&
            prev != null && ctx.isNotEmpty() && ctx.last() == prev.second && !prev.first.contains(' ') && !prev.second.contains(' ')) {
            val mov = Cerebro.motor?.moverEspacio(prev.first, escrito)
            if (mov != null) {
                val previo = antes(prev.second.length + 1 + escrito.length)
                if (previo.startsWith(prev.second + " ")) {
                    anteriorVieja = prev.second
                    anteriorNueva = mov.first
                    resultado = mov.second
                }
            }
        }
        // Dónde tocas cada letra se aprende ahora, ya sabiendo qué letra iba de verdad
        if (aprender && !vinoDeslizado && !respetar && atajo == null && anteriorNueva == null) teclado?.cerrarToques(escrito, resultado)
        else teclado?.olvidarToques()
        marcarEdicion()
        // Palabra reabierta con un espacio ya detrás: no se agrega otro, el cursor salta el que hay
        if (respetar && separador == " " && resultado == escrito && despues(1) == " " && selIni == selFin) {
            val fin = selFin
            ic.beginBatchEdit()
            ic.finishComposingText()
            ic.setSelection(fin + 1, fin + 1)
            ic.endBatchEdit()
            aprenderPalabra(resultado, ctx)
            ultimaCorreccion = null
            reiniciarPalabra()
            refrescar()
            return
        }
        ic.beginBatchEdit()
        if (anteriorNueva != null && anteriorVieja != null) {
            ic.setComposingText("", 1)
            ic.deleteSurroundingText(anteriorVieja.length + 1, 0)
            ic.commitText("$anteriorNueva $resultado$separador", 1)
        } else {
            ic.commitText(resultado + separador, 1)
        }
        ic.endBatchEdit()
        if (atajo == null) aprenderPalabra(resultado, ctx)
        ultimaCorreccion = if (resultado != escrito || anteriorNueva != null) {
            val puesta = if (anteriorNueva != null) "$anteriorNueva $resultado" else resultado
            val original = if (anteriorVieja != null) "$anteriorVieja $escrito" else escrito
            Correccion(original, puesta, separador, atajo != null)
        } else null
        previa = if (separador == " " && anteriorNueva == null && atajo == null) escrito to resultado else null
        // La palabra corregida se ilumina en la barra (tocarla la deshace)
        barra?.avisarCorreccion(ultimaCorreccion?.puesta)
        reiniciarPalabra()
        refrescar()
    }

    private fun aprenderPalabra(w: String, ctx: List<String>? = null) {
        if (!aprender) return
        val m = Cerebro.motor ?: return
        val piezas = w.split(' ').filter { it.isNotEmpty() }
        val contexto = (ctx ?: contexto()).toMutableList()
        for (p in piezas) {
            if (p.any { it.isDigit() } || p.length > 30) { contexto.add(p); continue }
            val limpio = p.trim('\'', '-')
            if (limpio.isEmpty()) continue
            m.aprendido.usar(limpio, m.dic.existe(Texto.normal(limpio)))
            m.aprendido.siguiente(contexto, limpio.lowercase())
            contexto.add(limpio)
        }
        if (m.aprendido.cambios > 25) Cerebro.guardar()
    }

    private fun borrarTexto() {
        if (buscandoEmoji) {
            if (consultaEmoji.isNotEmpty()) consultaEmoji.setLength(consultaEmoji.length - 1)
            buscarEmoji(); return
        }
        val ic = ic() ?: return
        previa = null
        principal.removeCallbacks(reabrirTrasBorrar)
        // Animación: la letra que se va "entra" en la tecla borrar
        if (selIni == selFin) teclado?.comerLetra(if (palabra.isNotEmpty()) palabra.last().toString() else antes(1))
        marcarEdicion()
        espacioAuto = false
        if (selIni != selFin && selIni >= 0) {
            if (palabra.isNotEmpty()) { ic.finishComposingText(); reiniciarPalabra() }
            ic.commitText("", 1)
            refrescar()
            return
        }
        // Deshacer la corrección automática
        val corr = ultimaCorreccion
        if (corr != null && palabra.isEmpty()) {
            val esperado = corr.puesta + corr.separador
            if (antes(esperado.length) == esperado) {
                ic.beginBatchEdit()
                ic.finishComposingText()
                ic.deleteSurroundingText(esperado.length, 0)
                val ultima = corr.original.substringAfterLast(' ')
                val antesDeUltima = corr.original.dropLast(ultima.length)
                if (antesDeUltima.isNotEmpty()) ic.commitText(antesDeUltima, 1)
                palabra.append(ultima)
                for (i in ultima.indices) ponerToque(i, Float.NaN, Float.NaN)
                ic.setComposingText(palabra, 1)
                ic.endBatchEdit()
                if (!corr.atajo) Cerebro.motor?.aprendido?.let {
                    it.rechazar(corr.original.substringAfterLast(' '), corr.puesta.substringAfterLast(' '))
                    if (aprender) it.agregar(ultima)
                }
                barra?.avisarCorreccion(null)
                ultimaCorreccion = null
                reabierta = true   // la dejaste como la escribiste: no se vuelve a corregir
                pedirSugerencias()
                return
            }
            ultimaCorreccion = null
        }
        if (palabra.isNotEmpty()) {
            val quitar = if (palabra.length >= 2 && Character.isSurrogatePair(palabra[palabra.length - 2], palabra[palabra.length - 1])) 2 else 1
            palabra.setLength(palabra.length - quitar)
            vinoDeslizado = false
            if (palabra.isEmpty()) {
                ic.beginBatchEdit(); ic.setComposingText("", 1); ic.finishComposingText(); ic.endBatchEdit()
                reiniciarPalabra(); refrescar()
            } else { ic.setComposingText(palabra, 1); pedirSugerencias() }
            return
        }
        // Sin palabra abierta. IMPORTANTE: primero se cierra cualquier composición que haya
        // quedado en la app. Si quedaba una abierta, Android borraba la letra de ANTES de esa
        // palabra (espacios y letras de las palabras anteriores): ese era el desorden al borrar.
        val previo = antes(16)
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (previo.isEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            val it = BreakIterator.getCharacterInstance()
            it.setText(previo)
            val fin = previo.length
            val ini = it.preceding(fin).coerceAtLeast(0)
            ic.deleteSurroundingText(fin - ini, 0)
        }
        ic.endBatchEdit()
        // La palabra donde quedó el cursor se vuelve a abrir (para sugerencias) solo cuando
        // terminas de borrar, sin tocar el texto: así nunca se mueve ni se mezcla nada.
        principal.postDelayed(reabrirTrasBorrar, 380L)
        refrescar()
    }

    override fun borrarRepetido(paso: Int) {
        try { borrarRepetidoReal(paso) } catch (e: Throwable) { Registro.anotar("borrar", e) }
    }

    private fun borrarRepetidoReal(paso: Int) {
        if (paso < 14 || buscandoEmoji) { borrarTexto(); return }
        // Manteniendo mucho rato: borra palabras enteras
        val ic = ic() ?: return
        principal.removeCallbacks(reabrirTrasBorrar)
        marcarEdicion()
        if (palabra.isNotEmpty()) {
            ic.beginBatchEdit(); ic.setComposingText("", 1); ic.finishComposingText(); ic.endBatchEdit()
            reiniciarPalabra(); actualizarBarra(); return
        }
        val t = antes(64)
        if (t.isEmpty()) { ic.finishComposingText(); sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        var i = t.length
        while (i > 0 && !Texto.esLetraPalabra(t[i - 1])) i--
        while (i > 0 && Texto.esLetraPalabra(t[i - 1])) i--
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText((t.length - i).coerceAtLeast(1), 0)
        ic.endBatchEdit()
        principal.postDelayed(reabrirTrasBorrar, 380L)
        ultimaCorreccion = null
        refrescar()
    }

    /**
     * Si el cursor queda justo al final de una palabra, la vuelve a abrir para sugerencias.
     * Nunca borra ni vuelve a escribir la palabra: solo la marca (así no se puede desordenar
     * nada aunque la app vaya atrasada). Solo se usa con el cursor quieto.
     */
    private fun recapturar(@Suppress("UNUSED_PARAMETER") alBorrar: Boolean) {
        if (!sugerir || palabra.isNotEmpty() || modo != Distribuciones.LETRAS || buscandoEmoji) return
        if (selIni != selFin) return
        val ic = ic() ?: return
        val al = alrededor(40, 1) ?: return
        val t = al.antes
        if (t.isEmpty() || !t.last().isLetter()) return
        val sig = al.despues
        if (sig.isNotEmpty() && Texto.esLetraPalabra(sig[0])) return
        var i = t.length
        while (i > 0 && (t[i - 1].isLetter() || t[i - 1] == '\'')) i--
        val w = t.substring(i)
        if (w.isEmpty() || w.length > 30 || (i > 0 && t[i - 1].isDigit())) return
        val fin = al.cursor
        if (fin < w.length) return
        marcarEdicion()
        if (!ic.setComposingRegion(fin - w.length, fin)) return
        palabra.append(w)
        reabierta = true
        for (k in w.indices) ponerToque(k, Float.NaN, Float.NaN)
        pedirSugerencias()
    }

    // ---------- Sugerencias ----------

    private val ejecutar = Cerebro.hilo

    private fun pedirSugerencias() {
        if (!sugerir) return
        val m = Cerebro.motor ?: run { actualizarBarra(); return }
        // Zona caliente: se calcula en el hilo del cerebro (nada extra al tocar la tecla)
        val zona = cfg.zonaCaliente && modo == Distribuciones.LETRAS
        val escrito = palabra.toString()
        val n = escrito.length
        val xs = tx.copyOf(n); val ys = ty.copyOf(n)
        val ctx = contextoPalabra()
        val t = turno.incrementAndGet()
        val deslizado = vinoDeslizado
        val alts = alternativasDeslizar
        val pedidoNs = System.nanoTime()
        ejecutar.execute {
            if (turno.get() != t) return@execute
            val p = try {
                if (deslizado) Propuesta(alts.map { Opcion(it, 0f, false, false) }, null, true)
                else m.proponer(escrito, xs, ys, ctx, corregir)
            } catch (e: Throwable) { Registro.anotar("proponer", e); null }
            // Primero la barra (lo que se ve) y recién después la zona caliente (lo que no se ve)
            // La barra pasa adelante de la cola del hilo principal: no espera a dibujos pendientes
            if (p != null) rapido.postAtFrontOfQueue {
                if (turno.get() == t && palabra.toString() == escrito) {
                    propuesta = p; propuestaDe = escrito
                    actualizarBarra()
                }
            }
            if (zona && turno.get() == t) {
                val prob = try { m.letrasProbables(escrito) } catch (_: Throwable) { emptyMap() }
                rapido.post { if (turno.get() == t && palabra.toString() == escrito) teclado?.probables = prob }
            } else if (!zona) rapido.post { if (turno.get() == t) teclado?.probables = emptyMap() }
        }
    }

    /** La propuesta para lo escrito: la ya calculada o, si aún no llega, se calcula ahora */
    private fun propuestaPara(escrito: String, ctx: List<String>): Propuesta? {
        if (propuestaDe == escrito) propuesta?.let { return it }
        val m = Cerebro.motor ?: return null
        val n = escrito.length
        return try { m.proponer(escrito, tx.copyOf(n), ty.copyOf(n), ctx, corregir) } catch (e: Throwable) { Registro.anotar("proponer ya", e); null }
    }

    private fun actualizarBarra(leido: String? = null) {
        val b = barra ?: return
        if (buscandoEmoji) return
        // Palabra mal escrita tocada: [tal cual] [corrección] [otra opción]
        revision?.let { rv ->
            if (palabra.isEmpty()) {
                // Mal escrita: en rojo suave con la letra que cambia resaltada.
                // Bien escrita: la palabra tal cual y sus parecidas, sin marcar error.
                b.revision = if (rv.bienEscrita) null else rv.original
                b.mostrarSugerencias(rv.opciones, rv.original, !rv.bienEscrita)
                return
            }
        }
        // Palabra reabierta que no existe: también se resalta la letra que cambia
        b.revision = if (palabra.isNotEmpty() && reabierta && propuesta?.takeIf { propuestaDe == palabra.toString() }?.valida == false) palabra.toString() else null
        // Autocompletar como en iPhone: código de SMS o tus datos (con el campo vacío)
        val oferta = if (palabra.isEmpty()) ofertaAutocompletar(leido) else null
        ofertaEscribir = oferta?.second
        // Lo copiado se ofrece un minuto
        val copiado = chipTexto?.takeIf { SystemClock.uptimeMillis() - chipMs < 60_000 && cfg.portapapeles }
        if (copiado == null) chipTexto = null
        val chip = oferta?.second ?: copiado
        b.chip(chip, oferta?.first)
        // Las contraseñas guardadas se ven mientras el campo está vacío
        llaves?.let { ll ->
            val hay = (filaLlaves?.childCount ?: 0) > 0
            val vacio = palabra.isEmpty() && (leido ?: antes(1)).isEmpty()
            ll.visibility = if (hay && vacio) View.VISIBLE else View.GONE
        }
        if (palabra.isNotEmpty()) b.avisarCorreccion(null)
        if (palabra.isNotEmpty() && sugerir) {
            // Un atajo va al centro y se pone solo con espacio
            if (!reabierta && !vinoDeslizado && campo == Distribuciones.CAMPO_TEXTO) {
                val exp = Atajos.expandir(cfg.atajos, palabra.toString())
                if (exp != null) {
                    val resto = propuesta?.takeIf { propuestaDe == palabra.toString() }?.opciones?.map { it.texto }?.filter { it != exp } ?: emptyList()
                    b.mostrarSugerencias(listOf(exp) + resto, palabra.toString(), true)
                    return
                }
            }
            val p = propuesta?.takeIf { propuestaDe == palabra.toString() }
            if (p == null) { b.mostrarSugerencias(emptyList(), palabra.toString(), false); return }
            val ops = p.opciones.map { it.texto }.toMutableList()
            val auto = p.autocorregir
            if (auto != null && corregir && !reabierta) {
                ops.remove(auto); ops.add(0, auto)
                b.mostrarSugerencias(ops, palabra.toString(), true)
            } else {
                // Sin corrección: lo escrito va al centro si es válido
                val escrito = palabra.toString()
                ops.removeAll { it == escrito }
                if (p.valida || vinoDeslizado) { ops.add(0, escrito); b.mostrarSugerencias(ops, null, false) }
                else b.mostrarSugerencias(ops, escrito, false)
            }
            return
        }
        // Sin escribir: la barra nunca se esconde, siempre propone la siguiente palabra (también
        // con el campo vacío o recién enviado). Lo copiado reciente tiene prioridad un minuto.
        if (palabra.isEmpty() && sugerir && cfg.prediccion && Cerebro.motor != null && chip == null && (captura == null || (leido?.takeLast(1) ?: antes(1)).isNotEmpty()) && selIni == selFin) {
            val t = leido ?: antes(80)
            val pred = Cerebro.motor?.predecir(contexto(t), inicioDeFrase(t)) ?: emptyList()
            // Cierre automático: si quedó abierto un ( « ¿ ¡ “ [, la barra ofrece cerrarlo
            val cierre = if (cfg.cierre && campo == Distribuciones.CAMPO_TEXTO) cierrePendiente(t) else null
            if (cierre != null) {
                b.mostrarSugerencias(if (pred.isEmpty()) listOf(cierre) else listOf(pred[0], cierre) + pred.drop(1).filter { it != cierre }, null, false)
                return
            }
            if (pred.isNotEmpty()) {
                // Frase adelantada: si el cerebro está seguro de las dos siguientes palabras,
                // la tercera casilla las ofrece juntas («mañana temprano»)
                val frase = Cerebro.motor?.frase(contexto(t), inicioDeFrase(t))
                val lista = if (frase != null && pred.size >= 2) pred.take(2) + frase else pred
                b.mostrarSugerencias(lista, null, false); return
            }
        }
        b.limpiar()
    }

    override fun elegir(texto: String, esLiteral: Boolean) {
        try { elegirReal(texto, esLiteral) } catch (e: Throwable) { Registro.anotar("elegir", e) }
    }

    private fun elegirReal(texto: String, esLiteral: Boolean) {
        val ic = ic() ?: return
        marcarEdicion()
        // Corrección de una palabra tocada: se cambia la palabra entera, en su lugar
        revision?.let { rv ->
            revision = null
            if (esLiteral) {
                // La dejas como está: si estaba mal escrita, el cerebro la aprende
                if (aprender && !rv.bienEscrita) Cerebro.motor?.aprendido?.agregar(texto)
            } else {
                // Se vuelve a leer la palabra donde está el cursor AHORA y solo se cambia si
                // sigue siendo la misma: así nunca se borra una palabra por una posición vieja
                val al = alrededor(40, 40)
                var izq = 0; var der = 0
                if (al != null) {
                    var i = al.antes.length
                    while (i > 0 && (al.antes[i - 1].isLetter() || al.antes[i - 1] == '\'')) i--
                    var j = 0
                    while (j < al.despues.length && (al.despues[j].isLetter() || al.despues[j] == '\'')) j++
                    if (al.antes.substring(i) + al.despues.substring(0, j) == rv.original) { izq = al.antes.length - i; der = j }
                }
                if (izq + der > 0) {
                    ic.beginBatchEdit()
                    ic.finishComposingText()
                    ic.deleteSurroundingText(izq, der)
                    ic.commitText(texto, 1)
                    ic.endBatchEdit()
                    aprenderPalabra(texto)
                    if (aprender) Cerebro.motor?.aprendido?.confundir(rv.original, texto)
                }
            }
            refrescar()
            return
        }
        // Cierre elegido en la barra: va pegado a la última palabra ("(hola)" y no "(hola )")
        if (palabra.isEmpty() && texto.length == 1 && texto[0] in CIERRES.values) {
            ic.beginBatchEdit()
            if (antes(1) == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText("$texto ", 1)
            ic.endBatchEdit()
            refrescar()
            return
        }
        // Palabra reabierta al tocarla: si la app soltó la marca de la palabra (pasa en algunas
        // apps), elegir una sugerencia la borraba o la duplicaba. Se comprueba antes de cambiar.
        if (reabierta && palabra.isNotEmpty() && antes(palabra.length) != palabra.toString()) {
            ic.finishComposingText()
            reiniciarPalabra()
            refrescar()
            return
        }
        val ctx = contexto()
        ic.beginBatchEdit()
        if (palabra.isNotEmpty()) {
            val escrito = palabra.toString()
            ic.commitText("$texto ", 1)
            if (esLiteral && aprender) Cerebro.motor?.aprendido?.agregar(texto)
            ultimaCorreccion = if (texto != escrito && !esLiteral) Correccion(escrito, texto, " ", Atajos.expandir(cfg.atajos, escrito) == texto) else null
            // Mis confusiones: elegiste la corrección tú mismo → aprende qué letra confundiste
            if (!esLiteral && texto != escrito && aprender) Cerebro.motor?.aprendido?.confundir(escrito, texto)
            // Y dónde tocaste: cada toque se aprende para la letra que de verdad iba
            if (!vinoDeslizado && aprender) teclado?.cerrarToques(escrito, texto)
        } else {
            val previo = antes(1)
            val pre = if (previo.isNotEmpty() && previo != " " && previo != "\n") " " else ""
            ic.commitText("$pre$texto ", 1)
            ultimaCorreccion = null
        }
        ic.endBatchEdit()
        if (ultimaCorreccion?.atajo != true) aprenderPalabra(texto, ctx)
        reiniciarPalabra()
        espacioAuto = true
        refrescar()
    }

    /** Quita la última letra escrita, sin tocar correcciones (para cambiarla por una tilde) */
    override fun deshacerLetra() {
        try {
            val ic = ic() ?: return
            marcarEdicion()
            if (palabra.isNotEmpty()) {
                val quitar = if (palabra.length >= 2 && Character.isSurrogatePair(palabra[palabra.length - 2], palabra[palabra.length - 1])) 2 else 1
                palabra.setLength(palabra.length - quitar)
                if (palabra.isEmpty()) {
                    ic.beginBatchEdit(); ic.setComposingText("", 1); ic.finishComposingText(); ic.endBatchEdit()
                    reiniciarPalabra()
                } else ic.setComposingText(palabra, 1)
            } else {
                ic.beginBatchEdit(); ic.finishComposingText(); ic.deleteSurroundingText(1, 0); ic.endBatchEdit()
            }
        } catch (e: Throwable) { Registro.anotar("deshacer", e) }
    }

    /** Eliminar una sugerencia: sale de la barra y del cerebro (se recupera en Ajustes) */
    override fun olvidar(texto: String) {
        val m = Cerebro.motor ?: return
        m.aprendido.eliminar(texto)
        Cerebro.guardar()
        propuesta = null; propuestaDe = ""
        if (palabra.isNotEmpty()) pedirSugerencias() else refrescar()
    }

    // ---------- Deslizar ----------

    override fun deslizado(xs: FloatArray, ys: FloatArray, n: Int) {
        val d = Cerebro.deslizar ?: return
        val ic = ic() ?: return
        // Lo que se estaba escribiendo se cierra primero
        if (palabra.isNotEmpty()) {
            if (vinoDeslizado) { aprenderPalabra(palabra.toString()); ic.commitText(palabra, 1); reiniciarPalabra() }
            else terminarPalabra("")
        }
        val ctx = contexto()
        val mayus = shift
        val copiaX = xs.copyOf(n); val copiaY = ys.copyOf(n)
        ejecutar.execute {
            val res = try { d.decodificar(copiaX, copiaY, n, ctx) } catch (e: Throwable) { Registro.anotar("deslizar", e); emptyList() }
            principal.post { ponerDeslizado(res, mayus) }
        }
    }

    private fun ponerDeslizado(res: List<String>, mayus: Int) {
        val ic = ic() ?: return
        if (res.isEmpty()) return
        val ajustar = { w: String -> if (mayus == 2) w.uppercase() else if (mayus == 1) Texto.capitalizar(w) else w }
        val lista = res.map(ajustar)
        marcarEdicion()
        ic.beginBatchEdit()
        val previo = antes(1)
        if (previo.isNotEmpty() && !previo[0].isWhitespace() && previo[0] !in "(¿¡\"'@#/") ic.commitText(" ", 1)
        palabra.setLength(0)
        palabra.append(lista[0])
        for (i in lista[0].indices) ponerToque(i, Float.NaN, Float.NaN)
        ic.setComposingText(palabra, 1)
        ic.endBatchEdit()
        vinoDeslizado = true
        alternativasDeslizar = lista
        espacioAuto = false
        ultimaCorreccion = null
        if (shift == 1) { shift = 0; teclado?.shift = 0 }
        propuesta = Propuesta(lista.map { Opcion(it, 0f, false, false) }, null, true)
        propuestaDe = palabra.toString()
        actualizarBarra()
    }

    // ---------- Cursor y selección ----------

    private fun soltarComposicion() {
        if (palabra.isNotEmpty()) {
            ic()?.finishComposingText()
            if (vinoDeslizado) aprenderPalabra(palabra.toString())
            reiniciarPalabra()
        }
        ultimaCorreccion = null; espacioAuto = false
    }

    /** Trackpad seleccionando: las flechas van con mayúscula (extienden la selección) */
    private var seleccionandoTrackpad = false

    override fun seleccionTrackpad(activa: Boolean) {
        if (activa) soltarComposicion()
        seleccionandoTrackpad = activa
    }

    /** Flecha con Shift apretado: mueve un extremo de la selección (funciona en casi todas las apps) */
    private fun flechaConShift(codigo: Int) {
        val ic = ic() ?: return
        val t = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, codigo, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, codigo, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    private var chocado = 0

    override fun cursor(pasos: Int) {
        soltarComposicion()
        // Al llegar al inicio o al final del texto, el cursor "choca": rebote y vibración suave
        val lado = if (pasos < 0 && selFin <= 0) -1 else if (pasos > 0 && despues(1).isEmpty()) 1 else 0
        if (lado != 0 && !seleccionandoTrackpad) {
            if (chocado != lado) { chocado = lado; teclado?.rebotar(lado); respuesta.tocar(Respuesta.Tipo.FUNCION) }
            return
        }
        chocado = 0
        marcarEdicion()
        val tecla = if (pasos < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(pasos).coerceAtMost(20)) { if (seleccionandoTrackpad) flechaConShift(tecla) else sendDownUpKeyEvents(tecla) }
    }

    override fun cursorVertical(pasos: Int) {
        soltarComposicion()
        marcarEdicion()
        val tecla = if (pasos < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(kotlin.math.abs(pasos).coerceAtMost(10)) { if (seleccionandoTrackpad) flechaConShift(tecla) else sendDownUpKeyEvents(tecla) }
    }

    override fun seleccionBorrar(palabras: Int) {
        val ic = ic() ?: return
        if (borrarFin < 0) {
            soltarComposicion()
            borrarFin = selFin
            borrarAntes = antes(600)
        }
        if (borrarFin < 0) return
        var i = borrarAntes.length
        repeat(palabras) {
            while (i > 0 && !Texto.esLetraPalabra(borrarAntes[i - 1])) i--
            while (i > 0 && Texto.esLetraPalabra(borrarAntes[i - 1])) i--
        }
        val inicio = (borrarFin - (borrarAntes.length - i)).coerceAtLeast(0)
        marcarEdicion()
        ic.setSelection(inicio, borrarFin)
    }

    override fun terminarSeleccionBorrar(borrar: Boolean) {
        val ic = ic() ?: return
        marcarEdicion()
        if (borrarFin >= 0) {
            if (borrar) ic.commitText("", 1) else ic.setSelection(borrarFin, borrarFin)
        }
        borrarFin = -1; borrarAntes = ""
        refrescar()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        try { seleccionCambio(newSelStart, newSelEnd, candidatesStart, candidatesEnd) } catch (e: Throwable) { Registro.anotar("seleccion", e) }
    }

    private fun seleccionCambio(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        selIni = newSelStart; selFin = newSelEnd
        if (borrarFin >= 0) return
        val nuestra = SystemClock.uptimeMillis() - ultimaEdicionMs < 250
        // Escribiste o moviste el cursor: la revisión anterior ya no vale
        revision?.let { rv -> if (nuestra || newSelStart != newSelEnd || newSelStart < rv.ini || newSelStart > rv.fin) revision = null }
        if (palabra.isNotEmpty()) {
            if (candidatesStart < 0 || newSelStart != newSelEnd || newSelEnd != candidatesEnd) {
                // ¿Aviso atrasado de nuestra propia edición, o la app cambió el texto (por
                // ejemplo, lo vació al ENVIAR)? Se pregunta a la app qué hay de verdad antes del
                // cursor: si la palabra sigue ahí, era un aviso atrasado y se ignora; si no, se
                // suelta. Antes se ignoraba a ciegas y, si enviabas rápido, la palabra enviada
                // volvía a aparecer al escribir la siguiente letra.
                // Escribiendo rápido llegan muchos avisos atrasados con la palabra aún abierta:
                // esos se ignoran sin preguntarle nada a la app (preguntar cada vez trababa)
                if (nuestra && candidatesStart >= 0 && newSelStart == newSelEnd) return
                if (nuestra && newSelStart == newSelEnd && newSelEnd > 0 && antes(palabra.length) == palabra.toString()) return
                currentInputConnection?.finishComposingText()
                reiniciarPalabra()
                ultimaCorreccion = null; espacioAuto = false
                refrescar()
            }
        } else if (!nuestra) {
            ultimaCorreccion = null; espacioAuto = false
            if (newSelStart == newSelEnd && candidatesStart < 0) recapturar(false)
            refrescar()
            if (palabra.isEmpty() && newSelStart == newSelEnd) revisarEnCursor()
        }
    }

    /**
     * Tocaste dentro de una palabra: si está mal escrita, la barra muestra la palabra tal cual
     * y al lado cómo debería ir. (Si el cursor queda al final de la palabra, eso ya lo hace
     * [recapturar] reabriéndola.)
     */
    private fun revisarEnCursor() {
        if (!sugerir || modo != Distribuciones.LETRAS || buscandoEmoji || selIni != selFin) return
        val m = Cerebro.motor ?: return
        val al = alrededor(32, 32) ?: return
        val a = al.antes; val d = al.despues
        val cursor = al.cursor
        var i = a.length
        while (i > 0 && (a[i - 1].isLetter() || a[i - 1] == '\'')) i--
        var j = 0
        while (j < d.length && (d[j].isLetter() || d[j] == '\'')) j++
        if (j == 0) return                       // al final de la palabra: lo hace recapturar
        val izq = a.substring(i); val der = d.substring(0, j)
        val w = izq + der
        if (w.length < 2 || w.length > 30 || (i > 0 && a[i - 1].isDigit())) return
        val ini = cursor - izq.length; val fin = cursor + der.length
        if (ini < 0) return
        val ctx = contexto(a.substring(0, i))
        val t = turno.incrementAndGet()
        ejecutar.execute {
            if (turno.get() != t) return@execute
            val p = try { m.proponer(w, null, null, ctx, true) } catch (e: Throwable) { Registro.anotar("revisar", e); null }
            // Al tocar una palabra (bien o mal escrita), la barra ofrece esa palabra y otras
            // parecidas para cambiarla con un toque. Solo cuando tocas sobre ella.
            if (p == null) return@execute
            val mayus = w[0].isUpperCase()
            val ops = (listOfNotNull(p.autocorregir) + p.opciones.map { it.texto })
                .map { if (mayus) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
                .filter { !it.equals(w, ignoreCase = true) }.distinct().take(2)
            if (ops.isEmpty()) return@execute
            principal.post {
                if (turno.get() == t && palabra.isEmpty() && selIni == selFin && selIni in ini..fin) {
                    revision = Revision(ini, fin, w, ops, p.valida)
                    actualizarBarra()
                }
            }
        }
    }

    // ---------- Herramientas de la barra ----------

    override fun herramienta(cual: Int) {
        when (cual) {
            Barra.H_EMOJI -> mostrarPanel(Panel.EMOJI)
            Barra.H_PORTAPAPELES -> mostrarPanel(if (panel == Panel.PORTAPAPELES) Panel.TECLADO else Panel.PORTAPAPELES)
            Barra.H_EDITAR -> mostrarPanel(if (panel == Panel.EDICION) Panel.TECLADO else Panel.EDICION)
            Barra.H_UNA_MANO -> prefs.edit().putInt(Ajustes.UNA_MANO, if (cfg.unaMano == 0) 2 else 0).apply()
            Barra.H_TAMANO -> abrirTamano()
            Barra.H_AJUSTES -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- Ajustar tamaño ----------

    private fun abrirTamano() {
        val aj = ajusteTamano ?: return
        val kb = teclado ?: return
        mostrarPanel(Panel.TECLADO)
        aj.altura = cfg.altura
        aj.margen = cfg.margenAbajo
        aj.ancho = cfg.ancho
        aj.anchoPantalla = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        aj.altoBase = (kb.height * 100f / cfg.altura).coerceAtLeast(1f)
        aj.visibility = View.VISIBLE
        aj.invalidate()
    }

    override fun tamano(altura: Int, margen: Int, ancho: Int, fin: Boolean) {
        teclado?.alturaForzada = altura
        marco?.margenAbajo = (margen * resources.displayMetrics.density).toInt()
        marco?.margenLado = ladoPara(ancho)
        if (fin) prefs.edit().putInt(Ajustes.ALTURA, altura).putInt(Ajustes.MARGEN_ABAJO, margen).putInt(Ajustes.ANCHO, ancho).apply()
    }

    /** Espacio a cada lado (px) para que el teclado ocupe [ancho] % de la pantalla, centrado */
    private fun ladoPara(ancho: Int): Int =
        (resources.displayMetrics.widthPixels * (100 - ancho.coerceIn(70, 100)) / 200f).toInt()

    override fun cerrarTamano() {
        ajusteTamano?.visibility = View.GONE
        teclado?.alturaForzada = null
        // Si se cerró sin «Listo», vuelve a lo guardado
        val guardado = Config(prefs)
        marco?.margenAbajo = (guardado.margenAbajo * resources.displayMetrics.density).toInt()
        marco?.margenLado = ladoPara(guardado.ancho)
        teclado?.requestLayout()
    }

    override fun unaMano(accion: Int) {
        if (accion == 0) prefs.edit().putInt(Ajustes.UNA_MANO, 0).apply()
        else prefs.edit().putInt(Ajustes.UNA_MANO, if (cfg.unaMano == 1) 2 else 1).apply()
    }

    private fun mostrarPanel(p: Panel, conAnimacion: Boolean = true) {
        val antes = panel
        panel = p
        // Ahorro: los paneles (emojis, edición, portapapeles) cambian directo, sin animación
        val animar = conAnimacion && !Ahorro.activo && antes != p && marco?.isShown == true
        // Primero se prepara el contenido y recién después se anima: así el primer cuadro de
        // la animación no carga nada (antes se armaba todo en medio y daba un tirón)
        when (p) {
            Panel.EMOJI -> { soltarComposicion(); panelEmoji?.refrescar() }
            Panel.PORTAPAPELES -> { panelCopiado?.activo = cfg.portapapeles; panelCopiado?.elementos = portapapeles.elementos() }
            Panel.EDICION -> { soltarComposicion(); panelEdicion?.seleccionando = false }
            Panel.TECLADO -> {}
        }
        if (animar && (p == Panel.EMOJI || antes == Panel.EMOJI) && (p == Panel.TECLADO || antes == Panel.TECLADO)) {
            // Emojis como en iOS: el panel sube desde abajo tapando las letras, y al volver baja
            deslizarEmojis(p == Panel.EMOJI)
        } else {
            revelado?.cancel()
            transicion(teclado, p == Panel.TECLADO, View.INVISIBLE, animar)
            transicion(panelEmoji, p == Panel.EMOJI, View.INVISIBLE, animar)
            if (p == Panel.EMOJI) panelEmoji?.soltar()
        }
        transicion(panelEdicion, p == Panel.EDICION, View.GONE, animar)
        transicion(panelCopiado, p == Panel.PORTAPAPELES, View.GONE, animar)
        teclado?.cancelarTodo()
        actualizarBarra()
    }

    /**
     * Cambio de panel con transición: el que entra aparece subiendo apenas y el que sale se
     * desvanece rápido. Usa las animaciones de Android (en la GPU: casi no gasta).
     */
    private fun transicion(v: View?, mostrar: Boolean, oculto: Int, @Suppress("UNUSED_PARAMETER") conAnimacion: Boolean) {
        v ?: return
        // Paneles (emojis, portapapeles, edición): fundido corto subiendo apenas
        val animar = conAnimacion
        v.animate().cancel()
        val dy = 10 * resources.displayMetrics.density
        if (mostrar) {
            if (v.visibility == View.VISIBLE && v.alpha == 1f && v.scaleX == 1f) return
            v.visibility = View.VISIBLE
            v.scaleX = 1f; v.scaleY = 1f
            if (animar) {
                v.alpha = 0f; v.translationY = dy
                v.animate().alpha(1f).translationY(0f).setDuration(200L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).withLayer().start()
            } else { v.alpha = 1f; v.translationY = 0f }
        } else {
            if (v.visibility != View.VISIBLE) return
            if (animar) {
                v.animate().alpha(0f).setDuration(120L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)).withLayer().withEndAction {
                    v.visibility = oculto; v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
                }.start()
            } else {
                v.visibility = oculto; v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
                if (v === panelEmoji) v.background = null   // quedaba el fondo de la animación
            }
        }
    }

    /**
     * Emojis «desde el botón»: un círculo nace en la tecla emoji y crece hasta cubrir el
     * teclado, revelando el panel; al cerrar se encoge de vuelta hacia la tecla.
     * Es un revelado circular de Android: lo recorta la GPU sobre el panel ya dibujado y
     * quieto (congelado), así no se redibuja nada en cada cuadro y va fluido.
     */
    private fun deslizarEmojis(subir: Boolean) {
        val pe = panelEmoji ?: return
        val kb = teclado ?: return
        revelado?.cancel()
        kb.animate().cancel(); pe.animate().cancel()
        val w = pe.width.takeIf { it > 0 } ?: kb.width
        val h = pe.height.takeIf { it > 0 } ?: kb.height
        if (w <= 0 || h <= 0 || !pe.isAttachedToWindow) {
            // Sin medidas todavía: cambio directo, sin animación
            pe.visibility = if (subir) View.VISIBLE else View.INVISIBLE
            kb.alpha = if (subir) 0f else 1f; kb.scaleX = 1f; kb.scaleY = 1f
            if (subir) pe.soltar()
            return
        }
        val centro = kb.centroDe(K.EMOJI) ?: android.graphics.PointF(w * 0.15f, h * 0.9f)
        val cx = centro.x.toInt(); val cy = centro.y.toInt()
        // Radio que cubre todo el panel desde ese punto (hasta la esquina más lejana)
        val max = kotlin.math.hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat()
        // Si se invierte a la mitad, sigue desde el tamaño en que iba
        val ahora = SystemClock.uptimeMillis()
        val previo = if (ahora - reveladoMs < reveladoDura) {
            val f = curvaRevelado.getInterpolation(((ahora - reveladoMs).toFloat() / reveladoDura).coerceIn(0f, 1f))
            if (reveladoAbre) f else 1f - f
        } else if (subir) 0f else 1f
        pe.translationY = 0f; pe.alpha = 1f
        pe.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tema.fondo, tema.fondoAbajo))
        pe.visibility = View.VISIBLE
        kb.visibility = View.VISIBLE; kb.scaleX = 1f; kb.scaleY = 1f; kb.translationY = 0f
        if (subir) kb.alpha = 1f else { pe.congelar(); kb.alpha = 1f }
        val dura = if (subir) ABRE_EMOJIS else CIERRA_EMOJIS
        val anim = try {
            android.view.ViewAnimationUtils.createCircularReveal(pe, cx, cy, max * previo, if (subir) max else 0f)
        } catch (e: Throwable) { Registro.anotar("revelar emojis", e); null }
        if (anim == null) {
            pe.visibility = if (subir) View.VISIBLE else View.INVISIBLE
            kb.alpha = if (subir) 0f else 1f
            return
        }
        pe.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        anim.duration = dura
        anim.interpolator = curvaRevelado
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelada = false
            override fun onAnimationCancel(a: android.animation.Animator) { cancelada = true }
            override fun onAnimationEnd(a: android.animation.Animator) {
                pe.setLayerType(View.LAYER_TYPE_NONE, null)
                if (cancelada) return
                if (subir && panel == Panel.EMOJI) {
                    pe.background = null
                    kb.alpha = 0f        // las letras quedan tapadas: no se dibujan
                    pe.soltar()
                } else if (!subir && panel != Panel.EMOJI) {
                    pe.visibility = View.INVISIBLE; pe.background = null
                }
            }
        })
        revelado = anim; reveladoMs = ahora; reveladoDura = dura; reveladoAbre = subir
        anim.start()
    }

    private var revelado: android.animation.Animator? = null
    private var reveladoMs = 0L
    private var reveladoDura = 1L
    private var reveladoAbre = true
    private val curvaRevelado = android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val ABRE_EMOJIS = 360L
    private val CIERRA_EMOJIS = 300L

    override fun menuTeclado(opcion: String) {
        try {
            when (opcion) {
                "Ajustes" -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Teclados" -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
                // Teclado a una mano, como en el iPhone (izquierda, completo o derecha)
                "◀ Mano" -> prefs.edit().putInt(Ajustes.UNA_MANO, 1).apply()
                "Completo" -> prefs.edit().putInt(Ajustes.UNA_MANO, 0).apply()
                "Mano ▶" -> prefs.edit().putInt(Ajustes.UNA_MANO, 2).apply()
            }
        } catch (e: Throwable) { Registro.anotar("menú teclado", e) }
    }

    override fun abc() {
        mostrarPanel(Panel.TECLADO)
        actualizarShift()
    }

    // ---------- Emojis ----------

    override fun emoji(e: String) {
        val ic = ic() ?: return
        marcarEdicion()
        // Si hay una palabra a medio escribir, se cierra como con espacio (corrigiéndola si estaba
        // mal escrita) y el emoji va después: antes el emoji reemplazaba la palabra en composición
        if (palabra.isNotEmpty()) terminarPalabra(" ")
        if (buscandoEmoji) {
            ic.commitText(e, 1)
            return
        }
        ic.commitText(e, 1)
        ultimaCorreccion = null; espacioAuto = false
        refrescar()
    }

    override fun buscar() {
        buscandoEmoji = true
        consultaEmoji.setLength(0)
        mostrarPanel(Panel.TECLADO)
        modo = Distribuciones.LETRAS; ponerDistribucion()
        shift = 0; teclado?.shift = 0
        buscarEmoji()
    }

    private fun buscarEmoji() {
        if (consultaEmoji.isEmpty()) {
            // Sin escribir nada: primero tus usados recientemente y después los emojis normales
            val recientes = prefs.getString("emojis_recientes", null)?.split('\n')?.filter { it.isNotEmpty() }?.take(30) ?: emptyList()
            val normales = PanelEmoji.cargar(this).flatMap { it.emojis }.filter { it !in recientes }.take(240)
            barra?.busqueda(true, "", recientes + normales, recientes.size)
            return
        }
        val res = BuscadorEmoji.buscar(this, consultaEmoji.toString(), 40)
        barra?.busqueda(true, consultaEmoji.toString(), res)
    }

    override fun emojiBuscado(e: String) {
        emoji(e)
        val prefsE = prefs.getString("emojis_recientes", null)?.split('\n')?.toMutableList() ?: mutableListOf()
        prefsE.remove(e); prefsE.add(0, e)
        prefs.edit().putString("emojis_recientes", prefsE.take(36).joinToString("\n")).apply()
    }

    override fun cerrarBusqueda() {
        if (!buscandoEmoji) return
        buscandoEmoji = false
        consultaEmoji.setLength(0)
        barra?.busqueda(false, "", emptyList())
        actualizarBarra()
    }

    // ---------- Edición ----------

    private var seleccionando = false

    override fun mover(tecla: Int) {
        soltarComposicion()
        marcarEdicion()
        val ic = ic() ?: return
        val meta = if (seleccionando) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, tecla, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, tecla, 0, meta))
    }

    override fun seleccionar(activo: Boolean) { seleccionando = activo }

    override fun accionTexto(id: Int) {
        soltarComposicion()
        marcarEdicion()
        ic()?.performContextMenuAction(id)
    }

    /** Borrar desde el panel de emojis o de edición */
    override fun borrar() = borrarTexto()

    // ---------- Portapapeles ----------

    private val alCopiar = ClipboardManager.OnPrimaryClipChangedListener {
        if (!cfg.portapapeles) return@OnPrimaryClipChangedListener
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val sensible = clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
            if (t.isBlank()) return@OnPrimaryClipChangedListener
            // Sin historial: solo se muestra lo último copiado en la barra
            chipTexto = if (sensible) "••••••" else t
            chipSensible = if (sensible) t else null
            chipMs = SystemClock.uptimeMillis()
            if (palabra.isEmpty()) actualizarBarra()
        } catch (e: Throwable) { Registro.anotar("portapapeles", e) }
    }
    private var chipSensible: String? = null

    override fun pegarChip() {
        val oferta = ofertaEscribir
        if (oferta != null && oferta == Codigos.actual()) Codigos.usado()
        ofertaEscribir = null
        val t = oferta ?: chipSensible ?: chipTexto ?: return
        if (oferta != null) {
            soltarComposicion(); marcarEdicion()
            ic()?.commitText(t, 1)
            refrescar()
            return
        }
        soltarComposicion()
        marcarEdicion()
        ic()?.commitText(t, 1)
        chipTexto = null; chipSensible = null
        refrescar()
    }

    override fun pegar(texto: String) {
        soltarComposicion()
        marcarEdicion()
        ic()?.commitText(texto, 1)
    }

    override fun fijar(texto: String, si: Boolean) {
        portapapeles.fijar(texto, si)
        panelCopiado?.elementos = portapapeles.elementos()
    }

    override fun quitar(texto: String) {
        portapapeles.quitar(texto)
        panelCopiado?.elementos = portapapeles.elementos()
    }

    override fun borrarTodo() {
        portapapeles.borrarNoFijos()
        panelCopiado?.elementos = portapapeles.elementos()
    }

    override fun activar() {
        prefs.edit().putBoolean(Ajustes.PORTAPAPELES, true).apply()
        panelCopiado?.activo = true
    }

    // ---------- Capturas de pantalla ----------

    private fun revisarCaptura(info: EditorInfo) {
        captura = null
        barra?.captura(null)
        if (!cfg.capturas || !Capturas.tienePermiso(this)) return
        val ultima = prefs.getLong("ultima_captura", 0L)
        Cerebro.hilo.execute {
            val cap = Capturas.buscar(this) ?: return@execute
            if (cap.ms <= ultima || !Capturas.aceptaImagen(info, cap.mime)) return@execute
            val mini = Capturas.miniatura(this, cap.uri)
            principal.post {
                if (currentInputEditorInfo?.packageName == info.packageName) { captura = cap; barra?.captura(mini); if (palabra.isEmpty()) actualizarBarra() }
            }
        }
    }

    override fun enviarCaptura() {
        val cap = captura ?: return
        val info = currentInputEditorInfo ?: return
        Cerebro.hilo.execute {
            val uri = Capturas.copiarParaEnviar(this, cap) ?: return@execute
            principal.post {
                try {
                    val contenido = InputContentInfo(uri, ClipDescription("captura", arrayOf(cap.mime)), null)
                    val ok = ic()?.commitContent(contenido, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true
                    if (ok) {
                        prefs.edit().putLong("ultima_captura", cap.ms).apply()
                        captura = null; barra?.captura(null); actualizarBarra()
                    }
                } catch (e: Throwable) { Registro.anotar("enviar captura", e) }
            }
        }
    }

    override fun descartarCaptura() {
        val cap = captura ?: return
        // Ya no se vuelve a ofrecer esta captura (la foto sigue en tu galería)
        prefs.edit().putLong("ultima_captura", cap.ms).apply()
        captura = null; barra?.captura(null); actualizarBarra()
        respuesta.tocar(Respuesta.Tipo.FUNCION)
    }

    override fun respuesta(tipo: Respuesta.Tipo) = respuesta.tocar(tipo)

    override fun deshacerCorreccion() {
        val corr = ultimaCorreccion ?: return
        if (palabra.isNotEmpty() || antes(corr.puesta.length + corr.separador.length) != corr.puesta + corr.separador) return
        try { borrarTexto() } catch (e: Throwable) { Registro.anotar("deshacer corrección", e) }
    }
}

/** Fondo del teclado: negro, esquinas de arriba curvas y filo del color de la app */
class Marco(c: Context) : FrameLayout(c) {
    var tema = Tema(Tema.AZUL); set(v) { field = v; invalidate() }
    var conBorde = false; set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val forma = Path()
    private val r = RectF()

    /** Alto de la barra de navegación de Android (el teclado queda encima) */
    var abajoSistema = 0; set(v) { field = v; aplicarRelleno() }
    /** Espacio extra abajo que elige el usuario al ajustar el tamaño (en px) */
    var margenAbajo = 0; set(v) { field = v; aplicarRelleno() }
    /** Espacio extra a cada lado cuando el teclado es más angosto (en px) */
    var margenLado = 0; set(v) { if (field != v) { field = v; aplicarRelleno(); requestLayout() } }

    private val lado = (1 * resources.displayMetrics.density).toInt()

    init {
        setWillNotDraw(false)
        aplicarRelleno()
    }

    private fun aplicarRelleno() = setPadding(lado + margenLado, (6 * d).toInt(), lado + margenLado, abajoSistema + margenAbajo + (1 * d).toInt())

    /**
     * El alto lo pone solo la barra con el teclado (el primer hijo). Las demás capas (globos,
     * ajuste de tamaño) se ajustan a ese mismo tamaño: si se midieran solas, ocuparían toda la
     * pantalla.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contenido = getChildAt(0) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val anchoDentro = w - paddingLeft - paddingRight
        contenido.measure(MeasureSpec.makeMeasureSpec(anchoDentro, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = contenido.measuredHeight + paddingTop + paddingBottom
        for (i in 1 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(anchoDentro, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contenido.measuredHeight, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    /** Con desenfoque real detrás (vidrio): el tinte es translúcido */
    var vidrio = false; set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * Vidrio líquido oscuro: un tinte encima del desenfoque (o un fondo sólido si el teléfono
     * no desenfoca), apenas más claro arriba. Esquinas curvas arriba, recto abajo y SIN líneas
     * de borde.
     */
    /** Como en iOS: un fondo liso y recto, sin esquinas ni líneas */
    // Degradado vertical muy suave (se arma solo al cambiar de alto o de tema)
    private var degradado: android.graphics.Shader? = null
    private var degradadoDe = ""

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val clave = "${h.toInt()}:${tema.fondo}:${tema.fondoAbajo}"
        if (clave != degradadoDe) {
            degradado = android.graphics.LinearGradient(0f, 0f, 0f, h, tema.fondo, tema.fondoAbajo, android.graphics.Shader.TileMode.CLAMP)
            degradadoDe = clave
        }
        fondo.shader = degradado
        c.drawRect(0f, 0f, width.toFloat(), h, fondo)
    }
}

/** Donde van el teclado y los paneles: todos con el alto exacto del teclado */
class Cuerpo(c: Context) : FrameLayout(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val teclado = getChildAt(0) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val exactoW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        teclado.measure(exactoW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = teclado.measuredHeight
        for (i in 1 until childCount) {
            val v = getChildAt(i)
            if (v.visibility != View.GONE) v.measure(exactoW, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }
}

/** Buscar emojis por palabra (\"risa\", \"cora\", \"perr\") */
object BuscadorEmoji {
    private var mapa: List<Pair<String, String>>? = null

    private fun cargar(c: Context): List<Pair<String, String>> {
        mapa?.let { return it }
        val l = ArrayList<Pair<String, String>>(800)
        try {
            c.assets.open("emoji_palabras.txt").bufferedReader().useLines { ls ->
                for (linea in ls) {
                    if (linea.isEmpty() || linea[0] == '#') continue
                    val i = linea.lastIndexOf(':')
                    if (i <= 0) continue
                    val e = linea.substring(i + 1).trim()
                    for (p in linea.substring(0, i).split(',')) { val k = Texto.normal(p.trim()); if (k.isNotEmpty()) l.add(k to e) }
                }
            }
        } catch (e: Throwable) { Registro.anotar("emoji palabras", e) }
        mapa = l
        return l
    }

    fun buscar(c: Context, consulta: String, max: Int): List<String> {
        val q = Texto.normal(consulta.trim())
        if (q.isEmpty()) return emptyList()
        val puntos = LinkedHashMap<String, Int>()
        for ((k, e) in cargar(c)) {
            val p = when {
                k == q -> 0
                k.startsWith(q) -> 10 + k.length
                k.split(' ').any { it.startsWith(q) } -> 40 + k.length
                else -> continue
            }
            val previo = puntos[e]
            if (previo == null || p < previo) puntos[e] = p
        }
        return puntos.entries.sortedBy { it.value }.take(max).map { it.key }
    }
}

@Suppress("unused")
private fun Bitmap.vacio() = width == 0
