package com.tecladoios

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout

class TecladoService : InputMethodService(),
    KeyboardView.Acciones, EmojiPanel.Acciones, SuggestionBar.Acciones {

    private var barra: SuggestionBar? = null
    private var teclado: KeyboardView? = null
    private var emojis: EmojiPanel? = null
    private var tema: Tema? = null
    private lateinit var diccionario: Diccionario
    private var vibrador: Vibrator? = null
    private var audio: AudioManager? = null

    private var vibrar = true
    private var sonido = false
    private var sugerenciasActivas = true
    private var autocorreccionActiva = true
    private var mayusPermitida = true
    private var puntoDoble = true
    private var atajosActivos = true
    private var atajos: Map<String, String> = emptyMap()

    private var ultimaCorreccion: Pair<String, String>? = null
    private var ultimoEspacio = 0L
    /** true justo después de elegir una sugerencia (el espacio lo puso el teclado) */
    private var espacioAutomatico = false

    // La barra aparece al empezar a escribir y se queda hasta cerrar el teclado o cambiar de app
    private var barraActiva = false
    private var ultimaApp: String? = null

    override fun onCreate() {
        super.onCreate()
        diccionario = Diccionario(this)
        vibrador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onCreateInputView(): View = construir()

    private fun dp(v: Float) = (v * Medidas.escala(this)).toInt()

    private fun puedeDesenfocar(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !Ajustes.desenfoque(this)) return false
        val wm = getSystemService(WindowManager::class.java) ?: return false
        if (!wm.isCrossWindowBlurEnabled) return false
        val w = window?.window ?: return false
        return w.attributes.height == WindowManager.LayoutParams.WRAP_CONTENT
    }

    private fun construir(): View {
        val t = Tema.desde(this, puedeDesenfocar())
        tema = t

        val b = SuggestionBar(this, t, this).apply { visibility = View.GONE }
        val k = KeyboardView(this, t, this)
        val e = EmojiPanel(this, t, this).apply { visibility = View.GONE }
        barra = b; teclado = k; emojis = e

        val zona = FrameLayout(this)
        zona.addView(k, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        zona.addView(e, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, k.alto()))

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.fondo)
            addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)))
            addView(zona, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        raiz.setOnApplyWindowInsetsListener { v, insets ->
            val abajo = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, abajo)
            insets
        }
        KeyboardCorners.apply(raiz, { radio -> aplicarVidrio(radio) })
        return raiz
    }

    private fun aplicarVidrio(radio: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val w = window?.window ?: return
        val blur = tema?.desenfoque ?: 0
        if (blur > 0) {
            // Desenfoque rectangular: abajo lo recortan las propias esquinas de la pantalla
            w.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            w.setBackgroundBlurRadius(blur)
        } else {
            w.setBackgroundBlurRadius(0)
        }
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
        if (Tema.desde(this, puedeDesenfocar()) != tema) setInputView(construir())
        configurarVentana()

        vibrar = Ajustes.vibrar(this)
        sonido = Ajustes.sonido(this)
        puntoDoble = Ajustes.puntoDoble(this)
        atajosActivos = Ajustes.atajosActivos(this)
        atajos = Ajustes.atajos(this).associate { it.first.lowercase() to it.second }
        diccionario.recargarSiCambio()

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
        autocorreccionActiva = Ajustes.autocorregir(this) && sugerenciasActivas
        mayusPermitida = Ajustes.mayusculas(this) && esTexto && !esClave && !esCorreoOWeb

        val numerico = clase == InputType.TYPE_CLASS_NUMBER ||
                clase == InputType.TYPE_CLASS_PHONE ||
                clase == InputType.TYPE_CLASS_DATETIME

        teclado?.apply {
            vistaPrevia = Ajustes.vistaPrevia(this@TecladoService)
            bloqMayusPermitido = Ajustes.bloqMayus(this@TecladoService)
            soloNumeros = numerico
            modo = if (numerico) Modo.NUMEROS else Modo.LETRAS
            val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
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
        if (info.packageName != ultimaApp) barraActiva = false
        ultimaApp = info.packageName
        ultimaCorreccion = null
        espacioAutomatico = false
        volverABC()
        actualizar()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        barraActiva = false
        barra?.visible(false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        teclado?.ocultarPreview()
        diccionario.guardar()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        diccionario.guardar()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        actualizar()
    }

    // ---------- Estado ----------

    /** (palabra anterior, palabra que se está escribiendo) */
    private fun ultimasPalabras(): Pair<String, String> {
        val antes = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: return "" to ""
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

    private fun inicioDeOracion(): Boolean =
        (currentInputConnection?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) ?: 0) != 0

    private fun buscarAtajo(p: String): String? =
        if (atajosActivos && sugerenciasActivas) atajos[p.lowercase()] else null

    private fun actualizar() {
        actualizarMayus()
        actualizarBarra()
    }

    private fun actualizarMayus() {
        val k = teclado ?: return
        if (k.modo != Modo.LETRAS) return
        if (!mayusPermitida) { k.mayusAuto(false); return }
        k.mayusAuto(inicioDeOracion())
    }

    private fun actualizarBarra() {
        val b = barra ?: return
        val enEmojis = emojis?.visibility == View.VISIBLE
        if (!sugerenciasActivas) {
            b.mostrar(emptyList()); b.visible(false); return
        }
        val (previa, p) = ultimasPalabras()
        if (p.isNotEmpty() && !enEmojis) barraActiva = true
        b.visible(barraActiva && !enEmojis)
        if (enEmojis) { b.mostrar(emptyList()); return }

        if (p.isEmpty()) {
            // Predicción de la siguiente palabra, como en iPhone
            val inicio = inicioDeOracion()
            val sig = diccionario.siguientes(if (inicio) "" else previa, 3)
                .map { if (inicio) it.replaceFirstChar { c -> c.uppercase() } else it }
            b.mostrar(listOf(
                sig.getOrNull(1)?.let { Sugerencia(it) },
                sig.getOrNull(0)?.let { Sugerencia(it) },
                sig.getOrNull(2)?.let { Sugerencia(it) },
            ))
            return
        }

        val corr = buscarAtajo(p) ?: if (autocorreccionActiva) diccionario.corregir(p) else null
        val otras = diccionario.sugerir(p, 3).filter { it != corr && it != p }
        val centro = corr?.let { Sugerencia(it, destacada = true) } ?: otras.getOrNull(0)?.let { Sugerencia(it) }
        val derecha = (if (corr != null) otras.getOrNull(0) else otras.getOrNull(1))?.let { Sugerencia(it) }
        b.mostrar(listOf(Sugerencia(p, literal = true), centro, derecha))
    }

    /** Cierra la palabra actual (corrigiéndola o reemplazando el atajo) y agrega el sufijo. */
    private fun cerrarPalabra(sufijo: String) {
        val ic = currentInputConnection ?: return
        val (previa, palabra) = ultimasPalabras()
        espacioAutomatico = false
        if (palabra.isNotEmpty()) {
            val atajo = buscarAtajo(palabra)
            val corr = atajo ?: if (autocorreccionActiva) diccionario.corregir(palabra) else null
            if (corr != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(palabra.length, 0)
                ic.commitText(corr + sufijo, 1)
                ic.endBatchEdit()
                ultimaCorreccion = Pair(palabra, corr + sufijo)
                if (atajo == null) {
                    diccionario.aprender(corr)
                    diccionario.aprenderPar(previa, corr)
                }
                return
            }
            if (sugerenciasActivas) {
                diccionario.aprender(palabra)
                diccionario.aprenderPar(previa, palabra)
            }
        }
        ic.commitText(sufijo, 1)
        ultimaCorreccion = null
    }

    // ---------- Acciones de las teclas ----------

    override fun texto(s: String) {
        val ic = currentInputConnection ?: return
        if (s.length == 1 && s[0] in ".,;:!?") {
            // Después de elegir una sugerencia, la puntuación se pega a la palabra: "hola, "
            if (espacioAutomatico && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$s ", 1)
                ic.endBatchEdit()
                espacioAutomatico = false
                ultimaCorreccion = null
                return
            }
            cerrarPalabra(s)
            return
        }
        espacioAutomatico = false
        ultimaCorreccion = null
        ic.commitText(s, 1)
    }

    override fun espacio() {
        val ic = currentInputConnection ?: return
        val ahora = SystemClock.uptimeMillis()
        val antes = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (puntoDoble && antes.length == 2 && antes[1] == ' ' && antes[0].isLetterOrDigit() &&
            ahora - ultimoEspacio < 450
        ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            ultimoEspacio = 0
            ultimaCorreccion = null
            espacioAutomatico = false
            return
        }
        ultimoEspacio = ahora
        cerrarPalabra(" ")
    }

    override fun borrar() {
        val ic = currentInputConnection ?: return
        espacioAutomatico = false
        val uc = ultimaCorreccion
        ultimaCorreccion = null
        if (uc != null) {
            // Deshacer la autocorrección como en iPhone
            val antes = ic.getTextBeforeCursor(uc.second.length, 0)?.toString()
            if (antes == uc.second) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(uc.second.length, 0)
                ic.commitText(uc.first, 1)
                ic.endBatchEdit()
                diccionario.aprender(uc.first, 2)
                return
            }
        }
        val seleccion = ic.getSelectedText(0)
        if (!seleccion.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun borrarPalabra() {
        val ic = currentInputConnection ?: return
        espacioAutomatico = false
        ultimaCorreccion = null
        val antes = ic.getTextBeforeCursor(60, 0)?.toString() ?: return
        if (antes.isEmpty()) return
        var i = antes.length
        while (i > 0 && antes[i - 1].isWhitespace()) i--
        while (i > 0 && antes[i - 1].isLetterOrDigit()) i--
        val n = antes.length - i
        if (n <= 1) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        else ic.deleteSurroundingText(n, 0)
    }

    override fun moverCursor(pasos: Int) {
        espacioAutomatico = false
        ultimaCorreccion = null
        val tecla = if (pasos > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(Math.abs(pasos)) { sendDownUpKeyEvents(tecla) }
    }

    override fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        ultimaCorreccion = null
        espacioAutomatico = false
        val accion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val sinAccion = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!sinAccion && accion != EditorInfo.IME_ACTION_NONE && accion != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(accion)
            return
        }
        val multilinea = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (multilinea) cerrarPalabra("\n") else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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
        espacioAutomatico = false
        currentInputConnection?.commitText(s, 1)
    }

    override fun elegir(s: Sugerencia) {
        val ic = currentInputConnection ?: return
        val (previa, palabra) = ultimasPalabras()
        ic.beginBatchEdit()
        if (palabra.isNotEmpty()) ic.deleteSurroundingText(palabra.length, 0)
        ic.commitText(s.texto + " ", 1)
        ic.endBatchEdit()
        diccionario.aprender(s.texto, if (s.literal) 2 else 1)
        diccionario.aprenderPar(previa, s.texto)
        ultimaCorreccion = null
        espacioAutomatico = true
        retro(Tipo.LETRA)
    }

    override fun retro(tipo: Tipo) {
        if (vibrar) {
            try {
                vibrador?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } catch (_: Exception) { }
        }
        if (sonido) {
            val efecto = when (tipo) {
                Tipo.BORRAR -> AudioManager.FX_KEYPRESS_DELETE
                Tipo.ESPACIO -> AudioManager.FX_KEYPRESS_SPACEBAR
                Tipo.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(efecto, -1f)
        }
    }
}
