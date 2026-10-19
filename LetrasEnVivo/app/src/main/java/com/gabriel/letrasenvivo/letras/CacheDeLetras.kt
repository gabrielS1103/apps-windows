package com.gabriel.letrasenvivo.letras

import android.content.Context
import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.letras.modelos.Letra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class LetraGuardada(
    val version: Int = VERSION,
    val clave: String,
    val titulo: String,
    val artista: String,
    val duracionMs: Long,
    val letra: Letra? = null,
    val sinLetra: Boolean = false,
    val fechaMs: Long,
    /** Desfase que puso el usuario para esta canción; null = usar el sugerido. */
    val desfaseMs: Long? = null,
) {
    companion object { const val VERSION = 4 }
}

/** Un archivo JSON por canción en filesDir/letras. Las búsquedas sin resultado también se guardan (7 días). */
class CacheDeLetras(context: Context) {

    private val carpeta = File(context.filesDir, "letras").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun obtener(clave: String): LetraGuardada? = withContext(Dispatchers.IO) {
        val f = archivo(clave)
        if (!f.exists()) return@withContext null
        try {
            val g = json.decodeFromString<LetraGuardada>(f.readText())
            if (g.version != LetraGuardada.VERSION) { f.delete(); return@withContext null }
            if (g.sinLetra && System.currentTimeMillis() - g.fechaMs > NEGATIVA_MS) { f.delete(); return@withContext null }
            f.setLastModified(System.currentTimeMillis())
            g
        } catch (e: Exception) {
            Registro.aviso(Registro.CACHE, "Archivo de caché ilegible, se borra: ${f.name}")
            f.delete(); null
        }
    }

    suspend fun guardar(g: LetraGuardada) = withContext(Dispatchers.IO) {
        try {
            archivo(g.clave).writeText(json.encodeToString(LetraGuardada.serializer(), g))
            podar()
        } catch (e: Exception) {
            Registro.error(Registro.CACHE, "No se pudo guardar", e)
        }
    }

    suspend fun guardarDesfase(clave: String, desfaseMs: Long) {
        val g = obtener(clave) ?: return
        guardar(g.copy(desfaseMs = desfaseMs))
    }

    suspend fun borrar(clave: String) = withContext(Dispatchers.IO) { archivo(clave).delete() }

    suspend fun borrarTodo() = withContext(Dispatchers.IO) {
        carpeta.listFiles()?.forEach { it.delete() }
        Registro.info(Registro.CACHE, "Caché borrada")
    }

    private fun podar() {
        val archivos = carpeta.listFiles() ?: return
        if (archivos.size <= MAXIMO_ARCHIVOS) return
        archivos.sortedBy { it.lastModified() }.take(archivos.size - MAXIMO_ARCHIVOS).forEach { it.delete() }
    }

    private fun archivo(clave: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(clave.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(carpeta, "$hash.json")
    }

    companion object {
        private const val MAXIMO_ARCHIVOS = 500
        private const val NEGATIVA_MS = 7L * 24 * 60 * 60 * 1000
    }
}
