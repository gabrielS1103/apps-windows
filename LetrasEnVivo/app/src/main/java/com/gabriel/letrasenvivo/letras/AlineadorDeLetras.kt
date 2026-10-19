package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.letras.modelos.LineaLetra
import com.gabriel.letrasenvivo.letras.modelos.LineaSincronizada

/**
 * Empareja las líneas con tiempo (LRC) con las líneas de lyricstranslate, que no tienen tiempo
 * pero sí traducción. Usa una alineación monótona (tipo Needleman-Wunsch): las parejas solo
 * avanzan, así los estribillos repetidos se emparejan en orden y no todos con la primera copia.
 */
object AlineadorDeLetras {

    const val UMBRAL = 0.62
    private const val PENALIZACION_SALTO = 0.15
    private const val MEJORA_MINIMA_UNION = 0.05

    data class Resultado(val lineas: List<LineaLetra>, val cobertura: Float, val emparejadas: Int)

    /**
     * @param sincronizadas líneas con tiempo, en orden.
     * @param originalLT líneas originales de lyricstranslate, en orden de página.
     * @param traduccion texto traducido por posición (misma numeración que [originalLT]).
     */
    fun alinear(
        sincronizadas: List<LineaSincronizada>,
        originalLT: List<LineaLT>,
        traduccion: Map<PosicionLT, String>,
        automatica: Boolean = false,
    ): Resultado {
        val n = sincronizadas.size
        val m = originalLT.size
        val normS = sincronizadas.map { NormalizadorTexto.normalizar(it.texto) }
        val normO = originalLT.map { NormalizadorTexto.normalizar(it.texto) }

        // Matriz de parecidos (n y m rondan las 60 líneas: coste despreciable).
        val sim = Array(n) { i -> DoubleArray(m) { j -> if (normS[i].isEmpty() || normO[j].isEmpty()) 0.0 else Similitud.similitudNormalizada(normS[i], normO[j]) } }

        // Programación dinámica monótona.
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        val origen = Array(n + 1) { ByteArray(m + 1) } // 1 = emparejar, 2 = saltar S, 3 = saltar O
        for (i in 1..n) { dp[i][0] = -i * PENALIZACION_SALTO; origen[i][0] = 2 }
        for (j in 1..m) { dp[0][j] = -j * PENALIZACION_SALTO; origen[0][j] = 3 }
        for (i in 1..n) {
            for (j in 1..m) {
                val s = sim[i - 1][j - 1]
                val emparejar = dp[i - 1][j - 1] + if (s >= UMBRAL) s else -1.0
                val saltarS = dp[i - 1][j] - PENALIZACION_SALTO
                val saltarO = dp[i][j - 1] - PENALIZACION_SALTO
                var mejor = emparejar; var o: Byte = 1
                if (saltarS > mejor) { mejor = saltarS; o = 2 }
                if (saltarO > mejor) { mejor = saltarO; o = 3 }
                dp[i][j] = mejor
                origen[i][j] = o
            }
        }

        val pareja = IntArray(n) { -1 }
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when (origen[i][j]) {
                1.toByte() -> { if (sim[i - 1][j - 1] >= UMBRAL) pareja[i - 1] = j - 1; i--; j-- }
                2.toByte() -> i--
                else -> j--
            }
        }

        // Refinamiento 1: lyricstranslate junta en una línea lo que el LRC tiene en dos.
        for (k in 0 until n) {
            if (pareja[k] != -1 || normS[k].isEmpty()) continue
            val anterior = if (k > 0) pareja[k - 1] else -1
            val siguiente = if (k < n - 1) pareja[k + 1] else -1
            if (anterior != -1) {
                val unido = normS[k - 1] + " " + normS[k]
                if (Similitud.similitudNormalizada(unido, normO[anterior]) > sim[k - 1][anterior] + MEJORA_MINIMA_UNION) {
                    pareja[k] = anterior; continue
                }
            }
            if (siguiente != -1) {
                val unido = normS[k] + " " + normS[k + 1]
                if (Similitud.similitudNormalizada(unido, normO[siguiente]) > sim[k + 1][siguiente] + MEJORA_MINIMA_UNION) {
                    pareja[k] = siguiente
                }
            }
        }

        // Refinamiento 2: el LRC junta en una línea lo que lyricstranslate tiene en dos.
        val extra = HashMap<Int, Int>() // índice S -> segunda línea O a concatenar
        val usadas = pareja.filter { it != -1 }.toHashSet()
        for (k in 0 until n) {
            val p = pareja[k]
            if (p == -1 || p + 1 >= m || (p + 1) in usadas) continue
            val unido = normO[p] + " " + normO[p + 1]
            if (Similitud.similitudNormalizada(normS[k], unido) > sim[k][p] + MEJORA_MINIMA_UNION) {
                extra[k] = p + 1
                usadas.add(p + 1)
            }
        }

        var emparejadas = 0
        val lineas = sincronizadas.mapIndexed { k, s ->
            val p = pareja[k]
            val traducida = if (p == -1) null else {
                val base = traduccion[originalLT[p].posicion]
                val segunda = extra[k]?.let { traduccion[originalLT[it].posicion] }
                when {
                    base == null -> segunda
                    segunda == null -> base
                    else -> "$base / $segunda"
                }
            }
            if (traducida != null) emparejadas++
            LineaLetra(s.tiempoMs, s.texto, traducida?.takeIf { it.isNotBlank() }, automatica && traducida != null)
        }
        val noVacias = normS.count { it.isNotEmpty() }
        val cobertura = if (noVacias == 0) 0f else emparejadas.toFloat() / noVacias
        return Resultado(lineas, cobertura, emparejadas)
    }

    /** Alinea líneas sin tiempo (texto plano) con lyricstranslate; devuelve la traducción por índice de línea. */
    fun alinearPlano(lineas: List<String>, originalLT: List<LineaLT>, traduccion: Map<PosicionLT, String>): Resultado {
        val falsas = lineas.map { LineaSincronizada(0L, it) }
        val r = alinear(falsas, originalLT, traduccion)
        return r.copy(lineas = r.lineas.map { it.copy(tiempoMs = null) })
    }
}
