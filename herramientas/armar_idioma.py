#!/usr/bin/env python3
"""
Arma el léxico de otro idioma (inglés o portugués) para escribir mezclado con el español.

Uso: armar_idioma.py <frecuencias.txt> <salida.txt> <en|pt> [cuantas]

<frecuencias.txt> es una lista "palabra cuenta" ordenada por frecuencia (FrequencyWords).
Se quedan solo palabras de verdad: letras (y el apóstrofo en inglés: don't, I'm), sin
pedacitos de contracciones sueltos ("ll", "ve") ni letras sueltas raras.
"""
import re
import sys

PEDACITOS = {"ll", "ve", "re", "nt", "em", "th", "st", "nd", "rd", "ii", "iii"}
SUELTAS = {"en": {"a", "i"}, "pt": {"a", "e", "o", "é", "à"}}


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    origen, destino, idioma = sys.argv[1], sys.argv[2], sys.argv[3]
    cuantas = int(sys.argv[4]) if len(sys.argv) > 4 else 50000
    if idioma == "en":
        valida = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
    else:
        valida = re.compile(r"^[a-zàáâãçéêíóôõúü]+$")
    salida, vistas = [], set()
    with open(origen, encoding="utf-8", errors="ignore") as f:
        for linea in f:
            partes = linea.split()
            if not partes:
                continue
            w = partes[0].strip().lower().replace("\u2019", "'")
            if not valida.match(w) or w in vistas:
                continue
            if len(w) == 1 and w not in SUELTAS.get(idioma, set()):
                continue
            if w in PEDACITOS:
                continue
            vistas.add(w)
            salida.append("I" if (idioma == "en" and w == "i") else
                          (w.replace("i'", "I'", 1) if idioma == "en" and w.startswith("i'") else w))
            if len(salida) >= cuantas:
                break
    nombre = "inglés" if idioma == "en" else "portugués"
    with open(destino, "w", encoding="utf-8") as f:
        f.write(f"# Léxico de {nombre} por frecuencia (lo arma herramientas/armar_idioma.py)\n")
        f.write("\n".join(salida) + "\n")
    print(f"{nombre}: {len(salida)} palabras")


if __name__ == "__main__":
    main()
