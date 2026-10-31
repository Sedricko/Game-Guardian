// PainelTinker.exe: um arquivo so com o painel e o agente dentro. Tira os scripts para %LOCALAPPDATA%\TinkerPainel e abre o
// painel escondido (sem janela preta); tudo o mais (ligar o bot, logs, opcoes) e pelo painel. Compilado pelo GitHub (painel.yml).
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

class PainelTinker
{
    static void Main()
    {
        string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TinkerPainel");
        Directory.CreateDirectory(dir);
        Assembly a = Assembly.GetExecutingAssembly();
        foreach (string n in new[] { "PAINEL-TINKER.ps1", "painel-tinker.html", "TINKER-AGENTE.ps1" })
        {
            string f = Path.Combine(dir, n);
            if (File.Exists(f) && n != "painel-tinker.html") continue;  // o agente e o painel se atualizam sozinhos pelo GitHub
            try
            {
                using (Stream s = a.GetManifestResourceStream(n))
                using (FileStream o = File.Create(f)) s.CopyTo(o);
            }
            catch (IOException) { }  // arquivo em uso (painel ja aberto): fica o que esta la
        }
        ProcessStartInfo p = new ProcessStartInfo("powershell.exe",
            "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + Path.Combine(dir, "PAINEL-TINKER.ps1") + "\"");
        p.UseShellExecute = false;
        p.CreateNoWindow = true;
        p.WorkingDirectory = dir;
        Process.Start(p);
    }
}
