package com.gabriel.letrasenvivo.ajustes

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.databinding.ActividadPrincipalBinding
import com.gabriel.letrasenvivo.databinding.FilaPermisoBinding
import com.gabriel.letrasenvivo.panel.BotonPanel
import com.gabriel.letrasenvivo.superposicion.EstadoSuperposicion
import com.gabriel.letrasenvivo.superposicion.ServicioSuperposicion
import kotlinx.coroutines.launch

/** Pantalla de inicio: interruptor, lista de permisos y accesos a ajustes y depuración. */
class ActividadPrincipal : AppCompatActivity() {

    private lateinit var b: ActividadPrincipalBinding
    private val filas = ArrayList<Pair<FilaPermisoBinding, () -> Boolean>>()

    private val pedirNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refrescar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActividadPrincipalBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.botonInterruptor.setOnClickListener { alternar() }
        b.botonAjustes.setOnClickListener { startActivity(Intent(this, ActividadAjustes::class.java)) }
        b.botonDepuracion.setOnClickListener { startActivity(Intent(this, PantallaDepuracion::class.java)) }
        b.botonAnadirPanel.setOnClickListener { anadirAlPanel() }

        construirPermisos()

        lifecycleScope.launch { EstadoSuperposicion.activa.collect { pintarEstado(it) } }
        lifecycleScope.launch {
            EstadoSuperposicion.cancion.collect { c ->
                b.textoCancion.text = c?.let { "${it.artista} – ${it.titulo}" } ?: getString(R.string.sin_cancion)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun construirPermisos() {
        val inflador = LayoutInflater.from(this)
        fun fila(nombre: Int, detalle: Int, comprobar: () -> Boolean, pedir: () -> Unit) {
            val f = FilaPermisoBinding.inflate(inflador, b.listaPermisos, true)
            f.permisoNombre.setText(nombre)
            f.permisoDetalle.setText(detalle)
            f.permisoBoton.setOnClickListener { pedir() }
            filas += f to comprobar
        }
        fila(R.string.permiso_notificaciones, R.string.permiso_notificaciones_detalle, { Permisos.accesoNotificaciones(this) }) {
            abrir(Permisos.intentAccesoNotificaciones(this))
        }
        fila(R.string.permiso_superposicion, R.string.permiso_superposicion_detalle, { Permisos.superposicion(this) }) {
            abrir(Permisos.intentSuperposicion(this))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            fila(R.string.permiso_avisos, R.string.permiso_avisos_detalle, { Permisos.publicarNotificaciones(this) }) {
                pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        fila(R.string.permiso_bateria, R.string.permiso_bateria_detalle, { Permisos.sinOptimizacionBateria(this) }) {
            abrir(Permisos.intentBateria(this))
        }
    }

    private fun abrir(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show() }
    }

    private fun refrescar() {
        for ((f, comprobar) in filas) {
            val ok = comprobar()
            f.permisoEstado.setText(if (ok) R.string.concedido else R.string.pendiente)
            f.permisoEstado.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.error))
            f.permisoBoton.isEnabled = !ok
        }
        pintarEstado(EstadoSuperposicion.activa.value)
    }

    private fun pintarEstado(activa: Boolean) {
        b.botonInterruptor.setText(if (activa) R.string.interruptor_apagar else R.string.interruptor_encender)
        b.textoEstado.setText(if (activa) R.string.estado_encendida else R.string.estado_apagada)
    }

    private fun alternar() {
        if (!Permisos.imprescindibles(this)) {
            Toast.makeText(this, R.string.faltan_permisos, Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(ServicioSuperposicion.intent(this, ServicioSuperposicion.ACCION_ALTERNAR))
    }

    private fun anadirAlPanel() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.anadir_al_panel_ayuda, Toast.LENGTH_LONG).show()
            return
        }
        val gestor = getSystemService(StatusBarManager::class.java)
        gestor.requestAddTileService(
            ComponentName(this, BotonPanel::class.java),
            getString(R.string.nombre_boton_panel),
            Icon.createWithResource(this, R.drawable.ic_letras),
            mainExecutor,
        ) { resultado ->
            if (resultado == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED || resultado == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                Toast.makeText(this, R.string.concedido, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
