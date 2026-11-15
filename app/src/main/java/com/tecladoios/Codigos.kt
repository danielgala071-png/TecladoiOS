package com.tecladoios

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Códigos de verificación que llegan por mensaje: se guardan solo en memoria unos minutos para
 * ofrecerlos en la barra (como "De Mensajes" en iPhone). Nunca se escriben en ningún archivo.
 */
object Codigos {
    const val DURA = 3 * 60_000L

    @Volatile private var codigo: String? = null
    @Volatile private var llegoMs = 0L
    /** El teclado se entera al instante si está abierto */
    @Volatile var alLlegar: (() -> Unit)? = null

    fun actual(): String? {
        val c = codigo ?: return null
        if (SystemClock.uptimeMillis() - llegoMs > DURA) { codigo = null; return null }
        return c
    }

    fun usado() { codigo = null }

    fun poner(c: String) {
        codigo = c; llegoMs = SystemClock.uptimeMillis()
        alLlegar?.invoke()
    }

    private val PALABRAS = Regex("(?i)c[oó]digo|code|clave|otp|pin\\b|verificaci|verify|token|contrase[ñn]a|password|seguridad|acceso|confirma")
    private val NUMERO = Regex("(?<!\\d)(?<!\\d[,.])(\\d{3}[- ]\\d{3}|\\d{4,8})(?!\\d)(?![,.]\\d)")

    /** Busca el código en el texto del aviso: debe haber una palabra como "código" o "code" */
    fun buscar(texto: String): String? {
        if (!PALABRAS.containsMatchIn(texto)) return null
        // El número más cercano a la palabra clave (evita montos o fechas sueltas)
        val clave = PALABRAS.find(texto)?.range?.first ?: 0
        return NUMERO.findAll(texto)
            .filter { m -> !esFechaUHora(texto, m.range) }
            .minByOrNull { kotlin.math.abs(it.range.first - clave) }
            ?.groupValues?.get(1)?.replace("-", "")?.replace(" ", "")
    }

    private fun esFechaUHora(t: String, r: IntRange): Boolean {
        val antes = t.getOrNull(r.first - 1); val despues = t.getOrNull(r.last + 1)
        return antes == '/' || despues == '/' || antes == ':' || despues == ':' || antes == '$' ||
            t.regionMatches(maxOf(0, r.first - 3), "S/", 0, 2, true)
    }
}

/** Escucha los avisos (solo si lo activas en Ajustes y le das el permiso) */
class CodigosService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val p = Ajustes.prefs(this)
            if (!p.getBoolean(Ajustes.CODIGOS, false)) return
            if (sbn.packageName == packageName) return
            val ex = sbn.notification?.extras ?: return
            val texto = listOfNotNull(
                ex.getCharSequence(Notification.EXTRA_TITLE),
                ex.getCharSequence(Notification.EXTRA_TEXT),
                ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ).joinToString(" ")
            if (texto.isBlank()) return
            Codigos.buscar(texto)?.let { Codigos.poner(it) }
        } catch (e: Throwable) { Registro.anotar("códigos", e) }
    }
}
