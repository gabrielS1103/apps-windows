package com.gabriel.letrasenvivo.letras

import java.text.Normalizer

/** Limpia texto para compararlo: sin acentos, minúsculas, sin puntuación ni acotaciones. */
object NormalizadorTexto {

    private val marcasDiacriticas = Regex("\\p{Mn}+")
    private val acotaciones = Regex("[\\[(][^\\])]*[\\])]")
    private val repeticiones = Regex("\\b(x|×)\\s*\\d+\\b|\\b\\d+\\s*(x|×)\\b")
    private val noAlfanumerico = Regex("[^\\p{L}\\p{Nd}\\s']")
    private val espacios = Regex("\\s+")

    fun normalizar(texto: String): String {
        var s = Normalizer.normalize(texto, Normalizer.Form.NFD)
        s = marcasDiacriticas.replace(s, "")
        s = s.lowercase()
        s = acotaciones.replace(s, " ")
        s = repeticiones.replace(s, " ")
        s = s.replace('’', '\'').replace('`', '\'')
        s = noAlfanumerico.replace(s, " ")
        s = s.replace("'", "")
        return espacios.replace(s, " ").trim()
    }

    fun tokens(texto: String): List<String> =
        normalizar(texto).split(' ').filter { it.isNotEmpty() }

    /** Normalización más suave para claves de caché: conserva solo letras, números y espacios. */
    fun clave(texto: String): String = normalizar(texto)
}
