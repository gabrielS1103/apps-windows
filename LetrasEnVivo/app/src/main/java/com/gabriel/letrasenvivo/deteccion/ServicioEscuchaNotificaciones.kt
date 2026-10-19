package com.gabriel.letrasenvivo.deteccion

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.gabriel.letrasenvivo.Registro

/**
 * No lee notificaciones: existe solo para que el sistema nos deje consultar las sesiones de medios
 * (MediaSessionManager exige ser un "notification listener" habilitado por el usuario).
 */
class ServicioEscuchaNotificaciones : NotificationListenerService() {

    override fun onListenerConnected() {
        Registro.info(Registro.SESION, "Acceso a notificaciones conectado")
    }

    companion object {
        fun componente(context: Context) = ComponentName(context, ServicioEscuchaNotificaciones::class.java)

        fun tienePermiso(context: Context): Boolean {
            val habilitados = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val nombre = componente(context).flattenToString()
            val nombreCorto = componente(context).flattenToShortString()
            return habilitados.split(':').any { it == nombre || it == nombreCorto }
        }
    }
}
