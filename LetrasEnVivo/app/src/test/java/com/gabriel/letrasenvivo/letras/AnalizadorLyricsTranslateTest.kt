package com.gabriel.letrasenvivo.letras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Usa páginas de ejemplo en src/test/resources/lt con la misma estructura HTML que lyricstranslate.com
 * y una canción inventada ("Paper Lanterns"), para no incluir letras reales en el repositorio.
 */
class AnalizadorLyricsTranslateTest {

    private fun recurso(nombre: String): String =
        javaClass.getResourceAsStream("/lt/$nombre")!!.bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun laBusquedaDevuelveSoloCancionesConArtistaEIdioma() {
        val r = AnalizadorLyricsTranslate.resultadosBusqueda(recurso("busqueda.html"))
        assertEquals(3, r.size) // el bloque de artistas no cuenta
        val buena = r.first { it.artista == "The Example Band" && it.titulo == "Paper Lanterns" }
        assertEquals("English", buena.idioma)
        assertEquals("https://lyricstranslate.com/en/The-Example-Band-Paper-Lanterns-lyrics.html", buena.url)
    }

    @Test
    fun laPaginaDeCancionTieneLineasNumeradas() {
        val lineas = AnalizadorLyricsTranslate.lineasCancion(recurso("cancion.html"))
        assertEquals(8, lineas.size)
        assertEquals(PosicionLT(0, 1), lineas[0].posicion)
        assertEquals("Paper lanterns over the river", lineas[0].texto)
        assertEquals("Drifting slowly into the night", lineas[1].texto) // sin el salto de línea inicial
        assertTrue(lineas.any { it.posicion == PosicionLT(1, 1) && it.texto == "Light them up, light them up" })
    }

    @Test
    fun eligeLaTraduccionAlEspanolPrincipal() {
        val enlaces = AnalizadorLyricsTranslate.enlacesTraduccion(recurso("cancion.html"))
        assertEquals(4, enlaces.size) // el enlace a la propia canción no es una traducción
        val es = AnalizadorLyricsTranslate.elegirTraduccionEspanol(enlaces)
        assertNotNull(es)
        assertEquals("https://lyricstranslate.com/en/paper-lanterns-linternas-de-papel.html", es!!.url)
        // Las variantes "#2" se reconocen por el atributo title.
        assertTrue(enlaces.any { it.idioma == "Russian #2" })
    }

    @Test
    fun laTraduccionUsaLaMismaNumeracion() {
        val original = AnalizadorLyricsTranslate.lineasCancion(recurso("cancion.html"))
        val traduccion = AnalizadorLyricsTranslate.lineasTraduccion(recurso("traduccion.html"))
        assertEquals(original.size, traduccion.size)
        val mapa = traduccion.associate { it.posicion to it.texto }
        assertEquals("Linternas de papel sobre el río", mapa[original[0].posicion])
        assertEquals("Enciéndelas, enciéndelas", mapa[PosicionLT(1, 1)])
    }

    @Test
    fun htmlDesconocidoDevuelveListasVacias() {
        assertTrue(AnalizadorLyricsTranslate.resultadosBusqueda("<html><body>nada</body></html>").isEmpty())
        assertTrue(AnalizadorLyricsTranslate.lineasCancion("<html></html>").isEmpty())
        assertTrue(AnalizadorLyricsTranslate.enlacesTraduccion("<html></html>").isEmpty())
    }
}
