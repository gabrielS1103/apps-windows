package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import kotlin.math.abs

/** Letra encontrada en una fuente de letras con tiempos (LRCLIB, NetEase, Kugou…). */
data class LetraEncontrada(
    val fuente: String,
    val id: String,
    val titulo: String,
    val artista: String,
    val duracionSeg: Double?,
    /** Texto en formato LRC, o null si la fuente solo tiene letra plana. */
    val lrc: String?,
    val plano: String? = null,
    val instrumental: Boolean = false,
) {
    val sincronizada: Boolean get() = !lrc.isNullOrBlank()

    /** Diferencia de duración con la canción que suena, en segundos (null si alguna no se conoce). */
    fun diferenciaDuracion(cancion: CancionDetectada): Double? =
        if (duracionSeg == null || cancion.duracionMs <= 0) null else abs(duracionSeg - cancion.duracionMs / 1000.0)
}

/** Fuente de letras con tiempos. */
interface ProveedorSincronizadas {
    val nombre: String
    suspend fun buscar(cancion: CancionDetectada): LetraEncontrada?
}

/** Puntúa candidatos de cualquier fuente contra la canción que suena. */
object Emparejador {
    /** Más de esta diferencia de duración se considera otra versión (otro arreglo) y se descarta. */
    const val TOLERANCIA_DURACION_SEG = 15.0
    /** Hasta esta diferencia se considera la misma grabación: los tiempos valen sin ajustar. */
    const val MISMA_VERSION_SEG = 3.0
    const val MINIMO = 0.6
    /** Parecido mínimo del artista: por debajo es otra canción con el mismo título. */
    const val ARTISTA_MINIMO = 0.7

    /**
     * Devuelve la puntuación (0..1) o null si el candidato se descarta.
     * @param duracionSeg duración del candidato, si la fuente la conoce.
     */
    fun puntuar(titulo: String, artista: String, duracionSeg: Double?, cancion: CancionDetectada): Double? {
        val simTitulo = cancion.variantesTitulo.maxOf { Similitud.similitud(titulo, it) }
        val artistaNorm = NormalizadorTexto.normalizar(artista)
        val principalNorm = NormalizadorTexto.normalizar(cancion.artistaPrincipal)
        val simArtista = when {
            principalNorm.isNotEmpty() && artistaNorm.contains(principalNorm) -> 1.0
            else -> maxOf(Similitud.similitud(artista, cancion.artista), Similitud.similitud(artista, cancion.artistaPrincipal))
        }
        if (simArtista < ARTISTA_MINIMO) return null
        var puntos = 0.6 * simTitulo + 0.4 * simArtista
        if (duracionSeg != null && cancion.duracionMs > 0) {
            val dif = abs(duracionSeg - cancion.duracionMs / 1000.0)
            if (cancion.esVideo) {
                // Vídeo: se busca la pista de audio, que suele ser más corta (el vídeo añade intro o final).
                // Se acepta cualquier duración razonable y solo se penaliza un poco que sea más larga que el vídeo.
                if (duracionSeg > cancion.duracionMs / 1000.0 + 10) puntos -= 0.15
                puntos -= 0.002 * dif
            } else {
                if (dif > TOLERANCIA_DURACION_SEG) return null
                puntos -= 0.015 * dif
            }
        }
        return if (puntos >= MINIMO) puntos else null
    }

    /** Elige el mejor candidato de una lista ya puntuada; null si ninguno pasa. */
    fun <T> mejor(candidatos: List<T>, puntuacion: (T) -> Double?): Pair<T, Double>? =
        candidatos.mapNotNull { c -> puntuacion(c)?.let { c to it } }.maxByOrNull { it.second }
}
