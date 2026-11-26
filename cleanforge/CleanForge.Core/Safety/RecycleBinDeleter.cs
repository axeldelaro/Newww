using System.Runtime.InteropServices;
using CleanForge.Core.Models;

namespace CleanForge.Core.Safety;

/// <summary>
/// Safe deletion executor: enforces SafetyGuard on EVERY single path, supports Dry-Run simulation,
/// sends files/folders to the real Windows Recycle Bin by default via SHFileOperationW (FOF_ALLOWUNDO),
/// and records a complete audit log.
/// </summary>
public sealed class RecycleBinDeleter
{
    private readonly AuditLogger _auditLogger;

    public RecycleBinDeleter(AuditLogger? auditLogger = null)
    {
        _auditLogger = auditLogger ?? new AuditLogger();
    }

    public CleanupExecutionReport ExecuteCleanup(
        IEnumerable<CleanCandidate> candidates,
        bool dryRun = false,
        bool useRecycleBin = true,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var items = candidates.Where(c => c.IsSelected).ToList();
        var report = new CleanupExecutionReport { WasDryRun = dryRun };

        for (int i = 0; i < items.Count; i++)
        {
            if (ct.IsCancellationRequested) break;
            var item = items[i];

            progress?.Report(new ScanProgress
            {
                Stage = dryRun ? "Simulation de nettoyage..." : "Nettoyage sécurisé en cours...",
                CurrentPath = item.Path,
                Percent = items.Count > 0 ? (double)i / items.Count * 100.0 : 100.0,
                FilesScanned = i,
                BytesFound = report.TotalBytesFreed
            });

            if (item.CleanContentsOnly && Directory.Exists(item.Path))
            {
                CleanDirectoryContentsSafe(item.Path, dryRun, useRecycleBin, report, ct);
            }
            else
            {
                var res = DeleteSinglePathSafe(item.Path, item.IsDirectory, dryRun, useRecycleBin);
                RecordResult(report, res);
            }
        }

        progress?.Report(new ScanProgress
        {
            Stage = dryRun ? "Simulation terminée" : "Nettoyage terminé",
            Percent = 100.0,
            FilesScanned = items.Count,
            BytesFound = report.TotalBytesFreed
        });

        _auditLogger.WriteCleanupReport(report);
        return report;
    }

    public CleanupExecutionReport DeletePaths(
        IEnumerable<(string Path, bool IsDirectory)> targets,
        bool dryRun = false,
        bool useRecycleBin = true,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var list = targets.ToList();
        var report = new CleanupExecutionReport { WasDryRun = dryRun };

        for (int i = 0; i < list.Count; i++)
        {
            if (ct.IsCancellationRequested) break;
            var (path, isDir) = list[i];

            progress?.Report(new ScanProgress
            {
                Stage = dryRun ? "Simulation..." : "Envoi vers la Corbeille...",
                CurrentPath = path,
                Percent = list.Count > 0 ? (double)i / list.Count * 100.0 : 100.0,
                FilesScanned = i,
                BytesFound = report.TotalBytesFreed
            });

            var res = DeleteSinglePathSafe(path, isDir, dryRun, useRecycleBin);
            RecordResult(report, res);
        }

        _auditLogger.WriteCleanupReport(report);
        return report;
    }

    private void CleanDirectoryContentsSafe(
        string rootDir,
        bool dryRun,
        bool useRecycleBin,
        CleanupExecutionReport report,
        CancellationToken ct)
    {
        var rootVerdict = SafetyGuard.EvaluatePathForDeletion(rootDir, isDirectory: true, allowCleanContentsOfKnownCacheRoot: true);
        if (!rootVerdict.IsAllowed)
        {
            RecordResult(report, new DeletionActionResult
            {
                Path = rootDir,
                Success = false,
                WasDryRun = dryRun,
                Message = $"BLOQUÉ PAR SAFETYGUARD : {rootVerdict.Reason}"
            }, isBlocked: true);
            return;
        }

        IEnumerable<string> entries;
        try
        {
            entries = Directory.EnumerateFileSystemEntries(rootDir);
        }
        catch (Exception ex)
        {
            RecordResult(report, new DeletionActionResult
            {
                Path = rootDir,
                Success = false,
                WasDryRun = dryRun,
                Message = $"Accès impossible : {ex.Message}"
            });
            return;
        }

        foreach (string entry in entries)
        {
            if (ct.IsCancellationRequested) break;
            bool isDir = Directory.Exists(entry);
            var res = DeleteSinglePathSafe(entry, isDir, dryRun, useRecycleBin);
            RecordResult(report, res);
        }
    }

    public DeletionActionResult DeleteSinglePathSafe(string path, bool isDirectory, bool dryRun, bool useRecycleBin)
    {
        // Hard engine-level safety gate — CANNOT be bypassed!
        var verdict = SafetyGuard.EvaluatePathForDeletion(path, isDirectory);
        if (!verdict.IsAllowed)
        {
            return new DeletionActionResult
            {
                Path = path,
                Success = false,
                WasDryRun = dryRun,
                Message = $"BLOQUÉ : {verdict.Reason}"
            };
        }

        bool exists = isDirectory ? Directory.Exists(path) : File.Exists(path);
        if (!exists)
        {
            return new DeletionActionResult
            {
                Path = path,
                Success = false,
                WasDryRun = dryRun,
                Message = "Élément déjà absent."
            };
        }

        long bytes = EstimatePathSize(path, isDirectory);

        if (dryRun)
        {
            return new DeletionActionResult
            {
                Path = path,
                Success = true,
                WasDryRun = true,
                SentToRecycleBin = useRecycleBin,
                BytesFreed = bytes,
                Message = useRecycleBin ? "Simulation : serait envoyé à la Corbeille Windows." : "Simulation : serait supprimé."
            };
        }

        try
        {
            if (useRecycleBin && OperatingSystem.IsWindows())
            {
                if (TrySendToWindowsRecycleBin(path, out int winErr))
                {
                    return new DeletionActionResult
                    {
                        Path = path,
                        Success = true,
                        WasDryRun = false,
                        SentToRecycleBin = true,
                        BytesFreed = bytes,
                        Message = "Envoyé vers la Corbeille Windows (récupérable)."
                    };
                }
                return new DeletionActionResult
                {
                    Path = path,
                    Success = false,
                    WasDryRun = false,
                    Message = $"Fichier en cours d'utilisation ou verrouillé (code Win32 {winErr})."
                };
            }

            // On non-Windows test hosts (or when user explicitly disables Recycle Bin for huge temp caches)
            if (isDirectory)
            {
                Directory.Delete(path, recursive: true);
            }
            else
            {
                var attrs = File.GetAttributes(path);
                if ((attrs & FileAttributes.ReadOnly) != 0)
                    File.SetAttributes(path, attrs & ~FileAttributes.ReadOnly);
                File.Delete(path);
            }

            return new DeletionActionResult
            {
                Path = path,
                Success = true,
                WasDryRun = false,
                SentToRecycleBin = false,
                BytesFreed = bytes,
                Message = "Supprimé proprement."
            };
        }
        catch (Exception ex)
        {
            return new DeletionActionResult
            {
                Path = path,
                Success = false,
                WasDryRun = false,
                Message = $"Ignoré (verrouillé par une appli ouverte) : {ex.Message}"
            };
        }
    }

    private static void RecordResult(CleanupExecutionReport report, DeletionActionResult res, bool isBlocked = false)
    {
        report.Details.Add(res);
        if (res.Success)
        {
            report.SucceededCount++;
            report.TotalBytesFreed += res.BytesFreed;
        }
        else if (isBlocked || res.Message.StartsWith("BLOQUÉ", StringComparison.Ordinal))
        {
            report.BlockedBySafetyCount++;
        }
        else
        {
            report.FailedOrLockedCount++;
        }
    }

    public static long EstimatePathSize(string path, bool isDirectory)
    {
        try
        {
            if (!isDirectory)
                return File.Exists(path) ? new FileInfo(path).Length : 0;

            long total = 0;
            var stack = new Stack<string>();
            stack.Push(path);
            while (stack.Count > 0)
            {
                string current = stack.Pop();
                if (!SafetyGuard.CanTraverseDirectory(current)) continue;
                try
                {
                    foreach (string f in Directory.EnumerateFiles(current))
                    {
                        try { total += new FileInfo(f).Length; } catch { }
                    }
                    foreach (string sub in Directory.EnumerateDirectories(current))
                    {
                        stack.Push(sub);
                    }
                }
                catch { }
            }
            return total;
        }
        catch
        {
            return 0;
        }
    }

    // Win32 SHFileOperationW for genuine Windows Recycle Bin recovery (FOF_ALLOWUNDO)
    private const uint FO_DELETE = 0x0003;
    private const ushort FOF_SILENT = 0x0004;
    private const ushort FOF_NOCONFIRMATION = 0x0010;
    private const ushort FOF_ALLOWUNDO = 0x0040;
    private const ushort FOF_NOERRORUI = 0x0400;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct SHFILEOPSTRUCT
    {
        public IntPtr hwnd;
        public uint wFunc;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string pFrom;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string? pTo;
        public ushort fFlags;
        [MarshalAs(UnmanagedType.Bool)]
        public bool fAnyOperationsAborted;
        public IntPtr hNameMappings;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string? lpszProgressTitle;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHFileOperationW(ref SHFILEOPSTRUCT lpFileOp);

    private static bool TrySendToWindowsRecycleBin(string path, out int errorCode)
    {
        var op = new SHFILEOPSTRUCT
        {
            wFunc = FO_DELETE,
            pFrom = path + "\0\0",
            fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT
        };
        errorCode = SHFileOperationW(ref op);
        return errorCode == 0 && !op.fAnyOperationsAborted;
    }
}
