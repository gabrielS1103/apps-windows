package com.gabriel.letrasenvivo.superposicion

import com.gabriel.letrasenvivo.deteccion.CancionDetectada
import com.gabriel.letrasenvivo.letras.modelos.Letra
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartido entre el servicio, el botón del panel y las pantallas de la app. */
object EstadoSuperposicion {
    /** true mientras el servicio está encendido. */
    val activa = MutableStateFlow(false)
    val cancion = MutableStateFlow<CancionDetectada?>(null)
    val letra = MutableStateFlow<Letra?>(null)
    /** Texto de estado cuando no hay letra que mostrar ("Buscando letra…", "Sin conexión"…). */
    val mensaje = MutableStateFlow<String?>(null)
    val indiceLinea = MutableStateFlow(-1)
    /** Desfase manual de la canción actual (se suma al general). */
    val desfaseCancionMs = MutableStateFlow(0L)
    /** Metadatos crudos de la sesión, para depuración. */
    @Volatile var metadatosCrudos: String = ""
}
