package com.gabriel.letrasenvivo.superposicion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.ajustes.Configuracion
import com.gabriel.letrasenvivo.ajustes.ModoVisualizacion
import com.gabriel.letrasenvivo.databinding.SuperposicionBinding
import com.gabriel.letrasenvivo.letras.modelos.Letra
import kotlin.math.abs
import kotlin.math.roundToInt

/** La ventana flotante: se añade con WindowManager encima de cualquier app. */
class VistaSuperposicion(
    private val context: Context,
    private val alCerrar: () -> Unit,
    private val alAbrirAjustes: () -> Unit,
    private val alCambiarDesfase: (Long) -> Unit,
    private val alMoverse: (Int, Int) -> Unit,
    private val alSincronizarLinea: (Int) -> Unit,
) {
    private val gestor = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // El servicio no tiene tema de Material: se envuelve el contexto para que los estilos resuelvan.
    private val b = SuperposicionBinding.inflate(LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_LetrasEnVivo)))
    private val adaptador = AdaptadorLineas { alSincronizarLinea(it) }
    private val parametros = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var visible = false
    private var expandida = false
    private var letra: Letra? = null
    private var indice = -1
    private var modo = ModoVisualizacion.AMBOS
    private var mostrarSiguiente = true
    private var tamanoSp = 18f

    init {
        b.listaLineas.layoutManager = LinearLayoutManager(context)
        b.listaLineas.adapter = adaptador
        b.botonCerrar.setOnClickListener { alCerrar() }
        b.botonAjustes.setOnClickListener { alAbrirAjustes() }
        b.botonExpandir.setOnClickListener { alternarExpandida() }
        b.botonMenos.setOnClickListener { alCambiarDesfase(-500) }
        b.botonMas.setOnClickListener { alCambiarDesfase(500) }
        instalarGestos()
    }

    fun mostrar() {
        if (visible) return
        try {
            gestor.addView(b.root, parametros)
            visible = true
        } catch (e: Exception) {
            Registro.error(Registro.OVERLAY, "No se pudo mostrar la ventana flotante", e)
        }
    }

    fun ocultar() {
        if (!visible) return
        try { gestor.removeView(b.root) } catch (_: Exception) {}
        visible = false
    }

    fun aplicarConfiguracion(c: Configuracion) {
        modo = c.modo
        mostrarSiguiente = c.mostrarSiguiente
        tamanoSp = c.tamanoFuenteSp.toFloat()
        adaptador.modo = c.modo
        adaptador.tamanoSp = c.tamanoFuenteSp - 3f
        (b.root.background.mutate() as? GradientDrawable)?.alpha = (255 * c.opacidad / 100f).roundToInt()
        if (parametros.x != c.posicionX || parametros.y != c.posicionY) {
            parametros.x = c.posicionX
            parametros.y = c.posicionY
            actualizarVentana()
        }
        pintar()
    }

    fun mostrarCancion(nombre: String?) {
        b.textoCabecera.text = nombre ?: context.getString(R.string.nombre_app)
    }

    fun mostrarLetra(l: Letra?, mensaje: String?) {
        letra = l
        adaptador.lineas = l?.lineas.orEmpty()
        b.textoAviso.visibility = View.GONE
        if (l == null) {
            b.textoOriginal.text = mensaje ?: ""
            b.textoOriginal.textSize = tamanoSp - 2
            b.textoTraducido.visibility = View.GONE
            b.textoSiguiente.visibility = View.GONE
            return
        }
        if (l.instrumental) {
            b.textoOriginal.text = context.getString(R.string.instrumental)
            b.textoTraducido.visibility = View.GONE
            b.textoSiguiente.visibility = View.GONE
            return
        }
        val avisos = ArrayList<String>()
        if (l.aproximada) avisos += context.getString(R.string.aproximada)
        if (l.versionDistinta) avisos += context.getString(R.string.version_distinta)
        if (l.fuentes.mlKit) avisos += context.getString(R.string.traduccion_automatica)
        if (avisos.isNotEmpty()) { b.textoAviso.text = avisos.joinToString(" · "); b.textoAviso.visibility = View.VISIBLE }
        pintar()
    }

    fun mostrarDesfase(totalMs: Long) {
        b.textoDesfase.text = "desfase %+.1f s".format(totalMs / 1000.0)
    }

    fun marcarLinea(i: Int) {
        indice = i
        adaptador.indiceActual = i
        if (expandida && i >= 0) b.listaLineas.smoothScrollToPosition(i)
        pintar()
    }

    private fun pintar() {
        val l = letra ?: return
        if (l.instrumental) return
        val lineas = l.lineas
        val actual = lineas.getOrNull(indice)
        b.textoOriginal.textSize = tamanoSp
        b.textoTraducido.textSize = tamanoSp - 1
        b.textoSiguiente.textSize = (tamanoSp - 5).coerceAtLeast(10f)
        if (actual == null) {
            // Antes de la primera línea: mostrar la primera como "siguiente".
            b.textoOriginal.text = if (!l.tieneTiempos) context.getString(R.string.sin_tiempos) else "…"
            if (!l.tieneTiempos) {
                b.textoOriginal.textSize = tamanoSp - 3
                b.textoTraducido.visibility = View.GONE
                b.textoSiguiente.visibility = View.GONE
                return
            }
            b.textoTraducido.visibility = View.GONE
            val primera = lineas.firstOrNull { it.original.isNotBlank() }
            b.textoSiguiente.text = primera?.let { TextosDeLinea.para(it, modo, context).first }
            b.textoSiguiente.visibility = if (primera != null && mostrarSiguiente) View.VISIBLE else View.GONE
            return
        }
        val (original, traducida) = TextosDeLinea.para(actual, modo, context)
        b.textoOriginal.text = original
        b.textoTraducido.text = traducida
        b.textoTraducido.visibility = if (traducida == null) View.GONE else View.VISIBLE
        val siguiente = lineas.drop(indice + 1).firstOrNull { it.original.isNotBlank() }
        if (mostrarSiguiente && siguiente != null && !expandida) {
            b.textoSiguiente.text = TextosDeLinea.para(siguiente, modo, context).first
            b.textoSiguiente.visibility = View.VISIBLE
        } else b.textoSiguiente.visibility = View.GONE
    }

    private fun alternarExpandida() {
        expandida = !expandida
        b.panelExpandido.visibility = if (expandida) View.VISIBLE else View.GONE
        b.panelContraido.visibility = if (expandida) View.GONE else View.VISIBLE
        b.botonExpandir.rotation = if (expandida) 180f else 0f
        if (expandida && indice >= 0) b.listaLineas.scrollToPosition(indice)
        pintar()
    }

    private fun actualizarVentana() {
        if (!visible) return
        try { gestor.updateViewLayout(b.root, parametros) } catch (_: Exception) {}
    }

    /** Tocar = expandir/contraer; arrastrar = mover. */
    @SuppressLint("ClickableViewAccessibility")
    private fun instalarGestos() {
        val umbral = ViewConfiguration.get(context).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var px = 0; var py = 0; var arrastrando = false
        val oyente = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; px = parametros.x; py = parametros.y; arrastrando = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!arrastrando && (abs(dx) > umbral || abs(dy) > umbral)) arrastrando = true
                    if (arrastrando) { parametros.x = (px + dx).roundToInt(); parametros.y = (py + dy).roundToInt(); actualizarVentana() }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (arrastrando) alMoverse(parametros.x, parametros.y) else alternarExpandida()
                    true
                }
                else -> false
            }
        }
        b.panelContraido.setOnTouchListener(oyente)
        b.textoCabecera.setOnTouchListener(oyente)
    }

    fun estaVisible() = visible

    companion object {
        fun colorTexto(context: Context) = ContextCompat.getColor(context, R.color.texto_original)
    }
}
