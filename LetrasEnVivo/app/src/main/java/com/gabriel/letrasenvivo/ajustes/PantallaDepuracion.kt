package com.gabriel.letrasenvivo.ajustes

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gabriel.letrasenvivo.AplicacionLetras
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.databinding.PantallaDepuracionBinding
import com.gabriel.letrasenvivo.superposicion.EstadoSuperposicion
import com.gabriel.letrasenvivo.superposicion.ServicioSuperposicion

/** Muestra qué se detectó, qué fuentes se usaron, cómo quedó la alineación y el log. */
class PantallaDepuracion : AppCompatActivity() {

    private lateinit var b: PantallaDepuracionBinding
    private val manejador = Handler(Looper.getMainLooper())
    private val refresco = object : Runnable {
        override fun run() { b.textoInforme.text = informe(); manejador.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = PantallaDepuracionBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.botonCopiar.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LetrasEnVivo", informe()))
            Toast.makeText(this, R.string.dep_copiado, Toast.LENGTH_SHORT).show()
        }
        b.botonReintentar.setOnClickListener {
            startForegroundService(ServicioSuperposicion.intent(this, ServicioSuperposicion.ACCION_REINTENTAR))
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() { super.onResume(); manejador.post(refresco) }
    override fun onPause() { super.onPause(); manejador.removeCallbacks(refresco) }

    private fun informe(): String {
        val app = AplicacionLetras.de(this)
        val sb = StringBuilder()
        sb.append("Ventana: ${if (EstadoSuperposicion.activa.value) "encendida" else "apagada"}\n")
        sb.append("Permisos: notificaciones=${Permisos.accesoNotificaciones(this)} superposición=${Permisos.superposicion(this)}\n")
        sb.append("Metadatos: ${EstadoSuperposicion.metadatosCrudos}\n")
        sb.append("Mensaje: ${EstadoSuperposicion.mensaje.value}\n")
        sb.append("Desfase canción: ${EstadoSuperposicion.desfaseCancionMs.value} ms; general: ${app.ajustes.actual.desfaseGlobalMs} ms\n\n")
        sb.append("— Última búsqueda —\n").append(app.repositorio.ultimoInforme).append('\n')
        val letra = EstadoSuperposicion.letra.value
        if (letra != null) {
            val f = letra.fuentes
            val difDuracion = f.diferenciaDuracionSeg?.let { "%.0f s".format(it) } ?: "?"
            sb.append("— Fuentes —\nTiempos: ${f.fuenteSincronizada ?: "ninguna"} id=${f.idSincronizada} (dif. duración $difDuracion)\nLT canción=${f.urlCancionLT}\nLT traducción=${f.urlTraduccionLT}\nML Kit=${f.mlKit}\nCobertura=${"%.0f".format(f.cobertura * 100)}%  aproximada=${letra.aproximada}  idioma=${letra.idiomaOriginal}\n\n")
            sb.append("— Líneas (actual: ${EstadoSuperposicion.indiceLinea.value}) —\n")
            letra.lineas.forEachIndexed { i, l ->
                val t = l.tiempoMs?.let { "%d:%02d.%d".format(it / 60000, (it / 1000) % 60, (it % 1000) / 100) } ?: "  --  "
                sb.append(if (i == EstadoSuperposicion.indiceLinea.value) "> " else "  ").append(t).append("  ").append(l.original)
                if (l.traducida != null) sb.append("\n            ↳ ").append(l.traducida).append(if (l.traduccionAutomatica) " [auto]" else "")
                sb.append('\n')
            }
            sb.append('\n')
        }
        sb.append("— Log —\n").append(Registro.volcar())
        return sb.toString()
    }
}
