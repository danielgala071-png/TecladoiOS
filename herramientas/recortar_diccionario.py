#!/usr/bin/env python3
"""
Ahorro: deja en diccionario.txt solo las palabras generales más usadas (las primeras N; el
orden del archivo es la frecuencia) y quita de errores.txt los errores cuya palabra correcta
ya no está en ninguna lista del teclado. Así el APK pesa menos y errores.bin es más chico.

Uso: recortar_diccionario.py <assets> <N>
"""
import os
import re
import sys

assets, n = sys.argv[1], int(sys.argv[2])
dic = os.path.join(assets, "diccionario.txt")
err = os.path.join(assets, "errores.txt")

lineas = open(dic, encoding="utf-8").read().splitlines()
salida, generales, conservadas = [], 0, set()
for l in lineas:
    w = l.strip()
    if not w or w.startswith("#") or w.startswith("@"):
        salida.append(l); continue
    if generales >= n:
        continue
    generales += 1
    salida.append(l)
    conservadas.add(w.lower())
open(dic, "w", encoding="utf-8").write("\n".join(salida) + "\n")

# Palabras de las demás listas (Perú, frecuentes, frases, juntas): se conservan siempre
for extra in ("peruano.txt", "frecuentes.txt", "bigramas.txt", "juntas.txt", "errores_comunes.txt"):
    p = os.path.join(assets, extra)
    if os.path.exists(p):
        for tok in re.findall(r"[a-záéíóúüñ]+", open(p, encoding="utf-8").read().lower()):
            conservadas.add(tok)

antes = despues = 0
fuera = []
for l in open(err, encoding="utf-8"):
    if l.startswith("#") or ":" not in l:
        fuera.append(l); continue
    antes += 1
    correcto = l.rstrip("\n").split(":", 1)[1].strip().lower()
    if all(t in conservadas for t in correcto.split()):
        fuera.append(l); despues += 1
open(err, "w", encoding="utf-8").write("".join(fuera))
print(f"diccionario: {generales} palabras generales; errores: {antes} -> {despues}")
