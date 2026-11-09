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
}

OIDO = [
    ("b", "v"), ("v", "b"), ("ll", "y"), ("y", "ll"), ("ce", "se"), ("ci", "si"), ("se", "ce"),
    ("si", "ci"), ("z", "s"), ("s", "z"), ("za", "sa"), ("zo", "so"), ("ge", "je"), ("gi", "ji"),
    ("je", "ge"), ("ji", "gi"), ("que", "ke"), ("qui", "ki"), ("ca", "ka"), ("co", "ko"), ("x", "s"),
    ("cc", "c"), ("rr", "r"), ("ll", "l"), ("gue", "ge"), ("gui", "gi"), ("güe", "gue"),
    ("mb", "nb"), ("mp", "np"), ("x", "cs"), ("cion", "sion"), ("sion", "cion"),
    ("yo", "llo"), ("lla", "ya"), ("ue", "we"), ("hue", "we"), ("hue", "gue"),
]


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
    maximo = int(sys.argv[4]) if len(sys.argv) > 4 else 160000

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
        if i > 60000:
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
        if len(np_) >= 6 and i < 15000:
            for v, pv in list(errs.items()):
                if pv < 0.7:
                    continue
                for v2, pv2 in errores_de(v).items():
                    if v2 != np_ and pv2 >= 0.7 and len(v2) >= len(np_) - 2:
                        errs[v2] = max(errs.get(v2, 0.0), TIPO["doble"] * pv * pv2)
        usadas += 1
        # Cupo por palabra: así ninguna palabra se lleva todos los errores
        cupo_palabra = 30 if i < 600 else 14 if i < 5000 else 7
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
        tope = 30 if r < 600 else 14 if r < 5000 else 7
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
