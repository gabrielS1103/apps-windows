package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import com.gabriel.letrasenvivo.letras.modelos.LineaLetra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimadorYDetectorTest {

    @Test
    fun repartePorTiempoYSaltaLasLineasVacias() {
        val lineas = listOf("a", "b", "", "c", "d").map { LineaLetra(null, it) }
        val r = EstimadorDeTiempos.estimar(lineas, 100_000)
        assertEquals(4, r.size)
        assertEquals(10_000L, r[0].tiempoMs)
        assertTrue(r.zipWithNext().all { (x, y) -> x.tiempoMs!! < y.tiempoMs!! })
        assertTrue(r.last().tiempoMs!! < 90_000)
        // El hueco de párrafo entre b y c es mayor que entre a y b.
        assertTrue(r[2].tiempoMs!! - r[1].tiempoMs!! > r[1].tiempoMs!! - r[0].tiempoMs!!)
    }

    @Test
    fun detectaEspanolEIngles() {
        assertEquals("es", DetectorDeIdioma.detectar(listOf("Cada ventana guarda una historia", "que se lleven lo que dijimos", "y solo quedan cenizas en el agua de la noche")))
        assertEquals("en", DetectorDeIdioma.detectar(listOf("Paper lanterns over the river", "let them carry what we said", "and the morning comes for you")))
        assertNull(DetectorDeIdioma.detectar(listOf("la la la")))
    }

    @Test
    fun limpiaTitulosDeYouTube() {
        assertEquals("Yellow", CancionDetectada.limpiarTitulo("Yellow (Official Video)"))
        assertEquals("Yellow", CancionDetectada.limpiarTitulo("Yellow [Lyrics] | Coldplay"))
        assertEquals("Coldplay" to "Yellow", CancionDetectada.separarArtistaYTitulo("Coldplay - Yellow (Official Video)", "ColdplayVEVO"))
        assertEquals("Coldplay" to "Yellow", CancionDetectada.separarArtistaYTitulo("Yellow", "Coldplay - Topic"))
    }

    @Test
    fun artistaPrincipalDeArtistasCompuestos() {
        assertEquals("Coldplay", CancionDetectada("Yellow", "Coldplay, BTS & Selena Gomez", null, 0, "p").artistaPrincipal)
        assertEquals("Shakira", CancionDetectada("x", "Shakira feat. Bizarrap", null, 0, "p").artistaPrincipal)
        assertEquals("Shakira|hips dont lie", CancionDetectada("Hips Don't Lie", "Shakira", null, 0, "p").clave.replace("shakira", "Shakira"))
    }
}
