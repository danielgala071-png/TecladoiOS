package com.tecladoios

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Escáner de texto por cámara: abre la cámara, lee el texto de la foto (documento, cartel,
 * recibo) y te deja revisarlo antes de ponerlo en el chat.
 *
 * Corre en su propio proceso (":escaner"): el modelo de lectura y la foto nunca ocupan
 * memoria del teclado, y al cerrarse, Android recupera todo. Funciona sin internet.
 */
class EscanerActivity : Activity() {

    companion object {
        const val ACCION = "com.tecladoios.TEXTO_ESCANEADO"
        const val EXTRA_TEXTO = "texto"
        /** Ficha que manda el teclado: solo acepta el texto que vuelve con la misma */
        const val EXTRA_FICHA = "ficha"
        private const val PEDIR_FOTO = 71
        private const val LADO_MAX = 2560
    }

    private var ficha = ""
    private var reconocedor: TextRecognizer? = null
    private lateinit var caja: EditText
    private lateinit var estado: TextView
    private lateinit var girando: ProgressBar
    private lateinit var insertar: TextView

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        ficha = intent.getStringExtra(EXTRA_FICHA) ?: ""
        armarPantalla()
        val previo = guardado?.getString("texto")
        if (previo != null) {
            caja.setText(previo)
            listo(previo.isNotBlank())
        } else {
            tomarFoto()
        }
    }

    override fun onSaveInstanceState(s: Bundle) {
        super.onSaveInstanceState(s)
        s.putString("texto", caja.text.toString())
    }

    override fun onDestroy() {
        try { reconocedor?.close() } catch (_: Throwable) { }
        reconocedor = null
        try { EscanerProvider.archivo(this).delete() } catch (_: Throwable) { }
        super.onDestroy()
    }

    // ---------- Pantalla (negra y sobria, como el teclado) ----------

    private fun armarPantalla() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            val p = dp(20f).toInt()
            setPadding(p, p, p, p)
        }
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        arriba.addView(TextView(this).apply {
            text = "Texto escaneado"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            Fuentes.aplicar(this, 700)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(TextView(this).apply {
            text = "Cancelar"
            setTextColor(0xFF0A84FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            Fuentes.aplicar(this, 400)
            setPadding(dp(8f).toInt(), dp(8f).toInt(), 0, dp(8f).toInt())
            setOnClickListener { finish() }
        })
        raiz.addView(arriba)

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f).toInt(), 0, dp(12f).toInt())
        }
        girando = ProgressBar(this).apply { isIndeterminate = true }
        fila.addView(girando, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply { marginEnd = dp(8f).toInt() })
        estado = TextView(this).apply {
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            Fuentes.aplicar(this, 400)
            text = "Abriendo la cámara…"
        }
        fila.addView(estado)
        raiz.addView(fila)

        caja = EditText(this).apply {
            setTextColor(0xFFF5F5F7.toInt())
            setHintTextColor(0xFF5A5A5E.toInt())
            hint = "Aquí aparecerá el texto. Puedes corregirlo antes de ponerlo en el chat."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            Fuentes.aplicar(this, 400)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val p = dp(14f).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = dp(14f)
            }
        }
        raiz.addView(caja, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val botones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14f).toInt(), 0, 0)
        }
        botones.addView(boton("Otra foto", 0xFF2C2C2E.toInt(), Color.WHITE) { tomarFoto() },
            LinearLayout.LayoutParams(0, dp(50f).toInt(), 1f).apply { marginEnd = dp(10f).toInt() })
        insertar = boton("Poner en el chat", 0xFF0A84FF.toInt(), Color.WHITE) { enviar() }
        botones.addView(insertar, LinearLayout.LayoutParams(0, dp(50f).toInt(), 1.4f))
        raiz.addView(botones)
        setContentView(raiz)
        listo(false)
    }

    private fun boton(texto: String, fondo: Int, letra: Int, accion: () -> Unit) = TextView(this).apply {
        text = texto
        gravity = Gravity.CENTER
        setTextColor(letra)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        Fuentes.aplicar(this, 600)
        background = GradientDrawable().apply {
            setColor(fondo)
            cornerRadius = dp(14f)
        }
        setOnClickListener { accion() }
    }

    private fun listo(hayTexto: Boolean) {
        girando.visibility = View.GONE
        insertar.isEnabled = hayTexto
        insertar.alpha = if (hayTexto) 1f else 0.4f
    }

    // ---------- Cámara ----------

    @Suppress("DEPRECATION")
    private fun tomarFoto() {
        try { EscanerProvider.archivo(this).delete() } catch (_: Throwable) { }
        val uri = EscanerProvider.uri()
        val camara = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("foto", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        girando.visibility = View.VISIBLE
        estado.text = "Apunta al texto y toma la foto"
        try {
            startActivityForResult(camara, PEDIR_FOTO)
        } catch (e: Exception) {
            Registro.anotar("abrir la cámara", e)
            estado.text = "No se encontró una app de cámara en el celular."
            listo(caja.text.isNotBlank())
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(pedido: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(pedido, resultado, datos)
        if (pedido != PEDIR_FOTO) return
        val archivo = EscanerProvider.archivo(this)
        if (resultado != RESULT_OK || !archivo.exists() || archivo.length() == 0L) {
            // Cancelaste la cámara: si no había texto, se cierra todo
            if (caja.text.isBlank()) finish() else { estado.text = "Revisa el texto"; listo(true) }
            return
        }
        leerTexto()
    }

    // ---------- Lectura del texto ----------

    private fun leerTexto() {
        estado.text = "Leyendo el texto…"
        girando.visibility = View.VISIBLE
        insertar.isEnabled = false
        Thread({
            val (foto, giro) = try { cargarFoto() } catch (e: Throwable) {
                Registro.anotar("cargar la foto del escáner", e)
                null to 0
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (foto == null) {
                    estado.text = "No se pudo abrir la foto. Prueba otra vez."
                    listo(caja.text.isNotBlank())
                } else reconocer(foto, giro)
            }
        }, "escaner").start()
    }

    /** La foto reducida (más que suficiente para leer) y cuánto hay que girarla */
    private fun cargarFoto(): Pair<Bitmap?, Int> {
        val ruta = EscanerProvider.archivo(this).absolutePath
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, medidas)
        var escala = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (escala * 2) >= LADO_MAX) escala *= 2
        val foto = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = escala })
        val giro = try {
            when (ExifInterface(ruta).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        return foto to giro
    }

    private fun reconocer(foto: Bitmap, giro: Int) {
        val r = reconocedor ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { reconocedor = it }
        r.process(InputImage.fromBitmap(foto, giro))
            .addOnSuccessListener { resultado ->
                val texto = armarTexto(resultado)
                // La foto ya no hace falta: se borra (nada queda guardado)
                try { EscanerProvider.archivo(this).delete() } catch (_: Throwable) { }
                if (texto.isBlank()) {
                    estado.text = "No se encontró texto. Acércate más o busca mejor luz."
                    listo(caja.text.isNotBlank())
                } else {
                    // Otra foto: el texto nuevo se agrega a lo que ya había
                    val antes = caja.text.toString().trimEnd()
                    caja.setText(if (antes.isEmpty()) texto else antes + "\n" + texto)
                    caja.setSelection(caja.text.length)
                    estado.text = "Revisa el texto y ponlo en el chat"
                    listo(true)
                }
            }
            .addOnFailureListener { e ->
                Registro.anotar("leer el texto de la foto", e)
                estado.text = "No se pudo leer el texto. Prueba otra vez."
                listo(caja.text.isNotBlank())
            }
    }

    /**
     * Arma el texto como se lee: las líneas de un mismo párrafo se juntan (una foto corta las
     * líneas donde termina el papel, no donde termina la frase), los párrafos quedan aparte y
     * las palabras cortadas con guion al final de la línea se vuelven a unir.
     */
    private fun armarTexto(r: Text): String = r.textBlocks.joinToString("\n") { bloque ->
        val sb = StringBuilder()
        for (linea in bloque.lines) {
            val t = linea.text.trim()
            if (t.isEmpty()) continue
            when {
                sb.isEmpty() -> sb.append(t)
                sb.endsWith("-") && t.first().isLowerCase() -> { sb.setLength(sb.length - 1); sb.append(t) }
                else -> sb.append(' ').append(t)
            }
        }
        sb.toString()
    }.trim()

    // ---------- Al chat ----------

    private fun enviar() {
        val texto = caja.text.toString().trim()
        if (texto.isEmpty()) return
        sendBroadcast(Intent(ACCION).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXTO, texto)
            putExtra(EXTRA_FICHA, ficha)
        })
        finish()
    }
}
