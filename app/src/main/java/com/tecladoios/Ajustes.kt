package com.tecladoios

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Todas las opciones del teclado, con los mismos nombres que en Ajustes > General > Teclado del iPhone. */
object Ajustes {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("tecladoios", Context.MODE_PRIVATE)

    private fun si(c: Context, clave: String, porDefecto: Boolean) = prefs(c).getBoolean(clave, porDefecto)

    // Todos los teclados
    fun mayusculas(c: Context) = si(c, "mayusculas", true)
    fun autocorregir(c: Context) = si(c, "autocorregir", true)
    fun revisarOrtografia(c: Context) = si(c, "ortografia", true)
    fun bloqMayus(c: Context) = si(c, "bloq_mayus", true)
    fun atajosActivos(c: Context) = si(c, "atajos_activos", true)
    fun sugerencias(c: Context) = si(c, "sugerencias", true)
    fun enLinea(c: Context) = si(c, "en_linea", false)
    /** Separa palabras pegadas mientras escribes, sin esperar el espacio ("yanpodemos" → "ya podemos") */
    fun separarVivo(c: Context) = si(c, "separar_vivo", true)
    fun puntuacionInteligente(c: Context) = si(c, "puntuacion", true)
    /** Quitado: deslizar para escribir ya no existe (se escribe tecla por tecla) */
    @Suppress("UNUSED_PARAMETER") fun deslizar(c: Context) = false
    /** Quitado junto con deslizar para escribir */
    @Suppress("UNUSED_PARAMETER") fun borrarDeslizado(c: Context) = false
    fun vistaPrevia(c: Context) = si(c, "vista_previa", true)
    fun puntoDoble(c: Context) = si(c, "punto_doble", true)
    /** Lo copiado aparece en la barra de sugerencias para pegarlo con un toque */
    fun portapapeles(c: Context) = si(c, "portapapeles", true)
    /** Las contraseñas que copias se borran solas del portapapeles al minuto */
    fun limpiarClaves(c: Context) = si(c, "limpiar_claves", true)
    /** Recordar contraseñas (cifradas, solo en el celular; se guardan cuando tú lo pides) */
    fun recordarClaves(c: Context) = si(c, "recordar_claves", true)
    /** Capturas de pantalla recientes en la barra para enviarlas con un toque */
    fun capturas(c: Context) = si(c, "capturas", true)
    /** Botón "Escanear" (texto con la cámara) en la barra con el campo vacío */
    fun escanerBarra(c: Context) = si(c, "escaner_barra", true)
    /** Idiomas que escribes además del español (sin cambiar nada a mano) */
    fun idiomas(c: Context): Set<String> = buildSet {
        if (si(c, "idioma_en", true)) add("en")
        if (si(c, "idioma_pt", false)) add("pt")
    }
    /** Al cerrar con ? o ! se pone el signo de apertura (¿ ¡) al inicio, como se escribe en español */
    fun apertura(c: Context) = si(c, "apertura", true)
    /** Emojis en la barra de sugerencias según lo que escribes ("jaja" → 😂) */
    @Suppress("UNUSED_PARAMETER") fun emojisBarra(c: Context) = false // quitado: la barra solo sugiere palabras
    /** Fila de números siempre a la vista arriba de las letras (como Gboard) */
    fun filaNumeros(c: Context) = si(c, "fila_numeros", false)
    /** Guarda lo último que copiaste (hasta 10) para pegarlo desde el panel de emojis */
    fun historialPortapapeles(c: Context) = si(c, "historial_portapapeles", true)
    /** Aprender los nombres de tus contactos (no los corrige y los escribe con mayúscula) */
    fun nombresContactos(c: Context) = si(c, "contactos", false)
    /** Ícono de la app donde escribes, muy tenue en la barra espaciadora */
    fun iconoEspacio(c: Context) = si(c, "icono_espacio", true)

    // Teclado con una mano: "no", "izq" o "der"
    fun unaMano(c: Context) = prefs(c).getString("una_mano", "no") ?: "no"

    /** Tamaño del teclado en porcentaje (80 = más chico, 130 = más grande). Las letras se ajustan solas. */
    const val TAMANO_MIN = 80
    const val TAMANO_MAX = 130
    fun tamano(c: Context) = prefs(c).getInt("tamano", 100).coerceIn(TAMANO_MIN, TAMANO_MAX)

    // Respuesta del teclado
    fun vibrar(c: Context) = si(c, "vibrar", true)
    fun sonido(c: Context) = si(c, "sonido", false)
    /** Fuerza de la háptica: "suave", "media" o "fuerte" */
    fun vibracion(c: Context) = prefs(c).getString("vibracion", "media") ?: "media"

    // Escritura
    /** Escritura rápida: sin vista previa ni acentos al mantener (para escribir muy rápido con los pulgares) */
    @Suppress("UNUSED_PARAMETER") fun escrituraRapida(c: Context) = false // quitado: siempre hay burbuja y acentos
    /** La barra de sugerencias se queda unos segundos después de enviar o dejar de escribir */
    fun barraSostenida(c: Context) = si(c, "barra_sostenida", true)
    /** Con el campo vacío, la barra muestra tus emojis más usados */
    @Suppress("UNUSED_PARAMETER") fun emojisFrecuentes(c: Context) = false // quitado: sin emojis en la barra

    /** Qué tan visible es el filo de color de las teclas: "apagado", "sutil", "normal" o "vivo" */
    fun bordeTeclas(c: Context) = prefs(c).getString("borde_teclas", "normal") ?: "normal"

    /** En correo y apps de trabajo, las sugerencias se vuelven formales (sin jerga) */
    fun modoFormal(c: Context) = si(c, "modo_formal", true)
    /** Deslizar la barra espaciadora hacia arriba pone la sugerencia del centro */
    @Suppress("UNUSED_PARAMETER") fun aceptarDeslizando(c: Context) = false // quitado

    /** Filas de emojis en el panel: 3 (grandes), 4 (normal) o 5 (más emojis a la vista) */
    fun filasEmoji(c: Context) = prefs(c).getInt("emoji_filas", 5).coerceIn(3, 5)

    /** Sustitución de texto: (función rápida, frase) */
    fun atajos(c: Context): MutableList<Pair<String, String>> {
        val p = prefs(c)
        val guardado = p.getString("atajos", null)
            ?: return ATAJOS_SUGERIDOS.toMutableList()
        val lista = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(guardado)
            for (i in 0 until arr.length()) {
                val par = arr.getJSONArray(i)
                lista.add(par.getString(0) to par.getString(1))
            }
        } catch (_: Exception) { }
        // Una sola vez: sumar las sustituciones nuevas sin tocar las tuyas
        if (!p.getBoolean("atajos_v5", false)) {
            val claves = lista.map { it.first.lowercase() }.toSet()
            lista += ATAJOS_SUGERIDOS.filter { it.first.lowercase() !in claves }
            guardarAtajos(c, lista)
            p.edit().putBoolean("atajos_v5", true).apply()
        }
        // Una sola vez: quitar las sustituciones que chocaban con palabras reales
        // (solo si siguen tal cual venían; si las cambiaste tú, se respetan)
        if (!p.getBoolean("atajos_v6", false)) {
            val antes = lista.size
            lista.removeAll { (atajo, frase) -> RETIRADOS[atajo.lowercase()] == frase }
            if (lista.size != antes) guardarAtajos(c, lista)
            p.edit().putBoolean("atajos_v6", true).apply()
        }
        return lista
    }

    fun guardarAtajos(c: Context, lista: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((atajo, frase) in lista.sortedBy { it.first.lowercase() }) arr.put(JSONArray().put(atajo).put(frase))
        prefs(c).edit().putString("atajos", arr.toString()).apply()
    }

    /**
     * Sustituciones que se quitaron porque cambiaban palabras que sí querías escribir:
     * "dnd" (también es "dónde"), "aver" (a veces es "haber"), "toy", "sta" e "ig".
     */
    private val RETIRADOS = mapOf(
        "dnd" to "de nada", "aver" to "a ver", "toy" to "estoy", "sta" to "está", "ig" to "Instagram",
    )

    /** Sustituciones comunes del chat peruano */
    val ATAJOS_SUGERIDOS = listOf(
        "pq" to "porque", "xq" to "porque", "tmb" to "también", "tb" to "también",
        "tqm" to "te quiero mucho", "tkm" to "te quiero mucho", "ntp" to "no te preocupes",
        "xfa" to "por favor", "cdt" to "cuídate", "bn" to "bien",
        "msj" to "mensaje", "salu2" to "saludos", "nd" to "nada", "dsp" to "después",
        "aki" to "aquí", "vdd" to "verdad", "grax" to "gracias",
        "q" to "que", "k" to "que", "xk" to "por qué", "bno" to "bueno", "tons" to "entonces",
        "ps" to "pues", "tbn" to "también", "kiero" to "quiero", "ke" to "que",
        // Más abreviaturas comunes del chat
        "pk" to "porque", "porq" to "porque", "pqe" to "porque", "tmbn" to "también", "tambn" to "también",
        "tmpc" to "tampoco", "tpc" to "tampoco", "dsps" to "después", "dps" to "después", "dspues" to "después",
        "mñn" to "mañana", "mñna" to "mañana", "hrs" to "horas", "sgte" to "siguiente",
        "aprox" to "aproximadamente", "tdo" to "todo", "tds" to "todos", "tdos" to "todos",
        "nc" to "no sé", "ns" to "no sé", "nse" to "no sé", "nose" to "no sé", "sbs" to "sabes",
        "stoy" to "estoy", "mxo" to "mucho",
        "msjs" to "mensajes", "tlf" to "teléfono", "qtal" to "qué tal", "ktal" to "qué tal",
        "hla" to "hola", "grs" to "gracias", "gcs" to "gracias", "grx" to "gracias", "pls" to "por favor",
        "plis" to "por favor", "bss" to "besos", "bdias" to "buenos días",
        "bnas" to "buenas", "bnoches" to "buenas noches", "bn8" to "buenas noches", "btardes" to "buenas tardes",
        "kien" to "quién", "kmo" to "cómo", "ksa" to "casa", "kasa" to "casa",
        "osea" to "o sea", "nomas" to "nomás", "hno" to "hermano", "hna" to "hermana", "nunk" to "nunca",
        "ntc" to "no te creas", "tqi" to "te quiero", "tq" to "te quiero",
        "cdte" to "cuídate",
        "cmo" to "cómo", "xfis" to "por favor", "xfavor" to "por favor",
        "sldos" to "saludos", "fds" to "fin de semana", "bday" to "cumpleaños",
        "felicidds" to "felicidades",
        // Chat peruano: más abreviaturas y formas rápidas
        "tqmm" to "te quiero mucho", "tmo" to "te amo", "lqm" to "lo quiero mucho", "porfa" to "por favor",
        "porfis" to "por favor", "xfas" to "por favor", "pf" to "por favor", "dnde" to "dónde",
        "dond" to "dónde", "cnd" to "cuando", "cdo" to "cuando", "kndo" to "cuando", "tdv" to "todavía",
        "tdvia" to "todavía", "xa" to "para", "pra" to "para", "kn" to "con", "cn" to "con", "sn" to "sin",
        "dsd" to "desde", "hta" to "hasta", "hst" to "hasta", "msm" to "mismo", "mjr" to "mejor",
        "vrdd" to "verdad", "vdad" to "verdad", "tas" to "estás",
        "stas" to "estás", "stamos" to "estamos", "ksi" to "casi", "kpo" to "capo", "gnial" to "genial",
        "dle" to "dale", "chvr" to "chévere", "chvre" to "chévere", "bkn" to "bacán", "slds" to "saludos",
        "atte" to "atentamente", "nro" to "número", "wsp" to "WhatsApp", "wasa" to "WhatsApp",
        "fb" to "Facebook", "yt" to "YouTube", "nsc" to "no sé",
        "ntp" to "no te preocupes", "mñ" to "mañana", "hy" to "hoy", "ayr" to "ayer", "ahi" to "ahí",
        "aorita" to "ahorita", "orita" to "ahorita", "fds" to "fin de semana",
        "hbd" to "¡Feliz cumpleaños!", "qepd" to "que en paz descanse", "bnd" to "bendiciones",
        "dtb" to "Dios te bendiga",
    ).distinctBy { it.first.lowercase() }
}
