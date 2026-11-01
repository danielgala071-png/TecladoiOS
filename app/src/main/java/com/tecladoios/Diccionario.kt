package com.tecladoios

import android.content.Context
import java.text.Normalizer

/**
 * Sugerencias, predicción de la siguiente palabra y autocorrección en español peruano.
 * Aprende las palabras que escribes y el orden en que las usas.
 */
class Diccionario(context: Context) {

    private val prefs = Ajustes.prefs(context)
    private val base = LinkedHashMap<String, Int>()
    private val baseMinuscula = HashSet<String>()
    private val normales = HashMap<String, String>()
    private val aprendidas = HashMap<String, Int>()
    private val pares = HashMap<String, HashMap<String, Int>>()
    private var revision = -1
    private var cambios = false
    private var sinGuardar = 0
    private var totalBase = 0

    init {
        val fijas = (BASE + " " + BASE_EXTRA + " " + BASE_TILDES).trim().split(Regex("\\s+"))
        // Diccionario grande (español, Latinoamérica y Perú) desde assets/diccionario.txt
        val archivo = try {
            context.assets.open("diccionario.txt").bufferedReader().useLines { lineas ->
                lineas.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        } catch (_: Exception) { emptyList() }
        val palabras = fijas + archivo
        totalBase = palabras.size
        palabras.forEachIndexed { i, p ->
            if (!base.containsKey(p)) {
                base[p] = palabras.size - i
                baseMinuscula.add(p.lowercase())
            }
        }
        cargar()
    }

    private fun normal(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARCAS, "")

    private fun normalDe(p: String) = normales.getOrPut(p) { normal(p) }

    private fun conoce(p: String): Boolean {
        val l = p.lowercase()
        return l in baseMinuscula || (aprendidas[l] ?: 0) >= 2
    }

    private fun igualarMayus(c: String, modelo: String): String = when {
        modelo.length > 1 && modelo.all { !it.isLetter() || it.isUpperCase() } -> c.uppercase()
        modelo.isNotEmpty() && modelo[0].isUpperCase() -> c.replaceFirstChar { it.uppercase() }
        else -> c
    }

    fun sugerir(prefijo: String, max: Int, previa: String = ""): List<String> {
        if (prefijo.isEmpty()) return emptyList()
        val np = normal(prefijo)
        val lp = prefijo.lowercase()
        val res = HashMap<String, Int>()
        for ((p, s) in base) {
            if (p.lowercase() != lp && normalDe(p).startsWith(np)) res[p] = s
        }
        // Contexto: lo que sueles escribir después de la palabra anterior pesa muchísimo
        if (previa.isNotEmpty()) {
            val lprev = previa.lowercase()
            pares[lprev]?.forEach { (w, c) ->
                if (w != lp && normalDe(w).startsWith(np)) res[w] = (res[w] ?: 0) + 400 + c * 120
            }
            PARES_BASE[lprev]?.forEachIndexed { i, w ->
                if (w != lp && normalDe(w).startsWith(np)) res[w] = (res[w] ?: 0) + 300 - i * 30
            }
        }
        for ((p, c) in aprendidas) {
            if (p != lp && normalDe(p).startsWith(np)) {
                // Tus palabras pesan más; desde la segunda vez que las usas, mucho más
                res[p] = (res[p] ?: 0) + if (c >= 2) c * 60 else 15
            }
        }
        // Si hay pocas, agregar palabras parecidas por si te equivocaste de tecla
        if (res.size < max && np.length >= 3) {
            for ((p, s) in base) {
                if (!res.containsKey(p) && p.lowercase() != lp && casi(normalDe(p), np)) res[p] = s / 4
            }
            for ((p, c) in aprendidas) {
                if (c >= 2 && !res.containsKey(p) && p != lp && casi(normalDe(p), np)) res[p] = c * 20
            }
        }
        return res.entries.sortedByDescending { it.value }
            .take(max)
            .map { igualarMayus(it.key, prefijo) }
    }

    /** true si a y b se diferencian en una sola letra (cambiada, sobrante, faltante o invertida) */
    private fun casi(a: String, b: String): Boolean {
        if (a == b) return false
        val la = a.length
        val lb = b.length
        if (Math.abs(la - lb) > 1) return false
        if (la == lb) {
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i >= la) return false
            if (a.regionMatches(i + 1, b, i + 1, la - i - 1)) return true
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] &&
                    a.regionMatches(i + 2, b, i + 2, la - i - 2)
        }
        val corta = if (la < lb) a else b
        val larga = if (la < lb) b else a
        var i = 0
        while (i < corta.length && corta[i] == larga[i]) i++
        return corta.regionMatches(i, larga, i + 1, corta.length - i)
    }

    /** Predicción de la siguiente palabra (barra después de un espacio) */
    fun siguientes(previa: String, max: Int): List<String> {
        val l = previa.lowercase()
        val res = LinkedHashMap<String, Int>()
        pares[l]?.forEach { (w, c) -> res[w] = c * 10 }
        val fijas = if (l.isEmpty()) INICIO else PARES_BASE[l]
        fijas?.forEachIndexed { i, w -> res[w] = (res[w] ?: 0) + (5 - i) }
        return res.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    /** Devuelve la corrección (por ahora: tildes que faltan) o null. */
    fun corregir(p: String): String? {
        if (p.length < 2 || conoce(p)) return null
        val np = normal(p)
        var mejor: String? = null
        var puntaje = -1
        for ((c, s) in base) {
            if (s > puntaje && normalDe(c) == np) { mejor = c; puntaje = s }
        }
        for ((c, n) in aprendidas) {
            if (n >= 2 && n * 40 > puntaje && normalDe(c) == np) { mejor = c; puntaje = n * 40 }
        }
        val yaLaUsaste = (aprendidas[p.lowercase()] ?: 0) >= 1
        if (mejor == null && np.length >= 3 && !yaLaUsaste) {
            // Errores de tipeo: "qeu" → "que", "hloa" → "hola"
            // Sutil: solo corrige hacia palabras muy comunes
            val minimo = totalBase - 700
            for ((c, s) in base) {
                if (s > puntaje && s >= minimo && casi(normalDe(c), np)) { mejor = c; puntaje = s }
            }
            for ((c, n) in aprendidas) {
                if (n >= 3 && n * 60 > puntaje && casi(normalDe(c), np)) { mejor = c; puntaje = n * 60 }
            }
        }
        val r = mejor ?: return null
        val resultado = igualarMayus(r, p)
        return if (resultado == p) null else resultado
    }

    /**
     * Tildes que dependen de la palabra anterior: "que tal" → "qué tal",
     * "como estas" → "cómo estás", "todo esta bien" → "todo está".
     * Devuelve (anterior, actual) corregidas, o null si no hay nada que cambiar.
     */
    fun corregirPar(previa: String, palabra: String): Pair<String, String>? {
        if (previa.isEmpty() || palabra.isEmpty()) return null
        val par = PARES_TILDE[normal(previa) + " " + normal(palabra)] ?: return null
        val a = igualarMayus(par.first, previa)
        val b = igualarMayus(par.second, palabra)
        return if (a == previa && b == palabra) null else a to b
    }

    /** Palabra de pregunta con tilde: "que" → "qué", "como" → "cómo" */
    fun interrogativa(w: String): String? =
        INTERROGATIVAS[normal(w)]?.let { igualarMayus(it, w) }?.takeIf { it != w }

    private fun valida(p: String) = p.length >= 1 && p.all { it.isLetter() }

    fun aprender(p: String, peso: Int = 1) {
        if (p.length < 2 || !valida(p)) return
        val l = p.lowercase()
        aprendidas[l] = (aprendidas[l] ?: 0) + peso
        cambios = true
        // Guardar seguido para no perder lo aprendido si Android cierra el teclado
        if (++sinGuardar >= 20) guardar()
    }

    fun aprenderPar(previa: String, palabra: String) {
        if (!valida(palabra) || (previa.isNotEmpty() && !valida(previa))) return
        val mapa = pares.getOrPut(previa.lowercase()) { HashMap() }
        val w = palabra.lowercase()
        mapa[w] = (mapa[w] ?: 0) + 1
        cambios = true
    }

    fun guardar() {
        if (!cambios) return
        val texto = aprendidas.entries
            .sortedByDescending { it.value }
            .take(5000)
            .joinToString("|") { "${it.key}:${it.value}" }
        val sb = StringBuilder()
        var n = 0
        loop@ for ((previa, mapa) in pares) {
            for ((w, c) in mapa) {
                if (n++ > 8000) break@loop
                sb.append(previa).append(SEP1).append(w).append(SEP1).append(c).append(SEP2)
            }
        }
        prefs.edit().putString("aprendidas", texto).putString("pares", sb.toString()).apply()
        cambios = false
        sinGuardar = 0
    }

    fun recargarSiCambio() {
        if (prefs.getInt("aprendidas_rev", 0) != revision) {
            aprendidas.clear()
            pares.clear()
            cargar()
        }
    }

    private fun cargar() {
        revision = prefs.getInt("aprendidas_rev", 0)
        val texto = prefs.getString("aprendidas", "") ?: ""
        for (par in texto.split("|")) {
            val i = par.lastIndexOf(':')
            if (i <= 0) continue
            val n = par.substring(i + 1).toIntOrNull() ?: continue
            aprendidas[par.substring(0, i)] = n
        }
        val textoPares = prefs.getString("pares", "") ?: ""
        for (item in textoPares.split(SEP2)) {
            val partes = item.split(SEP1)
            if (partes.size != 3) continue
            val c = partes[2].toIntOrNull() ?: continue
            pares.getOrPut(partes[0]) { HashMap() }[partes[1]] = c
        }
        cambios = false
    }

    companion object {
        private val MARCAS = Regex("\\p{Mn}+")
        private const val SEP1 = "\u0001"
        private const val SEP2 = "\u0002"

        private val INICIO = listOf("hola", "yo", "gracias", "ya", "ok")

        private val INTERROGATIVAS = mapOf(
            "que" to "qué", "como" to "cómo", "donde" to "dónde", "adonde" to "adónde",
            "cuando" to "cuándo", "quien" to "quién", "quienes" to "quiénes", "cual" to "cuál",
            "cuales" to "cuáles", "cuanto" to "cuánto", "cuanta" to "cuánta",
            "cuantos" to "cuántos", "cuantas" to "cuántas",
        )

        /** "anterior actual" sin tildes → con tildes correctas */
        private val PARES_TILDE: Map<String, Pair<String, String>> = """
            que tal|qué tal; que haces|qué haces; que hacen|qué hacen; que paso|qué pasó; que pasa|qué pasa;
            que fue|qué fue; que onda|qué onda; que hora|qué hora; que dices|qué dices; que hiciste|qué hiciste;
            que miedo|qué miedo; que bueno|qué bueno; que rico|qué rico; que lindo|qué lindo; que linda|qué linda;
            que asco|qué asco; que pena|qué pena; que roche|qué roche; que palta|qué palta; que bien|qué bien;
            que bonito|qué bonito; que feo|qué feo; que raro|qué raro; por que|por qué;
            como estas|cómo estás; como esta|cómo está; como estan|cómo están; como fue|cómo fue;
            como asi|cómo así; como vas|cómo vas; como va|cómo va; como sigues|cómo sigues;
            como haces|cómo haces; como amaneciste|cómo amaneciste;
            donde estas|dónde estás; donde esta|dónde está; donde estan|dónde están; donde vives|dónde vives;
            donde queda|dónde queda; donde vas|dónde vas; de donde|de dónde; a donde|a dónde; por donde|por dónde;
            cuando vienes|cuándo vienes; cuando llegas|cuándo llegas; cuando sales|cuándo sales;
            cuando vas|cuándo vas; desde cuando|desde cuándo; hasta cuando|hasta cuándo;
            quien es|quién es; quien eres|quién eres; quien sabe|quién sabe; quien fue|quién fue;
            cual es|cuál es; cuanto es|cuánto es; cuanto cuesta|cuánto cuesta; cuanto vale|cuánto vale;
            cuanto falta|cuánto falta; cuantos años|cuántos años; cuantos anos|cuántos años;
            esta bien|está bien; esta mal|está mal; esta aqui|está aquí; esta ahi|está ahí; esta lloviendo|está lloviendo;
            ya esta|ya está; no esta|no está; todo esta|todo está;
            estas bien|estás bien; estas ahi|estás ahí; estas ocupado|estás ocupado; estas ocupada|estás ocupada;
            estas loco|estás loco; estas loca|estás loca;
            el es|él es; el esta|él está; el dijo|él dijo;
            tu eres|tú eres; tu estas|tú estás; tu sabes|tú sabes;
            si claro|sí claro; mas o|más o; mas tarde|más tarde; aun no|aún no
        """.trim().split(";").mapNotNull { linea ->
            val partes = linea.trim().split("|")
            if (partes.size != 2) null else {
                val a = partes[0].trim().split(" ")
                val b = partes[1].trim().split(" ")
                if (a.size == 2 && b.size == 2) "${a[0]} ${a[1]}" to (b[0] to b[1]) else null
            }
        }.toMap()

        /** Palabras con tilde o ñ para corregir cuando las escribes sin ella */
        private const val BASE_TILDES = """
            también después además todavía quizás jamás aquí allá acá ahí así atrás según algún ningún
            común fácil difícil útil árbol cámara música número página rápido rápida teléfono último última
            público pública práctica práctico lápiz inglés francés japonés alemán canción camión avión razón
            corazón opción acción atención información relación situación educación dirección estación
            habitación televisión pasión emoción ilusión decisión reunión región millón jabón botón ratón
            limón melón salón sillón balón montón café bebé sofá menú papá mamá tío tía río frío día días
            mía tía película políticos política médico médica clínica química física matemática gráfico
            pasó llegó salió comió terminó empezó habló llamó quedó dejó mandó compró pagó ganó perdió
            vivió murió subió bajó entró pidió escribió recibió sintió durmió pensó esperó trabajó estudió
            viajó regresó volvió jugó cambió encontró llevó tomó sacó dio
            llegué pagué jugué busqué toqué saqué empecé terminé compré hablé llamé mandé dejé quedé pensé
            esperé entré trabajé estudié viajé regresé volví comí salí escribí viví perdí subí recibí sentí
            pedí dormí leí oí reí vi
            será estará tendrá hará irá podrá vendrá saldrá llegará haré iré podré tendré estaré seré
            pasaré llamaré escribiré avisaré veré diré sabré querrá
            sería podría tendría haría iría debería querría gustaría estaría
            tenía había quería sabía podía decía hacía vivía creía sentía salía venía
            estábamos íbamos éramos teníamos hacíamos
            dímelo avísame llámame escríbeme mándame pásame dámelo cuéntame explícame ayúdame espérame
            búscame díselo mírala míralo enséñame acompáñame tráeme cómpralo
            lógico básico económico eléctrico único típico clásico fantástico romántico simpático
            sábado miércoles jóvenes exámenes imágenes órdenes
            año años niño niña niños niñas señor señora baño mañana pequeño pequeña sueño otoño
            cumpleaños español española compañero compañera compañía montaña araña enseñar diseño dueño
            dueña cariño piña uña uñas ñaño ñaña engaño extraño extraña
        """


        private val PARES_BASE = mapOf(
            "que" to listOf("tal", "es", "no", "te", "haces"),
            "qué" to listOf("tal", "haces", "pasó", "es", "bien"),
            "de" to listOf("la", "que", "los", "nada", "verdad"),
            "te" to listOf("quiero", "amo", "extraño", "llamo", "aviso"),
            "buenos" to listOf("días"),
            "buenas" to listOf("noches", "tardes"),
            "muchas" to listOf("gracias"),
            "por" to listOf("favor", "qué", "eso", "fin", "la"),
            "estoy" to listOf("bien", "en", "yendo", "llegando", "cansado"),
            "hola" to listOf("cómo", "qué", "amor", "causa", "buenos"),
            "como" to listOf("estás", "está", "te", "así"),
            "cómo" to listOf("estás", "está", "te", "así"),
            "nos" to listOf("vemos", "vamos"),
            "a" to listOf("la", "las", "qué", "ver", "mí"),
            "en" to listOf("la", "el", "casa", "serio", "un"),
            "no" to listOf("sé", "te", "hay", "puedo", "pasa"),
            "yo" to listOf("también", "te", "no", "estoy", "sé"),
            "ya" to listOf("voy", "llegué", "está", "pe", "nos"),
            "voy" to listOf("a", "en", "para"),
            "gracias" to listOf("por", "amor", "causa"),
            "todo" to listOf("bien", "el"),
            "está" to listOf("bien", "en"),
            "estás" to listOf("bien", "en"),
            "dónde" to listOf("estás", "está"),
            "donde" to listOf("estás", "está"),
            "mi" to listOf("amor", "mamá", "papá", "casa"),
            "tal" to listOf("vez"),
            "lo" to listOf("siento", "que", "sé"),
            "un" to listOf("momento", "rato", "poco"),
            "me" to listOf("avisas", "gusta", "voy", "parece"),
        )

        // Más palabras frecuentes: verbos conjugados, sustantivos y expresiones del día a día
        private const val BASE_EXTRA = """
            ser soy eres somos son sido siendo estar estaba estaban estuve estuvo estado estando
            tener tenía tenías tuve tuvo tienen tenido teniendo hacer haces hacemos hacen hice hiciste
            hecho haciendo ir vas va van fui fuiste fuimos ido yendo iba ibas íbamos poder puede pueden
            pude pudo podía podría podrías querer quiere queremos quieren quise quería querías decir dices
            dice decimos dicen dije dijo dicho diciendo saber sabe sabemos saben supe sabía sabías veo ves
            vemos ven vi vio visto viendo dar doy das da damos dan di dio dado venir vienes vienen vine
            vino viniste venido llegar llegas llega llegamos llegan llegó llegaste pasar pasas pasamos
            pasan pasé pasado poner pongo pones pone ponemos ponen puse puso puesto quedar quedo quedas
            queda quedamos quedan quedé quedó creer creo crees cree creemos creen creí hablar hablo hablas
            habla hablamos hablan hablé habló hablando llevar llevo llevas lleva llevamos llevé dejar dejo
            dejas deja dejé dejó seguir sigo sigues sigue seguimos siguen encontrar encuentro encontré
            llamo llamas llama llamamos llamé llamó pensar pienso piensas piensa pensé pensando conocer
            conozco conoces conoce conocí vivir vivo vives vive vivimos sentir siento sientes siente
            sentí mirar miro miras miré contar cuento cuentas cuenta conté empezar empiezo empieza empezó
            esperar espero esperas espera esperé esperando buscar busco buscas busca busqué buscando
            entrar entro entras entra entré trabajar trabajo trabajas trabaja trabajé trabajando escribo
            escribes escribí escribiendo perder pierdo pierdes pierde perdiste entender entiendo entiendes
            entiende entendí pedir pido pides pide pedí recibir recibo recibí recordar recuerdo recuerdas
            terminar termino terminé terminó terminamos conseguir consigo conseguí leer leo lees leí caer
            caí cayó cambiar cambio cambié crear abrir abro abres abre abrí abierto cerrar cierro cierra
            cerré cerrado acabar acabo acabé acabó ganar gano ganas gana traer traigo traes trae traje
            morir muero aceptar acepto lograr logré explicar explico explícame preguntar pregunto pregunté
            pregúntale tocar toco tocó estudias estudia estudiamos estudiando correr corro corrí utilizar
            pago pagas paga pagué ayudar ayudo ayudas ayuda ayúdame ayudé gustan gustaba escuchar escucho
            escuchas escucha escuché cumplir ofrecer descubrir levantar levanto intentar intento intenté
            usar uso usas usa usé decidir decidí olvidar olvido olvidé olvidaste comer como comes come
            comemos comí comiste comiendo mostrar muestra mover muevo continuar suceder sucede preparar
            preparo enviar envío envías envía envié enviaste mandar mando mandas manda mandé mándame
            dormir duermo duermes duerme dormí durmiendo despertar despierto desperté bañar baño bañé
            cocinar cocino limpiar limpio manejar manejo viajar viajo regresar regreso regresé volver
            vuelvo vuelves vuelve volví subir subo subí bajar bajo bajé caminar camino caminé bailar
            bailo cantar canto reír río llorar lloro extrañar extraño extrañas extrañé amar amo amas
            odio molestar molesta preocupar preocupa preocupes importar importa importó faltar falta
            faltan doler duele avisar aviso avisas avisa avisé confirmar confirmo responder respondo
            respondes respondí contestas contesté grabar descargar instalar actualizar cargar prender
            apagar conectar recoger recojo paso pasas pasa pásame dime díselo cuéntame mírame espera
            oye escúchame tranquilo dale vale chévere genial bacán increíble horrible feo bonito bonita
            lindo linda hermoso hermosa rico rica bueno malo mala grande gran pequeña nuevo viejo vieja
            joven mayor menor primero primera segundo segunda tercero último siguiente anterior mismo
            igual diferente posible imposible importante necesario seguro segura listo lista libre
            ocupado ocupada cansada enfermo enferma contento contenta triste alegre aburrido aburrida
            molesto molesta preocupado preocupada emocionado emocionada nervioso nerviosa bravo brava
            gente persona personas hombre mujer chico chica chicos chicas niños hijo hija hijos padre
            madre papás tío tía primo prima abuelo abuela familia novio novia esposo esposa vecino
            vecina jefe compañeros grupo mundo país ciudad pueblo barrio lugar sitio parte lado centro
            casa departamento cuarto sala puerta ventana cama mesa silla ropa zapatillas polo casaca
            mochila llaves billetera tarjeta cuenta pasaje carro bus moto tren avión aeropuerto terminal
            calle avenida esquina parque playa mar río cerro campo chacra plaza iglesia hospital clínica
            farmacia banco tienda bodega mercado restaurante pollería chifa cevichería cine fiesta
            reunión cumple matrimonio bautizo velorio partido concierto evento cita trabajo oficina
            colegio cole universidad instituto academia curso clase tarea examen práctica nota notas
            libro cuaderno lapicero celular cel teléfono laptop computadora compu tablet audífonos
            cargador internet wifi señal batería saldo recarga datos megas mensaje audio llamada
            videollamada foto fotos video videos estado historia historias publicación comentario like
            grupo chat contacto número correo cuenta clave contraseña aplicación app juego juegos
            partida nivel equipo cosa cosas algo nada todo todos todas mucho muchos poco pocos varios
            varias alguno alguna ningún ninguno ninguna cualquier otro otra mismo tanto tanta cuánto
            cuánta cuántos cuántas cuál cuáles quién quiénes dónde adónde cuándo cómo qué porque pues
            entonces luego después antes ahora ahorita hoy mañana ayer anoche tarde temprano siempre
            nunca jamás casi solo sólo también tampoco además aunque sino mientras hasta desde durante
            según contra sin sobre bajo entre hacia mediante dentro fuera cerca lejos arriba abajo
            adelante atrás encima debajo delante detrás aquí acá ahí allí allá sí no quizá quizás
            claro obvio exacto cierto verdad mentira bueno bien mal mejor peor rápido lento despacio
            fuerte suave mucho demasiado bastante apenas recién justo tal cual hora horas minuto minutos
            segundo segundos día días semana semanas mes meses año años momento rato vez veces tiempo
            mañanita tardecita nochecita fin finde feriado vacaciones navidad año nuevo cumpleaños
            hambre sed sueño frío calor miedo ganas suerte prisa razón culpa problema problemas
            solución pregunta respuesta idea plan opinión verdad noticia noticias historia caso forma
            manera modo tipo clase número cantidad precio plata sencillo vuelto soles dólares cuenta
            deuda pago regalo sorpresa comida almuerzo desayuno cena lonche menú pan leche café té
            jugo agua gaseosa cerveza chela vino pollo carne pescado arroz frijoles lentejas papa
            camote yuca choclo huevo queso fruta manzana plátano naranja mango palta tomate cebolla
            ají limón azúcar sal salchipapa hamburguesa pizza tallarines anticucho causa rocoto
            jaja jajaja jajajaja jeje jsjs xd ok okey okay oki ya yap yaa sip nop nel simón uff ufff
            wow guau ay ayy ah oh eh mmm bueno chau chao adiós hola holi holis buenas saludos gracias
            graciass porfavor perdón disculpa lo siento felicidades feliz suerte bendiciones besos
            abrazos cuídate descansa duerme bien que te vaya bien nos vemos hablamos luego te llamo
            te escribo avísame cuando llegues ya llegué estoy llegando voy saliendo en camino
        """

        // Ordenadas de más a menos usadas
        private const val BASE = """
            que de no a la el y en lo es un por me una te los se con para mi pero las su al le si ya
            todo como bien esta este eso esto esa ese muy hay también sí qué cómo cuándo dónde quién cuál
            porque hola gracias bueno buena buenos buenas días noches tardes jaja jajaja jajajaja xd
            causa pata chamba chévere bacán pe pues oe asu mare habla tal vez mañana hoy ayer ahorita
            después luego nada nadie algo alguien siempre nunca casa trabajo colegio universidad celular
            mamá papá hermano hermana hermanito hermanita amigo amiga amigos amigas quiero puedo tengo voy
            vamos estoy estás está estamos están era fue hacer hago hace hizo dime dile sé sabes saber ver
            veo vez cosa cosas tiempo año años día hora horas momento ahora aquí ahí allá así más menos
            mucho mucha muchos muchas poco poca nuevo nueva grande pequeño pequeña mejor peor otro otra
            otros mismo misma cada sin sobre entre hasta desde hacia durante según yo tú él ella nosotros
            ustedes ellos ellas usted conmigo contigo nos les sus tu del cuando donde quien cual cuanto
            claro okey ok listo dale chau adiós vemos quiere quieres amor cariño beso besos abrazo feliz
            cumpleaños porfa favor disculpa perdón siento salud comida almuerzo cena desayuno pollo ceviche
            chaufa lomo saltado arroz papa papas agua gaseosa chela plata luca lucas soles dinero pagar
            comprar vender tienda mercado calle carro combi micro taxi cuarto baño cocina tele televisor
            juego juegos jugar video videos foto fotos música canción película serie mensaje mensajes
            llamar llamada llámame escribir escribe responde contesta número teléfono internet wifi datos
            batería cargador tarde noche semana mes lunes martes miércoles jueves viernes sábado domingo
            enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre Perú
            Lima peruano verdad mentira sería será fueron vino viene venir ir irme salir sale salimos
            llegar llego llegué estudiar estudio examen tarea clase profe profesor profesora curso nota
            notas difícil fácil rápido lento temprano todavía aún recién quizás tampoco además entonces
            aunque mientras sino solo sola acá allí oye mira ojo pucha bro mano manyas jato jerma flaco
            flaca chibolo chibola roche palta piña misio alucina yapa paja bravazo buenazo chambear jalar
            aprobar aprobé ganar perder gané perdí partido fútbol pelota equipo gol mal tranqui tranquilo
            tranquila cansado cansada sueño hambre frío calor lluvia sol playa paseo viaje moto bici
            corazón razón información atención opinión compañero compañera niño niña señor señora español
            inglés último última próximo próxima jamás árbol lápiz también qué tal pasó pasa pasar dijo
            dijiste digo creo crees parece gusta gustó encanta necesito necesitas podemos puedes tienes
            tiene tenemos hay había habías avísame avísale espérame ven vente vengo
        """
    }
}
