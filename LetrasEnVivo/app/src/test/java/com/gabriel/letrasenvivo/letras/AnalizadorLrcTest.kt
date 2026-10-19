package com.gabriel.letrasenvivo.letras

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalizadorLrcTest {

    @Test
    fun parseaMarcasConCentesimasYLineasVacias() {
        val lrc = "[00:12.40] Paper lanterns over the river\n[00:16.85] Drifting slowly into the night\n[00:25.60] \n[01:03.98] Only ashes on the water"
        val lineas = AnalizadorLrc.parsear(lrc)
        assertEquals(4, lineas.size)
        assertEquals(12_400L, lineas[0].tiempoMs)
        assertEquals("Paper lanterns over the river", lineas[0].texto)
        assertEquals("", lineas[2].texto)
        assertEquals(63_980L, lineas[3].tiempoMs)
    }

    @Test
    fun admiteVariasMarcasPorLineaYMilisegundos() {
        val lrc = "[00:10.5][00:20.123] Coro\n[01:00] Final"
        val lineas = AnalizadorLrc.parsear(lrc)
        assertEquals(listOf(10_500L, 20_123L, 60_000L), lineas.map { it.tiempoMs })
        assertEquals("Coro", lineas[1].texto)
    }

    @Test
    fun ordenaPorTiempo() {
        val lineas = AnalizadorLrc.parsear("[00:20.00] b\n[00:10.00] a")
        assertEquals(listOf("a", "b"), lineas.map { it.texto })
    }
}
