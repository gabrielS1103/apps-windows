package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.ajustes.Ajustes
import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import com.gabriel.letrasenvivo.red.ClienteHttp
import com.gabriel.letrasenvivo.red.NavegadorOculto
import java.net.URLEncoder

/** Lo que devuelve lyricstranslate para una canción. */
data class ResultadoLT(
    val original: List<LineaLT>,
    /** Traducción al español por posición, o null si no hay traducción humana. */
    val traduccion: Map<PosicionLT, String>?,
    /** Idioma de la canción según lyricstranslate ("English", "Spanish"…). */
    val idiomaCancion: String?,
    val urlCancion: String,
    val urlTraduccion: String?,
    /** Candidatos de la búsqueda con su puntuación, para la depuración. */
    val candidatos: List<String>,
)

class BloqueoLyricsTranslate(mensaje: String) : Exception(mensaje)

/**
 * Traducciones humanas de lyricstranslate.com. Tres páginas por canción: búsqueda, canción y traducción.
 * Si Cloudflare interpone su desafío, se reintenta con el WebView oculto; si tampoco pasa, se activa
 * un enfriamiento para no insistir.
 */
class ProveedorLyricsTranslate(
    private val http: ClienteHttp,
    private val navegador: NavegadorOculto?,
    private val ajustes: Ajustes,
) {

    suspend fun buscar(cancion: CancionDetectada): ResultadoLT? {
        val ahora = System.currentTimeMillis()
        if (ahora < ajustes.ltBloqueadoHasta) {
            Registro.aviso(Registro.LT, "En enfriamiento hasta dentro de ${(ajustes.ltBloqueadoHasta - ahora) / 60000} min")
            return null
        }
        return try {
            buscarInterno(cancion).also { ajustes.ltBloqueosSeguidos = 0 }
        } catch (e: BloqueoLyricsTranslate) {
            val n = ajustes.ltBloqueosSeguidos + 1
            ajustes.ltBloqueosSeguidos = n
            val minutos = minOf(15L shl (n - 1), 360L)
            ajustes.ltBloqueadoHasta = System.currentTimeMillis() + minutos * 60_000
            Registro.aviso(Registro.LT, "Bloqueado (${e.message}); enfriamiento de $minutos min")
            null
        }
    }

    private suspend fun buscarInterno(cancion: CancionDetectada): ResultadoLT? {
        // 1) Búsqueda.
        // Sin "(con X)" ni "- Radio Edit": lyricstranslate indexa la canción base.
        var (elegido, candidatos) = buscarCancion(cancion.artista, cancion.tituloBase, cancion)
        if (elegido == null && cancion.artistaPrincipal != cancion.artista) {
            val segunda = buscarCancion(cancion.artistaPrincipal, cancion.tituloBase, cancion)
            elegido = segunda.first
            candidatos = candidatos + segunda.second
        }
        if (elegido == null) { Registro.info(Registro.LT, "Sin resultado aceptable para $cancion"); return null }

        // 2) Página de la canción: letra original y traducciones disponibles.
        val htmlCancion = descargar(elegido.url) ?: return null
        val original = AnalizadorLyricsTranslate.lineasCancion(htmlCancion)
        if (original.isEmpty()) { Registro.aviso(Registro.LT, "La página de la canción no tiene líneas reconocibles: ${elegido.url}"); return null }
        val enlaces = AnalizadorLyricsTranslate.enlacesTraduccion(htmlCancion)
        Registro.info(Registro.LT, "Canción: ${elegido.url} (${original.size} líneas, idioma=${elegido.idioma}, ${enlaces.size} traducciones)")

        if (elegido.idioma?.startsWith("Spanish", ignoreCase = true) == true) {
            return ResultadoLT(original, null, elegido.idioma, elegido.url, null, candidatos)
        }
        val enlaceEs = AnalizadorLyricsTranslate.elegirTraduccionEspanol(enlaces)
            ?: return ResultadoLT(original, null, elegido.idioma, elegido.url, null, candidatos)

        // 3) Página de la traducción.
        val htmlTraduccion = descargar(enlaceEs.url) ?: return ResultadoLT(original, null, elegido.idioma, elegido.url, null, candidatos)
        val traduccion = AnalizadorLyricsTranslate.lineasTraduccion(htmlTraduccion)
        if (traduccion.isEmpty()) {
            Registro.aviso(Registro.LT, "La traducción no tiene líneas reconocibles: ${enlaceEs.url}")
            return ResultadoLT(original, null, elegido.idioma, elegido.url, enlaceEs.url, candidatos)
        }
        Registro.info(Registro.LT, "Traducción: ${enlaceEs.url} (${traduccion.size} líneas, \"${enlaceEs.idioma}\")")
        return ResultadoLT(original, traduccion.associate { it.posicion to it.texto }, elegido.idioma, elegido.url, enlaceEs.url, candidatos)
    }

    private suspend fun buscarCancion(artista: String, titulo: String, cancion: CancionDetectada): Pair<ResultadoBusquedaLT?, List<String>> {
        val consulta = URLEncoder.encode("$artista $titulo", "UTF-8")
        val html = descargar("${AnalizadorLyricsTranslate.BASE}/en/site-search?query=$consulta") ?: return null to emptyList()
        val resultados = AnalizadorLyricsTranslate.resultadosBusqueda(html)
        val puntuados = resultados.map { r ->
            val simTitulo = Similitud.similitud(r.titulo, titulo)
            val simArtista = maxOf(Similitud.similitud(r.artista, artista), Similitud.similitud(r.artista, cancion.artistaPrincipal))
            // Mismo título de otro artista = otra canción: se descarta aunque el título sea idéntico.
            r to (if (simArtista < Emparejador.ARTISTA_MINIMO || simTitulo < 0.7) 0.0 else 0.6 * simTitulo + 0.4 * simArtista)
        }.sortedByDescending { it.second }
        val descripcion = puntuados.take(5).map { "%.2f %s – %s [%s] %s".format(it.second, it.first.artista, it.first.titulo, it.first.idioma, it.first.url) }
        val mejor = puntuados.firstOrNull()
        Registro.info(Registro.LT, "Búsqueda \"$artista $titulo\": ${resultados.size} resultados; mejor=${descripcion.firstOrNull()}")
        return (if (mejor != null && mejor.second >= 0.7) mejor.first else null) to descripcion
    }

    /** Descarga una página; si Cloudflare la bloquea, prueba con el WebView oculto. */
    private suspend fun descargar(url: String): String? {
        val r = http.obtener(url)
        if (r.correcta) return r.cuerpo
        if (!ClienteHttp.esDesafioCloudflare(r)) {
            Registro.aviso(Registro.LT, "HTTP ${r.codigo} en $url")
            return if (r.codigo == 404) null else throw BloqueoLyricsTranslate("HTTP ${r.codigo}")
        }
        Registro.aviso(Registro.LT, "Desafío de Cloudflare (${r.codigo}); probando con el navegador oculto")
        val html = navegador?.obtener(url)
        if (html != null && !html.contains("challenges.cloudflare.com") && !html.contains("Just a moment")) return html
        throw BloqueoLyricsTranslate("desafío de Cloudflare no superado")
    }
}
