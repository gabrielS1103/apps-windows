package com.gabriel.letrasenvivo.deteccion

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.gabriel.letrasenvivo.Registro
import com.gabriel.letrasenvivo.ajustes.AppsObjetivo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Vigila las sesiones de medios activas, elige la del reproductor configurado y publica
 * la canción en curso y el estado de reproducción.
 */
class MonitorDeSesiones(private val context: Context, private val appsObjetivo: () -> AppsObjetivo) {

    private val gestor = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val manejador = Handler(Looper.getMainLooper())
    private val componente = ServicioEscuchaNotificaciones.componente(context)

    private val _cancion = MutableStateFlow<CancionDetectada?>(null)
    val cancion: StateFlow<CancionDetectada?> = _cancion

    private val _reproduccion = MutableStateFlow(EstadoReproduccion.vacio)
    val reproduccion: StateFlow<EstadoReproduccion> = _reproduccion

    private val _haySesion = MutableStateFlow(false)
    val haySesion: StateFlow<Boolean> = _haySesion

    private val _sinPermiso = MutableStateFlow(false)
    val sinPermiso: StateFlow<Boolean> = _sinPermiso

    /** Último metadato crudo, para la pantalla de depuración. */
    @Volatile var metadatosCrudos: String = ""
        private set

    private var controlador: MediaController? = null
    private var pendiente: Runnable? = null
    private var candidata: CancionDetectada? = null

    private val oyenteSesiones = MediaSessionManager.OnActiveSessionsChangedListener { lista -> elegir(lista.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { procesarMetadatos(metadata) }
        override fun onPlaybackStateChanged(state: PlaybackState?) { _reproduccion.value = EstadoReproduccion.desde(state) }
        override fun onSessionDestroyed() {
            Registro.info(Registro.SESION, "Sesión destruida: ${controlador?.packageName}")
            soltar()
            refrescar()
        }
    }

    fun iniciar() {
        try {
            gestor.addOnActiveSessionsChangedListener(oyenteSesiones, componente, manejador)
            _sinPermiso.value = false
            refrescar()
        } catch (e: SecurityException) {
            Registro.aviso(Registro.SESION, "Sin acceso a notificaciones: no se pueden leer las sesiones")
            _sinPermiso.value = true
        }
    }

    fun detener() {
        try { gestor.removeOnActiveSessionsChangedListener(oyenteSesiones) } catch (_: Exception) {}
        soltar()
        pendiente?.let { manejador.removeCallbacks(it) }
        _cancion.value = null
        _haySesion.value = false
    }

    /** Vuelve a consultar las sesiones (p. ej. tras cambiar el reproductor objetivo en ajustes). */
    fun refrescar() {
        try {
            elegir(gestor.getActiveSessions(componente))
            _sinPermiso.value = false
        } catch (e: SecurityException) {
            _sinPermiso.value = true
        }
    }

    private fun elegir(lista: List<MediaController>) {
        val permitidas = lista.filter { paqueteAdmitido(it.packageName) }
        val elegida = permitidas.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: permitidas.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: permitidas.firstOrNull()
        _haySesion.value = elegida != null
        if (elegida == null) {
            if (controlador != null) Registro.info(Registro.SESION, "Sin sesiones admitidas (${lista.size} activas)")
            soltar()
            return
        }
        if (controlador?.sessionToken == elegida.sessionToken) return
        soltar()
        controlador = elegida
        elegida.registerCallback(callback, manejador)
        Registro.info(Registro.SESION, "Sesión elegida: ${elegida.packageName}")
        _reproduccion.value = EstadoReproduccion.desde(elegida.playbackState)
        procesarMetadatos(elegida.metadata)
    }

    private fun soltar() {
        controlador?.let { try { it.unregisterCallback(callback) } catch (_: Exception) {} }
        controlador = null
        _reproduccion.value = EstadoReproduccion.vacio
    }

    private fun paqueteAdmitido(paquete: String): Boolean = when (appsObjetivo()) {
        AppsObjetivo.YTMUSIC -> CancionDetectada.esYtMusic(paquete)
        AppsObjetivo.YOUTUBE -> CancionDetectada.esYtMusic(paquete) || CancionDetectada.esYouTube(paquete)
        AppsObjetivo.CUALQUIERA -> true
    }

    private fun procesarMetadatos(md: MediaMetadata?) {
        val paquete = controlador?.packageName ?: return
        if (md == null) return
        val tituloCrudo = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artistaCrudo = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null }
        val duracion = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        metadatosCrudos = "título=\"$tituloCrudo\" artista=\"$artistaCrudo\" álbum=\"$album\" duración=${duracion}ms paquete=$paquete"
        if (tituloCrudo.isEmpty()) return

        // En YouTube, o cuando el "artista" es un canal o sello, el artista real va en el título: "Artista - Canción".
        val (artista, titulo) = if (CancionDetectada.esYouTube(paquete) || CancionDetectada.esCanal(artistaCrudo)) {
            CancionDetectada.separarArtistaYTitulo(tituloCrudo, artistaCrudo)
        } else {
            artistaCrudo to CancionDetectada.limpiarTitulo(CancionDetectada.quitarArtistaDelTitulo(tituloCrudo, artistaCrudo))
        }
        val esVideo = CancionDetectada.esYouTube(paquete) || CancionDetectada.pareceVideo(tituloCrudo)
        val nueva = CancionDetectada(titulo, artista, album, duracion, paquete, esVideo)

        // Anuncios de YouTube Music: muy cortos o sin artista.
        if (CancionDetectada.esYtMusic(paquete) && duracion in 1..40_000) {
            Registro.info(Registro.SESION, "Ignorado (probable anuncio): $nueva")
            return
        }

        // Debounce: YouTube Music manda varios metadatos seguidos (primero sin duración, luego con carátula…).
        candidata = nueva
        pendiente?.let { manejador.removeCallbacks(it) }
        val r = Runnable {
            val c = candidata ?: return@Runnable
            val actual = _cancion.value
            if (actual != null && actual.clave == c.clave && actual.paquete == c.paquete) {
                if (actual.duracionMs != c.duracionMs && c.duracionMs > 0) _cancion.value = c // solo refresca la duración
                return@Runnable
            }
            Registro.info(Registro.SESION, "Canción: $c")
            _cancion.value = c
        }
        pendiente = r
        // Sin artista todavía: YouTube Music suele mandarlo en una segunda tanda; se espera más.
        manejador.postDelayed(r, if (artistaCrudo.isEmpty()) 2500L else 700L)
    }
}
