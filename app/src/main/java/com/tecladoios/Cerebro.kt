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
                limpiezaSemanal(app, m)
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

    /** Una vez por semana el cerebro olvida los errores de dedo que aprendió una sola vez */
    private fun limpiezaSemanal(c: Context, m: Motor) {
        try {
            val p = Ajustes.prefs(c)
            val ahora = System.currentTimeMillis()
            val ultima = p.getLong("limpieza_cerebro", 0L)
            if (ultima == 0L) { p.edit().putLong("limpieza_cerebro", ahora).apply(); return }
            if (ahora - ultima < 7L * 24 * 3600 * 1000) return
            m.aprendido.limpiar()
            p.edit().putLong("limpieza_cerebro", ahora).apply()
            m.aprendido.guardar()
        } catch (e: Throwable) { Registro.anotar("limpieza semanal", e) }
    }

    private fun lineas(c: Context, archivo: String): List<String> = try {
        c.assets.open(archivo).bufferedReader().useLines { ls -> ArmarDiccionario.utiles(ls) }
    } catch (e: Throwable) { Registro.anotar("leer $archivo", e); emptyList() }

    private fun armar(c: Context): Motor {
        // Rápido: el diccionario ya armado de la vez anterior (mientras no se actualice la app)
        val marca = try { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        val guardado = File(c.filesDir, "diccionario.bin")
        // 1) El de la vez anterior; 2) el que viene ya armado dentro del APK (recién instalada o
        //    actualizada: arranque instantáneo); 3) si no hay ninguno, se arma aquí (lento)
        val dic = Diccionario.leer(guardado, marca) ?: run {
            val listo = try { Diccionario.leer(c.assets.open(ArmarDiccionario.ARCHIVO_LISTO), Diccionario.PRECOMPILADO) } catch (_: Throwable) { null }
            (listo ?: ArmarDiccionario.armar { lineas(c, it) }).also { d ->
                hilo.execute { try { d.guardar(guardado, marca) } catch (e: Throwable) { Registro.anotar("guardar diccionario", e) } }
            }
        }
        val big = HashMap<String, Array<String>>(4096)
        try { c.assets.open("bigramas.txt").bufferedReader().useLines { Motor.leerBigramas(it, big) } } catch (e: Throwable) { Registro.anotar("bigramas", e) }
        val aprendido = Aprendizaje(File(c.filesDir, "aprendido.tsv"))
        aprendido.cargar()
        val juntas = try { c.assets.open("juntas.txt").bufferedReader().useLines { Juntas.leer(it) } }
            catch (e: Throwable) { Registro.anotar("juntas", e); Juntas.VACIA }
        return Motor(dic, Errores.cargar(c), aprendido, big, geo).also { it.juntas = juntas }
    }

    /**
     * Al desbloquear el celular: si el cerebro no está cargado lo carga, y si ya está, lo
     * «despierta» con una búsqueda de prueba (Android pudo haber dormido o comprimido su
     * memoria): así la primera tecla y la primera sugerencia salen sin espera.
     */
    @Volatile private var sumidero = 0

    fun precalentar(c: Context) {
        val m = motor ?: run { iniciar(c); return }
        hilo.execute {
            try {
                m.proponer("hola", null, null, listOf("buenos", "dias"), true)
                m.predecir(listOf("nos", "vemos"), false)
                m.letrasProbables("ho")
                // Toca la memoria del diccionario para que esté a mano
                val d = m.dic; var s = 0
                var i = 0
                while (i < d.hijo.size) { s += d.hijo[i] xor d.mejor[i]; i += 512 }
                sumidero = s
            } catch (e: Throwable) { Registro.anotar("precalentar", e) }
        }
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
