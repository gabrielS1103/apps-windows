# Apps para Windows y Android

Pequeñas utilidades para Windows 10/11 escritas en C# (WinForms) y una app para Android (Kotlin). Las de Windows son un solo `.exe` sin instalación ni dependencias: usan el .NET Framework 4 que ya trae Windows.

Los `.exe` y el `.apk` listos para usar están en la sección **[Releases](../../releases)**.

## Las apps

### MonitorPing
Monitor de conexión para jugar en la nube (GeForce NOW y Xbox Cloud) con un widget flotante y un icono en la barra de tareas.
- **Antes de jugar:** mide el ping a las zonas de GeForce NOW y a las regiones de Azure donde corre xCloud, elige la mejor y dice si es buen momento para jugar.
- **Jugando:** lee los encabezados del stream WebRTC/RTP para mostrar la pérdida real de paquetes, los cortes, los Mbps y el ping dentro del stream.
- **Diagnóstico:** compara con un servidor en EE.UU. y con el router para saber si el problema es del servidor, de tu internet o de tu Wi‑Fi. Avisa si hay una VPN activa.
- **Prueba de pérdida de paquetes:** prueba varios servidores a la vez (router, Cloudflare, Google, EE.UU., zonas de GFN, regiones de xCloud o cualquier host) durante 30 s a 30 min, a 1–10 paquetes por segundo. Muestra pérdida, ping mín/prom/máx, jitter y rachas de pérdidas, concluye si el problema es tu red, tu proveedor o un servidor, y copia los resultados.
- **Ratón y PC:** detecta tirones del ratón y pausas del sistema, e incluye una prueba de ratón en primer plano para ver su frecuencia real.
- **Widget tipo monitor:** el ratón lo atraviesa, tiene transparencia ajustable y modo compacto, y queda por debajo de los menús del sistema.
- **Temas:** Clásico, Aurora (azul marino con resplandores y degradados iridiscentes), Cristal claro y Cristal violeta (cristal esmerilado sobre degradados pastel). Cambian el widget, el menú, el icono y todas las ventanas, con fondo animado opcional.

Necesita permisos de administrador para leer el tráfico del stream (solo cabeceras, nunca el contenido).

### ModoJuegoWifi
Activa o desactiva el "modo juego" del Wi‑Fi cada vez que se abre: apaga la búsqueda de redes en segundo plano de Windows, que provoca picos de ping. Usa la API de Wi‑Fi de Windows directamente y pide permisos de administrador.

### MantenerPantalla
Evita que la pantalla se apague o que el equipo se suspenda moviendo el ratón de forma imperceptible. Se controla desde la barra de tareas o con un atajo de teclado.

### ScrollRemoto
Controla un teléfono Android por Wi‑Fi con la depuración inalámbrica (adb): vincula el teléfono con un código QR y pasa los videos con atajos de teclado o con los botones laterales del ratón. La primera vez descarga las Android Platform-Tools oficiales de Google.

### LetrasEnVivo (Android)
Muestra sobre YouTube Music (o cualquier reproductor) una ventana flotante con la letra de la canción que suena, sincronizada con la reproducción, como los subtítulos en vivo. Las letras no se transcriben del audio: se buscan en internet.
- **Letras sincronizadas** de LRCLIB, NetEase y Kugou (sin clave ni cuenta). Se elige la grabación con la misma duración y el mismo artista que la que suena.
- **Vídeos musicales:** detecta cuándo suena el vídeo en vez de la pista de audio y retrasa la letra lo que dura la intro del vídeo.
- **Traducción al español hecha por personas** desde lyricstranslate.com, alineada línea a línea con la letra original. Si no hay traducción humana, puede traducir en el teléfono con ML Kit como último recurso (se marca como automática; se puede desactivar).
- **Modos:** original + español, solo español o solo original; tamaño de letra y opacidad ajustables.
- **Botón en el panel de ajustes rápidos** (el que baja desde arriba, junto a Wi‑Fi y Bluetooth) para encender o apagar la ventana con un toque. La app lo añade al panel con un botón; también se puede arrastrar desde «editar panel».
- Toca la ventana para ver la letra completa tipo karaoke; arrástrala para moverla. Si algo va desfasado, toca en la lista la línea que estás oyendo y queda sincronizada para esa canción (también hay botones de −0,5 s / +0,5 s).
- Si ninguna fuente tiene la letra con tiempos, la ventana lo avisa en vez de inventarlos, y puedes leer la letra entera.
- Guarda en caché cada canción para que la segunda vez sea instantánea y funcione sin conexión. Una pantalla de depuración muestra qué fuentes se usaron y cómo quedó la alineación.

Necesita dos permisos que se conceden desde la propia app: **acceso a notificaciones** (para saber qué canción suena y en qué segundo va) y **mostrar sobre otras apps**. No usa el micrófono ni graba nada.

## Compilar
**Apps de Windows:** cada carpeta tiene un `compilar.bat`: haz doble clic y genera el `.exe` con el compilador `csc.exe` que incluye Windows (`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319`). No hace falta instalar Visual Studio.

**LetrasEnVivo (Android):** abre la carpeta `LetrasEnVivo` en Android Studio y usa *Build > Build APK*, o ejecuta `gradlew.bat assembleDebug` dentro de esa carpeta (necesita el SDK de Android y un JDK 17; Android Studio los instala). El APK queda en `app/build/outputs/apk/debug/`. Instálalo con `adb install` (vale el adb que descarga ScrollRemoto) o copiándolo al teléfono.
