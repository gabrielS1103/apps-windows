# ModoJuegoWifi

**Windows 10/11** · Última versión: [**v1.0**](https://github.com/gabrielS1103/mis-apps/releases/tag/modojuegowifi-v1.0)

**[Descargar ModoJuegoWifi.exe](https://github.com/gabrielS1103/mis-apps/releases/download/modojuegowifi-v1.0/ModoJuegoWifi.exe)** · [Todas sus versiones](https://github.com/gabrielS1103/mis-apps/releases?q=ModoJuegoWifi&expanded=true) · [Volver a todas las apps](../)

Activa o desactiva el "modo juego" del Wi‑Fi cada vez que se abre. Apaga la búsqueda de redes en segundo plano de Windows, que provoca picos de ping mientras juegas.

## Cómo se usa
- Ábrelo una vez: **modo juego activado**. Ábrelo otra vez: **desactivado**. Cada vez muestra en qué estado quedó.
- Mientras está activado, si el Wi‑Fi se cae no se reconecta solo, ni siquiera al reiniciar. Si te pasa, ábrelo otra vez.
- Pide permisos de administrador. Usa la API de Wi‑Fi de Windows directamente, sin `cmd` ni `netsh`, y solo carga librerías de `System32`.

## Compilar
Doble clic en `compilar.bat`: genera `ModoJuegoWifi.exe` con el `csc.exe` que trae Windows y le incluye el manifiesto de administrador.

## Versiones
| Versión | Novedades |
|---|---|
| [v1.0](https://github.com/gabrielS1103/mis-apps/releases/tag/modojuegowifi-v1.0) | Primera versión |
