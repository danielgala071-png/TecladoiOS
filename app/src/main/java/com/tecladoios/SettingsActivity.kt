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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ajustes con el mismo diseño y nombres que Ajustes > General > Teclado del iPhone. */
class SettingsActivity : Activity() {

    private val prefs by lazy { Ajustes.prefs(this) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var oscuro = false
    private val fondoPantalla get() = if (oscuro) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val fondoTarjeta get() = if (oscuro) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val colorTexto get() = if (oscuro) Color.WHITE else Color.BLACK
    private val colorSeparador get() = if (oscuro) 0xFF38383A.toInt() else 0xFFD1D1D6.toInt()
    private val gris = 0xFF8E8E93.toInt()
    private val azul = 0xFF007AFF.toInt()
    private val verde = 0xFF34C759.toInt()
    private val rojo = 0xFFFF3B30.toInt()

    private lateinit var contenedor: LinearLayout
    private var estado: TextView? = null
    private var pagina = "principal"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            construirPantalla()
        } catch (e: Throwable) {
            mostrarError(Log.getStackTraceString(e))
        }
    }

    // ---------- Pantalla de error ----------

    private fun mostrarError(texto: String) {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        caja.addView(TextView(this).apply {
            text = "Ocurrió un error. Cópialo y envíaselo a Claude:"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        caja.addView(android.widget.Button(this).apply {
            text = "Copiar error"
            isAllCaps = false
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("error", texto))
                Toast.makeText(this@SettingsActivity, "Error copiado", Toast.LENGTH_SHORT).show()
            }
        })
        caja.addView(android.widget.Button(this).apply {
            text = "Borrar y continuar"
            isAllCaps = false
            setOnClickListener {
                getSharedPreferences("tecladoios_error", MODE_PRIVATE).edit().remove("ultimo").apply()
                recreate()
            }
        })
        caja.addView(TextView(this).apply {
            text = texto
            textSize = 12f
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(caja)
        })
    }

    // ---------- Estructura ----------

    private fun construirPantalla() {
        val previo = getSharedPreferences("tecladoios_error", MODE_PRIVATE).getString("ultimo", null)
        if (previo != null) {
            mostrarError(previo)
            return
        }
        oscuro = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        actionBar?.hide()

        contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(40))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(fondoPantalla)
            addView(contenedor)
        }
        setContentView(scroll)
        dibujar()
        if (intent?.getBooleanExtra("pedir_microfono", false) == true) pedirMicrofono()
    }

    private fun microfonoPermitido() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun pedirMicrofono() {
        if (!microfonoPermitido()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (microfonoPermitido()) {
                Toast.makeText(this, "Listo, ya puedes dictar con el micrófono del teclado", Toast.LENGTH_SHORT).show()
            }
            if (::contenedor.isInitialized) dibujar()
        }
    }

    private fun dibujar() {
        contenedor.removeAllViews()
        if (pagina == "atajos") paginaAtajos() else paginaPrincipal()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pagina != "principal") {
            pagina = "principal"
            dibujar()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarEstado()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) actualizarEstado()
    }

    private fun actualizarEstado() {
        val e = estado ?: return
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            val activado = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
            val elegido = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith(packageName) == true
            e.text = when {
                elegido -> "TecladoiOS está activo y en uso."
                activado -> "Activado. Ahora toca “Elegir teclado”."
                else -> "Primero toca “Activar TecladoiOS” y enciéndelo."
            }
        } catch (_: Throwable) { }
    }

    // ---------- Piezas estilo iOS ----------

    private fun tituloGrande(texto: String) {
        contenedor.addView(TextView(this).apply {
            text = texto
            textSize = 34f
            Fuentes.aplicar(this, 700)
            setTextColor(colorTexto)
            setPadding(dp(4), dp(12), 0, dp(12))
        })
    }

    private fun grupo(titulo: String?, filas: List<View>, pie: String? = null): TextView? {
        if (titulo != null) {
            contenedor.addView(TextView(this).apply {
                text = titulo.uppercase()
                Fuentes.aplicar(this, 400)
                textSize = 13f
                setTextColor(gris)
                setPadding(dp(16), dp(22), dp(16), dp(7))
            })
        } else {
            contenedor.addView(View(this), LinearLayout.LayoutParams(1, dp(22)))
        }
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(fondoTarjeta)
            }
            clipToOutline = true
        }
        filas.forEachIndexed { i, fila ->
            if (i > 0) {
                tarjeta.addView(View(this).apply { setBackgroundColor(colorSeparador) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                        marginStart = dp(16)
                    })
            }
            tarjeta.addView(fila)
        }
        contenedor.addView(tarjeta)
        var piePagina: TextView? = null
        if (pie != null) {
            piePagina = TextView(this).apply {
                text = pie
                Fuentes.aplicar(this, 400)
                textSize = 13f
                setTextColor(gris)
                setPadding(dp(16), dp(7), dp(16), 0)
            }
            contenedor.addView(piePagina)
        }
        return piePagina
    }

    private fun fila(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50)
        setPadding(dp(16), dp(6), dp(16), dp(6))
    }

    private fun textoFila(texto: String, color: Int = colorTexto) = TextView(this).apply {
        text = texto
        Fuentes.aplicar(this, 400)
        textSize = 17f
        setTextColor(color)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    @Suppress("DEPRECATION")
    private fun filaInterruptor(titulo: String, clave: String, porDefecto: Boolean): View {
        val f = fila()
        f.addView(textoFila(titulo), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(clave, porDefecto)
            trackTintList = ColorStateList(estados, intArrayOf(verde, if (oscuro) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()))
            thumbTintList = ColorStateList(estados, intArrayOf(Color.WHITE, Color.WHITE))
            setOnCheckedChangeListener { _, activo -> prefs.edit().putBoolean(clave, activo).apply() }
        }
        f.addView(sw)
        f.setOnClickListener { sw.toggle() }
        return f
    }

    @Suppress("DEPRECATION")
    private fun filaInterruptorAccion(titulo: String, clave: String, porDefecto: Boolean, alCambiar: (Boolean) -> Unit): View {
        val f = fila()
        f.addView(textoFila(titulo), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(clave, porDefecto)
            trackTintList = ColorStateList(estados, intArrayOf(verde, if (oscuro) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()))
            thumbTintList = ColorStateList(estados, intArrayOf(Color.WHITE, Color.WHITE))
            setOnCheckedChangeListener { _, activo ->
                prefs.edit().putBoolean(clave, activo).apply()
                try { alCambiar(activo) } catch (_: Exception) { }
            }
        }
        f.addView(sw)
        f.setOnClickListener { sw.toggle() }
        return f
    }

    private fun filaAccion(titulo: String, color: Int = colorTexto, derecha: String? = "›", accion: () -> Unit): View {
        val f = fila()
        f.addView(textoFila(titulo, color), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (derecha != null) {
            f.addView(TextView(this).apply {
                text = derecha
                textSize = if (derecha == "›") 22f else 17f
                setTextColor(gris)
                maxLines = 1
            })
        }
        f.setOnClickListener { accion() }
        return f
    }

    private fun filaCheck(titulo: String, marcado: Boolean, accion: () -> Unit): View {
        val f = fila()
        f.addView(textoFila(titulo), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        f.addView(TextView(this).apply {
            text = if (marcado) "✓" else ""
            textSize = 19f
            setTextColor(azul)
            typeface = Typeface.DEFAULT_BOLD
        })
        f.setOnClickListener { accion() }
        return f
    }

    // ---------- Página principal ----------

    private fun paginaPrincipal() {
        tituloGrande("Teclado")

        estado = grupo("Teclados", listOf(
            filaAccion("Activar TecladoiOS") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            filaAccion("Elegir teclado") {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            },
        ), pie = "")
        actualizarEstado()

        val cantidad = Ajustes.atajos(this).size
        grupo(null, listOf(
            filaAccion("Sustitución de texto", derecha = "$cantidad  ›") {
                pagina = "atajos"
                dibujar()
            },
        ))

        grupo("Todos los teclados", listOf(
            filaInterruptor("Mayúsculas automáticas", "mayusculas", true),
            filaInterruptor("Autocorrección", "autocorregir", true),
            filaInterruptor("Activar Bloq Mayús", "bloq_mayus", true),
            filaInterruptor("Atajos", "atajos_activos", true),
            filaInterruptor("Predictivo", "sugerencias", true),
            filaInterruptor("Previsualización de caracteres", "vista_previa", true),
            filaInterruptor("Atajo con “.”", "punto_doble", true),
            filaInterruptor("Pegar lo copiado desde la barra", "portapapeles", true),
        ), pie = "Pulsar dos veces la barra espaciadora inserta un punto seguido de un espacio. " +
                "Mantén pulsada la barra espaciadora para mover el cursor. Lo que copias aparece unos " +
                "minutos en la barra: si tiene números, puedes pegarlo completo, solo el texto o solo los números.")

        grupo("Dictado", listOf(
            if (microfonoPermitido()) filaAccion("Micrófono permitido", derecha = "✓") {}
            else filaAccion("Permitir micrófono", azul) { pedirMicrofono() },
        ), pie = "Toca el micrófono abajo a la derecha del teclado para dictar.")

        grupo("Respuesta del teclado", listOf(
            filaInterruptor("Sonido", "sonido", false),
            filaInterruptor("Respuesta háptica", "vibrar", true),
        ))

        val vidrio = Ajustes.vidrio(this)
        grupo("Liquid Glass", listOf(
            filaCheck("Transparente", vidrio == "transparente") { elegir("vidrio", "transparente") },
            filaCheck("Predeterminado", vidrio == "normal") { elegir("vidrio", "normal") },
            filaCheck("Sólido (como iPhone)", vidrio == "tintado") { elegir("vidrio", "tintado") },
            filaCheck("De color (según la app)", vidrio == "color") { elegir("vidrio", "color") },
            filaInterruptor("Desenfocar el fondo", "desenfoque", true),
            filaInterruptor("Combinar con la app", "adaptar", true),
            filaInterruptor("Teclas de función con color", "teclas_color", true),
        ), pie = "“Sólido” es el diseño del teclado del iPhone. “Combinar con la app” toma el color " +
                "de la app que estás usando (verde en WhatsApp, rosado en Instagram…) para la tecla de " +
                "acción y un toque sutil del fondo. “Teclas de función con color” tiñe mayúsculas, borrar, " +
                "123 y enter con un vidrio translúcido de ese color. “De color” tiñe todo el vidrio con el color de la app, " +
                "difuminado de arriba hacia abajo; si la app no tiene color, usa el de tu fondo de pantalla.")

        val tema = Ajustes.tema(this)
        grupo("Apariencia", listOf(
            filaCheck("Automático", tema == "auto") { elegir("tema", "auto") },
            filaCheck("Claro", tema == "claro") { elegir("tema", "claro") },
            filaCheck("Oscuro", tema == "oscuro") { elegir("tema", "oscuro") },
            filaCheck("AMOLED (negro puro)", tema == "amoled") { elegir("tema", "amoled") },
        ), pie = "AMOLED usa negro puro, como el teclado oscuro del iPhone, y ahorra batería.")

        grupo("Tamaño", listOf(
            filaDeslizador("Altura de las teclas", "altura"),
            filaDeslizador("Tamaño de las letras", "letra"),
        ), pie = "Los cambios se ven la próxima vez que abras el teclado.")

        grupo("App", listOf(
            filaInterruptorAccion("Ocultar ícono de la app", "ocultar_icono", false) { ocultar ->
                packageManager.setComponentEnabledSetting(
                    ComponentName(this, "com.tecladoios.Lanzador"),
                    if (ocultar) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                if (ocultar) Toast.makeText(this,
                    "Ícono oculto. Para volver aquí: Ajustes del celular → Teclado → TecladoiOS",
                    Toast.LENGTH_LONG).show()
            },
        ), pie = "Para abrir estos ajustes con el ícono oculto: Ajustes del celular → Idioma y entrada " +
                "(o Teclado) → TecladoiOS. Algunos celulares muestran igual un acceso a la información de la app.")

        grupo(null, listOf(
            filaAccion("Restablecer diccionario del teclado", rojo, derecha = null) {
                AlertDialog.Builder(this)
                    .setMessage("Se borrarán todas las palabras que el teclado aprendió.")
                    .setPositiveButton("Restablecer diccionario") { _, _ ->
                        prefs.edit()
                            .remove("aprendidas")
                            .remove("pares")
                            .remove("trios")
                            .remove("rechazos")
                            .remove("bloqueadas")
                            .remove("nombres")
                            .putInt("aprendidas_rev", prefs.getInt("aprendidas_rev", 0) + 1)
                            .apply()
                        Toast.makeText(this, "Diccionario restablecido", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
        ))

        val prueba = EditText(this).apply {
            hint = "Escribe algo para probar…"
            minLines = 3
            gravity = Gravity.TOP
            background = null
            setTextColor(colorTexto)
            setHintTextColor(gris)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        grupo("Prueba", listOf(prueba))

        // Número de versión: sirve para comprobar que se instaló la última compilación
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        contenedor.addView(TextView(this).apply {
            text = "TecladoiOS versión $version · escritura rápida v5"
            Fuentes.aplicar(this, 400)
            textSize = 13f
            setTextColor(gris)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
    }

    /** Fila con barra deslizante de 80% a 130% */
    private fun filaDeslizador(titulo: String, clave: String): View {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val arriba = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val valor = TextView(this).apply {
            textSize = 17f
            setTextColor(gris)
        }
        arriba.addView(textoFila(titulo), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(valor)
        val actual = prefs.getInt(clave, 100).coerceIn(80, 130)
        valor.text = "$actual%"
        val barra = SeekBar(this).apply {
            max = 50
            progress = actual - 80
            progressTintList = ColorStateList.valueOf(azul)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, deUsuario: Boolean) {
                    valor.text = "${p + 80}%"
                    if (deUsuario) prefs.edit().putInt(clave, p + 80).apply()
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        caja.addView(arriba)
        caja.addView(barra)
        return caja
    }

    private fun elegir(clave: String, valor: String) {
        prefs.edit().putString(clave, valor).apply()
        dibujar()
    }

    // ---------- Sustitución de texto ----------

    private fun paginaAtajos() {
        estado = null
        contenedor.addView(TextView(this).apply {
            text = "‹ Teclado"
            textSize = 17f
            setTextColor(azul)
            setPadding(dp(4), dp(10), dp(4), dp(4))
            setOnClickListener {
                pagina = "principal"
                dibujar()
            }
        })
        tituloGrande("Sustitución de texto")

        val lista = Ajustes.atajos(this)
        val filas = ArrayList<View>()
        lista.forEachIndexed { i, (atajo, frase) ->
            filas.add(filaAccion(frase, derecha = atajo) { editarAtajo(i) })
        }
        filas.add(filaAccion("Nueva sustitución", azul, derecha = "+") { editarAtajo(-1) })
        grupo(null, filas, pie = "Escribe la función rápida y se reemplaza por la frase al poner espacio o un signo. " +
                "Puede tener letras, números o símbolos. Si borras justo después, vuelve lo que escribiste.")

        grupo(null, listOf(
            filaAccion("Agregar sustituciones sugeridas", azul, derecha = null) {
                val actuales = Ajustes.atajos(this)
                val claves = actuales.map { it.first.lowercase() }.toSet()
                val nuevas = Ajustes.ATAJOS_SUGERIDOS.filter { it.first.lowercase() !in claves }
                Ajustes.guardarAtajos(this, actuales + nuevas)
                Toast.makeText(this, "Se agregaron ${nuevas.size} sustituciones", Toast.LENGTH_SHORT).show()
                dibujar()
            },
        ), pie = "Agrega atajos comunes como tqm, ntp, xfa, dnd, cdt…")
    }

    private fun editarAtajo(indice: Int) {
        val lista = Ajustes.atajos(this)
        val actual = lista.getOrNull(indice)
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val frase = EditText(this).apply {
            hint = "Frase"
            setText(actual?.second ?: "")
        }
        val atajo = EditText(this).apply {
            hint = "Función rápida"
            isSingleLine = true
            setText(actual?.first ?: "")
        }
        caja.addView(frase)
        caja.addView(atajo)

        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (actual == null) "Nueva sustitución" else "Editar sustitución")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val f = frase.text.toString().trim()
                val a = atajo.text.toString().trim()
                if (f.isNotEmpty() && a.isNotEmpty() && !a.contains(' ')) {
                    // Si ya existe esa función rápida, se reemplaza en vez de duplicarse
                    val repetido = lista.indexOfFirst { it.first.equals(a, ignoreCase = true) }
                    when {
                        actual != null -> lista[indice] = a to f
                        repetido >= 0 -> lista[repetido] = a to f
                        else -> lista.add(a to f)
                    }
                    Ajustes.guardarAtajos(this, lista)
                    dibujar()
                } else {
                    Toast.makeText(this, "La función rápida debe ser una sola palabra", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
        if (actual != null) {
            dialogo.setNeutralButton("Eliminar") { _, _ ->
                lista.removeAt(indice)
                Ajustes.guardarAtajos(this, lista)
                dibujar()
            }
        }
        dialogo.show()
    }
}
