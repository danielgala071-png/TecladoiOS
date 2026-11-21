package com.tecladoios

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Ajustes del teclado: los mismos que el teclado del iPhone, nada más */
class SettingsActivity : Activity() {

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val p = Ajustes.prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.BLACK)
        }

        fun titulo(s: String, tam: Float, colorTexto: Int = Color.WHITE) = TextView(this).apply {
            text = s; textSize = tam; setTextColor(colorTexto)
            setPadding(0, dp(18), 0, dp(8))
        }

        col.addView(titulo("TecladoiOS", 30f).apply { setPadding(0, 0, 0, dp(6)) })

        col.addView(titulo("ACTIVAR", 13f, 0xFF8E8E93.toInt()))
        col.addView(Button(this).apply {
            text = "1. Activar TecladoiOS"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "2. Elegir TecladoiOS"
            setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        })

        col.addView(titulo("TECLADO", 13f, 0xFF8E8E93.toInt()))
        fun opcion(nombre: String, clave: String, porDefecto: Boolean) {
            col.addView(Switch(this).apply {
                text = nombre; textSize = 17f; setTextColor(Color.WHITE)
                isChecked = p.getBoolean(clave, porDefecto)
                setPadding(0, dp(12), 0, dp(12))
                setOnCheckedChangeListener { _, v -> p.edit().putBoolean(clave, v).apply() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        opcion("Mayúsculas automáticas", Ajustes.MAYUS_AUTO, true)
        opcion("Corrección automática", Ajustes.AUTOCORRECCION, true)
        opcion("Predicción", Ajustes.PREDICTIVO, true)
        opcion("¿ y ¡ automáticos", Ajustes.SIGNOS, true)

        col.addView(titulo("DICCIONARIO PERSONAL", 13f, 0xFF8E8E93.toInt()))
        col.addView(Button(this).apply {
            text = "Ver palabras aprendidas"
            setOnClickListener { verAprendidas() }
        })
        col.addView(Button(this).apply {
            text = "Borrar todo lo aprendido"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("¿Borrar todo lo aprendido?")
                    .setMessage("El teclado olvidará tus palabras, nombres y frases. Esto no se puede deshacer.")
                    .setPositiveButton("Borrar") { _, _ -> borrarTodo() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        })
        opcion("Bloqueo de mayúsculas", Ajustes.BLOQ_MAYUS, true)
        opcion("Vista previa de caracteres", Ajustes.VISTA_PREVIA, true)
        opcion("Atajo \".\"", Ajustes.ATAJO_PUNTO, true)
        col.addView(titulo("Al pulsar dos veces la barra espaciadora se insertará un punto seguido de un espacio.", 13f, 0xFF8E8E93.toInt()).apply { setPadding(0, 0, 0, dp(8)) })

        col.addView(titulo("TAMAÑO DEL TECLADO", 13f, 0xFF8E8E93.toInt()))
        val etiquetaTam = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE) }
        fun mostrarTam(v: Int) { etiquetaTam.text = "Alto de las teclas: $v %" }
        val actual = p.getInt(Ajustes.TAMANO, 100).coerceIn(80, 130)
        mostrarTam(actual)
        col.addView(etiquetaTam)
        val barraTam = android.widget.SeekBar(this)
        col.addView(barraTam.apply {
            max = 10                      // de 80 % a 130 %, de 5 en 5
            progress = (actual - 80) / 5
            setPadding(dp(8), dp(14), dp(8), dp(14))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar?, v: Int, deUsuario: Boolean) {
                    mostrarTam(80 + v * 5)
                    if (deUsuario) p.edit().putInt(Ajustes.TAMANO, 80 + v * 5).apply()
                }
                override fun onStartTrackingTouch(b: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(b: android.widget.SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(titulo("Pruébalo en la casilla de abajo: el teclado cambia al momento.", 13f, 0xFF8E8E93.toInt()).apply { setPadding(0, 0, 0, dp(8)) })

        // Ajuste a la forma de escribir con dos dedos
        col.addView(titulo("AJUSTE A TU FORMA DE ESCRIBIR", 13f, 0xFF8E8E93.toInt()))
        opcion("Ajustar las teclas a cómo escribo", Ajustes.AJUSTE_AUTO, true)
        val estado = TextView(this).apply { textSize = 15f; setTextColor(0xFFC7C7CC.toInt()); setPadding(0, dp(4), 0, dp(4)) }
        col.addView(estado)
        val usar = Button(this)
        col.addView(usar)
        fun refrescar() {
            val v = p.getString(Ajustes.AJUSTE, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            val n = v?.getOrNull(6)?.toInt() ?: 0
            if (v == null || v.size < 7 || n < 300) {
                estado.text = "Escribe con normalidad: el teclado va aprendiendo dónde tocas con cada pulgar " +
                    "y ajusta las zonas de las teclas solo. Para recomendarte un tamaño necesita unas 300 letras ($n de 300)."
                usar.visibility = android.view.View.GONE
                return
            }
            // Si tus toques caen dispersos arriba y abajo, teclas más altas te ayudan (y al revés)
            val dispX = v.getOrNull(7) ?: 0.2f
            val rec = (100 + (v[5] - 0.18f) * 150 + (dispX - 0.2f) * 50).let { (Math.round(it / 5f) * 5).coerceIn(90, 125) }
            estado.text = "Ya aprendió de $n letras. Según cómo escribes con dos dedos, el tamaño que mejor te queda es $rec %."
            usar.visibility = android.view.View.VISIBLE
            usar.text = "Usar tamaño recomendado ($rec %)"
            usar.setOnClickListener {
                p.edit().putInt(Ajustes.TAMANO, rec).apply()
                mostrarTam(rec); barraTam.progress = (rec - 80) / 5
                android.widget.Toast.makeText(this, "Tamaño ajustado a $rec %", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        refrescar()
        col.addView(Button(this).apply {
            text = "Volver a aprender desde cero"
            setOnClickListener {
                p.edit().remove(Ajustes.AJUSTE).apply()
                refrescar()
                android.widget.Toast.makeText(this@SettingsActivity, "El ajuste empezará de nuevo", android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(titulo("RESPUESTA DEL TECLADO", 13f, 0xFF8E8E93.toInt()))
        opcion("Vibración", Ajustes.VIBRACION, true)
        opcion("Sonido", Ajustes.SONIDO, false)

        col.addView(titulo("PRUEBA", 13f, 0xFF8E8E93.toInt()))
        col.addView(EditText(this).apply {
            hint = "Escribe aquí para probar"
            setTextColor(Color.WHITE); setHintTextColor(0xFF636366.toInt())
            gravity = Gravity.TOP
            minLines = 3
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    private val claves = listOf(Diccionario.PROPIAS, Diccionario.NOMBRES, Diccionario.USOS, Diccionario.PARES, Diccionario.TRIOS)

    private val dir by lazy { Diccionario.carpeta(this) }
    private fun lista(k: String) = Diccionario.leer(dir, k)

    /** Lo aprendido: nombres, palabras que agregaste y tus 150 palabras más usadas */
    private fun aprendidas(): List<String> {
        val r = LinkedHashSet<String>()
        r.addAll(lista(Diccionario.NOMBRES))
        r.addAll(lista(Diccionario.PROPIAS))
        lista(Diccionario.USOS).mapNotNull { l -> val i = l.lastIndexOf(':'); if (i > 0) l.substring(0, i) to (l.substring(i + 1).toIntOrNull() ?: 0) else null }
            .sortedByDescending { it.second }.take(150).forEach { r.add(it.first) }
        return r.toList()
    }

    private fun verAprendidas() {
        val items = aprendidas()
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Palabras aprendidas")
        if (items.isEmpty()) {
            dlg.setMessage("Todavía no aprendió palabras. Se llenará a medida que escribas.").setPositiveButton("OK", null).show()
            return
        }
        val marcadas = BooleanArray(items.size)
        dlg.setMultiChoiceItems(items.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Olvidar las marcadas") { _, _ ->
                olvidar(items.filterIndexed { i, _ -> marcadas[i] }.map { it.lowercase() }.toSet())
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Quita esas palabras de todo lo aprendido (también de tus frases) */
    private fun olvidar(quitar: Set<String>) {
        if (quitar.isEmpty()) return
        Diccionario.escribir(dir, Diccionario.PROPIAS, lista(Diccionario.PROPIAS).filter { it.lowercase() !in quitar })
        Diccionario.escribir(dir, Diccionario.NOMBRES, lista(Diccionario.NOMBRES).filter { it.lowercase() !in quitar })
        Diccionario.escribir(dir, Diccionario.USOS, lista(Diccionario.USOS).filter { it.substringBeforeLast(':') !in quitar })
        for (k in listOf(Diccionario.PARES, Diccionario.TRIOS))
            Diccionario.escribir(dir, k, lista(k).filter { l -> l.substringBeforeLast(':').split(' ').none { it in quitar } })
        Ajustes.prefs(this).edit().putLong(Ajustes.DIC_VERSION, System.currentTimeMillis()).apply()
        android.widget.Toast.makeText(this, "Listo: olvidó ${quitar.size} palabra(s)", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun borrarTodo() {
        claves.forEach { java.io.File(dir, "$it.txt").delete() }
        Ajustes.prefs(this).edit().putLong(Ajustes.DIC_VERSION, System.currentTimeMillis()).apply()
        android.widget.Toast.makeText(this, "Se borró todo lo aprendido", android.widget.Toast.LENGTH_SHORT).show()
    }
}
