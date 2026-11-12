package com.tecladoios

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Contraseñas recordadas, guardadas SOLO en el celular y cifradas.
 *
 * - La llave de cifrado (AES-256-GCM) vive en el Android Keystore: está dentro del chip de
 *   seguridad del celular y no se puede sacar ni copiar, ni siquiera la app puede leerla.
 * - Lo que queda en el archivo (dentro de la carpeta privada de la app) es ilegible sin esa
 *   llave. Ni el nombre de la app se guarda en claro: se guarda su huella (SHA-256).
 * - Nada sale del celular: el teclado no usa internet y la app no tiene copia de seguridad.
 * - Solo se guarda una contraseña cuando TÚ tocas "Guardar contraseña" en la barra.
 */
object Claves {

    class Guardada(val usuario: String, val clave: String, val ms: Long)

    private const val ALIAS = "tecladoios_claves"
    private const val ARCHIVO = "claves_cifradas"
    private const val MAX_POR_APP = 5

    /** Descifrado una vez por app mientras el teclado está abierto (no en cada letra) */
    private val cache = HashMap<String, List<Guardada>>()

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    private fun huella(app: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(("tecladoios:" + app).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(h, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    @Synchronized
    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun cifrar(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave())
        val datos = c.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(datos, Base64.NO_WRAP)
    }

    private fun descifrar(s: String): String? = try {
        val i = s.indexOf(':')
        if (i <= 0) null else {
            val iv = Base64.decode(s.substring(0, i), Base64.NO_WRAP)
            val datos = Base64.decode(s.substring(i + 1), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, iv))
            String(c.doFinal(datos), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Registro.anotar("leer contraseñas", e)
        null
    }

    /** Las contraseñas guardadas para [app] (la más reciente primero) */
    @Synchronized
    fun de(c: Context, app: String?): List<Guardada> {
        if (app.isNullOrEmpty()) return emptyList()
        cache[app]?.let { return it }
        val crudo = prefs(c).getString(huella(app), null)
        val lista = if (crudo == null) emptyList() else leer(descifrar(crudo))
        cache[app] = lista
        return lista
    }

    private fun leer(json: String?): List<Guardada> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { k ->
                val o = a.optJSONObject(k) ?: return@mapNotNull null
                val clave = o.optString("c")
                if (clave.isEmpty()) null else Guardada(o.optString("u"), clave, o.optLong("t"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun escribir(c: Context, app: String, lista: List<Guardada>) {
        val k = huella(app)
        if (lista.isEmpty()) {
            prefs(c).edit().remove(k).apply()
        } else {
            val a = JSONArray()
            for (g in lista) a.put(JSONObject().put("u", g.usuario).put("c", g.clave).put("t", g.ms))
            prefs(c).edit().putString(k, cifrar(a.toString())).apply()
        }
        cache[app] = lista
    }

    /** Guarda (o actualiza) la contraseña [clave] de [usuario] en [app]. true si se guardó. */
    @Synchronized
    fun guardar(c: Context, app: String?, usuario: String, clave: String): Boolean {
        if (app.isNullOrEmpty() || clave.isEmpty()) return false
        return try {
            val antes = de(c, app)
            // Misma cuenta (o misma contraseña): se reemplaza y sube al primer lugar
            val resto = antes.filter { it.clave != clave && (usuario.isEmpty() || it.usuario != usuario) }
            escribir(c, app, (listOf(Guardada(usuario, clave, System.currentTimeMillis())) + resto).take(MAX_POR_APP))
            true
        } catch (e: Exception) {
            Registro.anotar("guardar contraseña", e)
            false
        }
    }

    @Synchronized
    fun quitar(c: Context, app: String?, clave: String) {
        if (app.isNullOrEmpty()) return
        try {
            escribir(c, app, de(c, app).filter { it.clave != clave })
        } catch (e: Exception) { Registro.anotar("quitar contraseña", e) }
    }

    /** ¿Cuántas apps tienen contraseñas guardadas? (para Ajustes) */
    fun cuantasApps(c: Context): Int = prefs(c).all.size

    /** Borra TODO: las contraseñas y la llave del Keystore */
    @Synchronized
    fun borrarTodo(c: Context) {
        cache.clear()
        prefs(c).edit().clear().apply()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) { }
    }

    /** Olvida lo descifrado en memoria (al cerrar el teclado) */
    @Synchronized
    fun soltarMemoria() = cache.clear()
}
