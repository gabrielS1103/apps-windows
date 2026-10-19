package com.gabriel.letrasenvivo.panel

import android.app.Activity
import android.os.Bundle
import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.superposicion.ServicioSuperposicion

/** Actividad sin interfaz: arranca el servicio (desde una actividad siempre está permitido) y se cierra. */
class ActividadInterruptor : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startForegroundService(ServicioSuperposicion.intent(this, ServicioSuperposicion.ACCION_ALTERNAR))
        } catch (e: Exception) {
            Registro.error(Registro.PANEL, "No se pudo arrancar el servicio", e)
        }
        finish()
    }
}
