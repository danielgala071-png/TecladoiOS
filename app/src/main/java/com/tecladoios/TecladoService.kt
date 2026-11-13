package com.tecladoios

import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
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
    private var acentoApp = Tema.AZUL

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

    private class Correccion(val original: String, val puesta: String, val separador: String)
    private var ultimaCorreccion: Correccion? = null

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
    private var fichaEscaner = ""

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
        val filtro = IntentFilter(EscanerActivity.ACCION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receptorEscaner, filtro, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receptorEscaner, filtro)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receptorEscaner) } catch (_: Exception) { }
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
        val pe = PanelEmoji(c).apply { oyente = this@TecladoService; visibility = View.GONE }
        val ed = PanelEdicion(c).apply { oyente = this@TecladoService; visibility = View.GONE; alResponder = { respuesta.tocar(it) } }
        val pc = PanelPortapapeles(c).apply { oyente = this@TecladoService; visibility = View.GONE; alResponder = { respuesta.tocar(it) } }
        val aj = AjusteTamano(c).apply { oyente = this@TecladoService; visibility = View.GONE }
        val cuerpo = Cuerpo(c).apply {
            addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            for (v in listOf(pe, ed, pc)) addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val columna = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(br, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * resources.displayMetrics.density).toInt()))
            addView(cuerpo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val m = Marco(c).apply {
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
        aplicarTema(acentoApp)
        return m
    }

    override fun onWindowShown() {
        super.onWindowShown()
        window?.window?.let { w ->
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.BLACK
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cfg = Config(prefs); respuesta.config = cfg
        teclado?.config = cfg
        teclado?.unaMano = cfg.unaMano
        barra?.herramientasActivas = cfg.barraHerramientas
        leerCampo(info)
        if (!restarting) {
            reiniciarPalabra()
            ultimaCorreccion = null; espacioAuto = false
            mostrarPanel(Panel.TECLADO)
            cerrarBusqueda()
        }
        selIni = info.initialSelStart; selFin = info.initialSelEnd
        ponerDistribucion()
        actualizarIntro(info)
        // Color de la app
        val pkg = info.packageName
        val conocido = ColorApp.conocido(this, pkg)
        if (conocido != null) aplicarTema(conocido)
        else if (pkg != null) { aplicarTema(Tema.AZUL); ColorApp.calcular(this, pkg) { color -> if (currentInputEditorInfo?.packageName == pkg) aplicarTema(color) } }
        actualizarShift()
        revisarCaptura(info)
        actualizarBarra()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        teclado?.cancelarTodo()
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
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> { modo = Distribuciones.NUMEROS; sugerir = false; corregir = false }
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
            EditorInfo.IME_ACTION_DONE -> "OK" to false
            else -> null to false
        }
        teclado?.textoIntro = txt
        teclado?.introDestacado = destacado
    }

    private fun aplicarTema(acento: Int) {
        acentoApp = acento
        val t = Tema(if (cfg.bordesApp) acento else Tema.AZUL)
        tema = t
        marco?.tema = t; teclado?.tema = t; barra?.tema = t; capa?.tema = t
        panelEmoji?.tema = t; panelEdicion?.tema = t; panelCopiado?.tema = t; ajusteTamano?.tema = t
        marco?.conBorde = false   // negro puro: sin filo de color
    }

    // ---------- Ayudas de texto ----------

    private fun ic(): InputConnection? = currentInputConnection

    private fun marcarEdicion() { ultimaEdicionMs = SystemClock.uptimeMillis() }

    private fun antes(n: Int): String = ic()?.getTextBeforeCursor(n, 0)?.toString() ?: ""
    private fun despues(n: Int): String = ic()?.getTextAfterCursor(n, 0)?.toString() ?: ""

    /** Contexto de la palabra actual: se lee una vez al empezarla (menos consultas a la app = menos batería) */
    private var ctxPalabra: List<String>? = null

    private fun contextoPalabra(): List<String> = ctxPalabra ?: contexto().also { ctxPalabra = it }

    private fun reiniciarPalabra() {
        ctxPalabra = null
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
    private fun contexto(): List<String> {
        val t = antes(80)
        val sinActual = if (palabra.isNotEmpty() && t.endsWith(palabra)) t.dropLast(palabra.length) else t
        val res = ArrayList<String>(2)
        var i = sinActual.length - 1
        while (res.size < 2 && i >= 0) {
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

    private fun inicioDeFrase(): Boolean {
        val t = antes(6).trimEnd(' ')
        return t.isEmpty() || t.last() in ".?!\n¡¿"
    }

    // ---------- Mayúsculas ----------

    private fun actualizarShift() {
        if (shift == 2) { teclado?.shift = 2; return }
        val auto = cfg.mayusAuto && mayusCampo && modo == Distribuciones.LETRAS && palabra.isEmpty() && run {
            val t = antes(4)
            if (t.isEmpty()) true
            else {
                val sinEsp = t.trimEnd(' ')
                (t.endsWith(" ") || t.endsWith("\n") || sinEsp.isEmpty()) &&
                    (sinEsp.isEmpty() || sinEsp.last() in ".?!\n")
            }
        }
        shift = if (auto) 1 else if (shift == 1 && palabra.isEmpty()) 0 else shift
        teclado?.shift = shift
    }

    // ---------- Teclas ----------

    override fun tecla(texto: String, x: Float, y: Float, esLetra: Boolean) {
        if (buscandoEmoji) { consultaEmoji.append(texto.lowercase()); buscarEmoji(); return }
        val ic = ic() ?: return
        var t = texto
        if (texto.length == 1 && texto[0].isLetter() && shift > 0) t = texto.uppercase()
        if (shift == 1 && texto.isNotEmpty() && texto[0].isLetter()) { shift = 0; teclado?.shift = 0 }
        val ch = t[0]
        val dePalabra = t.length == 1 && (ch.isLetterOrDigit() || ((ch == '\'' || ch == '-') && palabra.isNotEmpty()))
        marcarEdicion()
        if (dePalabra) {
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
        actualizarShift()
        actualizarBarra()
    }

    private fun espacioDespuesDe(t: String) = cfg.espacioAuto && campo == Distribuciones.CAMPO_TEXTO && t != "-"

    override fun codigo(tipo: Int) {
        when (tipo) {
            K.BORRAR -> borrarTexto()
            K.ESPACIO -> espacio()
            K.INTRO -> intro()
            K.SHIFT -> {
                val ahora = SystemClock.uptimeMillis()
                shift = when {
                    shift == 0 -> 1
                    shift == 1 && ahora - ultimoShiftMs < 350 -> 2
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
            ultimoEspacioMs = 0
            espacioAuto = false
            ultimaCorreccion = null
            actualizarShift(); actualizarBarra()
            return
        } else if (espacioAuto && antes(1) == " ") {
            espacioAuto = false
        } else {
            ic.commitText(" ", 1)
            ultimaCorreccion = null
        }
        ultimoEspacioMs = ahora
        if (modo == Distribuciones.SIMBOLOS || modo == Distribuciones.SIMBOLOS2) volverLetras()
        actualizarShift()
        actualizarBarra()
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
        actualizarShift()
        actualizarBarra()
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
        if (!vinoDeslizado && sugerir && corregir) {
            val p = propuestaPara(escrito, ctx)
            p?.autocorregir?.let { resultado = it }
        }
        // Dos palabras seguidas que se corrigen juntas ("a sido" → "ha sido")
        var anteriorNueva: String? = null
        var anteriorVieja: String? = null
        if (corregir && ctx.isNotEmpty() && Cerebro.motor != null) {
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
        marcarEdicion()
        ic.beginBatchEdit()
        if (anteriorNueva != null && anteriorVieja != null) {
            ic.setComposingText("", 1)
            ic.deleteSurroundingText(anteriorVieja.length + 1, 0)
            ic.commitText("$anteriorNueva $resultado$separador", 1)
        } else {
            ic.commitText(resultado + separador, 1)
        }
        ic.endBatchEdit()
        aprenderPalabra(resultado, ctx)
        ultimaCorreccion = if (resultado != escrito || anteriorNueva != null) {
            val puesta = if (anteriorNueva != null) "$anteriorNueva $resultado" else resultado
            val original = if (anteriorVieja != null) "$anteriorVieja $escrito" else escrito
            Correccion(original, puesta, separador)
        } else null
        reiniciarPalabra()
        actualizarShift()
        actualizarBarra()
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
        marcarEdicion()
        espacioAuto = false
        if (selIni != selFin && selIni >= 0) {
            if (palabra.isNotEmpty()) { ic.finishComposingText(); reiniciarPalabra() }
            ic.commitText("", 1)
            actualizarShift(); actualizarBarra()
            return
        }
        // Deshacer la corrección automática
        val corr = ultimaCorreccion
        if (corr != null && palabra.isEmpty()) {
            val esperado = corr.puesta + corr.separador
            if (antes(esperado.length) == esperado) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(esperado.length, 0)
                val ultima = corr.original.substringAfterLast(' ')
                val antesDeUltima = corr.original.dropLast(ultima.length)
                if (antesDeUltima.isNotEmpty()) ic.commitText(antesDeUltima, 1)
                palabra.append(ultima)
                for (i in ultima.indices) ponerToque(i, Float.NaN, Float.NaN)
                ic.setComposingText(palabra, 1)
                ic.endBatchEdit()
                Cerebro.motor?.aprendido?.let {
                    it.rechazar(corr.original.substringAfterLast(' '), corr.puesta.substringAfterLast(' '))
                    if (aprender) it.agregar(ultima)
                }
                ultimaCorreccion = null
                pedirSugerencias()
                return
            }
            ultimaCorreccion = null
        }
        if (palabra.isNotEmpty()) {
            val quitar = if (palabra.length >= 2 && Character.isSurrogatePair(palabra[palabra.length - 2], palabra[palabra.length - 1])) 2 else 1
            palabra.setLength(palabra.length - quitar)
            vinoDeslizado = false
            if (palabra.isEmpty()) { ic.setComposingText("", 1); ic.finishComposingText(); reiniciarPalabra(); actualizarShift(); actualizarBarra() }
            else { ic.setComposingText(palabra, 1); pedirSugerencias() }
            return
        }
        val previo = antes(16)
        if (previo.isEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            val it = BreakIterator.getCharacterInstance()
            it.setText(previo)
            val fin = previo.length
            val ini = it.preceding(fin).coerceAtLeast(0)
            ic.deleteSurroundingText(fin - ini, 0)
        }
        recapturar(true)
        actualizarShift()
        actualizarBarra()
    }

    override fun borrarRepetido(paso: Int) {
        if (paso < 14 || buscandoEmoji) { borrarTexto(); return }
        // Manteniendo mucho rato: borra palabras enteras
        val ic = ic() ?: return
        marcarEdicion()
        if (palabra.isNotEmpty()) { ic.setComposingText("", 1); ic.finishComposingText(); reiniciarPalabra(); actualizarBarra(); return }
        val t = antes(64)
        if (t.isEmpty()) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        var i = t.length
        while (i > 0 && !Texto.esLetraPalabra(t[i - 1])) i--
        while (i > 0 && Texto.esLetraPalabra(t[i - 1])) i--
        ic.deleteSurroundingText((t.length - i).coerceAtLeast(1), 0)
        ultimaCorreccion = null
        actualizarShift()
        actualizarBarra()
    }

    /**
     * Si el cursor queda justo al final de una palabra, la vuelve a abrir para corregirla.
     * Al borrar se usa la forma segura (se quita y se vuelve a poner la palabra), que no
     * depende de la posición del cursor (puede ir atrasada si borras rápido).
     */
    private fun recapturar(alBorrar: Boolean) {
        if (!sugerir || palabra.isNotEmpty() || modo != Distribuciones.LETRAS) return
        val ic = ic() ?: return
        val t = antes(40)
        if (t.isEmpty() || !t.last().isLetter()) return
        val sig = despues(1)
        if (sig.isNotEmpty() && Texto.esLetraPalabra(sig[0])) return
        var i = t.length
        while (i > 0 && (t[i - 1].isLetter() || t[i - 1] == '\'')) i--
        val w = t.substring(i)
        if (w.isEmpty() || w.length > 30 || (i > 0 && t[i - 1].isDigit())) return
        if (alBorrar) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(w.length, 0)
            ic.setComposingText(w, 1)
            ic.endBatchEdit()
        } else {
            if (selFin < w.length || selIni != selFin) return
            ic.setComposingRegion(selFin - w.length, selFin)
        }
        palabra.append(w)
        for (k in w.indices) ponerToque(k, Float.NaN, Float.NaN)
        pedirSugerencias()
    }

    // ---------- Sugerencias ----------

    private val ejecutar = Cerebro.hilo

    private fun pedirSugerencias() {
        if (!sugerir) return
        val m = Cerebro.motor ?: run { actualizarBarra(); return }
        val escrito = palabra.toString()
        val n = escrito.length
        val xs = tx.copyOf(n); val ys = ty.copyOf(n)
        val ctx = contextoPalabra()
        val t = turno.incrementAndGet()
        val deslizado = vinoDeslizado
        val alts = alternativasDeslizar
        ejecutar.execute {
            if (turno.get() != t) return@execute
            val p = try {
                if (deslizado) Propuesta(alts.map { Opcion(it, 0f, false, false) }, null, true)
                else m.proponer(escrito, xs, ys, ctx, corregir)
            } catch (e: Throwable) { Registro.anotar("proponer", e); null }
            if (p != null) principal.post {
                if (turno.get() == t && palabra.toString() == escrito) {
                    propuesta = p; propuestaDe = escrito
                    actualizarBarra()
                }
            }
        }
    }

    /** La propuesta para lo escrito: la ya calculada o, si aún no llega, se calcula ahora */
    private fun propuestaPara(escrito: String, ctx: List<String>): Propuesta? {
        if (propuestaDe == escrito) propuesta?.let { return it }
        val m = Cerebro.motor ?: return null
        val n = escrito.length
        return try { m.proponer(escrito, tx.copyOf(n), ty.copyOf(n), ctx, corregir) } catch (e: Throwable) { Registro.anotar("proponer ya", e); null }
    }

    private fun actualizarBarra() {
        val b = barra ?: return
        if (buscandoEmoji) return
        // Lo copiado se ofrece un minuto
        val chip = chipTexto?.takeIf { SystemClock.uptimeMillis() - chipMs < 60_000 && cfg.portapapeles }
        if (chip == null) chipTexto = null
        b.chip(chip)
        if (palabra.isNotEmpty() && sugerir) {
            val p = propuesta?.takeIf { propuestaDe == palabra.toString() }
            if (p == null) { b.mostrarSugerencias(emptyList(), palabra.toString(), false); return }
            val ops = p.opciones.map { it.texto }.toMutableList()
            val auto = p.autocorregir
            if (auto != null && corregir) {
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
        if (palabra.isEmpty() && sugerir && cfg.prediccion && Cerebro.motor != null) {
            val t = antes(2)
            if (t.isNotEmpty() && (t.last() == ' ') && selIni == selFin) {
                val pred = Cerebro.motor?.predecir(contexto(), inicioDeFrase()) ?: emptyList()
                if (pred.isNotEmpty() && chip == null) { b.mostrarSugerencias(pred, null, false); return }
            }
        }
        b.limpiar()
    }

    override fun elegir(texto: String, esLiteral: Boolean) {
        val ic = ic() ?: return
        marcarEdicion()
        val ctx = contexto()
        ic.beginBatchEdit()
        if (palabra.isNotEmpty()) {
            val escrito = palabra.toString()
            ic.commitText("$texto ", 1)
            if (esLiteral && aprender) Cerebro.motor?.aprendido?.agregar(texto)
            ultimaCorreccion = if (texto != escrito && !esLiteral) Correccion(escrito, texto, " ") else null
        } else {
            val previo = antes(1)
            val pre = if (previo.isNotEmpty() && previo != " " && previo != "\n") " " else ""
            ic.commitText("$pre$texto ", 1)
            ultimaCorreccion = null
        }
        ic.endBatchEdit()
        aprenderPalabra(texto, ctx)
        reiniciarPalabra()
        espacioAuto = true
        actualizarShift()
        actualizarBarra()
    }

    override fun olvidar(texto: String) {
        val m = Cerebro.motor ?: return
        if (!m.dic.existe(Texto.normal(texto))) {
            m.aprendido.quitar(texto)
            Cerebro.guardar()
            android.widget.Toast.makeText(this, "Se olvidó «$texto»", android.widget.Toast.LENGTH_SHORT).show()
            pedirSugerencias()
        }
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

    override fun cursor(pasos: Int) {
        soltarComposicion()
        marcarEdicion()
        val tecla = if (pasos < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(pasos).coerceAtMost(20)) { sendDownUpKeyEvents(tecla) }
    }

    override fun cursorVertical(pasos: Int) {
        soltarComposicion()
        marcarEdicion()
        val tecla = if (pasos < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(kotlin.math.abs(pasos).coerceAtMost(10)) { sendDownUpKeyEvents(tecla) }
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
        actualizarShift(); actualizarBarra()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selIni = newSelStart; selFin = newSelEnd
        if (borrarFin >= 0) return
        val nuestra = SystemClock.uptimeMillis() - ultimaEdicionMs < 250
        if (palabra.isNotEmpty()) {
            if (candidatesStart < 0 || newSelStart != newSelEnd || newSelEnd != candidatesEnd) {
                if (nuestra && candidatesStart >= 0) return
                if (candidatesStart >= 0) currentInputConnection?.finishComposingText()
                reiniciarPalabra()
                ultimaCorreccion = null; espacioAuto = false
                actualizarShift(); actualizarBarra()
            }
        } else if (!nuestra) {
            ultimaCorreccion = null; espacioAuto = false
            if (newSelStart == newSelEnd && candidatesStart < 0) recapturar(false)
            actualizarShift(); actualizarBarra()
        }
    }

    // ---------- Herramientas de la barra ----------

    override fun herramienta(cual: Int) {
        when (cual) {
            Barra.H_EMOJI -> mostrarPanel(Panel.EMOJI)
            Barra.H_PORTAPAPELES -> mostrarPanel(if (panel == Panel.PORTAPAPELES) Panel.TECLADO else Panel.PORTAPAPELES)
            Barra.H_EDITAR -> mostrarPanel(if (panel == Panel.EDICION) Panel.TECLADO else Panel.EDICION)
            Barra.H_UNA_MANO -> prefs.edit().putInt(Ajustes.UNA_MANO, if (cfg.unaMano == 0) 2 else 0).apply()
            Barra.H_ESCANEAR -> abrirEscaner()
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
        aj.altoBase = (kb.height * 100f / cfg.altura).coerceAtLeast(1f)
        aj.visibility = View.VISIBLE
        aj.invalidate()
    }

    override fun tamano(altura: Int, margen: Int, fin: Boolean) {
        teclado?.alturaForzada = altura
        marco?.margenAbajo = (margen * resources.displayMetrics.density).toInt()
        if (fin) prefs.edit().putInt(Ajustes.ALTURA, altura).putInt(Ajustes.MARGEN_ABAJO, margen).apply()
    }

    override fun cerrarTamano() {
        ajusteTamano?.visibility = View.GONE
        teclado?.alturaForzada = null
        teclado?.requestLayout()
    }

    override fun unaMano(accion: Int) {
        if (accion == 0) prefs.edit().putInt(Ajustes.UNA_MANO, 0).apply()
        else prefs.edit().putInt(Ajustes.UNA_MANO, if (cfg.unaMano == 1) 2 else 1).apply()
    }

    private fun mostrarPanel(p: Panel) {
        panel = p
        teclado?.visibility = if (p == Panel.TECLADO) View.VISIBLE else View.INVISIBLE
        panelEmoji?.visibility = if (p == Panel.EMOJI) View.VISIBLE else View.GONE
        panelEdicion?.visibility = if (p == Panel.EDICION) View.VISIBLE else View.GONE
        panelCopiado?.visibility = if (p == Panel.PORTAPAPELES) View.VISIBLE else View.GONE
        when (p) {
            Panel.EMOJI -> { soltarComposicion(); panelEmoji?.refrescar() }
            Panel.PORTAPAPELES -> { panelCopiado?.activo = cfg.portapapeles; panelCopiado?.elementos = portapapeles.elementos() }
            Panel.EDICION -> { soltarComposicion(); panelEdicion?.seleccionando = false }
            Panel.TECLADO -> {}
        }
        teclado?.cancelarTodo()
        actualizarBarra()
    }

    override fun abc() {
        mostrarPanel(Panel.TECLADO)
        actualizarShift()
    }

    // ---------- Emojis ----------

    override fun emoji(e: String) {
        val ic = ic() ?: return
        marcarEdicion()
        if (buscandoEmoji) {
            ic.commitText(e, 1)
            return
        }
        ic.commitText(e, 1)
        ultimaCorreccion = null; espacioAuto = false
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
        val res = BuscadorEmoji.buscar(this, consultaEmoji.toString(), 40)
        val lista = if (consultaEmoji.isEmpty()) PanelEmoji.cargar(this).firstOrNull()?.emojis?.take(30) ?: emptyList() else res
        barra?.busqueda(true, consultaEmoji.toString(), lista)
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
            if (!sensible && !esClave()) portapapeles.agregar(t)
            chipTexto = if (sensible) "••••••" else t
            chipSensible = if (sensible) t else null
            chipMs = SystemClock.uptimeMillis()
            if (palabra.isEmpty()) actualizarBarra()
        } catch (e: Throwable) { Registro.anotar("portapapeles", e) }
    }
    private var chipSensible: String? = null

    override fun pegarChip() {
        val t = chipSensible ?: chipTexto ?: return
        soltarComposicion()
        marcarEdicion()
        ic()?.commitText(t, 1)
        chipTexto = null; chipSensible = null
        actualizarShift(); actualizarBarra()
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
                if (currentInputEditorInfo?.packageName == info.packageName) { captura = cap; barra?.captura(mini) }
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
                        captura = null; barra?.captura(null)
                    }
                } catch (e: Throwable) { Registro.anotar("enviar captura", e) }
            }
        }
    }

    // ---------- Escáner ----------

    private val receptorEscaner = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val texto = i.getStringExtra(EscanerActivity.EXTRA_TEXTO) ?: return
            val ficha = i.getStringExtra(EscanerActivity.EXTRA_FICHA) ?: return
            if (fichaEscaner.isEmpty() || ficha != fichaEscaner) return
            fichaEscaner = ""
            principal.postDelayed({ ic()?.commitText(texto, 1) }, 250L)
        }
    }

    private fun abrirEscaner() {
        fichaEscaner = java.util.UUID.randomUUID().toString()
        try {
            startActivity(Intent(this, EscanerActivity::class.java).apply {
                putExtra(EscanerActivity.EXTRA_FICHA, fichaEscaner)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            fichaEscaner = ""
            Registro.anotar("abrir escáner", e)
        }
    }

    override fun respuesta(tipo: Respuesta.Tipo) = respuesta.tocar(tipo)
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

    private val lado = 0

    init {
        setWillNotDraw(false)
        aplicarRelleno()
    }

    private fun aplicarRelleno() = setPadding(lado, (2 * d).toInt(), lado, abajoSistema + margenAbajo)

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

    /** Negro puro AMOLED con la parte de arriba curva (barra de sugerencias redondeada), sin líneas */
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = 18 * d
        forma.reset()
        r.set(0f, 0f, w, h + rad)
        forma.addRoundRect(r, floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
        fondo.shader = null
        fondo.color = tema.fondo
        c.drawPath(forma, fondo)
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
