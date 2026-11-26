using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Threading;
using CleanForge.Core;

namespace CleanForge.App;

public partial class App : Application
{
    [DllImport("kernel32.dll")]
    private static extern bool AttachConsole(int dwProcessId);

    protected override void OnStartup(StartupEventArgs e)
    {
        // Headless CLI verification mode used in Windows CI & diagnostics: CleanForgePro.exe --self-test
        if (e.Args.Any(a => a.Equals("--self-test", StringComparison.OrdinalIgnoreCase)))
        {
            AttachConsole(-1);
            int code = SelfTestRunner.RunLiveSelfTest(Console.Out);
            Console.Out.Flush();
            Environment.Exit(code);
            return;
        }

        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;

        base.OnStartup(e);

        var mainWindow = new MainWindow();
        MainWindow = mainWindow;
        mainWindow.Show();
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        e.Handled = true;
        MessageBox.Show(
            $"Une erreur non critique a été interceptée sans aucun impact sur votre PC :\n\n{e.Exception.Message}",
            "CleanForge Pro — Protection Stabilité",
            MessageBoxButton.OK,
            MessageBoxImage.Warning);
    }

    private static void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception ex)
        {
            try
            {
                string logDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CleanForge", "logs");
                Directory.CreateDirectory(logDir);
                File.WriteAllText(Path.Combine(logDir, $"crash_{DateTime.Now:yyyyMMdd_HHmmss}.txt"), ex.ToString());
            }
            catch { }
        }
    }
}
