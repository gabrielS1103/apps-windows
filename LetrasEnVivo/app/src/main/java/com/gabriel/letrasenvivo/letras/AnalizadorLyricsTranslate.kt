package com.gabriel.letrasenvivo.letras

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Posición de una línea dentro de la letra en lyricstranslate: párrafo y línea (ambos empezando en 0 / 1). */
data class PosicionLT(val parrafo: Int, val linea: Int) : Comparable<PosicionLT> {
    override fun compareTo(other: PosicionLT): Int =
        if (parrafo != other.parrafo) parrafo.compareTo(other.parrafo) else linea.compareTo(other.linea)
}

data class LineaLT(val posicion: PosicionLT, val texto: String)

data class ResultadoBusquedaLT(val titulo: String, val artista: String, val idioma: String?, val url: String)

data class EnlaceTraduccionLT(val idioma: String, val url: String)

/**
 * Extrae datos de las páginas HTML de lyricstranslate.com.
 * Todas las funciones son puras: reciben el HTML y devuelven listas (vacías si la página cambió de formato).
 */
object AnalizadorLyricsTranslate {

    const val BASE = "https://lyricstranslate.com"

    private val clasePosicion = Regex("^ll-(\\d+)-(\\d+)$")

    fun resultadosBusqueda(html: String): List<ResultadoBusquedaLT> {
        val doc = Jsoup.parse(html, BASE)
        val bloque = doc.selectFirst("div.block-search-res._songs") ?: return emptyList()
        return bloque.select("div.block-search-res__item").mapNotNull { item ->
            val enlaceTitulo = item.selectFirst("a.block-1-table__title") ?: return@mapNotNull null
            val titulo = enlaceTitulo.text().trim()
            val url = enlaceTitulo.absUrl("href").ifEmpty { return@mapNotNull null }
            val artista = item.selectFirst("a.block-1-table__author")?.text()?.trim().orEmpty()
            val idioma = item.selectFirst("div.table__langs")?.text()?.trim()?.ifEmpty { null }
            ResultadoBusquedaLT(titulo, artista, idioma, url)
        }
    }

    /** Líneas de la letra original en la página de la canción (…-lyrics.html). */
    fun lineasCancion(html: String): List<LineaLT> =
        lineasDe(Jsoup.parse(html, BASE), "#song-body")

    /** Líneas de la traducción en la página de traducción. */
    fun lineasTraduccion(html: String): List<LineaLT> =
        lineasDe(Jsoup.parse(html, BASE), "#translation-body")

    /** Traducciones disponibles en la página de la canción, en el orden en que aparecen. */
    fun enlacesTraduccion(html: String): List<EnlaceTraduccionLT> {
        val doc = Jsoup.parse(html, BASE)
        return doc.select("a.tags__tag").mapNotNull { a ->
            val url = a.absUrl("href").ifEmpty { return@mapNotNull null }
            if (url.endsWith("-lyrics.html")) return@mapNotNull null
            val texto = a.text().trim()
            // Las variantes se muestran como "#2" con el idioma en el atributo title ("Russian #2").
            val idioma = if (texto.startsWith("#")) a.attr("title").trim() else texto
            if (idioma.isEmpty()) null else EnlaceTraduccionLT(idioma, url)
        }
    }

    /** Elige la mejor traducción al español: "Spanish" antes que "Spanish #2", "#3"… */
    fun elegirTraduccionEspanol(enlaces: List<EnlaceTraduccionLT>): EnlaceTraduccionLT? {
        val candidatas = enlaces.filter { it.idioma.startsWith("Spanish", ignoreCase = true) }
        if (candidatas.isEmpty()) return null
        return candidatas.sortedBy { numeroVariante(it.idioma) }.first()
    }

    private fun numeroVariante(idioma: String): Int =
        Regex("#(\\d+)").find(idioma)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun lineasDe(doc: Document, selectorContenedor: String): List<LineaLT> {
        val contenedor = doc.selectFirst(selectorContenedor) ?: return emptyList()
        val lineas = ArrayList<LineaLT>()
        for (div in contenedor.select("div[class^=ll-]")) {
            val clase = div.classNames().firstOrNull { clasePosicion.matches(it) } ?: continue
            val m = clasePosicion.find(clase) ?: continue
            val posicion = PosicionLT(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            val texto = div.wholeText().replace(' ', ' ').trim()
            lineas.add(LineaLT(posicion, texto))
        }
        lineas.sortBy { it.posicion }
        return lineas
    }
}
