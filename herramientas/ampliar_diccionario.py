#!/usr/bin/env python3
"""
Amplía el diccionario del teclado al compilar (lo corre GitHub Actions).

Toma una lista grande de palabras del español con su frecuencia real de uso
(subtítulos de películas y series: es el español conversado, como en el chat)
y agrega las que faltan, cada una en el lugar que le toca según qué tan común es.
Quita lo que es solo de España (vosotros, os, tenéis...) y lo que parece inglés.

Si no hay internet o algo falla, el diccionario queda como estaba (nunca rompe la compilación).
Uso: ampliar_diccionario.py <diccionario.txt> <lista_frecuencias.txt> [cuantas]
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


def main():
    if len(sys.argv) < 3:
        print("uso: ampliar_diccionario.py diccionario.txt frecuencias.txt [cuantas]")
        return
    ruta_dic, ruta_frec = sys.argv[1], sys.argv[2]
    cuantas = int(sys.argv[3]) if len(sys.argv) > 3 else 50000
    lineas = open(ruta_dic, encoding="utf-8").read().split("\n")
    while lineas and lineas[-1] == "":
        lineas.pop()

    frec = []
    for linea in open(ruta_frec, encoding="utf-8", errors="ignore"):
        partes = linea.strip().split()
        if len(partes) < 2:
            continue
        w = partes[0].lower()
        if LETRAS.match(w):
            frec.append(w)
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

    quitar = {w for w in existentes if w in QUITAR}
    nuevas = {}  # índice de línea -> palabras que van después de esa línea
    agregadas = 0
    for r, w in enumerate(frec):
        if rango[w] != r or w in existentes or w in ESPANA or w in RUIDO:
            continue
        if len(w) < 2 or parece_ingles(w) or es_de_vosotros(w, conocidas):
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
    print(f"Diccionario: {agregadas} palabras nuevas, {len(quitar)} quitadas (solo de España)")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # nunca romper la compilación por esto
        print("No se pudo ampliar el diccionario:", e)
