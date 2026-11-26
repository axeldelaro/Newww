namespace CleanForge.Core.Models;

public enum OptimizerProfileId
{
    GamingPerformance,
    SilenceAndBattery,
    StudioDevCreator,
    PrivacyZeroBloat
}

public enum StartupRiskLevel
{
    SafeToDisable,
    CautionUtility,
    ProtectedSystemLocked
}

public sealed class OptimizationTweak
{
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string Category { get; init; } = "";
    public string Description { get; init; } = "";
    public string SafetyGuarantee { get; init; } = "100% logiciel Windows · 0% risque matériel · Réversible 1-clic";
    public bool RequiresAdmin { get; init; }
    public bool IsHardwareSafe { get; init; } = true; // Invariant: ALWAYS true in CleanForge Pro!
    public List<OptimizerProfileId> Profiles { get; init; } = [];

    // Registry target (null for non-registry actions like Power Plan)
    public string? RegistryHive { get; init; } // "HKCU" or "HKLM"
    public string? RegistrySubKey { get; init; }
    public string? RegistryValueName { get; init; }
    public int? RecommendedDwordValue { get; init; }
    public int? WindowsDefaultDwordValue { get; init; }
    public string? RecommendedStringValue { get; init; }
    public string? WindowsDefaultStringValue { get; init; }
    public string? PowerPlanGuid { get; init; }

    // Live UI state
    public bool IsEnabledByUser { get; set; }
    public bool IsCurrentlyApplied { get; set; }
    public string CurrentStatusLabel => IsCurrentlyApplied ? "ACTIF" : "ORIGINE WINDOWS";
}

public sealed class OptimizerProfile
{
    public OptimizerProfileId Id { get; init; }
    public string Title { get; init; } = "";
    public string Subtitle { get; init; } = "";
    public string AccentHex { get; init; } = "#06B6D4";
    public List<string> TweakIds { get; init; } = [];
}

public sealed class StartupAppItem
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string Command { get; init; } = "";
    public string SourceLocation { get; init; } = ""; // "HKCU Run", "HKLM Run", "Dossier Démarrage"
    public string RegistryHive { get; init; } = "";
    public string RegistrySubKey { get; init; } = "";
    public string? StartupFilePath { get; init; }
    public bool IsEnabled { get; set; }
    public StartupRiskLevel RiskLevel { get; init; }
    public string RiskBadge => RiskLevel switch
    {
        StartupRiskLevel.SafeToDisable => "SÛR À DÉSACTIVER",
        StartupRiskLevel.CautionUtility => "UTILITAIRE",
        _ => "SYSTÈME VERROUILLÉ"
    };
}

public sealed class RegistryBackupEntry
{
    public string TweakId { get; init; } = "";
    public string Hive { get; init; } = "";
    public string SubKey { get; init; } = "";
    public string ValueName { get; init; } = "";
    public bool ExistedBefore { get; init; }
    public string ValueKind { get; init; } = "DWord";
    public int? PreviousDwordValue { get; init; }
    public string? PreviousStringValue { get; init; }
}

public sealed class OptimizerSnapshot
{
    public string SnapshotId { get; init; } = DateTime.UtcNow.ToString("yyyyMMdd_HHmmss");
    public DateTime CreatedUtc { get; init; } = DateTime.UtcNow;
    public string ProfileName { get; init; } = "Personnalisé";
    public string? PreviousPowerPlanGuid { get; init; }
    public List<RegistryBackupEntry> RegistryEntries { get; init; } = [];
    public string FilePath { get; set; } = "";
    public string DisplayLabel => $"{CreatedUtc.ToLocalTime():dd/MM/yyyy HH:mm} — {ProfileName} ({RegistryEntries.Count} réglages)";
}

public sealed class OptimizerApplyReport
{
    public bool Success { get; set; }
    public bool WasDryRun { get; init; }
    public int AppliedCount { get; set; }
    public int SkippedAdminCount { get; set; }
    public string SnapshotPath { get; set; } = "";
    public List<string> Messages { get; } = [];
}

public sealed class SystemHealthSummary
{
    public string OsDescription { get; init; } = "";
    public string MachineName { get; init; } = "";
    public int CpuLogicalCores { get; init; }
    public bool IsAdministrator { get; init; }
    public long TotalRamBytes { get; init; }
    public long AvailableRamBytes { get; init; }
    public long UsedRamBytes => Math.Max(0, TotalRamBytes - AvailableRamBytes);
    public double RamUsagePercent => TotalRamBytes > 0 ? (double)UsedRamBytes / TotalRamBytes * 100.0 : 0.0;
    public string RamFormatted => TotalRamBytes > 0
        ? $"{ByteFormatter.Format(UsedRamBytes)} / {ByteFormatter.Format(TotalRamBytes)} ({RamUsagePercent:0}%)"
        : "N/A";
    public List<DriveSummary> Drives { get; init; } = [];
}
