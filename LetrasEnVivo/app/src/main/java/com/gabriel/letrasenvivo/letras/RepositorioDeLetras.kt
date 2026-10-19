package com.gabriel.letrasenvivo.letras

import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.ajustes.Ajustes
import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import com.gabriel.letrasenvivo.letras.modelos.EstadoLetra
import com.gabriel.letrasenvivo.letras.modelos.Fuentes
import com.gabriel.letrasenvivo.letras.modelos.Letra
import com.gabriel.letrasenvivo.letras.modelos.LineaLetra
import com.gabriel.letrasenvivo.letras.modelos.LineaSincronizada
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * Orquesta la obtención de la letra de una canción:
 * caché → (fuentes con tiempos ∥ lyricstranslate) → alineación → traducción automática si hace falta → caché.
 */
class RepositorioDeLetras(
    private val cache: CacheDeLetras,
    private val sincronizadas: List<ProveedorSincronizadas>,
    private val lyricsTranslate: ProveedorLyricsTranslate,
    private val mlKit: TraductorMlKit?,
    private val ajustes: Ajustes,
) {

    /** Informe de la última búsqueda, para la pantalla de depuración. */
    @Volatile var ultimoInforme: String = ""
        private set

    fun obtener(cancion: CancionDetectada, ignorarCache: Boolean = false): Flow<EstadoLetra> = flow {
        val informe = StringBuilder("Canción: $cancion\nClave: ${cancion.clave}\nTítulos probados: ${cancion.variantesTitulo}\n")
        if (!ignorarCache) {
            val guardada = cache.obtener(cancion.clave)
            if (guardada != null) {
                informe.append("Caché: sí (${if (guardada.sinLetra) "sin letra" else "letra"})\n")
                ultimoInforme = informe.toString()
                if (guardada.sinLetra || guardada.letra == null) emit(EstadoLetra.SinLetra("sin resultados (caché)"))
                else emit(EstadoLetra.Lista(guardada.letra))
                return@flow
            }
        }
        informe.append("Caché: no\n")
        emit(EstadoLetra.Cargando)

        val config = ajustes.actual
        val letra: Letra? = try {
            coroutineScope {
                val tareasSinc = if (config.usarLrclib) sincronizadas.map { p ->
                    async { runCatching { p.buscar(cancion) }.onFailure { registrarFallo(p.nombre, it) }.getOrNull() }
                } else emptyList()
                val tareaLT = async { if (config.usarLyricsTranslate) runCatching { lyricsTranslate.buscar(cancion) }.onFailure { registrarFallo("lyricstranslate", it) }.getOrNull() else null }

                // Se espera a todas las fuentes con tiempos y se elige la versión con la duración más parecida.
                var encontradas = tareasSinc.mapNotNull { it.await() }
                // Ninguna con la misma duración: probablemente suena el vídeo o una edición distinta.
                // Se busca la pista de audio sin exigir duración y luego se compensa la diferencia.
                // No se hace con remixes: la voz va recolocada y la letra de la original nunca cuadraría.
                if (config.usarLrclib && !cancion.esVideo && !cancion.esRemix && encontradas.none { it.sincronizada }) {
                    informe.append("Sin letra de la misma duración: se busca como versión distinta\n")
                    val comoVideo = cancion.copy(esVideo = true)
                    encontradas = encontradas + sincronizadas.map { p -> async { runCatching { p.buscar(comoVideo) }.onFailure { registrarFallo(p.nombre, it) }.getOrNull() } }.mapNotNull { it.await() }
                }
                for (e in encontradas) informe.append("${e.fuente}: id=${e.id} \"${e.artista} – ${e.titulo}\" dur=${e.duracionSeg} sincronizada=${e.sincronizada}\n")
                if (encontradas.isEmpty()) informe.append("Fuentes con tiempos: nada\n")
                val elegida = elegirSincronizada(encontradas, cancion)
                if (elegida != null) informe.append("Elegida: ${elegida.fuente} (dif. duración ${elegida.diferenciaDuracion(cancion)?.let { "%.0f s".format(it) } ?: "?"})\n")

                if (elegida?.instrumental == true) {
                    tareaLT.cancel()
                    return@coroutineScope Letra.instrumental().copy(fuentes = Fuentes(fuenteSincronizada = elegida.fuente, idSincronizada = elegida.id))
                }
                val lineasSinc = elegida?.lrc?.let { AnalizadorLrc.quitarCreditos(AnalizadorLrc.parsear(it), cancion.tituloBase, cancion.artistaPrincipal) }?.takeIf { it.isNotEmpty() }
                val fuentesBase = Fuentes(
                    fuenteSincronizada = elegida?.fuente, idSincronizada = elegida?.id,
                    diferenciaDuracionSeg = elegida?.diferenciaDuracion(cancion),
                )
                val ajuste = ajusteDeVersion(cancion, elegida, lineasSinc)
                if (ajuste.first) informe.append("Versión distinta: desfase sugerido ${ajuste.second} ms\n")
                if (lineasSinc != null) {
                    emit(EstadoLetra.Parcial(Letra(lineasSinc.map { LineaLetra(it.tiempoMs, it.texto) }, fuentes = fuentesBase, versionDistinta = ajuste.first, desfaseSugeridoMs = ajuste.second)))
                }

                val r2 = tareaLT.await()
                informe.append("lyricstranslate: ${if (r2 == null) "nada" else "canción=${r2.urlCancion} idioma=${r2.idiomaCancion} traducción=${r2.urlTraduccion ?: "ninguna"} (${r2.original.size} líneas)"}\n")
                r2?.candidatos?.forEach { informe.append("  candidato: $it\n") }

                val plano = encontradas.firstOrNull { it.plano != null }?.plano
                construir(cancion, lineasSinc, plano, r2, fuentesBase, informe)
                    ?.let { if (lineasSinc != null) it.copy(versionDistinta = ajuste.first, desfaseSugeridoMs = ajuste.second) else it }
            }
        } catch (e: IOException) {
            Registro.aviso(Registro.APP, "Sin red: ${e.message}")
            ultimoInforme = informe.append("Error de red: ${e.message}\n").toString()
            emit(EstadoLetra.Error("Sin conexión"))
            return@flow
        }

        if (letra == null) {
            informe.append("Resultado: sin letra\n")
            cache.guardar(LetraGuardada(clave = cancion.clave, titulo = cancion.titulo, artista = cancion.artista, duracionMs = cancion.duracionMs, sinLetra = true, fechaMs = System.currentTimeMillis()))
            ultimoInforme = informe.toString()
            emit(EstadoLetra.SinLetra("sin resultados"))
            return@flow
        }
        informe.append("Resultado: ${letra.lineas.size} líneas, traducción=${letra.tieneTraduccion}, aproximada=${letra.aproximada}, cobertura=%.0f%%\n".format(letra.fuentes.cobertura * 100))
        cache.guardar(LetraGuardada(clave = cancion.clave, titulo = cancion.titulo, artista = cancion.artista, duracionMs = cancion.duracionMs, letra = letra, fechaMs = System.currentTimeMillis()))
        ultimoInforme = informe.toString()
        emit(EstadoLetra.Lista(letra))
    }

    /**
     * Si la letra es de otra grabación (el vídeo, una edición), propone un desfase.
     * Los vídeos musicales casi siempre añaden la diferencia de duración al principio (escenas, diálogos
     * antes de la música), así que se retrasa la letra esa diferencia, como mucho 90 s. Si no acierta,
     * el usuario toca la línea que suena y queda exacto.
     * Devuelve (es otra versión, desfase en ms; negativo = la letra va más tarde).
     */
    private fun ajusteDeVersion(cancion: CancionDetectada, elegida: LetraEncontrada?, lineas: List<LineaSincronizada>?): Pair<Boolean, Long> {
        if (elegida == null || lineas == null || elegida.duracionSeg == null || cancion.duracionMs <= 0) return (cancion.esVideo) to 0L
        val difSeg = cancion.duracionMs / 1000.0 - elegida.duracionSeg
        val distinta = cancion.esVideo || kotlin.math.abs(difSeg) > Emparejador.MISMA_VERSION_SEG
        if (!distinta || difSeg <= Emparejador.MISMA_VERSION_SEG) return distinta to 0L
        val introMs = (difSeg * 1000).toLong().coerceAtMost(90_000L)
        return true to -introMs
    }

    /**
     * Entre las letras con tiempos encontradas, la de duración más parecida a la que suena.
     * A igualdad (±2 s) gana el orden de los proveedores (LRCLIB primero).
     */
    private fun elegirSincronizada(encontradas: List<LetraEncontrada>, cancion: CancionDetectada): LetraEncontrada? {
        val conTiempos = encontradas.filter { it.sincronizada || it.instrumental }
        if (conTiempos.isEmpty()) return encontradas.firstOrNull()
        return conTiempos.sortedWith(compareBy<LetraEncontrada> { ((it.diferenciaDuracion(cancion) ?: 30.0) / 2.0).toInt() }.thenBy { encontradas.indexOf(it) }).first()
    }

    private suspend fun construir(
        cancion: CancionDetectada,
        lineasSinc: List<LineaSincronizada>?,
        plano: String?,
        r2: ResultadoLT?,
        fuentesBase: Fuentes,
        informe: StringBuilder,
    ): Letra? {
        val fuentes = fuentesBase.copy(urlCancionLT = r2?.urlCancion, urlTraduccionLT = r2?.urlTraduccion)
        var letra: Letra = when {
            lineasSinc != null && r2?.traduccion != null -> {
                val a = AlineadorDeLetras.alinear(lineasSinc, r2.original, r2.traduccion)
                Registro.info(Registro.ALINEADOR, "Cobertura ${"%.0f".format(a.cobertura * 100)}% (${a.emparejadas} de ${lineasSinc.size})")
                Letra(a.lineas, fuentes = fuentes.copy(cobertura = a.cobertura))
            }
            lineasSinc != null -> Letra(lineasSinc.map { LineaLetra(it.tiempoMs, it.texto) }, fuentes = fuentes)
            plano != null -> {
                val lineas = AnalizadorLrc.lineasDePlano(plano).map { LineaLetra(null, it) }
                val conTraduccion = if (r2?.traduccion != null) AlineadorDeLetras.alinearPlano(lineas.map { it.original }, r2.original, r2.traduccion).lineas else lineas
                sinTiempos(conTraduccion, cancion, fuentes)
            }
            r2 != null -> {
                // Solo lyricstranslate: la traducción va 1:1 con el original por posición.
                val lineas = ArrayList<LineaLetra>()
                var parrafo = -1
                for (l in r2.original) {
                    if (parrafo != -1 && l.posicion.parrafo != parrafo) lineas.add(LineaLetra(null, ""))
                    parrafo = l.posicion.parrafo
                    lineas.add(LineaLetra(null, l.texto, r2.traduccion?.get(l.posicion)))
                }
                sinTiempos(lineas, cancion, fuentes.copy(cobertura = if (r2.traduccion != null) 1f else 0f))
            }
            else -> return null
        }
        if (letra.lineas.isEmpty()) return null

        // Idioma: lyricstranslate lo sabe; si no, heurística.
        val idioma = DetectorDeIdioma.codigoDesdeNombre(r2?.idiomaCancion) ?: DetectorDeIdioma.detectar(letra.lineas.map { it.original })
        letra = letra.copy(idiomaOriginal = idioma)
        informe.append("Idioma: ${idioma ?: "desconocido"}\n")
        if (idioma == "es") return letra.copy(lineas = letra.lineas.map { it.copy(traducida = null) })

        // Último recurso: traducción automática en el teléfono.
        if (!letra.tieneTraduccion && mlKit != null && ajustes.actual.usarMlKit) {
            val textos = letra.lineas.map { it.original }
            val traducidas = mlKit.traducir(textos, idioma)
            if (traducidas != null && traducidas.size == textos.size) {
                letra = letra.copy(
                    lineas = letra.lineas.mapIndexed { i, l -> if (l.original.isBlank()) l else l.copy(traducida = traducidas[i].ifBlank { null }, traduccionAutomatica = true) },
                    fuentes = letra.fuentes.copy(mlKit = true, cobertura = 1f),
                )
                informe.append("ML Kit: traducción automática\n")
            } else informe.append("ML Kit: no disponible\n")
        }
        return letra
    }

    /**
     * Letra sin tiempos reales. Por defecto se deja sin tiempos (la ventana avisa y se puede leer entera);
     * repartirla a lo largo de la canción solo si el usuario lo activó, porque casi nunca cuadra.
     */
    private fun sinTiempos(lineas: List<LineaLetra>, cancion: CancionDetectada, fuentes: Fuentes): Letra =
        if (ajustes.actual.repartirSinTiempos) Letra(EstimadorDeTiempos.estimar(lineas, cancion.duracionMs), aproximada = true, fuentes = fuentes)
        else Letra(lineas.filter { it.original.isNotBlank() }, fuentes = fuentes)

    private fun registrarFallo(fuente: String, e: Throwable) {
        if (e is IOException) throw e
        Registro.error(Registro.APP, "Fallo en $fuente", e)
    }
}
