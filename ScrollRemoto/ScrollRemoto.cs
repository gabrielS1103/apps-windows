using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace ScrollRemoto
{
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            bool nueva;
            using (Mutex m = new Mutex(true, "ScrollRemoto_UnicaInstancia", out nueva))
            {
                if (!nueva)
                {
                    MessageBox.Show("Scroll Remoto ya se está ejecutando (mira en la barra de iconos ocultos).",
                        "Scroll Remoto", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm(Array.IndexOf(args, "/min") >= 0));
            }
        }
    }

    static class Native
    {
        public const int WM_HOTKEY = 0x0312;
        public const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_NOREPEAT = 0x4000;
        public const int WH_MOUSE_LL = 14;
        public const int WM_MBUTTONDOWN = 0x0207, WM_MBUTTONUP = 0x0208, WM_MOUSEWHEEL = 0x020A,
                         WM_XBUTTONDOWN = 0x020B, WM_XBUTTONUP = 0x020C;
        public const int VK_CONTROL = 0x11;

        [StructLayout(LayoutKind.Sequential)]
        public struct MSLLHOOKSTRUCT { public int x, y; public uint mouseData, flags, time; public IntPtr dwExtraInfo; }

        public delegate IntPtr LowLevelMouseProc(int nCode, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll")]
        public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern IntPtr SetWindowsHookEx(int idHook, LowLevelMouseProc lpfn, IntPtr hMod, uint dwThreadId);

        [DllImport("user32.dll")]
        public static extern bool UnhookWindowsHookEx(IntPtr hhk);

        [DllImport("user32.dll")]
        public static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        public static extern IntPtr GetModuleHandle(string lpModuleName);

        [DllImport("user32.dll")]
        public static extern short GetAsyncKeyState(int vKey);

        [DllImport("user32.dll")]
        public static extern bool DestroyIcon(IntPtr handle);

        [DllImport("iphlpapi.dll")]
        public static extern int GetBestInterface(uint dwDestAddr, out uint pdwBestIfIndex);
    }

    // Explica por qué no se llega al teléfono. Con el teléfono en la misma red local que la PC, Windows manda
    // ese tráfico por la Wi-Fi aunque haya una VPN activa (la ruta local es más específica que las de la VPN).
    // Si el teléfono está en otra red, el tráfico se va por la VPN (o por el router) y no llega.
    static class Red
    {
        // ¿Se llega a esta IP:puerto por la red local (IPv4 de la misma subred, o el puente de 127.0.0.1)?
        public static bool EnMiRed(string direccion)
        {
            int dp = direccion.LastIndexOf(':');
            IPAddress ip;
            if (dp <= 0 || !IPAddress.TryParse(direccion.Substring(0, dp), out ip) || ip.AddressFamily != AddressFamily.InterNetwork)
                return true; // nombres o IPv6: que lo intente adb
            if (IPAddress.IsLoopback(ip)) return true;
            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || PareceVpn(ni)) continue;
                foreach (UnicastIPAddressInformation u in ni.GetIPProperties().UnicastAddresses)
                    if (u.Address.AddressFamily == AddressFamily.InterNetwork && u.IPv4Mask != null &&
                        !u.Address.ToString().StartsWith("169.254.") && MismaSubred(ip, u.Address, u.IPv4Mask))
                        return true;
            }
            return false;
        }

        public static string Diagnostico(string direccion, out string corto)
        {
            corto = null;
            int dp = direccion.LastIndexOf(':');
            IPAddress ip;
            if (dp <= 0 || !IPAddress.TryParse(direccion.Substring(0, dp), out ip) || ip.AddressFamily != AddressFamily.InterNetwork)
                return null;

            uint indiceRuta;
            bool hayRuta = Native.GetBestInterface(BitConverter.ToUInt32(ip.GetAddressBytes(), 0), out indiceRuta) == 0;
            bool mismaRed = false;
            string rutaVpn = null;
            List<string> mias = new List<string>(); // primero las de Wi-Fi
            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                IPInterfaceProperties props = ni.GetIPProperties();
                bool vpn = PareceVpn(ni);
                try
                {
                    if (hayRuta && vpn && props.GetIPv4Properties() != null && props.GetIPv4Properties().Index == indiceRuta) rutaVpn = ni.Name;
                }
                catch (NetworkInformationException) { }
                if (vpn) continue;
                foreach (UnicastIPAddressInformation u in props.UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask == null) continue;
                    if (u.Address.ToString().StartsWith("169.254.")) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211) mias.Insert(0, u.Address.ToString());
                    else mias.Add(u.Address.ToString());
                    if (MismaSubred(ip, u.Address, u.IPv4Mask)) mismaRed = true;
                }
            }

            if (!mismaRed && mias.Count > 0)
            {
                corto = "teléfono en otra red";
                string wifi = NombreWifi();
                string red = wifi != null ? "la Wi-Fi «" + wifi + "»" : "la misma Wi-Fi que la PC";
                string prefijo = mias[0].Substring(0, mias[0].LastIndexOf('.') + 1);
                return "El teléfono (" + ip + ") está en otra red que la PC (" + string.Join(", ", mias) + "). " +
                       "Conecta el teléfono a " + red + ": su IP tiene que empezar por " + prefijo +
                       " Así funciona aunque la VPN esté activada.";
            }
            if (rutaVpn != null)
            {
                corto = "la VPN bloquea la LAN";
                return "La VPN («" + rutaVpn + "») se está llevando también la conexión con la red local. " +
                       "Activa en la VPN la opción de permitir la red local (LAN).";
            }
            return null;
        }

        static string NombreWifi()
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo("netsh", "wlan show interfaces");
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.RedirectStandardOutput = true;
                psi.StandardOutputEncoding = Encoding.GetEncoding(System.Globalization.CultureInfo.CurrentCulture.TextInfo.OEMCodePage);
                using (Process p = Process.Start(psi))
                {
                    string salida = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(3000);
                    Match m = Regex.Match(salida, @"^\s*SSID\s*:\s*(.+?)\s*$", RegexOptions.Multiline);
                    return m.Success ? m.Groups[1].Value : null;
                }
            }
            catch { return null; }
        }

        public static bool PareceVpn(NetworkInterface ni)
        {
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Ppp) return true;
            string texto = (ni.Name + " " + ni.Description).ToLowerInvariant();
            if (Regex.IsMatch(texto, "teredo|isatap|6to4|ip-https")) return false;
            return ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel ||
                   Regex.IsMatch(texto, @"vpn|wintun|wireguard|openvpn|tap-windows|\btun\b|hotspot ?shield|nordlynx|proton|express|surfshark|cyberghost|zerotier|tailscale|hamachi");
        }

        static bool MismaSubred(IPAddress a, IPAddress b, IPAddress mascara)
        {
            byte[] x = a.GetAddressBytes(), y = b.GetAddressBytes(), m = mascara.GetAddressBytes();
            for (int i = 0; i < 4; i++)
                if ((x[i] & m[i]) != (y[i] & m[i])) return false;
            return true;
        }
    }

    // Búsqueda mDNS por IPv6 de enlace local (ff02::fb). Encuentra el teléfono aunque tenga una IPv4 de otro
    // router de la misma Wi-Fi, y aunque haya una VPN: las direcciones fe80:: solo existen en la Wi-Fi.
    static class Mdns6
    {
        public class Servicio
        {
            public string Nombre;      // p. ej. adb-R5CRA0K00YY-WOvvv2
            public IPEndPoint Destino; // [fe80::...%interfaz]:puerto
        }

        // tipo: "_adb-tls-connect._tcp" o "_adb-tls-pairing._tcp"
        public static List<Servicio> Buscar(string tipo, int esperaMs)
        {
            List<Servicio> res = new List<Servicio>();
            string tipoLocal = tipo + ".local";
            foreach (int idx in Interfaces())
            {
                Socket s = null;
                try
                {
                    s = new Socket(AddressFamily.InterNetworkV6, SocketType.Dgram, ProtocolType.Udp);
                    s.Bind(new IPEndPoint(IPAddress.IPv6Any, 0));
                    s.SetSocketOption(SocketOptionLevel.IPv6, SocketOptionName.MulticastInterface, idx);
                    s.SetSocketOption(SocketOptionLevel.IPv6, SocketOptionName.MulticastTimeToLive, 255);
                    IPAddress grupo = IPAddress.Parse("ff02::fb");
                    grupo.ScopeId = idx;
                    IPEndPoint destino = new IPEndPoint(grupo, 5353);
                    byte[] pregunta = Pregunta(tipoLocal);
                    s.SendTo(pregunta, destino);
                    int inicio = Environment.TickCount;
                    bool repetida = false;
                    byte[] buf = new byte[9000];
                    while (Environment.TickCount - inicio < esperaMs)
                    {
                        if (!repetida && Environment.TickCount - inicio > esperaMs / 2)
                        {
                            s.SendTo(pregunta, destino); // por si se perdió la primera
                            repetida = true;
                        }
                        if (!s.Poll(100000, SelectMode.SelectRead)) continue;
                        EndPoint de = new IPEndPoint(IPAddress.IPv6Any, 0);
                        int n = s.ReceiveFrom(buf, ref de);
                        Analizar(buf, n, tipoLocal, ((IPEndPoint)de).Address, res);
                    }
                }
                catch (Exception) { }
                finally { if (s != null) s.Close(); }
            }
            return res;
        }

        static List<int> Interfaces()
        {
            List<int> r = new List<int>();
            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || !ni.Supports(NetworkInterfaceComponent.IPv6) || Red.PareceVpn(ni)) continue;
                if (ni.NetworkInterfaceType != NetworkInterfaceType.Wireless80211 && ni.NetworkInterfaceType != NetworkInterfaceType.Ethernet &&
                    ni.NetworkInterfaceType != NetworkInterfaceType.GigabitEthernet) continue;
                try { r.Add(ni.GetIPProperties().GetIPv6Properties().Index); }
                catch (NetworkInformationException) { }
            }
            return r;
        }

        // Pregunta PTR por el tipo de servicio, pidiendo respuesta unicast (bit QU)
        static byte[] Pregunta(string nombre)
        {
            List<byte> b = new List<byte>(new byte[] { 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0 });
            foreach (string parte in nombre.Split('.'))
            {
                b.Add((byte)parte.Length);
                b.AddRange(Encoding.ASCII.GetBytes(parte));
            }
            b.Add(0);
            b.AddRange(new byte[] { 0, 12, 0x80, 1 });
            return b.ToArray();
        }

        // Saca de la respuesta los registros SRV "<nombre>.<tipo>" (puerto). La IP es la de quien responde.
        static void Analizar(byte[] b, int n, string tipo, IPAddress origen, List<Servicio> res)
        {
            if (n < 12) return;
            int preguntas = (b[4] << 8) | b[5];
            int registros = ((b[6] << 8) | b[7]) + ((b[8] << 8) | b[9]) + ((b[10] << 8) | b[11]);
            int pos = 12;
            for (int i = 0; i < preguntas && pos < n; i++)
            {
                LeerNombre(b, n, ref pos);
                pos += 4;
            }
            for (int i = 0; i < registros && pos + 10 <= n; i++)
            {
                string nombre = LeerNombre(b, n, ref pos);
                if (pos + 10 > n) break;
                int tipoRr = (b[pos] << 8) | b[pos + 1];
                int largo = (b[pos + 8] << 8) | b[pos + 9];
                int datos = pos + 10;
                if (datos + largo > n) break;
                if (tipoRr == 33 && largo >= 6 && nombre.EndsWith("." + tipo, StringComparison.OrdinalIgnoreCase))
                {
                    string etiqueta = nombre.Substring(0, nombre.Length - tipo.Length - 1);
                    int puerto = (b[datos + 4] << 8) | b[datos + 5];
                    res.RemoveAll(delegate(Servicio x) { return x.Nombre == etiqueta; });
                    Servicio sv = new Servicio();
                    sv.Nombre = etiqueta;
                    sv.Destino = new IPEndPoint(origen, puerto);
                    res.Add(sv);
                }
                pos = datos + largo;
            }
        }

        static string LeerNombre(byte[] b, int n, ref int pos)
        {
            StringBuilder sb = new StringBuilder();
            int p = pos, saltos = 0;
            bool salto = false;
            while (p < n)
            {
                int largo = b[p];
                if (largo == 0) { p++; break; }
                if ((largo & 0xC0) == 0xC0) // puntero de compresión
                {
                    if (p + 1 >= n || ++saltos > 20) break;
                    if (!salto) { pos = p + 2; salto = true; }
                    p = ((largo & 0x3F) << 8) | b[p + 1];
                    continue;
                }
                p++;
                if (p + largo > n) break;
                if (sb.Length > 0) sb.Append('.');
                sb.Append(Encoding.UTF8.GetString(b, p, largo));
                p += largo;
            }
            if (!salto) pos = p;
            return sb.ToString();
        }
    }

    // Puente TCP local: adb se conecta a 127.0.0.1:puerto y el programa reenvía al teléfono por una dirección
    // que adb no sabe usar (IPv6 de enlace local). Así la conexión no pasa por la VPN ni por los routers.
    class Puente : IDisposable
    {
        readonly TcpListener escucha;
        readonly List<Socket> abiertos = new List<Socket>();
        volatile bool cerrado;
        public readonly IPEndPoint Destino;
        public readonly int Puerto;
        public volatile bool LlegoAlTelefono; // alguna conexión se abrió de verdad con el teléfono

        public string Direccion { get { return "127.0.0.1:" + Puerto; } }

        public Puente(IPEndPoint destino)
        {
            Destino = destino;
            escucha = new TcpListener(IPAddress.Loopback, 0);
            escucha.Start();
            Puerto = ((IPEndPoint)escucha.LocalEndpoint).Port;
            Thread t = new Thread(Aceptar);
            t.IsBackground = true;
            t.Start();
        }

        void Aceptar()
        {
            while (!cerrado)
            {
                Socket cliente;
                try { cliente = escucha.AcceptSocket(); }
                catch { if (cerrado) return; continue; }
                Thread t = new Thread(delegate() { Atender(cliente); });
                t.IsBackground = true;
                t.Start();
            }
        }

        void Atender(Socket cliente)
        {
            Socket remoto = new Socket(Destino.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
            try
            {
                IAsyncResult r = remoto.BeginConnect(Destino, null, null);
                if (!r.AsyncWaitHandle.WaitOne(5000)) throw new TimeoutException();
                remoto.EndConnect(r);
                remoto.NoDelay = true;
                cliente.NoDelay = true;
            }
            catch
            {
                Cerrar(remoto);
                Cerrar(cliente);
                return;
            }
            LlegoAlTelefono = true;
            lock (abiertos) { abiertos.Add(cliente); abiertos.Add(remoto); }
            Thread ida = new Thread(delegate() { Copiar(cliente, remoto); });
            ida.IsBackground = true;
            ida.Start();
            Copiar(remoto, cliente);
        }

        void Copiar(Socket de, Socket a)
        {
            byte[] buf = new byte[16384];
            try
            {
                int n;
                while ((n = de.Receive(buf)) > 0)
                    for (int enviado = 0; enviado < n; ) enviado += a.Send(buf, enviado, n - enviado, SocketFlags.None);
            }
            catch { }
            // si un lado se cierra, se cierran los dos para que el otro hilo también termine
            Cerrar(a);
            Cerrar(de);
            lock (abiertos) { abiertos.Remove(a); abiertos.Remove(de); }
        }

        static void Cerrar(Socket s)
        {
            try { s.Shutdown(SocketShutdown.Both); } catch { }
            try { s.Close(); } catch { }
        }

        public void Dispose()
        {
            cerrado = true;
            try { escucha.Stop(); } catch { }
            lock (abiertos)
            {
                foreach (Socket s in abiertos) Cerrar(s);
                abiertos.Clear();
            }
        }
    }

    enum BotonRaton { Ninguno, LateralAtras, LateralAdelante, Central, CtrlRuedaArriba, CtrlRuedaAbajo }

    class Config
    {
        public Keys TeclaSiguiente = Keys.Control | Keys.Alt | Keys.Down;
        public Keys TeclaAnterior = Keys.Control | Keys.Alt | Keys.Up;
        public Keys TeclaPausa = Keys.Control | Keys.Alt | Keys.S;
        public BotonRaton RatonSiguiente = BotonRaton.LateralAdelante;
        public BotonRaton RatonAnterior = BotonRaton.LateralAtras;
        public int Distancia = 50;   // % de la altura de la pantalla
        public int Duracion = 120;   // ms
        public bool Invertir = false;
        public bool AutoConectar = true;
        public bool SiempreEncima = false;
        public string Direccion = ""; // última IP:puerto que funcionó
        public string Serial = "";    // último dispositivo usado

        public static readonly string[] NombresRaton = {
            "(ninguno)", "Botón lateral atrás", "Botón lateral adelante",
            "Clic de la rueda", "Ctrl + rueda arriba", "Ctrl + rueda abajo"
        };

        static readonly object cerrojo = new object();

        static string Ruta
        {
            get
            {
                string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ScrollRemoto");
                Directory.CreateDirectory(dir);
                return Path.Combine(dir, "config.ini");
            }
        }

        public static Config Cargar()
        {
            Config c = new Config();
            try
            {
                if (!File.Exists(Ruta)) return c;
                foreach (string linea in File.ReadAllLines(Ruta))
                {
                    int i = linea.IndexOf('=');
                    if (i < 0) continue;
                    string k = linea.Substring(0, i).Trim(), v = linea.Substring(i + 1).Trim();
                    try
                    {
                        if (k == "siguiente") c.TeclaSiguiente = (Keys)int.Parse(v);
                        else if (k == "anterior") c.TeclaAnterior = (Keys)int.Parse(v);
                        else if (k == "pausa") c.TeclaPausa = (Keys)int.Parse(v);
                        else if (k == "raton_siguiente") c.RatonSiguiente = Raton(v);
                        else if (k == "raton_anterior") c.RatonAnterior = Raton(v);
                        else if (k == "distancia") c.Distancia = Math.Min(90, Math.Max(20, int.Parse(v)));
                        else if (k == "duracion") c.Duracion = Math.Min(1000, Math.Max(30, int.Parse(v)));
                        else if (k == "invertir") c.Invertir = v == "1";
                        else if (k == "autoconectar") c.AutoConectar = v == "1";
                        else if (k == "encima") c.SiempreEncima = v == "1";
                        else if (k == "direccion") c.Direccion = v;
                        else if (k == "serial") c.Serial = v;
                    }
                    catch { }
                }
            }
            catch { }
            return c;
        }

        static BotonRaton Raton(string v)
        {
            int n = int.Parse(v);
            return n >= 0 && n < NombresRaton.Length ? (BotonRaton)n : BotonRaton.Ninguno;
        }

        public void Guardar()
        {
            lock (cerrojo)
            {
                try
                {
                    File.WriteAllLines(Ruta, new string[] {
                        "siguiente=" + (int)TeclaSiguiente,
                        "anterior=" + (int)TeclaAnterior,
                        "pausa=" + (int)TeclaPausa,
                        "raton_siguiente=" + (int)RatonSiguiente,
                        "raton_anterior=" + (int)RatonAnterior,
                        "distancia=" + Distancia,
                        "duracion=" + Duracion,
                        "invertir=" + (Invertir ? "1" : "0"),
                        "autoconectar=" + (AutoConectar ? "1" : "0"),
                        "encima=" + (SiempreEncima ? "1" : "0"),
                        "direccion=" + Direccion,
                        "serial=" + Serial
                    });
                }
                catch { }
            }
        }

        public static string TeclaTexto(Keys k)
        {
            Keys codigo = k & Keys.KeyCode;
            if (codigo == Keys.None) return "(ninguna)";
            string s = "";
            if ((k & Keys.Control) != 0) s += "Ctrl + ";
            if ((k & Keys.Alt) != 0) s += "Alt + ";
            if ((k & Keys.Shift) != 0) s += "Shift + ";
            switch (codigo)
            {
                case Keys.Up: return s + "↑";
                case Keys.Down: return s + "↓";
                case Keys.Left: return s + "←";
                case Keys.Right: return s + "→";
                case Keys.PageUp: return s + "RePág";
                case Keys.PageDown: return s + "AvPág";
                case Keys.Space: return s + "Espacio";
            }
            return s + codigo.ToString();
        }
    }

    // Localiza, descarga y ejecuta adb.exe (Android Platform-Tools)
    static class Adb
    {
        public const string UrlDescarga = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";
        public static volatile string Ruta;

        static string CarpetaApp { get { return Path.GetDirectoryName(Application.ExecutablePath); } }

        static string CarpetaLocal
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ScrollRemoto"); }
        }

        public static string Buscar()
        {
            List<string> candidatos = new List<string>();
            candidatos.Add(Path.Combine(CarpetaApp, @"platform-tools\adb.exe"));
            candidatos.Add(Path.Combine(CarpetaLocal, @"platform-tools\adb.exe"));
            foreach (string d in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            {
                try { if (d.Trim().Length > 0) candidatos.Add(Path.Combine(d.Trim().Trim('"'), "adb.exe")); }
                catch { }
            }
            candidatos.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                @"Android\Sdk\platform-tools\adb.exe"));
            foreach (string c in candidatos)
                if (File.Exists(c)) return Ruta = c;
            return Ruta = null;
        }

        // Descarga el zip oficial de Google y lo descomprime junto al programa (o en %LOCALAPPDATA% si no se puede escribir)
        public static void Descargar(Action<int> progreso)
        {
            ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072; // TLS 1.2
            string zip = Path.Combine(Path.GetTempPath(), "scrollremoto-platform-tools.zip");
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(UrlDescarga);
            req.UserAgent = "ScrollRemoto";
            using (WebResponse resp = req.GetResponse())
            using (Stream entrada = resp.GetResponseStream())
            using (FileStream salida = File.Create(zip))
            {
                long total = resp.ContentLength, leidos = 0;
                byte[] buf = new byte[81920];
                int n;
                while ((n = entrada.Read(buf, 0, buf.Length)) > 0)
                {
                    salida.Write(buf, 0, n);
                    leidos += n;
                    if (total > 0) progreso((int)(leidos * 100 / total));
                }
            }

            string raiz = Path.GetFullPath(CarpetaDestino()).TrimEnd('\\') + "\\";
            using (ZipArchive archivo = ZipFile.OpenRead(zip))
            {
                foreach (ZipArchiveEntry e in archivo.Entries)
                {
                    string ruta = Path.GetFullPath(Path.Combine(raiz, e.FullName));
                    if (!ruta.StartsWith(raiz, StringComparison.OrdinalIgnoreCase)) continue;
                    if (e.Name.Length == 0) { Directory.CreateDirectory(ruta); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(ruta));
                    e.ExtractToFile(ruta, true);
                }
            }
            try { File.Delete(zip); } catch { }
            if (Buscar() == null) throw new Exception("El archivo descargado no contiene adb.exe.");
        }

        static string CarpetaDestino()
        {
            try
            {
                string prueba = Path.Combine(CarpetaApp, ".prueba_escritura");
                File.WriteAllText(prueba, "");
                File.Delete(prueba);
                return CarpetaApp;
            }
            catch
            {
                Directory.CreateDirectory(CarpetaLocal);
                return CarpetaLocal;
            }
        }

        public static string Comillas(string s) { return "\"" + s + "\""; }

        public static string Ejecutar(string args, int esperaMs)
        {
            string ruta = Ruta;
            if (ruta == null) return "";
            ProcessStartInfo psi = new ProcessStartInfo(ruta, args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            psi.StandardOutputEncoding = Encoding.UTF8;
            psi.StandardErrorEncoding = Encoding.UTF8;

            StringBuilder sb = new StringBuilder();
            ManualResetEvent finOut = new ManualResetEvent(false), finErr = new ManualResetEvent(false);
            Process p = new Process();
            p.StartInfo = psi;
            p.OutputDataReceived += delegate(object s, DataReceivedEventArgs e) { if (e.Data == null) finOut.Set(); else lock (sb) sb.AppendLine(e.Data); };
            p.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e) { if (e.Data == null) finErr.Set(); else lock (sb) sb.AppendLine(e.Data); };
            try
            {
                p.Start();
            }
            catch (Exception ex)
            {
                return "No se pudo ejecutar adb: " + ex.Message;
            }
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            if (!p.WaitForExit(esperaMs))
            {
                try { p.Kill(); } catch { }
                lock (sb) sb.AppendLine("(adb no respondió a tiempo)");
            }
            // El servidor de adb que se arranca la primera vez puede heredar las tuberías: no esperar indefinidamente
            bool cerradas = finOut.WaitOne(1500) & finErr.WaitOne(500);
            if (cerradas) p.Dispose();
            lock (sb) return sb.ToString().Trim();
        }

        // serial -> estado ("device", "offline", "unauthorized"...)
        public static Dictionary<string, string> Estados()
        {
            Dictionary<string, string> estados = new Dictionary<string, string>();
            foreach (string linea in Ejecutar("devices", 8000).Split('\n'))
            {
                string[] partes = linea.Trim().Split(new char[] { '\t', ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (partes.Length >= 2 && linea.IndexOf("List of devices", StringComparison.Ordinal) < 0) estados[partes[0]] = partes[1];
            }
            return estados;
        }

        public static List<string> Dispositivos()
        {
            List<string> lista = new List<string>();
            foreach (KeyValuePair<string, string> d in Estados())
                if (d.Value == "device") lista.Add(d.Key);
            return lista;
        }

        // Teléfonos con depuración inalámbrica anunciados en la red (mDNS)
        public static List<string> ServiciosMdns()
        {
            List<string> lista = new List<string>();
            foreach (Match m in Regex.Matches(Ejecutar("mdns services", 8000), @"_adb-tls-connect\._tcp\.?\s+(\S+:\d+)"))
                if (!lista.Contains(m.Groups[1].Value)) lista.Add(m.Groups[1].Value);
            return lista;
        }

        // Dirección que anuncia el teléfono al escanear nuestro QR (servicio _adb-tls-pairing con nuestro nombre)
        public static string ServicioVinculacion(string nombre)
        {
            foreach (string linea in Ejecutar("mdns services", 5000).Split('\n'))
            {
                if (linea.IndexOf(nombre, StringComparison.Ordinal) < 0) continue;
                Match m = Regex.Match(linea, @"_adb-tls-pairing\._tcp\.?\s+(\S+:\d+)");
                if (m.Success) return m.Groups[1].Value;
            }
            return null;
        }

        public static Size TamanoPantalla(string serial)
        {
            string o = Ejecutar("-s " + Comillas(serial) + " shell wm size", 8000);
            Match m = Regex.Match(o, @"Override size:\s*(\d+)x(\d+)");
            if (!m.Success) m = Regex.Match(o, @"Physical size:\s*(\d+)x(\d+)");
            if (!m.Success) return Size.Empty;
            return new Size(int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value));
        }
    }

    // Sesión "adb shell" abierta todo el tiempo: cada gesto es solo una línea, sin arrancar adb cada vez
    class ShellPersistente : IDisposable
    {
        readonly Process p;

        public ShellPersistente(string serial)
        {
            ProcessStartInfo psi = new ProcessStartInfo(Adb.Ruta, "-s " + Adb.Comillas(serial) + " shell");
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardInput = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            p = Process.Start(psi);
            p.OutputDataReceived += delegate { };
            p.ErrorDataReceived += delegate { };
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            p.StandardInput.NewLine = "\n"; // el shell de Android no entiende \r\n
            p.StandardInput.AutoFlush = true;
        }

        public bool Viva { get { try { return !p.HasExited; } catch { return false; } } }

        public bool Enviar(string comando)
        {
            try
            {
                if (p.HasExited) return false;
                p.StandardInput.WriteLine(comando);
                return true;
            }
            catch { return false; }
        }

        public void Dispose()
        {
            try
            {
                if (!p.HasExited)
                {
                    p.StandardInput.WriteLine("exit");
                    if (!p.WaitForExit(1000)) p.Kill();
                }
            }
            catch { }
            try { p.Dispose(); } catch { }
        }
    }

    // Todo lo que habla con el teléfono se ejecuta en orden en un hilo propio para no congelar la ventana
    class Telefono : IDisposable
    {
        readonly Config cfg;
        readonly BlockingCollection<Action> cola = new BlockingCollection<Action>();
        ShellPersistente shell;
        int ancho, alto;
        bool servidorIniciado, silencioso;
        volatile bool trabajando;
        string ultimoEstado;
        string pista, pistaCorta; // explicación del último fallo de red (otra Wi-Fi, VPN...)
        readonly Dictionary<string, Puente> puentes = new Dictionary<string, Puente>(); // por nombre de servicio mDNS

        public event Action<bool, string> EstadoCambiado;
        public event Action<string> Registro;
        public event Action<string> Problema; // no se llega al teléfono por la red (VPN, otra Wi-Fi...)
        public bool Conectado { get; private set; }

        public Telefono(Config cfg)
        {
            this.cfg = cfg;
            Thread hilo = new Thread(Bucle);
            hilo.IsBackground = true;
            hilo.Name = "adb";
            hilo.Start();
        }

        void Bucle()
        {
            foreach (Action a in cola.GetConsumingEnumerable())
            {
                trabajando = true;
                try { a(); }
                catch (Exception ex) { Log("Error: " + ex.Message); }
                trabajando = false;
            }
        }

        void Encolar(Action a)
        {
            try { cola.Add(a); } catch (InvalidOperationException) { }
        }

        // ---- Acciones públicas ----

        public void Deslizar(bool siguiente)
        {
            if (cola.Count >= 2) return; // no acumular gestos si el teléfono va lento
            Encolar(delegate { HacerDeslizar(siguiente); });
        }

        public void ConectarAutomatico() { Encolar(delegate { ConectarAuto(); }); }

        public void Buscar()
        {
            Encolar(delegate
            {
                if (!AdbListo()) return;
                if (!ConectarAuto())
                    Log("No se encontró ningún teléfono. Comprueba que la Depuración inalámbrica está activa y que el teléfono y la PC están en la misma Wi-Fi.");
            });
        }

        public void Vincular(string dir, string codigo)
        {
            Encolar(delegate
            {
                if (!AdbListo()) return;
                Log("Vinculando con " + dir + "...");
                string ruta = Alcanzable(dir, "_adb-tls-pairing._tcp");
                string r = Adb.Ejecutar("pair " + ruta + " " + codigo, 20000);
                Log(r);
                if (r.IndexOf("Successfully paired", StringComparison.OrdinalIgnoreCase) < 0)
                {
                    if (ruta != dir || ExplicarFallo(dir) == null)
                        Log("No se pudo vincular. Revisa la IP:puerto y el código (cambian cada vez que abres esa ventana en el teléfono).");
                    return;
                }
                Log("¡Vinculado! Buscando el teléfono para conectar...");
                Thread.Sleep(2000);
                if (!ConectarAuto())
                    Log("Ahora completa el Paso 2 con la IP y el puerto de la pantalla principal de Depuración inalámbrica.");
            });
        }

        // Espera a que el teléfono escanee el QR (se anuncia en la red con nuestro nombre), vincula y conecta.
        // avisar(texto, terminado, exito) se llama desde el hilo de adb.
        public void VincularQr(string nombre, string clave, SesionQr sesion, Action<string, bool, bool> avisar)
        {
            Encolar(delegate
            {
                if (sesion.Cancelada) return;
                if (!AdbListo())
                {
                    avisar("Falta ADB.", true, false);
                    return;
                }
                Log("Código QR listo (" + nombre + "). Esperando a que el teléfono lo escanee...");
                int inicio = Environment.TickCount, vueltas = 0;
                bool ayudaMostrada = false;
                string dir = null;
                while (dir == null)
                {
                    if (sesion.Cancelada) return;
                    int pasado = Environment.TickCount - inicio;
                    if (pasado > 180000)
                    {
                        avisar("Se acabó el tiempo. Pulsa «Generar otro» para intentarlo de nuevo.", true, false);
                        return;
                    }
                    if (pasado > 40000 && !ayudaMostrada)
                    {
                        ayudaMostrada = true;
                        avisar("¿Ya lo escaneaste y no pasa nada? Revisa que estén en la misma Wi-Fi, " +
                               "o usa el código de 6 números en la ventana anterior.", false, false);
                    }
                    // Tras varias vueltas sin verlo por IPv6, se prueba también la IPv4 aunque sea de otra red
                    dir = DireccionVinculacion(nombre, ++vueltas > 8);
                    if (dir == null) Thread.Sleep(300);
                }
                avisar("Teléfono encontrado. Vinculando...", false, false);
                Log("Vinculando con " + dir + "...");
                string r = Adb.Ejecutar("pair " + dir + " " + clave, 20000);
                Log(r);
                QuitarPuente(nombre);
                if (r.IndexOf("Successfully paired", StringComparison.OrdinalIgnoreCase) < 0)
                {
                    string p = dir.StartsWith("127.0.0.1:") ? null : ExplicarFallo(dir);
                    avisar("No se pudo vincular. " + (p ?? "Pulsa «Generar otro» e inténtalo de nuevo."), true, false);
                    return;
                }
                avisar("¡Vinculado! Conectando...", false, false);
                Thread.Sleep(1500);
                bool ok = ConectarAuto();
                for (int i = 0; i < 3 && !ok && !sesion.Cancelada; i++)
                {
                    Thread.Sleep(2000);
                    ok = ConectarAuto();
                }
                if (ok) avisar("¡Listo! Teléfono conectado.", true, true);
                else avisar("Vinculado, pero aún no se pudo conectar. " + (pista ?? "Usa «Buscar solo» o el Paso 2."), true, false);
            });
        }

        public void ConectarA(string dir)
        {
            Encolar(delegate
            {
                if (!AdbListo()) return;
                Estado(false, "Conectando...");
                pista = pistaCorta = null;
                string ruta = Alcanzable(dir, "_adb-tls-connect._tcp");
                if (ConectarDireccion(ruta, ruta == dir) && Usar(ruta)) return;
                Estado(false, TextoDesconectado());
            });
        }

        public void DescargarAdb(Action<Exception> alTerminar)
        {
            Encolar(delegate
            {
                Exception error = null;
                try
                {
                    Log("Descargando ADB desde " + Adb.UrlDescarga);
                    Estado(false, "Descargando ADB... 0%");
                    Adb.Descargar(delegate(int pct) { Estado(false, "Descargando ADB... " + pct + "%"); });
                    Log("ADB listo: " + Adb.Ruta);
                    Estado(false, "Desconectado");
                }
                catch (Exception ex)
                {
                    error = ex;
                    Log("Error al descargar ADB: " + ex.Message);
                    Estado(false, "No se pudo descargar ADB");
                }
                alTerminar(error);
            });
        }

        // Llamado cada poco: detecta conexiones caídas y reconecta en silencio
        public void Vigilar()
        {
            if (cola.Count > 0 || trabajando || Adb.Ruta == null) return;
            Encolar(delegate
            {
                if (shell != null && shell.Viva) return;
                if (Conectado) Perdida();
                if (!cfg.AutoConectar) return;
                silencioso = true;
                try { ConectarAuto(); }
                finally { silencioso = false; }
            });
        }

        // ---- Implementación (hilo de adb) ----

        void HacerDeslizar(bool siguiente)
        {
            if (shell == null || !shell.Viva)
            {
                if (Conectado) Perdida();
                if (!ConectarAuto()) return;
            }
            // Para pasar al siguiente video el dedo sube (de abajo hacia arriba)
            bool dedoSube = siguiente != cfg.Invertir;
            int x = ancho / 2;
            int mitad = alto * cfg.Distancia / 200;
            int abajo = alto / 2 + mitad, arriba = alto / 2 - mitad;
            string cmd = string.Format("input swipe {0} {1} {0} {2} {3}",
                x, dedoSube ? abajo : arriba, dedoSube ? arriba : abajo, cfg.Duracion);
            if (shell.Enviar(cmd)) return;
            Perdida();
            if (ConectarAuto()) shell.Enviar(cmd);
        }

        bool AdbListo()
        {
            if (Adb.Ruta == null && Adb.Buscar() == null)
            {
                Estado(false, "Falta ADB");
                Log("No se encontró adb.exe.");
                return false;
            }
            if (!servidorIniciado)
            {
                Adb.Ejecutar("start-server", 20000);
                servidorIniciado = true;
            }
            return true;
        }

        bool ConectarAuto()
        {
            if (!AdbListo()) return false;
            pista = pistaCorta = null;
            if (!silencioso) Estado(false, "Buscando el teléfono...");
            // 1) ¿Ya hay un dispositivo listo? (adb reconecta solo los teléfonos vinculados)
            string serial = ElegirDispositivo();
            // 2) Última dirección que funcionó, si es de nuestra red
            if (serial == null && cfg.Direccion.Length > 0 && Red.EnMiRed(cfg.Direccion) && ConectarDireccion(cfg.Direccion, true))
                serial = cfg.Direccion;
            // 3) Teléfonos anunciados en la red con una IPv4 de nuestra subred (la VPN no se mete en la red local)
            List<string> lejanos = new List<string>();
            if (serial == null)
            {
                List<string> servicios = Adb.ServiciosMdns();
                if (servicios.Count == 0 && !silencioso)
                {
                    Thread.Sleep(1500);
                    servicios = Adb.ServiciosMdns();
                }
                foreach (string dir in servicios)
                {
                    if (!Red.EnMiRed(dir)) { lejanos.Add(dir); continue; }
                    if (ConectarDireccion(dir, true)) { serial = dir; break; }
                }
            }
            // 4) Por IPv6 de enlace local con un puente: teléfono con IP de otro router de la misma Wi-Fi, o VPN
            if (serial == null)
            {
                foreach (Mdns6.Servicio s in Mdns6.Buscar("_adb-tls-connect._tcp", 1500))
                {
                    Puente p = PuenteA(s.Nombre, s.Destino);
                    p.LlegoAlTelefono = false;
                    if (ConectarDireccion(p.Direccion, false)) { serial = p.Direccion; break; }
                    if (p.LlegoAlTelefono) NoVinculado(); // contesta, pero rechaza la conexión
                }
            }
            // 5) Último intento: IPv4 de otras redes (por si se llega a través del router)
            if (serial == null && pistaCorta == null)
                foreach (string dir in lejanos)
                    if (ConectarDireccion(dir, true)) { serial = dir; break; }
            if (serial != null && Usar(serial)) return true;
            Estado(false, TextoDesconectado());
            return false;
        }

        Puente PuenteA(string nombre, IPEndPoint destino)
        {
            Puente p;
            if (puentes.TryGetValue(nombre, out p))
            {
                if (p.Destino.Equals(destino)) return p;
                p.Dispose();
            }
            p = new Puente(destino);
            puentes[nombre] = p;
            Log("Llegando al teléfono por IPv6 de enlace local (" + destino + ") con un puente en " + p.Direccion + ".");
            return p;
        }

        void QuitarPuente(string nombre)
        {
            Puente p;
            if (!puentes.TryGetValue(nombre, out p)) return;
            p.Dispose();
            puentes.Remove(nombre);
        }

        // Para una IP:puerto escrita a mano: si no es de nuestra red, busca ese mismo puerto por IPv6 y usa un puente
        string Alcanzable(string dir, string tipo)
        {
            if (Red.EnMiRed(dir)) return dir;
            int puerto;
            if (!int.TryParse(dir.Substring(dir.LastIndexOf(':') + 1), out puerto)) return dir;
            foreach (Mdns6.Servicio s in Mdns6.Buscar(tipo, 1500))
                if (s.Destino.Port == puerto) return PuenteA(s.Nombre, s.Destino).Direccion;
            return dir;
        }

        // Dónde está el servicio de vinculación que anuncia el teléfono al escanear nuestro QR
        string DireccionVinculacion(string nombre, bool ultimoRecurso)
        {
            string v4 = Adb.ServicioVinculacion(nombre);
            if (v4 != null && Red.EnMiRed(v4)) return v4;
            foreach (Mdns6.Servicio s in Mdns6.Buscar("_adb-tls-pairing._tcp", 1200))
                if (string.Equals(s.Nombre, nombre, StringComparison.OrdinalIgnoreCase)) return PuenteA(nombre, s.Destino).Direccion;
            return ultimoRecurso ? v4 : null;
        }

        void NoVinculado()
        {
            pistaCorta = "falta vincular";
            pista = "Tu teléfono responde, pero no reconoce esta PC. Vuelve a vincularlo: " +
                    "«Conectar teléfono» > «Vincular escaneando un código QR».";
            Log(pista);
            Action<string> h = Problema;
            if (h != null && !silencioso) h(pista);
        }

        bool ConectarDireccion(string dir, bool guardar)
        {
            // Una conexión vieja "offline" hace que adb responda "already connected" sin conectar de verdad
            string estado;
            if (Adb.Estados().TryGetValue(dir, out estado) && estado != "device")
                Adb.Ejecutar("disconnect " + dir, 5000);
            Log("Conectando con " + dir + "...");
            string r = Adb.Ejecutar("connect " + dir, 12000);
            Log(r);
            bool ok = r.IndexOf("connected to", StringComparison.OrdinalIgnoreCase) >= 0;
            if (ok)
            {
                // A través de una VPN adb puede decir "connected" sin haber llegado de verdad al teléfono
                ok = false;
                for (int i = 0; i < 6 && !ok; i++)
                {
                    string estado2;
                    ok = Adb.Estados().TryGetValue(dir, out estado2) && estado2 == "device";
                    if (!ok) Thread.Sleep(500);
                }
            }
            if (ok)
            {
                pista = pistaCorta = null;
                if (guardar)
                {
                    cfg.Direccion = dir;
                    cfg.Guardar();
                }
            }
            else
            {
                Adb.Ejecutar("disconnect " + dir, 5000); // que no quede como "offline"
                if (!dir.StartsWith("127.0.0.1:")) ExplicarFallo(dir);
            }
            return ok;
        }

        // Si se sabe por qué no se llega al teléfono (otra red, VPN...), lo apunta y avisa
        string ExplicarFallo(string dir)
        {
            string corto;
            string p = Red.Diagnostico(dir, out corto);
            if (p == null) return null;
            pista = p;
            pistaCorta = corto;
            Log(p);
            Action<string> h = Problema;
            if (h != null && !silencioso) h(p);
            return p;
        }

        string TextoDesconectado()
        {
            return pistaCorta != null ? "Desconectado: " + pistaCorta : "Desconectado";
        }

        string ElegirDispositivo()
        {
            List<string> listos = Adb.Dispositivos();
            if (listos.Count == 0) return null;
            if (listos.Contains(cfg.Serial)) return cfg.Serial;
            if (listos.Contains(cfg.Direccion)) return cfg.Direccion;
            return listos[0];
        }

        bool Usar(string serial)
        {
            Size t = Adb.TamanoPantalla(serial);
            if (t.IsEmpty)
            {
                Log("No se pudo leer el tamaño de pantalla de " + serial + ".");
                return false;
            }
            CerrarShell();
            ShellPersistente s = new ShellPersistente(serial);
            Thread.Sleep(300);
            if (!s.Viva)
            {
                s.Dispose();
                Log("No se pudo abrir la sesión con " + serial + ".");
                return false;
            }
            shell = s;
            ancho = t.Width;
            alto = t.Height;
            string modelo = Adb.Ejecutar("-s " + Adb.Comillas(serial) + " shell getprop ro.product.model", 8000).Trim();
            if (modelo.Length == 0 || modelo.IndexOf("error", StringComparison.OrdinalIgnoreCase) >= 0) modelo = serial;
            cfg.Serial = serial;
            cfg.Guardar();
            Log("Conectado a " + modelo + " (" + ancho + "x" + alto + "). ¡Listo para usar!");
            Estado(true, "Conectado: " + modelo);
            return true;
        }

        void Perdida()
        {
            CerrarShell();
            Log("Se perdió la conexión con el teléfono.");
            Estado(false, "Se perdió la conexión");
        }

        void CerrarShell()
        {
            if (shell != null) shell.Dispose();
            shell = null;
        }

        void Estado(bool conectado, string texto)
        {
            Conectado = conectado;
            string clave = (conectado ? "1" : "0") + texto;
            if (clave == ultimoEstado) return;
            ultimoEstado = clave;
            Action<bool, string> h = EstadoCambiado;
            if (h != null) h(conectado, texto);
        }

        void Log(string texto)
        {
            Action<string> h = Registro;
            if (silencioso || h == null || texto == null) return;
            foreach (string l in texto.Replace("\r", "").Split('\n'))
                if (l.Trim().Length > 0) h(DateTime.Now.ToString("HH:mm:ss") + "  " + l.TrimEnd());
        }

        public void Dispose()
        {
            cola.CompleteAdding();
            ShellPersistente s = shell;
            if (s != null) s.Dispose();
            foreach (Puente p in new List<Puente>(puentes.Values)) p.Dispose();
        }
    }

    // Ventana invisible que recibe los atajos de teclado globales
    class HotkeyWindow : NativeWindow, IDisposable
    {
        public event Action<int> Pulsada;
        readonly List<int> registradas = new List<int>();

        public HotkeyWindow() { CreateHandle(new CreateParams()); }

        public bool Registrar(int id, Keys k)
        {
            Quitar(id);
            Keys codigo = k & Keys.KeyCode;
            if (codigo == Keys.None) return true;
            uint mods = Native.MOD_NOREPEAT;
            if ((k & Keys.Control) != 0) mods |= Native.MOD_CONTROL;
            if ((k & Keys.Alt) != 0) mods |= Native.MOD_ALT;
            if ((k & Keys.Shift) != 0) mods |= Native.MOD_SHIFT;
            if (!Native.RegisterHotKey(Handle, id, mods, (uint)codigo)) return false;
            registradas.Add(id);
            return true;
        }

        public void Quitar(int id)
        {
            if (registradas.Remove(id)) Native.UnregisterHotKey(Handle, id);
        }

        public void QuitarTodas()
        {
            foreach (int id in registradas) Native.UnregisterHotKey(Handle, id);
            registradas.Clear();
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == Native.WM_HOTKEY && Pulsada != null) Pulsada((int)m.WParam);
            base.WndProc(ref m);
        }

        public void Dispose() { QuitarTodas(); DestroyHandle(); }
    }

    // Gancho global del ratón: botones laterales, clic de la rueda y Ctrl + rueda
    class RatonHook : IDisposable
    {
        public Func<BotonRaton, bool> Manejar; // devuelve true para "comerse" el evento
        readonly Native.LowLevelMouseProc proc;
        IntPtr hook;
        bool consumidoX1, consumidoX2, consumidoCentral;

        public RatonHook()
        {
            proc = Procesar; // referencia guardada para que el GC no la libere
            hook = Native.SetWindowsHookEx(Native.WH_MOUSE_LL, proc, Native.GetModuleHandle(null), 0);
        }

        public bool Instalado { get { return hook != IntPtr.Zero; } }

        IntPtr Procesar(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0 && Manejar != null)
            {
                Native.MSLLHOOKSTRUCT d = (Native.MSLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(Native.MSLLHOOKSTRUCT));
                int alto = (short)(d.mouseData >> 16);
                bool consumir = false;
                switch (wParam.ToInt32())
                {
                    case Native.WM_XBUTTONDOWN:
                        consumir = Manejar(alto == 1 ? BotonRaton.LateralAtras : BotonRaton.LateralAdelante);
                        if (alto == 1) consumidoX1 = consumir; else consumidoX2 = consumir;
                        break;
                    case Native.WM_XBUTTONUP:
                        consumir = alto == 1 ? consumidoX1 : consumidoX2;
                        break;
                    case Native.WM_MBUTTONDOWN:
                        consumir = consumidoCentral = Manejar(BotonRaton.Central);
                        break;
                    case Native.WM_MBUTTONUP:
                        consumir = consumidoCentral;
                        break;
                    case Native.WM_MOUSEWHEEL:
                        if ((Native.GetAsyncKeyState(Native.VK_CONTROL) & 0x8000) != 0)
                            consumir = Manejar(alto > 0 ? BotonRaton.CtrlRuedaArriba : BotonRaton.CtrlRuedaAbajo);
                        break;
                }
                if (consumir) return (IntPtr)1;
            }
            return Native.CallNextHookEx(hook, nCode, wParam, lParam);
        }

        public void Dispose()
        {
            if (hook != IntPtr.Zero) Native.UnhookWindowsHookEx(hook);
            hook = IntPtr.Zero;
        }
    }

    class SesionQr
    {
        public volatile bool Cancelada;
    }

    // Generador mínimo de códigos QR: modo byte, corrección de errores M, versiones 1 a 6 (hasta 106 bytes)
    static class Qr
    {
        static readonly int[] EccPorBloque = { 0, 10, 16, 26, 18, 24, 16 };
        static readonly int[] Bloques = { 0, 1, 1, 1, 2, 2, 4 };

        public static bool[,] Generar(string texto)
        {
            byte[] datos = Encoding.UTF8.GetBytes(texto);
            int version = 0, capacidad = 0;
            for (int v = 1; v <= 6 && version == 0; v++)
            {
                capacidad = CodewordsTotales(v) - Bloques[v] * EccPorBloque[v];
                if (4 + 8 + datos.Length * 8 <= capacidad * 8) version = v;
            }
            if (version == 0) throw new ArgumentException("Texto demasiado largo para el código QR.");

            // Modo byte (0100), longitud en 8 bits, datos, terminador y relleno hasta llenar la capacidad
            List<bool> bits = new List<bool>();
            AgregarBits(bits, 4, 4);
            AgregarBits(bits, datos.Length, 8);
            foreach (byte b in datos) AgregarBits(bits, b, 8);
            AgregarBits(bits, 0, Math.Min(4, capacidad * 8 - bits.Count));
            AgregarBits(bits, 0, (8 - bits.Count % 8) % 8);
            byte[] palabras = new byte[capacidad];
            for (int i = 0; i < bits.Count; i++)
                if (bits[i]) palabras[i >> 3] |= (byte)(0x80 >> (i & 7));
            for (int i = bits.Count / 8, k = 0; i < capacidad; i++, k++)
                palabras[i] = (byte)(k % 2 == 0 ? 0xEC : 0x11);

            Matriz m = new Matriz(version);
            m.DibujarFijos();
            m.DibujarDatos(Intercalar(palabras, version));

            // Elegir la máscara con menos penalización (aplicar la misma máscara dos veces la deshace)
            int mejor = 0;
            long minimo = long.MaxValue;
            for (int k = 0; k < 8; k++)
            {
                m.Mascara(k);
                m.Formato(k);
                long p = m.Penalizacion();
                if (p < minimo) { minimo = p; mejor = k; }
                m.Mascara(k);
            }
            m.Mascara(mejor);
            m.Formato(mejor);
            return m.Modulos;
        }

        // Imagen con 4 módulos de margen blanco, escalada sin suavizar para que se lea bien
        public static Bitmap Imagen(bool[,] modulos, int lado)
        {
            int n = modulos.GetLength(0), total = n + 8;
            int escala = Math.Max(1, lado / total);
            Bitmap bmp = new Bitmap(total * escala, total * escala);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.White);
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++)
                        if (modulos[x, y]) g.FillRectangle(Brushes.Black, (x + 4) * escala, (y + 4) * escala, escala, escala);
            }
            return bmp;
        }

        static void AgregarBits(List<bool> bits, int valor, int cantidad)
        {
            for (int i = cantidad - 1; i >= 0; i--) bits.Add(((valor >> i) & 1) != 0);
        }

        static int CodewordsTotales(int v)
        {
            int r = (16 * v + 128) * v + 64;
            if (v >= 2)
            {
                int alin = v / 7 + 2;
                r -= (25 * alin - 10) * alin - 55;
                if (v >= 7) r -= 36;
            }
            return r / 8;
        }

        // Añade la corrección Reed-Solomon a cada bloque y los intercala
        static byte[] Intercalar(byte[] datos, int version)
        {
            int numBloques = Bloques[version], ecc = EccPorBloque[version];
            int total = CodewordsTotales(version);
            int cortos = numBloques - total % numBloques;
            int largoCorto = total / numBloques;
            byte[] divisor = DivisorRs(ecc);
            byte[][] bloques = new byte[numBloques][];
            for (int i = 0, k = 0; i < numBloques; i++)
            {
                int largoDatos = largoCorto - ecc + (i < cortos ? 0 : 1);
                byte[] dat = new byte[largoDatos];
                Array.Copy(datos, k, dat, 0, largoDatos);
                k += largoDatos;
                byte[] bloque = new byte[largoCorto + 1];
                Array.Copy(dat, bloque, largoDatos);
                Array.Copy(RestoRs(dat, divisor), 0, bloque, bloque.Length - ecc, ecc);
                bloques[i] = bloque;
            }
            byte[] res = new byte[total];
            for (int i = 0, k = 0; i < bloques[0].Length; i++)
                for (int j = 0; j < numBloques; j++)
                    if (i != largoCorto - ecc || j >= cortos) res[k++] = bloques[j][i];
            return res;
        }

        static byte[] DivisorRs(int grado)
        {
            byte[] r = new byte[grado];
            r[grado - 1] = 1;
            int raiz = 1;
            for (int i = 0; i < grado; i++)
            {
                for (int j = 0; j < grado; j++)
                {
                    r[j] = (byte)MultiplicarGf(r[j], raiz);
                    if (j + 1 < grado) r[j] ^= r[j + 1];
                }
                raiz = MultiplicarGf(raiz, 0x02);
            }
            return r;
        }

        static byte[] RestoRs(byte[] datos, byte[] divisor)
        {
            byte[] r = new byte[divisor.Length];
            foreach (byte b in datos)
            {
                int factor = b ^ r[0];
                Array.Copy(r, 1, r, 0, r.Length - 1);
                r[r.Length - 1] = 0;
                for (int i = 0; i < r.Length; i++) r[i] ^= (byte)MultiplicarGf(divisor[i], factor);
            }
            return r;
        }

        static int MultiplicarGf(int x, int y)
        {
            int z = 0;
            for (int i = 7; i >= 0; i--)
            {
                z = (z << 1) ^ ((z >> 7) * 0x11D);
                z ^= ((y >> i) & 1) * x;
            }
            return z;
        }

        class Matriz
        {
            public readonly bool[,] Modulos; // [x, y], true = negro
            readonly bool[,] fijo;
            readonly int n, version;

            public Matriz(int version)
            {
                this.version = version;
                n = version * 4 + 17;
                Modulos = new bool[n, n];
                fijo = new bool[n, n];
            }

            void Fijar(int x, int y, bool negro)
            {
                Modulos[x, y] = negro;
                fijo[x, y] = true;
            }

            public void DibujarFijos()
            {
                for (int i = 0; i < n; i++)
                {
                    Fijar(6, i, i % 2 == 0);
                    Fijar(i, 6, i % 2 == 0);
                }
                Buscador(3, 3);
                Buscador(n - 4, 3);
                Buscador(3, n - 4);
                if (version >= 2) Alineacion(n - 7, n - 7); // en versiones 2-6 solo hay uno
                Formato(0); // reserva la zona de formato
            }

            void Buscador(int cx, int cy)
            {
                for (int dy = -4; dy <= 4; dy++)
                    for (int dx = -4; dx <= 4; dx++)
                    {
                        int x = cx + dx, y = cy + dy, d = Math.Max(Math.Abs(dx), Math.Abs(dy));
                        if (x >= 0 && x < n && y >= 0 && y < n) Fijar(x, y, d != 2 && d != 4);
                    }
            }

            void Alineacion(int cx, int cy)
            {
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        Fijar(cx + dx, cy + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
            }

            public void Formato(int mascara)
            {
                int datos = (0 << 3) | mascara; // nivel M = 00
                int resto = datos;
                for (int i = 0; i < 10; i++) resto = (resto << 1) ^ ((resto >> 9) * 0x537);
                int bits = ((datos << 10) | resto) ^ 0x5412;
                for (int i = 0; i <= 5; i++) Fijar(8, i, Bit(bits, i));
                Fijar(8, 7, Bit(bits, 6));
                Fijar(8, 8, Bit(bits, 7));
                Fijar(7, 8, Bit(bits, 8));
                for (int i = 9; i < 15; i++) Fijar(14 - i, 8, Bit(bits, i));
                for (int i = 0; i < 8; i++) Fijar(n - 1 - i, 8, Bit(bits, i));
                for (int i = 8; i < 15; i++) Fijar(8, n - 15 + i, Bit(bits, i));
                Fijar(8, n - 8, true);
            }

            static bool Bit(int valor, int i) { return ((valor >> i) & 1) != 0; }

            // Recorrido en zigzag de dos columnas, de abajo a la derecha hacia arriba
            public void DibujarDatos(byte[] datos)
            {
                int i = 0;
                for (int derecha = n - 1; derecha >= 1; derecha -= 2)
                {
                    if (derecha == 6) derecha = 5;
                    for (int vert = 0; vert < n; vert++)
                        for (int j = 0; j < 2; j++)
                        {
                            int x = derecha - j;
                            bool subiendo = ((derecha + 1) & 2) == 0;
                            int y = subiendo ? n - 1 - vert : vert;
                            if (!fijo[x, y] && i < datos.Length * 8)
                            {
                                Modulos[x, y] = Bit(datos[i >> 3], 7 - (i & 7));
                                i++;
                            }
                        }
                }
            }

            public void Mascara(int k)
            {
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++)
                    {
                        if (fijo[x, y]) continue;
                        bool invertir;
                        switch (k)
                        {
                            case 0: invertir = (x + y) % 2 == 0; break;
                            case 1: invertir = y % 2 == 0; break;
                            case 2: invertir = x % 3 == 0; break;
                            case 3: invertir = (x + y) % 3 == 0; break;
                            case 4: invertir = (x / 3 + y / 2) % 2 == 0; break;
                            case 5: invertir = x * y % 2 + x * y % 3 == 0; break;
                            case 6: invertir = (x * y % 2 + x * y % 3) % 2 == 0; break;
                            default: invertir = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                        }
                        if (invertir) Modulos[x, y] = !Modulos[x, y];
                    }
            }

            static readonly bool[] Patron1 = { true, false, true, true, true, false, true, false, false, false, false };
            static readonly bool[] Patron2 = { false, false, false, false, true, false, true, true, true, false, true };

            public long Penalizacion()
            {
                long p = 0;
                // Rachas de 5 o más módulos iguales en filas y columnas
                for (int a = 0; a < n; a++)
                {
                    int fila = 1, col = 1;
                    for (int b = 1; b < n; b++)
                    {
                        if (Modulos[b, a] == Modulos[b - 1, a]) { fila++; if (fila == 5) p += 3; else if (fila > 5) p++; }
                        else fila = 1;
                        if (Modulos[a, b] == Modulos[a, b - 1]) { col++; if (col == 5) p += 3; else if (col > 5) p++; }
                        else col = 1;
                    }
                }
                // Bloques 2x2 del mismo color
                for (int y = 0; y < n - 1; y++)
                    for (int x = 0; x < n - 1; x++)
                    {
                        bool c = Modulos[x, y];
                        if (c == Modulos[x + 1, y] && c == Modulos[x, y + 1] && c == Modulos[x + 1, y + 1]) p += 3;
                    }
                // Secuencias que se parecen a los cuadros de las esquinas
                for (int a = 0; a < n; a++)
                    for (int b = 0; b + 11 <= n; b++)
                    {
                        if (Coincide(b, a, true)) p += 40;
                        if (Coincide(b, a, false)) p += 40;
                    }
                // Equilibrio entre negros y blancos
                int oscuros = 0;
                foreach (bool v in Modulos) if (v) oscuros++;
                int total = n * n;
                p += ((Math.Abs(oscuros * 20 - total * 10) + total - 1) / total - 1) * 10;
                return p;
            }

            bool Coincide(int inicio, int linea, bool horizontal)
            {
                bool p1 = true, p2 = true;
                for (int i = 0; i < 11; i++)
                {
                    bool v = horizontal ? Modulos[inicio + i, linea] : Modulos[linea, inicio + i];
                    if (v != Patron1[i]) p1 = false;
                    if (v != Patron2[i]) p2 = false;
                }
                return p1 || p2;
            }
        }
    }

    static class Ui
    {
        public static Label Etiqueta(string texto, int x, int y, int ancho, int alto)
        {
            Label l = new Label();
            l.Text = texto;
            l.SetBounds(x, y, ancho, alto);
            return l;
        }

        public static Button Boton(string texto, int x, int y, int ancho, int alto)
        {
            Button b = new Button();
            b.Text = texto;
            b.SetBounds(x, y, ancho, alto);
            return b;
        }
    }

    class MainForm : Form
    {
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string AppName = "ScrollRemoto";
        const int ID_SIGUIENTE = 1, ID_ANTERIOR = 2, ID_PAUSA = 3;

        readonly Config cfg;
        readonly Telefono telefono;
        readonly HotkeyWindow atajos;
        readonly RatonHook raton;
        readonly NotifyIcon tray;
        readonly System.Windows.Forms.Timer vigilancia;
        readonly Icon iconVerde, iconAmbar, iconGris;
        readonly List<string> registro = new List<string>();
        readonly Label lblEstado, lblAtajos;
        readonly Button btnAnterior, btnSiguiente;
        readonly CheckBox chkActivo;
        readonly ToolStripMenuItem itemActivo;
        ConectarForm conectarAbierto;
        Form modal;
        bool activo = true, conectado, iniciarOculto, arranqueOculto, saliendo, avisoBandeja, configAbierta, descargando;
        string textoEstado = "Desconectado", ultimoProblema;
        int ultimaRueda;

        public MainForm(bool oculto)
        {
            iniciarOculto = arranqueOculto = oculto;
            cfg = Config.Cargar();
            iconVerde = CrearIcono(Color.FromArgb(40, 180, 90));
            iconAmbar = CrearIcono(Color.FromArgb(230, 160, 30));
            iconGris = CrearIcono(Color.FromArgb(140, 140, 140));

            Text = "Scroll Remoto";
            Icon = iconVerde;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(320, 316);
            Font = new Font("Segoe UI", 9f);
            TopMost = cfg.SiempreEncima;

            lblEstado = Ui.Etiqueta("", 12, 10, 296, 22);
            lblEstado.Font = new Font("Segoe UI", 10f, FontStyle.Bold);

            btnAnterior = Ui.Boton("▲   Anterior", 12, 38, 296, 56);
            btnAnterior.Font = new Font("Segoe UI", 14f, FontStyle.Bold);
            btnAnterior.Click += delegate { Accion(false); };
            btnSiguiente = Ui.Boton("▼   Siguiente", 12, 100, 296, 56);
            btnSiguiente.Font = btnAnterior.Font;
            btnSiguiente.Click += delegate { Accion(true); };

            chkActivo = new CheckBox();
            chkActivo.Text = "Control con teclado y ratón activo";
            chkActivo.Checked = true;
            chkActivo.SetBounds(12, 164, 296, 22);
            chkActivo.CheckedChanged += delegate { if (chkActivo.Checked != activo) EstablecerActivo(chkActivo.Checked, false); };

            lblAtajos = Ui.Etiqueta("", 12, 190, 296, 74);
            lblAtajos.ForeColor = SystemColors.GrayText;

            Button btnConectar = Ui.Boton("Conectar teléfono...", 12, 274, 146, 30);
            btnConectar.Click += delegate { AbrirConectar(); };
            Button btnConfig = Ui.Boton("Configuración...", 162, 274, 146, 30);
            btnConfig.Click += delegate { AbrirConfig(); };

            Controls.AddRange(new Control[] { lblEstado, btnAnterior, btnSiguiente, chkActivo, lblAtajos, btnConectar, btnConfig });

            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Mostrar ventana", null, delegate { MostrarVentana(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("▼ Siguiente video", null, delegate { Accion(true); });
            menu.Items.Add("▲ Video anterior", null, delegate { Accion(false); });
            itemActivo = new ToolStripMenuItem("Control con teclado y ratón activo", null, delegate { EstablecerActivo(!activo, false); });
            menu.Items.Add(itemActivo);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Conectar teléfono...", null, delegate { AbrirConectar(); });
            menu.Items.Add("Configuración...", null, delegate { AbrirConfig(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Salir", null, delegate { Salir(); });

            tray = new NotifyIcon();
            tray.ContextMenuStrip = menu;
            tray.Icon = iconGris;
            tray.Visible = true;
            tray.MouseClick += delegate(object s, MouseEventArgs e) { if (e.Button == MouseButtons.Left) MostrarVentana(); };

            telefono = new Telefono(cfg);
            telefono.EstadoCambiado += delegate(bool c, string t) { EnUI(delegate { MostrarEstado(c, t); }); };
            telefono.Registro += delegate(string linea) { EnUI(delegate { AgregarRegistro(linea); }); };
            telefono.Problema += delegate(string p) { EnUI(delegate { AvisarProblema(p); }); };

            atajos = new HotkeyWindow();
            atajos.Pulsada += delegate(int id)
            {
                if (id == ID_PAUSA) EstablecerActivo(!activo, true);
                else if (activo && !configAbierta) Accion(id == ID_SIGUIENTE);
            };

            raton = new RatonHook();
            raton.Manejar = ManejarRaton;

            vigilancia = new System.Windows.Forms.Timer();
            vigilancia.Interval = 20000;
            vigilancia.Tick += delegate { telefono.Vigilar(); };
            vigilancia.Start();

            IntPtr h = Handle; // crea la ventana ya, para poder usar BeginInvoke aunque arranque oculta
            MostrarEstado(false, Adb.Buscar() == null ? "Falta ADB" : "Desconectado");
            ActualizarTextoAtajos();
            List<string> fallos = RegistrarAtajos();
            if (fallos.Count > 0)
                tray.ShowBalloonTip(5000, "Scroll Remoto", "No se pudieron registrar los atajos " + string.Join(", ", fallos) +
                    " (los usa otra aplicación). Cámbialos en Configuración.", ToolTipIcon.Warning);
            else if (!raton.Instalado)
                tray.ShowBalloonTip(5000, "Scroll Remoto", "No se pudo activar el control con el ratón.", ToolTipIcon.Warning);
            BeginInvoke((MethodInvoker)Inicio);
        }

        protected override void SetVisibleCore(bool value)
        {
            if (iniciarOculto)
            {
                iniciarOculto = false;
                value = false;
            }
            base.SetVisibleCore(value);
        }

        void Inicio()
        {
            bool primeraVez = cfg.Direccion.Length == 0 && cfg.Serial.Length == 0;
            if (Adb.Ruta == null)
            {
                OfrecerDescarga(primeraVez);
                return;
            }
            if (cfg.AutoConectar) telefono.ConectarAutomatico();
            if (primeraVez && !arranqueOculto) AbrirConectar();
        }

        void OfrecerDescarga(bool abrirConectar)
        {
            if (descargando) return;
            MostrarVentana();
            if (MessageBox.Show(this,
                    "Para comunicarse con el teléfono, Scroll Remoto necesita ADB (Android Platform-Tools, " +
                    "la herramienta oficial de Google, unos 7 MB).\n\n¿Descargarlo ahora?",
                    "Scroll Remoto", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
            {
                MostrarEstado(false, "Falta ADB");
                return;
            }
            descargando = true;
            telefono.DescargarAdb(delegate(Exception error)
            {
                EnUI(delegate
                {
                    descargando = false;
                    if (error != null)
                    {
                        MessageBox.Show(this, "No se pudo descargar ADB:\n" + error.Message +
                            "\n\nComprueba la conexión a internet y vuelve a intentarlo con «Conectar teléfono...».",
                            "Scroll Remoto", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        return;
                    }
                    tray.ShowBalloonTip(2000, "Scroll Remoto", "ADB descargado correctamente.", ToolTipIcon.Info);
                    telefono.ConectarAutomatico();
                    if (abrirConectar) AbrirConectar();
                });
            });
        }

        void EnUI(MethodInvoker m)
        {
            if (saliendo || IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(m); } catch (InvalidOperationException) { }
        }

        void Accion(bool siguiente)
        {
            telefono.Deslizar(siguiente);
            Destello(siguiente ? btnSiguiente : btnAnterior);
        }

        static void Destello(Button b)
        {
            b.BackColor = Color.FromArgb(190, 225, 255);
            System.Windows.Forms.Timer t = new System.Windows.Forms.Timer();
            t.Interval = 180;
            t.Tick += delegate
            {
                t.Stop();
                t.Dispose();
                b.BackColor = SystemColors.Control;
                b.UseVisualStyleBackColor = true;
            };
            t.Start();
        }

        bool ManejarRaton(BotonRaton b)
        {
            if (!activo || configAbierta || b == BotonRaton.Ninguno) return false;
            bool siguiente;
            if (b == cfg.RatonSiguiente) siguiente = true;
            else if (b == cfg.RatonAnterior) siguiente = false;
            else return false;
            if (b == BotonRaton.CtrlRuedaArriba || b == BotonRaton.CtrlRuedaAbajo)
            {
                // Las ruedas suaves mandan muchos eventos por gesto: como mucho uno cada 400 ms
                int ahora = Environment.TickCount;
                if (ahora - ultimaRueda < 400) return true;
                ultimaRueda = ahora;
            }
            BeginInvoke((MethodInvoker)delegate { Accion(siguiente); });
            return true;
        }

        void EstablecerActivo(bool on, bool avisar)
        {
            activo = on;
            chkActivo.Checked = on;
            itemActivo.Checked = on;
            RegistrarAtajos();
            ActualizarIcono();
            if (avisar)
                tray.ShowBalloonTip(1200, "Scroll Remoto", on ? "Control activo" : "Control en pausa", ToolTipIcon.Info);
        }

        // Con el control en pausa se liberan las teclas de Siguiente/Anterior para que funcionen en otros programas
        List<string> RegistrarAtajos()
        {
            List<string> fallos = new List<string>();
            if (!atajos.Registrar(ID_PAUSA, cfg.TeclaPausa)) fallos.Add(Config.TeclaTexto(cfg.TeclaPausa));
            if (activo)
            {
                if (!atajos.Registrar(ID_SIGUIENTE, cfg.TeclaSiguiente)) fallos.Add(Config.TeclaTexto(cfg.TeclaSiguiente));
                if (!atajos.Registrar(ID_ANTERIOR, cfg.TeclaAnterior)) fallos.Add(Config.TeclaTexto(cfg.TeclaAnterior));
            }
            else
            {
                atajos.Quitar(ID_SIGUIENTE);
                atajos.Quitar(ID_ANTERIOR);
            }
            return fallos;
        }

        void MostrarEstado(bool c, string texto)
        {
            conectado = c;
            textoEstado = texto;
            lblEstado.Text = "● " + texto;
            lblEstado.ForeColor = c ? Color.FromArgb(30, 140, 70) : Color.FromArgb(130, 130, 130);
            ActualizarIcono();
        }

        void ActualizarIcono()
        {
            tray.Icon = !conectado ? iconGris : activo ? iconVerde : iconAmbar;
            string t = "Scroll Remoto: " + textoEstado + (activo ? "" : " (pausa)");
            tray.Text = t.Length > 63 ? t.Substring(0, 63) : t;
        }

        void ActualizarTextoAtajos()
        {
            lblAtajos.Text =
                "Siguiente:  " + Combinacion(cfg.TeclaSiguiente, cfg.RatonSiguiente) + "\n" +
                "Anterior:  " + Combinacion(cfg.TeclaAnterior, cfg.RatonAnterior) + "\n" +
                "Activar / pausar:  " + Config.TeclaTexto(cfg.TeclaPausa);
        }

        static string Combinacion(Keys k, BotonRaton b)
        {
            string s = Config.TeclaTexto(k);
            if (b != BotonRaton.Ninguno) s += "  ·  " + Config.NombresRaton[(int)b];
            return s;
        }

        void AvisarProblema(string p)
        {
            if (p == ultimoProblema) return;
            ultimoProblema = p;
            tray.ShowBalloonTip(8000, "Scroll Remoto: no llega al teléfono", p.Length > 250 ? p.Substring(0, 250) : p, ToolTipIcon.Warning);
        }

        void AgregarRegistro(string linea)
        {
            registro.Add(linea);
            if (registro.Count > 400) registro.RemoveRange(0, 100);
            if (conectarAbierto != null) conectarAbierto.AgregarLinea(linea);
        }

        void MostrarVentana()
        {
            Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
        }

        void AbrirConectar()
        {
            if (modal != null) { modal.Activate(); return; }
            MostrarVentana();
            if (Adb.Ruta == null && Adb.Buscar() == null)
            {
                OfrecerDescarga(true);
                return;
            }
            conectarAbierto = new ConectarForm(telefono, cfg, registro, Icon);
            modal = conectarAbierto;
            conectarAbierto.ShowDialog(this);
            conectarAbierto.Dispose();
            conectarAbierto = null;
            modal = null;
        }

        void AbrirConfig()
        {
            if (modal != null) { modal.Activate(); return; }
            MostrarVentana();
            configAbierta = true;
            atajos.QuitarTodas(); // para poder capturar las teclas actuales en el formulario
            using (ConfigForm f = new ConfigForm(cfg, InicioConWindows(), Icon))
            {
                modal = f;
                if (f.ShowDialog(this) == DialogResult.OK)
                {
                    cfg.TeclaSiguiente = f.TeclaSiguiente;
                    cfg.TeclaAnterior = f.TeclaAnterior;
                    cfg.TeclaPausa = f.TeclaPausa;
                    cfg.RatonSiguiente = f.RatonSiguiente;
                    cfg.RatonAnterior = f.RatonAnterior;
                    cfg.Distancia = f.Distancia;
                    cfg.Duracion = f.Duracion;
                    cfg.Invertir = f.Invertir;
                    cfg.AutoConectar = f.AutoConectar;
                    cfg.SiempreEncima = f.SiempreEncima;
                    cfg.Guardar();
                    FijarInicioConWindows(f.IniciarConWindows);
                    TopMost = cfg.SiempreEncima;
                    ActualizarTextoAtajos();
                }
                modal = null;
            }
            configAbierta = false;
            List<string> fallos = RegistrarAtajos();
            if (fallos.Count > 0)
                MessageBox.Show(this, "No se pudieron registrar estos atajos: " + string.Join(", ", fallos) +
                    ".\nProbablemente otra aplicación ya los usa; elige otros.", "Scroll Remoto",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        static bool InicioConWindows()
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey))
                return k != null && k.GetValue(AppName) != null;
        }

        static void FijarInicioConWindows(bool on)
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
            {
                if (k == null) return;
                if (on) k.SetValue(AppName, "\"" + Application.ExecutablePath + "\" /min");
                else if (k.GetValue(AppName) != null) k.DeleteValue(AppName);
            }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!saliendo && e.CloseReason == CloseReason.UserClosing)
            {
                // La X solo esconde la ventana: los atajos siguen funcionando desde la bandeja
                e.Cancel = true;
                Hide();
                if (!avisoBandeja)
                {
                    avisoBandeja = true;
                    tray.ShowBalloonTip(2500, "Scroll Remoto",
                        "Sigue funcionando aquí. Clic derecho en el icono para salir.", ToolTipIcon.Info);
                }
            }
            base.OnFormClosing(e);
        }

        void Salir()
        {
            if (modal != null) return;
            saliendo = true;
            vigilancia.Stop();
            raton.Dispose();
            atajos.Dispose();
            telefono.Dispose();
            tray.Visible = false;
            tray.Dispose();
            Application.Exit();
        }

        static Icon CrearIcono(Color color)
        {
            using (Bitmap bmp = new Bitmap(32, 32))
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                // Teléfono
                using (GraphicsPath marco = Redondeado(new Rectangle(7, 1, 18, 30), 4))
                using (SolidBrush b = new SolidBrush(color))
                    g.FillPath(b, marco);
                using (SolidBrush w = new SolidBrush(Color.White))
                    g.FillRectangle(w, 10, 4, 12, 22);
                // Flechas arriba / abajo
                using (SolidBrush b = new SolidBrush(color))
                {
                    g.FillPolygon(b, new Point[] { new Point(16, 6), new Point(21, 12), new Point(11, 12) });
                    g.FillPolygon(b, new Point[] { new Point(16, 24), new Point(21, 18), new Point(11, 18) });
                }
                IntPtr h = bmp.GetHicon();
                Icon tmp = Icon.FromHandle(h);
                Icon copia = (Icon)tmp.Clone();
                Native.DestroyIcon(h);
                return copia;
            }
        }

        static GraphicsPath Redondeado(Rectangle r, int radio)
        {
            int d = radio * 2;
            GraphicsPath p = new GraphicsPath();
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }
    }

    class ConectarForm : Form
    {
        readonly Telefono telefono;
        readonly TextBox txtVincular, txtCodigo, txtDireccion, txtLog;

        public ConectarForm(Telefono telefono, Config cfg, List<string> registro, Icon icono)
        {
            this.telefono = telefono;
            Text = "Scroll Remoto - Conectar teléfono";
            Icon = icono;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(470, 552);
            Font = new Font("Segoe UI", 9f);

            Label info = Ui.Etiqueta(
                "En el teléfono: Ajustes > Opciones de desarrollador > Depuración inalámbrica, y actívala. " +
                "El teléfono y la PC tienen que estar en la misma red Wi-Fi.\r\n" +
                "¿No ves «Opciones de desarrollador»? Ajustes > Acerca del teléfono > toca 7 veces «Número de compilación».",
                14, 10, 442, 66);

            GroupBox paso1 = new GroupBox();
            paso1.Text = "Paso 1 · Vincular (solo la primera vez)";
            paso1.SetBounds(14, 80, 442, 146);
            Button qr = Ui.Boton("Vincular escaneando un código QR  (lo más rápido)", 12, 22, 418, 36);
            qr.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            qr.Click += delegate { AbrirQr(); };
            txtVincular = new TextBox();
            txtVincular.SetBounds(74, 107, 160, 24);
            txtCodigo = new TextBox();
            txtCodigo.MaxLength = 7;
            txtCodigo.SetBounds(294, 107, 66, 24);
            txtCodigo.KeyDown += delegate(object s, KeyEventArgs e) { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; Vincular(); } };
            Button vincular = Ui.Boton("Vincular", 366, 105, 64, 27);
            vincular.Click += delegate { Vincular(); };
            paso1.Controls.AddRange(new Control[] {
                qr,
                Ui.Etiqueta("O con código de 6 números: toca «Vincular dispositivo con código de vinculación» y copia la IP:puerto y el código:", 12, 66, 418, 36),
                Ui.Etiqueta("IP:puerto", 12, 110, 60, 20), txtVincular,
                Ui.Etiqueta("Código", 244, 110, 48, 20), txtCodigo, vincular
            });

            GroupBox paso2 = new GroupBox();
            paso2.Text = "Paso 2 · Conectar";
            paso2.SetBounds(14, 234, 442, 120);
            txtDireccion = new TextBox();
            txtDireccion.SetBounds(12, 61, 200, 24);
            txtDireccion.KeyDown += delegate(object s, KeyEventArgs e) { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; Conectar(); } };
            Button conectar = Ui.Boton("Conectar", 220, 59, 100, 27);
            conectar.Click += delegate { Conectar(); };
            Button buscar = Ui.Boton("Buscar solo", 326, 59, 104, 27);
            buscar.Click += delegate { telefono.Buscar(); };
            Label notaBuscar = Ui.Etiqueta("Si ya lo vinculaste antes, «Buscar solo» lo encuentra.", 12, 94, 418, 18);
            notaBuscar.ForeColor = SystemColors.GrayText;
            paso2.Controls.AddRange(new Control[] {
                Ui.Etiqueta("Escribe la «Dirección IP y puerto» que aparece en la pantalla principal de Depuración inalámbrica:", 12, 20, 418, 36),
                txtDireccion, conectar, buscar, notaBuscar
            });

            txtLog = new TextBox();
            txtLog.Multiline = true;
            txtLog.ReadOnly = true;
            txtLog.ScrollBars = ScrollBars.Vertical;
            txtLog.BackColor = SystemColors.Window;
            txtLog.Font = new Font("Consolas", 8.5f);
            txtLog.SetBounds(14, 382, 442, 120);

            Button cerrar = Ui.Boton("Cerrar", 372, 512, 84, 28);
            cerrar.DialogResult = DialogResult.Cancel;
            CancelButton = cerrar;

            Controls.AddRange(new Control[] { info, paso1, paso2, Ui.Etiqueta("Registro:", 14, 362, 200, 18), txtLog, cerrar });

            int dp = cfg.Direccion.LastIndexOf(':');
            if (dp > 0) txtVincular.Text = cfg.Direccion.Substring(0, dp + 1);
            txtDireccion.Text = cfg.Direccion;
            foreach (string l in registro) txtLog.AppendText(l + "\r\n");
        }

        public void AgregarLinea(string linea)
        {
            if (!IsDisposed) txtLog.AppendText(linea + "\r\n");
        }

        static bool DireccionValida(string d) { return Regex.IsMatch(d, @"^[A-Za-z0-9.\-]+:\d{1,5}$"); }

        void AbrirQr()
        {
            using (QrForm f = new QrForm(telefono, Icon))
            {
                // Si quedó conectado no hace falta seguir en esta ventana
                if (f.ShowDialog(this) == DialogResult.OK) Close();
            }
        }

        void Vincular()
        {
            string dir = txtVincular.Text.Trim(), codigo = txtCodigo.Text.Replace(" ", "").Trim();
            if (!DireccionValida(dir))
            {
                Aviso("Escribe la IP y el puerto de vinculación tal como aparecen en el teléfono, por ejemplo 192.168.1.20:37123");
                return;
            }
            if (!Regex.IsMatch(codigo, @"^\d{6}$"))
            {
                Aviso("El código de vinculación son los 6 números que muestra el teléfono.");
                return;
            }
            telefono.Vincular(dir, codigo);
        }

        void Conectar()
        {
            string dir = txtDireccion.Text.Trim();
            if (!DireccionValida(dir))
            {
                Aviso("Escribe la IP y el puerto tal como aparecen en el teléfono, por ejemplo 192.168.1.20:41235");
                return;
            }
            telefono.ConectarA(dir);
        }

        void Aviso(string texto)
        {
            MessageBox.Show(this, texto, "Scroll Remoto", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    // Muestra el QR de "Vincular dispositivo con código QR" (formato WIFI:T:ADB;S:nombre;P:clave;;)
    class QrForm : Form
    {
        readonly Telefono telefono;
        readonly PictureBox imagen;
        readonly Label lblEstado;
        SesionQr sesion;

        public QrForm(Telefono telefono, Icon icono)
        {
            this.telefono = telefono;
            Text = "Scroll Remoto - Vincular con QR";
            Icon = icono;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(360, 534);
            Font = new Font("Segoe UI", 9f);

            Label info = Ui.Etiqueta("En el teléfono: Opciones de desarrollador > Depuración inalámbrica > " +
                "«Vincular dispositivo con código QR», y escanea este código:", 16, 12, 328, 52);

            imagen = new PictureBox();
            imagen.SetBounds(40, 66, 280, 280);
            imagen.BackColor = Color.White;
            imagen.SizeMode = PictureBoxSizeMode.CenterImage;

            lblEstado = Ui.Etiqueta("", 16, 354, 328, 84);
            lblEstado.Font = new Font("Segoe UI", 9f, FontStyle.Bold);

            Label nota = Ui.Etiqueta("El teléfono tiene que estar en la misma Wi-Fi que la PC (la VPN puede seguir activada). " +
                "Si Windows pregunta, permite que adb use la red.", 16, 440, 328, 50);
            nota.ForeColor = SystemColors.GrayText;

            Button otro = Ui.Boton("Generar otro", 16, 494, 110, 28);
            otro.Click += delegate { Empezar(); };
            Button cerrar = Ui.Boton("Cerrar", 260, 494, 84, 28);
            cerrar.DialogResult = DialogResult.Cancel;
            CancelButton = cerrar;

            Controls.AddRange(new Control[] { info, imagen, lblEstado, nota, otro, cerrar });
            Empezar();
        }

        // Cada QR lleva un nombre y una clave nuevos; el anterior deja de esperarse
        void Empezar()
        {
            if (sesion != null) sesion.Cancelada = true;
            SesionQr s = new SesionQr();
            sesion = s;
            string nombre = "ScrollRemoto-" + Aleatorio(6), clave = Aleatorio(10);
            Image anterior = imagen.Image;
            imagen.Image = Qr.Imagen(Qr.Generar("WIFI:T:ADB;S:" + nombre + ";P:" + clave + ";;"), 280);
            if (anterior != null) anterior.Dispose();
            MostrarEstado("Esperando a que escanees el código...", false);

            telefono.VincularQr(nombre, clave, s, delegate(string texto, bool terminado, bool exito)
            {
                if (s.Cancelada) return;
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        if (s.Cancelada || IsDisposed) return;
                        MostrarEstado(texto, exito);
                        if (exito) CerrarEnUnMomento();
                    });
                }
                catch (Exception) { } // la ventana ya se cerró
            });
        }

        void MostrarEstado(string texto, bool exito)
        {
            lblEstado.Text = texto;
            lblEstado.ForeColor = exito ? Color.FromArgb(30, 140, 70) : SystemColors.ControlText;
        }

        void CerrarEnUnMomento()
        {
            System.Windows.Forms.Timer t = new System.Windows.Forms.Timer();
            t.Interval = 1500;
            t.Tick += delegate
            {
                t.Stop();
                t.Dispose();
                if (!IsDisposed) DialogResult = DialogResult.OK;
            };
            t.Start();
        }

        static string Aleatorio(int largo)
        {
            const string letras = "abcdefghijkmnpqrstuvwxyz23456789"; // 32 símbolos: sin sesgo con bytes aleatorios
            byte[] b = new byte[largo];
            using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(b);
            StringBuilder sb = new StringBuilder();
            foreach (byte x in b) sb.Append(letras[x % letras.Length]);
            return sb.ToString();
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            if (sesion != null) sesion.Cancelada = true;
            if (imagen.Image != null) imagen.Image.Dispose();
            base.OnFormClosed(e);
        }
    }

    class ConfigForm : Form
    {
        readonly TextBox[] cajas = new TextBox[3];
        readonly Keys[] teclas = new Keys[3];
        readonly ComboBox cmbSiguiente, cmbAnterior;
        readonly NumericUpDown numDistancia, numDuracion;
        readonly CheckBox chkInvertir, chkAuto, chkEncima, chkInicio;

        public Keys TeclaSiguiente { get { return teclas[0]; } }
        public Keys TeclaAnterior { get { return teclas[1]; } }
        public Keys TeclaPausa { get { return teclas[2]; } }
        public BotonRaton RatonSiguiente { get { return (BotonRaton)cmbSiguiente.SelectedIndex; } }
        public BotonRaton RatonAnterior { get { return (BotonRaton)cmbAnterior.SelectedIndex; } }
        public int Distancia { get { return (int)numDistancia.Value; } }
        public int Duracion { get { return (int)numDuracion.Value; } }
        public bool Invertir { get { return chkInvertir.Checked; } }
        public bool AutoConectar { get { return chkAuto.Checked; } }
        public bool SiempreEncima { get { return chkEncima.Checked; } }
        public bool IniciarConWindows { get { return chkInicio.Checked; } }

        public ConfigForm(Config cfg, bool inicioWindows, Icon icono)
        {
            Text = "Scroll Remoto - Configuración";
            Icon = icono;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(440, 404);
            Font = new Font("Segoe UI", 9f);

            Label hTeclado = Ui.Etiqueta("Teclado", 170, 10, 120, 18);
            Label hRaton = Ui.Etiqueta("Ratón", 298, 10, 126, 18);
            hTeclado.ForeColor = hRaton.ForeColor = SystemColors.GrayText;
            Controls.Add(hTeclado);
            Controls.Add(hRaton);

            teclas[0] = cfg.TeclaSiguiente;
            teclas[1] = cfg.TeclaAnterior;
            teclas[2] = cfg.TeclaPausa;
            string[] nombres = { "Siguiente video (▼):", "Video anterior (▲):", "Activar / pausar:" };
            for (int i = 0; i < 3; i++)
            {
                int y = 32 + i * 32;
                Controls.Add(Ui.Etiqueta(nombres[i], 16, y + 3, 150, 20));
                TextBox t = new TextBox();
                t.ReadOnly = true;
                t.BackColor = SystemColors.Window;
                t.Text = Config.TeclaTexto(teclas[i]);
                t.SetBounds(170, y, 120, 24);
                cajas[i] = t;
                Controls.Add(t);
            }
            cmbSiguiente = ComboRaton(cfg.RatonSiguiente, 32);
            cmbAnterior = ComboRaton(cfg.RatonAnterior, 64);

            Label ayuda = Ui.Etiqueta("Haz clic en un cuadro de teclado y pulsa la combinación (Supr = ninguna). " +
                "Evita teclas sueltas: mientras el control está activo dejan de funcionar en los demás programas.", 16, 132, 410, 36);
            ayuda.ForeColor = SystemColors.GrayText;

            numDistancia = Numero(20, 90, cfg.Distancia, 334, 177);
            numDuracion = Numero(30, 1000, cfg.Duracion, 334, 209);
            numDuracion.Increment = 10;

            chkInvertir = Casilla("Invertir dirección (si pasa al video contrario)", cfg.Invertir, 244);
            chkAuto = Casilla("Conectar automáticamente con el teléfono", cfg.AutoConectar, 270);
            chkEncima = Casilla("Ventana siempre encima de las demás", cfg.SiempreEncima, 296);
            chkInicio = Casilla("Iniciar con Windows (en la bandeja)", inicioWindows, 322);

            Button ok = Ui.Boton("Guardar", 252, 364, 84, 28);
            ok.DialogResult = DialogResult.OK;
            Button cancel = Ui.Boton("Cancelar", 340, 364, 84, 28);
            cancel.DialogResult = DialogResult.Cancel;
            AcceptButton = ok;
            CancelButton = cancel;

            Controls.AddRange(new Control[] {
                ayuda,
                Ui.Etiqueta("Distancia del deslizamiento (% de la pantalla):", 16, 180, 310, 20), numDistancia,
                Ui.Etiqueta("Duración del gesto (milisegundos):", 16, 212, 310, 20), numDuracion,
                ok, cancel
            });
        }

        ComboBox ComboRaton(BotonRaton valor, int y)
        {
            ComboBox c = new ComboBox();
            c.DropDownStyle = ComboBoxStyle.DropDownList;
            c.Items.AddRange(Config.NombresRaton);
            c.SelectedIndex = (int)valor;
            c.SetBounds(298, y, 126, 24);
            Controls.Add(c);
            return c;
        }

        static NumericUpDown Numero(int min, int max, int valor, int x, int y)
        {
            NumericUpDown n = new NumericUpDown();
            n.Minimum = min;
            n.Maximum = max;
            n.Value = Math.Min(max, Math.Max(min, valor));
            n.SetBounds(x, y, 90, 24);
            return n;
        }

        CheckBox Casilla(string texto, bool valor, int y)
        {
            CheckBox c = new CheckBox();
            c.Text = texto;
            c.Checked = valor;
            c.SetBounds(16, y, 400, 22);
            Controls.Add(c);
            return c;
        }

        void Capturar(int i, Keys keyData)
        {
            Keys code = keyData & Keys.KeyCode;
            if (code == Keys.Delete || code == Keys.Back) teclas[i] = Keys.None;
            else if (code == Keys.ControlKey || code == Keys.Menu || code == Keys.ShiftKey ||
                     code == Keys.LWin || code == Keys.RWin) return; // esperando la tecla principal
            else teclas[i] = keyData;
            cajas[i].Text = Config.TeclaTexto(teclas[i]);
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            // Permite capturar combinaciones con Alt / Enter / Esc cuando un cuadro de tecla tiene el foco
            if (keyData != Keys.Tab && keyData != (Keys.Shift | Keys.Tab))
            {
                for (int i = 0; i < cajas.Length; i++)
                {
                    if (!cajas[i].Focused) continue;
                    Capturar(i, keyData);
                    return true;
                }
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (DialogResult == DialogResult.OK)
            {
                string error = Validar();
                if (error != null)
                {
                    MessageBox.Show(this, error, "Scroll Remoto", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    e.Cancel = true;
                }
            }
            base.OnFormClosing(e);
        }

        string Validar()
        {
            for (int i = 0; i < teclas.Length; i++)
                for (int j = i + 1; j < teclas.Length; j++)
                    if ((teclas[i] & Keys.KeyCode) != Keys.None && teclas[i] == teclas[j])
                        return "Hay dos acciones con el mismo atajo de teclado (" + Config.TeclaTexto(teclas[i]) + ").";
            if (cmbSiguiente.SelectedIndex > 0 && cmbSiguiente.SelectedIndex == cmbAnterior.SelectedIndex)
                return "Siguiente y Anterior no pueden usar el mismo botón del ratón.";
            return null;
        }
    }
}
