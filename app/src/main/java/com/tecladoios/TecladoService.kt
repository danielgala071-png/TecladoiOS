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
 * El teclado: letras, números y símbolos, y emojis. Como el nativo del iPhone, sin barra de
 * sugerencias, sin predicción ni autocorrección y sin aprender nada.
 */
class TecladoService : InputMethodService(), KeyboardView.Oyente, PanelEmoji.Oyente,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var cfg: Config
    private lateinit var respuesta: Respuesta

    private var raiz: FrameLayout? = null
    private var teclado: KeyboardView? = null
    private var panel: PanelEmoji? = null

    private var info: EditorInfo? = null
    private var ultimoEspacioMs = 0L
    private var selIni = 0; private var selFin = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Ajustes.prefs(this)
        cfg = Config(prefs)
        respuesta = Respuesta(this).also { it.config = cfg }
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        respuesta.soltar()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, clave: String?) {
        cfg = Config(prefs)
        respuesta.config = cfg
        teclado?.config = cfg
        teclado?.invalidate()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        try { window?.window?.setWindowAnimations(R.style.AnimacionTeclado) } catch (_: Throwable) { }
        try { window?.window?.navigationBarColor = Color.BLACK } catch (_: Throwable) { }
        val kb = KeyboardView(this).apply { config = cfg; oyente = this@TecladoService }
        val r = FrameLayout(this).apply {
            addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnApplyWindowInsetsListener { _, ins ->
                val abajo = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                kb.abajoSistema = abajo
                panel?.abajoSistema = abajo
                ins
            }
        }
        raiz = r; teclado = kb; panel = null
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
        when (ei.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> kb.ponerModo(Distribuciones.NUMEROS,
                ei.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0)
            InputType.TYPE_CLASS_PHONE -> kb.ponerModo(Distribuciones.TELEFONO)
            InputType.TYPE_CLASS_DATETIME -> kb.ponerModo(Distribuciones.NUMEROS)
            else -> if (!restarting || kb.distribucion.numerico) kb.ponerModo(Distribuciones.LETRAS)
        }
        kb.etiquetaIntro = etiquetaIntro(ei)
        actualizarMayus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        teclado?.terminarTodo()
        cerrarEmojis()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selIni = newSelStart; selFin = newSelEnd
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
        val si = try { ic.getCursorCapsMode(modos) != 0 } catch (_: Throwable) { false }
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
    override fun texto(s: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(s, 1)
    }

    override fun espacio() {
        val ic = currentInputConnection ?: return
        val ahora = SystemClock.uptimeMillis()
        // Atajo del iPhone: dos espacios seguidos ponen punto y espacio
        if (cfg.atajoPunto && ahora - ultimoEspacioMs < 700L && selIni == selFin) {
            val antes = ic.getTextBeforeCursor(2, 0)
            if (antes != null && antes.length == 2 && antes[1] == ' ' && antes[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                ultimoEspacioMs = 0L
                return
            }
        }
        ic.commitText(" ", 1)
        ultimoEspacioMs = ahora
    }

    override fun borrar(palabra: Boolean) {
        val ic = currentInputConnection ?: return
        ultimoEspacioMs = 0L
        if (selIni != selFin && selIni >= 0) { ic.commitText("", 1); return }
        val antes = ic.getTextBeforeCursor(if (palabra) 60 else 24, 0)
        if (antes.isNullOrEmpty()) {
            if (antes == null) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val n = if (palabra) {
            var i = antes.length
            while (i > 0 && antes[i - 1].isWhitespace()) i--
            while (i > 0 && !antes[i - 1].isWhitespace()) i--
            (antes.length - i).coerceAtLeast(1)
        } else {
            // Un carácter completo (un emoji con todas sus partes cuenta como uno)
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(antes.toString())
            val ini = bi.preceding(antes.length)
            if (ini == BreakIterator.DONE) 1 else antes.length - ini
        }
        ic.deleteSurroundingText(n, 0)
    }

    override fun intro() {
        val ei = info
        val ic = currentInputConnection ?: return
        ultimoEspacioMs = 0L
        val accion = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val sinAccion = ei == null || ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            accion == EditorInfo.IME_ACTION_NONE || accion == EditorInfo.IME_ACTION_UNSPECIFIED
        if (sinAccion) {
            if (ei != null && (ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) ic.commitText("\n", 1)
            else sendKeyChar('\n')
        } else ic.performEditorAction(accion)
    }

    override fun cursor(dx: Int, dy: Int) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        repeat(kotlin.math.abs(dx)) { sendDownUpKeyEvents(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(kotlin.math.abs(dy)) { sendDownUpKeyEvents(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        ic.endBatchEdit()
    }

    override fun respuesta(t: Respuesta.Tipo) = respuesta.tocar(t)

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
        p.abrir()
        p.visibility = View.VISIBLE
        kb.visibility = View.INVISIBLE
    }

    private fun cerrarEmojis() {
        val p = panel ?: return
        // El panel se suelta al cerrarlo (no ocupa memoria mientras no se usa)
        raiz?.removeView(p)
        panel = null
        teclado?.visibility = View.VISIBLE
    }

    override fun emoji(e: String) { currentInputConnection?.commitText(e, 1); ultimoEspacioMs = 0L }

    override fun abc() {
        cerrarEmojis()
        teclado?.ponerModo(Distribuciones.LETRAS)
        actualizarMayus()
    }
}
