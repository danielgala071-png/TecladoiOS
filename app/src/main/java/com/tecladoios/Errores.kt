package com.tecladoios

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Errores de tipeo conocidos (\"nesesito\" → \"necesito\"). Los de fábrica (millones) se leen
 * directo desde el APK sin copiarlos a memoria; los del chat y ortografía van en un mapa chico.
 */
class Errores private constructor(
    private val buf: ByteBuffer?,
    private val comunes: HashMap<String, String>,
    private val confusiones: HashMap<String, Pair<String, String>>,
) {
    private var n = 0
    private var m = 0
    private var offInicios = 0
    private var offDestinos = 0
    private var offIniPal = 0
    private var offClaves = 0
    private var offPal = 0

    init {
        val b = buf
        if (b != null && b.capacity() > 20 && b.get(0) == 'T'.code.toByte() && b.get(1) == 'P'.code.toByte()) {
            n = leInt(b, 4); m = leInt(b, 8)
            val totalClaves = leInt(b, 12)
            offInicios = 20
            offDestinos = offInicios + 3 * (n + 1)
            offIniPal = offDestinos + 3 * n
            offClaves = offIniPal + 3 * (m + 1)
            offPal = offClaves + totalClaves
        }
    }

    private fun leInt(b: ByteBuffer, i: Int) =
        (b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or
            ((b.get(i + 2).toInt() and 0xFF) shl 16) or ((b.get(i + 3).toInt() and 0xFF) shl 24)

    private fun u24(b: ByteBuffer, i: Int) =
        (b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or ((b.get(i + 2).toInt() and 0xFF) shl 16)

    /** Corrección conocida para lo escrito (en minúsculas), o null */
    fun buscar(escrito: String): String? {
        comunes[escrito]?.let { return it }
        val b = buf ?: return null
        if (n == 0) return null
        val q = escrito.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val ini = u24(b, offInicios + 3 * mid)
            val fin = u24(b, offInicios + 3 * (mid + 1))
            val c = comparar(b, offClaves + ini, fin - ini, q)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    val d = u24(b, offDestinos + 3 * mid)
                    val pi = u24(b, offIniPal + 3 * d)
                    val pf = u24(b, offIniPal + 3 * (d + 1))
                    val bytes = ByteArray(pf - pi)
                    for (i in bytes.indices) bytes[i] = b.get(offPal + pi + i)
                    return String(bytes, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun comparar(b: ByteBuffer, desde: Int, largo: Int, q: ByteArray): Int {
        val lim = minOf(largo, q.size)
        for (i in 0 until lim) {
            val x = b.get(desde + i).toInt() and 0xFF
            val y = q[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return largo - q.size
    }

    /** \"a sido\" → (\"ha\", \"sido\"): dos palabras seguidas que se corrigen juntas */
    fun confusion(anterior: String, actual: String): Pair<String, String>? =
        confusiones[Texto.normal(anterior) + " " + Texto.normal(actual)]

    companion object {
        fun cargar(c: Context): Errores {
            var buf: ByteBuffer? = null
            try {
                val fd = c.assets.openFd("errores.bin")
                val canal = FileInputStream(fd.fileDescriptor).channel
                buf = canal.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            } catch (e: Throwable) {
                Registro.anotar("errores.bin", e)
            }
            val comunes = HashMap<String, String>(4096)
            try {
                c.assets.open("errores_comunes.txt").bufferedReader().useLines { ls ->
                    for (l in ls) {
                        if (l.isEmpty() || l[0] == '#') continue
                        val i = l.indexOf(':')
                        if (i > 0 && i < l.length - 1) comunes.putIfAbsent(l.substring(0, i).trim(), l.substring(i + 1).trim().replace('_', ' '))
                    }
                }
            } catch (e: Throwable) { Registro.anotar("errores comunes", e) }
            val conf = HashMap<String, Pair<String, String>>(1024)
            try {
                c.assets.open("confusiones.txt").bufferedReader().useLines { ls ->
                    for (l in ls) {
                        if (l.isEmpty() || l[0] == '#') continue
                        val p = l.split('|')
                        if (p.size != 2) continue
                        val a = p[0].trim().split(' ')
                        val bt = p[1].trim()
                        val mas = bt.indexOf('+')
                        val b = if (mas > 0) listOf(bt.substring(0, mas).trim(), bt.substring(mas + 1).trim()) else bt.split(' ')
                        if (a.size == 2 && b.size == 2 && b[0].isNotEmpty() && b[1].isNotEmpty())
                            conf["${Texto.normal(a[0])} ${Texto.normal(a[1])}"] = b[0] to b[1]
                    }
                }
            } catch (e: Throwable) { Registro.anotar("confusiones", e) }
            return Errores(buf, comunes, conf)
        }

        fun vacio() = Errores(null, HashMap(), HashMap())
    }
}
