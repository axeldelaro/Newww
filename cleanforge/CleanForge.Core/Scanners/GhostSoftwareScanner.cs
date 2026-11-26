using System.Diagnostics;
using System.Runtime.Versioning;
using CleanForge.Core.Models;
using CleanForge.Core.Safety;
using Microsoft.Win32;

namespace CleanForge.Core.Scanners;

/// <summary>
/// Audits Windows installed applications across all 4 Registry Uninstall hives (HKLM/HKCU x 64/32-bit),
/// identifies dead/ghost uninstaller entries, and detects orphan leftover folders in AppData/ProgramData/Program Files
/// with strict OS/Driver/Antivirus vendor whitelisting.
/// </summary>
public sealed class GhostSoftwareScanner
{
    private static readonly HashSet<string> StandardAppDataSystemFolders = new(StringComparer.OrdinalIgnoreCase)
    {
        "microsoft", "windows", "packages", "temp", "programs", "publishers", "connecteddevicesplatform",
        "d3dscache", "crashdumps", "comms", "diagnostics", "elevateddiagnostics", "fontcache",
        "history", "iconcache", "low", "peerdistrepub", "placeholders", "squirreltemp", "virtualstore",
        "identities", "adobe", "google", "mozilla", "braveSoftware", "opera software", "vivaldi",
        "nvidia", "nvidia corporation", "amd", "intel", "realtek", "cleanforge", "nuget", "pip", "npm-cache"
    };

    public (List<GhostSoftwareItem> GhostAndOrphans, List<GhostSoftwareItem> InstalledApps) ScanWindowsSoftware(
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var ghosts = new List<GhostSoftwareItem>();
        var installed = new List<GhostSoftwareItem>();

        if (!OperatingSystem.IsWindows())
            return (ghosts, installed);

        progress?.Report(new ScanProgress { Stage = "Audit des logiciels installés et entrées fantômes...", Percent = 15 });

        var installedNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var installedPublishers = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        ReadUninstallHive(RegistryHive.LocalMachine, RegistryView.Registry64, @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall", "HKLM64", ghosts, installed, installedNames, installedPublishers, ct);
        ReadUninstallHive(RegistryHive.LocalMachine, RegistryView.Registry32, @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall", "HKLM32", ghosts, installed, installedNames, installedPublishers, ct);
        ReadUninstallHive(RegistryHive.CurrentUser, RegistryView.Registry64, @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall", "HKCU", ghosts, installed, installedNames, installedPublishers, ct);

        progress?.Report(new ScanProgress { Stage = "Recherche de dossiers résiduels orphelins (AppData / ProgramData)...", Percent = 60 });

        var candidateDirs = new List<string>();
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string roaming = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string progData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        if (Directory.Exists(local)) candidateDirs.Add(local);
        if (Directory.Exists(roaming)) candidateDirs.Add(roaming);
        if (Directory.Exists(progData)) candidateDirs.Add(progData);

        var orphans = ScanOrphanDirectories(candidateDirs, installedNames, installedPublishers, minDormantDays: 30, ct);
        ghosts.AddRange(orphans);

        progress?.Report(new ScanProgress
        {
            Stage = $"Audit logiciels terminé ({ghosts.Count} résidus/fantômes, {installed.Count} installés)",
            Percent = 100
        });

        return (
            ghosts.OrderByDescending(g => g.EstimatedBytes).ThenBy(g => g.DisplayName).ToList(),
            installed.OrderByDescending(a => a.EstimatedBytes).ThenBy(a => a.DisplayName).ToList()
        );
    }

    /// <summary>
    /// Cross-platform testable orphan folder detector: flags folders in AppData/ProgramData that do not match
    /// any currently installed software or protected vendor and have been dormant > minDormantDays.
    /// </summary>
    public static List<GhostSoftwareItem> ScanOrphanDirectories(
        IEnumerable<string> parentRoots,
        ISet<string> installedAppTokens,
        ISet<string> installedPublishers,
        int minDormantDays = 30,
        CancellationToken ct = default)
    {
        var orphans = new List<GhostSoftwareItem>();
        DateTime dormantCutoff = DateTime.UtcNow.AddDays(-Math.Max(0, minDormantDays));

        foreach (string root in parentRoots)
        {
            if (ct.IsCancellationRequested || !Directory.Exists(root)) continue;
            try
            {
                foreach (string subDir in Directory.EnumerateDirectories(root))
                {
                    if (ct.IsCancellationRequested) break;
                    string name = Path.GetFileName(subDir);
                    if (string.IsNullOrWhiteSpace(name) || name.StartsWith('.')) continue;
                    if (StandardAppDataSystemFolders.Contains(name)) continue;
                    if (SafetyGuard.IsProtectedVendorOrApp(name)) continue;
                    if (!SafetyGuard.EvaluatePathForDeletion(subDir, isDirectory: true).IsAllowed) continue;

                    if (MatchesAnyInstalledToken(name, installedAppTokens) || MatchesAnyInstalledToken(name, installedPublishers))
                        continue;

                    var di = new DirectoryInfo(subDir);
                    DateTime lastMod = SafeDate(() => di.LastWriteTimeUtc);
                    if (minDormantDays > 0 && lastMod > dormantCutoff)
                        continue; // Recently active -> could be a portable app currently in use -> skip for 0 false positives!

                    long size = RecycleBinDeleter.EstimatePathSize(subDir, isDirectory: true);
                    if (size < 64 * 1024) continue; // Ignore tiny empty markers (covered by EmptyFolderScanner)

                    orphans.Add(new GhostSoftwareItem
                    {
                        DisplayName = name,
                        Publisher = "Inconnu / Ancien logiciel",
                        PathOrKey = subDir,
                        Kind = GhostItemKind.OrphanResidualFolder,
                        EstimatedBytes = size,
                        Reason = $"Dossier orphelin inactif dans {Path.GetFileName(root)} (aucun logiciel installé correspondant)",
                        IsProtectedVendor = false,
                        IsSelected = false // Always manual verification before trashing!
                    });
                }
            }
            catch { }
        }

        return orphans;
    }

    [SupportedOSPlatform("windows")]
    private static void ReadUninstallHive(
        RegistryHive hive,
        RegistryView view,
        string subKeyPath,
        string hiveLabel,
        List<GhostSoftwareItem> ghosts,
        List<GhostSoftwareItem> installed,
        HashSet<string> installedNames,
        HashSet<string> installedPublishers,
        CancellationToken ct)
    {
        try
        {
            using var baseKey = RegistryKey.OpenBaseKey(hive, view);
            using var uninstallKey = baseKey.OpenSubKey(subKeyPath, writable: false);
            if (uninstallKey == null) return;

            foreach (string childName in uninstallKey.GetSubKeyNames())
            {
                if (ct.IsCancellationRequested) break;
                try
                {
                    using var appKey = uninstallKey.OpenSubKey(childName, writable: false);
                    if (appKey == null) continue;

                    string? displayName = appKey.GetValue("DisplayName") as string;
                    if (string.IsNullOrWhiteSpace(displayName)) continue;

                    int sysComp = Convert.ToInt32(appKey.GetValue("SystemComponent") ?? 0);
                    if (sysComp == 1) continue;

                    string publisher = (appKey.GetValue("Publisher") as string)?.Trim() ?? "";
                    string version = (appKey.GetValue("DisplayVersion") as string)?.Trim() ?? "";
                    string installLoc = (appKey.GetValue("InstallLocation") as string)?.Trim().Trim('"') ?? "";
                    string uninstallCmd = (appKey.GetValue("UninstallString") as string)?.Trim() ?? "";
                    string displayIcon = (appKey.GetValue("DisplayIcon") as string)?.Trim() ?? "";
                    long estKb = 0;
                    try { estKb = Convert.ToInt64(appKey.GetValue("EstimatedSize") ?? 0); } catch { }

                    bool isProtected = SafetyGuard.IsProtectedVendorOrApp(displayName) || SafetyGuard.IsProtectedVendorOrApp(publisher);

                    RegisterTokens(displayName, installedNames);
                    RegisterTokens(publisher, installedPublishers);
                    if (!string.IsNullOrWhiteSpace(installLoc))
                    {
                        RegisterTokens(Path.GetFileName(installLoc.TrimEnd('\\')), installedNames);
                    }

                    // Determine if this registry entry is a ghost (both InstallLocation and Uninstall executable missing!)
                    bool hasInstallLoc = !string.IsNullOrWhiteSpace(installLoc);
                    bool installLocExists = hasInstallLoc && Directory.Exists(installLoc);
                    string? uninstExe = ExtractExecutableFromCommand(uninstallCmd);
                    bool uninstIsMsi = !string.IsNullOrWhiteSpace(uninstallCmd) && uninstallCmd.Contains("msiexec", StringComparison.OrdinalIgnoreCase);
                    bool uninstExists = uninstIsMsi || (!string.IsNullOrWhiteSpace(uninstExe) && File.Exists(uninstExe));
                    string? iconExe = ExtractExecutableFromCommand(displayIcon);
                    bool iconExists = !string.IsNullOrWhiteSpace(iconExe) && File.Exists(iconExe);

                    bool isGhost = !isProtected
                        && ((hasInstallLoc && !installLocExists && !uninstExists && !iconExists)
                            || (!uninstIsMsi && !string.IsNullOrWhiteSpace(uninstExe) && !File.Exists(uninstExe) && !installLocExists && !iconExists));

                    if (isGhost)
                    {
                        ghosts.Add(new GhostSoftwareItem
                        {
                            DisplayName = displayName.Trim(),
                            Publisher = publisher,
                            Version = version,
                            PathOrKey = $@"{hiveLabel}\{subKeyPath}\{childName}",
                            RegistryHive = hive == RegistryHive.LocalMachine ? "HKLM" : "HKCU",
                            RegistrySubKey = $@"{subKeyPath}\{childName}",
                            UninstallCommand = uninstallCmd,
                            Kind = GhostItemKind.DeadRegistryUninstaller,
                            EstimatedBytes = 0,
                            Reason = "Entrée fantôme : le dossier d'installation et le désinstalleur n'existent plus sur le disque.",
                            IsProtectedVendor = false,
                            IsSelected = false
                        });
                    }
                    else
                    {
                        installed.Add(new GhostSoftwareItem
                        {
                            DisplayName = displayName.Trim(),
                            Publisher = publisher,
                            Version = version,
                            PathOrKey = installLoc,
                            RegistryHive = hive == RegistryHive.LocalMachine ? "HKLM" : "HKCU",
                            RegistrySubKey = $@"{subKeyPath}\{childName}",
                            UninstallCommand = uninstallCmd,
                            Kind = GhostItemKind.InstalledApplication,
                            EstimatedBytes = estKb > 0 ? estKb * 1024L : 0,
                            Reason = isProtected ? "Composant système / pilote / éditeur protégé" : "Logiciel actif (désinstallation officielle disponible)",
                            IsProtectedVendor = isProtected,
                            IsSelected = false
                        });
                    }
                }
                catch { }
            }
        }
        catch { }
    }

    public static bool RemoveGhostRegistryEntry(GhostSoftwareItem item, bool dryRun, out string message)
    {
        if (item.Kind != GhostItemKind.DeadRegistryUninstaller || item.IsProtectedVendor)
        {
            message = "Entrée protégée ou active : suppression refusée.";
            return false;
        }
        if (dryRun)
        {
            message = $"Simulation : l'entrée fantôme '{item.DisplayName}' serait retirée du Registre.";
            return true;
        }
        if (!OperatingSystem.IsWindows())
        {
            message = "Opération disponible uniquement sous Windows.";
            return false;
        }

        var verdict = SafetyGuard.EvaluateRegistryPath(item.RegistryHive, item.RegistrySubKey);
        if (!verdict.IsAllowed)
        {
            message = $"BLOQUÉ : {verdict.Reason}";
            return false;
        }

        try
        {
            var hive = item.RegistryHive == "HKLM" ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;
            var view = item.PathOrKey.StartsWith("HKLM32", StringComparison.Ordinal) ? RegistryView.Registry32 : RegistryView.Registry64;
            using var baseKey = RegistryKey.OpenBaseKey(hive, view);
            baseKey.DeleteSubKeyTree(item.RegistrySubKey, throwOnMissingSubKey: false);
            message = $"Entrée fantôme '{item.DisplayName}' nettoyée du Registre.";
            return true;
        }
        catch (Exception ex)
        {
            message = $"Droits Administrateur requis ou clé verrouillée : {ex.Message}";
            return false;
        }
    }

    public static bool LaunchOfficialUninstaller(GhostSoftwareItem app, out string message)
    {
        if (string.IsNullOrWhiteSpace(app.UninstallCommand))
        {
            message = "Aucune commande de désinstallation officielle disponible.";
            return false;
        }
        try
        {
            if (!OperatingSystem.IsWindows())
            {
                message = "Lancement désinstalleur disponible uniquement sous Windows.";
                return false;
            }
            Process.Start(new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/c start \"\" {app.UninstallCommand}",
                UseShellExecute = true,
                CreateNoWindow = true
            });
            message = $"Désinstalleur officiel lancé pour {app.DisplayName}.";
            return true;
        }
        catch (Exception ex)
        {
            message = $"Impossible de lancer le désinstalleur : {ex.Message}";
            return false;
        }
    }

    public static string? ExtractExecutableFromCommand(string? cmd)
    {
        if (string.IsNullOrWhiteSpace(cmd)) return null;
        string s = cmd.Trim();
        if (s.StartsWith('"'))
        {
            int end = s.IndexOf('"', 1);
            if (end > 1) s = s[1..end];
        }
        else
        {
            int exeIdx = s.IndexOf(".exe", StringComparison.OrdinalIgnoreCase);
            if (exeIdx > 0) s = s[..(exeIdx + 4)];
            else
            {
                int comma = s.IndexOf(',');
                if (comma > 0) s = s[..comma];
            }
        }
        s = Environment.ExpandEnvironmentVariables(s).Trim();
        return s.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? s : null;
    }

    private static void RegisterTokens(string? raw, HashSet<string> set)
    {
        if (string.IsNullOrWhiteSpace(raw)) return;
        string cleaned = raw.Trim();
        if (cleaned.Length >= 3) set.Add(cleaned);
        foreach (string tok in cleaned.Split([' ', '-', '_', '.', '(', ')', ','], StringSplitOptions.RemoveEmptyEntries))
        {
            if (tok.Length >= 4) set.Add(tok);
        }
    }

    private static bool MatchesAnyInstalledToken(string folderName, ISet<string> tokens)
    {
        if (tokens.Contains(folderName)) return true;
        string lower = folderName.ToLowerInvariant();
        foreach (string tok in tokens)
        {
            if (tok.Length >= 4 && (lower.Contains(tok.ToLowerInvariant(), StringComparison.Ordinal)
                || tok.ToLowerInvariant().Contains(lower, StringComparison.Ordinal)))
            {
                return true;
            }
        }
        return false;
    }

    private static DateTime SafeDate(Func<DateTime> fn)
    {
        try { return fn(); } catch { return DateTime.MinValue; }
    }
}
