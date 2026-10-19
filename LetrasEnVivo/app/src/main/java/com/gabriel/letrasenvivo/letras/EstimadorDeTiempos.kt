package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.letras.modelos.LineaLetra

/**
 * Cuando no hay letra sincronizada, reparte las líneas a lo largo de la canción:
 * empieza al 10 % de la duración y termina al 90 %, dejando medio hueco en cada cambio de párrafo.
 */
object EstimadorDeTiempos {

    private const val INICIO = 0.10
    private const val FIN = 0.90

    /** @param lineas texto por renglón; renglón vacío = separación de párrafo. */
    fun estimar(lineas: List<LineaLetra>, duracionMs: Long): List<LineaLetra> {
        if (lineas.isEmpty() || duracionMs <= 0) return lineas
        val noVacias = lineas.count { it.original.isNotBlank() }
        if (noVacias == 0) return lineas
        var huecos = 0
        var previaVacia = false
        for (l in lineas) {
            val vacia = l.original.isBlank()
            if (vacia && !previaVacia) huecos++
            previaVacia = vacia
        }
        val unidades = noVacias + 0.5 * huecos
        val inicio = duracionMs * INICIO
        val paso = duracionMs * (FIN - INICIO) / unidades
        var t = inicio
        val resultado = ArrayList<LineaLetra>(noVacias)
        previaVacia = false
        for (l in lineas) {
            val vacia = l.original.isBlank()
            if (vacia) {
                if (!previaVacia) t += 0.5 * paso
            } else {
                resultado.add(l.copy(tiempoMs = t.toLong()))
                t += paso
            }
            previaVacia = vacia
        }
        return resultado
    }
}
