#!/usr/bin/env python3
"""
Amplía el diccionario del teclado al compilar (lo corre GitHub Actions).

Toma una lista grande de palabras del español con su frecuencia real de uso
(subtítulos de películas y series: es el español conversado, como en el chat)
y agrega las que faltan, cada una en el lugar que le toca según qué tan común es.
Quita lo que es solo de España (vosotros, os, tenéis...) y lo que parece inglés.

Con la lista completa (es_full, más de un millón de formas) entran también las palabras menos
comunes pero reales: las formales (-ción, -idad, -mente...), del trabajo y del Estado. Para que
no se cuelen errores de tipeo de los subtítulos, una palabra rara NO se agrega si a un solo
dedazo (letra de menos, de más, cambiada por la vecina o cruzada) hay otra muchísimo más usada:
"pueblo" sí, "peublo" no.

Al final limpia errores.txt: si un "error de fábrica" ahora es una palabra del diccionario, se
quita (una palabra de verdad nunca se corrige).

Si no hay internet o algo falla, el diccionario queda como estaba (nunca rompe la compilación).
Uso: ampliar_diccionario.py <diccionario.txt> <lista_frecuencias.txt> [cuantas] [errores.txt] [minimo]
"""
import bisect
import re
import sys
import unicodedata

LETRAS = re.compile(r"^[a-záéíóúüñ]+$")
# Solo de España (en Perú y Latinoamérica no se usan así)
ESPANA = set("""
vosotros vosotras os vuestro vuestra vuestros vuestras gilipollas gilipollez gilipolleces
joder jodo coño hostia hostias guay mola molar molan molaba chaval chavales chavala chavalas
ordenador ordenadores zumo zumos currar curro curras mogollón chorrada chorradas
cabreado cabreada cabrear hala majo maja majos majas pringado pringada
""".split())
# Estas se quitan aunque ya estén en el diccionario (el resto de ESPANA solo no se agrega)
QUITAR = set("vosotros vosotras os vuestro vuestra vuestros vuestras".split())
# Palabras cortas que en los subtítulos aparecen pero no sirven en el teclado
RUIDO = set("""
id ad ed ss mm hm hmm mmm ah eh oh uh uy ay ja je ji jo ok okay hey yeah wow
""".split())


def sin_tildes(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def parece_ingles(w):
    if "k" in w or "w" in w:
        return True
    for g in ("th", "sh", "ck", "oo", "ee", "ght", "ph", "tch", "wh"):
        if g in w:
            return True
    # termina en consonante + y ("happy", "sorry"); en español casi siempre es vocal + y
    if len(w) > 2 and w.endswith("y") and w[-2] not in "aeiouáéíóú":
        return True
    # termina en letras que en español casi nunca cierran palabra
    if w[-1] in "bcfghjmpqtv" and w not in ("club", "chat", "test", "web", "internet", "robot"):
        return True
    return False


def es_de_vosotros(w, conocidas):
    if w.endswith(("áis", "éis")):
        return True
    # "decís", "vivís" (vosotros/voseo): solo si existe el verbo en -ir
    if w.endswith("ís") and len(w) > 4 and (w[:-2] + "ir") in conocidas:
        return True
    # imperativo de vosotros: "cuidad", "comed", "salid"
    if len(w) > 3 and w[-1] == "d" and w[-2] in "aei" and (w[:-1] + "r") in conocidas:
        return True
    return False


FILAS = ["qwertyuiop", "asdfghjklñ", "zxcvbnm"]
VECINAS = {}
for f, fila in enumerate(FILAS):
    for i, c in enumerate(fila):
        v = set()
        if i > 0:
            v.add(fila[i - 1])
        if i < len(fila) - 1:
            v.add(fila[i + 1])
        for g in (f - 1, f + 1):
            if 0 <= g < len(FILAS):
                otra = FILAS[g]
                # La fila de abajo está corrida: se toman las dos teclas que quedan debajo/encima
                for j in (i - 1, i, i + 1):
                    if 0 <= j < len(otra):
                        v.add(otra[j])
        VECINAS[c] = v
VOCAL_TILDE = {"a": "á", "e": "é", "i": "í", "o": "ó", "u": "ú"}


def variantes(w):
    """Lo que sale de [w] con un solo dedazo (sin contar letras de más)"""
    n = len(w)
    for i in range(n):
        yield w[:i] + w[i + 1:]                      # una letra de más en w
        if i < n - 1 and w[i] != w[i + 1]:
            yield w[:i] + w[i + 1] + w[i] + w[i + 2:]  # letras cruzadas
        base = sin_tildes(w[i])
        for v in VECINAS.get(base, ()):
            yield w[:i] + v + w[i + 1:]              # tecla vecina
        if base in VOCAL_TILDE and w[i] == base:
            yield w[:i] + VOCAL_TILDE[base] + w[i + 1:]  # le faltó la tilde


def es_dedazo(w, c, cuenta, borradas):
    """[w] (vista [c] veces) parece un error de tipeo de otra palabra muchísimo más usada"""
    tope = max(400, c * 40)
    for v in variantes(w):
        if v != w and cuenta.get(v, 0) >= tope:
            return True
    # Le falta una letra: w es una palabra común sin una de sus letras ("porqe" ← "porque")
    if borradas.get(w, 0) >= tope:
        return True
    return False


def limpiar_errores(ruta, palabras):
    """Quita de errores.txt los errores que ahora son palabras del diccionario"""
    try:
        lineas = open(ruta, encoding="utf-8").read().split("\n")
    except OSError:
        return
    salida = []
    quitados = 0
    for l in lineas:
        if l and not l.startswith("#"):
            k = l.split(":", 1)[0]
            if k in palabras:
                quitados += 1
                continue
        salida.append(l)
    if quitados:
        open(ruta, "w", encoding="utf-8").write("\n".join(salida))
    print(f"errores.txt: {quitados} errores quitados (ahora son palabras)")


def main():
    if len(sys.argv) < 3:
        print("uso: ampliar_diccionario.py diccionario.txt frecuencias.txt [cuantas]")
        return
    ruta_dic, ruta_frec = sys.argv[1], sys.argv[2]
    cuantas = int(sys.argv[3]) if len(sys.argv) > 3 else 50000
    ruta_errores = sys.argv[4] if len(sys.argv) > 4 else None
    minimo = int(sys.argv[5]) if len(sys.argv) > 5 else 1
    lineas = open(ruta_dic, encoding="utf-8").read().split("\n")
    while lineas and lineas[-1] == "":
        lineas.pop()

    frec = []
    cuenta = {}
    for linea in open(ruta_frec, encoding="utf-8", errors="ignore"):
        partes = linea.strip().split()
        if len(partes) < 2:
            continue
        w = unicodedata.normalize("NFC", partes[0].lower())
        try:
            c = int(partes[1])
        except ValueError:
            continue
        if c < minimo:
            break
        if LETRAS.match(w):
            if w not in cuenta:
                frec.append(w)
            cuenta[w] = cuenta.get(w, 0) + c
        if len(frec) >= cuantas:
            break
    if len(frec) < 1000:
        print("La lista de frecuencias está vacía o incompleta: no se cambia nada")
        return
    rango = {}
    for i, w in enumerate(frec):
        rango.setdefault(w, i)

    # Palabras que ya están (sin importar mayúsculas)
    existentes = {}
    fijas = None
    for i, l in enumerate(lineas):
        p = l.strip()
        if not p or p.startswith("#"):
            continue
        if p == "@fijas":
            fijas = i
            continue
        existentes.setdefault(p.lower(), i)
    conocidas = set(existentes) | set(frec)

    # Lugar de cada palabra existente según la lista de frecuencias (para insertar las nuevas
    # junto a palabras igual de comunes). Solo se usan las de después de @fijas: lo de arriba
    # es la lista hecha a mano con lo más usado en el chat.
    desde = (fijas + 1) if fijas is not None else 0
    anclas = sorted((rango[w], i) for w, i in existentes.items() if w in rango and i >= desde)
    claves = [a[0] for a in anclas]

    # Palabras comunes sin una de sus letras (para reconocer el error "le faltó una letra")
    borradas = {}
    for w, c in cuenta.items():
        if c < 400:
            continue
        for i in range(len(w)):
            b = w[:i] + w[i + 1:]
            if borradas.get(b, 0) < c:
                borradas[b] = c

    quitar = {w for w in existentes if w in QUITAR}
    dedazos = 0
    nuevas = {}  # índice de línea -> palabras que van después de esa línea
    agregadas = 0
    for r, w in enumerate(frec):
        if rango[w] != r or w in existentes or w in ESPANA or w in RUIDO:
            continue
        if len(w) < 2 or len(w) > 24 or parece_ingles(w) or es_de_vosotros(w, conocidas):
            continue
        # Tres letras iguales seguidas ("holaaa", "siii"): se escriben así a propósito
        if re.search(r"(.)\1\1", w):
            continue
        # Error de tipeo de los subtítulos: no entra
        if es_dedazo(w, cuenta.get(w, 0), cuenta, borradas):
            dedazos += 1
            continue
        # Una palabra sin tilde que ya existe con tilde ("esta" sí existe; "telefono" no)
        k = bisect.bisect_right(claves, r) - 1
        despues = anclas[k][1] if k >= 0 else (desde - 1 if fijas is not None else len(lineas) - 1)
        nuevas.setdefault(despues, []).append(w)
        agregadas += 1

    sin_tilde_existentes = {sin_tildes(w) for w in existentes if sin_tildes(w) != w}
    salida = []
    for i, l in enumerate(lineas):
        p = l.strip().lower()
        if p in quitar:
            continue
        salida.append(l)
        for w in nuevas.get(i, []):
            # "telefono" cuando ya está "teléfono": no se agrega la forma sin tilde
            if w in sin_tilde_existentes:
                agregadas -= 1
                continue
            salida.append(w)
    open(ruta_dic, "w", encoding="utf-8").write("\n".join(salida) + "\n")
    print(f"Diccionario: {agregadas} palabras nuevas, {len(quitar)} quitadas (solo de España), "
          f"{dedazos} errores de tipeo descartados")
    if ruta_errores:
        palabras = {l.strip().lower() for l in salida if l.strip() and not l.startswith("#")}
        limpiar_errores(ruta_errores, palabras)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # nunca romper la compilación por esto
        print("No se pudo ampliar el diccionario:", e)
