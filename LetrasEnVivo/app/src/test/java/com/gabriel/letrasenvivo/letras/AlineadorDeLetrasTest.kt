package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.letras.modelos.LineaSincronizada
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlineadorDeLetrasTest {

    private fun recurso(nombre: String): String =
        javaClass.getResourceAsStream(nombre)!!.bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun alineaLetraConTiemposConTraduccionDeLyricsTranslate() {
        val json = Json.parseToJsonElement(recurso("/lrclib/cancion.json")).jsonObject
        val sincronizadas = AnalizadorLrc.parsear(json["syncedLyrics"]!!.jsonPrimitive.content)
        val original = AnalizadorLyricsTranslate.lineasCancion(recurso("/lt/cancion.html"))
        val traduccion = AnalizadorLyricsTranslate.lineasTraduccion(recurso("/lt/traduccion.html")).associate { it.posicion to it.texto }

        val r = AlineadorDeLetras.alinear(sincronizadas, original, traduccion)
        assertEquals("cobertura ${r.cobertura}", 1f, r.cobertura)
        assertEquals("Linternas de papel sobre el río", r.lineas.first { it.original.startsWith("Paper lanterns") }.traducida)
        // Acotaciones y puntuación distintas ("(Oh) … !") no impiden el emparejamiento.
        assertEquals("Enciéndelas, enciéndelas", r.lineas.first { it.original.startsWith("(Oh)") }.traducida)
        // Las pausas instrumentales no reciben traducción.
        assertTrue(r.lineas.filter { it.original.isBlank() }.all { it.traducida == null })
    }

    @Test
    fun estribilloRepetidoSeEmparejaEnOrden() {
        val s = listOf(
            LineaSincronizada(1000, "hello world"),
            LineaSincronizada(2000, "second line here"),
            LineaSincronizada(3000, "hello world"),
            LineaSincronizada(4000, "final words now"),
        )
        val o = listOf(
            LineaLT(PosicionLT(0, 1), "hello world"),
            LineaLT(PosicionLT(0, 2), "second line here"),
            LineaLT(PosicionLT(1, 1), "hello world"),
            LineaLT(PosicionLT(1, 2), "final words now"),
        )
        val t = mapOf(PosicionLT(0, 1) to "hola mundo A", PosicionLT(0, 2) to "segunda", PosicionLT(1, 1) to "hola mundo B", PosicionLT(1, 2) to "palabras finales")
        val r = AlineadorDeLetras.alinear(s, o, t)
        assertEquals(listOf("hola mundo A", "segunda", "hola mundo B", "palabras finales"), r.lineas.map { it.traducida })
    }

    @Test
    fun lineaUnidaEnLyricsTranslateRecibeLaMismaTraduccion() {
        val s = listOf(LineaSincronizada(1000, "Paper lanterns"), LineaSincronizada(2000, "over the river tonight"), LineaSincronizada(3000, "every window holds a story"))
        val o = listOf(LineaLT(PosicionLT(0, 1), "Paper lanterns, over the river tonight"), LineaLT(PosicionLT(0, 2), "every window holds a story"))
        val t = mapOf(PosicionLT(0, 1) to "Linternas de papel, sobre el río esta noche", PosicionLT(0, 2) to "cada ventana guarda una historia")
        val r = AlineadorDeLetras.alinear(s, o, t)
        assertEquals("Linternas de papel, sobre el río esta noche", r.lineas[0].traducida)
        assertEquals("Linternas de papel, sobre el río esta noche", r.lineas[1].traducida)
        assertEquals("cada ventana guarda una historia", r.lineas[2].traducida)
    }

    @Test
    fun lineasSinParecidoQuedanSinTraduccion() {
        val s = listOf(LineaSincronizada(1000, "completely different text"), LineaSincronizada(2000, "paper lanterns over the river"))
        val o = listOf(LineaLT(PosicionLT(0, 1), "paper lanterns over the river"))
        val t = mapOf(PosicionLT(0, 1) to "linternas de papel sobre el río")
        val r = AlineadorDeLetras.alinear(s, o, t)
        assertNull(r.lineas[0].traducida)
        assertEquals("linternas de papel sobre el río", r.lineas[1].traducida)
    }
}
