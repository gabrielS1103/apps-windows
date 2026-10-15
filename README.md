# Apps para Windows

Pequeñas utilidades para Windows 10/11 escritas en C# (WinForms). Cada una es un solo `.exe` sin instalación ni dependencias: usan el .NET Framework 4 que ya trae Windows.

Los `.exe` listos para usar están en la sección **[Releases](../../releases)**.

## Las apps

### MonitorPing
Monitor de conexión para jugar en la nube (GeForce NOW y Xbox Cloud) con un widget flotante y un icono en la barra de tareas.
- **Antes de jugar:** mide el ping a las zonas de GeForce NOW y a las regiones de Azure donde corre xCloud, elige la mejor y dice si es buen momento para jugar.
- **Jugando:** lee los encabezados del stream WebRTC/RTP para mostrar la pérdida real de paquetes, los cortes, los Mbps y el ping dentro del stream.
- **Diagnóstico:** compara con un servidor en EE.UU. y con el router para saber si el problema es del servidor, de tu internet o de tu Wi‑Fi. Avisa si hay una VPN activa.
- **Prueba de pérdida de paquetes:** prueba varios servidores a la vez (router, Cloudflare, Google, EE.UU., zonas de GFN, regiones de xCloud o cualquier host) durante 30 s a 30 min, a 1–10 paquetes por segundo. Muestra pérdida, ping mín/prom/máx, jitter y rachas de pérdidas, concluye si el problema es tu red, tu proveedor o un servidor, y copia los resultados.
- **Ratón y PC:** detecta tirones del ratón y pausas del sistema, e incluye una prueba de ratón en primer plano para ver su frecuencia real.
- **Widget tipo monitor:** el ratón lo atraviesa, tiene transparencia ajustable y modo compacto, y queda por debajo de los menús del sistema.

Necesita permisos de administrador para leer el tráfico del stream (solo cabeceras, nunca el contenido).

### ModoJuegoWifi
Activa o desactiva el "modo juego" del Wi‑Fi cada vez que se abre: apaga la búsqueda de redes en segundo plano de Windows, que provoca picos de ping. Usa la API de Wi‑Fi de Windows directamente y pide permisos de administrador.

### MantenerPantalla
Evita que la pantalla se apague o que el equipo se suspenda moviendo el ratón de forma imperceptible. Se controla desde la barra de tareas o con un atajo de teclado.

### ScrollRemoto
Controla un teléfono Android por Wi‑Fi con la depuración inalámbrica (adb): vincula el teléfono con un código QR y pasa los videos con atajos de teclado o con los botones laterales del ratón. La primera vez descarga las Android Platform-Tools oficiales de Google.

## Compilar
Cada carpeta tiene un `compilar.bat`: haz doble clic y genera el `.exe` con el compilador `csc.exe` que incluye Windows (`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319`). No hace falta instalar Visual Studio.
