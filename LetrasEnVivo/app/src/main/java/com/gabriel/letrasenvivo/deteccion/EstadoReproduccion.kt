package com.gabriel.letrasenvivo.deteccion

import android.media.session.PlaybackState
import android.os.SystemClock

/** Instantánea del estado de reproducción, suficiente para calcular la posición en cualquier momento. */
data class EstadoReproduccion(
    val estado: Int,
    val posicionMs: Long,
    /** SystemClock.elapsedRealtime() del momento en que [posicionMs] era válida. */
    val marcaTiempoMs: Long,
    val velocidad: Float,
) {
    val reproduciendo: Boolean get() = estado == PlaybackState.STATE_PLAYING

    fun posicionAhora(ahoraMs: Long = SystemClock.elapsedRealtime()): Long =
        if (reproduciendo) posicionMs + ((ahoraMs - marcaTiempoMs) * velocidad).toLong() else posicionMs

    companion object {
        val vacio = EstadoReproduccion(PlaybackState.STATE_NONE, 0L, 0L, 1f)

        fun desde(ps: PlaybackState?): EstadoReproduccion {
            if (ps == null) return vacio
            val velocidad = if (ps.playbackSpeed <= 0f) 1f else ps.playbackSpeed
            return EstadoReproduccion(ps.state, ps.position, ps.lastPositionUpdateTime, velocidad)
        }
    }
}
