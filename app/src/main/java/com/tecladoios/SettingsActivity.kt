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
        opcion("Bloqueo de mayúsculas", Ajustes.BLOQ_MAYUS, true)
        opcion("Vista previa de caracteres", Ajustes.VISTA_PREVIA, true)
        opcion("Atajo \".\"", Ajustes.ATAJO_PUNTO, true)
        col.addView(titulo("Al pulsar dos veces la barra espaciadora se insertará un punto seguido de un espacio.", 13f, 0xFF8E8E93.toInt()).apply { setPadding(0, 0, 0, dp(8)) })

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
}
