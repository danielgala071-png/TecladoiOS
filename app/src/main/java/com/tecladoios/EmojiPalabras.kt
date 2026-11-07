package com.tecladoios

import java.text.Normalizer

/**
 * Emoji sugerido en la barra según la palabra que escribes, como en el iPhone:
 * "jaja" → 😂, "corazón" → ❤️, "cumpleaños" → 🎂. Pensado para el chat peruano.
 * Buscar es instantáneo (un solo mapa en memoria, se arma la primera vez que se usa).
 */
object EmojiPalabras {

    private val MARCAS = Regex("\\p{Mn}+")
    private val RISA = Regex("^(j+[aeiou]+)+j*$|^(js)+j?$|^(ja|je|ji|jo|ha|he|ka)+$|^x+d+$")

    private fun normal(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARCAS, "")

    private val mapa: Map<String, String> by lazy {
        val m = HashMap<String, String>(512)
        for (linea in DATOS.lines()) {
            val i = linea.indexOf(':')
            if (i <= 0) continue
            val emoji = linea.substring(i + 1).trim()
            if (emoji.isEmpty()) continue
            for (p in linea.substring(0, i).split(',')) {
                val clave = normal(p.trim())
                if (clave.isNotEmpty()) m.putIfAbsent(clave, emoji)
            }
        }
        m
    }

    /** El emoji para [palabra], o null si no hay uno que tenga sentido */
    fun para(palabra: String?): String? {
        if (palabra == null || palabra.length < 2) return null
        val n = normal(palabra)
        if (n.length >= 4 && RISA.matches(n)) return "😂"
        mapa[n]?.let { return it }
        // Plurales: "corazones" → "corazon", "perros" → "perro"
        if (n.length > 4 && n.endsWith("es")) mapa[n.dropLast(2)]?.let { return it }
        if (n.length > 3 && n.endsWith("s")) mapa[n.dropLast(1)]?.let { return it }
        // Diminutivos comunes: "perrito" → "perro", "besito" → "beso"
        if (n.length > 5 && (n.endsWith("ito") || n.endsWith("ita"))) {
            val raiz = n.dropLast(3)
            mapa[raiz + "o"]?.let { return it }
            mapa[raiz + "a"]?.let { return it }
            mapa[raiz]?.let { return it }
        }
        return null
    }

    /**
     * Buscar emojis escribiendo ("risa", "cora", "perr"): los que tienen una palabra que empieza
     * así, primero los exactos y después los de palabra más corta. Sin tildes ni mayúsculas.
     */
    fun buscar(consulta: String, max: Int): List<String> {
        val q = normal(consulta.trim())
        if (q.isEmpty()) return emptyList()
        val puntos = LinkedHashMap<String, Int>()
        if (q.length >= 4 && RISA.matches(q)) puntos["😂"] = 0
        for ((clave, emoji) in mapa) {
            val p = when {
                clave == q -> 1
                clave.startsWith(q) -> 10 + clave.length
                clave.split(' ').any { it.startsWith(q) } -> 40 + clave.length
                else -> continue
            }
            val previo = puntos[emoji]
            if (previo == null || p < previo) puntos[emoji] = p
        }
        return puntos.entries.sortedBy { it.value }.take(max).map { it.key }
    }

    /** palabras separadas por coma : emoji */
    private const val DATOS = """
risa, jaja, jeje, jiji, lol, xd, muerto de risa, carcajada: 😂
jajaja, jsjs: 🤣
feliz, contento, contenta, alegre, alegria: 😊
sonrisa, sonriendo: 😁
amor, te amo, amo, enamorado, enamorada, corazon, querido, querida: ❤️
te quiero, cariño, carino: 🥰
beso, besos, muak, mua: 😘
lindo, linda, hermoso, hermosa, precioso, preciosa, bello, bella: 😍
triste, pena, tristeza, sad: 😢
llorar, llorando, lloro: 😭
enojado, enojada, molesto, molesta, colera, furioso, furiosa, rabia, cólera: 😡
miedo, susto, asustado, asustada, terror: 😱
sorpresa, sorprendido, sorprendida, wow, guau: 😮
pensar, pensando, hmm, duda, dudas: 🤔
cansado, cansada, sueño, sueno, dormir, durmiendo, zzz: 😴
aburrido, aburrida: 😑
nervioso, nerviosa: 😬
verguenza, roche, vergüenza: 😳
loco, loca, locura: 🤪
genial, bacan, chevere, paja, buenazo, cool: 😎
ok, okey, listo, dale, perfecto, excelente: 👍
pesimo, horrible: 👎
gracias, porfa, por favor, amen, ojala, rezar, bendiciones, bendicion: 🙏
aplausos, bravo, felicidades, felicitaciones: 👏
fuerza, animo: 💪
hola, chau, chao, adios, bye: 👋
fuego, caliente, calor, candela: 🔥
frio, congelado: 🥶
fiesta, juerga, tono, celebrar, celebracion: 🎉
cumpleaños, cumple, torta, pastel: 🎂
regalo, regalos, sorpresita: 🎁
musica, cancion, cantar: 🎶
baile, bailar, bailando: 💃
estrella, estrellas, brillo: ✨
sol, soleado, verano: ☀️
luna, noche, buenas noches: 🌙
lluvia, llueve, lloviendo: 🌧️
flor, flores, rosa: 🌹
planta, plantas: 🌱
arbol: 🌳
navidad: 🎄
año nuevo, ano nuevo: 🎆
perro, perrito, firulais, can: 🐶
gato, gatito, michi, miau: 🐱
pollito: 🐥
pez, pescado: 🐟
mono: 🐒
caballo: 🐴
vaca: 🐮
cerdo, chancho: 🐷
leon: 🦁
oso: 🐻
mariposa: 🦋
cafe, cafecito, desayuno: ☕
cerveza, chela, chelas, cervezas: 🍺
vino: 🍷
pisco, trago, tragos, coctel: 🍸
comida, comer, hambre, almuerzo, almorzar, cena, cenar: 🍽️
pizza: 🍕
hamburguesa: 🍔
pan: 🍞
pollo, brasa: 🍗
ceviche: 🐟
helado, heladito: 🍦
chocolate: 🍫
fruta, manzana: 🍎
platano: 🍌
agua: 💧
dinero, plata, sencillo, billete, lucas, soles, pago, pagar, sueldo: 💰
trabajo, chamba, oficina, trabajar, chambear: 💼
estudiar, estudio, tarea, tareas, examen, clase, clases, universidad, colegio: 📚
libro, leer: 📖
escribir, nota, notas: 📝
idea: 💡
casa, hogar, jato: 🏠
carro, auto, coche: 🚗
bus, combi, micro: 🚌
taxi: 🚕
moto: 🏍️
bicicleta, bici: 🚲
avion, viaje, viajar, vuelo: ✈️
playa, mar: 🏖️
montaña, montana, cerro: ⛰️
peru, peruano, peruana: 🇵🇪
futbol, pelota, partido, gol: ⚽
juego, jugar, gamer: 🎮
pelicula, peliculas, cine: 🎬
foto, fotos, selfie: 📸
telefono, celular, llamar, llamada: 📱
computadora, laptop, compu: 💻
mensaje, correo, email: ✉️
apurate, alarma, despertador: ⏰
calendario, fecha: 📅
dormido: 😴
enfermo, enferma, gripe, resfriado: 🤒
doctor, medico, hospital: 🏥
medicina, pastilla: 💊
bebe, bebito, bebita: 👶
mama, mami, madre: 👩
papa, papi, padre: 👨
familia: 👨‍👩‍👧
amigo, amiga, broder, hermano, hermana: 🤝
boda, matrimonio, casarse: 💍
diablo, malvado: 😈
angel: 😇
fantasma, miedito: 👻
calavera, muerto, muerta, me muero, morir: 💀
caca: 💩
cien, 100, perfecto: 💯
ojo, ojos, mirar, viendo: 👀
silencio, secreto, shh: 🤫
abrazo, abrazos: 🤗
guiño, guino: 😉
llamas, alpaca: 🦙
"""
}
