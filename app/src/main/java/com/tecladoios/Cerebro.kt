package com.tecladoios

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import java.io.File
import java.util.concurrent.Executors

/**
 * Arma el cerebro una sola vez por proceso, en segundo plano (el teclado aparece al instante
 * y las sugerencias llegan apenas está listo). También guarda lo aprendido sin trabar nada.
 */
object Cerebro {
    val geo = Geometria()
    @Volatile var motor: Motor? = null
        private set
    @Volatile var deslizar: Deslizar? = null
        private set
    @Volatile private var cargando = false
    private val esperando = ArrayList<() -> Unit>()
    val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "cerebro").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val principal = Handler(Looper.getMainLooper())

    fun iniciar(c: Context, listo: (() -> Unit)? = null) {
        val app = c.applicationContext
        synchronized(this) {
            if (motor != null) { if (listo != null) principal.post { listo() }; return }
            listo?.let { esperando.add(it) }
            if (cargando) return
            cargando = true
        }
        hilo.execute {
            try {
                val m = armar(app)
                motor = m
                deslizar = Deslizar(m)
                if (Ajustes.prefs(app).getBoolean(Ajustes.CONTACTOS, false)) cargarContactos(app)
                // El índice para deslizar se arma después, sin apuro
                hilo.execute { try { deslizar?.preparar() } catch (e: Throwable) { Registro.anotar("preparar deslizar", e) } }
            } catch (e: Throwable) {
                Registro.anotar("armar cerebro", e)
            } finally {
                val lista: List<() -> Unit>
                synchronized(this) { cargando = false; lista = esperando.toList(); esperando.clear() }
                for (f in lista) principal.post { f() }
            }
        }
    }

    private fun lineas(c: Context, archivo: String): List<String> = try {
        c.assets.open(archivo).bufferedReader().useLines { ls -> ls.map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }.toList() }
    } catch (e: Throwable) { Registro.anotar("leer $archivo", e); emptyList() }

    private fun armar(c: Context): Motor {
        // Rápido: el diccionario ya armado de la vez anterior (mientras no se actualice la app)
        val marca = try { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        val guardado = File(c.filesDir, "diccionario.bin")
        val dic = Diccionario.leer(guardado, marca) ?: armarDiccionario(c).also { d ->
            hilo.execute { try { d.guardar(guardado, marca) } catch (e: Throwable) { Registro.anotar("guardar diccionario", e) } }
        }
        val big = HashMap<String, Array<String>>(4096)
        try { c.assets.open("bigramas.txt").bufferedReader().useLines { Motor.leerBigramas(it, big) } } catch (e: Throwable) { Registro.anotar("bigramas", e) }
        val aprendido = Aprendizaje(File(c.filesDir, "aprendido.tsv"))
        aprendido.cargar()
        return Motor(dic, Errores.cargar(c), aprendido, big, geo)
    }

    private fun armarDiccionario(c: Context): Diccionario {
        val frecuentes = lineas(c, "frecuentes.txt")
        val peru = lineas(c, "peruano.txt")
        val corte = peru.indexOf("@medio")
        val fijas = if (corte >= 0) peru.subList(0, corte) else peru
        val medio = if (corte >= 0) peru.subList(corte + 1, peru.size) else emptyList()
        val principal = ArrayList<String>(300_000)
        principal.addAll(frecuentes)
        var conFijas = false
        var conMedio = false
        for (w in lineas(c, "diccionario.txt")) {
            if (w == "@fijas") { principal.addAll(fijas); conFijas = true; continue }
            principal.add(w)
            if (!conMedio && principal.size >= 30_000) { principal.addAll(medio); conMedio = true }
        }
        if (!conFijas) principal.addAll(fijas)
        if (!conMedio) principal.addAll(medio)
        val ingles = lineas(c, "ingles.txt")
        val portugues = lineas(c, "portugues.txt")
        return Diccionario.armar(principal, listOf(ingles to 1.4f, portugues to 1.9f))
    }

    fun cargarContactos(c: Context) {
        val m = motor ?: return
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { m.contactos = emptyMap(); return }
        hilo.execute {
            val mapa = HashMap<String, String>()
            try {
                c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { cur ->
                    while (cur.moveToNext() && mapa.size < 4000) {
                        val nombre = cur.getString(0) ?: continue
                        for (parte in nombre.split(' ', '-')) {
                            if (parte.length < 2 || !parte.all { it.isLetter() }) continue
                            val k = Texto.normal(parte)
                            if (m.dic.existe(k)) continue
                            mapa.putIfAbsent(k, parte.replaceFirstChar { it.uppercaseChar() })
                        }
                    }
                }
            } catch (e: Throwable) { Registro.anotar("contactos", e) }
            m.contactos = mapa
        }
    }

    /** Guarda lo aprendido en segundo plano (si hubo cambios) */
    fun guardar() {
        val m = motor ?: return
        if (m.aprendido.cambios == 0) return
        hilo.execute { m.aprendido.guardar() }
    }
}
