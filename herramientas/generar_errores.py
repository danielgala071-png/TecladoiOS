#!/usr/bin/env python3
"""
Entrena los errores de fábrica del teclado (assets/errores.txt).

Simula cómo se equivoca un pulgar en el celular escribiendo rápido y calcula, para las
palabras más usadas, los dedazos más probables:

  - Letra que no salió (la tecla se soltó muy rápido o el toque no llegó): "porqe" → "porque"
  - Tecla vecina (el dedo cayó corrido): "cada" → "cafa"
  - Dos teclas a la vez (el dedo rozó la de al lado): "holka" → "hola"
  - Letra repetida por rebote: "graciias" → "gracias"
  - Letras invertidas al escribir con dos pulgares: "qeu" → "que"
  - Confusiones de oído: b/v, s/c/z, y/ll, h muda, g/j, qu/k
  - Dos errores juntos en palabras largas y muy usadas
  - Pulgar que cae una fila más arriba o más abajo, e/i y o/u de oído, d final que no suena

A cada error se le da una probabilidad (qué tan común es ese tipo de dedazo × qué tan común es
la palabra). Un error solo se guarda si:
  - no es una palabra que existe (ni en el diccionario ni en las listas del código), y
  - una sola palabra lo explica claramente (si dos palabras parecidas compiten, no se guarda:
    ahí decide la autocorrección con el contexto).
Al final se guardan los más probables, sin pasarse de [MAXIMO].

Uso: generar_errores.py <diccionario.txt> <Diccionario.kt> <errores.txt> [maximo]
"""
import re
import sys
import unicodedata
from collections import defaultdict

FILAS = ["qwertyuiop", "asdfghjklñ", "zxcvbnm"]
POS = {}
for i, c in enumerate(FILAS[0]):
    POS[c] = (i + 0.5, 0.0)
for i, c in enumerate(FILAS[1]):
    POS[c] = (i + 0.5, 1.0)
for i, c in enumerate(FILAS[2]):
    POS[c] = (i + 2.0, 2.0)


def vecinas(c):
    """Teclas a las que el dedo se corre: (letra, peso)"""
    if c not in POS:
        return []
    x, y = POS[c]
    r = []
    for o, (ox, oy) in POS.items():
        if o == c:
            continue
        d = ((x - ox) ** 2 + (y - oy) ** 2) ** 0.5
        if d <= 1.05:
            r.append((o, 1.0 if oy == y else 0.55))  # al lado pesa más que arriba/abajo
        elif d <= 1.6:
            r.append((o, 0.3))
    return r


def normal(s):
    # La ñ se queda (tiene su tecla); el resto de marcas se va
    s = unicodedata.normalize("NFC", s.lower())
    s = s.replace("ñ", "\u0001")
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
    return s.replace("\u0001", "ñ")


LETRAS = re.compile(r"^[a-zñ]+$")
RISA = re.compile(r"^(?:(?:j+[aeiouhs]+)+j*|(?:js|sj)+[js]?|x+d+)$")

# Inglés del día a día que no debe volverse una corrección
INGLES = set("""
ok okay link links like likes love baby babe please sorry thanks thank you yes yeah nope wifi online
story post selfie meme crush random cool nice bro happy birthday weekend show live delivery feedback
meeting email mail password login app apps update reel tiktok stream gamer full premium style look
outfit match chill vibe hater friend cute fashion fitness gym coach crack fail zoom sticker fans fake
check smart delete download upload playlist remix feat trend follow spoiler girl boy bestie honey
darling omg lol btw idk asap cringe flow swag drip flex single party brunch snack burger coffee bye
hello hey guys the and for are but not all any can had her was one our out day get has him his how
man new now old see two way who did its let put say she too use dad mom top yes yet run sun fun gun
hot hit sit set bet net pet red bed bad sad mad dog cat car bar far star tar war bus box fox mix six
""".split())

TIPO = {
    "falta": 1.0,       # la letra no salió (lo más común al soltar rápido)
    "falta_ini": 0.25,  # la primera letra casi nunca se pierde
    "falta_fin": 0.75,
    "vecina": 0.85,
    "doble_toque": 0.5,
    "rebote": 0.45,
    "invertidas": 0.6,
    "oido": 0.7,
    "doble": 0.12,      # dos errores juntos
    "salteada": 0.22,   # escribiendo a dos dedos, una letra se adelanta dos lugares ("prouqe")
    "ñ": 1.1,           # la ñ escrita como n ("manana" → "mañana"): muy común
    "h_medio": 0.55,    # h muda que se pierde en medio ("aora", "proibido")
    "stes": 0.9,        # pretérito con s de más, muy común al hablar ("dijistes", "fuistes")
    "pulgar_cruzado": 0.35,  # a dos dedos: la letra de la otra mano sale antes ("tmabien")
    "vocal_comida": 0.6,     # se come una vocal entre dos consonantes al ir rápido ("nesctio")
    "fin_vecina": 0.45,      # al soltar rápido la última letra sale corrida ("holz")
    "tecla_larga": 0.3,      # la tecla vecina salió EN VEZ y ADEMÁS de la buena al barrer ("hiola")
    "fila_abajo": 0.25,      # el pulgar cae una fila más abajo de lo que quería ("nueno" → "bueno")
    "falta_dos": 0.15,       # palabras largas: se pierden dos letras al ir muy rápido
    "fila_arriba": 0.2,      # el pulgar se estira de más y cae una fila más arriba ("yodo" → "todo")
    "vocal_oido": 0.3,       # e/i y o/u que se confunden al hablar ("siguir", "dispues", "polecia")
    "fin_d": 0.5,            # la d del final que no se pronuncia ("verda", "ciuda", "uste")
    # Nuevos
    "mente_o": 0.6,          # el adverbio con la o del masculino ("seguromente" → "seguramente")
    "mente_corta": 0.5,      # se come la "men" o la "te" al ir rápido ("realmete", "totalente")
    "iente": 0.45,           # -ente / -iente cruzados ("pendente", "exceliente", "suficente")
    "doble_letra": 0.4,      # la letra doble se pierde o aparece donde no va ("acion", "proffesor")
    "mos_nos": 0.3,          # -mos dicho -nos ("vinienos", "fuinos")
    "seseo_final": 0.55,     # z final dicha s ("felis", "capas", "lus", "ves")
    "u_muda": 0.5,           # la u muda de gue/gui/que/qui que no salió ("gerra", "seguir" → "segir")
}

OIDO = [
    ("b", "v"), ("v", "b"), ("ll", "y"), ("y", "ll"), ("ce", "se"), ("ci", "si"), ("se", "ce"),
    ("si", "ci"), ("z", "s"), ("s", "z"), ("za", "sa"), ("zo", "so"), ("ge", "je"), ("gi", "ji"),
    ("je", "ge"), ("ji", "gi"), ("que", "ke"), ("qui", "ki"), ("ca", "ka"), ("co", "ko"), ("x", "s"),
    ("cc", "c"), ("rr", "r"), ("ll", "l"), ("gue", "ge"), ("gui", "gi"), ("güe", "gue"),
    ("mb", "nb"), ("mp", "np"), ("x", "cs"), ("cion", "sion"), ("sion", "cion"),
    ("yo", "llo"), ("lla", "ya"), ("ue", "we"), ("hue", "we"), ("hue", "gue"),
    # Más errores de oído y de apuro, fáciles de reconocer
    ("que", "qe"), ("qui", "qi"), ("sc", "s"), ("xc", "c"), ("xc", "sc"), ("ps", "s"),
    ("ns", "s"), ("bs", "s"), ("mn", "n"), ("pt", "t"), ("ct", "t"), ("cc", "cs"),
    ("mb", "nv"), ("nv", "mb"), ("ll", "lly"), ("gu", "g"), ("ex", "es"), ("aho", "ao"),
    ("cion", "cio"), ("mente", "mete"), ("ado", "dao"), ("ido", "dio"),
    # Seseo y confusiones de oído que faltaban ("ciencia" → "siensia", "cuando" → "quando")
    ("cia", "sia"), ("cio", "sio"), ("ce", "ze"), ("ci", "zi"), ("cua", "qua"),
    ("cuo", "quo"), ("cie", "sie"), ("sc", "c"), ("xi", "si"), ("exp", "esp"),
    ("exc", "esc"), ("ext", "est"), ("bs", "vs"), ("bv", "v"), ("nm", "m"),
]

# Letras de cada mano al escribir con dos pulgares (mitad izquierda / derecha del teclado)
IZQUIERDA = set("qwertasdfgzxcv")


def errores_de(w):
    """Todos los dedazos de [w] (sin tildes) con su peso"""
    n = len(w)
    r = defaultdict(float)

    def poner(v, p):
        if v != w and len(v) >= 2:
            r[v] = max(r[v], p)

    for i in range(n):
        # letra que no salió
        if n >= 4:
            t = "falta_ini" if i == 0 else "falta_fin" if i == n - 1 else "falta"
            # una letra doble que se pierde es todavía más común ("aroz")
            extra = 1.3 if (i > 0 and w[i - 1] == w[i]) else 1.0
            poner(w[:i] + w[i + 1:], TIPO[t] * extra)
        # tecla vecina
        for v, p in vecinas(w[i]):
            poner(w[:i] + v + w[i + 1:], TIPO["vecina"] * p * (0.4 if i == 0 else 1.0))
            # dos teclas a la vez (antes o después de la buena)
            poner(w[:i] + v + w[i:], TIPO["doble_toque"] * p * 0.8)
            poner(w[:i + 1] + v + w[i + 1:], TIPO["doble_toque"] * p)
        # rebote: la letra salió dos veces
        poner(w[:i + 1] + w[i] + w[i + 1:], TIPO["rebote"])
        # letras invertidas
        if i < n - 1 and w[i] != w[i + 1]:
            poner(w[:i] + w[i + 1] + w[i] + w[i + 2:], TIPO["invertidas"] * (0.5 if i == 0 else 1.0))
    # de oído
    for a, b in OIDO:
        k = w.find(a)
        while k >= 0:
            poner(w[:k] + b + w[k + len(a):], TIPO["oido"])
            k = w.find(a, k + 1)
    if w.startswith("h") and n >= 4:
        poner(w[1:], TIPO["oido"])
    # h muda en medio de la palabra ("ahora" → "aora")
    k = w.find("h", 1)
    while k > 0:
        if w[k - 1] != "c" and n >= 4:
            poner(w[:k] + w[k + 1:], TIPO["h_medio"])
        k = w.find("h", k + 1)
    # ñ como n (o como ni): "manana", "anio"
    k = w.find("ñ")
    while k >= 0:
        poner(w[:k] + "n" + w[k + 1:], TIPO["ñ"])
        poner(w[:k] + "ni" + w[k + 1:], TIPO["ñ"] * 0.35)
        k = w.find("ñ", k + 1)
    # A dos dedos: una letra sale dos lugares antes ("porque" → "prouqe" no; "porqeu"/"proque")
    for i in range(n - 2):
        if w[i] != w[i + 2]:
            poner(w[:i] + w[i + 2] + w[i + 1] + w[i] + w[i + 3:], TIPO["salteada"])
            poner(w[:i] + w[i + 1] + w[i + 2] + w[i] + w[i + 3:], TIPO["salteada"] * 0.8)
    # "estoi", "voi", "hoi": la y del final escrita como i
    if n >= 3 and w.endswith("y") and w[-2] in "aeiou":
        poner(w[:-1] + "i", TIPO["oido"] * 1.2)
    # "dijistes", "fuistes", "hicistes": la s de más del pretérito (tú)
    if n >= 4 and (w.endswith("aste") or w.endswith("iste")):
        poner(w + "s", TIPO["stes"])
    # A dos pulgares: cuando dos letras seguidas son de manos distintas, la segunda mano a
    # veces llega primero ("tmabien" → "también"); se mira también saltando una letra
    for i in range(n - 1):
        a, b = w[i], w[i + 1]
        if a != b and (a in IZQUIERDA) != (b in IZQUIERDA):
            poner(w[:i] + b + a + w[i + 2:], TIPO["pulgar_cruzado"] * (0.5 if i == 0 else 1.0))
    # Vocal que no salió entre dos consonantes (la tecla se soltó muy rápido)
    for i in range(1, n - 1):
        if w[i] in "aeiou" and w[i - 1] not in "aeiou" and w[i + 1] not in "aeiou" and n >= 5:
            poner(w[:i] + w[i + 1:], TIPO["vocal_comida"])
    # La última letra salió corrida a la tecla vecina
    if n >= 4:
        for v, p in vecinas(w[-1]):
            poner(w[:-1] + v, TIPO["fin_vecina"] * p)
    # El pulgar cae justo debajo (las filas de abajo están más lejos del pulgar que se estira)
    for i in range(n):
        c = w[i]
        if c in POS:
            x, y = POS[c]
            for o, (ox, oy) in POS.items():
                if oy == y + 1 and abs(ox - x) <= 0.55:
                    poner(w[:i] + o + w[i + 1:], TIPO["fila_abajo"] * (0.4 if i == 0 else 1.0))
    # El pulgar se estira de más y cae justo arriba (al revés de fila_abajo)
    for i in range(n):
        c = w[i]
        if c in POS:
            x, y = POS[c]
            for o, (ox, oy) in POS.items():
                if oy == y - 1 and abs(ox - x) <= 0.55:
                    poner(w[:i] + o + w[i + 1:], TIPO["fila_arriba"] * (0.4 if i == 0 else 1.0))
    # e/i y o/u de oído (en sílabas sin acento, muy común al escribir como se habla)
    for i in range(1, n):
        par = {"e": "i", "i": "e", "o": "u", "u": "o"}.get(w[i])
        if par and not (i > 0 and w[i - 1] == "q") and not (i > 0 and w[i - 1] == "g" and w[i] == "u"):
            poner(w[:i] + par + w[i + 1:], TIPO["vocal_oido"])
    # d final que no suena: "verdad" → "verda", "usted" → "uste"
    if n >= 4 and w.endswith("d"):
        poner(w[:-1], TIPO["fin_d"])
    # Adverbios en -mente: con la o del masculino, o se come un pedacito al ir rápido
    if n >= 8 and w.endswith("amente"):
        poner(w[:-6] + "omente", TIPO["mente_o"])
    if n >= 8 and w.endswith("mente"):
        poner(w[:-5] + "mete", TIPO["mente_corta"])
        poner(w[:-5] + "ente", TIPO["mente_corta"] * 0.8)
        poner(w[:-5] + "ment", TIPO["mente_corta"])
        poner(w[:-5] + "mnte", TIPO["mente_corta"])
    # -ente / -iente cruzados
    if n >= 6:
        if w.endswith("iente") and n >= 7:
            poner(w[:-5] + "ente", TIPO["iente"])
            poner(w[:-4] + "nte", TIPO["iente"] * 0.6)  # "pendinte"
        elif w.endswith("ente"):
            poner(w[:-4] + "iente", TIPO["iente"])
        if w.endswith("ientes"):
            poner(w[:-6] + "entes", TIPO["iente"])
        k = w.find("cien")
        if k > 0:
            poner(w[:k] + "cen" + w[k + 4:], TIPO["iente"])  # "suficente", "eficente"
    # Letra doble: se pierde ("acion", "aroz") o sale doble donde no va ("proffesor")
    for i in range(1, n):
        if w[i] == w[i - 1] and w[i] in "crln":
            poner(w[:i] + w[i + 1:], TIPO["doble_letra"])
    for i in range(1, n - 1):
        if w[i] in "fmptsl" and w[i - 1] in "aeiou" and w[i + 1] in "aeiou":
            poner(w[:i] + w[i] + w[i:], TIPO["doble_letra"] * 0.5)
    # -mos dicho -nos (pretérito y presente de nosotros)
    if n >= 5 and w.endswith("mos"):
        poner(w[:-3] + "nos", TIPO["mos_nos"])
    # z final dicha s ("feliz" → "felis", "luz" → "lus")
    if n >= 3 and w.endswith("z"):
        poner(w[:-1] + "s", TIPO["seseo_final"])
    # u muda que no salió ("guerra" → "gerra", "quiero" → "qiero" ya está)
    for k in range(n - 2):
        if w[k] == "g" and w[k + 1] == "u" and w[k + 2] in "ei":
            poner(w[:k + 1] + w[k + 2:], TIPO["u_muda"])
    # Palabras largas: dos letras que no salieron (no seguidas)
    if n >= 8:
        for i in range(1, n - 2):
            for j in range(i + 2, n):
                poner(w[:i] + w[i + 1:j] + w[j + 1:], TIPO["falta_dos"])
    return r


def leer_palabras(ruta):
    palabras = []
    for linea in open(ruta, encoding="utf-8"):
        linea = linea.strip()
        if not linea or linea[0] in "#@":
            continue
        palabras.append(unicodedata.normalize("NFC", linea.lower()))
    return palabras


def main():
    ruta_dic, ruta_kt, ruta_err = sys.argv[1], sys.argv[2], sys.argv[3]
    maximo = int(sys.argv[4]) if len(sys.argv) > 4 else 720000
    # Hasta qué palabra (por frecuencia) se entrenan errores y hasta cuál van errores dobles
    alcance = int(sys.argv[5]) if len(sys.argv) > 5 else 140000
    alcance_doble = int(sys.argv[6]) if len(sys.argv) > 6 else 60000

    codigo = open(ruta_kt, encoding="utf-8").read()
    # El orden es el mismo que usa el teclado: primero lo más usado en el chat (FRECUENCIA del
    # código) y después el diccionario de la palabra más común a la menos común
    m = re.search(r'FRECUENCIA = """(.*?)"""', codigo, re.S)
    primeras = [unicodedata.normalize("NFC", w.lower()) for w in m.group(1).split()] if m else []
    palabras = primeras + leer_palabras(ruta_dic)
    # Palabras conocidas: diccionario + todo lo que aparece en el código (jerga, listas, formas)
    conocidas = {normal(p) for p in palabras}
    for t in re.findall(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+", codigo):
        conocidas.add(normal(t))
    conocidas |= INGLES

    # Errores que ya estaban (se respetan tal cual)
    previos = {}
    cabecera = []
    for linea in open(ruta_err, encoding="utf-8"):
        linea = linea.rstrip("\n")
        if linea.startswith("#"):
            if not previos:
                cabecera.append(linea)
            continue
        if ":" in linea:
            a, b = linea.split(":", 1)
            previos[a] = b

    # Rango de cada palabra (el orden del diccionario es la frecuencia) y el destino de cada forma
    # sin tildes: si dos palabras comparten la forma ("esta"/"está"), va a la más común solo si
    # lo es claramente
    rango = {}
    por_normal = defaultdict(list)
    for i, p in enumerate(palabras):
        if p not in rango:
            rango[p] = i
            por_normal[normal(p)].append(p)

    candidatos = defaultdict(list)  # error -> [(peso, destino)]
    usadas = 0
    for p, i in sorted(rango.items(), key=lambda x: x[1]):
        if i > alcance:
            break
        np_ = normal(p)
        if len(np_) < 3 or not LETRAS.match(np_):
            continue
        hermanas = por_normal[np_]
        if len(hermanas) > 1:
            # "esta"/"está", "como"/"cómo": el error va a la forma sin tilde y después la tilde
            # la pone el contexto ("como estas" → "cómo estás"). Si no hay forma sin tilde, a la
            # más común solo si lo es claramente.
            if np_ in hermanas:
                if p != np_:
                    continue
            elif hermanas[0] != p or rango[hermanas[1]] < rango[p] * 6 + 200:
                continue
        frec = 1.0 / (i + 40.0)
        errs = errores_de(np_)
        # Dos errores en las palabras largas más usadas (letra que falta + otra cosa)
        if len(np_) >= 6 and i < alcance_doble:
            umbral = 0.45 if i < 6000 else 0.7 if i < 20000 else 0.85
            for v, pv in list(errs.items()):
                if pv < umbral:
                    continue
                for v2, pv2 in errores_de(v).items():
                    if v2 != np_ and pv2 >= umbral and len(v2) >= len(np_) - 2:
                        errs[v2] = max(errs.get(v2, 0.0), TIPO["doble"] * pv * pv2)
        usadas += 1
        # Cupo por palabra: así ninguna palabra se lleva todos los errores
        cupo_palabra = 40 if i < 600 else 20 if i < 5000 else 10
        mejores = sorted(errs.items(), key=lambda x: -x[1])[:cupo_palabra * 3]
        for v, pv in mejores:
            candidatos[v].append((frec * pv, p))

    elegidos = []
    corregidos = 0
    for v, lista in candidatos.items():
        if v in previos and v not in conocidas:
            # Un error viejo que apunta a la palabra equivocada ("manana" → "manzana") se
            # corrige si ahora otra palabra lo explica muchísimo mejor ("mañana")
            lista.sort(reverse=True)
            mejor, destino = lista[0]
            viejo = max((pp for pp, dd in lista if dd == previos[v]), default=0.0)
            if destino != previos[v] and mejor > viejo * 4:
                previos[v] = destino
                corregidos += 1
            continue
        if v in conocidas or v in previos or not LETRAS.match(v) or RISA.match(v):
            continue
        if re.search(r"(.)\1\1", v):
            continue  # "holaaa" es expresivo, no un error
        lista.sort(reverse=True)
        mejor, destino = lista[0]
        segundo = lista[1][0] if len(lista) > 1 and lista[1][1] != destino else 0.0
        if segundo > 0 and mejor < segundo * 8:
            continue  # dos palabras lo explican: no se adivina a ciegas
        # Errores cortos: solo si la palabra es de las más comunes
        if len(v) <= 3 and rango[destino] > 400:
            continue
        elegidos.append((mejor, v, destino))

    elegidos.sort(reverse=True)
    cupo = max(0, maximo - len(previos))
    nuevos = {}
    por_destino = defaultdict(int)
    for _, v, d in elegidos:
        if len(nuevos) >= cupo:
            break
        r = rango[d]
        tope = 40 if r < 600 else 20 if r < 5000 else 10
        if por_destino[d] >= tope:
            continue
        por_destino[d] += 1
        nuevos[v] = d
    todos = dict(previos)
    todos.update(nuevos)

    with open(ruta_err, "w", encoding="utf-8") as f:
        f.write("# Errores de tipeo de fábrica: lo que el pulgar suele escribir mal al ir rápido en el\n")
        f.write("# celular (letra que no salió, tecla vecina, dos teclas a la vez, rebote, letras\n")
        f.write("# invertidas y confusiones de oído). Solo los que no son palabras y que una sola\n")
        f.write("# palabra explica. Los genera herramientas/generar_errores.py. Formato: escrito:correcto\n")
        for v in sorted(todos):
            f.write(f"{v}:{todos[v]}\n")
    print(f"palabras entrenadas: {usadas} · antes: {len(previos)} · corregidos: {corregidos} · nuevos: {len(nuevos)} · total: {len(todos)}")


if __name__ == "__main__":
    main()
