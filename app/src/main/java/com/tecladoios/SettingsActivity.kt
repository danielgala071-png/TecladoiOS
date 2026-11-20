package com.tecladoios

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Ajustes en una sola pantalla, agrupados como en el iPhone. Todo se guarda al instante y el
 * teclado lo toma sin reiniciarse.
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { Ajustes.prefs(this) }
    private val acento = Tema.AZUL
    private lateinit var lista: LinearLayout
    private var estado: TextView? = null

    // Colores de Ajustes del iPhone: claro u oscuro según el celular
    private val oscuro by lazy {
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    private val cFondo get() = if (oscuro) Color.BLACK else 0xFFF2F2F7.toInt()
    private val cTarjeta get() = if (oscuro) 0xFF161011.toInt() else Color.WHITE   // obsidiana
    private val cTexto get() = if (oscuro) Color.WHITE else Color.BLACK
    private val cGris = 0xFF8E8E93.toInt()
    private val cLinea get() = if (oscuro) 0xFF2E1C1F.toInt() else 0xFFC6C6C8.toInt()
    private val cToque get() = if (oscuro) 0xFF2C2C2E.toInt() else 0xFFD1D1D6.toInt()

    /** Fila que se oscurece al tocarla, como en iOS */
    private fun alTocar(v: View) {
        v.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(cToque))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        window.statusBarColor = cFondo
        window.navigationBarColor = cFondo
        if (!oscuro) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(40f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(cFondo)
            addView(lista)
            fitsSystemWindows = true
        }
        setContentView(scroll)
        Cerebro.iniciar(this)
        armar()
    }

    override fun onResume() {
        super.onResume()
        actualizarEstado()
    }

    // ---------- Piezas de la pantalla ----------

    private fun titulo(t: String) {
        lista.addView(TextView(this).apply {
            text = t; setTextColor(cTexto); textSize = 34f
            Fuentes.aplicar(this, 700)
            setPadding(dp(4f), dp(8f), 0, dp(18f))
        })
    }

    // ---------- Íconos de colores como en Ajustes del iPhone ----------
    // Obsidiana carmesí: los íconos de cada sección en tonos de rojo metálico (antes, colores iOS)
    private val COLORES_IOS = intArrayOf(0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(), 0xFF5E5CE6.toInt(),
        0xFFFF453A.toInt(), 0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(), 0xFF8E8E93.toInt(), 0xFFFF375F.toInt())
    private var colorSeccion = COLORES_IOS[0]
    private var nSeccion = 0

    /** Símbolo de la fila según lo que hace (como los íconos del iPhone) */
    private fun simbolo(t: String): String {
        val x = t.lowercase()
        return when {
            "mayús" in x || "mayus" in x -> "⇧"
            "corrección" in x || "corregir" in x -> "✓"
            "punto" in x -> "•"
            "puntuación" in x || "cierre" in x -> "❞"
            "espacio" in x -> "␣"
            "sugerencia" in x || "predecir" in x || "barra" in x -> "≡"
            "separar" in x -> "⇹"
            "aprender" in x || "palabra" in x || "estadística" in x -> "✎"
            "vibración" in x || "vibrar" in x -> "≋"
            "sonido" in x || "volumen" in x -> "♪"
            "emoji" in x -> "☺"
            "código" in x || "sms" in x -> "✉"
            "contraseña" in x || "gestor" in x -> "⚿"
            "datos" in x || "contacto" in x || "nombre" in x -> "☻"
            "atajo" in x || "sustitución" in x -> "⇄"
            "tamaño" in x || "altura" in x || "dividido" in x -> "⤢"
            "modo" in x || "tema" in x -> "◐"
            "activar" in x || "elegir" in x -> "⌨"
            "portapapeles" in x || "copiado" in x || "captura" in x -> "⎘"
            "números" in x -> "#"
            else -> "⚙"
        }
    }

    private fun icono(t: String): View = TextView(this).apply {
        text = simbolo(t); setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(colorSeccion); cornerRadius = dp(7f).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(29f), dp(29f)).apply { marginEnd = dp(14f) }
    }

    private fun seccion(t: String, nota: String? = null): LinearLayout {
        colorSeccion = COLORES_IOS[nSeccion % COLORES_IOS.size]; nSeccion++
        lista.addView(TextView(this).apply {
            text = t.uppercase(); setTextColor(cGris); textSize = 12.5f
            Fuentes.aplicar(this, 500)
            setPadding(dp(16f), dp(18f), 0, dp(7f))
        })
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cTarjeta); cornerRadius = dp(10f).toFloat() }
            clipToOutline = true
        }
        lista.addView(caja)
        if (nota != null) lista.addView(TextView(this).apply {
            text = nota; setTextColor(0xFF8E8E93.toInt()); textSize = 12.5f
            setPadding(dp(16f), dp(7f), dp(16f), 0)
        })
        return caja
    }

    private fun separador(caja: LinearLayout) {
        if (caja.childCount == 0) return
        // Como el iPhone: la línea empieza donde empieza el texto (después del ícono)
        caja.addView(View(this).apply { setBackgroundColor(cLinea) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(59f) })
    }

    private fun interruptor(caja: LinearLayout, texto: String, clave: String, porDefecto: Boolean, sub: String? = null, alCambiar: ((Boolean) -> Boolean)? = null) {
        separador(caja)
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(11f), dp(12f), dp(11f))
        }
        fila.addView(icono(texto))
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply { text = texto; setTextColor(cTexto); textSize = 17f })
        if (sub != null) textos.addView(TextView(this).apply { text = sub; setTextColor(0xFF8E8E93.toInt()); textSize = 12.5f })
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(clave, porDefecto)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF34C759.toInt(), if (oscuro) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()))
            setOnCheckedChangeListener { b, si ->
                val ok = alCambiar?.invoke(si) ?: true
                if (ok) prefs.edit().putBoolean(clave, si).apply() else b.isChecked = !si
            }
        }
        fila.addView(sw)
        alTocar(fila)
        fila.setOnClickListener { sw.toggle() }
        caja.addView(fila)
    }

    private fun deslizador(caja: LinearLayout, texto: String, clave: String, min: Int, max: Int, porDefecto: Int, formato: (Int) -> String) {
        separador(caja)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(11f), dp(12f), dp(8f)) }
        val etiqueta = TextView(this).apply { setTextColor(cTexto); textSize = 17f }
        val actual = prefs.getInt(clave, porDefecto)
        etiqueta.text = "$texto: ${formato(actual)}"
        col.addView(etiqueta)
        col.addView(SeekBar(this).apply {
            this.max = max - min
            progress = actual - min
            progressTintList = ColorStateList.valueOf(acento)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) { etiqueta.text = "$texto: ${formato(p + min)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt(clave, (s?.progress ?: 0) + min).apply() }
            })
        })
        caja.addView(col)
    }

    private fun boton(caja: LinearLayout, texto: String, color: Int = acento, sub: String? = null, accion: () -> Unit): TextView {
        separador(caja)
        // Fila como en Ajustes del iPhone: texto a la izquierda y "›" gris a la derecha
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), 0, 0, 0) }
        if (texto.isNotEmpty()) fila.addView(icono(texto))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(if (texto.isEmpty()) 0 else 0, dp(12f), dp(8f), dp(12f)) }
        val t = TextView(this).apply { text = texto; setTextColor(if (color == Color.WHITE) cTexto else color); textSize = 17f }
        col.addView(t)
        if (sub != null) col.addView(TextView(this).apply { text = sub; setTextColor(cGris); textSize = 12.5f })
        fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila.addView(TextView(this).apply { text = "›"; setTextColor(0xFFC4C4C7.toInt()); textSize = 22f; setPadding(0, 0, dp(14f), dp(2f)) })
        col.setOnClickListener { accion() }
        fila.setOnClickListener { col.performClick() }
        alTocar(fila)
        caja.addView(fila)
        return t
    }

    private fun opciones(caja: LinearLayout, texto: String, clave: String, nombres: List<String>, porDefecto: Int) {
        val t = boton(caja, texto, Color.WHITE) {}
        fun refrescar() { t.text = "$texto: ${nombres.getOrElse(prefs.getInt(clave, porDefecto)) { nombres[0] }}" }
        refrescar()
        (t.parent as View).setOnClickListener {
            AlertDialog.Builder(this).setTitle(texto)
                .setSingleChoiceItems(nombres.toTypedArray(), prefs.getInt(clave, porDefecto)) { d, i ->
                    prefs.edit().putInt(clave, i).apply(); refrescar(); d.dismiss()
                }.show()
        }
    }

    // ---------- Contenido ----------

    private fun armar() {
        titulo("TecladoiOS")

        val act = seccion("Activar", "Actívalo una vez y elígelo como teclado. Todo lo que aprende se queda en tu celular, sin internet.")
        estado = boton(act, "Activar", Color.WHITE) {}
        boton(act, "1. Activar en los ajustes de Android") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        boton(act, "2. Elegir TecladoiOS") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }

        val prueba = seccion("Probar")
        prueba.addView(EditText(this).apply {
            hint = "Escribe aquí para probar…"
            setHintTextColor(0xFF8E8E93.toInt()); setTextColor(cTexto)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        })

        val corr = seccion("Corrección y sugerencias", "Las sugerencias tienen en cuenta dónde tocas cada tecla, la palabra anterior y cómo escribes tú. Si borras justo después de una corrección, vuelve lo que escribiste y el teclado lo aprende.")
        interruptor(corr, "Corrección automática", Ajustes.AUTOCORRECCION, true)
        interruptor(corr, "Mayúsculas automáticas", Ajustes.MAYUS_AUTO, true)
        interruptor(corr, "Bloqueo de mayúsculas", Ajustes.BLOQUEO_MAYUS, true, "Toca dos veces la mayúscula para dejarla fija")
        interruptor(corr, "Puntuación inteligente", Ajustes.PUNTUACION, true, "Dos guiones seguidos se vuelven una raya (—)")
        interruptor(corr, "Punto con doble espacio", Ajustes.PUNTO_DOBLE_ESPACIO, true)
        interruptor(corr, "Espacio después de signos", Ajustes.ESPACIO_AUTO, true, "Después de , . ? ! al usar una sugerencia")
        interruptor(corr, "Barra de sugerencias", Ajustes.SUGERENCIAS, true)
        interruptor(corr, "Predecir la siguiente palabra", Ajustes.PREDICCION, true)
        interruptor(corr, "Aprender mis palabras", Ajustes.APRENDER, true)

        val des = seccion("Gestos")
        interruptor(des, "Mover el cursor con el espacio", Ajustes.CURSOR_ESPACIO, true, "Desliza el espacio; mantenlo para usarlo como trackpad")

        val tec = seccion("Teclado")
        interruptor(tec, "Vista previa al tocar", Ajustes.VISTA_PREVIA, true)
        deslizador(tec, "Altura", Ajustes.ALTURA, 75, 135, 100) { "$it %" }
        deslizador(tec, "Espacio debajo del teclado", Ajustes.MARGEN_ABAJO, 0, 120, 0) { "$it" }
        deslizador(tec, "Ancho", Ajustes.ANCHO, 70, 100, 100) { "$it %" }
        interruptor(tec, "Escribir al tocar", Ajustes.AL_TOCAR, true, "La letra sale apenas tocas (más rápido a dos dedos)")
        interruptor(tec, "¿ y ¡ automáticos", Ajustes.SIGNOS_INICIO, true, "Al cerrar con ? o ! se pone ¿ o ¡ al inicio de la frase")
        deslizador(tec, "Mantener presionado", Ajustes.RETARDO_LARGA, 150, 700, 320) { "$it ms" }

        // Sin sonido (gasta batería): solo queda la vibración, corta e igual en todas las teclas
        val son = seccion("Vibración")
        interruptor(son, "Vibración al tocar", Ajustes.VIBRACION, true, "Sutil, distinta en letras, palabras, espacio, borrar, intro y demás teclas")


        val dic = seccion("Diccionario personal")
        boton(dic, "Ver mis palabras") { verPalabras() }
        boton(dic, "Agregar una palabra") { agregarPalabra() }
        boton(dic, "Eliminar una palabra", sub = "No se sugiere ni se usa para corregir. También: mantén presionada una sugerencia") { eliminarPalabra() }
        boton(dic, "Palabras eliminadas", sub = "Toca una para que vuelva a sugerirse") { verEliminadas() }
        boton(dic, "Borrar todo lo aprendido", 0xFFFF453A.toInt()) {
            AlertDialog.Builder(this).setMessage("¿Borrar todas las palabras y costumbres que aprendió el teclado?")
                .setPositiveButton("Borrar") { _, _ -> Cerebro.motor?.aprendido?.borrarTodo() }
                .setNegativeButton("Cancelar", null).show()
        }


        val est = seccion("Diagnóstico")
        boton(est, "Compartir mi uso con Claude", sub = "Solo números: dónde tocas cada tecla, qué letras borras y confundes, y tus ajustes") { compartirUso() }

        val otros = seccion("Otros")
        interruptor(otros, "Ocultar el ícono de la app", Ajustes.ICONO_OCULTO, false, "Abre los ajustes desde el engranaje del teclado") { si ->
            packageManager.setComponentEnabledSetting(ComponentName(this, "com.tecladoios.Lanzador"),
                if (si) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
            true
        }
        boton(otros, "Que Android no cierre el teclado", sub = "Quita el ahorro de batería solo para el teclado") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this).setMessage("Listo: el teclado ya está libre del ahorro de batería.").setPositiveButton("OK", null).show()
            } else try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        boton(otros, "Registro de errores") { verRegistro() }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        lista.addView(TextView(this).apply {
            text = "TecladoiOS $version"; setTextColor(0xFF636366.toInt()); textSize = 12f
            gravity = Gravity.CENTER; setPadding(0, dp(24f), 0, 0)
        })
    }

    private fun actualizarEstado() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val activo = imm.enabledInputMethodList.any { it.packageName == packageName }
        val elegido = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        estado?.text = when {
            elegido -> "✓ TecladoiOS está activo y elegido"
            activo -> "Activado — falta elegirlo como teclado"
            else -> "Todavía no está activado"
        }
        estado?.setTextColor(if (elegido) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt())
    }

    private fun conMotor(f: (Motor) -> Unit) {
        val m = Cerebro.motor
        if (m != null) { f(m); return }
        val espera = AlertDialog.Builder(this).setMessage("Cargando el diccionario…").show()
        Cerebro.iniciar(this) { espera.dismiss(); Cerebro.motor?.let(f) }
    }

    private fun verPalabras() = conMotor { m ->
        val palabras = synchronized(m.aprendido) { m.aprendido.propias.values.filter { it.veces >= 2 || it.fija }.sortedBy { it.texto.lowercase() }.map { it.texto } }
        if (palabras.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Aún no hay palabras propias. Se agregan solas cuando escribes una palabra nueva varias veces o deshaces una corrección.").setPositiveButton("OK", null).show()
            return@conMotor
        }
        AlertDialog.Builder(this).setTitle("Mis palabras (toca para borrar)")
            .setItems(palabras.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setMessage("¿Olvidar «${palabras[i]}»?")
                    .setPositiveButton("Olvidar") { _, _ -> m.aprendido.quitar(palabras[i]); Cerebro.guardar() }
                    .setNegativeButton("Cancelar", null).show()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun eliminarPalabra() = conMotor { m ->
        val campo = EditText(this).apply { hint = "Palabra"; setPadding(dp(20f), dp(16f), dp(20f), dp(16f)) }
        AlertDialog.Builder(this).setTitle("Eliminar palabra").setView(campo)
            .setPositiveButton("Eliminar") { _, _ ->
                val w = campo.text.toString().trim()
                if (w.isNotEmpty() && !w.contains(' ')) { m.aprendido.eliminar(w); Cerebro.guardar() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun verEliminadas() = conMotor { m ->
        val palabras = synchronized(m.aprendido) { m.aprendido.eliminadas.sorted() }
        if (palabras.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No has eliminado ninguna palabra. Para eliminar una, mantén presionada la sugerencia en la barra del teclado.").setPositiveButton("OK", null).show()
            return@conMotor
        }
        AlertDialog.Builder(this).setTitle("Palabras eliminadas (toca para recuperar)")
            .setItems(palabras.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setMessage("¿Volver a sugerir «${palabras[i]}»?")
                    .setPositiveButton("Recuperar") { _, _ -> m.aprendido.restaurar(palabras[i]); Cerebro.guardar() }
                    .setNegativeButton("Cancelar", null).show()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun agregarPalabra() = conMotor { m ->
        val campo = EditText(this).apply { hint = "Palabra"; setPadding(dp(20f), dp(16f), dp(20f), dp(16f)) }
        AlertDialog.Builder(this).setTitle("Agregar palabra").setView(campo)
            .setPositiveButton("Agregar") { _, _ ->
                val w = campo.text.toString().trim()
                if (w.isNotEmpty() && !w.contains(' ')) { m.aprendido.agregar(w, fija = true); Cerebro.guardar() }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------- Estadísticas ----------

    private fun verEstadisticas() = conMotor { m ->
        val top = synchronized(m.aprendido) { m.aprendido.uso.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value } }
        AlertDialog.Builder(this).setTitle("Mis estadísticas")
            .setMessage(Estadisticas.resumen(prefs, top))
            .setNegativeButton("Reiniciar") { _, _ -> Estadisticas.reiniciar(prefs) }
            .setPositiveButton("Cerrar", null).show()
    }

    /**
     * Informe de uso para ajustar el teclado a tu manera de escribir. Solo números (nada de
     * lo que escribes): desvío de tus toques en cada tecla, letras que borras justo después
     * de escribirlas, pares de letras que confundes, velocidad y tus ajustes actuales.
     */
    private fun compartirUso() = conMotor { m ->
        val sb = StringBuilder()
        val dm = resources.displayMetrics
        sb.append("TecladoiOS: mi uso (solo números)\n")
        sb.append("Versión: ").append(try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }).append('\n')
        sb.append("Pantalla: ").append((dm.widthPixels / dm.density).toInt()).append(" x ").append((dm.heightPixels / dm.density).toInt()).append(" dp\n")
        val pal = prefs.getLong("est_palabras", 0); val let = prefs.getLong("est_letras", 0)
        val cor = prefs.getLong("est_correcciones", 0); val min = prefs.getLong("est_ms", 0) / 60000.0
        sb.append("Palabras: ").append(pal).append(" · letras: ").append(let).append(" · corregidas: ").append(cor)
        if (pal > 0) sb.append(" (").append(cor * 100 / pal).append(" %)")
        if (min > 0.5) sb.append(" · ").append((pal / min).toInt()).append(" palabras/min")
        sb.append('\n')
        sb.append("\nAjustes: altura ").append(prefs.getInt(Ajustes.ALTURA, 100)).append(" %, ancho ").append(prefs.getInt(Ajustes.ANCHO, 100))
            .append(" %, espacio abajo ").append(prefs.getInt(Ajustes.MARGEN_ABAJO, 0)).append(", una mano ").append(prefs.getInt(Ajustes.UNA_MANO, 0))
            .append(", vibración ").append(if (prefs.getBoolean(Ajustes.VIBRACION, true)) "sí" else "no")
            .append(", mantener ").append(prefs.getInt(Ajustes.RETARDO_LARGA, 320)).append(" ms")
            .append(", escribir al tocar ").append(if (prefs.getBoolean(Ajustes.AL_TOCAR, true)) "sí" else "no").append('\n')
        sb.append("\nLetras (usada / borrada enseguida / % de error):\n")
        val err = Estadisticas.erroresPorLetra(prefs).sortedByDescending { if (it.second > 0) it.third * 1000 / it.second else 0 }
        for ((ch, u, e) in err) sb.append(ch).append(": ").append(u).append(" / ").append(e).append(" / ").append(if (u > 0) e * 100 / u else 0).append(" %\n")
        sb.append("\nDónde tocas cada tecla (desvío del centro, en anchos de tecla; + derecha / + abajo):\n")
        sb.append(prefs.getString("desvios", null) ?: "aún sin datos").append('\n')
        lecturaToque(prefs.getString("desvios", null), prefs.getInt(Ajustes.ALTURA, 100))?.let { sb.append('\n').append(it) }
        sb.append("\nLetras que confundes (pusiste → iba):\n")
        val conf = synchronized(m.aprendido) { m.aprendido.confusiones.entries.sortedByDescending { it.value }.take(25).map { it.key to it.value } }
        if (conf.isEmpty()) sb.append("aún sin datos\n")
        for ((par, v) in conf) if (par.length == 2) sb.append(par[0]).append(" → ").append(par[1]).append(": ").append(v).append('\n')
        sb.append("\nVelocidad del teclado:\n").append(Medidor.resumen(this))
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sb.toString())
        startActivity(Intent.createChooser(i, "Compartir mi uso"))
    }

    /**
     * Lectura en palabras de los desvíos: cuánto más abajo/arriba tocas cada fila y, si el
     * teclado te queda alto o bajo para tu mano, cuánta altura conviene probar.
     */
    private fun lecturaToque(desvios: String?, altura: Int): String? {
        if (desvios.isNullOrEmpty()) return null
        val v = HashMap<Char, Float>()
        for (p in desvios.split(';')) {
            val q = p.split(','); if (q.size != 3 || q[0].length != 1) continue
            v[q[0][0]] = q[2].toFloatOrNull() ?: continue
        }
        fun media(fila: String): Float? = fila.mapNotNull { v[it] }.takeIf { it.size >= 3 }?.average()?.toFloat()
        val arriba = media("qwertyuiop"); val centro = media("asdfghjklñ"); val abajo = media("zxcvbnm")
        if (arriba == null || abajo == null) return null
        fun f(x: Float?) = if (x == null) "?" else "%+.2f".format(java.util.Locale.US, x)
        val sb = StringBuilder("Lectura del toque (vertical por fila): arriba ${f(arriba)} · centro ${f(centro)} · abajo ${f(abajo)}\n")
        // Fila de arriba tocada abajo y la de abajo tocada arriba = el teclado es más alto que
        // lo que recorre tu dedo. Al revés = te queda bajo.
        val aprieta = arriba - abajo
        when {
            aprieta > 0.2f -> sb.append("→ Tocas hacia el centro: el teclado te queda alto. Prueba la altura en ${(altura - (aprieta * 25).toInt().coerceIn(3, 12))} %.\n")
            aprieta < -0.2f -> sb.append("→ Tocas hacia afuera: el teclado te queda bajo. Prueba la altura en ${(altura + (-aprieta * 25).toInt().coerceIn(3, 12))} %.\n")
            else -> sb.append("→ La altura te queda bien.\n")
        }
        return sb.toString()
    }

    // ---------- Autocompletar ----------

    private fun accesoNotificaciones(): Boolean = try {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.isNotificationListenerAccessGranted(android.content.ComponentName(this, CodigosService::class.java))
    } catch (e: Exception) { false }

    private fun misDatos() {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val campos = listOf(
            Triple(Ajustes.DATO_NOMBRE, "Nombre completo", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS),
            Triple(Ajustes.DATO_CORREO, "Correo", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            Triple(Ajustes.DATO_TELEFONO, "Teléfono", InputType.TYPE_CLASS_PHONE),
            Triple(Ajustes.DATO_DIRECCION, "Dirección", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
        ).map { (clave, pista, tipo) ->
            val e = EditText(this).apply { hint = pista; inputType = tipo; setText(prefs.getString(clave, "")) }
            caja.addView(e); clave to e
        }
        AlertDialog.Builder(this).setTitle("Mis datos").setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val ed = prefs.edit()
                for ((clave, e) in campos) ed.putString(clave, e.text.toString().trim())
                ed.apply()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---------- Atajos de texto ----------

    private fun verAtajos() {
        val m = Atajos.leer(prefs)
        if (m.isEmpty()) { editarAtajo(null); return }
        val claves = m.keys.toList()
        val filas = claves.map { "$it  →  ${m[it]}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Atajos de texto")
            .setItems(filas) { _, i -> editarAtajo(claves[i]) }
            .setPositiveButton("Agregar") { _, _ -> editarAtajo(null) }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun editarAtajo(clave: String?) {
        val m = Atajos.leer(prefs)
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val campoClave = EditText(this).apply { hint = "Clave (ej.: bd)"; setText(clave ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val campoFrase = EditText(this).apply { hint = "Frase (ej.: Buenos días)"; setText(clave?.let { m[it] } ?: "") }
        caja.addView(campoClave); caja.addView(campoFrase)
        val d = AlertDialog.Builder(this).setTitle(if (clave == null) "Nuevo atajo" else "Editar atajo").setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val k = campoClave.text.toString().trim().lowercase()
                val v = campoFrase.text.toString().trim()
                if (!Atajos.claveValida(k) || v.isEmpty()) {
                    AlertDialog.Builder(this).setMessage("La clave debe ser una sola palabra sin espacios y la frase no puede quedar vacía.").setPositiveButton("OK", null).show()
                    return@setPositiveButton
                }
                if (clave != null) m.remove(clave)
                m[k] = v
                Atajos.guardar(prefs, m)
            }
            .setNegativeButton("Cancelar", null)
        if (clave != null) d.setNeutralButton("Borrar") { _, _ -> m.remove(clave); Atajos.guardar(prefs, m) }
        d.show()
    }

    private fun verMedidor() {
        AlertDialog.Builder(this).setTitle("Medidor de velocidad").setMessage(Medidor.resumen(this))
            .setNeutralButton("Reiniciar") { _, _ -> Medidor.reiniciar(this) }
            .setPositiveButton("Cerrar", null).show()
    }

    private fun verRegistro() {
        val texto = Registro.leer(this).ifEmpty { "No hay errores anotados." }
        val ultimo = getSharedPreferences("tecladoios_error", MODE_PRIVATE).getString("ultimo", null)
        val todo = if (ultimo != null) "$texto\n\nÚltimo cierre inesperado:\n$ultimo" else texto
        AlertDialog.Builder(this).setTitle("Registro de errores").setMessage(todo.take(6000))
            .setPositiveButton("Copiar") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("registro", todo))
            }
            .setNeutralButton("Borrar") { _, _ ->
                Registro.borrar(this)
                getSharedPreferences("tecladoios_error", MODE_PRIVATE).edit().clear().apply()
            }
            .setNegativeButton("Cerrar", null).show()
    }
}
