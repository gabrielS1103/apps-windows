package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import com.gabriel.letrasenvivo.letras.modelos.LineaSincronizada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Analizadores de NetEase y Kugou con respuestas de ejemplo (misma estructura que las reales, canción inventada),
 * emparejamiento por duración y artista, y limpieza de títulos de YouTube Music.
 */
class ProveedoresSincronizadasTest {

    private fun recurso(nombre: String): String =
        javaClass.getResourceAsStream(nombre)!!.bufferedReader(Charsets.UTF_8).readText()

    /** Lo que mandaría YouTube Music: título con versión y colaborador, 273 s. */
    private val radioEdit = CancionDetectada("Paper Lanterns - Radio Edit (con Guest Singer)", "The Example Band", null, 273_000, "p")

    @Test
    fun variantesDelTituloDeYouTubeMusic() {
        assertEquals(listOf("Paper Lanterns - Radio Edit (con Guest Singer)", "Paper Lanterns - Radio Edit", "Paper Lanterns"), radioEdit.variantesTitulo)
        assertEquals("Here On Earth", CancionDetectada("Here On Earth (con Cary Brothers)", "Tiësto", null, 0, "p").tituloSinColaboradores)
        val acappella = CancionDetectada("Thing Called Love (a cappella) (con Richard Bedford)", "Above & Beyond", null, 0, "p")
        assertEquals("Thing Called Love (a cappella)", acappella.tituloSinColaboradores)
        assertEquals("Thing Called Love", acappella.tituloBase)
        assertEquals("Yellow", CancionDetectada("Yellow", "Coldplay", null, 0, "p").tituloBase)
    }

    @Test
    fun netEaseBusquedaYEleccionPorDuracion() {
        val candidatos = ProveedorNetEase.analizarBusqueda(recurso("/netease/busqueda.json"))
        assertEquals(5, candidatos.size)
        assertEquals("Paper Lanterns", candidatos[0].titulo)
        assertTrue(candidatos[0].artista.contains("The Example Band"))
        // Suena una versión de 273 s: el Radio Edit de 258 s entra en tolerancia; las de 393 y 331 s no.
        val mejor = Emparejador.mejor(candidatos) { Emparejador.puntuar(it.titulo, it.artista, it.duracionSeg, radioEdit) }
        assertNotNull(mejor)
        assertEquals(5002L, mejor!!.first.id)
    }

    @Test
    fun netEaseLetraConTiemposYSinTiempos() {
        val lrc = ProveedorNetEase.analizarLetra(recurso("/netease/letra.json"))
        assertNotNull(lrc)
        val lineas = AnalizadorLrc.quitarCreditos(AnalizadorLrc.parsear(lrc!!), "Paper Lanterns", "The Example Band")
        assertEquals("Paper lanterns over the river", lineas.first().texto) // sin las líneas de créditos
        assertNull(ProveedorNetEase.analizarLetra("""{"lrc":{"lyric":"sin marcas de tiempo"}}"""))
        assertNull(ProveedorNetEase.analizarLetra("""{"uncollected":true,"lrc":{"version":0}}"""))
    }

    @Test
    fun kugouBusquedaCandidatasYDescarga() {
        val canciones = ProveedorKugou.analizarBusqueda(recurso("/kugou/busqueda.json"))
        assertEquals(5, canciones.size)
        assertTrue(canciones.all { it.hash.length == 32 })
        assertEquals("Guest Singer, The Example Band", canciones[0].artista) // "、" pasa a ", "
        val candidatas = ProveedorKugou.analizarCandidatas(recurso("/kugou/candidatas.json"))
        assertEquals(1, candidatas.size)
        assertEquals("58379942", candidatas[0].id)
        assertEquals(297.0, candidatas[0].duracionSeg!!, 0.01)
        val lrc = ProveedorKugou.analizarDescarga(recurso("/kugou/descarga.json")) { Base64.getDecoder().decode(it) }
        assertNotNull(lrc)
        val lineas = AnalizadorLrc.parsear(lrc!!)
        assertEquals("Paper lanterns over the river", lineas[0].texto)
        assertEquals(5_660L, lineas[0].tiempoMs)
    }

    @Test
    fun emparejadorDescartaDuracionesLejanasYOtrasCanciones() {
        assertNull(Emparejador.puntuar("Paper Lanterns", "The Example Band", 393.0, radioEdit))
        assertNotNull(Emparejador.puntuar("Paper Lanterns (Radio Edit)", "Guest Singer, The Example Band", 258.0, radioEdit))
        assertNull(Emparejador.puntuar("Lonely Road", "The Example Band", 273.0, radioEdit))
    }

    @Test
    fun quitaCreditosDeNetEaseYKugou() {
        val lineas = listOf(
            LineaSincronizada(0, "Paper Lanterns - The Example Band"),
            LineaSincronizada(1000, "作词 : Someone"),
            LineaSincronizada(2000, "Produced by The Example Band"),
            LineaSincronizada(27_000, "Paper lanterns over the river"),
            LineaSincronizada(60_000, "written by me, sung by you"),
        )
        val limpias = AnalizadorLrc.quitarCreditos(lineas, "Paper Lanterns", "The Example Band")
        assertEquals(listOf("Paper lanterns over the river", "written by me, sung by you"), limpias.map { it.texto })
    }

    @Test
    fun detectaVideosMusicales() {
        assertTrue(CancionDetectada.pareceVideo("Flashback (Official Video)"))
        assertTrue(CancionDetectada.pareceVideo("Despacito (Video Oficial)"))
        assertTrue(CancionDetectada.pareceVideo("Dynamite [MV]"))
        assertTrue(!CancionDetectada.pareceVideo("Flashback"))
        assertTrue(!CancionDetectada.pareceVideo("Movement"))
        // El vídeo tiene su propia entrada en caché (y su propio desfase).
        val audio = CancionDetectada("Flashback", "Calvin Harris", null, 229_000, "p")
        assertTrue(audio.clave != audio.copy(esVideo = true).clave)
    }

    @Test
    fun enModoVideoSeAceptaLaPistaDeAudioMasCorta() {
        val video = radioEdit.copy(duracionMs = 393_000, esVideo = true)
        // La pista de audio de 331 s se descartaría por duración si no fuera un vídeo.
        assertNull(Emparejador.puntuar("Paper Lanterns", "The Example Band", 331.0, video.copy(esVideo = false)))
        assertNotNull(Emparejador.puntuar("Paper Lanterns", "The Example Band", 331.0, video))
    }

    @Test
    fun remixesYArtistaEnElTitulo() {
        assertTrue(CancionDetectada("Drowning (Avicii Remix)", "Armin van Buuren", null, 0, "p").esRemix)
        assertTrue(!CancionDetectada("Mixed Emotions", "X", null, 0, "p").esRemix)
        assertEquals("Flashback", CancionDetectada.quitarArtistaDelTitulo("Calvin Harris - Flashback", "Calvin Harris"))
        assertEquals("I'm Not Alone", CancionDetectada.quitarArtistaDelTitulo("Calvin Harris I'm Not Alone", "Calvin Harris"))
        assertEquals("Yellow", CancionDetectada.quitarArtistaDelTitulo("Yellow", "Coldplay"))
    }

    @Test
    fun mismoTituloDeOtroArtistaSeDescarta() {
        val kaleidoscope = CancionDetectada("Kaleidoscope (Extended Version)", "Tiësto", null, 459_000, "p")
        assertNull(Emparejador.puntuar("Kaleidoscope", "kikuo", 459.0, kaleidoscope))
        assertNotNull(Emparejador.puntuar("Kaleidoscope", "Tiësto", 459.0, kaleidoscope))
    }

    @Test
    fun subidasDeCanalesYSellos() {
        assertTrue(CancionDetectada.esCanal("Black Hole Recordings"))
        assertTrue(CancionDetectada.esCanal("Armada Music TV"))
        assertTrue(!CancionDetectada.esCanal("Tiësto"))
        val (artista, titulo) = CancionDetectada.separarArtistaYTitulo("Tiësto featuring Kirsty Hawkshaw - Urban Train (Official Video)", "Black Hole Recordings")
        assertEquals("Urban Train", titulo)
        assertEquals("Tiësto", CancionDetectada.primerArtista(artista))
        assertEquals("Waiting", CancionDetectada.quitarArtistaDelTitulo("Dash Berlin Feat. Emma Hewitt - Waiting", "Dash Berlin y Emma Hewitt"))
    }
}
