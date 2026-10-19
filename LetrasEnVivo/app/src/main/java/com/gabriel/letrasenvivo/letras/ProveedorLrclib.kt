package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import com.gabriel.letrasenvivo.red.ClienteHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.math.roundToLong

@Serializable
data class RespuestaLrclib(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** Letras sincronizadas de LRCLIB (https://lrclib.net), sin clave de API. */
class ProveedorLrclib(private val http: ClienteHttp) : ProveedorSincronizadas {

    override val nombre = "LRCLIB"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun buscar(cancion: CancionDetectada): LetraEncontrada? {
        val duracionSeg = if (cancion.duracionMs > 0) (cancion.duracionMs / 1000.0).roundToLong() else null
        // 1) Búsqueda exacta (LRCLIB ya comprueba la duración con ±2 s).
        if (duracionSeg != null) {
            for (titulo in cancion.variantesTitulo) {
                val directa = obtenerExacta(cancion.artista, titulo, cancion.album, duracionSeg)
                if (directa != null) return convertir(directa)
            }
        }
        // 2) Búsqueda libre por variantes del título y elección por parecido y duración.
        val consultas = buildList {
            for (t in cancion.variantesTitulo) add(t to cancion.artista)
            if (cancion.artistaPrincipal != cancion.artista) add(cancion.tituloBase to cancion.artistaPrincipal)
        }.distinct().take(4)
        for ((titulo, artista) in consultas) {
            val candidatas = buscarLibre(titulo, artista)
            val elegida = elegir(candidatas, cancion)
            if (elegida != null) return convertir(elegida)
        }
        return null
    }

    private fun convertir(r: RespuestaLrclib) = LetraEncontrada(
        fuente = nombre, id = r.id.toString(), titulo = r.trackName.orEmpty(), artista = r.artistName.orEmpty(),
        duracionSeg = r.duration, lrc = r.syncedLyrics?.takeIf { it.isNotBlank() }, plano = r.plainLyrics, instrumental = r.instrumental,
    )

    private suspend fun obtenerExacta(artista: String, titulo: String, album: String?, duracionSeg: Long): RespuestaLrclib? {
        val url = StringBuilder("https://lrclib.net/api/get?artist_name=").append(codificar(artista))
            .append("&track_name=").append(codificar(titulo))
            .append("&duration=").append(duracionSeg)
        if (!album.isNullOrBlank()) url.append("&album_name=").append(codificar(album))
        val r = http.obtener(url.toString())
        if (r.codigo == 404) return null
        if (!r.correcta) { Registro.aviso(Registro.LRCLIB, "get -> ${r.codigo}"); return null }
        return try { json.decodeFromString<RespuestaLrclib>(r.cuerpo).also { Registro.info(Registro.LRCLIB, "get \"$titulo\": id=${it.id} sincronizada=${it.syncedLyrics != null}") } }
        catch (e: Exception) { Registro.error(Registro.LRCLIB, "JSON inválido en get", e); null }
    }

    private suspend fun buscarLibre(titulo: String, artista: String): List<RespuestaLrclib> {
        val url = "https://lrclib.net/api/search?track_name=${codificar(titulo)}&artist_name=${codificar(artista)}"
        val r = http.obtener(url)
        if (!r.correcta) { Registro.aviso(Registro.LRCLIB, "search -> ${r.codigo}"); return emptyList() }
        return try { json.decodeFromString<List<RespuestaLrclib>>(r.cuerpo) }
        catch (e: Exception) { Registro.aviso(Registro.LRCLIB, "search \"$titulo\": respuesta no es una lista"); emptyList() }
    }

    private fun elegir(candidatas: List<RespuestaLrclib>, cancion: CancionDetectada): RespuestaLrclib? {
        if (candidatas.isEmpty()) return null
        // Se prefieren las sincronizadas: pequeño extra en la puntuación.
        val mejor = Emparejador.mejor(candidatas) { c ->
            Emparejador.puntuar(c.trackName.orEmpty(), c.artistName.orEmpty(), c.duration, cancion)?.plus(if (c.syncedLyrics != null) 0.1 else 0.0)
        }
        Registro.info(Registro.LRCLIB, "search: ${candidatas.size} candidatas; mejor=${mejor?.let { "id=${it.first.id} %.2f sinc=${it.first.syncedLyrics != null} dur=${it.first.duration}".format(it.second) } ?: "ninguna aceptable"}")
        return mejor?.first
    }

    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8")
}
