package com.tecladoios

import android.content.SharedPreferences
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import java.text.BreakIterator

/**
 * El teclado: letras, números y símbolos, y emojis, como el nativo del iPhone, con su barra de
 * sugerencias y su autocorrección (corrige al tocar espacio o un signo; borrar justo después
 * devuelve lo que habías escrito).
 */
/** Artículos y preposiciones que no van dos veces seguidas («el el» → «el») */
private val REPETIBLES_NO = setOf("el", "la", "los", "las", "un", "una", "de", "del", "al", "en", "con", "por", "para", "a")

class TecladoService : InputMethodService(), KeyboardView.Oyente, PanelEmoji.Oyente, PanelPortapapeles.Oyente,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var cfg: Config
    private lateinit var respuesta: Respuesta

    private var raiz: FrameLayout? = null
    private var teclado: KeyboardView? = null
    private var panel: PanelEmoji? = null
    private var panelClip: PanelPortapapeles? = null

    // ---------- Portapapeles ----------
    private lateinit var clip: Portapapeles
    /** Lo recién copiado: aparece en la barra para pegarlo (como el iPhone) */
    private var copiado: String? = null
    private var copiadoMs = 0L
    private val alCopiar = android.content.ClipboardManager.OnPrimaryClipChangedListener { revisarCopiado() }

    private fun revisarCopiado() {
        if (!cfg.portapapeles) return
        if (!clip.leerSistema()) return
        val r = clip.recortes().firstOrNull() ?: return
        copiado = r.texto; copiadoMs = r.ms
        if (palabra.isEmpty() && tocada == null) actualizarBarra()
    }

    private fun copiadoVigente(): String? {
        val c = copiado ?: return null
        if (System.currentTimeMillis() - copiadoMs > Portapapeles.EN_BARRA_MS) { copiado = null; return null }
        return c
    }

    private var info: EditorInfo? = null
    private var ultimoEspacioMs = 0L
    private var selIni = 0; private var selFin = 0

    // ---------- Sugerencias y autocorrección ----------
    @Volatile private var dic: Diccionario? = null
    @Volatile private var cargando = false
    private val hilo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "diccionario") }
    private val principal = android.os.Handler(android.os.Looper.getMainLooper())
    /** La palabra que estás escribiendo (justo antes del cursor) */
    private val palabra = StringBuilder()
    private var propuesta: Diccionario.Propuesta? = null
    private var turno = 0
    /** Barra de sugerencias en este campo */
    private var sugerir = false
    /** Autocorrección en este campo */
    private var corregir = false
    /** Se sigue la palabra que escribes (para la barra o para corregir) */
    private val seguir get() = sugerir || corregir
    /** Lo que muestra cada casilla de la barra */
    private val casillas = arrayOfNulls<KeyboardView.Sug>(3)
    /** Última corrección automática: borrar justo después la deshace */
    private var deshacerOriginal: String? = null
    private var deshacerPuesta: String? = null
    private var deshacerPos = -1
    /** Dónde debería estar el cursor tras lo último que escribió el teclado */
    private var esperado = -1
    private var pendientes = 0
    /** La última palabra terminada (para sugerir la siguiente); null al empezar una oración */
    private var anterior: String? = null
    private var anterior2: String? = null
    /** Dónde cayó el dedo en cada letra de la palabra (para corregir según el toque) */
    private val toques = ArrayList<FloatArray?>()
    /** Palabra ya escrita que tocaste (con el cursor en medio): la barra ofrece cómo corregirla */
    private var tocada: String? = null
    private var tocadaAntes = 0
    private var tocadaDespues = 0
    /** El cursor está en medio de una palabra: no se sigue lo que escribes (no se corrige a medias) */
    private var enMedio = false

    private fun vaciar() { palabra.setLength(0); toques.clear(); teclado?.probables = null }

    private fun toquesDe(w: String): List<FloatArray?>? = if (toques.size == w.length) ArrayList(toques) else null
    /** Palabras que suelen ir después (se muestran con la barra vacía) */
    private var predicciones: List<String> = emptyList()
    private val letrasBI: BreakIterator = BreakIterator.getCharacterInstance()

    override fun onCreate() {
        super.onCreate()
        prefs = Ajustes.prefs(this)
        cfg = Config(prefs)
        respuesta = Respuesta(this).also { it.config = cfg }
        prefs.registerOnSharedPreferenceChangeListener(this)
        clip = Portapapeles(this)
        try { getSystemService(android.content.ClipboardManager::class.java)?.addPrimaryClipChangedListener(alCopiar) } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        hilo.shutdown()
        try { getSystemService(android.content.ClipboardManager::class.java)?.removePrimaryClipChangedListener(alCopiar) } catch (_: Throwable) { }
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        respuesta.soltar()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, clave: String?) {
        if (clave == Ajustes.DIC_VERSION) { dic = null; if (seguir) cargarDiccionario(); return }
        if (clave == Ajustes.AJUSTE) { if (prefs.getString(Ajustes.AJUSTE, null) == null) teclado?.reiniciarAjuste(); return }
        if (clave == null || clave.startsWith("emojis") || clave.startsWith("palabras")) return
        cfg = Config(prefs)
        respuesta.config = cfg
        if (!cfg.sonido) respuesta.soltarSonidos()
        // Apagar el historial borra lo guardado
        if (clave == Ajustes.PORTAPAPELES && !cfg.portapapeles) { clip.borrarTodo(); copiado = null; cerrarEmojis(); actualizarBarra() }
        teclado?.config = cfg
        if (clave == Ajustes.TAMANO || clave == Ajustes.AJUSTE_AUTO) { cerrarEmojis(); teclado?.requestLayout() }
        teclado?.invalidate()
    }

    override fun onEvaluateFullscreenMode() = false

    /** Android pide memoria: se suelta todo lo que se puede volver a armar */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        respuesta.soltarSonidos()
        PanelEmoji.soltarCache()
        clip.soltar()
        if (!isInputViewShown) { cerrarEmojis(); dic = null }
    }

    override fun onCreateInputView(): View {
        try { window?.window?.setWindowAnimations(R.style.AnimacionTeclado) } catch (_: Throwable) { }
        try { window?.window?.navigationBarColor = Color.BLACK } catch (_: Throwable) { }
        val kb = KeyboardView(this).apply { config = cfg; oyente = this@TecladoService }
        kb.cargarAjuste(prefs.getString(Ajustes.AJUSTE, null))
        val r = FrameLayout(this).apply {
            addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnApplyWindowInsetsListener { _, ins ->
                val abajo = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                kb.abajoSistema = abajo
                panel?.abajoSistema = abajo
                panelClip?.abajoSistema = abajo
                ins
            }
        }
        raiz = r; teclado = kb; panel = null; panelClip = null
        return r
    }

    /** Solo el teclado recibe toques; la franja de arriba (globos) deja pasar los toques a la app */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val r = raiz ?: return
        val kb = teclado ?: return
        if (!isInputViewShown || r.height == 0) return
        val arriba = (r.height - kb.altoVisible()).coerceAtLeast(0)
        outInsets.contentTopInsets = arriba
        outInsets.visibleTopInsets = arriba
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, arriba, r.width, r.height)
    }

    override fun onStartInputView(ei: EditorInfo, restarting: Boolean) {
        super.onStartInputView(ei, restarting)
        info = ei
        selIni = ei.initialSelStart; selFin = ei.initialSelEnd
        cerrarEmojis()
        val kb = teclado ?: return
        kb.config = cfg
        val clase = ei.inputType and InputType.TYPE_MASK_CLASS
        val variacion = ei.inputType and InputType.TYPE_MASK_VARIATION
        val clave = variacion == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variacion == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variacion == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val texto = clase == InputType.TYPE_CLASS_TEXT && !clave
        sugerir = cfg.predictivo && texto
        corregir = texto && cfg.autocorreccion &&
            variacion != InputType.TYPE_TEXT_VARIATION_URI &&
            variacion != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variacion != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        kb.conBarra = sugerir
        if (seguir) cargarDiccionario()
        vaciar(); propuesta = null; olvidarDeshacer()
        anterior = null; anterior2 = null; predicciones = emptyList()
        esperado = ei.initialSelStart; pendientes = 0
        when (clase) {
            InputType.TYPE_CLASS_NUMBER -> kb.ponerModo(Distribuciones.NUMEROS,
                ei.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0)
            InputType.TYPE_CLASS_PHONE -> kb.ponerModo(Distribuciones.TELEFONO)
            InputType.TYPE_CLASS_DATETIME -> kb.ponerModo(Distribuciones.NUMEROS)
            // Al reiniciar la misma caja se conserva el modo, salvo que la app la haya vaciado
            // (WhatsApp, Telegram… reinician la caja al enviar con su botón)
            else -> if (!restarting || kb.distribucion.numerico || cajaVacia()) kb.ponerModo(Distribuciones.LETRAS)
        }
        kb.etiquetaIntro = etiquetaIntro(ei)
        actualizarMayus()
        // Lo copiado mientras el teclado estaba cerrado
        if (texto) revisarCopiado()
        recapturar()
    }

    private fun cargarDiccionario() {
        if (dic != null || cargando) return
        cargando = true
        hilo.execute {
            try { dic = Diccionario.cargar(this) } catch (_: Throwable) { }
            cargando = false
            principal.post { if (palabra.isNotEmpty()) pedir() }
        }
    }

    /** Después de escribir algo: dónde quedará el cursor */
    private fun movido(cambio: Int) {
        val base = if (selIni != selFin && selIni >= 0) minOf(selIni, selFin) else esperado
        esperado = if (base >= 0) base + cambio else -1
        pendientes++
    }

    private fun olvidarDeshacer() { deshacerOriginal = null; deshacerPuesta = null; deshacerPos = -1 }

    /** Lee la palabra justo antes del cursor (al tocar en otro lugar del texto) */
    private fun recapturar() {
        vaciar(); propuesta = null
        anterior = null; anterior2 = null; predicciones = emptyList()
        tocada = null; enMedio = false
        if (!seguir || selIni != selFin) { actualizarBarra(); return }
        val ic = currentInputConnection ?: return
        val antes = ic.getTextBeforeCursor(32, 0)
        val despues = ic.getTextAfterCursor(32, 0)
        // Tocaste en medio de una palabra ya escrita: la barra ofrece cómo corregirla (como el iPhone)
        if (antes != null && !despues.isNullOrEmpty() && despues[0].isLetter()) {
            var i = antes.length
            while (i > 0 && antes[i - 1].isLetter()) i--
            var j = 0
            while (j < despues.length && despues[j].isLetter()) j++
            val w = antes.substring(i) + despues.substring(0, j)
            enMedio = true
            if (sugerir && w.length >= 2 && (j < despues.length || despues.length < 32) && (i > 0 || antes.length < 32)) {
                tocada = w; tocadaAntes = antes.length - i; tocadaDespues = j
                pedirTocada(w)
            } else actualizarBarra()
            return
        }
        if (antes != null && (despues.isNullOrEmpty() || !despues[0].isLetter())) {
            var i = antes.length
            while (i > 0 && antes[i - 1].isLetter()) i--
            if (i == 0 || !antes[i - 1].isDigit()) palabra.append(antes, i, antes.length)
        }
        if (palabra.isNotEmpty()) pedir() else actualizarBarra()
    }

    private fun pedirTocada(w: String) {
        val d = dic ?: return
        val t = ++turno
        hilo.execute {
            val p = try { d.proponer(w) } catch (_: Throwable) { null }
            principal.post { if (t == turno && tocada == w) { propuesta = p; actualizarBarra() } }
        }
    }

    /** Calcula las sugerencias en el hilo del diccionario (las teclas nunca esperan) */
    private fun pedir() {
        val d = dic
        val w = palabra.toString()
        if (d == null || w.isEmpty()) { actualizarBarra(); return }
        val t = ++turno
        val tq0 = toquesDe(w)
        hilo.execute {
            if (t != turno) return@execute
            val ant = anterior
            // Con mayúscula en medio de una oración probablemente es un nombre: no se corrige
            val nombre = ant != null && w.first().isUpperCase()
            val p = try { d.proponer(w, ant, nombre, tq0) } catch (_: Throwable) { null }
            principal.post { if (t == turno && palabra.toString() == w) { propuesta = p; actualizarBarra() } }
            // Qué letras pueden seguir: ayuda a acertar la tecla cuando el toque cae justo entre dos
            val sig = try { d.siguientes(w) } catch (_: Throwable) { null }
            principal.post { if (palabra.toString() == w) teclado?.probables = sig?.takeIf { it.isNotEmpty() } }
        }
        // La barra no se vacía mientras se calcula (no parpadea): solo cambia lo escrito
        if (sugerir) teclado?.let { kb ->
            casillas[0] = KeyboardView.Sug(w, 1)
            kb.ponerSugerencias(casillas[0], casillas[1], casillas[2])
        }
    }

    private fun actualizarBarra() {
        val kb = teclado ?: return
        val w = palabra.toString()
        casillas.fill(null)
        val tc = tocada
        if (sugerir && w.isEmpty() && tc != null) {
            // Palabra tocada: la corrección primero, luego las parecidas
            casillas[0] = KeyboardView.Sug(tc, 1)
            val p = propuesta?.takeIf { it.escrito == tc }
            if (p != null) {
                val ops = listOfNotNull(p.correccion) + p.opciones
                ops.getOrNull(0)?.let { casillas[1] = KeyboardView.Sug(it, if (p.correccion != null) 2 else 0) }
                ops.getOrNull(1)?.let { casillas[2] = KeyboardView.Sug(it, 0) }
            }
        } else if (sugerir && w.isEmpty()) {
            // Sin palabra a medias: lo recién copiado primero, luego lo que sueles escribir después
            val may = kb.shift > 0
            val cp = copiadoVigente()
            var k = 0
            if (cp != null) casillas[k++] = KeyboardView.Sug(cp, 3)
            for (s in predicciones) {
                if (k >= 3) break
                casillas[k++] = KeyboardView.Sug(if (may) s.replaceFirstChar { it.uppercaseChar() } else s, 0)
            }
            // Caja vacía: la barra no se queda en blanco, ofrece con qué empezar (como el iPhone)
            if (k < 3 && predicciones.isEmpty() && anterior == null && cajaVacia()) {
                val ini = paraEmpezar()
                if (k == 0) { casillas[0] = KeyboardView.Sug(ini[0], 0); casillas[1] = KeyboardView.Sug(ini[1], 0); casillas[2] = KeyboardView.Sug(ini[2], 0) }
                else { casillas[1] = KeyboardView.Sug(ini[1], 0); casillas[2] = KeyboardView.Sug(ini[0], 0) }
            }
        } else if (sugerir && w.isNotEmpty()) {
            casillas[0] = KeyboardView.Sug(w, 1)
            val p = propuesta?.takeIf { it.escrito == w }
            if (p != null) {
                val corr = p.correccion
                if (corr != null) {
                    casillas[1] = KeyboardView.Sug(corr, if (corregir) 2 else 0)
                    p.opciones.getOrNull(0)?.let { casillas[2] = KeyboardView.Sug(it, 0) }
                } else {
                    p.opciones.getOrNull(0)?.let { casillas[1] = KeyboardView.Sug(it, 0) }
                    p.opciones.getOrNull(1)?.let { casillas[2] = KeyboardView.Sug(it, 0) }
                }
            }
        }
        kb.ponerSugerencias(casillas[0], casillas[1], casillas[2])
    }

    /** La corrección para la palabra que termina (la ya calculada o, si no llegó, al momento) */
    private fun correccionAhora(): String? {
        if (!corregir || palabra.isEmpty()) return null
        val w = palabra.toString()
        // Si la cuenta completa (en el otro hilo) aún no llegó, se hace una rápida aquí mismo:
        // solo un error, sin buscar dos ni completar palabras, para que el espacio nunca se trabe
        val p = propuesta?.takeIf { it.escrito == w } ?: try {
            dic?.proponer(w, anterior, anterior != null && w.first().isUpperCase(), toquesDe(w), rapido = true)
        } catch (_: Throwable) { null }
        p?.correccion?.takeIf { it != w }?.let { return it }
        // Dos mayúsculas al empezar por dejar la mayúscula un instante de más: «HOla» → «Hola»
        if (w.length >= 3 && w[0].isUpperCase() && w[1].isUpperCase() && w.substring(2).all { it.isLowerCase() })
            return w[0] + w.substring(1).lowercase()
        return null
    }

    /** Cambia la palabra que estás escribiendo por [nueva] (sin tocar nada más) */
    private fun reemplazarPalabra(nueva: String) {
        val ic = currentInputConnection ?: return
        val n = palabra.length
        ic.deleteSurroundingText(n, 0)
        ic.commitText(nueva, 1)
        movido(nueva.length - n)
    }

    /** Termina la palabra: la corrige si hace falta. Devuelve la original si la corrigió. */
    private fun terminarPalabra(): String? {
        val corr = correccionAhora()
        val original = palabra.toString()
        if (corr != null) reemplazarPalabra(corr)
        var final = corr ?: original
        if (corregir) final = gramatica(final)
        if (final.isNotEmpty()) registrar(final)
        vaciar(); propuesta = null; turno++
        if (corr != null) { deshacerOriginal = original; deshacerPuesta = corr } else olvidarDeshacer()
        return if (corr != null) original else null
    }

    /**
     * Gramática rápida al terminar una palabra:
     * «a el» → «al», «de el» → «del», y artículos o preposiciones repetidos («el el») se quitan.
     * Devuelve lo que quedó escrito ("" si se quitó la palabra repetida).
     */
    private fun gramatica(w: String): String {
        val ic = currentInputConnection ?: return w
        val lw = w.lowercase()
        val ant = anterior
        // «a El Comercio» se deja: «El» con mayúscula es parte de un nombre
        if (lw == "el" && w[0] == 'e' && (ant == "a" || ant == "de")) {
            val antes = ic.getTextBeforeCursor(ant.length + 3, 0)?.toString() ?: return w
            val esperadoTxt = "$ant $lw"
            if (antes.lowercase().endsWith(esperadoTxt) && antes.length >= esperadoTxt.length) {
                val original = antes.substring(antes.length - esperadoTxt.length)
                val junta = if (ant == "a") "al" else "del"
                val conForma = if (original[0].isUpperCase()) junta.replaceFirstChar { it.uppercaseChar() } else junta
                ic.deleteSurroundingText(esperadoTxt.length, 0)
                ic.commitText(conForma, 1)
                movido(conForma.length - esperadoTxt.length)
                anterior = anterior2; anterior2 = null
                return conForma
            }
        }
        if (lw == ant && lw in REPETIBLES_NO) {
            val antes = ic.getTextBeforeCursor(w.length + 1, 0)?.toString() ?: return w
            if (antes.length == w.length + 1 && antes[0] == ' ') {
                ic.deleteSurroundingText(w.length + 1, 0)
                movido(-(w.length + 1))
                return ""
            }
        }
        return w
    }

    /** Cuenta la palabra terminada y calcula las que pueden seguir */
    // ---------- Con qué empezar (barra con la caja vacía) ----------
    private val inicios by lazy { getSharedPreferences("inicios", MODE_PRIVATE) }

    private fun saludoDeLaHora(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) { in 5..11 -> "Buenos días"; in 12..18 -> "Buenas tardes"; else -> "Buenas noches" }
    }

    /** [izquierda, centro, derecha]: el saludo de la hora al medio y a los lados tus inicios más usados */
    private fun paraEmpezar(): Array<String> {
        val saludo = saludoDeLaHora()
        val tuyas = try {
            (inicios.getString("lista", "") ?: "").split(';').mapNotNull {
                val i = it.lastIndexOf(':'); if (i <= 0) null else it.substring(0, i) to (it.substring(i + 1).toIntOrNull() ?: 0)
            }.filter { it.second >= 3 && !saludo.startsWith(it.first, true) }.sortedByDescending { it.second }.map { it.first }
        } catch (_: Throwable) { emptyList() }
        val izq = tuyas.getOrNull(0) ?: "Hola"
        val der = tuyas.getOrNull(1)?.takeIf { it != izq } ?: if (izq != "Gracias") "Gracias" else "Hola"
        return arrayOf(izq, saludo, der)
    }

    /** Aprende con qué palabra sueles empezar tus mensajes (sin tocar el cerebro, solo un contador) */
    private fun aprenderInicio(w: String) {
        if (w.length < 2 || !w.all { it.isLetter() }) return
        val p = w.lowercase().replaceFirstChar { it.uppercaseChar() }
        hilo.execute {
            try {
                val m = LinkedHashMap<String, Int>()
                (inicios.getString("lista", "") ?: "").split(';').forEach {
                    val i = it.lastIndexOf(':'); if (i > 0) m[it.substring(0, i)] = it.substring(i + 1).toIntOrNull() ?: 0
                }
                m[p] = (m[p] ?: 0) + 1
                val top = m.entries.sortedByDescending { it.value }.take(12)
                inicios.edit().putString("lista", top.joinToString(";") { "${it.key}:${it.value}" }).apply()
            } catch (_: Throwable) { }
        }
    }

    private fun registrar(final: String) {
        val d = dic ?: return
        if (anterior == null && anterior2 == null) final.split(' ').firstOrNull()?.let { aprenderInicio(it) }
        for (w in final.split(' ')) {
            if (w.isEmpty()) continue
            val a2 = anterior2; val a1 = anterior
            // Un nombre con mayúscula en medio de una oración se recuerda (maría → María)
            if (a1 != null && w[0].isUpperCase()) hilo.execute { try { d.aprenderNombre(w) } catch (_: Throwable) { } }
            hilo.execute { try { d.registrar(a2, a1, w) } catch (_: Throwable) { } }
            anterior2 = a1; anterior = w.lowercase()
        }
        pedirPrediccion()
    }

    private fun pedirPrediccion() {
        val d = dic
        val ant = anterior
        val ant2 = anterior2
        predicciones = emptyList()
        if (d == null || ant == null || !sugerir) return
        val t = ++turno
        hilo.execute {
            val r = try { d.predecir(ant2, ant) } catch (_: Throwable) { emptyList() }
            principal.post { if (t == turno && palabra.isEmpty() && anterior == ant) { predicciones = r; actualizarBarra() } }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        dic?.let { d -> hilo.execute { try { d.guardar() } catch (_: Throwable) { } } }
        teclado?.terminarTodo()
        cerrarEmojis()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selIni = newSelStart; selFin = newSelEnd
        // La app vació la caja por su cuenta (WhatsApp, Messenger… envían con su propio botón):
        // si estabas en números o símbolos, vuelve a las letras para el siguiente mensaje
        if (newSelStart == 0 && newSelEnd == 0 && oldSelStart > 0 && esperado != 0) {
            cajaVaciada()
            // Algunas apps vacían la caja un instante después de mover el cursor: se revisa de nuevo
            principal.removeCallbacks(revisarVaciada)
            principal.postDelayed(revisarVaciada, 150L)
        }
        if (newSelStart == newSelEnd && newSelStart == esperado) pendientes = 0
        else if (pendientes > 0) pendientes--
        else {
            // El cursor se movió por otra causa (tocaste el texto): se lee la palabra de ahí
            esperado = newSelStart
            olvidarDeshacer()
            recapturar()
        }
        actualizarMayus()
    }

    // ---------- Mayúscula automática (inicio de oración) ----------
    private fun actualizarMayus() {
        val kb = teclado ?: return
        val ei = info ?: return
        if (!cfg.mayusAuto || kb.distribucion.modo != Distribuciones.LETRAS) { kb.mayusculaAutomatica(false); return }
        val variacion = ei.inputType and InputType.TYPE_MASK_VARIATION
        val clase = ei.inputType and InputType.TYPE_MASK_CLASS
        if (clase != InputType.TYPE_CLASS_TEXT ||
            variacion == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variacion == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variacion == InputType.TYPE_TEXT_VARIATION_URI ||
            variacion == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variacion == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variacion == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) { kb.mayusculaAutomatica(false); return }
        val ic = currentInputConnection ?: return
        var modos = TextUtils.CAP_MODE_SENTENCES
        if (ei.inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) modos = modos or TextUtils.CAP_MODE_WORDS
        if (ei.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) modos = modos or TextUtils.CAP_MODE_CHARACTERS
        var si = try { ic.getCursorCapsMode(modos) != 0 } catch (_: Throwable) { false }
        // «¿Cómo…», «¡Hola…»: después del signo de apertura al empezar la oración también va mayúscula
        if (!si) try {
            val antes = ic.getTextBeforeCursor(40, 0)?.toString()
            if (!antes.isNullOrEmpty() && antes.last() in "¿¡") {
                val previo = antes.dropLast(1).trimEnd(' ')
                si = if (previo.isEmpty()) antes.length < 40 else previo.last() in ".!?:\n"
            }
        } catch (_: Throwable) { }
        kb.mayusculaAutomatica(si)
    }

    private fun etiquetaIntro(ei: EditorInfo): String? {
        if (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "ir"
            EditorInfo.IME_ACTION_SEARCH -> "buscar"
            EditorInfo.IME_ACTION_SEND -> "enviar"
            EditorInfo.IME_ACTION_NEXT -> "siguiente"
            EditorInfo.IME_ACTION_DONE -> "OK"
            else -> null
        }
    }

    // ---------- Teclas ----------
    /** «hola ,» → «hola,»: en español el signo va pegado a la palabra (como hace el iPhone) */
    private fun pegarSigno(s: String) {
        if (!corregir || palabra.isNotEmpty() || s.length != 1 || s[0] !in ".,;:!?)") return
        val ic = currentInputConnection ?: return
        if (selIni != selFin) return
        val antes = ic.getTextBeforeCursor(2, 0) ?: return
        if (antes.length == 2 && antes[1] == ' ' && (antes[0].isLetterOrDigit() || antes[0] in ".,;:!?)»\"”")) {
            ic.deleteSurroundingText(1, 0)
            movido(-1)
        }
    }

    override fun texto(s: String, costos: FloatArray?) {
        val ic = currentInputConnection ?: return
        olvidarDeshacer()
        copiado = null      // al escribir, lo copiado deja la barra (sigue en el portapapeles)
        tocada = null
        // Recién se terminó una palabra con espacio y ahora va un signo: se pega a la palabra
        if (s.length == 1 && s[0] in ".,;:!?)" && palabra.isEmpty() && SystemClock.uptimeMillis() - ultimoEspacioMs < 4000L) {
            ic.beginBatchEdit(); pegarSigno(s); ic.endBatchEdit()
        }
        if (s.isNotEmpty() && s.all { it.isLetter() }) {
            ic.commitText(s, 1); movido(s.length)
            if (seguir && !enMedio) {
                if (toques.size != palabra.length) { toques.clear(); repeat(palabra.length) { toques.add(null) } }
                palabra.append(s)
                if (s.length == 1) toques.add(costos) else repeat(s.length) { toques.add(null) }
                pedir()
            }
            return
        }
        if ((s == "?" || s == "!") && corregir && cfg.signosApertura) {
            ic.beginBatchEdit()
            if (palabra.isNotEmpty()) terminarPalabra()
            abrirSigno(if (s == "?") '¿' else '¡')
            ic.commitText(s, 1); movido(s.length)
            ic.endBatchEdit()
            vaciar(); propuesta = null; olvidarDeshacer()
            anterior = null; anterior2 = null; predicciones = emptyList(); turno++
            actualizarBarra()
            return
        }
        // Un signo termina la palabra: se corrige antes de ponerlo (como el iPhone)
        val signo = s.length == 1 && s[0] in ".,;:!?)»\"”"
        ic.beginBatchEdit()
        if (signo && palabra.isNotEmpty()) terminarPalabra()
        ic.commitText(s, 1); movido(s.length)
        ic.endBatchEdit()
        vaciar(); propuesta = null; olvidarDeshacer()
        anterior = null; anterior2 = null; predicciones = emptyList(); turno++
        actualizarBarra()
    }

    /** Al cerrar con ? o !, pone ¿ o ¡ al comienzo de la oración si falta (como se escribe en español) */
    private fun abrirSigno(abre: Char) {
        val ic = currentInputConnection ?: return
        val antes = ic.getTextBeforeCursor(300, 0)?.toString() ?: return
        var i = antes.length
        while (i > 0 && antes[i - 1] !in ".!?¿¡\n") i--
        if (i == 0 && antes.length >= 300) return            // la oración es más larga de lo que se ve
        if (i > 0 && antes[i - 1] == abre) return             // ya está abierto
        val oracion = antes.substring(i)
        if (oracion.contains(abre) || oracion.none { it.isLetter() }) return
        val lead = oracion.takeWhile { it.isWhitespace() }
        val resto = oracion.substring(lead.length)
        ic.deleteSurroundingText(oracion.length, 0)
        ic.commitText(lead + abre + resto, 1)
        movido(1)
    }

    override fun ajustes() {
        try {
            startActivity(android.content.Intent(this, SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            requestHideSelf(0)
        } catch (_: Throwable) { }
    }

    override fun sugerencia(i: Int) {
        val sg = casillas.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        if (sg.tipo == 3) { pegar(sg.texto); return }
        val tc = tocada
        if (palabra.isEmpty() && tc != null) {
            // Cambia la palabra tocada entera (sin agregar espacio)
            if (sg.tipo != 1) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tocadaAntes, tocadaDespues)
                ic.commitText(sg.texto, 1)
                ic.endBatchEdit()
                esperado = -1; pendientes = 0
            } else dic?.let { d -> hilo.execute { d.aprender(tc) } }
            tocada = null; propuesta = null; turno++
            actualizarBarra()
            return
        }
        if (palabra.isEmpty()) {
            // Palabra que suele seguir: se pone con su espacio
            ic.commitText(sg.texto + " ", 1); movido(sg.texto.length + 1)
            olvidarDeshacer()
            ultimoEspacioMs = SystemClock.uptimeMillis()
            registrar(sg.texto)
            return
        }
        ic.beginBatchEdit()
        if (sg.tipo == 1) {
            // Lo escrito tal cual: el teclado lo aprende y no lo vuelve a corregir
            val w = palabra.toString()
            dic?.let { d -> hilo.execute { d.aprender(w) } }
        } else reemplazarPalabra(sg.texto)
        ic.commitText(" ", 1); movido(1)
        ic.endBatchEdit()
        val elegida = if (sg.tipo == 1) palabra.toString() else sg.texto
        vaciar(); propuesta = null; olvidarDeshacer(); turno++
        registrar(elegida)
        ultimoEspacioMs = SystemClock.uptimeMillis()
        actualizarBarra()
    }

    override fun espacio() {
        val ic = currentInputConnection ?: return
        val ahora = SystemClock.uptimeMillis()
        if (palabra.isNotEmpty()) {
            ic.beginBatchEdit()
            val original = terminarPalabra()
            ic.commitText(" ", 1); movido(1)
            ic.endBatchEdit()
            if (original != null) deshacerPos = esperado
            ultimoEspacioMs = ahora
            actualizarBarra()
            return
        }
        olvidarDeshacer()
        // Atajo del iPhone: dos espacios seguidos ponen punto y espacio
        if (cfg.atajoPunto && ahora - ultimoEspacioMs < 700L && selIni == selFin) {
            val antes = ic.getTextBeforeCursor(2, 0)
            if (antes != null && antes.length == 2 && antes[1] == ' ' && antes[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                movido(1)
                ultimoEspacioMs = 0L
                return
            }
        }
        ic.commitText(" ", 1); movido(1)
        ultimoEspacioMs = ahora
    }

    override fun borrar(entera: Boolean) {
        val ic = currentInputConnection ?: return
        ultimoEspacioMs = 0L
        // Borrar justo después de una corrección automática devuelve lo que escribiste
        val orig = deshacerOriginal; val puesta = deshacerPuesta
        if (!entera && orig != null && puesta != null && selIni == selFin && selIni == deshacerPos) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(puesta.length + 1, 0)
            ic.commitText(orig, 1)
            ic.endBatchEdit()
            movido(orig.length - puesta.length - 1)
            olvidarDeshacer()
            dic?.let { d -> hilo.execute { d.aprender(orig) } }
            vaciar(); propuesta = null; turno++
            actualizarBarra()
            return
        }
        olvidarDeshacer()
        anterior = null; anterior2 = null; predicciones = emptyList()
        if (selIni != selFin && selIni >= 0) {
            ic.commitText("", 1); movido(0)
            vaciar(); propuesta = null; actualizarBarra()
            return
        }
        val antes = ic.getTextBeforeCursor(if (entera) 60 else 24, 0)
        if (antes.isNullOrEmpty()) {
            if (antes == null) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            esperado = -1
            return
        }
        val n = if (entera) {
            var i = antes.length
            while (i > 0 && antes[i - 1].isWhitespace()) i--
            while (i > 0 && !antes[i - 1].isWhitespace()) i--
            (antes.length - i).coerceAtLeast(1)
        } else {
            // Un carácter completo (un emoji con todas sus partes cuenta como uno)
            val bi = letrasBI
            bi.setText(antes.toString())
            val ini = bi.preceding(antes.length)
            if (ini == BreakIterator.DONE) 1 else antes.length - ini
        }
        ic.deleteSurroundingText(n, 0)
        movido(-n)
        if (!seguir) return
        if (palabra.isNotEmpty() && !entera) {
            palabra.setLength(maxOf(0, palabra.length - n))
            while (toques.size > palabra.length) toques.removeAt(toques.size - 1)
            if (palabra.isNotEmpty()) pedir() else { propuesta = null; actualizarBarra() }
        } else {
            // Al volver a una palabra ya escrita, sus sugerencias reaparecen (como el iPhone)
            val resto = antes.subSequence(0, antes.length - n)
            vaciar(); propuesta = null
            val despues = ic.getTextAfterCursor(1, 0)
            if (despues.isNullOrEmpty() || !despues[0].isLetter()) {
                var i = resto.length
                while (i > 0 && resto[i - 1].isLetter()) i--
                if (i > 0 || resto.length < 24) palabra.append(resto, i, resto.length)
            }
            if (palabra.isNotEmpty()) pedir() else actualizarBarra()
        }
    }

    override fun intro() {
        val ei = info
        val ic = currentInputConnection ?: return
        ultimoEspacioMs = 0L
        if (palabra.isNotEmpty()) { ic.beginBatchEdit(); terminarPalabra(); ic.endBatchEdit() }
        anterior = null; anterior2 = null; predicciones = emptyList(); turno++
        olvidarDeshacer(); actualizarBarra()
        esperado = -1; pendientes = 0
        val accion = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val sinAccion = ei == null || ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            accion == EditorInfo.IME_ACTION_NONE || accion == EditorInfo.IME_ACTION_UNSPECIFIED
        if (sinAccion) {
            if (ei != null && (ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) ic.commitText("\n", 1)
            else sendKeyChar('\n')
        } else ic.performEditorAction(accion)
        volverALetras()
    }

    /**
     * Después de enviar (o de un salto de línea) estando en números o símbolos: vuelve a las letras
     * con la mayúscula del inicio de oración, listo para el siguiente mensaje (como el iPhone)
     */
    private val revisarVaciada = Runnable { if (cajaVaciada()) actualizarMayus() }

    /** La caja de texto no tiene nada escrito */
    private fun cajaVacia(): Boolean {
        val ic = currentInputConnection ?: return false
        return try {
            ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()
        } catch (_: Throwable) { false }
    }

    /** Si la app vació la caja estando en números o símbolos, vuelve a las letras. Devuelve si cambió */
    private fun cajaVaciada(): Boolean {
        val kb = teclado ?: return false
        val m = kb.distribucion.modo
        if (m != Distribuciones.SIMBOLOS && m != Distribuciones.SIMBOLOS2) return false
        if (!cajaVacia()) return false
        kb.ponerModo(Distribuciones.LETRAS)
        return true
    }

    private fun volverALetras() {
        val kb = teclado ?: return
        val m = kb.distribucion.modo
        if (m == Distribuciones.SIMBOLOS || m == Distribuciones.SIMBOLOS2) kb.ponerModo(Distribuciones.LETRAS)
        actualizarMayus()
        // La app vacía la caja un instante después de enviar: se revisa otra vez la mayúscula
        principal.postDelayed({ actualizarMayus() }, 120L)
    }

    override fun cursor(dx: Int, dy: Int) {
        val ic = currentInputConnection ?: return
        esperado = -1; pendientes = 0; olvidarDeshacer()
        ic.beginBatchEdit()
        repeat(kotlin.math.abs(dx)) { sendDownUpKeyEvents(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(kotlin.math.abs(dy)) { sendDownUpKeyEvents(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        ic.endBatchEdit()
    }

    override fun respuesta(t: Respuesta.Tipo) = respuesta.tocar(t, panelClip ?: panel ?: teclado)

    override fun guardarAjuste(s: String) { prefs.edit().putString(Ajustes.AJUSTE, s).apply() }

    // ---------- Emojis ----------
    override fun emojis() {
        val r = raiz ?: return
        val kb = teclado ?: return
        kb.terminarTodo()
        val p = panel ?: PanelEmoji(this).also {
            it.oyente = this
            it.abajoSistema = kb.abajoSistema
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kb.altoVisible())
            lp.gravity = android.view.Gravity.BOTTOM
            r.addView(it, lp)
            panel = it
        }
        p.layoutParams = (p.layoutParams as FrameLayout.LayoutParams).also { it.height = kb.altoVisible() }
        p.conPortapapeles = cfg.portapapeles
        p.abrir()
        p.visibility = View.VISIBLE
        kb.visibility = View.INVISIBLE
    }

    private fun cerrarEmojis() {
        cerrarPortapapeles()
        val p = panel ?: return
        // El panel se suelta al cerrarlo (no ocupa memoria mientras no se usa)
        raiz?.removeView(p)
        panel = null
        PanelEmoji.soltarCache()
        teclado?.visibility = View.VISIBLE
    }

    override fun emoji(e: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(e, 1); movido(e.length)
        ultimoEspacioMs = 0L
        anterior = null; anterior2 = null; predicciones = emptyList()
        vaciar(); propuesta = null; olvidarDeshacer(); actualizarBarra()
    }

    // ---------- Portapapeles ----------
    override fun portapapeles() {
        val r = raiz ?: return
        val kb = teclado ?: return
        val pc = panelClip ?: PanelPortapapeles(this).also {
            it.oyente = this
            it.abajoSistema = kb.abajoSistema
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kb.altoVisible())
            lp.gravity = android.view.Gravity.BOTTOM
            r.addView(it, lp)
            panelClip = it
        }
        pc.layoutParams = (pc.layoutParams as FrameLayout.LayoutParams).also { it.height = kb.altoVisible() }
        pc.abrir(clip.recortes().map { it.texto })
        pc.visibility = View.VISIBLE
        panel?.visibility = View.INVISIBLE
        kb.visibility = View.INVISIBLE
    }

    private fun cerrarPortapapeles() {
        val pc = panelClip ?: return
        raiz?.removeView(pc)
        panelClip = null
    }

    override fun pegar(t: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (palabra.isNotEmpty() && seguir) terminarPalabra()
        ic.commitText(t, 1); movido(t.length)
        ic.endBatchEdit()
        copiado = null
        ultimoEspacioMs = 0L
        anterior = null; anterior2 = null; predicciones = emptyList()
        vaciar(); propuesta = null; olvidarDeshacer(); turno++
        actualizarBarra()
    }

    override fun quitarRecorte(t: String) {
        clip.quitar(t)
        if (copiado == t) { copiado = null; actualizarBarra() }
    }

    override fun borrarPortapapeles() {
        clip.borrarTodo()
        copiado = null; actualizarBarra()
    }

    override fun abc() {
        cerrarEmojis()
        teclado?.ponerModo(Distribuciones.LETRAS)
        actualizarMayus()
    }
}
