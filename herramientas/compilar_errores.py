#!/usr/bin/env python3
"""
Compila assets/errores.txt a assets/errores.bin: el mismo contenido, pero listo para que el
teclado lo use directo desde el APK, sin leerlo línea por línea al arrancar ni copiarlo a su
memoria (Android lo "mapea" y trae solo las partes que se consultan).

Uso: compilar_errores.py <errores.txt> <errores.bin>

Formato (números de 3 bytes, little endian):
  "TPE1" | n (4 bytes) | m (4 bytes) | total_claves (4) | total_palabras (4)
  inicios de cada error (n+1) | destino de cada error (n) | inicios de cada palabra (m+1)
  bytes de los errores (UTF-8, en orden de bytes) | bytes de las palabras (UTF-8)
"""
import struct
import sys

LIMITE = 1 << 24


def u24(v):
    if not 0 <= v < LIMITE:
        raise SystemExit(f"valor fuera de rango para 3 bytes: {v}")
    return bytes((v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    pares = {}
    with open(sys.argv[1], encoding="utf-8") as f:
        for linea in f:
            linea = linea.rstrip("\n")
            if not linea or linea[0] == "#":
                continue
            i = linea.find(":")
            if i <= 0 or i >= len(linea) - 1:
                continue
            clave = linea[:i].encode("utf-8")
            destino = linea[i + 1:].strip().replace("_", " ")
            if destino and clave not in pares:
                pares[clave] = destino
    claves = sorted(pares)
    palabras, indice = [], {}
    for k in claves:
        d = pares[k]
        if d not in indice:
            indice[d] = len(palabras)
            palabras.append(d)
    n, m = len(claves), len(palabras)
    bloque_claves = b"".join(claves)
    bloque_pal = [p.encode("utf-8") for p in palabras]
    total_pal = sum(len(p) for p in bloque_pal)
    salida = bytearray(b"TPE1")
    salida += struct.pack("<iiii", n, m, len(bloque_claves), total_pal)
    pos = 0
    for k in claves:
        salida += u24(pos)
        pos += len(k)
    salida += u24(pos)
    for k in claves:
        salida += u24(indice[pares[k]])
    pos = 0
    for p in bloque_pal:
        salida += u24(pos)
        pos += len(p)
    salida += u24(pos)
    salida += bloque_claves
    salida += b"".join(bloque_pal)
    with open(sys.argv[2], "wb") as f:
        f.write(salida)
    print(f"errores: {n} · palabras destino: {m} · {len(salida) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
