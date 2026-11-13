package com.tecladoios

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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ajustes con el mismo diseño, orden y nombres que Ajustes > General > Teclado del iPhone. */
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

    // ---------- Ocultar aplicación ----------

    private val lanzador get() = ComponentName(this, "com.tecladoios.Lanzador")

    private fun iconoOculto(): Boolean = try {
        packageManager.getComponentEnabledSetting(lanzador) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { false }

    /** Quita o vuelve a poner el ícono de TecladoiOS en el menú de apps */
    private fun ocultarIcono(ocultar: Boolean) {
        packageManager.setComponentEnabledSetting(
            lanzador,
            if (ocultar) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        Toast.makeText(
            this,
            if (ocultar) "Ícono oculto. Puede tardar unos segundos en desaparecer."
            else "El ícono vuelve a aparecer en el menú de apps.",
            Toast.LENGTH_SHORT,
        ).show()
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

    /** Los errores que el teclado anotó, para copiarlos y enviarlos */
    private fun verRegistro() {
        val texto = Registro.leer(this).ifBlank { "No hay errores anotados." }
        AlertDialog.Builder(this)
            .setTitle("Registro de errores")
            .setMessage(texto)
            .setPositiveButton("Copiar") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("errores", texto))
                Toast.makeText(this, "Registro copiado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Borrar") { _, _ ->
                Registro.borrar(this)
                recreate()
            }
            .setNeutralButton("Cerrar", null)
            .show()
    }

    // ---------- Estructura ----------

    private fun construirPantalla() {
        val previo = getSharedPreferences("tecladoios_error", MODE_PRIVATE).getString("ultimo", null)
        if (previo != null) {
            mostrarError(previo)
            return
        }
        // Todo negro AMOLED, igual que el teclado
        oscuro = true
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
    }

    private fun irA(p: String) {
        pagina = p
        dibujar()
    }

    private fun dibujar() {
        contenedor.removeAllViews()
        estado = null
        when (pagina) {
            "atajos" -> paginaAtajos()
            "teclados" -> paginaTeclados()
            "una_mano" -> paginaUnaMano()
            "palabras" -> paginaPalabras()
            "gestos" -> paginaGestos()
            "vibracion" -> paginaVibracion()
            "emojis" -> paginaEmojis()
            "borde" -> paginaBorde()
            else -> paginaPrincipal()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pagina != "principal") {
            irA("principal")
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Al volver de un permiso del sistema, la página se vuelve a dibujar con el estado nuevo */
    private var redibujarAlVolver = false

    override fun onResume() {
        super.onResume()
        actualizarEstado()
        if (redibujarAlVolver) {
            redibujarAlVolver = false
            dibujar()
        }
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

    /** Enlace "‹ Teclado" para volver, como en iPhone */
    private fun volver(destino: String = "principal", texto: String = "Teclado") {
        contenedor.addView(TextView(this).apply {
            text = "‹ $texto"
            textSize = 17f
            setTextColor(azul)
            setPadding(dp(4), dp(10), dp(4), dp(4))
            setOnClickListener { irA(destino) }
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
    private fun filaInterruptor(titulo: String, clave: String, porDefecto: Boolean, alCambiar: (Boolean) -> Unit = {}): View {
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

    /** Deslizador del tamaño del teclado, con una muestra de cómo se ven las letras */
    private fun filaTamano(): View {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valor = TextView(this).apply {
            textSize = 17f
            setTextColor(gris)
            Fuentes.aplicar(this, 400)
        }
        arriba.addView(textoFila("Tamaño"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(valor)
        caja.addView(arriba)

        // Muestra: una tecla negra con el filo de color y la letra al tamaño elegido
        val muestra = TextView(this).apply {
            text = "A  s  d  ñ"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            Fuentes.aplicar(this, 400)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.BLACK)
                setStroke(maxOf(1, dp(1)), azul)
            }
        }
        caja.addView(muestra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        val paso = 5
        val barraTam = android.widget.SeekBar(this).apply {
            max = (Ajustes.TAMANO_MAX - Ajustes.TAMANO_MIN) / paso
            progress = (Ajustes.tamano(this@SettingsActivity) - Ajustes.TAMANO_MIN) / paso
            progressTintList = ColorStateList.valueOf(azul)
            thumbTintList = ColorStateList.valueOf(azul)
        }
        fun pintar(porcentaje: Int) {
            valor.text = "$porcentaje %"
            val f = porcentaje / 100f
            muestra.textSize = 23f * f
            val alto = (54 * f).toInt()
            muestra.minHeight = dp(alto)
            muestra.setPadding(0, dp(4), 0, dp(4))
        }
        pintar(Ajustes.tamano(this))
        barraTam.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progreso: Int, delUsuario: Boolean) {
                val porcentaje = Ajustes.TAMANO_MIN + progreso * paso
                pintar(porcentaje)
                if (delUsuario) prefs.edit().putInt("tamano", porcentaje).apply()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        caja.addView(barraTam, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        val pie = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pie.addView(TextView(this).apply {
            text = "Más chico"
            textSize = 13f
            setTextColor(gris)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pie.addView(TextView(this).apply {
            text = "Normal"
            textSize = 13f
            setTextColor(azul)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                barraTam.progress = (100 - Ajustes.TAMANO_MIN) / paso
                prefs.edit().putInt("tamano", 100).apply()
            }
        })
        pie.addView(TextView(this).apply {
            text = "Más grande"
            textSize = 13f
            setTextColor(gris)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        caja.addView(pie)
        return caja
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

    private fun elegir(clave: String, valor: String) {
        prefs.edit().putString(clave, valor).apply()
        dibujar()
    }

    // ---------- Página principal: General > Teclado ----------

    private fun paginaPrincipal() {
        tituloGrande("Teclado")

        grupo(null, listOf(
            filaAccion("Teclados", derecha = "2  ›") { irA("teclados") },
        ))

        val mano = when (Ajustes.unaMano(this)) {
            "izq" -> "Izquierda"
            "der" -> "Derecha"
            else -> "No"
        }
        val cantidad = Ajustes.atajos(this).size
        grupo(null, listOf(
            filaAccion("Teclado con una mano", derecha = "$mano  ›") { irA("una_mano") },
            filaAccion("Sustitución de texto", derecha = "$cantidad  ›") { irA("atajos") },
        ))

        grupo("Todos los teclados", listOf(
            filaInterruptor("Mayúsculas automáticas", "mayusculas", true),
            filaInterruptor("Autocorrección", "autocorregir", true),
            filaInterruptor("Separar palabras pegadas al escribir", "separar_vivo", true),
            filaInterruptor("Revisar ortografía", "ortografia", true),
            filaInterruptor("Activar Bloq Mayús", "bloq_mayus", true),
            filaInterruptor("Atajos", "atajos_activos", true),
            filaInterruptor("Texto predictivo", "sugerencias", true),
            filaInterruptor("Mostrar predicciones en línea", "en_linea", false),
            filaInterruptor("Puntuación inteligente", "puntuacion", true),
            filaInterruptor("Signos de apertura ¿ ¡", "apertura", true),
            filaInterruptor("Previsualización de caracteres", "vista_previa", true),
            filaInterruptor("Atajo con “.”", "punto_doble", true),
            filaInterruptor("Fila de números", "fila_numeros", false),
            filaInterruptor("Ícono de la app en la barra espaciadora", "icono_espacio", true),
        ), pie = "Pulsar dos veces la barra espaciadora insertará un punto seguido de un espacio. " +
                "Con “Signos de apertura”, al terminar con ? o ! se pone ¿ o ¡ donde empieza la " +
                "pregunta (también después de una coma: “Hola, ¿cómo estás?”). “Separar palabras " +
                "pegadas”: si se te pasa el espacio o cae una letra en su lugar (“Yanpodemos”), el " +
                "teclado lo separa solo mientras escribes (“Ya podemos”). Si no era eso, borra una " +
                "vez y vuelve como lo escribiste; el teclado aprende y no lo vuelve a separar.")

        grupo("Portapapeles", listOf(
            filaInterruptor("Mostrar lo copiado", "portapapeles", true),
            filaInterruptor("Borrar contraseñas copiadas", "limpiar_claves", true),
            filaInterruptor("Historial del portapapeles", "historial_portapapeles", true) { activo ->
                if (!activo) prefs.edit().remove("historial_copiado").apply()
            },
        ), pie = "Lo que copias aparece unos minutos en la barra de sugerencias para pegarlo con un " +
                "toque. Mantenlo presionado para quitarlo. Las contraseñas se muestran con puntos y, " +
                "si las pegas con el teclado, se borran solas del portapapeles al minuto. El historial " +
                "guarda lo último que copiaste (botón 📋 en el panel de emojis); las contraseñas nunca se guardan.")

        val sinAhorro = try {
            getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Exception) { false }
        grupo("Rendimiento", listOf(
            filaAccion("Que Android no cierre el teclado", derecha = if (sinAhorro) "Listo" else "›") {
                if (!sinAhorro) try {
                    redibujarAlVolver = true
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
                }
            },
        ), pie = "El ahorro de batería de algunos celulares (Xiaomi, Samsung, Huawei, Oppo) cierra " +
                "el teclado cuando abres apps pesadas, y luego tarda en volver a aparecer. Al " +
                "permitirlo, Android lo deja abierto. El teclado casi no gasta batería en reposo.")

        grupo("Idiomas", listOf(
            filaInterruptor("Inglés", "idioma_en", true),
            filaInterruptor("Portugués", "idioma_pt", false),
        ), pie = "Escribe en dos o tres idiomas a la vez sin cambiar nada: el teclado nota en qué " +
                "idioma va cada frase. No corrige las palabras de otro idioma activado, corrige los " +
                "dedazos en el idioma de la frase (\u201Cteh\u201D → \u201Cthe\u201D, \u201Cdont\u201D → " +
                "\u201Cdon\u2019t\u201D) y te sugiere palabras de los idiomas que vas usando. El español " +
                "siempre está activo.")

        grupo("Escáner de texto", listOf(
            filaInterruptor("Botón Escanear en la barra", "escaner_barra", true),
        ), pie = "Con el cuadro de texto vacío, toca “Escanear” en la barra (o en la barra de " +
                "edición): se abre la cámara, fotografías un documento o cartel y el texto aparece " +
                "listo para revisar y poner en el chat. Funciona sin internet; la foto se borra al " +
                "leerla.")

        val conPermiso = Capturas.tienePermiso(this)
        grupo("Capturas de pantalla", listOfNotNull(
            filaInterruptor("Capturas en la barra", "capturas", true) { activo ->
                if (activo && !Capturas.tienePermiso(this)) requestPermissions(arrayOf(Capturas.permiso()), PEDIR_CAPTURAS)
            },
            if (!conPermiso && Ajustes.capturas(this)) filaAccion("Permitir ver las capturas", azul, derecha = null) {
                requestPermissions(arrayOf(Capturas.permiso()), PEDIR_CAPTURAS)
            } else null,
        ), pie = "Al sacar una captura, aparece unos minutos en la barra de sugerencias: tócala y se " +
                "envía en el chat donde estás escribiendo. Mantenla presionada para quitarla. El " +
                "teclado solo mira las capturas de los últimos minutos (nunca tu galería). Al pedir " +
                "el permiso, elige “Permitir todo”.")

        grupo("Contraseñas", listOf(
            filaInterruptor("Recordar contraseñas", "recordar_claves", true),
            filaAccion("Borrar contraseñas guardadas", rojo, derecha = null) {
                val n = Claves.cuantasApps(this)
                AlertDialog.Builder(this)
                    .setMessage(if (n == 0) "No hay contraseñas guardadas." else
                        "Se borrarán las contraseñas guardadas de $n " + (if (n == 1) "app." else "apps."))
                    .setPositiveButton(if (n == 0) "OK" else "Borrar") { _, _ ->
                        if (n > 0) {
                            Claves.borrarTodo(this)
                            Toast.makeText(this, "Contraseñas borradas", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
        ), pie = "En un campo de contraseña, toca “Guardar contraseña” en la barra. La próxima vez " +
                "aparece arriba con el nombre de tu cuenta: tócala y se escribe sola. Se guardan solo " +
                "en este celular, cifradas con la llave de seguridad de Android (nunca salen del " +
                "teclado ni van a internet). Mantén presionada una para olvidarla.")

        val fuerza = when (Ajustes.vibracion(this)) { "suave" -> "Suave"; "fuerte" -> "Fuerte"; else -> "Media" }
        grupo("Respuesta del teclado", listOf(
            filaInterruptor("Sonido", "sonido", false),
            filaInterruptor("Háptica", "vibrar", true),
            filaAccion("Intensidad de la háptica", derecha = "$fuerza  ›") { irA("vibracion") },
        ), pie = "El sonido es un clic corto como el del iPhone (uno para letras, otro para borrar, " +
                "espacio e intro). Con el celular en silencio o vibración, el teclado no suena.")

        grupo("Escritura", listOf(
            filaInterruptor("Modo formal automático", "modo_formal", true),
        ), pie = "La barra de sugerencias queda siempre a la vista. “Modo formal”: en el " +
                "correo y en apps de trabajo, el teclado propone saludos formales y casi no sugiere jerga.")

        val borde = when (Ajustes.bordeTeclas(this)) {
            "apagado" -> "Apagado"; "sutil" -> "Sutil"; "vivo" -> "Vivo"; else -> "Normal"
        }
        grupo("Diseño", listOf(
            filaAccion("Filo de color de las teclas", derecha = "$borde  ›") { irA("borde") },
        ), pie = "El filo de cada tecla toma el color de la app donde escribes (verde en WhatsApp, " +
                "rosado en Instagram…). Al presionar una tecla, su filo se enciende un instante.")

        val filasEmoji = Ajustes.filasEmoji(this)
        grupo("Emojis", listOf(
            filaAccion("Tamaño de los emojis", derecha = "${nombreFilas(filasEmoji)}  ›") { irA("emojis") },
        ))

        grupo("Tamaño del teclado", listOf(filaTamano()),
            pie = "Hace las teclas más altas o más bajas. Las letras se agrandan o achican junto con el " +
                    "teclado. El cambio se ve la próxima vez que abras el teclado.")

        // El interruptor siempre muestra el estado real del ícono
        prefs.edit().putBoolean("ocultar_app", iconoOculto()).apply()
        grupo("Aplicación", listOf(
            filaInterruptor("Ocultar aplicación", "ocultar_app", false) { ocultarIcono(it) },
        ), pie = "Quita el ícono de TecladoiOS del menú de apps. El teclado sigue funcionando. Para " +
                "volver a estos ajustes, entra a los ajustes del teléfono, busca la sección de " +
                "teclados y toca TecladoiOS.")

        grupo(null, listOf(
            filaAccion("Gestos y trucos") { irA("gestos") },
        ))

        // Errores que el teclado anotó sin cerrarse (solo aparece si hay alguno)
        if (Registro.leer(this).isNotBlank()) {
            grupo(null, listOf(
                filaAccion("Registro de errores") { verRegistro() },
            ))
        }

        grupo("Diccionario", listOf(
            filaInterruptor("Nombres de tus contactos", "contactos", false) { activo ->
                if (activo && checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                    PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), PEDIR_CONTACTOS)
            },
            filaAccion("Palabras aprendidas") { irA("palabras") },
            filaAccion("Exportar copia de seguridad", azul, derecha = null) { exportar() },
            filaAccion("Importar copia de seguridad", azul, derecha = null) { importar() },
        ), pie = "La copia guarda lo que el teclado aprendió de ti, tus sustituciones de texto, tus " +
                "emojis recientes y los tonos de piel. Guárdala antes de desinstalar o para pasarla a " +
                "otro celular.")

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
                        Diccionario.borrarDatos(this)
                        Toast.makeText(this, "Diccionario restablecido", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            filaAccion("Restablecer ajuste de toque", rojo, derecha = null) {
                AlertDialog.Builder(this)
                    .setMessage("El teclado olvidará cómo tocas las teclas y volverá a aprenderlo desde cero.")
                    .setPositiveButton("Restablecer") { _, _ ->
                        ModeloToque.restablecer(this)
                        Toast.makeText(this, "Ajuste de toque restablecido", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
        ), pie = "El teclado aprende solo cómo tocas cada letra (un poco más abajo, más a un lado) " +
                "y ajusta las teclas a tu dedo para que se confundan menos.")

        // Número de versión: sirve para comprobar que se instaló la última compilación
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        contenedor.addView(TextView(this).apply {
            text = "TecladoiOS versión $version · AMOLED v11"
            Fuentes.aplicar(this, 400)
            textSize = 13f
            setTextColor(gris)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
    }

    // ---------- Palabras aprendidas ----------

    private fun paginaPalabras() {
        volver()
        tituloGrande("Palabras aprendidas")
        // Lo que aprendió el cerebro por su cuenta (frases, errores tuyos y cómo se calibró)
        val resumen = try { Cerebro.resumen(Diccionario.leerCerebro(this)) } catch (_: Throwable) { null }
        if (resumen != null) {
            grupo("Cerebro", listOf(filaInfo("Lo que aprendió de ti", resumen)),
                pie = "Aprende solo mientras escribes: si borras una palabra y la escribes bien, " +
                    "la próxima vez la corrige sola. Todo queda en tu celular, sin internet.")
        }
        val todas = try { Diccionario.palabrasAprendidas(this) } catch (_: Throwable) { emptyList() }
        if (todas.isEmpty()) {
            grupo(null, listOf(filaAccion("Todavía no aprendió palabras", gris, derecha = null) {}),
                pie = "A medida que escribes, el teclado aprende las palabras que usas y cómo las usas.")
            return
        }
        // Las más usadas primero (mostrar miles de filas haría lenta la pantalla)
        val maximo = 300
        val filas = todas.take(maximo).map { (palabra, veces) ->
            filaAccion(palabra, derecha = "$veces") { confirmarOlvido(palabra) }
        }
        grupo(null, filas, pie = "Toca una palabra para que el teclado la olvide: no la volverá a " +
                "sugerir ni a usar para corregir. " +
                (if (todas.size > maximo) "Se muestran las $maximo más usadas de ${todas.size}." else "${todas.size} palabras."))
    }

    private fun confirmarOlvido(palabra: String) {
        AlertDialog.Builder(this)
            .setMessage("¿Olvidar “$palabra”? El teclado no la volverá a sugerir.")
            .setPositiveButton("Olvidar") { _, _ ->
                try { Diccionario.olvidarPalabra(this, palabra) } catch (_: Throwable) { }
                Toast.makeText(this, "“$palabra” olvidada", Toast.LENGTH_SHORT).show()
                dibujar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Gestos y trucos ----------

    private fun filaInfo(titulo: String, detalle: String): View {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        caja.addView(textoFila(titulo))
        caja.addView(TextView(this).apply {
            text = detalle
            Fuentes.aplicar(this, 400)
            textSize = 14f
            setTextColor(gris)
            setPadding(0, dp(2), 0, 0)
        })
        return caja
    }

    private fun paginaGestos() {
        volver()
        tituloGrande("Gestos y trucos")
        grupo("Escribir", listOf(
            filaInfo("Mover el cursor", "Mantén presionada la barra espaciadora y desliza: a los lados mueve el cursor letra por letra; arriba y abajo cambia de línea en textos largos."),
            filaInfo("Barra de edición", "Al mover el cursor con la barra espaciadora, o al marcar texto, la barra muestra Seleccionar todo, Seleccionar, Cortar, Copiar y Pegar. Con Seleccionar encendido, mover el cursor va marcando el texto. Al volver a escribir, regresan las sugerencias."),
            filaInfo("Ir a los ajustes", "Mantén presionada la tecla de emojis."),
            filaInfo("Acentos y ñ", "Mantén presionada una letra (a, e, n…) o un signo (? ! $ \" -) para ver sus variantes."),
            filaInfo("Bloq Mayús", "Toca dos veces la tecla de mayúsculas."),
            filaInfo("Escanear texto", "Con el cuadro vacío, toca Escanear en la barra y fotografía un documento o cartel: el texto se pone en el chat."),
            filaInfo("Letra firme", "Escribiendo rápido o con dos dedos, sale la letra donde cayó tu dedo aunque lo barras al soltar."),
            filaInfo("Punto rápido", "Toca dos veces la barra espaciadora para poner punto y espacio."),
        ))
        grupo("Barra de sugerencias", listOf(
            filaInfo("Tildes que cambian el sentido", "Al escribir tu, el, si, mas, que, como, esta… la otra forma (tú, él, sí, más, qué, cómo, está) aparece a la derecha."),
            filaInfo("Sugerencias en cadena", "Después de tocar una sugerencia, la barra ya propone la siguiente palabra: puedes armar frases tocando solo la barra."),
            filaInfo("Siglas y nombres", "SUNAT, EsSalud, WhatsApp o Cusco se escriben bien aunque los pongas en minúsculas."),
        ))
        grupo("Borrar", listOf(
            filaInfo("Borrar rápido", "Mantén presionada la tecla borrar: primero va letra por letra y luego palabra por palabra."),
            filaInfo("Deshacer la autocorrección", "Si el teclado cambió una palabra que no querías, toca borrar justo después y vuelve lo que escribiste."),
            filaInfo("Olvidar una palabra", "Mantén presionada una sugerencia y toca Borrar."),
        ))
        grupo("Emojis", listOf(
            filaInfo("Tonos de piel", "En el panel de emojis, mantén presionado un emoji de manos o personas y elige el tono. Se recuerda para la próxima vez."),
        ))
        grupo("Portapapeles", listOf(
            filaInfo("Pegar con un toque", "Lo que copias aparece en la barra al abrir el teclado. Mantenlo presionado para quitarlo."),
        ))
    }

    // ---------- Copia de seguridad ----------

    private fun exportar() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "TecladoiOS-respaldo.txt")
        }
        try {
            startActivityForResult(intent, PEDIDO_EXPORTAR)
        } catch (_: Exception) {
            Toast.makeText(this, "No se encontró dónde guardar el archivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importar() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, PEDIDO_IMPORTAR)
        } catch (_: Exception) {
            Toast.makeText(this, "No se encontró el selector de archivos", Toast.LENGTH_SHORT).show()
        }
    }

    /** Todo en un solo archivo de texto: encabezado, sustituciones, recientes, tonos y lo aprendido */
    private fun armarRespaldo(): String {
        val sb = StringBuilder()
        sb.append(RESPALDO).append('\n')
        sb.append("#atajos\n").append(prefs.getString("atajos", "") ?: "").append('\n')
        sb.append("#recientes\n").append(prefs.getString("recientes", "") ?: "").append('\n')
        sb.append("#tonos\n").append(prefs.getString("tonos", "") ?: "").append('\n')
        // El cerebro va antes de lo aprendido (lo aprendido siempre es lo último del archivo)
        Diccionario.leerCerebro(this)?.let { sb.append("#cerebro\n").append(it.trimEnd()).append('\n') }
        sb.append("#aprendido\n").append(Diccionario.leerDatos(this) ?: "")
        return sb.toString()
    }

    private fun aplicarRespaldo(texto: String): Boolean {
        val lineas = texto.replace("\r\n", "\n").split('\n')
        if (lineas.firstOrNull()?.trim() != RESPALDO) return false
        fun valor(clave: String): String? {
            val i = lineas.indexOf(clave)
            return if (i >= 0 && i + 1 < lineas.size) lineas[i + 1] else null
        }
        val editor = prefs.edit()
        valor("#atajos")?.takeIf { it.isNotBlank() }?.let { json ->
            // Solo si se puede leer (un archivo dañado no borra tus sustituciones)
            try { org.json.JSONArray(json); editor.putString("atajos", json).putBoolean("atajos_v5", true) } catch (_: Exception) { }
        }
        valor("#recientes")?.takeIf { it.isNotBlank() }?.let { editor.putString("recientes", it) }
        valor("#tonos")?.let { editor.putString("tonos", it) }
        editor.commit()
        val i = lineas.indexOf("#aprendido")
        val ic = lineas.indexOf("#cerebro")
        if (ic >= 0 && i > ic) {
            val cerebro = lineas.subList(ic + 1, i).joinToString("\n")
            try { Diccionario.escribirCerebro(this, cerebro) } catch (e: Exception) { Registro.anotar("importar el cerebro", e) }
        }
        if (i >= 0) {
            val aprendido = lineas.drop(i + 1).joinToString("\n")
            if (Diccionario.datosValidos(aprendido)) Diccionario.escribirDatos(this, aprendido)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PEDIR_CAPTURAS) {
            if (!Capturas.tienePermiso(this)) {
                Toast.makeText(this, "Sin permiso, el teclado no puede mostrar tus capturas", Toast.LENGTH_SHORT).show()
            }
            dibujar()
            return
        }
        if (requestCode != PEDIR_CONTACTOS) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            // Sin permiso no se puede: el interruptor vuelve a apagado
            prefs.edit().putBoolean("contactos", false).apply()
            Toast.makeText(this, "Sin permiso, el teclado no puede leer los nombres", Toast.LENGTH_SHORT).show()
        }
        dibujar()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            PEDIDO_EXPORTAR -> {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(armarRespaldo().toByteArray(Charsets.UTF_8)) } != null
                } catch (_: Exception) { false }
                Toast.makeText(this, if (ok) "Copia de seguridad guardada" else "No se pudo guardar la copia",
                    Toast.LENGTH_SHORT).show()
            }
            PEDIDO_IMPORTAR -> {
                val ok = try {
                    val texto = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    texto != null && aplicarRespaldo(texto)
                } catch (_: Exception) { false }
                Toast.makeText(this,
                    if (ok) "Copia importada. El teclado ya usa tus palabras." else "Ese archivo no es una copia de TecladoiOS",
                    Toast.LENGTH_LONG).show()
                if (ok) dibujar()
            }
        }
    }

    private companion object {
        const val PEDIDO_EXPORTAR = 41
        const val PEDIDO_IMPORTAR = 42
        const val PEDIR_CONTACTOS = 43
        const val PEDIR_CAPTURAS = 44
        const val RESPALDO = "tecladoios-respaldo-1"
    }

    // ---------- Teclados ----------

    private fun paginaTeclados() {
        volver()
        tituloGrande("Teclados")
        grupo(null, listOf(
            filaAccion("Español (Perú)", derecha = "Español") {},
            filaAccion("Emoji", derecha = null) {},
        ))
        estado = grupo(null, listOf(
            filaAccion("Activar TecladoiOS", azul, derecha = null) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            filaAccion("Elegir teclado", azul, derecha = null) {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            },
        ), pie = "")
        actualizarEstado()
    }

    // ---------- Intensidad de la háptica ----------

    private fun paginaVibracion() {
        volver()
        tituloGrande("Intensidad de la háptica")
        val actual = Ajustes.vibracion(this)
        grupo(null, listOf(
            filaCheck("Suave", actual == "suave") { elegir("vibracion", "suave"); probarVibracion("suave") },
            filaCheck("Media", actual == "media") { elegir("vibracion", "media"); probarVibracion("media") },
            filaCheck("Fuerte", actual == "fuerte") { elegir("vibracion", "fuerte"); probarVibracion("fuerte") },
        ), pie = "Cada tecla es un golpe seco y firme (no un zumbido). El espacio, el intro y la " +
                "mayúscula se sienten más pesados que las letras. Al tocar una opción la sientes al " +
                "instante: dos letras y un espacio.")
    }

    /** Prueba en vivo: "tac, tac, TAC" (dos letras y un espacio) con la fuerza elegida */
    private fun probarVibracion(nivel: String) {
        try {
            // (Entre paréntesis: así el "?: return" vale para los dos casos, no solo para el else)
            val v: android.os.Vibrator = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                getSystemService(android.os.Vibrator::class.java)
            }) ?: return
            val (letra, pesada) = when (nivel) {
                "suave" -> 17L to 26L
                "fuerte" -> 27L to 40L
                else -> 22L to 33L
            }
            val fuerza = if (v.hasAmplitudeControl()) 255 else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(android.os.VibrationEffect.createWaveform(
                longArrayOf(0, letra, 140, letra, 140, pesada),
                intArrayOf(0, fuerza, 0, fuerza, 0, fuerza), -1))
        } catch (_: Exception) { }
    }

    // ---------- Filo de color de las teclas ----------

    private fun paginaBorde() {
        volver()
        tituloGrande("Filo de color")
        val actual = Ajustes.bordeTeclas(this)
        grupo(null, listOf(
            filaCheck("Apagado", actual == "apagado") { elegir("borde_teclas", "apagado") },
            filaCheck("Sutil", actual == "sutil") { elegir("borde_teclas", "sutil") },
            filaCheck("Normal", actual == "normal") { elegir("borde_teclas", "normal") },
            filaCheck("Vivo", actual == "vivo") { elegir("borde_teclas", "vivo") },
        ), pie = "Qué tanto se nota el color de la app en el borde de cada tecla. El cambio se ve " +
                "la próxima vez que abras el teclado.")
    }

    // ---------- Tamaño de los emojis ----------

    private fun nombreFilas(n: Int) = when (n) { 3 -> "Grandes"; 5 -> "Pequeños"; else -> "Normal" }

    private fun paginaEmojis() {
        volver()
        tituloGrande("Tamaño de los emojis")
        val actual = Ajustes.filasEmoji(this)
        fun poner(n: Int) {
            prefs.edit().putInt("emoji_filas", n).apply()
            dibujar()
        }
        grupo(null, listOf(
            filaCheck("Grandes (3 filas)", actual == 3) { poner(3) },
            filaCheck("Normal (4 filas)", actual == 4) { poner(4) },
            filaCheck("Pequeños (5 filas)", actual == 5) { poner(5) },
        ), pie = "Con menos filas cada emoji se ve más grande; con más filas ves más emojis a la vez. " +
                "El cambio se ve la próxima vez que abras el teclado.")
    }

    // ---------- Teclado con una mano ----------

    private fun paginaUnaMano() {
        volver()
        tituloGrande("Teclado con una mano")
        val actual = Ajustes.unaMano(this)
        grupo(null, listOf(
            filaCheck("Izquierda", actual == "izq") { elegir("una_mano", "izq") },
            filaCheck("No", actual == "no") { elegir("una_mano", "no") },
            filaCheck("Derecha", actual == "der") { elegir("una_mano", "der") },
        ), pie = "El teclado se hace más angosto y se pega al lado que elijas. Toca la flecha del " +
                "costado del teclado para volver al tamaño completo. En horizontal y en el teclado " +
                "numérico se usa siempre el teclado completo.")
    }

    // ---------- Sustitución de texto ----------

    private fun paginaAtajos() {
        volver()
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
