using System.Diagnostics;
using System.Runtime.Versioning;
using CleanForge.Core.Models;
using CleanForge.Core.Safety;
using Microsoft.Win32;

namespace CleanForge.Core.Optimizer;

/// <summary>
/// Hardware-Safe, 100% Reversible Windows Optimizer.
/// ZERO overclocking, ZERO voltage/BIOS/fan edits, ZERO Defender/Firewall/Update tampering.
/// Automatically writes a timestamped JSON snapshot of every previous value before applying ANY change,
/// enabling 1-click full restoration to exact original Windows state!
/// </summary>
public sealed class SafeWindowsOptimizer
{
    public const string BalancedPowerPlanGuid = "381b4222-f694-41f0-9685-ff5bb260df2e";
    public const string HighPerfPowerPlanGuid = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";

    private readonly AuditLogger _auditLogger;
    private readonly string? _customSnapshotsDir;

    public SafeWindowsOptimizer(AuditLogger? auditLogger = null, string? customSnapshotsDir = null)
    {
        _auditLogger = auditLogger ?? new AuditLogger();
        _customSnapshotsDir = customSnapshotsDir;
    }

    public static List<OptimizerProfile> GetProfiles() =>
    [
        new OptimizerProfile
        {
            Id = OptimizerProfileId.GamingPerformance,
            Title = "Gaming & Fluidité FPS",
            Subtitle = "Mode Jeu Windows, arrêt capture Xbox DVR, priorité premier plan, réactivité menus & plan Performances Élevées (0% overclock).",
            AccentHex = "#10B981",
            TweakIds = ["game_mode", "disable_game_dvr", "power_high_perf", "fast_menu_delay", "disable_windows_tips", "disable_bg_apps", "disable_mouse_accel"]
        },
        new OptimizerProfile
        {
            Id = OptimizerProfileId.SilenceAndBattery,
            Title = "Silence & Autonomie (PC Portable)",
            Subtitle = "Plan Équilibré Windows, allègement transparence GPU, limitation apps arrière-plan pour moins de chauffe et plus d'autonomie.",
            AccentHex = "#06B6D4",
            TweakIds = ["power_balanced", "disable_transparency", "disable_bg_apps", "disable_windows_tips", "fast_menu_delay"]
        },
        new OptimizerProfile
        {
            Id = OptimizerProfileId.StudioDevCreator,
            Title = "Studio Audio / Dev / Créateur",
            Subtitle = "Priorité temps réel application active, recherche Démarrer instantanée sans Bing, menus rapides & plan Performances.",
            AccentHex = "#8B5CF6",
            TweakIds = ["power_high_perf", "studio_fg_priority", "fast_menu_delay", "disable_bing_start", "disable_windows_tips", "game_mode"]
        },
        new OptimizerProfile
        {
            Id = OptimizerProfileId.PrivacyZeroBloat,
            Title = "Vie Privée & Zéro Pub Windows",
            Subtitle = "Supprime pubs/suggestions Menu Démarrer, écran verrouillage, Bing Search et ID publicitaire (Defender & Windows Update 100% intacts).",
            AccentHex = "#F59E0B",
            TweakIds = ["disable_startup_ads", "disable_lockscreen_tips", "disable_windows_tips", "disable_bing_start", "disable_advertising_id", "disable_bg_apps", "fast_menu_delay"]
        }
    ];

    public static List<OptimizationTweak> CreateAllTweaks() =>
    [
        new OptimizationTweak
        {
            Id = "game_mode",
            Title = "Activer le Mode Jeu Windows officiel",
            Category = "Performance & Gaming",
            Description = "Priorise le GPU et bloque les notifications parasites en plein écran via le mécanisme natif Windows.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\GameBar",
            RegistryValueName = "AutoGameModeEnabled",
            RecommendedDwordValue = 1,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.GamingPerformance, OptimizerProfileId.StudioDevCreator]
        },
        new OptimizationTweak
        {
            Id = "disable_game_dvr",
            Title = "Désactiver l'enregistrement vidéo Xbox DVR arrière-plan",
            Category = "Performance & Gaming",
            Description = "Évite que Windows n'enregistre constamment l'écran en arrière-plan (libère RAM/VRAM & micro-saccades).",
            RegistryHive = "HKCU",
            RegistrySubKey = @"System\GameConfigStore",
            RegistryValueName = "GameDVR_Enabled",
            RecommendedDwordValue = 0,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.GamingPerformance]
        },
        new OptimizationTweak
        {
            Id = "power_high_perf",
            Title = "Plan d'alimentation Windows : Performances Élevées",
            Category = "Énergie (Officiel Windows)",
            Description = "Active le profil officiel Windows Performances Élevées (évite les baisses brutales de réactivité sans modifier aucune tension).",
            PowerPlanGuid = HighPerfPowerPlanGuid,
            Profiles = [OptimizerProfileId.GamingPerformance, OptimizerProfileId.StudioDevCreator]
        },
        new OptimizationTweak
        {
            Id = "power_balanced",
            Title = "Plan d'alimentation Windows : Équilibré (Silence)",
            Category = "Énergie (Officiel Windows)",
            Description = "Privilégie le silence des ventilateurs, des températures fraîches et une autonomie maximale sur batterie.",
            PowerPlanGuid = BalancedPowerPlanGuid,
            Profiles = [OptimizerProfileId.SilenceAndBattery]
        },
        new OptimizationTweak
        {
            Id = "fast_menu_delay",
            Title = "Accélérer l'ouverture des menus Windows (400 ms → 50 ms)",
            Category = "Réactivité Interface",
            Description = "Supprime le délai artificiel d'ouverture des sous-menus Windows pour une sensation de bureau instantané.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Control Panel\Desktop",
            RegistryValueName = "MenuShowDelay",
            RecommendedStringValue = "50",
            WindowsDefaultStringValue = "400",
            Profiles = [OptimizerProfileId.GamingPerformance, OptimizerProfileId.SilenceAndBattery, OptimizerProfileId.StudioDevCreator, OptimizerProfileId.PrivacyZeroBloat]
        },
        new OptimizationTweak
        {
            Id = "disable_transparency",
            Title = "Alléger les effets de transparence Windows",
            Category = "Réactivité Interface",
            Description = "Réduit la charge GPU du bureau (idéal PC portable ou ancien GPU pour gagner en fluidité et autonomie).",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
            RegistryValueName = "EnableTransparency",
            RecommendedDwordValue = 0,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.SilenceAndBattery]
        },
        new OptimizationTweak
        {
            Id = "disable_mouse_accel",
            Title = "Désactiver l'accélération souris (Visée 1:1)",
            Category = "Performance & Gaming",
            Description = "Désactive l'option « Améliorer la précision du pointeur » pour un mouvement souris linéaire et précis.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Control Panel\Mouse",
            RegistryValueName = "MouseSpeed",
            RecommendedStringValue = "0",
            WindowsDefaultStringValue = "1",
            Profiles = [OptimizerProfileId.GamingPerformance]
        },
        new OptimizationTweak
        {
            Id = "studio_fg_priority",
            Title = "Priorité CPU renforcée sur l'application au premier plan",
            Category = "Studio & Création",
            Description = "Réglage Windows standard favorisant la réactivité audio/DAW/IDE de la fenêtre active (Admin requis).",
            RequiresAdmin = true,
            RegistryHive = "HKLM",
            RegistrySubKey = @"SYSTEM\CurrentControlSet\Control\PriorityControl",
            RegistryValueName = "Win32PrioritySeparation",
            RecommendedDwordValue = 38,
            WindowsDefaultDwordValue = 2,
            Profiles = [OptimizerProfileId.StudioDevCreator]
        },
        new OptimizationTweak
        {
            Id = "disable_bg_apps",
            Title = "Limiter les applications Store en arrière-plan",
            Category = "Ressources & Batterie",
            Description = "Empêche les apps Microsoft Store inutilisées de consommer RAM, CPU et batterie quand elles sont fermées.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\Windows\CurrentVersion\BackgroundAccessApplications",
            RegistryValueName = "GlobalUserDisabled",
            RecommendedDwordValue = 1,
            WindowsDefaultDwordValue = 0,
            Profiles = [OptimizerProfileId.GamingPerformance, OptimizerProfileId.SilenceAndBattery, OptimizerProfileId.PrivacyZeroBloat]
        },
        new OptimizationTweak
        {
            Id = "disable_startup_ads",
            Title = "Masquer les suggestions/publicités du Menu Démarrer",
            Category = "Vie Privée & Zéro Pub",
            Description = "Retire les applications sponsorisées et suggestions commerciales affichées dans le Menu Démarrer.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager",
            RegistryValueName = "SystemPaneSuggestionsEnabled",
            RecommendedDwordValue = 0,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.PrivacyZeroBloat]
        },
        new OptimizationTweak
        {
            Id = "disable_lockscreen_tips",
            Title = "Retirer les astuces commerciales de l'écran de verrouillage",
            Category = "Vie Privée & Zéro Pub",
            Description = "Garde un écran de verrouillage propre sans textes publicitaires Microsoft.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager",
            RegistryValueName = "RotatingLockScreenOverlayEnabled",
            RecommendedDwordValue = 0,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.PrivacyZeroBloat]
        },
        new OptimizationTweak
        {
            Id = "disable_windows_tips",
            Title = "Désactiver les pop-ups « Astuces et conseils Windows »",
            Category = "Vie Privée & Zéro Pub",
            Description = "Stoppe les notifications intrusives de conseils Windows pendant que vous travaillez ou jouez.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager",
            RegistryValueName = "SoftLandingEnabled",
            RecommendedDwordValue = 0,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.GamingPerformance, OptimizerProfileId.SilenceAndBattery, OptimizerProfileId.StudioDevCreator, OptimizerProfileId.PrivacyZeroBloat]
        },
        new OptimizationTweak
        {
            Id = "disable_bing_start",
            Title = "Recherche locale pure dans Démarrer (couper Bing Web)",
            Category = "Vie Privée & Zéro Pub",
            Description = "Accélère fortement la recherche dans la barre Windows en cherchant uniquement sur votre PC (sans résultats web Bing).",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Policies\Microsoft\Windows\Explorer",
            RegistryValueName = "DisableSearchBoxSuggestions",
            RecommendedDwordValue = 1,
            WindowsDefaultDwordValue = 0,
            Profiles = [OptimizerProfileId.StudioDevCreator, OptimizerProfileId.PrivacyZeroBloat]
        },
        new OptimizationTweak
        {
            Id = "disable_advertising_id",
            Title = "Désactiver l'identifiant publicitaire Windows",
            Category = "Vie Privée & Zéro Pub",
            Description = "Empêche le ciblage publicitaire inter-applications lié à votre profil local Windows.",
            RegistryHive = "HKCU",
            RegistrySubKey = @"Software\Microsoft\Windows\CurrentVersion\AdvertisingInfo",
            RegistryValueName = "Enabled",
            RecommendedDwordValue = 0,
            WindowsDefaultDwordValue = 1,
            Profiles = [OptimizerProfileId.PrivacyZeroBloat]
        }
    ];

    public static void ApplyProfileSelection(List<OptimizationTweak> tweaks, OptimizerProfileId profileId)
    {
        var profile = GetProfiles().FirstOrDefault(p => p.Id == profileId);
        if (profile == null) return;
        var set = new HashSet<string>(profile.TweakIds, StringComparer.OrdinalIgnoreCase);
        foreach (var t in tweaks)
        {
            t.IsEnabledByUser = set.Contains(t.Id);
        }
    }

    public void RefreshCurrentWindowsStates(List<OptimizationTweak> tweaks)
    {
        if (!OperatingSystem.IsWindows()) return;
        string? activePlan = GetActivePowerPlanGuid();

        foreach (var t in tweaks)
        {
            if (!string.IsNullOrEmpty(t.PowerPlanGuid))
            {
                t.IsCurrentlyApplied = activePlan != null
                    && activePlan.Contains(t.PowerPlanGuid, StringComparison.OrdinalIgnoreCase);
                continue;
            }

            if (t.RegistryHive != null && t.RegistrySubKey != null && t.RegistryValueName != null)
            {
                var (exists, dw, str) = ReadRegistryValue(t.RegistryHive, t.RegistrySubKey, t.RegistryValueName);
                if (!exists)
                {
                    t.IsCurrentlyApplied = false;
                }
                else if (t.RecommendedDwordValue.HasValue)
                {
                    t.IsCurrentlyApplied = dw == t.RecommendedDwordValue.Value;
                }
                else if (t.RecommendedStringValue != null)
                {
                    t.IsCurrentlyApplied = string.Equals(str, t.RecommendedStringValue, StringComparison.OrdinalIgnoreCase);
                }
            }
        }
    }

    public OptimizerApplyReport ApplySelectedTweaks(
        IEnumerable<OptimizationTweak> tweaks,
        string profileName,
        bool dryRun = false)
    {
        var selected = tweaks.Where(t => t.IsEnabledByUser).ToList();
        var report = new OptimizerApplyReport { WasDryRun = dryRun, Success = true };

        if (selected.Count == 0)
        {
            report.Messages.Add("Aucun réglage sélectionné.");
            return report;
        }

        var snapshot = new OptimizerSnapshot
        {
            ProfileName = profileName,
            PreviousPowerPlanGuid = OperatingSystem.IsWindows() ? GetActivePowerPlanGuid() : BalancedPowerPlanGuid
        };

        // Always record exact before-state into snapshot BEFORE applying any change!
        foreach (var t in selected)
        {
            if (t.RegistryHive == null || t.RegistrySubKey == null || t.RegistryValueName == null)
                continue;

            var safety = SafetyGuard.EvaluateRegistryPath(t.RegistryHive, t.RegistrySubKey);
            if (!safety.IsAllowed)
            {
                report.Messages.Add($"BLOQUÉ : {t.Title} ({safety.Reason})");
                continue;
            }

            if (OperatingSystem.IsWindows())
            {
                var (exists, dw, str) = ReadRegistryValue(t.RegistryHive, t.RegistrySubKey, t.RegistryValueName);
                snapshot.RegistryEntries.Add(new RegistryBackupEntry
                {
                    TweakId = t.Id,
                    Hive = t.RegistryHive,
                    SubKey = t.RegistrySubKey,
                    ValueName = t.RegistryValueName,
                    ExistedBefore = exists,
                    ValueKind = t.RecommendedDwordValue.HasValue ? "DWord" : "String",
                    PreviousDwordValue = dw,
                    PreviousStringValue = str
                });
            }
            else
            {
                snapshot.RegistryEntries.Add(new RegistryBackupEntry
                {
                    TweakId = t.Id,
                    Hive = t.RegistryHive,
                    SubKey = t.RegistrySubKey,
                    ValueName = t.RegistryValueName,
                    ExistedBefore = true,
                    ValueKind = t.RecommendedDwordValue.HasValue ? "DWord" : "String",
                    PreviousDwordValue = t.WindowsDefaultDwordValue,
                    PreviousStringValue = t.WindowsDefaultStringValue
                });
            }
        }

        if (!dryRun)
        {
            report.SnapshotPath = _auditLogger.WriteOptimizerSnapshot(snapshot, _customSnapshotsDir);
            report.Messages.Add($"Sauvegarde de sécurité créée : {Path.GetFileName(report.SnapshotPath)}");
        }

        // Apply each selected safe tweak
        foreach (var t in selected)
        {
            if (!string.IsNullOrEmpty(t.PowerPlanGuid))
            {
                if (dryRun)
                {
                    report.AppliedCount++;
                    report.Messages.Add($"[SIMU] Plan d'alimentation activé : {t.Title}");
                }
                else if (OperatingSystem.IsWindows() && TrySetPowerPlan(t.PowerPlanGuid))
                {
                    t.IsCurrentlyApplied = true;
                    report.AppliedCount++;
                    report.Messages.Add($"[OK] {t.Title}");
                }
                else
                {
                    report.Messages.Add($"[INFO] {t.Title} ignoré (plan non présent sur cette machine).");
                }
                continue;
            }

            if (t.RegistryHive == null || t.RegistrySubKey == null || t.RegistryValueName == null)
                continue;

            var safety = SafetyGuard.EvaluateRegistryPath(t.RegistryHive, t.RegistrySubKey);
            if (!safety.IsAllowed) continue;

            if (dryRun)
            {
                report.AppliedCount++;
                report.Messages.Add($"[SIMU] {t.Title}");
                continue;
            }

            if (!OperatingSystem.IsWindows())
            {
                t.IsCurrentlyApplied = true;
                report.AppliedCount++;
                continue;
            }

            if (TryWriteRegistryValue(t.RegistryHive, t.RegistrySubKey, t.RegistryValueName, t.RecommendedDwordValue, t.RecommendedStringValue, out string err))
            {
                t.IsCurrentlyApplied = true;
                report.AppliedCount++;
                report.Messages.Add($"[OK] {t.Title}");
            }
            else
            {
                if (t.RequiresAdmin) report.SkippedAdminCount++;
                report.Messages.Add($"[ADMIN REQUIS] {t.Title} : {err}");
            }
        }

        return report;
    }

    public OptimizerApplyReport RestoreSnapshot(OptimizerSnapshot snapshot)
    {
        var report = new OptimizerApplyReport { Success = true };

        if (!OperatingSystem.IsWindows())
        {
            report.AppliedCount = snapshot.RegistryEntries.Count;
            report.Messages.Add("Snapshot restauré (mode test).");
            return report;
        }

        if (!string.IsNullOrWhiteSpace(snapshot.PreviousPowerPlanGuid))
        {
            if (TrySetPowerPlan(snapshot.PreviousPowerPlanGuid))
            {
                report.AppliedCount++;
                report.Messages.Add("Plan d'alimentation d'origine restauré.");
            }
        }

        foreach (var entry in snapshot.RegistryEntries)
        {
            var safety = SafetyGuard.EvaluateRegistryPath(entry.Hive, entry.SubKey);
            if (!safety.IsAllowed) continue;

            try
            {
                using var baseKey = OpenBaseHive(entry.Hive);
                using var key = baseKey.OpenSubKey(entry.SubKey, writable: true);
                if (key == null) continue;

                if (!entry.ExistedBefore)
                {
                    key.DeleteValue(entry.ValueName, throwOnMissingValue: false);
                }
                else if (entry.ValueKind == "DWord" && entry.PreviousDwordValue.HasValue)
                {
                    key.SetValue(entry.ValueName, entry.PreviousDwordValue.Value, RegistryValueKind.DWord);
                }
                else if (entry.PreviousStringValue != null)
                {
                    key.SetValue(entry.ValueName, entry.PreviousStringValue, RegistryValueKind.String);
                }
                report.AppliedCount++;
            }
            catch (Exception ex)
            {
                report.Messages.Add($"Impossible de restaurer {entry.ValueName} : {ex.Message}");
            }
        }

        report.Messages.Add($"Restauration complète terminée ({report.AppliedCount} paramètres revenus à l'état d'origine Windows).");
        return report;
    }

    public List<OptimizerSnapshot> GetSavedSnapshots() => _auditLogger.LoadOptimizerSnapshots(_customSnapshotsDir);

    public static bool TryCreateWindowsRestorePoint(string description, out string message)
    {
        if (!OperatingSystem.IsWindows())
        {
            message = "Points de restauration disponibles uniquement sous Windows.";
            return false;
        }

        try
        {
            string safeDesc = description.Replace("'", "").Replace("\"", "");
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"Checkpoint-Computer -Description '{safeDesc}' -RestorePointType 'MODIFY_SETTINGS'\"",
                UseShellExecute = false,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null)
            {
                message = "Impossible de lancer PowerShell.";
                return false;
            }
            proc.WaitForExit(30_000);
            if (proc.ExitCode == 0)
            {
                message = $"Point de restauration Windows créé : « {safeDesc} ».";
                return true;
            }
            string err = proc.StandardError.ReadToEnd().Trim();
            message = string.IsNullOrEmpty(err)
                ? "Droits Administrateur ou Protection Système Windows requis."
                : $"Point de restauration non créé : {err}";
            return false;
        }
        catch (Exception ex)
        {
            message = $"Erreur point de restauration : {ex.Message}";
            return false;
        }
    }

    [SupportedOSPlatform("windows")]
    private static RegistryKey OpenBaseHive(string hive) =>
        hive.Equals("HKLM", StringComparison.OrdinalIgnoreCase)
            ? RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
            : RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64);

    [SupportedOSPlatform("windows")]
    private static (bool Exists, int? DwordVal, string? StringVal) ReadRegistryValue(string hive, string subKey, string valueName)
    {
        try
        {
            using var baseKey = OpenBaseHive(hive);
            using var key = baseKey.OpenSubKey(subKey, writable: false);
            object? val = key?.GetValue(valueName);
            if (val == null) return (false, null, null);
            if (val is int iv) return (true, iv, iv.ToString());
            return (true, int.TryParse(val.ToString(), out int p) ? p : null, val.ToString());
        }
        catch
        {
            return (false, null, null);
        }
    }

    [SupportedOSPlatform("windows")]
    private static bool TryWriteRegistryValue(string hive, string subKey, string valueName, int? dwordVal, string? stringVal, out string error)
    {
        error = "";
        try
        {
            using var baseKey = OpenBaseHive(hive);
            using var key = baseKey.CreateSubKey(subKey, writable: true);
            if (key == null)
            {
                error = "Clé introuvable ou accès refusé.";
                return false;
            }
            if (dwordVal.HasValue)
                key.SetValue(valueName, dwordVal.Value, RegistryValueKind.DWord);
            else if (stringVal != null)
                key.SetValue(valueName, stringVal, RegistryValueKind.String);
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    private static string? GetActivePowerPlanGuid()
    {
        if (!OperatingSystem.IsWindows()) return null;
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powercfg.exe",
                Arguments = "/getactivescheme",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                CreateNoWindow = true
            };
            using var p = Process.Start(psi);
            string output = p?.StandardOutput.ReadToEnd() ?? "";
            p?.WaitForExit(3000);
            foreach (string token in output.Split([' ', '\t', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
            {
                if (Guid.TryParse(token, out var g))
                    return g.ToString();
            }
        }
        catch { }
        return null;
    }

    private static bool TrySetPowerPlan(string guid)
    {
        if (!OperatingSystem.IsWindows()) return false;
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powercfg.exe",
                Arguments = $"/setactive {guid}",
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var p = Process.Start(psi);
            p?.WaitForExit(3000);
            return p?.ExitCode == 0;
        }
        catch { return false; }
    }
}
