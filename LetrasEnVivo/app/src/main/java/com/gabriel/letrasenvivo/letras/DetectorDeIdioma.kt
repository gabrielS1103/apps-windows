package com.gabriel.letrasenvivo.letras

/** Heurística sencilla con palabras frecuentes para distinguir español de inglés. */
object DetectorDeIdioma {

    private val espanol = setOf(
        "de", "que", "el", "la", "y", "en", "no", "un", "una", "por", "con", "te", "mi", "tu", "es",
        "se", "lo", "me", "para", "como", "pero", "del", "al", "los", "las", "si", "mas", "ya", "su",
        "yo", "quiero", "amor", "corazon", "nunca", "siempre", "todo", "nada", "donde", "cuando",
        "porque", "aqui", "sin", "eres", "soy", "esta", "estoy", "vida", "tengo", "noche", "quiere",
    )
    private val ingles = setOf(
        "the", "and", "you", "to", "of", "a", "i", "in", "it", "my", "your", "is", "me", "that", "on",
        "for", "with", "we", "be", "this", "all", "but", "not", "are", "was", "so", "just", "like",
        "what", "know", "love", "never", "when", "can", "don't", "dont", "got", "want", "baby", "yeah",
        "oh", "im", "i'm", "will", "now", "they", "she", "he", "if", "do", "up", "out", "been", "about",
    )

    /** Devuelve "es", "en" o null si no hay suficiente evidencia. */
    fun detectar(lineas: List<String>): String? {
        val tokens = lineas.flatMap { NormalizadorTexto.tokens(it) }
        if (tokens.size < 8) return null
        val es = tokens.count { it in espanol }.toDouble() / tokens.size
        val en = tokens.count { it in ingles }.toDouble() / tokens.size
        return when {
            es >= 0.12 && es > en * 1.5 -> "es"
            en >= 0.12 && en > es * 1.5 -> "en"
            else -> null
        }
    }

    /** Convierte el nombre de idioma de lyricstranslate ("English", "Spanish"…) a código ISO. */
    fun codigoDesdeNombre(nombre: String?): String? = when (nombre?.lowercase()?.substringBefore(" (")?.trim()) {
        null -> null
        "english" -> "en"; "spanish" -> "es"; "portuguese" -> "pt"; "french" -> "fr"; "italian" -> "it"
        "german" -> "de"; "korean" -> "ko"; "japanese" -> "ja"; "chinese" -> "zh"; "russian" -> "ru"
        "turkish" -> "tr"; "arabic" -> "ar"; "hindi" -> "hi"; "dutch" -> "nl"; "swedish" -> "sv"
        "polish" -> "pl"; "greek" -> "el"; "romanian" -> "ro"; "catalan" -> "ca"; "indonesian" -> "id"
        "thai" -> "th"; "vietnamese" -> "vi"; "ukrainian" -> "uk"; "hebrew" -> "he"; "finnish" -> "fi"
        "norwegian" -> "no"; "danish" -> "da"; "czech" -> "cs"; "hungarian" -> "hu"; "tagalog" -> "tl"
        else -> null
    }
}
