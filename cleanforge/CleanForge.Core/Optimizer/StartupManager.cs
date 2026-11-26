using System.Runtime.Versioning;
using CleanForge.Core.Models;
using CleanForge.Core.Safety;
using Microsoft.Win32;

namespace CleanForge.Core.Optimizer;

/// <summary>
/// Safe Windows Startup Manager: inspects HKCU/HKLM Run keys & Startup folders, classifies risk level,
/// locks critical Security/Antivirus/Hardware Driver entries from accidental disabling,
/// and supports clean reversible enable/disable (stores disabled entries under CleanForge\DisabledStartup).
/// </summary>
public sealed class StartupManager
{
    private const string RunSubKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string DisabledStoreSubKey = @"Software\CleanForge\DisabledStartup";

    public List<StartupAppItem> ListStartupApps()
    {
        var items = new List<StartupAppItem>();
        if (!OperatingSystem.IsWindows()) return items;

        ReadRunKey(RegistryHive.CurrentUser, "HKCU", "HKCU Run (Utilisateur)", items);
        ReadRunKey(RegistryHive.LocalMachine, "HKLM", "HKLM Run (Système)", items);
        ReadDisabledBackupStore(items);
        ReadStartupFolder(Environment.GetFolderPath(Environment.SpecialFolder.Startup), "Dossier Démarrage", items);

        return items.OrderBy(i => i.RiskLevel).ThenBy(i => i.Name, StringComparer.OrdinalIgnoreCase).ToList();
    }

    public static StartupRiskLevel ClassifyStartupRisk(string name, string command)
    {
        string combined = $"{name} {command}".ToLowerInvariant();

        // Critical OS, Security, Audio, GPU & Input Drivers -> LOCKED!
        string[] lockedTokens =
        [
            "securityhealth", "windows defender", "msascui", "windefend", "realtek", "rthdvcpl",
            "igfx", "nvbackend", "nvidia", "radeon", "amddvr", "synaptics", "etdctrl", "elan",
            "kaspersky", "bitdefender", "avast", "avg", "eset", "malwarebytes", "norton", "sophos",
            "ctfmon", "explorer.exe"
        ];
        foreach (string t in lockedTokens)
        {
            if (combined.Contains(t, StringComparison.Ordinal))
                return StartupRiskLevel.ProtectedSystemLocked;
        }

        // Known safe-to-disable launchers, chat & updater apps
        string[] safeTokens =
        [
            "discord", "spotify", "steam", "epicgames", "battle.net", "riot", "ubisoft", "origin", "ea desktop",
            "skype", "teams", "zoom", "slack", "telegram", "whatsapp", "utorrent", "qbittorrent",
            "adobe", "ccxprocess", "dropbox", "onedrive", "edgeupdate", "chrome", "opera", "brave",
            "itunes", "overwolf", "medal", "twitch", "wallpaper", "canva", "notion"
        ];
        foreach (string t in safeTokens)
        {
            if (combined.Contains(t, StringComparison.Ordinal))
                return StartupRiskLevel.SafeToDisable;
        }

        return StartupRiskLevel.CautionUtility;
    }

    public bool SetStartupEnabled(StartupAppItem item, bool enable, out string message)
    {
        if (item.RiskLevel == StartupRiskLevel.ProtectedSystemLocked)
        {
            message = $"« {item.Name} » est un composant sécurité/pilote verrouillé par CleanForge.";
            return false;
        }
        if (!OperatingSystem.IsWindows())
        {
            item.IsEnabled = enable;
            message = enable ? "Activé (simulation)." : "Désactivé (simulation).";
            return true;
        }

        try
        {
            if (!string.IsNullOrEmpty(item.StartupFilePath))
            {
                string current = item.StartupFilePath;
                if (!enable && File.Exists(current))
                {
                    string disabledPath = current + ".cleanforge_disabled";
                    File.Move(current, disabledPath, overwrite: true);
                    item.IsEnabled = false;
                    message = $"« {item.Name} » désactivé proprement (réversible).";
                    return true;
                }
                if (enable)
                {
                    string disabledPath = current.EndsWith(".cleanforge_disabled", StringComparison.OrdinalIgnoreCase)
                        ? current
                        : current + ".cleanforge_disabled";
                    if (File.Exists(disabledPath))
                    {
                        string restored = disabledPath[..^".cleanforge_disabled".Length];
                        File.Move(disabledPath, restored, overwrite: true);
                        item.IsEnabled = true;
                        message = $"« {item.Name} » réactivé au démarrage.";
                        return true;
                    }
                }
                message = "Fichier de démarrage introuvable.";
                return false;
            }

            // Registry Run toggle (reversible backup in HKCU\Software\CleanForge\DisabledStartup)
            var hive = item.RegistryHive == "HKLM" ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;
            using var baseKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64);
            using var runKey = baseKey.OpenSubKey(RunSubKey, writable: true);
            using var hkcu = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64);
            using var disabledStore = hkcu.CreateSubKey(DisabledStoreSubKey, writable: true);

            if (runKey == null || disabledStore == null)
            {
                message = "Droits Administrateur requis pour modifier ce démarrage système.";
                return false;
            }

            string storeKeyName = $"{item.RegistryHive}|{item.Name}";
            if (!enable)
            {
                disabledStore.SetValue(storeKeyName, item.Command, RegistryValueKind.String);
                runKey.DeleteValue(item.Name, throwOnMissingValue: false);
                item.IsEnabled = false;
                message = $"« {item.Name} » retiré du démarrage (sauvegardé, réactivable en 1 clic).";
            }
            else
            {
                runKey.SetValue(item.Name, item.Command, RegistryValueKind.String);
                disabledStore.DeleteValue(storeKeyName, throwOnMissingValue: false);
                item.IsEnabled = true;
                message = $"« {item.Name} » réactivé au démarrage.";
            }
            return true;
        }
        catch (Exception ex)
        {
            message = $"Droits insuffisants ou clé verrouillée : {ex.Message}";
            return false;
        }
    }

    [SupportedOSPlatform("windows")]
    private static void ReadRunKey(RegistryHive hive, string hiveName, string label, List<StartupAppItem> list)
    {
        try
        {
            using var baseKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64);
            using var key = baseKey.OpenSubKey(RunSubKey, writable: false);
            if (key == null) return;
            foreach (string valName in key.GetValueNames())
            {
                string cmd = key.GetValue(valName)?.ToString() ?? "";
                if (string.IsNullOrWhiteSpace(cmd)) continue;
                list.Add(new StartupAppItem
                {
                    Id = $"{hiveName}|{valName}",
                    Name = valName,
                    Command = cmd,
                    SourceLocation = label,
                    RegistryHive = hiveName,
                    RegistrySubKey = RunSubKey,
                    IsEnabled = true,
                    RiskLevel = ClassifyStartupRisk(valName, cmd)
                });
            }
        }
        catch { }
    }

    [SupportedOSPlatform("windows")]
    private static void ReadDisabledBackupStore(List<StartupAppItem> list)
    {
        try
        {
            using var hkcu = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64);
            using var store = hkcu.OpenSubKey(DisabledStoreSubKey, writable: false);
            if (store == null) return;
            foreach (string rawName in store.GetValueNames())
            {
                string cmd = store.GetValue(rawName)?.ToString() ?? "";
                string[] parts = rawName.Split('|', 2);
                string hiveName = parts.Length == 2 ? parts[0] : "HKCU";
                string appName = parts.Length == 2 ? parts[1] : rawName;
                if (list.Any(x => x.Name.Equals(appName, StringComparison.OrdinalIgnoreCase) && x.RegistryHive == hiveName))
                    continue;

                list.Add(new StartupAppItem
                {
                    Id = rawName,
                    Name = appName,
                    Command = cmd,
                    SourceLocation = $"{hiveName} Run (Désactivé par CleanForge)",
                    RegistryHive = hiveName,
                    RegistrySubKey = RunSubKey,
                    IsEnabled = false,
                    RiskLevel = ClassifyStartupRisk(appName, cmd)
                });
            }
        }
        catch { }
    }

    private static void ReadStartupFolder(string folder, string label, List<StartupAppItem> list)
    {
        if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder)) return;
        try
        {
            foreach (string file in Directory.EnumerateFiles(folder))
            {
                string name = Path.GetFileName(file);
                if (name.Equals("desktop.ini", StringComparison.OrdinalIgnoreCase)) continue;
                bool disabled = name.EndsWith(".cleanforge_disabled", StringComparison.OrdinalIgnoreCase);
                string cleanName = disabled ? name[..^".cleanforge_disabled".Length] : name;
                list.Add(new StartupAppItem
                {
                    Id = file,
                    Name = cleanName,
                    Command = file,
                    SourceLocation = label,
                    StartupFilePath = file,
                    IsEnabled = !disabled,
                    RiskLevel = ClassifyStartupRisk(cleanName, file)
                });
            }
        }
        catch { }
    }
}
