using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows.Forms;

// Las DLL nativas solo se cargan desde System32: nadie puede colar una wlanapi.dll falsa junto al .exe
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]

namespace ModoJuegoWifi
{
    // Cada vez que se abre alterna el "modo juego" del Wi-Fi: apaga o enciende la configuración automática
    // de Windows (lo mismo que "netsh wlan set autoconfig enabled=no/yes"), que es la que hace las
    // búsquedas de redes en segundo plano que provocan picos de ping.
    static class Program
    {
        const string Titulo = "Modo juego Wi-Fi";

        [STAThread]
        static void Main()
        {
            Native.SetDllDirectory(""); // quita la carpeta actual de la búsqueda de DLL
            Application.EnableVisualStyles();

            IntPtr h = IntPtr.Zero;
            try
            {
                h = Wlan.Abrir();
                List<Guid> tarjetas = Wlan.Tarjetas(h);
                if (tarjetas.Count == 0)
                {
                    MessageBox.Show("No se encontró ninguna tarjeta Wi-Fi.", Titulo, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }

                // Se alterna según el estado de la primera tarjeta y se aplica igual a todas
                bool volverANormal = !Wlan.AutoconfigActivada(h, tarjetas[0]);
                foreach (Guid t in tarjetas) Wlan.FijarAutoconfig(h, t, volverANormal);

                if (volverANormal)
                    MessageBox.Show("Modo juego DESACTIVADO.\n\nWindows vuelve a manejar el Wi-Fi como siempre, " +
                        "con reconexión automática incluida.", Titulo, MessageBoxButtons.OK, MessageBoxIcon.Information);
                else
                    MessageBox.Show("Modo juego ACTIVADO.\n\nWindows deja de buscar redes Wi-Fi en segundo plano, " +
                        "que es lo que provoca los picos de ping.\n\nOjo: mientras esté activado, si el Wi-Fi se cae " +
                        "no se reconecta solo (tampoco al reiniciar).\nCuando termines de jugar, abre este programa " +
                        "otra vez para desactivarlo.", Titulo, MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Win32Exception ex)
            {
                string texto = ex.NativeErrorCode == 5
                    ? "Windows no dio permiso para cambiar el Wi-Fi. Ábrelo con clic derecho > Ejecutar como administrador."
                    : "No se pudo cambiar el Wi-Fi: " + ex.Message + " (código " + ex.NativeErrorCode + ")";
                MessageBox.Show(texto, Titulo, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                if (h != IntPtr.Zero) Wlan.Cerrar(h);
            }
        }
    }

    static class Native
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        public static extern bool SetDllDirectory(string ruta);
    }

    // API nativa de Wi-Fi de Windows (wlanapi.dll), sin pasar por cmd ni netsh
    static class Wlan
    {
        const int WLAN_INTF_OPCODE_AUTOCONF_ENABLED = 1;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct WLAN_INTERFACE_INFO
        {
            public Guid InterfaceGuid;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
            public string strInterfaceDescription;
            public int isState;
        }

        [DllImport("wlanapi.dll")]
        static extern uint WlanOpenHandle(uint version, IntPtr reservado, out uint versionNegociada, out IntPtr handle);

        [DllImport("wlanapi.dll")]
        static extern uint WlanCloseHandle(IntPtr handle, IntPtr reservado);

        [DllImport("wlanapi.dll")]
        static extern uint WlanEnumInterfaces(IntPtr handle, IntPtr reservado, out IntPtr lista);

        [DllImport("wlanapi.dll")]
        static extern uint WlanQueryInterface(IntPtr handle, ref Guid tarjeta, int opcode, IntPtr reservado, out uint tam, out IntPtr datos, IntPtr tipoValor);

        [DllImport("wlanapi.dll")]
        static extern uint WlanSetInterface(IntPtr handle, ref Guid tarjeta, int opcode, uint tam, IntPtr datos, IntPtr reservado);

        [DllImport("wlanapi.dll")]
        static extern void WlanFreeMemory(IntPtr memoria);

        static void Comprobar(uint resultado)
        {
            if (resultado != 0) throw new Win32Exception((int)resultado);
        }

        public static IntPtr Abrir()
        {
            uint version;
            IntPtr h;
            Comprobar(WlanOpenHandle(2, IntPtr.Zero, out version, out h));
            return h;
        }

        public static void Cerrar(IntPtr h) { WlanCloseHandle(h, IntPtr.Zero); }

        public static List<Guid> Tarjetas(IntPtr h)
        {
            IntPtr lista;
            Comprobar(WlanEnumInterfaces(h, IntPtr.Zero, out lista));
            try
            {
                // WLAN_INTERFACE_INFO_LIST: número de elementos, índice actual y luego el arreglo
                int n = Marshal.ReadInt32(lista);
                int tam = Marshal.SizeOf(typeof(WLAN_INTERFACE_INFO));
                List<Guid> res = new List<Guid>();
                for (int i = 0; i < n; i++)
                {
                    WLAN_INTERFACE_INFO info = (WLAN_INTERFACE_INFO)Marshal.PtrToStructure(new IntPtr(lista.ToInt64() + 8 + i * tam), typeof(WLAN_INTERFACE_INFO));
                    res.Add(info.InterfaceGuid);
                }
                return res;
            }
            finally { WlanFreeMemory(lista); }
        }

        public static bool AutoconfigActivada(IntPtr h, Guid tarjeta)
        {
            uint tam;
            IntPtr datos;
            Comprobar(WlanQueryInterface(h, ref tarjeta, WLAN_INTF_OPCODE_AUTOCONF_ENABLED, IntPtr.Zero, out tam, out datos, IntPtr.Zero));
            try { return Marshal.ReadInt32(datos) != 0; }
            finally { WlanFreeMemory(datos); }
        }

        public static void FijarAutoconfig(IntPtr h, Guid tarjeta, bool activar)
        {
            IntPtr datos = Marshal.AllocHGlobal(4);
            try
            {
                Marshal.WriteInt32(datos, activar ? 1 : 0);
                Comprobar(WlanSetInterface(h, ref tarjeta, WLAN_INTF_OPCODE_AUTOCONF_ENABLED, 4, datos, IntPtr.Zero));
            }
            finally { Marshal.FreeHGlobal(datos); }
        }
    }
}
