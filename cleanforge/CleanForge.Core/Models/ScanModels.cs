using System.Collections.ObjectModel;

namespace CleanForge.Core.Models;

public static class ByteFormatter
{
    private static readonly string[] Units = ["o", "Ko", "Mo", "Go", "To"];

    public static string Format(long bytes)
    {
        if (bytes <= 0) return "0 o";
        double val = bytes;
        int unit = 0;
        while (val >= 1024.0 && unit < Units.Length - 1)
        {
            val /= 1024.0;
            unit++;
        }
        return unit == 0 ? $"{val:0} {Units[unit]}" : $"{val:0.##} {Units[unit]}";
    }
}

public enum SafetyTier
{
    SafeAuto,       // 100% safe regenerable cache/temp -> can be checked by default
    SafeReview,     // Safe to delete (e.g. node_modules, old installers, orphan folders) -> user confirms
    ManualOnly,     // User content (old study PDFs, large videos, duplicates) -> NEVER auto-selected unless explicit duplicate copy
    ProtectedLocked // Hard-locked by SafetyGuard (OS, drivers, passwords, antivirus)
}

public enum CleanupCategory
{
    WindowsSafeCache,
    BrowserCacheOnly,
    GpuShaderCache,
    AppMediaCache,
    DevBuildCache,
    StudyClutter,
    ForgottenInstaller,
    DuplicateCopy,
    GhostSoftwareEntry,
    OrphanAppFolder,
    BrokenShortcut,
    EmptyFolder,
    ExplorerManual
}

public sealed class ScanProgress
{
    public string Stage { get; init; } = "";
    public string CurrentPath { get; init; } = "";
    public double Percent { get; init; }
    public long FilesScanned { get; init; }
    public long BytesFound { get; init; }
}

public sealed class DriveSummary
{
    public string Name { get; init; } = "";
    public string VolumeLabel { get; init; } = "";
    public string DriveFormat { get; init; } = "";
    public long TotalBytes { get; init; }
    public long FreeBytes { get; init; }
    public long UsedBytes => Math.Max(0, TotalBytes - FreeBytes);
    public double UsedPercent => TotalBytes > 0 ? (double)UsedBytes / TotalBytes * 100.0 : 0.0;
    public string UsedFormatted => ByteFormatter.Format(UsedBytes);
    public string FreeFormatted => ByteFormatter.Format(FreeBytes);
    public string TotalFormatted => ByteFormatter.Format(TotalBytes);
    public string DisplayTitle => string.IsNullOrWhiteSpace(VolumeLabel) ? $"Disque ({Name.TrimEnd('\\')})" : $"{VolumeLabel} ({Name.TrimEnd('\\')})";
}

public sealed class FileNode
{
    public string Name { get; init; } = "";
    public string FullPath { get; init; } = "";
    public bool IsDirectory { get; init; }
    public long SizeBytes { get; set; }
    public int FileCount { get; set; }
    public DateTime LastWriteUtc { get; init; }
    public DateTime LastAccessUtc { get; init; }
    public string Extension { get; init; } = "";
    public double PercentOfParent { get; set; }
    public bool IsProtected { get; init; }
    public List<FileNode> Children { get; } = [];
    public string SizeFormatted => ByteFormatter.Format(SizeBytes);
    public string LastWriteFormatted => LastWriteUtc == DateTime.MinValue ? "-" : LastWriteUtc.ToLocalTime().ToString("dd/MM/yyyy");
}

public sealed class ExtensionStat
{
    public string Extension { get; init; } = "";
    public string CategoryLabel { get; init; } = "";
    public int FileCount { get; set; }
    public long TotalBytes { get; set; }
    public string SizeFormatted => ByteFormatter.Format(TotalBytes);
}

public sealed class FullDiskScanResult
{
    public List<DriveSummary> Drives { get; } = [];
    public List<FileNode> RootNodes { get; } = [];
    public List<FileNode> AllIndexedFiles { get; } = [];
    public List<FileNode> LargestFiles { get; } = [];
    public List<ExtensionStat> ExtensionStats { get; } = [];
    public long TotalScannedFiles { get; set; }
    public long TotalScannedFolders { get; set; }
    public long TotalScannedBytes { get; set; }
    public int SkippedProtectedOrDenied { get; set; }
    public TimeSpan Elapsed { get; set; }
}

public sealed class CleanCandidate
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string DisplayName { get; init; } = "";
    public string Path { get; init; } = "";
    public string GroupName { get; init; } = "";
    public string Description { get; init; } = "";
    public CleanupCategory Category { get; init; }
    public SafetyTier SafetyTier { get; init; }
    public bool IsDirectory { get; init; }
    public bool CleanContentsOnly { get; init; }
    public bool RequiresAdmin { get; init; }
    public long SizeBytes { get; set; }
    public int ItemCount { get; set; }
    public DateTime LastModifiedUtc { get; init; }
    public bool IsSelected { get; set; }
    public string SizeFormatted => ByteFormatter.Format(SizeBytes);
    public string SafetyBadge => SafetyTier switch
    {
        SafetyTier.SafeAuto => "100% SÛR",
        SafetyTier.SafeReview => "À VÉRIFIER",
        SafetyTier.ManualOnly => "MANUEL",
        _ => "PROTÉGÉ"
    };
    public string LastModifiedFormatted => LastModifiedUtc == DateTime.MinValue ? "-" : LastModifiedUtc.ToLocalTime().ToString("dd/MM/yyyy");
}

public sealed class DuplicateFileEntry
{
    public string FullPath { get; init; } = "";
    public string DirectoryName => System.IO.Path.GetDirectoryName(FullPath) ?? "";
    public string FileName => System.IO.Path.GetFileName(FullPath);
    public long SizeBytes { get; init; }
    public DateTime LastWriteUtc { get; init; }
    public bool IsOriginalKept { get; set; }
    public bool IsSelectedForDelete { get; set; }
    public string SizeFormatted => ByteFormatter.Format(SizeBytes);
    public string RoleLabel => IsOriginalKept ? "ORIGINAL CONSERVÉ" : "DOUBLON";
}

public sealed class DuplicateGroup
{
    public string Sha256 { get; init; } = "";
    public string ShortHash => Sha256.Length >= 12 ? Sha256[..12] : Sha256;
    public long FileSizeBytes { get; init; }
    public List<DuplicateFileEntry> Entries { get; } = [];
    public long ReclaimableBytes => FileSizeBytes * Math.Max(0, Entries.Count - 1);
    public string ReclaimableFormatted => ByteFormatter.Format(ReclaimableBytes);
    public string FileSizeFormatted => ByteFormatter.Format(FileSizeBytes);
    public string SummaryTitle => $"{Entries.FirstOrDefault()?.FileName ?? "Fichier"} ({Entries.Count} copies · {ReclaimableFormatted} récupérables)";
}

public enum StudyClutterKind
{
    RegenerableDevBuild,
    ForgottenInstallerOrArchive,
    OldStudyDocument
}

public sealed class StudyClutterItem
{
    public string Name { get; init; } = "";
    public string FullPath { get; init; } = "";
    public StudyClutterKind Kind { get; init; }
    public string Reason { get; init; } = "";
    public long SizeBytes { get; init; }
    public DateTime LastAccessUtc { get; init; }
    public bool IsDirectory { get; init; }
    public bool IsSelected { get; set; }
    public string SizeFormatted => ByteFormatter.Format(SizeBytes);
    public string LastAccessFormatted => LastAccessUtc == DateTime.MinValue ? "-" : LastAccessUtc.ToLocalTime().ToString("dd/MM/yyyy");
    public string KindBadge => Kind switch
    {
        StudyClutterKind.RegenerableDevBuild => "BUILD / DEV",
        StudyClutterKind.ForgottenInstallerOrArchive => "INSTALLEUR / ZIP",
        _ => "DOC ANCIEN (MANUEL)"
    };
}

public enum GhostItemKind
{
    DeadRegistryUninstaller,
    OrphanResidualFolder,
    InstalledApplication
}

public sealed class GhostSoftwareItem
{
    public string DisplayName { get; init; } = "";
    public string Publisher { get; init; } = "";
    public string Version { get; init; } = "";
    public string PathOrKey { get; init; } = "";
    public string RegistryHive { get; init; } = "";
    public string RegistrySubKey { get; init; } = "";
    public string UninstallCommand { get; init; } = "";
    public GhostItemKind Kind { get; init; }
    public long EstimatedBytes { get; init; }
    public string Reason { get; init; } = "";
    public bool IsProtectedVendor { get; init; }
    public bool IsSelected { get; set; }
    public string SizeFormatted => EstimatedBytes > 0 ? ByteFormatter.Format(EstimatedBytes) : "-";
    public string KindBadge => Kind switch
    {
        GhostItemKind.DeadRegistryUninstaller => "FANTÔME REGISTRE",
        GhostItemKind.OrphanResidualFolder => "DOSSIER ORPHELIN",
        _ => "INSTALLÉ"
    };
}

public sealed class BrokenShortcutItem
{
    public string ShortcutPath { get; init; } = "";
    public string ShortcutName => System.IO.Path.GetFileName(ShortcutPath);
    public string MissingTarget { get; init; } = "";
    public string LocationLabel { get; init; } = "";
    public bool IsSelected { get; set; } = true;
}

public sealed class EmptyFolderItem
{
    public string FullPath { get; init; } = "";
    public string FolderName => System.IO.Path.GetFileName(FullPath);
    public DateTime LastWriteUtc { get; init; }
    public bool IsSelected { get; set; } = true;
}

public sealed class DeletionActionResult
{
    public string Path { get; init; } = "";
    public bool Success { get; init; }
    public bool WasDryRun { get; init; }
    public bool SentToRecycleBin { get; init; }
    public long BytesFreed { get; init; }
    public string Message { get; init; } = "";
}

public sealed class CleanupExecutionReport
{
    public bool WasDryRun { get; init; }
    public int SucceededCount { get; set; }
    public int BlockedBySafetyCount { get; set; }
    public int FailedOrLockedCount { get; set; }
    public long TotalBytesFreed { get; set; }
    public string AuditLogPath { get; set; } = "";
    public List<DeletionActionResult> Details { get; } = [];
    public string TotalFreedFormatted => ByteFormatter.Format(TotalBytesFreed);
}
