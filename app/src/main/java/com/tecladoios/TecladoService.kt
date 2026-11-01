package com.tecladoios

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

class TecladoService : InputMethodService(),
    KeyboardView.Acciones, EmojiPanel.Acciones, SuggestionBar.Acciones {

    private var barra: SuggestionBar? = null
    private var teclado: KeyboardView? = null
    private var emojis: EmojiPanel? = null
    private var tema: Tema? = null
    private var firmaTamano = ""

    // Color de acento de cada app (sale de su ícono), para combinar el teclado con la app
    private val coloresApps = HashMap<String, Int?>()
    private var appActual: String? = null
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

    private var toqueFino: Boolean? = null
    private var ultimaCorreccion: Pair<String, String>? = null
    private var ultimoEspacio = 0L
    /** (palabra anterior, palabra, texto que quedó escrito) de lo último que se aprendió */
    private var ultimaAprendida: Triple<String, String, String>? = null
    /** true justo después de elegir una sugerencia (el espacio lo puso el teclado) */
    private var espacioAutomatico = false

    // La barra aparece al empezar a escribir y se queda hasta cerrar el teclado o cambiar de app
    private var barraActiva = false
    private var ultimaApp: String? = null

    // Dictado por voz (botón de micrófono)
    private var reconocedor: SpeechRecognizer? = null
    private var dictando = false

    override fun onCreate() {
        super.onCreate()
        Fuentes.iniciar(this)
        Ajustes.migrarDiseno(this)
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

    private fun firma() = "${Ajustes.altura(this)}-${Ajustes.letra(this)}-${Medidas.horizontal(this)}"

    private fun construir(): View {
        val t = Tema.desde(this, puedeDesenfocar(), colorDeApp(appActual))
        tema = t
        firmaTamano = firma()

        val b = SuggestionBar(this, t, this).apply { visibility = View.GONE }
        val k = KeyboardView(this, t, this)
        val e = EmojiPanel(this, t, this).apply { visibility = View.GONE }
        barra = b; teclado = k; emojis = e

        val zona = FrameLayout(this)
        zona.addView(k, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        zona.addView(e, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, k.alto()))

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Panel con un filo de luz arriba, como el teclado del iPhone
            background = GradientDrawable().apply {
                // Liquid Glass de color: el tono de la app se difumina de arriba hacia abajo
                if (t.fondoArriba != 0) {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(t.fondoArriba, t.fondo, t.fondo)
                } else setColor(t.fondo)
                val r = 30f * Medidas.escala(this@TecladoService)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(maxOf(1, (resources.displayMetrics.density * 0.6f).toInt()),
                    if (t.oscuro) 0x26FFFFFF else 0x14000000)
            }
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
        appActual = info.packageName
        if (Tema.desde(this, puedeDesenfocar(), colorDeApp(appActual)) != tema || firma() != firmaTamano) {
            setInputView(construir())
        }
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
        if (dictando) detenerDictado()
        teclado?.ocultarPreview()
        diccionario.guardar()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        reconocedor?.destroy()
        reconocedor = null
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

    /** Color principal del ícono de la app (verde en WhatsApp, rosado en Instagram, etc.) */
    private fun colorDeApp(paquete: String?): Int? {
        if (paquete == null || paquete == packageName || !Ajustes.adaptar(this)) return null
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

    /** Lo escrito desde el último espacio (para atajos con números o símbolos) */
    private fun tokenActual(): String {
        val antes = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString() ?: return ""
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
        val token = tokenActual()
        if ((p.isNotEmpty() || token.isNotEmpty()) && !enEmojis) barraActiva = true
        b.visible(barraActiva && !enEmojis)
        if (enEmojis) { b.mostrar(emptyList()); return }

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

        if (p.isEmpty()) {
            // Predicción de la siguiente palabra, como en iPhone
            val inicio = inicioDeOracion()
            val sig = diccionario.siguientes(if (inicio) "" else previa, 3)
                .map { if (inicio) it.replaceFirstChar { c -> c.uppercase() } else it }
            val uc = ultimaCorreccion
            val deshacer = if (uc != null &&
                currentInputConnection?.getTextBeforeCursor(uc.second.length, 0)?.toString() == uc.second
            ) Sugerencia(uc.first.trim(), literal = true, revertir = true) else null
            b.mostrar(listOf(
                deshacer ?: sig.getOrNull(1)?.let { Sugerencia(it) },
                sig.getOrNull(0)?.let { Sugerencia(it) },
                (if (deshacer != null) sig.getOrNull(1) else sig.getOrNull(2))?.let { Sugerencia(it) },
            ))
            return
        }

        val corr = if (autocorreccionActiva) diccionario.corregir(p, previa) else null
        val otras = diccionario.sugerir(p, 4, previa).filter { it != corr && it != p }
        val centro = corr?.let { Sugerencia(it, destacada = true) } ?: otras.getOrNull(0)?.let { Sugerencia(it) }
        val derecha = (if (corr != null) otras.getOrNull(0) else otras.getOrNull(1))?.let { Sugerencia(it) }
        b.mostrar(listOf(Sugerencia(p, literal = true), centro, derecha))
    }

    /**
     * Cierra la palabra actual y agrega el sufijo (espacio, signo o salto de línea):
     * 1) sustitución de texto, 2) autocorrección, 3) tildes según la palabra anterior.
     */
    private fun cerrarPalabra(sufijo: String) {
        val ic = currentInputConnection ?: return
        espacioAutomatico = false
        ultimaAprendida = null

        // 1) Sustitución de texto
        val token = tokenActual()
        val frase = buscarAtajo(token)
        if (frase != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(token.length, 0)
            ic.commitText(frase + sufijo, 1)
            ic.endBatchEdit()
            ultimaCorreccion = Pair(token, frase + sufijo)
            return
        }

        val antes = ic.getTextBeforeCursor(80, 0)?.toString() ?: ""
        var i = antes.length
        while (i > 0 && antes[i - 1].isLetter()) i--
        val palabra = antes.substring(i)
        var j = i
        while (j > 0 && antes[j - 1] == ' ') j--
        var k = j
        while (k > 0 && antes[k - 1].isLetter()) k--
        val previa = if (j < i && k < j) antes.substring(k, j) else ""
        val espacios = antes.substring(j, i)

        if (palabra.isEmpty()) {
            ic.commitText(sufijo, 1)
            ultimaCorreccion = null
            return
        }

        // 2) Autocorrección de la palabra (tildes que faltan y errores de tipeo)
        var nuevaPalabra = palabra
        if (autocorreccionActiva) diccionario.corregir(palabra, previa)?.let { nuevaPalabra = it }

        // 3) Tildes que dependen de la palabra anterior ("que tal" → "qué tal")
        var nuevaPrevia = previa
        if (autocorreccionActiva && previa.isNotEmpty()) {
            diccionario.corregirPar(previa, nuevaPalabra)?.let { (a, b) ->
                nuevaPrevia = a
                nuevaPalabra = b
            }
        }

        if (sugerenciasActivas) {
            diccionario.aprender(nuevaPalabra)
            diccionario.aprenderPar(nuevaPrevia, nuevaPalabra)
            // Si hay palabra anterior pegada, estás a mitad de oración (sirve para recordar nombres)
            diccionario.notarMayuscula(nuevaPalabra, previa.isNotEmpty())
            // Si borras el espacio enseguida, se deshace lo aprendido (era un error)
            ultimaAprendida = if (sufijo == " ") Triple(nuevaPrevia, nuevaPalabra, nuevaPalabra + sufijo) else null
        }

        if (nuevaPrevia == previa && nuevaPalabra == palabra) {
            ic.commitText(sufijo, 1)
            ultimaCorreccion = null
            return
        }
        val cambiaPrevia = nuevaPrevia != previa
        val original = if (cambiaPrevia) previa + espacios + palabra else palabra
        val nuevo = (if (cambiaPrevia) nuevaPrevia + espacios else "") + nuevaPalabra
        ic.beginBatchEdit()
        ic.deleteSurroundingText(original.length, 0)
        ic.commitText(nuevo + sufijo, 1)
        ic.endBatchEdit()
        ultimaCorreccion = Pair(original, nuevo + sufijo)
    }

    /** Al poner "?", la palabra de pregunta del inicio lleva tilde: "que hora es?" → "qué hora es?" */
    private fun acentuarPregunta() {
        if (!autocorreccionActiva) return
        val ic = currentInputConnection ?: return
        val antes = ic.getTextBeforeCursor(200, 0)?.toString() ?: return
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
        val conTilde = diccionario.interrogativa(objetivo) ?: return
        val desde = i + inicio
        val resto = antes.substring(desde + objetivo.length)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(antes.length - desde, 0)
        ic.commitText(conTilde + resto, 1)
        ic.endBatchEdit()
    }

    // ---------- Acciones de las teclas ----------

    override fun texto(s: String) {
        val ic = currentInputConnection ?: return
        ultimaAprendida = null
        if (s == "?") acentuarPregunta()
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
        val ua = ultimaAprendida
        ultimaAprendida = null
        if (uc != null) {
            // Deshacer la autocorrección como en iPhone
            val antes = ic.getTextBeforeCursor(uc.second.length, 0)?.toString()
            if (antes == uc.second) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(uc.second.length, 0)
                ic.commitText(uc.first, 1)
                ic.endBatchEdit()
                // La corrección no era lo que querías: se olvida y se aprende lo tuyo
                if (ua != null) diccionario.desaprender(ua.second, ua.first)
                uc.first.split(" ").filter { it.isNotBlank() }.forEach {
                    diccionario.aprender(it, 2)
                    if (it.all { c -> c.isLowerCase() }) diccionario.notarMayuscula(it, true)
                }
                return
            }
        }
        // Borraste el espacio justo después de una palabra: no se cuenta como aprendida
        if (ua != null && ic.getTextBeforeCursor(ua.third.length, 0)?.toString() == ua.third) {
            diccionario.desaprender(ua.second, ua.first)
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
        ultimaAprendida = null
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
        ultimaAprendida = null
        espacioAutomatico = false
        currentInputConnection?.commitText(s, 1)
    }

    override fun elegir(s: Sugerencia) {
        if (dictando) return
        val ic = currentInputConnection ?: return
        if (s.revertir) {
            // Volver a lo que escribiste y aprenderlo para no corregirlo otra vez
            val uc = ultimaCorreccion ?: return
            val sufijo = uc.second.takeLastWhile { !it.isLetterOrDigit() }
            ic.beginBatchEdit()
            ic.deleteSurroundingText(uc.second.length, 0)
            ic.commitText(uc.first + sufijo, 1)
            ic.endBatchEdit()
            ultimaAprendida?.let { diccionario.desaprender(it.second, it.first) }
            ultimaAprendida = null
            uc.first.split(" ").filter { it.isNotBlank() }.forEach {
                diccionario.aprender(it, 2)
                if (it.all { c -> c.isLowerCase() }) diccionario.notarMayuscula(it, true)
            }
            ultimaCorreccion = null
            retro(Tipo.LETRA)
            return
        }
        val (previa, palabra) = ultimasPalabras()
        val borrarN = if (s.reemplaza >= 0) s.reemplaza else palabra.length
        ic.beginBatchEdit()
        if (borrarN > 0) ic.deleteSurroundingText(borrarN, 0)
        ic.commitText(s.texto + " ", 1)
        ic.endBatchEdit()
        diccionario.aprender(s.texto, if (s.literal) 2 else 1)
        diccionario.aprenderPar(previa, s.texto)
        ultimaAprendida = Triple(previa, s.texto, s.texto + " ")
        ultimaCorreccion = null
        espacioAutomatico = true
        retro(Tipo.LETRA)
    }

    /** Mantener presionada una sugerencia → Borrar: el teclado olvida esa palabra */
    override fun olvidar(s: Sugerencia) {
        if (dictando) return
        diccionario.olvidar(s.texto)
        retro(Tipo.BORRAR)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
        val ic = currentInputConnection ?: return
        val antes = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val t = if (inicioDeOracion()) texto.replaceFirstChar { it.uppercase() } else texto
        val prefijo = if (antes.isNotEmpty() && !antes.last().isWhitespace()) " " else ""
        ic.commitText("$prefijo$t ", 1)
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

    override fun retro(tipo: Tipo) {
        if (vibrar) {
            try {
                val v = vibrador
                // Toque corto y seco, como el Taptic Engine del iPhone (si el celular lo soporta)
                if (v != null && toqueFino == null) {
                    toqueFino = v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
                }
                if (toqueFino == true) {
                    v?.vibrate(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f)
                        .compose())
                } else {
                    v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
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
