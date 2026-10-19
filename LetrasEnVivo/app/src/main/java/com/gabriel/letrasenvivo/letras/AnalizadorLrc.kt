package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.letras.modelos.LineaSincronizada

/** Lee letras en formato LRC: "[mm:ss.xx] texto". Admite varias marcas por línea y líneas vacías. */
object AnalizadorLrc {

    private val marca = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parsear(lrc: String): List<LineaSincronizada> {
        val resultado = ArrayList<LineaSincronizada>()
        for (lineaCruda in lrc.lineSequence()) {
            val linea = lineaCruda.trimEnd()
            val marcas = marca.findAll(linea).toList()
            if (marcas.isEmpty()) continue
            val texto = linea.substring(marcas.last().range.last + 1).trim()
            for (m in marcas) {
                val minutos = m.groupValues[1].toLong()
                val segundos = m.groupValues[2].toLong()
                val fraccion = m.groupValues[3]
                val ms = when (fraccion.length) {
                    0 -> 0L
                    1 -> fraccion.toLong() * 100
                    2 -> fraccion.toLong() * 10
                    else -> fraccion.take(3).toLong()
                }
                resultado.add(LineaSincronizada(minutos * 60_000 + segundos * 1000 + ms, texto))
            }
        }
        resultado.sortBy { it.tiempoMs }
        return resultado
    }

    private val creditos = Regex("""(作词|作曲|编曲|制作人|监制|混音|母带|录音|和声|出品|发行|词\s*[:：]|曲\s*[:：]|lyrics?\s+by|composed\s+by|produced\s+by|written\s+by|^\s*by\s*[:：])""", RegexOption.IGNORE_CASE)

    /**
     * Quita las líneas de créditos que NetEase y Kugou incluyen al principio ("作词 : …", "Título - Artista")
     * y las marcas de cabecera. Las líneas de créditos solo se eliminan si están en los primeros 15 s.
     */
    fun quitarCreditos(lineas: List<LineaSincronizada>, titulo: String, artista: String): List<LineaSincronizada> {
        val tituloNorm = NormalizadorTexto.normalizar(titulo)
        val artistaNorm = NormalizadorTexto.normalizar(artista)
        return lineas.filterIndexed { i, l ->
            if (l.tiempoMs > 15_000 || l.texto.isBlank()) return@filterIndexed true
            if (creditos.containsMatchIn(l.texto)) return@filterIndexed false
            if (i < 3) {
                val n = NormalizadorTexto.normalizar(l.texto)
                val pareceCabecera = (tituloNorm.isNotEmpty() && n.contains(tituloNorm) && (artistaNorm.isEmpty() || n.contains(artistaNorm) || n.length <= tituloNorm.length + 3))
                if (pareceCabecera) return@filterIndexed false
            }
            true
        }
    }

    /** Convierte texto plano (una línea por renglón, renglón vacío = fin de párrafo) en líneas sin tiempo. */
    fun lineasDePlano(plano: String): List<String> =
        plano.lineSequence().map { it.trim() }.toList().dropLastWhile { it.isEmpty() }
}
