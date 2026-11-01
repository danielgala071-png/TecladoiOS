package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView

class Categoria(val icono: String, val nombre: String, val emojis: List<String>)

private fun lista(s: String) = s.trim().split(Regex("\\s+"))

/** Mismas categorías y en el mismo orden que el teclado del iPhone */
val CATEGORIAS = listOf(
    Categoria("🕘", "USADOS FRECUENTEMENTE", emptyList()),
    Categoria("😀", "EMOTICONOS Y PERSONAS", lista("""
        😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳
        😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔 🫢 🤭
        🤫 🤥 😶 🫠 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩
        👻 💀 ☠️ 👽 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇
        ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 💋 👶 🧒 👦 👧 🧑 👱
        👨 🧔 👩 🧓 👴 👵 🙋 🤷 🤦 💁 🙆 🙅 🙇 🧑‍💻 👮 👷 💂 🕵️ 👩‍⚕️ 👨‍🍳 🧑‍🎓 🏃 🚶 💃 🕺 👫 👭 👬 💑 👪
    """)),
    Categoria("🐻", "ANIMALES Y NATURALEZA", lista("""
        🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌
        🐞 🐜 🕷️ 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🦙 🐄 🐎 🐖 🐑 🐐
        🦌 🐕 🐈 🐓 🦃 🦚 🦜 🦢 🕊️ 🐇 🦝 🦦 🐁 🐿️ 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺
        🌸 🌼 🌻 🌞 🌝 🌛 🌚 🌙 🌎 🪐 💫 ⭐ 🌟 ✨ ⚡ 🔥 💥 ☄️ ☀️ 🌤️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ ⛄ 💨 💧 💦 🌊 🌈
    """)),
    Categoria("🍔", "COMIDA Y BEBIDA", lista("""
        🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🍞 🥖
        🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🍢 🍡 🍧
        🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️
    """)),
    Categoria("⚽", "ACTIVIDADES", lista("""
        ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🥅 ⛳ 🏹 🎣 🥊 🥋 🛹 🛼 ⛸️ 🎿 🏂 🏋️ 🤸 ⛹️ 🤺 🏄 🏊 🚴 🏆 🥇 🥈
        🥉 🏅 🎖️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩
    """)),
    Categoria("🚗", "VIAJES Y LUGARES", lista("""
        🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚨 🚍 🚘 🚖 🚡 🚠 🚃 🚄 🚅 🚂 🚆 🚇 ✈️ 🛫 🛬 🚀
        🛸 🚁 🛶 ⛵ 🚤 🛳️ 🚢 ⚓ ⛽ 🚧 🚦 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🏕️ 🏠 🏡 🏘️ 🏭 🏢
        🏬 🏥 🏦 🏨 🏪 🏫 💒 🏛️ ⛪ 🕌 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉
    """)),
    Categoria("💡", "OBJETOS", lista("""
        ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💰 💳 💎 ⚖️ 🔧
        🔨 🛠️ 🔩 ⚙️ 🧱 💣 🔪 🛡️ 🔮 🔭 🔬 💊 💉 🩹 🧬 🧹 🧺 🧻 🚽 🚿 🛁 🧼 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎁 🎈 🎀 🎊 🎉 ✉️
        📩 📦 📝 📁 📅 📌 📎 ✂️ 🔒 🔓 📚 📖 🔖 🖊️ ✏️ 🔍
    """)),
    Categoria("&%", "SÍMBOLOS", lista("""
        ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆗 🆒
        🆕 🆓 🆙 ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❗ 💯 🔞 📵 🚫 ⛔ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹
        ▶️ ⏸️ ⏹️ ⏭️ ⏮️ ⏩ ⏪ 🔀 🔁 🔂 ⬆️ ⬇️ ⬅️ ➡️ ↩️ ↪️ 🔄 🎵 🎶 💲 ©️ ®️ ™️ 🔔 🔕 💬 💭 ♠️ ♣️ ♥️ ♦️ 🃏
    """)),
    Categoria("🏳️", "BANDERAS", lista("""
        🇵🇪 🏳️ 🏴 🏁 🚩 🏳️‍🌈 🇦🇷 🇧🇴 🇧🇷 🇨🇱 🇨🇴 🇪🇨 🇲🇽 🇵🇾 🇺🇾 🇻🇪 🇺🇸 🇪🇸 🇨🇦 🇯🇵 🇰🇷 🇨🇳 🇮🇹 🇫🇷 🇩🇪 🇬🇧 🇵🇹 🇨🇺
        🇩🇴 🇵🇦 🇨🇷 🇬🇹 🇭🇳 🇸🇻 🇳🇮 🇵🇷
    """)),
)

/**
 * Panel de emojis como iOS: se desliza en horizontal (5 filas), títulos de sección,
 * y abajo ABC, los íconos de categorías en gris y borrar.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val tema: Tema,
    private val acciones: Acciones,
) : LinearLayout(context) {

    interface Acciones {
        fun emoji(s: String)
        fun borrar()
        fun volverABC()
        fun retro(tipo: Tipo)
    }

    private val d = Medidas.escala(context)
    private fun dp(v: Float) = (v * d).toInt()
    private val prefs = Ajustes.prefs(context)
    private val grilla = EmojiGrid(context, tema) { elegido(it) }
    private val scroll = HorizontalScrollView(context)
    private val categorias = BarraCategorias(context, tema) { i ->
        acciones.retro(Tipo.A_LETRAS)
        scroll.smoothScrollTo(grilla.inicioDe(i), 0)
    }

    init {
        orientation = VERTICAL

        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(grilla, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.setOnScrollChangeListener { _, x, _, _, _ ->
            grilla.desplazamiento = x
            categorias.marcado = grilla.seccionEn(x)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val abajo = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val abc = TextView(context).apply {
            text = "ABC"
            textSize = 15f
            setTextColor(tema.texto)
            gravity = Gravity.CENTER
            Fuentes.aplicar(this, 500)
            setOnClickListener {
                acciones.retro(Tipo.A_LETRAS)
                acciones.volverABC()
            }
        }
        abajo.addView(abc, LayoutParams(dp(52f), LayoutParams.MATCH_PARENT))
        abajo.addView(categorias, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val borrar = BotonBorrar(context, tema, { acciones.retro(Tipo.BORRAR) }, { acciones.borrar() })
        abajo.addView(borrar, LayoutParams(dp(52f), LayoutParams.MATCH_PARENT))
        addView(abajo, LayoutParams(LayoutParams.MATCH_PARENT, dp(42f)))

        refrescar()
    }

    private fun recientes(): List<String> {
        val guardados = prefs.getString("recientes", null)
        return if (guardados.isNullOrBlank()) lista("😂 ❤️ 😍 🙏 😭 👍 😊 🔥 🥰 😅 🤣 💀 😘 🥺 😁 ✨ 😎 🤔 😢 👀")
        else guardados.split(" ").filter { it.isNotBlank() }
    }

    /** Se llama al abrir el panel: actualiza "Usados frecuentemente" */
    fun refrescar() {
        grilla.secciones = listOf(EmojiGrid.Seccion(CATEGORIAS[0].nombre, recientes())) +
                CATEGORIAS.drop(1).map { EmojiGrid.Seccion(it.nombre, it.emojis) }
    }

    private fun elegido(e: String) {
        acciones.retro(Tipo.LETRA)
        acciones.emoji(e)
        val nuevos = recientes().toMutableList()
        nuevos.remove(e)
        nuevos.add(0, e)
        prefs.edit().putString("recientes", nuevos.take(30).joinToString(" ")).apply()
    }
}

/** Íconos de categorías en gris, como en iOS; la categoría actual con un círculo. */
@SuppressLint("ViewConstructor")
class BarraCategorias(
    context: Context,
    private val tema: Tema,
    private val alElegir: (Int) -> Unit,
) : View(context) {

    var marcado = 0
        set(v) { if (v != field) { field = v; invalidate() } }

    private val d = Medidas.escala(context)
    private val gris = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Quita el color a los emojis para que se vean como íconos grises
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    private val textoSimbolos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }.also { Fuentes.aplicar(it, 600) }
    private val circulo = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val n = CATEGORIAS.size
        if (n == 0 || width == 0) return
        val ancho = width / n.toFloat()
        val cy = height / 2f
        gris.textSize = 16f * d
        textoSimbolos.textSize = 13f * d
        textoSimbolos.color = tema.textoSecundario
        val fm = gris.fontMetrics
        val fm2 = textoSimbolos.fontMetrics
        CATEGORIAS.forEachIndexed { i, cat ->
            val cx = ancho * i + ancho / 2f
            if (i == marcado) {
                circulo.color = (tema.teclaPresionada and 0x00FFFFFF) or 0x66000000
                c.drawCircle(cx, cy, minOf(ancho, height.toFloat()) * 0.40f, circulo)
            }
            gris.alpha = if (i == marcado) 255 else 150
            if (cat.icono == "&%") {
                c.drawText(cat.icono, cx, cy - (fm2.ascent + fm2.descent) / 2f, textoSimbolos)
            } else {
                c.drawText(cat.icono, cx, cy - (fm.ascent + fm.descent) / 2f, gris)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && width > 0) {
            val i = (e.x / (width / CATEGORIAS.size.toFloat())).toInt().coerceIn(0, CATEGORIAS.size - 1)
            marcado = i
            alElegir(i)
        }
        return true
    }
}

@SuppressLint("ViewConstructor")
class EmojiGrid(
    context: Context,
    private val tema: Tema,
    private val alElegir: (String) -> Unit,
) : View(context) {

    class Seccion(val titulo: String, val emojis: List<String>)

    var secciones: List<Seccion> = emptyList()
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Cuánto se ha deslizado (para que el título se quede fijo como en iOS) */
    var desplazamiento = 0
        set(v) { field = v; invalidate() }

    private val d = Medidas.escala(context)
    private val filas = 5
    private val altoTitulo = 28f * d
    private val margen = 10f * d
    private val separacion = 20f * d
    private var celda = 0f
    private val inicios = ArrayList<Float>()
    private val finales = ArrayList<Float>()
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
    }
    private val pinturaTitulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * d
        letterSpacing = 0.03f
    }.also { Fuentes.aplicar(it, 600) }
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var presionado: Pair<Int, Int>? = null
    private var x0 = 0f
    private var y0 = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun columnasDe(n: Int) = (n + filas - 1) / filas

    fun inicioDe(i: Int): Int = ((inicios.getOrNull(i) ?: 0f) - margen).toInt().coerceAtLeast(0)

    fun seccionEn(x: Int): Int {
        var r = 0
        for (i in inicios.indices) if (inicios[i] <= x + margen + 20f * d) r = i
        return r
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alto = MeasureSpec.getSize(heightMeasureSpec)
        celda = ((alto - altoTitulo - 6f * d) / filas).coerceAtLeast(1f)
        inicios.clear()
        finales.clear()
        var x = margen
        for (s in secciones) {
            inicios.add(x)
            // La sección es al menos tan ancha como su título (para que no se encimen)
            x += maxOf(columnasDe(s.emojis.size) * celda, pinturaTitulo.measureText(s.titulo) + 12f * d)
            finales.add(x)
            x += separacion
        }
        setMeasuredDimension((x + margen).toInt(), alto)
    }

    override fun onDraw(c: Canvas) {
        val clip = c.clipBounds
        pintura.textSize = celda * 0.74f
        val fm = pintura.fontMetrics
        pinturaTitulo.color = tema.textoSecundario
        secciones.forEachIndexed { i, s ->
            val ini = inicios.getOrElse(i) { 0f }
            val fin = finales.getOrElse(i) { 0f }
            if (fin < clip.left || ini > clip.right) return@forEachIndexed

            // Título fijo a la izquierda mientras se ve su sección
            val anchoTitulo = pinturaTitulo.measureText(s.titulo)
            val xt = maxOf(ini + 4f * d, minOf(desplazamiento + margen + 4f * d, fin - anchoTitulo))
            c.drawText(s.titulo, xt, altoTitulo * 0.66f, pinturaTitulo)

            s.emojis.forEachIndexed { j, e ->
                val x = ini + (j / filas) * celda
                if (x + celda < clip.left || x > clip.right) return@forEachIndexed
                val y = altoTitulo + (j % filas) * celda
                if (presionado == Pair(i, j)) {
                    fondo.color = (tema.teclaPresionada and 0x00FFFFFF) or 0x99000000.toInt()
                    c.drawRoundRect(x + 3 * d, y + 3 * d, x + celda - 3 * d, y + celda - 3 * d, 12 * d, 12 * d, fondo)
                }
                c.drawText(e, x + celda / 2f, y + celda / 2f - (fm.ascent + fm.descent) / 2f, pintura)
            }
        }
    }

    private fun buscar(x: Float, y: Float): Pair<Int, Int>? {
        if (celda <= 0f || y < altoTitulo) return null
        for (i in secciones.indices) {
            val ini = inicios.getOrElse(i) { 0f }
            val fin = finales.getOrElse(i) { 0f }
            if (x in ini..fin) {
                val col = ((x - ini) / celda).toInt()
                val fila = ((y - altoTitulo) / celda).toInt().coerceIn(0, filas - 1)
                val j = col * filas + fila
                return if (j < secciones[i].emojis.size) Pair(i, j) else null
            }
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y
                presionado = buscar(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = presionado
                presionado = null
                invalidate()
                val quieto = Math.abs(e.x - x0) < slop && Math.abs(e.y - y0) < slop
                if (p != null && quieto) alElegir(secciones[p.first].emojis[p.second])
            }
            MotionEvent.ACTION_CANCEL -> {
                presionado = null
                invalidate()
            }
        }
        return true
    }
}

@SuppressLint("ViewConstructor")
class BotonBorrar(
    context: Context,
    tema: Tema,
    private val alPresionar: () -> Unit,
    private val alBorrar: () -> Unit,
) : View(context) {

    private val d = Medidas.escala(context)
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tema.texto
    }
    private val manejador = Handler(Looper.getMainLooper())
    private val repetir = object : Runnable {
        override fun run() {
            alBorrar()
            manejador.postDelayed(this, 60)
        }
    }
    private var presionado = false

    override fun onDraw(c: Canvas) {
        pintura.alpha = if (presionado) 120 else 255
        Iconos.borrar(c, width / 2f, height / 2f, 22f * d, pintura)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                presionado = true
                alPresionar()
                alBorrar()
                manejador.postDelayed(repetir, 400)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                presionado = false
                manejador.removeCallbacks(repetir)
                invalidate()
            }
        }
        return true
    }
}
