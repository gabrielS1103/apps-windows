package com.gabriel.letrasenvivo.panel

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gabriel.letrasenvivo.AplicacionLetras
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.ajustes.ActividadPrincipal
import com.gabriel.letrasenvivo.ajustes.Permisos
import com.gabriel.letrasenvivo.superposicion.EstadoSuperposicion
import com.gabriel.letrasenvivo.superposicion.ServicioSuperposicion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Botón del panel de ajustes rápidos: un toque enciende o apaga la ventana flotante. */
class BotonPanel : TileService() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observacion: Job? = null

    /** Mientras el panel está abierto, el botón se repinta solo cuando cambia el estado o la canción. */
    override fun onStartListening() {
        pintar()
        observacion?.cancel()
        observacion = ambito.launch {
            combine(EstadoSuperposicion.activa, EstadoSuperposicion.cancion) { a, c -> a to c }.collect { pintar() }
        }
    }

    override fun onStopListening() {
        observacion?.cancel()
        observacion = null
    }

    override fun onDestroy() {
        observacion?.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (!Permisos.imprescindibles(this)) {
            Registro.aviso(Registro.PANEL, "Faltan permisos: se abre la app")
            abrir(Intent(this, ActividadPrincipal::class.java))
            return
        }
        try {
            startForegroundService(ServicioSuperposicion.intent(this, ServicioSuperposicion.ACCION_ALTERNAR))
            Registro.info(Registro.PANEL, "Alternar desde el panel")
        } catch (e: Exception) {
            // Android puede negarse a arrancar un servicio en primer plano desde aquí: lo hace una actividad invisible.
            Registro.aviso(Registro.PANEL, "No se pudo arrancar el servicio directamente (${e.javaClass.simpleName}); plan B")
            abrir(Intent(this, ActividadInterruptor::class.java))
        }
    }

    private fun abrir(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun pintar() {
        val tile = qsTile ?: return
        val activa = EstadoSuperposicion.activa.value || AplicacionLetras.de(this).ajustes.actual.superposicionActiva
        tile.state = if (activa) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_letras)
        tile.label = getString(R.string.nombre_boton_panel)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !activa -> null
                EstadoSuperposicion.cancion.value != null -> EstadoSuperposicion.cancion.value?.titulo
                else -> getString(R.string.sin_musica)
            }
        }
        tile.updateTile()
    }
}
