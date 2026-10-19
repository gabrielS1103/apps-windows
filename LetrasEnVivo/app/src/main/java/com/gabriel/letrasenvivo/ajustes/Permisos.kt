package com.gabriel.letrasenvivo.ajustes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.gabriel.letrasenvivo.deteccion.ServicioEscuchaNotificaciones

/** Comprobaciones y accesos a los ajustes del sistema para cada permiso que necesita la app. */
object Permisos {

    fun accesoNotificaciones(context: Context) = ServicioEscuchaNotificaciones.tienePermiso(context)

    fun superposicion(context: Context) = Settings.canDrawOverlays(context)

    fun publicarNotificaciones(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun sinOptimizacionBateria(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Los imprescindibles para que la ventana funcione. */
    fun imprescindibles(context: Context) = accesoNotificaciones(context) && superposicion(context)

    fun intentAccesoNotificaciones(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).also { i ->
            if (Build.VERSION.SDK_INT >= 30) {
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ServicioEscuchaNotificaciones.componente(context).flattenToString())
            }
        }

    fun intentSuperposicion(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun intentBateria(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
