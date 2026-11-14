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

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(40f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
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
            text = t; setTextColor(Color.WHITE); textSize = 32f
            Fuentes.aplicar(this, 700)
            setPadding(dp(4f), dp(8f), 0, dp(18f))
        })
    }

    private fun seccion(t: String, nota: String? = null): LinearLayout {
        lista.addView(TextView(this).apply {
            text = t.uppercase(); setTextColor(0xFF8E8E93.toInt()); textSize = 12.5f
            Fuentes.aplicar(this, 500)
            setPadding(dp(16f), dp(18f), 0, dp(7f))
        })
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius = dp(12f).toFloat() }
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
        caja.addView(View(this).apply { setBackgroundColor(0xFF38383A.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16f) })
    }

    private fun interruptor(caja: LinearLayout, texto: String, clave: String, porDefecto: Boolean, sub: String? = null, alCambiar: ((Boolean) -> Boolean)? = null) {
        separador(caja)
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(11f), dp(12f), dp(11f))
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply { text = texto; setTextColor(Color.WHITE); textSize = 16.5f })
        if (sub != null) textos.addView(TextView(this).apply { text = sub; setTextColor(0xFF8E8E93.toInt()); textSize = 12.5f })
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(clave, porDefecto)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF30D158.toInt(), 0xFF39393D.toInt()))
            setOnCheckedChangeListener { b, si ->
                val ok = alCambiar?.invoke(si) ?: true
                if (ok) prefs.edit().putBoolean(clave, si).apply() else b.isChecked = !si
            }
        }
        fila.addView(sw)
        fila.setOnClickListener { sw.toggle() }
        caja.addView(fila)
    }

    private fun deslizador(caja: LinearLayout, texto: String, clave: String, min: Int, max: Int, porDefecto: Int, formato: (Int) -> String) {
        separador(caja)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(11f), dp(12f), dp(8f)) }
        val etiqueta = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16.5f }
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(13f), dp(12f), dp(13f)) }
        val t = TextView(this).apply { text = texto; setTextColor(color); textSize = 16.5f }
        col.addView(t)
        if (sub != null) col.addView(TextView(this).apply { text = sub; setTextColor(0xFF8E8E93.toInt()); textSize = 12.5f })
        col.setOnClickListener { accion() }
        caja.addView(col)
        return t
    }

    private fun opciones(caja: LinearLayout, texto: String, clave: String, nombres: List<String>, porDefecto: Int) {
        val t = boton(caja, "", Color.WHITE) {}
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
        estado = boton(act, "", Color.WHITE) {}
        boton(act, "1. Activar en los ajustes de Android") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        boton(act, "2. Elegir TecladoiOS") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }

        val prueba = seccion("Probar")
        prueba.addView(EditText(this).apply {
            hint = "Escribe aquí para probar…"
            setHintTextColor(0xFF8E8E93.toInt()); setTextColor(Color.WHITE)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        })

        val corr = seccion("Corrección y sugerencias", "Las sugerencias tienen en cuenta dónde tocas cada tecla, la palabra anterior y cómo escribes tú. Si borras justo después de una corrección, vuelve lo que escribiste y el teclado lo aprende.")
        interruptor(corr, "Corrección automática", Ajustes.AUTOCORRECCION, true)
        interruptor(corr, "Mayúsculas automáticas", Ajustes.MAYUS_AUTO, true)
        interruptor(corr, "Punto con doble espacio", Ajustes.PUNTO_DOBLE_ESPACIO, true)
        interruptor(corr, "Espacio después de signos", Ajustes.ESPACIO_AUTO, true, "Después de , . ? ! al usar una sugerencia")
        interruptor(corr, "Barra de sugerencias", Ajustes.SUGERENCIAS, true)
        interruptor(corr, "Predecir la siguiente palabra", Ajustes.PREDICCION, true)
        interruptor(corr, "Separar palabras pegadas", Ajustes.SEPARAR_PEGADAS, true, "«Yanpodemos» → «Ya podemos»")
        interruptor(corr, "Aprender mis palabras", Ajustes.APRENDER, true)
        interruptor(corr, "Sugerir nombres de contactos", Ajustes.CONTACTOS, false, "Los nombres nunca salen del celular") { si ->
            if (si && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 11); true
            } else true
        }

        val des = seccion("Gestos")
        interruptor(des, "Escribir deslizando", Ajustes.DESLIZAR, true, "Pasa el dedo por las letras sin levantarlo")
        interruptor(des, "Mostrar el trazo", Ajustes.ESTELA, true)
        interruptor(des, "Borrar palabras deslizando", Ajustes.BORRAR_DESLIZANDO, true, "Desde borrar hacia la izquierda")
        interruptor(des, "Mover el cursor con el espacio", Ajustes.CURSOR_ESPACIO, true, "Desliza el espacio; mantenlo para usarlo como trackpad")

        val tec = seccion("Teclado")
        interruptor(tec, "Fila de números", Ajustes.FILA_NUMEROS, false)
        interruptor(tec, "Símbolos en las teclas", Ajustes.PISTAS, false, "Mantén una letra para escribir su símbolo o tilde")
        interruptor(tec, "Vista previa al tocar", Ajustes.VISTA_PREVIA, true)
        interruptor(tec, "Detalles del color de la app", Ajustes.BORDES_APP, true, "Trazo al deslizar y tecla enviar")
        interruptor(tec, "Barra de herramientas", Ajustes.BARRA_HERRAMIENTAS, true, "Emojis, portapapeles, edición, una mano, tamaño y ajustes")
        opciones(tec, "Modo una mano", Ajustes.UNA_MANO, listOf("Apagado", "Izquierda", "Derecha"), 0)
        deslizador(tec, "Altura", Ajustes.ALTURA, 75, 135, 100) { "$it %" }
        deslizador(tec, "Espacio debajo del teclado", Ajustes.MARGEN_ABAJO, 0, 120, 0) { "$it" }
        deslizador(tec, "Ancho", Ajustes.ANCHO, 70, 100, 100) { "$it %" }
        interruptor(tec, "Escribir al tocar", Ajustes.AL_TOCAR, true, "La letra sale apenas tocas (más rápido a dos dedos)")
        interruptor(tec, "¿ y ¡ automáticos", Ajustes.SIGNOS_INICIO, true, "Al cerrar con ? o ! se pone ¿ o ¡ al inicio de la frase")
        interruptor(tec, "Vidrio con desenfoque", Ajustes.VIDRIO, false, "Android 12+. Desenfoca lo que hay detrás (gasta un poco más). Apagado: negro AMOLED")
        deslizador(tec, "Mantener presionado", Ajustes.RETARDO_LARGA, 150, 700, 320) { "$it ms" }

        val son = seccion("Sonido y vibración")
        interruptor(son, "Vibración al tocar", Ajustes.VIBRACION, true, "Golpe fuerte en cada tecla, más fuerte en espacio, mayúscula y borrar")
        interruptor(son, "Sonido al tocar", Ajustes.SONIDO, false)
        deslizador(son, "Volumen", Ajustes.VOLUMEN, 0, 100, 40) { "$it %" }

        val por = seccion("Portapapeles y capturas", "Lo copiado se guarda una hora (lo fijado, siempre). Las contraseñas copiadas no se guardan.")
        interruptor(por, "Historial del portapapeles", Ajustes.PORTAPAPELES, true)
        interruptor(por, "Ofrecer captura reciente", Ajustes.CAPTURAS, false, "Tu última captura aparece en la barra para enviarla") { si ->
            if (si && !Capturas.tienePermiso(this)) { requestPermissions(arrayOf(Capturas.permiso()), 12) }
            true
        }

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

        val ata = seccion("Atajos de texto", "Escribe la clave y toca espacio: se pone la frase completa. Si borras justo después, vuelve lo que escribiste.")
        boton(ata, "Mis atajos", sub = "Toca uno para cambiarlo o borrarlo") { verAtajos() }
        boton(ata, "Agregar un atajo") { editarAtajo(null) }

        val copia = seccion("Copia de seguridad", "Guarda en un archivo tus palabras, costumbres y atajos para pasarlos a otro celular o recuperarlos.")
        boton(copia, "Exportar lo aprendido") { exportarCerebro() }
        boton(copia, "Importar una copia", sub = "Se suma a lo que el teclado ya sabe") { importarCerebro() }

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

    // ---------- Copia de seguridad del cerebro ----------

    private val PEDIR_EXPORTAR = 41
    private val PEDIR_IMPORTAR = 42

    private fun exportarCerebro() = conMotor {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "tecladoios-cerebro.txt")
        try { startActivityForResult(i, PEDIR_EXPORTAR) } catch (e: Exception) { aviso("No se pudo abrir el selector de archivos.") }
    }

    private fun importarCerebro() = conMotor {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, PEDIR_IMPORTAR) } catch (e: Exception) { aviso("No se pudo abrir el selector de archivos.") }
    }

    private fun aviso(t: String) { AlertDialog.Builder(this).setMessage(t).setPositiveButton("OK", null).show() }

    @Deprecated("Activity sencilla, sin AndroidX")
    override fun onActivityResult(pedido: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(pedido, resultado, datos)
        val uri = datos?.data
        if (resultado != RESULT_OK || uri == null) return
        val m = Cerebro.motor ?: run { aviso("El diccionario aún no está listo. Inténtalo de nuevo."); return }
        when (pedido) {
            PEDIR_EXPORTAR -> try {
                contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write("#TecladoiOS-cerebro 1\n")
                    for ((k, v) in Atajos.leer(prefs)) w.write("A\t$k\t$v\n")
                    m.aprendido.escribir(w)
                }
                aviso("Listo: copia guardada.")
            } catch (e: Exception) { Registro.anotar("exportar", e); aviso("No se pudo guardar la copia.") }
            PEDIR_IMPORTAR -> try {
                val lineas = contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readLines() }
                if (lineas.firstOrNull()?.startsWith("#TecladoiOS-cerebro") != true && lineas.firstOrNull()?.startsWith("#TPA") != true) {
                    aviso("Ese archivo no es una copia del teclado."); return
                }
                val atajos = Atajos.leer(prefs)
                var n = 0
                for (l in lineas) {
                    if (l.startsWith("A\t")) {
                        val p = l.split('\t', limit = 3)
                        if (p.size == 3 && Atajos.claveValida(p[1]) && p[2].isNotBlank()) { atajos[p[1]] = p[2]; n++ }
                    } else if (m.aprendido.mezclar(l)) n++
                }
                Atajos.guardar(prefs, atajos)
                m.aprendido.guardar()
                aviso(if (n > 0) "Listo: copia importada." else "La copia estaba vacía.")
            } catch (e: Exception) { Registro.anotar("importar", e); aviso("No se pudo leer la copia.") }
        }
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
