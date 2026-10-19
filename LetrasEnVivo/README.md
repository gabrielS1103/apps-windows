# LetrasEnVivo

**Android 8 o superior** (probado en Android 15) · Última versión: [**v1.0**](https://github.com/gabrielS1103/mis-apps/releases/tag/letrasenvivo-v1.0)

**[Descargar LetrasEnVivo.apk](https://github.com/gabrielS1103/mis-apps/releases/download/letrasenvivo-v1.0/LetrasEnVivo.apk)** · [Todas sus versiones](https://github.com/gabrielS1103/mis-apps/releases?q=LetrasEnVivo&expanded=true) · [Volver a todas las apps](../)

Muestra sobre YouTube Music (o cualquier reproductor) una ventana flotante con la letra de la canción que suena, sincronizada con la reproducción, como unos subtítulos en vivo. Las letras no se transcriben del audio: se buscan en internet.

- **Letras sincronizadas** de LRCLIB, NetEase y Kugou, sin clave ni cuenta. Se elige la grabación con la misma duración y el mismo artista que la que suena.
- **Vídeos musicales:** detecta cuándo suena el vídeo en vez de la pista de audio y retrasa la letra lo que dura la intro del vídeo.
- **Traducción al español hecha por personas**, desde lyricstranslate.com, alineada línea a línea con la letra original. Si no hay traducción humana, puede traducir en el teléfono con ML Kit como último recurso; se marca como automática y se puede desactivar.
- **Modos:** original + español, solo español o solo original. Tamaño de letra y opacidad ajustables.
- **Botón en el panel de ajustes rápidos**, junto a Wi‑Fi y Bluetooth, para encender o apagar la ventana con un toque.
- Toca la ventana para ver la letra completa tipo karaoke; arrástrala para moverla. Si algo va desfasado, toca en la lista la línea que estás oyendo y queda sincronizada para esa canción. También hay botones de −0,5 s / +0,5 s.
- Si ninguna fuente tiene la letra con tiempos, la ventana lo avisa en vez de inventarlos, y puedes leer la letra entera.
- Guarda cada canción en caché: la segunda vez es instantánea y funciona sin conexión. Una pantalla de depuración muestra qué fuentes se usaron y cómo quedó la alineación.

## Instalación
1. Descarga `LetrasEnVivo.apk` en el teléfono, ábrelo y permite instalar apps de origen desconocido.
2. Al abrir la app, concede el **acceso a notificaciones** (para saber qué canción suena y en qué segundo va) y el permiso para **mostrar sobre otras apps**.
3. Pulsa **«Añadir al panel»** para tener el botón en los ajustes rápidos.

No usa el micrófono ni graba nada. Es una compilación de depuración, sin firmar para Play Store.

## Compilar
Abre esta carpeta en Android Studio y usa *Build > Build APK*, o ejecuta `gradlew.bat assembleDebug` aquí. Necesita el SDK de Android y un JDK 17; Android Studio los instala. El APK queda en `app/build/outputs/apk/debug/`. Para instalarlo usa `adb install` (vale el adb que descarga ScrollRemoto) o cópialo al teléfono.

## Versiones
| Versión | Novedades |
|---|---|
| [v1.0](https://github.com/gabrielS1103/mis-apps/releases/tag/letrasenvivo-v1.0) | Primera versión |
