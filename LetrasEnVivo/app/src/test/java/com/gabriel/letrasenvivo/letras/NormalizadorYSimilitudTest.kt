package com.gabriel.letrasenvivo.letras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizadorYSimilitudTest {

    @Test
    fun normalizaAcentosPuntuacionYAcotaciones() {
        assertEquals("cada ventana guarda una historia", NormalizadorTexto.normalizar("¡Cada ventana guarda una historia! (x2)"))
        assertEquals("dont stop", NormalizadorTexto.normalizar("Don't stop [Chorus]"))
        assertEquals("ya know", NormalizadorTexto.normalizar("  Ya   know..."))
        assertEquals("cancion", NormalizadorTexto.normalizar("Canción"))
    }

    @Test
    fun similitudAltaParaVariantesDeLaMismaLinea() {
        assertTrue(Similitud.similitud("And it was called Lanterns", "And it was called 'Lanterns'") > 0.9)
        assertTrue(Similitud.similitud("Light them up, light them up", "(Oh) Light them up, light them up!") > 0.75)
    }

    @Test
    fun similitudBajaParaLineasDistintas() {
        assertTrue(Similitud.similitud("Paper lanterns over the river", "Only ashes on the water") < 0.5)
    }

    @Test
    fun jaroWinklerExtremos() {
        assertEquals(1.0, Similitud.jaroWinkler("abc", "abc"), 1e-9)
        assertEquals(0.0, Similitud.jaroWinkler("abc", ""), 1e-9)
    }
}
