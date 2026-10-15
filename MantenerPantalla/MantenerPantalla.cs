using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace MantenerPantalla
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            bool nueva;
            using (Mutex m = new Mutex(true, "MantenerPantalla_UnicaInstancia", out nueva))
            {
                if (!nueva)
                {
                    MessageBox.Show("Mantener Pantalla ya se está ejecutando (mira en la barra de iconos ocultos).",
                        "Mantener Pantalla", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new TrayContext());
            }
        }
    }

    static class Native
    {
        public const uint ES_CONTINUOUS = 0x80000000;
        public const uint ES_SYSTEM_REQUIRED = 0x00000001;
        public const uint ES_DISPLAY_REQUIRED = 0x00000002;
        public const uint MOUSEEVENTF_MOVE = 0x0001;
        public const int WM_HOTKEY = 0x0312;
        public const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_WIN = 0x8, MOD_NOREPEAT = 0x4000;

        [DllImport("kernel32.dll")]
        public static extern uint SetThreadExecutionState(uint esFlags);

        [DllImport("user32.dll")]
        public static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll")]
        public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        [StructLayout(LayoutKind.Sequential)]
        public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }

        [DllImport("user32.dll")]
        public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

        [DllImport("user32.dll")]
        public static extern bool DestroyIcon(IntPtr handle);

        // Milisegundos desde la última entrada de teclado/ratón del usuario
        public static uint IdleMs()
        {
            LASTINPUTINFO lii = new LASTINPUTINFO();
            lii.cbSize = (uint)Marshal.SizeOf(lii);
            if (!GetLastInputInfo(ref lii)) return 0;
            return (uint)Environment.TickCount - lii.dwTime;
        }
    }

    class Config
    {
        public int IntervaloSegundos = 60;
        public Keys Tecla = Keys.Control | Keys.Alt | Keys.P;
        public bool ActivoAlIniciar = true;
        public bool SoloSiInactivo = true;

        static string Ruta
        {
            get
            {
                string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MantenerPantalla");
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
                    if (k == "intervalo") c.IntervaloSegundos = Math.Max(5, int.Parse(v));
                    else if (k == "tecla") c.Tecla = (Keys)int.Parse(v);
                    else if (k == "activo") c.ActivoAlIniciar = v == "1";
                    else if (k == "soloinactivo") c.SoloSiInactivo = v == "1";
                }
            }
            catch { }
            return c;
        }

        public void Guardar()
        {
            try
            {
                File.WriteAllLines(Ruta, new string[] {
                    "intervalo=" + IntervaloSegundos,
                    "tecla=" + (int)Tecla,
                    "activo=" + (ActivoAlIniciar ? "1" : "0"),
                    "soloinactivo=" + (SoloSiInactivo ? "1" : "0")
                });
            }
            catch { }
        }

        public static string TeclaTexto(Keys k)
        {
            if ((k & Keys.KeyCode) == Keys.None) return "(ninguna)";
            string s = "";
            if ((k & Keys.Control) != 0) s += "Ctrl + ";
            if ((k & Keys.Alt) != 0) s += "Alt + ";
            if ((k & Keys.Shift) != 0) s += "Shift + ";
            return s + (k & Keys.KeyCode).ToString();
        }
    }

    // Ventana invisible que recibe el atajo de teclado global
    class HotkeyWindow : NativeWindow, IDisposable
    {
        const int ID = 0xB00B;
        public event EventHandler Pulsada;
        bool registrada;

        public HotkeyWindow() { CreateHandle(new CreateParams()); }

        public bool Registrar(Keys k)
        {
            Quitar();
            Keys codigo = k & Keys.KeyCode;
            if (codigo == Keys.None) return true;
            uint mods = Native.MOD_NOREPEAT;
            if ((k & Keys.Control) != 0) mods |= Native.MOD_CONTROL;
            if ((k & Keys.Alt) != 0) mods |= Native.MOD_ALT;
            if ((k & Keys.Shift) != 0) mods |= Native.MOD_SHIFT;
            registrada = Native.RegisterHotKey(Handle, ID, mods, (uint)codigo);
            return registrada;
        }

        public void Quitar()
        {
            if (registrada) Native.UnregisterHotKey(Handle, ID);
            registrada = false;
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == Native.WM_HOTKEY && (int)m.WParam == ID && Pulsada != null) Pulsada(this, EventArgs.Empty);
            base.WndProc(ref m);
        }

        public void Dispose() { Quitar(); DestroyHandle(); }
    }

    class TrayContext : ApplicationContext
    {
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string AppName = "MantenerPantalla";

        NotifyIcon tray;
        System.Windows.Forms.Timer timer;
        HotkeyWindow hotkey;
        Config cfg;
        bool activo;
        Icon iconOn, iconOff;
        ToolStripMenuItem itemToggle;
        ConfigForm formAbierto;

        public TrayContext()
        {
            cfg = Config.Cargar();
            iconOn = CrearIcono(Color.FromArgb(40, 180, 90));
            iconOff = CrearIcono(Color.FromArgb(140, 140, 140));

            ContextMenuStrip menu = new ContextMenuStrip();
            itemToggle = new ToolStripMenuItem("Activar", null, delegate { Alternar(); });
            menu.Items.Add(itemToggle);
            menu.Items.Add("Configuración...", null, delegate { AbrirConfig(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Salir", null, delegate { Salir(); });

            tray = new NotifyIcon();
            tray.ContextMenuStrip = menu;
            tray.Visible = true;
            tray.MouseClick += delegate(object s, MouseEventArgs e) { if (e.Button == MouseButtons.Left) Alternar(); };
            tray.DoubleClick += delegate { AbrirConfig(); };

            timer = new System.Windows.Forms.Timer();
            timer.Tick += delegate { Pulso(); };

            hotkey = new HotkeyWindow();
            hotkey.Pulsada += delegate { Alternar(); };
            if (!hotkey.Registrar(cfg.Tecla))
                tray.ShowBalloonTip(4000, "Mantener Pantalla",
                    "No se pudo registrar el atajo " + Config.TeclaTexto(cfg.Tecla) + " (lo usa otra aplicación).", ToolTipIcon.Warning);

            Establecer(cfg.ActivoAlIniciar, false);
        }

        void Establecer(bool on, bool avisar)
        {
            activo = on;
            timer.Stop();
            if (on)
            {
                Native.SetThreadExecutionState(Native.ES_CONTINUOUS | Native.ES_DISPLAY_REQUIRED | Native.ES_SYSTEM_REQUIRED);
                timer.Interval = cfg.IntervaloSegundos * 1000;
                timer.Start();
            }
            else
            {
                Native.SetThreadExecutionState(Native.ES_CONTINUOUS);
            }
            tray.Icon = on ? iconOn : iconOff;
            string texto = "Mantener Pantalla: " + (on ? "ACTIVO" : "desactivado") + "\nAtajo: " + Config.TeclaTexto(cfg.Tecla);
            tray.Text = texto.Length > 63 ? texto.Substring(0, 63) : texto;
            itemToggle.Text = on ? "Desactivar" : "Activar";
            if (avisar)
                tray.ShowBalloonTip(1500, "Mantener Pantalla", on ? "Activado: la pantalla no se apagará." : "Desactivado.", ToolTipIcon.Info);
        }

        void Alternar() { Establecer(!activo, true); }

        void Pulso()
        {
            // Si el usuario está usando el equipo no hace falta mover el ratón
            if (cfg.SoloSiInactivo && Native.IdleMs() < (uint)(cfg.IntervaloSegundos * 1000 - 500)) return;
            Native.mouse_event(Native.MOUSEEVENTF_MOVE, 1, 0, 0, UIntPtr.Zero);
            Thread.Sleep(30);
            Native.mouse_event(Native.MOUSEEVENTF_MOVE, -1, 0, 0, UIntPtr.Zero);
            Native.SetThreadExecutionState(Native.ES_CONTINUOUS | Native.ES_DISPLAY_REQUIRED | Native.ES_SYSTEM_REQUIRED);
        }

        void AbrirConfig()
        {
            if (formAbierto != null) { formAbierto.Activate(); return; }
            hotkey.Quitar(); // para poder capturar la tecla actual en el formulario
            formAbierto = new ConfigForm(cfg, InicioConWindows(), Icon.FromHandle(iconOn.Handle));
            if (formAbierto.ShowDialog() == DialogResult.OK)
            {
                cfg.IntervaloSegundos = formAbierto.Intervalo;
                cfg.Tecla = formAbierto.Tecla;
                cfg.ActivoAlIniciar = formAbierto.ActivoAlIniciar;
                cfg.SoloSiInactivo = formAbierto.SoloSiInactivo;
                cfg.Guardar();
                FijarInicioConWindows(formAbierto.IniciarConWindows);
            }
            formAbierto.Dispose();
            formAbierto = null;
            if (!hotkey.Registrar(cfg.Tecla))
                MessageBox.Show("No se pudo registrar el atajo " + Config.TeclaTexto(cfg.Tecla) +
                    ". Probablemente otra aplicación ya lo usa; elige otro.", "Mantener Pantalla",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            Establecer(activo, false); // aplica el nuevo intervalo
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
                if (on) k.SetValue(AppName, "\"" + Application.ExecutablePath + "\"");
                else if (k.GetValue(AppName) != null) k.DeleteValue(AppName);
            }
        }

        void Salir()
        {
            Native.SetThreadExecutionState(Native.ES_CONTINUOUS);
            timer.Stop();
            hotkey.Dispose();
            tray.Visible = false;
            tray.Dispose();
            ExitThread();
        }

        static Icon CrearIcono(Color color)
        {
            using (Bitmap bmp = new Bitmap(32, 32))
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                // Monitor
                using (SolidBrush b = new SolidBrush(color))
                {
                    g.FillRectangle(b, 2, 4, 28, 19);
                    g.FillRectangle(b, 13, 23, 6, 4);
                    g.FillRectangle(b, 8, 27, 16, 3);
                }
                using (SolidBrush w = new SolidBrush(Color.White))
                    g.FillEllipse(w, 11, 8, 10, 10);
                IntPtr h = bmp.GetHicon();
                Icon tmp = Icon.FromHandle(h);
                Icon copia = (Icon)tmp.Clone();
                Native.DestroyIcon(h);
                return copia;
            }
        }
    }

    class ConfigForm : Form
    {
        NumericUpDown numIntervalo;
        TextBox txtTecla;
        CheckBox chkActivo, chkInicio, chkInactivo;
        Keys tecla;

        public int Intervalo { get { return (int)numIntervalo.Value; } }
        public Keys Tecla { get { return tecla; } }
        public bool ActivoAlIniciar { get { return chkActivo.Checked; } }
        public bool IniciarConWindows { get { return chkInicio.Checked; } }
        public bool SoloSiInactivo { get { return chkInactivo.Checked; } }

        public ConfigForm(Config cfg, bool inicioWindows, Icon icono)
        {
            Text = "Mantener Pantalla - Configuración";
            Icon = icono;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(380, 250);
            Font = new Font("Segoe UI", 9f);
            TopMost = true;

            Label l1 = new Label();
            l1.Text = "Mover el ratón cada (segundos):";
            l1.SetBounds(16, 20, 200, 20);
            numIntervalo = new NumericUpDown();
            numIntervalo.Minimum = 5;
            numIntervalo.Maximum = 3600;
            numIntervalo.Value = Math.Min(3600, Math.Max(5, cfg.IntervaloSegundos));
            numIntervalo.SetBounds(230, 17, 130, 24);

            Label l2 = new Label();
            l2.Text = "Atajo para activar/desactivar:";
            l2.SetBounds(16, 58, 200, 20);
            tecla = cfg.Tecla;
            txtTecla = new TextBox();
            txtTecla.ReadOnly = true;
            txtTecla.BackColor = SystemColors.Window;
            txtTecla.Text = Config.TeclaTexto(tecla);
            txtTecla.SetBounds(230, 55, 130, 24);
            txtTecla.KeyDown += CapturarTecla;

            Label ayuda = new Label();
            ayuda.Text = "Haz clic en el cuadro y pulsa la combinación (Supr = ninguna).";
            ayuda.ForeColor = SystemColors.GrayText;
            ayuda.SetBounds(16, 84, 350, 18);

            chkInactivo = new CheckBox();
            chkInactivo.Text = "Mover solo si no estoy usando el ratón/teclado";
            chkInactivo.Checked = cfg.SoloSiInactivo;
            chkInactivo.SetBounds(16, 112, 350, 22);

            chkActivo = new CheckBox();
            chkActivo.Text = "Activar automáticamente al abrir la aplicación";
            chkActivo.Checked = cfg.ActivoAlIniciar;
            chkActivo.SetBounds(16, 138, 350, 22);

            chkInicio = new CheckBox();
            chkInicio.Text = "Iniciar con Windows";
            chkInicio.Checked = inicioWindows;
            chkInicio.SetBounds(16, 164, 350, 22);

            Button ok = new Button();
            ok.Text = "Guardar";
            ok.DialogResult = DialogResult.OK;
            ok.SetBounds(194, 206, 80, 28);
            Button cancel = new Button();
            cancel.Text = "Cancelar";
            cancel.DialogResult = DialogResult.Cancel;
            cancel.SetBounds(282, 206, 80, 28);
            AcceptButton = ok;
            CancelButton = cancel;

            Controls.AddRange(new Control[] { l1, numIntervalo, l2, txtTecla, ayuda, chkInactivo, chkActivo, chkInicio, ok, cancel });
        }

        void CapturarTecla(object sender, KeyEventArgs e)
        {
            e.SuppressKeyPress = true;
            e.Handled = true;
            Keys code = e.KeyCode;
            if (code == Keys.Delete || code == Keys.Back) { tecla = Keys.None; }
            else if (code == Keys.ControlKey || code == Keys.Menu || code == Keys.ShiftKey ||
                     code == Keys.LWin || code == Keys.RWin) { return; } // esperando la tecla principal
            else { tecla = code | e.Modifiers; }
            txtTecla.Text = Config.TeclaTexto(tecla);
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            // Permite capturar combinaciones con Alt / Enter / Esc cuando el cuadro tiene el foco
            if (txtTecla.Focused && keyData != Keys.Tab)
            {
                CapturarTecla(txtTecla, new KeyEventArgs(keyData));
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }
    }
}
