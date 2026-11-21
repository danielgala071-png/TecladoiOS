# Genera app/src/main/assets/orden.bin a partir de palabras.txt.
# Ejecutar cada vez que se cambie palabras.txt (si no, el teclado ordena al cargar, más lento).
import struct, zlib
p = 'app/src/main/assets/'
datos = open(p + 'palabras.txt', 'rb').read()
P = datos.decode('utf-8').split('\n')[:-1]
T = str.maketrans('áàâäéèêëíìîïóòôöúùûü', 'aaaaeeeeiiiioooouuuu')
orden = sorted(range(len(P)), key=lambda i: (P[i].translate(T).encode('utf-8'), i))
with open(p + 'orden.bin', 'wb') as f:
    f.write(struct.pack('>iiI', 0x4F524431, len(P), zlib.crc32(datos) & 0xffffffff))
    f.write(struct.pack('>%di' % len(P), *orden))
