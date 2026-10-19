package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.Registro
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Traducción en el teléfono con ML Kit: último recurso cuando no hay traducción humana. */
class TraductorMlKit {

    /**
     * Traduce las líneas al español. Devuelve null si no se pudo (idioma no reconocido, modelo sin
     * descargar y sin Wi-Fi, etc.). Las líneas vacías se devuelven vacías.
     */
    suspend fun traducir(lineas: List<String>, idiomaSugerido: String?): List<String>? {
        val texto = lineas.filter { it.isNotBlank() }
        if (texto.isEmpty()) return null
        val codigo = idiomaSugerido?.let { TranslateLanguage.fromLanguageTag(it) } ?: identificar(texto.joinToString("\n"))
        if (codigo == null) { Registro.aviso(Registro.MLKIT, "Idioma no identificado"); return null }
        if (codigo == TranslateLanguage.SPANISH) return null
        val opciones = TranslatorOptions.Builder().setSourceLanguage(codigo).setTargetLanguage(TranslateLanguage.SPANISH).build()
        val traductor = Translation.getClient(opciones)
        try {
            val condiciones = DownloadConditions.Builder().requireWifi().build()
            traductor.downloadModelIfNeeded(condiciones).esperar()
            val resultado = ArrayList<String>(lineas.size)
            for (l in lineas) resultado.add(if (l.isBlank()) "" else traductor.translate(l).esperar())
            Registro.info(Registro.MLKIT, "Traducidas ${texto.size} líneas desde $codigo")
            return resultado
        } catch (e: Exception) {
            Registro.error(Registro.MLKIT, "No se pudo traducir", e)
            return null
        } finally {
            traductor.close()
        }
    }

    private suspend fun identificar(texto: String): String? {
        val identificador = LanguageIdentification.getClient()
        return try {
            val etiqueta = identificador.identifyLanguage(texto.take(2000)).esperar()
            if (etiqueta == "und") null else TranslateLanguage.fromLanguageTag(etiqueta)
        } catch (e: Exception) {
            Registro.error(Registro.MLKIT, "Identificación de idioma fallida", e); null
        } finally {
            identificador.close()
        }
    }

    private suspend fun <T> Task<T>.esperar(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }
}
