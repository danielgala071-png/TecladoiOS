package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView

class Categoria(val icono: String, val nombre: String, val emojis: List<String>)

private fun lista(s: String) = s.trim().split(Regex("\\s+"))

val CATEGORIAS = listOf(
    Categoria("😀", "EMOTICONOS Y PERSONAS", lista("""
        😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳
        😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔 🫢 🤭
        🤫 🤥 😶 🫠 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩
        👻 💀 ☠️ 👽 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇
        ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 💋 👶 🧒 👦 👧 🧑 👱
        👨 🧔 👩 🧓 👴 👵 🙋 🤷 🤦 💁 🙆 🙅 🙇 🧑‍💻 👮 👷 💂 🕵️ 👩‍⚕️ 👨‍🍳 🧑‍🎓 🏃 🚶 💃 🕺 👫 👭 👬 💑 👪
    """)),
    Categoria("🐶", "ANIMALES Y NATURALEZA", lista("""
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
    Categoria("❤️", "SÍMBOLOS", lista("""
        ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆗 🆒
        🆕 🆓 🆙 ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❗ 💯 🔞 📵 🚫 ⛔ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹
        ▶️ ⏸️ ⏹️ ⏭️ ⏮️ ⏩ ⏪ 🔀 🔁 🔂 ⬆️ ⬇️ ⬅️ ➡️ ↩️ ↪️ 🔄 🎵 🎶 💲 ©️ ®️ ™️ 🔔 🔕 💬 💭 ♠️ ♣️ ♥️ ♦️ 🃏
    """)),
    Categoria("🏳️", "BANDERAS", lista("""
        🇵🇪 🏳️ 🏴 🏁 🚩 🏳️‍🌈 🇦🇷 🇧🇴 🇧🇷 🇨🇱 🇨🇴 🇪🇨 🇲🇽 🇵🇾 🇺🇾 🇻🇪 🇺🇸 🇪🇸 🇨🇦 🇯🇵 🇰🇷 🇨🇳 🇮🇹 🇫🇷 🇩🇪 🇬🇧 🇵🇹 🇨🇺
        🇩🇴 🇵🇦 🇨🇷 🇬🇹 🇭🇳 🇸🇻 🇳🇮 🇵🇷
    """)),
)

/** Panel de emojis: solo la cuadrícula, con ABC y borrar abajo (sin categorías). */
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
    private val grilla = EmojiGrid(context, tema) {
        acciones.retro(Tipo.LETRA)
        acciones.emoji(it)
    }
    private val scroll = ScrollView(context)

    init {
        orientation = VERTICAL

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(grilla, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            setOnClickListener {
                acciones.retro(Tipo.A_LETRAS)
                acciones.volverABC()
            }
        }
        abajo.addView(abc, LayoutParams(dp(60f), LayoutParams.MATCH_PARENT))
        abajo.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val borrar = BotonBorrar(context, tema, { acciones.retro(Tipo.BORRAR) }, { acciones.borrar() })
        abajo.addView(borrar, LayoutParams(dp(60f), LayoutParams.MATCH_PARENT))
        addView(abajo, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        refrescar()
    }

    fun refrescar() {
        if (grilla.secciones.isEmpty()) {
            grilla.secciones = CATEGORIAS.map { EmojiGrid.Seccion(it.nombre, it.emojis) }
        }
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

    private val d = Medidas.escala(context)
    private val columnas = 8
    private val altoTitulo = 30f * d
    private var celda = 0f
    private val tops = ArrayList<Float>()
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
    }
    private val pinturaTitulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var presionado: Pair<Int, Int>? = null
    private var x0 = 0f
    private var y0 = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun filasDe(n: Int) = (n + columnas - 1) / columnas

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ancho = MeasureSpec.getSize(widthMeasureSpec)
        celda = ancho / columnas.toFloat()
        tops.clear()
        var y = 0f
        for (s in secciones) {
            tops.add(y)
            y += altoTitulo + filasDe(s.emojis.size) * celda
        }
        setMeasuredDimension(ancho, (y + 8f * d).toInt())
    }

    fun topDe(i: Int): Int = tops.getOrNull(i)?.toInt() ?: 0

    fun seccionEn(y: Int): Int {
        var r = 0
        for (i in tops.indices) if (tops[i] <= y + altoTitulo) r = i
        return r
    }

    override fun onDraw(c: Canvas) {
        val clip = c.clipBounds
        pintura.textSize = celda * 0.64f
        val fm = pintura.fontMetrics
        pinturaTitulo.color = tema.textoSecundario
        secciones.forEachIndexed { i, s ->
            val top = tops.getOrElse(i) { 0f }
            c.drawText(s.titulo, 12f * d, top + altoTitulo * 0.68f, pinturaTitulo)
            val inicio = top + altoTitulo
            s.emojis.forEachIndexed { j, e ->
                val y = inicio + (j / columnas) * celda
                if (y + celda < clip.top || y > clip.bottom) return@forEachIndexed
                val x = (j % columnas) * celda
                if (presionado == Pair(i, j)) {
                    fondo.color = tema.teclaPresionada
                    c.drawRoundRect(x + 2 * d, y + 2 * d, x + celda - 2 * d, y + celda - 2 * d, 10 * d, 10 * d, fondo)
                }
                c.drawText(e, x + celda / 2f, y + celda / 2f - (fm.ascent + fm.descent) / 2f, pintura)
            }
        }
    }

    private fun buscar(x: Float, y: Float): Pair<Int, Int>? {
        for (i in secciones.indices) {
            val inicio = tops.getOrElse(i) { 0f } + altoTitulo
            val fin = inicio + filasDe(secciones[i].emojis.size) * celda
            if (y in inicio..fin && celda > 0f) {
                val j = ((y - inicio) / celda).toInt() * columnas + (x / celda).toInt().coerceIn(0, columnas - 1)
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
