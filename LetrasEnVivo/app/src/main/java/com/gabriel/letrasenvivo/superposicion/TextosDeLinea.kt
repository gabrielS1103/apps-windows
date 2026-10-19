package com.gabriel.letrasenvivo.superposicion

import android.content.Context
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.ajustes.ModoVisualizacion
import com.gabriel.letrasenvivo.letras.modelos.LineaLetra

/** Decide qué textos se muestran para una línea según el modo elegido. null = no mostrar ese renglón. */
object TextosDeLinea {
    fun para(l: LineaLetra, modo: ModoVisualizacion, context: Context): Pair<String?, String?> {
        if (l.original.isBlank()) return "♪" to null
        return when (modo) {
            ModoVisualizacion.AMBOS -> l.original to l.traducida
            ModoVisualizacion.SOLO_ORIGINAL -> l.original to null
            ModoVisualizacion.SOLO_ESPANOL -> if (l.traducida != null) l.traducida to null else l.original to context.getString(R.string.sin_traduccion)
        }
    }
}
