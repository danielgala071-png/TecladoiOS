package com.tecladoios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.ScrollView
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
import java.text.Collator
import java.util.Locale

class Categoria(val icono: String, val nombre: String, private val crudos: List<String>) {
    /** Solo los emojis que tu celular puede dibujar (los más nuevos dependen de la versión de Android) */
    val emojis: List<String> by lazy {
        val p = Paint()
        crudos.distinct().filter { it.isNotBlank() && p.hasGlyph(it) }
    }
}

private fun lista(s: String) = s.trim().split(Regex("\\s+"))

/** Todas las banderas de países: Perú primero y luego en orden alfabético (en español) */
private fun banderasPaises(): List<String> {
    val es = Locale("es")
    val orden = Collator.getInstance(es)
    fun bandera(cc: String) = cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    val paises = Locale.getISOCountries().filter { it != "PE" }
        .sortedWith { x, y -> orden.compare(Locale("", x).getDisplayCountry(es), Locale("", y).getDisplayCountry(es)) }
    return listOf(bandera("PE")) + paises.map { bandera(it) }
}

/** Mismas categorías, en el mismo orden que el teclado del iPhone (iOS 18) */
val CATEGORIAS = listOf(
    Categoria("🕘", "USADOS FRECUENTEMENTE", emptyList()),
    Categoria("😀", "EMOTICONOS Y PERSONAS", lista("""
        😀 😃 😄 😁 😆 🥹 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳
        🙂‍↕️ 😏 😒 🙂‍↔️ 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😮‍💨 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣
        🤗 🫡 🤔 🫢 🤭 🤫 🤥 😶 😶‍🌫️ 😐 😑 😬 🫨 🫠 🙄 😯 😦 😧 😮 😲 🥱 🫩 😴 🤤 😪 😵 😵‍💫 🫥 🤐 🥴 🤢 🤮 🤧 😷
        🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾
        👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 🫵 🫱 🫲 🫸 🫷 🫳 🫴 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏
        🫶 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦵 🦿 🦶 👣 👂 🦻 👃 🫀 🫁 🧠 🦷 🦴 👀 👁️ 👅 👄 🫦 💋 🩸
        👶 👧 🧒 👦 👩 🧑 👨 👩‍🦱 🧑‍🦱 👨‍🦱 👩‍🦰 🧑‍🦰 👨‍🦰 👱‍♀️ 👱 👱‍♂️ 👩‍🦳 🧑‍🦳 👨‍🦳 👩‍🦲 🧑‍🦲 👨‍🦲 🧔‍♀️ 🧔 🧔‍♂️
        👵 🧓 👴 👲 👳‍♀️ 👳 👳‍♂️ 🧕 👮‍♀️ 👮 👮‍♂️ 👷‍♀️ 👷 👷‍♂️ 💂‍♀️ 💂 💂‍♂️ 🕵️‍♀️ 🕵️ 🕵️‍♂️ 👩‍⚕️ 🧑‍⚕️ 👨‍⚕️
        👩‍🌾 🧑‍🌾 👨‍🌾 👩‍🍳 🧑‍🍳 👨‍🍳 👩‍🎓 🧑‍🎓 👨‍🎓 👩‍🎤 🧑‍🎤 👨‍🎤 👩‍🏫 🧑‍🏫 👨‍🏫 👩‍🏭 🧑‍🏭 👨‍🏭 👩‍💻 🧑‍💻 👨‍💻
        👩‍💼 🧑‍💼 👨‍💼 👩‍🔧 🧑‍🔧 👨‍🔧 👩‍🔬 🧑‍🔬 👨‍🔬 👩‍🎨 🧑‍🎨 👨‍🎨 👩‍🚒 🧑‍🚒 👨‍🚒 👩‍✈️ 🧑‍✈️ 👨‍✈️ 👩‍🚀 🧑‍🚀 👨‍🚀
        👩‍⚖️ 🧑‍⚖️ 👨‍⚖️ 👰‍♀️ 👰 👰‍♂️ 🤵‍♀️ 🤵 🤵‍♂️ 👸 🫅 🤴 🥷 🦸‍♀️ 🦸 🦸‍♂️ 🦹‍♀️ 🦹 🦹‍♂️ 🤶 🧑‍🎄 🎅
        🧙‍♀️ 🧙 🧙‍♂️ 🧝‍♀️ 🧝 🧝‍♂️ 🧌 🧛‍♀️ 🧛 🧛‍♂️ 🧟‍♀️ 🧟 🧟‍♂️ 🧞‍♀️ 🧞 🧞‍♂️ 🧜‍♀️ 🧜 🧜‍♂️ 🧚‍♀️ 🧚 🧚‍♂️ 👼
        🤰 🫄 🫃 🤱 👩‍🍼 🧑‍🍼 👨‍🍼 🙇‍♀️ 🙇 🙇‍♂️ 💁‍♀️ 💁 💁‍♂️ 🙅‍♀️ 🙅 🙅‍♂️ 🙆‍♀️ 🙆 🙆‍♂️ 🙋‍♀️ 🙋 🙋‍♂️
        🧏‍♀️ 🧏 🧏‍♂️ 🤦‍♀️ 🤦 🤦‍♂️ 🤷‍♀️ 🤷 🤷‍♂️ 🙎‍♀️ 🙎 🙎‍♂️ 🙍‍♀️ 🙍 🙍‍♂️ 💇‍♀️ 💇 💇‍♂️ 💆‍♀️ 💆 💆‍♂️ 🧖‍♀️ 🧖 🧖‍♂️
        💃 🕺 👯‍♀️ 👯 👯‍♂️ 🕴️ 👩‍🦽 🧑‍🦽 👨‍🦽 👩‍🦼 🧑‍🦼 👨‍🦼 🚶‍♀️ 🚶 🚶‍♂️ 👩‍🦯 🧑‍🦯 👨‍🦯 🧎‍♀️ 🧎 🧎‍♂️
        🏃‍♀️ 🏃 🏃‍♂️ 🧍‍♀️ 🧍 🧍‍♂️ 👭 🧑‍🤝‍🧑 👬 👫 👩‍❤️‍👩 💑 👨‍❤️‍👨 👩‍❤️‍👨 👩‍❤️‍💋‍👩 💏 👨‍❤️‍💋‍👨 👩‍❤️‍💋‍👨
        👪 👨‍👩‍👦 👨‍👩‍👧 👨‍👩‍👧‍👦 👨‍👩‍👦‍👦 👨‍👩‍👧‍👧 👩‍👩‍👦 👩‍👩‍👧 👨‍👨‍👦 👨‍👨‍👧 👩‍👦 👩‍👧 👨‍👦 👨‍👧 🗣️ 👤 👥 🫂
        🧳 🌂 ☂️ 🧵 🪡 🪢 🪭 🧶 👓 🕶️ 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩴 🩱 🩲 🩳 👙 👚 👛 👜 👝 🎒
        👞 👟 🥾 🥿 👠 👡 🩰 👢 🪮 👑 👒 🎩 🎓 🧢 ⛑️ 🪖 💄 💍 💼
    """)),
    Categoria("🐻", "ANIMALES Y NATURALEZA", lista("""
        🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐦‍⬛ 🐤 🐣 🐥 🪿 🦆 🐦‍🔥
        🦅 🦉 🦇 🐺 🐗 🐴 🦄 🫎 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🪼 🦐
        🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🦭 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🫏 🐎 🐖 🐏
        🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐕‍🦺 🐈 🐈‍⬛ 🪶 🪽 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️
        🦔 🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🪾 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🪺 🪹 🍄 🍄‍🟫 🐚 🪸 🪨 🌾 💐 🌷
        🌹 🥀 🪻 🪷 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️
        💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🫧 ☔ ☂️ 🌊 🌫️
    """)),
    Categoria("🍔", "COMIDA Y BEBIDA", lista("""
        🍏 🍎 🍐 🍊 🍋 🍋‍🟩 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🫛 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒
        🧄 🧅 🫚 🥔 🍠 🫘 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮
        🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮
        🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🫙 🍶 🍺 🍻 🥂 🍷 🫗 🥃 🍸 🍹 🧉 🍾 🧊 🥄
        🍴 🍽️ 🥣 🥡 🥢 🧂
    """)),
    Categoria("⚽", "ACTIVIDADES", lista("""
        ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌
        🎿 ⛷️ 🏂 🪂 🏋️‍♀️ 🏋️ 🏋️‍♂️ 🤼‍♀️ 🤼 🤼‍♂️ 🤸‍♀️ 🤸 🤸‍♂️ ⛹️‍♀️ ⛹️ ⛹️‍♂️ 🤺 🤾‍♀️ 🤾 🤾‍♂️ 🏌️‍♀️ 🏌️ 🏌️‍♂️ 🏇
        🧘‍♀️ 🧘 🧘‍♂️ 🏄‍♀️ 🏄 🏄‍♂️ 🏊‍♀️ 🏊 🏊‍♂️ 🤽‍♀️ 🤽 🤽‍♂️ 🚣‍♀️ 🚣 🚣‍♂️ 🧗‍♀️ 🧗 🧗‍♂️ 🚵‍♀️ 🚵 🚵‍♂️ 🚴‍♀️ 🚴 🚴‍♂️
        🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🤹‍♀️ 🤹 🤹‍♂️ 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🪇 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻
        🪈 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩
    """)),
    Categoria("🚗", "VIAJES Y LUGARES", lista("""
        🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🩼 🛴 🚲 🛵 🏍️ 🛺 🛞 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟
        🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 🛟 ⚓ 🪝 ⛽
        🚧 🚦 🚥 🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🛝 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️
        🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋 ⛩️ 🛤️ 🛣️ 🗾 🎑 🏞️ 🌅 🌄 🌠 🎇 🎆 🌇
        🌆 🏙️ 🌃 🌌 🌉 🌁
    """)),
    Categoria("💡", "OBJETOS", lista("""
        ⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️
        🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 🪪 💎 ⚖️ 🪜 🧰 🪛
        🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ ⛓️‍💥 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 🪬 💈
        ⚗️ 🔭 🔬 🕳️ 🩻 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚰 🚿 🛁 🛀 🧼 🪥 🪒 🧽 🪣 🧴 🛎️
        🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🛌 🧸 🪆 🖼️ 🪞 🪟 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🪩 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧
        💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁
        📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️
        🔍 🔎 🔏 🔐 🔒 🔓
    """)),
    Categoria("&%", "SÍMBOLOS", lista("""
        ❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🖤 🩶 🤍 🤎 ❤️‍🔥 ❤️‍🩹 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🪯 🕉️ ☸️ ✡️
        🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚 💮 🉐
        ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔
        ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿ 🅿️ 🛗 🈳 🈂️ 🛂 🛃
        🛄 🛅 🛜 🚹 🚺 🚼 ⚧️ 🚻 🚮 🎦 📶 🈁 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣
        8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️
        ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ™️ ©️ ®️ 〰️ ➰ ➿ 🔚 🔙 🔛 🔝 🔜 ✔️
        ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛
        ⬜ 🟫 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 👁️‍🗨️ 💬 💭 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙
        🕚 🕛 🕜 🕝 🕞 🕟 🕠 🕡 🕢 🕣 🕤 🕥 🕦 🕧
    """)),
    Categoria("🏳️", "BANDERAS", lista("🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇺🇳") + banderasPaises()),
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
        /** Tocaste "Buscar emoji" */
        fun buscarEmoji()
        /** Lo que copiaste últimamente (fijados primero) */
        fun historialCopiado(): List<Portapapeles.Elemento>
        /** Fija o suelta algo del historial; devuelve si quedó fijado */
        fun fijarCopiado(texto: String): Boolean
        fun quitarCopiado(texto: String)
        /** Pegar algo del historial */
        fun pegar(texto: String)
    }

    private val d = Medidas.escala(context)
    private fun dp(v: Float) = (v * d).toInt()
    private val prefs = Ajustes.prefs(context)
    private val grilla = EmojiGrid(context, tema) { elegido(it) }.apply {
        alAbrirTonos = { acciones.retro(Tipo.LETRA) }
    }
    private val scroll = HorizontalScrollView(context)
    private val categorias = BarraCategorias(context, tema) { i ->
        acciones.retro(Tipo.A_LETRAS)
        if (enCopiado) mostrarCopiado(false)
        // Como en iPhone: salta directo a la categoría
        scroll.scrollTo(grilla.inicioDe(i), 0)
    }

    /** Historial del portapapeles (se muestra en lugar de los emojis) */
    private val listaCopiado = LinearLayout(context).apply { orientation = VERTICAL }
    private val scrollCopiado = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.GONE
        addView(listaCopiado, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val buscador: TextView
    private val botonCopiado: TextView
    private var enCopiado = false

    /** Fondo de vidrio con el filo de la app (como las teclas) */
    private fun vidrio(radio: Float, conBorde: Boolean = true) = GradientDrawable().apply {
        setColor(tema.tecla)
        cornerRadius = radio
        if (conBorde) setStroke(maxOf(1, dp(0.7f)), (tema.borde and 0x00FFFFFF) or 0x99000000.toInt())
    }

    init {
        orientation = VERTICAL

        // "Buscar emoji" arriba, como en iOS: al tocarlo vuelven las letras y escribes para buscar
        buscador = TextView(context).apply {
            text = "\uD83D\uDD0D  Buscar emoji"
            textSize = 14f
            setTextColor(tema.textoSecundario)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            Fuentes.aplicar(this, 400)
            background = vidrio(dp(16f).toFloat())
            setOnClickListener {
                acciones.retro(Tipo.A_LETRAS)
                acciones.buscarEmoji()
            }
        }
        addView(buscador, LayoutParams(LayoutParams.MATCH_PARENT, dp(32f)).apply {
            setMargins(dp(10f), dp(6f), dp(10f), dp(2f))
        })

        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(grilla, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.setOnScrollChangeListener { _, x, _, _, _ ->
            grilla.desplazamiento = x
            categorias.marcado = grilla.seccionEn(x)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(scrollCopiado, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

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
        // Portapapeles: lo último que copiaste, para pegarlo con un toque
        botonCopiado = TextView(context).apply {
            text = "\uD83D\uDCCB"
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener {
                acciones.retro(Tipo.A_LETRAS)
                mostrarCopiado(!enCopiado)
            }
        }
        abajo.addView(botonCopiado, LayoutParams(dp(40f), LayoutParams.MATCH_PARENT))
        abajo.addView(categorias, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val borrar = BotonBorrar(context, tema, { acciones.retro(Tipo.BORRAR) }, { acciones.borrar() })
        abajo.addView(borrar, LayoutParams(dp(52f), LayoutParams.MATCH_PARENT))
        addView(abajo, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        refrescar()
    }

    private fun recientes(): List<String> = recientesDe(prefs)

    /** Se llama al abrir el panel: actualiza "Usados frecuentemente" y vuelve al inicio */
    fun refrescar() {
        mostrarCopiado(false)
        scroll.scrollTo(0, 0)
        categorias.marcado = 0
        grilla.secciones = listOf(EmojiGrid.Seccion(CATEGORIAS[0].nombre, recientes())) +
                CATEGORIAS.drop(1).map { EmojiGrid.Seccion(it.nombre, it.emojis) }
    }

    private fun elegido(e: String) {
        acciones.retro(Tipo.LETRA)
        acciones.emoji(e)
        sumarReciente(context, e)
    }

    /** Muestra el historial del portapapeles en lugar de los emojis (o vuelve a los emojis) */
    private fun mostrarCopiado(si: Boolean) {
        enCopiado = si
        grilla.cerrarTonos()
        scroll.visibility = if (si) View.GONE else View.VISIBLE
        buscador.visibility = if (si) View.GONE else View.VISIBLE
        scrollCopiado.visibility = if (si) View.VISIBLE else View.GONE
        botonCopiado.background = if (si) vidrio(dp(14f).toFloat(), conBorde = false) else null
        categorias.alpha = if (si) 0.45f else 1f
        if (si) armarCopiado()
    }

    private fun armarCopiado() {
        listaCopiado.removeAllViews()
        val elementos = try { acciones.historialCopiado() } catch (_: Exception) { emptyList() }
        listaCopiado.addView(TextView(context).apply {
            text = if (elementos.isEmpty()) "Lo que copies aparecerá aquí."
                else "Toca para pegar · mantén presionado para fijar o quitar"
            textSize = 12f
            setTextColor(tema.textoSecundario)
            gravity = Gravity.CENTER
            Fuentes.aplicar(this, 400)
            setPadding(dp(12f), dp(if (elementos.isEmpty()) 40f else 8f), dp(12f), dp(6f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        for (e in elementos) {
            val fila = TextView(context).apply {
                text = (if (e.fijado) "\uD83D\uDCCC  " else "") + e.texto.replace(Regex("\\s+"), " ").trim()
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(tema.texto)
                Fuentes.aplicar(this, 400)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = vidrio(dp(12f).toFloat())
                setOnClickListener { acciones.pegar(e.texto) }
                setOnLongClickListener {
                    acciones.retro(Tipo.BORRAR)
                    ofrecerOpciones(e)
                    true
                }
            }
            listaCopiado.addView(fila, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10f), dp(3f), dp(10f), dp(3f))
            })
        }
    }

    /** Mantener presionado: fijar/soltar y quitar, como dos botones en la misma fila */
    private fun ofrecerOpciones(e: Portapapeles.Elemento) {
        listaCopiado.removeAllViews()
        val caja = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(10f), dp(16f), dp(10f), dp(8f))
        }
        fun boton(t: String, color: Int, accion: () -> Unit) = TextView(context).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color)
            Fuentes.aplicar(this, 500)
            background = vidrio(dp(12f).toFloat())
            setOnClickListener {
                acciones.retro(Tipo.A_LETRAS)
                accion()
                armarCopiado()
            }
        }
        caja.addView(boton(if (e.fijado) "Soltar" else "Fijar", tema.texto) { acciones.fijarCopiado(e.texto) },
            LayoutParams(0, dp(44f), 1f).apply { setMargins(0, 0, dp(6f), 0) })
        caja.addView(boton("Quitar", 0xFFFF453A.toInt()) { acciones.quitarCopiado(e.texto) },
            LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(6f), 0, 0, 0) })
        caja.addView(boton("Listo", tema.textoSecundario) { },
            LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(12f), 0, 0, 0) })
        listaCopiado.addView(TextView(context).apply {
            text = e.texto.replace(Regex("\\s+"), " ").trim()
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(tema.textoSecundario)
            setPadding(dp(14f), dp(14f), dp(14f), 0)
        })
        listaCopiado.addView(caja)
    }

    /** Al cerrar el panel: se cierra la ventanita de tonos si quedó abierta */
    fun cerrarTonos() = grilla.cerrarTonos()

    companion object {
        /** Los emojis que más usas (para la búsqueda, antes de escribir) */
        fun recientes(c: Context): List<String> = recientesDe(Ajustes.prefs(c))

        private fun recientesDe(prefs: android.content.SharedPreferences): List<String> {
            val guardados = prefs.getString("recientes", null)
            return if (guardados.isNullOrBlank()) lista("😂 ❤️ 😍 🙏 😭 👍 😊 🔥 🥰 😅 🤣 💀 😘 🥺 😁 ✨ 😎 🤔 😢 👀")
            else guardados.split(" ").filter { it.isNotBlank() }.take(30)
        }

        /** Suma un emoji a "Usados frecuentemente" (también los que eliges en la barra de sugerencias) */
        fun sumarReciente(c: Context, e: String) {
            val prefs = Ajustes.prefs(c)
            val nuevos = recientesDe(prefs).toMutableList()
            nuevos.remove(e)
            nuevos.add(0, e)
            // Como en iPhone: hasta 30 (6 columnas de 5)
            prefs.edit().putString("recientes", nuevos.take(30).joinToString(" ")).apply()
        }
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
        gris.textSize = 17f * d
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
        set(v) { if (v != field) { field = v; invalidate() } }

    private val d = Medidas.escala(context)
    /** Igual que el iPhone: 5 filas, con aire entre emoji y emoji */
    private val filas = 5
    private val altoTitulo = 30f * d
    private val margen = 10f * d
    private val separacion = 12f * d
    /** Ancho de cada columna: se ven unas 8 columnas y media (la media invita a deslizar) */
    private val anchoCelda: Float
    private var altoCelda = 0f
    private val inicios = ArrayList<Float>()
    private val finales = ArrayList<Float>()
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
    }
    private val pinturaTitulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * d
        letterSpacing = 0.02f
    }.also { Fuentes.aplicar(it, 600) }
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var presionado: Pair<Int, Int>? = null
    private var x0 = 0f
    private var y0 = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // ---------- Tonos de piel (mantener presionado, como en iPhone) ----------

    /** Avisa al abrir los tonos (para vibrar) */
    var alAbrirTonos: (() -> Unit)? = null
    private val prefsTonos = Ajustes.prefs(context)
    /** Tono elegido para cada emoji: 👍 → 👍🏽 (se recuerda y se ve así en el panel) */
    private val tonos: HashMap<String, String> = HashMap<String, String>().apply {
        for (par in (prefsTonos.getString("tonos", "") ?: "").split('|')) {
            val i = par.indexOf('=')
            if (i > 0 && i < par.length - 1) put(par.substring(0, i), par.substring(i + 1))
        }
    }
    private var popupTonos: VariantesPopup? = null
    private var tonosAbiertos = false
    /** El emoji base (sin tono) de la ventanita abierta */
    private var baseTonos = ""
    private val abrirTonos = Runnable { mostrarTonos() }
    private val manejadorTonos = Handler(Looper.getMainLooper())
    private val rectTonos = android.graphics.RectF()
    private val pinturaPrueba = Paint()

    /** Lo que se dibuja y se escribe para [e]: con el tono que elegiste, si elegiste uno */
    private fun conTono(e: String) = tonos[e] ?: e

    private fun mostrarTonos() {
        val p = presionado ?: return
        val visto = conTono(secciones.getOrNull(p.first)?.emojis?.getOrNull(p.second) ?: return)
        val base = sinTono(visto)
        val opciones = listOf(base) + TONOS.map { conModificador(base, it) }.filter { pinturaPrueba.hasGlyph(it) }
        if (opciones.size <= 1) return
        val ancla = parent as? View ?: return
        val col = p.second / filas
        val fila = p.second % filas
        val x = (inicios.getOrElse(p.first) { 0f }) + col * anchoCelda - desplazamiento
        val y = altoTitulo + fila * altoCelda
        rectTonos.set(x, y, x + anchoCelda, y + altoCelda)
        val popup = popupTonos ?: VariantesPopup(ancla).also { popupTonos = it }
        popup.mostrar(rectTonos, opciones, ancla.width.toFloat(), tema.oscuro, tema.azul)
        baseTonos = base
        tonosAbiertos = true
        presionado = null
        // El panel no se desliza mientras eliges el tono
        parent?.requestDisallowInterceptTouchEvent(true)
        alAbrirTonos?.invoke()
        invalidate()
    }

    fun cerrarTonos() {
        manejadorTonos.removeCallbacks(abrirTonos)
        popupTonos?.cerrar()
        tonosAbiertos = false
    }

    private fun guardarTono(base: String, elegido: String) {
        // Se recuerda para el emoji base y para cualquier versión con tono que aparezca en el panel
        if (elegido == base) tonos.remove(base) else tonos[base] = elegido
        prefsTonos.edit().putString("tonos", tonos.entries.joinToString("|") { "${it.key}=${it.value}" }).apply()
        invalidate()
    }

    init {
        val m = resources.displayMetrics
        val columnasVisibles = if (Medidas.horizontal(context)) 14.5f else 8.5f
        anchoCelda = (m.widthPixels - margen) / columnasVisibles
    }

    private fun columnasDe(n: Int) = (n + filas - 1) / filas

    fun inicioDe(i: Int): Int = ((inicios.getOrNull(i) ?: 0f) - margen).toInt().coerceAtLeast(0)

    fun seccionEn(x: Int): Int {
        var r = 0
        for (i in inicios.indices) if (inicios[i] <= x + margen + anchoCelda) r = i
        return r
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alto = MeasureSpec.getSize(heightMeasureSpec)
        altoCelda = ((alto - altoTitulo - 6f * d) / filas).coerceAtLeast(1f)
        inicios.clear()
        finales.clear()
        var x = margen
        for (s in secciones) {
            inicios.add(x)
            // Como en iOS, cada sección mide lo que miden sus emojis (sin huecos)
            x += maxOf(1, columnasDe(s.emojis.size)) * anchoCelda
            finales.add(x)
            x += separacion
        }
        setMeasuredDimension((x + margen).toInt(), alto)
    }

    override fun onDraw(c: Canvas) {
        // Con aceleración por hardware el lienzo no sabe qué parte se ve, así que se calcula
        // con el desplazamiento: solo se dibujan las ~9 columnas visibles (deslizar fluido)
        val anchoVisible = (parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val visIzq = desplazamiento - anchoCelda
        val visDer = desplazamiento + anchoVisible + anchoCelda
        // Emoji grande y con aire alrededor, como en el iPhone
        pintura.textSize = minOf(altoCelda * 0.80f, anchoCelda * 0.70f)
        val fm = pintura.fontMetrics
        val centroY = -(fm.ascent + fm.descent) / 2f
        pinturaTitulo.color = tema.textoSecundario
        val fmt = pinturaTitulo.fontMetrics
        val yTitulo = altoTitulo * 0.5f - (fmt.ascent + fmt.descent) / 2f + 2f * d
        // Sin crear objetos por cada emoji dibujado (deslizar más fluido)
        val pi = presionado?.first ?: -1
        val pj = presionado?.second ?: -1
        secciones.forEachIndexed { i, s ->
            val ini = inicios.getOrElse(i) { 0f }
            val fin = finales.getOrElse(i) { 0f }
            if (fin + separacion < visIzq || ini > visDer) return@forEachIndexed

            // Título fijo a la izquierda mientras se ve su sección; el siguiente lo empuja
            val anchoTitulo = pinturaTitulo.measureText(s.titulo)
            val siguiente = inicios.getOrElse(i + 1) { Float.MAX_VALUE }
            val tope = siguiente - separacion - anchoTitulo
            val xt = maxOf(ini + 2f * d, minOf(desplazamiento + margen + 2f * d, tope))
            c.save()
            c.clipRect(ini, 0f, minOf(siguiente - 4f * d, ini + 100000f), altoTitulo)
            c.drawText(s.titulo, xt, yTitulo, pinturaTitulo)
            c.restore()

            // Solo se dibujan las columnas que se ven (cientos de emojis no se tocan)
            val total = columnasDe(s.emojis.size)
            val desdeCol = ((visIzq - ini) / anchoCelda).toInt().coerceAtLeast(0)
            val hastaCol = ((visDer - ini) / anchoCelda).toInt().coerceAtMost(total - 1)
            for (col in desdeCol..hastaCol) {
                val x = ini + col * anchoCelda
                for (fila in 0 until filas) {
                    val j = col * filas + fila
                    if (j >= s.emojis.size) break
                    val y = altoTitulo + fila * altoCelda
                    if (pi == i && pj == j) {
                        fondo.color = (tema.teclaPresionada and 0x00FFFFFF) or 0x99000000.toInt()
                        val r = 9f * d
                        c.drawRoundRect(x + 2f * d, y + 1f * d, x + anchoCelda - 2f * d, y + altoCelda - 1f * d, r, r, fondo)
                    }
                    c.drawText(conTono(s.emojis[j]), x + anchoCelda / 2f, y + altoCelda / 2f + centroY, pintura)
                }
            }
        }
    }

    private fun buscar(x: Float, y: Float): Pair<Int, Int>? {
        if (altoCelda <= 0f || y < altoTitulo) return null
        for (i in secciones.indices) {
            val ini = inicios.getOrElse(i) { 0f }
            val fin = finales.getOrElse(i) { 0f }
            if (x in ini..fin) {
                val col = ((x - ini) / anchoCelda).toInt()
                val fila = ((y - altoTitulo) / altoCelda).toInt().coerceIn(0, filas - 1)
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
                cerrarTonos()
                x0 = e.x; y0 = e.y
                presionado = buscar(e.x, e.y)
                if (presionado != null) manejadorTonos.postDelayed(abrirTonos, 420)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tonosAbiertos) {
                    popupTonos?.mover(e.x - desplazamiento)
                    return true
                }
                // Al deslizar ya no es un toque: se quita el resaltado
                if (presionado != null && (Math.abs(e.x - x0) > slop || Math.abs(e.y - y0) > slop)) {
                    manejadorTonos.removeCallbacks(abrirTonos)
                    presionado = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                manejadorTonos.removeCallbacks(abrirTonos)
                if (tonosAbiertos) {
                    val elegido = popupTonos?.elegido()
                    cerrarTonos()
                    if (elegido != null) {
                        guardarTono(baseTonos, elegido)
                        alElegir(elegido)
                    }
                    return true
                }
                val p = presionado
                presionado = null
                invalidate()
                val quieto = Math.abs(e.x - x0) < slop && Math.abs(e.y - y0) < slop
                if (p != null && quieto) alElegir(conTono(secciones[p.first].emojis[p.second]))
            }
            MotionEvent.ACTION_CANCEL -> {
                // Si los tonos estaban abiertos, se cierran sin elegir nada
                cerrarTonos()
                presionado = null
                invalidate()
            }
        }
        return true
    }
}

/** Los cinco tonos de piel (claro → oscuro) */
private val TONOS = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

/** El emoji sin tono de piel: 👍🏽 → 👍 */
private fun sinTono(e: String): String {
    var r = e
    for (t in TONOS) r = r.replace(t, "")
    return r
}

/**
 * El emoji con el tono [tono]: va justo después del primer carácter (y sin el selector
 * de variante que algunos traen): ✌️ → ✌🏽, 🧑‍💻 → 🧑🏽‍💻, 🏃‍♀️ → 🏃🏽‍♀️.
 */
private fun conModificador(base: String, tono: String): String {
    if (base.isEmpty()) return base
    val primero = Character.charCount(base.codePointAt(0))
    var resto = base.substring(primero)
    if (resto.startsWith("\uFE0F")) resto = resto.substring(1)
    return base.substring(0, primero) + tono + resto
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
