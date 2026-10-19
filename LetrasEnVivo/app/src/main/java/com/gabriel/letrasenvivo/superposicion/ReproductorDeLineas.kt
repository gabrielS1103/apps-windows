package com.gabriel.letrasenvivo.superposicion

import android.os.SystemClock
import com.gabriel.letrasenvivo.deteccion.EstadoReproduccion
import com.gabriel.letrasenvivo.letras.modelos.Letra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Calcula qué línea toca mostrar según la posición de reproducción. Solo emite cuando cambia el índice
 * y solo hace trabajo mientras la música está sonando.
 */
class ReproductorDeLineas(
    private val ambito: CoroutineScope,
    private val letra: StateFlow<Letra?>,
    private val reproduccion: StateFlow<EstadoReproduccion>,
    private val desfaseMs: () -> Long,
) {
    private val _indice = MutableStateFlow(-1)
    val indice: StateFlow<Int> = _indice

    private var bucle: Job? = null

    fun iniciar() {
        ambito.launch {
            combine(letra, reproduccion, EstadoSuperposicion.desfaseCancionMs) { l, r, _ -> l to r }.collect { (l, r) ->
                bucle?.cancel()
                if (l == null || !l.tieneTiempos) { _indice.value = -1; return@collect }
                bucle = launch { seguir(l, r) }
            }
        }
    }

    /** Fuerza el recálculo (p. ej. tras cambiar el desfase). */
    fun recalcular() {
        val l = letra.value ?: return
        _indice.value = indicePara(l, reproduccion.value.posicionAhora() + desfaseMs())
    }

    private suspend fun seguir(l: Letra, r: EstadoReproduccion) {
        val tiempos = l.lineas.map { it.tiempoMs ?: Long.MAX_VALUE }
        while (ambito.isActive) {
            val posicion = r.posicionAhora(SystemClock.elapsedRealtime()) + desfaseMs()
            val i = indicePara(l, posicion)
            if (_indice.value != i) _indice.value = i
            if (!r.reproduciendo) return
            val siguiente = tiempos.getOrNull(i + 1) ?: Long.MAX_VALUE
            val espera = if (siguiente == Long.MAX_VALUE) 1000L else (siguiente - posicion).coerceIn(30L, 400L)
            delay(espera)
        }
    }

    /** Última línea cuyo tiempo es <= posición (búsqueda binaria); -1 antes de la primera. */
    private fun indicePara(l: Letra, posicionMs: Long): Int {
        val lineas = l.lineas
        var bajo = 0
        var alto = lineas.size - 1
        var resultado = -1
        while (bajo <= alto) {
            val medio = (bajo + alto) / 2
            val t = lineas[medio].tiempoMs ?: Long.MAX_VALUE
            if (t <= posicionMs) { resultado = medio; bajo = medio + 1 } else alto = medio - 1
        }
        return resultado
    }
}
